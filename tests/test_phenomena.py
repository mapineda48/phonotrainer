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


def detect_seq(*specs):
    """Consecutive words: (text, canonical, real[, mk_word kwargs])."""
    words, t = [], 0.0
    for text, canonical, real, *extra in specs:
        w = mk_word(text, canonical, real, t0=t, **(extra[0] if extra else {}))
        words.append(w)
        t = w["end"] + 0.02
    return phenomena.detect(words, first_in_segment=True)


def test_t_deletion_in_a_cluster():
    # next day → nex' day, don't → don', and → an'
    for text, canon, real in [("next", "n ɛ k s t", "n ɛ k s"),
                              ("don't", "d oʊ n t", "d oʊ n"),
                              ("and", "æ n d", "æ n")]:
        w = detect_one(mk_word(text, canon, real))
        assert "t_deletion" in w["phenomena"], text
        assert "t_unreleased" not in w["phenomena"], text


def test_t_unreleased_after_a_vowel():
    # that → ðæʔ / ðæt̚: silence to the recognizer, but not a deletion
    w = detect_one(mk_word("that", "ð æ t", "ð æ"))
    assert "t_unreleased" in w["phenomena"]
    assert "t_deletion" not in w["phenomena"]


def test_glottalization():
    # button → bʌʔn̩ against a canonical /t/
    w = detect_one(mk_word("button", "b ʌ t n̩", "b ʌ ʔ n̩"))
    assert "glottalization" in w["phenomena"]


def test_glottalization_when_espeak_is_already_glottalized():
    # espeak en-us writes button as bʌʔn̩: a ʔ↔ʔ match is still glottalization,
    # because the citation form has the /t/
    w = detect_one(mk_word("button", "b ʌ ʔ n̩", "b ʌ ʔ n̩",
                           dict_arpabet=["B", "AH1", "T", "AH0", "N"]))
    assert "glottalization" in w["phenomena"]


def test_glottalization_veto_without_a_dictionary_t():
    # uh-oh has a glottal stop of its own: there is no /t/ to glottalize
    w = detect_one(mk_word("uh-oh", "ʌ ʔ oʊ", "ʌ ʔ oʊ", dict_arpabet=["AH1", "OW2"]))
    assert "glottalization" not in w["phenomena"]


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
    w = detect_one(mk_word("probably", "p ɹ ɑː b ə b l i", "p ɹ ɑː b l i",
                           dict_arpabet=["P", "R", "AA1", "B", "AH0", "B", "L", "IY0"]))
    assert "elision_syllable" in w["phenomena"]


def test_elision_syllable_against_the_dictionary():
    # family → famly: the aligner expected the vowel and nobody said it
    w = detect_one(mk_word("family", "f æ m ɪ l i", "f æ m l i",
                           dict_arpabet=["F", "AE1", "M", "AH0", "L", "IY0"]))
    assert "elision_syllable" in w["phenomena"]
    w = detect_one(mk_word("family", "f æ m ɪ l i", "f æ m ɪ l i",
                           dict_arpabet=["F", "AE1", "M", "AH0", "L", "IY0"]))
    assert "elision_syllable" not in w["phenomena"]


def test_elision_syllable_when_espeak_is_already_syncopated():
    # espeak writes camera as kæmɹə and different as dɪfɹənt: matching espeak is
    # still one syllable short of the citation form
    cases = [
        ("camera", "k æ m ɹ ə", ["K", "AE1", "M", "ER0", "AH0"]),
        ("different", "d ɪ f ɹ ə n t", ["D", "IH1", "F", "ER0", "AH0", "N", "T"]),
    ]
    for text, canon, arpa in cases:
        w = detect_one(mk_word(text, canon, canon, dict_arpabet=arpa))
        assert "elision_syllable" in w["phenomena"], text
    # ...but the full form is not an elision
    w = detect_one(mk_word("camera", "k æ m ɹ ə", "k æ m ɚ ɹ ə",
                           dict_arpabet=["K", "AE1", "M", "ER0", "AH0"]))
    assert "elision_syllable" not in w["phenomena"]


