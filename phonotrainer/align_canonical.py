"""Canonical phonemes TIME-ALIGNED through CTC forced alignment.

Strategy (a Phase 0 decision): instead of MMS_FA (which aligns characters), we force
the canonical sequence — phonemized with the model's own espeak tokenizer — against
the emissions of that same wav2vec2-espeak model, using
torchaudio.functional.forced_align. Canonical and real therefore share both an
alphabet and a single acoustic pass.
"""

from __future__ import annotations

from functools import lru_cache

import numpy as np

from .canonical import clean_word
from .ipa_maps import normalize_espeak


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
    _TOKENIZERS[id(tokenizer)] = tokenizer
    return list(_word_phone_ids(id(tokenizer), word))


def align_words(engine, audio: np.ndarray, words: list[dict],
                t_offset: float = 0.0, log_probs=None) -> list[dict]:
    """Align the canonical phonemes of `words` against the segment audio.

    words: [{'word', 'start', 'end', …}] (absolute times, informational only here).
    Returns, per word: {'word', 'phones': [{'phone','start','end','score'}, …],
    'canonical_espeak': 'dʌz', 'fallback': bool}.
    """
    import torch
    import torchaudio.functional as F

    lp = engine.log_probs(audio) if log_probs is None else log_probs
    frame_dur = engine.frame_duration(len(audio), lp.size(0))

    per_word_ids = [canonical_phone_ids(engine.tokenizer, w["word"]) for w in words]
    targets = [tid for ids in per_word_ids for tid in ids]

    results = [
        {
            "word": w["word"],
            "canonical_espeak": " ".join(
                engine.tokenizer.convert_ids_to_tokens(list(ids))
            ),
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
            res["phones"] = _uniform_fallback(engine.tokenizer, w, ids)
        return results

    idx = 0
    for res, ids in zip(results, per_word_ids):
        phones = []
        for _ in ids:
            span = spans[idx]
            idx += 1
            raw = engine.tokenizer.convert_ids_to_tokens(span.token)
            phones.append({
                "phone": normalize_espeak(raw) or raw,
                "raw_phone": raw,
                "start": round(t_offset + span.start * frame_dur, 3),
                "end": round(t_offset + span.end * frame_dur, 3),
                "score": round(float(span.score), 3),
            })
        res["phones"] = phones
    return results


def _uniform_fallback(tokenizer, word: dict, ids: tuple) -> list[dict]:
    if not ids:
        return []
    t0, t1 = word["start"], word["end"]
    step = (t1 - t0) / len(ids)
    phones = []
    for k, tid in enumerate(ids):
        raw = tokenizer.convert_ids_to_tokens(tid)
        phones.append({
            "phone": normalize_espeak(raw) or raw,
            "raw_phone": raw,
            "start": round(t0 + k * step, 3),
            "end": round(t0 + (k + 1) * step, 3),
            "score": 0.0,
        })
    return phones
