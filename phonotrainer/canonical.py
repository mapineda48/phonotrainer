"""Canonical dictionary pronunciation (CMUdict via g2p_en) for display and for OOV.

The time-aligned canonical sequence does NOT come from here (see align_canonical.py);
this module provides the "dictionary form" in IPA and flags out-of-vocabulary words.
"""

from __future__ import annotations

import re
from functools import lru_cache

from .ipa_maps import arpabet_to_ipa

_WORD_RE = re.compile(r"[a-z']+")


def clean_word(word: str) -> str:
    """Normalize an ASR word for lookups: lowercased, punctuation stripped."""
    m = _WORD_RE.findall(word.lower())
    return "".join(m)


@lru_cache(maxsize=1)
def _g2p():
    from g2p_en import G2p

    return G2p()


@lru_cache(maxsize=1)
def _cmudict():
    import nltk

    try:
        return nltk.corpus.cmudict.dict()
    except LookupError:
        nltk.download("cmudict", quiet=True)
        return nltk.corpus.cmudict.dict()


@lru_cache(maxsize=4096)
def dict_pronunciation(word: str) -> dict:
    """Return {'ipa': str, 'arpabet': [..], 'oov': bool} for a word."""
    w = clean_word(word)
    if not w:
        return {"ipa": "", "arpabet": [], "oov": True}
    entry = _cmudict().get(w)
    if entry:
        arpabet = entry[0]
        oov = False
    else:
        arpabet = [p for p in _g2p()(w) if p.strip() and p != " "]
        oov = True
    # Keeping stress: /bˈɛtɚ/ teaches that the flap lives in the unstressed syllable,
    # which is exactly the rule. The rules in phenomena.py look at `arpabet` (with its
    # stress digits), not at this string, which is for display only.
    ipa = "".join(arpabet_to_ipa(arpabet, with_stress=True))
    return {"ipa": ipa, "arpabet": arpabet, "oov": oov}
