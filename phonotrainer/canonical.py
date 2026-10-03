"""Canonical dictionary pronunciation (CMUdict via g2p_en) for display and for OOV.

With the espeak engine the time-aligned canonical sequence does NOT come from here
(see align_canonical.py) and this module only provides the "dictionary form" in IPA
and flags out-of-vocabulary words. The TIMIT engine forces this very citation form
against the audio, so here it becomes the reference every rule measures against.
"""

from __future__ import annotations

import re
from functools import lru_cache

from .ipa_maps import arpabet_to_ipa

_WORD_RE = re.compile(r"[a-z']+")

# Function words whose weak form (report §2) is measured against the STRONG one.
# CMUdict lists the weak form first for some of them (a AH0, and AH0 N D, her
# HH ER0, were W ER0): as the citation form that would make [ə] the norm for "a"
# and hide the reduction the weak-form table teaches. "the" is left out on
# purpose: ðə/ði alternate with the next sound (ði apple), so a strong /ðiː/
# reference would flag nearly every "the" — noise, not a lesson.
STRONG_CITATION = frozenset({
    "a", "an", "and", "but", "or", "of", "to", "for", "from", "at", "as", "than",
    "that", "can", "could", "would", "should", "must", "have", "has", "had", "was",
    "were", "do", "does", "am", "are", "he", "him", "his", "her", "them", "us",
    "you", "your", "some", "there",
})


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
        arpabet = _citation_entry(w, entry)
        oov = False
    else:
        arpabet = [p for p in _g2p()(w) if p.strip() and p != " "]
        oov = True
    # Keeping stress: /bˈɛtɚ/ teaches that the flap lives in the unstressed syllable,
    # which is exactly the rule. The rules in phenomena.py look at `arpabet` (with its
    # stress digits), not at this string, which is for display only.
    ipa = "".join(arpabet_to_ipa(arpabet, with_stress=True))
    return {"ipa": ipa, "arpabet": arpabet, "oov": oov}


def _citation_entry(word: str, entries: list[list[str]]) -> list[str]:
    """CMUdict's first entry, except for STRONG_CITATION words: their first entry
    with a primary-stressed vowel (a → EY1, and → AE1 N D, were → W ER1)."""
    if word in STRONG_CITATION:
        for arpabet in entries:
            if any(p.endswith("1") for p in arpabet):
                return arpabet
    return entries[0]
