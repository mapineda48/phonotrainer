"""Uploads from phones (server-contract 6.6; the limits of 4.7).

A phone first declares a file (`POST /api/phone/uploads`: name, size,
SHA-256, its idempotency key), which opens a session and **reserves** the
declared bytes against free space; then it sends the whole file in one `PUT`.
The body is streamed to `<workspace>/.phone-uploads/<key>.part`, hashed while
written, and on success the part is renamed into the new job's `media/`
directory (no second copy).

A body can never wedge its key (the phone's Wi-Fi may drop mid-upload without
the server ever seeing the connection close):
- a body that delivers no byte for 30 s is aborted, its part deleted and its
  slot released (the session stays, so the phone starts again with its key);
- a new `PUT` for the same key, while the older one has received nothing for
  10 s, replaces it ("newest wins");
- at most one body per device receives bytes at a time, and at most four
  sessions per device are open.

Upload bodies are awaited on the event loop and only each chunk's write is
handed to a worker thread (a dedicated limiter of four), so a slow or idle
upload holds no thread of the pool the browser client's routes use.

In v1-core an interrupted body deletes its part: the next `PUT` starts at 0.
A janitor deletes sessions untouched for 48 hours.
"""

from __future__ import annotations

import asyncio
import errno
import hashlib
import json
import logging
import os
import re
import tempfile
import threading
import weakref
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable

import anyio

from . import (MAX_CHUNK_BYTES, MAX_SESSIONS_PER_DEVICE, MIN_FREE_BYTES, UPLOAD_EXPIRY_S,
               UPLOAD_IDLE_ABORT_S, UPLOAD_TAKEOVER_IDLE_S)
from .errors import PhoneError
from .identity import iso

log = logging.getLogger("phonotrainer.phone")

UPLOADS_DIR = ".phone-uploads"
KEY_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
MAX_FILE_NAME_CHARS = 200
# The stored name's budget in UTF-8 bytes: file systems limit a name to 255
# bytes, not characters (85 CJK characters or 64 emoji are already more), and
# the pipeline may add a few bytes around a name.
MAX_FILE_NAME_BYTES = 200
WRITE_THREADS = 4
JANITOR_INTERVAL_S = 3600
# How often (real seconds) a waiting body checks whether it went idle or was
# replaced. The idle time itself is measured with the phone settings' clock.
POLL_S = 1.0
# How long a newer body waits for the one it replaces to let go of the part.
TAKEOVER_WAIT_S = 30.0


def fit_file_name(name: str, max_bytes: int = MAX_FILE_NAME_BYTES) -> str:
    """`name` shortened, if needed, so that its UTF-8 form fits `max_bytes`:
    the stem loses its last characters (never half of one) and the suffix
    (`.mp4`) is kept. The same name always gives the same result, so a
    declaration and its repetition compare equal."""
    if len(name.encode("utf-8")) <= max_bytes:
        return name
    stem, suffix = os.path.splitext(name)
    room = max_bytes - len(suffix.encode("utf-8"))
    if not stem or room < 1:
        return name.encode("utf-8")[:max_bytes].decode("utf-8", "ignore")
    short = stem.encode("utf-8")[:room].decode("utf-8", "ignore").rstrip(" .")
    return (short or "upload") + suffix


def normalize_key(key: str) -> str | None:
    """A `client_request_id` in canonical lower-case form, or None."""
    key = str(key or "").strip().lower()
    return key if KEY_RE.match(key) else None


@dataclass
class Session:
    """A declared upload (persisted as `<key>.json`)."""

    client_request_id: str
    device_id: str
    file_name: str
    bytes: int
    sha256: str
    separate_dialogue: bool | None
    created_at: float
    touched_at: float

    def same_file(self, file_name: str, size: int, sha256: str) -> bool:
        return (self.file_name, self.bytes, self.sha256) == (file_name, size, sha256.lower())

    @classmethod
    def from_dict(cls, raw: dict) -> "Session | None":
        try:
            session = cls(**{k: raw[k] for k in cls.__dataclass_fields__})
        except (KeyError, TypeError):
            return None
        if normalize_key(session.client_request_id) != session.client_request_id:
            return None
        return session


@dataclass(eq=False)
class Attempt:
    """One `PUT` body receiving bytes."""

    device_id: str
    key: str
    last_byte_at: float
    superseded: bool = False
    ended: bool = False
    finished: threading.Event = field(default_factory=threading.Event)


