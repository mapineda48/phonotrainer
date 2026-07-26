"""Store de trabajos: ejecución en hilo, progreso, cancelación y persistencia."""

import json
import threading
import time
from pathlib import Path

import pytest
from conftest import fake_analyze, mk_analysis, wait_until

from phonotrainer import jobs
from phonotrainer.jobs import JobError, JobStore, estimate_percent


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.wav"
    path.write_bytes(b"RIFF fake media")
    return path


@pytest.fixture
def store(tmp_path):
    st = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze)
    yield st
    st.shutdown()


def _done(store, job):
    return wait_until(lambda: store.get(job.id).status == jobs.DONE and job)


def test_analiza_y_deja_los_artefactos(store, media):
    job = store.create(media, options={"whisper_model": "tiny"})
    _done(store, job)

    assert job.artifact("analysis.json") is not None
    assert job.artifact("audio.wav") is not None
    assert job.summary["words"] == 5
    assert job.meta["source"] == "clip.wav"
    assert job.meta["options"]["whisper_model"] == "tiny"   # las opciones llegan al pipeline


def test_progreso_con_marca_de_tiempo_y_porcentaje(store, media):
    job = store.create(media)
    _done(store, job)

    mensajes = [p["message"] for p in job.progress]
    assert any("Transcribiendo" in m for m in mensajes)
    assert all(p["at"] for p in job.progress)
    assert job.percent == 100


def test_estimacion_de_porcentaje():
    assert estimate_percent(None) == 0
    assert estimate_percent("Extrayendo audio (ffmpeg…)") == 17
    assert estimate_percent("Segmento 1/10: fonos…") == 39
    assert estimate_percent("Segmento 10/10: fonos…") == 95
    assert estimate_percent("Generando report.html…") == 98
    assert estimate_percent("mensaje que no reconocemos") == 0


def test_la_descarga_ocupa_su_propio_tramo_y_la_barra_no_retrocede():
    """Un job que empieza descargando pasa por dos fases: el porcentaje debe
    crecer de una a otra, no reiniciarse."""
    descarga = [estimate_percent(f"Descargando de YouTube… {p} %") for p in (0, 50, 100)]
    assert descarga == [2, 8, 15]
    assert descarga[-1] <= estimate_percent("Descargado: clip.mp4")
    assert estimate_percent("Descargado: clip.mp4") <= estimate_percent("Extrayendo audio…")
    assert estimate_percent("Extrayendo audio…") <= estimate_percent("Segmento 1/10: …")


def test_el_error_del_pipeline_queda_en_el_job(tmp_path, media):
    def boom(*a, **kw):
        raise RuntimeError("ffmpeg falló")

    store = JobStore(tmp_path / "ws", analyze_fn=boom)
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.ERROR)
        assert "ffmpeg falló" in job.error
        assert job.finished
    finally:
        store.shutdown()


def test_cancelar_aborta_el_analisis(tmp_path, media):
    def lento(media_path, out_dir, progress=lambda m: None, **kw):
        for i in range(1, 201):
            time.sleep(0.01)                    # simula el trabajo de un segmento
            progress(f"Segmento {i}/200: …")    # el callback lanza JobCancelled
        return mk_analysis()

    store = JobStore(tmp_path / "ws", analyze_fn=lento)
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.RUNNING and len(job.progress) > 2)
        store.cancel(job.id)
        wait_until(lambda: job.status == jobs.CANCELLED)
        assert job.artifact("analysis.json") is None
    finally:
        store.shutdown()


def test_los_jobs_se_recuperan_al_reiniciar(tmp_path, media):
    root = tmp_path / "ws"
    store = JobStore(root, analyze_fn=fake_analyze)
    job = store.create(media)
    _done(store, job)
    store.shutdown()

    revivido = JobStore(root, analyze_fn=fake_analyze)
    try:
        assert [j.id for j in revivido.list()] == [job.id]
        assert revivido.get(job.id).status == jobs.DONE
    finally:
        revivido.shutdown()


