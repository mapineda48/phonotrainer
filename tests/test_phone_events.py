"""The job channel for phones: `WS /api/phone/events` (server-contract 7; S-14, S-05).

The channel is opened through Starlette's test client from a LAN address. A
refused upgrade is closed before accept (on a real socket the phone sees the
handshake refused with HTTP 403), which the test client reports as
WebSocketDisconnect when connecting.
"""

import json
import secrets
import time
from contextlib import ExitStack
from pathlib import Path

import anyio
import pytest
from conftest import fake_analyze, lan, paired_client, wait_until, write_silent_wav
from phone_fixtures import sign_header
from starlette.websockets import WebSocketDisconnect

from phonotrainer.download import DownloadError
from phonotrainer.jobs import PHONE_ORIGIN, JobStore
from phonotrainer.phone import events as events_mod
from phonotrainer.server import API_VERSION

EVENTS_URL = "ws://192.0.2.10:8000/api/phone/events"
KEY1 = "00000000-0000-4000-8000-000000000001"
KEY2 = "00000000-0000-4000-8000-000000000002"
KEY3 = "00000000-0000-4000-8000-000000000003"
ORDER = {"queued": 0, "running": 1, "done": 2, "error": 2, "cancelled": 2}


def receive(ws, timeout: float = 10.0) -> dict:
    """The next frame as JSON. The test client's own `receive_json` waits
    forever; this one fails the test after `timeout` seconds."""
    async def next_frame():
        with anyio.fail_after(timeout):
            return await ws._send_rx.receive()

    message = ws.portal.call(next_frame)
    ws._raise_on_close(message)
    return json.loads(message["text"])


def receive_until(ws, predicate, timeout: float = 10.0) -> list[dict]:
    """Every frame up to and including the first that satisfies `predicate`."""
    frames = []
    deadline = time.monotonic() + timeout
    while True:
        frame = receive(ws, max(0.1, deadline - time.monotonic()))
        frames.append(frame)
        if predicate(frame):
            return frames


def opened(ws) -> tuple[dict, dict]:
    """The first two frames: hello and the snapshot."""
    return receive(ws), receive(ws)


def refused(client, url: str = EVENTS_URL, **kwargs) -> None:
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect(url, **kwargs) as ws:
            receive(ws, timeout=2)


def link(signing, key=KEY1, url="https://youtu.be/abc123def45"):
    return signing.post("/api/phone/jobs/link", json={"client_request_id": key, "url": url})


def forbidden_download(url, dest_dir, audio_only=False, progress=lambda m: None, **kwargs):
    progress("Looking up the URL…")
    raise DownloadError("unable to download video data: HTTP Error 403: Forbidden")


# --- the upgrade (7, 4.3, 4.4) -------------------------------------------------------------

def test_an_upgrade_without_a_valid_signature_is_refused(phone_app, paired, fake_clock):
    signing, device_id, key = paired
    refused(lan(phone_app))                                       # no signature
    ts = int(fake_clock())
    refused(lan(phone_app), headers={"Authorization": sign_header(
        device_id, b"\x00" * 32, "GET", "/api/phone/events", ts, secrets.token_hex(16))})
    refused(lan(phone_app), headers={"Authorization": sign_header(        # signed for another path
        device_id, key, "GET", "/api/phone/jobs", ts, secrets.token_hex(16))})
    refused(lan(phone_app), headers={"Authorization": "Bearer pt1_" + "a" * 43})
    fake_clock.advance(400)                                       # outside the clock window
    refused(lan(phone_app), headers={"Authorization": sign_header(
        device_id, key, "GET", "/api/phone/events", ts, secrets.token_hex(16))})
    assert phone_app.state.phone.channels.count() == 0


def test_a_signed_upgrade_is_accepted_once(phone_app, paired, fake_clock):
    _, device_id, key = paired
    header = {"Authorization": sign_header(device_id, key, "GET", "/api/phone/events",
                                           int(fake_clock()), secrets.token_hex(16))}
    with lan(phone_app).websocket_connect(EVENTS_URL, headers=header) as ws:
        assert receive(ws)["type"] == "hello"
    refused(lan(phone_app), headers=header)                       # the same nonce again


