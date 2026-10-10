"""UDP discovery (server-contract 5; S-03).

The datagrams are checked byte by byte; the responder's rules (private
sources inside an approved network, rate limits) with a fake clock; and one
real responder on 127.0.0.1 with an ephemeral port answers a real probe. No
test ever binds the product port 45317 or a non-loopback address.
"""

import dataclasses
import json
import socket

import pytest
from conftest import enable_phone_access, loopback, paired_client, wait_until
from fastapi.testclient import TestClient

from phonotrainer.jobs import JobStore
from phonotrainer.phone import DISCOVERY_PORT
from phonotrainer.phone.discovery import (MAX_PROBE_BYTES, DiscoveryResponder, make_reply,
                                          parse_probe, source_allowed)
from phonotrainer.phone.netinfo import Interface
from phonotrainer.server import API_VERSION, create_app

NONCE = "6f1c2a9e4b7d3f80"
PROBE = json.dumps({"t": "phonotrainer-probe", "v": 1, "nonce": NONCE}).encode()

# A home network in a private range, a container bridge and a source outside
# every private range. None of these is contacted: the rules are pure
# functions, and the real socket below only speaks to 127.0.0.1.
# hygiene: test-vector begin
HOME = Interface("wlan0", "10.77.1.10", 24, "00:00:5e:00:53:01")
BRIDGE = Interface("docker0", "10.88.0.1", 16, None)
LINK_LOCAL = Interface("eth0", "169.254.10.1", 16, None)
PHONE = "10.77.1.23"
CONTAINER = "10.88.0.5"
ELSEWHERE_PRIVATE = "10.99.0.7"
PUBLIC = "1.2.3.4"
LINK_LOCAL_PHONE = "169.254.10.20"
WIDE_HOME = Interface("wlan0", "10.77.0.1", 16, None)          # room for 51 sources
# hygiene: test-vector end


def probe(**fields) -> bytes:
    body = {"t": "phonotrainer-probe", "v": 1, "nonce": NONCE}
    body.update(fields)
    return json.dumps({k: v for k, v in body.items() if v is not None}).encode()


def responder(clock, active=(HOME,), **kwargs) -> DiscoveryResponder:
    return DiscoveryResponder(0, lambda: ("srv_example1", "Study desk"), 8000, API_VERSION,
                              lambda: list(active), clock, host="127.0.0.1", **kwargs)


# --- the datagrams (5.2, 5.3) ---------------------------------------------------------------

def test_the_exact_probe_is_accepted_and_extra_fields_are_ignored():
    assert parse_probe(PROBE) == NONCE
    assert parse_probe(probe(extra="ignored", more={"a": 1})) == NONCE
    assert parse_probe(probe(nonce="a" * 64)) == "a" * 64
    assert parse_probe(bytearray(PROBE)) == NONCE


@pytest.mark.parametrize("data", [
    b"",
    b"not json",
    b"\xff\xfe\x00",                                   # not UTF-8
    json.dumps(["phonotrainer-probe", 1, NONCE]).encode(),
    json.dumps("phonotrainer-probe").encode(),
    probe(t="phonotrainer-here"),
    probe(t="PHONOTRAINER-PROBE"),
    probe(t=None),
    probe(v=2),
    probe(v="1"),
    probe(v=1.0),
    probe(v=True),
    probe(v=None),
    probe(nonce=None),
    probe(nonce=NONCE.upper()),
    probe(nonce="a" * 15),
    probe(nonce="a" * 65),
    probe(nonce="g" * 16),
    probe(nonce=12345678901234567),
    probe(pad="x" * MAX_PROBE_BYTES),                  # oversized, even if well formed
])
def test_anything_else_is_dropped(data):
    assert parse_probe(data) is None


def test_the_reply_holds_only_the_four_fields_and_the_nonce():
    reply = json.loads(make_reply(NONCE, "srv_example1", "Study desk", 8000, 6))
    assert reply == {"t": "phonotrainer-here", "v": 1, "nonce": NONCE, "server_id": "srv_example1",
                     "name": "Study desk", "http_port": 8000, "api_version": 6}


def test_the_port_is_the_products_constant():
    assert DISCOVERY_PORT == 45317


