"""Who may reach what: two pure ASGI middlewares (server-contract 4.1 to 4.4).

Pure ASGI, because FastAPI's `@app.middleware("http")` never sees WebSocket
upgrades: both see `http` and `websocket` scopes alike.

- `LoopbackHostGuard` (installed in every mode, phone access off included): a
  request from a loopback peer must carry a `Host` that is an IP literal,
  `localhost`, `*.localhost`, this machine's host name or `<hostname>.local`.
  Otherwise a page in the owner's browser could use DNS rebinding to read the
  disk browser (`/api/browse`) through a name that resolves to 127.0.0.1.
  "Loopback" is the peer address or the local address the connection arrived
  on: only this machine can reach a loopback address, and no header can change
  that one, so a proxy header that rewrote the peer (uvicorn runs with
  `proxy_headers=False`, this is the second line) still cannot skip the rule.
- `PhoneAccessMiddleware` (phone access on or suspended): LAN clients reach only
  `/api/phone/*`, only on the local addresses of approved networks, only from
  inside the network they reached (`peer_allowed`: not another subnet, a
  container, a VPN peer or the internet routed to that address), and only
  with `Host` equal to the address they connected to; phone paths with dot
  segments or an encoded slash are refused; with phone access switched off
  live, every phone route except `hello` answers `phone_access_off`; JSON
  bodies on phone routes must declare a length of at most 16 KiB.
"""

from __future__ import annotations

import ipaddress
import re
import socket
from typing import Iterable

from starlette.types import ASGIApp, Receive, Scope, Send

from . import MAX_JSON_BYTES
from .errors import error_response

PHONE_PREFIX = "/api/phone/"
HELLO_PATH = "/api/phone/hello"
EVENTS_PATH = "/api/phone/events"

# Phone routes whose body is JSON: `Content-Length` required, at most 16 KiB.
JSON_ROUTES = (
    ("POST", re.compile(r"^/api/phone/pair$")),
    ("POST", re.compile(r"^/api/phone/jobs/link$")),
    ("POST", re.compile(r"^/api/phone/uploads$")),
    ("POST", re.compile(r"^/api/phone/jobs/[^/]+/rerun$")),
)
# Phone routes that stream a file body (bounded by their own rules).
FILE_BODY_ROUTES = (
    ("PUT", re.compile(r"^/api/phone/uploads/[^/]+$")),
    ("PATCH", re.compile(r"^/api/phone/uploads/[^/]+$")),
)


def is_file_body(method: str, path: str) -> bool:
    """A phone route whose body is a file (an upload's PUT or PATCH)."""
    method = str(method).upper()
    return any(m == method and rx.match(path) for m, rx in FILE_BODY_ROUTES)


def is_loopback(host: str | None) -> bool:
    """127.0.0.0/8, ::1 or an IPv4-mapped loopback; anything else (including a
    peer that is not an IP literal) is a LAN client."""
    if not host:
        return False
    try:
        address = ipaddress.ip_address(host.split("%", 1)[0])
    except ValueError:
        return False
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        address = address.ipv4_mapped
    return address.is_loopback


def split_host(value: str) -> tuple[str, int | None, bool] | None:
    """`Host` → (host, port or None, is an IP literal); None when malformed."""
    value = value.strip()
    if not value:
        return None
    if value.startswith("["):
        end = value.find("]")
        if end < 0:
            return None
        host, rest = value[1:end], value[end + 1:]
        try:
            ipaddress.IPv6Address(host)
        except ValueError:
            return None
        literal = True
    else:
        if value.count(":") > 1:
            return None                       # an unbracketed IPv6 address
        host, sep, port_text = value.partition(":")
        rest = f":{port_text}" if sep else ""
        try:
            ipaddress.IPv4Address(host)
            literal = True
        except ValueError:
            literal = False
    port = None
    if rest:
        if not re.fullmatch(r":[0-9]{1,5}", rest):
            return None
        port = int(rest[1:])
        if port > 65535:
            return None
    host = host.lower()
    if not literal:
        host = host.rstrip(".")
        if not host or not re.fullmatch(r"[a-z0-9._-]+", host):
            return None
    return host, port, literal


def _header(scope: Scope, name: bytes) -> list[str]:
    return [value.decode("latin-1") for key, value in scope.get("headers") or []
            if key.lower() == name]


def machine_names() -> set[str]:
    full = socket.gethostname().lower().rstrip(".")
    names = {full, full.split(".", 1)[0]}
    names |= {f"{name}.local" for name in list(names)}
    return {name for name in names if name}


def _loopback_host_ok(values: list[str], names: set[str]) -> bool:
    if not values:
        return True                           # no name, no DNS rebinding
    if len(values) > 1:
        return False
    parsed = split_host(values[0])
    if parsed is None:
        return False
    host, _, literal = parsed
    return (literal or host == "localhost" or host.endswith(".localhost")
            or host in names)


def _announces_body(scope: Scope) -> bool:
    if _header(scope, b"transfer-encoding"):
        return True
    return any(value.strip() not in ("", "0") for value in _header(scope, b"content-length"))


