"""WebSocket /ws/jobs: the interface gets the state pushed to it, with no polling."""

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
    app = create_app(store=store, web_dist=tmp_path / "not-built",
                     allowed_roots=[tmp_path])
    with TestClient(app) as c:
        yield c


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.wav"
    path.write_bytes(b"RIFF fake media")
    return path


def test_snapshot_and_events_of_the_whole_lifecycle(client, media):
    with client.websocket_connect("/ws/jobs") as ws:
        assert ws.receive_json() == {"type": "snapshot", "jobs": []}

        resp = client.post("/api/jobs", json={"path": str(media)})
        assert resp.status_code == 201
        job_id = resp.json()["id"]

        # The events arrive on their own: created → running → progress → done.
        # Each carries the COMPLETE, fresh job (idempotent).
        seen = []
        for _ in range(60):
            msg = ws.receive_json()
            assert msg["type"] == "job" and msg["job"]["id"] == job_id
            seen.append(msg["job"])
            if msg["job"]["status"] == "done":
                break
        else:
            pytest.fail("the 'done' event of the analysis never arrived")

        assert seen[0]["status"] in ("queued", "running")
        assert seen[-1]["percent"] == 100
        assert seen[-1]["has_analysis"] is True
        # and the progress log grows inside the event itself: the last stage of
        # the pipeline is generating the report
        assert "report.html" in seen[-1]["progress"][-1]["message"]

        # Saving a review touches the job outside the store: it also notifies
        resp = client.put(f"/api/jobs/{job_id}/review", json={"verdicts": []})
        assert resp.status_code == 200
        msg = ws.receive_json()
        assert msg["type"] == "job" and msg["job"]["has_review"] is True

        client.delete(f"/api/jobs/{job_id}")
        assert ws.receive_json() == {"type": "deleted", "id": job_id}


def test_the_snapshot_arrives_with_what_was_already_there(client, media):
    resp = client.post("/api/jobs/import", json={"path": str(media.parent)})
    # without an analysis.json there is nothing to import: we create one below
    assert resp.status_code == 400
    import json
    from conftest import mk_analysis
    (media.parent / "analysis.json").write_text(json.dumps(mk_analysis()),
                                                encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(media.parent)}).json()["id"]

    with client.websocket_connect("/ws/jobs") as ws:
        snap = ws.receive_json()
        assert snap["type"] == "snapshot"
        assert [j["id"] for j in snap["jobs"]] == [job_id]
        assert snap["jobs"][0]["progress"] == []        # full=True but empty


def test_a_foreign_origin_cannot_read_the_channel(client):
    """A WebSocket does not go through CORS: a page on the internet must not be
    able to read your list of analyses even though the server is localhost."""
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect(
            "/ws/jobs", headers={"Origin": "https://evil.example"}
        ):
            pass

    # the SPA (same host) and Vite's proxy (loopback) do get through
    with client.websocket_connect(
        "/ws/jobs", headers={"Origin": "http://localhost:5173"}
    ) as ws:
        assert ws.receive_json()["type"] == "snapshot"
