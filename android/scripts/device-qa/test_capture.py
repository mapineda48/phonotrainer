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


def ethernet(packet: bytes) -> bytes:
    return b"\x52\x54\x00\x12\x35\x02" + b"\x52\x54\x00\x12\x34\x56" + b"\x08\x00" + packet


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
    def check(self, data: bytes, *args: str) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run.pcap"
            path.write_bytes(data)
            out = io.StringIO()
            with redirect_stdout(out):
                code = capture.main(["check", str(path), "--computer", f"{HOST}:8765", *args])
        return code, out.getvalue()

    def test_traffic_to_the_computer_dns_and_platform_passes(self):
        report = capture.analyse(session())
        self.assertEqual(GUEST, report.guest)
        self.assertEqual(1, report.flows[("tcp", HOST, 8765)])
        self.assertEqual(["connectivitycheck.gstatic.com"], report.queried)
        code, output = self.check(session())
        self.assertEqual(0, code, output)
        self.assertIn("platform", output)

    def test_an_unexpected_connection_fails(self):
        code, output = self.check(session([ethernet(ipv4(GUEST, PUBLIC, 6, tcp(40002, 443, 0x02)))]))
        self.assertEqual(1, code)
        self.assertIn(f"UNEXPECTED tcp {PUBLIC}:443", output)
        self.assertEqual(0, self.check(session([ethernet(ipv4(GUEST, PUBLIC, 6, tcp(40002, 443, 0x02)))]),
                                       "--allow", f"{PUBLIC}:443")[0])

    def test_strict_mode_refuses_platform_traffic(self):
        self.assertEqual(1, self.check(session(), "--strict")[0])

    def test_link_types_and_byte_order(self):
        raw = [ipv4(GUEST, HOST, 6, tcp(1, 8765, 0x02))]
        for data in (pcap(raw, linktype=101), pcap(raw, linktype=101, big_endian=True),
                     pcap([b"\0" * 14 + b"\x08\x00" + raw[0]], linktype=113)):
            self.assertEqual(1, capture.analyse(data).flows[("tcp", HOST, 8765)])

    def test_not_a_pcap(self):
        with self.assertRaises(ValueError):
            list(capture.read_pcap(b"\x0a\x0d\x0d\x0a" + b"\0" * 40))


if __name__ == "__main__":
    unittest.main()
