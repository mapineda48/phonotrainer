"""Johnson-type reduction metrics: per analysis, pooled, and their contexts."""

import pytest
from conftest import mk_analysis, mk_analysis_word

from phonotrainer import metrics


def mk(words, rhythm=None):
    return {"meta": {}, "segments": [{"words": words, "rhythm": rhythm}]}


def test_deviation_segment_loss_and_syllable_loss_are_counted_per_word():
    m = metrics.compute(mk([
        mk_analysis_word("does", 0.0, "d ʌ z", "d ə z"),              # deviates
        mk_analysis_word("that", 0.4, "ð æ t", "ð æ"),                # loses a segment
        mk_analysis_word("work", 0.8, "w ɝ k", "w ɝ k"),              # identical
        mk_analysis_word("probably", 1.2, "p ɹ ɑː b ə b l i", "p ɹ ɑː b l i",
                         dict_ipa="pɹˈɑbəbli"),                       # loses a syllable
    ]))
    assert m["words"]["analyzed"] == 4
    assert m["deviate"] == {"count": 3, "of": 4, "pct": 75.0}
    assert m["segment_loss"] == {"count": 2, "of": 4, "pct": 50.0}
    assert m["syllable_loss"] == {"count": 1, "of": 4, "pct": 25.0}


def test_low_confidence_words_count_as_words_but_are_not_compared():
    m = metrics.compute(mk([
        mk_analysis_word("the", 0.0, "ð ə", "", low_confidence=True),
        mk_analysis_word("dog", 0.3, "d ɔ ɡ", "d ɔ ɡ"),
    ]))
    assert m["words"] == {"total": 2, "analyzed": 1, "low_confidence": 1,
                          "low_confidence_pct": 50.0}
    assert m["function_words"] == {"count": 1, "of": 2, "pct": 50.0}
    assert m["segment_loss"]["of"] == 1


def test_out_of_vocabulary_words_have_no_syllable_reference():
    # the "dictionary" form of an OOV word is a g2p guess
    m = metrics.compute(mk([
        mk_analysis_word("poopsikins", 0.0, "p uː p s ɪ k ɪ n z", "p uː p s k ɪ n z",
                         dict_ipa="pˈupsɪkɪnz", oov=True),
    ]))
    assert m["syllable_loss"]["of"] == 0 and m["syllable_loss"]["pct"] is None


def test_an_empty_analysis_has_no_percentages():
    m = metrics.compute({"segments": []})
    assert m["deviate"] == {"count": 0, "of": 0, "pct": None}
    assert m["labels_per_100_words"] == {} and m["rhythm"] is None


@pytest.mark.parametrize("phones, expected", [
    (["ə", "æ"], (2, 1)),
    (["ɨ", "ə̥", "iː"], (3, 2)),           # TIMIT ix and ax-h are reduced
    (["b", "ʌ", "ʔ", "n̩"], (2, 1)),       # a syllabic consonant is a reduced nucleus
    (["ŋ̍", "m̩"], (2, 2)),
    (["p", "iː", "p", "əl"], (2, 1)),      # espeak's əl
    (["s", "aɪə", "n", "s"], (2, 1)),      # sci-ence: one full nucleus, one reduced
    (["d", "ɪ", "d", "n", "t"], (2, 1)),   # didn't dɪdnt: a syllabic n in all but name
])
def test_nuclei_and_reduced_nuclei_survive_every_engine_alphabet(phones, expected):
    assert metrics.nucleus_counts(phones) == expected


@pytest.mark.parametrize("dict_ipa, syllables", [
    ("tɹˈaɪɪŋ", 2), ("kˈæmɚə", 3), ("bˈʌtən", 2), ("ˈɔɪl", 1), ("ðə", 1), ("", 0),
])
def test_dictionary_syllables_come_from_the_citation_ipa(dict_ipa, syllables):
    assert metrics.dict_syllables(dict_ipa) == syllables


