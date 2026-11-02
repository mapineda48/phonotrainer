"""The configuration directory, the computer's identity and the approved
networks (server-contract 2.1, 3; S-01, S-02, D-34)."""

import json
import logging
import os
import socket
import stat
import struct
import subprocess
import sys

import pytest
from conftest import enable_phone_access, lan, loopback
from fastapi.testclient import TestClient
from phone_fixtures import (DOCKER, HOME_MAC, LOOPBACK_IF, TUNNEL, WLAN_CAFE, WLAN_HOME,
                            FakeClock)

from phonotrainer.jobs import JobStore
from phonotrainer.phone.config import ConfigDir, ConfigError, config_dir, runtime_info
from phonotrainer.phone.identity import (ServerIdentity, active_addresses, approvable,
                                         is_virtual, library_id, matches, network_entry)
from phonotrainer.phone.netinfo import (Interface, kernel_virtual, parse_arp,
                                        parse_default_gateways)
from phonotrainer.server import create_app


def mode(path) -> int:
    return stat.S_IMODE(os.stat(path).st_mode)


POSIX_ONLY = pytest.mark.skipif(sys.platform == "win32",
                                reason="POSIX owners and mode bits (Windows: the DACL tests)")
WINDOWS_ONLY = pytest.mark.skipif(sys.platform != "win32", reason="Windows owners and DACLs")


# --- where it is -----------------------------------------------------------------------------

def test_directory_resolution(tmp_path, monkeypatch):
    env = {"PHONOTRAINER_CONFIG_DIR": str(tmp_path / "explicit"),
           "XDG_CONFIG_HOME": str(tmp_path / "xdg")}
    assert config_dir(env) == tmp_path / "explicit"
    del env["PHONOTRAINER_CONFIG_DIR"]
    assert config_dir(env) == tmp_path / "xdg" / "phonotrainer"
    env["XDG_CONFIG_HOME"] = "relative/xdg"            # the XDG spec ignores it
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("USERPROFILE", str(tmp_path / "home"))     # Path.home() on Windows
    assert config_dir(env) == tmp_path / "home" / ".config" / "phonotrainer"
    assert config_dir({}) == tmp_path / "home" / ".config" / "phonotrainer"
    # %LOCALAPPDATA% comes after an absolute XDG_CONFIG_HOME, and only on Windows;
    # the roaming %APPDATA% never (it would carry the keys to other computers)
    env["APPDATA"] = str(tmp_path / "roaming")
    assert config_dir(env) == tmp_path / "home" / ".config" / "phonotrainer"
    env["LOCALAPPDATA"] = str(tmp_path / "local")
    expected = tmp_path / ("local" if sys.platform == "win32" else "home/.config")
    assert config_dir(env) == expected / "phonotrainer"
    env["XDG_CONFIG_HOME"] = str(tmp_path / "xdg")
    assert config_dir(env) == tmp_path / "xdg" / "phonotrainer"


# --- modes and ownership ----------------------------------------------------------------------

@POSIX_ONLY
def test_created_0700_and_files_0600(isolated_config):
    config = ConfigDir(isolated_config)
    assert not isolated_config.exists()
    config.write_json("devices.json", {"devices": []})
    config.write_json("server.json", {})
    assert mode(isolated_config) == 0o700
    assert mode(isolated_config / "devices.json") == 0o600
    assert mode(isolated_config / "server.json") == 0o600


@POSIX_ONLY
def test_modes_are_rechecked_and_tightened_at_every_open(isolated_config, caplog):
    config = ConfigDir(isolated_config)
    config.write_json("devices.json", {"devices": []})
    os.chmod(isolated_config, 0o755)
    os.chmod(isolated_config / "devices.json", 0o644)
    with caplog.at_level(logging.WARNING, logger="phonotrainer.phone"):
        assert ConfigDir(isolated_config).read_json("devices.json") == {"devices": []}
    assert mode(isolated_config) == 0o700
    assert mode(isolated_config / "devices.json") == 0o600
    warnings = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1
    assert str(isolated_config) not in warnings[0] and "/" not in warnings[0]
    # an open that finds nothing wrong says nothing
    caplog.clear()
    with caplog.at_level(logging.WARNING, logger="phonotrainer.phone"):
        config.read_json("devices.json")
    assert not caplog.records