def test_un_job_interrumpido_se_marca_como_error(tmp_path):
    root = tmp_path / "ws"
    job_dir = root / "20260101-000000-abcd"
    job_dir.mkdir(parents=True)
    (job_dir / "job.json").write_text(json.dumps({
        "id": job_dir.name, "source": "x.wav", "status": "running",
        "created": "2026-01-01T00:00:00+00:00", "progress": [],
    }), encoding="utf-8")

    store = JobStore(root, analyze_fn=fake_analyze)
    try:
        assert store.get(job_dir.name).status == jobs.ERROR
        assert "Interrumpido" in store.get(job_dir.name).error
    finally:
        store.shutdown()


def test_importar_un_directorio_existente(store, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("episodio.webm")),
                                        encoding="utf-8")

    job = store.import_dir(out)
    assert job.status == jobs.DONE
    assert job.imported and job.result_dir == out.resolve()
    assert job.summary["phenomena_counts"]["t_deletion"] == 1
    assert store.import_dir(out).id == job.id      # idempotente


def test_importar_sin_analysis_falla(store, tmp_path):
    vacio = tmp_path / "vacio"
    vacio.mkdir()
    with pytest.raises(JobError):
        store.import_dir(vacio)


def test_borrar_no_toca_los_datos_importados(store, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    job = store.import_dir(out)

    store.delete(job.id)
    assert (out / "analysis.json").is_file()      # los datos externos siguen ahí
    assert not (store.root / job.id).exists()
    with pytest.raises(JobError):
        store.get(job.id)


def test_borrar_un_job_en_curso_espera_a_que_pare(tmp_path, media):
    """El trabajador sigue escribiendo en el directorio: borrarlo en el acto
    dejaba el análisis a medias, un FileNotFoundError tragado y —si el pipeline
    recreaba el directorio— el job resucitado al reiniciar."""
    arrancado = threading.Event()

    def lento(media_path, out_dir, progress=lambda m: None, **kw):
        arrancado.set()
        for i in range(1, 201):
            time.sleep(0.01)
            progress(f"Segmento {i}/200: …")
            Path(out_dir).mkdir(parents=True, exist_ok=True)
            (Path(out_dir) / "parcial.json").write_text("{}", encoding="utf-8")
        return mk_analysis()

    store = JobStore(tmp_path / "ws", analyze_fn=lento)
    try:
        job = store.create(media)
        arrancado.wait(5)
        store.delete(job.id)

        assert store.list() == []                       # desaparece de la UI al instante
        with pytest.raises(JobError):
            store.get(job.id)
        wait_until(lambda: job.status == jobs.CANCELLED)
        wait_until(lambda: not job.dir.exists())        # y el directorio se limpia al parar
    finally:
        store.shutdown()


def test_borrar_un_job_en_curso_no_lo_resucita_al_reiniciar(tmp_path, media):
    """El trabajador puede tardar minutos en llegar a su punto de cancelación
    (cargando modelos). Si el proceso muere antes, el job borrado no debe volver."""
    arrancado = threading.Event()

    def lento(media_path, out_dir, progress=lambda m: None, **kw):
        arrancado.set()
        time.sleep(30)                      # como cargar los modelos: sin progress()
        return mk_analysis()

    root = tmp_path / "ws"
    store = JobStore(root, analyze_fn=lento)
    job = store.create(media)
    arrancado.wait(5)
    store.delete(job.id)
    # el proceso "muere" aquí: no le damos tiempo a _finish

    revivido = JobStore(root, analyze_fn=fake_analyze)
    try:
        assert revivido.list() == []
    finally:
        revivido.shutdown()
        store.shutdown()


def test_importar_algo_que_no_es_un_analisis_falla(store, tmp_path):
    for contenido in ("[1,2,3]", "null", "{}", '{"segments": 4}'):
        raro = tmp_path / f"raro{abs(hash(contenido))}"
        raro.mkdir()
        (raro / "analysis.json").write_text(contenido, encoding="utf-8")
        with pytest.raises(JobError):
            store.import_dir(raro)


def test_importar_a_la_vez_el_mismo_directorio_crea_un_solo_job(store, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")

    listos = threading.Barrier(8)
    creados: list[str] = []
    lock = threading.Lock()

    def importar():
        listos.wait()
        job = store.import_dir(out)
        with lock:
            creados.append(job.id)

    hilos = [threading.Thread(target=importar) for _ in range(8)]
    for hilo in hilos:
        hilo.start()
    for hilo in hilos:
        hilo.join()

    assert len(set(creados)) == 1
    assert len(store.list()) == 1


def test_los_hitos_de_progreso_siguen_existiendo_en_el_codigo():
    """`estimate_percent` reconoce prefijos por texto: si alguien reescribe un
    mensaje, la barra se queda en 0 sin que nada falle. Este test ata las puntas."""
    raiz = Path(jobs.__file__).parent
    fuente = "".join((raiz / nombre).read_text(encoding="utf-8")
                     for nombre in ("pipeline.py", "download.py"))
    for prefijo, _ in jobs._STAGE_PERCENT:
        assert f'"{prefijo}' in fuente or f"f\"{prefijo}" in fuente, prefijo
    assert "Segmento {si + 1}/{n_seg}" in fuente     # el que alimenta el porcentaje fino
    assert "Descargando de YouTube… {percent} %" in fuente


def test_un_job_desde_una_url_descarga_y_luego_analiza(tmp_path):
    descargas = []

    def fake_download(url, dest_dir, audio_only=False, progress=lambda m: None):
        descargas.append((url, Path(dest_dir), audio_only))
        progress("Descargando de YouTube… 100 %")
        destino = Path(dest_dir)
        destino.mkdir(parents=True, exist_ok=True)
        archivo = destino / "Un vídeo [abc123].mp4"
        archivo.write_bytes(b"media")
        progress(f"Descargado: {archivo.name}")
        return archivo

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze,
                     download_dir=tmp_path / "downloads", download_fn=fake_download)
    try:
        job = store.create_from_url("https://youtu.be/abc123", {"whisper_model": "tiny"},
                                    audio_only=True)
        assert job.source == "https://youtu.be/abc123"      # hasta que se sepa el título
        wait_until(lambda: job.status == jobs.DONE)

        assert descargas == [("https://youtu.be/abc123", tmp_path / "downloads", True)]
        assert job.source == "Un vídeo [abc123].mp4"        # ya con el nombre real
        assert job.media_path.endswith("Un vídeo [abc123].mp4")
        assert job.artifact("analysis.json") is not None
        mensajes = [p["message"] for p in job.progress]
        assert any("Descargando" in m for m in mensajes)
        assert any("Transcribiendo" in m for m in mensajes)
        assert job.to_public()["source_url"] == "https://youtu.be/abc123"
    finally:
        store.shutdown()


def test_el_analisis_terminado_entra_en_el_corpus(tmp_path, media):
    from phonotrainer.db import Corpus

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.DONE)

        assert corpus.stats()["words"] == 5
        assert corpus.occurrences(phenomenon="t_deletion")[0]["job_id"] == job.id
        # la identidad en el corpus es el directorio del análisis, no el job:
        # así analizar por CLI e importar después no cuenta dos veces
        assert corpus.analyses()[0]["id"] == str(job.result_dir.resolve())

        store.delete(job.id)                    # y salir de la lista lo saca del corpus
        assert corpus.stats()["analyses"] == 0
    finally:
        store.shutdown()
        corpus.close()


