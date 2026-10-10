"""The files a phone may download for a job, their checksums and the media
facts of the original.

Checksums are computed by a background thread (at start, after each finished
job) and cached in `<job>/phone/checksums.json` keyed by (name, size,
mtime_ns): the listing never hashes inline, so a large original never delays
it; an uncached file reads `preparing` for a few seconds. The media facts of
the original come from ffprobe and are cached in `<job>/phone/media.json`.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import queue
import tempfile
import threading
from pathlib import Path
from typing import Callable

from ..jobs import DONE, MEDIA_SUFFIXES, MEDIA_TYPES, VIDEO_SUFFIXES, Job, JobNotFound, JobStore

log = logging.getLogger("phonotrainer.phone")

PHONE_DIR = "phone"
CHECKSUMS_FILE = "checksums.json"
MEDIA_FILE = "media.json"
HASH_CHUNK = 1 << 20

# The artifact table of v1-core (server-contract 6.4.1): name → (role, content type).
# The content type of `original` comes from the closed MEDIA_TYPES table.
ARTIFACTS: dict[str, tuple[str, str | None]] = {
    "analysis": ("clip_data", "application/json"),
    "audio_mix": ("audio_mix", "audio/wav"),
    "audio_dialogue": ("audio_dialogue", "audio/wav"),
    "original": ("video", None),
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while chunk := fh.read(HASH_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def _write_json(path: Path, data: dict) -> None:
    """Atomic write into an existing directory (never creates the job's)."""
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass
        raise


# --- media facts ---------------------------------------------------------------------

def _float(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if number == number else None          # NaN → None


def _int(value) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _rotation(stream: dict) -> int:
    """0, 90, 180 or 270: the display matrix side data (ffprobe reports the
    counter-clockwise angle, e.g. -90 for a phone held upright) or the older
    `rotate` tag (the clockwise angle, e.g. 90)."""
    degrees = None
    for side in stream.get("side_data_list") or []:
        if isinstance(side, dict) and side.get("rotation") is not None:
            angle = _float(side.get("rotation"))
            if angle is not None:
                degrees = -angle
                break
    if degrees is None:
        angle = _float((stream.get("tags") or {}).get("rotate"))
        degrees = angle if angle is not None else 0.0
    return int(round(degrees / 90.0)) % 4 * 90


def kind_from_suffix(name: str) -> str:
    suffix = Path(name).suffix.lower()
    if suffix in VIDEO_SUFFIXES:
        return "video"
    if suffix in MEDIA_SUFFIXES:
        return "audio"
    return "none"


def unknown_facts(kind: str) -> dict:
    return {"kind": kind, "codec": None, "width": None, "height": None,
            "rotation": 0, "start_offset_ms": 0, "duration_s": None}


def facts_from_probe(probe: dict | None, file_name: str) -> dict:
    """ffprobe's JSON → the PhoneJob `media` object (server-contract 6.4.3)."""
    if probe is None:
        return unknown_facts("none")                # ffprobe could not read the file
    streams = [s for s in probe.get("streams") or [] if isinstance(s, dict)]
    video = next((s for s in streams if s.get("codec_type") == "video"
                  and not (s.get("disposition") or {}).get("attached_pic")), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    facts = unknown_facts("video" if video else "audio" if audio else "none")
    if video is not None:
        facts["codec"] = video.get("codec_name") or None
        facts["width"] = _int(video.get("width"))
        facts["height"] = _int(video.get("height"))
        facts["rotation"] = _rotation(video)
        audio_start = _float(audio.get("start_time")) if audio else None
        video_start = _float(video.get("start_time"))
        if audio_start is not None and video_start is not None:
            facts["start_offset_ms"] = int(round((audio_start - video_start) * 1000))
    duration = _float((probe.get("format") or {}).get("duration"))
    facts["duration_s"] = round(duration, 3) if duration is not None else None
    return facts


# --- the index ------------------------------------------------------------------------

class ArtifactIndex:
    """Listed artifacts, cached checksums and media facts; a background warmer."""

    def __init__(self, store: JobStore, probe: Callable[[Path], dict | None]) -> None:
        self.store = store
        self.probe = probe
        self._checksums: dict[str, dict[str, dict]] = {}
        self._media: dict[str, dict] = {}
        self._lock = threading.Lock()
        self._queue: queue.SimpleQueue[str | None] = queue.SimpleQueue()
        self._pending: set[str] = set()
        self._thread: threading.Thread | None = None
        self._unsubscribe: Callable[[], None] | None = None

    # --- which files ---------------------------------------------------------------
    @staticmethod
    def files(job: Job) -> list[tuple[str, Path | None]]:
        """(name, file) for each artifact listed for this job; the file is None
        when it is gone (`unavailable`)."""
        if job.status != DONE:
            return []
        listed: list[tuple[str, Path | None]] = []
        analysis = job.artifact("analysis.json")
        if analysis is not None:
            listed.append(("analysis", analysis))
        mix = job.artifact("audio.wav")
        if mix is not None:
            listed.append(("audio_mix", mix))
        dialogue = job.dialogue_audio()
        if dialogue is not None:
            listed.append(("audio_dialogue", dialogue))
        if job.media_path and Path(job.media_path).suffix.lower() in VIDEO_SUFFIXES:
            original = Path(job.media_path)
            listed.append(("original", original if original.is_file() else None))
        return listed

    @staticmethod
    def content_type(name: str, path: Path | None) -> str:
        role_type = ARTIFACTS[name][1]
        if role_type is not None:
            return role_type
        suffix = path.suffix.lower() if path is not None else ""
        return MEDIA_TYPES.get(suffix, "application/octet-stream")

    # --- checksum cache ------------------------------------------------------------
    def _cache_of(self, job: Job) -> dict[str, dict]:
        with self._lock:
            cached = self._checksums.get(job.id)
        if cached is not None:
            return cached
        try:
            data = json.loads((Path(job.dir) / PHONE_DIR / CHECKSUMS_FILE)
                              .read_text(encoding="utf-8"))
            files = data.get("files") if isinstance(data, dict) else None
            cached = {k: v for k, v in (files or {}).items() if isinstance(v, dict)}
        except (OSError, json.JSONDecodeError):
            cached = {}
        with self._lock:
            return self._checksums.setdefault(job.id, cached)

    def checksum(self, job: Job, name: str, path: Path) -> tuple[int, str] | None:
        """(bytes, sha256) when the cached checksum matches the file's current
        size and modification time; None otherwise."""
        try:
            st = path.stat()
        except OSError:
            return None
        entry = self._cache_of(job).get(name)
        if (entry and entry.get("bytes") == st.st_size
                and entry.get("mtime_ns") == st.st_mtime_ns and entry.get("sha256")):
            return st.st_size, entry["sha256"]
        return None

    def entries(self, job: Job) -> list[dict]:
        """The `artifacts` list of a PhoneJob."""
        result = []
        missing = False
        for name, path in self.files(job):
            role = ARTIFACTS[name][0]
            typed = path if path is not None else Path(job.media_path or "")
            entry = {"name": name, "role": role, "status": "unavailable", "bytes": None,
                     "sha256": None, "revision": None,
                     "content_type": self.content_type(name, typed), "url": None,
                     "error": None}
            if path is not None:
                known = self.checksum(job, name, path)
                if known is None:
                    entry["status"] = "preparing"
                    missing = True
                else:
                    size, sha = known
                    entry.update(status="ready", bytes=size, sha256=sha, revision=sha[:16],
                                 url=f"/api/phone/jobs/{job.id}/artifacts/{name}")
            result.append(entry)
        if missing:
            self.request(job.id)
        return result

    def media_facts(self, job: Job) -> dict | None:
        """The original's facts; null until probed (imported jobs: kind none)."""
        if job.imported or not job.media_path:
            return unknown_facts("none") if job.status == DONE else None
        with self._lock:
            facts = self._media.get(job.id)
        if facts is not None:
            return dict(facts)
        try:
            data = json.loads((Path(job.dir) / PHONE_DIR / MEDIA_FILE).read_text(encoding="utf-8"))
            facts = data.get("facts") if isinstance(data, dict) else None
        except (OSError, json.JSONDecodeError):
            facts = None
        if isinstance(facts, dict):
            with self._lock:
                self._media[job.id] = facts
            return dict(facts)
        return None

    # --- the warmer --------------------------------------------------------------------
    def warm(self, job_id: str) -> bool:
        """Compute what is missing for one job (checksums, media facts). Returns
        True when something changed. Runs on the warmer thread; callable
        directly from tests."""
        try:
            job = self.store.get(job_id)
        except JobNotFound:
            return False
        if job.status != DONE:
            return False
        phone_dir = Path(job.dir) / PHONE_DIR
        changed = False
        cache = dict(self._cache_of(job))
        for name, path in self.files(job):
            if path is None or self.checksum(job, name, path) is not None:
                continue
            try:
                before = path.stat()
                sha = sha256_file(path)
                after = path.stat()
            except OSError:
                continue
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                continue                        # changed while hashing: next time
            cache[name] = {"bytes": after.st_size, "mtime_ns": after.st_mtime_ns,
                           "sha256": sha}
            changed = True
        if changed:
            if not self._persist(job, phone_dir, CHECKSUMS_FILE,
                                 {"version": 1, "files": cache}):
                return False
            with self._lock:
                self._checksums[job.id] = cache
        if not job.imported and job.media_path and self.media_facts(job) is None:
            facts = self._probe(Path(job.media_path))
            if self._persist(job, phone_dir, MEDIA_FILE, {"version": 1, "facts": facts}):
                with self._lock:
                    self._media[job.id] = facts
                changed = True
        if changed:
            try:
                self.store.touch(job_id)        # the revision changed: tell the channels
            except JobNotFound:
                pass
        return changed

    def _probe(self, path: Path) -> dict:
        if not path.is_file():
            return unknown_facts(kind_from_suffix(path.name))
        try:
            probe = self.probe(path)
        except Exception:                       # noqa: BLE001 — ffprobe missing, timeout…
            return unknown_facts(kind_from_suffix(path.name))
        return facts_from_probe(probe, path.name)

    def _persist(self, job: Job, phone_dir: Path, name: str, data: dict) -> bool:
        """Write next to the job, but never re-create a deleted job's directory."""
        try:
            self.store.get(job.id)
            phone_dir.mkdir(exist_ok=True)      # no parents: the job dir must exist
            _write_json(phone_dir / name, data)
            return True
        except (JobNotFound, OSError):
            return False

    def request(self, job_id: str) -> None:
        """Ask the warmer to look at a job (deduplicated)."""
        with self._lock:
            if job_id in self._pending or self._thread is None:
                return
            self._pending.add(job_id)
        self._queue.put(job_id)

    def _on_store_event(self, event: str, job_id: str) -> None:
        if event == "deleted":
            with self._lock:
                self._checksums.pop(job_id, None)
                self._media.pop(job_id, None)
            return
        if event == "upsert":
            try:
                if self.store.get(job_id).status == DONE:
                    self.request(job_id)
            except JobNotFound:
                pass

    def _loop(self) -> None:
        while True:
            job_id = self._queue.get()
            if job_id is None:
                return
            with self._lock:
                self._pending.discard(job_id)
            try:
                self.warm(job_id)
            except Exception:                   # noqa: BLE001 — the thread must not die
                log.exception("Could not prepare a job's files for phones.")

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._loop, name="phonotrainer-phone-files",
                                        daemon=True)
        self._thread.start()
        self._unsubscribe = self.store.subscribe(self._on_store_event)
        for job in self.store.list():
            if job.status == DONE:
                self.request(job.id)

    def stop(self) -> None:
        if self._unsubscribe is not None:
            self._unsubscribe()
            self._unsubscribe = None
        if self._thread is not None:
            self._queue.put(None)
            # A daemon thread: Ctrl-C never waits for a large file being hashed.
            self._thread.join(timeout=1)
            self._thread = None
