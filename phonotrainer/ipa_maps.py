"""Mappings between phonetic alphabets: ARPAbet ↔ IPA, sanitization of the
multilingual espeak inventory down to English (IMPROVEMENT 0) and normalization for
panphon.

The facebook/wav2vec2-lv-60-espeak-cv-ft model is multilingual: its 392-token
vocabulary leaks non-English phones into English audio (Mandarin tone digits `ai5`,
aspirates `kʰ`/`kh`, raw SAMPA `dZ`, `ᵻ`…). `normalize_espeak` maps EVERY token into a
closed English inventory before any diff runs; anything without an explicit entry
falls back to its nearest neighbor by panphon features (with a warning).

The TIMIT engine (phones_timit.py) maps its 61 labels straight into the same
inventory, which is why it also holds the narrow symbols only that engine emits:
unreleased stops (t̚), the nasal flap (ɾ̃), stressed ɝ and the syllabic m̩/ŋ̍.
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
    """['DH', 'AH0', 'Z'] → ['ð', 'ə', 'z'] (optionally carrying the stress mark).

    `AH0` is schwa by definition in CMUdict: mapping it to /ʌ/ made the dictionary
    claim /ðʌ/ for "the" and /ʌbaʊt/ for "about", right next to the very vowel
    reduction this tool sets out to teach. `ER` follows suit: stressed /ɝ/ (bird,
    were) against unstressed /ɚ/ (butter), the contrast TIMIT keeps as er/axr.
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
        elif base == "ER" and digit in {"1", "2"}:
            ipa = "ɝ"
        out.append((stress + ipa) if with_stress else ipa)
    return out


# --- Normalizing espeak tokens → symbols panphon understands ------------------
# panphon has no rhotic vowels, nor some of espeak's "internal" symbols.
ESPEAK_TO_PANPHON = {
    "ɚ": "ə", "ɝ": "ɜ", "ᵻ": "ɪ", "ɫ": "l", "ɬ": "l",
    "ɐ": "ə", "ʔ̞": "ʔ", "ɹ̩": "ɹ", "n̩": "n", "l̩": "l", "m̩": "m",
    "aɪɚ": "aɪə", "aɪə": "aɪə", "oːɹ": "ɔɹ", "ɔːɹ": "ɔɹ", "ɑːɹ": "ɑɹ",
    "ɛɹ": "ɛɹ", "ʊɹ": "ʊɹ", "ɪɹ": "ɪɹ", "iə": "iə", "ʉ": "u", "ɵ": "ə",
    "oː": "oː", "eː": "eː", "ŋ̍": "ŋ",
    # an unreleased stop is the stop (panphon has no feature for the release):
    # t↔t̚ then costs next to nothing and t̚ inherits every NATIVE_SHIFT of t
    "p̚": "p", "b̚": "b", "t̚": "t", "d̚": "d", "k̚": "k", "ɡ̚": "ɡ",
}


def normalize_for_panphon(token: str) -> str:
    """Return a form of the token that panphon is able to vectorize."""
    token = token.strip("ˈˌ")
    return ESPEAK_TO_PANPHON.get(token, token)


# --- IMPROVEMENT 0: closed English inventory, multilingual vocab sanitization ---
ENGLISH_INVENTORY = frozenset({
    # vowels and diphthongs (espeak en-us + r-colored variants)
    "i", "iː", "ɪ", "ɛ", "æ", "ɑ", "ɑː", "ɒ", "ʌ", "ʊ", "u", "uː",
    "ə", "ɐ", "ɚ", "ɜ", "ɜː", "ɔ", "ɔː", "oʊ", "aʊ", "aɪ", "eɪ", "ɔɪ",
    "aɪɚ", "aɪə", "iə", "eə", "ɪɹ", "ɛɹ", "ʊɹ", "ɔːɹ", "oːɹ", "ɑːɹ",
    "əl", "ju",
    # consonants (including the allophones that ARE the phenomena: ɾ, ʔ)
    "p", "b", "t", "d", "k", "ɡ", "tʃ", "dʒ", "f", "v", "θ", "ð",
    "s", "z", "ʃ", "ʒ", "h", "m", "n", "ŋ", "l", "ɹ", "w", "j",
    "ɾ", "ʔ", "n̩", "l̩",
    # narrow symbols only the TIMIT engine emits: stressed r-colored vowel (er),
    # reduced high vowel (ix: roses, a weak "it"), nasal flap (nx: winter, twenty),
    # syllabic m/ŋ (em, eng) and stops whose closure never releases (bcl…kcl with
    # no burst: that [ðæt̚], stop it). espeak's own ᵻ still normalizes to ɪ.
    "ɝ", "ᵻ", "ɾ̃", "m̩", "ŋ̍", "p̚", "b̚", "t̚", "d̚", "k̚", "ɡ̚",
})

