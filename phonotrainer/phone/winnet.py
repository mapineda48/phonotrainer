"""The computer's IPv4 interfaces on Windows (iphlpapi through ctypes).
Imported only when `sys.platform == "win32"`; netinfo.py keeps the Linux
implementation.

- `GetAdaptersAddresses`: the name (the adapter's friendly name, which is what
  `--phone-interface` takes), every IPv4 address with its prefix, whether it
  is up, loopback, and the default gateway;
- `GetIpNetTable2`: the gateway's MAC from the neighbour table;
- `GetIfEntry2`: whether Windows calls the adapter a hardware interface.

`virtual` fails closed, like `/sys/devices/virtual` on Linux and more: only a
hardware Ethernet or Wi-Fi adapter whose driver description names no VPN or
virtual machine (VPN drivers often register as hardware Ethernet, and their
descriptions are rebranded: "TAP-NordVPN Windows Adapter", "PANGP Virtual
Ethernet Adapter") counts as physical. Hyper-V's vEthernet, Wi-Fi Direct,
Bluetooth, WAN miniports, loopback, tunnels, PPP and mobile broadband are all
virtual; `--phone-interface` still approves one by name.

Nothing here is ever written to a file or a log.
"""

from __future__ import annotations

import ctypes
import re
import socket
from ctypes import wintypes

from .netinfo import Interface

_iphlpapi = ctypes.WinDLL("iphlpapi", use_last_error=True)

AF_INET = 2
GAA_FLAG_SKIP_ANYCAST = 0x0002
GAA_FLAG_SKIP_MULTICAST = 0x0004
GAA_FLAG_SKIP_DNS_SERVER = 0x0008
GAA_FLAG_INCLUDE_GATEWAYS = 0x0080
ERROR_BUFFER_OVERFLOW = 111
ERROR_NO_DATA = 232
IF_OPER_STATUS_UP = 1
IF_TYPE_ETHERNET = 6
IF_TYPE_SOFTWARE_LOOPBACK = 24
IF_TYPE_WIFI = 71                     # IEEE 802.11
PHYSICAL_TYPES = (IF_TYPE_ETHERNET, IF_TYPE_WIFI)
HARDWARE_INTERFACE_FLAG = 0x01        # InterfaceAndOperStatusFlags.HardwareInterface
# NL_NEIGHBOR_STATE: probe, delay, stale, reachable, permanent hold a MAC.
NEIGHBOUR_STATES_WITH_MAC = (2, 3, 4, 5, 6)
# What the driver descriptions of VPNs and of virtual machines' host-side
# adapters say, in any case. A driver that registers as hardware Ethernet is
# still virtual when its description has one of these.
VIRTUAL_DESCRIPTION_RE = re.compile(
    r"\btap\b|virtual|vpn|miniport|anyconnect|pangp|globalprotect|fortinet|check point|"
    r"juniper|pulse|sonicwall|zscaler|private internet access|surfshark|mullvad|nordlynx|"
    r"wireguard|wintun|tailscale|zerotier|hamachi|npcap|hyper-v|vethernet|virtualbox|vmware",
    re.IGNORECASE)


class _SocketAddress(ctypes.Structure):
    _fields_ = [("lpSockaddr", ctypes.c_void_p), ("iSockaddrLength", ctypes.c_int)]


class _UnicastAddress(ctypes.Structure):
    pass


_UnicastAddress._fields_ = [
    ("Length", wintypes.ULONG), ("Flags", wintypes.DWORD),
    ("Next", ctypes.POINTER(_UnicastAddress)), ("Address", _SocketAddress),
    ("PrefixOrigin", ctypes.c_int), ("SuffixOrigin", ctypes.c_int), ("DadState", ctypes.c_int),
    ("ValidLifetime", wintypes.ULONG), ("PreferredLifetime", wintypes.ULONG),
    ("LeaseLifetime", wintypes.ULONG), ("OnLinkPrefixLength", ctypes.c_ubyte)]


class _GatewayAddress(ctypes.Structure):
    pass


_GatewayAddress._fields_ = [
    ("Length", wintypes.ULONG), ("Reserved", wintypes.DWORD),
    ("Next", ctypes.POINTER(_GatewayAddress)), ("Address", _SocketAddress)]


class _AdapterAddresses(ctypes.Structure):
    """IP_ADAPTER_ADDRESSES_LH up to FirstGatewayAddress (the rest is unused)."""


