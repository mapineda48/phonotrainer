"""A job's state as a phone reads it (server-contract 6.3 and 8; S-13, S-15)."""

import re
from pathlib import Path

import pytest

from phonotrainer.jobs import Job
from phonotrainer.phone import state

ROOT = Path(__file__).resolve().parents[1]
STAGES_TS = ROOT / "web" / "src" / "jobs" / "stages.ts"


def test_the_stage_table_equals_the_web_clients():
    """Drift guard: `PREFIXES` of web/src/jobs/stages.ts, read as text (the web
    client belongs to another agent; when it changes, the integrator updates
    phone/state.py, never the web file)."""
    source = STAGES_TS.read_text(encoding="utf-8")
    block = re.search(r"const PREFIXES[^=]*=\s*\[(.*?)\];", source, re.S)
    assert block, "PREFIXES not found in stages.ts"
    pairs = re.findall(r'\[\s*"([^"]*)"\s*,\s*"([a-z]+)"\s*\]', block.group(1))
    assert tuple(pairs) == state.PREFIXES
    keys = re.search(r"export type StageKey\s*=\s*([^;]+);", source).group(1)
    assert tuple(re.findall(r'"([a-z]+)"', keys)) == state.STAGES


def test_the_download_progress_regex_equals_the_web_clients():
    source = STAGES_TS.read_text(encoding="utf-8")
    assert "const DOWNLOAD_RE = /Downloading from YouTube… (\\d+)%/;" in source
    assert "const SEGMENT_RE = /Segment (\\d+)\\/(\\d+)/;" in source


def _job(status="running", messages=(), **kw) -> Job:
    job = Job(id="20261010-091503-4be1", source=kw.pop("source", "clip.mp4"),
              dir=Path("/srv/workspace/20261010-091503-4be1"), status=status, **kw)
    job.progress = [{"at": "2026-10-10T09:15:03+00:00", "message": m} for m in messages]
    return job


def test_stage_is_the_furthest_reached_and_null_when_queued_or_done():
    assert state.stage_of(_job("queued", ["Looking up the URL…"])) is None
    assert state.stage_of(_job("done", ["Saving outputs…"])) is None
    assert state.stage_of(_job("running", ["Queued → starting…"])) is None
    msgs = ["Queued → starting…", "Extracting audio (ffmpeg → 16 kHz mono WAV)…",
            "Transcribing with faster-whisper small…", "Warning: could not index"]
    assert state.stage_of(_job("running", msgs)) == "transcribe"
    assert state.stage_of(_job("error", ["Looking up the URL…"])) == "download"
    assert state.stage_of(_job("cancelled", msgs + ["Segment 2/9: phones…"])) == "phones"


def test_progress_details_while_running():
    job = _job("running", ["Looking up the URL…", "Downloading from YouTube… 40%"])
    assert state.progress_of(job) == {"percent": job.percent, "segment": None,
                                      "segments": None, "download_percent": 40}
    job = _job("running", ["Transcribing…", "Segment 3/27: phones + alignment…"])
    details = state.progress_of(job)
    assert (details["segment"], details["segments"]) == (3, 27)
    assert details["percent"] == job.percent
    done = _job("done", ["Segment 2/2: phones…"])
    assert state.progress_of(done) == {"percent": 100, "segment": None, "segments": None,
                                       "download_percent": None}


def test_separation():
    assert state.separation_of(_job(options={"separate_dialogue": False})) == "off"
    assert state.separation_of(_job(messages=[
        "Dialogue separation skipped (demucs is not installed)"])) == "skipped"
    assert state.separation_of(_job(options={"separate_dialogue": True})) is None


