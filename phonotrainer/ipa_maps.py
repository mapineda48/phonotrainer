"""Mapeos entre alfabetos fonéticos: ARPAbet ↔ IPA, saneamiento del inventario
espeak multilingüe → inglés (MEJORA 0) y normalización para panphon.

El modelo facebook/wav2vec2-lv-60-espeak-cv-ft es multilingüe: su vocabulario de 392
tokens filtra fonos no ingleses en audio inglés (dígitos de tono de mandarín `ai5`,
aspiradas `kʰ`/`kh`, SAMPA crudo `dZ`, `ᵻ`…). `normalize_espeak` lleva TODO token a un
inventario inglés cerrado antes de cualquier diff; lo que no tiene entrada explícita
cae al vecino más cercano por rasgos panphon (con warning).
"""

from __future__ import annotations

import logging
import re
import unicodedata
from functools import lru_cache

logger = logging.getLogger("phonotrainer.ipa_maps")

# --- ARPAbet (CMUdict / g2p_en) → IPA (en-US) ---------------------------------
ARPABET_TO_IPA = {
    "AA": "ɑ", "AE": "æ", "AH": "ʌ", "AO": "ɔ", "AW": "aʊ", "AY": "aɪ",
    "B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "EH": "ɛ", "ER": "ɚ",
    "EY": "eɪ", "F": "f", "G": "ɡ", "HH": "h", "IH": "ɪ", "IY": "i",
    "JH": "dʒ", "K": "k", "L": "l", "M": "m", "N": "n", "NG": "ŋ",
    "OW": "oʊ", "OY": "ɔɪ", "P": "p", "R": "ɹ", "S": "s", "SH": "ʃ",
    "T": "t", "TH": "θ", "UH": "ʊ", "UW": "u", "V": "v", "W": "w",
    "Y": "j", "Z": "z", "ZH": "ʒ",
}

STRESS_MARKS = {"0": "", "1": "ˈ", "2": "ˌ"}


def arpabet_to_ipa(phones: list[str], with_stress: bool = False) -> list[str]:
    """['DH', 'AH0', 'Z'] → ['ð', 'ə', 'z'] (opcionalmente con marca de acento).

    `AH0` es schwa por definición en CMUdict: mapearlo a /ʌ/ hacía que el
    diccionario dijera /ðʌ/ para «the» y /ʌbaʊt/ para «about», justo al lado de
    la reducción vocálica que la herramienta quiere enseñar.
    """
    out = []
    for p in phones:
        stress = ""
        base = p
        digit = ""
        if base and base[-1].isdigit():
            digit = base[-1]
            stress = STRESS_MARKS.get(digit, "")
            base = base[:-1]
        ipa = ARPABET_TO_IPA.get(base)
        if ipa is None:
            continue
        if base == "AH" and digit == "0":
            ipa = "ə"
        out.append((stress + ipa) if with_stress else ipa)
    return out


# --- Normalización de tokens espeak → símbolos que panphon conoce --------------
# panphon no tiene vocales rotizadas ni algunos símbolos "internos" de espeak.
ESPEAK_TO_PANPHON = {
    "ɚ": "ə", "ɝ": "ɜ", "ᵻ": "ɪ", "ɫ": "l", "ɬ": "l",
    "ɐ": "ə", "ʔ̞": "ʔ", "ɹ̩": "ɹ", "n̩": "n", "l̩": "l", "m̩": "m",
    "aɪɚ": "aɪə", "aɪə": "aɪə", "oːɹ": "ɔɹ", "ɔːɹ": "ɔɹ", "ɑːɹ": "ɑɹ",
    "ɛɹ": "ɛɹ", "ʊɹ": "ʊɹ", "ɪɹ": "ɪɹ", "iə": "iə", "ʉ": "u", "ɵ": "ə",
    "oː": "oː", "eː": "eː",
}


