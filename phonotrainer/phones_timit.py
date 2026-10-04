"""Phones ACTUALLY pronounced, from a recognizer trained on NARROW transcriptions.

The espeak engine (phones_real.Wav2Vec2PhoneEngine) was fine-tuned on G2P labels, and
a recognizer trained that way learns the standard pronunciation, not the one it
hears (ZIPA, ACL 2025): on our clips it gave "of" [ʌv] 69 % of the time and never a
glottal stop. This engine is a wav2vec2 fine-tuned on TIMIT's hand-made phonetic
transcriptions, whose 61 labels keep exactly what connected speech does: flaps (dx),
nasal flaps (nx), glottal stops (q), reduced vowels (ax, ix, axr), syllabic
consonants (en, el, em, eng) and stop closures (bcl…kcl) apart from their bursts —
so a /t/ that closes and never releases, the [t̚] of "that", is visible as a closure
with no burst after it.

The canonical side changes too. There is no espeak tokenizer here: the CMUdict
citation form (canonical.dict_pronunciation) is spelled in TIMIT labels and forced
against the SAME emissions (align_canonical.py), so real and canonical still share
an alphabet and one acoustic pass — and the reference is the dictionary itself, not
an espeak form that already flaps "better" and glottalizes "button".

Training data: TIMIT (LDC93S1), distributed by the LDC for non-commercial research
and education; the weights are Apache-2.0. See THIRD-PARTY-NOTICES.md.
"""

from __future__ import annotations

import json

import numpy as np

from .ipa_maps import UNRELEASED

MODEL_ID = "excalibur12/wav2vec2-large-lv60_phoneme-timit_english_timit-4k"
SAMPLE_RATE = 16000

# TIMIT-61 → the common IPA inventory (ipa_maps.ENGLISH_INVENTORY). Vowels follow
# the CMUdict convention of ipa_maps.arpabet_to_ipa (i, u, ɑ, ɔ), so the dictionary
# row and the canonical row of a word spell the same thing. ix, the reduced high
# vowel of "roses" and of a weak "it", is ᵻ (schwa-like, as espeak writes it): as ɪ
# it would vanish from the schwa share and read as the full vowel of "bit". ux (the
# fronted u of "dude") becomes u: not a phenomenon the report teaches. hv is a
# voiced h; ax-h a devoiced schwa (the "po" of "potato").
TIMIT_TO_IPA = {
    "aa": "ɑ", "ae": "æ", "ah": "ʌ", "ao": "ɔ", "aw": "aʊ", "ax": "ə", "ax-h": "ə",
    "axr": "ɚ", "ay": "aɪ", "eh": "ɛ", "er": "ɝ", "ey": "eɪ", "ih": "ɪ", "ix": "ᵻ",
    "iy": "i", "ow": "oʊ", "oy": "ɔɪ", "uh": "ʊ", "uw": "u", "ux": "u",
    "b": "b", "d": "d", "g": "ɡ", "p": "p", "t": "t", "k": "k",
    "ch": "tʃ", "jh": "dʒ", "dh": "ð", "th": "θ", "f": "f", "v": "v",
    "s": "s", "z": "z", "sh": "ʃ", "zh": "ʒ", "hh": "h", "hv": "h",
    "m": "m", "n": "n", "ng": "ŋ", "l": "l", "r": "ɹ", "w": "w", "y": "j",
    "dx": "ɾ", "nx": "ɾ̃", "q": "ʔ",
    "em": "m̩", "en": "n̩", "el": "l̩", "eng": "ŋ̍",
}

# A closure followed by one of its bursts is ONE stop (or affricate) spanning both;
# a closure with no burst after it is the unreleased stop — unless a vowel or an
# approximant comes right after: a stop cannot run into a vowel without releasing,
# so there the burst was there and the model just missed it (an onset [ɡ̚ʌ] in "got").
CLOSURES = {"bcl": "b̚", "dcl": "d̚", "gcl": "ɡ̚", "pcl": "p̚", "tcl": "t̚", "kcl": "k̚"}
RELEASES = {"bcl": {"b"}, "dcl": {"d", "jh"}, "gcl": {"g"}, "pcl": {"p"},
            "tcl": {"t", "ch"}, "kcl": {"k"}}
