"""The live state of phone access inside one server process.

`create_app(phone=PhoneSettings(...))` builds one `PhoneRuntime`: the
computer's identity, the paired phones, the signature checker, the artifact
index and the capabilities, plus the state that follows the operator's
`phonotrainer phone on|off` while the server runs:

- `server.json` is re-read when it changes (checked at most once a second);
- the active approved addresses are recomputed every 30 s (and whenever
  server.json changes), so a laptop that joins an approved network becomes
  reachable by itself, and one that leaves it stops answering;
- the state is `on`, `suspended` (on, but no approved network is up) or `off`;
- a monitor thread follows those changes about once a second, so the
  discovery responder runs exactly while the state is `on`.

It also owns the upload sessions, the phone channels' slots and the limits of
phone-origin downloads (which the job store asks for).
"""

from __future__ import annotations

import logging
import os
import threading
from types import SimpleNamespace
from typing import Callable

from ..download import DownloadLimits
from ..jobs import JobStore
from . import MIN_FREE_BYTES
from .access import is_loopback
from .artifacts import ArtifactIndex
from .auth import Authenticator
from .config import RUNTIME_FILE, ConfigDir, ConfigError
from .discovery import DiscoveryResponder
from .events import ChannelSlots
from .identity import (Capabilities, ServerIdentity, active_addresses, iso, library_id,
                       parse_iso)
from .netinfo import Interface
from .registry import PAIRING_FILE, DeviceRegistry, RateLimiter
from .settings import PhoneSettings
from .ssrf import check_link
from .uploads import UploadSessions

log = logging.getLogger("phonotrainer.phone")

IDENTITY_CHECK_S = 1.0
NETWORK_CHECK_S = 30.0
MONITOR_INTERVAL_S = 1.0
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
        self.uploads = UploadSessions(store.root, self.clock)
        self.capabilities = Capabilities(self.identity, store.root, settings.which,
                                         settings.disk_usage, self.clock,
                                         reserved_bytes=self.uploads.reserved)
        self.artifacts = ArtifactIndex(store, settings.media_probe)
        self.channels = ChannelSlots()
        store.download_limits = self.download_limits
        self._responder: DiscoveryResponder | None = None
        self._discovery_failed = False      # the port was busy: not retried until off and on
        self._monitor: threading.Thread | None = None
        self._monitor_stop = threading.Event()
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

    # --- phone-origin downloads ---------------------------------------------------------
    def download_limits(self) -> DownloadLimits:
        """The bounds of a phone-origin link download, read now (server.json
        may have changed): the duration and size limits, free space minus the
        open upload reservations, and the SSRF check with this server's resolver."""
        resolver = self.settings.resolver

        def disk_usage(path):
            free = int(self.settings.disk_usage(path).free) - int(self.uploads.reserved())
            return SimpleNamespace(free=max(0, free))

        return DownloadLimits(max_duration_s=self.identity.max_link_duration_s,
                              max_bytes=self.identity.max_upload_bytes,
                              min_free_bytes=MIN_FREE_BYTES, disk_usage=disk_usage,
                              check_link=lambda url: check_link(url, resolver))

    # --- discovery ----------------------------------------------------------------------
    @property
    def responder(self) -> DiscoveryResponder | None:
        return self._responder

    def sync_discovery(self) -> None:
        """Run the responder exactly while phone access is on (not while
        suspended or off). Its replies carry the listening port, so it needs
        one (`serve` sets it); a server bound to a loopback address announces
        nothing, since no phone could connect to it. A busy port is reported
        once and retried only after phone access was off or suspended in
        between."""
        want = (self.state == ON and self.settings.port is not None
                and not is_loopback(self.settings.host))
        stopping = None
        with self._lock:
            if want and self._responder is None and not self._discovery_failed:
                responder = DiscoveryResponder(
                    self.settings.discovery_port,
                    lambda: (self.identity.server_id, self.identity.name),
                    self.settings.port, self.api_version, self.active_interfaces, self.clock,
                    host=self.settings.discovery_host)
                if responder.start():
                    self._responder = responder
                    self.capabilities.discovery = True
                else:
                    self._discovery_failed = True
                    self.capabilities.discovery = False
            elif not want:
                self._discovery_failed = False
                stopping, self._responder = self._responder, None
                self.capabilities.discovery = False
        if stopping is not None:
            stopping.stop()

    def _monitor_loop(self) -> None:
        while not self._monitor_stop.wait(MONITOR_INTERVAL_S):
            try:
                self.refresh()
                self.sync_discovery()
            except Exception:                     # noqa: BLE001 — the thread must not die
                log.exception("Could not follow the phone access state.")

    # --- lifecycle (the app's lifespan) -----------------------------------------------
    def start(self) -> None:
        self._discard_stale_pairing()
        self.artifacts.start()
        self.uploads.start()
        if self.settings.port is not None:
            self._started_at = self.now_iso()
            self._runtime_written = True
            self._write_runtime()
        self.sync_discovery()
        self._monitor_stop.clear()
        self._monitor = threading.Thread(target=self._monitor_loop,
                                         name="phonotrainer-phone-state", daemon=True)
        self._monitor.start()

    def _discard_stale_pairing(self) -> None:
        """A leftover pairing.json goes at start: an outcome nobody read (the
        source address it holds must not outlive the seconds it is needed), or
        a code that expired, locked or is unreadable. A valid code created just
        before the server started (by a test harness) is kept."""
        with self.config.locked():
            try:
                state = self.config.read_json(PAIRING_FILE)
            except ConfigError:
                state = {}
            if state is None:
                return
            try:
                valid = ("code_sha256" in state and not state.get("locked")
                         and parse_iso(state["expires_at"]) > self.clock())
            except (KeyError, TypeError, ValueError):
                valid = False
            if not valid:
                self.config.delete(PAIRING_FILE)

    def stop(self) -> None:
        self._monitor_stop.set()
        if self._monitor is not None:
            self._monitor.join(timeout=2)
            self._monitor = None
        with self._lock:
            responder, self._responder = self._responder, None
        if responder is not None:
            responder.stop()
        self.capabilities.discovery = False
        self.uploads.stop()
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
