"""WebSocket /ws/jobs: la interfaz recibe el estado empujado, sin sondeo."""

import pytest
from conftest import fake_analyze
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from phonotrainer.db import Corpus
from phonotrainer.jobs import JobStore
from phonotrainer.server import create_app


@pytest.fixture
def store(tmp_path):
    st = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze,
                  corpus=Corpus(":memory:"))
    yield st
    st.shutdown()


@pytest.fixture
def client(store, tmp_path):
    app = create_app(store=store, web_dist=tmp_path / "sin-compilar",
                     allowed_roots=[tmp_path])
    with TestClient(app) as c:
        yield c


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.wav"
    path.write_bytes(b"RIFF fake media")
    return path


def test_snapshot_y_eventos_del_ciclo_completo(client, media):
    with client.websocket_connect("/ws/jobs") as ws:
        assert ws.receive_json() == {"type": "snapshot", "jobs": []}

        resp = client.post("/api/jobs", json={"path": str(media)})
        assert resp.status_code == 201
        job_id = resp.json()["id"]

        # Los eventos llegan solos: creado → corriendo → progreso → terminado.
        # Cada uno trae el job COMPLETO y fresco (idempotente).
        vistos = []
        for _ in range(60):
            msg = ws.receive_json()
            assert msg["type"] == "job" and msg["job"]["id"] == job_id
            vistos.append(msg["job"])
            if msg["job"]["status"] == "done":
                break
        else:
            pytest.fail("no llegó el evento 'done' del análisis")

        assert vistos[0]["status"] in ("queued", "running")
        assert vistos[-1]["percent"] == 100
        assert vistos[-1]["has_analysis"] is True
        # y el log de progreso crece dentro del propio evento
        assert vistos[-1]["progress"][-1]["message"].startswith("Generando report")

        # Guardar una revisión toca el job por fuera del store: también avisa
        resp = client.put(f"/api/jobs/{job_id}/review", json={"verdicts": []})
        assert resp.status_code == 200
        msg = ws.receive_json()
        assert msg["type"] == "job" and msg["job"]["has_review"] is True

        client.delete(f"/api/jobs/{job_id}")
        assert ws.receive_json() == {"type": "deleted", "id": job_id}


def test_el_snapshot_llega_con_lo_que_ya_habia(client, media):
    resp = client.post("/api/jobs/import", json={"path": str(media.parent)})
    assert resp.status_code == 400    # sin analysis.json no se importa: creamos uno
    import json
    from conftest import mk_analysis
    (media.parent / "analysis.json").write_text(json.dumps(mk_analysis()),
                                                encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(media.parent)}).json()["id"]

    with client.websocket_connect("/ws/jobs") as ws:
        snap = ws.receive_json()
        assert snap["type"] == "snapshot"
        assert [j["id"] for j in snap["jobs"]] == [job_id]
        assert snap["jobs"][0]["progress"] == []        # full=True pero vacío


def test_un_origen_ajeno_no_puede_leer_el_canal(client):
    """Un WebSocket no pasa por CORS: una página de internet no debe poder
    leer tu lista de análisis aunque el servidor sea localhost."""
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect(
            "/ws/jobs", headers={"Origin": "https://evil.example"}
        ):
            pass

    # la SPA (mismo host) y el proxy de Vite (loopback) sí pasan
    with client.websocket_connect(
        "/ws/jobs", headers={"Origin": "http://localhost:5173"}
    ) as ws:
        assert ws.receive_json()["type"] == "snapshot"
