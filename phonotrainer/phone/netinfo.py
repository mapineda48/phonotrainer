"""The computer's IPv4 interfaces: address, prefix and default-gateway MAC.

Used for the approved networks (a laptop must not expose phone access on a
café Wi-Fi it joins later), the `ui` banner and `phonotrainer phone pair`.
Linux only, like the project: the addresses come from `SIOCGIFADDR` and
`SIOCGIFNETMASK`, the gateway from `/proc/net/route` and its MAC from
`/proc/net/arp`. Nothing here is ever written to a file or a log.
"""

from __future__ import annotations

import fcntl
import ipaddress
import socket
import struct
from dataclasses import dataclass
from pathlib import Path

SIOCGIFFLAGS = 0x8913
SIOCGIFADDR = 0x8915
SIOCGIFNETMASK = 0x891B
IFF_UP = 0x1
IFF_LOOPBACK = 0x8
RTF_GATEWAY = 0x2
ARP_COMPLETE = 0x2


@dataclass(frozen=True)
class Interface:
    name: str
    address: str                  # dotted IPv4
    prefix: int
    gateway_mac: str | None = None
    up: bool = True
    loopback: bool = False

    @property
    def network(self) -> str:
        return str(ipaddress.ip_interface(f"{self.address}/{self.prefix}").network)


def parse_default_gateways(route_text: str) -> dict[str, str]:
    """`/proc/net/route` → {interface: gateway IPv4} for default routes."""
    gateways: dict[str, str] = {}
    for line in route_text.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 8:
            continue
        iface, destination, gateway, flags, mask = (fields[0], fields[1], fields[2],
                                                    fields[3], fields[7])
        try:
            if int(destination, 16) or int(mask, 16) or not int(flags, 16) & RTF_GATEWAY:
                continue
            address = socket.inet_ntoa(struct.pack("<L", int(gateway, 16)))
        except (ValueError, struct.error):
            continue
        gateways.setdefault(iface, address)
    return gateways


def parse_arp(arp_text: str) -> dict[tuple[str, str], str]:
    """`/proc/net/arp` → {(IPv4, interface): MAC} for complete entries."""
    table: dict[tuple[str, str], str] = {}
    for line in arp_text.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 6:
            continue
        address, flags, mac, device = fields[0], fields[2], fields[3], fields[5]
        try:
            if not int(flags, 16) & ARP_COMPLETE:
                continue
        except ValueError:
            continue
        if mac == "00:00:00:00:00:00":
            continue
        table[(address, device)] = mac.lower()
    return table


def _read(path: str) -> str:
    try:
        return Path(path).read_text(encoding="ascii", errors="replace")
    except OSError:
        return ""


def _ioctl(sock: socket.socket, request: int, name: str) -> bytes:
    return fcntl.ioctl(sock.fileno(), request, struct.pack("256s", name.encode()[:15]))


def interfaces() -> list[Interface]:
    """Every interface with an IPv4 address, up or not."""
    gateways = parse_default_gateways(_read("/proc/net/route"))
    arp = parse_arp(_read("/proc/net/arp"))
    found: list[Interface] = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        for _, name in socket.if_nameindex():
            try:
                flags = struct.unpack("H", _ioctl(sock, SIOCGIFFLAGS, name)[16:18])[0]
                address = socket.inet_ntoa(_ioctl(sock, SIOCGIFADDR, name)[20:24])
                mask = socket.inet_ntoa(_ioctl(sock, SIOCGIFNETMASK, name)[20:24])
            except OSError:
                continue                       # no IPv4 address on this interface
            prefix = ipaddress.IPv4Network(f"0.0.0.0/{mask}").prefixlen
            gateway = gateways.get(name)
            found.append(Interface(
                name=name, address=address, prefix=prefix,
                gateway_mac=arp.get((gateway, name)) if gateway else None,
                up=bool(flags & IFF_UP), loopback=bool(flags & IFF_LOOPBACK)))
    return found