def test_elision_syllable_counts_syllabic_consonants_and_merged_tokens():
    # a syllabic n/l, or a single espeak token holding two syllables, is not a
    # lost syllable
    cases = [
        ("button", "b ʌ ʔ n̩", "b ʌ ʔ n̩", ["B", "AH1", "T", "AH0", "N"]),
        ("button", "b ʌ ʔ n̩", "b ʌ t n", ["B", "AH1", "T", "AH0", "N"]),
        ("didn't", "d ɪ d n t", "d ɪ d n t", ["D", "IH1", "D", "AH0", "N", "T"]),
        ("people", "p iː p əl", "p iː p l", ["P", "IY1", "P", "AH0", "L"]),
        ("idea", "aɪ d iə", "aɪ d iə", ["AY0", "D", "IY1", "AH0"]),
        ("science", "s aɪə n s", "s aɪə n s", ["S", "AY1", "AH0", "N", "S"]),
        # they're: espeak has more nuclei than the dictionary; dropping the extra
        # one is not an elision
        ("they're", "ð eɪ ɚ", "ð eɪ", ["DH", "EH1", "R"]),
    ]
    for text, canon, real, arpa in cases:
        w = detect_one(mk_word(text, canon, real, dict_arpabet=arpa))
        assert "elision_syllable" not in w["phenomena"], f"{text} [{real}]"


def test_nuclei_count():
    def n(spaced):
        return phenomena._nuclei(mk_phones(spaced))

    assert n("d ɪ d n t") == 2        # didn't: syllabic n
    assert n("f æ m l i") == 2        # family syncopated: l is an onset
    assert n("aɪ d iə") == 3          # idea: iə is two syllables...
    assert n("θ iə ɹ i") == 2         # ...except before ɹ (theory)
    assert n("f aɪɚ") == 2            # fire
    assert n("k ɑːɹ n v əl") == 2     # carnival syncopated: n is an onset cluster


def test_linking_consonant_to_vowel():
    # does it → does‿it (no temporal gap)
    does = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    it = mk_word("it", "ɪ t", "ɪ t", t0=0.18)
    out = phenomena.detect([does, it], first_in_segment=True)
    assert out[0]["boundary_link_next"] is True
    assert out[0]["boundary_link_type"] == "consonant"
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


def test_an_h_heard_as_a_glottal_attack_is_dropped():
    # in his car → in [ʔɪz] car: the h is gone and the vowel starts with a glottal
    # stop; that is h_dropping, never a glottalized /t/, and it blocks linking
    in_ = mk_word("in", "ɪ n", "ɪ n", t0=0.0)
    his = mk_word("his", "h ɪ z", "ʔ ɪ z", t0=0.13)
    out = phenomena.detect([in_, his], first_in_segment=True, narrow=True)
    assert "h_dropping" in out[1]["phenomena"]
    assert "glottalization" not in out[1]["phenomena"]
    assert out[0]["boundary_link_next"] is False
    # same conditions as a deleted h: not utterance-initially, not on other words
    assert "h_dropping" not in phenomena.detect([his], first_in_segment=True)[0]["phenomena"]
    hat = mk_word("hat", "h æ t", "ʔ æ t", t0=0.13)
    assert "h_dropping" not in phenomena.detect([in_, hat])[1]["phenomena"]


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


def test_contraction_lex_needs_the_forms_own_signature():
    # a reduced "to" [tə] is everywhere: it does not make "want to" wanna, nor
    # "have to" hafta, nor "going to" gonna
    cases = [
        ("want", "w ɔ n t", "w ɔ n t", "to", "t uː", "t ə"),
        ("have", "h æ v", "h æ v", "to", "t uː", "t ə"),
        ("going", "ɡ oʊ ɪ ŋ", "ɡ oʊ ɪ ŋ", "to", "t uː", "t ə"),
        ("kind", "k aɪ n d", "k aɪ n", "of", "ʌ v", "ʌ v"),
        ("let", "l ɛ t", "l ɛ t", "me", "m iː", "m iː"),
        ("should", "ʃ ʊ d", "ʃ ʊ d", "have", "h æ v", "ə v"),
        ("don't", "d oʊ n t", "d oʊ n t", "know", "n oʊ", "n oʊ"),
        ("out", "aʊ t", "aʊ ɾ", "of", "ʌ v", "ʌ v"),
    ]
    for a, ac, ar, b, bc, br in cases:
        out = detect_seq((a, ac, ar), (b, bc, br), ("go", "ɡ oʊ", "ɡ oʊ"))
        assert "contraction_lex" not in out[0]["phenomena"], f"{a} [{ar}] {b} [{br}]"