async def _deny(scope: Scope, receive: Receive, send: Send, status: int, code: str) -> None:
    if scope["type"] == "websocket":
        # Closed before accept: the client sees the handshake refused (HTTP 403).
        await receive()
        await send({"type": "websocket.close", "code": 1008})
        return
    # A refused request's body is never read: the connection closes after the
    # answer instead of waiting for (or discarding) what the client still sends.
    headers = {"Connection": "close"} if _announces_body(scope) else None
    await error_response(status, code, headers=headers)(scope, receive, send)


class LoopbackHostGuard:
    """The loopback Host rule (every mode)."""

    def __init__(self, app: ASGIApp, hostnames: Iterable[str] | None = None) -> None:
        self.app = app
        self.names = set(hostnames) if hostnames is not None else machine_names()

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] in ("http", "websocket"):
            client = (scope.get("client") or (None,))[0]
            local = (scope.get("server") or (None,))[0]
            if ((is_loopback(client) or is_loopback(local))
                    and not _loopback_host_ok(_header(scope, b"host"), self.names)):
                await _deny(scope, receive, send, 400, "bad_host")
                return
        await self.app(scope, receive, send)


def is_phone_path(path: str) -> bool:
    return path.startswith(PHONE_PREFIX) or path == PHONE_PREFIX.rstrip("/")


def unsafe_path(scope: Scope) -> bool:
    """A `.` or `..` segment (after percent-decoding) or an encoded slash."""
    raw = scope.get("raw_path") or b""
    if b"%2f" in raw.lower() or b"%5c" in raw.lower():
        return True
    return any(segment in (".", "..") for segment in scope.get("path", "").split("/"))


def _lan_host_ok(values: list[str], server: tuple[str, int] | None) -> bool:
    """Host must be the IPv4 literal the connection arrived on, with the
    listening port or no port. Host names are refused (DNS rebinding)."""
    if len(values) != 1 or not server:
        return False
    parsed = split_host(values[0])
    if parsed is None:
        return False
    host, port, literal = parsed
    if not literal or ":" in host:
        return False
    return host == server[0] and (port is None or port == server[1])


def peer_allowed(client: str | None, iface) -> bool:
    """May this LAN peer use the approved interface it reached? Only from
    inside that interface's network, and only from an address that is not
    global unicast (private, link-local, shared…), the rule discovery
    applies to its probes (5.4). A peer with a global address is accepted
    only when the approved network is itself global, which `phone on` never
    approves unless the operator names the interface (`--phone-interface`)."""
    if not client:
        return False
    try:
        address = ipaddress.ip_address(client.split("%", 1)[0])
        network = ipaddress.ip_interface(f"{iface.address}/{iface.prefix}").network
    except (ValueError, TypeError):
        return False
    if isinstance(address, ipaddress.IPv6Address):
        if address.ipv4_mapped is None:
            return False                          # approved networks are IPv4
        address = address.ipv4_mapped
    if address not in network:
        return False
    return not address.is_global or network.is_global


class PhoneAccessMiddleware:
    """LAN restrictions and phone-route rules (phone access on or suspended)."""

    def __init__(self, app: ASGIApp, runtime) -> None:
        self.app = app
        self.runtime = runtime

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        phone = is_phone_path(path)
        client = (scope.get("client") or (None,))[0]
        if not is_loopback(client):
            server = scope.get("server")
            server = (str(server[0]), int(server[1])) if server else None
            iface = self.runtime.approved_interface(server[0]) if server else None
            if iface is None or not peer_allowed(client, iface):
                await _deny(scope, receive, send, 403, "lan_forbidden")
                return
            if not _lan_host_ok(_header(scope, b"host"), server):
                await _deny(scope, receive, send, 400, "bad_host")
                return
            if not phone:
                await _deny(scope, receive, send, 403, "lan_forbidden")
                return
        if phone:
            refusal = self._phone_rule(scope, path)
            if refusal is not None:
                await _deny(scope, receive, send, *refusal)
                return
        await self.app(scope, receive, send)

    def _phone_rule(self, scope: Scope, path: str) -> tuple[int, str] | None:
        if unsafe_path(scope):
            return 400, "invalid_request"
        if scope["type"] == "websocket" and path != EVENTS_PATH:
            return 404, "unknown_route"
        if path != HELLO_PATH and self.runtime.state == "off":
            return 403, "phone_access_off"
        if scope["type"] == "http":
            return self._body_rule(scope, path)
        return None

    @staticmethod
    def _body_rule(scope: Scope, path: str) -> tuple[int, str] | None:
        method = scope.get("method", "GET").upper()
        if is_file_body(method, path):
            return None
        lengths = _header(scope, b"content-length")
        json_route = any(m == method and rx.match(path) for m, rx in JSON_ROUTES)
        if _header(scope, b"transfer-encoding"):
            # A chunked body (even beside a Content-Length, which it would
            # override) is never measured in advance: refused on every route
            # that does not stream a file.
            return 411, "length_required"
        if not lengths:
            if json_route:
                return 411, "length_required"
            return None
        if len(lengths) > 1 or not lengths[0].strip().isdigit():
            return 400, "invalid_request"
        if int(lengths[0]) > MAX_JSON_BYTES:
            return 413, "too_large"
        return None