@POSIX_ONLY
def test_a_directory_owned_by_another_user_is_refused(isolated_config, monkeypatch):
    config = ConfigDir(isolated_config)
    config.ensure()
    real_uid = os.getuid()
    monkeypatch.setattr(os, "getuid", lambda: real_uid + 1)
    with pytest.raises(ConfigError) as err:
        ConfigDir(isolated_config).read_json("server.json")
    assert "another user" in str(err.value)
    assert str(isolated_config) not in str(err.value)


@POSIX_ONLY
def test_a_secret_file_owned_by_another_user_is_refused(isolated_config, monkeypatch):
    config = ConfigDir(isolated_config)
    config.write_json("devices.json", {"devices": []})
    real_stat = os.lstat

    class Foreign:
        def __init__(self, st):
            self._st = st

        def __getattr__(self, name):
            return getattr(self._st, name)

        @property
        def st_uid(self):
            return self._st.st_uid + 1

    def fake_lstat(path, *args, **kwargs):
        st = real_stat(path, *args, **kwargs)
        return Foreign(st) if str(path).endswith("devices.json") else st

    monkeypatch.setattr(os, "lstat", fake_lstat)
    with pytest.raises(ConfigError):
        config.read_json("devices.json")


# The Windows equivalents: a private DACL instead of 0700/0600, the owner SID
# instead of the uid. SDDL: full access (FA) granted to Everyone (WD).
EVERYONE_TOO = "D:(A;OICI;FA;;;WD)"
FOREIGN_SID = "S-1-5-21-1-2-3-1001"          # a made-up account


def grant_everyone(path) -> None:
    import ctypes
    from ctypes import wintypes

    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    descriptor, dacl = ctypes.c_void_p(), ctypes.c_void_p()
    present, defaulted = wintypes.BOOL(), wintypes.BOOL()
    assert advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
        EVERYONE_TOO, 1, ctypes.byref(descriptor), None)
    assert advapi32.GetSecurityDescriptorDacl(descriptor, ctypes.byref(present),
                                              ctypes.byref(dacl), ctypes.byref(defaulted))
    unprotected = 0x20000000                 # UNPROTECTED_DACL_SECURITY_INFORMATION
    assert advapi32.SetNamedSecurityInfoW(str(path), 1, 0x4 | unprotected, None, None,
                                          dacl, None) == 0
    ctypes.WinDLL("kernel32").LocalFree(descriptor)


@WINDOWS_ONLY
def test_created_private_to_this_user_on_windows(isolated_config):
    from phonotrainer.phone import winsec

    config = ConfigDir(isolated_config)
    config.write_json("devices.json", {"devices": []})
    trusted = {winsec.current_user_sid(), winsec.SYSTEM_SID, winsec.ADMINISTRATORS_SID}
    for path in (isolated_config, isolated_config / "devices.json"):
        assert winsec.grantees(path) == trusted, path
        assert winsec.foreign_grantees(path) == set()
        assert winsec.owned_by_me(path)


@WINDOWS_ONLY
def test_a_dacl_letting_others_in_is_tightened_at_every_open(isolated_config, caplog):
    from phonotrainer.phone import winsec

    config = ConfigDir(isolated_config)
    config.write_json("devices.json", {"devices": []})
    grant_everyone(isolated_config)
    grant_everyone(isolated_config / "devices.json")
    assert winsec.foreign_grantees(isolated_config / "devices.json")
    with caplog.at_level(logging.WARNING, logger="phonotrainer.phone"):
        assert ConfigDir(isolated_config).read_json("devices.json") == {"devices": []}
    assert winsec.foreign_grantees(isolated_config) == set()
    assert winsec.foreign_grantees(isolated_config / "devices.json") == set()
    warnings = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1 and "the directory and devices.json" in warnings[0]
    assert str(isolated_config) not in warnings[0] and "\\" not in warnings[0]
    caplog.clear()
    with caplog.at_level(logging.WARNING, logger="phonotrainer.phone"):
        config.read_json("devices.json")
    assert not caplog.records