def test_an_upgrade_with_a_bad_host_is_refused(phone_app, paired):
    signing, _, _ = paired
    refused(signing, headers={"Host": "attacker.example:8000"})
    refused(signing, headers={"Host": "192.0.2.99:8000"})        # not the arrival address
    with signing.websocket_connect(EVENTS_URL) as ws:             # the arrival address passes
        assert receive(ws)["type"] == "hello"


def test_a_revoked_phone_cannot_open_a_channel(phone_app, paired):
    signing, device_id, _ = paired
    phone_app.state.phone.registry.revoke(device_id)
    refused(signing)


# --- what the channel says ------------------------------------------------------------------

def test_hello_then_a_lean_snapshot_of_every_job(phone_app, paired, phone_store, tmp_path):
    signing, device_id, _ = paired
    media = write_silent_wav(tmp_path / "in" / "clip.wav")
    mine = phone_store.adopt_upload("clip.wav", lambda dest: dest.write_bytes(media.read_bytes()),
                                    {}, device_id=device_id, client_request_id=KEY1,
                                    origin=PHONE_ORIGIN)
    browser = phone_store.create(write_silent_wav(tmp_path / "browser.wav"))
    for job in (mine, browser):
        wait_until(lambda job=job: phone_store.get(job.id).status == "done")

    with signing.websocket_connect(EVENTS_URL) as ws:
        hello, snapshot = opened(ws)
    runtime = phone_app.state.phone
    assert hello == {"type": "hello", "server_id": runtime.identity.server_id,
                     "library_id": runtime.library_id, "api_version": API_VERSION}
    assert snapshot["type"] == "snapshot" and set(snapshot) == {"type", "jobs"}
    ids = [job["id"] for job in snapshot["jobs"]]
    assert ids == sorted([mine.id, browser.id])
    for job in snapshot["jobs"]:
        assert "artifacts" not in job and "progress" in job          # the percent object only
        full = signing.get(f"/api/phone/jobs/{job['id']}").json()
        full.pop("artifacts")
        assert job == full                     # the lean PhoneJob is the PhoneJob without artifacts
    by_id = {job["id"]: job for job in snapshot["jobs"]}
    assert by_id[mine.id]["own"] is True and by_id[mine.id]["device_id"] == device_id
    assert by_id[browser.id]["own"] is False and by_id[browser.id]["device_id"] is None
    text = json.dumps(snapshot)
    for private in (str(tmp_path), str(Path.home()), "result_dir", "media_path", "Queued →"):
        assert private not in text


def test_job_events_follow_a_lifecycle_and_never_go_backwards(phone_app, paired, phone_store):
    signing, _, _ = paired
    with signing.websocket_connect(EVENTS_URL) as ws:
        opened(ws)
        job_id = link(signing).json()["job"]["id"]
        frames = receive_until(ws, lambda f: f["type"] == "job" and f["job"]["id"] == job_id
                               and f["job"]["status"] == "done")
    events = [f["job"] for f in frames if f["type"] == "job" and f["job"]["id"] == job_id]
    statuses = [event["status"] for event in events]
    assert statuses[-1] == "done"
    assert [ORDER[s] for s in statuses] == sorted(ORDER[s] for s in statuses)
    assert all("artifacts" not in event for event in events)
    assert events[-1]["own"] is True and events[-1]["client_request_id"] == KEY1
    assert events[-1]["revision"] == signing.get(f"/api/phone/jobs/{job_id}").json()["revision"]