def test_contraction_lex_signatures():
    cases = [
        ("want", "w ɔ n t", "w ɔ", "to", "t uː", "ɾ ə", "wanna"),    # nasal flap
        ("going", "ɡ oʊ ɪ ŋ", "ɡ ʌ n", "to", "t uː", "ə", "gonna"),
        ("got", "ɡ ɑː t", "ɡ ɑː ɾ", "to", "t uː", "ə", "gotta"),
        ("have", "h æ v", "h æ f", "to", "t uː", "t ə", "hafta"),
        ("got", "ɡ ɑː t", "ɡ ɑː tʃ", "you", "j uː", "ə", "gotcha"),
        ("let", "l ɛ t", "l ɛ", "me", "m iː", "m iː", "lemme"),
        ("kind", "k aɪ n d", "k aɪ n d", "of", "ʌ v", "ə", "kinda"),
        ("out", "aʊ t", "aʊ ɾ", "of", "ʌ v", "ə", "outta"),
        ("should", "ʃ ʊ d", "ʃ ʊ d", "have", "h æ v", "ə", "shoulda"),
        ("don't", "d oʊ n t", "d oʊ n", "know", "n oʊ", "oʊ", "dunno"),
    ]
    for a, ac, ar, b, bc, br, form in cases:
        out = detect_seq((a, ac, ar), (b, bc, br), ("go", "ɡ oʊ", "ɡ oʊ"))
        assert out[0]["lexical_form"] == form, f"{a} [{ar}] {b} [{br}]"


def test_contraction_lex_palatal_forms_trust_the_boundary_rule():
    # don't [dʒ] + you [uː]: the vowel of "don't" was not heard, the dʒ was
    out = detect_seq(("don't", "d oʊ n t", "dʒ"), ("you", "j uː", "uː"))
    assert "palatalization" in out[0]["phenomena"]
    assert out[0]["lexical_form"] == "dontcha"


def test_contraction_lex_whaddya():
    out = detect_seq(("what", "w ʌ t", "w ʌ"), ("do", "d uː", "ɾ ə"),
                     ("you", "j uː", "j ə"))
    assert out[0]["lexical_form"] == "whaddya"


def test_gonna_is_future_plus_verb_only():
    # "going to the gym" is motion: it never contracts, whatever the phones say
    for after in ["the", "my", "them", "Denver"]:
        out = detect_seq(("going", "ɡ oʊ ɪ ŋ", "ɡ ʌ n"), ("to", "t uː", "ə"),
                         (after, "ð ə", "ð ə"))
        assert "contraction_lex" not in out[0]["phenomena"], after
    out = detect_seq(("going", "ɡ oʊ ɪ ŋ", "ɡ ʌ n"), ("to", "t uː", "ə"),
                     ("be", "b iː", "b iː"))
    assert out[0]["lexical_form"] == "gonna"


def test_contraction_lex_lexicon_hygiene():
    # Whisper writes "'em" and "'cause"; a bare "cause" is also the noun
    w = detect_one(mk_word("'em", "ɛ m", "ə m"))
    assert w["lexical_expansion"] == "them"
    w = detect_one(mk_word("'cause", "k ɔː z", "k ə z"))
    assert w["lexical_expansion"] == "because"
    w = detect_one(mk_word("cause", "k ɔː z", "k ɔː z"))
    assert "contraction_lex" not in w["phenomena"]


def test_summary_counts():
    w1 = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    w2 = mk_word("that", "ð æ t", "d æ", t0=0.3)
    out = phenomena.detect([w1, w2], first_in_segment=True)
    counts = phenomena.count_phenomena([{"words": out}])
    assert counts["vowel_reduction"] >= 1
    assert counts["th_stopping"] >= 1
    assert counts["t_unreleased"] >= 1


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
        "vowel_reduction", "monophthongization", "t_deletion", "t_unreleased",
        "glottalization",
        "th_stopping", "flapping", "palatalization", "elision_syllable",
        "word_elision", "linking", "h_dropping", "contraction_lex",
        "place_assimilation", "nt_reduction", "function_elision",
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