def normalize_for_panphon(token: str) -> str:
    """Devuelve una forma del token que panphon pueda vectorizar."""
    token = token.strip("ˈˌ")
    return ESPEAK_TO_PANPHON.get(token, token)


# --- MEJORA 0: inventario inglés cerrado y saneamiento del vocab multilingüe ----
ENGLISH_INVENTORY = frozenset({
    # vocales y diptongos (espeak en-us + variantes r-coloreadas)
    "i", "iː", "ɪ", "ɛ", "æ", "ɑ", "ɑː", "ɒ", "ʌ", "ʊ", "u", "uː",
    "ə", "ɐ", "ɚ", "ɜ", "ɜː", "ɔ", "ɔː", "oʊ", "aʊ", "aɪ", "eɪ", "ɔɪ",
    "aɪɚ", "aɪə", "iə", "eə", "ɪɹ", "ɛɹ", "ʊɹ", "ɔːɹ", "oːɹ", "ɑːɹ",
    "əl", "ju",
    # consonantes (con alófonos que SON fenómeno: ɾ, ʔ)
    "p", "b", "t", "d", "k", "ɡ", "tʃ", "dʒ", "f", "v", "θ", "ð",
    "s", "z", "ʃ", "ʒ", "h", "m", "n", "ŋ", "l", "ɹ", "w", "j",
    "ɾ", "ʔ", "n̩", "l̩",
})

# Entradas explícitas: confusiones plausibles en audio inglés y bases de tono.
# Ojo: "o"/"e"/"a" escuetos son realizaciones monoptongadas — mapear a monoptongo
# vecino, NUNCA al diptongo (ocultaría monophthongization).
NORMALIZE_ESPEAK = {
    "ᵻ": "ɪ", "ɨ": "ɪ", "ɨː": "iː", "ʉ": "u", "ɵ": "ə", "ɵː": "ɜː",
    "ɘ": "ə", "ɫ": "l", "ɬ": "l", "r": "ɹ", "ʁ": "ɹ", "ɻ": "ɹ",
    "ɽ": "ɾ", "r̩": "ɚ", "ər": "ɚ", "β": "v", "ʋ": "v", "ɸ": "f",
    "x": "h", "χ": "h", "ç": "h", "ħ": "h", "ʕ": "h", "ʝ": "j",
    "ɲ": "n", "ɴ": "ŋ", "ɳ": "n", "ɭ": "l", "ʂ": "ʃ", "ɕ": "ʃ",
    "ʑ": "ʒ", "ʐ": "ʒ", "c": "k", "ɟ": "dʒ", "q": "k", "ʈ": "t",
    "ɖ": "d", "t̪": "t", "d̪": "d", "s̪": "s",
    "dZ": "dʒ", "tS": "tʃ", "S": "ʃ", "N": "ŋ", "X": "h",
    "ts": "s", "dz": "z", "pf": "f", "sx": "s", "tɕ": "tʃ", "dʑ": "dʒ",
    "a": "æ", "e": "ɛ", "o": "ɔ",
    "aː": "ɑː", "ä": "ɑː", "æː": "æ", "ɪː": "iː", "ʊː": "uː",
    "ø": "oʊ", "øː": "ɜː", "œ": "ɜː", "œː": "ɜː", "y": "u", "yː": "uː",
    "ɯ": "u", "e̞": "ɛ", "o̞": "ɔ", "ɛː": "ɛ", "eː": "eɪ", "oː": "oʊ",
    "əʊ": "oʊ", "ee": "iː", "oe": "ɜː",
    "ai": "aɪ", "au": "aʊ", "ɑu": "aʊ", "ei": "eɪ", "ou": "oʊ", "oi": "ɔɪ",
    "ɑ̃": "ɑː", "ɛ̃": "ɛ", "ɔ̃": "ɔ", "œ̃": "ɜː", "ɐ̃": "ɐ",
    "õ": "oʊ", "ã": "ɑː", "ĩ": "iː", "ũ": "uː", "ẽ": "eɪ",
}

