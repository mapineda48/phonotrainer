"""Local HTTP API exposing the pipeline to the React interface.

`phonotrainer ui` starts this server: it serves the compiled SPA (`web/dist`)
and a minimal REST API on top of `jobs.JobStore`. Everything is local
(localhost); audio is served with Range support so the player can seek.

    GET    /api/health
    GET    /api/reference                phenomenon taxonomy (single source)
    GET    /api/jobs                     list of analyses
    POST   /api/jobs                     analyze a local file by path
    POST   /api/jobs/upload              analyze an uploaded file
    POST   /api/jobs/import              register an existing out/
    GET    /api/jobs/{id}                status + progress log
    DELETE /api/jobs/{id}                delete (imported data is left alone)
    POST   /api/jobs/{id}/cancel
    GET    /api/jobs/{id}/analysis|audio|media|report
    GET    /api/jobs/{id}/review         saved review.json
    GET    /api/jobs/{id}/review/sample  prioritized sample (n, seed)
    PUT    /api/jobs/{id}/review         save verdicts
    GET    /api/browse                   file browser (under $HOME)
    WS     /ws/jobs                      analysis state pushed live
"""

from __future__ import annotations

import asyncio
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal, get_args
from urllib.parse import urlparse

import anyio
from fastapi import (FastAPI, File, Form, HTTPException, Query, Request,
                     UploadFile, WebSocket, WebSocketDisconnect)
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from pydantic import BaseModel, Field, ValidationError

from . import __version__, ipa_maps, metrics as metrics_mod, phenomena, report
from . import review as review_mod, separation
from .db import DEFAULT_DB, Corpus
from .jobs import DOWNLOAD_DIR, MEDIA_SUFFIXES, JobError, JobNotFound, JobStore
from .phones_real import ENGINE_ALIASES

DEFAULT_WORKSPACE = Path("workspace")
WEB_DIST = Path(__file__).resolve().parents[1] / "web" / "dist"

# Types served for the original file. The table is closed on purpose: guessing
# the type would allow serving text/html from the same origin as the SPA.
MEDIA_TYPES = {
    ".webm": "video/webm", ".mp4": "video/mp4", ".mkv": "video/x-matroska",
    ".mov": "video/quicktime", ".avi": "video/x-msvideo", ".m4v": "video/x-m4v",
    ".mp3": "audio/mpeg", ".wav": "audio/wav", ".m4a": "audio/mp4",
    ".flac": "audio/flac", ".ogg": "audio/ogg", ".opus": "audio/ogg",
    ".aac": "audio/aac",
}


MAX_SAMPLE = 500   # cap on words per review sample

# Contract between this API and the SPA. Bump it when adding or changing
# endpoints the interface needs: the SPA is served from disk and is always up to
# date, but the process answering may be an old one left open —and then the
# interface asked for routes that did not exist and showed "Method Not Allowed".
#   2: /ws/jobs — the interface no longer polls /api/jobs
#   3: verdict and family keys renamed to English
#   4: timit61 engine, dialogue track (/audio?track=dialogue), practice and
#      metrics in /api/reference, /api/corpus/metrics
API_VERSION = 4

# What each phone engine is, for whoever picks one in the interface. The
# licensing line is not decoration: the default engine's training data is not
# free for every use, and the choice belongs to the user.
ENGINE_NOTES = {
    "timit61": "Narrow phonetic recognizer (TIMIT-61): hears flaps, glottal stops, "
               "unreleased stops and weak forms. Weights Apache-2.0; trained on TIMIT "
               "(LDC), whose data is licensed for non-commercial research.",
    "espeak": "The original engine (wav2vec2 trained on espeak-ng transcriptions). "
              "Unrestricted training data, but it tends to hear the dictionary form "
              "instead of what was said.",
}