# Unreleased stop → its released counterpart.
UNRELEASED = {"p̚": "p", "b̚": "b", "t̚": "t", "d̚": "d", "k̚": "k", "ɡ̚": "ɡ"}

# Syllabic consonants: a syllable nucleus without a vowel (button bʌʔn̩).
SYLLABIC = frozenset({"n̩", "l̩", "m̩", "ŋ̍", "ɹ̩"})

# Explicit entries: confusions that are plausible in English audio, plus the bare
# bases behind tone-marked tokens.
# Careful: a bare "o"/"e"/"a" is a monophthongized realization — map it to the
# neighboring monophthong, NEVER to the diphthong (that would hide
# monophthongization).
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
# combining marks dropped during cleanup (dental, nasal tilde, ATR, ring…)
_COMBINING_KEEP = {"̩"}  # the syllabic mark (n̩, l̩) is kept

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
    """Nearest neighbor within the inventory, by panphon features."""
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
    """Raw model token → English-inventory token (or None, meaning: discard it)."""
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
        logger.warning("phone outside the English inventory: %r → %r", token, mapped)


# English diphthongs and their first element (for monophthongization, IMPROVEMENT 2)
DIPHTHONGS = frozenset({"aɪ", "oʊ", "eɪ", "aʊ", "ɔɪ"})
FIRST_ELEMENT = {"aɪ": "a", "oʊ": "o", "eɪ": "e", "aʊ": "a", "ɔɪ": "ɔ"}


# --- Phone classification (over espeak/IPA tokens) -----------------------------
_VOWEL_CHARS = set("aeiouæɑɒʌɛɜɝɪʊɔəɐɚᵻyøœɶɯɤʉɨ")

SCHWA_LIKE = {"ə", "ɐ", "ᵻ", "ɚ", "ɘ", "ɵ"}

GLOTTAL = {"ʔ"}
FLAP = {"ɾ", "ɾ̃"}

# Place assimilation of a word-final alveolar to the next onset (ten bucks → tem,
# in case → ing, that boy → thap): the alveolar, the place of the onset, and what
# it becomes there (unreleased variants included: the narrow engine hears [ðæp̚]).
LABIAL = frozenset({"p", "b", "m"})
VELAR = frozenset({"k", "ɡ"})
PLACE_ASSIMILATION = {
    ("n", "labial"): frozenset({"m"}), ("n", "velar"): frozenset({"ŋ"}),
    ("t", "labial"): frozenset({"p", "p̚"}), ("t", "velar"): frozenset({"k", "k̚"}),
    ("d", "labial"): frozenset({"b", "b̚"}), ("d", "velar"): frozenset({"ɡ", "ɡ̚"}),
}


def is_vowel(token: str) -> bool:
    t = token.strip("ˈˌː")
    return bool(t) and t[0] in _VOWEL_CHARS


def is_schwa_like(token: str) -> bool:
    return token.strip("ˈˌː") in SCHWA_LIKE


def is_full_vowel(token: str) -> bool:
    return is_vowel(token) and not is_schwa_like(token)


def is_consonant(token: str) -> bool:
    return bool(token) and not is_vowel(token)


HIGH_FRONT = frozenset({"i", "iː", "ɪ"})


def reduces_vowel(full: str | None, real: str | None, weak_form_word: bool = False) -> bool:
    """Is `real` the reduced version of the full vowel `full`? The vowel_reduction
    criterion, shared by the rules, the variant scoring and the metrics.

    Schwa-like counts, except ᵻ (TIMIT ix) for a high front vowel: "is"/"it" [ᵻz]
    are the same vowel, not the weak forms the report teaches. So does ɪ for a vowel
    that is neither high front nor a diphthong — and, for a weak-form function word
    whose strong form is a diphthong (a → eɪ), for that diphthong too: [ɪ] is the
    article's weak form.
    """
    if not full or not real or not is_full_vowel(full):
        return False
    if is_schwa_like(real):
        return not (real.strip("ˈˌː") == "ᵻ" and full in HIGH_FRONT)
    if real == "ɪ":
        return full not in HIGH_FRONT and (full not in DIPHTHONGS or weak_form_word)
    return False