@pytest.mark.parametrize("dict_ipa, flap, syllabic_n", [
    ("wˈɔtɚ", True, False),      # water
    ("pˈɑɹti", True, False),     # party: after /r/
    ("lˈɪtəl", True, False),     # little flaps before a syllabic l
    ("ətˈɑmɪk", False, False),   # atomic: the next vowel is stressed
    ("twˈɛnti", False, False),   # twenty: after /n/, not a vowel
    ("bˈʌtən", False, True),     # button: the glottal context, not the flap one
    ("mˈaʊntən", False, True),   # mountain
])
def test_flapping_and_glottal_contexts_come_from_the_citation_form(dict_ipa, flap, syllabic_n):
    assert metrics.flapping_context(dict_ipa) is flap
    assert metrics.syllabic_n_context(dict_ipa) is syllabic_n


def test_weak_forms_of_function_words_greedy_and_rescored():
    strong = {"weak_margin": -3.0}
    weak = {"weak_margin": 2.5}
    m = metrics.compute(mk([
        mk_analysis_word("of", 0.0, "ʌ v", "ə", form=weak),           # weak vowel
        mk_analysis_word("you", 0.3, "j uː", "j uː", form=strong),    # strong
        mk_analysis_word("him", 0.6, "h ɪ m", "ɪ m", form={"weak_margin": 0.1,
                                                            "h_drop_margin": 6.0}),
        mk_analysis_word("the", 0.9, "ð ə", "ð ə"),        # already weak in the canonical
        mk_analysis_word("dog", 1.2, "d ɔ ɡ", "d ə ɡ"),    # not a function word
    ]))
    assert m["weak_forms"]["greedy"] == {"count": 2, "of": 3, "pct": 66.7}
    assert m["weak_forms"]["variant"] == {"weak": 2, "strong": 1, "uncertain": 0,
                                          "count": 2, "of": 3, "pct": 66.7}


def test_without_rescoring_there_is_no_variant_figure():
    m = metrics.compute(mk([mk_analysis_word("of", 0.0, "ʌ v", "ə")]))
    assert m["weak_forms"]["variant"] is None


def test_flapping_rate_is_over_the_words_where_it_can_happen():
    m = metrics.compute(mk([
        mk_analysis_word("water", 0.0, "w ɔː t ɚ", "w ɔː ɾ ɚ", dict_ipa="wˈɔtɚ",
                         phenomena=["flapping"]),
        mk_analysis_word("city", 0.4, "s ɪ t i", "s ɪ t i", dict_ipa="sˈɪti"),
        mk_analysis_word("atomic", 0.8, "ɐ t ɑː m ɪ k", "ɐ t ɑː m ɪ k", dict_ipa="ətˈɑmɪk"),
    ]))
    assert m["flapping"] == {"count": 1, "of": 2, "pct": 50.0}


def test_final_t_before_a_vowel_is_classified_by_what_became_of_it():
    m = metrics.compute(mk([
        mk_analysis_word("get", 0.0, "ɡ ɛ t", "ɡ ɛ ʔ", dict_ipa="ɡˈɛt",
                         phenomena=["glottalization"]),
        mk_analysis_word("it", 0.3, "ɪ t", "ɪ ɾ", dict_ipa="ˈɪt", phenomena=["flapping"]),
        mk_analysis_word("out", 0.6, "aʊ t", "aʊ t", dict_ipa="ˈaʊt"),
        mk_analysis_word("of", 0.9, "ʌ v", "ə", dict_ipa="ˈʌv"),
        mk_analysis_word("what", 1.2, "w ʌ t", "w ʌ t̚", dict_ipa="wˈʌt"),
        mk_analysis_word("is", 1.5, "ɪ z", "ɪ z", dict_ipa="ˈɪz"),
        mk_analysis_word("that", 1.8, "ð æ t", "ð æ t", dict_ipa="ðˈæt"),  # last: no vowel after
    ]))
    assert m["final_t_prevocalic"] == {"released": 1, "flap": 1, "glottal": 1,
                                       "unreleased": 1, "other": 0, "of": 4}
    assert m["glottal_prevocalic"] == {"count": 1, "of": 4, "pct": 25.0}


