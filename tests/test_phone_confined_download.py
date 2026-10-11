"""The confined download of a phone's link (server-contract 4.5 and 9 point 7).

The link itself is checked against private addresses (test_phone_jobs.py),
but the page it names can hand over to other URLs, and a service address, a
media address or a redirect can be read from that page. So a phone-origin
download (1) uses no extractor that accepts a link on any host, and (2) runs
inside `ssrf.confined_connections()`, which checks every connect it makes.

Everything here is offline: the "public" pages are served on loopback, and a
second loopback server stands in for a device on the home network (a router,
a NAS); the guard's rule is told which port plays the public site.
"""

from __future__ import annotations

import http.server
import json
import socket
import subprocess
import sys
import threading
from types import SimpleNamespace
from urllib.parse import urlsplit, urlunsplit

import pytest
from phone_fixtures import PUBLIC_V4, PUBLIC_V6

from phonotrainer.download import (CONFINED_EXTRACTORS, DownloadError, DownloadLimits,
                                   _make_confined_ydl, download)
from phonotrainer.phone import ssrf
from phonotrainer.phone.ssrf import (ConnectRefused, LinkRefused, confined_connections,
                                     connect_allowed)
from phonotrainer.phone.state import classify_error

yt_dlp = pytest.importorskip("yt_dlp")

GIB = 1024 ** 3
MEDIASITE_ID = "24aace4429fc450fb5b38cdbf424a66e1d"
MEDIA = b"\x00\x00\x00\x18ftypmp42" + b"PUBLIC-BYTES" * 64
LAN_MEDIA = b"\x00\x00\x00\x18ftypmp42" + b"PRIVATE-LAN-BYTES" * 64

# hygiene: test-vector begin
PRIVATE_HOSTS = ["192.168.1.1", "10.0.0.1", "127.0.0.1", "[::1]", "[fd00::1]",
                 "rebind.attacker.example"]
# hygiene: test-vector end


# --- 1. no extractor that accepts any host ------------------------------------------------

def confined_extractors():
    with yt_dlp.YoutubeDL({"allowed_extractors": CONFINED_EXTRACTORS, "quiet": True}) as ydl:
        return list(ydl._ies.values())


def test_the_extractors_that_accept_any_host_are_left_out():
    from yt_dlp.extractor.mediasite import (MediasiteCatalogIE, MediasiteIE,
                                            MediasiteNamedCatalogIE)

    keys = {ie.ie_key() for ie in confined_extractors()}
    assert "Generic" not in keys
    assert not keys & {"Mediasite", "MediasiteCatalog", "MediasiteNamedCatalog"}
    assert "Youtube" in keys and "Twitter" in keys            # site-specific ones stay
    # what the scan below looks for: Mediasite takes a private literal as its host
    assert MediasiteIE.suitable(f"http://192.168.1.1/Mediasite/Play/{MEDIASITE_ID}")  # hygiene: test-vector
    assert MediasiteCatalogIE.suitable(
        f"https://rebind.attacker.example/Mediasite/Catalog/Full/{MEDIASITE_ID}")
    assert MediasiteNamedCatalogIE.suitable(
        "https://rebind.attacker.example/Mediasite/Catalog/catalogs/lectures")


# URL shapes common to self-hosted video software, on a host the attacker chooses.
SHAPES = ["/", f"/Mediasite/Play/{MEDIASITE_ID}", "/videos/watch/9c9de5e8-0a1e-484a-b099-e80766180a6d",
          "/w/kkGMgK9ZtnKfYAgnEtQxbv", "/video.mp4", "/index.m3u8", "/manifest.mpd",
          "/watch?v=abcdefghijk", "/embed/123456", "/@user/109000000000000000"]


def _own_test_urls(ie) -> list[str]:
    try:
        cases = list(ie.get_testcases(include_onlymatching=True))
    except Exception:                                   # noqa: BLE001 — a broken test table
        cases = []
    return [c["url"] for c in cases
            if isinstance(c.get("url"), str) and c["url"].startswith(("http://", "https://"))]