# --- narrow engine (timit61): the canonical is the citation form ---------------

def detect_narrow(word, next_word=None):
    words = [word] + ([next_word] if next_word else [])
    return phenomena.detect(words, first_in_segment=True, narrow=True)[0]


def test_unreleased_t_is_seen_by_the_narrow_engine():
    # that [ðæt̚]: the closure is there, the burst never comes
    w = detect_narrow(mk_word("that", "ð æ t", "ð æ t̚", dict_arpabet=["DH", "AE1", "T"]))
    assert "t_unreleased" in w["phenomena"]
    assert "t_deletion" not in w["phenomena"]


def test_unreleased_is_a_word_final_label():
    # didn't [dɪd̚n̩t̚]: the /d/ releases through the nose, only the final /t/ counts
    w = detect_narrow(mk_word("didn't", "d ɪ d ə n t", "d ɪ d̚ n̩ t̚",
                              dict_arpabet=["D", "IH1", "D", "AH0", "N", "T"]))
    assert "t_unreleased" in w["phenomena"]
    w = detect_narrow(mk_word("didn't", "d ɪ d ə n t", "d ɪ d̚ n̩ t",
                              dict_arpabet=["D", "IH1", "D", "AH0", "N", "T"]))
    assert "t_unreleased" not in w["phenomena"]


def test_a_final_t_with_no_closure_is_deleted_for_the_narrow_engine():
    # the espeak engine cannot tell [ðæ] from [ðæt̚]; a recognizer that writes t̚ can
    word = mk_word("that", "ð æ t", "ð æ", dict_arpabet=["DH", "AE1", "T"])
    assert "t_deletion" in detect_narrow(dict(word))["phenomena"]
    assert "t_unreleased" in detect_one(dict(word))["phenomena"]


def test_nasal_flap_swallowing_nt_is_nt_reduction_not_flapping():
    # winter wɪɾ̃ɚ: the /nt/ becomes one nasal tap (the "winner" of the report);
    # one label per /t/, and flapping stays the oral flap after a vowel
    w = detect_narrow(mk_word("winter", "w ɪ n t ɚ", "w ɪ ɾ̃ ɚ",
                              dict_arpabet=["W", "IH1", "N", "T", "ER0"]))
    assert "nt_reduction" in w["phenomena"]
    assert "flapping" not in w["phenomena"]


def test_a_word_initial_t_does_not_glottalize():
    # to [əʔ]: the ʔ is the glottal onset of the next vowel-initial word
    w = detect_narrow(mk_word("to", "t u", "ə ʔ", dict_arpabet=["T", "UW1"]))
    assert "glottalization" not in w["phenomena"]
    w = detect_narrow(mk_word("Cartman", "k ɑ ɹ t m ə n", "k ɑ ɹ ʔ m ə n",
                              dict_arpabet=["K", "AA1", "R", "T", "M", "AH0", "N"]))
    assert "glottalization" in w["phenomena"]


def test_reduced_high_vowel_is_not_a_weak_form_of_a_high_vowel():
    # TIMIT's ix: "is" [ᵻz] is the same small step as [ɪ]; "to" [tᵻ] is a weak form
    w = detect_narrow(mk_word("is", "ɪ z", "ᵻ z", dict_arpabet=["IH1", "Z"]))
    assert "vowel_reduction" not in w["phenomena"]
    w = detect_narrow(mk_word("to", "t u", "t ᵻ", dict_arpabet=["T", "UW1"]))
    assert "vowel_reduction" in w["phenomena"]


def test_stressed_r_vowel_reduces():
    # were: citation /wɝ/, weak [wɚ]
    w = detect_narrow(mk_word("were", "w ɝ", "w ɚ", dict_arpabet=["W", "ER1"]))
    assert "vowel_reduction" in w["phenomena"]


def test_syllabic_nasal_keeps_its_syllable():
    # button [bʌʔn̩] against the citation form /bʌtən/: glottalized, nothing lost
    w = detect_narrow(mk_word("button", "b ʌ t ə n", "b ʌ ʔ n̩",
                              dict_arpabet=["B", "AH1", "T", "AH0", "N"]))
    assert "glottalization" in w["phenomena"]
    assert "elision_syllable" not in w["phenomena"]


