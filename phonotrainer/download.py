"""Downloading material from YouTube (and from anything else yt-dlp supports).

The native speech this project cares about lives in videos: downloading them by
hand and dragging them in was one step too many. Files land in `downloads/`
(git-ignored: every clone starts empty) and are reused if already present, so
re-analyzing the same URL does not download it again.

Only what the user explicitly asks for is downloaded, and it is processed
locally; redistributing the files is not this tool's business.
"""

from __future__ import annotations

import re
from pathlib import Path

from .errors import JobCancelled

DEFAULT_DIR = Path("downloads")
# Video up to 720p: more than enough to see the face, and much faster.
VIDEO_FORMAT = "bv*[height<=720]+ba/b[height<=720]/b"
AUDIO_FORMAT = "ba/b"

_URL_RE = re.compile(r"^https?://", re.IGNORECASE)


class DownloadError(RuntimeError):
    """The download failed (invalid URL, no network, private video…)."""


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
             progress=_noop, ydl_factory=_make_ydl) -> Path:
    """Download `url` into `dest_dir` and return the path of the file.

    `progress` receives messages carrying the percentage, so the interface can
    show them just like the pipeline's own. `ydl_factory` is the test seam.
    """
    if not is_url(url):
        raise DownloadError(f"does not look like a URL: {url}")
    dest_dir = Path(dest_dir).expanduser()
    dest_dir.mkdir(parents=True, exist_ok=True)

    last_percent = -1
    last_mb = -1

    def hook(status: dict) -> None:
        nonlocal last_percent, last_mb
        if status.get("status") == "downloading":
            total = status.get("total_bytes") or status.get("total_bytes_estimate") or 0
            done = status.get("downloaded_bytes") or 0
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

    progress("Looking up the URL…")
    try:
        with ydl_factory(options) as ydl:
            info = ydl.extract_info(url, download=True)
            if isinstance(info, dict) and info.get("_type") == "playlist":
                raise DownloadError(
                    "that is a playlist: paste the URL of a single video")
            path = _resolve_path(ydl, info)
    except (DownloadError, JobCancelled):
        raise                           # cancelling is not failing
    except Exception as exc:            # noqa: BLE001 — yt-dlp raises all sorts
        raise DownloadError(str(exc) or f"{type(exc).__name__}") from exc

    if path is None or not path.is_file():
        raise DownloadError(
            f"yt-dlp finished but the file could not be found in {dest_dir}")
    progress(f"Downloaded: {path.name}")
    return path


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