def test_no_confined_extractor_accepts_a_private_address_or_any_host():
    """A future yt-dlp release that adds an extractor whose links may name any
    host fails this test: each extractor's own example links, with their host
    swapped for a private address or an attacker's name, and common URL shapes
    on such hosts, must all be unsuitable."""
    accepted = []
    for ie in confined_extractors():
        candidates = [f"http://{host}{shape}" for host in PRIVATE_HOSTS for shape in SHAPES]
        for url in _own_test_urls(ie):
            parts = urlsplit(url)
            candidates += [urlunsplit((parts.scheme, host, parts.path, parts.query, ""))
                           for host in PRIVATE_HOSTS]
        for url in candidates:
            try:
                if ie.suitable(url):
                    accepted.append((ie.ie_key(), url))
            except Exception:                           # noqa: BLE001 — a regex error is "no"
                pass
    assert accepted == []


# --- 2. the connect-time guard ---------------------------------------------------------------

@pytest.fixture
def listener():
    """A loopback TCP listener (it only accepts)."""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(16)
    yield server.getsockname()
    server.close()


def connect(address) -> None:
    with socket.create_connection(address, timeout=5):
        pass


def test_connect_allowed_is_global_unicast_only():
    assert connect_allowed(PUBLIC_V4, 443) and connect_allowed(PUBLIC_V6, 443)
    # hygiene: test-vector begin
    for host in ("127.0.0.1", "::1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
                 "::ffff:192.168.1.1", "64:ff9b::a00:1", "2002:c0a8:101::1", "fe80::1%eth0",
                 "0.0.0.0", "224.0.0.1", "192.0.2.10", "localhost", "router.example", ""):
        assert not connect_allowed(host, 80), host
    # hygiene: test-vector end


def test_a_confined_thread_cannot_connect_to_a_local_address(listener):
    with confined_connections() as confinement:
        with pytest.raises(ConnectRefused) as refused:
            connect(listener)
        with pytest.raises(LinkRefused):                 # also a LinkRefused for the job code
            connect(listener)
    assert isinstance(refused.value, OSError)            # yt-dlp's networking treats it as one
    assert isinstance(confinement.refused, LinkRefused)
    connect(listener)                                    # the block is over: allowed again


def test_the_rule_decides_and_other_threads_are_never_confined(listener):
    host, port = listener
    go, done = threading.Event(), threading.Event()
    outcome = []

    def elsewhere():                                     # a thread nobody confined
        go.wait(5)
        try:
            connect(listener)
            outcome.append("connected")
        except OSError:                                  # pragma: no cover - the failure case
            outcome.append("refused")
        done.set()

    other = threading.Thread(target=elsewhere)
    other.start()
    with confined_connections(lambda h, p: p == port) as confinement:
        connect(listener)                                # the seam lets this port through
        with confined_connections(lambda h, p: False):
            with pytest.raises(ConnectRefused):
                connect(listener)
            go.set()                                     # meanwhile, on another thread…
            done.wait(5)
        connect(listener)                                # the outer rule is back
    other.join(5)
    assert outcome == ["connected"]
    assert confinement.refused is None                   # the inner block kept its own


def test_threads_a_confined_thread_starts_are_confined(listener):
    outcome = []

    def worker():
        try:
            connect(listener)
            outcome.append("connected")
        except ConnectRefused:
            outcome.append("refused")

    with confined_connections():
        thread = threading.Thread(target=worker)
        thread.start()
        thread.join(5)
    assert outcome == ["refused"]
    free = threading.Thread(target=worker)
    free.start()
    free.join(5)
    assert outcome == ["refused", "connected"]


def test_ipv4_mapped_and_unix_sockets_are_refused(listener, tmp_path):
    host, port = listener
    if socket.has_ipv6:
        six = socket.socket(socket.AF_INET6)
        try:
            with confined_connections():
                with pytest.raises(ConnectRefused):
                    six.connect(("::ffff:127.0.0.1", port))  # hygiene: test-vector
        finally:
            six.close()
    unix = socket.socket(socket.AF_UNIX)
    try:
        with confined_connections():
            with pytest.raises(ConnectRefused):
                unix.connect(str(tmp_path / "socket"))
    finally:
        unix.close()


