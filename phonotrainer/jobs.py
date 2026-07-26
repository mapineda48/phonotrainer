"""Store de trabajos de análisis para la interfaz web.

Un *job* es una ejecución de `pipeline.analyze` (o la importación de un
directorio de salida ya existente, p. ej. el `out/` que dejó la CLI). Los jobs
se ejecutan de uno en uno en un hilo aparte —el pipeline es CPU-bound y carga
~1.8 GB de modelos— y el progreso textual que emite el pipeline se guarda con
marca de tiempo para que la UI lo muestre en vivo.

Cada job vive en `<root>/<id>/`:

    job.json                metadatos + progreso (fuente de verdad al reiniciar)
    media/<archivo>         solo si el archivo se subió por la UI
    audio.wav, analysis.json, canonical.json, phones_real.json, report.html

Los archivos locales indicados por ruta NO se copian: se referencian. Los jobs
importados guardan `result_dir` apuntando fuera del workspace.
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

QUEUED = "queued"
RUNNING = "running"
DONE = "done"
ERROR = "error"
CANCELLED = "cancelled"

MEDIA_SUFFIXES = {".webm", ".mp4", ".mkv", ".mov", ".avi", ".m4v",
                  ".mp3", ".wav", ".m4a", ".flac", ".ogg", ".opus", ".aac"}
VIDEO_SUFFIXES = {".webm", ".mp4", ".mkv", ".mov", ".avi", ".m4v"}

# job.json es solo el registro para sobrevivir a un reinicio: la fuente de
# verdad en caliente es la memoria. Volcarlo en cada mensaje de progreso haría
# I/O dentro del lock y retrasaría, entre otras cosas, las cancelaciones.
SAVE_INTERVAL = 1.0  # s


class JobCancelled(RuntimeError):
    """Se lanza dentro del callback de progreso para abortar el pipeline."""


class JobError(RuntimeError):
    """Petición inválida sobre el store (ruta inexistente, directorio sin
    analysis.json…). El servidor la traduce a 400."""


class JobNotFound(JobError):
    """El job no existe. El servidor la traduce a 404."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


# El pipeline emite "Segmento 3/27: …" en el bucle largo; el resto de etapas son
# hitos fijos. Con eso estimamos un porcentaje sin tocar pipeline.py.
_SEG_RE = re.compile(r"Segmento (\d+)/(\d+)")
_STAGE_PERCENT = (
    ("Extrayendo audio", 3),
    ("Transcribiendo", 8),
    ("Cargando motor", 18),
    ("Cargando prosodia", 23),
    ("Guardando salidas", 96),
    ("Generando report", 98),
)
_SEG_FLOOR, _SEG_SPAN = 25, 70


def estimate_percent(message: str | None) -> int:
    """Porcentaje aproximado a partir del último mensaje de progreso."""
    if not message:
        return 0
    m = _SEG_RE.search(message)
    if m:
        done, total = int(m.group(1)), max(int(m.group(2)), 1)
        return int(_SEG_FLOOR + _SEG_SPAN * min(done / total, 1.0))
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
    result_dir_override: str | None = None   # jobs importados: salida fuera del workspace
    created: str = field(default_factory=_now)
    started: str | None = None
    finished: str | None = None
    progress: list[dict] = field(default_factory=list)
    error: str | None = None
    meta: dict | None = None
    summary: dict | None = None

    # --- runtime (no se serializa) -----------------------------------------
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

    def to_dict(self) -> dict:
        """Forma persistida en job.json."""
        return {
            "id": self.id,
            "source": self.source,
            "status": self.status,
            "options": self.options,
            "media_path": self.media_path,
            "result_dir_override": self.result_dir_override,
            "created": self.created,
            "started": self.started,
            "finished": self.finished,
            "progress": list(self.progress),   # copia: se serializa fuera del lock
            "error": self.error,
            "meta": self.meta,
            "summary": self.summary,
        }

    def to_public(self, full: bool = False) -> dict:
        """Forma que consume la UI. `full=False` omite el log de progreso."""
        media = Path(self.media_path) if self.media_path else None
        data = {
            "id": self.id,
            "source": self.source,
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
            "has_report": self.artifact("report.html") is not None,
            "has_review": self.artifact("review.json") is not None,
            "has_media": bool(media and media.is_file()),
            "is_video": bool(media and media.suffix.lower() in VIDEO_SUFFIXES),
            "meta": self.meta,
            "summary": self.summary,
        }
        if full:
            data["progress"] = self.progress
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
        result_dir_override=raw.get("result_dir_override"),
        created=raw.get("created") or _now(),
        started=raw.get("started"),
        finished=raw.get("finished"),
        progress=raw.get("progress") or [],
        error=raw.get("error"),
        meta=raw.get("meta"),
        summary=raw.get("summary"),
    )
    # Un job "corriendo" en disco significa que el server murió a mitad.
    if job.status in (RUNNING, QUEUED):
        job.status = ERROR
        job.error = "Interrumpido: el servidor se detuvo durante el análisis."
    return job


