"""Who this computer is to phones, and where phone access is allowed.

- `server.json` (configuration directory): the server id, the name phones see,
  whether phone access is on, the approved networks and two limits.
- `<workspace>/library.json`: the library id of a workspace.
- The approved networks: turning phone access on approves the networks the
  computer is on now, so a laptop never exposes phone access on a network it
  joins later. The server answers LAN clients only on the local addresses of
  approved networks ("active approved addresses").
- `Capabilities`: what this server can do, computed once at start so that
  `health` never imports a heavy module or waits on the analysis worker.
"""

from __future__ import annotations

import base64
import importlib.metadata
import importlib.util
import ipaddress
import json
import logging
import os
import secrets
import tempfile
import threading
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable

from . import (DEFAULT_COMPUTER_NAME, LOW_SPACE_BYTES, MAX_CHUNK_BYTES,
               MAX_LINK_DURATION_S_DEFAULT, MAX_UPLOAD_BYTES_DEFAULT)
from .config import ConfigDir, ConfigError
from .netinfo import Interface

SERVER_FILE = "server.json"
LIBRARY_FILE = "library.json"

# Interfaces never approved unless the operator names them (--phone-interface):
# container bridges, virtual machines' networks, VPN tunnels and overlays. The
# kernel's own answer (`Interface.virtual`, from /sys/class/net) is the main
# signal; these names are an extra one for a system whose sysfs says nothing.
VIRTUAL_PREFIXES = (
    "docker", "br-", "virbr", "veth", "tun", "tap", "wg",           # server-contract 2.1
    "tailscale", "zt", "ppp", "nordlynx", "proton", "cscotun", "gpd", "ipsec",
    "vboxnet", "vmnet", "podman", "cni", "lxcbr", "lxdbr", "flannel", "cali",
    "vxlan", "ham", "nebula",
)

MAX_NAME_CHARS = 40
FREE_BYTES_CACHE_S = 5.0

log = logging.getLogger("phonotrainer.phone")


def iso(clock_value: float) -> str:
    """Unix seconds → ISO-8601 with UTC offset, second resolution (as job.json)."""
    return datetime.fromtimestamp(clock_value, timezone.utc).isoformat(timespec="seconds")


def parse_iso(text: str) -> float:
    return datetime.fromisoformat(text).timestamp()


def new_id(prefix: str) -> str:
    """`prefix` + 26 lower-case base32 characters (16 random bytes)."""
    return prefix + base64.b32encode(secrets.token_bytes(16)).decode("ascii").rstrip("=").lower()


_STRIPPED_CATEGORIES = {"Cc", "Cf", "Zl", "Zp"}


def clean_text(text: str) -> str:
    """Text that reaches a terminal or a phone screen: control and format
    characters (bidirectional overrides, zero-width characters), line and
    paragraph separators removed, then trimmed."""
    return "".join(ch for ch in str(text)
                   if unicodedata.category(ch) not in _STRIPPED_CATEGORIES).strip()


def clean_name(name: str) -> str:
    """The computer's name as phones see it; ValueError when invalid."""
    cleaned = clean_text(name)
    if not 1 <= len(cleaned) <= MAX_NAME_CHARS:
        raise ValueError(f"the name must be 1 to {MAX_NAME_CHARS} characters long")
    return cleaned


# --- approved networks ---------------------------------------------------------

def is_virtual(iface: Interface | str) -> bool:
    """A container, virtual machine or VPN interface: the kernel says it is not
    a physical card (`Interface.virtual`), or its name is a known virtual one.
    VPN and overlay interfaces have no gateway MAC and the same name and
    network everywhere, so an approved one would match on every network."""
    if isinstance(iface, Interface):
        return iface.virtual or iface.name.startswith(VIRTUAL_PREFIXES)
    return iface.startswith(VIRTUAL_PREFIXES)


def approvable(interfaces: Iterable[Interface], extra: Iterable[str] = ()) -> list[Interface]:
    """The interfaces `phone on` approves: up, IPv4, not loopback, not virtual
    unless named."""
    named = set(extra)
    chosen = []
    for iface in interfaces:
        if not iface.up or iface.loopback:
            continue
        try:
            if ipaddress.ip_address(iface.address).is_loopback:
                continue
        except ValueError:
            continue
        if is_virtual(iface) and iface.name not in named:
            continue
        chosen.append(iface)
    return chosen


def network_entry(iface: Interface, approved_at: str) -> dict:
    entry = {"interface": iface.name, "network": iface.network}
    if iface.gateway_mac:
        entry["gateway_mac"] = iface.gateway_mac.lower()
    entry["approved_at"] = approved_at
    return entry


