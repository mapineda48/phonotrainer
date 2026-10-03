"""WebSocket /ws/jobs: the interface gets the state pushed to it, with no polling."""

import json

import pytest
from conftest import fake_analyze, wait_until
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


STATUS_ORDER = {"queued": 0, "running": 1, "done": 2}


def test_snapshot_and_events_of_the_whole_lifecycle(client, store, media):
    with client.websocket_connect("/ws/jobs") as ws:
        assert ws.receive_json() == {"type": "snapshot", "jobs": []}

        # Count the store's events ourselves. The socket re-reads the job at
        # SEND time, so a message says nothing about WHEN its event happened:
        # when the sender lags behind the worker (a loaded machine), the very
        # first message can already say "done" and several in a row can be the
        # same state. What is guaranteed is one message per event, in order,
        # never going backwards.
        events: list[str] = []
        store.subscribe(lambda event, job_id: events.append(event))

        resp = client.post("/api/jobs", json={"path": str(media)})
        assert resp.status_code == 201
        job_id = resp.json()["id"]

        wait_until(lambda: store.get(job_id).status == "done")
        # Barrier: the worker has emitted its last event (the one after "done").
        # The rest of the test only needs request threads, not the worker.
        store.shutdown(wait=True)
        assert set(events) == {"upsert"}

        seen = []
        for _ in events:
            msg = ws.receive_json()
            assert msg["type"] == "job" and msg["job"]["id"] == job_id
            seen.append(msg["job"])

        # Each message carries the COMPLETE, fresh job, so applying them is
        # idempotent and the client never moves backwards.
        for before, after in zip(seen, seen[1:]):
            assert STATUS_ORDER[after["status"]] >= STATUS_ORDER[before["status"]]
            assert after["percent"] >= before["percent"]
            assert len(after["progress"]) >= len(before["progress"])
        assert seen[-1]["status"] == "done"
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



def test_a_real_server_shuts_down_after_a_tab_closed(store, tmp_path):
    """With no events flowing, the handler must still notice the tab closing.
    One stuck in its queue kept its store listener forever, and uvicorn's
    shutdown waited on it: Ctrl-C on `phonotrainer ui` hung. TestClient cancels
    the handler on exit, which hides this; a real server and socket do not."""
    import asyncio
    import socket
    import threading

    import uvicorn
    import websockets

    app = create_app(store=store, web_dist=tmp_path / "not-built", allowed_roots=[tmp_path])
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port,
                                           log_level="warning", lifespan="off"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        wait_until(lambda: server.started, timeout=10)

        async def visit() -> str:
            async with websockets.connect(f"ws://127.0.0.1:{port}/ws/jobs",
                                          origin=f"http://127.0.0.1:{port}") as ws:
                return json.loads(await ws.recv())["type"]

        assert asyncio.run(visit()) == "snapshot"
        wait_until(lambda: not store._listeners, timeout=5)   # the handler let go

        server.should_exit = True
        thread.join(timeout=10)
        assert not thread.is_alive(), "the server did not shut down"
    finally:
        server.force_exit = True
        server.should_exit = True
        thread.join(timeout=5)