@WINDOWS_ONLY
def test_a_directory_or_secret_file_owned_by_another_user_is_refused_on_windows(
        isolated_config, monkeypatch):
    from phonotrainer.phone import winsec

    config = ConfigDir(isolated_config)
    config.write_json("devices.json", {"devices": []})
    real_owner = winsec.owner_sid
    for foreign in (str(isolated_config), str(isolated_config / "devices.json")):
        monkeypatch.setattr(winsec, "owner_sid", lambda path, foreign=foreign: (
            FOREIGN_SID if str(path) == foreign else real_owner(path)))
        with pytest.raises(ConfigError) as err:
            ConfigDir(isolated_config).read_json("devices.json")
        assert "another user" in str(err.value) and str(isolated_config) not in str(err.value)


@WINDOWS_ONLY
def test_every_file_in_the_directory_is_checked_on_windows(isolated_config, monkeypatch, caplog):
    """"Bypass traverse checking" lets anyone open a file by its path inside a
    private directory: a planted file is refused, a shared one made private."""
    from phonotrainer.phone import winsec

    config = ConfigDir(isolated_config)
    config.write_json("server.json", {})
    shared = isolated_config / "notes.txt"
    shared.write_text("x")
    grant_everyone(shared)
    assert winsec.foreign_grantees(shared)
    with caplog.at_level(logging.WARNING, logger="phonotrainer.phone"):
        assert config.read_json("server.json") == {}
    assert winsec.foreign_grantees(shared) == set()
    warnings = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1 and "notes.txt" in warnings[0]
    real_owner = winsec.owner_sid
    monkeypatch.setattr(winsec, "owner_sid", lambda path: (
        FOREIGN_SID if os.path.basename(path) == "server.json" else real_owner(path)))
    with pytest.raises(ConfigError) as err:
        ConfigDir(isolated_config).read_json("server.json")
    assert "server.json" in str(err.value) and "another user" in str(err.value)
    assert str(isolated_config) not in str(err.value)


@WINDOWS_ONLY
def test_an_ace_the_check_does_not_understand_counts_as_someone_else():
    import ctypes

    from phonotrainer.phone import winsec

    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    advapi32.GetLengthSid.restype = ctypes.c_ulong

    def sid(text: str) -> bytes:
        pointer = ctypes.c_void_p()
        assert advapi32.ConvertStringSidToSidW(text, ctypes.byref(pointer))
        data = ctypes.string_at(pointer, advapi32.GetLengthSid(pointer))
        ctypes.WinDLL("kernel32").LocalFree(pointer)
        return data

    def ace(kind: int, sid_bytes: bytes, mask: int = 0x1F01FF) -> bytes:
        body = struct.pack("<I", mask) + sid_bytes
        return struct.pack("<BBH", kind, 0, 4 + len(body)) + body

    def grantees(*aces: bytes) -> set[str]:
        body = b"".join(aces)
        acl = ctypes.create_string_buffer(struct.pack("<BBHHH", 2, 0, 8 + len(body),
                                                      len(aces), 0) + body)
        return winsec.acl_grantees(ctypes.addressof(acl))

    me, everyone = winsec.current_user_sid(), sid("S-1-1-0")
    mine = ace(0x0, sid(me))
    assert grantees(mine) == {me}
    assert grantees(mine, ace(0x1, everyone)) == {me}                 # denying takes nothing
    assert grantees(mine, ace(0x0, everyone, mask=0)) == {me}         # grants nothing
    for kind in (0x5, 0xB, 0x11, 0x15):        # object types, a label, a type yet to come
        assert grantees(mine, ace(kind, everyone)) == {me, winsec.UNKNOWN_GRANTEE}, kind


def test_writes_are_atomic(isolated_config, monkeypatch):
    config = ConfigDir(isolated_config)
    config.write_json("server.json", {"server_id": "srv_before"})

    def broken_dump(*args, **kwargs):
        raise RuntimeError("disk full halfway")

    monkeypatch.setattr("phonotrainer.phone.config.json.dump", broken_dump)
    with pytest.raises(RuntimeError):
        config.write_json("server.json", {"server_id": "srv_after"})
    monkeypatch.undo()
    assert config.read_json("server.json") == {"server_id": "srv_before"}
    assert sorted(p.name for p in isolated_config.iterdir()) == [".lock", "server.json"] or \
        sorted(p.name for p in isolated_config.iterdir()) == ["server.json"]


