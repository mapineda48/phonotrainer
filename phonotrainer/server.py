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
import mimetypes
import os
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

from . import __version__, report, review as review_mod
from .jobs import MEDIA_SUFFIXES, JobError, JobStore

DEFAULT_WORKSPACE = Path("workspace")
WEB_DIST = Path(__file__).resolve().parents[1] / "web" / "dist"


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
               web_dist: str | Path | None = None) -> FastAPI:
    app = FastAPI(title="PhonoTrainer", version=__version__, docs_url="/api/docs",
                  openapi_url="/api/openapi.json")
    app.state.store = store or JobStore(workspace)
    app.state.web_dist = Path(web_dist) if web_dist else WEB_DIST

    @app.exception_handler(JobError)
    def _job_error(request: Request, exc: JobError):   # noqa: ARG001
        return JSONResponse({"detail": str(exc)}, status_code=404)

    def _store() -> JobStore:
        return app.state.store

    def _job(job_id: str):
        return _store().get(job_id)

    def _artifact(job_id: str, name: str) -> Path:
        path = _job(job_id).artifact(name)
        if path is None:
            raise HTTPException(404, f"{name} todavía no existe para {job_id}")
        return path

    def _analysis(job_id: str) -> dict:
        return json.loads(_artifact(job_id, "analysis.json").read_text(encoding="utf-8"))

    # --- meta ---------------------------------------------------------------
    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True, "version": __version__,
                "workspace": str(_store().root.resolve()),
                "web_built": (app.state.web_dist / "index.html").is_file()}

    @app.get("/api/reference")
    def reference() -> dict:
        """Taxonomía de fenómenos: la UI no mantiene su propia copia."""
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
            "verdicts": list(review_mod.VERDICTS),
            "options": Options.model_json_schema(),
        }

    # --- jobs ---------------------------------------------------------------
    @app.get("/api/jobs")
    def list_jobs() -> list[dict]:
        return [j.to_public() for j in _store().list()]

    @app.post("/api/jobs", status_code=201)
    def create_job(req: AnalyzeRequest) -> dict:
        path = Path(req.path).expanduser()
        if not path.is_file():
            raise HTTPException(400, f"no existe el archivo: {path}")
        return _store().create(path, options=req.options.model_dump()).to_public(full=True)

    @app.post("/api/jobs/upload", status_code=201)
    def upload_job(file: UploadFile = File(...), options: str = Form("{}")) -> dict:
        try:
            opts = Options(**json.loads(options or "{}"))
        except (json.JSONDecodeError, ValueError) as exc:
            raise HTTPException(400, f"opciones inválidas: {exc}") from exc

        def writer(dest: Path) -> None:
            with dest.open("wb") as fh:
                while chunk := file.file.read(1 << 20):
                    fh.write(chunk)

        job = _store().adopt_upload(file.filename or "media", writer, opts.model_dump())
        return job.to_public(full=True)

    @app.post("/api/jobs/import", status_code=201)
    def import_job(req: ImportRequest) -> dict:
        return _store().import_dir(req.path).to_public(full=True)

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
        media_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return FileResponse(path, media_type=media_type)

    @app.get("/api/jobs/{job_id}/report", response_class=HTMLResponse)
    def get_report(job_id: str) -> FileResponse:
        return FileResponse(_artifact(job_id, "report.html"), media_type="text/html")

    # --- revisión humana ----------------------------------------------------
    @app.get("/api/jobs/{job_id}/review")
    def get_review(job_id: str) -> dict:
        path = _job(job_id).artifact("review.json")
        if path is None:
            return {"items": [], "sampled": 0, "ok": 0, "mal": 0, "dudosa": 0,
                    "accuracy": None, "seed": review_mod.DEFAULT_SEED}
        return json.loads(path.read_text(encoding="utf-8"))

    @app.get("/api/jobs/{job_id}/review/sample")
    def sample_review(job_id: str,
                      n: int = Query(review_mod.DEFAULT_N, ge=1, le=500),
                      seed: int = review_mod.DEFAULT_SEED) -> dict:
        sample = review_mod.sample_for_ui(_analysis(job_id), n=n, seed=seed)
        return {"seed": seed, "n": n, "items": sample}

    @app.put("/api/jobs/{job_id}/review")
    def put_review(job_id: str, req: ReviewRequest) -> dict:
        analysis_path = _artifact(job_id, "analysis.json")
        analysis = json.loads(analysis_path.read_text(encoding="utf-8"))
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
        home = Path.home().resolve()
        target = Path(path).expanduser().resolve() if path else Path.cwd().resolve()
        if target != home and home not in target.parents:
            target = home            # nunca salimos de $HOME
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
        parent = str(target.parent) if (target != home and home in target.parents) else None
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
          port: int = 8000, reload: bool = False, open_browser: bool = True) -> None:
    """Levanta uvicorn con la app (usado por `phonotrainer ui`)."""
    import uvicorn

    if open_browser:
        import threading
        import webbrowser

        threading.Timer(1.2, lambda: webbrowser.open(f"http://{host}:{port}")).start()

    os.environ.setdefault("PHONOTRAINER_WORKSPACE", str(workspace))
    uvicorn.run("phonotrainer.server:app_from_env" if reload else create_app(workspace),
                host=host, port=port, reload=reload, factory=reload)


def app_from_env() -> FastAPI:
    """Factory para `uvicorn --reload` (no puede recibir la app ya construida)."""
    return create_app(os.environ.get("PHONOTRAINER_WORKSPACE", DEFAULT_WORKSPACE))
