"""API HTTP: ciclo completo (analizar → consultar → revisar) sin cargar modelos."""

import json

import pytest
from conftest import fake_analyze, mk_analysis, wait_until
from fastapi.testclient import TestClient

from phonotrainer.db import Corpus
from phonotrainer.jobs import JobStore
from phonotrainer.server import create_app


@pytest.fixture
def corpus():
    db = Corpus(":memory:")
    yield db
    db.close()


@pytest.fixture
def store(tmp_path, corpus):
    st = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze, corpus=corpus,
                  download_dir=tmp_path / "downloads",
                  download_fn=_fake_download)
    yield st
    st.shutdown()


def _fake_download(url, dest_dir, audio_only=False, progress=lambda m: None):
    """Doble de la descarga: los tests no salen a la red."""
    from pathlib import Path

    progress("Descargando de YouTube… 100 %")
    destino = Path(dest_dir)
    destino.mkdir(parents=True, exist_ok=True)
    archivo = destino / "Un vídeo [abc123].mp4"
    archivo.write_bytes(b"media")
    progress(f"Descargado: {archivo.name}")
    return archivo


@pytest.fixture
def client(store, tmp_path):
    # tmp_path como raíz permitida: los tests no dependen del $HOME real.
    app = create_app(store=store, web_dist=tmp_path / "sin-compilar",
                     allowed_roots=[tmp_path])
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
    # la UI puebla sus selectores con esto, no con listas copiadas a mano
    assert ref["options"]["whisper_models"] == ["tiny", "base", "small", "medium"]
    assert ref["options"]["phone_engines"] == ["wav2vec2", "allosaurus"]
    assert ref["options"]["defaults"]["whisper_model"] == "small"
    assert ref["review"] == {"default_n": 20, "default_seed": 48, "max_n": 500}
    # sin esto la UI no sabría qué es "flapping" ni cómo tokenizar /aɪ/
    assert "erre suave" in ref["descriptions"]["flapping"]
    assert set(ref["descriptions"]) == set(ref["labels"])
    assert "aɪ" in ref["ipa_tokens"] and "tʃ" in ref["ipa_tokens"]
    assert ref["ipa_tokens"] == sorted(ref["ipa_tokens"], key=len, reverse=True)


def test_el_report_importado_va_en_sandbox(client, tmp_path):
    """Un out/ puede venir de fuera: su report.html no debe poder hablar con
    esta API desde el origen de la interfaz."""
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    (out / "report.html").write_text("<script>fetch('/api/browse')</script>", encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(out)}).json()["id"]

    resp = client.get(f"/api/jobs/{job_id}/report")
    assert resp.status_code == 200
    assert "sandbox" in resp.headers["content-security-policy"]
    assert resp.headers["x-content-type-options"] == "nosniff"


def test_las_peticiones_de_otro_origen_se_rechazan(client, tmp_path):
    """multipart no lleva preflight: sin esto, cualquier web abierta podría
    encolar análisis en la máquina del usuario."""
    resp = client.post(
        "/api/jobs/upload",
        files={"file": ("x.wav", b"RIFF", "audio/wav")},
        headers={"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"},
    )
    assert resp.status_code == 403

    # el mismo origen (y las herramientas de consola, que no mandan la cabecera) pasan
    assert client.get("/api/jobs", headers={"Sec-Fetch-Site": "cross-site"}).status_code == 200
    ok = client.post("/api/jobs/upload", files={"file": ("x.wav", b"RIFF", "audio/wav")},
                     headers={"Sec-Fetch-Site": "same-origin"})
    assert ok.status_code == 201


def test_solo_se_suben_archivos_de_media(client):
    resp = client.post("/api/jobs/upload", files={"file": ("payload.html", b"<script>", "text/html")})
    assert resp.status_code == 400
    assert "video ni un audio" in resp.json()["detail"]


