"""Paired phones, their keys, pairing codes, proofs and rate limits.

- A phone's token (`pt1_` + 43 base64url characters) crosses the network once,
  in the pairing answer, and is never stored here: `devices.json` keeps its
  MAC key `K = sha256(token)`, with which the server checks request signatures
  and computes identity proofs.
- A pairing code is six digits, single use, valid for five minutes, locked
  after five wrong attempts, and stored only as `sha256(salt ‖ code)`.
- "Connect again" re-activates a phone's previous device row only when the
  phone proves it held that row's key (`previous_token_proof`); knowing another
  phone's device id is never enough.
"""

from __future__ import annotations

import hashlib
import hmac
import math
import secrets
import threading
from collections import deque
from dataclasses import asdict, dataclass, fields
from typing import Callable

from . import MIN_APP_VERSION, PAIRING_CODE_TTL_S, PAIRING_MAX_WRONG
from .config import ConfigDir
from .identity import clean_text, iso, new_id, parse_iso

DEVICES_FILE = "devices.json"
PAIRING_FILE = "pairing.json"

LAST_SEEN_INTERVAL_S = 60            # last_seen_at is written at most this often
PAIR_LIMIT_PER_SOURCE = 10           # pairing attempts per minute per source address
PAIR_LIMIT_TOTAL = 30                # and in total
MAX_DEVICE_NAME_CHARS = 60


# --- the shared cryptography (the phone computes the same values) -------------------

def new_token() -> str:
    """The pairing secret handed to the phone once."""
    return "pt1_" + secrets.token_urlsafe(32)


def token_key(token: str) -> bytes:
    """The device's MAC key `K = sha256(token)` (32 raw bytes)."""
    return hashlib.sha256(token.encode("ascii")).digest()


def hmac_hex(key: bytes, message: str) -> str:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).hexdigest()


def hello_proof(key: bytes, server_id: str, device_id: str, challenge: str) -> str:
    """The identity proof of `GET /api/phone/hello` (only the computer that
    issued the phone's token can compute it)."""
    return hmac_hex(key, f"pt-hello-v1|{server_id}|{device_id}|{challenge}")


def reactivation_proof(key: bytes, code: str) -> str:
    """What a phone sends to re-activate its previous device row."""
    return hmac_hex(key, f"pt-reactivate-v1|{code}")


def code_hash(salt: bytes, code: str) -> str:
    return hashlib.sha256(salt + code.encode("ascii")).hexdigest()


# --- rate limits ---------------------------------------------------------------------

class RateLimiter:
    """At most `per_key` hits per key and `total` hits overall in a sliding
    window. In memory: a restart forgets it, which is fine for its purpose."""

    def __init__(self, per_key: int, total: int, window_s: float,
                 clock: Callable[[], float]) -> None:
        self.per_key = per_key
        self.total = total
        self.window_s = window_s
        self.clock = clock
        self._hits: dict[str, deque[float]] = {}
        self._all: deque[float] = deque()
        self._lock = threading.Lock()

    def hit(self, key: str) -> int | None:
        """Record a hit; None when allowed, otherwise the seconds to wait
        (nothing is recorded then)."""
        now = self.clock()
        horizon = now - self.window_s
        with self._lock:
            while self._all and self._all[0] <= horizon:
                self._all.popleft()
            mine = self._hits.get(key)
            if mine is not None:
                while mine and mine[0] <= horizon:
                    mine.popleft()
                if not mine:
                    del self._hits[key]
                    mine = None
            if mine is not None and len(mine) >= self.per_key:
                return max(1, math.ceil(mine[0] + self.window_s - now))
            if len(self._all) >= self.total:
                return max(1, math.ceil(self._all[0] + self.window_s - now))
            self._hits.setdefault(key, deque()).append(now)
            self._all.append(now)
            return None


# --- devices ---------------------------------------------------------------------------

