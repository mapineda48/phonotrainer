"""Corpus SQLite: indexado, consultas entre análisis y limpieza."""

import json

import pytest
from conftest import mk_analysis, mk_analysis_word

from phonotrainer.db import Corpus, word_key


@pytest.fixture
def corpus():
    db = Corpus(":memory:")
    yield db
    db.close()


def test_indexa_un_analisis_completo(corpus):
    n = corpus.index_analysis("job1", mk_analysis("ep1.webm"))

    assert n == 5
    (fila,) = corpus.analyses()
    assert fila["source"] == "ep1.webm"
    assert fila["words"] == 5 and fila["segments"] == 2
    assert fila["asr_model"].startswith("faster-whisper")


def test_reindexar_reemplaza_en_vez_de_duplicar(corpus):
    corpus.index_analysis("job1", mk_analysis())
    corpus.index_analysis("job1", mk_analysis())

    assert len(corpus.analyses()) == 1
    assert corpus.stats()["words"] == 5


def test_estadisticas_suman_todos_los_analisis(corpus):
    corpus.index_analysis("job1", mk_analysis("ep1.webm"))
    corpus.index_analysis("job2", mk_analysis("ep2.webm"))

    stats = corpus.stats()
    assert stats["analyses"] == 2
    assert stats["words"] == 10
    assert stats["duration"] == 6.0
    conteos = {p["phenomenon"]: p for p in stats["phenomena"]}
    assert conteos["t_deletion"]["count"] == 2
    assert conteos["t_deletion"]["analyses"] == 2      # aparece en los dos


def test_busca_apariciones_de_un_fenomeno_entre_analisis(corpus):
    corpus.index_analysis("job1", mk_analysis("ep1.webm"))
    corpus.index_analysis("job2", mk_analysis("ep2.webm"))

    filas = corpus.occurrences(phenomenon="t_deletion")
    assert len(filas) == 2
    assert {f["analysis_source"] for f in filas} == {"ep1.webm", "ep2.webm"}
    primera = filas[0]
    assert primera["word"] == "that"
    assert primera["phenomena"] == ["t_deletion"]
    # la posición exacta, para que la interfaz pueda saltar allí
    assert (primera["segment"], primera["word_idx"]) == (0, 1)
    assert primera["start"] == 0.4


def test_cuenta_el_total_aunque_el_listado_se_recorte(corpus):
    """«200 apariciones» no debe ser en realidad el tope de la consulta."""
    corpus.index_analysis("job1", mk_analysis())

    assert len(corpus.occurrences(limit=2)) == 2
    assert corpus.count_occurrences() == 5
    assert corpus.count_occurrences(phenomenon="t_deletion") == 1
    assert corpus.count_occurrences(word="does") == 1


def test_las_apariciones_salen_de_mayor_a_menor_divergencia(corpus):
    analysis = mk_analysis()
    analysis["segments"][0]["words"][2]["diff_cost"] = 1.4
    analysis["segments"][0]["words"][2]["phenomena"] = ["t_deletion"]
    corpus.index_analysis("job1", analysis)

    filas = corpus.occurrences(phenomenon="t_deletion")
    assert [f["word"] for f in filas] == ["work", "that"]


def test_busca_una_palabra_sin_importar_puntuacion_ni_mayusculas(corpus):
    analysis = mk_analysis()
    analysis["segments"][0]["words"][0] = mk_analysis_word("Does,", 0.0, "d ʌ z", "d ə z")
    corpus.index_analysis("job1", analysis)

    assert word_key("Does,") == "does"
    filas = corpus.occurrences(word="does")
    assert len(filas) == 1 and filas[0]["word"] == "Does,"


def test_variantes_de_pronunciacion_de_una_palabra(corpus):
    """La pregunta que un análisis suelto no contesta: cómo se ha dicho *to*
    en todo lo que llevo visto."""
    for i, realizado in enumerate(["t ə", "t ə", "t ʊ"]):
        analysis = mk_analysis(f"ep{i}.webm")
        analysis["segments"][0]["words"] = [mk_analysis_word("to", 0.0, "t u", realizado)]
        corpus.index_analysis(f"job{i}", analysis)

    variantes = corpus.word_variants("to")
    assert [(v["realized_ipa"], v["count"]) for v in variantes] == [("tə", 2), ("tʊ", 1)]
    assert variantes[0]["analyses"] == 2


def test_olvidar_un_analisis_lo_borra_entero(corpus):
    corpus.index_analysis("job1", mk_analysis("ep1.webm"))
    corpus.index_analysis("job2", mk_analysis("ep2.webm"))

    corpus.forget("job1")

    assert [a["id"] for a in corpus.analyses()] == ["job2"]
    assert corpus.stats()["words"] == 5
    assert all(f["analysis_id"] == "job2" for f in corpus.occurrences())


def test_indexa_desde_un_archivo(corpus, tmp_path):
    ruta = tmp_path / "analysis.json"
    ruta.write_text(json.dumps(mk_analysis("ep.webm")), encoding="utf-8")

    corpus.index_file("job1", ruta)

    (fila,) = corpus.analyses()
    assert fila["result_dir"] == str(tmp_path)


def test_un_analisis_vacio_no_rompe_nada(corpus):
    corpus.index_analysis("vacio", {"meta": {}, "segments": []})
    assert corpus.stats()["analyses"] == 1
    assert corpus.occurrences() == []


def test_la_base_de_datos_se_crea_sola(tmp_path):
    ruta = tmp_path / "data" / "phonotrainer.db"
    db = Corpus(ruta)
    try:
        db.index_analysis("job1", mk_analysis())
        assert ruta.is_file()
    finally:
        db.close()

    # y se puede reabrir sin perder nada: es el "corpus" entre sesiones
    otra = Corpus(ruta)
    try:
        assert otra.stats()["words"] == 5
    finally:
        otra.close()
