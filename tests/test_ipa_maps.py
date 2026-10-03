"""Coverage of the phonetic mappings."""

from phonotrainer import ipa_maps


def test_arpabet_covers_all_of_cmudict():
    """All 39 ARPAbet phonemes used by CMUdict must have an IPA counterpart."""
    cmu_phones = {
        "AA", "AE", "AH", "AO", "AW", "AY", "B", "CH", "D", "DH", "EH", "ER",
        "EY", "F", "G", "HH", "IH", "IY", "JH", "K", "L", "M", "N", "NG",
        "OW", "OY", "P", "R", "S", "SH", "T", "TH", "UH", "UW", "V", "W",
        "Y", "Z", "ZH",
    }
    assert cmu_phones == set(ipa_maps.ARPABET_TO_IPA)


def test_arpabet_to_ipa_with_stress():
    # AH0 is schwa in CMUdict: "the" is /ðə/, not /ðʌ/
    assert ipa_maps.arpabet_to_ipa(["DH", "AH0", "Z"]) == ["ð", "ə", "z"]
    assert ipa_maps.arpabet_to_ipa(["DH", "AH1", "Z"]) == ["ð", "ʌ", "z"]
    assert ipa_maps.arpabet_to_ipa(["W", "AO1", "T", "ER0"]) == ["w", "ɔ", "t", "ɚ"]


def test_vowel_classification():
    assert ipa_maps.is_vowel("ə")
    assert ipa_maps.is_vowel("oʊ")
    assert ipa_maps.is_vowel("iː")
    assert not ipa_maps.is_vowel("tʃ")
    assert ipa_maps.is_schwa_like("ə")
    assert ipa_maps.is_schwa_like("ɚ")
    assert not ipa_maps.is_schwa_like("æ")
    assert ipa_maps.is_full_vowel("æ")
    assert not ipa_maps.is_full_vowel("ə")
    assert ipa_maps.is_consonant("ð")
    assert not ipa_maps.is_consonant("aɪ")


def test_normalize_for_panphon_known_tokens():
    import panphon

    ft = panphon.FeatureTable()
    # Every normalized symbol has to be segmentable by panphon.
    for token in ["ɚ", "ᵻ", "ɐ", "ɫ", "n̩", "oʊ", "aɪ", "ɜː", "ʔ", "ɾ", "d", "ə"]:
        norm = ipa_maps.normalize_for_panphon(token)
        assert ft.ipa_segs(norm), f"panphon cannot segment {token!r} → {norm!r}"


def test_stressed_er_is_the_stressed_r_vowel():
    assert ipa_maps.arpabet_to_ipa(["B", "ER1", "D"]) == ["b", "ɝ", "d"]
    assert ipa_maps.is_full_vowel("ɝ")
    assert ipa_maps.is_schwa_like("ɚ")


def test_narrow_symbols_are_diffable():
    from phonotrainer.diff import phone_cost

    # an unreleased stop is its stop to panphon, and inherits its native shifts
    assert phone_cost("t", "t̚") < 0.1
    assert phone_cost("t̚", "ʔ") == phone_cost("t", "ʔ")
    assert phone_cost("ɝ", "ɚ") < 0.3
    assert phone_cost("t", "ɾ̃") < 0.5