def test_a_re_run_and_a_delete_send_deleted_events(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=forbidden_download)
    app, signing, _ = phone_server(store)
    old = link(signing).json()["job"]["id"]
    wait_until(lambda: store.get(old).status == "error")
    with signing.websocket_connect(EVENTS_URL) as ws:
        opened(ws)
        resp = signing.post(f"/api/phone/jobs/{old}/rerun", json={"client_request_id": KEY2})
        assert resp.status_code == 201, resp.text
        new = resp.json()["job"]["id"]
        # the old job's `deleted` and the new job's `job` arrive together
        # (the phone keeps one row); the re-run fails again, as the download is refused
        seen = []
        receive_until(ws, lambda f: seen.append(f) or (
            {"type": "deleted", "id": old} in seen
            and any(g["type"] == "job" and g["job"]["id"] == new
                    and g["job"]["status"] == "error" for g in seen)))
        assert seen.index({"type": "deleted", "id": old}) <= min(
            i for i, g in enumerate(seen) if g["type"] == "job" and g["job"]["id"] == new) + 1
        assert all(g["job"]["rerun_of"] == old for g in seen
                   if g["type"] == "job" and g["job"]["id"] == new)
        assert signing.delete(f"/api/phone/jobs/{new}").status_code == 204
        receive_until(ws, lambda f: f == {"type": "deleted", "id": new})


def test_job_events_are_coalesced(phone_app, paired, phone_store, tmp_path, monkeypatch):
    signing, _, _ = paired
    busy = phone_store.create(write_silent_wav(tmp_path / "busy.wav"))
    sentinel = phone_store.create(write_silent_wav(tmp_path / "sentinel.wav"))
    for job in (busy, sentinel):
        wait_until(lambda job=job: phone_store.get(job.id).status == "done")
    with signing.websocket_connect(EVENTS_URL) as ws:
        opened(ws)
        for _ in range(100):                                       # a burst of store changes
            phone_store.touch(busy.id)
        time.sleep(3 * events_mod.COALESCE_S)
        phone_store.delete(sentinel.id)                            # not coalesced: sent at once
        frames = receive_until(ws, lambda f: f == {"type": "deleted", "id": sentinel.id})
    sent = [f for f in frames if f["type"] == "job" and f["job"]["id"] == busy.id]
    # the first change at once, the rest as one event 500 ms later (the latest state)
    assert 1 <= len(sent) <= 3, len(sent)


def test_a_revoked_phones_channel_closes(phone_app, paired, monkeypatch):
    monkeypatch.setattr(events_mod, "RECHECK_S", 0.05)
    signing, device_id, _ = paired
    with signing.websocket_connect(EVENTS_URL) as ws:
        opened(ws)
        phone_app.state.phone.registry.revoke(device_id)
        with pytest.raises(WebSocketDisconnect):
            receive(ws, timeout=10)
    wait_until(lambda: phone_app.state.phone.channels.count() == 0)


def test_phone_access_turned_off_closes_the_channel(phone_app, paired, monkeypatch):
    monkeypatch.setattr(events_mod, "RECHECK_S", 0.05)
    signing, _, _ = paired
    with signing.websocket_connect(EVENTS_URL) as ws:
        opened(ws)
        phone_app.state.phone.identity.turn_off()
        with pytest.raises(WebSocketDisconnect):
            receive(ws, timeout=10)


# --- limits (4.7) ---------------------------------------------------------------------------

def test_two_channels_per_device_and_eight_in_all(phone_app, paired, fake_clock):
    signing, _, _ = paired
    slots = phone_app.state.phone.channels
    with ExitStack() as stack:
        for _ in range(2):
            opened(stack.enter_context(signing.websocket_connect(EVENTS_URL)))
        refused(signing)                                           # this device's third
        others = [paired_client(phone_app, fake_clock, f"Phone {n}")[0] for n in range(4)]
        for client in others[:3]:
            for _ in range(2):
                opened(stack.enter_context(client.websocket_connect(EVENTS_URL)))
        assert slots.count() == 8
        refused(others[3])                                         # the ninth
        assert slots.count() == 8
    wait_until(lambda: slots.count() == 0)
    with others[3].websocket_connect(EVENTS_URL) as ws:            # slots come back
        assert receive(ws)["type"] == "hello"
