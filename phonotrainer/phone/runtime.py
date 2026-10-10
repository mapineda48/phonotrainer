"""The live state of phone access inside one server process.

`create_app(phone=PhoneSettings(...))` builds one `PhoneRuntime`: the
computer's identity, the paired phones, the signature checker, the artifact
index and the capabilities, plus the state that follows the operator's
`phonotrainer phone on|off` while the server runs:

- `server.json` is re-read when it changes (checked at most once a second);
- the active approved addresses are recomputed every 30 s (and whenever
  server.json changes), so a laptop that joins an approved network becomes
  reachable by itself, and one that leaves it stops answering;
- the state is `on`, `suspended` (on, but no approved network is up) or `off`.
"""

from __future__ import annotations

import os
import threading
from typing import Callable

from ..jobs import JobStore
from .artifacts import ArtifactIndex
from .auth import Authenticator
from .config import RUNTIME_FILE, ConfigDir
from .identity import Capabilities, ServerIdentity, active_addresses, iso, library_id
from .netinfo import Interface
from .registry import PAIRING_FILE, DeviceRegistry, RateLimiter
from .settings import PhoneSettings

IDENTITY_CHECK_S = 1.0
NETWORK_CHECK_S = 30.0
HELLO_LIMIT_PER_SOURCE = 20          # hello with a challenge, per minute
HELLO_LIMIT_TOTAL = 120

ON, SUSPENDED, OFF = "on", "suspended", "off"


class PhoneRuntime:
    def __init__(self, settings: PhoneSettings, store: JobStore, api_version: int,
                 reference: Callable[[], dict]) -> None:
        self.settings = settings
        self.store = store
        self.api_version = api_version
        self.reference = reference
        self.clock = settings.clock
        self.config = ConfigDir(settings.config_dir)
        self.identity = ServerIdentity(self.config, self.clock)
        self.identity.load()
        self.registry = DeviceRegistry(self.config, self.clock)
        self.auth = Authenticator(self.registry, self.clock)
        self.library_id = library_id(store.root, self.clock)
        self.capabilities = Capabilities(self.identity, store.root, settings.which,
                                         settings.disk_usage, self.clock)
        self.artifacts = ArtifactIndex(store, settings.media_probe)
        self.hello_limiter = RateLimiter(HELLO_LIMIT_PER_SOURCE, HELLO_LIMIT_TOTAL, 60,
                                         self.clock)
        self._lock = threading.RLock()
        self._active: list[Interface] = []
        self._identity_checked_at = float("-inf")
        self._network_checked_at = float("-inf")
        self._state: str | None = None
        self._runtime_written = False
        self._started_at: str | None = None
        self.refresh(force=True)

    # --- the live state ---------------------------------------------------------------
    def refresh(self, force: bool = False) -> None:
        """Follow server.json and the network (cheap when nothing is due)."""
        now = self.clock()
        with self._lock:
            changed = False
            if force or now - self._identity_checked_at >= IDENTITY_CHECK_S:
                self._identity_checked_at = now
                changed = self.identity.reload_if_changed()
            if force or changed or now - self._network_checked_at >= NETWORK_CHECK_S:
                self._network_checked_at = now
                try:
                    interfaces = list(self.settings.netinfo())
                except OSError:
                    interfaces = []
                self._active = active_addresses(self.identity.approved_networks, interfaces)
            state = self._compute_state()
            if state != self._state:
                self._state = state
                if self._runtime_written:
                    self._write_runtime()

    def _compute_state(self) -> str:
        if not self.identity.phone_access:
            return OFF
        return ON if self._active else SUSPENDED

    @property
    def state(self) -> str:
        self.refresh()
        return self._state or OFF

    def active_interfaces(self) -> list[Interface]:
        """The active approved addresses (one per interface), for the LAN rule
        and the banner. Empty while suspended or off."""
        self.refresh()
        with self._lock:
            return list(self._active) if self.identity.phone_access else []

    def is_approved_address(self, address: str) -> bool:
        return any(iface.address == address for iface in self.active_interfaces())

    def now_iso(self) -> str:
        return iso(self.clock())

    # --- lifecycle (the app's lifespan) -----------------------------------------------
    def start(self) -> None:
        # A leftover pairing.json (a code or an outcome nobody read) goes: the
        # source address it may hold must not outlive the seconds it is needed.
        with self.config.locked():
            self.config.delete(PAIRING_FILE)
        self.artifacts.start()
        if self.settings.port is not None:
            self._started_at = self.now_iso()
            self._runtime_written = True
            self._write_runtime()

    def stop(self) -> None:
        self.artifacts.stop()
        if self._runtime_written:
            self._runtime_written = False
            try:
                current = self.config.read_json(RUNTIME_FILE)
            except Exception:                         # noqa: BLE001
                current = None
            if current is not None and current.get("pid") == os.getpid():
                self.config.delete(RUNTIME_FILE)

    def _write_runtime(self) -> None:
        self.config.write_json(RUNTIME_FILE, {
            "pid": os.getpid(), "port": self.settings.port,
            "started_at": self._started_at or self.now_iso(), "state": self._state or OFF})