_AdapterAddresses._fields_ = [
    ("Length", wintypes.ULONG), ("IfIndex", wintypes.DWORD),
    ("Next", ctypes.POINTER(_AdapterAddresses)), ("AdapterName", ctypes.c_char_p),
    ("FirstUnicastAddress", ctypes.POINTER(_UnicastAddress)),
    ("FirstAnycastAddress", ctypes.c_void_p), ("FirstMulticastAddress", ctypes.c_void_p),
    ("FirstDnsServerAddress", ctypes.c_void_p), ("DnsSuffix", ctypes.c_wchar_p),
    ("Description", ctypes.c_wchar_p), ("FriendlyName", ctypes.c_wchar_p),
    ("PhysicalAddress", ctypes.c_ubyte * 8), ("PhysicalAddressLength", wintypes.ULONG),
    ("Flags", wintypes.ULONG), ("Mtu", wintypes.ULONG), ("IfType", wintypes.ULONG),
    ("OperStatus", ctypes.c_int), ("Ipv6IfIndex", wintypes.DWORD),
    ("ZoneIndices", wintypes.ULONG * 16), ("FirstPrefix", ctypes.c_void_p),
    ("TransmitLinkSpeed", ctypes.c_uint64), ("ReceiveLinkSpeed", ctypes.c_uint64),
    ("FirstWinsServerAddress", ctypes.c_void_p),
    ("FirstGatewayAddress", ctypes.POINTER(_GatewayAddress))]


class _IfRow2Head(ctypes.Structure):
    """MIB_IF_ROW2 up to InterfaceAndOperStatusFlags. GetIfEntry2 writes the
    whole row, so it is given a buffer of IF_ROW2_BUFFER bytes."""
    _fields_ = [
        ("InterfaceLuid", ctypes.c_uint64), ("InterfaceIndex", wintypes.ULONG),
        ("InterfaceGuid", ctypes.c_ubyte * 16), ("Alias", ctypes.c_wchar * 257),
        ("Description", ctypes.c_wchar * 257), ("PhysicalAddressLength", wintypes.ULONG),
        ("PhysicalAddress", ctypes.c_ubyte * 32), ("PermanentPhysicalAddress", ctypes.c_ubyte * 32),
        ("Mtu", wintypes.ULONG), ("Type", wintypes.ULONG), ("TunnelType", ctypes.c_int),
        ("MediaType", ctypes.c_int), ("PhysicalMediumType", ctypes.c_int),
        ("AccessType", ctypes.c_int), ("DirectionType", ctypes.c_int),
        ("InterfaceAndOperStatusFlags", ctypes.c_ubyte)]


IF_ROW2_BUFFER = 4096                     # the real MIB_IF_ROW2 is 1352 bytes


class _NeighbourRow(ctypes.Structure):
    """MIB_IPNET_ROW2."""
    _fields_ = [
        ("Family", ctypes.c_ushort), ("Address", ctypes.c_ubyte * 26),   # SOCKADDR_INET
        ("InterfaceIndex", wintypes.ULONG), ("InterfaceLuid", ctypes.c_uint64),
        ("PhysicalAddress", ctypes.c_ubyte * 32), ("PhysicalAddressLength", wintypes.ULONG),
        ("State", ctypes.c_int), ("Flags", ctypes.c_ubyte), ("ReachabilityTime", wintypes.ULONG)]


class _NeighbourTable(ctypes.Structure):
    _fields_ = [("NumEntries", wintypes.ULONG), ("Table", _NeighbourRow * 1)]


_iphlpapi.GetAdaptersAddresses.argtypes = (wintypes.ULONG, wintypes.ULONG, ctypes.c_void_p,
                                           ctypes.c_void_p, ctypes.POINTER(wintypes.ULONG))
_iphlpapi.GetAdaptersAddresses.restype = wintypes.ULONG
_iphlpapi.GetIfEntry2.argtypes = (ctypes.c_void_p,)
_iphlpapi.GetIfEntry2.restype = wintypes.ULONG
_iphlpapi.GetIpNetTable2.argtypes = (ctypes.c_ushort, ctypes.POINTER(ctypes.c_void_p))
_iphlpapi.GetIpNetTable2.restype = wintypes.ULONG
_iphlpapi.FreeMibTable.argtypes = (ctypes.c_void_p,)
_iphlpapi.FreeMibTable.restype = None


