"""Canonical phonemes TIME-ALIGNED through CTC forced alignment.

Strategy (a Phase 0 decision): instead of MMS_FA (which aligns characters), we force
the canonical sequence — spelled in the recognizer's own labels — against the
emissions of that same model, using torchaudio.functional.forced_align. Canonical and
real therefore share both an alphabet and a single acoustic pass.

What the canonical sequence is depends on the engine (`engine.word_ids`): with the
`espeak` engine it is the model's own espeak phonemization (which already flaps
"better" and glottalizes "button"); with the default `timit61` engine it is the
CMUdict citation form spelled in TIMIT labels (phones_timit.py).
"""

from __future__ import annotations

from functools import lru_cache

import numpy as np

from .canonical import clean_word


class AlignmentError(RuntimeError):
    pass


@lru_cache(maxsize=4096)
def _word_phone_ids(tokenizer_id: int, word: str) -> tuple:
    """Canonical (espeak) phoneme ids for a word; cached by text."""
    tokenizer = _TOKENIZERS[tokenizer_id]
    w = clean_word(word)
    if not w:
        return ()
    ids = tokenizer(w).input_ids
    specials = set(tokenizer.all_special_ids)
    unk = tokenizer.unk_token_id
    return tuple(i for i in ids if i not in specials and i != unk)


_TOKENIZERS: dict[int, object] = {}


def canonical_phone_ids(tokenizer, word: str) -> list[int]:
    """espeak phoneme ids of `word` for the espeak tokenizer (variants.py uses it too)."""
    _TOKENIZERS[id(tokenizer)] = tokenizer
    return list(_word_phone_ids(id(tokenizer), word))


def align_words(engine, audio: np.ndarray, words: list[dict],
                t_offset: float = 0.0, log_probs=None) -> list[dict]:
    """Align the canonical phonemes of `words` against the segment audio.

    words: [{'word', 'start', 'end', …}] (absolute times, informational only here).
    Returns, per word: {'word', 'phones': [{'phone','start','end','score'}, …],
    'canonical_units': 'd ʌ z' (the engine's labels), 'fallback': bool}.
    """
    import torch
    import torchaudio.functional as F

    lp = engine.log_probs(audio) if log_probs is None else log_probs
    for_alignment = getattr(engine, "for_alignment", None)
    if for_alignment is not None:
        lp = for_alignment(lp)
    frame_dur = engine.frame_duration(len(audio), lp.size(0))

    per_word_ids = [engine.word_ids(w["word"]) for w in words]
    targets = [tid for ids in per_word_ids for tid in ids]

    results = [
        {
            "word": w["word"],
            "canonical_units": engine.ids_label(ids),
            "phones": [],
            "fallback": False,
        }
        for w, ids in zip(words, per_word_ids)
    ]
    if not targets:
        return results

    try:
        if lp.size(0) < len(targets):
            raise AlignmentError(
                f"segment with {lp.size(0)} frames for {len(targets)} phonemes"
            )
        aligned, scores = F.forced_align(
            lp.unsqueeze(0),
            torch.tensor([targets], dtype=torch.int64),
            blank=engine.blank_id,
        )
        spans = F.merge_tokens(aligned[0], scores[0].exp(), blank=engine.blank_id)
        if len(spans) != len(targets):
            raise AlignmentError(
                f"merge_tokens returned {len(spans)} spans for {len(targets)} targets"
            )
    except Exception:
        # Fallback: spread the phonemes uniformly inside the Whisper window.
        for res, w, ids in zip(results, words, per_word_ids):
            res["fallback"] = True
            res["phones"] = _uniform_fallback(engine, w, ids)
        return results

    idx = 0
    for res, ids in zip(results, per_word_ids):
        word_spans = [(s.token, s.start, s.end, float(s.score))
                      for s in spans[idx:idx + len(ids)]]
        idx += len(ids)
        res["phones"] = engine.spans_to_phones(word_spans, t_offset, frame_dur)
    return results


def _uniform_fallback(engine, word: dict, ids: tuple) -> list[dict]:
    """One equal slice of the Whisper window per unit (a "frame" = one slice)."""
    if not ids:
        return []
    step = (word["end"] - word["start"]) / len(ids)
    units = [(tid, k, k + 1, 0.0) for k, tid in enumerate(ids)]
    return engine.spans_to_phones(units, word["start"], step)
