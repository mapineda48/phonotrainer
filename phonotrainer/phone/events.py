"""The job channel for phones: `WS /api/phone/events` (server-contract 7; S-14).

The upgrade request carries a signed `Authorization: PT1 …` header (signed as
`GET /api/phone/events`); the signature is checked before `accept()`, and a
refused upgrade is closed before accept (the client sees HTTP 403). At most
eight phone channels are open at once, two per device.

Server → phone, JSON text frames, in this order:
1. `{"type": "hello", "server_id", "library_id", "api_version"}`;
2. `{"type": "snapshot", "jobs": [<lean PhoneJob>, …]}`, every job by id;
3. then `{"type": "job", "job": <lean PhoneJob>}` and `{"type": "deleted", "id"}`
   as the store changes.

A lean PhoneJob has no `artifacts` (and, like every PhoneJob, no progress log
and no path). Each event re-reads the job when it is sent, so applying events
is idempotent and never goes backwards; `job` events are coalesced to at most
one per job every 500 ms. The phone sends nothing; frames are read only to
notice a close, and its WebSocket pings are answered by the server's stack.
"""

from __future__ import annotations

import asyncio
import threading
import time

import anyio
from starlette.websockets import WebSocket, WebSocketDisconnect

from . import MAX_CHANNELS, MAX_CHANNELS_PER_DEVICE
from .auth import request_target
from .errors import PhoneError
from .shape import phone_job

COALESCE_S = 0.5
# How often an open channel checks that its phone is still paired and phone
# access still on (a revoked phone's channel closes within this time).
RECHECK_S = 5.0


class ChannelSlots:
    """At most MAX_CHANNELS open channels, MAX_CHANNELS_PER_DEVICE per device."""

    def __init__(self, total: int = MAX_CHANNELS, per_device: int = MAX_CHANNELS_PER_DEVICE) -> None:
        self.total = total
        self.per_device = per_device
        self._open: dict[str, int] = {}
        self._lock = threading.Lock()

    def take(self, device_id: str) -> bool:
        with self._lock:
            if sum(self._open.values()) >= self.total:
                return False
            if self._open.get(device_id, 0) >= self.per_device:
                return False
            self._open[device_id] = self._open.get(device_id, 0) + 1
            return True

    def give_back(self, device_id: str) -> None:
        with self._lock:
            left = self._open.get(device_id, 0) - 1
            if left > 0:
                self._open[device_id] = left
            else:
                self._open.pop(device_id, None)

    def count(self, device_id: str | None = None) -> int:
        with self._lock:
            return sum(self._open.values()) if device_id is None else self._open.get(device_id, 0)


async def phone_events(websocket: WebSocket) -> None:
    phone = websocket.app.state.phone
    store = websocket.app.state.store
    try:
        device = await anyio.to_thread.run_sync(
            phone.auth.verify, websocket.headers.get("authorization"), "GET",
            request_target(websocket.scope))
    except PhoneError:
        await websocket.close(code=1008)          # before accept: the handshake is refused
        return
    if not phone.channels.take(device.device_id):
        await websocket.close(code=1008)
        return
    try:
        await websocket.accept()
        await _serve(websocket, phone, store, device.device_id)
    finally:
        phone.channels.give_back(device.device_id)


async def _serve(websocket: WebSocket, phone, store, device_id: str) -> None:
    loop = asyncio.get_running_loop()
    pending: asyncio.Queue[tuple[str, str]] = asyncio.Queue()

    def on_store_event(event: str, job_id: str) -> None:
        loop.call_soon_threadsafe(pending.put_nowait, (event, job_id))

    unsubscribe = store.subscribe(on_store_event)

    def lean(job, positions: dict[str, int] | None = None) -> dict:
        if positions is None:
            positions = store.queue_positions()
        return phone_job(job, device_id, phone.artifacts, positions, lean=True)

    async def publish(scope: anyio.CancelScope) -> None:
        try:
            await websocket.send_json({"type": "hello", "server_id": phone.identity.server_id,
                                       "library_id": phone.library_id,
                                       "api_version": phone.api_version})
            jobs = sorted(store.list(), key=lambda j: j.id)
            positions = store.queue_positions()          # once for the whole snapshot
            await websocket.send_json({"type": "snapshot",
                                       "jobs": [lean(j, positions) for j in jobs]})
            last_sent: dict[str, float] = {}
            due: dict[str, float] = {}
            checked = time.monotonic()
            while True:
                now = time.monotonic()
                wait = min([max(0.0, t - now) for t in due.values()] + [RECHECK_S])
                event = None
                with anyio.move_on_after(wait):
                    event = await pending.get()
                now = time.monotonic()
                if event is not None:
                    kind, job_id = event
                    if kind == "deleted":
                        due.pop(job_id, None)
                        last_sent.pop(job_id, None)
                        await websocket.send_json({"type": "deleted", "id": job_id})
                    elif job_id not in due:
                        due[job_id] = max(now, last_sent.get(job_id, float("-inf")) + COALESCE_S)
                for job_id in [j for j, t in due.items() if t <= now]:
                    del due[job_id]
                    try:
                        job = store.get(job_id)
                    except Exception:                 # noqa: BLE001 — deleted meanwhile
                        continue
                    last_sent[job_id] = now
                    await websocket.send_json({"type": "job", "job": lean(job)})
                if now - checked >= RECHECK_S:
                    checked = now
                    still = await anyio.to_thread.run_sync(phone.registry.active, device_id)
                    if still is None or phone.state == "off":
                        await websocket.close(code=1008)
                        break
        except (WebSocketDisconnect, RuntimeError):
            pass
        scope.cancel()

    async def until_disconnect(scope: anyio.CancelScope) -> None:
        try:
            while (await websocket.receive())["type"] != "websocket.disconnect":
                pass
        except (WebSocketDisconnect, RuntimeError):
            pass
        scope.cancel()

    try:
        async with anyio.create_task_group() as tg:
            tg.start_soon(publish, tg.cancel_scope)
            tg.start_soon(until_disconnect, tg.cancel_scope)
    finally:
        unsubscribe()