def test_a_program_that_would_fetch_a_url_itself_is_not_started(tmp_path):
    marker = tmp_path / "ran"
    script = f"open({str(marker)!r}, 'w').close()"
    with confined_connections() as confinement:
        with pytest.raises(ConnectRefused):
            subprocess.run([sys.executable, "-c", script, "https://example.com/stream.m3u8"],
                           check=False)
        assert not marker.exists()
        # local files (ffmpeg's `file:` arguments when it merges) are fine
        subprocess.run([sys.executable, "-c", script, "file:/tmp/video.mp4"], check=True)
    assert marker.exists()
    assert confinement.refused is not None
    assert ssrf.carries_network_url(["ffmpeg", "-i", "rtmp://example.com/live"])
    assert ssrf.carries_network_url("ffmpeg -i http://example.com/a.m3u8")
    assert not ssrf.carries_network_url(["ffmpeg", "-i", "file:a.mp4", "-c", "copy", "file:b.mp4"])


# --- 3. the real yt-dlp, offline: hand-overs, service calls, media, redirects ---------------

class Recorder:
    def __init__(self) -> None:
        self.hits: list[tuple[str, str]] = []


def _serve(handler_class) -> tuple[http.server.ThreadingHTTPServer, int]:
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler_class)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]


@pytest.fixture
def sites():
    """`site` plays a public Mediasite server, `lan` a device on the home
    network. `site.config` says where the page sends yt-dlp next."""
    lan_log, site_log = Recorder(), Recorder()
    config = {}

    class Base(http.server.BaseHTTPRequestHandler):
        log = None

        def log_message(self, *args):
            pass

        def send(self, body: bytes, ctype: str, status: int = 200, headers=()):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            for name, value in headers:
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(body)

    class Lan(Base):
        def do_GET(self):
            lan_log.hits.append(("GET", self.path))
            self.send(LAN_MEDIA, "video/mp4")

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length") or 0))
            lan_log.hits.append(("POST", self.path))
            self.send(json.dumps(config["options"]).encode(), "application/json")

    class Site(Base):
        def do_GET(self):
            site_log.hits.append(("GET", self.path))
            if self.path.startswith("/Mediasite/Play/"):
                page = f'<html><div id="ServicePath">{config["service"]}</div></html>'
                self.send(page.encode(), "text/html")
            elif self.path == "/redirect.mp4":
                self.send(b"", "text/plain", 302, [("Location", config["redirect_to"])])
            else:
                self.send(MEDIA, "video/mp4")

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length") or 0))
            site_log.hits.append(("POST", self.path))
            self.send(json.dumps(config["options"]).encode(), "application/json")

    lan, lan_port = _serve(Lan)
    site, site_port = _serve(Site)
    base = {"lan": f"http://127.0.0.1:{lan_port}", "site": f"http://127.0.0.1:{site_port}"}

    def player_options(location: str) -> dict:
        return {"d": {"Presentation": {"Title": "A lecture", "Duration": 60000, "Streams": [
            {"StreamType": 0, "VideoUrls": [{"Location": location, "MimeType": "video/mp4",
                                             "MediaType": "MP4"}]}]}}}

    yield SimpleNamespace(base=base, config=config, lan=lan_log, site=site_log,
                          site_port=site_port, player_options=player_options,
                          link=f"{base['site']}/Mediasite/Play/{MEDIASITE_ID}")
    for server in (lan, site):
        server.shutdown()
        server.server_close()


