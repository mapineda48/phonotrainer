"""Access control for LAN clients (server-contract 4; S-05, S-06, S-08, F-02-AC8, QT-8).

LAN clients are simulated with a TestClient whose ASGI `client` is a LAN
address and whose `server` (the local address the connection arrived on) is
the approved Wi-Fi address 192.0.2.10 of `FakeNetinfo`.
"""

import getpass
import hashlib
import json
import re
import socket
import time
from pathlib import Path

import anyio
import pytest
from conftest import enable_phone_access, fake_analyze, lan, loopback, wait_until
from fastapi.testclient import TestClient
from phone_fixtures import (REFERENCE_DEVICE_ID, REFERENCE_HEADER, REFERENCE_KEY_HEX,
                            REFERENCE_NONCE, REFERENCE_SIGNATURES, REFERENCE_TOKEN,
                            REFERENCE_TS, PT1Auth, asgi_call, sign_header)
from starlette.websockets import WebSocketDisconnect

from phonotrainer.phone.auth import signature
from phonotrainer.phone.config import ConfigDir
from phonotrainer.phone.identity import ServerIdentity
from phonotrainer.phone.registry import DeviceRegistry, token_key
from phonotrainer.server import create_app

LAN_FORBIDDEN = {"detail": "This part of PhonoTrainer is only available on the computer itself.",
                 "code": "lan_forbidden"}


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.mp4"
    path.write_bytes(b"RIFF fake media")
    return path


@pytest.fixture
def done_job(phone_store, media):
    job = phone_store.create(media)
    wait_until(lambda: phone_store.get(job.id).status == "done")
    return job


def browser_routes(job_id: str, media: Path, tmp_path: Path):
    """Every kind of existing route a LAN client must never reach."""
    return [
        ("GET", "/api/browse", None),
        ("POST", "/api/jobs", {"path": str(media)}),
        ("POST", "/api/jobs/import", {"path": str(tmp_path)}),
        ("PUT", f"/api/jobs/{job_id}/review", {"verdicts": []}),
        ("DELETE", f"/api/jobs/{job_id}", None),
        ("GET", "/api/corpus/stats", None),
        ("GET", "/api/health", None),
        ("GET", "/api/jobs", None),
        ("GET", f"/api/jobs/{job_id}/analysis", None),
        ("GET", "/api/reference", None),
        ("GET", "/", None),
        ("GET", "/index.html", None),
        ("GET", "/library", None),
    ]


# --- the route matrix (4.2) ---------------------------------------------------------------

def test_lan_clients_reach_no_existing_route(phone_app, done_job, media, tmp_path):
    client = lan(phone_app)
    for method, path, body in browser_routes(done_job.id, media, tmp_path):
        resp = client.request(method, path, json=body)
        assert resp.status_code == 403, (method, path)
        assert resp.json() == LAN_FORBIDDEN, (method, path)
    # nothing happened: the job is still there
    assert phone_app.state.store.get(done_job.id)


def test_lan_clients_get_no_browser_channel(phone_app):
    # (TestClient's websocket_connect defaults to ws://testserver: give the full URL)
    with pytest.raises(WebSocketDisconnect):
        with lan(phone_app).websocket_connect("ws://192.0.2.10:8000/ws/jobs") as ws:
            ws.receive_json()


def test_loopback_keeps_every_route_with_phone_access_on(phone_app, done_job):
    client = loopback(phone_app)
    assert client.get("/api/health").status_code == 200
    assert client.get("/api/jobs").status_code == 200
    assert client.get(f"/api/jobs/{done_job.id}/analysis").status_code == 200
    assert client.get("/api/browse").status_code == 200
    assert client.get("/").status_code == 200
    with client.websocket_connect("ws://127.0.0.1:8000/ws/jobs") as ws:
        assert ws.receive_json()["type"] == "snapshot"


def test_a_lan_request_on_an_unapproved_address_is_forbidden(phone_app):
    for server in (("198.51.100.1", 8000), ("203.0.113.10", 8000)):   # docker0, elsewhere
        client = TestClient(phone_app, client=("192.0.2.20", 50000),
                            base_url=f"http://{server[0]}:{server[1]}")
        for path in ("/api/phone/hello", "/api/phone/health", "/api/browse", "/"):
            resp = client.get(path)
            assert resp.status_code == 403 and resp.json() == LAN_FORBIDDEN, (server, path)


