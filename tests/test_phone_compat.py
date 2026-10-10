"""The browser client is unaffected (server-contract 1.3; S-21)."""

import json
import re
from pathlib import Path

import pytest
from conftest import loopback, wait_until
from fastapi.testclient import TestClient

from phonotrainer import jobs
from phonotrainer.server import API_VERSION, create_app

ROOT = Path(__file__).resolve().parents[1]

# The shapes the browser client reads today (API 5), key for key.
PUBLIC_JOB_KEYS = {"id", "source", "source_url", "status", "options", "created", "started",
                   "finished", "error", "imported", "result_dir", "percent", "last_message",
                   "has_analysis", "has_audio", "has_dialogue_audio", "has_report",
                   "has_review", "has_media", "is_video", "meta", "summary"}
HEALTH_KEYS = {"ok", "version", "api_version", "workspace", "web_built"}
JOB_JSON_KEYS = {"id", "source", "status", "options", "media_path", "source_url", "audio_only",
                 "result_dir_override", "created", "started", "finished", "progress", "error",
                 "meta", "summary"}


def test_the_web_clients_required_version_is_met():
    source = (ROOT / "web" / "src" / "api.ts").read_text(encoding="utf-8")
    required = int(re.search(r"export const REQUIRED_API_VERSION\s*=\s*(\d+)", source).group(1))
    assert required <= API_VERSION
    assert API_VERSION == 6


@pytest.fixture
def browser_app(phone_store, tmp_path):
    app = create_app(store=phone_store, web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    with TestClient(app):
        yield app


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.mp4"
    path.write_bytes(b"RIFF fake media")
    return path


def test_shapes_are_unchanged_with_phone_access_off(browser_app, phone_store, media):
    client = loopback(browser_app)
    created = client.post("/api/jobs", json={"path": str(media)})
    assert created.status_code == 201
    job_id = created.json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")
    assert set(client.get("/api/health").json()) == HEALTH_KEYS
    assert set(client.get("/api/jobs").json()[0]) == PUBLIC_JOB_KEYS
    assert set(client.get(f"/api/jobs/{job_id}").json()) == PUBLIC_JOB_KEYS | {"progress"}
    with client.websocket_connect("ws://127.0.0.1:8000/ws/jobs") as ws:
        snapshot = ws.receive_json()
    assert set(snapshot) == {"type", "jobs"}
    assert set(snapshot["jobs"][0]) == PUBLIC_JOB_KEYS | {"progress"}
    # job.json of a browser job gains no key
    stored = json.loads((phone_store.root / job_id / "job.json").read_text())
    assert set(stored) == JOB_JSON_KEYS


def test_the_same_shapes_with_phone_access_on(phone_app, media):
    client = loopback(phone_app)
    job_id = client.post("/api/jobs", json={"path": str(media)}).json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")
    assert set(client.get("/api/health").json()) == HEALTH_KEYS
    assert set(client.get("/api/jobs").json()[0]) == PUBLIC_JOB_KEYS


@pytest.mark.parametrize("host", ["127.0.0.1:8000", "localhost:5173"])
def test_the_hosts_the_browser_and_vite_send_still_pass(browser_app, host):
    client = loopback(browser_app)
    assert client.get("/api/health", headers={"Host": host}).status_code == 200
    assert client.get("/api/jobs", headers={"Host": host}).status_code == 200


def test_phone_fields_stay_out_of_the_public_shape(phone_store):
    job = phone_store.create_from_url("https://youtu.be/abc123def45", device_id="dev_x",
                                      client_request_id="00000000-0000-4000-8000-000000000001",
                                      origin="phone")
    assert set(job.to_public()) == PUBLIC_JOB_KEYS
    stored = json.loads((job.dir / "job.json").read_text())
    assert stored["device_id"] == "dev_x" and stored["origin"] == "phone"
    assert stored["client_request_id"] == "00000000-0000-4000-8000-000000000001"
    # and they survive a restart
    wait_until(lambda: phone_store.get(job.id).status in ("done", "error"))
    reloaded = jobs._load_job(job.dir)
    assert (reloaded.device_id, reloaded.origin) == ("dev_x", "phone")


def test_download_progress_texts_are_unchanged():
    assert jobs._DOWNLOAD_RE.pattern == "Downloading from YouTube… (\\d+)%"
    prefixes = [prefix for prefix, _ in jobs._STAGE_PERCENT]
    assert prefixes[:4] == ["Looking up the URL", "Downloading from YouTube",
                            "Download finished", "Downloaded:"]
    source = (ROOT / "phonotrainer" / "download.py").read_text(encoding="utf-8")
    for text in ('progress(f"Downloading from YouTube… {percent}%")',
                 'progress(f"Downloading from YouTube… {mb} MB")',
                 'progress("Download finished, preparing the file…")',
                 'progress("Looking up the URL…")',
                 'progress(f"Downloaded: {path.name}")'):
        assert text in source


def test_pyproject_lists_the_phone_package():
    text = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
    packages = re.search(r"^packages\s*=\s*\[([^\]]*)\]", text, re.M).group(1)
    assert '"phonotrainer.phone"' in packages and '"phonotrainer"' in packages


def test_the_stable_entry_point_of_the_host_side_fake_computer(tmp_path):
    """`android/scripts/device-qa/host_computer.py` (WP-06) builds the server from
    these names; their shape is part of the contract (server-contract 10.1, 12)."""
    import dataclasses

    from phonotrainer.jobs import JobStore
    from phonotrainer.phone.identity import enable_phone_access
    from phonotrainer.phone.settings import PhoneSettings

    names = [f.name for f in dataclasses.fields(PhoneSettings)]
    assert names[:9] == ["config_dir", "clock", "resolver", "media_probe", "disk_usage",
                         "netinfo", "which", "runner", "discovery_port"]
    settings = PhoneSettings(config_dir=tmp_path / "config")
    store = JobStore(tmp_path / "workspace", analyze_fn=lambda *a, **k: {}, corpus=None,
                     download_fn=lambda *a, **k: None)
    try:
        enable_phone_access(settings)
        app = create_app(store=store, phone=settings, web_dist=None, allowed_roots=[tmp_path])
        assert app.state.phone.identity.phone_access is True
    finally:
        store.shutdown()
