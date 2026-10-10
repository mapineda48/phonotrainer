"""Shapes of sensitive values, the local denylist and redaction (architecture 20.7).

Shared by `check_repo.py` (which reports these values in files) and `device-qa/qa_log.py` (which redacts them from
QA logs). Python standard library only.

The local denylist is computed at run time from this computer (host name, user name, home directory, git e-mail, the
serials adb lists, the host's IPv4 and MAC addresses). It lives in memory only: nothing here ever writes it to a file
or prints its values.
"""

from __future__ import annotations

import functools
import getpass
import ipaddress
import math
import os
import re
import socket
import subprocess
from dataclasses import dataclass
from pathlib import Path

# --------------------------------------------------------------------------------------------------------------------
# Addresses

DOCUMENTATION_V4 = tuple(
    ipaddress.ip_network(n) for n in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")
)
LOOPBACK_V4 = ipaddress.ip_network("127.0.0.0/8")
# The wildcard, the limited broadcast and the emulator's fixed addresses: 10.0.2.2 is its alias of the host's loopback
# (architecture 21.6), 10.0.2.3 its DNS forwarder and 10.0.2.15 the guest itself; they are the same on every machine.
ALLOWED_V4 = frozenset(
    ipaddress.ip_address(a) for a in ("0.0.0.0", "255.255.255.255", "10.0.2.2", "10.0.2.3", "10.0.2.15")
)

# Well-known special-purpose blocks (RFC 6890 and friends). Their network address, written alone or with its own
# prefix length, names a range rather than a host, so documents may cite it ("10.0.0.0/8", "64:ff9b::/96").
SPECIAL_BLOCKS = tuple(
    ipaddress.ip_network(n)
    for n in (
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24",
        "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "::/128", "::1/128", "::ffff:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48", "100::/64", "2001::/32",
        "2001:db8::/32", "2002::/16", "fc00::/7", "fd00::/8", "fe80::/10", "ff00::/8",
    )
)
DOCUMENTATION_V6 = ipaddress.ip_network("2001:db8::/32")
ALLOWED_V6 = frozenset(ipaddress.ip_address(a) for a in ("::", "::1"))

_OCTET = r"(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"
IPV4_RE = re.compile(rf"(?<![\w.])({_OCTET}(?:\.{_OCTET}){{3}})(?:/(\d{{1,2}}))?(?!\w)(?!\.\d)")
IPV6_RE = re.compile(r"(?<![\w:.])((?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4})(?:%\w+)?(?:/(\d{1,3}))?(?![\w:])")
# SVG and vector-drawable geometry packs decimals without separators, which can look like an address; geometry never
# holds one.
GEOMETRY_RE = re.compile(r"""\b(?:d|pathData|android:pathData|points|viewBox)\s*=\s*\\?["'][^"']*["']""")


def _is_netmask(address: ipaddress.IPv4Address) -> bool:
    value = int(address)
    inverted = ~value & 0xFFFFFFFF
    return value >> 24 == 0xFF and inverted & (inverted + 1) == 0


def _names_special_block(address, prefix: int | None) -> bool:
    for block in SPECIAL_BLOCKS:
        if block.version == address.version and address == block.network_address:
            if prefix is None or prefix == block.prefixlen:
                return True
    return False


def ipv4_allowed(address: ipaddress.IPv4Address, prefix: int | None = None) -> bool:
    """True for addresses that identify nobody: documentation, loopback, wildcard, broadcast, 10.0.2.2, masks, ranges."""
    if address in ALLOWED_V4 or address in LOOPBACK_V4 or any(address in n for n in DOCUMENTATION_V4):
        return True
    return _is_netmask(address) or _names_special_block(address, prefix)


def ipv6_allowed(address: ipaddress.IPv6Address, prefix: int | None = None) -> bool:
    if address in ALLOWED_V6 or address in DOCUMENTATION_V6:
        return True
    return _names_special_block(address, prefix)


def address_class(address) -> str:
    """A label that says what kind of address was there without saying which one (for redaction)."""
    if address.is_loopback:
        return "loopback"
    if address.is_link_local:
        return "link-local"
    if address.is_private:
        return "private"
    return "public"


