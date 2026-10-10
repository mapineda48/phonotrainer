"""Downloading material from YouTube (and from anything else yt-dlp supports).

The native speech this project cares about lives in videos: downloading them by
hand and dragging them in was one step too many. Files land in `downloads/`
(git-ignored: every clone starts empty) and are reused if already present, so
re-analyzing the same URL does not download it again.

Only what the user explicitly asks for is downloaded, and it is processed
locally; redistributing the files is not this tool's business.
"""

from __future__ import annotations

import os
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from .errors import JobCancelled

DEFAULT_DIR = Path("downloads")
# Video up to 720p: more than enough to see the face, and much faster. H.264
# first (every phone decodes it in hardware), then VP9, then any codec, then a
# single file; AV1 is no longer picked just because a site lists it first.
VIDEO_FORMAT = ("bv*[height<=720][vcodec^=avc]+ba/bv*[height<=720][vcodec~='^vp0?9']+ba/"
                "bv*[height<=720]+ba/b[height<=720]/b")
AUDIO_FORMAT = "ba/b"

_URL_RE = re.compile(r"^https?://", re.IGNORECASE)


# JavaScript runtimes yt-dlp can use to solve YouTube's challenges, in the order
# they are preferred; only the ones installed on PATH are offered to it.
JS_RUNTIMES = ("deno", "node")

# yt-dlp may fetch its JavaScript challenge components from the internet
# (`remote_components`, code downloaded at run time). Never by default: only
# when the operator lists them here, e.g. `ejs:github` (comma-separated).
REMOTE_COMPONENTS_ENV = "PHONOTRAINER_YTDLP_REMOTE_COMPONENTS"

GIB = 1024 ** 3


class DownloadError(RuntimeError):
    """The download failed (invalid URL, no network, private video…)."""


# Refusals of the confined phone-origin download. The job store writes
# "<class name>: <message>" and the phone API maps the class name to a stable
# job code (phone/state.py), so the messages never depend on yt-dlp's wording.
class LiveStreamRefused(DownloadError):
    """A live, upcoming or just-ended live stream (job code `live_stream`)."""


class TooLongRefused(DownloadError):
    """Longer than `max_link_duration_s` or larger than `max_upload_bytes`
    (job code `too_long`)."""


class NoSpaceRefused(DownloadError):
    """Not enough free space for the download (job code `no_space`)."""


@dataclass
class DownloadLimits:
    """The bounds of a phone-origin download (server-contract 9 point 7).

    `check_link(url)` checks the link against private and local addresses
    once more, right before the download (it raises `LinkRefused` or
    `CannotCheckLink`, phone/ssrf.py); None uses the real resolver.
    """

    max_duration_s: int = 3600
    max_bytes: int = 2 * GIB
    min_free_bytes: int = 2 * GIB
    disk_usage: Callable[[Path], object] = shutil.disk_usage
    check_link: Callable[[str], str] | None = None


def js_runtimes(which=shutil.which) -> dict[str, dict]:
    """yt-dlp's `js_runtimes` option: the runtimes found on PATH."""
    return {name: {} for name in JS_RUNTIMES if which(name)}


def chosen_js_runtime(which=shutil.which) -> str | None:
    """The runtime yt-dlp is given first (reported in the phone `health`)."""
    return next(iter(js_runtimes(which)), None)


def remote_components(environ=None) -> list[str]:
    """The remote components the operator allowed (none by default)."""
    value = (os.environ if environ is None else environ).get(REMOTE_COMPONENTS_ENV, "")
    return [item.strip() for item in value.split(",") if item.strip()]


def is_url(text: str) -> bool:
    """Is this a URL or a local path?"""
    return bool(_URL_RE.match(str(text).strip()))


def _noop(message: str) -> None:
    pass


def _make_ydl(options: dict):
    try:
        from yt_dlp import YoutubeDL
    except ImportError as exc:   # pragma: no cover - depends on the environment
        raise DownloadError(
            'yt-dlp is missing: install it with "uv pip install yt-dlp"'
        ) from exc
    return YoutubeDL(options)


