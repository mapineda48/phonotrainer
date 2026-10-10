"""Paired phones, keys, pairing codes, proofs and rate limits
(server-contract 2.3, 3.2, 6.2; S-04, F-02-AC3)."""

import hashlib
import json
import re

import pytest
from phone_fixtures import (REFERENCE_CHALLENGE, REFERENCE_CODE, REFERENCE_DEVICE_ID,
                            REFERENCE_HELLO_PROOF, REFERENCE_KEY_HEX,
                            REFERENCE_REACTIVATION_PROOF, REFERENCE_SERVER_ID,
                            REFERENCE_TOKEN, FakeClock)

from phonotrainer.phone.config import ConfigDir
from phonotrainer.phone.identity import ServerIdentity, clean_name, clean_text
from phonotrainer.phone.registry import (DeviceRegistry, PairingError, RateLimiter,
                                         hello_proof, reactivation_proof, token_key)


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def config(isolated_config):
    return ConfigDir(isolated_config)


@pytest.fixture
def registry(config, clock):
    return DeviceRegistry(config, clock)


def pair(registry, code, source="192.0.2.20", name="Pixel 9", **kw):
    return registry.pair(code, name, 1, "1.0.0", source, **kw)


def wrong_of(code: str) -> str:
    return f"{(int(code) + 1) % 10 ** 6:06d}"


# --- codes ---------------------------------------------------------------------------------

def test_a_code_is_six_digits_stored_only_as_a_salted_hash(registry, config):
    code = registry.create_code()
    assert re.fullmatch(r"[0-9]{6}", code.code)
    assert code.grouped == f"{code.code[:3]} {code.code[3:]}"
    stored = json.loads((config.path / "pairing.json").read_text())
    assert code.code not in json.dumps(stored)
    assert re.fullmatch(r"[0-9a-f]{32}", stored["salt"])
    assert stored["code_sha256"] == hashlib.sha256(
        bytes.fromhex(stored["salt"]) + code.code.encode()).hexdigest()
    assert stored["wrong_attempts"] == 0 and stored["locked"] is False
    assert stored["expires_at"] == "2026-10-10T09:05:00+00:00"   # 300 s after the clock
    assert (config.path / "pairing.json").stat().st_mode & 0o777 == 0o600


def test_a_code_is_single_use(registry):
    code = registry.create_code().code
    pair(registry, code)
    with pytest.raises(PairingError) as err:
        pair(registry, code, source="192.0.2.21")
    assert err.value.code == "code_invalid" and err.value.status == 403


def test_a_code_expires_after_300_seconds(registry, clock):
    code = registry.create_code().code
    clock.advance(299)
    device, _ = pair(registry, code)
    assert device
    code = registry.create_code().code
    clock.advance(300)
    with pytest.raises(PairingError) as err:
        pair(registry, code)
    assert err.value.code == "code_expired"


def test_five_wrong_attempts_lock_the_code(registry, config):
    code = registry.create_code().code
    for attempt in range(1, 6):
        with pytest.raises(PairingError) as err:
            pair(registry, wrong_of(code), source=f"192.0.2.{20 + attempt}")
        assert err.value.code == "code_invalid"
        assert json.loads((config.path / "pairing.json").read_text())["wrong_attempts"] \
            == attempt
    assert json.loads((config.path / "pairing.json").read_text())["locked"] is True
    with pytest.raises(PairingError) as err:
        pair(registry, code)                          # the right digits, the sixth attempt
    assert err.value.code == "code_locked"
    # a new code starts over
    fresh = registry.create_code().code
    assert pair(registry, fresh)[0]


def test_no_code_answers_code_invalid(registry):
    with pytest.raises(PairingError) as err:
        pair(registry, "482913")
    assert err.value.code == "code_invalid"