def find_addresses(line: str):
    """Yields (start, end, text, kind) for every IPv4 and IPv6 literal that is not allowed."""
    scrubbed = GEOMETRY_RE.sub(lambda m: " " * len(m.group(0)), line)
    for match in IPV4_RE.finditer(scrubbed):
        address = ipaddress.ip_address(match.group(1))
        prefix = int(match.group(2)) if match.group(2) else None
        if not ipv4_allowed(address, prefix):
            yield match.start(), match.end(), match.group(0), "ipv4"
    for match in IPV6_RE.finditer(scrubbed):
        text = match.group(1)
        if sum(c in "0123456789abcdefABCDEF" for c in text) < 4:
            continue
        try:
            address = ipaddress.IPv6Address(text)
        except ValueError:
            continue
        prefix = int(match.group(2)) if match.group(2) else None
        if not ipv6_allowed(address, prefix):
            yield match.start(), match.end(), match.group(0), "ipv6"


# --------------------------------------------------------------------------------------------------------------------
# MAC addresses

MAC_RE = re.compile(r"(?<![\w:-])([0-9A-Fa-f]{2}([:-])(?:[0-9A-Fa-f]{2}\2){4}[0-9A-Fa-f]{2})(?![\w:-])")
ALLOWED_MACS = frozenset({"00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "02:00:00:00:00:00"})


def mac_allowed(text: str) -> bool:
    """The documentation range 00:00:5e:00:53:xx (RFC 7042), all zeros, broadcast and Android's placeholder."""
    normal = text.lower().replace("-", ":")
    return normal.startswith("00:00:5e:00:53:") or normal in ALLOWED_MACS


def find_macs(line: str):
    for match in MAC_RE.finditer(line):
        if not mac_allowed(match.group(1)):
            yield match.start(), match.end(), match.group(1)


# --------------------------------------------------------------------------------------------------------------------
# Home paths

# `/home/<name>` and `/Users/<name>` not preceded by a path character (so `feature/home/` passes), and Windows
# profiles. Placeholders such as `/home/<name>/` never match: `<` is not a name character.
HOME_PATH_RE = re.compile(
    r"(?<![A-Za-z0-9_.~-])/(?:home|Users)/([A-Za-z0-9._-]+)(?=/|[^A-Za-z0-9._-]|$)"
    r"|(?<![\w])[A-Za-z]:\\{1,2}Users\\{1,2}([A-Za-z0-9._-]+)"
)


# Names that are obviously placeholders in examples and tests (`/home/me/video.webm` in the server tests). The real
# home directory is in the local denylist anyway.
PLACEHOLDER_HOME_NAMES = frozenset({"me", "you", "user", "username", "someone", "example", "name", "yourname"})


def find_home_paths(line: str):
    for match in HOME_PATH_RE.finditer(line):
        name = match.group(1) or match.group(2)
        if name.lower() not in PLACEHOLDER_HOME_NAMES:
            yield match.start(), match.end(), match.group(0)


# --------------------------------------------------------------------------------------------------------------------
# Credentials and tokens

PAIRING_PLACEHOLDER = "482913"  # server-contract 2.2 and 6.2: the one pairing code documents may show

CREDENTIAL_RES = (
    ("pairing token", re.compile(r"(?<![A-Za-z0-9_-])pt1_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])")),
    ("bearer credential", re.compile(r"\bBearer\s+(?![<{$])(?=[A-Za-z0-9._~+/-]*\d)[A-Za-z0-9._~+/-]{16,}=*")),
    ("signed request header", re.compile(r"\bPT1\s+device=[^,\s]+,.*?\bmac=[0-9A-Fa-f]{64}\b")),
    ("pairing code", re.compile(r'"code"\s*:\s*"(?!' + PAIRING_PLACEHOLDER + r'")\d{6}"')),
    ("pairing code", re.compile(r"\bPairing code:\s*(?!482 ?913\b)\d{3} ?\d{3}\b")),
    ("server, device or library id", re.compile(r"\b(?:srv|dev|lib)_[A-Za-z2-7]{26}\b")),
)

PRIVATE_KEY_RE = re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----")

KNOWN_TOKEN_RES = (
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{22,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b"),
    re.compile(r"\bsk-(?:ant-)?[A-Za-z0-9_-]{32,}\b"),
)

LONG_TOKEN_RE = re.compile(r"(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/_-]{32,}={0,2}(?![A-Za-z0-9+/=_-])")
LONG_TOKEN_MIN_ENTROPY = 4.3


def shannon_entropy(text: str) -> float:
    counts: dict[str, int] = {}
    for c in text:
        counts[c] = counts.get(c, 0) + 1
    return -sum(n / len(text) * math.log2(n / len(text)) for n in counts.values())


def _looks_like_token(text: str, before: str) -> bool:
    body = text.rstrip("=")
    if re.fullmatch(r"[0-9a-fA-F]+", body):
        return False  # digests and commit ids are checksums, not credentials
    if not (re.search(r"\d", body) and re.search(r"[a-z]", body) and re.search(r"[A-Z]", body)):
        return False
    if before.endswith("base64,"):
        return False  # inline image data
    if "/" in body and all(re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{1,}", s) for s in body.split("/") if s):
        if shannon_entropy(body) < 4.6:
            return False  # a path or a qualified name
    return shannon_entropy(body) >= LONG_TOKEN_MIN_ENTROPY


def find_credentials(line: str):
    """Yields (start, end, kind) for credential shapes, known token formats and long random-looking tokens; a match
    inside an earlier one (the 43 characters of a `pt1_` token) is not reported twice."""
    spans: list[tuple[int, int]] = []

    def fresh(start: int, end: int) -> bool:
        if any(s <= start and end <= e for s, e in spans):
            return False
        spans.append((start, end))
        return True

    for kind, pattern in CREDENTIAL_RES:
        for match in pattern.finditer(line):
            if fresh(match.start(), match.end()):
                yield match.start(), match.end(), kind
    for pattern in KNOWN_TOKEN_RES:
        for match in pattern.finditer(line):
            if fresh(match.start(), match.end()):
                yield match.start(), match.end(), "access token"
    for match in LONG_TOKEN_RE.finditer(line):
        if _looks_like_token(match.group(0), line[: match.start()]) and fresh(match.start(), match.end()):
            yield match.start(), match.end(), "long token"


# --------------------------------------------------------------------------------------------------------------------
# Video and post links (docs/qa-report.md, QA logs)

VIDEO_HOSTS = (
    "youtube.com", "youtu.be", "youtube-nocookie.com", "x.com", "twitter.com", "tiktok.com", "instagram.com",
    "vimeo.com", "facebook.com", "fb.watch", "twitch.tv", "dailymotion.com",
)
URL_RE = re.compile(r"\bhttps?://[^\s<>\"'`)\]]+", re.IGNORECASE)
POST_ID_RE = re.compile(r"/status(?:es)?/\d{5,}|[?&]v=[\w-]{6,}|/(?:shorts|watch|video|videos|reel|reels|p)/[\w-]{5,}")


def find_post_links(line: str):
    """URLs that name a video or a post: any path on a video site, or an id-shaped path anywhere."""
    for match in URL_RE.finditer(line):
        url = match.group(0)
        rest = url.split("://", 1)[1]
        host, _, path = rest.partition("/")
        host = host.split(":")[0].lower()
        on_video_site = any(host == h or host.endswith("." + h) for h in VIDEO_HOSTS)
        if (on_video_site and path.strip("/")) or POST_ID_RE.search("/" + path):
            yield match.start(), match.end(), url


# --------------------------------------------------------------------------------------------------------------------
# The local denylist

GENERIC_HOST_NAMES = frozenset({
    "localhost", "localhost.localdomain", "fedora", "ubuntu", "debian", "archlinux", "arch", "raspberrypi", "kali",
    "mint", "pop-os", "manjaro", "linux", "computer", "desktop", "laptop", "pc", "runner", "mac", "macbook",
})
GENERIC_USER_NAMES = frozenset({
    "root", "user", "admin", "ubuntu", "runner", "android", "build", "builder", "test", "tester", "guest", "nobody",
    "vagrant", "docker", "ci", "jenkins", "github",
})


@dataclass(frozen=True)
class DenyEntry:
    kind: str  # host name, user name, home directory, git e-mail, device serial, host address, host MAC
    value: str

    def pattern(self) -> re.Pattern:
        return _entry_pattern(self.kind, self.value)


@functools.lru_cache(maxsize=None)
def _entry_pattern(kind: str, raw: str) -> re.Pattern:
    value = re.escape(raw)
    if kind == "host MAC":
        value = "[:-]".join(re.escape(part) for part in raw.split(":"))
    if kind in ("host name", "user name", "device serial", "host address", "host MAC"):
        return re.compile(rf"(?<![A-Za-z0-9]){value}(?![A-Za-z0-9])", re.IGNORECASE)
    return re.compile(value, re.IGNORECASE)


@functools.lru_cache(maxsize=64)
def _prefilter(entries: tuple) -> re.Pattern | None:
    """One case-insensitive alternation of every value, so most lines are rejected with a single search."""
    if not entries:
        return None
    parts = sorted({re.escape(e.value) if e.kind != "host MAC" else _entry_pattern(e.kind, e.value).pattern
                    for e in entries}, key=len, reverse=True)
    return re.compile("|".join(parts), re.IGNORECASE)


def _adb_serials(timeout: float = 2.0) -> list[str]:
    """Serials from an adb server that is already running. It speaks the adb host protocol directly, so it never
    starts a server (which could make an attached phone ask for authorization) and never talks to a device."""
    port = int(os.environ.get("ANDROID_ADB_SERVER_PORT", "5037"))
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as conn:
            request = b"host:devices"
            conn.sendall(b"%04x" % len(request) + request)
            data = b""
            while True:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                data += chunk
    except OSError:
        return []
    if not data.startswith(b"OKAY") or len(data) < 8:
        return []
    payload = data[8 : 8 + int(data[4:8], 16)].decode("utf-8", "replace")
    return [line.split()[0] for line in payload.splitlines() if line.strip()]


def _host_ipv4_addresses() -> list[str]:
    found: set[str] = set()
    try:
        out = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True, timeout=5).stdout
        found.update(re.findall(r"\binet (\d+\.\d+\.\d+\.\d+)", out))
    except (OSError, subprocess.SubprocessError):
        pass
    if not found:
        try:
            out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5).stdout
            found.update(re.findall(r"\binet (?:addr:)?(\d+\.\d+\.\d+\.\d+)", out))
        except (OSError, subprocess.SubprocessError):
            pass
    try:  # the address of the default route; a UDP connect sends nothing
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            found.add(probe.getsockname()[0])
    except OSError:
        pass
    return sorted(a for a in found if not ipv4_allowed(ipaddress.ip_address(a)))


def _host_macs() -> list[str]:
    found: set[str] = set()
    net = Path("/sys/class/net")
    if net.is_dir():
        for iface in net.iterdir():
            try:
                found.add((iface / "address").read_text().strip().lower())
            except OSError:
                continue
    else:
        try:
            out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5).stdout
            found.update(m.lower() for m in re.findall(r"\bether ((?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})", out))
        except (OSError, subprocess.SubprocessError):
            pass
    return sorted(m for m in found if re.fullmatch(r"(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", m) and not mac_allowed(m))


def _git_email(repo: Path | None) -> str:
    try:
        out = subprocess.run(
            ["git", "config", "user.email"], cwd=repo, capture_output=True, text=True, timeout=5
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return ""
    return out.strip()


def local_denylist(repo: Path | None = None, environ=None) -> list[DenyEntry]:
    """The identifiers of this computer and its user, computed now and never stored."""
    env = os.environ if environ is None else environ
    entries: list[DenyEntry] = []

    def add(kind: str, value: str, minimum: int = 3) -> None:
        value = (value or "").strip()
        if len(value) >= minimum and DenyEntry(kind, value) not in entries:
            entries.append(DenyEntry(kind, value))

    hostname = socket.gethostname()
    for name in {hostname, hostname.split(".")[0]}:
        if name.lower() not in GENERIC_HOST_NAMES:
            add("host name", name, minimum=4)
    users = set()
    try:
        users.add(getpass.getuser())
    except (KeyError, OSError):
        pass
    users.update(env.get(k, "") for k in ("USER", "LOGNAME", "USERNAME"))
    for user in users:
        if user and user.lower() not in GENERIC_USER_NAMES:
            add("user name", user)
    home = os.path.expanduser("~")
    if home not in ("/", "~") and len(Path(home).parts) > 1:
        add("home directory", home.rstrip("/"))
    add("git e-mail", _git_email(repo), minimum=3)
    serials = _adb_serials()
    if env.get("ANDROID_SERIAL"):
        serials.append(env["ANDROID_SERIAL"])
    for serial in serials:
        if not re.fullmatch(r"emulator-\d+", serial):
            add("device serial", serial, minimum=4)
    for address in _host_ipv4_addresses():
        add("host address", address)
    for mac in _host_macs():
        add("host MAC", mac)
    return entries


def _public_handle_form(line: str, start: int, end: int) -> bool:
    """The forms of the owner's public GitHub handle the repository may hold: the application id and package path
    (`com.<handle>.`, `com/<handle>/`, allowed by the brief) and the repository's own address (`github.com/<handle>`,
    already in pyproject.toml). Every other occurrence of the user name is a finding."""
    before, after = line[:start], line[end:]
    for sep in "./":
        if before.endswith("com" + sep) and after.startswith(sep):
            head = before[: -len("com" + sep)]
            if not head or not re.match(r"[A-Za-z0-9.]", head[-1]):
                return True
    return bool(re.search(r"(?:^|[^A-Za-z0-9.-])(?:https?://)?(?:www\.)?github\.com/$", before, re.IGNORECASE))


def find_denied(line: str, denylist: list[DenyEntry]):
    """Yields (start, end, kind) for every denylisted value in the line, never the value itself."""
    quick = _prefilter(tuple(denylist))
    if quick is None or not quick.search(line):
        return
    for entry in denylist:
        for match in entry.pattern().finditer(line):
            if entry.kind == "user name" and _public_handle_form(line, match.start(), match.end()):
                continue
            yield match.start(), match.end(), entry.kind


# --------------------------------------------------------------------------------------------------------------------
# Redaction (QA logs)

_REDACTIONS = (
    (PRIVATE_KEY_RE, "<private key>"),
    (re.compile(r"\bPT1\s+device=[^\n]*"), "PT1 <redacted>"),
    (re.compile(r"(?<![A-Za-z0-9_-])pt1_[A-Za-z0-9_-]+"), "pt1_<redacted>"),
    (re.compile(r"\bBearer\s+\S+"), "Bearer <redacted>"),
    (re.compile(r'("(?:code|token|proof|previous_token_proof|challenge|mac|nonce)"\s*:\s*)"[^"]*"'), r'\1"<redacted>"'),
    (re.compile(r"\b(Pairing code:\s*)\d{3} ?\d{3}\b"), r"\1<redacted>"),
    (re.compile(r"\b(srv|dev|lib)_[A-Za-z0-9]{6,}\b"), r"\1_<redacted>"),
)


def redact(text: str, denylist: list[DenyEntry] | None = None, serials: list[str] | None = None) -> str:
    """Replaces credentials, ids, denylisted values, serials, home paths, addresses, MACs and post links."""
    for pattern, replacement in _REDACTIONS:
        text = pattern.sub(replacement, text)
    text = HOME_PATH_RE.sub("~", text)
    for entry in sorted(denylist or [], key=lambda e: -len(e.value)):

        def _deny(match: re.Match, entry: DenyEntry = entry) -> str:
            line_start = match.string.rfind("\n", 0, match.start()) + 1
            line_end = match.string.find("\n", match.end())
            line = match.string[line_start : len(match.string) if line_end < 0 else line_end]
            if entry.kind == "user name" and _public_handle_form(
                line, match.start() - line_start, match.end() - line_start
            ):
                return match.group(0)
            return f"<{entry.kind}>"

        text = entry.pattern().sub(_deny, text)
    for serial in serials or []:
        if serial:
            text = re.sub(rf"(?<![A-Za-z0-9]){re.escape(serial)}(?![A-Za-z0-9])", "<device serial>", text)
    text = URL_RE.sub(lambda m: "<link>" if any(True for _ in find_post_links(m.group(0))) else m.group(0), text)

    def _v4(match: re.Match) -> str:
        address = ipaddress.ip_address(match.group(1))
        prefix = int(match.group(2)) if match.group(2) else None
        return match.group(0) if ipv4_allowed(address, prefix) else f"<{address_class(address)} IPv4>"

    text = IPV4_RE.sub(_v4, text)

    def _v6(match: re.Match) -> str:
        try:
            address = ipaddress.IPv6Address(match.group(1))
        except ValueError:
            return match.group(0)
        if sum(c in "0123456789abcdefABCDEF" for c in match.group(1)) < 4 or ipv6_allowed(address):
            return match.group(0)
        return f"<{address_class(address)} IPv6>"

    text = IPV6_RE.sub(_v6, text)
    text = MAC_RE.sub(lambda m: m.group(0) if mac_allowed(m.group(1)) else "<MAC>", text)
    return text
