"""A job's state as a phone reads it: stage, progress details, title, site,
error code and a clean message.

The job store keeps writing free-text progress and error messages exactly as
before (the browser client parses them); everything here is derived from that
text, so old jobs get stages and error codes too.
"""

from __future__ import annotations

import getpass
import re
from pathlib import Path
from urllib.parse import urlsplit

from ..jobs import CANCELLED, DONE, MEDIA_SUFFIXES, QUEUED, RUNNING, Job

# --- stages ------------------------------------------------------------------------
# The web client's `PREFIXES` (web/src/jobs/stages.ts), entry for entry: a test
# reads that file and fails if the two drift apart.
PREFIXES: tuple[tuple[str, str], ...] = (
    ("Looking up the URL", "download"),
    ("Downloading from YouTube", "download"),
    ("Download finished", "download"),
    ("Downloaded:", "download"),
    ("Extracting audio", "extract"),
    ("Separating dialogue", "separate"),
    ("Dialogue separation skipped", "separate"),
    ("Transcribing", "transcribe"),
    ("Loading phone engine", "phones"),
    ("Loading prosody", "phones"),
    ("Segment ", "phones"),
    ("Saving outputs", "save"),
    ("Generating report", "save"),
)
STAGES = ("download", "extract", "separate", "transcribe", "phones", "save")

_SEGMENT_RE = re.compile(r"Segment (\d+)/(\d+)")
_DOWNLOAD_RE = re.compile(r"Downloading from YouTube… (\d+)%")


def stage_of_message(message: str | None) -> str | None:
    if not message:
        return None
    for prefix, key in PREFIXES:
        if message.startswith(prefix):
            return key
    return None


def _messages(job: Job) -> list[str]:
    return [str(entry.get("message", "")) for entry in list(job.progress)
            if isinstance(entry, dict)]


def stage_of(job: Job) -> str | None:
    """The furthest stage any progress message reached; null while queued,
    when done, or before any stage message."""
    if job.status in (QUEUED, DONE):
        return None
    reached = -1
    for message in _messages(job):
        key = stage_of_message(message)
        if key is not None:
            reached = max(reached, STAGES.index(key))
    return STAGES[reached] if reached >= 0 else None


def progress_of(job: Job) -> dict:
    """`percent` (the store's estimate); while running, `segment`/`segments`
    from `Segment i/n` and `download_percent` from `Downloading from YouTube…
    N%`, read from the latest message of the current stage."""
    details = {"percent": job.percent, "segment": None, "segments": None,
               "download_percent": None}
    if job.status != RUNNING:
        return details
    stage = stage_of(job)
    for message in reversed(_messages(job)):
        if stage_of_message(message) != stage:
            continue
        if stage == "phones":
            m = _SEGMENT_RE.search(message)
            if m:
                details["segment"], details["segments"] = int(m.group(1)), int(m.group(2))
                break
        elif stage == "download":
            m = _DOWNLOAD_RE.search(message)
            if m:
                details["download_percent"] = int(m.group(1))
                break
        else:
            break
    return details


def separation_of(job: Job) -> str | None:
    """`off` (the option is off), `skipped` (the separator was unavailable),
    or null (for done jobs `analysis.dialogue_separation` is authoritative)."""
    if (job.options or {}).get("separate_dialogue") is False:
        return "off"
    if any(m.startswith("Dialogue separation skipped") for m in _messages(job)):
        return "skipped"
    return None


# --- sources -------------------------------------------------------------------------
_YOUTUBE_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com"}
_YOUTUBE_OTHER_HOSTS = {"music.youtube.com", "youtu.be"}
_X_HOSTS = {"x.com", "twitter.com", "mobile.twitter.com", "t.co"}


def site_of(url: str | None) -> str | None:
    """`youtube_shorts`, `youtube`, `x` or `other` for a link; null otherwise."""
    if not url:
        return None
    try:
        parts = urlsplit(str(url).strip())
        host = (parts.hostname or "").lower().rstrip(".")
    except ValueError:
        return "other"
    if host in _YOUTUBE_HOSTS and parts.path.startswith("/shorts/"):
        return "youtube_shorts"
    if host in _YOUTUBE_HOSTS or host in _YOUTUBE_OTHER_HOSTS:
        return "youtube"
    if host in _X_HOSTS:
        return "x"
    return "other"


def source_kind(job: Job) -> str:
    """`import`, `link`, `upload` (the file lives in the job's `media/`) or `path`."""
    if job.imported:
        return "import"
    if job.source_url:
        return "link"
    if job.media_path and Path(job.media_path).parent == Path(job.dir).resolve() / "media":
        return "upload"
    return "path"


_TRAILING_ID_RE = re.compile(r"\s*\[[^\[\]]*\]$")


def clean_title(name: str | None) -> str | None:
    """A file name as a title: no media extension, no trailing ` [<id>]`
    (yt-dlp's template puts the video id there)."""
    if not name:
        return None
    text = Path(str(name)).name
    suffix = Path(text).suffix
    if suffix.lower() in MEDIA_SUFFIXES:
        text = text[: -len(suffix)]
    text = _TRAILING_ID_RE.sub("", text).strip()
    return text or None