def test_importar_tambien_indexa(tmp_path):
    from phonotrainer.db import Corpus

    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("ep.webm")), encoding="utf-8")

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        store.import_dir(out)
        assert corpus.stats()["analyses"] == 1
        assert corpus.analyses()[0]["source"] == "ep.webm"
    finally:
        store.shutdown()
        corpus.close()


def test_el_corpus_se_reconstruye_con_lo_que_ya_hay_en_el_workspace(tmp_path, media):
    """El corpus es derivado: borrar data/ (o clonar el repo, que empieza sin
    base de datos) no debe dejarlo desincronizado con la lista de análisis."""
    from phonotrainer.db import Corpus

    root = tmp_path / "ws"
    primero = Corpus(":memory:")
    store = JobStore(root, analyze_fn=fake_analyze, corpus=primero)
    job = store.create(media)
    wait_until(lambda: job.status == jobs.DONE)
    store.shutdown()
    primero.close()

    # base de datos nueva y vacía, mismo workspace
    vacio = Corpus(":memory:")
    revivido = JobStore(root, analyze_fn=fake_analyze, corpus=vacio)
    try:
        assert vacio.stats()["analyses"] == 1
        assert vacio.analyses()[0]["job_id"] == job.id
        assert vacio.occurrences(phenomenon="t_deletion")
    finally:
        revivido.shutdown()
        vacio.close()