def test_gonna_signature_with_a_nasal_flap():
    # gonna [ɡʌɾ̃ə]: the nasal tap is where the /t/ of "to" went
    out = phenomena.detect([
        mk_word("going", "ɡ oʊ ɪ ŋ", "ɡ ʌ ɾ̃", t0=0.0),
        mk_word("to", "t u", "ə", t0=0.3),
        mk_word("be", "b i", "b i", t0=0.5),
    ], narrow=True)
    assert out[0]["lexical_form"] == "gonna"


# --- linking subtypes, place assimilation, nt, function-word elision ----------

def chain(*specs, narrow=False, gap=0.02):
    """Consecutive words (text, canonical, real[, mk_word kwargs]) `gap` s apart."""
    words, t = [], 0.0
    for text, canonical, real, *extra in specs:
        w = mk_word(text, canonical, real, t0=t, **(extra[0] if extra else {}))
        words.append(w)
        t = w["end"] + gap
    return phenomena.detect(words, first_in_segment=True, narrow=narrow)


def test_linking_r_from_a_rhotic_ending():
    # far away → fa-ra-way: the r-colored vowel carries the link
    out = chain(("far", "f ɑːɹ", "f ɑːɹ"), ("away", "ə w eɪ", "ə w eɪ"))
    assert out[0]["boundary_link_type"] == "r"
    assert "linking" in out[0]["phenomena"]


def test_linking_glides_after_a_vowel():
    # go on → go‿won, he is → he‿yis: the vowel ends in the glide it links with
    out = chain(("go", "ɡ oʊ", "ɡ oʊ"), ("on", "ɑ n", "ɑ n"))
    assert out[0]["boundary_link_type"] == "glide_w"
    out = chain(("he", "h i", "h i"), ("is", "ɪ z", "ɪ z"), narrow=True)
    assert out[0]["boundary_link_type"] == "glide_j"
    # a vowel with no glide to offer (ə, ɑ) meets the next one in a hiatus
    out = chain(("a", "ə", "ə"), ("apple", "æ p ə l", "æ p ə l"))
    assert out[0]["boundary_link_type"] is None


def test_linking_glide_heard_at_the_start_of_the_next_word():
    # go and → ɡoʊ wən: the recognizer wrote the glide into the next word
    out = chain(("go", "ɡ oʊ", "ɡ oʊ"), ("and", "æ n d", "w ə n"))
    assert out[0]["boundary_link_type"] == "glide_w"
    # a w after an r-colored vowel is noise, not a glide
    out = chain(("for", "f ɔːɹ", "f ɔːɹ"),
                ("insurance", "ɪ n ʃ ʊɹ ə n s", "w ɪ n ʃ ʊɹ ə n s"))
    assert out[0]["boundary_link_type"] != "glide_w"


def test_linking_when_the_consonant_moved_into_the_next_word():
    # an apple → a napple: the n was bucketed into "apple"
    out = chain(("an", "æ n", "æ"), ("apple", "æ p ə l", "n æ p ə l"))
    assert out[0]["boundary_link_type"] == "consonant"
    # door over → dɔːɹ ɹoʊvɚ
    out = chain(("door", "d ɔːɹ", "d ɔːɹ"), ("over", "oʊ v ɚ", "ɹ oʊ v ɚ"))
    assert out[0]["boundary_link_type"] == "r"


def test_no_linking_through_a_glottal_onset_a_held_stop_or_a_sentence_end():
    out = chain(("the", "ð ə", "ð i"), ("only", "oʊ n l i", "ʔ oʊ n l i"), narrow=True)
    assert out[0]["boundary_link_next"] is False           # [ʔoʊnli]: hard attack
    out = chain(("that", "ð æ t", "ð æ t̚"), ("is", "ɪ z", "ɪ z"), narrow=True)
    assert out[0]["boundary_link_next"] is False           # [ðæt̚]: never released
    out = chain(("Stop.", "s t ɑ p", "s t ɑ p"), ("It", "ɪ t", "ɪ t"))
    assert out[0]["boundary_link_next"] is False


