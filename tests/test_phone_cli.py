"""`phonotrainer ui --phone-access` and `phonotrainer phone on|off|status|pair`
(server-contract 2.1, 2.2; S-01, S-04).

Milestone A covers what the scripted smoke of server-contract 11.4 needs; the
rest of the `phone` group (list, revoke, name) arrives with milestone B.
"""

import json
import os
import re

import pytest
from click.testing import CliRunner
from phone_fixtures import FakeClock, FakeNetinfo

from phonotrainer.cli import main
from phonotrainer.phone import cli as phone_cli
from phonotrainer.phone.config import ConfigDir
from phonotrainer.phone.identity import ServerIdentity
from phonotrainer.phone.registry import DeviceRegistry, PairingError
from phonotrainer.phone.settings import PhoneSettings


@pytest.fixture
def netinfo(monkeypatch):
    fake = FakeNetinfo()
    monkeypatch.setattr(phone_cli, "real_interfaces", fake)
    return fake


@pytest.fixture
def served(monkeypatch):
    calls = []
    monkeypatch.setattr("phonotrainer.server.serve", lambda **kw: calls.append(kw))
    return calls


@pytest.fixture
def config(isolated_config):
    return ConfigDir(isolated_config)


def run(*args):
    result = CliRunner().invoke(main, list(args), catch_exceptions=False)
    return result


def identity(config):
    ident = ServerIdentity(config, FakeClock())
    ident.load()
    return ident


def fake_running_server(config, port=8000, state="on"):
    config.write_json("runtime.json", {"pid": os.getpid(), "port": port,
                                       "started_at": "2026-10-10T09:00:00+00:00",
                                       "state": state})


# --- ui --------------------------------------------------------------------------------------

def test_ui_phone_access_is_remembered_and_served(served, netinfo, config):
    result = run("ui", "--no-open", "--phone-access")
    assert result.exit_code == 0, result.output
    assert identity(config).phone_access is True
    assert [n["interface"] for n in identity(config).approved_networks] == ["wlan0"]
    (call,) = served
    assert isinstance(call["phone"], PhoneSettings)
    assert call["phone"].config_dir == config.path
    assert call["host"] is None                           # serve binds 0.0.0.0
    assert "PhonoTrainer UI → http://127.0.0.1:8000" in result.output
    assert "Phone access is ON: paired phones on this Wi-Fi can reach this computer at" \
        in result.output
    assert "  192.0.2.10:8000   (wlan0)" in result.output
    assert "Pair a phone:            phonotrainer phone pair" in result.output
    # remembered: the next start without the flag has it on too
    served.clear()
    run("ui", "--no-open")
    assert isinstance(served[0]["phone"], PhoneSettings)


def test_ui_no_phone_access_turns_it_off(served, netinfo, config):
    run("ui", "--no-open", "--phone-access")
    served.clear()
    result = run("ui", "--no-open", "--no-phone-access")
    assert result.exit_code == 0
    assert served[0]["phone"] is None
    assert identity(config).phone_access is False
    assert identity(config).approved_networks == []
    assert "Phone access" not in result.output


def test_ui_never_chosen_is_off_and_creates_nothing(served, netinfo, config):
    result = run("ui", "--no-open")
    assert result.exit_code == 0
    assert served[0]["phone"] is None
    assert not config.path.exists()


def test_ui_phone_interface_approves_a_virtual_interface(served, netinfo, config):
    run("ui", "--no-open", "--phone-access", "--phone-interface", "tun0")
    assert [n["interface"] for n in identity(config).approved_networks] == ["wlan0", "tun0"]


def test_ui_on_an_unapproved_network_says_suspended(served, netinfo, config):
    run("ui", "--no-open", "--phone-access")
    netinfo.at_cafe()
    result = run("ui", "--no-open")
    assert "this computer is not on a network you approved for phones" in result.output
    assert "Run `phonotrainer phone on` here to approve this network." in result.output
    assert isinstance(served[-1]["phone"], PhoneSettings)


def test_ui_bound_to_one_address_says_who_can_reach_it(served, netinfo, config):
    result = run("ui", "--no-open", "--phone-access", "--host", "127.0.0.1")
    assert ("Phone access is ON, but this server listens on 127.0.0.1 only: phones cannot "
            "reach it.") in result.output
    assert served[-1]["host"] == "127.0.0.1"
    result = run("ui", "--no-open", "--host", "192.0.2.10")
    assert "  192.0.2.10:8000   (wlan0)" in result.output


def test_serve_with_phone_access(uvicorn_calls, monkeypatch, tmp_path, isolated_config):
    from phonotrainer import server

    monkeypatch.setattr(server, "create_app", lambda *a, **kw: ("app", a, kw))
    settings = PhoneSettings(config_dir=isolated_config)
    server.serve(workspace=tmp_path / "ws", open_browser=False, phone=settings)
    (app, kw), = uvicorn_calls
    assert kw == {"host": "0.0.0.0", "port": 8000, "reload": False, "factory": False,
                  "proxy_headers": False, "access_log": False}
    assert app[2]["phone"].port == 8000
    uvicorn_calls.clear()
    server.serve(workspace=tmp_path / "ws", open_browser=False)
    (app, kw), = uvicorn_calls
    # phone access off: as before, except that proxy headers are never trusted
    assert kw == {"host": "127.0.0.1", "port": 8000, "reload": False, "factory": False,
                  "proxy_headers": False}
    assert "phone" not in app[2] or app[2]["phone"] is None


