"""The host-side fake computer (server-contract 12, 11.3; S-21 without side effects).

Every emulator test and every [emulator] step of device QA talks to a "fake
computer": the real server code with a synthetic analysis writer and a
downloader that refuses every link, on throwaway directories, so it can never
run the pipeline, write the owner's corpus or reach a website.

Two proofs:
1. the stable entry point it is built from (`create_app(phone=…)`,
   `PhoneSettings`, `JobStore` with injected analysis and download functions,
   `enable_phone_access`, the registry's pairing code) is assembled here the
   way the contract describes, served by a real uvicorn on 127.0.0.1, and a
   whole phone session runs against it over real sockets (HTTP and the
   WebSocket channel): pair → list → download → upload → cancel → re-run →
   cancel → delete → unpair, plus a refused link from a phone and from the
   browser. The directory it was started from must stay empty;
2. when this checkout carries the device-QA kit's
   `android/scripts/device-qa/host_computer.py` (work-plan WP-06), its own
   `--self-check` must pass from an empty working directory: it starts the
   script, runs a phone session over HTTP and checks that nothing is left
   behind.
"""

import hashlib
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

import httpx
import pytest
from conftest import fake_analyze, write_silent_wav
from phone_fixtures import PUBLIC_V4, PT1Auth, sign_header

from phonotrainer.errors import JobCancelled  # noqa: F401  (the synthetic analysis raises it)
from phonotrainer.jobs import CANCELLED, DONE, ERROR, JobStore
from phonotrainer.phone.identity import enable_phone_access
from phonotrainer.phone.netinfo import Interface
from phonotrainer.phone.registry import token_key
from phonotrainer.phone.settings import PhoneSettings
from phonotrainer.server import API_VERSION, create_app

REPO = Path(__file__).resolve().parents[1]
HOST_COMPUTER = REPO / "android" / "scripts" / "device-qa" / "host_computer.py"
REFUSAL = "downloads are disabled on the fake computer"
NEVER_FETCHED = "https://video.example/never-fetched"
FINISHED = {DONE, ERROR, CANCELLED}


class RefusingDownloads:
    """Every download is refused before any connection; the calls are kept."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def __call__(self, url, dest_dir=None, *args, **kwargs):
        from phonotrainer.download import DownloadError

        self.calls.append((url, kwargs))
        raise DownloadError(REFUSAL)


def synthetic_analysis(media_path, out_dir, progress=lambda m: None, **options):
    """The pipeline's progress strings over about a second (so a cancel lands
    promptly), then the conftest double's artifacts."""
    progress("Extracting audio (ffmpeg → 16 kHz mono WAV)…")
    for index in range(1, 11):
        time.sleep(0.1)
        progress(f"Segment {index}/10: phones + alignment…")
    return fake_analyze(media_path, out_dir, progress=progress, **options)


def offline_resolver(host: str) -> list[str]:
    """Never asks DNS: every name is a global address nothing connects to."""
    return [PUBLIC_V4]


class FakeComputer:
    """The fake computer of server-contract 12, assembled from the public
    entry point and served by uvicorn on 127.0.0.1 in a thread."""

    def __init__(self, base: Path) -> None:
        import uvicorn

        self.base = base
        for name in ("config", "workspace", "downloads", "seed"):
            (base / name).mkdir(parents=True)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.port = self.sock.getsockname()[1]
        fake_wifi = Interface("fake0", "192.0.2.10", 24, None)   # approved, exists nowhere
        self.settings = PhoneSettings(config_dir=base / "config", resolver=offline_resolver,
                                      netinfo=lambda: [fake_wifi], discovery_port=0,
                                      discovery_host="127.0.0.1", port=self.port)
        enable_phone_access(self.settings)
        self.downloads = RefusingDownloads()
        self.store = JobStore(base / "workspace", analyze_fn=synthetic_analysis, corpus=None,
                              download_dir=base / "downloads", download_fn=self.downloads)
        seed = self.store.create(write_silent_wav(base / "seed" / "seed clip.wav", seconds=2.0),
                                 options={"separate_dialogue": True})
        self.seed_id = seed.id
        self.app = create_app(store=self.store, phone=self.settings, web_dist=None,
                              allowed_roots=[base])
        config = uvicorn.Config(self.app, host="127.0.0.1", port=self.port, proxy_headers=False,
                                access_log=False, log_level="warning", lifespan="on")
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(target=self.server.run, kwargs={"sockets": [self.sock]},
                                       daemon=True)

    def __enter__(self) -> "FakeComputer":
        self.thread.start()
        deadline = time.monotonic() + 30
        while not self.server.started:
            assert self.thread.is_alive() and time.monotonic() < deadline, "uvicorn did not start"
            time.sleep(0.02)
        return self

    def __exit__(self, *exc) -> None:
        self.server.should_exit = True
        self.thread.join(timeout=30)
        self.sock.close()
        self.store.shutdown(wait=True)

    def pairing_code(self) -> str:
        return self.app.state.phone.registry.create_code().code


