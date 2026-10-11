"""UDP discovery (server-contract 5; S-03).

The phone broadcasts a small probe on UDP port 45317; this computer answers by
unicast to the probe's source with its server id, display name, HTTP port and
API version, and nothing else (never a credential, a path, a user name or an
address: the phone takes the address from the reply's source). No mDNS, no
multicast, no broadcast from the computer.

Replies go only to private or link-local IPv4 sources that lie inside an
active approved network (a source on a container bridge or a VPN tunnel gets
no answer unless the operator approved that interface), at most 5 per second
per source and 50 per second in total; everything else is dropped silently.
The responder runs only while phone access is on (not while suspended).
"""

from __future__ import annotations

import ipaddress
import json
import logging
import re
import socket
import threading
from typing import Callable

from .netinfo import Interface
from .registry import RateLimiter

log = logging.getLogger("phonotrainer.phone")

PROBE_TYPE = "phonotrainer-probe"
REPLY_TYPE = "phonotrainer-here"
PROTOCOL_VERSION = 1
MAX_PROBE_BYTES = 512
NONCE_RE = re.compile(r"^[0-9a-f]{16,64}$")
REPLIES_PER_SOURCE = 5            # per second
REPLIES_TOTAL = 50                # per second
RECEIVE_TIMEOUT_S = 0.5           # how often the thread checks whether to stop

DISCOVERY_RANGES = tuple(ipaddress.ip_network(n) for n in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"))


def parse_probe(data: bytes) -> str | None:
    """The probe's nonce, or None for anything that is not exactly a probe
    (size, encoding, type, fields); unknown extra fields are ignored."""
    if not isinstance(data, (bytes, bytearray)) or not data or len(data) > MAX_PROBE_BYTES:
        return None
    try:
        probe = json.loads(bytes(data).decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None
    if not isinstance(probe, dict) or probe.get("t") != PROBE_TYPE:
        return None
    version = probe.get("v")
    if type(version) is not int or version != PROTOCOL_VERSION:
        return None
    nonce = probe.get("nonce")
    if not isinstance(nonce, str) or not NONCE_RE.match(nonce):
        return None
    return nonce


def make_reply(nonce: str, server_id: str, name: str, http_port: int, api_version: int) -> bytes:
    """The reply datagram: the nonce and the four fields of S-03, nothing else."""
    return json.dumps({"t": REPLY_TYPE, "v": PROTOCOL_VERSION, "nonce": nonce,
                       "server_id": server_id, "name": name, "http_port": int(http_port),
                       "api_version": int(api_version)},
                      ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def source_allowed(source: str, active: list[Interface]) -> bool:
    """A private or link-local IPv4 source inside an active approved network."""
    try:
        address = ipaddress.IPv4Address(source)
    except ValueError:
        return False
    if not any(address in network for network in DISCOVERY_RANGES):
        return False
    for iface in active:
        try:
            if address in ipaddress.ip_interface(f"{iface.address}/{iface.prefix}").network:
                return True
        except ValueError:
            continue
    return False


class DiscoveryResponder:
    """One daemon thread owning one UDP socket."""

    def __init__(self, port: int, identity: Callable[[], tuple[str, str]], http_port: int,
                 api_version: int, active: Callable[[], list[Interface]],
                 clock: Callable[[], float], host: str = "0.0.0.0",
                 allowed: Callable[[str, list[Interface]], bool] = source_allowed) -> None:
        self.port = port
        self.host = host
        self.identity = identity              # () -> (server_id, name), read at each reply
        self.http_port = http_port
        self.api_version = api_version
        self.active = active
        self.allowed = allowed
        self.limiter = RateLimiter(REPLIES_PER_SOURCE, REPLIES_TOTAL, 1.0, clock)
        self._sock: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    @property
    def bound_port(self) -> int | None:
        return self._sock.getsockname()[1] if self._sock is not None else None

    def answer(self, data: bytes, source: str) -> bytes | None:
        """The reply to one datagram from `source`, or None (dropped)."""
        nonce = parse_probe(data)
        if nonce is None or not self.allowed(source, self.active()):
            return None
        if self.limiter.hit(source) is not None:
            return None
        server_id, name = self.identity()
        return make_reply(nonce, server_id, name, self.http_port, self.api_version)

    def start(self) -> bool:
        """Bind and serve; False (with one warning line) when the port is busy."""
        if self._sock is not None:
            return True
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.bind((self.host, self.port))
        except OSError:
            sock.close()
            log.warning("The phone discovery port is busy: phones can still connect by "
                        "entering this computer's address.")
            return False
        sock.settimeout(RECEIVE_TIMEOUT_S)
        self._sock = sock
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="phonotrainer-discovery",
                                        daemon=True)
        self._thread.start()
        return True

    def _loop(self) -> None:
        sock = self._sock
        while not self._stop.is_set() and sock is not None:
            try:
                data, (source, port) = sock.recvfrom(MAX_PROBE_BYTES + 1)
            except socket.timeout:
                continue
            except OSError:
                return                         # closed by stop()
            try:
                reply = self.answer(data, source)
                if reply is not None:
                    sock.sendto(reply, (source, port))
            except Exception:                  # noqa: BLE001 — the thread must not die
                log.debug("Discovery reply failed.", exc_info=True)

    def stop(self) -> None:
        self._stop.set()
        sock, self._sock = self._sock, None
        if sock is not None:
            sock.close()
        if self._thread is not None:
            self._thread.join(timeout=2)
            self._thread = None
