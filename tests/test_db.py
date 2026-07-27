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
    assert corpus.count_occurrences() == 3          # las 3 palabras con fenómeno
    assert corpus.count_occurrences(phenomenon="t_deletion") == 1
    assert corpus.count_occurrences(word="does") == 1


def test_sin_filtro_solo_se_listan_palabras_con_fenomeno(corpus):
    """La lista completa ordenada por divergencia son sobre todo fallos de
    alineamiento: no es lo que hay que enseñar primero."""
    corpus.index_analysis("job1", mk_analysis())

    sin_filtro = corpus.occurrences()
    assert {fila["word"] for fila in sin_filtro} == {"does", "that", "wanna"}
    assert all(fila["phenomena"] for fila in sin_filtro)

    # buscando una palabra concreta sí se ven todas sus apariciones
    assert [f["word"] for f in corpus.occurrences(word="work")] == ["work"]


def test_cada_aparicion_trae_con_qué_enlaza_y_de_qué_análisis_viene(corpus):
    """En linking el fenómeno ocurre entre dos palabras; y dos análisis del
    mismo material con distinta configuración no son variación nativa."""
    analysis = mk_analysis()
    analysis["meta"]["attraction"] = False
    corpus.index_analysis("/tmp/out", analysis, job_id="job1")

    (fila,) = corpus.occurrences(phenomenon="vowel_reduction")
    assert fila["word"] == "does" and fila["next_word"] == "that"
    assert fila["analysis_attraction"] is False
    assert fila["job_id"] == "job1"


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


def test_el_material_repetido_se_reconoce_aunque_cambie_el_nombre(corpus):
    """Un recorte del mismo vídeo se llama distinto y sigue siendo la misma
    grabación: contarlo como otra fuente infla todas las cifras."""
    entero = mk_analysis("episodio.webm")
    recorte = mk_analysis("clip.mp3")
    # los primeros segundos: mismas palabras, mismos instantes, menos material
    recorte["segments"][1]["words"] = recorte["segments"][1]["words"][:1]
    otro = mk_analysis("otra-cosa.webm")
    otro["segments"][0]["words"][0]["start"] = 7.5     # otra grabación

    corpus.index_analysis("/a", entero)
    corpus.index_analysis("/b", recorte)
    corpus.index_analysis("/c", otro)

    stats = corpus.stats()
    assert stats["analyses"] == 3
    assert stats["sources"] == 3            # tres nombres de archivo…
    assert stats["materials"] == 2          # …pero dos grabaciones
    por_id = {a["id"]: a for a in corpus.analyses()}
    assert por_id["/a"]["duplicate_source"] and por_id["/b"]["duplicate_source"]
    assert not por_id["/c"]["duplicate_source"]


def test_las_palabras_de_un_frame_no_encabezan_la_lista(corpus):
    """Duran 20 ms: son picos de CTC sueltos, no algo que se pueda oír ni
    juzgar. Siguen en el corpus, pero al final."""
    analysis = mk_analysis()
    corta = analysis["segments"][0]["words"][0]
    corta["end"] = corta["start"] + 0.02
    corta["diff_cost"] = 9.9                # la más divergente de todas

    corpus.index_analysis("job1", analysis)
    filas = corpus.occurrences()

    assert filas[0]["word"] != corta["word"]
    assert filas[-1]["word"] == corta["word"]
    assert filas[-1]["too_short"] is True
    assert filas[0]["too_short"] is False


def test_buscar_sin_apostrofo_encuentra_la_palabra(corpus):
    analysis = mk_analysis()
    analysis["segments"][0]["words"][0] = mk_analysis_word("don't", 0.0, "d oʊ n t", "d oʊ n")
    corpus.index_analysis("job1", analysis)

    assert corpus.occurrences(word="dont")[0]["word"] == "don't"
    assert corpus.occurrences(word="DON'T")[0]["word"] == "don't"


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


def test_una_base_ilegible_se_aparta_y_se_rehace(tmp_path):
    """El corpus es derivado: nunca debe impedir arrancar la aplicación."""
    ruta = tmp_path / "phonotrainer.db"
    ruta.write_bytes(b"esto no es una base de datos")

    db = Corpus(ruta)
    try:
        assert db.rebuilt is True
        assert list(tmp_path.glob("phonotrainer.db.*.corrupta"))    # apartada, no borrada
        db.index_analysis("x", mk_analysis())        # y funciona desde cero
        assert db.stats()["analyses"] == 1
    finally:
        db.close()


def test_una_base_bloqueada_no_se_da_por_corrupta(tmp_path):
    """Retirar una base sana por un bloqueo pasajero le costaba el corpus entero
    al usuario."""
    import sqlite3

    from phonotrainer.db import _looks_broken

    assert _looks_broken(sqlite3.OperationalError("database is locked")) is False
    assert _looks_broken(sqlite3.OperationalError("attempt to write a readonly database")) is False
    assert _looks_broken(sqlite3.DatabaseError("file is not a database")) is True
    assert _looks_broken(sqlite3.DatabaseError("database disk image is malformed")) is True

    ruta = tmp_path / "sana.db"
    primera = Corpus(ruta)
    try:
        primera.index_analysis("job1", mk_analysis())
        otra = Corpus(ruta)                          # segunda conexión: no pasa nada
        try:
            assert otra.rebuilt is False
            assert otra.stats()["analyses"] == 1
        finally:
            otra.close()
    finally:
        primera.close()


def test_un_esquema_viejo_tambien_se_rehace(tmp_path):
    import sqlite3

    ruta = tmp_path / "phonotrainer.db"
    con = sqlite3.connect(ruta)
    con.executescript("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);"
                      "INSERT INTO meta VALUES ('schema_version', '1');"
                      "CREATE TABLE analyses (id TEXT PRIMARY KEY);")
    con.commit()
    con.close()

    db = Corpus(ruta)
    try:
        assert db.rebuilt is True
        db.index_analysis("x", mk_analysis())
        assert db.stats()["words"] == 5
    finally:
        db.close()


def test_usa_wal_para_convivir_con_otro_proceso(tmp_path):
    db = Corpus(tmp_path / "corpus.db")
    try:
        modo = db._rows("PRAGMA journal_mode")[0]
        assert list(modo.values())[0] == "wal"
    finally:
        db.close()


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
