"""MEJORA 1: atracción fonética hacia el canónico (limpieza de ruido acústico)."""

from conftest import mk_phones

from phonotrainer.phones_real import attract_to_canonical


def test_atrae_ruido_pero_conserva_elision_y_vocal():
    # "don't" → [l a]: l→d se atrae (ruido); la vocal monoptongada NO se atrae
    # (candidata a monophthongization) y la elisión de n/t se conserva tal cual.
    real = mk_phones("l a", t0=0.0, dur=0.06)
    canonical = mk_phones("d oʊ n t", t0=0.0, dur=0.03)
    out = attract_to_canonical(real, canonical)
    assert len(out) == 2                      # nunca inserta ni borra
    assert out[0]["phone"] == "d"
    assert out[0]["attracted"] is True
    assert out[0]["raw_phone"] == "l"         # el crudo se conserva siempre
    assert out[1]["phone"] == "a"
    assert not out[1].get("attracted")


def test_no_atrae_flap():
    real = mk_phones("ɾ")
    canonical = mk_phones("t")
    out = attract_to_canonical(real, canonical)
    assert out[0]["phone"] == "ɾ"
    assert not out[0].get("attracted")


def test_no_atrae_variacion_nativa_de_your():
    # "your" → [jɔːɹ] (yor): ʊɹ→ɔːɹ es variación nativa protegida
    real = mk_phones("j ɔːɹ")
    canonical = mk_phones("j ʊɹ")
    out = attract_to_canonical(real, canonical)
    assert [p["phone"] for p in out] == ["j", "ɔːɹ"]
    assert not any(p.get("attracted") for p in out)
    # "your" → [jɚ] (yer): también protegida
    out = attract_to_canonical(mk_phones("j ɚ"), canonical)
    assert out[1]["phone"] == "ɚ"
    assert not out[1].get("attracted")


def test_atrae_confusiones_tipicas():
    # b→v ("blowing"→[vloʊɪŋ]) y n→l ("know"→[lɔ])
    out = attract_to_canonical(mk_phones("v"), mk_phones("b"))
    assert out[0]["phone"] == "b" and out[0]["attracted"] is True
    out = attract_to_canonical(mk_phones("l"), mk_phones("n"))
    assert out[0]["phone"] == "n" and out[0]["attracted"] is True
    # j→t está en EXTRA_ATTRACT ("you"→[tuː])
    out = attract_to_canonical(mk_phones("t uː"), mk_phones("j uː"))
    assert out[0]["phone"] == "j" and out[0]["attracted"] is True


def test_no_atrae_reduccion_vocalica():
    # ʌ→ə es el fenómeno estrella: jamás atraer
    out = attract_to_canonical(mk_phones("ə"), mk_phones("ʌ"))
    assert out[0]["phone"] == "ə"
    assert not out[0].get("attracted")


def test_no_atrae_sin_solape_temporal():
    real = mk_phones("s", t0=5.0)
    canonical = mk_phones("t", t0=0.0)
    out = attract_to_canonical(real, canonical)
    assert out[0]["phone"] == "s"
    assert not out[0].get("attracted")


def test_umbral_configurable():
    out = attract_to_canonical(mk_phones("v"), mk_phones("b"), max_cost=0.1)
    assert out[0]["phone"] == "v"             # con umbral estricto no atrae


def test_no_absorbe_delecion_final():
    # BUG validación: "don't" → [d oʊ n]; la n real NO debe atraerse a la t
    # ELIDIDA aunque su span solape la ventana de la t (mataría t_deletion/dunno).
    real = mk_phones("d oʊ n", dur=0.06)
    canonical = mk_phones("d oʊ n t", dur=0.045)
    out = attract_to_canonical(real, canonical)
    assert [p["phone"] for p in out] == ["d", "oʊ", "n"]
    assert not any(p.get("attracted") for p in out)