def run_confined(sites, tmp_path, url=None, extractors=("mediasite",)):
    """A phone-origin download through the real confined yt-dlp. The test
    re-allows only the extractors it drives (Mediasite is left out of real
    confined downloads); the guard's rule lets only the public site's port
    through, as global unicast would."""
    def factory(options):
        if extractors is not None:
            options = {**options, "allowed_extractors": list(extractors)}
        return _make_confined_ydl(options)

    limits = DownloadLimits(check_link=lambda link: link,
                            disk_usage=lambda path: SimpleNamespace(free=100 * GIB),
                            connect_allowed=lambda host, port: port == sites.site_port,
                            media_probe=lambda path: None)
    return download(url or sites.link, tmp_path / "downloads", ydl_factory=factory,
                    phone_origin=True, limits=limits, which=lambda name: None, environ={})


def test_the_set_up_downloads_from_the_public_site(sites, tmp_path):
    """The control: everything on the public site, so the download works."""
    sites.config["service"] = f"{sites.base['site']}/svc"
    sites.config["options"] = sites.player_options(f"{sites.base['site']}/media.mp4")
    path = run_confined(sites, tmp_path)
    assert path.read_bytes() == MEDIA
    assert [hit[0] for hit in sites.site.hits] == ["GET", "POST", "GET"]
    assert sites.lan.hits == []


@pytest.mark.parametrize("where", ["service", "media", "redirect"])
def test_a_public_page_cannot_send_the_download_to_the_home_network(sites, tmp_path, where):
    lan, site = sites.base["lan"], sites.base["site"]
    sites.config["service"] = f"{lan}/svc" if where == "service" else f"{site}/svc"
    media = {"service": f"{site}/media.mp4", "media": f"{lan}/private/recording.mp4",
             "redirect": f"{site}/redirect.mp4"}[where]
    sites.config["options"] = sites.player_options(media)
    sites.config["redirect_to"] = f"{lan}/cgi-bin/anything?x=1"
    with pytest.raises(LinkRefused) as refused:
        run_confined(sites, tmp_path)
    assert sites.lan.hits == []                       # not one request reached the device
    assert type(refused.value) is LinkRefused         # the job's text names LinkRefused
    assert classify_error("error", f"LinkRefused: {refused.value}") == "refused_address"
    downloads = tmp_path / "downloads"
    assert not [p for p in downloads.iterdir() if p.is_file()]


def test_an_x_short_link_handing_over_to_the_home_network(sites, tmp_path, monkeypatch):
    """t.co answers with a redirect to wherever the post's author pointed it;
    yt-dlp hands that URL to whichever extractor accepts it. With the real
    confined extractors none accepts a private address; with an extractor that
    did, the connect guard refuses it."""
    import yt_dlp.extractor.twitter as twitter

    target = f"{sites.base['lan']}/Mediasite/Play/{MEDIASITE_ID}"
    sites.config["service"] = f"{sites.base['lan']}/svc"
    sites.config["options"] = sites.player_options(f"{sites.base['lan']}/private.mp4")
    asked = []

    def t_co(self, url, *args, **kwargs):                 # t.co's single request, offline
        asked.append(url)
        return SimpleNamespace(url=target)

    monkeypatch.setattr(twitter.TwitterShortenerIE, "_request_webpage", t_co)
    with pytest.raises(DownloadError) as unsupported:
        run_confined(sites, tmp_path, url="https://t.co/AbCdEf1234", extractors=None)
    assert not isinstance(unsupported.value, LinkRefused)
    assert asked == ["https://t.co/AbCdEf1234"] and sites.lan.hits == []

    with pytest.raises(LinkRefused):
        run_confined(sites, tmp_path, url="https://t.co/AbCdEf1234",
                     extractors=("twitter:shortener", "mediasite"))
    assert sites.lan.hits == []


def test_the_confined_class_uses_only_handlers_the_guard_sees():
    from phonotrainer.download import CONFINED_REQUEST_HANDLERS

    with _make_confined_ydl({"quiet": True}) as ydl:
        handlers = {handler.RH_KEY for handler in ydl._request_director.handlers.values()}
    assert handlers and handlers <= set(CONFINED_REQUEST_HANDLERS)
    assert "CurlCFFI" not in handlers
