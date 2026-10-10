"""Phone API: hello, pair, health, devices/self, the library listing, reference
(server-contract 6.2, 6.3, 6.8; S-02, S-11, S-13, S-17)."""

import json
import re
import threading
from pathlib import Path

import pytest
from conftest import (enable_phone_access, lan, loopback, pair_phone, wait_until,
                      write_silent_wav)
from fastapi.testclient import TestClient
from phone_fixtures import (REFERENCE_CHALLENGE, PT1Auth, ffprobe_json)

from phonotrainer.jobs import JobStore
from phonotrainer.phone.registry import hello_proof, reactivation_proof, token_key
from phonotrainer.server import API_VERSION, create_app

CHALLENGE = REFERENCE_CHALLENGE


def _media(tmp_path, name="clip.mp4"):
    path = tmp_path / name
    path.write_bytes(b"RIFF fake media")
    return path


# --- hello -----------------------------------------------------------------------------

def test_hello_without_a_challenge_says_who_answers(lan_client, phone_app, fake_clock):
    body = lan_client.get("/api/phone/hello").json()
    identity = phone_app.state.phone.identity
    assert body == {"service": "phonotrainer", "phone_access": True,
                    "server_id": identity.server_id, "name": "PhonoTrainer computer",
                    "api_version": API_VERSION, "server_time": int(fake_clock()),
                    "proof": None, "revoked": None}
    assert re.fullmatch(r"srv_[a-z2-7]{26}", body["server_id"])
    assert lan_client.get("/api/phone/hello").headers["cache-control"] == "no-store"


def test_hello_proves_identity_to_a_paired_phone(paired, phone_app, lan_client):
    _, device_id, key = paired
    server_id = phone_app.state.phone.identity.server_id
    body = lan_client.get("/api/phone/hello",
                          params={"device_id": device_id, "challenge": CHALLENGE}).json()
    assert body["proof"] == hello_proof(key, server_id, device_id, CHALLENGE)
    assert body["revoked"] is False


def test_hello_proof_for_a_revoked_device_says_revoked(paired, phone_app, lan_client):
    _, device_id, key = paired
    phone_app.state.phone.registry.revoke(device_id)
    body = lan_client.get("/api/phone/hello",
                          params={"device_id": device_id, "challenge": CHALLENGE}).json()
    assert body["revoked"] is True
    assert body["proof"] == hello_proof(key, phone_app.state.phone.identity.server_id,
                                        device_id, CHALLENGE)


def test_hello_has_no_proof_for_an_unknown_device(lan_client):
    body = lan_client.get("/api/phone/hello",
                          params={"device_id": "dev_unknown", "challenge": CHALLENGE}).json()
    assert body["proof"] is None and body["revoked"] is None


@pytest.mark.parametrize("params", [
    {"device_id": "dev_x"},                              # challenge missing
    {"challenge": CHALLENGE},                            # device id missing
    {"device_id": "dev_x", "challenge": "abc"},          # too short
    {"device_id": "dev_x", "challenge": "Z" * 32},       # not lower-case hex
    {"device_id": "dev_x", "challenge": "a" * 65},       # too long
])
def test_a_malformed_challenge_is_invalid(lan_client, params):
    resp = lan_client.get("/api/phone/hello", params=params)
    assert resp.status_code == 400
    assert resp.json()["code"] == "invalid_request"


def test_challenges_are_rate_limited_per_source_and_in_total(phone_app, fake_clock):
    client = lan(phone_app)
    params = {"device_id": "dev_x", "challenge": CHALLENGE}
    for _ in range(20):
        assert client.get("/api/phone/hello", params=params).status_code == 200
    limited = client.get("/api/phone/hello", params=params)
    assert limited.status_code == 429
    assert limited.json()["code"] == "rate_limited"
    assert int(limited.headers["retry-after"]) >= 1
    # without a challenge hello is never limited
    assert client.get("/api/phone/hello").status_code == 200
    # other sources still pass, until the total of 120 per minute
    for i in range(100):
        other = lan(phone_app, client=(f"192.0.2.{30 + i % 50}", 50000))
        assert other.get("/api/phone/hello", params=params).status_code == 200
    assert lan(phone_app, client=("192.0.2.99", 1)).get(
        "/api/phone/hello", params=params).status_code == 429
    fake_clock.advance(61)
    assert client.get("/api/phone/hello", params=params).status_code == 200