@WINDOWS_ONLY
def test_a_write_or_delete_waits_for_a_reader_that_holds_the_file_on_windows(isolated_config):
    """Windows refuses to replace or delete a file another handle has open (a
    server re-reading server.json while `phonotrainer phone name` writes it)."""
    import threading

    config = ConfigDir(isolated_config)
    config.write_json("server.json", {"n": 1})
    reader = open(isolated_config / "server.json", encoding="utf-8")
    threading.Timer(0.3, reader.close).start()
    config.write_json("server.json", {"n": 2})
    assert reader.closed and config.read_json("server.json") == {"n": 2}
    assert sorted(p.name for p in isolated_config.iterdir()) == [".lock", "server.json"] or \
        sorted(p.name for p in isolated_config.iterdir()) == ["server.json"]
    reader = open(isolated_config / "server.json", encoding="utf-8")
    threading.Timer(0.3, reader.close).start()
    config.delete("server.json")
    assert reader.closed and not (isolated_config / "server.json").exists()


def test_a_damaged_file_is_an_error_not_a_guess(isolated_config):
    config = ConfigDir(isolated_config)
    config.ensure()
    (isolated_config / "server.json").write_text("{not json")
    with pytest.raises(ConfigError) as err:
        ServerIdentity(config, FakeClock()).load()
    assert "server.json" in str(err.value) and str(isolated_config) not in str(err.value)


INCREMENT = """
import sys
from phonotrainer.phone.config import ConfigDir
config = ConfigDir(sys.argv[1])
for _ in range(int(sys.argv[2])):
    with config.locked():
        data = config.read_json("counter.json") or {"n": 0}
        data["n"] += 1
        config.write_json("counter.json", data)
"""


def test_the_lock_holds_across_processes(isolated_config):
    """Read-modify-write cycles of separate processes (the CLI and a running
    server) never lose each other's writes."""
    import subprocess
    import sys
    from pathlib import Path

    ConfigDir(isolated_config).ensure()
    root = Path(__file__).resolve().parents[1]
    workers = [subprocess.Popen([sys.executable, "-c", INCREMENT, str(isolated_config), "40"],
                                cwd=root) for _ in range(4)]
    for worker in workers:
        assert worker.wait(timeout=60) == 0
    assert ConfigDir(isolated_config).read_json("counter.json") == {"n": 160}


# --- identity ------------------------------------------------------------------------------------

def test_server_id_is_stable_across_restarts_and_workspaces(isolated_config, phone_settings,
                                                            tmp_path):
    ids, libraries = set(), []
    for workspace in ("one", "two", "one"):
        store = JobStore(tmp_path / workspace, corpus=None)
        app = create_app(store=store, phone=phone_settings, web_dist=tmp_path / "nb",
                         allowed_roots=[tmp_path])
        ids.add(app.state.phone.identity.server_id)
        libraries.append(app.state.phone.library_id)
        store.shutdown()
    assert len(ids) == 1
    assert libraries[0] == libraries[2] != libraries[1]
    server = json.loads((isolated_config / "server.json").read_text())
    assert server["server_id"] in ids
    assert set(server) == {"server_id", "name", "phone_access", "approved_networks",
                           "max_upload_bytes", "max_link_duration_s", "created_at"}
    assert server["max_upload_bytes"] == 2 * 1024 ** 3
    assert server["max_link_duration_s"] == 3600


def test_the_default_name_is_never_the_host_name(isolated_config):
    identity = ServerIdentity(ConfigDir(isolated_config), FakeClock())
    identity.load()
    assert identity.created is True
    assert identity.name == "PhonoTrainer computer"
    assert socket.gethostname() not in (isolated_config / "server.json").read_text()
    again = ServerIdentity(ConfigDir(isolated_config), FakeClock())
    again.load()
    assert again.created is False


def test_library_id_per_workspace(tmp_path):
    clock = FakeClock()
    first = library_id(tmp_path / "a", clock)
    assert first.startswith("lib_") and len(first) == 30
    assert library_id(tmp_path / "a", clock) == first
    assert library_id(tmp_path / "b", clock) != first
    stored = json.loads((tmp_path / "a" / "library.json").read_text())
    assert stored == {"library_id": first, "created_at": "2026-10-10T09:00:00+00:00"}
    # the job store ignores it
    store = JobStore(tmp_path / "a", corpus=None)
    assert store.list() == []
    store.shutdown()


