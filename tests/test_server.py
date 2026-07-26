"""API HTTP: ciclo completo (analizar → consultar → revisar) sin cargar modelos."""

import json

import pytest
from conftest import fake_analyze, mk_analysis, wait_until
from fastapi.testclient import TestClient

from phonotrainer.jobs import JobStore
from phonotrainer.server import create_app


@pytest.fixture
def store(tmp_path):
    st = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze)
    yield st
    st.shutdown()


@pytest.fixture
def client(store, tmp_path):
    app = create_app(store=store, web_dist=tmp_path / "sin-compilar")
    with TestClient(app) as c:
        yield c


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.wav"
    path.write_bytes(b"RIFF fake media")
    return path


def analizado(client, media, **options) -> str:
    """Crea un job y espera a que termine; devuelve su id."""
    resp = client.post("/api/jobs", json={"path": str(media), "options": options})
    assert resp.status_code == 201, resp.text
    job_id = resp.json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")
    return job_id


def test_health_y_referencia(client):
    health = client.get("/api/health").json()
    assert health["ok"] is True and health["web_built"] is False

    ref = client.get("/api/reference").json()
    assert ref["family_of"]["flapping"] == "td"
    assert ref["labels"]["vowel_reduction"] == "reducción vocálica"
    assert [f["key"] for f in ref["families"]] == ["red", "td", "asim", "fron"]
    assert ref["verdicts"] == ["ok", "mal", "dudosa"]


def test_analizar_archivo_local_de_principio_a_fin(client, media):
    job_id = analizado(client, media, whisper_model="tiny")

    job = client.get(f"/api/jobs/{job_id}").json()
    assert job["percent"] == 100 and job["has_analysis"] and job["has_audio"]
    assert [p["message"] for p in job["progress"]][-1].startswith("Generando report")
    assert job["summary"]["words"] == 5

    analysis = client.get(f"/api/jobs/{job_id}/analysis").json()
    assert analysis["segments"][0]["words"][0]["word"] == "does"
    assert analysis["meta"]["options"]["whisper_model"] == "tiny"

    assert client.get("/api/jobs").json()[0]["id"] == job_id


def test_ruta_inexistente_da_400(client, tmp_path):
    resp = client.post("/api/jobs", json={"path": str(tmp_path / "nope.wav")})
    assert resp.status_code == 400
    assert "no existe" in resp.json()["detail"]


def test_opciones_invalidas_dan_422(client, media):
    resp = client.post("/api/jobs", json={"path": str(media),
                                          "options": {"whisper_model": "gigante"}})
    assert resp.status_code == 422


def test_subida_de_archivo(client):
    resp = client.post(
        "/api/jobs/upload",
        files={"file": ("subido.wav", b"RIFF fake", "audio/wav")},
        data={"options": json.dumps({"attraction": False})},
    )
    assert resp.status_code == 201
    job_id = resp.json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")

    job = client.get(f"/api/jobs/{job_id}").json()
    assert job["source"] == "subido.wav" and job["has_media"] is True
    assert job["options"]["attraction"] is False


def test_audio_soporta_range_para_hacer_seek(client, media):
    job_id = analizado(client, media)

    completo = client.get(f"/api/jobs/{job_id}/audio")
    assert completo.status_code == 200
    assert completo.headers["accept-ranges"] == "bytes"

    parcial = client.get(f"/api/jobs/{job_id}/audio", headers={"Range": "bytes=0-99"})
    assert parcial.status_code == 206
    assert len(parcial.content) == 100
    assert parcial.headers["content-range"].startswith("bytes 0-99/")


def test_media_original_se_sirve_para_el_video(client, media):
    job_id = analizado(client, media)
    resp = client.get(f"/api/jobs/{job_id}/media")
    assert resp.status_code == 200 and resp.content == media.read_bytes()


def test_artefacto_que_aun_no_existe_da_404(client, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(out)}).json()["id"]

    assert client.get(f"/api/jobs/{job_id}/audio").status_code == 404
    assert client.get(f"/api/jobs/{job_id}/media").status_code == 404
    assert client.get(f"/api/jobs/{job_id}/analysis").status_code == 200


def test_importar_directorio_existente(client, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("episodio.webm")),
                                        encoding="utf-8")

    job = client.post("/api/jobs/import", json={"path": str(out)}).json()
    assert job["status"] == "done" and job["imported"] is True
    assert job["source"] == "episodio.webm"
    assert job["summary"]["segments"] == 2


def test_job_desconocido_da_404(client):
    assert client.get("/api/jobs/no-existe").status_code == 404
    assert client.get("/api/jobs/no-existe/analysis").status_code == 404
    assert client.get("/api/inventado").status_code == 404