_STRIP_RE = re.compile(r"[0-9.^\[\]\"?]")
_MODIFIERS = str.maketrans("", "", "ʰʲˤᵝʷ")
# marcas combinantes a eliminar en la limpieza (dental, tilde, ATR, anillo…)
_COMBINING_KEEP = {"̩"}  # syllabic (n̩, l̩) se conserva

_WARNED: set[str] = set()


def _cleanup(token: str) -> str:
    t = token.replace(":", "ː").replace("ːː", "ː")
    t = _STRIP_RE.sub("", t)
    t = t.translate(_MODIFIERS)
    decomposed = unicodedata.normalize("NFD", t)
    t = "".join(ch for ch in decomposed
                if unicodedata.category(ch) != "Mn" or ch in _COMBINING_KEEP)
    t = unicodedata.normalize("NFC", t)
    half = len(t) // 2
    if half and t[:half] == t[half:]:
        t = t[:half]
    return t


@lru_cache(maxsize=4096)
def _nearest_english(token: str) -> str | None:
    """Vecino más cercano en el inventario por rasgos panphon."""
    import panphon.distance

    dst = panphon.distance.Distance()
    norm = normalize_for_panphon(token)
    if not dst.fm.ipa_segs(norm):
        return None
    best, best_d = None, None
    for cand in sorted(ENGLISH_INVENTORY):
        try:
            d = dst.weighted_feature_edit_distance(norm, normalize_for_panphon(cand))
        except Exception:
            continue
        if best_d is None or d < best_d:
            best, best_d = cand, d
    return best


def _lookup(token: str) -> str | None:
    if token in NORMALIZE_ESPEAK:
        return NORMALIZE_ESPEAK[token]
    if token in ENGLISH_INVENTORY:
        return token
    return None


@lru_cache(maxsize=4096)
def normalize_espeak(token: str) -> str | None:
    """Token crudo del modelo → token del inventario inglés (o None = descartar)."""
    found = _lookup(token)
    if found is not None:
        return found

    cleaned = _cleanup(token)
    if not cleaned:
        _warn(token, None)
        return None
    found = _lookup(cleaned)
    if found is not None:
        return found
    if cleaned.endswith("h") and len(cleaned) > 1:
        found = _lookup(cleaned[:-1])
        if found is not None:
            return found

    nearest = _nearest_english(cleaned)
    _warn(token, nearest)
    return nearest


def _warn(token: str, mapped: str | None) -> None:
    if token not in _WARNED:
        _WARNED.add(token)
        logger.warning("fono fuera del inventario inglés: %r → %r", token, mapped)


# Diptongos ingleses y su primer elemento (para monophthongization, MEJORA 2)
DIPHTHONGS = frozenset({"aɪ", "oʊ", "eɪ", "aʊ", "ɔɪ"})
FIRST_ELEMENT = {"aɪ": "a", "oʊ": "o", "eɪ": "e", "aʊ": "a", "ɔɪ": "ɔ"}


# --- Clasificación de fonos (sobre tokens espeak/IPA) ---------------------------
_VOWEL_CHARS = set("aeiouæɑɒʌɛɜɝɪʊɔəɐɚᵻyøœɶɯɤʉɨ")

SCHWA_LIKE = {"ə", "ɐ", "ᵻ", "ɚ", "ɘ", "ɵ"}

GLOTTAL = {"ʔ"}
FLAP = {"ɾ", "ɾ̃"}


def is_vowel(token: str) -> bool:
    t = token.strip("ˈˌː")
    return bool(t) and t[0] in _VOWEL_CHARS


def is_schwa_like(token: str) -> bool:
    return token.strip("ˈˌː") in SCHWA_LIKE


def is_full_vowel(token: str) -> bool:
    return is_vowel(token) and not is_schwa_like(token)


def is_consonant(token: str) -> bool:
    return bool(token) and not is_vowel(token)