@pytest.mark.parametrize("url, site", [
    ("https://www.youtube.com/shorts/abc123def45", "youtube_shorts"),
    ("https://m.youtube.com/shorts/abc123def45?feature=share", "youtube_shorts"),
    ("https://youtube.com/watch?v=abc123def45", "youtube"),
    ("https://music.youtube.com/watch?v=abc123def45", "youtube"),
    ("https://youtu.be/abc123def45", "youtube"),
    ("https://youtu.be/shorts/abc123def45", "youtube"),        # youtu.be has no shorts path
    ("https://x.com/someone/status/1234567890", "x"),
    ("https://twitter.com/someone/status/1", "x"),
    ("https://mobile.twitter.com/someone/status/1", "x"),
    ("https://t.co/abcdef", "x"),
    ("https://vimeo.com/123", "other"),
    ("https://notyoutube.com/watch", "other"),
    ("https://youtube.com.example/watch", "other"),
    (None, None),
])
def test_site_labels(url, site):
    assert state.site_of(url) == site


@pytest.mark.parametrize("name, title", [
    ("A video [abc123def45].mp4", "A video"),
    ("Ordering coffee [x].webm", "Ordering coffee"),
    ("clip.wav", "clip"),
    ("Mr. Smith goes to Washington", "Mr. Smith goes to Washington"),
    ("talk.final.mkv", "talk.final"),
    ("notes.txt", "notes.txt"),
    ("[only an id].mp4", None),
    ("", None),
])
def test_titles_are_cleaned(name, title):
    assert state.clean_title(name) == title


def test_source_kinds_and_titles():
    link = _job("queued", source="https://youtu.be/abc123def45",
                source_url="https://youtu.be/abc123def45")
    assert state.source_kind(link) == "link"
    assert state.title_of(link) is None                       # before its download
    link.media_path = "/srv/downloads/A talk [abc].mp4"
    link.source = "A talk [abc].mp4"
    assert state.title_of(link) == "A talk"
    upload = _job("queued", media_path="/srv/workspace/20261010-091503-4be1/media/VID 1.mp4")
    assert state.source_kind(upload) == "upload"
    assert state.title_of(upload) == "VID 1"
    path = _job("queued", media_path="/srv/videos/lesson.mp4")
    assert state.source_kind(path) == "path"
    imported = _job("done", result_dir_override="/srv/out", meta={"source": "show.webm"})
    assert state.source_kind(imported) == "import"
    assert state.title_of(imported) == "show"


# --- error codes (8.2) ----------------------------------------------------------------------

ANSI_403 = ("DownloadError: \x1b[0;31mERROR:\x1b[0m [youtube] abc123def45: unable to download "
            "video data: HTTP Error 403: Forbidden")


@pytest.mark.parametrize("text, code", [
    ("Interrupted: the server stopped during the analysis.", "interrupted"),
    ("LinkRefused: This link points to a private or local address.", "refused_address"),
    ("LiveStreamRefused: Live streams can't be analyzed.", "live_stream"),
    ("TooLongRefused: This video is longer than 3600 seconds.", "too_long"),
    ("DownloadError: that is a playlist: paste the URL of a single video", "playlist"),
    (ANSI_403, "download_forbidden"),
    ("DownloadError: ERROR: [youtube] abc: Private video. Sign in if you've been granted "
     "access to this video", "download_unavailable"),
    ("DownloadError: ERROR: [youtube] abc: Video unavailable. This video is unavailable",
     "download_unavailable"),
    ("DownloadError: ERROR: [youtube] abc: Sign in to confirm your age", "download_unavailable"),
    ("DownloadError: ERROR: [twitter] 1: No video could be found in this tweet",
     "download_unavailable"),
    ("DownloadError: ERROR: unable to download video data: HTTP Error 404: Not Found",
     "download_unavailable"),
    ("DownloadError: ERROR: Unsupported URL: https://example.com/page", "unsupported_site"),
    ("DownloadError: ERROR: No suitable extractor found for URL https://example.com/x",
     "unsupported_site"),
    ("DownloadError: ERROR: [youtube] abc: Unable to download webpage: <urlopen error "
     "[Errno -3] Temporary failure in name resolution>", "network"),
    ("DownloadError: ERROR: [generic] x: HTTP Error 503: Service Unavailable", "network"),
    ("DownloadError: ERROR: The read operation timed out", "network"),
    ("OSError: [Errno 28] No space left on device: 'audio.wav'", "no_space"),
    ("NoSpaceRefused: Your computer is low on space.", "no_space"),
    ("FileNotFoundError: [Errno 2] No such file or directory: 'ffmpeg'", "tool_missing"),
    ('DownloadError: yt-dlp is missing: install it with "uv pip install yt-dlp"',
     "tool_missing"),
    ("AudioExtractionError: ffmpeg failed: clip.mp4: Invalid data found when processing input",
     "not_media"),
    ("AudioExtractionError: Output file does not contain any stream", "not_media"),
    ("JobError: the uploaded file arrived empty", "not_media"),
    ("AudioExtractionError: ffmpeg exited with status 1", "extract_failed"),
    ("RuntimeError: CUDA out of memory", "processing_failed"),
    ("", "processing_failed"),
    (None, "processing_failed"),
])
def test_error_codes(text, code):
    assert state.classify_error("error", text) == code


