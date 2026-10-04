"""Analysis job store for the web interface.

A *job* is one run of `pipeline.analyze` (or the import of an already existing
output directory, e.g. the `out/` left behind by the CLI). Jobs run one at a
time in a separate thread —the pipeline is CPU-bound and loads ~1.8 GB of
models— and the textual progress the pipeline emits is stored with a timestamp
so the UI can show it live.

Each job lives in `<root>/<id>/`:

    job.json                metadata + progress (source of truth on restart)
    media/<file>            only if the file was uploaded through the UI
    audio.wav, analysis.json, canonical.json, phones_real.json, report.html
    audio_dialogue.wav      the separated dialogue, when separation ran

Local files given by path are NOT copied: they are referenced. Imported jobs
store a `result_dir` pointing outside the workspace.
"""

from __future__ import annotations

import json
import queue
import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .errors import JobCancelled   # raised from the progress callback

__all__ = ["JobCancelled", "JobError", "JobNotFound", "JobStore", "Job"]

QUEUED = "queued"
RUNNING = "running"
DONE = "done"
ERROR = "error"
CANCELLED = "cancelled"

MEDIA_SUFFIXES = {".webm", ".mp4", ".mkv", ".mov", ".avi", ".m4v",
                  ".mp3", ".wav", ".m4a", ".flac", ".ogg", ".opus", ".aac"}
VIDEO_SUFFIXES = {".webm", ".mp4", ".mkv", ".mov", ".avi", ".m4v"}

# job.json is only the record that survives a restart: the hot source of truth
# is memory. Dumping it on every progress message would do I/O inside the lock
# and delay, among other things, cancellations.
SAVE_INTERVAL = 1.0  # s

# Downloaded videos go here (git-ignored: every clone starts clean).
DOWNLOAD_DIR = Path("downloads")


class JobError(RuntimeError):
    """Invalid request against the store (non-existent path, directory without
    an analysis.json…). The server turns it into a 400."""


