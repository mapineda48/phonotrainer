"""The `/api/phone` HTTP routes (server-contract 6).

`router` is included by `create_app(phone=…)`; `off_router` by every app
started without phone access: it answers `hello` (`phone_access: false`) and
`403 phone_access_off` everywhere else under `/api/phone/`.
"""

from __future__ import annotations

import re
import time
from pathlib import Path

import anyio
from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, model_validator

from .. import __version__
from ..jobs import (CANCELLED, DONE, ERROR, MEDIA_SUFFIXES, PHONE_ORIGIN, QUEUED, RUNNING,
                    JobError, JobNotFound, NotRerunnable, SourceGone)
from . import MIN_APP_VERSION, MIN_FREE_BYTES
from .artifacts import ARTIFACTS
from .auth import current_device
from .errors import NoStoreJSONResponse, PhoneError, error_response
from .events import phone_events
from .identity import clean_text
from .registry import MAX_DEVICE_NAME_CHARS, Device, PairingError, hello_proof
from .runtime import PhoneRuntime
from .shape import phone_job
from .ssrf import CannotCheckLink, InvalidLink, LinkRefused, check_link
from .state import source_kind
from .uploads import MAX_FILE_NAME_CHARS, TAKEOVER_WAIT_S, normalize_key

ALL_METHODS = ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
DEVICE_ID_PATTERN = r"^[A-Za-z0-9_]{1,64}$"
KEY_PATTERN = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
CHALLENGE_PATTERN = r"^[0-9a-f]{32,64}$"
JOB_ID_RE = re.compile(r"^\d{8}-\d{6}-[0-9a-f]{4}$")

router = APIRouter(prefix="/api/phone", default_response_class=NoStoreJSONResponse)
off_router = APIRouter(prefix="/api/phone", default_response_class=NoStoreJSONResponse)


def _phone(request: Request) -> PhoneRuntime:
    return request.app.state.phone


def _now(request: Request) -> int:
    phone = getattr(request.app.state, "phone", None)
    return int(phone.clock() if phone is not None else time.time())


def _source(request: Request) -> str:
    return (request.scope.get("client") or ("unknown",))[0]


# --- identity, pairing, health (6.2) ---------------------------------------------------------

@router.get("/hello")
def hello(request: Request,
          device_id: str | None = Query(None, pattern=DEVICE_ID_PATTERN),
          challenge: str | None = Query(None, pattern=CHALLENGE_PATTERN)) -> dict:
    """Who answers here. With `device_id` and `challenge`, an HMAC proof keyed
    by that device's key: only the computer that issued its token can make it."""
    phone = _phone(request)
    if (device_id is None) != (challenge is None):
        raise PhoneError(400, "invalid_request",
                         "device_id and challenge go together.")
    if phone.state != "on":
        return {"service": "phonotrainer", "phone_access": False,
                "api_version": phone.api_version, "server_time": _now(request)}
    proof = revoked = None
    if device_id is not None:
        retry = phone.hello_limiter.hit(_source(request))
        if retry is not None:
            raise PhoneError(429, "rate_limited", headers={"Retry-After": str(retry)})
        device = phone.registry.get(device_id)
        if device is not None:
            proof = hello_proof(device.key, phone.identity.server_id, device_id, challenge)
            revoked = device.revoked
    return {"service": "phonotrainer", "phone_access": True,
            "server_id": phone.identity.server_id, "name": phone.identity.name,
            "api_version": phone.api_version, "server_time": _now(request),
            "proof": proof, "revoked": revoked}


class PairBody(BaseModel):
    model_config = ConfigDict(extra="ignore")

    code: str = Field(pattern=r"^[0-9]{6}$")
    device_name: str = Field(min_length=1, max_length=200)
    app_version: int = Field(ge=1)
    app_version_name: str | None = Field(None, max_length=40)
    previous_device_id: str | None = Field(None, pattern=DEVICE_ID_PATTERN)
    previous_token_proof: str | None = Field(None, pattern=r"^[0-9a-fA-F]{64}$")

    @model_validator(mode="after")
    def _previous_together(self) -> "PairBody":
        if (self.previous_device_id is None) != (self.previous_token_proof is None):
            raise ValueError("previous_device_id and previous_token_proof go together")
        return self