def test_un_analysis_con_otra_forma_no_da_500(client, tmp_path):
    for contenido in ("[1,2,3]", "null", "{}", '{"segments": {"a": 1}}'):
        raro = tmp_path / f"raro{abs(hash(contenido))}"
        raro.mkdir()
        (raro / "analysis.json").write_text(contenido, encoding="utf-8")
        resp = client.post("/api/jobs/import", json={"path": str(raro)})
        assert resp.status_code == 400, contenido


def test_rutas_imposibles_dan_400(client):
    for ruta in ("/tmp/con\x00nulo.wav", "~usuarioquenoexiste999/x.wav"):
        assert client.post("/api/jobs", json={"path": ruta}).status_code == 400
    assert client.get("/api/browse", params={"path": "~usuarioquenoexiste999"}).status_code == 400


def test_no_se_permite_la_raiz_del_sistema_como_raiz(store, tmp_path):
    import pytest as _pytest

    with _pytest.raises(ValueError):
        create_app(store=store, web_dist=tmp_path, allowed_roots=["/"])


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


def test_no_se_puede_analizar_fuera_de_las_raices(client, tmp_path):
    """La interfaz no debe convertirse en un lector de archivos del sistema."""
    resp = client.post("/api/jobs", json={"path": "/etc/passwd"})
    assert resp.status_code == 403
    assert "seguridad" in resp.json()["detail"]

    fuera = tmp_path.parent / "fuera.wav"
    fuera.write_bytes(b"RIFF")
    try:
        assert client.post("/api/jobs", json={"path": str(fuera)}).status_code == 403
        assert client.post("/api/jobs/import",
                           json={"path": str(tmp_path.parent)}).status_code == 403
    finally:
        fuera.unlink()


def test_solo_se_analizan_archivos_de_media(client, tmp_path):
    texto = tmp_path / "notas.txt"
    texto.write_text("no soy un video", encoding="utf-8")
    resp = client.post("/api/jobs", json={"path": str(texto)})
    assert resp.status_code == 400
    assert "video ni un audio" in resp.json()["detail"]


def test_el_media_original_nunca_se_sirve_como_html(client, tmp_path):
    """Adivinar el Content-Type permitiría servir HTML —y por tanto JS— desde
    el mismo origen que la interfaz."""
    trampa = tmp_path / "trampa.webm"
    trampa.write_bytes(b"<script>alert(1)</script>")
    job_id = client.post("/api/jobs", json={"path": str(trampa)}).json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")

    resp = client.get(f"/api/jobs/{job_id}/media")
    assert resp.headers["content-type"].startswith("video/webm")


def test_opciones_de_subida_no_json_objeto_dan_400(client):
    for payload in ("[]", '"hola"', "5", "{no json"):
        resp = client.post(
            "/api/jobs/upload",
            files={"file": ("x.wav", b"RIFF", "audio/wav")},
            data={"options": payload},
        )
        assert resp.status_code == 400, payload
        assert "opciones inválidas" in resp.json()["detail"]


def test_analysis_corrupto_da_400_y_no_500(client, tmp_path):
    roto = tmp_path / "roto"
    roto.mkdir()
    (roto / "analysis.json").write_text("{ esto no es json", encoding="utf-8")

    resp = client.post("/api/jobs/import", json={"path": str(roto)})
    assert resp.status_code == 400
    assert "ilegible" in resp.json()["detail"]

    # y si se corrompe después de importarlo, las rutas que lo leen tampoco revientan
    (roto / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(roto)}).json()["id"]
    (roto / "analysis.json").write_text("{ roto otra vez", encoding="utf-8")
    assert client.get(f"/api/jobs/{job_id}/review/sample").status_code == 400
    assert client.put(f"/api/jobs/{job_id}/review", json={"verdicts": []}).status_code == 400


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