RELEASING_CONTEXT = frozenset({
    "aa", "ae", "ah", "ao", "aw", "ax", "ax-h", "axr", "ay", "eh", "er", "ey",
    "ih", "ix", "iy", "ow", "oy", "uh", "uw", "ux", "w", "y", "r", "l",
})

# Silence, epenthetic silence, utterance edges and the word delimiter: no phone.
SILENCES = frozenset({"h#", "pau", "epi", "|"})
# A burst this close after its closure, with only silence between them, is still
# the same stop held a little longer (tcl pau t): not an unreleased [t̚] plus a [t].
BURST_MAX_GAP = 0.06  # s

# ARPAbet (CMUdict) → TIMIT labels for the canonical. The reduced vowels are
# spelled the way TIMIT's own lexicon spells them (AH0 ax, IH0 ix, ER0 axr) and
# every stop carries its closure, which is what the model emits for a released
# stop: forcing a bare burst would squeeze the closure frames into the neighbors.
ARPABET_TO_TIMIT = {
    "AA": ["aa"], "AE": ["ae"], "AH": ["ah"], "AO": ["ao"], "AW": ["aw"],
    "AY": ["ay"], "EH": ["eh"], "ER": ["er"], "EY": ["ey"], "IH": ["ih"],
    "IY": ["iy"], "OW": ["ow"], "OY": ["oy"], "UH": ["uh"], "UW": ["uw"],
    "B": ["bcl", "b"], "D": ["dcl", "d"], "G": ["gcl", "g"], "P": ["pcl", "p"],
    "T": ["tcl", "t"], "K": ["kcl", "k"], "CH": ["tcl", "ch"], "JH": ["dcl", "jh"],
    "DH": ["dh"], "TH": ["th"], "F": ["f"], "V": ["v"], "S": ["s"], "Z": ["z"],
    "SH": ["sh"], "ZH": ["zh"], "HH": ["hh"], "M": ["m"], "N": ["n"],
    "NG": ["ng"], "L": ["l"], "R": ["r"], "W": ["w"], "Y": ["y"],
}
UNSTRESSED_TIMIT = {"AH": "ax", "IH": "ix", "ER": "axr"}


def arpabet_to_timit(arpabet: list[str]) -> list[str]:
    """['T', 'UW1'] → ['tcl', 't', 'uw']; ['B', 'AH1', 'T', 'AH0', 'N'] →
    ['bcl', 'b', 'ah', 'tcl', 't', 'ax', 'n']."""
    out: list[str] = []
    for p in arpabet:
        base, digit = (p[:-1], p[-1]) if p[-1:].isdigit() else (p, "")
        if digit == "0" and base in UNSTRESSED_TIMIT:
            out.append(UNSTRESSED_TIMIT[base])
        else:
            out.extend(ARPABET_TO_TIMIT.get(base, []))
    return out


def merge_units(units: list[tuple[str, float, float, float]]) -> list[dict]:
    """TIMIT label spans → phones: closure + burst fused, a closure alone made
    unreleased, silences dropped.

    `units` are (label, start, end, score) in temporal order, with times already
    in seconds. Each phone keeps the TIMIT labels it came from in `label`.
    """
    phones: list[dict] = []
    k = 0
    while k < len(units):
        label, start, end, score = units[k]
        k += 1
        if label in SILENCES:
            continue
        if label in CLOSURES:
            j = k
            while (j < len(units) and units[j][0] in SILENCES
                   and units[j][1] - end <= BURST_MAX_GAP):
                j += 1
            if (j < len(units) and units[j][0] in RELEASES[label]
                    and units[j][1] - end <= BURST_MAX_GAP):
                k = j                   # the silences in between go with the stop
            nxt = units[k][0] if k < len(units) else None
            if nxt in RELEASES[label]:
                _, _, end, burst_score = units[k]
                k += 1
                phones.append({"phone": TIMIT_TO_IPA[nxt], "label": f"{label} {nxt}",
                               "start": start, "end": end,
                               "score": round((score + burst_score) / 2, 3)})
            elif nxt in RELEASING_CONTEXT:
                phones.append({"phone": UNRELEASED[CLOSURES[label]], "label": label,
                               "start": start, "end": end, "score": score})
            else:
                phones.append({"phone": CLOSURES[label], "label": label,
                               "start": start, "end": end, "score": score})
            continue
        ipa = TIMIT_TO_IPA.get(label)
        if ipa is None:
            continue  # [UNK] and the like
        phones.append({"phone": ipa, "label": label, "start": start, "end": end,
                       "score": score})
    return phones


