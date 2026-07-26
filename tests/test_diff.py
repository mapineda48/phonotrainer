"""Tests del alineamiento Needleman-Wunsch con costos panphon."""

from conftest import mk_phones

from phonotrainer import diff


def ops_signature(ops):
    return [(o["op"],
             o["canonical"]["phone"] if o["canonical"] else None,
             o["real"]["phone"] if o["real"] else None) for o in ops]


def test_identidad_solo_matches():
    real = mk_phones("d ʌ z")
    canon = mk_phones("d ʌ z")
    ops = diff.align_word(real, canon)
    assert [o["op"] for o in ops] == ["match", "match", "match"]


def test_reduccion_vocal_es_sub():
    ops = diff.align_word(mk_phones("d ə z"), mk_phones("d ʌ z"))
    assert ops_signature(ops) == [("match", "d", "d"), ("sub", "ʌ", "ə"), ("match", "z", "z")]


def test_t_final_elidida_es_del():
    # that → ðæ
    ops = diff.align_word(mk_phones("ð æ"), mk_phones("ð æ t"))
    assert ops_signature(ops) == [("match", "ð", "ð"), ("match", "æ", "æ"), ("del", "t", None)]


def test_fono_extra_es_ins():
    ops = diff.align_word(mk_phones("ð ə ʔ"), mk_phones("ð ə"))
    assert ops_signature(ops)[-1] == ("ins", None, "ʔ")


def test_flapping_prefiere_sub_sobre_indel():
    # water: t→ɾ debe alinear como sustitución, no como del+ins
    ops = diff.align_word(mk_phones("w ɔ ɾ ɚ"), mk_phones("w ɔ t ɚ"))
    assert ("sub", "t", "ɾ") in ops_signature(ops)


def test_glotalizacion_prefiere_sub():
    ops = diff.align_word(mk_phones("b ʌ ʔ n̩"), mk_phones("b ʌ t n̩"))
    assert ("sub", "t", "ʔ") in ops_signature(ops)


def test_costos_ordenados():
    c = diff.phone_cost
    gap = diff.GAP_COST
    # cambios nativos baratos, muy por debajo del gap
    assert c("ʌ", "ə") < gap
    assert c("ð", "d") < gap
    assert c("θ", "t") < gap
    assert c("t", "ɾ") < gap
    assert c("t", "ʔ") < gap
    # pares lejanos: más caros que cualquier cambio nativo
    assert c("p", "s") > c("t", "ɾ")
    assert c("k", "m") > 2 * gap - 0.01  # indel preferido
    # vocal↔consonante nunca barato
    assert c("æ", "k") > gap


def test_secuencias_vacias():
    assert diff.align_word([], []) == []
    ops = diff.align_word([], mk_phones("t"))
    assert ops_signature(ops) == [("del", "t", None)]
    ops = diff.align_word(mk_phones("ə"), [])
    assert ops_signature(ops) == [("ins", None, "ə")]


def test_asignacion_de_fonos_a_palabras():
    words = [{"start": 0.0, "end": 0.30}, {"start": 0.35, "end": 0.60}]
    real = (mk_phones("d ə z", t0=0.02, dur=0.08)
            + mk_phones("ð ə", t0=0.36, dur=0.10))
    buckets = diff.assign_real_to_words(real, words)
    assert [len(b) for b in buckets] == [3, 2]
    # fono en el hueco entre palabras se asigna a la más cercana
    real_gap = mk_phones("s", t0=0.31, dur=0.02)
    buckets = diff.assign_real_to_words(real + real_gap, words)
    assert len(buckets[0]) + len(buckets[1]) == 6
