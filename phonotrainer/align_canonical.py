"""Fonemas canónicos ALINEADOS EN TIEMPO vía forced alignment CTC.

Estrategia (decisión de Fase 0): en vez de MMS_FA (alinea caracteres), forzamos la
secuencia canónica — fonemizada con el propio tokenizer espeak del modelo — contra
las emisiones del mismo wav2vec2-espeak usando torchaudio.functional.forced_align.
Canónico y real comparten así alfabeto y pasada acústica.
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
    """ids de fonemas canónicos (espeak) para una palabra; cachea por texto."""
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
    """Alinea los fonemas canónicos de `words` contra el audio del segmento.

    words: [{'word', 'start', 'end', …}] (tiempos absolutos, solo informativos aquí).
    Devuelve por palabra: {'word', 'phones': [{'phone','start','end','score'}, …],
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
                f"segmento con {lp.size(0)} frames para {len(targets)} fonemas"
            )
        aligned, scores = F.forced_align(
            lp.unsqueeze(0),
            torch.tensor([targets], dtype=torch.int64),
            blank=engine.blank_id,
        )
        spans = F.merge_tokens(aligned[0], scores[0].exp(), blank=engine.blank_id)
        if len(spans) != len(targets):
            raise AlignmentError(
                f"merge_tokens devolvió {len(spans)} spans para {len(targets)} targets"
            )
    except Exception:
        # Respaldo: distribuir fonemas uniformemente dentro de la ventana Whisper.
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