def test_the_before_a_vowel_links_with_a_j_glide():
    # the other → ðə jʌðɚ: "the" turns into [ði] before a vowel
    out = chain(("the", "ð ə", "ð ə"), ("other", "ʌ ð ɚ", "j ʌ ð ɚ"))
    assert out[0]["boundary_link_type"] == "glide_j"


def test_place_assimilation_of_a_final_alveolar():
    # ten bucks → tem bucks, in case → ing case, that boy → thap boy
    out = chain(("ten", "t ɛ n", "t ɛ m"), ("bucks", "b ʌ k s", "b ʌ k s"))
    assert "place_assimilation" in out[0]["phenomena"]
    out = chain(("in", "ɪ n", "ɪ ŋ"), ("case", "k eɪ s", "k eɪ s"))
    assert "place_assimilation" in out[0]["phenomena"]
    out = chain(("that", "ð æ t", "ð æ p̚"), ("boy", "b ɔɪ", "b ɔɪ"), narrow=True)
    assert "place_assimilation" in out[0]["phenomena"]


def test_place_assimilation_needs_the_right_place_and_a_real_substitution():
    # n → m before a velar is not assimilation
    out = chain(("in", "ɪ n", "ɪ m"), ("case", "k eɪ s", "k eɪ s"))
    assert "place_assimilation" not in out[0]["phenomena"]
    # get [ɡɛt̚ m] + my [aɪ]: the m of "my" was bucketed after an intact t
    out = chain(("get", "ɡ ɛ t", "ɡ ɛ t̚ m"), ("my", "m aɪ", "aɪ"), narrow=True)
    assert "place_assimilation" not in out[0]["phenomena"]


def test_attraction_leaves_a_final_place_assimilation_alone():
    from phonotrainer.phones_real import attract_to_canonical

    real = attract_to_canonical(mk_phones("ð æ p"), mk_phones("ð æ t"))
    assert [p["phone"] for p in real] == ["ð", "æ", "p"]


def test_nt_reduction_inside_the_word():
    # twenty → twenny (the t is gone), plenty [plɛnɾi] (espeak's nasal flap)
    w = detect_one(mk_word("twenty", "t w ɛ n t i", "t w ɛ n i"))
    assert "nt_reduction" in w["phenomena"]
    w = detect_one(mk_word("plenty", "p l ɛ n t i", "p l ɛ n ɾ i",
                           dict_arpabet=["P", "L", "EH1", "N", "T", "IY0"]))
    assert "nt_reduction" in w["phenomena"] and "flapping" not in w["phenomena"]
    # a released /nt/ is not reduced; an oral flap after a vowel is still flapping
    w = detect_one(mk_word("twenty", "t w ɛ n t i", "t w ɛ n t i"))
    assert "nt_reduction" not in w["phenomena"]
    w = detect_one(mk_word("water", "w ɔ t ɚ", "w ɔ ɾ ɚ",
                           dict_arpabet=["W", "AO1", "T", "ER0"]))
    assert "flapping" in w["phenomena"] and "nt_reduction" not in w["phenomena"]


def test_nt_reduction_across_the_boundary_replaces_t_deletion():
    # want it → wɑɾ̃ɪt, and I → æn aɪ: before a vowel this is the /nt/, not a cluster
    out = chain(("want", "w ɑ n t", "w ɑ ɾ̃"), ("it", "ɪ t", "ɪ t"), narrow=True)
    assert "nt_reduction" in out[0]["phenomena"]
    assert "t_deletion" not in out[0]["phenomena"]
    out = chain(("and", "æ n d", "æ n"), ("I", "aɪ", "aɪ"))
    assert "nt_reduction" in out[0]["phenomena"]
    # before a consonant the lost /d/ is still a cluster reduction
    out = chain(("and", "æ n d", "æ n"), ("then", "ð ɛ n", "ð ɛ n"))
    assert "t_deletion" in out[0]["phenomena"]
    assert "nt_reduction" not in out[0]["phenomena"]


def test_function_elision():
    # kind of → kinda: "of" is a bare schwa; them → 'em
    out = chain(("kind", "k aɪ n d", "k aɪ n"), ("of", "ʌ v", "ə"),
                ("fun", "f ʌ n", "f ʌ n"))
    assert "function_elision" in out[1]["phenomena"]
    out = chain(("tell", "t ɛ l", "t ɛ l"), ("them", "ð ɛ m", "ə m"))
    assert "function_elision" in out[1]["phenomena"]