@pytest.mark.parametrize("phone_on", [True, False])
def test_serve_with_reload_never_trusts_proxy_headers(uvicorn_calls, tmp_path, isolated_config,
                                                      phone_on):
    from phonotrainer import server

    phone = PhoneSettings(config_dir=isolated_config) if phone_on else None
    server.serve(workspace=tmp_path / "ws", open_browser=False, reload=True, phone=phone)
    (app, kw), = uvicorn_calls
    assert app == "phonotrainer.server:app_from_env"
    assert kw["reload"] is True and kw["factory"] is True
    assert kw["proxy_headers"] is False


# --- phone on / off / status ------------------------------------------------------------------

def test_phone_on_and_off(netinfo, config):
    result = run("phone", "on")
    assert result.exit_code == 0
    assert result.output.splitlines() == ["Approved for phones: wlan0 192.0.2.0/24",
                                          "Phone access is now ON.",
                                          "Restart `phonotrainer ui` to apply it."]
    fake_running_server(config)
    assert "Restart" not in run("phone", "on").output
    result = run("phone", "off")
    assert result.output.strip() == "Phone access is now OFF."
    assert identity(config).approved_networks == []


def test_phone_status(netinfo, config):
    result = run("phone", "status")
    assert "Phone access: OFF" in result.output
    assert 'Computer name: "PhonoTrainer computer"' in result.output
    assert re.search(r"Server id: srv_[a-z2-7]{4}…", result.output)
    assert "Server: not running with phone access" in result.output
    run("phone", "on")
    fake_running_server(config, port=8123)
    result = run("phone", "status")
    assert "Phone access: ON" in result.output
    assert "  wlan0 192.0.2.0/24" in result.output
    assert "Paired phones: 0" in result.output
    assert "Server: running (phone access on), port 8123" in result.output
    netinfo.offline()
    assert "Phone access: SUSPENDED" in run("phone", "status").output


def test_a_stale_runtime_file_is_not_a_running_server(netinfo, config):
    run("phone", "on")
    config.write_json("runtime.json", {"pid": 2 ** 22 + 12345, "port": 8000, "state": "on"})
    assert "Server: not running with phone access" in run("phone", "status").output


# --- phone pair ----------------------------------------------------------------------------------

def test_pair_with_phone_access_off_shows_no_code(netinfo, config):
    result = run("phone", "pair", "--no-wait")
    assert result.exit_code == 1
    assert result.output.strip() == "Phone access is OFF. Turn it on with: phonotrainer phone on"
    assert not (config.path / "pairing.json").exists()


def test_pair_without_a_running_server_shows_no_code(netinfo, config):
    run("phone", "on")
    result = run("phone", "pair", "--no-wait")
    assert result.exit_code == 1
    assert result.output.strip() == ("PhonoTrainer is not running with phone access on. "
                                     "Start it with: phonotrainer ui --phone-access")
    assert not (config.path / "pairing.json").exists()


def test_pair_no_wait_shows_the_code_and_the_addresses(netinfo, config):
    run("phone", "on")
    fake_running_server(config, port=8000)
    result = run("phone", "pair", "--no-wait")
    assert result.exit_code == 0
    lines = result.output.splitlines()
    match = re.fullmatch(r"Pairing code: (\d{3}) (\d{3})   \(valid for 5 minutes, single use\)",
                         lines[0])
    assert match
    assert lines[1] == ('On the phone: Connect to your computer, pick "PhonoTrainer computer", '
                        "or enter one of these addresses:")
    assert lines[2:] == ["  192.0.2.10:8000   (wlan0)"]
    code = match.group(1) + match.group(2)
    stored = (config.path / "pairing.json").read_text()
    assert code not in stored
    salt_hash = json.loads(stored)
    assert salt_hash["salt"] not in result.output and salt_hash["code_sha256"] not in result.output
    # the code shown is the one the server accepts (the CLI used the real clock)
    import time

    device, token = DeviceRegistry(config, time.time).pair(code, "Pixel 9", 1, "1.0.0",
                                                           "192.0.2.23")
    assert device.name == "Pixel 9" and token not in result.output


def test_waiting_prints_wrong_attempts_and_the_pairing(config, capsys):
    clock = FakeClock()
    registry = DeviceRegistry(config, clock)
    code = registry.create_code()
    wrong = f"{(int(code.code) + 1) % 10 ** 6:06d}"
    script = iter([
        lambda: _attempt(registry, wrong, "192.0.2.21"),
        lambda: _attempt(registry, wrong, "192.0.2.21"),
        lambda: registry.pair(code.code, "Pixel 9", 1, "1.0.0", "192.0.2.23"),
    ])
    status = phone_cli.wait_for_pairing(registry, code.expires_at, clock=clock,
                                        sleep=lambda s: next(script)())
    out = capsys.readouterr().out.splitlines()
    assert status == 0
    assert out == ["Wrong code tried 1 time (another device on this network may be guessing).",
                   "Wrong code tried 2 times (another device on this network may be guessing).",
                   "Paired: Pixel 9 (from 192.0.2.23)"]
    assert not (config.path / "pairing.json").exists()     # the address is gone


def _attempt(registry, code, source):
    try:
        registry.pair(code, "Rogue", 1, None, source)
    except PairingError:
        pass


def test_waiting_exits_4_when_locked_and_3_when_expired(config, capsys):
    clock = FakeClock()
    registry = DeviceRegistry(config, clock)
    code = registry.create_code()
    wrong = f"{(int(code.code) + 1) % 10 ** 6:06d}"
    for i in range(5):
        _attempt(registry, wrong, f"192.0.2.{30 + i}")
    assert phone_cli.wait_for_pairing(registry, code.expires_at, clock=clock,
                                      sleep=lambda s: None) == 4
    assert "locked after 5 wrong attempts" in capsys.readouterr().out
    code = registry.create_code()

    def later(seconds):
        clock.advance(100)

    assert phone_cli.wait_for_pairing(registry, code.expires_at, clock=clock, sleep=later) == 3
    assert "expired" in capsys.readouterr().out
