"""Errors of the phone API: `{"detail": "<English sentence>", "code": "<stable code>"}`.

Every non-2xx answer of a phone route has this shape (server-contract 6.1 and
8.1). The phone keys its behaviour on `code`; `detail` is for people.
"""

from __future__ import annotations

from starlette.responses import JSONResponse

NO_STORE = {"Cache-Control": "no-store"}

# The default sentence for each code; a route may pass a more precise one.
DETAILS = {
    "credential_rejected": "This phone is not paired with this computer.",
    "clock_skew": "This phone's clock differs from this computer's.",
    "phone_access_off": "Phone access is off on this computer.",
    "lan_forbidden": "This part of PhonoTrainer is only available on the computer itself.",
    "unknown_route": "There is no such phone route.",
    "bad_host": "Unexpected Host header.",
    "invalid_request": "The request is malformed.",
    "too_large": "The request body is too large.",
    "length_required": "The request must declare its length.",
    "code_invalid": "That pairing code is not valid.",
    "code_expired": "That pairing code has expired. Create a new one on the computer.",
    "code_locked": "That pairing code is locked after too many wrong attempts. "
                   "Create a new one on the computer.",
    "rate_limited": "Too many attempts. Try again in a moment.",
    "app_too_old": "This app is too old for this computer. Update the app.",
    "unknown_job": "There is no such job on this computer.",
    "unknown_artifact": "There is no such file for this job.",
    "artifact_not_ready": "This file is not ready yet.",
    "unknown_request": "This phone sent no request with that id.",
    "unknown_upload": "There is no such upload; declare it first.",
    "invalid_url": "This is not a link PhonoTrainer can open.",
    "refused_address": "This link points to a private or local address.",
    "cannot_check_link": "This computer could not look up the link's website. Try again later.",
    "key_conflict": "This request id was already used for a different request.",
    "not_own_job": "This job was not sent from this phone.",
    "already_finished": "This job already finished; it was not cancelled.",
    "not_deletable": "Delete it on your computer.",
    "not_rerunnable": "Only a failed or cancelled job can run again.",
    "source_gone": "Your computer no longer has this file.",
    "invalid_option": "Audio only applies to links.",
    "unsupported_type": "PhonoTrainer cannot analyze this kind of file.",
    "no_space": "This computer would drop below 2 GiB of free space.",
    "length_mismatch": "The body length differs from the declared size.",
    "checksum_mismatch": "The file arrived damaged; send it again.",
    "upload_busy": "Another upload from this phone is still receiving bytes.",
}


class PhoneError(Exception):
    """An error answer of a phone route. Raised anywhere below a route; the
    app's exception handler (or a middleware) renders it."""

    def __init__(self, status: int, code: str, detail: str | None = None,
                 headers: dict[str, str] | None = None, **extra) -> None:
        super().__init__(code)
        self.status = status
        self.code = code
        self.detail = detail or DETAILS.get(code, "The request failed.")
        self.headers = dict(headers or {})
        self.extra = extra

    def response(self) -> JSONResponse:
        return error_response(self.status, self.code, self.detail, self.headers, **self.extra)


def error_response(status: int, code: str, detail: str | None = None,
                   headers: dict[str, str] | None = None, **extra) -> JSONResponse:
    body = {"detail": detail or DETAILS.get(code, "The request failed."), "code": code}
    body.update(extra)
    return JSONResponse(body, status_code=status, headers={**NO_STORE, **(headers or {})})


class NoStoreJSONResponse(JSONResponse):
    """The phone routes' JSON answers: never cached by anything in between."""

    def __init__(self, content, status_code: int = 200, headers=None, **kwargs) -> None:
        super().__init__(content, status_code=status_code,
                         headers={**NO_STORE, **(headers or {})}, **kwargs)
