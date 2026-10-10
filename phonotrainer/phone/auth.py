"""Signed requests (server-contract 4.3).

The phone never sends its token after pairing. Each request carries

    Authorization: PT1 device=<device_id>, ts=<unix seconds>, nonce=<32 hex>, mac=<64 hex>

with `mac = hex(HMAC-SHA256(K, "pt-req-v1|" + METHOD + "|" + path_and_query +
"|" + ts + "|" + nonce))`, `K = sha256(token)`, and `path_and_query` the
request target exactly as sent (still percent-encoded). A request is accepted
when the device exists and is not revoked, the MAC matches, the time is within
300 s of the server's and the nonce was not seen for that device in the last
600 s.
"""

from __future__ import annotations

import hmac
import re
import threading
from collections import OrderedDict
from typing import Callable

from fastapi import Request

from . import NONCE_TTL_S, SIGNATURE_WINDOW_S
from .errors import PhoneError
from .registry import Device, DeviceRegistry, hmac_hex

SCHEME = "PT1"
MAX_NONCES_PER_DEVICE = 4096

_HEADER_RE = re.compile(
    r"^PT1 device=(?P<device>[A-Za-z0-9_]{1,64}),\s*ts=(?P<ts>[0-9]{1,12}),\s*"
    r"nonce=(?P<nonce>[0-9a-f]{32}),\s*mac=(?P<mac>[0-9a-f]{64})$")


def signature(key: bytes, method: str, target: str, ts: int | str, nonce: str) -> str:
    """The MAC of one request (the phone computes the same)."""
    return hmac_hex(key, f"pt-req-v1|{method.upper()}|{target}|{ts}|{nonce}")


def request_target(scope: dict) -> str:
    """The request target as the client sent it: raw (percent-encoded) path
    plus `?query` when there is one."""
    raw = scope.get("raw_path")
    if raw is None:
        from urllib.parse import quote

        raw = quote(scope.get("path", "/")).encode("ascii")
    target = raw.decode("latin-1")
    query = scope.get("query_string") or b""
    if query:
        target += "?" + query.decode("latin-1")
    return target


def rejected() -> PhoneError:
    return PhoneError(401, "credential_rejected", headers={"WWW-Authenticate": SCHEME})


class Authenticator:
    """Checks signatures; remembers nonces per device."""

    def __init__(self, registry: DeviceRegistry, clock: Callable[[], float],
                 window_s: int = SIGNATURE_WINDOW_S, nonce_ttl_s: int = NONCE_TTL_S) -> None:
        self.registry = registry
        self.clock = clock
        self.window_s = window_s
        self.nonce_ttl_s = nonce_ttl_s
        self._nonces: dict[str, OrderedDict[str, float]] = {}
        self._lock = threading.Lock()

    def verify(self, header: str | None, method: str, target: str) -> Device:
        """The signing device, or PhoneError 401 (`credential_rejected` or
        `clock_skew`)."""
        match = _HEADER_RE.match(header.strip()) if header else None
        if match is None:
            raise rejected()
        device = self.registry.active(match["device"])
        if device is None:
            raise rejected()
        expected = signature(device.key, method, target, match["ts"], match["nonce"])
        if not hmac.compare_digest(expected, match["mac"]):
            raise rejected()
        now = self.clock()
        if abs(int(match["ts"]) - now) > self.window_s:
            raise PhoneError(401, "clock_skew", headers={"WWW-Authenticate": SCHEME},
                             server_time=int(now))
        with self._lock:
            seen = self._nonces.setdefault(device.device_id, OrderedDict())
            while seen:
                oldest, expiry = next(iter(seen.items()))
                if expiry > now:
                    break
                seen.pop(oldest)
            if match["nonce"] in seen:
                raise rejected()                        # a replayed request
            seen[match["nonce"]] = now + self.nonce_ttl_s
            while len(seen) > MAX_NONCES_PER_DEVICE:
                seen.popitem(last=False)
        self.registry.touch(device.device_id)
        return device


def current_device(request: Request) -> Device:
    """FastAPI dependency of every signed phone route."""
    runtime = request.app.state.phone
    return runtime.auth.verify(request.headers.get("authorization"), request.method,
                               request_target(request.scope))