def test_labels_are_counted_and_rated_per_hundred_words():
    m = metrics.compute(mk_analysis())
    assert m["labels"] == {"vowel_reduction": 1, "t_deletion": 1, "contraction_lex": 1}
    assert m["labels_per_100_words"]["t_deletion"] == 20.0


def test_rhythm_is_pooled_from_the_segments():
    r = {"npvi": 40.0, "varco": 50.0, "n_intervals": 4, "n_pairs": 3, "mean_ms": 200.0,
         "sd_ms": 100.0, "approximate": True, "method": "inter-nucleus intervals (CTC peaks)"}
    m = metrics.compute(mk([mk_analysis_word("dog", 0.0, "d ɔ ɡ", "d ɔ ɡ")], rhythm=r))
    assert m["rhythm"]["npvi"] == 40.0 and m["rhythm"]["approximate"] is True


def test_the_corpus_adds_up_tokens_instead_of_averaging_percentages():
    one = metrics.compute(mk([mk_analysis_word("that", 0.0, "ð æ t", "ð æ")]))
    four = metrics.compute(mk([mk_analysis_word(w, i * 0.4, "d ɔ ɡ", "d ɔ ɡ")
                               for i, w in enumerate(["dog", "dog", "dog"])]))
    pooled = metrics.aggregate([one, four, None])
    assert pooled["analyses"] == 2
    assert pooled["segment_loss"] == {"count": 1, "of": 4, "pct": 25.0}   # not 50 %
    assert pooled["words"]["total"] == 4
    assert pooled["labels_per_100_words"] == {}


def test_nothing_to_aggregate():
    assert metrics.aggregate([None]) is None
    assert metrics.aggregate_by_engine([None]) == {}


def test_the_engine_is_recorded_and_engines_are_pooled_apart():
    old = mk([mk_analysis_word("that", 0.0, "ð æ t", "ð æ")])        # no phone_engine
    new = mk([mk_analysis_word("that", 0.0, "ð æ t", "ð æ t̚")])
    new["meta"] = {"phone_engine": "timit61"}
    m_old, m_new = metrics.compute(old), metrics.compute(new)
    assert (m_old["engine"], m_new["engine"]) == ("espeak", "timit61")
    pooled = metrics.aggregate_by_engine([m_old, m_new, m_old])
    assert list(pooled) == ["timit61", "espeak"]                      # default first
    assert pooled["espeak"]["analyses"] == 2 and pooled["espeak"]["engine"] == "espeak"
    assert pooled["timit61"]["segment_loss"]["count"] == 0
    assert metrics.aggregate([m_old, m_new])["engine"] is None        # mixed: no engine


def test_the_function_word_list_is_the_shared_one():
    from phonotrainer import lexicon, prosody

    assert metrics.FUNCTION_WORDS is lexicon.FUNCTION_WORDS is prosody.FUNCTION_WORDS
    assert {"not", "all", "both", "themselves"} <= lexicon.FUNCTION_WORDS
    # negative contractions and wh-words keep their stress: content words
    assert lexicon.word_class("can't") == lexicon.word_class("Why?") == "content"


def test_every_reference_names_its_source():
    for name, ref in metrics.REFERENCE.items():
        assert name in metrics.METRIC_LABELS
        assert ref["source"] and ref["cite"] and ref["display"]
        assert ref["high"] is None or ref["high"] >= ref["low"]


def test_the_rules_generation_is_recorded_and_a_mixed_pool_is_flagged():
    """Old analyses were labeled by older rules (no t_unreleased, glottalization
    against espeak's own ʔ…): their label figures must not pass silently for the
    current rules' when both sit in the same engine pool."""
    from phonotrainer import phenomena

    current = mk_analysis()
    current["meta"]["rules_version"] = phenomena.RULES_VERSION
    legacy = mk_analysis()
    legacy["meta"].pop("rules_version", None)

    now, then = metrics.compute(current), metrics.compute(legacy)
    assert now["rules"] == phenomena.RULES_VERSION and then["rules"] is None

    pool = metrics.aggregate([now, then])
    assert pool["mixed_rules"] is True and pool["rules"] == [None, phenomena.RULES_VERSION]
    assert metrics.aggregate([now, now])["mixed_rules"] is False
