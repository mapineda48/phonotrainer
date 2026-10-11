"""The SSRF guard for links sent by phones (server-contract 4.5; S-07).

A paired phone may ask this computer to download a link. Without a guard, a
link could point the downloader at this computer itself, at the router or at
any other device on the home network. `check_link` runs at three moments for
every phone-origin link: when it is created, when it is re-run, and right
before its download starts (DNS answers can change in between):

1. the URL is trimmed, at most 2,048 characters, `http` or `https`, with a
   host and without a user-info part (otherwise `InvalidLink`);
2. its host is resolved (an IP literal is used as is) with a time limit;
3. every address must be global unicast. IPv4-mapped IPv6 is unwrapped
   first; NAT64 (`64:ff9b::/96`, `64:ff9b:1::/48`), 6to4 (`2002::/16`) and
   Teredo (`2001::/32`) addresses are refused outright, because they can
   carry a private IPv4 address inside a "global" one (`LinkRefused`);
4. a resolution that fails or takes too long is `CannotCheckLink`: a link is
   never accepted unchecked.

The link is only where a download starts: the page it names can hand over to
other URLs (a short link, a post that embeds a video elsewhere), a service
call or a media URL can be read from that page, and any of them can redirect.
So the confined download also runs inside `confined_connections()`: on the
thread that runs it, and on every thread it starts, each socket connect is
checked against the address it is about to reach (after every redirect and
every DNS answer, so DNS rebinding gains nothing), and an outside program
whose arguments carry a network URL (ffmpeg fetching a stream itself, for
example) is not started. A refused connect raises `ConnectRefused`, and the
job fails with `LinkRefused`, the code `refused_address`.
"""

from __future__ import annotations

import concurrent.futures
import contextlib
import ipaddress
import os
import re
import socket
import subprocess
import threading
from typing import Callable, Iterator
from urllib.parse import urlsplit

MAX_URL_CHARS = 2048
RESOLVE_TIMEOUT_S = 3.0

# Ranges that embed an IPv4 address in an IPv6 one, refused whatever they embed.
EMBEDDING_NETWORKS = tuple(ipaddress.ip_network(n) for n in (
    "64:ff9b::/96",       # NAT64, well-known prefix
    "64:ff9b:1::/48",     # NAT64, local-use prefix
    "2002::/16",          # 6to4
    "2001::/32",          # Teredo
))

REFUSED_DETAIL = "This link points to a private or local address."

# Lookups run here so that a resolver stuck in the C library cannot hold the
# caller beyond the time limit (getaddrinfo ignores socket timeouts).
_LOOKUPS = concurrent.futures.ThreadPoolExecutor(max_workers=4,
                                                 thread_name_prefix="phonotrainer-dns")


class InvalidLink(ValueError):
    """Not an http(s) URL with a host, too long, or with a user-info part."""


class LinkRefused(Exception):
    """The link resolves to an address that is not global unicast. The job
    store writes `LinkRefused: <message>`, which `state.classify_error` maps to
    the job code `refused_address`."""

    def __init__(self, message: str = REFUSED_DETAIL) -> None:
        super().__init__(message)


class CannotCheckLink(Exception):
    """The host could not be resolved (or not in time): try again later."""


class ConnectRefused(LinkRefused, ConnectionRefusedError):
    """A connect (or an outside program) the confined download may not make.

    Also an OSError, so yt-dlp's networking turns it into its own transport
    error at once (no "unexpected error" retried with another handler); the
    `Confinement` keeps the refusal, and `download()` fails the job with a
    plain `LinkRefused` (job code `refused_address`)."""


def public_address(text: str) -> bool:
    """Is this address one a link may point to? Global unicast only."""
    try:
        address = ipaddress.ip_address(text.split("%", 1)[0])
    except ValueError:
        return False
    if isinstance(address, ipaddress.IPv6Address):
        if address.ipv4_mapped is not None:
            address = address.ipv4_mapped
        elif any(address in network for network in EMBEDDING_NETWORKS):
            return False
    return address.is_global and not address.is_multicast


