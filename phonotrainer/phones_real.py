"""Phones ACTUALLY pronounced: wav2vec2-espeak CTC → phonetic tokens with timings.

The same engine also exposes the emissions (log-probs) so that align_canonical.py can
force-align the canonical sequence with the SAME model: real and canonical then live
in the same alphabet (espeak/IPA) and share a single acoustic pass.

This is the `espeak` engine. The default is `timit61` (phones_timit.py), trained on
narrow human transcriptions; `build_engine` picks between them, and both expose the
same interface: log_probs, greedy_phones, frame_duration, blank_id, and for the
canonical word_ids / ids_label / spans_to_phones.
"""

from __future__ import annotations

import numpy as np

from .ipa_maps import (DIPHTHONGS, FLAP, GLOTTAL, PLACE_ASSIMILATION, SYLLABIC,
                       UNRELEASED, is_full_vowel, is_schwa_like, is_vowel,
                       normalize_espeak)

MODEL_ID = "facebook/wav2vec2-lv-60-espeak-cv-ft"
SAMPLE_RATE = 16000

# IMPROVEMENT 1: phonetic attraction toward the canonical form.
# Calibrated threshold: the acoustic confusions (b→v 0.42, n→l 0.67, l→d 1.17,
# h→f 1.25) fall below it; V↔C (1.5) stays out. j→t scores 1.5 (the C↔C ceiling) yet
# it is a typical confusion for this model ("you"→[tuː]): it goes into an explicit
# table.
DEFAULT_ATTRACTION_MAX_COST = 1.3
EXTRA_ATTRACT = {frozenset(("j", "t")), frozenset(("j", "d"))}


def attract_to_canonical(realized: list[dict], canonical: list[dict],
                         max_cost: float = DEFAULT_ATTRACTION_MAX_COST) -> list[dict]:
    """Replace real phones with their canonical counterpart whenever the confusion is
    acoustic and has no teaching value. Never inserts or deletes; never touches native
    variation (NATIVE_SHIFTS, reductions, monophthongizations, flaps/glottals).
    Marks "attracted": True and preserves raw_phone.

    The counterpart comes from the NW alignment, not from maximum overlap: an ELIDED
    canonical phone stays a `del` and cannot swallow the neighboring real phone (a
    bug caught in validation: "don't"→[doʊn] was turning into [doʊt]). Temporal
    overlap is kept as an additional condition.
    """
    from .diff import align_word, phone_cost

    if not realized or not canonical:
        return [dict(p) for p in realized]

    attract: dict[int, str] = {}  # id(real phone) → canonical phone
    for op in align_word(realized, canonical, cost_fn=_attraction_cost):
        if op["op"] != "sub":
            continue
        c, r = op["canonical"], op["real"]
        cp, rp = c["phone"], r["phone"]
        if _protected(cp, rp):
            continue
        if c is canonical[-1] and any(cp == alv and rp in found for (alv, _), found
                                      in PLACE_ASSIMILATION.items()):
            continue  # a final alveolar taking the next onset's place: tem bucks

        pair = frozenset((cp, rp))
        if pair not in EXTRA_ATTRACT and phone_cost(cp, rp) > max_cost:
            continue
        overlap = min(r["end"], c["end"]) - max(r["start"], c["start"])
        mid_dist = abs((r["start"] + r["end"]) - (c["start"] + c["end"])) / 2
        if overlap <= 0 and mid_dist > 0.15:
            continue  # paired by sequence but temporally unrelated
        attract[id(r)] = cp

    out = []
    for p in realized:
        q = dict(p)
        if id(p) in attract:
            q.setdefault("raw_phone", q["phone"])
            q["phone"] = attract[id(p)]
            q["attracted"] = True
        out.append(q)
    return out


def _attraction_cost(canon: str, real: str) -> float:
    """Cost function for the attraction NW: the EXTRA_ATTRACT confusions have to pair
    up as substitutions (their real cost of 1.5 would make del+ins preferable)."""
    from .diff import phone_cost

    if frozenset((canon, real)) in EXTRA_ATTRACT:
        return 0.4
    return phone_cost(canon, real)


def _protected(canon: str, real: str) -> bool:
    """Changes that are a phenomenon (or a candidate to be one): attraction leaves
    them alone."""
    from .diff import is_native_shift

    if is_native_shift(canon, real):
        return True
    if real in FLAP or real in GLOTTAL:
        return True
    # the narrow detail only the TIMIT engine sees: a closure that never released
    # (t→t̚ costs 0.05 and would be "fixed" back to t) and a syllabic consonant (n→n̩,
    # which would then read as a lost syllable)
    if real in UNRELEASED or real in SYLLABIC:
        return True
    # vowel reduction (same criterion as phenomena._word_rules)
    if is_full_vowel(canon) and (is_schwa_like(real) or real == "ɪ"):
        return True
    # monophthongization candidate: canonical diphthong + real simple vowel
    if canon in DIPHTHONGS and is_vowel(real) and real not in DIPHTHONGS:
        return True
    return False