class JobStore:
    """Registro de jobs con ejecución serializada en un hilo trabajador.

    El hilo es *daemon* y consume una cola: así `Ctrl-C` en el servidor cierra
    el proceso en el acto aunque haya un análisis a medias (que puede tardar
    minutos en llegar a su siguiente punto de cancelación).
    """

    def __init__(self, root: str | Path, analyze_fn=None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._analyze_fn = analyze_fn
        self._lock = threading.RLock()
        self._jobs: dict[str, Job] = {}
        # La cola lleva el Job, no su id: si lo borran mientras está encolado,
        # el trabajador sigue teniéndolo y puede limpiar su directorio.
        self._queue: queue.SimpleQueue[Job | None] = queue.SimpleQueue()
        self._worker = threading.Thread(target=self._worker_loop,
                                        name="phonotrainer-job", daemon=True)
        self._worker.start()
        self._discover()

    # --- ciclo de vida -------------------------------------------------------
    def _discover(self) -> None:
        for job_dir in sorted(p for p in self.root.iterdir() if p.is_dir()):
            job = _load_job(job_dir)
            if job is not None:
                self._jobs[job.id] = job

    def _worker_loop(self) -> None:
        while True:
            job = self._queue.get()
            if job is None:
                return
            try:
                self._run(job)
            except Exception:      # noqa: BLE001 — el hilo nunca debe morir
                pass

    def shutdown(self, wait: bool = False) -> None:
        with self._lock:
            for job in self._jobs.values():
                if job.status in (QUEUED, RUNNING):
                    job.cancel_requested = True
        self._queue.put(None)
        if wait:
            self._worker.join(timeout=5)

    # --- consultas -----------------------------------------------------------
    def list(self) -> list[Job]:
        with self._lock:
            return sorted(self._jobs.values(), key=lambda j: j.created, reverse=True)

    def get(self, job_id: str) -> Job:
        with self._lock:
            job = self._jobs.get(job_id)
        if job is None:
            raise JobNotFound(f"job desconocido: {job_id}")
        return job

    # --- creación ------------------------------------------------------------
    def _new_id(self) -> str:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        return f"{stamp}-{uuid.uuid4().hex[:4]}"

    def create(self, media_path: str | Path, options: dict | None = None,
               source: str | None = None) -> Job:
        """Registra un análisis y lo encola. `media_path` debe existir."""
        media_path = Path(media_path).expanduser()
        if not media_path.is_file():
            raise JobError(f"no existe el archivo: {media_path}")
        return self._enqueue(source or media_path.name, media_path, options)

    def adopt_upload(self, filename: str, data_writer, options: dict | None = None) -> Job:
        """Crea el job y deja que `data_writer(destino)` escriba el archivo subido."""
        job_id = self._new_id()
        media_dir = self.root / job_id / "media"
        media_dir.mkdir(parents=True, exist_ok=True)
        dest = media_dir / Path(filename).name
        data_writer(dest)
        if not dest.is_file() or dest.stat().st_size == 0:
            _rmtree(self.root / job_id)      # no dejamos restos de una subida fallida
            raise JobError("el archivo subido llegó vacío")
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
        self._queue.put(job)
        return job

    def import_dir(self, result_dir: str | Path, source: str | None = None) -> Job:
        """Registra un directorio de salida existente (out/) como job terminado."""
        result_dir = Path(result_dir).expanduser().resolve()
        analysis_path = result_dir / "analysis.json"
        if not analysis_path.is_file():
            raise JobError(f"no hay analysis.json en {result_dir}")
        try:
            analysis = json.loads(analysis_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise JobError(f"analysis.json ilegible en {result_dir}: {exc}") from exc
        meta = analysis.get("meta") or {}

        # Buscar y registrar bajo el mismo lock: si no, dos importaciones
        # simultáneas del mismo directorio crean dos jobs.
        with self._lock:
            for job in self._jobs.values():
                if job.imported and job.result_dir == result_dir:
                    return job   # ya importado: idempotente
            job_id = self._new_id()
            job = Job(
                id=job_id,
                source=source or meta.get("source") or result_dir.name,
                dir=self.root / job_id,
                status=DONE,
                options={"attraction": meta.get("attraction", True)},
                result_dir_override=str(result_dir),
                finished=_now(),
                meta=meta,
                summary=_summarize(analysis),
            )
            job.dir.mkdir(parents=True, exist_ok=True)
            self._jobs[job_id] = job
        self._save(job)
        return job

    def cancel(self, job_id: str) -> Job:
        """Pide la cancelación. Si aún no había arrancado, `_run` la ve al entrar."""
        job = self.get(job_id)
        if job.status in (QUEUED, RUNNING):
            job.cancel_requested = True
        return job

    def delete(self, job_id: str) -> None:
        """Elimina el job. Los datos importados (fuera del workspace) no se tocan.

        Si está en curso no se borra el directorio en el acto: el trabajador
        todavía escribe dentro. Se marca para que lo limpie al terminar.
        """
        job = self.get(job_id)
        with self._lock:
            self._jobs.pop(job_id, None)
        if job.status in (QUEUED, RUNNING):
            job.cancel_requested = True
            job.delete_when_done = True
            return
        self._cleanup(job)

    def _cleanup(self, job: Job) -> None:
        if job.dir.is_dir() and job.dir.parent == self.root:
            _rmtree(job.dir)

    # --- ejecución -----------------------------------------------------------
    def _run(self, job: Job) -> None:
        if job.cancel_requested:
            self._finish(job, CANCELLED, error="Cancelado antes de empezar.")
            return

        job.status = RUNNING
        job.started = _now()
        self._save(job)
        self._append_progress(job, "En cola → arrancando…")

        def progress(message: str) -> None:
            if job.cancel_requested:
                raise JobCancelled(message)
            self._append_progress(job, message)

        try:
            analysis = self._analyze(job, progress)
        except JobCancelled:
            self._finish(job, CANCELLED, error="Cancelado durante el análisis.")
        except Exception as exc:                      # noqa: BLE001 — se reporta a la UI
            self._finish(job, ERROR, error=f"{type(exc).__name__}: {exc}")
        else:
            job.meta = analysis.get("meta")
            job.summary = _summarize(analysis)
            self._finish(job, DONE)

    def _analyze(self, job: Job, progress) -> dict:
        analyze_fn = self._analyze_fn
        if analyze_fn is None:
            from .pipeline import analyze as analyze_fn   # import perezoso: torch tarda
        return analyze_fn(job.media_path, job.dir, progress=progress, **job.options)

    def _append_progress(self, job: Job, message: str) -> None:
        with self._lock:
            job.progress.append({"at": _now(), "message": message})
            due = time.monotonic() - job.last_saved >= SAVE_INTERVAL
        if due:
            self._save(job)

    def _finish(self, job: Job, status: str, error: str | None = None) -> None:
        with self._lock:
            job.status = status
            job.error = error
            job.finished = _now()
        if job.delete_when_done:      # lo borraron mientras corría
            self._cleanup(job)
            return
        self._save(job)

    def _save(self, job: Job) -> None:
        """Vuelca job.json. Solo el snapshot se toma bajo el lock; serializar y
        escribir se hace fuera para no bloquear a la UI ni a `cancel`."""
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
            # El job se borró bajo nuestros pies: no hay nada que persistir.
            pass


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