def test_hello_with_phone_access_off_carries_no_identity(phone_store, tmp_path, fake_clock):
    app = create_app(store=phone_store, web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    with TestClient(app) as client:
        body = client.get("/api/phone/hello").json()
    assert set(body) == {"service", "phone_access", "api_version", "server_time"}
    assert body["phone_access"] is False and body["api_version"] == API_VERSION


def test_hello_while_suspended_says_off_from_loopback(phone_store, phone_settings, tmp_path,
                                                      fake_netinfo):
    enable_phone_access(phone_settings)
    fake_netinfo.at_cafe()                       # approved at home, now elsewhere
    app = create_app(store=phone_store, phone=phone_settings, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    with TestClient(app):
        body = loopback(app).get("/api/phone/hello").json()
    assert body["phone_access"] is False
    assert "server_id" not in body


# --- pair ------------------------------------------------------------------------------

def test_pair_hands_out_a_token_once(phone_app, lan_client, fake_clock):
    resp, _ = pair_phone(phone_app, lan_client, name="Pixel 9")
    assert resp.status_code == 201
    body = resp.json()
    phone = phone_app.state.phone
    assert set(body) == {"device_id", "token", "server_id", "name", "library_id",
                         "api_version", "min_app_version", "server_time"}
    assert re.fullmatch(r"dev_[a-z2-7]{26}", body["device_id"])
    assert re.fullmatch(r"pt1_[A-Za-z0-9_-]{43}", body["token"])
    assert body["server_id"] == phone.identity.server_id
    assert body["library_id"] == phone.library_id
    assert re.fullmatch(r"lib_[a-z2-7]{26}", body["library_id"])
    assert body["min_app_version"] == 1 and body["api_version"] == API_VERSION
    assert body["server_time"] == int(fake_clock())
    # the token is never stored: only its key
    devices = (phone.config.path / "devices.json").read_text()
    assert body["token"] not in devices
    assert token_key(body["token"]).hex() in devices


def test_pairing_hands_the_source_address_to_the_waiting_command(phone_app, lan_client):
    pair_phone(phone_app, lan_client, name="Pixel 9")
    state = json.loads((phone_app.state.phone.config.path / "pairing.json").read_text())
    assert state == {"paired": {"name": "Pixel 9", "from": "192.0.2.20"}}


@pytest.mark.parametrize("body, field", [
    ({"code": "12345"}, "code"),
    ({"code": "1234567"}, "code"),
    ({"code": "12 345"}, "code"),
    ({"code": "١٢٣٤٥٦"}, "code"),                       # non-ASCII digits
    ({"device_name": ""}, "device_name"),
    ({"device_name": "\u200b\u202e"}, "device_name"),   # empty once cleaned
    ({"device_name": "x" * 61}, "device_name"),
    ({"app_version": 0}, "app_version"),
    ({"previous_device_id": "dev_x"}, "previous"),      # without its proof
    ({"previous_token_proof": "a" * 64}, "previous"),   # without its id
])
def test_pair_validates_its_body(phone_app, lan_client, body, field):
    code = phone_app.state.phone.registry.create_code().code
    request = {"code": code, "device_name": "Pixel 9", "app_version": 1,
               "app_version_name": "1.0.0", **body}
    resp = lan_client.post("/api/phone/pair", json=request)
    assert resp.status_code == 400, field
    assert resp.json() == {"detail": resp.json()["detail"], "code": "invalid_request"}


def test_pair_errors(phone_app, lan_client, fake_clock, monkeypatch):
    registry = phone_app.state.phone.registry

    def post(code):
        return lan_client.post("/api/phone/pair", json={
            "code": code, "device_name": "Pixel 9", "app_version": 1})

    # no code at all: code_invalid (no oracle about whether one is active)
    assert post("482913").json()["code"] == "code_invalid"
    code = registry.create_code().code
    wrong = f"{(int(code) + 1) % 10**6:06d}"
    resp = post(wrong)
    assert resp.status_code == 403 and resp.json()["code"] == "code_invalid"
    # an app older than the server accepts consumes nothing
    monkeypatch.setattr("phonotrainer.phone.registry.MIN_APP_VERSION", 2)
    too_old = post(code)
    assert too_old.status_code == 426 and too_old.json()["code"] == "app_too_old"
    monkeypatch.setattr("phonotrainer.phone.registry.MIN_APP_VERSION", 1)
    # the code expires after 300 s
    fake_clock.advance(301)
    resp = post(code)
    assert resp.status_code == 403 and resp.json()["code"] == "code_expired"


def test_the_sixth_attempt_is_locked_even_with_the_right_code(phone_app, lan_client):
    code = phone_app.state.phone.registry.create_code().code
    wrong = f"{(int(code) + 1) % 10**6:06d}"
    for _ in range(5):
        r = lan_client.post("/api/phone/pair", json={"code": wrong, "device_name": "P",
                                                      "app_version": 1})
        assert r.json()["code"] == "code_invalid"
    r = lan_client.post("/api/phone/pair", json={"code": code, "device_name": "P",
                                                  "app_version": 1})
    assert r.status_code == 403 and r.json()["code"] == "code_locked"


def test_pair_is_rate_limited(phone_app, lan_client):
    for _ in range(10):
        lan_client.post("/api/phone/pair", json={"code": "482913", "device_name": "P",
                                                  "app_version": 1})
    r = lan_client.post("/api/phone/pair", json={"code": "482913", "device_name": "P",
                                                  "app_version": 1})
    assert r.status_code == 429 and r.json()["code"] == "rate_limited"
    assert int(r.headers["retry-after"]) >= 1


def test_connect_again_keeps_the_device_id_and_kills_the_old_key(phone_app, lan_client,
                                                                 paired, fake_clock):
    signing, device_id, old_key = paired
    phone_app.state.phone.registry.revoke(device_id)
    assert signing.get("/api/phone/health").status_code == 401
    code = phone_app.state.phone.registry.create_code().code
    resp = lan_client.post("/api/phone/pair", json={
        "code": code, "device_name": "Pixel 9", "app_version": 1,
        "previous_device_id": device_id,
        "previous_token_proof": reactivation_proof(old_key, code)})
    assert resp.status_code == 201
    assert resp.json()["device_id"] == device_id
    assert signing.get("/api/phone/health").status_code == 401     # old key: dead
    signing.auth = PT1Auth(device_id, token_key(resp.json()["token"]), fake_clock)
    assert signing.get("/api/phone/health").status_code == 200


def test_knowing_a_device_id_is_not_enough_to_take_it_over(phone_app, lan_client, paired):
    _, device_id, _ = paired
    code = phone_app.state.phone.registry.create_code().code
    resp = lan_client.post("/api/phone/pair", json={
        "code": code, "device_name": "Rogue", "app_version": 1,
        "previous_device_id": device_id, "previous_token_proof": "0" * 64})
    assert resp.status_code == 201
    assert resp.json()["device_id"] != device_id


def test_pair_with_phone_access_off_is_refused(phone_store, tmp_path):
    app = create_app(store=phone_store, web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    with TestClient(app) as client:
        resp = client.post("/api/phone/pair", json={"code": "482913", "device_name": "P",
                                                     "app_version": 1})
    assert resp.status_code == 403 and resp.json()["code"] == "phone_access_off"


# --- health --------------------------------------------------------------------------------

def test_health_shape(paired, phone_app, fake_clock, fake_disk):
    signing, device_id, _ = paired
    body = signing.get("/api/phone/health").json()
    phone = phone_app.state.phone
    assert body["server_id"] == phone.identity.server_id
    assert body["library_id"] == phone.library_id
    assert body["api_version"] == API_VERSION and body["min_app_version"] == 1
    assert body["version"] == "0.1.0"
    assert body["server_time"] == int(fake_clock())
    assert body["device"] == {"device_id": device_id, "name": "Pixel 9"}
    assert body["queue"] == {"analyzing": 0, "in_line": 0}
    assert body["low_on_space"] is False
    caps = body["capabilities"]
    assert set(caps) == {"link_downloads", "downloader", "dialogue_separation", "discovery",
                         "renditions", "phone_video", "resumable_uploads", "record_compare",
                         "max_upload_bytes", "max_chunk_bytes", "max_link_duration_s",
                         "free_bytes"}
    assert caps["renditions"] is caps["phone_video"] is caps["resumable_uploads"] \
        is caps["record_compare"] is False
    assert caps["max_upload_bytes"] == 2 * 1024 ** 3
    assert caps["max_chunk_bytes"] == 8 * 1024 ** 2
    assert caps["max_link_duration_s"] == 3600
    assert caps["free_bytes"] == fake_disk.free


def test_health_reports_the_downloader_version_as_installed(paired):
    import importlib.metadata

    signing, _, _ = paired
    downloader = signing.get("/api/phone/health").json()["capabilities"]["downloader"]
    assert downloader["name"] == "yt-dlp"
    assert downloader["version"] == importlib.metadata.version("yt-dlp")   # not zero-padded
    assert downloader["js_runtime"] == "node"            # fake which: node only
    assert isinstance(downloader["challenge_solver"], bool)


def test_low_on_space_and_free_bytes_are_cached(paired, fake_disk, fake_clock):
    signing, _, _ = paired
    assert signing.get("/api/phone/health").json()["capabilities"]["free_bytes"] \
        == 50 * 1024 ** 3
    fake_disk.free = 3 * 1024 ** 3
    body = signing.get("/api/phone/health").json()
    assert body["capabilities"]["free_bytes"] == 50 * 1024 ** 3     # cached for 5 s
    fake_clock.advance(5)
    body = signing.get("/api/phone/health").json()
    assert body["capabilities"]["free_bytes"] == 3 * 1024 ** 3
    assert body["low_on_space"] is True


def test_health_answers_while_an_analysis_holds_the_worker(phone_settings, tmp_path,
                                                           blocking_analyze):
    enable_phone_access(phone_settings)
    store = JobStore(tmp_path / "ws", analyze_fn=blocking_analyze, corpus=None)
    app = create_app(store=store, phone=phone_settings, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    try:
        with TestClient(app):
            client = lan(app)
            resp, _ = pair_phone(app, client)
            client.auth = PT1Auth(resp.json()["device_id"], token_key(resp.json()["token"]),
                                  phone_settings.clock)
            media = _media(tmp_path)
            store.create(media)
            store.create(media)
            assert blocking_analyze.started.wait(5)
            result = {}

            def probe():
                result["resp"] = client.get("/api/phone/health")

            thread = threading.Thread(target=probe)
            thread.start()
            thread.join(timeout=1.0)
            assert not thread.is_alive(), "health waited on the worker"
            assert result["resp"].json()["queue"] == {"analyzing": 1, "in_line": 1}
    finally:
        blocking_analyze.release.set()
        store.shutdown()


# --- devices/self ------------------------------------------------------------------------

def test_forget_this_computer_revokes_the_caller(paired, phone_app):
    signing, device_id, _ = paired
    assert signing.delete("/api/phone/devices/self").status_code == 204
    assert phone_app.state.phone.registry.get(device_id).revoked
    again = signing.delete("/api/phone/devices/self")
    assert again.status_code == 401 and again.json()["code"] == "credential_rejected"


# --- the library ------------------------------------------------------------------------

def test_listing_shape_and_order(paired, phone_app, phone_store, tmp_path):
    signing, device_id, _ = paired
    a = phone_store.create(_media(tmp_path, "b clip.mp4"), options={"separate_dialogue": True})
    b = phone_store.create(_media(tmp_path, "a clip.wav"), options={"separate_dialogue": False})
    wait_until(lambda: all(phone_store.get(j.id).status == "done" for j in (a, b)))
    body = signing.get("/api/phone/library").json()
    phone = phone_app.state.phone
    assert body["api_version"] == API_VERSION
    assert body["server_id"] == phone.identity.server_id
    assert body["library_id"] == phone.library_id
    assert body["complete"] is True
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+00:00", body["generated_at"])
    ids = [j["id"] for j in body["jobs"]]
    assert ids == sorted([a.id, b.id])
    job = next(j for j in body["jobs"] if j["id"] == a.id)
    assert set(job) == {"id", "revision", "status", "stage", "progress", "separation",
                        "queue_position", "title", "source", "options", "device_id", "own",
                        "client_request_id", "rerun_of", "created", "started", "finished",
                        "error", "summary", "analysis", "media", "artifacts"}
    assert job["status"] == "done" and job["stage"] is None
    assert job["progress"] == {"percent": 100, "segment": None, "segments": None,
                               "download_percent": None}
    assert job["title"] == "b clip"
    assert job["source"] == {"kind": "path", "url": None, "site": None,
                             "file_name": "b clip.mp4"}
    assert job["options"] == {"audio_only": False, "separate_dialogue": True}
    assert job["own"] is False and job["device_id"] is None
    assert job["client_request_id"] is None and job["rerun_of"] is None
    assert job["error"] is None
    assert job["summary"] == {"duration_s": 3.0, "segments": 2, "words": 5,
                              "phenomena_counts": {"vowel_reduction": 1, "t_deletion": 1,
                                                   "contraction_lex": 1}}
    assert job["analysis"] == {"rules_version": None, "phone_engine": "espeak",
                               "dialogue_separation": True, "phonotrainer_version": "0.1.0"}
    other = next(j for j in body["jobs"] if j["id"] == b.id)
    assert other["separation"] == "off"
    assert other["analysis"]["dialogue_separation"] is False


def test_own_and_other_phones_jobs(paired, phone_app, phone_store, tmp_path):
    signing, device_id, _ = paired
    mine = phone_store.adopt_upload("mine.mp4", lambda d: d.write_bytes(b"x"),
                                    device_id=device_id, origin="phone",
                                    client_request_id="00000000-0000-4000-8000-000000000001")
    theirs = phone_store.adopt_upload("theirs.mp4", lambda d: d.write_bytes(b"x"),
                                      device_id="dev_someoneelse", origin="phone",
                                      client_request_id="00000000-0000-4000-8000-000000000002")
    wait_until(lambda: all(phone_store.get(j.id).status == "done" for j in (mine, theirs)))
    jobs = {j["id"]: j for j in signing.get("/api/phone/library").json()["jobs"]}
    assert jobs[mine.id]["own"] is True
    assert jobs[mine.id]["device_id"] == device_id
    assert jobs[mine.id]["client_request_id"] == "00000000-0000-4000-8000-000000000001"
    assert jobs[mine.id]["source"] == {"kind": "upload", "url": None, "site": None,
                                       "file_name": "mine.mp4"}
    assert jobs[theirs.id]["own"] is False
    assert jobs[theirs.id]["device_id"] is None
    assert jobs[theirs.id]["client_request_id"] is None


def test_queue_positions_stage_and_progress(phone_settings, tmp_path, blocking_analyze):
    enable_phone_access(phone_settings)
    store = JobStore(tmp_path / "ws", analyze_fn=blocking_analyze, corpus=None)
    app = create_app(store=store, phone=phone_settings, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    try:
        with TestClient(app):
            client = lan(app)
            resp, _ = pair_phone(app, client)
            client.auth = PT1Auth(resp.json()["device_id"], token_key(resp.json()["token"]),
                                  phone_settings.clock)
            media = _media(tmp_path)
            first, second, third = (store.create(media) for _ in range(3))
            assert blocking_analyze.started.wait(5)
            store.cancel(second.id)                    # being cancelled: not counted
            jobs = {j["id"]: j for j in client.get("/api/phone/library").json()["jobs"]}
            assert jobs[first.id]["status"] == "running"
            assert jobs[first.id]["queue_position"] is None
            assert jobs[second.id]["queue_position"] is None
            assert jobs[third.id]["queue_position"] == 1
            assert jobs[third.id]["stage"] is None             # queued
            assert jobs[first.id]["stage"] is None             # no stage message yet
            store._append_progress(store.get(first.id), "Segment 3/10: phones + alignment…")
            job = client.get(f"/api/phone/jobs/{first.id}").json()
            assert job["stage"] == "phones"
            assert job["progress"]["segment"] == 3 and job["progress"]["segments"] == 10
            assert job["artifacts"] == []
    finally:
        blocking_analyze.release.set()
        store.shutdown()


def _ready(signing, job_id):
    def check():
        body = signing.get(f"/api/phone/jobs/{job_id}").json()
        return body if body["artifacts"] and all(
            a["status"] == "ready" for a in body["artifacts"]) else None
    return wait_until(check)


def test_revision_ignores_progress_and_follows_status_and_files(paired, phone_store,
                                                                 tmp_path):
    signing, _, _ = paired
    job = phone_store.create(_media(tmp_path))
    wait_until(lambda: phone_store.get(job.id).status == "done")
    before = _ready(signing, job.id)["revision"]
    assert re.fullmatch(r"[0-9a-f]{16}", before)
    phone_store._append_progress(phone_store.get(job.id), "Warning: something harmless")
    assert signing.get(f"/api/phone/jobs/{job.id}").json()["revision"] == before
    # a file changes: its checksum (and so the revision) changes
    write_silent_wav(Path(phone_store.get(job.id).dir) / "audio.wav", seconds=2.0)
    assert signing.get(f"/api/phone/jobs/{job.id}").json()["revision"] != before
    # the status changes
    phone_store.get(job.id).status = "error"
    errored = signing.get(f"/api/phone/jobs/{job.id}").json()
    assert errored["revision"] != before and errored["artifacts"] == []


def test_failed_link_job_shape(paired, phone_store):
    signing, device_id, _ = paired
    job = phone_store.create_from_url("https://x.com/someone/status/1234567890",
                                      device_id=device_id, origin="phone",
                                      client_request_id="00000000-0000-4000-8000-000000000002")
    wait_until(lambda: phone_store.get(job.id).status == "done")
    stored = phone_store.get(job.id)
    stored.status = "error"
    stored.error = ("DownloadError: \x1b[0;31mERROR:\x1b[0m [twitter] 123: unable to download "
                    "video data: HTTP Error 403: Forbidden")
    body = signing.get(f"/api/phone/jobs/{job.id}").json()
    assert body["status"] == "error"
    assert body["source"] == {"kind": "link", "url": "https://x.com/someone/status/1234567890",
                              "site": "x", "file_name": None}
    assert body["error"]["code"] == "download_forbidden"
    assert body["error"]["message"] == ("[twitter] 123: unable to download video data: "
                                        "HTTP Error 403: Forbidden")
    assert body["error"]["downloader_version"]
    assert body["artifacts"] == []
    assert body["title"] == "A video"              # downloaded before it failed


def test_unknown_job(paired):
    signing, _, _ = paired
    for job_id in ("20261010-091503-4be1", "x", "20261010-091503-4BE1"):
        resp = signing.get(f"/api/phone/jobs/{job_id}")
        assert resp.status_code == 404
        assert resp.json()["code"] == "unknown_job"


def test_imported_jobs_are_listed_with_no_media(paired, phone_store, tmp_path):
    from conftest import mk_analysis

    signing, _, _ = paired
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("talk.webm")))
    write_silent_wav(out / "audio.wav")
    job = phone_store.import_dir(out)
    body = signing.get(f"/api/phone/jobs/{job.id}").json()
    assert body["source"]["kind"] == "import"
    assert body["title"] == "talk"
    assert body["media"] == {"kind": "none", "codec": None, "width": None, "height": None,
                             "rotation": 0, "start_offset_ms": 0, "duration_s": None}
    assert [a["name"] for a in body["artifacts"]] == ["analysis", "audio_mix"]


# --- reference -------------------------------------------------------------------------

def test_reference_is_byte_for_byte_the_browsers(paired, phone_app):
    signing, _, _ = paired
    phone = signing.get("/api/phone/reference")
    browser = loopback(phone_app).get("/api/reference")
    assert phone.status_code == 200
    assert phone.content == browser.content
    assert phone.headers["etag"] == f'"api-{API_VERSION}"'
    assert phone.json()["api_version"] == API_VERSION


def test_reference_requires_a_signature(lan_client):
    resp = lan_client.get("/api/phone/reference")
    assert resp.status_code == 401
    assert resp.headers["www-authenticate"] == "PT1"


def test_listing_media_facts_from_the_probe(paired, phone_app, phone_store, tmp_path,
                                            fake_probe):
    signing, _, _ = paired
    fake_probe.by_name["portrait.mp4"] = ffprobe_json(
        video={"codec": "av1", "width": 2160, "height": 3840,
               "side_data_list": [{"rotation": -90}], "start_time": "0.000000"},
        audio={"start_time": "0.021000"}, duration=42.7)
    job = phone_store.create(_media(tmp_path, "portrait.mp4"))
    wait_until(lambda: phone_store.get(job.id).status == "done")
    media = wait_until(lambda: signing.get(f"/api/phone/jobs/{job.id}").json()["media"])
    assert media == {"kind": "video", "codec": "av1", "width": 2160, "height": 3840,
                     "rotation": 90, "start_offset_ms": 21, "duration_s": 42.7}