class JobNotFound(JobError):
    """The job does not exist. The server turns it into a 404."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def analysis_key(result_dir: str | Path) -> str:
    """Identity of an analysis within the corpus: where its analysis.json lives.

    This way the same `out/` analyzed by the CLI and later imported into the
    interface is a single entry instead of being counted twice.
    """
    return str(Path(result_dir).resolve())


# The pipeline emits "Segment 3/27: …" inside the long loop and the download
# "Downloading from YouTube… 40%"; the remaining stages are fixed milestones.
# With those we estimate a percentage without touching pipeline.py or
# download.py.
_SEG_RE = re.compile(r"Segment (\d+)/(\d+)")
_DOWNLOAD_RE = re.compile(r"Downloading from YouTube… (\d+)%")
_STAGE_PERCENT = (
    ("Looking up the URL", 1),
    # With no known size the download reports in MB: without this entry the
    # percentage dropped back to 0 and the bar went backwards.
    ("Downloading from YouTube", 2),
    ("Download finished", 15),
    ("Downloaded:", 16),
    ("Extracting audio", 17),
    ("Separating dialogue", 18),
    ("Dialogue separation skipped", 20),
    ("Transcribing", 21),
    ("Loading phone engine", 27),
    ("Loading prosody", 31),
    ("Saving outputs", 96),
    ("Generating report", 98),
)
_SEG_FLOOR, _SEG_SPAN = 33, 62
_DL_FLOOR, _DL_SPAN = 2, 13


def estimate_percent(message: str | None) -> int:
    """Approximate percentage derived from the latest progress message."""
    if not message:
        return 0
    m = _SEG_RE.search(message)
    if m:
        done, total = int(m.group(1)), max(int(m.group(2)), 1)
        return int(_SEG_FLOOR + _SEG_SPAN * min(done / total, 1.0))
    m = _DOWNLOAD_RE.search(message)
    if m:
        return int(_DL_FLOOR + _DL_SPAN * min(int(m.group(1)) / 100, 1.0))
    for prefix, pct in _STAGE_PERCENT:
        if message.startswith(prefix):
            return pct
    return 0


@dataclass
class Job:
    id: str
    source: str
    dir: Path
    status: str = QUEUED
    options: dict = field(default_factory=dict)
    media_path: str | None = None
    source_url: str | None = None            # jobs that start by downloading
    audio_only: bool = False                 # a download option, not a pipeline one
    result_dir_override: str | None = None   # imported jobs: output outside the workspace
    created: str = field(default_factory=_now)
    started: str | None = None
    finished: str | None = None
    progress: list[dict] = field(default_factory=list)
    error: str | None = None
    meta: dict | None = None
    summary: dict | None = None

    # --- runtime (not serialized) ------------------------------------------
    cancel_requested: bool = field(default=False, repr=False)
    delete_when_done: bool = field(default=False, repr=False)
    last_saved: float = field(default=0.0, repr=False)

    @property
    def imported(self) -> bool:
        return self.result_dir_override is not None

    @property
    def result_dir(self) -> Path:
        return Path(self.result_dir_override) if self.result_dir_override else self.dir

    @property
    def percent(self) -> int:
        if self.status == DONE:
            return 100
        last = self.progress[-1]["message"] if self.progress else None
        return estimate_percent(last)

    def artifact(self, name: str) -> Path | None:
        path = self.result_dir / name
        return path if path.is_file() else None

    def dialogue_audio(self) -> Path | None:
        """The separated dialogue THIS analysis read, if separation ran for it: a
        file left in the directory by an earlier run is not it."""
        separation = (self.meta or {}).get("dialogue_separation") or {}
        if not separation.get("applied"):
            return None
        return self.artifact(Path(separation.get("audio") or "audio_dialogue.wav").name)

    def to_dict(self) -> dict:
        """The shape persisted in job.json."""
        return {
            "id": self.id,
            "source": self.source,
            "status": self.status,
            "options": self.options,
            "media_path": self.media_path,
            "source_url": self.source_url,
            "audio_only": self.audio_only,
            "result_dir_override": self.result_dir_override,
            "created": self.created,
            "started": self.started,
            "finished": self.finished,
            "progress": list(self.progress),   # copy: serialized outside the lock
            "error": self.error,
            "meta": self.meta,
            "summary": self.summary,
        }

    def to_public(self, full: bool = False) -> dict:
        """The shape the UI consumes. `full=False` omits the progress log."""
        media = Path(self.media_path) if self.media_path else None
        data = {
            "id": self.id,
            "source": self.source,
            "source_url": self.source_url,
            "status": self.status,
            "options": self.options,
            "created": self.created,
            "started": self.started,
            "finished": self.finished,
            "error": self.error,
            "imported": self.imported,
            "result_dir": str(self.result_dir),
            "percent": self.percent,
            "last_message": self.progress[-1]["message"] if self.progress else None,
            "has_analysis": self.artifact("analysis.json") is not None,
            "has_audio": self.artifact("audio.wav") is not None,
            "has_dialogue_audio": self.dialogue_audio() is not None,
            "has_report": self.artifact("report.html") is not None,
            "has_review": self.artifact("review.json") is not None,
            "has_media": bool(media and media.is_file()),
            "is_video": bool(media and media.suffix.lower() in VIDEO_SUFFIXES),
            "meta": self.meta,
            "summary": self.summary,
        }
        if full:
            # Copy: the worker may be appending lines while the WebSocket
            # serializes this to send it.
            data["progress"] = list(self.progress)
        return data


def _load_job(job_dir: Path) -> Job | None:
    try:
        raw = json.loads((job_dir / "job.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    job = Job(
        id=raw.get("id", job_dir.name),
        source=raw.get("source", job_dir.name),
        dir=job_dir,
        status=raw.get("status", DONE),
        options=raw.get("options") or {},
        media_path=raw.get("media_path"),
        source_url=raw.get("source_url"),
        audio_only=bool(raw.get("audio_only")),
        result_dir_override=raw.get("result_dir_override"),
        created=raw.get("created") or _now(),
        started=raw.get("started"),
        finished=raw.get("finished"),
        progress=raw.get("progress") or [],
        error=raw.get("error"),
        meta=raw.get("meta"),
        summary=raw.get("summary"),
    )
    # A job left "running" on disk means the server died halfway through.
    if job.status in (RUNNING, QUEUED):
        job.status = ERROR
        job.error = "Interrupted: the server stopped during the analysis."
    return job


class JobStore:
    """Job registry whose runs are serialized on a single worker thread.

    The thread is a *daemon* consuming a queue: that way `Ctrl-C` on the server
    closes the process immediately even with an analysis half done (which can
    take minutes to reach its next cancellation point).
    """

    def __init__(self, root: str | Path, analyze_fn=None, corpus=None,
                 download_dir: str | Path | None = None, download_fn=None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.corpus = corpus                 # SQLite index (optional)
        self.download_dir = Path(download_dir) if download_dir else DOWNLOAD_DIR
        self._analyze_fn = analyze_fn
        self._download_fn = download_fn
        self._lock = threading.RLock()
        self._jobs: dict[str, Job] = {}
        self._listeners: set[Callable[[str, str], None]] = set()
        # The queue carries the Job, not its id: if it is deleted while queued,
        # the worker still holds it and can clean up its directory.
        self._queue: queue.SimpleQueue[Job | None] = queue.SimpleQueue()
        self._worker = threading.Thread(target=self._worker_loop,
                                        name="phonotrainer-job", daemon=True)
        self._worker.start()
        self._discover()
        self._reconcile_corpus()

    # --- lifecycle -----------------------------------------------------------
    def _discover(self) -> None:
        for job_dir in sorted(p for p in self.root.iterdir() if p.is_dir()):
            job = _load_job(job_dir)
            if job is not None:
                self._jobs[job.id] = job

    def _reconcile_corpus(self) -> None:
        """The corpus is a derived index: it is brought up to date with what is
        in the workspace, in both directions.

        Adding: a new database or a deleted `data/` → whatever already exists is
        reindexed. Purging: a job deleted while it was running may have left its
        row behind (or its job.json was deleted by hand). Only rows left by a
        job are touched: the ones the CLI indexed have no job_id and are none of
        our business.
        """
        if self.corpus is None:
            return
        try:
            registered = self.corpus.analyses()
        except Exception:                             # noqa: BLE001
            return
        known = {row["id"] for row in registered}

        # Purge by what no longer exists ON DISK, not by "this is not one of my
        # jobs": with the latter rule, opening the interface on another
        # workspace deleted from the corpus everything the first one indexed.
        for row in registered:
            if not row["job_id"]:
                continue                              # the CLI indexed it: not our business
            if not (Path(row["id"]) / "analysis.json").is_file():
                try:
                    self.corpus.forget(row["id"])
                    known.discard(row["id"])
                except Exception:                     # noqa: BLE001
                    pass

        for job in list(self._jobs.values()):
            if job.status != DONE or analysis_key(job.result_dir) in known:
                continue
            path = job.artifact("analysis.json")
            if path is None:
                continue
            try:
                self.corpus.index_file(analysis_key(job.result_dir), path,
                                       source=job.source, job_id=job.id)
            except Exception:                         # noqa: BLE001 — an unreadable
                continue                              # analysis must not block startup

    def _worker_loop(self) -> None:
        while True:
            job = self._queue.get()
            if job is None:
                return
            try:
                self._run(job)
            except Exception:      # noqa: BLE001 — the thread must never die
                pass

    def shutdown(self, wait: bool = False) -> None:
        with self._lock:
            for job in self._jobs.values():
                if job.status in (QUEUED, RUNNING):
                    job.cancel_requested = True
        self._queue.put(None)
        if wait:
            self._worker.join(timeout=5)

    # --- listeners (WebSocket) -----------------------------------------------
    def subscribe(self, listener: Callable[[str, str], None]) -> Callable[[], None]:
        """Register a change listener; returns the function that unregisters it.

        The listener receives `(event, job_id)` —"upsert" (created or changed)
        or "deleted"— and is invoked FROM THE THREAD that made the change (the
        worker, the HTTP request…): it must be thread-safe and fast. It only
        carries the id: whoever publishes the state re-reads it fresh, so a
        stale event can never make the client go backwards.
        """
        with self._lock:
            self._listeners.add(listener)

        def unsubscribe() -> None:
            with self._lock:
                self._listeners.discard(listener)

        return unsubscribe

    def _notify(self, event: str, job_id: str) -> None:
        with self._lock:
            listeners = list(self._listeners)
        for listener in listeners:
            try:
                listener(event, job_id)
            except Exception:      # noqa: BLE001 — a broken listener must not
                pass               # break the analysis

    def touch(self, job_id: str) -> None:
        """Signal that the job changed outside the store (e.g. review.json)."""
        self.get(job_id)                       # 404 if it does not exist
        self._notify("upsert", job_id)

    # --- queries -------------------------------------------------------------
    def list(self) -> list[Job]:
        with self._lock:
            return sorted(self._jobs.values(), key=lambda j: j.created, reverse=True)

    def get(self, job_id: str) -> Job:
        with self._lock:
            job = self._jobs.get(job_id)
        if job is None:
            raise JobNotFound(f"unknown job: {job_id}")
        return job

    # --- creation ------------------------------------------------------------
    def _new_id(self) -> str:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        return f"{stamp}-{uuid.uuid4().hex[:4]}"

    def create(self, media_path: str | Path, options: dict | None = None,
               source: str | None = None) -> Job:
        """Register an analysis and queue it. `media_path` must exist."""
        media_path = Path(media_path).expanduser()
        if not media_path.is_file():
            raise JobError(f"file does not exist: {media_path}")
        return self._enqueue(source or media_path.name, media_path, options)

    def adopt_upload(self, filename: str, data_writer, options: dict | None = None) -> Job:
        """Create the job and let `data_writer(destination)` write the uploaded file."""
        job_id = self._new_id()
        media_dir = self.root / job_id / "media"
        media_dir.mkdir(parents=True, exist_ok=True)
        dest = media_dir / Path(filename).name
        data_writer(dest)
        if not dest.is_file() or dest.stat().st_size == 0:
            _rmtree(self.root / job_id)      # leave no traces of a failed upload
            raise JobError("the uploaded file arrived empty")
        return self._enqueue(dest.name, dest, options, job_id=job_id)

    def _enqueue(self, source: str, media_path: Path, options: dict | None,
                 job_id: str | None = None) -> Job:
        job_id = job_id or self._new_id()
        job_dir = self.root / job_id
        job_dir.mkdir(parents=True, exist_ok=True)
        job = Job(id=job_id, source=source, dir=job_dir,
                  options=dict(options or {}), media_path=str(media_path.resolve()))
        with self._lock:
            self._jobs[job_id] = job
        self._save(job)
        self._notify("upsert", job_id)
        self._queue.put(job)
        return job

    def create_from_url(self, url: str, options: dict | None = None,
                        audio_only: bool = False) -> Job:
        """Queue an analysis that starts by downloading the video."""
        url = str(url).strip()
        if not url:
            raise JobError("a URL is required")
        job_id = self._new_id()
        job_dir = self.root / job_id
        job_dir.mkdir(parents=True, exist_ok=True)
        job = Job(id=job_id, source=url, dir=job_dir, options=dict(options or {}),
                  source_url=url, audio_only=audio_only)
        with self._lock:
            self._jobs[job_id] = job
        self._save(job)
        self._notify("upsert", job_id)
        self._queue.put(job)
        return job

    def import_dir(self, result_dir: str | Path, source: str | None = None) -> Job:
        """Register an existing output directory (out/) as a finished job."""
        result_dir = Path(result_dir).expanduser().resolve()
        analysis_path = result_dir / "analysis.json"
        if not analysis_path.is_file():
            raise JobError(f"there is no analysis.json in {result_dir}")
        try:
            analysis = json.loads(analysis_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise JobError(f"unreadable analysis.json in {result_dir}: {exc}") from exc
        # Valid JSON is not enough: any file with that name would pass the
        # browser's filter and then blow up when read.
        if not isinstance(analysis, dict) or not isinstance(analysis.get("segments"), list):
            raise JobError(f"{analysis_path} does not look like a PhonoTrainer "
                           'analysis (the "segments" list is missing)')
        meta = analysis.get("meta") or {}

        # Look up and register under the same lock: otherwise two simultaneous
        # imports of the same directory create two jobs.
        with self._lock:
            for job in self._jobs.values():
                if job.imported and job.result_dir == result_dir:
                    # Already imported (idempotent), but its contents may have
                    # changed or it may be missing from the corpus: reindex anyway.
                    self._index(job, analysis)
                    self._notify("upsert", job.id)
                    return job
            job_id = self._new_id()
            job = Job(
                id=job_id,
                source=source or meta.get("source") or result_dir.name,
                dir=self.root / job_id,
                status=DONE,
                options={"attraction": meta.get("attraction", True),
                         "phone_engine": _phone_engine_of(meta),
                         "separate_dialogue": bool(
                             (meta.get("dialogue_separation") or {}).get("applied"))},
                result_dir_override=str(result_dir),
                finished=_now(),
                meta=meta,
                summary=_summarize(analysis),
            )
            job.dir.mkdir(parents=True, exist_ok=True)
            self._jobs[job_id] = job
        self._save(job)
        self._notify("upsert", job_id)
        self._index(job, analysis)
        return job

    def cancel(self, job_id: str) -> Job:
        """Request cancellation. If it had not started yet, `_run` sees it on entry."""
        job = self.get(job_id)
        if job.status in (QUEUED, RUNNING):
            job.cancel_requested = True
        return job

    def delete(self, job_id: str) -> None:
        """Remove the job. Imported data (outside the workspace) is left alone.

        If it is running, the directory is not deleted right away: the worker is
        still writing inside it. It is flagged so the worker cleans it up when
        it finishes.
        """
        job = self.get(job_id)
        with self._lock:
            self._jobs.pop(job_id, None)
            # Before touching the corpus: if the worker finishes right now, it
            # already knows it must not index.
            if job.status in (QUEUED, RUNNING):
                job.cancel_requested = True
                job.delete_when_done = True
        self._notify("deleted", job_id)
        if self.corpus is not None:
            try:
                # An imported analysis is still on disk and the CLI may have
                # indexed it: it stays in the corpus and merely loses its job.
                self.corpus.forget_job(job_id, keep_analysis=job.imported)
            except Exception:                 # noqa: BLE001 — deleting the job
                pass                          # must not fail because of the index
        if job.delete_when_done:
            # The removal has to be durable right now: if the process dies
            # before the worker reaches its cancellation point, without this the
            # deleted job would reappear on restart (job.json is still on disk).
            (job.dir / "job.json").unlink(missing_ok=True)
            return
        self._cleanup(job)

    def _cleanup(self, job: Job) -> None:
        if job.dir.is_dir() and job.dir.parent == self.root:
            _rmtree(job.dir)

    # --- execution -----------------------------------------------------------
    def _run(self, job: Job) -> None:
        if job.cancel_requested:
            self._finish(job, CANCELLED, error="Cancelled before starting.")
            return

        job.status = RUNNING
        job.started = _now()
        self._save(job)
        self._notify("upsert", job.id)
        self._append_progress(job, "Queued → starting…")

        def progress(message: str) -> None:
            if job.cancel_requested:
                raise JobCancelled(message)
            self._append_progress(job, message)

        try:
            if job.source_url and not job.media_path:
                self._download(job, progress)
            analysis = self._analyze(job, progress)
        except JobCancelled:
            self._finish(job, CANCELLED, error="Cancelled during the analysis.")
        except Exception as exc:                      # noqa: BLE001 — reported to the UI
            self._finish(job, ERROR, error=f"{type(exc).__name__}: {exc}")
        else:
            job.meta = analysis.get("meta")
            job.summary = _summarize(analysis)
            self._index(job, analysis)
            self._finish(job, DONE)

    def _download(self, job: Job, progress) -> None:
        download_fn = self._download_fn
        if download_fn is None:
            from .download import download as download_fn   # lazy import
        path = download_fn(job.source_url, self.download_dir,
                           audio_only=job.audio_only, progress=progress)
        job.media_path = str(Path(path).resolve())
        job.source = Path(path).name        # no longer the URL: the video's title
        self._save(job)
        self._notify("upsert", job.id)

    def _index(self, job: Job, analysis: dict) -> None:
        """Dump the analysis into the corpus. A failing index does not
        invalidate the analysis: it is reported in the progress log and we
        carry on."""
        if self.corpus is None or job.delete_when_done:
            return              # it is being deleted: let's not put it in the corpus
        try:
            self.corpus.index_analysis(analysis_key(job.result_dir), analysis,
                                       source=job.source,
                                       result_dir=str(job.result_dir.resolve()),
                                       job_id=job.id)
        except Exception as exc:                      # noqa: BLE001
            self._append_progress(
                job, f"Warning: could not index into the corpus ({exc})")

    def _analyze(self, job: Job, progress) -> dict:
        analyze_fn = self._analyze_fn
        if analyze_fn is None:
            from .pipeline import analyze as analyze_fn   # lazy import: torch is slow
        return analyze_fn(job.media_path, job.dir, progress=progress, **job.options)

    def _append_progress(self, job: Job, message: str) -> None:
        with self._lock:
            job.progress.append({"at": _now(), "message": message})
            due = time.monotonic() - job.last_saved >= SAVE_INTERVAL
        if due:
            self._save(job)
        self._notify("upsert", job.id)

    def _finish(self, job: Job, status: str, error: str | None = None) -> None:
        with self._lock:
            job.status = status
            job.error = error
            job.finished = _now()
        if job.delete_when_done:      # it was deleted while running
            self._cleanup(job)
            return
        self._save(job)
        self._notify("upsert", job.id)

    def _save(self, job: Job) -> None:
        """Dump job.json. Only the snapshot is taken under the lock; serializing
        and writing happen outside it so as not to block the UI or `cancel`."""
        if job.delete_when_done:
            return                      # it is on its way out: let's not revive it
        with self._lock:
            payload = job.to_dict()
            job.last_saved = time.monotonic()
        path = job.dir / "job.json"
        tmp = path.with_suffix(".json.tmp")
        try:
            tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                           encoding="utf-8")
            tmp.replace(path)
        except OSError:
            # The job was deleted from under our feet: there is nothing to persist.
            pass


def _phone_engine_of(meta: dict) -> str:
    """The engine an imported analysis was made with (see phones_real.engine_of)."""
    from .phones_real import engine_of

    return engine_of(meta)


def _summarize(analysis: dict) -> dict:
    segments = analysis.get("segments", [])
    return {
        "segments": len(segments),
        "words": sum(len(s.get("words", [])) for s in segments),
        "duration": analysis.get("meta", {}).get("duration"),
        "phenomena_counts": analysis.get("summary", {}).get("phenomena_counts", {}),
    }


def _rmtree(path: Path) -> None:
    import shutil

    shutil.rmtree(path, ignore_errors=True)
