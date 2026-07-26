"""API HTTP local que expone el pipeline a la interfaz React.

`phonotrainer ui` levanta este servidor: sirve la SPA compilada (`web/dist`) y
una API REST mínima sobre `jobs.JobStore`. Todo es local (localhost); el audio
se sirve con soporte de Range para que el reproductor pueda hacer seek.

    GET    /api/health
    GET    /api/reference                taxonomía de fenómenos (única fuente)
    GET    /api/jobs                     lista de análisis
    POST   /api/jobs                     analizar un archivo local por ruta
    POST   /api/jobs/upload              analizar un archivo subido
    POST   /api/jobs/import              registrar un out/ ya existente
    GET    /api/jobs/{id}                estado + log de progreso
    DELETE /api/jobs/{id}                borrar (los importados no se tocan)
    POST   /api/jobs/{id}/cancel
    GET    /api/jobs/{id}/analysis|audio|media|report
    GET    /api/jobs/{id}/review         review.json guardado
    GET    /api/jobs/{id}/review/sample  muestreo priorizado (n, seed)
    PUT    /api/jobs/{id}/review         guardar veredictos
    GET    /api/browse                   explorador de archivos (bajo $HOME)
"""

from __future__ import annotations

import json
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal, get_args

from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel, Field, ValidationError

from . import __version__, ipa_maps, report, review as review_mod
from .db import DEFAULT_DB, Corpus
from .jobs import DOWNLOAD_DIR, MEDIA_SUFFIXES, JobError, JobNotFound, JobStore

DEFAULT_WORKSPACE = Path("workspace")
WEB_DIST = Path(__file__).resolve().parents[1] / "web" / "dist"

# Tipos servidos para el archivo original. Es una tabla cerrada a propósito:
# adivinar el tipo permitiría servir text/html desde el mismo origen que la SPA.
MEDIA_TYPES = {
    ".webm": "video/webm", ".mp4": "video/mp4", ".mkv": "video/x-matroska",
    ".mov": "video/quicktime", ".avi": "video/x-msvideo", ".m4v": "video/x-m4v",
    ".mp3": "audio/mpeg", ".wav": "audio/wav", ".m4a": "audio/mp4",
    ".flac": "audio/flac", ".ogg": "audio/ogg", ".opus": "audio/ogg",
    ".aac": "audio/aac",
}


MAX_SAMPLE = 500   # tope de palabras por muestreo de revisión


class Options(BaseModel):
    """Opciones del pipeline; los nombres coinciden con `pipeline.analyze`."""

    whisper_model: Literal["tiny", "base", "small", "medium"] = "small"
    phone_engine: Literal["wav2vec2", "allosaurus"] = "wav2vec2"
    language: str = "en"
    attraction: bool = True


class AnalyzeRequest(BaseModel):
    path: str = Field(..., description="Ruta local del video o audio")
    options: Options = Options()


class ImportRequest(BaseModel):
    path: str = Field(..., description="Directorio con analysis.json")


class YoutubeRequest(BaseModel):
    url: str = Field(..., description="URL del vídeo (YouTube u otro sitio de yt-dlp)")
    options: Options = Options()
    audio_only: bool = Field(False, description="Bajar solo el audio (más rápido)")


class Verdict(BaseModel):
    segment: int
    word_idx: int
    verdict: Literal["ok", "mal", "dudosa"]
    note: str = ""


class ReviewRequest(BaseModel):
    seed: int = review_mod.DEFAULT_SEED
    verdicts: list[Verdict] = []