class UploadSessions:
    """Sessions, reservations, the one-body-per-device rule and the janitor."""

    def __init__(self, workspace: str | Path, clock: Callable[[], float]) -> None:
        self.dir = Path(workspace) / UPLOADS_DIR
        self.clock = clock
        self._lock = threading.Lock()
        self._sessions: dict[str, Session] = {}
        self._attempts: dict[str, Attempt] = {}
        self._limiters: weakref.WeakKeyDictionary = weakref.WeakKeyDictionary()
        self._stop = threading.Event()
        self._janitor: threading.Thread | None = None
        self._load()

    # --- files -------------------------------------------------------------------------
    def part_path(self, key: str) -> Path:
        return self.dir / f"{key}.part"

    def _session_path(self, key: str) -> Path:
        return self.dir / f"{key}.json"

    def _load(self) -> None:
        """Sessions survive a restart; parts do not (in v1-core an interrupted
        part is discarded, and no body can be receiving at start)."""
        if not self.dir.is_dir():
            return
        for path in self.dir.iterdir():
            if path.suffix == ".part" or path.name.endswith(".tmp"):
                path.unlink(missing_ok=True)
            elif path.suffix == ".json":
                try:
                    session = Session.from_dict(json.loads(path.read_text(encoding="utf-8")))
                except (OSError, json.JSONDecodeError, AttributeError):
                    session = None
                if session is None or path.stem != session.client_request_id:
                    path.unlink(missing_ok=True)
                    continue
                self._sessions[session.client_request_id] = session

    def _persist(self, session: Session) -> None:
        self.dir.mkdir(exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self.dir, prefix=".session.", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(asdict(session), fh)
            os.replace(tmp, self._session_path(session.client_request_id))
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise

    # --- queries ----------------------------------------------------------------------
    def reserved(self) -> int:
        """Bytes reserved by open sessions (free space minus these is what
        `health` reports)."""
        with self._lock:
            return sum(s.bytes for s in self._sessions.values())

    def get(self, device_id: str, key: str) -> Session | None:
        with self._lock:
            session = self._sessions.get(key)
        return session if session is not None and session.device_id == device_id else None

    def key_in_use(self, device_id: str, key: str) -> bool:
        """Does an open session hold this key (of this or, by a UUID clash,
        another device)? A link or a re-run with it is a key conflict."""
        with self._lock:
            return key in self._sessions

    def open_count(self, device_id: str) -> int:
        with self._lock:
            return sum(1 for s in self._sessions.values() if s.device_id == device_id)

    def expires_at(self, session: Session) -> str:
        return iso(session.touched_at + UPLOAD_EXPIRY_S)

    def open_state(self, session: Session) -> dict:
        return {"state": "open", "upload": {
            "client_request_id": session.client_request_id, "offset": 0,
            "bytes": session.bytes, "expires_at": self.expires_at(session),
            "max_chunk_bytes": MAX_CHUNK_BYTES, "resumable": False}}

    # --- declaration ------------------------------------------------------------------
    def declare(self, device_id: str, key: str, file_name: str, size: int, sha256: str,
                separate_dialogue: bool | None,
                disk_free_bytes: Callable[[], int]) -> tuple[bool, Session]:
        """(created, session). Raises PhoneError for a key held by another
        file, too many open sessions or too little free space.

        `disk_free_bytes` is the filesystem's free space WITHOUT the
        reservations: they are subtracted here, under the same lock that adds
        the new one, so two declarations that fit alone but not together
        cannot both pass."""
        sha256 = sha256.lower()
        disk_free = int(disk_free_bytes())
        with self._lock:
            existing = self._sessions.get(key)
            if existing is not None:
                if existing.device_id != device_id or not existing.same_file(file_name, size, sha256):
                    raise PhoneError(409, "key_conflict")
                return False, existing
            if sum(1 for s in self._sessions.values()
                   if s.device_id == device_id) >= MAX_SESSIONS_PER_DEVICE:
                raise PhoneError(409, "upload_busy", "Too many unfinished uploads from this phone.")
            reserved = sum(s.bytes for s in self._sessions.values())
            if disk_free - reserved - size < MIN_FREE_BYTES:
                raise PhoneError(507, "no_space")
            now = self.clock()
            session = Session(client_request_id=key, device_id=device_id, file_name=file_name,
                              bytes=size, sha256=sha256, separate_dialogue=separate_dialogue,
                              created_at=now, touched_at=now)
            self._sessions[key] = session
        try:
            self._persist(session)
        except OSError:
            with self._lock:
                self._sessions.pop(key, None)
            raise
        return True, session

    # --- ending a session --------------------------------------------------------------
    def remove(self, device_id: str, key: str) -> None:
        """Abandon ("Remove" while sending): the session and its part go, and a
        body still receiving bytes for it is stopped."""
        with self._lock:
            session = self._sessions.get(key)
            if session is None or session.device_id != device_id:
                return
            del self._sessions[key]
            attempt = self._attempts.get(device_id)
            if attempt is not None and attempt.key == key:
                attempt.superseded = True
        self._session_path(key).unlink(missing_ok=True)
        if attempt is None or attempt.key != key:
            self.part_path(key).unlink(missing_ok=True)

    def finish(self, session: Session) -> bool:
        """The session ended with a job: it goes (and its reservation with it).
        False when it had been removed meanwhile."""
        with self._lock:
            if self._sessions.get(session.client_request_id) is not session:
                return False
            del self._sessions[session.client_request_id]
        self._session_path(session.client_request_id).unlink(missing_ok=True)
        return True

    def touch(self, session: Session) -> None:
        session.touched_at = self.clock()
        with self._lock:
            current = self._sessions.get(session.client_request_id) is session
        if current:
            try:
                self._persist(session)
            except OSError:
                pass

    # --- one body per device ------------------------------------------------------------
    def begin(self, device_id: str, key: str) -> tuple[Attempt, Attempt | None]:
        """Admit a new body: (the new attempt, the older one it replaces, if
        any). Raises `409 upload_busy` while another body of this device is
        receiving bytes (or went quiet less than 10 s ago, for the same key)."""
        now = self.clock()
        with self._lock:
            current = self._attempts.get(device_id)
            replaced = None
            if current is not None and not current.ended:
                if current.key != key or now - current.last_byte_at < UPLOAD_TAKEOVER_IDLE_S:
                    raise PhoneError(409, "upload_busy")
                current.superseded = True                     # newest wins
                replaced = current
            attempt = Attempt(device_id=device_id, key=key, last_byte_at=now)
            self._attempts[device_id] = attempt
            return attempt, replaced

    def end(self, attempt: Attempt) -> None:
        with self._lock:
            if self._attempts.get(attempt.device_id) is attempt:
                del self._attempts[attempt.device_id]
            attempt.ended = True
        attempt.finished.set()

    def _limiter(self) -> anyio.CapacityLimiter:
        loop = asyncio.get_running_loop()
        limiter = self._limiters.get(loop)
        if limiter is None:
            limiter = self._limiters[loop] = anyio.CapacityLimiter(WRITE_THREADS)
        return limiter

    # --- receiving a body ----------------------------------------------------------------
    async def _follow(self, receive, on_message, on_quiet) -> None:
        """Feed the body's messages to `on_message` (which returns True to
        stop); every POLL_S seconds without a message, `on_quiet()` decides
        whether to stop. The ASGI `receive` runs in its own task and is only
        cancelled once the body is no longer wanted, so no message is lost
        to a timeout."""
        send_stream, recv_stream = anyio.create_memory_object_stream(4)

        async def pump() -> None:
            async with send_stream:
                while True:
                    message = await receive()
                    await send_stream.send(message)
                    if message.get("type") != "http.request" or not message.get("more_body", False):
                        return

        async with anyio.create_task_group() as tg:
            tg.start_soon(pump)
            try:
                while True:
                    message = None
                    with anyio.move_on_after(POLL_S):
                        try:
                            message = await recv_stream.receive()
                        except anyio.EndOfStream:
                            return
                    if message is None:
                        if on_quiet():
                            return
                    elif await on_message(message):
                        return
            finally:
                tg.cancel_scope.cancel()

    async def receive(self, receive, session: Session, attempt: Attempt) -> tuple[str, Path | None]:
        """Stream the body into the part. Returns (outcome, part): outcome is
        `ok` (size and SHA-256 match; the part is ready), `disconnected`,
        `idle`, `superseded`, `length_mismatch`, `checksum_mismatch`,
        `no_space` or `write_failed`. On every outcome but `ok` the part is
        deleted."""
        part = self.part_path(session.client_request_id)
        limiter = self._limiter()
        digest = hashlib.sha256()
        state = {"received": 0, "outcome": "disconnected"}
        fh = None

        def open_part():
            self.dir.mkdir(exist_ok=True)
            return part.open("wb")

        def write(chunk: bytes) -> None:
            fh.write(chunk)
            digest.update(chunk)

        async def on_message(message: dict) -> bool:
            if attempt.superseded:
                state["outcome"] = "superseded"
                return True
            if message.get("type") != "http.request":
                state["outcome"] = "disconnected"
                return True
            chunk = message.get("body") or b""
            if chunk:
                state["received"] += len(chunk)
                if state["received"] > session.bytes:
                    state["outcome"] = "length_mismatch"
                    return True
                attempt.last_byte_at = self.clock()
                session.touched_at = attempt.last_byte_at
                await anyio.to_thread.run_sync(write, chunk, limiter=limiter)
            if message.get("more_body", False):
                return False
            if state["received"] != session.bytes:
                state["outcome"] = "length_mismatch"
            elif digest.hexdigest() != session.sha256:
                state["outcome"] = "checksum_mismatch"
            else:
                state["outcome"] = "ok"
            return True

        def on_quiet() -> bool:
            if attempt.superseded:
                state["outcome"] = "superseded"
            elif self.clock() - attempt.last_byte_at >= UPLOAD_IDLE_ABORT_S:
                state["outcome"] = "idle"
            else:
                return False
            return True

        try:
            fh = await anyio.to_thread.run_sync(open_part, limiter=limiter)
            await self._follow(receive, on_message, on_quiet)
        except OSError as exc:
            state["outcome"] = "no_space" if exc.errno == errno.ENOSPC else "write_failed"
            log.warning("An upload from a phone could not be written to disk.")
        finally:
            if fh is not None:
                with anyio.CancelScope(shield=True):
                    await anyio.to_thread.run_sync(fh.close, limiter=limiter)
            if state["outcome"] != "ok":
                part.unlink(missing_ok=True)
        outcome = state["outcome"]
        return outcome, (part if outcome == "ok" else None)

    async def drain(self, receive) -> None:
        """Read and discard a body (a `PUT` for a key whose job exists), with
        the same idle bound."""
        last = [self.clock()]

        async def on_message(message: dict) -> bool:
            last[0] = self.clock()
            return message.get("type") != "http.request" or not message.get("more_body", False)

        await self._follow(receive, on_message,
                           lambda: self.clock() - last[0] >= UPLOAD_IDLE_ABORT_S)

    # --- the janitor ---------------------------------------------------------------------
    def sweep(self) -> int:
        """Delete sessions (and parts) untouched for 48 hours, and parts that
        belong to no session and no running body. Returns the sessions removed."""
        now = self.clock()
        with self._lock:
            receiving = {a.key for a in self._attempts.values() if not a.ended}
            stale = [s for s in self._sessions.values()
                     if now - s.touched_at >= UPLOAD_EXPIRY_S
                     and s.client_request_id not in receiving]
            for session in stale:
                del self._sessions[session.client_request_id]
            known = set(self._sessions) | receiving
        for session in stale:
            self._session_path(session.client_request_id).unlink(missing_ok=True)
            self.part_path(session.client_request_id).unlink(missing_ok=True)
        if self.dir.is_dir():
            for path in self.dir.glob("*.part"):
                if path.stem not in known:
                    path.unlink(missing_ok=True)
        return len(stale)

    def _janitor_loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.sweep()
            except Exception:                       # noqa: BLE001 — the thread must not die
                log.exception("Could not clean up old phone uploads.")
            self._stop.wait(JANITOR_INTERVAL_S)

    def start(self) -> None:
        if self._janitor is not None:
            return
        self._stop.clear()
        self._janitor = threading.Thread(target=self._janitor_loop,
                                         name="phonotrainer-phone-uploads", daemon=True)
        self._janitor.start()

    def stop(self) -> None:
        self._stop.set()
        if self._janitor is not None:
            self._janitor.join(timeout=1)
            self._janitor = None
