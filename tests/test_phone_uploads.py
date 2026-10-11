"""Uploads from phones (server-contract 4.7, 6.6; S-09, S-10 core part, QT-4 server side).

Bodies that stall, are interrupted or are replaced are driven through raw ASGI
calls, so the test decides exactly when each byte arrives; the idle times are
measured with the fake clock.
"""

import asyncio
import hashlib
import os
import secrets
import time
from pathlib import Path

import pytest
from conftest import paired_client, wait_until
from phone_fixtures import sign_header

from phonotrainer.jobs import MEDIA_SUFFIXES, PHONE_ORIGIN
from phonotrainer.phone import uploads as uploads_mod
from phonotrainer.phone.routes import SUFFIXES_TEXT

KEY1 = "00000000-0000-4000-8000-000000000001"
KEY2 = "00000000-0000-4000-8000-000000000002"
KEY3 = "00000000-0000-4000-8000-000000000003"
GIB = 1024 ** 3
DATA = b"\x00\x01fake video bytes" * 64


@pytest.fixture(autouse=True)
def quick_polls(monkeypatch):
    """Idle bodies are noticed within 20 ms of real time (the clock is fake)."""
    monkeypatch.setattr(uploads_mod, "POLL_S", 0.02)


def declare(signing, key=KEY1, name="VID_0001.mp4", data=DATA, **extra):
    body = {"client_request_id": key, "file_name": name, "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest()}
    body.update(extra)
    return signing.post("/api/phone/uploads", json=body)


def put(signing, key=KEY1, data=DATA, **kwargs):
    return signing.put(f"/api/phone/uploads/{key}", content=data,
                       headers={"Content-Type": "application/octet-stream"}, **kwargs)


def parts(app):
    folder = app.state.store.root / ".phone-uploads"
    return sorted(p.name for p in folder.glob("*.part")) if folder.is_dir() else []


# --- declaring ------------------------------------------------------------------------------

def test_a_declaration_opens_a_session(paired, phone_app, fake_clock):
    signing, _, _ = paired
    resp = declare(signing, key=KEY1.upper(), separate_dialogue=False)
    assert resp.status_code == 201, resp.text
    upload = resp.json()["upload"]
    assert resp.json()["state"] == "open"
    assert upload == {"client_request_id": KEY1, "offset": 0, "bytes": len(DATA),
                      "expires_at": "2026-10-12T09:00:00+00:00",
                      "max_chunk_bytes": 8 * 1024 ** 2, "resumable": False}
    again = declare(signing, separate_dialogue=False)
    assert again.status_code == 200 and again.json() == resp.json()
    other = declare(signing, data=DATA + b"x")
    assert other.status_code == 409 and other.json()["code"] == "key_conflict"


def test_the_accepted_types_are_the_servers_media_suffixes(paired):
    signing, _, _ = paired
    assert set(SUFFIXES_TEXT.split()) == MEDIA_SUFFIXES
    resp = declare(signing, name="notes.txt")
    assert resp.status_code == 400 and resp.json()["code"] == "unsupported_type"
    assert ".mp4" in resp.json()["detail"] and ".opus" in resp.json()["detail"]
    for index, name in enumerate(("CLIP.MOV", "voice.M4A", "a.webm")):
        key = f"00000000-0000-4000-8000-00000000010{index}"
        assert declare(signing, key=key, name=name).status_code == 201


@pytest.mark.parametrize("name", ["dir/clip.mp4", "..\\clip.mp4", "\u0000​", "a" * 197 + ".mp4"])
def test_file_names_must_be_base_names(paired, name):
    signing, _, _ = paired
    resp = declare(signing, name=name)
    assert resp.status_code == 400 and resp.json()["code"] == "invalid_request"


def test_control_characters_are_removed_from_file_names(paired, phone_app):
    signing, device_id, _ = paired
    assert declare(signing, name="VID‮_0001.mp4\u0007").status_code == 201
    assert phone_app.state.phone.uploads.get(device_id, KEY1).file_name == "VID_0001.mp4"


@pytest.mark.parametrize("body", [
    {"file_name": "a.mp4", "bytes": 10, "sha256": "0" * 64},
    {"client_request_id": KEY1, "file_name": "a.mp4", "bytes": 0, "sha256": "0" * 64},
    {"client_request_id": KEY1, "file_name": "a.mp4", "bytes": "10", "sha256": "0" * 64},
    {"client_request_id": KEY1, "file_name": "a.mp4", "bytes": 10, "sha256": "xyz"},
])
def test_malformed_declarations(paired, body):
    signing, _, _ = paired
    resp = signing.post("/api/phone/uploads", json=body)
    assert resp.status_code == 400 and resp.json()["code"] == "invalid_request"


def test_too_large_names_the_limit(paired, phone_app):
    signing, _, _ = paired
    resp = signing.post("/api/phone/uploads", json={
        "client_request_id": KEY1, "file_name": "big.mp4", "bytes": 2 * GIB + 1,
        "sha256": "0" * 64})
    assert resp.status_code == 413 and resp.json()["code"] == "too_large"
    assert str(2 * GIB) in resp.json()["detail"]
    # the limit is editable in server.json and followed live
    phone = phone_app.state.phone
    data = phone.config.read_json("server.json")
    data["max_upload_bytes"] = 100
    phone.config.write_json("server.json", data)
    phone.identity.reload_if_changed()
    resp = declare(signing, data=b"x" * 101)
    assert resp.status_code == 413 and "100 bytes" in resp.json()["detail"]


def test_declared_bytes_are_reserved_against_free_space(paired, phone_app, fake_disk, fake_clock):
    signing, _, _ = paired
    fake_disk.free = 5 * GIB
    fake_clock.advance(10)                                      # past the 5 s cache
    first = signing.post("/api/phone/uploads", json={
        "client_request_id": KEY1, "file_name": "a.mp4", "bytes": 2 * GIB, "sha256": "1" * 64})
    assert first.status_code == 201
    health = signing.get("/api/phone/health").json()
    assert health["capabilities"]["free_bytes"] == 3 * GIB       # minus the reservation
    second = signing.post("/api/phone/uploads", json={
        "client_request_id": KEY2, "file_name": "b.mp4", "bytes": 2 * GIB, "sha256": "2" * 64})
    assert second.status_code == 507 and second.json()["code"] == "no_space"
    # each would fit alone: removing the first frees its reservation
    assert signing.delete(f"/api/phone/uploads/{KEY1}").status_code == 204
    second = signing.post("/api/phone/uploads", json={
        "client_request_id": KEY2, "file_name": "b.mp4", "bytes": 2 * GIB, "sha256": "2" * 64})
    assert second.status_code == 201


def test_a_fifth_open_session_is_refused(paired, phone_app, fake_clock):
    signing, _, _ = paired
    for index in range(4):
        assert declare(signing, key=f"00000000-0000-4000-8000-00000000000{index}").status_code == 201
    resp = declare(signing, key=KEY3.replace("3", "9"))
    assert resp.status_code == 409 and resp.json()["code"] == "upload_busy"
    assert resp.json()["detail"] == "Too many unfinished uploads from this phone."
    other, _ = paired_client(phone_app, fake_clock, "Tablet")       # another phone is not limited
    assert declare(other, key="00000000-0000-4000-8000-0000000000aa").status_code == 201
    # Session files are named by the key alone (server-contract 3.2), so a key
    # another phone's open session holds (a UUID clash) is a conflict.
    clash = declare(other, key=KEY3)
    assert clash.status_code == 409 and clash.json()["code"] == "key_conflict"


def test_a_key_that_made_a_link_cannot_declare(paired):
    signing, _, _ = paired
    assert signing.post("/api/phone/jobs/link", json={
        "client_request_id": KEY1, "url": "https://youtu.be/abc123def45"}).status_code == 201
    resp = declare(signing)
    assert resp.status_code == 409 and resp.json()["code"] == "key_conflict"


# --- sending (single PUT) -------------------------------------------------------------------

def test_a_whole_file_creates_the_job_and_moves_the_part(paired, phone_app, monkeypatch):
    signing, device_id, _ = paired
    store = phone_app.state.store
    moved = {}
    original = store.adopt_file

    def spy(path, *args, **kwargs):
        moved["inode"] = os.stat(path).st_ino
        return original(path, *args, **kwargs)

    monkeypatch.setattr(store, "adopt_file", spy)
    assert declare(signing, separate_dialogue=False).status_code == 201
    resp = put(signing)
    assert resp.status_code == 201, resp.text
    job = resp.json()["job"]
    assert job["own"] is True and job["device_id"] == device_id
    assert job["client_request_id"] == KEY1
    assert job["source"] == {"kind": "upload", "url": None, "site": None,
                             "file_name": "VID_0001.mp4"}
    stored = store.get(job["id"])
    assert stored.origin == PHONE_ORIGIN and stored.options["separate_dialogue"] is False
    media = Path(stored.media_path)
    assert media.read_bytes() == DATA
    assert os.stat(media).st_ino == moved["inode"]              # renamed, never copied
    assert parts(phone_app) == []
    assert phone_app.state.phone.uploads.reserved() == 0
    # a lost answer: the same PUT and the same declaration find the job
    again = put(signing)
    assert again.status_code == 200 and again.json()["job"]["id"] == job["id"]
    done = declare(signing, separate_dialogue=False)
    assert done.status_code == 200
    assert done.json()["state"] == "done" and done.json()["job"]["id"] == job["id"]
    assert len(store.list()) == 1


def test_a_long_name_in_bytes_is_shortened_to_fit_the_file_system(paired, phone_app):
    """200 characters are allowed, but a name is limited in BYTES (255 on
    ext4): 104 three-byte characters are 304. The stored name is shortened,
    keeps its suffix, and a repeated declaration still matches."""
    signing, _, _ = paired
    name = "\u3042" * 100 + ".mp4"
    assert len(name) == 104 and len(name.encode("utf-8")) == 304
    first = declare(signing, name=name)
    assert first.status_code == 201, first.text
    assert declare(signing, name=name).status_code == 200            # same session
    resp = put(signing)
    assert resp.status_code == 201, resp.text
    stored = Path(phone_app.state.store.get(resp.json()["job"]["id"]).media_path)
    assert stored.suffix == ".mp4" and stored.name.startswith("\u3042" * 60)
    assert len(stored.name.encode("utf-8")) <= uploads_mod.MAX_FILE_NAME_BYTES
    assert stored.read_bytes() == DATA
    assert resp.json()["job"]["source"]["file_name"] == stored.name
    done = declare(signing, name=name)
    assert done.status_code == 200 and done.json()["state"] == "done"


def test_fit_file_name():
    fit = uploads_mod.fit_file_name
    assert fit("VID_0001.mp4") == "VID_0001.mp4"
    emoji = "\U0001F600" * 70 + ".webm"                            # 285 bytes
    short = fit(emoji)
    assert short.endswith(".webm") and len(short.encode("utf-8")) <= 200
    assert short == fit(emoji) and short[:-5] == "\U0001F600" * 48    # never half a character
    assert fit("a" * 300) == "a" * 200                              # no suffix: cut as a whole
    assert fit(" ." * 120 + ".mp4") == "upload.mp4"                 # nothing left of the stem


def test_a_file_system_error_at_the_move_answers_json_and_keeps_the_session(
        paired, phone_app, monkeypatch):
    import errno

    signing, _, _ = paired
    store = phone_app.state.store
    real = store.adopt_file
    failures = [OSError(errno.EIO, "I/O error"), OSError(errno.ENOSPC, "No space left")]

    def failing(*args, **kwargs):
        if failures:
            raise failures.pop(0)
        return real(*args, **kwargs)

    monkeypatch.setattr(store, "adopt_file", failing)
    assert declare(signing).status_code == 201
    resp = put(signing)
    assert resp.status_code == 500 and resp.json()["code"] == "server_error"
    assert resp.headers["content-type"].startswith("application/json")
    assert parts(phone_app) == [] and store.list() == []
    resp = put(signing)
    assert resp.status_code == 507 and resp.json()["code"] == "no_space"
    assert parts(phone_app) == []
    assert declare(signing).json()["state"] == "open"                 # the session stays
    assert put(signing).status_code == 201                           # and the phone sends again


def test_another_file_under_a_finished_key_is_a_conflict(paired, phone_app):
    signing, _, _ = paired
    assert declare(signing).status_code == 201
    assert put(signing).status_code == 201
    other = DATA[::-1]
    resp = declare(signing, data=other)                             # same name, another file
    assert resp.status_code == 409 and resp.json()["code"] == "key_conflict"
    job = phone_app.state.store.list()[0]
    assert job.upload_bytes == len(DATA)
    assert job.upload_sha256 == hashlib.sha256(DATA).hexdigest()
    assert declare(signing).json()["state"] == "done"
    wait_until(lambda: phone_app.state.store.get(job.id).status == "done")
    from conftest import fake_analyze

    from phonotrainer.jobs import JobStore

    reopened = JobStore(phone_app.state.store.root, analyze_fn=fake_analyze, corpus=None)
    try:                                                            # kept in job.json
        again = reopened.get(job.id)
        assert (again.upload_bytes, again.upload_sha256) == (job.upload_bytes, job.upload_sha256)
    finally:
        reopened.shutdown()


def test_put_without_a_session(paired):
    signing, _, _ = paired
    resp = put(signing)
    assert resp.status_code == 404 and resp.json()["code"] == "unknown_upload"


def test_a_put_refused_before_its_body_closes_the_connection(paired, phone_app):
    """An unsigned or unknown PUT is answered before its file is read: the
    connection closes, so the phone does not send megabytes for nothing."""
    from conftest import lan

    signing, _, _ = paired
    assert declare(signing).status_code == 201
    resp = put(lan(phone_app))                                     # no signature
    assert resp.status_code == 401 and resp.json()["code"] == "credential_rejected"
    assert resp.headers["connection"] == "close"
    resp = put(signing, key=KEY2)                                   # no session
    assert resp.status_code == 404 and resp.headers["connection"] == "close"
    # a JSON route's refusal after its (small) body was read keeps the connection
    resp = lan(phone_app).post("/api/phone/uploads", json={"client_request_id": KEY1})
    assert resp.status_code == 401 and "connection" not in resp.headers


def test_put_needs_its_declared_length(paired, phone_app):
    signing, _, _ = paired
    assert declare(signing).status_code == 201
    resp = put(signing, data=DATA[:-1])
    assert resp.status_code == 400 and resp.json()["code"] == "length_mismatch"

    def chunks():
        yield DATA

    resp = signing.put(f"/api/phone/uploads/{KEY1}", content=chunks())   # chunked: no length
    assert resp.status_code == 411 and resp.json()["code"] == "length_required"
    assert put(signing).status_code == 201


def test_a_damaged_file_is_refused_and_its_part_deleted(paired, phone_app):
    signing, _, _ = paired
    assert declare(signing).status_code == 201
    damaged = bytearray(DATA)
    damaged[5] ^= 0xFF
    resp = put(signing, data=bytes(damaged))
    assert resp.status_code == 422 and resp.json()["code"] == "checksum_mismatch"
    assert parts(phone_app) == []
    assert phone_app.state.store.list() == []
    assert put(signing).status_code == 201                      # the phone sends again


def test_no_space_at_put(paired, phone_app, fake_disk, fake_clock):
    signing, _, _ = paired
    assert declare(signing).status_code == 201
    fake_disk.free = 1 * GIB
    fake_clock.advance(10)
    resp = put(signing)
    assert resp.status_code == 507 and resp.json()["code"] == "no_space"


def test_abandoning_an_upload(paired, phone_app):
    signing, device_id, _ = paired
    assert declare(signing).status_code == 201
    assert signing.delete(f"/api/phone/uploads/{KEY1}").status_code == 204
    assert signing.delete(f"/api/phone/uploads/{KEY1}").status_code == 204    # idempotent
    assert signing.delete("/api/phone/uploads/not-a-key").status_code == 204
    assert phone_app.state.phone.uploads.get(device_id, KEY1) is None
    assert put(signing).json()["code"] == "unknown_upload"


# --- bodies that stall, break or are replaced (raw ASGI) -------------------------------------

class RawPut:
    """One signed `PUT /api/phone/uploads/<key>` whose body the test feeds."""

    def __init__(self, app, device_id, key_bytes, clock, upload_key=KEY1, length=len(DATA),
                 client=("192.0.2.20", 50000)):
        target = f"/api/phone/uploads/{upload_key}"
        auth = sign_header(device_id, key_bytes, "PUT", target, int(clock()),
                           secrets.token_hex(16))
        self.app = app
        self.scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
            "scheme": "http", "method": "PUT", "path": target, "raw_path": target.encode(),
            "query_string": b"", "root_path": "", "client": client,
            "server": ("192.0.2.10", 8000), "state": {},
            "headers": [(b"host", b"192.0.2.10:8000"), (b"authorization", auth.encode()),
                        (b"content-type", b"application/octet-stream"),
                        (b"content-length", str(length).encode())],
        }
        self.messages: asyncio.Queue = asyncio.Queue()
        self.status = None
        self.body = b""
        self.headers = {}
        self.done = asyncio.Event()

    def feed(self, chunk: bytes, more: bool = True) -> None:
        self.messages.put_nowait({"type": "http.request", "body": chunk, "more_body": more})

    def disconnect(self) -> None:
        self.messages.put_nowait({"type": "http.disconnect"})

    async def run(self) -> "RawPut":
        async def receive():
            getter = asyncio.ensure_future(self.messages.get())
            finished = asyncio.ensure_future(self.done.wait())
            await asyncio.wait({getter, finished}, return_when=asyncio.FIRST_COMPLETED)
            if getter.done():
                finished.cancel()
                return getter.result()
            getter.cancel()
            return {"type": "http.disconnect"}

        async def send(message):
            if message["type"] == "http.response.start":
                self.status = message["status"]
                self.headers = {k.decode().lower(): v.decode() for k, v in message["headers"]}
            elif message["type"] == "http.response.body":
                self.body += message.get("body", b"")
                if not message.get("more_body"):
                    self.done.set()

        await self.app(self.scope, receive, send)
        self.done.set()
        return self

    def json(self):
        import json

        return json.loads(self.body)


