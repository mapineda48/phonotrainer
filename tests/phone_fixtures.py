"""Phone access test support: reference vectors, the request signer and fakes.

The reference vectors below are the contract between this server and the
Android client (server-contract 4.3 and 6.2): the app's JVM tests (WP-05a,
WP-05b) copy them verbatim and must reproduce every value. They were checked
against `openssl dgst -sha256 -mac HMAC` when written. Every value is
synthetic: the token encodes the bytes 0x00 to 0x1f, the ids are the contract's
placeholders, and the addresses below are in the documentation ranges
(192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24; MACs in 00:00:5e:00:53:xx).

`PT1Auth` is the reference signer (about twenty lines) the phone's signing
interceptor is checked against.
"""

from __future__ import annotations

import asyncio
import secrets
from dataclasses import dataclass, field
from pathlib import Path

import httpx

from phonotrainer.phone.netinfo import Interface

# --- reference vectors ------------------------------------------------------------------
# hygiene: test-vector (the next lines hold a synthetic token and its MACs)
REFERENCE_TOKEN = "pt1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"  # hygiene: test-vector
REFERENCE_KEY_HEX = "b5f85503ce688bacc6fba9efa682fc78d6ea1cc93de4765e213f5af7176de5a6"  # hygiene: test-vector
REFERENCE_DEVICE_ID = "dev_example1"
REFERENCE_SERVER_ID = "srv_example1"
REFERENCE_TS = "1791622800"
REFERENCE_NONCE = "00112233445566778899aabbccddeeff"

# (method, request target as sent, expected mac); message =
# "pt-req-v1|" + METHOD + "|" + target + "|" + ts + "|" + nonce
REFERENCE_SIGNATURES = [
    ("GET", "/api/phone/jobs/20261010-091503-4be1/artifacts/audio_mix",
     "a8b96a295b5eca4413be81636b8b80d004d488b19a3c668b6098f10ea8204440"),  # hygiene: test-vector
    ("POST", "/api/phone/jobs/link",
     "37b0f8f47b41f3f51dbc435b77c4dc77bc932a711650a8f5a1e93ca1b1af23e3"),  # hygiene: test-vector
    ("GET", "/api/phone/events",
     "fbadc4187b93dca65ddbbde0575679a13347727982d5192e9b43ba1772e4f406"),  # hygiene: test-vector
    ("GET", "/api/phone/jobs/20261010-091503-4be1/contour?start=1.5&end=2.25",
     "417ee7d0a66de6004e9c10c246bd99a04d1b7833afef3980d36018f5ba747c20"),  # hygiene: test-vector
]
# The whole header of the first vector, as the phone sends it.
REFERENCE_HEADER = (
    "PT1 device=dev_example1, ts=1791622800, nonce=00112233445566778899aabbccddeeff, "  # hygiene: test-vector
    "mac=a8b96a295b5eca4413be81636b8b80d004d488b19a3c668b6098f10ea8204440")  # hygiene: test-vector

# hello: proof = HMAC(K, "pt-hello-v1|" + server_id + "|" + device_id + "|" + challenge)
REFERENCE_CHALLENGE = "0123456789abcdef0123456789abcdef"
REFERENCE_HELLO_PROOF = "f6113ae8ea4e0474d3b247c49e6a053e4d593ace49e2b3e745bf4da58391b296"  # hygiene: test-vector
# pair: previous_token_proof = HMAC(K_old, "pt-reactivate-v1|" + code)
REFERENCE_CODE = "482913"
REFERENCE_REACTIVATION_PROOF = "6c25019dd33f72685983eea58450f519b69da49f1b31857490345bdd61e497b7"  # hygiene: test-vector


# --- the reference signer ---------------------------------------------------------------