def test_analizar_una_url_de_youtube(client):
    resp = client.post("/api/jobs/youtube", json={
        "url": "https://youtu.be/abc123",
        "options": {"whisper_model": "tiny"},
        "audio_only": True,
    })
    assert resp.status_code == 201
    job_id = resp.json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")

    job = client.get(f"/api/jobs/{job_id}").json()
    assert job["source_url"] == "https://youtu.be/abc123"
    assert job["source"] == "Un vídeo [abc123].mp4"
    assert job["has_media"] is True and job["is_video"] is True
    assert any("Descargando" in p["message"] for p in job["progress"])


def test_una_url_que_no_lo_es_da_400(client):
    resp = client.post("/api/jobs/youtube", json={"url": "/home/yo/video.webm"})
    assert resp.status_code == 400
    assert "no parece una URL" in resp.json()["detail"]


def test_el_corpus_acumula_lo_analizado(client, media):
    vacio = client.get("/api/corpus/stats").json()
    assert vacio["analyses"] == 0 and vacio["phenomena"] == []

    analizado(client, media)
    analizado(client, media)

    stats = client.get("/api/corpus/stats").json()
    assert stats["analyses"] == 2
    assert stats["words"] == 10
    assert {p["phenomenon"] for p in stats["phenomena"]} >= {"t_deletion", "vowel_reduction"}
    assert stats["top_words"][0]["word"] in {"does", "that", "wanna"}


def test_el_corpus_lista_apariciones_con_su_posicion(client, media):
    job_id = analizado(client, media)

    body = client.get("/api/corpus/occurrences", params={"phenomenon": "t_deletion"}).json()
    assert body["phenomenon"] == "t_deletion"
    (item,) = body["items"]
    # con esto la interfaz puede abrir el análisis justo en esa palabra
    assert item["job_id"] == job_id
    assert (item["word"], item["segment"], item["word_idx"]) == ("that", 0, 1)
    assert item["analysis_source"] == "clip.wav"


def test_el_corpus_responde_como_se_ha_dicho_una_palabra(client, media):
    analizado(client, media)
    analizado(client, media)

    body = client.get("/api/corpus/variants", params={"word": "That,"}).json()
    assert body["variants"][0]["realized_ipa"] == "ðæ"
    assert body["variants"][0]["count"] == 2          # una vez por análisis


def test_borrar_un_analisis_lo_saca_del_corpus(client, media):
    job_id = analizado(client, media)
    assert client.get("/api/corpus/stats").json()["analyses"] == 1

    client.delete(f"/api/jobs/{job_id}")
    assert client.get("/api/corpus/stats").json()["analyses"] == 0


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
def home(tmp_path):
    """Raíz permitida simulada: el explorador nunca debe salir de ahí."""
    home = tmp_path / "home"
    home.mkdir()
    return home


@pytest.fixture
def browser(store, home, tmp_path):
    app = create_app(store=store, web_dist=tmp_path / "nada", allowed_roots=[home])
    with TestClient(app) as c:
        yield c


def test_explorador_lista_media_y_directorios(browser, home):
    (home / "videos").mkdir()
    (home / "videos" / "ep1.webm").write_bytes(b"x")
    (home / "salida").mkdir()
    (home / "salida" / "analysis.json").write_text("{}", encoding="utf-8")
    (home / "notas.txt").write_text("no es media", encoding="utf-8")

    raiz = browser.get("/api/browse", params={"path": str(home)}).json()
    assert {d["name"] for d in raiz["dirs"]} == {"videos", "salida"}
    assert raiz["files"] == []                                   # notas.txt no es media
    assert [d["has_analysis"] for d in raiz["dirs"] if d["name"] == "salida"] == [True]
    assert raiz["parent"] is None                                # no se sube de la raíz

    dentro = browser.get("/api/browse", params={"path": str(home / "videos")}).json()
    assert [f["name"] for f in dentro["files"]] == ["ep1.webm"]
    assert dentro["parent"] == str(home)


def test_el_explorador_no_sale_de_la_raiz(browser, home):
    fuera = browser.get("/api/browse", params={"path": "/etc"}).json()
    assert fuera["path"] == str(home)
    assert browser.get("/api/browse", params={"path": str(home.parent)}).json()["path"] == str(home)


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