@pytest.mark.parametrize("text", ["Cancelled before starting.", "Cancelled during the analysis.",
                                  "Interrupted: the server stopped during the analysis."])
def test_cancelled_is_decided_by_the_status(text):
    assert state.classify_error("cancelled", text) == "cancelled"
    assert state.classify_error("error", "Cancelled before starting.") == "processing_failed"


def test_a_network_text_without_a_download_error_is_not_network():
    assert state.classify_error("error", "RuntimeError: Connection refused by the model hub") \
        == "processing_failed"


# --- clean messages (8.3) --------------------------------------------------------------------

def test_clean_message_removes_ansi_prefixes_and_paths():
    assert state.clean_message(ANSI_403, home="/srv/home-of-someone", user="someone") == (
        "[youtube] abc123def45: unable to download video data: HTTP Error 403: Forbidden")
    text = ("AudioExtractionError: ffmpeg failed on /srv/data/videos/my clip.mp4: "
            "'/var/tmp/work/audio.wav' missing.")
    assert state.clean_message(text, home="/srv/home-of-someone", user="someone") == (
        "ffmpeg failed on my clip.mp4: 'audio.wav' missing.")


def test_clean_message_removes_the_home_directory_and_the_user_name():
    text = ("RuntimeError: cannot open ~ /srv/home-of-someone and /tmp/pytest-of-someone/x "
            "for someone; someone_else stays")
    cleaned = state.clean_message(text, home="/srv/home-of-someone", user="someone")
    assert "/srv/home-of-someone" not in cleaned
    assert re.search(r"(?<!\w)someone(?!\w)", cleaned) is None
    assert "someone_else" in cleaned
    assert cleaned.startswith("cannot open")


def test_clean_message_keeps_urls_and_rates():
    text = "DownloadError: ERROR: fetching https://www.youtube.com/watch/a/b at 2 MB/s"
    assert state.clean_message(text, home="/srv/x", user="x") == \
        "fetching https://www.youtube.com/watch/a/b at 2 MB/s"


def test_clean_message_collapses_whitespace_and_is_bounded():
    assert state.clean_message("ValueError:  a \n\t b ", home="/h/x", user="x") == "a b"
    long = "RuntimeError: " + "word " * 400
    cleaned = state.clean_message(long, home="/h/x", user="x")
    assert len(cleaned) == 500 and cleaned.endswith("…")


def test_clean_message_keeps_the_interrupted_and_cancelled_sentences():
    assert state.clean_message("Interrupted: the server stopped during the analysis.") == \
        "Interrupted: the server stopped during the analysis."
    assert state.clean_message("Cancelled before starting.") == "Cancelled before starting."