class Options(BaseModel):
    """Pipeline options; the names match `pipeline.analyze`."""

    whisper_model: Literal["tiny", "base", "small", "medium"] = "small"
    # "wav2vec2" = the espeak engine's old name, still accepted for old jobs
    phone_engine: Literal["timit61", "espeak", "wav2vec2"] = "timit61"
    language: str = "en"
    # None = the engine's default: on for espeak, off for timit61
    attraction: bool | None = None
    separate_dialogue: bool = separation.DEFAULT_ENABLED


class AnalyzeRequest(BaseModel):
    path: str = Field(..., description="Local path of the video or audio file")
    options: Options = Options()


class ImportRequest(BaseModel):
    path: str = Field(..., description="Directory holding an analysis.json")


class YoutubeRequest(BaseModel):
    url: str = Field(..., description="Video URL (YouTube or any other yt-dlp site)")
    options: Options = Options()
    audio_only: bool = Field(False, description="Download the audio only (faster)")


class Verdict(BaseModel):
    segment: int
    word_idx: int
    verdict: Literal["ok", "wrong", "unsure"]
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
    """The app. `allowed_roots` bounds which part of the disk can be touched
    from the browser (browsing, analyzing, importing): by default $HOME and the
    working directory. The CLI does not go through here and has no such
    limit."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        # Ctrl-C: flag the cancellation and let go of the (daemon) worker thread.
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
        raise ValueError('"/" as an allowed root would disable the confinement')
    app.state.roots = roots

    @app.middleware("http")
    async def same_origin_only(request: Request, call_next):
        """No page on the internet should be able to queue analyses on your machine.

        A `multipart` upload is a "simple" request: the browser sends it without
        a preflight, so CORS does not stop it. `Sec-Fetch-Site` does say where
        it came from, and every current browser sends it (curl and the tests do
        not).
        """
        site = request.headers.get("sec-fetch-site")
        if request.method not in ("GET", "HEAD") and site not in (None, "same-origin", "none"):
            return JSONResponse({"detail": "cross-origin request"}, status_code=403)
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
        """A non-existent `~user`, NUL bytes…: user input, not a 500."""
        try:
            return Path(path_str).expanduser().resolve()
        except (OSError, ValueError, RuntimeError) as exc:
            raise HTTPException(400, f"invalid path: {path_str}") from exc

    def _checked(path_str: str, *, want_dir: bool) -> Path:
        """A path the user picks from the browser, already validated."""
        path = _resolve(path_str)
        if not _inside_roots(path):
            raise HTTPException(
                403, "for safety the interface only opens files inside "
                     + " or ".join(str(root) for root in app.state.roots))
        if want_dir and not path.is_dir():
            raise HTTPException(400, f"not a directory: {path}")
        if not want_dir:
            if not path.is_file():
                raise HTTPException(400, f"file does not exist: {path}")
            if path.suffix.lower() not in MEDIA_SUFFIXES:
                raise HTTPException(
                    400, f"does not look like a video or an audio file: {path.name}")
        return path

    def _artifact(job_id: str, name: str) -> Path:
        path = _job(job_id).artifact(name)
        if path is None:
            raise HTTPException(404, f"{name} does not exist yet for {job_id}")
        return path

    def _read_json(path: Path) -> dict:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise HTTPException(
                400, f"unreadable {path.name} in {path.parent}: {exc}") from exc

    def _analysis(job_id: str) -> dict:
        analysis = _read_json(_artifact(job_id, "analysis.json"))
        if not isinstance(analysis, dict) or not isinstance(analysis.get("segments"), list):
            raise HTTPException(400, "analysis.json does not have the expected shape")
        return analysis

    # --- meta ---------------------------------------------------------------
    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True, "version": __version__, "api_version": API_VERSION,
                "workspace": str(_store().root.resolve()),
                "web_built": (app.state.web_dist / "index.html").is_file()}

    @app.get("/api/reference")
    def reference() -> dict:
        """The backend's vocabulary: phenomena, pipeline options and review
        settings. The UI consumes them as they are, it keeps no copies."""
        return {
            "api_version": API_VERSION,
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
            # to hear only, or to say too: per label and per lexical reduced form
            "practice": report.PHENOMENON_PRACTICE,
            "lexical_practice": report.LEXICAL_PRACTICE,
            "link_types": list(phenomena.LINK_TYPES),
            # IPA symbols longer than one character (aɪ, tʃ, ɑːɹ…): without them
            # the browser would split "aɪ" in two when tokenizing the dictionary
            # form.
            "ipa_tokens": sorted(
                (t for t in ipa_maps.ENGLISH_INVENTORY if len(t) > 1),
                key=len, reverse=True,
            ),
            "verdicts": list(review_mod.VERDICTS),
            # what summary.metrics and /api/corpus/metrics are compared against
            "metrics_reference": metrics_mod.REFERENCE,
            "metric_labels": metrics_mod.METRIC_LABELS,
            "options": {
                "whisper_models": list(get_args(Options.model_fields["whisper_model"].annotation)),
                "phone_engines": [
                    e for e in get_args(Options.model_fields["phone_engine"].annotation)
                    if e not in ENGINE_ALIASES
                ],
                "phone_engine_notes": ENGINE_NOTES,
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
            raise HTTPException(400, f"invalid options: {exc}") from exc

        # Same rule as when analyzing by path: video or audio only.
        name = Path(file.filename or "").name
        if not name or Path(name).suffix.lower() not in MEDIA_SUFFIXES:
            raise HTTPException(
                400, f"does not look like a video or an audio file: {file.filename!r}")

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
            raise HTTPException(400, f"does not look like a URL: {req.url}")
        job = _store().create_from_url(req.url, options=req.options.model_dump(),
                                       audio_only=req.audio_only)
        return job.to_public(full=True)

    @app.post("/api/jobs/import", status_code=201)
    def import_job(req: ImportRequest) -> dict:
        return _store().import_dir(_checked(req.path, want_dir=True)).to_public(full=True)

    # --- corpus (SQLite index across analyses) -------------------------------
    def _corpus() -> Corpus:
        corpus = _store().corpus
        if corpus is None:
            raise HTTPException(503, "this server is running without a corpus")
        return corpus

    @app.get("/api/corpus/stats")
    def corpus_stats() -> dict:
        return _corpus().stats()

    @app.get("/api/corpus/metrics")
    def corpus_metrics() -> dict:
        """How much of the corpus is reduced, pooled token by token, next to the
        report's reference values."""
        return {**_corpus().metrics(), "reference": metrics_mod.REFERENCE}

    @app.get("/api/corpus/analyses")
    def corpus_analyses() -> dict:
        """What the corpus is made of: without this, "3 analyses" may be the
        same video three times and the global figures mislead."""
        return {"items": _corpus().analyses()}

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
        """How a word has actually been pronounced across the whole corpus."""
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

    # --- live state (WebSocket: the interface does not poll) ------------------
    def _ws_origin_allowed(ws: WebSocket) -> bool:
        """A WebSocket does NOT go through CORS: without this filter any page
        open in the browser could read your list of analyses. We accept clients
        with no Origin (curl, tests), loopback origins (the SPA and Vite's
        proxy) and the very host they connected to."""
        origin = ws.headers.get("origin")
        if origin is None:
            return True
        host = urlparse(origin).hostname
        return host in ("localhost", "127.0.0.1", "::1") or host == ws.url.hostname

    @app.websocket("/ws/jobs")
    async def jobs_ws(ws: WebSocket) -> None:
        """Initial snapshot + "job" / "deleted" events as the store changes.

        The store listener only queues the id, and the state is re-read here at
        SEND time rather than when the event happened: every message carries the
        complete, fresh job, so applying them is idempotent and they can never
        make the client go backwards (a stale event just resends the current
        state).
        """
        if not _ws_origin_allowed(ws):
            await ws.close(code=1008)   # policy violation
            return
        await ws.accept()
        loop = asyncio.get_running_loop()
        pending: asyncio.Queue[tuple[str, str]] = asyncio.Queue()

        def on_store_event(event: str, job_id: str) -> None:
            # Called from the worker thread: hop over to the asyncio loop.
            loop.call_soon_threadsafe(pending.put_nowait, (event, job_id))

        unsubscribe = _store().subscribe(on_store_event)

        async def publish(scope: anyio.CancelScope) -> None:
            try:
                await ws.send_json({
                    "type": "snapshot",
                    "jobs": [j.to_public(full=True) for j in _store().list()],
                })
                while True:
                    event, job_id = await pending.get()
                    if event == "deleted":
                        await ws.send_json({"type": "deleted", "id": job_id})
                        continue
                    try:
                        job = _store().get(job_id)
                    except JobNotFound:
                        continue   # deleted in the meantime: its "deleted" comes next
                    await ws.send_json({"type": "job", "job": job.to_public(full=True)})
            except (WebSocketDisconnect, RuntimeError):
                pass               # the socket went away mid-send: same as leaving
            scope.cancel()

        async def until_disconnect(scope: anyio.CancelScope) -> None:
            # The client never talks; reading is how its leaving is noticed. A
            # handler that only sent sat in `pending.get()` forever once the tab
            # closed, and uvicorn's shutdown waited for it: Ctrl-C hung.
            try:
                while (await ws.receive())["type"] != "websocket.disconnect":
                    pass
            except (WebSocketDisconnect, RuntimeError):
                pass
            scope.cancel()

        # An anyio task group, not bare asyncio tasks: the server (and the test
        # client) cancel this handler through an anyio cancel scope, and native
        # tasks awaited with asyncio.wait/gather let that cancellation leak out
        # of the scope — the test client's teardown then failed with
        # CancelledError about one time in five.
        try:
            async with anyio.create_task_group() as tg:
                tg.start_soon(publish, tg.cancel_scope)
                tg.start_soon(until_disconnect, tg.cancel_scope)
        finally:
            unsubscribe()

    # --- artifacts ----------------------------------------------------------
    @app.get("/api/jobs/{job_id}/analysis")
    def get_analysis(job_id: str) -> FileResponse:
        return FileResponse(_artifact(job_id, "analysis.json"),
                            media_type="application/json")

    @app.get("/api/jobs/{job_id}/audio")
    def get_audio(job_id: str, track: Literal["mix", "dialogue"] = "mix") -> FileResponse:
        # Starlette's FileResponse answers Range requests → seeking in <audio>.
        # "dialogue" is the separated speech the analysis actually read — only if
        # its meta says separation ran for it (not a leftover from an earlier run).
        if track == "dialogue":
            path = _job(job_id).dialogue_audio()
            if path is None:
                raise HTTPException(404, f"{job_id} has no separated dialogue")
            return FileResponse(path, media_type="audio/wav")
        return FileResponse(_artifact(job_id, "audio.wav"), media_type="audio/wav")

    @app.get("/api/jobs/{job_id}/media")
    def get_media(job_id: str) -> FileResponse:
        job = _job(job_id)
        path = Path(job.media_path) if job.media_path else None
        if path is None or not path.is_file():
            raise HTTPException(404, "this analysis has no original file")
        return FileResponse(path,
                            media_type=MEDIA_TYPES.get(path.suffix.lower(),
                                                       "application/octet-stream"))

    @app.get("/api/jobs/{job_id}/report", response_class=HTMLResponse)
    def get_report(job_id: str) -> Response:
        # An imported out/ may come from elsewhere: the report is served from an
        # opaque origin (sandbox) so its JS cannot talk to this API.
        headers = {"Content-Security-Policy": "sandbox allow-scripts",
                   "X-Content-Type-Options": "nosniff"}
        # Rendered from analysis.json on every request: an out/ written by an
        # older version carries a report.html that predates the metrics, the
        # practice axis and the new labels. The file on disk is only the fallback.
        try:
            html = report.render_html(_analysis(job_id))
        except Exception:                             # noqa: BLE001
            return FileResponse(_artifact(job_id, "report.html"),
                                media_type="text/html", headers=headers)
        return HTMLResponse(html, headers=headers)

    # --- human review -------------------------------------------------------
    @app.get("/api/jobs/{job_id}/review")
    def get_review(job_id: str) -> dict:
        path = _job(job_id).artifact("review.json")
        if path is None:
            return {"items": [], "sampled": 0, "ok": 0, "wrong": 0, "unsure": 0,
                    "accuracy": None, "seed": review_mod.DEFAULT_SEED}
        saved = _read_json(path)
        # API 3 renamed the verdicts. A review.json written before that carries
        # values this vocabulary no longer has, and handing them to the UI would
        # be worse than losing them: the interface loads a saved review into its
        # state, so the next save would send them straight back and the PUT
        # would reject the whole batch. We drop those entries and recount, so
        # the answer always matches the contract; the file itself is untouched.
        items = [item for item in saved.get("items") or []
                 if item.get("verdict") in review_mod.VERDICTS]
        payload = {"seed": saved.get("seed", review_mod.DEFAULT_SEED),
                   "items": items}
        payload.update(review_mod.summarize(items))
        return payload

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
        _store().touch(job_id)        # has_review changed: tell the WS clients
        payload = {"seed": req.seed, "items": items}
        payload.update(review_mod.summarize(items))
        return payload

    # --- file browser -------------------------------------------------------
    @app.get("/api/browse")
    def browse(path: str | None = None) -> dict:
        home = app.state.roots[0]
        target = _resolve(path) if path else Path.cwd().resolve()
        if not _inside_roots(target):
            target = home            # we never leave the allowed roots
        if not target.is_dir():
            target = target.parent if target.parent.is_dir() else home

        dirs, files = [], []
        try:
            entries = sorted(target.iterdir(), key=lambda p: p.name.lower())
        except PermissionError as exc:
            raise HTTPException(403, f"no permission to read {target}") from exc
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
        if full_path.startswith("api/"):     # a misspelled API route is a 404
            raise HTTPException(404, f"unknown endpoint: /{full_path}")
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
<title>PhonoTrainer — interface not built</title>
<style>body{font:15px/1.6 system-ui;max-width:640px;margin:60px auto;padding:0 20px;
color-scheme:light dark}code{background:#8883;padding:1px 5px;border-radius:4px}</style>
<h1>The interface has not been built</h1>
<p>The API already works (<a href="/api/docs">/api/docs</a>). For the interface:</p>
<pre><code>cd web &amp;&amp; npm install &amp;&amp; npm run build</code></pre>
<p>or, during development, <code>npm run dev</code> (proxying to the backend on this
server's port).</p>
"""


def serve(workspace: str | Path = DEFAULT_WORKSPACE, host: str = "127.0.0.1",
          port: int = 8000, reload: bool = False, open_browser: bool = True,
          allowed_roots: list[str | Path] | None = None) -> None:
    """Start uvicorn with the app (used by `phonotrainer ui`)."""
    import uvicorn

    if open_browser:
        import threading
        import webbrowser

        timer = threading.Timer(1.2, lambda: webbrowser.open(f"http://{host}:{port}"))
        timer.daemon = True      # it must not delay shutdown on Ctrl-C
        timer.start()

    os.environ["PHONOTRAINER_WORKSPACE"] = str(workspace)
    if allowed_roots:
        os.environ["PHONOTRAINER_ROOTS"] = os.pathsep.join(str(r) for r in allowed_roots)
    uvicorn.run("phonotrainer.server:app_from_env" if reload
                else create_app(workspace, allowed_roots=allowed_roots),
                host=host, port=port, reload=reload, factory=reload)


def app_from_env() -> FastAPI:
    """Factory for `uvicorn --reload` (which cannot take an already built app)."""
    roots = os.environ.get("PHONOTRAINER_ROOTS")
    return create_app(os.environ.get("PHONOTRAINER_WORKSPACE", DEFAULT_WORKSPACE),
                      allowed_roots=roots.split(os.pathsep) if roots else None)