def test_phone_access_off_leaves_lan_clients_as_today(phone_store, tmp_path, done_job):
    """`--host` by hand without phone access: the README warns; nothing changes."""
    app = create_app(store=phone_store, web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    with TestClient(app):
        client = lan(app)
        assert client.get("/api/health").status_code == 200
        assert client.get("/api/jobs").status_code == 200
        hello = client.get("/api/phone/hello").json()
        assert hello["phone_access"] is False
        for method, path in (("POST", "/api/phone/pair"), ("GET", "/api/phone/health"),
                             ("GET", "/api/phone/library"),
                             ("DELETE", "/api/phone/devices/self"),
                             ("GET", "/api/phone/anything/else")):
            resp = client.request(method, path)
            assert resp.status_code == 403, path
            assert resp.json()["code"] == "phone_access_off"
            assert resp.headers["cache-control"] == "no-store"


def test_phone_access_switched_off_live(phone_app, paired, fake_clock, isolated_config):
    signing, device_id, key = paired
    assert signing.get("/api/phone/health").status_code == 200
    ServerIdentity(ConfigDir(isolated_config), fake_clock).turn_off()   # the CLI
    fake_clock.advance(1)                         # server.json is re-read within a second
    # the approved networks are cleared, so the LAN is closed altogether
    resp = signing.get("/api/phone/health")
    assert resp.status_code == 403 and resp.json()["code"] == "lan_forbidden"
    local = loopback(phone_app)
    assert local.get("/api/phone/hello").json()["phone_access"] is False
    local.auth = PT1Auth(device_id, key, fake_clock)
    resp = local.get("/api/phone/health")
    assert resp.status_code == 403 and resp.json()["code"] == "phone_access_off"
    # the browser client on the computer itself is untouched
    assert local.get("/api/jobs").status_code == 200


# --- signatures (4.3) ---------------------------------------------------------------------------

def _rejected(resp) -> None:
    assert resp.status_code == 401
    assert resp.json() == {"detail": "This phone is not paired with this computer.",
                           "code": "credential_rejected"}
    assert resp.headers["www-authenticate"] == "PT1"


def test_a_signature_is_required(phone_app, paired):
    _, device_id, key = paired
    client = lan(phone_app)
    _rejected(client.get("/api/phone/health"))
    _rejected(client.get("/api/phone/library", headers={"Authorization": "Bearer abc"}))
    good = sign_header(device_id, key, "GET", "/api/phone/health", REFERENCE_TS, "a" * 32)
    for bad in (good.replace("PT1 ", "PT2 "), good.replace("nonce=", "nonce=x"),
                good[:-1], good + "0", good.replace("ts=", "ts=-"),
                good.replace(", mac=", ", MAC=")):
        _rejected(client.get("/api/phone/health", headers={"Authorization": bad}))


def test_wrong_mac_unknown_and_revoked_devices_are_rejected(phone_app, paired, fake_clock):
    signing, device_id, key = paired
    client = lan(phone_app)
    ts = int(fake_clock())
    wrong = sign_header(device_id, b"\x00" * 32, "GET", "/api/phone/health", ts, "b" * 32)
    _rejected(client.get("/api/phone/health", headers={"Authorization": wrong}))
    unknown = sign_header("dev_nobody", key, "GET", "/api/phone/health", ts, "c" * 32)
    _rejected(client.get("/api/phone/health", headers={"Authorization": unknown}))
    # signed for another path or method
    other = sign_header(device_id, key, "GET", "/api/phone/library", ts, "d" * 32)
    _rejected(client.get("/api/phone/health", headers={"Authorization": other}))
    other = sign_header(device_id, key, "POST", "/api/phone/health", ts, "e" * 32)
    _rejected(client.get("/api/phone/health", headers={"Authorization": other}))
    # the query string is covered too
    other = sign_header(device_id, key, "GET", "/api/phone/health", ts, "f" * 32)
    _rejected(client.get("/api/phone/health?x=1", headers={"Authorization": other}))
    phone_app.state.phone.registry.revoke(device_id)
    _rejected(signing.get("/api/phone/health"))


def test_a_replayed_request_is_refused(phone_app, paired, fake_clock):
    _, device_id, key = paired
    client = lan(phone_app)
    header = sign_header(device_id, key, "GET", "/api/phone/health", int(fake_clock()),
                         "0" * 32)
    assert client.get("/api/phone/health", headers={"Authorization": header}).status_code == 200
    _rejected(client.get("/api/phone/health", headers={"Authorization": header}))
    # still refused later on, while the nonce is remembered (600 s)
    fake_clock.advance(299)
    _rejected(client.get("/api/phone/health", headers={"Authorization": header}))


@pytest.mark.parametrize("offset", [301, -301, 3600])
def test_a_clock_outside_the_window_is_clock_skew(phone_app, paired, fake_clock, offset):
    _, device_id, key = paired
    ts = int(fake_clock()) + offset
    header = sign_header(device_id, key, "GET", "/api/phone/health", ts, "1" * 32)
    resp = lan(phone_app).get("/api/phone/health", headers={"Authorization": header})
    assert resp.status_code == 401
    assert resp.json() == {"detail": "This phone's clock differs from this computer's.",
                           "code": "clock_skew", "server_time": int(fake_clock())}


@pytest.mark.parametrize("offset", [300, -300])
def test_the_window_is_300_seconds(phone_app, paired, fake_clock, offset):
    _, device_id, key = paired
    header = sign_header(device_id, key, "GET", "/api/phone/health",
                         int(fake_clock()) + offset, "2" * 32)
    resp = lan(phone_app).get("/api/phone/health", headers={"Authorization": header})
    assert resp.status_code == 200


def test_the_reference_vectors(phone_app, fake_clock, isolated_config):
    """The values the app's JVM tests reproduce (WP-05a, WP-05b)."""
    key = bytes.fromhex(REFERENCE_KEY_HEX)
    assert token_key(REFERENCE_TOKEN) == key
    for method, target, mac in REFERENCE_SIGNATURES:
        assert signature(key, method, target, REFERENCE_TS, REFERENCE_NONCE) == mac
    method, target, mac = REFERENCE_SIGNATURES[0]
    assert REFERENCE_HEADER == sign_header(REFERENCE_DEVICE_ID, key, method, target,
                                           REFERENCE_TS, REFERENCE_NONCE)
    # the server accepts the reference header as is
    registry = phone_app.state.phone.registry
    with registry.config.locked():
        devices = registry.config.read_json("devices.json") or {"devices": []}
        devices["devices"].append({"device_id": REFERENCE_DEVICE_ID, "name": "Reference",
                                   "key_sha256": REFERENCE_KEY_HEX,
                                   "paired_at": "2026-10-10T09:00:00+00:00"})
        registry.config.write_json("devices.json", devices)
    fake_clock.now = float(REFERENCE_TS)
    resp = lan(phone_app).get(target, headers={"Authorization": REFERENCE_HEADER})
    assert resp.status_code == 404                     # past authentication: no such job
    assert resp.json()["code"] == "unknown_job"


def test_a_cli_revoke_applies_at_the_next_request(phone_app, paired, fake_clock,
                                                  isolated_config):
    signing, device_id, _ = paired
    assert signing.get("/api/phone/library").status_code == 200
    # another process (the `phone revoke` command) on the same directory
    DeviceRegistry(ConfigDir(isolated_config), fake_clock).revoke(device_id)
    _rejected(signing.get("/api/phone/library"))


def test_loopback_clients_sign_phone_routes_too(phone_app, paired, fake_clock):
    _, device_id, key = paired
    client = loopback(phone_app)
    _rejected(client.get("/api/phone/health"))
    client.auth = PT1Auth(device_id, key, fake_clock)
    assert client.get("/api/phone/health").status_code == 200


def test_a_successful_request_updates_last_seen_at_most_once_a_minute(phone_app, paired,
                                                                      fake_clock):
    signing, device_id, _ = paired
    registry = phone_app.state.phone.registry
    first = registry.get(device_id).last_seen_at
    fake_clock.advance(30)
    signing.get("/api/phone/health")
    assert registry.get(device_id).last_seen_at == first
    fake_clock.advance(31)
    signing.get("/api/phone/health")
    assert registry.get(device_id).last_seen_at != first


# --- Host validation (4.4) -------------------------------------------------------------------------

@pytest.mark.parametrize("host", ["phonotrainer.example:8000", "192.0.2.11:8000",
                                  "192.0.2.10:9999", "localhost:8000", "[::1]:8000",
                                  "192.0.2.10:8000:1", ""])
def test_lan_host_must_be_the_arrival_address(phone_app, host):
    resp = lan(phone_app).get("/api/phone/hello", headers={"Host": host})
    assert resp.status_code == 400, host
    assert resp.json() == {"detail": "Unexpected Host header.", "code": "bad_host"}


def test_lan_host_without_a_port_is_accepted(phone_app):
    assert lan(phone_app).get("/api/phone/hello", headers={"Host": "192.0.2.10"}) \
        .status_code == 200


def _loopback_hosts():
    name = socket.gethostname().lower()
    return ["127.0.0.1:8000", "localhost:5173", "localhost", "app.localhost:8000",
            "[::1]:8000", "10.0.2.2:8000", f"{name}:8000", f"{name.split('.')[0]}.local"]


@pytest.mark.parametrize("phone_on", [True, False])
def test_loopback_host_rule_in_every_mode(phone_store, phone_settings, tmp_path, phone_on):
    if phone_on:
        enable_phone_access(phone_settings)
    app = create_app(store=phone_store, phone=phone_settings if phone_on else None,
                     web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    with TestClient(app):
        client = loopback(app)
        for host in _loopback_hosts():
            assert client.get("/api/browse", headers={"Host": host}).status_code == 200, host
        for host in ("attacker.example", "attacker.example:8000", "localhost.attacker.example",
                     "127.0.0.1.attacker.example"):
            resp = client.get("/api/browse", headers={"Host": host})
            assert resp.status_code == 400, host
            assert resp.json()["code"] == "bad_host"
        # the browser channel too
        with client.websocket_connect("ws://localhost:8000/ws/jobs") as ws:
            assert ws.receive_json()["type"] == "snapshot"
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("ws://attacker.example:8000/ws/jobs") as ws:
                ws.receive_json()


def test_existing_tests_peer_is_not_loopback(phone_store, tmp_path):
    """TestClient's default peer ("testclient") is not an IP literal: a LAN
    client by 4.1, which with phone access off is "as today"."""
    app = create_app(store=phone_store, web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    with TestClient(app) as client:
        assert client.get("/api/health").status_code == 200


# --- paths (4.2) ----------------------------------------------------------------------------------

@pytest.mark.parametrize("raw", [b"/api/phone/../browse", b"/api/phone/%2e%2e/health",
                                 b"/api/phone/./health", b"/api/phone/jobs%2F..%2Fx",
                                 b"/api/phone/%2E%2E/%2E%2E/api/browse"])
@pytest.mark.parametrize("peer", ["lan", "loopback"])
def test_dot_segments_and_encoded_slashes_are_refused(phone_app, raw, peer):
    client, server = (("192.0.2.20", 1), ("192.0.2.10", 8000)) if peer == "lan" \
        else (("127.0.0.1", 1), ("127.0.0.1", 8000))
    resp = asgi_call(phone_app, "GET", raw, client=client, server=server)
    assert resp["status"] == 400, raw
    assert json.loads(resp["body"])["code"] == "invalid_request"
    ws = asgi_call(phone_app, "GET", raw, client=client, server=server, websocket=True)
    assert ws["closed"] and not ws["accepted"]


def test_unknown_phone_paths_are_json_404_never_the_spa(phone_app, tmp_path):
    (tmp_path / "not-built").mkdir(exist_ok=True)
    for client in (lan(phone_app), loopback(phone_app)):
        for method, path in (("GET", "/api/phone/x"), ("POST", "/api/phone/x/y"),
                             ("GET", "/api/phone"), ("PUT", "/api/phone/health")):
            resp = client.request(method, path)
            assert resp.status_code == 404, path
            assert resp.json()["code"] == "unknown_route"
            assert resp.headers["content-type"].startswith("application/json")
    ws = asgi_call(phone_app, "GET", b"/api/phone/x", websocket=True)
    assert ws["closed"] and not ws["accepted"]


def test_proxy_headers_never_change_the_peer(phone_app):
    client = lan(phone_app)
    for header in ({"X-Forwarded-For": "127.0.0.1"}, {"X-Real-IP": "127.0.0.1"},
                   {"Forwarded": "for=127.0.0.1"}):
        resp = client.get("/api/browse", headers=header)
        assert resp.status_code == 403 and resp.json() == LAN_FORBIDDEN


def _uvicorn_app(app, **kwargs):
    """`app` wrapped exactly as uvicorn serves it (its ProxyHeadersMiddleware
    included when `proxy_headers` is on)."""
    import uvicorn

    config = uvicorn.Config(app, log_config=None, **kwargs)
    config.load()
    return config.loaded_app


FORWARDED = [(b"x-forwarded-for", b"203.0.113.9"), (b"sec-fetch-site", b"same-origin")]


@pytest.mark.parametrize("phone_on", [False, True])
def test_forwarded_for_cannot_skip_the_loopback_host_rule(
        uvicorn_calls, monkeypatch, phone_store, phone_settings, tmp_path, phone_on):
    """A DNS-rebinding page sends `X-Forwarded-For` with a foreign Host: through
    uvicorn's own stack, built from the arguments serve() gives it, in both
    modes, it is still a loopback request with a bad Host."""
    from phonotrainer import server

    if phone_on:
        enable_phone_access(phone_settings)
    phone = phone_settings if phone_on else None
    app = create_app(store=phone_store, phone=phone, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    monkeypatch.setattr(server, "create_app", lambda *a, **kw: app)
    server.serve(workspace=tmp_path / "ws", open_browser=False, phone=phone)
    (served, kwargs), = uvicorn_calls
    assert served is app and kwargs["proxy_headers"] is False
    wrapped = _uvicorn_app(app, **kwargs)
    peer = {"client": ("127.0.0.1", 50000), "server": ("127.0.0.1", 8000)}
    with TestClient(app):
        for method, path in (("GET", b"/api/browse"), ("POST", b"/api/jobs"),
                             ("GET", b"/api/jobs")):
            resp = asgi_call(wrapped, method, path, host=b"attacker.example",
                             headers=FORWARDED, **peer)
            assert resp["status"] == 400, path
            assert json.loads(resp["body"])["code"] == "bad_host"
        # the same request with an honest Host is the owner's browser: served
        resp = asgi_call(wrapped, "GET", b"/api/browse", host=b"127.0.0.1:8000",
                         headers=FORWARDED, **peer)
        assert resp["status"] == 200


@pytest.mark.parametrize("phone_on", [False, True])
def test_a_rewritten_peer_on_a_loopback_address_is_still_refused(
        phone_store, phone_settings, tmp_path, phone_on):
    """Defence in depth: even with uvicorn trusting proxy headers (which serve()
    never does), a connection that arrived on a loopback address cannot pass
    for a remote peer and skip the Host rule."""
    if phone_on:
        enable_phone_access(phone_settings)
    app = create_app(store=phone_store, phone=phone_settings if phone_on else None,
                     web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    wrapped = _uvicorn_app(app, proxy_headers=True, forwarded_allow_ips="127.0.0.1")
    with TestClient(app):
        resp = asgi_call(wrapped, "GET", b"/api/browse", client=("127.0.0.1", 50000),
                         server=("127.0.0.1", 8000), host=b"attacker.example",
                         headers=FORWARDED)
        # phone access on: the rewritten peer is a LAN client on an unapproved address
        expected = (403, "lan_forbidden") if phone_on else (400, "bad_host")
        assert (resp["status"], json.loads(resp["body"])["code"]) == expected


# --- body limits (4.7) ------------------------------------------------------------------------------

def test_json_bodies_must_declare_a_small_length(phone_app):
    big = {"code": "482913", "device_name": "x" * 20000, "app_version": 1}
    resp = lan(phone_app).post("/api/phone/pair", json=big)
    assert resp.status_code == 413 and resp.json()["code"] == "too_large"

    def chunks():
        yield b'{"code": "482913", '
        yield b'"device_name": "P", "app_version": 1}'

    resp = lan(phone_app).post("/api/phone/pair", content=chunks(),
                               headers={"Content-Type": "application/json"})
    assert resp.status_code == 411 and resp.json()["code"] == "length_required"


def test_a_phone_validation_error_is_400_but_the_browsers_stays_422(phone_app):
    resp = lan(phone_app).post("/api/phone/pair", json={"code": 1})
    assert resp.status_code == 400 and resp.json()["code"] == "invalid_request"
    resp = loopback(phone_app).post("/api/jobs", json={"nope": 1})
    assert resp.status_code == 422 and isinstance(resp.json()["detail"], list)


# --- nothing private ever leaves (4.6) --------------------------------------------------------

def test_no_path_home_or_user_name_in_a_full_session(phone_app, paired, phone_store, media,
                                                     tmp_path):
    signing, device_id, _ = paired
    for job in (phone_store.create(media),
                phone_store.adopt_upload("phone clip.mp4", lambda d: d.write_bytes(b"x"),
                                         device_id=device_id, origin="phone")):
        wait_until(lambda job=job: phone_store.get(job.id).status == "done")
    failed = phone_store.create(media)
    wait_until(lambda: phone_store.get(failed.id).status == "done")
    failed_job = phone_store.get(failed.id)
    failed_job.status = "error"
    failed_job.error = (f"AudioExtractionError: ffmpeg failed on {media}: "
                        f"{Path.home()}/x/{getpass.getuser()}.mp4 Invalid data found when "
                        "processing input")

    texts: list[str] = []

    def record(resp):
        assert resp.status_code < 500, resp.text
        texts.append(resp.text)
        return resp

    client = lan(phone_app)
    record(client.get("/api/phone/hello"))
    record(client.get("/api/phone/hello", params={"device_id": device_id,
                                                  "challenge": "ab" * 16}))
    record(signing.get("/api/phone/health"))
    library = record(signing.get("/api/phone/library")).json()
    for job in library["jobs"]:
        record(signing.get(f"/api/phone/jobs/{job['id']}"))
    record(signing.get("/api/phone/reference"))
    record(signing.get("/api/phone/jobs/20261010-091503-4be1"))
    record(signing.get("/api/phone/nowhere"))
    # every served JSON artifact
    for job in library["jobs"]:
        wait_until(lambda job=job: all(
            a["status"] == "ready" for a in signing.get(
                f"/api/phone/jobs/{job['id']}").json()["artifacts"]))
        for artifact in signing.get(f"/api/phone/jobs/{job['id']}").json()["artifacts"]:
            if artifact["content_type"] == "application/json":
                record(signing.get(artifact["url"]))
    record(signing.delete("/api/phone/devices/self"))

    forbidden = [str(tmp_path), str(Path.home()), getpass.getuser(),
                 str(phone_store.root.resolve())]
    for text in texts:
        for value in forbidden:
            assert value not in text, (value, text[:200])
    assert any(re.search(r'"code":\s*"not_media"', t) for t in texts)


def test_no_path_home_or_user_name_in_requests_uploads_or_the_channel(phone_server, tmp_path):
    from phonotrainer.download import DownloadError
    from phonotrainer.jobs import JobStore

    def leaky_download(url, dest_dir, audio_only=False, progress=lambda m: None, **kwargs):
        progress(f"Looking up the URL… ({dest_dir})")
        raise DownloadError(f"ERROR: unable to write {Path(dest_dir).resolve()}/x.part, "
                            f"{Path.home()}/{getpass.getuser()}/y: No space left on device")

    store = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "downloads", download_fn=leaky_download)
    app, signing, device_id = phone_server(store)
    texts: list[str] = []

    def record(resp):
        assert resp.status_code < 500, resp.text
        texts.append(resp.text)
        return resp

    with signing.websocket_connect("ws://192.0.2.10:8000/api/phone/events") as ws:
        key = "00000000-0000-4000-8000-0000000000a1"
        link = record(signing.post("/api/phone/jobs/link", json={
            "client_request_id": key, "url": "https://youtu.be/abc123def45"})).json()["job"]
        wait_until(lambda: store.get(link["id"]).status == "error")
        record(signing.get(f"/api/phone/requests/{key}"))
        record(signing.get(f"/api/phone/jobs/{link['id']}"))
        rerun = record(signing.post(f"/api/phone/jobs/{link['id']}/rerun", json={
            "client_request_id": "00000000-0000-4000-8000-0000000000a2"})).json()["job"]
        wait_until(lambda: store.get(rerun["id"]).status == "error")
        data = b"RIFF fake media" * 100
        upload_key = "00000000-0000-4000-8000-0000000000a3"
        record(signing.post("/api/phone/uploads", json={
            "client_request_id": upload_key, "file_name": "VID_0001.mp4", "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest()}))
        uploaded = record(signing.put(f"/api/phone/uploads/{upload_key}", content=data,
                                      headers={"Content-Type": "application/octet-stream"}))
        uploaded_id = uploaded.json()["job"]["id"]
        record(signing.post(f"/api/phone/jobs/{uploaded_id}/cancel"))
        wait_until(lambda: store.get(uploaded_id).status in ("done", "cancelled"))
        record(signing.get("/api/phone/library"))
        record(signing.delete(f"/api/phone/jobs/{rerun['id']}"))
        frames = []

        async def drain():
            with anyio.move_on_after(1.5):
                while True:
                    frames.append((await ws._send_rx.receive())["text"])

        time.sleep(0.6)                           # let the last coalesced events go out
        ws.portal.call(drain)
        texts.extend(frames)

    assert any('"code":"no_space"' in t.replace(" ", "") for t in texts)
    forbidden = [str(tmp_path), str(Path.home()), getpass.getuser(), str(store.root.resolve())]
    for text in texts:
        for value in forbidden:
            assert value not in text, (value, text[:300])
