"""Connection limits of a server with phone access (server-contract 4.7).

uvicorn never closes a connection that has not completed a request: a peer
that opens one and sends nothing, half a request head or half of a declared
JSON body holds one of the process's file descriptors for as long as it
likes, and no application code (the LAN rules, the approved networks) runs
before the head is complete. With phone access the server listens on every
interface, so anyone who can reach its port could exhaust the descriptors,
and the owner's browser, the paired phones and the job pipeline would then
fail to open anything.

`serve()` therefore runs uvicorn with one of the protocols below: uvicorn's
own HTTP protocol, plus these rules for connections from LAN peers
(loopback connections, the owner's browser, are never limited):

- a request head must be complete `HEADER_TIMEOUT_S` after the connection
  opened or after the previous answer; a request body that is not a file
  upload (JSON, at most 16 KiB) must be complete `BODY_TIMEOUT_S` after its
  head; otherwise the connection is closed. File bodies have their own rule
  (the 30 s idle abort of 6.6), and nothing limits the time an answer takes;
- at most `PEER_LIMIT` open connections per peer address, `APPROVED_LIMIT`
  in total on approved addresses and `OTHER_LIMIT` in total on any other
  local address (a café's network while suspended, a second network card):
  one more is closed as soon as it opens, so a flood on one network never
  takes the room of the phones on the approved one;
- a connection that becomes a WebSocket leaves these counts (the channel
  has its own limits, 4.7, and an unsigned upgrade is refused at once).

`serve()` also raises the soft limit of open files to the hard limit.
"""

from __future__ import annotations

import logging
from typing import Any

from .access import is_file_body, is_loopback

HEADER_TIMEOUT_S = 10.0
BODY_TIMEOUT_S = 10.0
PEER_LIMIT = 16
APPROVED_LIMIT = 128
OTHER_LIMIT = 16
OPEN_FILES_TARGET = 65536

HEAD, BODY, BUSY = "head", "body", "busy"


class ClientAddressLogFilter(logging.Filter):
    """Drops uvicorn's WebSocket lines (`<address> - "WebSocket <path>" …`):
    with its access log off (2.1, 3.3) they would still print every phone's
    address and the channel's path at each connect."""

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.msg
        return not (isinstance(message, str) and message.startswith('%s - "WebSocket '))


ADDRESS_LOG_FILTER = ClientAddressLogFilter()
UVICORN_LOGGER = "uvicorn.error"


def raise_open_file_limit(target: int = OPEN_FILES_TARGET) -> int | None:
    """Raise the soft limit of open files towards the hard limit (at most
    `target`). Returns the new soft limit, or None when it cannot be read."""
    try:
        import resource
    except ImportError:                       # pragma: no cover - not on Linux
        return None
    try:
        soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        wanted = target if hard == resource.RLIM_INFINITY else min(hard, target)
        if soft != resource.RLIM_INFINITY and soft < wanted:
            resource.setrlimit(resource.RLIMIT_NOFILE, (wanted, hard))
            return wanted
        return soft
    except (ValueError, OSError):
        return None


class _Book:
    """The open LAN connections of one server: per peer and per pool."""

    def __init__(self) -> None:
        self.peers: dict[str, int] = {}
        self.pools: dict[str, int] = {"approved": 0, "other": 0}

    def admit(self, peer: str, pool: str, peer_limit: int, pool_limit: int) -> bool:
        if self.peers.get(peer, 0) >= peer_limit or self.pools[pool] >= pool_limit:
            return False
        self.peers[peer] = self.peers.get(peer, 0) + 1
        self.pools[pool] += 1
        return True

    def release(self, peer: str, pool: str) -> None:
        left = self.peers.get(peer, 0) - 1
        if left > 0:
            self.peers[peer] = left
        else:
            self.peers.pop(peer, None)
        self.pools[pool] = max(0, self.pools[pool] - 1)


def _book(server_state: Any) -> _Book:
    book = getattr(server_state, "_phonotrainer_connections", None)
    if book is None:
        book = _Book()
        server_state._phonotrainer_connections = book
    return book


def _runtime_of(app: Any):
    """The app's PhoneRuntime (`app.state.phone`), looking through wrappers."""
    for _ in range(6):
        if app is None:
            return None
        state = getattr(app, "state", None)
        phone = getattr(state, "phone", None) if state is not None else None
        if phone is not None:
            return phone
        app = getattr(app, "app", None)
    return None


def _file_body(scope: dict | None) -> bool:
    return bool(scope) and is_file_body(scope.get("method", ""), scope.get("path", ""))


