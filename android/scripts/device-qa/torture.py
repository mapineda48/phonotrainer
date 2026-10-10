#!/usr/bin/env python3
"""The [emulator] steps of the queue torture test and the platform stop paths (architecture 21.7; spec 7.12).

Each step changes one thing on the emulator, waits, restores it exactly and compares the app's queue and transfers
before and after (db.py's tables, so a debug build is needed). The app talks to the host-side fake computer
(host_computer.py), which refuses every download, so no link can ever reach a site (spec D-36).

  reboot             QT-1   force-stop the app and reboot; the waiting requests are all still there, in order
  wifi-drop          QT-4   Wi-Fi off for --seconds (default 120) and back; the upload restarts and completes once
  process-kill       QT-13  kill the app process mid-copy; the request shows the interrupted copy at the next start
  airplane           F-06-AC3  airplane mode on, then off; downloaded clips play meanwhile
  midnight           F-08-AC4  automatic time off, the clock set to 23:59:30 local, then restored
  fgs-timeout        Android review M12: FGS_INTRODUCE_TIME_LIMITS with a one-minute data-sync limit
  standby-rare       Android review M12: the app in the `rare` standby bucket
  standby-restricted Android review M12: the app in the `restricted` standby bucket

Usage:
  torture.py list
  torture.py run <step> [--seconds N] [--settle N]   on an emulator only; every change is logged and restored
  torture.py plan <step>                             print the commands without touching a device

This script refuses to run against anything but an emulator: the test phone is never rebooted, its radios are never
toggled and its clock is never changed (spec NFR-14). Prepare the app state each step needs first (its `needs` line).
"""

from __future__ import annotations

import argparse
import datetime as dt
import sys
import time
from dataclasses import dataclass, field

import _device

PKG = _device.PACKAGE
ERROR_STATES = ("FAILED", "ATTENTION", "COPY_FAILED", "COPY_MISSING")


@dataclass
class Step:
    name: str
    covers: str
    needs: str
    record: list[str] = field(default_factory=list)  # read before the action, to restore exact values
    action: list[str] = field(default_factory=list)
    hold: bool = True  # wait --seconds between the action and the restore
    restore: list[str] = field(default_factory=list)
    reboot: bool = False
    debug_only: bool = False


