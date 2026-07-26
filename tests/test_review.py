"""MEJORA 5: muestreo priorizado del comando review."""

from phonotrainer import review


def _analysis(words_spec):
    """words_spec: lista de dicts parciales de palabra."""
    words = []
    for i, spec in enumerate(words_spec):
        w = {
            "word": f"w{i}", "start": float(i), "end": float(i) + 0.5,
            "canonical_ipa": "x", "realized_ipa": "x", "dict_ipa": "x",
            "phenomena": [], "diff_cost": 0.0, "attracted_count": 0,
            "low_confidence": False,
        }
        w.update(spec)
        words.append(w)
    return {"segments": [{"start": 0, "end": 99, "text": "t", "words": words}]}


def test_peso_prioriza_palabras_sospechosas():
    plain = review.word_weight({"diff_cost": 0.0, "attracted_count": 0, "low_confidence": False})
    attracted = review.word_weight({"diff_cost": 0.0, "attracted_count": 2, "low_confidence": False})
    lowconf = review.word_weight({"diff_cost": 0.0, "attracted_count": 0, "low_confidence": True})
    costly = review.word_weight({"diff_cost": 1.2, "attracted_count": 0, "low_confidence": False})
    assert attracted > plain
    assert lowconf > plain
    assert costly > plain


def test_muestreo_deterministico_con_seed():
    analysis = _analysis([{} for _ in range(40)])
    s1 = review.select_sample(analysis, n=10, seed=48)
    s2 = review.select_sample(analysis, n=10, seed=48)
    assert s1 == s2
    s3 = review.select_sample(analysis, n=10, seed=7)
    assert s1 != s3


def test_muestreo_respeta_n_y_orden_temporal():
    analysis = _analysis([{} for _ in range(8)])
    sample = review.select_sample(analysis, n=20, seed=48)
    assert len(sample) == 8                    # n > total → todas
    idxs = [(si, wi) for si, wi, _ in sample]
    assert idxs == sorted(idxs)                # orden temporal para revisar cómodo


def test_muestreo_prioriza_atraidas():
    # 1 palabra muy sospechosa entre 50 planas: debe entrar en la muestra
    spec = [{} for _ in range(50)]
    spec[25] = {"attracted_count": 3, "low_confidence": True, "diff_cost": 1.5}
    analysis = _analysis(spec)
    sample = review.select_sample(analysis, n=10, seed=48)
    assert any(w["word"] == "w25" for _, _, w in sample)
