"""The `/api/phone` HTTP routes (server-contract 6).

`router` is included by `create_app(phone=…)`; `off_router` by every app
started without phone access: it answers `hello` (`phone_access: false`) and
`403 phone_access_off` everywhere else under `/api/phone/`.
"""

from __future__ import annotations

import re
import time
from pathlib import Path

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .. import __version__
from ..jobs import DONE, QUEUED, RUNNING, JobNotFound
from . import MIN_APP_VERSION
from .artifacts import ARTIFACTS
from .auth import current_device
from .errors import NoStoreJSONResponse, PhoneError
from .identity import clean_text
from .registry import MAX_DEVICE_NAME_CHARS, Device, PairingError, hello_proof
from .runtime import PhoneRuntime
from .shape import phone_job

ALL_METHODS = ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
DEVICE_ID_PATTERN = r"^[A-Za-z0-9_]{1,64}$"
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