def test_function_elision_is_not_resyllabification_nor_h_or_td():
    # of it → ə vɪt: the v was heard, bucketed into "it" (a link, not a loss)
    out = chain(("of", "ʌ v", "ə"), ("it", "ɪ t", "v ɪ t"))
    assert "function_elision" not in out[0]["phenomena"]
    # have → ə: the h is h_dropping's, the v is function_elision's
    out = chain(("could", "k ʊ d", "k ʊ d"), ("have", "h æ v", "ə"))
    assert {"h_dropping", "function_elision"} <= set(out[1]["phenomena"])
    # the /d/ of "and" is t_deletion's
    out = chain(("and", "æ n d", "ə n"), ("then", "ð ɛ n", "ð ɛ n"))
    assert "function_elision" not in out[0]["phenomena"]


def test_new_lexical_forms_by_text_and_by_their_signature():
    out = chain(("I'm", "aɪ m", "aɪ m"), ("tryna", "t ɹ aɪ n ə", "t ɹ aɪ n ə"))
    assert out[1]["lexical_form"] == "tryna"
    # trying to → tryna: the ŋ and the /t/ of "to" are gone
    out = chain(("trying", "t ɹ aɪ ɪ ŋ", "t ɹ aɪ n"), ("to", "t u", "ə"),
                ("help", "h ɛ l p", "h ɛ l p"))
    assert out[0]["lexical_form"] == "tryna"
    out = chain(("trying", "t ɹ aɪ ɪ ŋ", "t ɹ aɪ ɪ ŋ"), ("to", "t u", "t ə"),
                ("help", "h ɛ l p", "h ɛ l p"))
    assert out[0]["lexical_form"] is None
    # used to → useta: z devoiced and the two stops fused
    out = chain(("used", "j u z d", "j u s"), ("to", "t u", "t ə"))
    assert out[0]["lexical_form"] == "useta"
    out = chain(("used", "j u z d", "j u z d"), ("to", "t u", "t ə"))
    assert out[0]["lexical_form"] is None
    # would you → wouldja
    out = chain(("would", "w ʊ d", "w ʊ dʒ"), ("you", "j u", "ə"))
    assert out[0]["lexical_form"] == "wouldja"


def test_every_label_and_lexical_form_says_whether_to_produce_it():
    from phonotrainer import report

    assert set(report.PHENOMENON_PRACTICE) == set(report.PHENOMENON_LABEL)
    forms = (set(phenomena.CONTRACTIONS) | set(phenomena.EXPANSIONS_2.values())
             | set(phenomena.EXPANSIONS_3.values()))
    assert forms <= set(report.LEXICAL_PRACTICE)
    for entry in [*report.PHENOMENON_PRACTICE.values(), *report.LEXICAL_PRACTICE.values()]:
        assert entry["practice"] in {"produce", "understand"}
        assert entry["register"] in {"universal", "casual", "marked"}
        assert entry["why"]
    # the report's own examples
    assert report.LEXICAL_PRACTICE["finna"]["practice"] == "understand"
    assert report.PHENOMENON_PRACTICE["flapping"]["practice"] == "produce"
    assert report.PHENOMENON_PRACTICE["flapping"]["register"] == "universal"
    assert report.PHENOMENON_PRACTICE["place_assimilation"]["practice"] == "understand"


def test_an_out_of_vocabulary_word_has_no_dictionary_reference():
    # muy: g2p guesses two syllables (M UW1 IY0) for a one-syllable word; the
    # guess must not turn the word into a lost syllable
    w = mk_word("muy", "m w i", "m w i", dict_arpabet=["M", "UW1", "IY0"])
    assert "elision_syllable" in detect_one(dict(w))["phenomena"]      # 1 < 2 by g2p
    w["oov"] = True
    assert "elision_syllable" not in detect_one(dict(w))["phenomena"]


# --- review fixes: labels on unheard words, /t/ resyllabified across words --------
def _pair(first, second, narrow):
    """Two consecutive mid-utterance words, as detect() sees them."""
    words = [mk_word(*first, t0=0.0), mk_word(*second, t0=0.2)]
    return phenomena.detect(words, first_in_segment=False, narrow=narrow)


