"""IMPROVEMENT 0: phone inventory sanitization (multilingual vocabulary → English)."""

import pytest

from phonotrainer.canonical import dict_pronunciation
from phonotrainer.ipa_maps import ENGLISH_INVENTORY, normalize_espeak


def test_the_dictionary_form_keeps_the_stress_mark():
    """Stress explains the reduction: /bˈɛtɚ/ teaches that the flap falls in the
    unstressed syllable. The rules keep looking at the ARPAbet, not at this string."""
    entry = dict_pronunciation("better")
    assert entry["ipa"] == "bˈɛtɚ"
    assert entry["arpabet"] == ["B", "EH1", "T", "ER0"]
    # and AH0 is schwa: the unstressed syllable is visible as such
    assert dict_pronunciation("tonight")["ipa"] == "tənˈaɪt"
    assert dict_pronunciation("the")["ipa"] == "ðə"


def test_english_tokens_pass_through_untouched():
    for tok in ["ɾ", "ʔ", "oʊ", "aɪ", "ə", "ɚ", "θ", "ð", "ŋ", "iː", "n̩", "əl", "ʊɹ", "ɔːɹ"]:
        assert normalize_espeak(tok) == tok, tok


def test_mandarin_tone_digits():
    assert normalize_espeak("ai5") == "aɪ"
    assert normalize_espeak("ɑu5") == "aʊ"
    assert normalize_espeak("ei2") == "eɪ"
    assert normalize_espeak("ou5") == "oʊ"
    assert normalize_espeak("u5") == "u"
    assert normalize_espeak("a5") == "æ"


def test_aspirates_become_plain_stops():
    # both spellings found in the vocab: with a modifier (kʰ) and with a plain h (kh)
    assert normalize_espeak("kʰ") == "k"
    assert normalize_espeak("kh") == "k"
    assert normalize_espeak("th") == "t"
    assert normalize_espeak("ph") == "p"
    assert normalize_espeak("tʰ") == "t"
    assert normalize_espeak("pʰ") == "p"
    assert normalize_espeak("tʃʰ") == "tʃ"


def test_symbols_outside_the_inventory():
    assert normalize_espeak("ᵻ") == "ɪ"
    assert normalize_espeak("dZ") == "dʒ"   # raw SAMPA
    assert normalize_espeak("tS") == "tʃ"
    assert normalize_espeak("S") == "ʃ"
    assert normalize_espeak("N") == "ŋ"
    assert normalize_espeak("r") == "ɹ"
    assert normalize_espeak("ɫ") == "l"
    assert normalize_espeak("t̪") == "t"
    assert normalize_espeak("nʲ") == "n"    # palatalized → plain


def test_raw_monophthongs_are_not_diphthongized():
    # a bare [o] or [e] is a monophthongized realization: do NOT map it to the diphthong
    assert normalize_espeak("o") == "ɔ"
    assert normalize_espeak("e") == "ɛ"
    assert normalize_espeak("a") == "æ"


def test_nearest_neighbor_for_exotic_symbols():
    # with no explicit entry: they fall back to the panphon neighbor inside the inventory
    for tok in ["ʂ", "ɖ", "œ", "ɯ", "χ", "ɴ"]:
        res = normalize_espeak(tok)
        assert res in ENGLISH_INVENTORY, f"{tok} → {res}"


@pytest.mark.slow
def test_full_vocabulary_coverage():
    """EVERY token in the tokenizer vocab must map to the English inventory (or be dropped)."""
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained("facebook/wav2vec2-lv-60-espeak-cv-ft")
    specials = set(tok.all_special_tokens)
    unmapped = []
    dropped = []
    for t in tok.get_vocab():
        if t in specials:
            continue
        res = normalize_espeak(t)
        if res is None:
            dropped.append(t)
        elif res not in ENGLISH_INVENTORY:
            unmapped.append((t, res))
    assert not unmapped, f"tokens that do not map to the inventory: {unmapped}"
    # the dropped ones must be residual junk (things like '??', a stray 'ʲ', '1')
    assert len(dropped) < 12, f"too many tokens dropped: {dropped}"


def test_function_words_are_cited_in_their_strong_form():
    """The weak form is measured against the strong one (report §2): CMUdict lists
    the weak one first for a few words, the citation form must not."""
    assert dict_pronunciation("a")["arpabet"] == ["EY1"]
    assert dict_pronunciation("and")["arpabet"] == ["AE1", "N", "D"]
    assert dict_pronunciation("were")["arpabet"] == ["W", "ER1"]
    assert dict_pronunciation("her")["ipa"] == "hˈɝ"
    # "the" alternates ðə/ði with the next sound: its first entry stays
    assert dict_pronunciation("the")["arpabet"] == ["DH", "AH0"]
    # content words are untouched
    assert dict_pronunciation("better")["arpabet"] == ["B", "EH1", "T", "ER0"]