@router.post("/pair", status_code=201)
def pair(request: Request, body: PairBody) -> dict:
    phone = _phone(request)
    name = clean_text(body.device_name)
    if not 1 <= len(name) <= MAX_DEVICE_NAME_CHARS:
        raise PhoneError(400, "invalid_request",
                         f"device_name must be 1 to {MAX_DEVICE_NAME_CHARS} characters long.")
    try:
        device, token = phone.registry.pair(
            body.code, name, body.app_version, body.app_version_name, _source(request),
            previous_device_id=body.previous_device_id,
            previous_token_proof=body.previous_token_proof)
    except PairingError as exc:
        headers = {"Retry-After": str(exc.retry_after)} if exc.retry_after else None
        raise PhoneError(exc.status, exc.code, headers=headers) from None
    return {"device_id": device.device_id, "token": token,
            "server_id": phone.identity.server_id, "name": phone.identity.name,
            "library_id": phone.library_id, "api_version": phone.api_version,
            "min_app_version": MIN_APP_VERSION, "server_time": _now(request)}


@router.get("/health")
def health(request: Request, device: Device = Depends(current_device)) -> dict:
    """The authenticated probe. Memory and cached values only: it answers while
    an analysis holds the worker."""
    phone = _phone(request)
    store = request.app.state.store
    jobs = store.list()
    return {
        "server_id": phone.identity.server_id,
        "name": phone.identity.name,
        "library_id": phone.library_id,
        "api_version": phone.api_version,
        "min_app_version": MIN_APP_VERSION,
        "version": __version__,
        "server_time": _now(request),
        "device": {"device_id": device.device_id, "name": device.name},
        "queue": {"analyzing": sum(1 for j in jobs if j.status == RUNNING),
                  "in_line": sum(1 for j in jobs
                                 if j.status == QUEUED and not j.cancel_requested)},
        "low_on_space": phone.capabilities.low_on_space(),
        "capabilities": phone.capabilities.as_dict(),
    }


@router.delete("/devices/self", status_code=204)
def forget_this_computer(request: Request, device: Device = Depends(current_device)) -> Response:
    """The phone forgets this computer: its device is revoked."""
    _phone(request).registry.revoke(device.device_id)
    return Response(status_code=204)


# --- the library (6.3) --------------------------------------------------------------------

@router.get("/library")
def library(request: Request, device: Device = Depends(current_device)) -> dict:
    phone = _phone(request)
    store = request.app.state.store
    positions = store.queue_positions()
    jobs = sorted(store.list(), key=lambda j: j.id)
    return {
        "api_version": phone.api_version,
        "server_id": phone.identity.server_id,
        "library_id": phone.library_id,
        "generated_at": phone.now_iso(),
        "complete": True,
        "jobs": [phone_job(j, device.device_id, phone.artifacts, positions) for j in jobs],
    }


def _job_or_404(request: Request, job_id: str):
    if not JOB_ID_RE.match(job_id):
        raise PhoneError(404, "unknown_job")
    try:
        return request.app.state.store.get(job_id)
    except JobNotFound:
        raise PhoneError(404, "unknown_job") from None


@router.get("/jobs/{job_id}")
def get_job(job_id: str, request: Request, device: Device = Depends(current_device)) -> dict:
    job = _job_or_404(request, job_id)
    store = request.app.state.store
    return phone_job(job, device.device_id, _phone(request).artifacts, store.queue_positions())


# --- artifacts (6.4) ----------------------------------------------------------------------