def parse_link(url: str) -> tuple[str, str]:
    """(the trimmed URL, its host in ASCII), or InvalidLink."""
    url = str(url or "").strip()
    if not url or len(url) > MAX_URL_CHARS:
        raise InvalidLink("The link is empty or longer than 2,048 characters.")
    if any(ord(ch) <= 0x20 or ord(ch) == 0x7F for ch in url) or "\\" in url:
        raise InvalidLink("The link contains spaces or control characters.")
    try:
        parts = urlsplit(url)
        host = parts.hostname
        parts.port                                  # a malformed port raises ValueError
    except ValueError:
        raise InvalidLink("The link is not a valid URL.") from None
    if parts.scheme.lower() not in ("http", "https"):
        raise InvalidLink("Only http and https links can be analyzed.")
    if not host:
        raise InvalidLink("The link has no host.")
    if "@" in parts.netloc or parts.username is not None or parts.password is not None:
        raise InvalidLink("Links with a user name or password are not accepted.")
    host = host.rstrip(".") or host
    try:
        ipaddress.ip_address(host)
        return url, host                            # an IP literal: used as is
    except ValueError:
        pass
    try:
        ascii_host = host.encode("idna").decode("ascii")
    except UnicodeError:
        raise InvalidLink("The link's host name is not valid.") from None
    return url, ascii_host


def resolve(host: str, resolver: Callable[[str], list[str]],
            timeout_s: float = RESOLVE_TIMEOUT_S) -> list[str]:
    """Every address of `host`, or CannotCheckLink."""
    try:
        literal = ipaddress.ip_address(host)
        return [str(literal)]
    except ValueError:
        pass
    future = _LOOKUPS.submit(resolver, host)
    try:
        addresses = list(future.result(timeout=timeout_s))
    except concurrent.futures.TimeoutError:
        future.cancel()
        raise CannotCheckLink("Looking up the link's host took too long.") from None
    except Exception as exc:                        # noqa: BLE001 — gaierror, OSError…
        raise CannotCheckLink("The link's host could not be looked up.") from exc
    if not addresses:
        raise CannotCheckLink("The link's host has no address.")
    return [str(a) for a in addresses]


def check_link(url: str, resolver: Callable[[str], list[str]],
               timeout_s: float = RESOLVE_TIMEOUT_S) -> str:
    """The trimmed URL when every address it resolves to is public.

    Raises InvalidLink (`400 invalid_url`), LinkRefused (`400 refused_address`,
    or the job code `refused_address` before a download) or CannotCheckLink
    (`503 cannot_check_link`, or the job code `network` before a download).
    """
    trimmed, host = parse_link(url)
    for address in resolve(host, resolver, timeout_s):
        if not public_address(address):
            raise LinkRefused()
    return trimmed


# --- the connect-time guard (server-contract 9 point 7) -----------------------------------

EXTERNAL_TOOL_DETAIL = ("This video needs an outside download tool, which links from phones "
                        "may not use.")

# A URL inside a program's argument: `scheme://`. Only `file:` is local.
_URL_IN_ARGUMENT = re.compile(r"([A-Za-z][A-Za-z0-9+.-]*)://")


def connect_allowed(host: str, port: int) -> bool:
    """The rule for every connect of a confined download: the address it is
    about to reach must be global unicast (`public_address`). A host name
    instead of an address is refused: the C library would resolve it out of
    sight, and the downloaders always connect to addresses."""
    return public_address(host)