class Wav2Vec2PhoneEngine:
    """wav2vec2 fine-tuned on espeak G2P labels (the `espeak` engine)."""

    name = "espeak"
    model_id = MODEL_ID
    narrow = False        # no unreleased stops, glottal stops almost never
    form_scoring = True   # variants.py rescoring of weak forms applies
    attraction_default = True  # multilingual leakage and acoustic noise to clean up
    alignment = "torchaudio forced_align over wav2vec2-espeak emissions"

    def __init__(self, model_id: str = MODEL_ID, device: str = "cpu"):
        import torch
        from transformers import AutoModelForCTC, AutoProcessor

        self.torch = torch
        self.model_id = model_id
        self.processor = AutoProcessor.from_pretrained(model_id)
        self.model = AutoModelForCTC.from_pretrained(model_id).to(device).eval()
        self.tokenizer = self.processor.tokenizer
        self.blank_id = self.tokenizer.pad_token_id
        self.special_ids = set(self.tokenizer.all_special_ids)
        self.device = device

    def log_probs(self, audio: np.ndarray):
        """CTC log-softmax emissions [T, C] for a mono 16 kHz chunk."""
        torch = self.torch
        with torch.inference_mode():
            inputs = self.processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt")
            logits = self.model(inputs.input_values.to(self.device)).logits[0]
            return torch.log_softmax(logits, dim=-1).cpu()

    @staticmethod
    def frame_duration(n_samples: int, n_frames: int) -> float:
        return n_samples / n_frames / SAMPLE_RATE

    def greedy_phones(self, audio: np.ndarray, t_offset: float = 0.0,
                      log_probs=None) -> list[dict]:
        """Greedy CTC decoding, collapsing repeats and keeping frame spans.

        Returns [{'phone', 'start', 'end', 'score'}, …] in absolute seconds.
        """
        lp = self.log_probs(audio) if log_probs is None else log_probs
        ids = lp.argmax(dim=-1).tolist()
        frame_dur = self.frame_duration(len(audio), len(ids))

        spans: list[list] = []  # [token_id, first_frame, last_frame, score_sum, n]
        prev_id = None
        for i, tid in enumerate(ids):
            if tid == self.blank_id:
                prev_id = None
                continue
            score = float(lp[i, tid])
            if tid == prev_id:
                spans[-1][2] = i + 1
                spans[-1][3] += score
                spans[-1][4] += 1
            else:
                spans.append([tid, i, i + 1, score, 1])
                prev_id = tid

        phones = []
        for tid, f0, f1, score, n in spans:
            if tid in self.special_ids:
                continue
            raw = self.tokenizer.convert_ids_to_tokens(tid)
            norm = normalize_espeak(raw)
            if norm is None:
                continue  # token with no English equivalent (the warning was logged)
            phones.append({
                "phone": norm,
                "raw_phone": raw,
                "start": round(t_offset + f0 * frame_dur, 3),
                "end": round(t_offset + f1 * frame_dur, 3),
                "score": round(float(np.exp(score / n)), 3),
            })
        return phones

    # --- canonical (align_canonical.py) --------------------------------------
    def word_ids(self, word: str) -> tuple[int, ...]:
        """espeak phonemization of `word`, as this model's token ids."""
        from .align_canonical import canonical_phone_ids

        return tuple(canonical_phone_ids(self.tokenizer, word))

    def ids_label(self, ids) -> str:
        return " ".join(self.tokenizer.convert_ids_to_tokens(list(ids)))

    def spans_to_phones(self, spans: list[tuple[int, int, int, float]],
                        t_offset: float, frame_dur: float) -> list[dict]:
        """Forced (token_id, start_frame, end_frame, score) → canonical phones."""
        phones = []
        for tid, f0, f1, score in spans:
            raw = self.tokenizer.convert_ids_to_tokens(tid)
            phones.append({
                "phone": normalize_espeak(raw) or raw,
                "raw_phone": raw,
                "start": round(t_offset + f0 * frame_dur, 3),
                "end": round(t_offset + f1 * frame_dur, 3),
                "score": round(float(score), 3),
            })
        return phones


DEFAULT_ENGINE = "timit61"
# "wav2vec2" is what the espeak engine was called before there were two of them:
# old jobs and scripts still pass it.
ENGINE_ALIASES = {"wav2vec2": "espeak"}


def engine_name(name: str) -> str:
    """Canonical engine name (resolves aliases; unknown names pass through)."""
    return ENGINE_ALIASES.get(name, name)


def engine_of(meta: dict) -> str:
    """The engine an analysis was made with, from its meta; analyses older than
    the timit61 engine carry no `phone_engine` and were all made with espeak."""
    if meta.get("phone_engine"):
        return engine_name(meta["phone_engine"])
    phones = str((meta.get("models") or {}).get("phones", ""))
    return "timit61" if "timit" in phones else "espeak"


def build_engine(name: str = DEFAULT_ENGINE, device: str = "cpu"):
    """Factory for phone engines (the --phone-engine flag)."""
    name = engine_name(name)
    if name == "timit61":
        from .phones_timit import TimitPhoneEngine

        return TimitPhoneEngine(device=device)
    if name == "espeak":
        return Wav2Vec2PhoneEngine(device=device)
    raise ValueError(f"Unknown phone engine: {name}")