@router.get("/jobs/{job_id}/artifacts/{name}")
def get_artifact(job_id: str, name: str, request: Request,
                 device: Device = Depends(current_device)) -> Response:   # noqa: ARG001
    job = _job_or_404(request, job_id)
    if name not in ARTIFACTS:
        raise PhoneError(404, "unknown_artifact")
    if job.status != DONE:
        raise PhoneError(409, "artifact_not_ready")
    index = _phone(request).artifacts
    listed = dict(index.files(job))
    if name not in listed:
        raise PhoneError(404, "unknown_artifact")
    path: Path | None = listed[name]
    known = index.checksum(job, name, path) if path is not None else None
    if known is None:
        if path is not None:
            index.request(job.id)
        raise PhoneError(409, "artifact_not_ready")
    _, sha = known
    return FileResponse(path, media_type=index.content_type(name, path),
                        headers={"ETag": f'"{sha}"'})


# --- reference (6.8) ------------------------------------------------------------------------

@router.get("/reference")
def reference(request: Request, device: Device = Depends(current_device)) -> Response:  # noqa: ARG001
    phone = _phone(request)
    return NoStoreJSONResponse(phone.reference(),
                               headers={"ETag": f'"api-{phone.api_version}"'})


# --- requests from phones: links, cancel, delete, re-run (6.3, 6.5, 6.7) ------------------------

def _job_dict(request: Request, job, device: Device, lean: bool = False) -> dict:
    store = request.app.state.store
    return phone_job(job, device.device_id, _phone(request).artifacts, store.queue_positions(),
                     lean=lean)


def _key_held_by_upload(request: Request, device: Device, key: str) -> None:
    """A key that opened an upload session cannot also create a link or a re-run."""
    if _phone(request).uploads.key_in_use(device.device_id, key):
        raise PhoneError(409, "key_conflict")


def _default_options(separate_dialogue: bool | None) -> dict:
    """Every analysis option is the server's default (spec D-08), except the
    dialogue separation a phone may choose."""
    from ..server import Options

    options = Options().model_dump()
    if separate_dialogue is not None:
        options["separate_dialogue"] = bool(separate_dialogue)
    return options


def _check_link(request: Request, url: str) -> str:
    try:
        return check_link(url, _phone(request).settings.resolver)
    except InvalidLink:
        raise PhoneError(400, "invalid_url") from None
    except LinkRefused:
        raise PhoneError(400, "refused_address") from None
    except CannotCheckLink:
        raise PhoneError(503, "cannot_check_link") from None


class LinkBody(BaseModel):
    model_config = ConfigDict(extra="ignore")

    client_request_id: str = Field(pattern=KEY_PATTERN)
    url: str = Field(max_length=8192)
    audio_only: StrictBool | None = False
    separate_dialogue: StrictBool | None = None


@router.post("/jobs/link", status_code=201)
def create_link(body: LinkBody, request: Request,
                device: Device = Depends(current_device)) -> Response:
    """A link to analyze. Idempotent by `client_request_id`; checked against
    private and local addresses before a job exists; the job is phone-origin,
    so its download runs confined."""
    store = request.app.state.store
    key = body.client_request_id.lower()
    url = body.url.strip()

    def created_by_key():
        job = store.find_by_request(device.device_id, key)
        if job is not None:
            if source_kind(job) == "link" and job.rerun_of is None and job.source_url == url:
                return job
            raise PhoneError(409, "key_conflict")
        _key_held_by_upload(request, device, key)
        return None

    with store.requests_locked():
        existing = created_by_key()
    if existing is None:
        url = _check_link(request, url)
        with store.requests_locked():
            existing = created_by_key()
            if existing is None:
                job = store.create_from_url(
                    url, _default_options(body.separate_dialogue),
                    audio_only=bool(body.audio_only), device_id=device.device_id,
                    client_request_id=key, origin=PHONE_ORIGIN)
                return NoStoreJSONResponse({"job": _job_dict(request, job, device),
                                            "created": True}, status_code=201)
    return NoStoreJSONResponse({"job": _job_dict(request, existing, device), "created": False},
                               status_code=200)