def test_hand_edited_limits_are_honoured(isolated_config):
    config = ConfigDir(isolated_config)
    identity = ServerIdentity(config, FakeClock())
    data = identity.load()
    data.update(max_upload_bytes=1024, max_link_duration_s=-5)
    config.write_json("server.json", data)
    assert identity.reload_if_changed()
    assert identity.max_upload_bytes == 1024
    assert identity.max_link_duration_s == 3600       # invalid: the default


# --- approved networks ----------------------------------------------------------------------

def test_phone_on_approves_the_current_networks_but_not_virtual_ones(isolated_config):
    identity = ServerIdentity(ConfigDir(isolated_config), FakeClock())
    identity.load()
    approved = identity.turn_on([LOOPBACK_IF, WLAN_HOME, DOCKER, TUNNEL])
    assert approved == [{"interface": "wlan0", "network": "192.0.2.0/24",
                         "gateway_mac": HOME_MAC, "approved_at": "2026-10-10T09:00:00+00:00"}]
    assert identity.phone_access is True
    # a named virtual interface is approved too, and nothing is ever removed by `on`
    identity.turn_on([WLAN_CAFE, TUNNEL], extra=["tun0"])
    networks = [(n["interface"], n["network"]) for n in identity.approved_networks]
    assert networks == [("wlan0", "192.0.2.0/24"), ("wlan0", "203.0.113.0/24"),
                        ("tun0", "198.51.100.128/25")]
    identity.turn_on([LOOPBACK_IF, WLAN_HOME])        # already approved: no duplicate
    assert len(identity.approved_networks) == 3
    identity.turn_off()
    assert identity.phone_access is False and identity.approved_networks == []


def test_an_unknown_gateway_mac_is_left_out_and_matches_on_name_and_network():
    no_mac = Interface("wlan0", "192.0.2.10", 24, None)
    entry = network_entry(no_mac, "2026-10-10T09:00:00+00:00")
    assert "gateway_mac" not in entry
    assert matches(entry, WLAN_HOME) and matches(entry, no_mac)
    with_mac = network_entry(WLAN_HOME, "x")
    assert matches(with_mac, no_mac)
    assert not matches(with_mac, Interface("wlan0", "192.0.2.10", 24, "00:00:5e:00:53:99"))
    assert not matches(with_mac, Interface("wlan1", "192.0.2.10", 24, HOME_MAC))
    assert not matches(with_mac, Interface("wlan0", "192.0.2.10", 25, HOME_MAC))


def test_active_addresses():
    approved = [network_entry(WLAN_HOME, "x")]
    assert active_addresses(approved, [LOOPBACK_IF, WLAN_HOME, DOCKER]) == [WLAN_HOME]
    assert active_addresses(approved, [LOOPBACK_IF, WLAN_CAFE]) == []
    down = Interface("wlan0", "192.0.2.10", 24, HOME_MAC, up=False)
    assert active_addresses(approved, [down]) == []
    assert approvable([LOOPBACK_IF, DOCKER, TUNNEL, down]) == []


def test_public_networks_are_approved_only_when_named(isolated_config, fake_clock):
    """A computer plugged straight into the ISP's modem, or on a campus network
    with public addressing: approving it would open pairing to the internet."""
    from phone_fixtures import PUBLIC_V4

    from phonotrainer.phone.identity import is_public, skipped_public

    modem = Interface("eth0", PUBLIC_V4, 24, None)
    cgnat = Interface("wwan0", "100.64.1.2", 10, None)          # hygiene: test-vector
    assert is_public(modem) and not is_public(WLAN_HOME) and not is_public(cgnat)
    assert approvable([LOOPBACK_IF, WLAN_HOME, modem, cgnat]) == [WLAN_HOME, cgnat]
    assert skipped_public([LOOPBACK_IF, WLAN_HOME, modem, DOCKER]) == [modem]
    assert approvable([WLAN_HOME, modem], extra=["eth0"]) == [WLAN_HOME, modem]
    assert skipped_public([modem], extra=["eth0"]) == []
    identity = ServerIdentity(ConfigDir(isolated_config), fake_clock)
    identity.load()
    approved = identity.turn_on([LOOPBACK_IF, WLAN_HOME, modem])
    assert [entry["interface"] for entry in approved] == ["wlan0"]


