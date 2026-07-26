"""Cobertura de mapeos fonéticos."""

from phonotrainer import ipa_maps


def test_arpabet_cubre_cmudict_completo():
    """Los 39 fonemas ARPAbet de CMUdict deben tener IPA."""
    cmu_phones = {
        "AA", "AE", "AH", "AO", "AW", "AY", "B", "CH", "D", "DH", "EH", "ER",
        "EY", "F", "G", "HH", "IH", "IY", "JH", "K", "L", "M", "N", "NG",
        "OW", "OY", "P", "R", "S", "SH", "T", "TH", "UH", "UW", "V", "W",
        "Y", "Z", "ZH",
    }
    assert cmu_phones == set(ipa_maps.ARPABET_TO_IPA)


def test_arpabet_to_ipa_con_stress():
    assert ipa_maps.arpabet_to_ipa(["DH", "AH0", "Z"]) == ["ð", "ʌ", "z"]
    assert ipa_maps.arpabet_to_ipa(["W", "AO1", "T", "ER0"]) == ["w", "ɔ", "t", "ɚ"]


def test_clasificacion_vocales():
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


def test_normalize_for_panphon_conocidos():
    import panphon

    ft = panphon.FeatureTable()
    # Todo símbolo normalizado debe ser segmentable por panphon.
    for token in ["ɚ", "ᵻ", "ɐ", "ɫ", "n̩", "oʊ", "aɪ", "ɜː", "ʔ", "ɾ", "d", "ə"]:
        norm = ipa_maps.normalize_for_panphon(token)
        assert ft.ipa_segs(norm), f"panphon no segmenta {token!r} → {norm!r}"
