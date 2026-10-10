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

Residual risk (README): a supported site's own redirects, followed by the
downloader after the check, could still reach a local address.
"""

from __future__ import annotations

import concurrent.futures
import ipaddress
from typing import Callable
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