class TimitPhoneEngine:
    """wav2vec2 + TIMIT-61 CTC: realized phones AND the forced canonical."""

    name = "timit61"
    model_id = MODEL_ID
    narrow = True          # sees unreleased stops, glottal stops, nasal flaps
    form_scoring = False   # variants.py is written in the espeak alphabet
    # Attraction exists for the espeak model's multilingual leakage. Here it changed
    # no label on the validation clips but rewrote 27 % of the words — ᵻ→ə, ɛ→æ,
    # d→t: exactly the narrow detail (and the vowel of can/can't) this engine is for.
    attraction_default = False
    alignment = "torchaudio forced_align of the CMUdict citation form over TIMIT-61 emissions"

    def __init__(self, model_id: str = MODEL_ID, device: str = "cpu"):
        import torch
        from huggingface_hub import hf_hub_download
        from transformers import AutoModelForCTC, Wav2Vec2FeatureExtractor

        self.torch = torch
        self.model_id = model_id
        self.feature_extractor = Wav2Vec2FeatureExtractor.from_pretrained(model_id)
        self.model = AutoModelForCTC.from_pretrained(model_id).to(device).eval()
        with open(hf_hub_download(model_id, "vocab.json"), encoding="utf-8") as fh:
            vocab = json.load(fh)
        self._init_vocab(vocab)
        self.device = device

    def _init_vocab(self, vocab: dict[str, int]) -> None:
        self.label_to_id = dict(vocab)
        self.id_to_label = {i: label for label, i in vocab.items()}
        self.blank_id = vocab["[PAD]"]
        self._silence_ids = [vocab[s] for s in sorted(SILENCES) if s in vocab]

    # --- acoustics -----------------------------------------------------------
    def log_probs(self, audio: np.ndarray):
        """CTC log-softmax emissions [T, C] for a mono 16 kHz chunk."""
        torch = self.torch
        with torch.inference_mode():
            inputs = self.feature_extractor(audio, sampling_rate=SAMPLE_RATE,
                                            return_tensors="pt")
            logits = self.model(inputs.input_values.to(self.device)).logits[0]
            return torch.log_softmax(logits, dim=-1).cpu()

    @staticmethod
    def frame_duration(n_samples: int, n_frames: int) -> float:
        return n_samples / n_frames / SAMPLE_RATE

    def greedy_phones(self, audio: np.ndarray, t_offset: float = 0.0,
                      log_probs=None) -> list[dict]:
        """Greedy CTC decoding → phones with absolute times (see merge_units)."""
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
        units = [
            (self.id_to_label.get(tid, "[UNK]"),
             round(t_offset + f0 * frame_dur, 3), round(t_offset + f1 * frame_dur, 3),
             round(float(np.exp(s / n)), 3))
            for tid, f0, f1, s, n in spans
        ]
        return merge_units(units)

    # --- canonical (align_canonical.py) --------------------------------------
    def for_alignment(self, log_probs):
        """Emissions for the forced alignment, with silence folded into the blank.

        The model labels silence as a token of its own (h#, pau, epi), which the
        citation form never contains: left alone, the aligner stretched the first
        phone of a segment over the leading h# ("The" got its /ð/ 180 ms before it
        was said, and read as an elided word).
        """
        torch = self.torch
        lp = log_probs.clone()
        cols = [self.blank_id] + self._silence_ids
        lp[:, self.blank_id] = torch.logsumexp(log_probs[:, cols], dim=-1)
        return lp

    def word_ids(self, word: str) -> tuple[int, ...]:
        """The citation form of `word` as TIMIT label ids (closures included)."""
        from .canonical import dict_pronunciation

        labels = arpabet_to_timit(dict_pronunciation(word)["arpabet"])
        return tuple(self.label_to_id[label] for label in labels)

    def ids_label(self, ids) -> str:
        return " ".join(self.id_to_label[i] for i in ids)

    def spans_to_phones(self, spans: list[tuple[int, int, int, float]],
                        t_offset: float, frame_dur: float) -> list[dict]:
        """Forced (token_id, start_frame, end_frame, score) → canonical phones."""
        units = [(self.id_to_label[tid], round(t_offset + f0 * frame_dur, 3),
                  round(t_offset + f1 * frame_dur, 3), round(float(score), 3))
                 for tid, f0, f1, score in spans]
        return merge_units(units)
