"""Connection limits of a server with phone access (server-contract 4.7;
`phone/connections.py`), over real sockets.

uvicorn closes nothing that has not completed a request, so without these
limits anyone who can reach the port could hold every file descriptor of the
server with idle or half-sent connections. The tests run uvicorn on 127.0.0.1
with a subclass of the protocol `serve()` uses: short deadlines, small
limits, and (except where loopback itself is tested) the loopback peer
treated as a device on the network.
"""

from __future__ import annotations

import hashlib
import logging
import secrets
import socket
import threading
import time

import pytest
from conftest import enable_phone_access, paired_client
from phone_fixtures import sign_header

from phonotrainer.phone import connections
from phonotrainer.server import create_app

BASE = connections.GuardedHttpToolsProtocol or connections.GuardedH11Protocol
KEY = "00000000-0000-4000-8000-0000000000c1"


def guarded(**attrs):
    """The protocol of serve(), with test limits; loopback counts as the LAN
    unless `unlimited_peer` is given."""
    attrs.setdefault("unlimited_peer", lambda self, host: False)
    attrs.setdefault("approved_local", lambda self, address: True)
    return type("TestGuarded", (BASE,), attrs)


class Served:
    """The phone app on a real uvicorn (its lifespan included), in a thread."""

    def __init__(self, app, protocol) -> None:
        import uvicorn

        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.port = self.sock.getsockname()[1]
        config = uvicorn.Config(app, host="127.0.0.1", port=self.port, proxy_headers=False,
                                access_log=False, log_config=None, lifespan="on",
                                http=protocol)
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(target=self.server.run, kwargs={"sockets": [self.sock]},
                                       daemon=True)
        self.opened: list[socket.socket] = []

    def __enter__(self) -> "Served":
        self.thread.start()
        deadline = time.monotonic() + 30
        while not self.server.started:
            assert self.thread.is_alive() and time.monotonic() < deadline, "uvicorn did not start"
            time.sleep(0.02)
        return self

    def __exit__(self, *exc) -> None:
        for sock in self.opened:
            sock.close()
        self.server.should_exit = True
        self.thread.join(timeout=30)
        self.sock.close()

    def open(self, payload: bytes = b"") -> socket.socket:
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        self.opened.append(sock)
        if payload:
            sock.sendall(payload)
        return sock

    def head(self, method: str, path: str, headers: dict | None = None) -> bytes:
        lines = [f"{method} {path} HTTP/1.1", f"Host: 127.0.0.1:{self.port}"]
        lines += [f"{name}: {value}" for name, value in (headers or {}).items()]
        return ("\r\n".join(lines) + "\r\n\r\n").encode()


def closed_within(sock: socket.socket, seconds: float) -> bool:
    """Did the server close the connection within `seconds` (after any answer)?"""
    deadline = time.monotonic() + seconds
    try:
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                return False
            sock.settimeout(left)
            if sock.recv(65536) == b"":
                return True
    except socket.timeout:
        return False
    except (ConnectionResetError, BrokenPipeError):
        return True


def read_answer(sock: socket.socket) -> bytes:
    sock.settimeout(5)
    data = b""
    while b"\r\n\r\n" not in data:
        data += sock.recv(65536)
    head, _, body = data.partition(b"\r\n\r\n")
    length = int(next((line.split(b":", 1)[1] for line in head.split(b"\r\n")
                       if line.lower().startswith(b"content-length:")), b"0"))
    while len(body) < length:
        body += sock.recv(65536)
    return head + b"\r\n\r\n" + body


@pytest.fixture
def app(phone_store, phone_settings, tmp_path):
    enable_phone_access(phone_settings)
    return create_app(store=phone_store, phone=phone_settings, web_dist=tmp_path / "nb",
                      allowed_roots=[tmp_path])


# --- deadlines ----------------------------------------------------------------------------------

def test_a_request_must_arrive_in_time(app):
    with Served(app, guarded(header_timeout_s=0.3, body_timeout_s=0.3)) as served:
        silent = served.open()
        half_head = served.open(b"GET /api/phone/hello HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        half_body = served.open(served.head("POST", "/api/phone/pair", {
            "Content-Type": "application/json", "Content-Length": "100"}) + b"{")
        for sock in (silent, half_head, half_body):
            assert closed_within(sock, 5)
        # a whole request is answered; a half one after it is closed again
        keep = served.open(served.head("GET", "/api/phone/hello"))
        assert read_answer(keep).startswith(b"HTTP/1.1 200")
        keep.sendall(b"GET /api/phone/hello HTTP/1.1\r\n")
        assert closed_within(keep, 3)                     # before uvicorn's 5 s keep-alive


