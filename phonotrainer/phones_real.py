"""Phones ACTUALLY pronounced: wav2vec2-espeak CTC → phonetic tokens with timings.

The same engine also exposes the emissions (log-probs) so that align_canonical.py can
force-align the canonical sequence with the SAME model: real and canonical then live
in the same alphabet (espeak/IPA) and share a single acoustic pass.
"""

from __future__ import annotations

import numpy as np

from .ipa_maps import (DIPHTHONGS, FLAP, GLOTTAL, is_full_vowel, is_schwa_like,
                       is_vowel, normalize_espeak)

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
    # vowel reduction (same criterion as phenomena._word_rules)
    if is_full_vowel(canon) and (is_schwa_like(real) or real == "ɪ"):
        return True
    # monophthongization candidate: canonical diphthong + real simple vowel
    if canon in DIPHTHONGS and is_vowel(real) and real not in DIPHTHONGS:
        return True
    return False


class Wav2Vec2PhoneEngine:
    def __init__(self, model_id: str = MODEL_ID, device: str = "cpu"):
        import torch
        from transformers import AutoModelForCTC, AutoProcessor

        self.torch = torch
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


def build_engine(name: str = "wav2vec2", device: str = "cpu"):
    """Factory for phone engines (the --phone-engine flag)."""
    if name == "wav2vec2":
        return Wav2Vec2PhoneEngine(device=device)
    if name == "allosaurus":
        raise NotImplementedError(
            "The allosaurus engine is not installed. Run `pip install allosaurus` and "
            "see task.md; the default engine (wav2vec2) does not need it."
        )
    raise ValueError(f"Unknown phone engine: {name}")