def download(url: str, dest_dir: str | Path = DEFAULT_DIR, audio_only: bool = False,
             progress=_noop, ydl_factory=_make_ydl, *, phone_origin: bool = False,
             limits: DownloadLimits | None = None, which=shutil.which,
             environ=None) -> Path:
    """Download `url` into `dest_dir` and return the path of the file.

    `progress` receives messages carrying the percentage, so the interface can
    show them just like the pipeline's own. `ydl_factory` is the test seam.

    `phone_origin` (a link a paired phone sent) runs the download confined:
    site-specific extractors only (never the generic one, which fetches any
    page and the URLs inside it), the link checked against private addresses
    once more, and, before any media byte is fetched, live streams, videos
    longer or larger than `limits` and a lack of free space refused.
    Browser and command-line downloads keep their options.
    """
    if not is_url(url):
        raise DownloadError(f"does not look like a URL: {url}")
    dest_dir = Path(dest_dir).expanduser()
    dest_dir.mkdir(parents=True, exist_ok=True)
    if phone_origin and limits is None:
        limits = DownloadLimits()

    last_percent = -1
    last_mb = -1
    received: dict[str, int] = {}
    written: set[str] = set()                  # files yt-dlp wrote to (phone origin)

    def hook(status: dict) -> None:
        nonlocal last_percent, last_mb
        if status.get("status") == "downloading":
            total = status.get("total_bytes") or status.get("total_bytes_estimate") or 0
            done = status.get("downloaded_bytes") or 0
            if phone_origin:
                written.update(str(status[k]) for k in ("tmpfilename", "filename")
                               if status.get(k))
                # A second bound for sizes not known in advance (the exact
                # total when the site gives it, otherwise what arrived).
                exact = status.get("total_bytes") or 0
                received[str(status.get("filename"))] = max(int(done), int(exact))
                if sum(received.values()) > limits.max_bytes:
                    raise TooLongRefused(_too_large(limits))
            if total:
                percent = int(done * 100 / total)
                if percent != last_percent:      # one message per percentage point
                    last_percent = percent
                    progress(f"Downloading from YouTube… {percent}%")
            else:
                # Live streams and fragmented downloads do not know their size:
                # without this the bar sat silently at 2% the whole time.
                mb = int(done / (1 << 20))
                if mb != last_mb:
                    last_mb = mb
                    progress(f"Downloading from YouTube… {mb} MB")
        elif status.get("status") == "finished":
            progress("Download finished, preparing the file…")

    options = {
        "format": AUDIO_FORMAT if audio_only else VIDEO_FORMAT,
        # The cut-off is in BYTES (the B suffix): 80 CJK characters are 240
        # bytes, and ext4 does not accept names that long.
        "outtmpl": str(dest_dir / "%(title).120B [%(id)s].%(ext)s"),
        "noplaylist": True,
        "playlist_items": "1",   # if the URL is a playlist, only the first video
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "progress_hooks": [hook],
    }
    if not audio_only:
        options["merge_output_format"] = "mp4"
    runtimes = js_runtimes(which)
    if runtimes:
        options["js_runtimes"] = runtimes     # YouTube's challenges need one
    components = remote_components(environ)
    if components:
        options["remote_components"] = components
    oversized = _OversizeLogger()
    if phone_origin:
        options["allowed_extractors"] = ["default", "-generic"]
        options["max_filesize"] = limits.max_bytes
        # yt-dlp skips a file whose announced size exceeds max_filesize with
        # only a screen line ("File is larger than max-filesize"); the logger
        # notices it, so the job fails as too long instead of "not found".
        options["logger"] = oversized

    progress("Looking up the URL…")
    if phone_origin:
        _check_link_again(url, limits)
    try:
        with ydl_factory(options) as ydl:
            if phone_origin:
                # Two phases: look first, refuse before any media byte, then
                # download what was looked up.
                info = ydl.extract_info(url, download=False)
                _refuse_playlist(info)
                _refuse_before_download(info, limits, dest_dir)
                info = ydl.process_ie_result(info, download=True)
            else:
                info = ydl.extract_info(url, download=True)
            _refuse_playlist(info)
            path = _resolve_path(ydl, info)
    except TooLongRefused:
        _remove_written(written, dest_dir)  # a refused download leaves no partial file
        raise
    except (DownloadError, JobCancelled):
        raise                           # cancelling is not failing; refusals keep their class
    except Exception as exc:            # noqa: BLE001 — yt-dlp raises all sorts
        raise DownloadError(str(exc) or f"{type(exc).__name__}") from exc

    if path is None or not path.is_file():
        if phone_origin and oversized.seen:
            _remove_written(written, dest_dir)
            raise TooLongRefused(_too_large(limits))
        raise DownloadError(
            f"yt-dlp finished but the file could not be found in {dest_dir}")
    if phone_origin and path.stat().st_size > limits.max_bytes:
        path.unlink(missing_ok=True)
        _remove_written(written, dest_dir)
        raise TooLongRefused(_too_large(limits))
    progress(f"Downloaded: {path.name}")
    return path