def create_app(workspace: str | Path = DEFAULT_WORKSPACE,
               store: JobStore | None = None,
               web_dist: str | Path | None = None,
               allowed_roots: list[str | Path] | None = None,
               db_path: str | Path = DEFAULT_DB,
               download_dir: str | Path = DOWNLOAD_DIR) -> FastAPI:
    """La app. `allowed_roots` acota qué parte del disco puede tocarse desde el
    navegador (explorar, analizar, importar): por defecto $HOME y el directorio
    de trabajo. La CLI no pasa por aquí y no tiene ese límite."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        # Ctrl-C: marcamos la cancelación y soltamos el hilo trabajador (daemon).
        app.state.store.shutdown()
        if app.state.store.corpus is not None:
            app.state.store.corpus.close()

    app = FastAPI(title="PhonoTrainer", version=__version__, docs_url="/api/docs",
                  openapi_url="/api/openapi.json", lifespan=lifespan)
    app.state.store = store or JobStore(workspace, corpus=Corpus(db_path),
                                        download_dir=download_dir)
    app.state.web_dist = Path(web_dist) if web_dist else WEB_DIST
    roots = [Path(root).expanduser().resolve()
             for root in (allowed_roots or [Path.home(), Path.cwd()])]
    if any(root == Path("/") for root in roots):
        raise ValueError("«/» como raíz permitida desactivaría el confinamiento")
    app.state.roots = roots

    @app.middleware("http")
    async def solo_mismo_origen(request: Request, call_next):
        """Ninguna página de internet debe poder encolar análisis en tu máquina.

        Una subida `multipart` es una petición «simple»: el navegador la manda
        sin preflight, así que CORS no la frena. `Sec-Fetch-Site` sí lo dice, y
        lo envían todos los navegadores actuales (curl y los tests no lo mandan).
        """
        site = request.headers.get("sec-fetch-site")
        if request.method not in ("GET", "HEAD") and site not in (None, "same-origin", "none"):
            return JSONResponse({"detail": "petición de otro origen"}, status_code=403)
        return await call_next(request)

    @app.exception_handler(JobNotFound)
    def _job_not_found(request: Request, exc: JobNotFound):   # noqa: ARG001
        return JSONResponse({"detail": str(exc)}, status_code=404)

    @app.exception_handler(JobError)
    def _job_error(request: Request, exc: JobError):          # noqa: ARG001
        return JSONResponse({"detail": str(exc)}, status_code=400)

    def _store() -> JobStore:
        return app.state.store

    def _job(job_id: str):
        return _store().get(job_id)

    def _inside_roots(path: Path) -> bool:
        return any(path == root or root in path.parents for root in app.state.roots)

    def _resolve(path_str: str) -> Path:
        """`~usuario` inexistente, bytes NUL…: entrada del usuario, no 500."""
        try:
            return Path(path_str).expanduser().resolve()
        except (OSError, ValueError, RuntimeError) as exc:
            raise HTTPException(400, f"ruta inválida: {path_str}") from exc

    def _checked(path_str: str, *, want_dir: bool) -> Path:
        """Ruta que el usuario elige desde el navegador, ya validada."""
        path = _resolve(path_str)
        if not _inside_roots(path):
            raise HTTPException(
                403, "por seguridad la interfaz solo abre archivos dentro de "
                     + " o ".join(str(root) for root in app.state.roots))
        if want_dir and not path.is_dir():
            raise HTTPException(400, f"no es un directorio: {path}")
        if not want_dir:
            if not path.is_file():
                raise HTTPException(400, f"no existe el archivo: {path}")
            if path.suffix.lower() not in MEDIA_SUFFIXES:
                raise HTTPException(400, f"no parece un video ni un audio: {path.name}")
        return path

    def _artifact(job_id: str, name: str) -> Path:
        path = _job(job_id).artifact(name)
        if path is None:
            raise HTTPException(404, f"{name} todavía no existe para {job_id}")
        return path

    def _read_json(path: Path) -> dict:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise HTTPException(400, f"{path.name} ilegible en {path.parent}: {exc}") from exc

    def _analysis(job_id: str) -> dict:
        analysis = _read_json(_artifact(job_id, "analysis.json"))
        if not isinstance(analysis, dict) or not isinstance(analysis.get("segments"), list):
            raise HTTPException(400, "analysis.json no tiene la forma esperada")
        return analysis

    # --- meta ---------------------------------------------------------------
    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True, "version": __version__,
                "workspace": str(_store().root.resolve()),
                "web_built": (app.state.web_dist / "index.html").is_file()}

    @app.get("/api/reference")
    def reference() -> dict:
        """Vocabulario del backend: fenómenos, opciones del pipeline y ajustes
        de la revisión. La UI los consume tal cual, no mantiene copias."""
        return {
            "families": [
                {"key": key, "label": label,
                 "members": report.FAMILY_MEMBERS[key],
                 "member_labels": [report.PHENOMENON_LABEL[m]
                                   for m in report.FAMILY_MEMBERS[key]]}
                for key, label in report.FAMILY_LABEL.items()
            ],
            "family_of": report.FAMILY_OF,
            "labels": report.PHENOMENON_LABEL,
            "descriptions": report.PHENOMENON_DESCRIPTION,
            # Símbolos IPA de más de un carácter (aɪ, tʃ, ɑːɹ…): sin ellos el
            # navegador partiría "aɪ" en dos al tokenizar la forma de diccionario.
            "ipa_tokens": sorted(
                (t for t in ipa_maps.ENGLISH_INVENTORY if len(t) > 1),
                key=len, reverse=True,
            ),
            "verdicts": list(review_mod.VERDICTS),
            "options": {
                "whisper_models": list(get_args(Options.model_fields["whisper_model"].annotation)),
                "phone_engines": list(get_args(Options.model_fields["phone_engine"].annotation)),
                "defaults": Options().model_dump(),
            },
            "review": {
                "default_n": review_mod.DEFAULT_N,
                "default_seed": review_mod.DEFAULT_SEED,
                "max_n": MAX_SAMPLE,
            },
        }

    # --- jobs ---------------------------------------------------------------
    @app.get("/api/jobs")
    def list_jobs() -> list[dict]:
        return [j.to_public() for j in _store().list()]

    @app.post("/api/jobs", status_code=201)
    def create_job(req: AnalyzeRequest) -> dict:
        path = _checked(req.path, want_dir=False)
        return _store().create(path, options=req.options.model_dump()).to_public(full=True)

    @app.post("/api/jobs/upload", status_code=201)
    def upload_job(file: UploadFile = File(...), options: str = Form("{}")) -> dict:
        try:
            opts = Options.model_validate(json.loads(options or "{}"))
        except (json.JSONDecodeError, ValidationError, TypeError) as exc:
            raise HTTPException(400, f"opciones inválidas: {exc}") from exc

        # Misma regla que al analizar por ruta: solo video o audio.
        name = Path(file.filename or "").name
        if not name or Path(name).suffix.lower() not in MEDIA_SUFFIXES:
            raise HTTPException(400, f"no parece un video ni un audio: {file.filename!r}")

        def writer(dest: Path) -> None:
            with dest.open("wb") as fh:
                while chunk := file.file.read(1 << 20):
                    fh.write(chunk)

        job = _store().adopt_upload(name, writer, opts.model_dump())
        return job.to_public(full=True)

    @app.post("/api/jobs/youtube", status_code=201)
    def youtube_job(req: YoutubeRequest) -> dict:
        from .download import is_url

        if not is_url(req.url):
            raise HTTPException(400, f"no parece una URL: {req.url}")
        job = _store().create_from_url(req.url, options=req.options.model_dump(),
                                       audio_only=req.audio_only)
        return job.to_public(full=True)

    @app.post("/api/jobs/import", status_code=201)
    def import_job(req: ImportRequest) -> dict:
        return _store().import_dir(_checked(req.path, want_dir=True)).to_public(full=True)

    # --- corpus (índice SQLite entre análisis) -------------------------------
    def _corpus() -> Corpus:
        corpus = _store().corpus
        if corpus is None:
            raise HTTPException(503, "este servidor corre sin corpus")
        return corpus

    @app.get("/api/corpus/stats")
    def corpus_stats() -> dict:
        return _corpus().stats()

    @app.get("/api/corpus/occurrences")
    def corpus_occurrences(phenomenon: str | None = None, word: str | None = None,
                           analysis: str | None = None,
                           limit: int = Query(100, ge=1, le=1000)) -> dict:
        corpus = _corpus()
        rows = corpus.occurrences(phenomenon=phenomenon, word=word,
                                  analysis_id=analysis, limit=limit)
        total = corpus.count_occurrences(phenomenon=phenomenon, word=word,
                                         analysis_id=analysis)
        return {"phenomenon": phenomenon, "word": word, "total": total, "items": rows}

    @app.get("/api/corpus/variants")
    def corpus_variants(word: str) -> dict:
        """Cómo se ha pronunciado realmente una palabra en todo el corpus."""
        return {"word": word, "variants": _corpus().word_variants(word)}

    @app.get("/api/jobs/{job_id}")
    def get_job(job_id: str) -> dict:
        return _job(job_id).to_public(full=True)

    @app.delete("/api/jobs/{job_id}", status_code=204)
    def delete_job(job_id: str) -> None:
        _store().delete(job_id)

    @app.post("/api/jobs/{job_id}/cancel")
    def cancel_job(job_id: str) -> dict:
        return _store().cancel(job_id).to_public(full=True)

    # --- artefactos ---------------------------------------------------------
    @app.get("/api/jobs/{job_id}/analysis")
    def get_analysis(job_id: str) -> FileResponse:
        return FileResponse(_artifact(job_id, "analysis.json"),
                            media_type="application/json")

    @app.get("/api/jobs/{job_id}/audio")
    def get_audio(job_id: str) -> FileResponse:
        # FileResponse de Starlette responde peticiones Range → seek en el <audio>.
        return FileResponse(_artifact(job_id, "audio.wav"), media_type="audio/wav")

    @app.get("/api/jobs/{job_id}/media")
    def get_media(job_id: str) -> FileResponse:
        job = _job(job_id)
        path = Path(job.media_path) if job.media_path else None
        if path is None or not path.is_file():
            raise HTTPException(404, "este análisis no tiene el archivo original")
        return FileResponse(path,
                            media_type=MEDIA_TYPES.get(path.suffix.lower(),
                                                       "application/octet-stream"))

    @app.get("/api/jobs/{job_id}/report", response_class=HTMLResponse)
    def get_report(job_id: str) -> FileResponse:
        # Un out/ importado puede venir de fuera: el report se sirve en un origen
        # opaco (sandbox) para que su JS no pueda hablar con esta API.
        return FileResponse(
            _artifact(job_id, "report.html"), media_type="text/html",
            headers={"Content-Security-Policy": "sandbox allow-scripts",
                     "X-Content-Type-Options": "nosniff"},
        )

    # --- revisión humana ----------------------------------------------------
    @app.get("/api/jobs/{job_id}/review")
    def get_review(job_id: str) -> dict:
        path = _job(job_id).artifact("review.json")
        if path is None:
            return {"items": [], "sampled": 0, "ok": 0, "mal": 0, "dudosa": 0,
                    "accuracy": None, "seed": review_mod.DEFAULT_SEED}
        return _read_json(path)

    @app.get("/api/jobs/{job_id}/review/sample")
    def sample_review(job_id: str,
                      n: int = Query(review_mod.DEFAULT_N, ge=1, le=MAX_SAMPLE),
                      seed: int = review_mod.DEFAULT_SEED) -> dict:
        sample = review_mod.sample_for_ui(_analysis(job_id), n=n, seed=seed)
        return {"seed": seed, "n": n, "items": sample}

    @app.put("/api/jobs/{job_id}/review")
    def put_review(job_id: str, req: ReviewRequest) -> dict:
        analysis_path = _artifact(job_id, "analysis.json")
        analysis = _read_json(analysis_path)
        try:
            items = review_mod.build_items(
                analysis, [v.model_dump() for v in req.verdicts])
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        review_mod.save_review(analysis_path, items, seed=req.seed)
        payload = {"seed": req.seed, "items": items}
        payload.update(review_mod.summarize(items))
        return payload

    # --- explorador de archivos ---------------------------------------------
    @app.get("/api/browse")
    def browse(path: str | None = None) -> dict:
        home = app.state.roots[0]
        target = _resolve(path) if path else Path.cwd().resolve()
        if not _inside_roots(target):
            target = home            # nunca salimos de las raíces permitidas
        if not target.is_dir():
            target = target.parent if target.parent.is_dir() else home

        dirs, files = [], []
        try:
            entries = sorted(target.iterdir(), key=lambda p: p.name.lower())
        except PermissionError as exc:
            raise HTTPException(403, f"sin permiso para leer {target}") from exc
        for entry in entries:
            if entry.name.startswith("."):
                continue
            try:
                if entry.is_dir():
                    dirs.append({
                        "name": entry.name, "path": str(entry),
                        "has_analysis": (entry / "analysis.json").is_file(),
                    })
                elif entry.suffix.lower() in MEDIA_SUFFIXES:
                    files.append({"name": entry.name, "path": str(entry),
                                  "size": entry.stat().st_size})
            except OSError:
                continue
        parent = str(target.parent) if _inside_roots(target.parent) else None
        return {"path": str(target), "parent": parent, "home": str(home),
                "dirs": dirs, "files": files}

    # --- SPA ----------------------------------------------------------------
    @app.get("/{full_path:path}", include_in_schema=False)
    def spa(full_path: str):
        if full_path.startswith("api/"):     # una ruta de API mal escrita es un 404
            raise HTTPException(404, f"endpoint desconocido: /{full_path}")
        dist = app.state.web_dist
        candidate = (dist / full_path).resolve()
        if full_path and dist.resolve() in candidate.parents and candidate.is_file():
            return FileResponse(candidate)
        index = dist / "index.html"
        if index.is_file():
            return FileResponse(index, media_type="text/html")
        return HTMLResponse(_NO_BUILD_HTML, status_code=200)

    return app


_NO_BUILD_HTML = """<!doctype html><meta charset="utf-8">
<title>PhonoTrainer — interfaz no compilada</title>
<style>body{font:15px/1.6 system-ui;max-width:640px;margin:60px auto;padding:0 20px;
color-scheme:light dark}code{background:#8883;padding:1px 5px;border-radius:4px}</style>
<h1>La interfaz no está compilada</h1>
<p>La API ya funciona (<a href="/api/docs">/api/docs</a>). Para la interfaz:</p>
<pre><code>cd web &amp;&amp; npm install &amp;&amp; npm run build</code></pre>
<p>o, en desarrollo, <code>npm run dev</code> (proxy al backend en el puerto de este server).</p>
"""


def serve(workspace: str | Path = DEFAULT_WORKSPACE, host: str = "127.0.0.1",
          port: int = 8000, reload: bool = False, open_browser: bool = True,
          allowed_roots: list[str | Path] | None = None) -> None:
    """Levanta uvicorn con la app (usado por `phonotrainer ui`)."""
    import uvicorn

    if open_browser:
        import threading
        import webbrowser

        timer = threading.Timer(1.2, lambda: webbrowser.open(f"http://{host}:{port}"))
        timer.daemon = True      # no debe retrasar el cierre con Ctrl-C
        timer.start()

    os.environ["PHONOTRAINER_WORKSPACE"] = str(workspace)
    if allowed_roots:
        os.environ["PHONOTRAINER_ROOTS"] = os.pathsep.join(str(r) for r in allowed_roots)
    uvicorn.run("phonotrainer.server:app_from_env" if reload
                else create_app(workspace, allowed_roots=allowed_roots),
                host=host, port=port, reload=reload, factory=reload)


def app_from_env() -> FastAPI:
    """Factory para `uvicorn --reload` (no puede recibir la app ya construida)."""
    roots = os.environ.get("PHONOTRAINER_ROOTS")
    return create_app(os.environ.get("PHONOTRAINER_WORKSPACE", DEFAULT_WORKSPACE),
                      allowed_roots=roots.split(os.pathsep) if roots else None)