def poll(fetch, done, timeout=60.0, what="a state"):
    deadline = time.monotonic() + timeout
    while True:
        value = fetch()
        if done(value):
            return value
        assert time.monotonic() < deadline, f"timed out waiting for {what}: {value!r}"
        time.sleep(0.05)


def listing(path: Path) -> list[str]:
    return sorted(os.listdir(path))


def test_the_entry_point_serves_a_whole_phone_session(tmp_path, monkeypatch):
    from websockets.exceptions import InvalidStatus
    from websockets.sync.client import connect

    start = tmp_path / "started-here"
    start.mkdir()
    monkeypatch.chdir(start)                    # data/ or downloads/ would land here
    with FakeComputer(tmp_path / "throwaway") as computer:
        base = f"http://127.0.0.1:{computer.port}"
        plain = httpx.Client(base_url=base, timeout=30)

        hello = plain.get("/api/phone/hello").json()
        assert hello["phone_access"] is True and hello["api_version"] == API_VERSION
        emulator = plain.get("/api/phone/hello", headers={"Host": f"10.0.2.2:{computer.port}"})
        assert emulator.status_code == 200                   # the emulator's alias of loopback
        assert plain.get("/api/phone/health").json()["code"] == "credential_rejected"

        # pair
        paired = plain.post("/api/phone/pair", json={
            "code": computer.pairing_code(), "device_name": "Emulator", "app_version": 1})
        assert paired.status_code == 201, paired.text
        device_id, key = paired.json()["device_id"], token_key(paired.json()["token"])
        phone = httpx.Client(base_url=base, timeout=30, auth=PT1Auth(device_id, key, time.time))
        health = phone.get("/api/phone/health").json()
        assert health["device"]["device_id"] == device_id
        assert health["capabilities"]["downloader"]["name"] == "yt-dlp"

        # the job channel, over a real socket: refused unsigned (HTTP 403), then open
        url = f"ws://127.0.0.1:{computer.port}/api/phone/events"
        with pytest.raises(InvalidStatus) as refused:
            connect(url, open_timeout=10).close()
        assert refused.value.response.status_code == 403

        def signed_upgrade():
            return {"Authorization": sign_header(device_id, key, "GET", "/api/phone/events",
                                                 int(time.time()), secrets.token_hex(16))}

        channel = connect(url, additional_headers=signed_upgrade(), open_timeout=10)
        frames: list[dict] = []

        def frame():
            frames.append(json.loads(channel.recv(timeout=30)))
            return frames[-1]

        assert frame()["type"] == "hello"
        assert frame()["type"] == "snapshot"

        # list, and download the seeded clip's study pack with its checksums
        def seed():
            return phone.get(f"/api/phone/jobs/{computer.seed_id}").json()

        clip = poll(seed, lambda job: job["status"] == "done" and job["artifacts"] and all(
            a["status"] == "ready" for a in job["artifacts"]), what="the seeded clip")
        assert computer.seed_id in [j["id"] for j in phone.get("/api/phone/library").json()["jobs"]]
        names = {a["name"] for a in clip["artifacts"]}
        assert {"analysis", "audio_mix", "audio_dialogue"} <= names
        for artifact in clip["artifacts"]:
            body = phone.get(artifact["url"])
            assert body.status_code == 200
            assert hashlib.sha256(body.content).hexdigest() == artifact["sha256"]
            assert body.headers["etag"] == f'"{artifact["sha256"]}"'
        tail = phone.get(clip["artifacts"][0]["url"], headers={"Range": "bytes=10-"})
        assert tail.status_code == 206

        # upload a synthetic WAV (larger than the 16 KiB JSON limit, in many chunks)
        data = write_silent_wav(tmp_path / "upload" / "take.wav", seconds=20.0).read_bytes()
        key1 = str(uuid.uuid4())
        declared = phone.post("/api/phone/uploads", json={
            "client_request_id": key1, "file_name": "take.wav", "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(), "separate_dialogue": False})
        assert declared.status_code == 201, declared.text
        put = phone.put(f"/api/phone/uploads/{key1}", content=data,
                        headers={"Content-Type": "application/octet-stream"})
        assert put.status_code == 201, put.text
        job_id = put.json()["job"]["id"]

        # cancel → re-run → cancel → delete
        def status(job):
            return phone.get(f"/api/phone/jobs/{job}").json()["status"]

        assert phone.post(f"/api/phone/jobs/{job_id}/cancel").status_code == 200
        poll(lambda: status(job_id), lambda s: s == "cancelled", what="the cancelled upload")
        rerun = phone.post(f"/api/phone/jobs/{job_id}/rerun",
                           json={"client_request_id": str(uuid.uuid4())})
        assert rerun.status_code == 201 and rerun.json()["replaced"] == job_id, rerun.text
        again = rerun.json()["job"]["id"]
        assert phone.get(f"/api/phone/jobs/{job_id}").status_code == 404
        assert phone.post(f"/api/phone/jobs/{again}/cancel").status_code == 200
        poll(lambda: status(again), lambda s: s == "cancelled", what="the cancelled re-run")
        assert phone.delete(f"/api/phone/jobs/{again}").status_code == 204
        assert phone.get(f"/api/phone/jobs/{again}").json()["code"] == "unknown_job"
        while {"type": "deleted", "id": again} not in frames:
            frame()
        assert {"type": "deleted", "id": job_id} in frames          # the re-run replaced it
        channel.close()

        # every download is refused: a phone's link (confined) and the browser's
        link = phone.post("/api/phone/jobs/link", json={"client_request_id": str(uuid.uuid4()),
                                                        "url": NEVER_FETCHED})
        assert link.status_code == 201, link.text
        failed = poll(lambda: phone.get(f"/api/phone/jobs/{link.json()['job']['id']}").json(),
                      lambda job: job["status"] in FINISHED, what="the phone's link")
        assert failed["status"] == "error" and REFUSAL in failed["error"]["message"]
        browser = plain.post("/api/jobs/youtube", json={"url": NEVER_FETCHED})
        assert browser.status_code == 201
        poll(lambda: plain.get(f"/api/jobs/{browser.json()['id']}").json()["status"],
             lambda s: s in FINISHED, what="the browser's link")
        assert [(url, kwargs.get("phone_origin", False)) for url, kwargs in
                computer.downloads.calls] == [(NEVER_FETCHED, True), (NEVER_FETCHED, False)]

        # unpair
        assert phone.delete("/api/phone/devices/self").status_code == 204
        assert phone.get("/api/phone/health").json()["code"] == "credential_rejected"

    assert listing(start) == []                 # nothing written where it was started
    throwaway = listing(tmp_path / "throwaway")
    assert "data" not in throwaway and listing(tmp_path / "throwaway" / "downloads") == []


@pytest.mark.skipif(not HOST_COMPUTER.is_file(),
                    reason="the device-QA kit's host_computer.py is not in this checkout yet")
def test_the_device_qa_kits_fake_computer_passes_its_self_check(tmp_path):
    start = tmp_path / "started-here"
    start.mkdir()
    parent = tmp_path / "parent"
    parent.mkdir()
    env = {k: v for k, v in os.environ.items() if not k.startswith("PHONOTRAINER_")}
    result = subprocess.run(
        [sys.executable, "-B", str(HOST_COMPUTER), "--self-check", "--parent", str(parent),
         "--python", sys.executable],
        cwd=start, env=env, capture_output=True, text=True, timeout=600, check=False)
    output = result.stdout + result.stderr
    assert result.returncode == 0, output[-4000:]
    assert "self-check: PASS" in output
    # milestone B's routes exist, so the session went through uploads, cancel and re-run
    assert "uploaded, cancelled, re-ran, cancelled and deleted" in output, output[-4000:]
    assert listing(start) == [] and listing(parent) == []