@router.get("/requests/{key}")
def request_lookup(key: str, request: Request, device: Device = Depends(current_device)) -> dict:
    """The job a key of this device created (another device's key is unknown)."""
    normalized = normalize_key(key)
    job = (request.app.state.store.find_by_request(device.device_id, normalized)
           if normalized else None)
    if job is None:
        raise PhoneError(404, "unknown_request")
    return {"job": _job_dict(request, job, device)}


def _own_job(request: Request, job_id: str, device: Device):
    job = _job_or_404(request, job_id)
    if job.device_id != device.device_id:
        raise PhoneError(403, "not_own_job")
    return job


@router.post("/jobs/{job_id}/cancel")
def cancel_job(job_id: str, request: Request, device: Device = Depends(current_device)) -> dict:
    """A queued job is cancelled at once; a running one at its next progress
    message; a failed or cancelled one is returned unchanged; a done one is
    kept (`already_finished`)."""
    job = _own_job(request, job_id, device)
    if job.status == DONE:
        raise PhoneError(409, "already_finished", job=_job_dict(request, job, device))
    if job.status in (QUEUED, RUNNING):
        try:
            job = request.app.state.store.cancel(job_id, now_if_queued=True)
        except JobNotFound:
            raise PhoneError(404, "unknown_job") from None
    return {"job": _job_dict(request, job, device)}


@router.delete("/jobs/{job_id}", status_code=204)
def delete_job(job_id: str, request: Request, device: Device = Depends(current_device)) -> Response:
    """Only failed or cancelled own jobs: finished analyses are deleted on the
    computer, never from a phone."""
    job = _own_job(request, job_id, device)
    if job.status not in (ERROR, CANCELLED):
        raise PhoneError(409, "not_deletable")
    try:
        request.app.state.store.delete(job_id)
    except JobNotFound:
        raise PhoneError(404, "unknown_job") from None
    return Response(status_code=204)


class RerunBody(BaseModel):
    model_config = ConfigDict(extra="ignore")

    client_request_id: str = Field(pattern=KEY_PATTERN)
    audio_only: StrictBool | None = None


@router.post("/jobs/{job_id}/rerun", status_code=201)
def rerun_job(job_id: str, body: RerunBody, request: Request,
              device: Device = Depends(current_device)) -> Response:
    """"Try again", "Try audio only", and the automatic re-run after an
    interruption: the job is created again from what this computer stored,
    and the old one is deleted."""
    store = request.app.state.store
    key = body.client_request_id.lower()

    def created_by_key():
        job = store.find_by_request(device.device_id, key)
        if job is not None:
            if job.rerun_of == job_id:
                return job
            raise PhoneError(409, "key_conflict")
        _key_held_by_upload(request, device, key)
        return None

    def answer(job, status: int) -> Response:
        return NoStoreJSONResponse({"job": _job_dict(request, job, device), "replaced": job_id},
                                   status_code=status)

    with store.requests_locked():
        existing = created_by_key()
    if existing is not None:
        return answer(existing, 200)
    old = _own_job(request, job_id, device)
    if old.status not in (ERROR, CANCELLED):
        raise PhoneError(409, "not_rerunnable")
    kind = source_kind(old)
    if body.audio_only is not None and kind != "link":
        raise PhoneError(400, "invalid_option")
    if kind == "link":
        try:
            check_link(old.source_url, _phone(request).settings.resolver)
        except (InvalidLink, LinkRefused):
            raise PhoneError(400, "refused_address") from None
        except CannotCheckLink:
            raise PhoneError(503, "cannot_check_link") from None
    with store.requests_locked():
        existing = created_by_key()
        if existing is not None:
            return answer(existing, 200)
        try:
            new = store.rerun(job_id, device.device_id, key, body.audio_only)
        except JobNotFound:
            raise PhoneError(404, "unknown_job") from None
        except NotRerunnable:
            raise PhoneError(409, "not_rerunnable") from None
        except SourceGone:
            raise PhoneError(410, "source_gone") from None
    return answer(new, 201)