def test_reimportar_vuelve_a_indexar(tmp_path):
    from phonotrainer.db import Corpus

    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("ep.webm")), encoding="utf-8")

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        job = store.import_dir(out)
        corpus.forget_job(job.id)                  # como si se hubiera perdido el índice
        assert corpus.stats()["analyses"] == 0

        assert store.import_dir(out).id == job.id  # sigue siendo idempotente…
        assert corpus.stats()["analyses"] == 1     # …pero recupera el índice
    finally:
        store.shutdown()
        corpus.close()


def test_borrar_mientras_corre_no_deja_fantasmas_en_el_corpus(tmp_path, media):
    """Si el trabajador termina después del borrado, su análisis no debe entrar
    al corpus: quedaba una fila que apuntaba a un job inexistente y sobrevivía a
    todos los reinicios."""
    from phonotrainer.db import Corpus

    arrancado = threading.Event()

    def lento(media_path, out_dir, progress=lambda m: None, **kw):
        arrancado.set()
        time.sleep(0.3)                 # no vuelve a llamar a progress: no se cancela solo
        return fake_analyze(media_path, out_dir, progress=lambda m: None, **kw)

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=lento, corpus=corpus)
    try:
        job = store.create(media)
        arrancado.wait(5)
        store.delete(job.id)
        wait_until(lambda: job.status in (jobs.DONE, jobs.CANCELLED, jobs.ERROR))

        assert corpus.stats()["analyses"] == 0
        assert corpus.occurrences() == []
    finally:
        store.shutdown()
        corpus.close()


def test_al_arrancar_se_purgan_las_filas_de_jobs_que_ya_no_existen(tmp_path, media):
    from phonotrainer.db import Corpus

    corpus = Corpus(":memory:")
    root = tmp_path / "ws"
    store = JobStore(root, analyze_fn=fake_analyze, corpus=corpus)
    job = store.create(media)
    wait_until(lambda: job.status == jobs.DONE)
    store.shutdown()

    _rmtree_dir(job.dir)                # como si lo hubieran borrado a mano
    assert corpus.stats()["analyses"] == 1

    revivido = JobStore(root, analyze_fn=fake_analyze, corpus=corpus)
    try:
        assert corpus.stats()["analyses"] == 0
    finally:
        revivido.shutdown()
        corpus.close()


def test_analizar_por_cli_e_importar_despues_no_cuenta_dos_veces(tmp_path):
    """El flujo del README: `analyze -o out/` y luego `ui --import-dir out/`."""
    from phonotrainer.db import Corpus
    from phonotrainer.jobs import analysis_key

    out = tmp_path / "out"
    out.mkdir()
    analysis = mk_analysis("ep.webm")
    (out / "analysis.json").write_text(json.dumps(analysis), encoding="utf-8")

    corpus = Corpus(":memory:")
    corpus.index_analysis(analysis_key(out), analysis, source="ep.webm",
                          result_dir=str(out))       # lo que hace la CLI

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        store.import_dir(out)                        # y ahora la interfaz
        assert corpus.stats()["analyses"] == 1       # el mismo material, una entrada
        assert corpus.stats()["words"] == 5
        assert corpus.analyses()[0]["job_id"] is not None
    finally:
        store.shutdown()
        corpus.close()


def _rmtree_dir(path):
    import shutil

    shutil.rmtree(path, ignore_errors=True)


def test_si_el_corpus_falla_el_analisis_sigue_siendo_valido(tmp_path, media):
    class CorpusRoto:
        def index_analysis(self, *a, **kw):
            raise RuntimeError("disco lleno")

        def forget(self, *a, **kw):
            pass

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=CorpusRoto())
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.DONE)
        assert job.artifact("analysis.json") is not None
        assert any("no se pudo indexar" in p["message"] for p in job.progress)
    finally:
        store.shutdown()


def test_crear_con_ruta_inexistente_falla(store, tmp_path):
    with pytest.raises(JobError):
        store.create(tmp_path / "no-existe.wav")


def test_subida_copia_el_archivo_al_job(store):
    job = store.adopt_upload("subido.wav", lambda dest: dest.write_bytes(b"RIFFdata"))
    _done(store, job)
    assert job.media_path.endswith("/media/subido.wav")
    assert job.to_public()["has_media"] is True