def test_an_unheard_word_carries_no_phonetic_label():
    """A word the recognizer never heard (silence, laughter) is word_elision and
    nothing else: its lost /t/ is not a t/d process, on either engine."""
    for narrow in (False, True):
        words = [mk_word("so", "s oʊ", "s oʊ", t0=0.0),
                 mk_word("that", "ð æ t", "", t0=0.2),
                 mk_word("works", "w ɝ k s", "w ɝ k s", t0=0.5)]
        that = phenomena.detect(words, first_in_segment=False, narrow=narrow)[1]
        assert that["phenomena"] == ["word_elision"], (narrow, that["phenomena"])


def test_a_barely_heard_word_keeps_only_word_elision():
    # "him" with a lone 10 ms spike over a 200 ms window: low coverage
    word = mk_word("him", "h ɪ m", "ɪ", real_dur=0.01)
    word["end"] = 0.2
    words = [mk_word("tell", "t ɛ l", "t ɛ l", t0=-0.2), word]
    him = phenomena.detect(words, first_in_segment=False, narrow=True)[1]
    assert him["low_confidence"] is True
    assert him["phenomena"] == ["word_elision"]


def test_a_final_t_heard_at_the_start_of_the_next_word_is_linking_not_deletion():
    """get it → ge‿tit: the /t/ moved onto the vowel, it did not vanish."""
    for narrow in (False, True):
        get, _ = _pair(("get", "ɡ ɛ t", "ɡ ɛ"), ("it", "ɪ t", "t ɪ t"), narrow)
        assert "t_deletion" not in get["phenomena"], narrow
        assert "t_unreleased" not in get["phenomena"], narrow
        assert "linking" in get["phenomena"], narrow


def test_a_final_t_flapped_onto_the_next_word_is_flapping():
    """get it → geɾit: the flap is the /t/ of "get", heard in the next window."""
    for narrow in (False, True):
        get, _ = _pair(("get", "ɡ ɛ t", "ɡ ɛ"), ("it", "ɪ t", "ɾ ɪ t"), narrow)
        assert "flapping" in get["phenomena"], narrow
        assert "t_deletion" not in get["phenomena"], narrow
        assert "t_unreleased" not in get["phenomena"], narrow


def test_the_next_words_own_t_is_not_taken_for_a_moved_one():
    # get to → [ɡɛ] [tə]: that /t/ is the onset of "to"; the /t/ of "get" is lost
    get, _ = _pair(("get", "ɡ ɛ t", "ɡ ɛ"), ("to", "t uː", "t ə"), True)
    assert "t_deletion" in get["phenomena"]


def test_a_released_t_after_n_moved_onto_the_vowel_is_not_nt_reduction():
    # want it → wɑn‿tɪt: the /t/ was said, so /nt/ did not reduce to [n]
    want, _ = _pair(("want", "w ɑ n t", "w ɑ n"), ("it", "ɪ t", "t ɪ t"), True)
    assert "nt_reduction" not in want["phenomena"]
    assert "t_deletion" not in want["phenomena"]


def test_a_weak_article_is_reduction_never_monophthongization():
    """"a" has the strong citation /eɪ/: heard as [ɪ] it is its weak form, and a
    plain [e] is at most a clipped strong form — never the regional
    monophthongization label."""
    def article(real):
        words = [mk_word("is", "ɪ z", "ɪ z", t0=0.0), mk_word("a", "eɪ", real, t0=0.2),
                 mk_word("dog", "d ɔ ɡ", "d ɔ ɡ", t0=0.4)]
        return phenomena.detect(words, first_in_segment=False, narrow=True)[1]["phenomena"]

    assert "vowel_reduction" in article("ɪ")
    assert "vowel_reduction" in article("ə")
    for real in ("ɪ", "ə", "e", "ɛ"):
        assert "monophthongization" not in article(real), real


def test_a_content_or_pronoun_diphthong_still_monophthongizes():
    # I → [a], my → [ma]: the report's Southern /aɪ/ → [aː]
    w = detect_one(mk_word("I", "aɪ", "a"))
    assert "monophthongization" in w["phenomena"]