def fake_sysfs(root, name: str, device: str, kind: int):
    """/sys/class/net/<name> → /sys/devices/<device>/net/<name>, with its type."""
    target = root / "devices" / device / "net" / name
    target.mkdir(parents=True)
    (target / "type").write_text(f"{kind}\n")
    link = root / "class" / "net" / name
    link.parent.mkdir(parents=True, exist_ok=True)
    link.symlink_to(target)


@pytest.mark.skipif(sys.platform == "win32",
                    reason="Linux sysfs: device names with ':' and symlinks, '/' separators")
def test_the_kernel_says_which_interfaces_are_virtual(tmp_path):
    sysfs = tmp_path / "sys"
    cards = [("wlp3s0", "pci0000:00/0000:00:1c.0/0000:02:00.0", 1, False),
             ("enp1s0f0", "pci0000:00/0000:00:1d.0/0000:03:00.0", 1, False),
             ("usb0", "pci0000:00/0000:00:14.0/usb1/1-2/1-2:1.0", 1, False),   # phone tethering
             ("wwan0", "pci0000:00/0000:00:14.0/usb1/1-3/1-3:1.4", 65534, True),
             ("tailscale0", "virtual", 65534, True),
             ("ppp0", "virtual", 512, True),
             ("vboxnet0", "virtual", 1, True),
             ("br0", "virtual", 1, True),
             ("corpvpn", "virtual", 65534, True)]
    for name, device, kind, _ in cards:
        fake_sysfs(sysfs, name, device, kind)
    net = sysfs / "class" / "net"
    for name, _, _, virtual in cards:
        assert kernel_virtual(name, net) is virtual, name
    assert kernel_virtual("eth9", net) is False          # unknown: the name rules decide
    assert kernel_virtual("../x", net) is True
    if os.path.exists("/sys/class/net/lo"):
        assert kernel_virtual("lo") is True


def test_vpn_overlay_and_vm_interfaces_are_never_approved_unless_named(isolated_config):
    tailscale = Interface("tailscale0", "198.51.100.5", 32, None)
    ppp = Interface("ppp0", "203.0.113.77", 32, None)
    vbox = Interface("vboxnet0", "192.0.2.129", 25, None)
    others = [Interface(name, "198.51.100.40", 24, None)
              for name in ("zt7nnig26", "nordlynx", "proton0", "cscotun0", "gpd0", "ipsec0",
                           "vmnet1", "podman0", "cni0", "lxcbr0", "wg0", "docker0")]
    renamed = Interface("corpvpn", "198.51.100.66", 24, None, virtual=True)  # only the kernel knows
    for iface in (tailscale, ppp, vbox, renamed, *others):
        assert is_virtual(iface), iface.name
    assert not is_virtual(WLAN_HOME)
    assert approvable([LOOPBACK_IF, WLAN_HOME, tailscale, ppp, vbox, renamed, *others]) \
        == [WLAN_HOME]
    assert approvable([WLAN_HOME, tailscale, ppp, renamed], extra=["tailscale0", "corpvpn"]) \
        == [WLAN_HOME, tailscale, renamed]
    identity = ServerIdentity(ConfigDir(isolated_config), FakeClock())
    identity.load()
    identity.turn_on([LOOPBACK_IF, WLAN_CAFE, tailscale, ppp, vbox])
    assert [n["interface"] for n in identity.approved_networks] == ["wlan0"]