def test_a_new_code_replaces_the_previous_one(registry):
    old = registry.create_code().code
    new = registry.create_code().code
    if old != new:
        with pytest.raises(PairingError):
            pair(registry, old)
    assert pair(registry, new)[0]


def test_rate_limits_per_source_and_in_total(registry, clock):
    for _ in range(10):
        with pytest.raises(PairingError):
            pair(registry, "482913", source="192.0.2.20")
    with pytest.raises(PairingError) as err:
        pair(registry, "482913", source="192.0.2.20")
    assert err.value.code == "rate_limited" and err.value.status == 429
    assert 1 <= err.value.retry_after <= 60
    for source in ("192.0.2.21", "192.0.2.22"):
        for _ in range(10):
            with pytest.raises(PairingError) as err:
                pair(registry, "482913", source=source)
            assert err.value.code == "code_invalid"
    with pytest.raises(PairingError) as err:               # 31st in a minute
        pair(registry, "482913", source="192.0.2.23")
    assert err.value.code == "rate_limited"
    clock.advance(60)
    with pytest.raises(PairingError) as err:
        pair(registry, "482913", source="192.0.2.20")
    assert err.value.code == "code_invalid"


def test_an_old_app_consumes_nothing(registry, monkeypatch):
    monkeypatch.setattr("phonotrainer.phone.registry.MIN_APP_VERSION", 3)
    code = registry.create_code().code
    with pytest.raises(PairingError) as err:
        registry.pair(code, "Pixel 9", 2, "0.9", "192.0.2.20")
    assert err.value.code == "app_too_old" and err.value.status == 426
    assert registry.pair(code, "Pixel 9", 3, "1.2", "192.0.2.20")[0]


def test_the_rate_limiter_window():
    clock = FakeClock()
    limiter = RateLimiter(2, 3, 60, clock)
    assert limiter.hit("a") is None and limiter.hit("a") is None
    assert limiter.hit("a") == 60
    clock.advance(10)
    assert limiter.hit("b") is None
    assert limiter.hit("c") == 50                          # the total, oldest at t=0
    clock.advance(50)
    assert limiter.hit("a") is None


# --- devices -------------------------------------------------------------------------------

def test_the_token_is_never_stored_only_its_key(registry, config):
    code = registry.create_code().code
    device, token = pair(registry, code)
    assert re.fullmatch(r"pt1_[A-Za-z0-9_-]{43}", token)
    text = (config.path / "devices.json").read_text()
    assert token not in text
    row = json.loads(text)["devices"][0]
    assert row["key_sha256"] == hashlib.sha256(token.encode()).hexdigest()
    assert set(row) == {"device_id", "name", "key_sha256", "paired_at", "last_seen_at",
                        "app_version", "app_version_name", "revoked_at"}
    assert row["name"] == "Pixel 9" and row["app_version"] == 1
    assert row["app_version_name"] == "1.0.0" and row["revoked_at"] is None
    assert (config.path / "devices.json").stat().st_mode & 0o777 == 0o600
    assert REFERENCE_KEY_HEX not in repr(device) and row["key_sha256"] not in repr(device)


def test_revoke_one_and_all(registry):
    devices = [pair(registry, registry.create_code().code, name=f"Phone {i}")[0]
               for i in range(3)]
    assert registry.revoke(devices[0].device_id) is True
    assert registry.revoke(devices[0].device_id) is False
    assert registry.active(devices[0].device_id) is None
    assert registry.get(devices[0].device_id).revoked
    assert len(registry.devices()) == 2
    assert registry.revoke_all() == 2
    assert registry.devices() == []
    assert len(registry.devices(include_revoked=True)) == 3


def test_reactivation_with_a_valid_proof_keeps_the_id(registry):
    device, token = pair(registry, registry.create_code().code)
    registry.revoke(device.device_id)
    code = registry.create_code().code
    again, new_token = pair(registry, code, previous_device_id=device.device_id,
                            previous_token_proof=reactivation_proof(token_key(token), code))
    assert again.device_id == device.device_id
    assert not registry.get(device.device_id).revoked
    assert registry.get(device.device_id).key == token_key(new_token)
    assert registry.get(device.device_id).key != token_key(token)     # the old key is dead
    assert len(registry.devices(include_revoked=True)) == 1


