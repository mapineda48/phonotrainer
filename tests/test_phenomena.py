"""Synthetic tests, one per connected-speech phenomenon rule (TDD, Phase 5)."""

from conftest import mk_phones, mk_word

from phonotrainer import phenomena


def detect_one(word, next_word=None, first_in_segment=True):
    words = [word] + ([next_word] if next_word else [])
    out = phenomena.detect(words, first_in_segment=first_in_segment)
    return out[0]


def test_vowel_reduction():
    # does → dəz
    w = detect_one(mk_word("does", "d ʌ z", "d ə z"))
    assert "vowel_reduction" in w["phenomena"]


def test_vowel_reduction_does_not_fire_when_already_schwa():
    w = detect_one(mk_word("the", "ð ə", "ð ə"))
    assert "vowel_reduction" not in w["phenomena"]


def test_t_deletion_final():
    # that → ðæ
    w = detect_one(mk_word("that", "ð æ t", "ð æ"))
    assert "t_deletion" in w["phenomena"]


def test_glottalization():
    # button → bʌʔn̩
    w = detect_one(mk_word("button", "b ʌ t n̩", "b ʌ ʔ n̩"))
    assert "glottalization" in w["phenomena"]


def test_th_stopping():
    # that → dæt
    w = detect_one(mk_word("that", "ð æ t", "d æ t"))
    assert "th_stopping" in w["phenomena"]
    w = detect_one(mk_word("think", "θ ɪ ŋ k", "t ɪ ŋ k"))
    assert "th_stopping" in w["phenomena"]


def test_flapping_sub():
    # "water" with a canonical /t/
    w = detect_one(mk_word("water", "w ɔ t ɚ", "w ɔ ɾ ɚ"))
    assert "flapping" in w["phenomena"]


def test_flapping_match_when_espeak_is_already_flapped():
    # espeak en-us already emits ɾ in "water": a ɾ↔ɾ match is still flapping
    w = detect_one(mk_word("water", "w ɔ ɾ ɚ", "w ɔ ɾ ɚ"))
    assert "flapping" in w["phenomena"]


def test_palatalization_across_word_boundary():
    # got you → gotcha
    got = mk_word("got", "ɡ ɑ t", "ɡ ɑ tʃ", t0=0.0)
    you = mk_word("you", "j u", "ə", t0=0.20)
    out = phenomena.detect([got, you], first_in_segment=True)
    assert "palatalization" in out[0]["phenomena"]


def test_elision_syllable():
    # probably → probli (3 nuclei → 2)
    w = detect_one(mk_word("probably", "p ɹ ɑ b ə b l i", "p ɹ ɑ b l i"))
    assert "elision_syllable" in w["phenomena"]


def test_linking_consonant_to_vowel():
    # does it → does‿it (no temporal gap)
    does = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    it = mk_word("it", "ɪ t", "ɪ t", t0=0.18)
    out = phenomena.detect([does, it], first_in_segment=True)
    assert out[0]["boundary_link_next"] is True
    assert "linking" in out[0]["phenomena"]


def test_linking_does_not_fire_across_a_pause():
    does = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    it = mk_word("it", "ɪ t", "ɪ t", t0=1.5)
    out = phenomena.detect([does, it], first_in_segment=True)
    assert out[0]["boundary_link_next"] is False


def test_h_dropping():
    # tell him → tell 'im ; "him" does not start the segment
    tell = mk_word("tell", "t ɛ l", "t ɛ l", t0=0.0)
    him = mk_word("him", "h ɪ m", "ɪ m", t0=0.2)
    out = phenomena.detect([tell, him], first_in_segment=True)
    assert "h_dropping" in out[1]["phenomena"]


def test_h_dropping_does_not_fire_utterance_initially():
    him = mk_word("him", "h ɪ m", "ɪ m", t0=0.0)
    out = phenomena.detect([him], first_in_segment=True)
    assert "h_dropping" not in out[0]["phenomena"]


def test_contraction_lex_from_the_text():
    # Whisper already wrote "gonna"
    w = detect_one(mk_word("gonna", "ɡ ə n ə", "ɡ ə n ə"))
    assert "contraction_lex" in w["phenomena"]
    assert w["lexical_form"] == "gonna"


def test_contraction_lex_from_the_phones():
    # the text says "want to" but the real phones spell out wanna
    want = mk_word("want", "w ɑ n t", "w ɑ n", t0=0.0)
    to = mk_word("to", "t u", "ə", t0=0.25)
    out = phenomena.detect([want, to], first_in_segment=True)
    assert "contraction_lex" in out[0]["phenomena"]
    assert out[0]["lexical_form"] == "wanna"


def test_contraction_lex_does_not_fire_without_reduction():
    # "want to" pronounced in full is NOT wanna
    want = mk_word("want", "w ɑ n t", "w ɑ n t", t0=0.0)
    to = mk_word("to", "t u", "t u", t0=0.30)
    out = phenomena.detect([want, to], first_in_segment=True)
    assert "contraction_lex" not in out[0]["phenomena"]