def test_suspended_on_an_unapproved_network_then_active_within_30_s(
        phone_store, phone_settings, fake_netinfo, fake_clock, tmp_path):
    enable_phone_access(phone_settings)
    fake_netinfo.at_cafe()
    app = create_app(store=phone_store, phone=phone_settings, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    with TestClient(app):
        phone = app.state.phone
        assert phone.state == "suspended"
        cafe = TestClient(app, client=("203.0.113.20", 1), base_url="http://203.0.113.10:8000")
        assert cafe.get("/api/phone/hello").json()["code"] == "lan_forbidden"
        assert loopback(app).get("/api/jobs").status_code == 200       # loopback as always
        fake_netinfo.at_home()
        fake_clock.advance(29)
        assert phone.state == "suspended"
        assert lan(app).get("/api/phone/hello").json()["code"] == "lan_forbidden"
        fake_clock.advance(1)
        assert phone.state == "on"
        assert lan(app).get("/api/phone/hello").json()["phone_access"] is True
        # and leaving the approved network suspends it again
        fake_netinfo.offline()
        fake_clock.advance(30)
        assert lan(app).get("/api/phone/hello").status_code == 403


def test_runtime_json_follows_the_server(phone_store, phone_settings, fake_netinfo, fake_clock,
                                         tmp_path, isolated_config):
    from dataclasses import replace

    enable_phone_access(phone_settings)
    fake_netinfo.offline()
    settings = replace(phone_settings, port=8123)
    app = create_app(store=phone_store, phone=settings, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    config = ConfigDir(isolated_config)
    assert runtime_info(config) is None
    with TestClient(app):
        info = runtime_info(config)
        assert info["pid"] == os.getpid() and info["port"] == 8123
        assert info["state"] == "suspended"
        if sys.platform == "win32":
            from phonotrainer.phone import winsec

            assert winsec.foreign_grantees(isolated_config / "runtime.json") == set()
        else:
            assert mode(isolated_config / "runtime.json") == 0o600
        fake_netinfo.at_home()
        fake_clock.advance(30)
        app.state.phone.refresh()
        assert runtime_info(config)["state"] == "on"
    assert runtime_info(config) is None
    assert not (isolated_config / "runtime.json").exists()


@pytest.mark.parametrize("leftover", [
    {"paired": {"name": "Pixel 9", "from": "192.0.2.23"}},       # an outcome nobody read
    "expired", "locked", {"garbage": True},
])
def test_a_server_start_deletes_a_leftover_pairing_file(phone_store, phone_settings, tmp_path,
                                                        isolated_config, fake_clock, leftover):
    from phonotrainer.phone.registry import DeviceRegistry

    enable_phone_access(phone_settings)
    config = ConfigDir(isolated_config)
    if isinstance(leftover, dict):
        config.write_json("pairing.json", leftover)
    else:
        DeviceRegistry(config, fake_clock).create_code()
        state = config.read_json("pairing.json")
        if leftover == "expired":
            fake_clock.advance(300)
        else:
            state["locked"] = True
            config.write_json("pairing.json", state)
    app = create_app(store=phone_store, phone=phone_settings, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    with TestClient(app):
        assert not (isolated_config / "pairing.json").exists()


def test_a_valid_code_created_before_the_start_is_kept(phone_store, phone_settings, tmp_path,
                                                       isolated_config, fake_clock):
    """The host-side fake computer prints a code and then starts serving."""
    from phonotrainer.phone.registry import DeviceRegistry

    enable_phone_access(phone_settings)
    code = DeviceRegistry(ConfigDir(isolated_config), fake_clock).create_code().code
    app = create_app(store=phone_store, phone=phone_settings, web_dist=tmp_path / "nb",
                     allowed_roots=[tmp_path])
    with TestClient(app):
        resp = lan(app).post("/api/phone/pair", json={"code": code, "device_name": "Emulator",
                                                      "app_version": 1})
        assert resp.status_code == 201


def test_create_app_without_phone_settings_touches_no_configuration_directory(
        phone_store, tmp_path, isolated_config):
    app = create_app(store=phone_store, web_dist=tmp_path / "nb", allowed_roots=[tmp_path])
    with TestClient(app) as client:
        client.get("/api/health")
        client.get("/api/phone/hello")
        client.post("/api/phone/pair", json={})
    assert not isolated_config.exists()
    assert not (phone_store.root / "library.json").exists()


# --- is the server still running? ------------------------------------------------------------

def test_liveness_of_this_process_and_of_one_that_exited(isolated_config):
    from phonotrainer.phone.config import pid_alive

    assert pid_alive(os.getpid())
    done = subprocess.Popen([sys.executable, "-c", "pass"])
    done.wait(timeout=60)
    assert not pid_alive(done.pid)
    config = ConfigDir(isolated_config)
    config.write_json("runtime.json", {"pid": done.pid, "port": 8000})
    assert runtime_info(config) is None
    config.write_json("runtime.json", {"pid": os.getpid(), "port": 8000})
    assert runtime_info(config)["port"] == 8000


@WINDOWS_ONLY
def test_the_liveness_probe_never_signals_on_windows(isolated_config, monkeypatch):
    """os.kill(pid, 0) on Windows sends CTRL_C_EVENT to the whole console (a
    running `phonotrainer ui` there would stop): `phone status` must never call
    it, for a live pid, a dead one or an impossible one."""
    def no_kill(*args):
        raise AssertionError(f"os.kill{args} on Windows")

    monkeypatch.setattr(os, "kill", no_kill)
    config = ConfigDir(isolated_config)
    for pid, running in ((os.getpid(), True), (2 ** 22 + 12345, False), (0, False),
                         (2 ** 40, False)):
        config.write_json("runtime.json", {"pid": pid, "port": 8000})
        assert (runtime_info(config) is not None) is running, pid


# --- netinfo parsing ---------------------------------------------------------------------------

ROUTE = """Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT
wlan0\t00000000\t010200C0\t0003\t0\t0\t600\t00000000\t0\t0\t0
wlan0\t000200C0\t00000000\t0001\t0\t0\t600\t00FFFFFF\t0\t0\t0
docker0\t006433C6\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0
"""
ARP = """IP address       HW type     Flags       HW address            Mask     Device
192.0.2.1        0x1         0x2         00:00:5E:00:53:01     *        wlan0
192.0.2.30       0x1         0x0         00:00:00:00:00:00     *        wlan0
"""


@WINDOWS_ONLY
def test_the_windows_interfaces_include_loopback_and_say_what_is_virtual():
    from phonotrainer.phone.netinfo import interfaces
    from phonotrainer.phone.winnet import windows_virtual

    found = interfaces()
    loopback_ifs = [i for i in found if i.loopback]
    assert loopback_ifs and all(i.address.startswith("127.") and i.virtual for i in loopback_ifs)
    assert all(0 <= i.prefix <= 32 for i in found)
    assert not windows_virtual(True, 71, "Some Wireless LAN Card")          # Wi-Fi
    assert not windows_virtual(True, 6, "Some PCIe GbE Controller")         # Ethernet
    assert windows_virtual(False, 6, "Hyper-V Virtual Ethernet Adapter")    # vEthernet (WSL)
    assert windows_virtual(True, 6, "VirtualBox Host-Only Ethernet Adapter")
    assert windows_virtual(True, 6, "VMware Virtual Ethernet Adapter for VMnet8")
    assert windows_virtual(True, 53, "TAP-Windows Adapter V9")
    assert windows_virtual(True, 131, "Some tunnel")
    assert windows_virtual(True, 23, "Some PPP link")
    assert windows_virtual(True, 243, "Some Mobile Broadband Adapter")     # not Ethernet or Wi-Fi
    # VPN drivers that register as hardware Ethernet, under their own brands
    for description in ("TAP-NordVPN Windows Adapter V9", "TAP-ProtonVPN Windows Adapter V9",
                        "TAP-Surfshark Windows Adapter V9", "ExpressVPN TAP Adapter",
                        "Private Internet Access Network Adapter",
                        "Cisco AnyConnect Secure Mobility Client Virtual Miniport Adapter",
                        "PANGP Virtual Ethernet Adapter Secure",
                        "Fortinet SSL VPN Virtual Ethernet Adapter",
                        "Juniper Networks Virtual Adapter", "Pulse Secure Network Adapter",
                        "Mullvad Wintun Adapter", "WireGuard Tunnel", "Tailscale Tunnel",
                        "ZeroTier Virtual Port", "Microsoft Hyper-V Network Adapter"):
        assert windows_virtual(True, 6, description), description
    assert not windows_virtual(True, 6, "Realtek USB GbE Family Controller")   # a USB dongle
    assert not windows_virtual(True, 71, "Intel(R) Wi-Fi 6E AX211 160MHz")


@WINDOWS_ONLY
def test_virtual_interface_names_are_matched_in_any_case_on_windows():
    for name in ("Tailscale", "NordLynx", "ProtonVPN", "vEthernet (WSL)", "ZT-Office", "WG0"):
        assert is_virtual(Interface(name, "198.51.100.5", 24, None)), name
    for name in ("Wi-Fi", "Ethernet", "Ethernet 2", "WLAN"):
        assert not is_virtual(Interface(name, "198.51.100.5", 24, None)), name


def test_proc_net_parsing():
    assert parse_default_gateways(ROUTE) == {"wlan0": "192.0.2.1"}
    assert parse_arp(ARP) == {("192.0.2.1", "wlan0"): "00:00:5e:00:53:01"}
    assert parse_default_gateways("") == {} and parse_arp("garbage") == {}