# --- who gets an answer (5.4) ---------------------------------------------------------------

def test_only_private_sources_inside_an_approved_network_get_a_reply(fake_clock):
    assert source_allowed(PHONE, [HOME])
    assert source_allowed(LINK_LOCAL_PHONE, [LINK_LOCAL])
    assert not source_allowed(CONTAINER, [HOME])                 # a container bridge
    assert not source_allowed(ELSEWHERE_PRIVATE, [HOME])         # private, but not approved
    assert not source_allowed(PUBLIC, [Interface("wan0", PUBLIC, 8, None)])   # not private
    assert not source_allowed("192.0.2.23", [Interface("wlan0", "192.0.2.10", 24, None)])
    assert not source_allowed("127.0.0.1", [HOME])
    assert not source_allowed("not an address", [HOME])
    assert not source_allowed(PHONE, [])                         # suspended: nothing approved

    answering = responder(fake_clock)
    assert json.loads(answering.answer(PROBE, PHONE))["nonce"] == NONCE
    for source in (CONTAINER, ELSEWHERE_PRIVATE, PUBLIC, "127.0.0.1"):
        assert answering.answer(PROBE, source) is None
    assert answering.answer(b"garbage", PHONE) is None
    # the container bridge is answered once the operator approved it by name
    assert responder(fake_clock, active=(HOME, BRIDGE)).answer(PROBE, CONTAINER) is not None


def test_the_reply_reads_the_current_name(fake_clock):
    names = iter(["PhonoTrainer computer", "Study desk"])
    answering = DiscoveryResponder(0, lambda: ("srv_example1", next(names)), 8000, 6,
                                   lambda: [HOME], fake_clock)
    assert json.loads(answering.answer(PROBE, PHONE))["name"] == "PhonoTrainer computer"
    assert json.loads(answering.answer(PROBE, PHONE))["name"] == "Study desk"


def test_rate_limits_per_source_and_in_total(fake_clock):
    answering = responder(fake_clock, active=(WIDE_HOME,))
    replies = [answering.answer(PROBE, PHONE) for _ in range(6)]
    assert all(replies[:5]) and replies[5] is None               # 5 per second per source
    fake_clock.advance(1.0)
    assert answering.answer(PROBE, PHONE) is not None

    fake_clock.advance(1.0)
    total = responder(fake_clock, active=(WIDE_HOME,))
    sources = [f"10.77.{i // 200}.{i % 200 + 2}" for i in range(51)]   # hygiene: test-vector
    answered = [total.answer(PROBE, source) is not None for source in sources]
    assert answered == [True] * 50 + [False]                     # 50 per second in total
    fake_clock.advance(1.0)
    assert total.answer(PROBE, sources[-1]) is not None


# --- a real socket ----------------------------------------------------------------------------

def udp_exchange(port: int, data: bytes, timeout: float = 5.0) -> bytes | None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
        client.bind(("127.0.0.1", 0))
        client.settimeout(timeout)
        client.sendto(data, ("127.0.0.1", port))
        try:
            return client.recvfrom(4096)[0]
        except socket.timeout:
            return None


def test_a_real_responder_answers_a_real_probe(fake_clock):
    # Loopback is never a phone's source; this responder lets it through so
    # that one real datagram can make the round trip on this machine.
    real = responder(fake_clock, allowed=lambda source, active: source == "127.0.0.1")
    assert real.start()
    try:
        assert real.bound_port and real.bound_port != DISCOVERY_PORT
        reply = udp_exchange(real.bound_port, PROBE)
        assert reply is not None
        assert json.loads(reply) == {"t": "phonotrainer-here", "v": 1, "nonce": NONCE,
                                     "server_id": "srv_example1", "name": "Study desk",
                                     "http_port": 8000, "api_version": API_VERSION}
        assert udp_exchange(real.bound_port, b"not a probe", timeout=0.7) is None
    finally:
        real.stop()
    assert real.bound_port is None


def test_a_busy_port_is_reported_and_nothing_breaks(fake_clock):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as taken:
        taken.bind(("127.0.0.1", 0))
        busy = DiscoveryResponder(taken.getsockname()[1], lambda: ("srv_example1", "x"), 8000, 6,
                                  lambda: [HOME], fake_clock, host="127.0.0.1")
        assert busy.start() is False
        busy.stop()                                              # harmless


