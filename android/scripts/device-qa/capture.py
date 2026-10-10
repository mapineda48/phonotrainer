#!/usr/bin/env python3
"""Captures every packet of one [emulator] run and checks where they went (architecture 21.7; spec X-AC1).

An OkHttp EventListener sees only OkHttp, so the "all traffic captured" check uses the emulator's own capture
(`emulator -tcpdump`), which records every packet of the guest's network.

Usage:
  capture.py start --avd NAME --pcap FILE [-- more emulator options]
      starts the emulator with the capture (foreground; close the emulator to end the capture)
  capture.py check FILE --computer 10.0.2.2:PORT [--allow HOST[:PORT]] [--platform-name PATTERN] [--strict]
      lists every connection the guest opened and every UDP destination, with the DNS names it looked up, and
      exits 1 when anything went somewhere other than the computer, the emulator's DNS (10.0.2.3:53) and, unless
      --strict, hosts whose looked-up name matches a platform pattern (connectivity checks, time). The app itself
      never resolves names (architecture 5.1), so platform traffic is the only named traffic expected.

Standard library only: classic pcap files (microsecond or nanosecond, either byte order) with Ethernet, raw IP or
Linux cooked link types.
"""

from __future__ import annotations

import argparse
import fnmatch
import ipaddress
import os
import struct
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

EMULATOR_DNS = ("10.0.2.3", 53)
DEFAULT_PLATFORM_NAMES = (
    "connectivitycheck.gstatic.com", "*.gstatic.com", "www.google.com", "clients*.google.com", "time.android.com",
    "*.pool.ntp.org", "time.google.com",
)


@dataclass(frozen=True)
class Packet:
    proto: str  # "tcp" or "udp"
    src: str
    sport: int
    dst: str
    dport: int
    syn: bool = False  # a TCP SYN without ACK: the opening of a connection
    payload: bytes = b""


def read_pcap(data: bytes):
    """Yields (link type, frame bytes) for each record of a classic pcap file."""
    if len(data) < 24:
        raise ValueError("not a pcap file (too short)")
    magic = data[:4]
    if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
        endian = "<"
    elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
        endian = ">"
    else:
        raise ValueError("not a classic pcap file (pcapng is not supported)")
    linktype = struct.unpack(endian + "I", data[20:24])[0] & 0x0FFFFFFF
    pos = 24
    while pos + 16 <= len(data):
        _sec, _frac, incl, _orig = struct.unpack(endian + "IIII", data[pos : pos + 16])
        pos += 16
        yield linktype, data[pos : pos + incl]
        pos += incl


def ip_payload(linktype: int, frame: bytes) -> bytes | None:
    if linktype == 1:  # Ethernet
        offset, ethertype = 14, struct.unpack("!H", frame[12:14])[0] if len(frame) >= 14 else 0
        while ethertype in (0x8100, 0x88A8) and len(frame) >= offset + 4:
            ethertype = struct.unpack("!H", frame[offset + 2 : offset + 4])[0]
            offset += 4
        return frame[offset:] if ethertype in (0x0800, 0x86DD) else None
    if linktype in (101, 12, 14):  # raw IP
        return frame
    if linktype == 113:  # Linux cooked v1
        return frame[16:] if len(frame) >= 16 and struct.unpack("!H", frame[14:16])[0] in (0x0800, 0x86DD) else None
    if linktype == 276:  # Linux cooked v2
        return frame[20:] if len(frame) >= 20 and struct.unpack("!H", frame[0:2])[0] in (0x0800, 0x86DD) else None
    if linktype == 0:  # BSD loopback
        return frame[4:]
    return None


def parse_ip(packet: bytes) -> Packet | None:
    if not packet:
        return None
    version = packet[0] >> 4
    if version == 4 and len(packet) >= 20:
        ihl = (packet[0] & 0x0F) * 4
        proto = packet[9]
        src, dst = str(ipaddress.IPv4Address(packet[12:16])), str(ipaddress.IPv4Address(packet[16:20]))
        rest = packet[ihl:]
    elif version == 6 and len(packet) >= 40:
        proto = packet[6]
        src, dst = str(ipaddress.IPv6Address(packet[8:24])), str(ipaddress.IPv6Address(packet[24:40]))
        rest = packet[40:]
    else:
        return None
    if proto == 6 and len(rest) >= 20:
        sport, dport = struct.unpack("!HH", rest[:4])
        flags = rest[13]
        offset = (rest[12] >> 4) * 4
        return Packet("tcp", src, sport, dst, dport, bool(flags & 0x02) and not flags & 0x10, rest[offset:])
    if proto == 17 and len(rest) >= 8:
        sport, dport = struct.unpack("!HH", rest[:4])
        return Packet("udp", src, sport, dst, dport, False, rest[8:])
    return None


def _dns_name(message: bytes, pos: int, depth: int = 0) -> tuple[str, int]:
    labels = []
    while pos < len(message):
        length = message[pos]
        if length == 0:
            return ".".join(labels), pos + 1
        if length & 0xC0 == 0xC0:
            if depth > 10 or pos + 1 >= len(message):
                break
            pointer = struct.unpack("!H", message[pos : pos + 2])[0] & 0x3FFF
            name, _ = _dns_name(message, pointer, depth + 1)
            labels.append(name)
            return ".".join(labels), pos + 2
        labels.append(message[pos + 1 : pos + 1 + length].decode("ascii", "replace"))
        pos += 1 + length
    return ".".join(labels), pos