class _OversizeLogger:
    """yt-dlp's `logger` for phone-origin downloads: notices the line with
    which yt-dlp skips a file above `max_filesize`, and writes errors to
    stderr as yt-dlp itself would (warnings are off, as `no_warnings` says)."""

    def __init__(self) -> None:
        self.seen = False

    def debug(self, message: str) -> None:
        if "larger than max-filesize" in str(message):
            self.seen = True

    info = debug

    def warning(self, message: str) -> None:
        pass

    def error(self, message: str) -> None:
        import sys

        print(message, file=sys.stderr)


def _remove_written(paths: set[str], dest_dir: Path) -> None:
    """Delete the (partial) files a refused phone-origin download wrote; only
    inside the download directory."""
    root = dest_dir.resolve()
    for name in paths:
        path = Path(name)
        try:
            if path.resolve().parent == root:
                path.unlink(missing_ok=True)
        except OSError:
            pass


def _refuse_playlist(info) -> None:
    if isinstance(info, dict) and info.get("_type") == "playlist":
        raise DownloadError("that is a playlist: paste the URL of a single video")


def _check_link_again(url: str, limits: DownloadLimits) -> None:
    """The SSRF check right before a phone-origin download (DNS answers can
    change after the link was accepted). A refused address raises
    `LinkRefused` as it is; a failed lookup is a network failure."""
    from .phone.ssrf import CannotCheckLink, check_link

    check = limits.check_link
    if check is None:
        from .phone.settings import default_resolver

        def check(link: str) -> str:
            return check_link(link, default_resolver)
    try:
        check(url)
    except CannotCheckLink as exc:
        raise DownloadError(f"Failed to resolve the link's host: {exc}") from exc


def _number(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if number == number and number >= 0 else None


def expected_bytes(info: dict) -> int:
    """The size the download is expected to have, 0 when unknown: the chosen
    format's, or the sum of the formats merged into one file."""
    size = _number(info.get("filesize")) or _number(info.get("filesize_approx"))
    if size:
        return int(size)
    total = 0
    for fmt in info.get("requested_formats") or []:
        if isinstance(fmt, dict):
            total += int(_number(fmt.get("filesize")) or _number(fmt.get("filesize_approx")) or 0)
    return total


def _too_large(limits: DownloadLimits) -> str:
    return (f"The video is larger than this computer's limit for phone links "
            f"({limits.max_bytes // (1 << 20)} MB).")


def _refuse_before_download(info, limits: DownloadLimits, dest_dir: Path) -> None:
    """Live streams, over-long or over-large videos and a lack of free space,
    refused from the looked-up info, before any media byte is fetched."""
    if not isinstance(info, dict):
        return
    if info.get("is_live") or info.get("live_status") in ("is_live", "is_upcoming", "post_live"):
        raise LiveStreamRefused("This is a live or upcoming stream; streams can't be analyzed.")
    duration = _number(info.get("duration"))
    if duration is not None and duration > limits.max_duration_s:
        raise TooLongRefused(
            f"The video lasts {duration / 60:.0f} minutes; links from phones may last up to "
            f"{limits.max_duration_s / 60:.0f} minutes on this computer.")
    expected = expected_bytes(info)
    if expected > limits.max_bytes:
        raise TooLongRefused(_too_large(limits))
    try:
        free = int(limits.disk_usage(dest_dir).free)
    except OSError:
        return
    if free - expected < limits.min_free_bytes:
        raise NoSpaceRefused("This computer is low on space for this download.")


def _resolve_path(ydl, info) -> Path | None:
    """The final path: after remuxing, the extension is not the template's."""
    if not isinstance(info, dict):
        return None
    requested = info.get("requested_downloads") or []
    if requested and requested[0].get("filepath"):
        return Path(requested[0]["filepath"])
    if info.get("filepath"):
        return Path(info["filepath"])
    try:
        candidate = Path(ydl.prepare_filename(info))
    except Exception:                   # noqa: BLE001
        return None
    if candidate.is_file():
        return candidate
    # It was remuxed to another extension: look it up by the video id, which is
    # part of the file name.
    matches = sorted(candidate.parent.glob(f"*[[]{info.get('id', '')}[]]*"))
    return matches[0] if matches else None
