"""Tests of capture.py with synthetic pcap files (Ethernet, IPv4, TCP and UDP, DNS)."""

from __future__ import annotations

import io
import ipaddress
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import capture  # noqa: E402

GUEST = "10.0.2.15"
HOST = "10.0.2.2"
DNS = "10.0.2.3"
PUBLIC = "203.0.113.50"  # a documentation address standing in for an Internet host


def ipv4(src: str, dst: str, proto: int, payload: bytes) -> bytes:
    header = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), 0, 0, 64, proto, 0,
                         ipaddress.IPv4Address(src).packed, ipaddress.IPv4Address(dst).packed)
    return header + payload


def tcp(sport: int, dport: int, flags: int) -> bytes:
    return struct.pack("!HHIIBBHHH", sport, dport, 0, 0, 5 << 4, flags, 1024, 0, 0)


def udp(sport: int, dport: int, payload: bytes) -> bytes:
    return struct.pack("!HHHH", sport, dport, 8 + len(payload), 0) + payload


def dns_name(name: str) -> bytes:
    return b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\0"


def dns_query(name: str) -> bytes:
    return struct.pack("!HHHHHH", 1, 0x0100, 1, 0, 0, 0) + dns_name(name) + struct.pack("!HH", 1, 1)


def dns_answer(name: str, address: str) -> bytes:
    question = dns_name(name) + struct.pack("!HH", 1, 1)
    answer = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + ipaddress.IPv4Address(address).packed
    return struct.pack("!HHHHHH", 1, 0x8180, 1, 1, 0, 0) + question + answer


def ipv6(src: str, dst: str, proto: int, payload: bytes) -> bytes:
    return struct.pack("!IHBB16s16s", 6 << 28, len(payload), proto, 64,
                       ipaddress.IPv6Address(src).packed, ipaddress.IPv6Address(dst).packed) + payload


def ethernet6(packet: bytes) -> bytes:
    return b"\x52\x54\x00\x12\x35\x02" + b"\x52\x54\x00\x12\x34\x56" + b"\x86\xdd" + packet


def ethernet(packet: bytes) -> bytes:
    return b"\x52\x54\x00\x12\x35\x02" + b"\x52\x54\x00\x12\x34\x56" + b"\x08\x00" + packet


RADIOTAP = b"\x00\x00\x08\x00\x00\x00\x00\x00"  # version 0, length 8, no fields
STATION, ACCESS_POINT = b"\x02" + b"\x00" * 5, b"\x02\x00\x00\x00\x00\x01"


def wifi(packet: bytes, qos: bool = True, protected: bool = False, ethertype: bytes = b"\x08\x00") -> bytes:
    """An 802.11 data frame from the station to the access point, after a radiotap header (netsim's form)."""
    control = 0x88 if qos else 0x08  # QoS data or data
    flags = 0x01 | (0x40 if protected else 0)  # to the distribution system
    header = bytes([control, flags]) + b"\0\0" + ACCESS_POINT + STATION + ACCESS_POINT + b"\0\0"
    return RADIOTAP + header + (b"\0\0" if qos else b"") + capture.LLC_SNAP + ethertype + packet


BEACON = RADIOTAP + bytes([0x80, 0]) + b"\0\0" + b"\xff" * 6 + ACCESS_POINT + ACCESS_POINT + b"\0\0" + b"\0" * 12