def matches(entry: dict, iface: Interface) -> bool:
    """Same interface name and network, and the same gateway MAC when both
    sides know it (a missing MAC on either side matches on name and network)."""
    if entry.get("interface") != iface.name or entry.get("network") != iface.network:
        return False
    known, seen = entry.get("gateway_mac"), iface.gateway_mac
    return known is None or seen is None or known.lower() == seen.lower()


def active_addresses(approved: list[dict], interfaces: Iterable[Interface]) -> list[Interface]:
    """The up interfaces (one address each) that lie on an approved network."""
    return [iface for iface in interfaces
            if iface.up and not iface.loopback and any(matches(e, iface) for e in approved)]


# --- server.json -----------------------------------------------------------------

def _positive_int(value, default: int) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) and value > 0 else default


class ServerIdentity:
    """`server.json`: read, created on first use, re-read when it changes."""

    def __init__(self, config: ConfigDir, clock: Callable[[], float]) -> None:
        self.config = config
        self.clock = clock
        self.created = False          # True when this object created server.json
        self._data: dict = {}
        self._stamp = None
        self._lock = threading.RLock()

    # --- loading ---------------------------------------------------------------
    def load(self) -> dict:
        """Read server.json, creating it (with a new server id) if missing."""
        with self._lock, self.config.locked():
            data = self.config.read_json(SERVER_FILE)
            if data is None or not str(data.get("server_id", "")).startswith("srv_"):
                data = {
                    "server_id": new_id("srv_"),
                    "name": DEFAULT_COMPUTER_NAME,
                    "phone_access": False,
                    "approved_networks": [],
                    "max_upload_bytes": MAX_UPLOAD_BYTES_DEFAULT,
                    "max_link_duration_s": MAX_LINK_DURATION_S_DEFAULT,
                    "created_at": iso(self.clock()),
                    **({} if data is None else {k: v for k, v in data.items()
                                                if k != "server_id"}),
                }
                self.config.write_json(SERVER_FILE, data)
                self.created = True
            self._data = data
            self._stamp = self.config.stat_key(SERVER_FILE)
            return dict(data)

    def reload_if_changed(self) -> bool:
        """Re-read server.json when it was replaced (by `phonotrainer phone …`)."""
        with self._lock:
            stamp = self.config.stat_key(SERVER_FILE)
            if stamp == self._stamp:
                return False
            try:
                data = self.config.read_json(SERVER_FILE)
            except ConfigError as exc:
                log.warning("Ignoring a change to server.json: %s", exc)
                self._stamp = stamp
                return False
            if data is None:
                self.load()           # deleted by hand: recreate (a new server id)
                return True
            self._data = data
            self._stamp = stamp
            return True

    def _update(self, change: Callable[[dict], None]) -> dict:
        with self._lock, self.config.locked():
            data = self.config.read_json(SERVER_FILE)
            if data is None:
                self.load()
                data = dict(self._data)
            change(data)
            self.config.write_json(SERVER_FILE, data)
            self._data = data
            self._stamp = self.config.stat_key(SERVER_FILE)
            return dict(data)

    # --- fields --------------------------------------------------------------
    @property
    def server_id(self) -> str:
        return self._data["server_id"]

    @property
    def name(self) -> str:
        try:
            return clean_name(self._data.get("name") or DEFAULT_COMPUTER_NAME)
        except ValueError:
            return DEFAULT_COMPUTER_NAME

    @property
    def phone_access(self) -> bool:
        return self._data.get("phone_access") is True

    @property
    def approved_networks(self) -> list[dict]:
        networks = self._data.get("approved_networks")
        return [n for n in networks if isinstance(n, dict)] if isinstance(networks, list) else []

    @property
    def max_upload_bytes(self) -> int:
        return _positive_int(self._data.get("max_upload_bytes"), MAX_UPLOAD_BYTES_DEFAULT)

    @property
    def max_link_duration_s(self) -> int:
        return _positive_int(self._data.get("max_link_duration_s"), MAX_LINK_DURATION_S_DEFAULT)

    # --- the operator's choices ---------------------------------------------------
    def turn_on(self, interfaces: Iterable[Interface], extra: Iterable[str] = ()) -> list[dict]:
        """Phone access on, approving the networks the computer is on now. Never
        removes an approved network. Returns the entries for the networks found
        now (new or already approved)."""
        now = iso(self.clock())
        current = [network_entry(i, now) for i in approvable(interfaces, extra)]

        def change(data: dict) -> None:
            data["phone_access"] = True
            known = [n for n in data.get("approved_networks") or [] if isinstance(n, dict)]
            for entry in current:
                same = [n for n in known
                        if (n.get("interface"), n.get("network"), n.get("gateway_mac"))
                        == (entry["interface"], entry["network"], entry.get("gateway_mac"))]
                if not same:
                    known.append(entry)
            data["approved_networks"] = known

        self._update(change)
        return current

    def turn_off(self) -> None:
        """Phone access off; the approved networks are cleared."""
        def change(data: dict) -> None:
            data["phone_access"] = False
            data["approved_networks"] = []

        self._update(change)

    def rename(self, name: str) -> str:
        cleaned = clean_name(name)

        def change(data: dict) -> None:
            data["name"] = cleaned

        self._update(change)
        return cleaned