def sign_header(device_id: str, key: bytes, method: str, target: str, ts: int | str,
                nonce: str) -> str:
    import hashlib
    import hmac

    message = f"pt-req-v1|{method.upper()}|{target}|{ts}|{nonce}"
    mac = hmac.new(key, message.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"PT1 device={device_id}, ts={ts}, nonce={nonce}, mac={mac}"


class PT1Auth(httpx.Auth):
    """Adds a fresh `Authorization: PT1 …` to every request: the target is the
    request's raw path and query exactly as sent, `ts` the given clock."""

    def __init__(self, device_id: str, key: bytes, clock) -> None:
        self.device_id, self.key, self.clock = device_id, key, clock

    def auth_flow(self, request: httpx.Request):
        target = request.url.raw_path.decode("ascii")
        request.headers["Authorization"] = sign_header(
            self.device_id, self.key, request.method, target, int(self.clock()),
            secrets.token_hex(16))
        yield request


# --- fakes ---------------------------------------------------------------------------------

class FakeClock:
    """A settable clock (unix seconds) shared by every phone component."""

    def __init__(self, start: float = 1791622800.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


HOME_MAC = "00:00:5e:00:53:01"
OTHER_MAC = "00:00:5e:00:53:02"
LOOPBACK_IF = Interface("lo", "127.0.0.1", 8, None, up=True, loopback=True)
WLAN_HOME = Interface("wlan0", "192.0.2.10", 24, HOME_MAC)
WLAN_CAFE = Interface("wlan0", "203.0.113.10", 24, OTHER_MAC)
DOCKER = Interface("docker0", "198.51.100.1", 24, None)
TUNNEL = Interface("tun0", "198.51.100.129", 25, None)


@dataclass
class FakeNetinfo:
    """The computer's interfaces, switchable per test."""

    current: list[Interface] = field(
        default_factory=lambda: [LOOPBACK_IF, WLAN_HOME, DOCKER, TUNNEL])

    def __call__(self) -> list[Interface]:
        return list(self.current)

    def at_home(self) -> None:
        self.current = [LOOPBACK_IF, WLAN_HOME, DOCKER, TUNNEL]

    def at_cafe(self) -> None:
        self.current = [LOOPBACK_IF, WLAN_CAFE, DOCKER, TUNNEL]

    def offline(self) -> None:
        self.current = [LOOPBACK_IF]


def ffprobe_json(*, video: dict | None = None, audio: dict | None = None,
                 duration: float | None = 3.0, attached_pic: bool = False) -> dict:
    """An ffprobe answer shaped like `-show_streams -show_format`."""
    streams = []
    if video is not None:
        streams.append({"codec_type": "video", "codec_name": video.get("codec", "h264"),
                        "width": video.get("width", 1280), "height": video.get("height", 720),
                        "start_time": video.get("start_time", "0.000000"),
                        "side_data_list": video.get("side_data_list", []),
                        "tags": video.get("tags", {}),
                        "disposition": {"attached_pic": 1 if attached_pic else 0}})
    if audio is not None:
        streams.append({"codec_type": "audio", "codec_name": audio.get("codec", "aac"),
                        "start_time": audio.get("start_time", "0.000000")})
    fmt = {"duration": f"{duration:.6f}"} if duration is not None else {}
    return {"streams": streams, "format": fmt}


class FakeProbe:
    """Media facts per file name; a file name it does not know reads as a
    plain 1280x720 H.264 video. `calls` counts the probes."""

    def __init__(self) -> None:
        self.by_name: dict[str, dict | None | Exception] = {}
        self.calls = 0

    def __call__(self, path: Path) -> dict | None:
        self.calls += 1
        answer = self.by_name.get(Path(path).name, ffprobe_json(video={}, audio={}))
        if isinstance(answer, Exception):
            raise answer
        return answer


@dataclass
class FakeDisk:
    free: int = 50 * 1024 ** 3

    def __call__(self, path) -> object:
        from collections import namedtuple

        usage = namedtuple("usage", "total used free")
        return usage(self.free * 2, self.free, self.free)


def fake_which(name: str) -> str | None:
    return "/usr/bin/node" if name == "node" else None


def no_runner(*args, **kwargs):
    raise AssertionError("the tests never run an external tool through the phone runner")


def fake_resolver(host: str) -> list[str]:
    raise OSError(f"no DNS in the tests: {host}")


# --- raw ASGI calls (for paths an HTTP client would normalize) ------------------------------

def asgi_call(app, method: str, raw_path: bytes, *, client=("192.0.2.20", 50000),
              server=("192.0.2.10", 8000), headers: list[tuple[bytes, bytes]] | None = None,
              websocket: bool = False, host: bytes | None = None) -> dict:
    """Call the app with a hand-built scope (the path exactly as given, never
    normalized; `Host` is the server address unless `host` is given). Returns
    {"status", "headers", "body"} for HTTP, and for a WebSocket
    {"accepted": bool, "closed": bool}."""
    from urllib.parse import unquote

    path_bytes, _, query = raw_path.partition(b"?")
    host = host if host is not None else f"{server[0]}:{server[1]}".encode()
    scope = {
        "type": "websocket" if websocket else "http",
        "asgi": {"version": "3.0"}, "http_version": "1.1",
        "scheme": "ws" if websocket else "http", "method": method,
        "path": unquote(path_bytes.decode("latin-1")), "raw_path": path_bytes,
        "query_string": query, "root_path": "", "client": client, "server": server,
        "headers": [(b"host", host), *(headers or [])],
        "state": {},
    }
    if websocket:
        scope["subprotocols"] = []
    sent: list[dict] = []

    async def run() -> None:
        incoming = [{"type": "websocket.connect"} if websocket
                    else {"type": "http.request", "body": b"", "more_body": False}]

        async def receive():
            if incoming:
                return incoming.pop(0)
            await asyncio.sleep(3600)

        async def send(message):
            sent.append(message)

        await app(scope, receive, send)

    asyncio.run(run())
    if websocket:
        return {"accepted": any(m["type"] == "websocket.accept" for m in sent),
                "closed": any(m["type"] == "websocket.close" for m in sent)}
    start = next(m for m in sent if m["type"] == "http.response.start")
    body = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    return {"status": start["status"],
            "headers": {k.decode().lower(): v.decode() for k, v in start.get("headers", [])},
            "body": body}