def windows_virtual(hardware: bool, if_type: int, description: str) -> bool:
    """The Windows counterpart of `netinfo.kernel_virtual`, failing closed:
    physical only for a hardware Ethernet or Wi-Fi adapter whose description
    names no VPN or virtual machine."""
    return (not hardware or if_type not in PHYSICAL_TYPES
            or VIRTUAL_DESCRIPTION_RE.search(description or "") is not None)


def _ipv4(socket_address: _SocketAddress) -> str | None:
    """The dotted address of a SOCKET_ADDRESS holding a sockaddr_in."""
    if not socket_address.lpSockaddr or socket_address.iSockaddrLength < 8:
        return None
    raw = ctypes.string_at(socket_address.lpSockaddr, 8)
    if int.from_bytes(raw[:2], "little") != AF_INET:
        return None
    return socket.inet_ntoa(raw[4:8])


def _adapters() -> ctypes.Array:
    flags = (GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST
             | GAA_FLAG_SKIP_DNS_SERVER)
    size = wintypes.ULONG(15 * 1024)
    for _ in range(4):
        buffer = ctypes.create_string_buffer(size.value)
        error = _iphlpapi.GetAdaptersAddresses(AF_INET, flags, None, buffer, ctypes.byref(size))
        if error == 0:
            return buffer
        if error == ERROR_NO_DATA:
            return ctypes.create_string_buffer(0)
        if error != ERROR_BUFFER_OVERFLOW:
            raise ctypes.WinError(error, "GetAdaptersAddresses failed")
    raise ctypes.WinError(ERROR_BUFFER_OVERFLOW, "GetAdaptersAddresses failed")


def _hardware(if_index: int) -> bool:
    """InterfaceAndOperStatusFlags.HardwareInterface; False when unknown (the
    description and type rules still apply, like an unknown sysfs entry)."""
    buffer = ctypes.create_string_buffer(IF_ROW2_BUFFER)
    row = _IfRow2Head.from_buffer(buffer)
    row.InterfaceIndex = if_index
    if _iphlpapi.GetIfEntry2(buffer) != 0:
        return False
    return bool(row.InterfaceAndOperStatusFlags & HARDWARE_INTERFACE_FLAG)


def _neighbour_macs() -> dict[tuple[str, int], str]:
    """{(IPv4, interface index): MAC} for entries that hold a MAC."""
    table = ctypes.c_void_p()
    if _iphlpapi.GetIpNetTable2(AF_INET, ctypes.byref(table)) != 0 or not table:
        return {}
    try:
        count = _NeighbourTable.from_address(table.value).NumEntries
        rows = (_NeighbourRow * count).from_address(table.value + _NeighbourTable.Table.offset)
        found = {}
        for row in rows:
            if (row.Family != AF_INET or row.State not in NEIGHBOUR_STATES_WITH_MAC
                    or row.PhysicalAddressLength != 6):
                continue
            mac = bytes(row.PhysicalAddress[:6])
            if not any(mac):
                continue
            address = socket.inet_ntoa(bytes(row.Address[2:6]))
            found[(address, row.InterfaceIndex)] = ":".join(f"{b:02x}" for b in mac)
        return found
    finally:
        _iphlpapi.FreeMibTable(table)


def interfaces() -> list[Interface]:
    """Every adapter with an IPv4 address, up or not (one entry per address)."""
    buffer = _adapters()
    if not len(buffer):
        return []
    macs = _neighbour_macs()
    found: list[Interface] = []
    adapter = ctypes.cast(buffer, ctypes.POINTER(_AdapterAddresses))
    while adapter:
        entry = adapter.contents
        gateway = None
        hop = entry.FirstGatewayAddress
        while hop and gateway is None:
            gateway = _ipv4(hop.contents.Address)
            hop = hop.contents.Next
        virtual = windows_virtual(_hardware(entry.IfIndex), entry.IfType, entry.Description)
        unicast = entry.FirstUnicastAddress
        while unicast:
            address = _ipv4(unicast.contents.Address)
            if address is not None:
                found.append(Interface(
                    name=entry.FriendlyName or entry.AdapterName.decode("ascii", "replace"),
                    address=address, prefix=int(unicast.contents.OnLinkPrefixLength),
                    gateway_mac=macs.get((gateway, entry.IfIndex)) if gateway else None,
                    up=entry.OperStatus == IF_OPER_STATUS_UP,
                    loopback=entry.IfType == IF_TYPE_SOFTWARE_LOOPBACK,
                    virtual=virtual))
            unicast = unicast.contents.Next
        adapter = entry.Next
    return found