async def until(predicate, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("condition not met")


def receiving(app, device_id):
    return app.state.phone.uploads._attempts.get(device_id)


def test_a_silent_body_is_aborted_after_30_seconds(paired, phone_app, fake_clock):
    signing, device_id, key = paired
    assert declare(signing).status_code == 201

    async def scenario():
        stalled = RawPut(phone_app, device_id, key, fake_clock)
        task = asyncio.ensure_future(stalled.run())
        stalled.feed(DATA[:100])                    # some bytes, then the Wi-Fi drops
        await until(lambda: parts(phone_app) and receiving(phone_app, device_id) is not None)
        fake_clock.advance(29)
        await asyncio.sleep(0.1)
        assert not task.done()
        fake_clock.advance(1)
        await asyncio.wait_for(task, 5)
        return stalled

    stalled = asyncio.run(scenario())
    assert stalled.status == 400 and stalled.json()["code"] == "length_mismatch"
    assert stalled.headers.get("connection") == "close"
    assert parts(phone_app) == []                                  # the part is deleted
    assert phone_app.state.phone.uploads.get(device_id, KEY1) is not None   # the session stays
    assert receiving(phone_app, device_id) is None                 # its slot is released
    assert put(signing).status_code == 201


def test_newest_wins_after_10_quiet_seconds(paired, phone_app, fake_clock):
    signing, device_id, key = paired
    assert declare(signing).status_code == 201

    async def scenario():
        old = RawPut(phone_app, device_id, key, fake_clock)
        old_task = asyncio.ensure_future(old.run())
        old.feed(DATA[:100])
        await until(lambda: receiving(phone_app, device_id) is not None and parts(phone_app))
        early = await RawPut(phone_app, device_id, key, fake_clock).run()
        assert early.status == 409 and early.json()["code"] == "upload_busy"
        fake_clock.advance(10)
        new = RawPut(phone_app, device_id, key, fake_clock)
        new_task = asyncio.ensure_future(new.run())
        await asyncio.wait_for(old_task, 5)                        # replaced
        new.feed(DATA, more=False)
        await asyncio.wait_for(new_task, 5)
        return old, new

    old, new = asyncio.run(scenario())
    assert old.status == 409 and old.json()["code"] == "upload_busy"
    assert new.status == 201, new.body
    assert Path(phone_app.state.store.get(new.json()["job"]["id"]).media_path).read_bytes() == DATA
    assert parts(phone_app) == []


def test_one_body_per_device_and_other_devices_are_not_blocked(paired, phone_app, fake_clock):
    signing, device_id, key = paired
    other, other_id = paired_client(phone_app, fake_clock, "Tablet")
    assert declare(signing).status_code == 201
    assert declare(signing, key=KEY2).status_code == 201
    assert declare(other, key=KEY3).status_code == 201

    async def scenario():
        stalled = RawPut(phone_app, device_id, key, fake_clock)
        task = asyncio.ensure_future(stalled.run())
        stalled.feed(DATA[:10])
        await until(lambda: receiving(phone_app, device_id) is not None)
        fake_clock.advance(15)
        busy = await RawPut(phone_app, device_id, key, fake_clock, upload_key=KEY2).run()
        assert busy.status == 409 and busy.json()["code"] == "upload_busy"   # another key
        stalled.disconnect()
        await asyncio.wait_for(task, 5)
        return stalled

    loop_result = {}

    def in_other_thread():
        loop_result["other"] = put(other, key=KEY3)

    import threading

    thread = threading.Thread(target=in_other_thread)
    thread.start()
    stalled = asyncio.run(scenario())
    thread.join(10)
    assert loop_result["other"].status_code == 201              # the second phone went through
    assert stalled.status == 400                                # an interrupted body
    assert parts(phone_app) == []
    assert phone_app.state.phone.uploads.get(device_id, KEY1) is not None
    assert put(signing, key=KEY2).status_code == 201            # the slot is free again


def test_an_interrupted_body_deletes_its_part_and_keeps_the_session(paired, phone_app,
                                                                   fake_clock):
    signing, device_id, key = paired
    assert declare(signing).status_code == 201

    async def scenario():
        broken = RawPut(phone_app, device_id, key, fake_clock)
        task = asyncio.ensure_future(broken.run())
        broken.feed(DATA[:200])
        await until(lambda: parts(phone_app))
        broken.disconnect()
        await asyncio.wait_for(task, 5)

    asyncio.run(scenario())
    assert parts(phone_app) == []
    resp = declare(signing)
    assert resp.status_code == 200 and resp.json()["upload"]["offset"] == 0
    assert put(signing).status_code == 201                      # restarts with the same key


def test_removing_an_upload_stops_its_body(paired, phone_app, fake_clock):
    signing, device_id, key = paired
    assert declare(signing).status_code == 201

    async def scenario():
        sending = RawPut(phone_app, device_id, key, fake_clock)
        task = asyncio.ensure_future(sending.run())
        sending.feed(DATA[:50])
        await until(lambda: parts(phone_app))
        phone_app.state.phone.uploads.remove(device_id, KEY1)     # what DELETE does
        await asyncio.wait_for(task, 5)
        return sending

    sending = asyncio.run(scenario())
    assert sending.status == 409
    assert parts(phone_app) == []
    assert put(signing).json()["code"] == "unknown_upload"


# --- the janitor and a restart ----------------------------------------------------------------

def test_the_janitor_removes_sessions_untouched_for_48_hours(paired, phone_app, fake_clock):
    signing, device_id, _ = paired
    uploads = phone_app.state.phone.uploads
    assert declare(signing).status_code == 201
    fake_clock.advance(24 * 3600)
    assert declare(signing, key=KEY2).status_code == 201
    orphan = uploads.dir / f"{KEY3}.part"
    orphan.write_bytes(b"left behind")
    fake_clock.advance(24 * 3600)
    assert uploads.sweep() == 1
    assert uploads.get(device_id, KEY1) is None and uploads.get(device_id, KEY2) is not None
    assert not (uploads.dir / f"{KEY1}.json").exists()
    assert not orphan.exists()
    assert put(signing).json()["code"] == "unknown_upload"


def test_sessions_survive_a_restart_and_parts_do_not(phone_settings, tmp_path, phone_server):
    from phonotrainer.jobs import JobStore
    from phonotrainer.phone.uploads import UploadSessions

    store = JobStore(tmp_path / "ws", corpus=None)
    app, signing, device_id = phone_server(store)
    assert declare(signing).status_code == 201
    uploads = app.state.phone.uploads
    (uploads.dir / f"{KEY1}.part").write_bytes(b"half")
    restarted = UploadSessions(tmp_path / "ws", phone_settings.clock)
    assert restarted.get(device_id, KEY1).sha256 == hashlib.sha256(DATA).hexdigest()
    assert not (uploads.dir / f"{KEY1}.part").exists()
    assert restarted.reserved() == len(DATA)


def test_a_declaration_after_a_job_was_deleted_starts_again(paired, phone_app):
    signing, _, _ = paired
    assert declare(signing).status_code == 201
    job_id = put(signing).json()["job"]["id"]
    wait_until(lambda: phone_app.state.store.get(job_id).status == "done")
    phone_app.state.store.delete(job_id)                       # deleted on the computer
    assert declare(signing).status_code == 201                 # the key is free again


def test_a_name_the_file_system_refuses_leaves_the_file_and_no_job(phone_store, tmp_path):
    """The store's side of a refused move: the file stays where it was (the
    route deletes the part), and no job directory is left behind."""
    part = tmp_path / "upload.part"
    part.write_bytes(DATA)
    too_long = "あ" * 100 + ".mp4"                      # 304 bytes: ENAMETOOLONG
    before = sorted(p.name for p in phone_store.root.iterdir()) if phone_store.root.exists() \
        else []
    with pytest.raises(OSError):
        phone_store.adopt_file(part, too_long, {})
    assert part.read_bytes() == DATA
    assert sorted(p.name for p in phone_store.root.iterdir()) == before
    assert phone_store.list() == []