class Confinement:
    """One confined download: the rule for its connects, and the first
    refusal, kept even when the downloader catches the exception and carries
    on (`download()` then fails the job as refused all the same)."""

    def __init__(self, allow: Callable[[str, int], bool]) -> None:
        self.allow = allow
        self.active = True
        self.refused: LinkRefused | None = None

    def refuse(self, message: str = REFUSED_DETAIL) -> ConnectRefused:
        refusal = ConnectRefused(message)
        if self.refused is None:
            self.refused = refusal
        return refusal

    def check_connect(self, family: int, address) -> None:
        if (family in (socket.AF_INET, socket.AF_INET6) and isinstance(address, tuple)
                and len(address) >= 2 and isinstance(address[0], str)):
            try:
                if self.allow(address[0], int(address[1])):
                    return
            except (TypeError, ValueError):
                pass
        raise self.refuse()                           # private, local, a name, or a Unix socket

    def check_program(self, argv) -> None:
        if carries_network_url(argv):
            raise self.refuse(EXTERNAL_TOOL_DETAIL)


def carries_network_url(argv) -> bool:
    """Does a program's argument list (or command string) carry a URL other
    than a local `file:` one?"""
    if argv is None:
        return False
    items = argv if isinstance(argv, (list, tuple)) else [argv]
    for item in items:
        try:
            text = os.fsdecode(item) if isinstance(item, (bytes, os.PathLike)) else str(item)
        except (TypeError, ValueError):
            continue
        if any(m.group(1).lower() != "file" for m in _URL_IN_ARGUMENT.finditer(text)):
            return True
    return False


_LOCAL = threading.local()
_HOOKS_LOCK = threading.Lock()
_hooks_installed = False


def current_confinement() -> Confinement | None:
    """The confinement of the calling thread, if a confined download runs on it."""
    confinement = getattr(_LOCAL, "confinement", None)
    return confinement if confinement is not None and confinement.active else None


@contextlib.contextmanager
def confined_connections(allow: Callable[[str, int], bool] | None = None
                         ) -> Iterator[Confinement]:
    """Confine the calling thread (and every thread it starts) while the block
    runs: each socket connect must reach an address `allow(host, port)`
    accepts (`connect_allowed` by default), and no program whose arguments
    carry a network URL is started. Other threads are never affected."""
    _install_hooks()
    previous = getattr(_LOCAL, "confinement", None)
    confinement = Confinement(allow or connect_allowed)
    _LOCAL.confinement = confinement
    try:
        yield confinement
    finally:
        confinement.active = False                    # threads it started are let go too
        _LOCAL.confinement = previous


def _install_hooks() -> None:
    """Wrap, once per process, the three doors a download can leave by:
    `socket.socket.connect` (and `connect_ex`), `subprocess.Popen` and
    `threading.Thread.start` (a thread started by a confined thread is
    confined as well). Outside a confined thread each wrapper only adds one
    thread-local lookup."""
    global _hooks_installed
    with _HOOKS_LOCK:
        if _hooks_installed:
            return
        real_connect = socket.socket.connect
        real_connect_ex = socket.socket.connect_ex
        real_popen_init = subprocess.Popen.__init__
        real_start = threading.Thread.start

        def check(sock, address) -> None:
            confinement = current_confinement()
            if confinement is not None:
                try:
                    confinement.check_connect(sock.family, address)
                except ConnectRefused:
                    sock.close()
                    raise

        def connect(self, address):
            check(self, address)
            return real_connect(self, address)

        def connect_ex(self, address):
            check(self, address)
            return real_connect_ex(self, address)

        def popen_init(self, *args, **kwargs):
            confinement = current_confinement()
            if confinement is not None:
                confinement.check_program(args[0] if args else kwargs.get("args"))
            real_popen_init(self, *args, **kwargs)

        def start(self):
            confinement = current_confinement()
            if confinement is not None:
                run = self.run

                def confined_run():
                    _LOCAL.confinement = confinement
                    try:
                        run()
                    finally:
                        _LOCAL.confinement = None

                self.run = confined_run
            return real_start(self)

        socket.socket.connect = connect
        socket.socket.connect_ex = connect_ex
        subprocess.Popen.__init__ = popen_init
        threading.Thread.start = start
        _hooks_installed = True
