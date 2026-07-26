"""Pronunciación canónica de diccionario (CMUdict vía g2p_en) para mostrar y para OOV.

La alineación temporal canónica NO sale de aquí (ver align_canonical.py); este módulo
da la "forma de diccionario" en IPA y marca palabras fuera de vocabulario.
"""

from __future__ import annotations

import re
from functools import lru_cache

from .ipa_maps import arpabet_to_ipa

_WORD_RE = re.compile(r"[a-z']+")


def clean_word(word: str) -> str:
    """Normaliza una palabra del ASR para búsquedas: minúsculas, sin puntuación."""
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
    """Devuelve {'ipa': str, 'arpabet': [..], 'oov': bool} para una palabra."""
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
    # Con acento: /bˈɛtɚ/ enseña que el flap vive en la sílaba átona, que es la
    # regla. Las reglas de phenomena.py miran `arpabet` (con dígitos de acento),
    # no esta cadena, que es solo para mostrar.
    ipa = "".join(arpabet_to_ipa(arpabet, with_stress=True))
    return {"ipa": ipa, "arpabet": arpabet, "oov": oov}