def parse_dns(message: bytes) -> tuple[list[str], dict[str, str]]:
    """(question names, answer address -> name) of one DNS message; empty on anything malformed."""
    try:
        _id, _flags, qd, an, _ns, _ar = struct.unpack("!HHHHHH", message[:12])
        pos, questions, answers = 12, [], {}
        for _ in range(qd):
            name, pos = _dns_name(message, pos)
            questions.append(name)
            pos += 4
        for _ in range(an):
            name, pos = _dns_name(message, pos)
            rtype, _cls, _ttl, length = struct.unpack("!HHIH", message[pos : pos + 10])
            pos += 10
            rdata = message[pos : pos + length]
            pos += length
            if rtype == 1 and length == 4:
                answers[str(ipaddress.IPv4Address(rdata))] = name
            elif rtype == 28 and length == 16:
                answers[str(ipaddress.IPv6Address(rdata))] = name
        return questions, answers
    except (struct.error, ValueError, IndexError):
        return [], {}


@dataclass
class Report:
    guest: str
    flows: Counter  # (proto, dst, dport) -> packets or connections
    names: dict[str, str]  # address -> name looked up
    queried: list[str]


def analyse(data: bytes) -> Report:
    packets = []
    for linktype, frame in read_pcap(data):
        payload = ip_payload(linktype, frame)
        parsed = parse_ip(payload) if payload else None
        if parsed:
            packets.append(parsed)
    syn_sources = Counter(p.src for p in packets if p.syn)
    guest = syn_sources.most_common(1)[0][0] if syn_sources else "10.0.2.15"
    flows: Counter = Counter()
    names: dict[str, str] = {}
    queried: list[str] = []
    for p in packets:
        if p.proto == "udp" and 53 in (p.sport, p.dport):
            questions, answers = parse_dns(p.payload)
            if p.dport == 53:
                queried += [q for q in questions if q not in queried]
            names.update(answers)
        if p.src != guest:
            continue
        if p.proto == "tcp" and p.syn:
            flows[("tcp", p.dst, p.dport)] += 1
        elif p.proto == "udp":
            flows[("udp", p.dst, p.dport)] += 1
    return Report(guest, flows, names, queried)


def verdicts(report: Report, computer: tuple[str, int], allowed: list[tuple[str, int | None]],
             platform_names: tuple[str, ...]) -> list[tuple[str, tuple, int, str]]:
    """(verdict, flow, count, name) for every flow: computer, dns, allowed, platform or UNEXPECTED."""
    rows = []
    for flow, count in sorted(report.flows.items()):
        proto, dst, port = flow
        name = report.names.get(dst, "")
        if (dst, port) == computer:
            verdict = "computer"
        elif (dst, port) == EMULATOR_DNS:
            verdict = "dns"
        elif any(dst == h and (p is None or p == port) for h, p in allowed):
            verdict = "allowed"
        elif name and any(fnmatch.fnmatch(name, pattern) for pattern in platform_names):
            verdict = "platform"
        else:
            verdict = "UNEXPECTED"
        rows.append((verdict, flow, count, name))
    return rows


def host_port(text: str, default_port: int | None = None) -> tuple[str, int | None]:
    host, _, port = text.rpartition(":") if text.count(":") == 1 else (text, "", "")
    return (host or text), (int(port) if port else default_port)


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    extra: list[str] = []
    if "--" in args:
        split = args.index("--")
        args, extra = args[:split], args[split + 1 :]
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    start = sub.add_parser("start")
    start.add_argument("--avd", required=True)
    start.add_argument("--pcap", required=True, type=Path)
    check = sub.add_parser("check")
    check.add_argument("pcap", type=Path)
    check.add_argument("--computer", required=True, help="the host-side fake computer, 10.0.2.2:PORT")
    check.add_argument("--allow", action="append", default=[], help="another allowed HOST[:PORT]")
    check.add_argument("--platform-name", action="append", default=[], help="a platform host name pattern")
    check.add_argument("--strict", action="store_true", help="no platform allowance")
    options = parser.parse_args(args)

    if options.command == "start":
        options.pcap.parent.mkdir(parents=True, exist_ok=True)
        emulator = os.path.join(os.environ.get("ANDROID_HOME", ""), "emulator", "emulator")
        binary = emulator if os.path.exists(emulator) else "emulator"
        command = [binary, "-avd", options.avd, "-tcpdump", str(options.pcap), "-no-snapshot-save", *extra]
        print("capture.py: " + " ".join(command))
        return subprocess.call(command)

    computer = host_port(options.computer)
    if computer[1] is None:
        parser.error("--computer needs HOST:PORT")
    report = analyse(options.pcap.read_bytes())
    allowed = [host_port(a) for a in options.allow]
    platform = () if options.strict else tuple(DEFAULT_PLATFORM_NAMES) + tuple(options.platform_name)
    rows = verdicts(report, computer, allowed, platform)
    print(f"guest address: {report.guest}; names looked up: {', '.join(report.queried) or 'none'}")
    for verdict, (proto, dst, port), count, name in rows:
        print(f"{verdict:10} {proto} {dst}:{port}  x{count}  {name}")
    unexpected = [r for r in rows if r[0] == "UNEXPECTED"]
    if unexpected:
        print(f"capture.py: {len(unexpected)} destination(s) other than the computer.")
        return 1
    print("capture.py: every connection went to the computer (or the platform services listed).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