# --- uploads (6.6) ---------------------------------------------------------------------------

# The server's media suffixes, in the contract's order (a test keeps it equal
# to jobs.MEDIA_SUFFIXES).
SUFFIXES_TEXT = ".webm .mp4 .mkv .mov .avi .m4v .mp3 .wav .m4a .flac .ogg .opus .aac"


class UploadBody(BaseModel):
    model_config = ConfigDict(extra="ignore")

    client_request_id: str = Field(pattern=KEY_PATTERN)
    file_name: str = Field(min_length=1, max_length=1024)
    bytes: StrictInt = Field(ge=1)
    sha256: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
    separate_dialogue: StrictBool | None = None


@router.post("/uploads", status_code=201)
def declare_upload(body: UploadBody, request: Request,
                   device: Device = Depends(current_device)) -> Response:
    """Declare a file before sending it: refused early (type, size, space,
    sessions), before any byte is sent."""
    phone = _phone(request)
    store = request.app.state.store
    key = body.client_request_id.lower()
    if "/" in body.file_name or "\\" in body.file_name:
        raise PhoneError(400, "invalid_request", "file_name must be a base name.")
    file_name = clean_text(body.file_name)
    if not file_name or len(file_name) > MAX_FILE_NAME_CHARS or file_name in (".", ".."):
        raise PhoneError(400, "invalid_request",
                         f"file_name must be 1 to {MAX_FILE_NAME_CHARS} characters long.")
    sha256 = body.sha256.lower()
    with store.requests_locked():
        job = store.find_by_request(device.device_id, key)
        if job is not None:
            if (source_kind(job) == "upload" and job.rerun_of is None and job.media_path
                    and Path(job.media_path).name == file_name):
                return NoStoreJSONResponse({"state": "done", "job": _job_dict(request, job, device)},
                                           status_code=200)
            raise PhoneError(409, "key_conflict")
        session = phone.uploads.get(device.device_id, key)
        if session is not None:
            if not session.same_file(file_name, body.bytes, sha256):
                raise PhoneError(409, "key_conflict")
            return NoStoreJSONResponse(phone.uploads.open_state(session), status_code=200)
        if Path(file_name).suffix.lower() not in MEDIA_SUFFIXES:
            raise PhoneError(400, "unsupported_type",
                             f"PhonoTrainer accepts {SUFFIXES_TEXT} files.")
        limit = phone.identity.max_upload_bytes
        if body.bytes > limit:
            raise PhoneError(413, "too_large",
                             f"Files are limited to {limit} bytes on this computer.")
        created, session = phone.uploads.declare(
            device.device_id, key, file_name, body.bytes, sha256, body.separate_dialogue,
            phone.capabilities.disk_free_bytes)
    return NoStoreJSONResponse(phone.uploads.open_state(session),
                               status_code=201 if created else 200)


def _closing(status: int, code: str, detail: str | None = None) -> Response:
    """An answer that also closes the connection (the body was not read)."""
    return error_response(status, code, detail, headers={"Connection": "close"})