# --- the responder follows phone access (5.1) -----------------------------------------------

@pytest.fixture
def make_app(phone_settings, phone_store, tmp_path):
    """create_app with phone settings that carry a listening port (as `serve`
    sets it), its lifespan running; returns the app."""
    opened = []

    def make(**changes):
        settings = dataclasses.replace(phone_settings, port=8000, **changes)
        app = create_app(store=phone_store, phone=settings, web_dist=tmp_path / "not-built",
                         allowed_roots=[tmp_path])
        client = TestClient(app)
        client.__enter__()
        opened.append(client)
        return app

    yield make
    for client in opened:
        client.__exit__(None, None, None)


def test_the_responder_runs_while_phone_access_is_on(make_app, phone_settings, fake_clock):
    enable_phone_access(phone_settings)
    app = make_app(host="0.0.0.0")
    runtime = app.state.phone
    assert runtime.state == "on"
    assert runtime.responder is not None and runtime.capabilities.discovery is True
    port = runtime.responder.bound_port
    assert port and port != DISCOVERY_PORT and runtime.responder.host == "127.0.0.1"
    signing, _ = paired_client(app, fake_clock)
    assert signing.get("/api/phone/health").json()["capabilities"]["discovery"] is True
    # FakeNetinfo's approved network is a documentation range, never answered;
    # with the source rule lifted, the reply carries this server's identity and port
    assert runtime.responder.answer(PROBE, "192.0.2.23") is None
    runtime.responder.allowed = lambda source, active: True
    reply = json.loads(runtime.responder.answer(PROBE, PHONE))
    assert reply["server_id"] == runtime.identity.server_id
    assert reply["name"] == runtime.identity.name == "PhonoTrainer computer"
    assert reply["http_port"] == 8000 and reply["api_version"] == API_VERSION

    # switched off live: the responder stops (the monitor follows within a second)
    runtime.identity.turn_off()
    wait_until(lambda: runtime.responder is None, timeout=10)
    assert runtime.capabilities.discovery is False
    assert udp_exchange(port, PROBE, timeout=0.5) is None


def test_not_started_while_suspended_or_off(make_app, phone_settings, fake_netinfo):
    enable_phone_access(phone_settings)
    fake_netinfo.offline()                                       # suspended: no approved network up
    app = make_app()
    assert app.state.phone.state == "suspended"
    assert app.state.phone.responder is None
    assert app.state.phone.capabilities.discovery is False


@pytest.mark.parametrize("host", ["127.0.0.1", "::1"])
def test_nothing_is_announced_by_a_server_bound_to_loopback(make_app, phone_settings, host):
    enable_phone_access(phone_settings)
    app = make_app(host=host)                      # `ui --host 127.0.0.1 --phone-access`
    assert app.state.phone.state == "on"
    assert app.state.phone.responder is None
    assert app.state.phone.capabilities.discovery is False


def test_not_started_without_phone_access(phone_store, tmp_path):
    app = create_app(store=phone_store, web_dist=tmp_path / "not-built", allowed_roots=[tmp_path])
    with TestClient(app):
        assert getattr(app.state, "phone", None) is None
        hello = loopback(app).get("/api/phone/hello")
        assert hello.json()["phone_access"] is False


def test_a_busy_port_means_discovery_false_and_the_server_runs_on(make_app, phone_settings,
                                                                  fake_clock):
    enable_phone_access(phone_settings)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as taken:
        taken.bind(("127.0.0.1", 0))
        app = make_app(discovery_port=taken.getsockname()[1])
        runtime = app.state.phone
        assert runtime.state == "on"
        assert runtime.responder is None and runtime.capabilities.discovery is False
        # still serving: hello answers in full
        hello = loopback(app).get("/api/phone/hello")
        assert hello.status_code == 200 and hello.json()["phone_access"] is True
        signing, _ = paired_client(app, fake_clock)
        health = signing.get("/api/phone/health")
        assert health.status_code == 200 and health.json()["capabilities"]["discovery"] is False