def test_an_upload_body_has_its_own_rule_not_the_body_deadline(app, phone_settings):
    """A file body may pause (Wi-Fi, a busy phone): the 30 s idle rule of 6.6
    applies to it, not the 10 s deadline of small JSON bodies."""
    data = b"\x00\x01video bytes" * 4096
    with Served(app, guarded(header_timeout_s=0.3, body_timeout_s=0.3)) as served:
        signing, device_id = paired_client(app, phone_settings.clock)
        declared = signing.post("/api/phone/uploads", json={
            "client_request_id": KEY, "file_name": "clip.mp4", "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest()})
        assert declared.status_code == 201, declared.text
        key = signing.auth.key
        path = f"/api/phone/uploads/{KEY}"
        auth = sign_header(device_id, key, "PUT", path, int(phone_settings.clock()),
                           secrets.token_hex(16))
        sock = served.open(served.head("PUT", path, {
            "Authorization": auth, "Content-Type": "application/octet-stream",
            "Content-Length": str(len(data))}) + data[: len(data) // 2])
        assert not closed_within(sock, 1.0)              # paused longer than the body deadline
        sock.sendall(data[len(data) // 2:])
        assert read_answer(sock).startswith(b"HTTP/1.1 201")


# --- limits -------------------------------------------------------------------------------------

def test_connections_per_peer_are_limited(app):
    with Served(app, guarded(header_timeout_s=10, peer_limit=3)) as served:
        first = [served.open() for _ in range(3)]
        extra = served.open()
        assert closed_within(extra, 3)                    # closed as soon as it opened
        assert not any(closed_within(sock, 0.2) for sock in first)
        first[0].close()
        time.sleep(0.3)                                   # the server notices the close
        again = served.open(served.head("GET", "/api/phone/hello"))
        assert read_answer(again).startswith(b"HTTP/1.1 200")


def test_each_network_has_its_own_total(app):
    """A flood on a network that is not approved (a café while suspended, a
    second network card) never takes the room of the phones on the approved
    one."""
    approved = {"now": False}
    protocol = guarded(header_timeout_s=10, peer_limit=50, other_limit=2, approved_limit=3,
                       approved_local=lambda self, address: approved["now"])
    with Served(app, protocol) as served:
        others = [served.open() for _ in range(2)]
        assert closed_within(served.open(), 3)            # the third on the other network
        approved["now"] = True
        phones = [served.open() for _ in range(3)]        # the approved network still has room
        assert not any(closed_within(sock, 0.2) for sock in others + phones)
        assert closed_within(served.open(), 3)            # until its own total


def test_a_channel_leaves_the_counts(app, phone_settings):
    from websockets.sync.client import connect

    with Served(app, guarded(header_timeout_s=10, peer_limit=2)) as served:
        signing, device_id = paired_client(app, phone_settings.clock)
        key = signing.auth.key
        auth = sign_header(device_id, key, "GET", "/api/phone/events",
                           int(phone_settings.clock()), secrets.token_hex(16))
        channel = connect(f"ws://127.0.0.1:{served.port}/api/phone/events",
                          additional_headers={"Authorization": auth}, open_timeout=10)
        try:
            assert channel.recv(timeout=10)                  # hello
            pending = [served.open() for _ in range(2)]      # the channel holds no slot
            assert not any(closed_within(sock, 0.3) for sock in pending)
        finally:
            channel.close()


def test_loopback_is_never_limited(app):
    """The owner's browser on this computer: no deadline, no limit."""
    with Served(app, guarded(header_timeout_s=0.2, peer_limit=1,
                             unlimited_peer=BASE.unlimited_peer)) as served:
        socks = [served.open(b"GET / HTTP/1.1\r\n") for _ in range(3)]
        assert not any(closed_within(sock, 0.5) for sock in socks)


def test_the_unapproved_pool_follows_the_approved_addresses(app):
    """The default `approved_local` asks the phone runtime: 127.0.0.1 is not
    an approved address of the fake Wi-Fi."""
    protocol = guarded(header_timeout_s=10, peer_limit=50, other_limit=1,
                       approved_local=BASE.approved_local)
    with Served(app, protocol) as served:
        served.open()
        assert closed_within(served.open(), 3)


# --- the rest of serve()'s setup ---------------------------------------------------------------

def test_the_open_file_limit_is_raised(monkeypatch):
    resource = pytest.importorskip("resource")
    calls = []
    monkeypatch.setattr(resource, "getrlimit", lambda which: (1024, 524288))
    monkeypatch.setattr(resource, "setrlimit", lambda which, limits: calls.append(limits))
    assert connections.raise_open_file_limit() == 65536
    assert calls == [(65536, 524288)]
    monkeypatch.setattr(resource, "getrlimit", lambda which: (1024, 4096))
    assert connections.raise_open_file_limit() == 4096
    monkeypatch.setattr(resource, "getrlimit", lambda which: (100000, 524288))
    calls.clear()
    assert connections.raise_open_file_limit() == 100000 and calls == []


def test_websocket_lines_never_print_a_phones_address(app, phone_settings, caplog):
    from websockets.sync.client import connect

    record = logging.LogRecord("uvicorn.error", logging.INFO, __file__, 1,
                               '%s - "WebSocket %s" [accepted]',
                               ("192.0.2.20:50000", "/api/phone/events"), None)
    assert connections.ADDRESS_LOG_FILTER.filter(record) is False
    other = logging.LogRecord("uvicorn.error", logging.INFO, __file__, 1, "Started server", (),
                              None)
    assert connections.ADDRESS_LOG_FILTER.filter(other) is True
    logger = logging.getLogger("uvicorn.error")
    with caplog.at_level(logging.INFO, logger="uvicorn.error"):
        with Served(app, guarded()) as served:
            assert connections.ADDRESS_LOG_FILTER in logger.filters
            signing, device_id = paired_client(app, phone_settings.clock)
            key = signing.auth.key
            auth = sign_header(device_id, key, "GET", "/api/phone/events",
                               int(phone_settings.clock()), secrets.token_hex(16))
            with connect(f"ws://127.0.0.1:{served.port}/api/phone/events",
                         additional_headers={"Authorization": auth}, open_timeout=10) as channel:
                channel.recv(timeout=10)
    assert connections.ADDRESS_LOG_FILTER not in logger.filters    # removed at the stop
    messages = [r.getMessage() for r in caplog.records if r.name == "uvicorn.error"]
    assert messages and not any("WebSocket" in m or "127.0.0.1" in m for m in messages)
