#!/usr/bin/env python3
"""Captures every packet of one [emulator] run and checks where they went (architecture 21.7; spec X-AC1).

An OkHttp EventListener sees only OkHttp, so the "all traffic captured" check uses the emulator's own captures.
On the API 36 images the emulator has two networks, and one capture each (delivery review D-10 spike):
- the cellular one (eth0, 10.0.2.15), recorded by `emulator -tcpdump` as Ethernet frames;
- the Wi-Fi one (wlan0, 10.0.2.16), the default network, carried by netsim, which `-tcpdump` never sees. netsimd
  records it as 802.11 frames with radiotap headers when it is started with `--pcap` (`-netsim-args --pcap`), in
  `<temp>/android-<user>/netsimd/pcaps/netsim-<n>-<avd>-WIFI.pcap`.
A check without a Wi-Fi capture is INCOMPLETE (exit 2): the app's own traffic goes over Wi-Fi.

Usage:
  capture.py start --avd NAME --pcap FILE [-- more emulator options]
      starts the emulator with both captures (foreground; close the emulator to end them). netsimd must not be
      running already (it would not record); afterwards the Wi-Fi capture is moved next to FILE as <FILE>-wifi.pcap.
  capture.py check FILE [FILE ...] --computer 10.0.2.2:PORT [--allow HOST[:PORT]] [--platform-name PATTERN]
                   [--play-services] [--strict] [--cellular-only]
      lists every connection the guest opened and every UDP destination, with the DNS names it looked up, and
      exits 1 when anything went somewhere other than the computer, the emulator's DNS (10.0.2.3 and fec0::3, port
      53 and DNS over TLS on 853), the app's discovery probe (UDP 45317 to the limited or the emulator network's
      broadcast) and, unless --strict, the platform: hosts whose looked-up name matches a platform pattern
      (connectivity checks, time) and mDNS (the app never uses mDNS, architecture AD-13). The app itself never
      resolves names (architecture 5.1), so platform traffic is the only named traffic expected. The network's own
      DHCP is allowed too. --play-services adds the names Play services reached on the google_apis image
      (PLAY_SERVICES_NAMES); --cellular-only accepts a check without a Wi-Fi capture.

Standard library only: classic pcap files (microsecond or nanosecond, either byte order) with Ethernet, raw IP,
Linux cooked or 802.11 (radiotap or bare) link types.
"""

from __future__ import annotations

import argparse
import fnmatch
import getpass
import ipaddress
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