@router.put("/uploads/{key}", status_code=201)
async def put_upload(key: str, request: Request,
                     device: Device = Depends(current_device)) -> Response:
    """The whole file in one body (v1-core)."""
    phone = _phone(request)
    store = request.app.state.store
    uploads = phone.uploads
    key = normalize_key(key) or ""
    lengths = request.headers.getlist("content-length")
    if not lengths or "chunked" in request.headers.get("transfer-encoding", "").lower():
        return _closing(411, "length_required")
    if len(lengths) > 1 or not lengths[0].strip().isdigit():
        return _closing(400, "invalid_request", "Content-Length is malformed.")
    length = int(lengths[0])
    session = uploads.get(device.device_id, key) if key else None
    if session is None:
        job = store.find_by_request(device.device_id, key) if key else None
        if job is not None and source_kind(job) == "upload" and job.rerun_of is None:
            await uploads.drain(request.receive)          # a lost answer: read and discard
            return NoStoreJSONResponse({"job": _job_dict(request, job, device)})
        return _closing(404, "unknown_upload")
    try:
        attempt, replaced = uploads.begin(device.device_id, key)
    except PhoneError as exc:
        return _closing(exc.status, exc.code, exc.detail)
    try:
        if replaced is not None:
            # Newest wins: the older body lets go of the part within a poll.
            waited = 0.0
            while not replaced.finished.is_set():
                if waited >= TAKEOVER_WAIT_S:
                    return _closing(409, "upload_busy")
                await anyio.sleep(0.05)
                waited += 0.05
        if length != session.bytes:
            return _closing(400, "length_mismatch")
        if phone.capabilities.free_bytes() < MIN_FREE_BYTES:
            return _closing(507, "no_space")
        outcome, part = await uploads.receive(request.receive, session, attempt)
        if outcome != "ok":
            uploads.touch(session)
            return _upload_failure(outcome)
        file_name, options = session.file_name, _default_options(session.separate_dialogue)

        def adopt():
            with store.requests_locked():
                if uploads.get(device.device_id, key) is not session:
                    part.unlink(missing_ok=True)          # removed while it arrived
                    return None
                job = store.adopt_file(part, file_name, options, device_id=device.device_id,
                                       client_request_id=key, origin=PHONE_ORIGIN)
                uploads.finish(session)
                return job

        try:
            job = await anyio.to_thread.run_sync(adopt)
        except JobError:
            return error_response(400, "length_mismatch")
        if job is None:
            return error_response(404, "unknown_upload")
        return NoStoreJSONResponse({"job": _job_dict(request, job, device)}, status_code=201)
    finally:
        uploads.end(attempt)


def _upload_failure(outcome: str) -> Response:
    if outcome == "checksum_mismatch":
        return error_response(422, "checksum_mismatch")
    if outcome == "length_mismatch":
        return _closing(400, "length_mismatch")
    if outcome == "idle":
        return _closing(400, "length_mismatch",
                        "The upload sent nothing for 30 seconds; send it again.")
    if outcome == "superseded":
        return _closing(409, "upload_busy", "A newer upload of this file replaced this one.")
    if outcome == "no_space":
        return _closing(507, "no_space")
    if outcome == "disconnected":
        return _closing(400, "length_mismatch", "The upload was interrupted; send it again.")
    return _closing(500, "server_error", "This computer could not store the upload.")


@router.delete("/uploads/{key}", status_code=204)
def abandon_upload(key: str, request: Request, device: Device = Depends(current_device)) -> Response:
    """"Remove" while sending: always 204."""
    normalized = normalize_key(key)
    if normalized:
        _phone(request).uploads.remove(device.device_id, normalized)
    return Response(status_code=204)


# --- the job channel (7) --------------------------------------------------------------------

router.add_api_websocket_route("/events", phone_events)


# --- every other path (keep these last) ---------------------------------------------------

def _unknown_route() -> None:
    raise PhoneError(404, "unknown_route")


router.add_api_route("", _unknown_route, methods=ALL_METHODS, include_in_schema=False)
router.add_api_route("/{rest:path}", _unknown_route, methods=ALL_METHODS,
                     include_in_schema=False)


# --- without phone access ---------------------------------------------------------------------

@off_router.get("/hello")
def hello_off(request: Request) -> dict:
    """Exists in every API ≥ 6 server, whatever the phone-access state."""
    from ..server import API_VERSION

    return {"service": "phonotrainer", "phone_access": False, "api_version": API_VERSION,
            "server_time": _now(request)}


def _phone_access_off() -> None:
    raise PhoneError(403, "phone_access_off")


off_router.add_api_route("", _phone_access_off, methods=ALL_METHODS, include_in_schema=False)
off_router.add_api_route("/{rest:path}", _phone_access_off, methods=ALL_METHODS,
                         include_in_schema=False)
