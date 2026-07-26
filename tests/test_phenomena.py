"""Tests sintéticos por regla de fenómeno de connected speech (TDD Fase 5)."""

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


def test_vowel_reduction_no_dispara_si_ya_es_schwa():
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
    # water con canónico /t/
    w = detect_one(mk_word("water", "w ɔ t ɚ", "w ɔ ɾ ɚ"))
    assert "flapping" in w["phenomena"]


def test_flapping_match_espeak_ya_flapeado():
    # espeak en-us ya emite ɾ en "water": un match ɾ↔ɾ sigue siendo flapping
    w = detect_one(mk_word("water", "w ɔ ɾ ɚ", "w ɔ ɾ ɚ"))
    assert "flapping" in w["phenomena"]


def test_palatalization_frontera():
    # got you → gotcha
    got = mk_word("got", "ɡ ɑ t", "ɡ ɑ tʃ", t0=0.0)
    you = mk_word("you", "j u", "ə", t0=0.20)
    out = phenomena.detect([got, you], first_in_segment=True)
    assert "palatalization" in out[0]["phenomena"]


def test_elision_syllable():
    # probably → probli (3 núcleos → 2)
    w = detect_one(mk_word("probably", "p ɹ ɑ b ə b l i", "p ɹ ɑ b l i"))
    assert "elision_syllable" in w["phenomena"]


def test_linking_consonante_vocal():
    # does it → does‿it (sin hueco temporal)
    does = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    it = mk_word("it", "ɪ t", "ɪ t", t0=0.18)
    out = phenomena.detect([does, it], first_in_segment=True)
    assert out[0]["boundary_link_next"] is True
    assert "linking" in out[0]["phenomena"]


def test_linking_no_dispara_con_pausa():
    does = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    it = mk_word("it", "ɪ t", "ɪ t", t0=1.5)
    out = phenomena.detect([does, it], first_in_segment=True)
    assert out[0]["boundary_link_next"] is False


def test_h_dropping():
    # tell him → tell 'im ; "him" no inicia segmento
    tell = mk_word("tell", "t ɛ l", "t ɛ l", t0=0.0)
    him = mk_word("him", "h ɪ m", "ɪ m", t0=0.2)
    out = phenomena.detect([tell, him], first_in_segment=True)
    assert "h_dropping" in out[1]["phenomena"]


def test_h_dropping_no_dispara_al_inicio():
    him = mk_word("him", "h ɪ m", "ɪ m", t0=0.0)
    out = phenomena.detect([him], first_in_segment=True)
    assert "h_dropping" not in out[0]["phenomena"]


def test_contraction_lex_via_texto():
    # Whisper ya escribió "gonna"
    w = detect_one(mk_word("gonna", "ɡ ə n ə", "ɡ ə n ə"))
    assert "contraction_lex" in w["phenomena"]
    assert w["lexical_form"] == "gonna"


def test_contraction_lex_via_fonos():
    # texto dice "want to" pero los fonos reales son wanna
    want = mk_word("want", "w ɑ n t", "w ɑ n", t0=0.0)
    to = mk_word("to", "t u", "ə", t0=0.25)
    out = phenomena.detect([want, to], first_in_segment=True)
    assert "contraction_lex" in out[0]["phenomena"]
    assert out[0]["lexical_form"] == "wanna"


def test_contraction_lex_no_dispara_sin_reduccion():
    # "want to" pronunciado completo NO es wanna
    want = mk_word("want", "w ɑ n t", "w ɑ n t", t0=0.0)
    to = mk_word("to", "t u", "t u", t0=0.30)
    out = phenomena.detect([want, to], first_in_segment=True)
    assert "contraction_lex" not in out[0]["phenomena"]


def test_resumen_cuentas():
    w1 = mk_word("does", "d ʌ z", "d ə z", t0=0.0)
    w2 = mk_word("that", "ð æ t", "d æ", t0=0.3)
    out = phenomena.detect([w1, w2], first_in_segment=True)
    counts = phenomena.count_phenomena([{"words": out}])
    assert counts["vowel_reduction"] >= 1
    assert counts["th_stopping"] >= 1
    assert counts["t_deletion"] >= 1