# The emulator's own addresses, the same on every machine: its alias of the host and its DNS forwarder, in both
# families. Android's private DNS ("automatic") also tries DNS over TLS (853) against the network's resolver.
EMULATOR_HOST = ("10.0.2.2", "fec0::2")
EMULATOR_DNS_SERVERS = ("10.0.2.3", "fec0::3")
EMULATOR_DNS = tuple((server, port) for server in EMULATOR_DNS_SERVERS for port in (53, 853))
# The guest as the captures see it: 10.0.2.15 on the cellular network, 10.0.2.16 on Wi-Fi, and addresses in the
# emulator's IPv6 prefix (or link-local ones).
DEFAULT_GUEST = "10.0.2.15"
WIFI_GUEST = "10.0.2.16"
GUEST_NETWORKS = (
    ipaddress.ip_network(f"{DEFAULT_GUEST}/24", strict=False),
    ipaddress.ip_network(f"{EMULATOR_HOST[1]}/64", strict=False),
    ipaddress.ip_network("fe80::/10"),
)
# The app's discovery probe (server-contract 5.2): UDP to the limited broadcast and to its network's broadcast.
DISCOVERY_PORT = 45317
DISCOVERY_TARGETS = ("255.255.255.255", str(GUEST_NETWORKS[0].broadcast_address))
# Multicast DNS, the platform's (Nearby, network service discovery); the app never uses it (architecture AD-13).
MDNS = (("224.0.0.251", 5353), ("ff02::fb", 5353))
# The network's own DHCP server (the emulator's router), asked again by the guest when a lease is renewed.
DHCP = ((EMULATOR_HOST[0], 67), ("255.255.255.255", 67))
WIFI_LINK_TYPES = (105, 127)  # IEEE 802.11, and 802.11 with radiotap (netsim)
LLC_SNAP = b"\xaa\xaa\x03\x00\x00\x00"
DEFAULT_PLATFORM_NAMES = (
    "connectivitycheck.gstatic.com", "*.gstatic.com", "www.google.com", "clients*.google.com", "time.android.com",
    "*.pool.ntp.org", "time.google.com",
)
# What Play services on the google_apis image reached in the D-10 spike runs (registration, push, updates, asset
# links), accepted with --play-services. Exact names, no wildcard over googleapis.com: an SDK inside the app would use
# names of its own, and X-AC1's dependency-list check is what proves the app carries none.
PLAY_SERVICES_NAMES = (
    "android.googleapis.com", "android.apis.google.com", "android.clients.google.com", "mtalk.google.com",
    "mobile-gtalk.l.google.com", "dl.google.com", "edgedl.me.gvt1.com", "storage.googleapis.com",
    "www.googleapis.com", "digitalassetlinks.googleapis.com", "remoteprovisioning.googleapis.com",
    "gmscompliance-pa.googleapis.com", "geller-pa.googleapis.com", "pagead2.googlesyndication.com",
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
    if linktype == 127:  # 802.11 after a radiotap header, whose length is in its bytes 2-3 (little-endian)
        return wifi_payload(frame[struct.unpack_from("<H", frame, 2)[0]:]) if len(frame) >= 4 else None
    if linktype == 105:
        return wifi_payload(frame)
    return None


def wifi_payload(frame: bytes) -> bytes | None:
    """The IP packet of an unencrypted 802.11 data frame (LLC/SNAP), or None for management, control, null-function
    and protected frames. The emulator's Wi-Fi network is open, so its data frames carry plain IP."""
    if len(frame) < 24:
        return None
    control, flags = frame[0], frame[1]
    kind, subtype = (control >> 2) & 0x3, (control >> 4) & 0xF
    if kind != 2 or subtype & 0x4 or flags & 0x40:
        return None
    header = 24 + (6 if flags & 0x03 == 0x03 else 0)  # a fourth address between two access points
    if subtype & 0x8:  # QoS data: QoS control, and HT control when the order bit is set
        header += 2 + (4 if flags & 0x80 else 0)
    body = frame[header:]
    if len(body) < 8 or body[:6] != LLC_SNAP:
        return None
    return body[8:] if struct.unpack("!H", body[6:8])[0] in (0x0800, 0x86DD) else None


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
    guests: tuple[str, ...]  # every address the guest sent from, in both families and on both networks
    flows: Counter  # (proto, dst, dport) -> packets or connections
    names: dict[str, str]  # address -> name looked up
    queried: list[str]
    link_types: frozenset = frozenset()  # of the captures read; 802.11 means the Wi-Fi network was captured

    @property
    def has_wifi(self) -> bool:
        return any(t in WIFI_LINK_TYPES for t in self.link_types)


def guest_addresses(packets) -> tuple[str, ...]:
    """Every address the guest sent from: each source that opened a TCP connection and each source inside the
    emulator's guest networks other than the emulator's own addresses. The API 36 images use both families, and
    a guest address that only ever sends UDP (QUIC, mDNS) must not hide its flows."""
    own = set(EMULATOR_HOST) | set(EMULATOR_DNS_SERVERS)
    found = {p.src for p in packets if p.syn}
    for p in packets:
        if p.src in found or p.src in own:
            continue
        address = ipaddress.ip_address(p.src)
        if any(address.version == n.version and address in n for n in GUEST_NETWORKS):
            found.add(p.src)
    found.add(DEFAULT_GUEST)
    return tuple(sorted(found, key=lambda a: (ipaddress.ip_address(a).version, ipaddress.ip_address(a))))


def analyse(*captures: bytes) -> Report:
    """One report over every capture of a run (the cellular and the Wi-Fi one)."""
    packets = []
    link_types = set()
    for data in captures:
        for linktype, frame in read_pcap(data):
            link_types.add(linktype)
            payload = ip_payload(linktype, frame)
            parsed = parse_ip(payload) if payload else None
            if parsed:
                packets.append(parsed)
    guests = guest_addresses(packets)
    flows: Counter = Counter()
    names: dict[str, str] = {}
    queried: list[str] = []
    for p in packets:
        if p.proto == "udp" and 53 in (p.sport, p.dport):
            questions, answers = parse_dns(p.payload)
            if p.dport == 53:
                queried += [q for q in questions if q not in queried]
            names.update(answers)
        if p.src not in guests:
            continue
        if p.proto == "tcp" and p.syn:
            flows[("tcp", p.dst, p.dport)] += 1
        elif p.proto == "udp":
            flows[("udp", p.dst, p.dport)] += 1
    return Report(guests, flows, names, queried, frozenset(link_types))


def verdicts(report: Report, computer: tuple[str, int], allowed: list[tuple[str, int | None]],
             platform_names: tuple[str, ...]) -> list[tuple[str, tuple, int, str]]:
    """(verdict, flow, count, name) for every flow: computer, dns, dhcp, discovery, allowed, platform or UNEXPECTED.
    `platform_names` empty means --strict: no platform allowance at all, mDNS included."""
    rows = []
    for flow, count in sorted(report.flows.items()):
        proto, dst, port = flow
        name = report.names.get(dst, "")
        if (dst, port) == computer:
            verdict = "computer"
        elif (dst, port) in EMULATOR_DNS:
            verdict = "dns"
        elif proto == "udp" and (dst, port) in DHCP:
            verdict = "dhcp"
        elif proto == "udp" and port == DISCOVERY_PORT and dst in DISCOVERY_TARGETS:
            verdict = "discovery"
        elif any(dst == h and (p is None or p == port) for h, p in allowed):
            verdict = "allowed"
        elif name and any(fnmatch.fnmatch(name, pattern) for pattern in platform_names):
            verdict = "platform"
        elif platform_names and proto == "udp" and (dst, port) in MDNS:
            verdict = "platform"
            name = name or "mDNS"
        else:
            verdict = "UNEXPECTED"
        rows.append((verdict, flow, count, name))
    return rows


def host_port(text: str, default_port: int | None = None) -> tuple[str, int | None]:
    host, _, port = text.rpartition(":") if text.count(":") == 1 else (text, "", "")
    return (host or text), (int(port) if port else default_port)


# --- the Wi-Fi capture (netsim) ----------------------------------------------------------------------------------

NETSIM_PCAP_ARGS = ("-netsim-args", "--pcap")


def netsim_pcap_dir() -> Path:
    """Where netsimd writes its captures: the emulator's temporary directory, `<temp>/android-<user>`."""
    return Path(tempfile.gettempdir()) / f"android-{getpass.getuser()}" / "netsimd" / "pcaps"


def netsimd_running(run=subprocess.run) -> bool:
    """True while this user's netsimd runs. One netsimd serves every emulator of the user and only records when it
    was started with --pcap, so a capture run must start it."""
    try:
        return run(["pgrep", "-u", str(os.getuid()), "-x", "netsimd"], capture_output=True).returncode == 0
    except OSError:
        return False


def wait_netsimd_gone(timeout: float = 30.0, running=netsimd_running, sleep=time.sleep) -> bool:
    """Waits up to `timeout` seconds for netsimd to stop (it stops a few seconds after the last emulator, closing its
    captures)."""
    for _ in range(int(timeout)):
        if not running():
            return True
        sleep(1)
    return not running()


def wifi_capture(avd: str, since: float, directory: Path | None = None) -> Path | None:
    """The newest netsim Wi-Fi capture of this AVD written at or after `since` (seconds since the epoch)."""
    directory = netsim_pcap_dir() if directory is None else directory
    found = [p for p in directory.glob(f"netsim-*-{avd}-WIFI.pcap") if p.stat().st_mtime >= since]
    return max(found, key=lambda p: p.stat().st_mtime) if found else None


def discard_other_captures(avd: str, since: float, directory: Path | None = None) -> None:
    """Deletes netsimd's other captures of this run (Bluetooth, UWB): nobody reads them."""
    directory = netsim_pcap_dir() if directory is None else directory
    for path in directory.glob(f"netsim-*-{avd}-*.pcap"):
        if not path.name.endswith("-WIFI.pcap") and path.stat().st_mtime >= since:
            path.unlink(missing_ok=True)


def wifi_path(pcap: Path) -> Path:
    """Where `start` puts the Wi-Fi capture: next to the cellular one, `<name>-wifi.pcap`."""
    return pcap.with_name(f"{pcap.stem}-wifi{pcap.suffix or '.pcap'}")


def start(avd: str, pcap: Path, extra: list[str], call=subprocess.call, running=netsimd_running,
          sleep=time.sleep, directory: Path | None = None) -> int:
    if not wait_netsimd_gone(30, running, sleep):
        print("capture.py: netsimd is already running (another emulator?): it would not record the Wi-Fi network. "
              "Stop every emulator first.")
        return 2
    pcap.parent.mkdir(parents=True, exist_ok=True)
    emulator = os.path.join(os.environ.get("ANDROID_HOME", ""), "emulator", "emulator")
    binary = emulator if os.path.exists(emulator) else "emulator"
    command = [binary, "-avd", avd, "-tcpdump", str(pcap), *NETSIM_PCAP_ARGS, "-no-snapshot-save", *extra]
    print("capture.py: " + " ".join(command))
    since = time.time() - 1
    code = call(command)
    wait_netsimd_gone(30, running, sleep)
    wifi = wifi_capture(avd, since, directory)
    discard_other_captures(avd, since, directory)
    if wifi is None:
        print("capture.py: netsimd wrote no Wi-Fi capture for this AVD; a check of this run is INCOMPLETE.")
        return code or 2
    target = wifi_path(pcap)
    shutil.move(str(wifi), target)
    print(f"capture.py: cellular capture {pcap}; Wi-Fi capture {target}")
    print(f"capture.py: next: capture.py check {pcap} {target} --computer 10.0.2.2:PORT")
    return code


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    extra: list[str] = []
    if "--" in args:
        split = args.index("--")
        args, extra = args[:split], args[split + 1 :]
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    start_parser = sub.add_parser("start")
    start_parser.add_argument("--avd", required=True)
    start_parser.add_argument("--pcap", required=True, type=Path)
    check = sub.add_parser("check")
    check.add_argument("pcap", type=Path, nargs="+", help="the run's captures: the cellular and the Wi-Fi one")
    check.add_argument("--computer", required=True, help="the host-side fake computer, 10.0.2.2:PORT")
    check.add_argument("--allow", action="append", default=[], help="another allowed HOST[:PORT]")
    check.add_argument("--platform-name", action="append", default=[], help="a platform host name pattern")
    check.add_argument("--play-services", action="store_true",
                       help="also accept the names Play services reach on the google_apis image")
    check.add_argument("--strict", action="store_true", help="no platform allowance")
    check.add_argument("--cellular-only", action="store_true", help="accept a check without a Wi-Fi capture")
    options = parser.parse_args(args)

    if options.command == "start":
        return start(options.avd, options.pcap, extra)

    computer = host_port(options.computer)
    if computer[1] is None:
        parser.error("--computer needs HOST:PORT")
    report = analyse(*(path.read_bytes() for path in options.pcap))
    allowed = [host_port(a) for a in options.allow]
    platform = () if options.strict else (DEFAULT_PLATFORM_NAMES + tuple(options.platform_name)
                                          + (PLAY_SERVICES_NAMES if options.play_services else ()))
    rows = verdicts(report, computer, allowed, platform)
    print(f"guest addresses: {', '.join(report.guests)}; names looked up: {', '.join(report.queried) or 'none'}")
    for verdict, (proto, dst, port), count, name in rows:
        print(f"{verdict:10} {proto} {dst}:{port}  x{count}  {name}")
    unexpected = [r for r in rows if r[0] == "UNEXPECTED"]
    if unexpected:
        print(f"capture.py: {len(unexpected)} destination(s) other than the computer.")
        return 1
    if not report.has_wifi and not options.cellular_only:
        print("capture.py: INCOMPLETE: no Wi-Fi (802.11) capture among the files, so the default network's traffic "
              "is missing. Add the netsim Wi-Fi capture (`start` writes <name>-wifi.pcap) or pass --cellular-only.")
        return 2
    print("capture.py: every connection went to the computer (or the platform services listed).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