def test_summary_counts():
    w1 = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    w2 = mk_word("that", "ð æ t", "d æ", t0=0.3)
    out = phenomena.detect([w1, w2], first_in_segment=True)
    counts = phenomena.count_phenomena([{"words": out}])
    assert counts["vowel_reduction"] >= 1
    assert counts["th_stopping"] >= 1
    assert counts["t_deletion"] >= 1


# --- IMPROVEMENT 2: monophthongization ---------------------------------------

def test_monophthongization_by_diphthong():
    cases = [
        ("I", "aɪ", "æ"),        # aɪ→æ
        ("I'll", "aɪ l", "ɑː l"),  # aɪ→ɑː
        ("know", "n oʊ", "n ɔ"),   # oʊ→ɔ
        ("cupcakes", "k ʌ p k eɪ k s", "k ʌ p k ɪ k s"),  # eɪ→ɪ
        ("out", "aʊ t", "a t"),    # aʊ→a
        ("boy", "b ɔɪ", "b ɔ"),    # ɔɪ→ɔ
    ]
    for text, canon, real in cases:
        w = detect_one(mk_word(text, canon, real))
        assert "monophthongization" in w["phenomena"], f"{text}: {w['phenomena']}"


def test_monophthongization_does_not_fire_for_a_distant_vowel():
    # aɪ→u is not a monophthongization toward the first element
    w = detect_one(mk_word("I", "aɪ", "u"))
    assert "monophthongization" not in w["phenomena"]


def test_reduction_takes_precedence_over_monophthongization():
    # the article "a" /eɪ/→[ɐ]: weak form = vowel_reduction, do NOT double up with mono
    w = detect_one(mk_word("a", "eɪ", "ɐ"))
    assert "vowel_reduction" in w["phenomena"]
    assert "monophthongization" not in w["phenomena"]


# --- IMPROVEMENT 3: word_elision ---------------------------------------------

def test_word_elision_when_realized_is_empty():
    w = detect_one(mk_word("and", "æ n d", ""))
    assert "word_elision" in w["phenomena"]
    assert "elision_syllable" not in w["phenomena"]
    assert w["low_confidence"] is True


def test_word_elision_on_low_coverage():
    # a 0.30 s word with a single 0.06 s real phone (20%)
    w = detect_one(mk_word("to", "t uː", "ə", dur=0.15, real_dur=0.06))
    assert "word_elision" in w["phenomena"]
    assert w["low_confidence"] is True


def test_no_word_elision_with_normal_coverage():
    w = detect_one(mk_word("does", "d ʌ z", "d ə z"))
    assert "word_elision" not in w["phenomena"]
    assert w["low_confidence"] is False


def test_elision_syllable_still_exists():
    # a syllable elision with healthy coverage does not turn into word_elision
    w = detect_one(mk_word("probably", "p ɹ ɑ b ə b l i", "p ɹ ɑ b l i"))
    assert "elision_syllable" in w["phenomena"]
    assert "word_elision" not in w["phenomena"]


# --- IMPROVEMENT 4: per-rule reference ---------------------------------------

def test_rule_reference_covers_every_label():
    all_labels = {
        "vowel_reduction", "monophthongization", "t_deletion", "glottalization",
        "th_stopping", "flapping", "palatalization", "elision_syllable",
        "word_elision", "linking", "h_dropping", "contraction_lex",
    }
    assert set(phenomena.RULE_REFERENCE) == all_labels
    assert all(v in {"dict", "aligned"} for v in phenomena.RULE_REFERENCE.values())


def test_flapping_match_is_validated_against_the_dictionary():
    # espeak canonical already flapped: only flapping if the dictionary has T/D
    w = detect_one(mk_word("water", "w ɔ ɾ ɚ", "w ɔ ɾ ɚ",
                           dict_arpabet=["W", "AO1", "T", "ER0"]))
    assert "flapping" in w["phenomena"]
    # hypothetical word whose dictionary form has no t/d: the ɾ↔ɾ match is not flapping
    w = detect_one(mk_word("xler", "ɾ ɚ", "ɾ ɚ", dict_arpabet=["R", "ER0"]))
    assert "flapping" not in w["phenomena"]


def test_no_word_elision_with_spiky_ctc_spans():
    # Validation bug: CTC spans are ~30 ms spikes; coverage is measured by EXTENT
    # (first start → last end), not by summing durations.
    word = {
        "word": "business", "start": 0.0, "end": 0.5,
        "canonical": mk_phones("b ɪ z n ə s", dur=0.083),
        "real": [
            {"phone": "b", "start": 0.02, "end": 0.05, "score": 1.0},
            {"phone": "z", "start": 0.20, "end": 0.23, "score": 1.0},
            {"phone": "s", "start": 0.42, "end": 0.45, "score": 1.0},
        ],
    }
    w = detect_one(word)
    assert "word_elision" not in w["phenomena"]
    assert w["low_confidence"] is False