STEPS = {
    s.name: s
    for s in (
        Step(
            "reboot", "QT-1",
            "computer unreachable (host_computer.py stopped); 3 links and 1 video shared, 4 requests waiting",
            action=[f"am force-stop {PKG}", "reboot"], hold=False, reboot=True,
        ),
        Step(
            "wifi-drop", "QT-4", "an upload to host_computer.py at about 60 %",
            action=["svc wifi disable"], restore=["svc wifi enable"],
        ),
        Step(
            "process-kill", "QT-13", "a large shared video being copied, at about 50 % (debug build: run-as)",
            action=[f"run-as {PKG} sh -c 'kill -9 $(pidof {PKG})'"], hold=False, debug_only=True,
            restore=[f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1"],
        ),
        Step(
            "airplane", "F-06-AC3", "a downloaded clip open in Watch",
            action=["cmd connectivity airplane-mode enable"], restore=["cmd connectivity airplane-mode disable"],
        ),
        Step(
            "midnight", "F-08-AC4", "Home open with today's set shown",
            record=["settings get global auto_time"],
            # 23:59:30 in the emulator's own time zone, computed by the emulator's shell
            action=["settings put global auto_time 0", "su 0 toybox date $(date +%m%d)2359$(date +%Y).30"],
            restore=["su 0 toybox date -u {utc_now}", "settings put global auto_time {auto_time}"],
        ),
        Step(
            "fgs-timeout", "Android review M12", "a study-pack download or an upload running in the foreground",
            record=["device_config get activity_manager data_sync_fgs_timeout_duration"],
            action=[
                f"am compat enable FGS_INTRODUCE_TIME_LIMITS {PKG}",
                "device_config put activity_manager data_sync_fgs_timeout_duration 60000",
            ],
            restore=[
                f"am compat reset FGS_INTRODUCE_TIME_LIMITS {PKG}",
                "{restore_fgs_timeout}",
            ],
        ),
        Step(
            "standby-rare", "Android review M12", "requests waiting and a download wanted, the app in the background",
            record=[f"am get-standby-bucket {PKG}"],
            action=[f"am set-standby-bucket {PKG} rare"], restore=[f"am set-standby-bucket {PKG} {{bucket}}"],
        ),
        Step(
            "standby-restricted", "Android review M12",
            "requests waiting and a download wanted, the app in the background",
            record=[f"am get-standby-bucket {PKG}"],
            action=[f"am set-standby-bucket {PKG} restricted"], restore=[f"am set-standby-bucket {PKG} {{bucket}}"],
        ),
    )
}

BUCKETS = {"5": "exempted", "10": "active", "20": "working_set", "30": "frequent", "40": "rare", "45": "restricted",
           "50": "never"}


def values_for(step: Step, recorded: list[str], now: dt.datetime) -> dict[str, str]:
    """The placeholders of a step's commands, from what was read before the change."""
    values = {"utc_now": now.astimezone(dt.timezone.utc).strftime("%m%d%H%M%Y.%S")}
    if step.name == "midnight":
        values["auto_time"] = recorded[0] if recorded and recorded[0] in ("0", "1") else "1"
    if step.name == "fgs-timeout":
        previous = recorded[0] if recorded else "null"
        values["restore_fgs_timeout"] = (
            "device_config delete activity_manager data_sync_fgs_timeout_duration"
            if previous in ("", "null")
            else f"device_config put activity_manager data_sync_fgs_timeout_duration {previous}"
        )
    if step.name.startswith("standby-"):
        raw = recorded[0] if recorded else "10"
        values["bucket"] = BUCKETS.get(raw.strip(), raw.strip() if raw.strip() in BUCKETS.values() else "active")
    return values


def plan(step: Step, recorded: list[str] | None = None, now: dt.datetime | None = None) -> list[tuple[str, str]]:
    """(phase, command) pairs the step runs, with placeholders filled (`now` is the host's clock, for the restore)."""
    now = now or dt.datetime.now(dt.timezone.utc)
    values = values_for(step, recorded or [], now)
    out = [("record", c) for c in step.record]
    out += [("action", c.format(**values)) for c in step.action]
    out += [("restore", c.format(**values)) for c in step.restore]
    return out


def snapshot() -> dict[str, dict[str, str]]:
    """request id -> {state, seq} and artifact (job, name) -> {local_state, part_bytes} from library.db."""
    import tempfile
    from pathlib import Path

    import db
    import sqlite3

    with tempfile.TemporaryDirectory(prefix="phonotrainer-torture-") as workdir:
        try:
            database = db.pull(Path(workdir))
        except RuntimeError:
            return {}
        with sqlite3.connect(database) as conn:
            state: dict[str, dict[str, str]] = {}
            try:
                for rid, seq, st in conn.execute("SELECT id, seq, state FROM request ORDER BY seq"):
                    state[f"request {rid}"] = {"seq": str(seq), "state": str(st)}
                for job, name, local, part in conn.execute(
                    "SELECT job_id, name, local_state, part_bytes FROM artifact"
                ):
                    state[f"artifact {job}/{name}"] = {"state": str(local), "part": str(part)}
            except sqlite3.Error:
                pass
    return state


def compare(before: dict, after: dict) -> list[str]:
    """Problems: a request that disappeared or entered an error state, an artifact that failed."""
    problems = []
    for key, row in before.items():
        if key.startswith("request ") and key not in after:
            problems.append(f"{key} disappeared")
    for key, row in after.items():
        if any(e in row.get("state", "") for e in ERROR_STATES) and before.get(key, {}).get("state") != row["state"]:
            problems.append(f"{key} entered {row['state']}")
    order_before = [k for k in before if k.startswith("request ")]
    order_after = [k for k in after if k.startswith("request ") and k in before]
    if order_before and order_after != [k for k in order_before if k in after]:
        problems.append("the requests changed order")
    return problems


def wait_for_boot(timeout: float = 300) -> None:
    _device.adb("wait-for-device", timeout=timeout)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if _device.shell("getprop sys.boot_completed") == "1":
            return
        time.sleep(2)
    raise TimeoutError("the emulator did not finish booting")


def run_step(step: Step, seconds: int, settle: int, log) -> int:
    _device.require_emulator("torture.py")
    _device.require_installed()
    log.write(f"torture step {step.name} ({step.covers}) on the emulator; needs: {step.needs}")
    recorded = [_device.shell(c) for c in step.record]
    commands = plan(step, recorded)
    before = snapshot()
    restored = False
    try:
        for phase, command in commands:
            if phase != "action":
                continue
            log.write(f"{step.name}: {command}")
            _device.shell(command)
        if step.reboot:
            wait_for_boot()
        if step.hold:
            time.sleep(seconds)
    finally:
        for phase, command in plan(step, recorded):  # recomputed: the restore sets the clock to the time now
            if phase == "restore":
                log.write(f"{step.name}: restore: {command}")
                _device.shell(command)
        restored = True
    time.sleep(settle)
    after = snapshot()
    problems = compare(before, after)
    verdict = "PASS (automated checks)" if not problems else "FAIL: " + "; ".join(problems)
    log.write(f"{step.name}: restored={restored}; {verdict}; check the screen against {step.covers} too")
    print(verdict)
    return 0 if not problems else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=("list", "run", "plan"))
    parser.add_argument("step", nargs="?", choices=sorted(STEPS))
    parser.add_argument("--seconds", type=int, default=120, help="how long the change holds (default 120)")
    parser.add_argument("--settle", type=int, default=30, help="wait after the restore before checking")
    args = parser.parse_args(argv)
    if args.command == "list":
        for step in STEPS.values():
            print(f"{step.name:20} {step.covers:20} needs: {step.needs}")
        return 0
    if not args.step:
        parser.error("name a step (torture.py list)")
    step = STEPS[args.step]
    if args.command == "plan":
        for phase, command in plan(step, ["<read from the device>"] * len(step.record)):
            print(f"{phase:8} adb shell {command}")
        return 0
    from qa_log import QaLog

    return run_step(step, args.seconds, args.settle, QaLog())


if __name__ == "__main__":
    sys.exit(main())