def test_borrar_job(client, media):
    job_id = analizado(client, media)
    assert client.delete(f"/api/jobs/{job_id}").status_code == 204
    assert client.get(f"/api/jobs/{job_id}").status_code == 404
    assert client.get("/api/jobs").json() == []


def test_muestreo_de_revision_es_reproducible(client, media):
    job_id = analizado(client, media)

    a = client.get(f"/api/jobs/{job_id}/review/sample", params={"n": 3, "seed": 48}).json()
    b = client.get(f"/api/jobs/{job_id}/review/sample", params={"n": 3, "seed": 48}).json()
    assert a == b
    assert len(a["items"]) == 3
    primero = a["items"][0]
    assert primero["word"]["word"]                      # la palabra completa
    assert primero["segment_text"]                      # y su contexto


def test_guardar_revision_escribe_review_json(client, media, store):
    job_id = analizado(client, media)
    resp = client.put(f"/api/jobs/{job_id}/review", json={
        "seed": 7,
        "verdicts": [
            {"segment": 0, "word_idx": 0, "verdict": "ok"},
            {"segment": 0, "word_idx": 1, "verdict": "mal", "note": "es una pausa"},
            {"segment": 1, "word_idx": 0, "verdict": "dudosa"},
        ],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert (body["ok"], body["mal"], body["dudosa"]) == (1, 1, 1)
    assert body["accuracy"] == 0.5

    # persistido con la misma forma que escribe la CLI
    guardado = json.loads((store.get(job_id).result_dir / "review.json")
                          .read_text(encoding="utf-8"))
    assert guardado["seed"] == 7
    assert guardado["items"][1]["word"] == "that"
    assert guardado["items"][1]["note"] == "es una pausa"
    assert guardado["items"][1]["phenomena"] == ["t_deletion"]

    assert client.get(f"/api/jobs/{job_id}/review").json()["accuracy"] == 0.5
    assert client.get(f"/api/jobs/{job_id}").json()["has_review"] is True


def test_revision_vacia_antes_de_revisar(client, media):
    job_id = analizado(client, media)
    assert client.get(f"/api/jobs/{job_id}/review").json() == {
        "items": [], "sampled": 0, "ok": 0, "mal": 0, "dudosa": 0,
        "accuracy": None, "seed": 48,
    }


def test_veredicto_fuera_de_rango_da_400(client, media):
    job_id = analizado(client, media)
    resp = client.put(f"/api/jobs/{job_id}/review", json={
        "verdicts": [{"segment": 9, "word_idx": 99, "verdict": "ok"}]})
    assert resp.status_code == 400
    assert "fuera de rango" in resp.json()["detail"]


@pytest.fixture
def home(tmp_path, monkeypatch):
    """$HOME simulado: el explorador nunca debe salir de ahí."""
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setattr("pathlib.Path.home", staticmethod(lambda: home))
    return home


def test_explorador_lista_media_y_directorios(client, home):
    (home / "videos").mkdir()
    (home / "videos" / "ep1.webm").write_bytes(b"x")
    (home / "salida").mkdir()
    (home / "salida" / "analysis.json").write_text("{}", encoding="utf-8")
    (home / "notas.txt").write_text("no es media", encoding="utf-8")

    raiz = client.get("/api/browse", params={"path": str(home)}).json()
    assert {d["name"] for d in raiz["dirs"]} == {"videos", "salida"}
    assert raiz["files"] == []                                   # notas.txt no es media
    assert [d["has_analysis"] for d in raiz["dirs"] if d["name"] == "salida"] == [True]
    assert raiz["parent"] is None                                # no se sube de $HOME

    dentro = client.get("/api/browse", params={"path": str(home / "videos")}).json()
    assert [f["name"] for f in dentro["files"]] == ["ep1.webm"]
    assert dentro["parent"] == str(home)


def test_el_explorador_no_sale_de_home(client, home):
    fuera = client.get("/api/browse", params={"path": "/etc"}).json()
    assert fuera["path"] == str(home)


def test_sin_compilar_explica_como_compilar(client):
    resp = client.get("/")
    assert resp.status_code == 200
    assert "npm install" in resp.text


def test_sirve_la_spa_compilada(store, tmp_path):
    dist = tmp_path / "dist"
    (dist / "assets").mkdir(parents=True)
    (dist / "index.html").write_text("<html>app</html>", encoding="utf-8")
    (dist / "assets" / "app.js").write_text("console.log(1)", encoding="utf-8")

    with TestClient(create_app(store=store, web_dist=dist)) as client:
        assert client.get("/").text == "<html>app</html>"
        assert client.get("/assets/app.js").text == "console.log(1)"
        assert client.get("/job/abc").text == "<html>app</html>"   # rutas del router
        assert client.get("/api/health").json()["web_built"] is True