def enable_phone_access(settings, extra_interfaces: Iterable[str] = ()) -> list[dict]:
    """What `phonotrainer phone on` does, for code that builds its own server
    (the host-side fake computer, the tests): phone access on in
    `settings.config_dir`, with the networks `settings.netinfo()` reports now
    approved. Returns the approved entries for those networks."""
    identity = ServerIdentity(ConfigDir(settings.config_dir), settings.clock)
    identity.load()
    return identity.turn_on(settings.netinfo(), extra_interfaces)


# --- the workspace's library id ----------------------------------------------------

def library_id(workspace: str | Path, clock: Callable[[], float]) -> str:
    """`<workspace>/library.json`'s id, created the first time a phone-enabled
    server opens the workspace. The job store ignores the file (it only loads
    directories holding a job.json)."""
    path = Path(workspace) / LIBRARY_FILE
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, dict) and str(data.get("library_id", "")).startswith("lib_"):
            return data["library_id"]
    except FileNotFoundError:
        pass
    except (OSError, json.JSONDecodeError):
        log.warning("The workspace's library.json was unreadable; it gets a new library id.")
    data = {"library_id": new_id("lib_"), "created_at": iso(clock())}
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".library.json.", suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, path)
    return data["library_id"]


# --- capabilities -------------------------------------------------------------------

def _installed(module: str) -> bool:
    try:
        return importlib.util.find_spec(module) is not None
    except (ImportError, ValueError):
        return False


def downloader_version() -> str | None:
    """yt-dlp's version string exactly as installed (e.g. `2026.8.19`)."""
    try:
        return importlib.metadata.version("yt-dlp")
    except importlib.metadata.PackageNotFoundError:
        return None


class Capabilities:
    """What this server can do. The static part is computed once; the free
    space is cached for a few seconds."""

    def __init__(self, identity: ServerIdentity, workspace: Path,
                 which: Callable[[str], str | None],
                 disk_usage: Callable[[Path], object],
                 clock: Callable[[], float],
                 reserved_bytes: Callable[[], int] = lambda: 0) -> None:
        from ..download import chosen_js_runtime

        self.identity = identity
        self.workspace = Path(workspace)
        self.disk_usage = disk_usage
        self.clock = clock
        self.reserved_bytes = reserved_bytes
        self.discovery = False            # set by the discovery responder when it binds
        self.link_downloads = _installed("yt_dlp")
        self.downloader = {
            "name": "yt-dlp",
            "version": downloader_version(),
            "js_runtime": chosen_js_runtime(which),
            "challenge_solver": _installed("yt_dlp_ejs"),
        }
        self.dialogue_separation = _installed("demucs")    # never imports torch
        self._free: tuple[float, int] | None = None
        self._lock = threading.Lock()

    def free_bytes(self) -> int:
        """Free space of the workspace's filesystem minus open upload reservations."""
        now = self.clock()
        with self._lock:
            if self._free is None or now - self._free[0] >= FREE_BYTES_CACHE_S:
                try:
                    free = int(self.disk_usage(self.workspace).free)
                except OSError:
                    free = 0
                self._free = (now, free)
            free = self._free[1]
        return max(0, free - int(self.reserved_bytes()))

    def low_on_space(self) -> bool:
        return self.free_bytes() < LOW_SPACE_BYTES

    def as_dict(self) -> dict:
        return {
            "link_downloads": self.link_downloads,
            "downloader": dict(self.downloader),
            "dialogue_separation": self.dialogue_separation,
            "discovery": self.discovery,
            "renditions": False,
            "phone_video": False,
            "resumable_uploads": False,
            "record_compare": False,
            "max_upload_bytes": self.identity.max_upload_bytes,
            "max_chunk_bytes": MAX_CHUNK_BYTES,
            "max_link_duration_s": self.identity.max_link_duration_s,
            "free_bytes": self.free_bytes(),
        }
