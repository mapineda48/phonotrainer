"""IMPROVEMENT 1: phonetic attraction toward the canonical (acoustic noise cleanup)."""

from conftest import mk_phones

from phonotrainer.phones_real import attract_to_canonical


def test_attracts_noise_but_keeps_elision_and_vowel():
    # "don't" → [l a]: l→d is attracted (noise); the monophthongized vowel is NOT
    # attracted (it is a monophthongization candidate) and the n/t elision is kept as is.
    real = mk_phones("l a", t0=0.0, dur=0.06)
    canonical = mk_phones("d oʊ n t", t0=0.0, dur=0.03)
    out = attract_to_canonical(real, canonical)
    assert len(out) == 2                      # never inserts nor deletes
    assert out[0]["phone"] == "d"
    assert out[0]["attracted"] is True
    assert out[0]["raw_phone"] == "l"         # the raw phone is always preserved
    assert out[1]["phone"] == "a"
    assert not out[1].get("attracted")


def test_does_not_attract_a_flap():
    real = mk_phones("ɾ")
    canonical = mk_phones("t")
    out = attract_to_canonical(real, canonical)
    assert out[0]["phone"] == "ɾ"
    assert not out[0].get("attracted")


def test_does_not_attract_native_variation_of_your():
    # "your" → [jɔːɹ] (yor): ʊɹ→ɔːɹ is protected native variation
    real = mk_phones("j ɔːɹ")
    canonical = mk_phones("j ʊɹ")
    out = attract_to_canonical(real, canonical)
    assert [p["phone"] for p in out] == ["j", "ɔːɹ"]
    assert not any(p.get("attracted") for p in out)
    # "your" → [jɚ] (yer): protected as well
    out = attract_to_canonical(mk_phones("j ɚ"), canonical)
    assert out[1]["phone"] == "ɚ"
    assert not out[1].get("attracted")


def test_attracts_typical_confusions():
    # b→v ("blowing"→[vloʊɪŋ]) and n→l ("know"→[lɔ])
    out = attract_to_canonical(mk_phones("v"), mk_phones("b"))
    assert out[0]["phone"] == "b" and out[0]["attracted"] is True
    out = attract_to_canonical(mk_phones("l"), mk_phones("n"))
    assert out[0]["phone"] == "n" and out[0]["attracted"] is True
    # j→t is in EXTRA_ATTRACT ("you"→[tuː])
    out = attract_to_canonical(mk_phones("t uː"), mk_phones("j uː"))
    assert out[0]["phone"] == "j" and out[0]["attracted"] is True


def test_does_not_attract_vowel_reduction():
    # ʌ→ə is the flagship phenomenon: never attract it
    out = attract_to_canonical(mk_phones("ə"), mk_phones("ʌ"))
    assert out[0]["phone"] == "ə"
    assert not out[0].get("attracted")


def test_does_not_attract_without_temporal_overlap():
    real = mk_phones("s", t0=5.0)
    canonical = mk_phones("t", t0=0.0)
    out = attract_to_canonical(real, canonical)
    assert out[0]["phone"] == "s"
    assert not out[0].get("attracted")


def test_threshold_is_configurable():
    out = attract_to_canonical(mk_phones("v"), mk_phones("b"), max_cost=0.1)
    assert out[0]["phone"] == "v"             # with a strict threshold it does not attract


def test_does_not_absorb_a_final_deletion():
    # Validation bug: "don't" → [d oʊ n]; the real n must NOT be attracted to the
    # ELIDED t even though its span overlaps the t's window (that would kill
    # t_deletion/dunno).
    real = mk_phones("d oʊ n", dur=0.06)
    canonical = mk_phones("d oʊ n t", dur=0.045)
    out = attract_to_canonical(real, canonical)
    assert [p["phone"] for p in out] == ["d", "oʊ", "n"]
    assert not any(p.get("attracted") for p in out)