@pytest.mark.parametrize("proof", ["0" * 64, None, "upper"])
def test_a_wrong_or_missing_proof_creates_a_new_device(registry, proof):
    device, token = pair(registry, registry.create_code().code)
    code = registry.create_code().code
    if proof == "upper":       # a proof for another code
        proof = reactivation_proof(token_key(token), wrong_of(code))
    again, _ = pair(registry, code, previous_device_id=device.device_id,
                    previous_token_proof=proof)
    assert again.device_id != device.device_id
    assert registry.get(device.device_id).key == token_key(token)     # untouched


def test_reactivating_an_unknown_device_creates_a_new_one(registry):
    code = registry.create_code().code
    device, _ = pair(registry, code, previous_device_id="dev_gone",
                     previous_token_proof="a" * 64)
    assert device.device_id != "dev_gone"


def test_the_reference_vectors():
    key = token_key(REFERENCE_TOKEN)
    assert key.hex() == REFERENCE_KEY_HEX
    assert hello_proof(key, REFERENCE_SERVER_ID, REFERENCE_DEVICE_ID, REFERENCE_CHALLENGE) \
        == REFERENCE_HELLO_PROOF
    assert reactivation_proof(key, REFERENCE_CODE) == REFERENCE_REACTIVATION_PROOF


def test_last_seen_is_written_at_most_once_a_minute(registry, clock, config):
    device, _ = pair(registry, registry.create_code().code)
    paired_at = registry.get(device.device_id).last_seen_at
    clock.advance(59)
    registry.touch(device.device_id)
    assert registry.get(device.device_id).last_seen_at == paired_at
    before = (config.path / "devices.json").stat().st_mtime_ns
    clock.advance(1)
    registry.touch(device.device_id)
    assert registry.get(device.device_id).last_seen_at == "2026-10-10T09:01:00+00:00"
    assert (config.path / "devices.json").stat().st_mtime_ns >= before
    # a fresh process does not write again within the minute either
    other = DeviceRegistry(config, clock)
    stamp = config.stat_key("devices.json")
    clock.advance(30)
    other.touch(device.device_id)
    assert config.stat_key("devices.json") == stamp


def test_another_process_sees_changes(config, clock):
    first, second = DeviceRegistry(config, clock), DeviceRegistry(config, clock)
    device, _ = pair(first, first.create_code().code)
    assert second.active(device.device_id) is not None
    second.revoke(device.device_id)
    assert first.active(device.device_id) is None


# --- names ---------------------------------------------------------------------------------

@pytest.mark.parametrize("raw, clean", [
    ("  Pixel 9  ", "Pixel 9"),
    ("Pixel‮ 9", "Pixel 9"),              # right-to-left override (Cf)
    ("Pi​xel", "Pixel"),                  # zero-width space (Cf)
    ("Pixel\n9\r", "Pixel9"),                  # control characters (Cc)
    ("Pixel  9", "Pixel9"),          # line and paragraph separators
    ("Galaxy A35 — Zoë", "Galaxy A35 — Zoë"),
])
def test_cc_cf_zl_zp_are_stripped_from_names(registry, raw, clean):
    assert clean_text(raw) == clean
    device, _ = pair(registry, registry.create_code().code, name=raw)
    assert registry.get(device.device_id).name == clean


def test_computer_names(config, clock):
    identity = ServerIdentity(config, clock)
    identity.load()
    assert identity.rename(" Study‮ desk ") == "Study desk"
    assert identity.name == "Study desk"
    for bad in ("", "​", "x" * 41):
        with pytest.raises(ValueError):
            clean_name(bad)