class GuardMixin:
    """The rules above, on top of a uvicorn HTTP protocol class."""

    header_timeout_s = HEADER_TIMEOUT_S
    body_timeout_s = BODY_TIMEOUT_S
    peer_limit = PEER_LIMIT
    approved_limit = APPROVED_LIMIT
    other_limit = OTHER_LIMIT

    _guard_slot: tuple[str, str] | None = None
    _guard_timer = None
    _guard_key: tuple | None = None

    # Seams (tests run a real socket on loopback and treat it as the LAN).
    def unlimited_peer(self, host: str | None) -> bool:
        return is_loopback(host)

    def approved_local(self, address: str | None) -> bool:
        runtime = _runtime_of(getattr(self, "app", None))
        if runtime is None or address is None:
            return True
        try:
            return bool(runtime.is_approved_address(address))
        except Exception:                     # noqa: BLE001 — never break a connection on it
            logging.getLogger("phonotrainer.phone").exception("approved address check failed")
            return True

    # --- uvicorn's protocol interface -----------------------------------------------
    def connection_made(self, transport) -> None:
        super().connection_made(transport)
        self._guard_slot = None
        self._guard_timer = None
        self._guard_key = None
        peer = self.client[0] if self.client else None
        if self.unlimited_peer(peer):
            return
        local = self.server[0] if self.server else None
        approved = self.approved_local(local)
        pool = "approved" if approved else "other"
        limit = self.approved_limit if approved else self.other_limit
        if not _book(self.server_state).admit(str(peer), pool, self.peer_limit, limit):
            transport.abort()
            return
        self._guard_slot = (str(peer), pool)
        self._guard_update()

    def data_received(self, data: bytes) -> None:
        super().data_received(data)
        if self._guard_slot is not None:
            self._guard_update()

    def on_response_complete(self) -> None:
        super().on_response_complete()
        if self._guard_slot is not None:
            self._guard_update()

    def handle_websocket_upgrade(self, *args) -> None:
        self._guard_release()
        super().handle_websocket_upgrade(*args)

    def connection_lost(self, exc) -> None:
        self._guard_release()
        super().connection_lost(exc)

    # --- the deadlines --------------------------------------------------------------
    def _guard_update(self) -> None:
        transport = self.transport
        if transport is None or transport.is_closing():
            self._guard_cancel()
            return
        cycle = getattr(self, "cycle", None)
        if cycle is None or cycle.response_complete:
            phase = HEAD
        elif cycle.more_body and not _file_body(getattr(cycle, "scope", None)):
            phase = BODY
        else:
            phase = BUSY
        key = (phase, cycle)
        if self._guard_key is not None and self._guard_key[0] == key[0] \
                and self._guard_key[1] is key[1]:
            return                            # same phase of the same request: the deadline stands
        self._guard_key = key
        self._guard_cancel()
        if phase == HEAD:
            self._guard_timer = self.loop.call_later(self.header_timeout_s, self._guard_expire)
        elif phase == BODY:
            self._guard_timer = self.loop.call_later(self.body_timeout_s, self._guard_expire)

    def _guard_expire(self) -> None:
        self._guard_timer = None
        if self.transport is not None and not self.transport.is_closing():
            self.transport.close()

    def _guard_cancel(self) -> None:
        if self._guard_timer is not None:
            self._guard_timer.cancel()
            self._guard_timer = None

    def _guard_release(self) -> None:
        self._guard_cancel()
        slot, self._guard_slot = self._guard_slot, None
        if slot is not None:
            _book(self.server_state).release(*slot)


from uvicorn.protocols.http.h11_impl import H11Protocol  # noqa: E402


class GuardedH11Protocol(GuardMixin, H11Protocol):
    """uvicorn's h11 protocol with the connection limits."""


try:
    from uvicorn.protocols.http.httptools_impl import HttpToolsProtocol  # noqa: E402
except ImportError:                            # pragma: no cover - httptools is optional
    HttpToolsProtocol = None
    GuardedHttpToolsProtocol = None
else:
    class GuardedHttpToolsProtocol(GuardMixin, HttpToolsProtocol):
        """uvicorn's httptools protocol (its default when installed) with the
        connection limits."""


def protocol_path() -> str:
    """The `http=` value for uvicorn: an import path, so `--reload`'s worker
    process loads the same class."""
    if GuardedHttpToolsProtocol is not None:
        return f"{__name__}:GuardedHttpToolsProtocol"
    return f"{__name__}:GuardedH11Protocol"
