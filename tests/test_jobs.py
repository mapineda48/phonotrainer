"""Store de trabajos: ejecución en hilo, progreso, cancelación y persistencia."""

import json
import time

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
    assert estimate_percent("Extrayendo audio (ffmpeg…)") == 3
    assert estimate_percent("Segmento 1/10: fonos…") == 32
    assert estimate_percent("Segmento 10/10: fonos…") == 95
    assert estimate_percent("Generando report.html…") == 98
    assert estimate_percent("mensaje que no reconocemos") == 0


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


def test_crear_con_ruta_inexistente_falla(store, tmp_path):
    with pytest.raises(JobError):
        store.create(tmp_path / "no-existe.wav")


def test_subida_copia_el_archivo_al_job(store):
    job = store.adopt_upload("subido.wav", lambda dest: dest.write_bytes(b"RIFFdata"))
    _done(store, job)
    assert job.media_path.endswith("/media/subido.wav")
    assert job.to_public()["has_media"] is True
