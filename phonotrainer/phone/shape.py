"""The PhoneJob: a job as a phone sees it (server-contract 6.3).

Built by its own serializer, never from `Job.to_public()`: it carries no path,
no workspace, no progress log and no `meta.source` beyond a cleaned title, and
it shows another phone's job without that phone's device id.
"""

from __future__ import annotations

import hashlib
import json

from ..jobs import CANCELLED, DONE, ERROR, Job
from ..separation import DEFAULT_ENABLED as SEPARATE_BY_DEFAULT
from .artifacts import ArtifactIndex
from .identity import downloader_version
from .state import (DOWNLOAD_CODES, classify_error, clean_message, progress_of,
                    separation_of, site_of, source_kind, stage_of, title_of)


def revision_of(status: str, error_code: str | None, artifacts: list[dict]) -> str:
    """First 16 hex digits of sha256 over `[status, error.code, [[name, status,
    sha256], …]]`: it changes with the status, the error or an artifact, and not
    with percent, stage or queue position."""
    payload = [status, error_code, [[a["name"], a["status"], a["sha256"]] for a in artifacts]]
    text = json.dumps(payload, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(text.encode("ascii")).hexdigest()[:16]


def error_of(job: Job) -> dict | None:
    if job.status not in (ERROR, CANCELLED):
        return None
    code = classify_error(job.status, job.error)
    error = {"code": code, "message": clean_message(job.error)}
    if code in DOWNLOAD_CODES:
        error["downloader_version"] = downloader_version()
    return error


def summary_of(job: Job) -> dict | None:
    summary = job.summary if isinstance(job.summary, dict) else None
    if not summary:
        return None
    return {"duration_s": summary.get("duration"), "segments": summary.get("segments"),
            "words": summary.get("words"),
            "phenomena_counts": dict(summary.get("phenomena_counts") or {})}


def analysis_of(job: Job) -> dict | None:
    meta = job.meta if isinstance(job.meta, dict) else None
    if not meta:
        return None
    from ..phones_real import engine_of

    return {"rules_version": meta.get("rules_version"),
            "phone_engine": engine_of(meta),
            "dialogue_separation": bool((meta.get("dialogue_separation") or {}).get("applied")),
            "phonotrainer_version": meta.get("phonotrainer_version")}


def phone_job(job: Job, caller: str | None, artifacts: ArtifactIndex,
              queue_positions: dict[str, int], lean: bool = False) -> dict:
    """The PhoneJob of `job` for the device `caller`. `lean` (channel events)
    leaves the artifact list out; the revision is the same either way."""
    entries = artifacts.entries(job)
    error = error_of(job)
    own = caller is not None and job.device_id == caller
    kind = source_kind(job)
    options = job.options or {}
    data = {
        "id": job.id,
        "revision": revision_of(job.status, error["code"] if error else None, entries),
        "status": job.status,
        "stage": stage_of(job),
        "progress": progress_of(job),
        "separation": separation_of(job),
        "queue_position": queue_positions.get(job.id),
        "title": title_of(job),
        "source": {
            "kind": kind,
            "url": job.source_url if kind == "link" else None,
            "site": site_of(job.source_url) if kind == "link" else None,
            "file_name": (job.media_path.rsplit("/", 1)[-1]
                          if kind in ("upload", "path") and job.media_path else None),
        },
        "options": {
            "audio_only": bool(job.audio_only) if kind == "link" else False,
            "separate_dialogue": bool(options.get("separate_dialogue", SEPARATE_BY_DEFAULT)),
        },
        "device_id": job.device_id if own else None,
        "own": own,
        "client_request_id": job.client_request_id if own else None,
        "rerun_of": job.rerun_of,
        "created": job.created,
        "started": job.started,
        "finished": job.finished,
        "error": error,
        "summary": summary_of(job),
        "analysis": analysis_of(job),
        "media": artifacts.media_facts(job),
    }
    if not lean:
        data["artifacts"] = entries if job.status == DONE else []
    return data