def pcap(frames: list[bytes], linktype: int = 1, big_endian: bool = False) -> bytes:
    e = ">" if big_endian else "<"
    out = struct.pack(e + "IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, linktype)
    for i, frame in enumerate(frames):
        out += struct.pack(e + "IIII", 1000 + i, 0, len(frame), len(frame)) + frame
    return out


def session(extra: list[bytes] = ()) -> bytes:
    frames = [
        ethernet(ipv4(GUEST, HOST, 6, tcp(40000, 8765, 0x02))),  # SYN to the fake computer
        ethernet(ipv4(HOST, GUEST, 6, tcp(8765, 40000, 0x12))),  # SYN-ACK
        ethernet(ipv4(GUEST, HOST, 6, tcp(40000, 8765, 0x10))),
        ethernet(ipv4(GUEST, DNS, 17, udp(5353, 53, dns_query("connectivitycheck.gstatic.com")))),
        ethernet(ipv4(DNS, GUEST, 17, udp(53, 5353, dns_answer("connectivitycheck.gstatic.com", "198.51.100.9")))),
        ethernet(ipv4(GUEST, "198.51.100.9", 6, tcp(40001, 443, 0x02))),
        *extra,
    ]
    return pcap(frames)


class Analysis(unittest.TestCase):
    def check(self, data: bytes | list[bytes], *args: str) -> tuple[int, str]:
        captures = data if isinstance(data, list) else [data]
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for index, capture_data in enumerate(captures):
                paths.append(Path(tmp) / f"run{index}.pcap")
                paths[-1].write_bytes(capture_data)
            out = io.StringIO()
            with redirect_stdout(out):
                code = capture.main(["check", *map(str, paths), "--computer", f"{HOST}:8765", *args])
        return code, out.getvalue()

    def test_traffic_to_the_computer_dns_and_platform_passes(self):
        report = capture.analyse(session())
        self.assertEqual((GUEST,), report.guests)
        self.assertEqual(1, report.flows[("tcp", HOST, 8765)])
        self.assertEqual(["connectivitycheck.gstatic.com"], report.queried)
        self.assertFalse(report.has_wifi)
        code, output = self.check(session(), "--cellular-only")
        self.assertEqual(0, code, output)
        self.assertIn("platform", output)

    def test_an_unexpected_connection_fails(self):
        code, output = self.check(session([ethernet(ipv4(GUEST, PUBLIC, 6, tcp(40002, 443, 0x02)))]))
        self.assertEqual(1, code, "an unexpected destination fails even without a Wi-Fi capture")
        self.assertIn(f"UNEXPECTED tcp {PUBLIC}:443", output)
        self.assertEqual(0, self.check(session([ethernet(ipv4(GUEST, PUBLIC, 6, tcp(40002, 443, 0x02)))]),
                                       "--allow", f"{PUBLIC}:443", "--cellular-only")[0])

    def test_the_wifi_capture_of_netsim(self):
        wifi_guest = capture.WIFI_GUEST
        frames = [BEACON,
                  wifi(ipv4(wifi_guest, HOST, 6, tcp(42000, 8765, 0x02))),
                  wifi(ipv4(wifi_guest, DNS, 17, udp(42001, 53, dns_query("time.android.com"))), qos=False),
                  wifi(ipv4(wifi_guest, PUBLIC, 6, tcp(42002, 443, 0x02)), protected=True),  # unreadable: skipped
                  wifi(b"\0" * 28, ethertype=b"\x08\x06"),  # ARP: not IP
                  BEACON[:6]]  # a record cut short at the end of a capture still being written
        report = capture.analyse(pcap(frames, linktype=127))
        self.assertTrue(report.has_wifi)
        self.assertIn(wifi_guest, report.guests)
        self.assertEqual(1, report.flows[("tcp", HOST, 8765)])
        self.assertEqual(1, report.flows[("udp", DNS, 53)])
        self.assertNotIn(("tcp", PUBLIC, 443), report.flows)
        bare = [frame[len(RADIOTAP):] for frame in frames[1:2]]
        self.assertEqual(1, capture.analyse(pcap(bare, linktype=105)).flows[("tcp", HOST, 8765)])

    def test_a_run_is_checked_over_both_networks_and_needs_the_wifi_one(self):
        cellular = session()
        wifi_capture = pcap([BEACON, wifi(ipv4(capture.WIFI_GUEST, HOST, 6, tcp(42000, 8765, 0x02)))], linktype=127)
        code, output = self.check([cellular, wifi_capture])
        self.assertEqual(0, code, output)
        self.assertIn(capture.WIFI_GUEST, output.splitlines()[0])
        code, output = self.check(cellular)
        self.assertEqual(2, code, output)
        self.assertIn("INCOMPLETE", output)
        leak = pcap([wifi(ipv4(capture.WIFI_GUEST, PUBLIC, 6, tcp(42003, 443, 0x02)))], linktype=127)
        code, output = self.check([cellular, wifi_capture, leak])
        self.assertEqual(1, code, output)
        self.assertIn(f"UNEXPECTED tcp {PUBLIC}:443", output)

    def test_play_services_names_only_when_asked(self):
        frames = [ethernet(ipv4(GUEST, DNS, 17, udp(5353, 53, dns_query("mtalk.google.com")))),
                  ethernet(ipv4(DNS, GUEST, 17, udp(53, 5353, dns_answer("mobile-gtalk.l.google.com", PUBLIC)))),
                  ethernet(ipv4(GUEST, PUBLIC, 6, tcp(40003, 5228, 0x02)))]
        self.assertEqual(1, self.check(session(frames), "--cellular-only")[0])
        code, output = self.check(session(frames), "--cellular-only", "--play-services")
        self.assertEqual(0, code, output)
        self.assertEqual(1, self.check(session(frames), "--cellular-only", "--play-services", "--strict")[0])

    def test_dhcp_of_the_emulator_network_is_allowed(self):
        frames = [ethernet(ipv4(GUEST, HOST, 17, udp(68, 67, b"dhcp"))),
                  ethernet(ipv4(GUEST, "255.255.255.255", 17, udp(68, 67, b"dhcp")))]
        rows = {flow: verdict for verdict, flow, _, _ in
                capture.verdicts(capture.analyse(session(frames)), (HOST, 8765), [], ())}
        self.assertEqual("dhcp", rows[("udp", HOST, 67)])
        self.assertEqual("dhcp", rows[("udp", "255.255.255.255", 67)])

    def test_strict_mode_refuses_platform_traffic(self):
        self.assertEqual(1, self.check(session(), "--strict")[0])

    def test_link_types_and_byte_order(self):
        raw = [ipv4(GUEST, HOST, 6, tcp(1, 8765, 0x02))]
        for data in (pcap(raw, linktype=101), pcap(raw, linktype=101, big_endian=True),
                     pcap([b"\0" * 14 + b"\x08\x00" + raw[0]], linktype=113)):
            self.assertEqual(1, capture.analyse(data).flows[("tcp", HOST, 8765)])

    def test_both_address_families_of_the_guest_are_checked(self):
        # The API 36 images open IPv6 connections too; a guest with more IPv6 openings must not hide IPv4 flows.
        guest6, public6 = "2001:db8::15", "2001:db8::50"  # documentation stand-ins
        frames = [ethernet6(ipv6(guest6, public6, 6, tcp(41000 + i, 443, 0x02))) for i in range(3)]
        frames += [ethernet(ipv4(GUEST, HOST, 6, tcp(40000, 8765, 0x02))),
                   ethernet(ipv4(GUEST, PUBLIC, 6, tcp(40002, 443, 0x02))),
                   ethernet6(ipv6(guest6, "fec0::3", 17, udp(5353, 53, dns_query("example.org"))))]
        report = capture.analyse(pcap(frames))
        self.assertEqual((GUEST, guest6), tuple(sorted(report.guests)))
        self.assertEqual(1, report.flows[("tcp", HOST, 8765)])
        rows = {flow: verdict for verdict, flow, _, _ in capture.verdicts(report, (HOST, 8765), [], ())}
        self.assertEqual("UNEXPECTED", rows[("tcp", PUBLIC, 443)])
        self.assertEqual("UNEXPECTED", rows[("tcp", public6, 443)])
        self.assertEqual("dns", rows[("udp", "fec0::3", 53)])

    def test_a_guest_address_that_only_sends_udp_is_still_the_guest(self):
        # An address in the emulator's IPv6 prefix that never opens a TCP connection (QUIC only) is the guest too.
        guest6 = str(ipaddress.ip_address(capture.EMULATOR_HOST[1]) + 0x13)
        public6 = "2001:db8::50"
        frames = [ethernet(ipv4(GUEST, HOST, 6, tcp(40000, 8765, 0x02))),
                  ethernet6(ipv6(guest6, public6, 17, udp(41000, 443, b"quic"))),
                  ethernet6(ipv6(public6, guest6, 17, udp(443, 41000, b"quic"))),
                  ethernet(ipv4(HOST, GUEST, 6, tcp(8765, 40000, 0x12)))]
        report = capture.analyse(pcap(frames))
        self.assertIn(guest6, report.guests)
        self.assertNotIn(HOST, report.guests, "the emulator's own addresses are never the guest")
        self.assertNotIn(public6, report.guests)
        rows = {flow: verdict for verdict, flow, _, _ in capture.verdicts(report, (HOST, 8765), [], ("*",))}
        self.assertEqual("UNEXPECTED", rows[("udp", public6, 443)])
        self.assertNotIn(("udp", guest6, 41000), rows, "answers to the guest are not its flows")

    def test_discovery_dns_over_tls_and_mdns(self):
        limited, directed = capture.DISCOVERY_TARGETS
        mdns4, mdns6 = capture.MDNS
        frames = [ethernet(ipv4(GUEST, HOST, 6, tcp(40000, 8765, 0x02))),
                  ethernet(ipv4(GUEST, limited, 17, udp(40100, capture.DISCOVERY_PORT, b"{}"))),
                  ethernet(ipv4(GUEST, directed, 17, udp(40100, capture.DISCOVERY_PORT, b"{}"))),
                  ethernet(ipv4(GUEST, PUBLIC, 17, udp(40100, capture.DISCOVERY_PORT, b"{}"))),
                  ethernet(ipv4(GUEST, DNS, 6, tcp(40200, 853, 0x02))),
                  ethernet(ipv4(GUEST, mdns4[0], 17, udp(5353, mdns4[1], b"mdns")))]
        report = capture.analyse(pcap(frames))
        rows = {flow: verdict for verdict, flow, _, _ in
                capture.verdicts(report, (HOST, 8765), [], capture.DEFAULT_PLATFORM_NAMES)}
        self.assertEqual("discovery", rows[("udp", limited, capture.DISCOVERY_PORT)])
        self.assertEqual("discovery", rows[("udp", directed, capture.DISCOVERY_PORT)])
        self.assertEqual("UNEXPECTED", rows[("udp", PUBLIC, capture.DISCOVERY_PORT)], "discovery is broadcast only")
        self.assertEqual("dns", rows[("tcp", DNS, 853)])
        self.assertEqual("platform", rows[("udp", *mdns4)])
        self.assertEqual("10.0.2.255", directed)  # hygiene: test-vector (the emulator network's broadcast)
        self.assertEqual("ff02::fb", mdns6[0])
        strict = {flow: verdict for verdict, flow, _, _ in capture.verdicts(report, (HOST, 8765), [], ())}
        self.assertEqual("UNEXPECTED", strict[("udp", *mdns4)], "--strict allows no platform traffic, mDNS included")
        self.assertEqual("discovery", strict[("udp", limited, capture.DISCOVERY_PORT)])

    def test_not_a_pcap(self):
        with self.assertRaises(ValueError):
            list(capture.read_pcap(b"\x0a\x0d\x0d\x0a" + b"\0" * 40))



class Start(unittest.TestCase):
    """`capture.py start` records both networks: -tcpdump for the cellular one, netsimd --pcap for Wi-Fi."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.tmp, ignore_errors=True))
        self.netsim = self.tmp / "netsimd"
        self.netsim.mkdir()

    def test_both_captures_and_the_wifi_one_moved_next_to_the_other(self):
        commands = []

        def call(command):
            commands.append(command)
            (self.netsim / "netsim-2-qa-avd-WIFI.pcap").write_bytes(pcap([BEACON], linktype=127))
            (self.netsim / "netsim-0-qa-avd-BLUETOOTH.pcap").write_bytes(b"")
            return 0

        target = self.tmp / "evidence" / "run.pcap"
        with redirect_stdout(io.StringIO()) as out:
            code = capture.start("qa-avd", target, ["-no-window"], call=call, running=lambda: False,
                                 sleep=lambda s: None, directory=self.netsim)
        self.assertEqual(0, code, out.getvalue())
        command = commands[0]
        self.assertEqual(["-tcpdump", str(target)], command[command.index("-tcpdump"):command.index("-tcpdump") + 2])
        self.assertEqual(list(capture.NETSIM_PCAP_ARGS),
                         command[command.index("-netsim-args"):command.index("-netsim-args") + 2])
        self.assertEqual("-no-window", command[-1])
        self.assertTrue((self.tmp / "evidence" / "run-wifi.pcap").exists())
        self.assertFalse((self.netsim / "netsim-2-qa-avd-WIFI.pcap").exists(), "moved, not copied")
        self.assertFalse((self.netsim / "netsim-0-qa-avd-BLUETOOTH.pcap").exists(), "netsimd's other captures go")

    def test_a_running_netsimd_would_not_record_so_nothing_starts(self):
        calls = []
        with redirect_stdout(io.StringIO()) as out:
            code = capture.start("qa-avd", self.tmp / "run.pcap", [], call=calls.append, running=lambda: True,
                                 sleep=lambda s: None, directory=self.netsim)
        self.assertEqual(2, code)
        self.assertEqual([], calls)
        self.assertIn("netsimd is already running", out.getvalue())

    def test_no_wifi_capture_is_reported(self):
        stale = self.netsim / "netsim-2-qa-avd-WIFI.pcap"
        stale.write_bytes(b"")
        old = 1_000_000_000
        __import__("os").utime(stale, (old, old))  # an earlier run's file is not this run's capture
        with redirect_stdout(io.StringIO()) as out:
            code = capture.start("qa-avd", self.tmp / "run.pcap", [], call=lambda c: 0, running=lambda: False,
                                 sleep=lambda s: None, directory=self.netsim)
        self.assertEqual(2, code)
        self.assertIn("no Wi-Fi capture", out.getvalue())
        self.assertEqual(Path("/e/run-wifi.pcap"), capture.wifi_path(Path("/e/run.pcap")))


if __name__ == "__main__":
    unittest.main()