def title_of(job: Job) -> str | None:
    kind = source_kind(job)
    meta_source = (job.meta or {}).get("source") if isinstance(job.meta, dict) else None
    if kind == "link":
        if not job.media_path:
            return None                       # a link before its download
        return clean_title(meta_source or job.source)
    if job.status == DONE and meta_source:
        return clean_title(meta_source)
    if kind == "import":
        return clean_title(meta_source or job.source)
    return clean_title(Path(job.media_path).name if job.media_path else job.source)


# --- errors -------------------------------------------------------------------------------
_ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]|\x1b[@-Z\\-_]")

_DOWNLOAD_UNAVAILABLE = (
    "private video", "video unavailable", "this video is private",
    "this video is unavailable", "sign in to confirm", "age-restricted",
    "members-only", "requires authentication", "login required", "has been removed",
    "http error 404", "http error 410", "no video could be found",
)
_NETWORK = (
    "unable to download webpage", "failed to resolve", "name or service not known",
    "temporary failure in name resolution", "timed out", "connection refused",
    "connection reset", "network is unreachable", "incompleteread",
)
_NOT_MEDIA = (
    "does not contain any stream", "invalid data found when processing input",
    "matches no streams", "moov atom not found",
)
# The codes whose failures add the installed yt-dlp version (rules 4 to 10).
DOWNLOAD_CODES = {"live_stream", "too_long", "playlist", "download_forbidden",
                  "download_unavailable", "unsupported_site", "network"}


def strip_ansi(text: str) -> str:
    return _ANSI_RE.sub("", text)


def classify_error(status: str, text: str | None) -> str:
    """The stable job failure code (server-contract 8.2): first match wins,
    case-insensitive, after removing ANSI codes."""
    if status == CANCELLED:
        return "cancelled"
    raw = strip_ansi(text or "").strip()
    low = raw.lower()
    if low.startswith("interrupted: the server stopped"):
        return "interrupted"
    if low.startswith("linkrefused:"):
        return "refused_address"
    if low.startswith("livestreamrefused:"):
        return "live_stream"
    if low.startswith("toolongrefused:"):
        return "too_long"
    if "that is a playlist" in low:
        return "playlist"
    if "http error 403" in low:
        return "download_forbidden"
    if any(p in low for p in _DOWNLOAD_UNAVAILABLE):
        return "download_unavailable"
    if "unsupported url" in low or "no suitable extractor" in low:
        return "unsupported_site"
    if "downloaderror" in low and (any(p in low for p in _NETWORK)
                                   or re.search(r"http error 5\d\d", low)):
        return "network"
    if ("no space left on device" in low or "[errno 28]" in low
            or low.startswith("nospacerefused:")):
        return "no_space"
    if ("no such file or directory: 'ffmpeg'" in low or "ffmpeg: not found" in low
            or "yt-dlp is missing" in low):
        return "tool_missing"
    if ("audioextractionerror" in low and any(p in low for p in _NOT_MEDIA)) \
            or "the uploaded file arrived empty" in low:
        return "not_media"
    if "audioextractionerror" in low:
        return "extract_failed"
    return "processing_failed"


_EXCEPTION_PREFIX_RE = re.compile(
    r"^(?:[A-Za-z_][\w]*\.)*[A-Z]\w*(?:Error|Exception|Refused|Cancelled|Expired|"
    r"Interrupt|Exit|Warning|Failure):\s+")
_ERROR_PREFIX_RE = re.compile(r"^ERROR:\s+")
# An absolute POSIX path of at least two segments, not part of a URL or of a
# word ("MB/s", "N/A", "https://host/a/b" are left alone).
_PATH_RE = re.compile(r"(?<![\w.:/~-])((?:/[^\s/'\"`()<>\[\]{},;]+){2,}/?)")
MAX_MESSAGE_CHARS = 500


def _basename(match: re.Match) -> str:
    path = match.group(1).rstrip("/")
    tail = re.search(r"[.:!?]+$", path)
    trailing = tail.group(0) if tail else ""
    if trailing:
        path = path[: -len(trailing)]
    return path.rsplit("/", 1)[-1] + trailing


def clean_message(text: str | None, home: str | None = None, user: str | None = None) -> str:
    """The error text a phone shows under "Show details": no ANSI codes, no
    leading exception class or `ERROR: `, absolute paths reduced to their base
    names, no home directory or user name, whitespace collapsed, at most 500
    characters."""
    message = strip_ansi(text or "").strip()
    message = _EXCEPTION_PREFIX_RE.sub("", message, count=1)
    message = _ERROR_PREFIX_RE.sub("", message, count=1)
    message = _PATH_RE.sub(_basename, message)
    if home is None:
        home = str(Path.home())
    if user is None:
        try:
            user = getpass.getuser()
        except (KeyError, OSError):
            user = None
    if home and len(home) > 1:
        message = message.replace(home, "~")
    if user and len(user) >= 2:
        message = re.sub(rf"(?<![\w]){re.escape(user)}(?![\w])", "user", message)
    message = " ".join(message.split())
    if len(message) > MAX_MESSAGE_CHARS:
        message = message[: MAX_MESSAGE_CHARS - 1] + "…"
    return message
