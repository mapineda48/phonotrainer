"""The configuration directory, the computer's identity and the approved
networks (server-contract 2.1, 3; S-01, S-02, D-34)."""

import json
import logging
import os
import socket
import stat

import pytest
from conftest import enable_phone_access, lan, loopback
from fastapi.testclient import TestClient
from phone_fixtures import (DOCKER, HOME_MAC, LOOPBACK_IF, TUNNEL, WLAN_CAFE, WLAN_HOME,
                            FakeClock)

from phonotrainer.jobs import JobStore
from phonotrainer.phone.config import ConfigDir, ConfigError, config_dir, runtime_info
from phonotrainer.phone.identity import (ServerIdentity, active_addresses, approvable,
                                         library_id, matches, network_entry)
from phonotrainer.phone.netinfo import Interface, parse_arp, parse_default_gateways
from phonotrainer.server import create_app


def mode(path) -> int:
    return stat.S_IMODE(os.stat(path).st_mode)


# --- where it is -----------------------------------------------------------------------------

def test_directory_resolution(tmp_path, monkeypatch):
    env = {"PHONOTRAINER_CONFIG_DIR": str(tmp_path / "explicit"),
           "XDG_CONFIG_HOME": str(tmp_path / "xdg")}
    assert config_dir(env) == tmp_path / "explicit"
    del env["PHONOTRAINER_CONFIG_DIR"]
    assert config_dir(env) == tmp_path / "xdg" / "phonotrainer"
    env["XDG_CONFIG_HOME"] = "relative/xdg"            # the XDG spec ignores it
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    assert config_dir(env) == tmp_path / "home" / ".config" / "phonotrainer"
    assert config_dir({}) == tmp_path / "home" / ".config" / "phonotrainer"


# --- modes and ownership ----------------------------------------------------------------------

def test_created_0700_and_files_0600(isolated_config):
    config = ConfigDir(isolated_config)
    assert not isolated_config.exists()
    config.write_json("devices.json", {"devices": []})
    config.write_json("server.json", {})
    assert mode(isolated_config) == 0o700
    assert mode(isolated_config / "devices.json") == 0o600
    assert mode(isolated_config / "server.json") == 0o600


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


def test_a_directory_owned_by_another_user_is_refused(isolated_config, monkeypatch):
    config = ConfigDir(isolated_config)
    config.ensure()
    real_uid = os.getuid()
    monkeypatch.setattr(os, "getuid", lambda: real_uid + 1)
    with pytest.raises(ConfigError) as err:
        ConfigDir(isolated_config).read_json("server.json")
    assert "another user" in str(err.value)
    assert str(isolated_config) not in str(err.value)


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


def test_proc_net_parsing():
    assert parse_default_gateways(ROUTE) == {"wlan0": "192.0.2.1"}
    assert parse_arp(ARP) == {("192.0.2.1", "wlan0"): "00:00:5e:00:53:01"}
    assert parse_default_gateways("") == {} and parse_arp("garbage") == {}