# --- MEJORA 2: monophthongization -------------------------------------------

def test_monophthongization_por_diptongo():
    casos = [
        ("I", "aɪ", "æ"),        # aɪ→æ
        ("I'll", "aɪ l", "ɑː l"),  # aɪ→ɑː
        ("know", "n oʊ", "n ɔ"),   # oʊ→ɔ
        ("cupcakes", "k ʌ p k eɪ k s", "k ʌ p k ɪ k s"),  # eɪ→ɪ
        ("out", "aʊ t", "a t"),    # aʊ→a
        ("boy", "b ɔɪ", "b ɔ"),    # ɔɪ→ɔ
    ]
    for texto, canon, real in casos:
        w = detect_one(mk_word(texto, canon, real))
        assert "monophthongization" in w["phenomena"], f"{texto}: {w['phenomena']}"


def test_monophthongization_no_dispara_vocal_lejana():
    # aɪ→u no es monoptongación del primer elemento
    w = detect_one(mk_word("I", "aɪ", "u"))
    assert "monophthongization" not in w["phenomena"]


def test_precedencia_reduccion_sobre_mono():
    # artículo "a" /eɪ/→[ɐ]: forma débil = vowel_reduction, NO duplicar con mono
    w = detect_one(mk_word("a", "eɪ", "ɐ"))
    assert "vowel_reduction" in w["phenomena"]
    assert "monophthongization" not in w["phenomena"]


# --- MEJORA 3: word_elision ---------------------------------------------------

def test_word_elision_realized_vacio():
    w = detect_one(mk_word("and", "æ n d", ""))
    assert "word_elision" in w["phenomena"]
    assert "elision_syllable" not in w["phenomena"]
    assert w["low_confidence"] is True


def test_word_elision_cobertura_baja():
    # palabra de 0.30 s con un solo fono real de 0.06 s (20%)
    w = detect_one(mk_word("to", "t uː", "ə", dur=0.15, real_dur=0.06))
    assert "word_elision" in w["phenomena"]
    assert w["low_confidence"] is True


def test_sin_word_elision_con_cobertura_normal():
    w = detect_one(mk_word("does", "d ʌ z", "d ə z"))
    assert "word_elision" not in w["phenomena"]
    assert w["low_confidence"] is False


def test_elision_syllable_sigue_existiendo():
    # elisión de sílaba con cobertura sana no se convierte en word_elision
    w = detect_one(mk_word("probably", "p ɹ ɑ b ə b l i", "p ɹ ɑ b l i"))
    assert "elision_syllable" in w["phenomena"]
    assert "word_elision" not in w["phenomena"]


# --- MEJORA 4: referencia por regla -------------------------------------------

def test_rule_reference_cubre_todas_las_etiquetas():
    todas = {
        "vowel_reduction", "monophthongization", "t_deletion", "glottalization",
        "th_stopping", "flapping", "palatalization", "elision_syllable",
        "word_elision", "linking", "h_dropping", "contraction_lex",
    }
    assert set(phenomena.RULE_REFERENCE) == todas
    assert all(v in {"dict", "aligned"} for v in phenomena.RULE_REFERENCE.values())


def test_flapping_match_valida_contra_diccionario():
    # canónico espeak ya flapeado: solo es flapping si el diccionario tiene T/D
    w = detect_one(mk_word("water", "w ɔ ɾ ɚ", "w ɔ ɾ ɚ",
                           dict_arpabet=["W", "AO1", "T", "ER0"]))
    assert "flapping" in w["phenomena"]
    # palabra hipotética cuyo diccionario NO tiene t/d: el match ɾ↔ɾ no es flapping
    w = detect_one(mk_word("xler", "ɾ ɚ", "ɾ ɚ", dict_arpabet=["R", "ER0"]))
    assert "flapping" not in w["phenomena"]


def test_sin_word_elision_con_spans_ctc_puntiagudos():
    # BUG validación: los spans CTC son picos de ~30 ms; la cobertura se mide por
    # EXTENSIÓN (primer inicio → último fin), no por suma de duraciones.
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