@dataclass
class Device:
    device_id: str
    name: str
    key_sha256: str                  # hex of K = sha256(token); a secret
    paired_at: str
    last_seen_at: str | None = None
    app_version: int = 1
    app_version_name: str | None = None
    revoked_at: str | None = None

    @property
    def key(self) -> bytes:
        return bytes.fromhex(self.key_sha256)

    @property
    def revoked(self) -> bool:
        return self.revoked_at is not None

    @classmethod
    def from_dict(cls, raw: dict) -> "Device | None":
        known = {f.name for f in fields(cls)}
        try:
            device = cls(**{k: v for k, v in raw.items() if k in known})
            bytes.fromhex(device.key_sha256)
        except (TypeError, ValueError):
            return None
        return device

    def __repr__(self) -> str:            # never print the key
        return f"Device({self.device_id!r}, {self.name!r}, revoked={self.revoked})"


class PairingError(Exception):
    """A refused pairing attempt; `code` is the API's error code."""

    STATUS = {"code_invalid": 403, "code_expired": 403, "code_locked": 403,
              "rate_limited": 429, "app_too_old": 426}

    def __init__(self, code: str, retry_after: int | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.status = self.STATUS[code]
        self.retry_after = retry_after


@dataclass
class PairingCode:
    """A code just created; the plain digits exist only in this object and
    the terminal."""

    code: str
    created_at: float
    expires_at: float

    @property
    def grouped(self) -> str:
        return f"{self.code[:3]} {self.code[3:]}"


class DeviceRegistry:
    """devices.json and pairing.json, safe across threads and processes."""

    def __init__(self, config: ConfigDir, clock: Callable[[], float]) -> None:
        self.config = config
        self.clock = clock
        self.pair_limiter = RateLimiter(PAIR_LIMIT_PER_SOURCE, PAIR_LIMIT_TOTAL, 60, clock)
        self._lock = threading.RLock()
        self._devices: dict[str, Device] = {}
        self._stamp: object = object()        # forces the first read
        self._seen_written: dict[str, float] = {}

    # --- devices.json --------------------------------------------------------------
    def _refresh(self) -> None:
        """Re-read devices.json when it changed (one stat per call), so a revoke
        by the CLI applies at the phone's next request."""
        stamp = self.config.stat_key(DEVICES_FILE)
        if stamp == self._stamp:
            return
        data = self.config.read_json(DEVICES_FILE) or {}
        devices = {}
        for raw in data.get("devices") or []:
            if isinstance(raw, dict):
                device = Device.from_dict(raw)
                if device is not None:
                    devices[device.device_id] = device
        self._devices = devices
        self._stamp = stamp

    def _write(self) -> None:
        self.config.write_json(DEVICES_FILE, {
            "devices": [asdict(d) for d in self._devices.values()]})
        self._stamp = self.config.stat_key(DEVICES_FILE)

    def get(self, device_id: str) -> Device | None:
        """The device's row, revoked or not."""
        with self._lock:
            self._refresh()
            return self._devices.get(device_id)

    def active(self, device_id: str) -> Device | None:
        device = self.get(device_id)
        return None if device is None or device.revoked else device

    def devices(self, include_revoked: bool = False) -> list[Device]:
        with self._lock:
            self._refresh()
            return [d for d in self._devices.values() if include_revoked or not d.revoked]

    def revoke(self, device_id: str) -> bool:
        """Revoke one phone; its key stops working at its next request. The row
        stays so that "Connect again" can re-activate it."""
        with self._lock, self.config.locked():
            self._stamp = object()
            self._refresh()
            device = self._devices.get(device_id)
            if device is None or device.revoked:
                return False
            device.revoked_at = iso(self.clock())
            self._write()
            return True

    def revoke_all(self) -> int:
        with self._lock, self.config.locked():
            self._stamp = object()
            self._refresh()
            now = iso(self.clock())
            count = 0
            for device in self._devices.values():
                if not device.revoked:
                    device.revoked_at = now
                    count += 1
            if count:
                self._write()
            return count

    def touch(self, device_id: str) -> None:
        """A request was accepted: last_seen_at, at most once a minute."""
        now = self.clock()
        with self._lock:
            last = self._seen_written.get(device_id)
            if last is not None and now - last < LAST_SEEN_INTERVAL_S:
                return
            with self.config.locked():
                self._stamp = object()
                self._refresh()
                device = self._devices.get(device_id)
                if device is None or device.revoked:
                    return
                if device.last_seen_at and last is None:
                    try:
                        if now - parse_iso(device.last_seen_at) < LAST_SEEN_INTERVAL_S:
                            self._seen_written[device_id] = parse_iso(device.last_seen_at)
                            return
                    except ValueError:
                        pass
                device.last_seen_at = iso(now)
                self._write()
                self._seen_written[device_id] = now

    # --- pairing codes ---------------------------------------------------------------
    def create_code(self) -> PairingCode:
        """A new code, replacing any previous one."""
        code = f"{secrets.randbelow(10 ** 6):06d}"
        salt = secrets.token_bytes(16)
        now = self.clock()
        with self.config.locked():
            self.config.write_json(PAIRING_FILE, {
                "salt": salt.hex(),
                "code_sha256": code_hash(salt, code),
                "created_at": iso(now),
                "expires_at": iso(now + PAIRING_CODE_TTL_S),
                "wrong_attempts": 0,
                "locked": False,
            })
        return PairingCode(code=code, created_at=now, expires_at=now + PAIRING_CODE_TTL_S)

    def pairing_state(self) -> dict | None:
        """pairing.json as it is (for the waiting `phone pair`)."""
        return self.config.read_json(PAIRING_FILE)

    def delete_code(self) -> None:
        with self.config.locked():
            self.config.delete(PAIRING_FILE)

    def pair(self, code: str, device_name: str, app_version: int,
             app_version_name: str | None, source: str,
             previous_device_id: str | None = None,
             previous_token_proof: str | None = None) -> tuple[Device, str]:
        """Check the code and pair. Returns the device and its token (which
        must only ever go into the pairing answer). Raises PairingError."""
        retry = self.pair_limiter.hit(source)
        if retry is not None:
            raise PairingError("rate_limited", retry_after=retry)
        if app_version < MIN_APP_VERSION:
            raise PairingError("app_too_old")          # nothing is consumed
        name = clean_text(device_name)[:MAX_DEVICE_NAME_CHARS]
        version_name = clean_text(app_version_name) if app_version_name else None

        with self._lock, self.config.locked():
            state = self.config.read_json(PAIRING_FILE) or {}
            if "code_sha256" not in state or "salt" not in state:
                raise PairingError("code_invalid")     # no oracle about an active code
            if state.get("locked"):
                raise PairingError("code_locked")
            now = self.clock()
            try:
                expired = now >= parse_iso(state["expires_at"])
                salt = bytes.fromhex(state["salt"])
            except (KeyError, TypeError, ValueError):
                raise PairingError("code_invalid") from None
            if expired:
                raise PairingError("code_expired")
            if not hmac.compare_digest(code_hash(salt, code), str(state["code_sha256"])):
                wrong = int(state.get("wrong_attempts") or 0) + 1
                state["wrong_attempts"] = wrong
                state["locked"] = wrong >= PAIRING_MAX_WRONG
                self.config.write_json(PAIRING_FILE, state)
                raise PairingError("code_invalid")

            self._stamp = object()
            self._refresh()
            token = new_token()
            key_hex = token_key(token).hex()
            stamp = iso(now)
            device = None
            if previous_device_id and previous_token_proof:
                row = self._devices.get(previous_device_id)
                if row is not None and hmac.compare_digest(
                        reactivation_proof(row.key, code), previous_token_proof.lower()):
                    device = row                        # same id: its jobs stay its own
            if device is None:
                device_id = new_id("dev_")
                while device_id in self._devices:
                    device_id = new_id("dev_")
                device = Device(device_id=device_id, name=name, key_sha256=key_hex,
                                paired_at=stamp)
                self._devices[device_id] = device
            device.name = name
            device.key_sha256 = key_hex                 # the old key stops working
            device.paired_at = stamp
            device.last_seen_at = stamp
            device.app_version = int(app_version)
            device.app_version_name = version_name
            device.revoked_at = None
            self._write()
            self._seen_written[device.device_id] = now
            # The outcome replaces the code (single use); the waiting
            # `phone pair` prints it and deletes the file.
            self.config.write_json(PAIRING_FILE, {"paired": {"name": name, "from": source}})
            return device, token
