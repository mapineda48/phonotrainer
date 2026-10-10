"""Tests of torture.py: the emulator guard, every change has its restore, and the stop paths of the architecture."""

from __future__ import annotations

import datetime as dt
import io
import os
import sqlite3
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _device  # noqa: E402
import qa_log  # noqa: E402
import torture  # noqa: E402
from fakedevice import FakeDevice  # noqa: E402

NOW = dt.datetime(2026, 10, 10, 15, 44, 16, tzinfo=dt.timezone.utc)


class Plans(unittest.TestCase):
    def test_every_state_change_is_restored(self):
        for name, step in torture.STEPS.items():
            with self.subTest(step=name):
                commands = torture.plan(step, ["1"] * len(step.record), NOW)
                actions = [c for p, c in commands if p == "action"]
                restores = [c for p, c in commands if p == "restore"]
                self.assertTrue(actions)
                if not step.reboot:
                    self.assertTrue(restores, name)

    def test_the_platform_stop_paths_of_the_architecture(self):
        fgs = [c for _p, c in torture.plan(torture.STEPS["fgs-timeout"], ["null"], NOW)]
        self.assertIn(f"am compat enable FGS_INTRODUCE_TIME_LIMITS {_device.PACKAGE}", fgs)
        self.assertIn("device_config put activity_manager data_sync_fgs_timeout_duration 60000", fgs)
        self.assertIn("device_config delete activity_manager data_sync_fgs_timeout_duration", fgs)
        self.assertIn(f"am compat reset FGS_INTRODUCE_TIME_LIMITS {_device.PACKAGE}", fgs)
        kept = [c for _p, c in torture.plan(torture.STEPS["fgs-timeout"], ["21600000"], NOW)]
        self.assertIn("device_config put activity_manager data_sync_fgs_timeout_duration 21600000", kept)
        for bucket in ("rare", "restricted"):
            commands = [c for _p, c in torture.plan(torture.STEPS[f"standby-{bucket}"], ["20"], NOW)]
            self.assertIn(f"am set-standby-bucket {_device.PACKAGE} {bucket}", commands)
            self.assertIn(f"am set-standby-bucket {_device.PACKAGE} working_set", commands)

    def test_the_clock_is_restored_to_the_time_of_the_restore(self):
        restores = [c for p, c in torture.plan(torture.STEPS["midnight"], ["1"], NOW) if p == "restore"]
        self.assertEqual(["su 0 toybox date -u 101015442026.16", "settings put global auto_time 1"], restores)

    def test_the_steps_cover_the_emulator_acceptance_criteria(self):
        covers = {s.covers for s in torture.STEPS.values()}
        self.assertTrue({"QT-1", "QT-4", "QT-13", "F-06-AC3", "F-08-AC4"} <= covers)

    def test_snapshot_comparison(self):
        before = {"request a": {"seq": "1", "state": "WAITING"}, "request b": {"seq": "2", "state": "WAITING"}}
        self.assertEqual([], torture.compare(before, dict(before)))
        after = {"request a": {"seq": "1", "state": "FAILED"}}
        problems = torture.compare(before, after)
        self.assertIn("request b disappeared", problems)
        self.assertIn("request a entered FAILED", problems)


def request(state: str, reason: str = "", persisted: str = "0", seq: str = "1") -> dict[str, str]:
    return {"seq": seq, "state": state, "reason": reason, "persisted": persisted}


class ExpectedTransitions(unittest.TestCase):
    """Spec QT-13, architecture 8.3/8.4: a shared copy cut short by process death is COPY_FAILED copy_interrupted."""

    KILL = torture.STEPS["process-kill"].expects

    def test_process_kill_passes_on_the_interrupted_copy_it_causes(self):
        before = {"request r1": request("PREPARING")}
        after = {"request r1": request("COPY_FAILED", "copy_interrupted")}
        self.assertEqual([], torture.compare(before, after, self.KILL))
        # without the step's expectations the same change is a failure, as it is for every other step
        self.assertEqual(["request r1 entered COPY_FAILED (copy_interrupted)"], torture.compare(before, after))
        for name in set(torture.STEPS) - {"process-kill"}:
            with self.subTest(step=name):
                self.assertTrue(torture.compare(before, after, torture.STEPS[name].expects))

    def test_process_kill_still_fails_on_any_other_error(self):
        before = {"request r1": request("PREPARING"), "request r2": request("PREPARING", seq="2")}
        cases = {
            "reason not stored": {"seq": "1", "state": "COPY_FAILED"},
            "no space": request("COPY_FAILED", "copy_no_space"),
            "unreadable": request("COPY_FAILED", "copy_unreadable"),
            "missing": request("COPY_MISSING", "copy_missing"),
            "attention": request("NEEDS_ATTENTION"),
        }
        for label, row in cases.items():
            with self.subTest(case=label):
                after = {"request r1": row, "request r2": request("COPY_FAILED", "copy_interrupted", seq="2")}
                problems = torture.compare(before, after, self.KILL)
                self.assertEqual(1, len(problems), problems)
                self.assertTrue(problems[0].startswith(f"request r1 entered {row['state']}"), problems)
        failed_clip = torture.compare(
            {**before, "artifact j1/pack.zip": {"state": "DOWNLOADING", "part": "10"}},
            {"request r1": request("COPY_FAILED", "copy_interrupted"),
             "request r2": request("COPY_FAILED", "copy_interrupted", seq="2"),
             "artifact j1/pack.zip": {"state": "FAILED", "part": "0"}},
            self.KILL,
        )
        self.assertEqual(["artifact j1/pack.zip entered FAILED"], failed_clip)

    def test_process_kill_requires_the_interrupted_copy(self):
        before = {"request r1": request("PREPARING")}
        for row in (request("PREPARING"), request("WAITING")):
            with self.subTest(after=row["state"]):
                self.assertEqual(
                    [f"request r1 was PREPARING and is {row['state']}, expected COPY_FAILED (copy_interrupted)"],
                    torture.compare(before, {"request r1": row}, self.KILL),
                )
        self.assertEqual(["request r1 disappeared"], torture.compare(before, {}, self.KILL))

    def test_a_picked_copy_restarts_instead(self):
        # architecture 8.3: FileIntakeWorker restarts a picked item's copy after process death
        before = {"request r1": request("PREPARING"), "request r2": request("PREPARING", persisted="1", seq="2")}
        shared = {"request r1": request("COPY_FAILED", "copy_interrupted")}
        for row in (request("PREPARING", persisted="1", seq="2"), request("WAITING", persisted="1", seq="2")):
            with self.subTest(picked=row["state"]):
                self.assertEqual([], torture.compare(before, {**shared, "request r2": row}, self.KILL))
        interrupted = request("COPY_FAILED", "copy_interrupted", persisted="1", seq="2")
        self.assertEqual(
            ["request r2 entered COPY_FAILED (copy_interrupted)"],
            torture.compare(before, {**shared, "request r2": interrupted}, self.KILL),
        )

    def test_a_step_with_nothing_to_interrupt_is_not_a_pass(self):
        for before in ({}, {"request r1": request("WAITING")}, {"request r1": request("PREPARING", persisted="1")}):
            with self.subTest(before=before):
                problems = torture.compare(before, dict(before), self.KILL)
                self.assertEqual(1, len(problems), problems)
                self.assertTrue(problems[0].startswith("nothing exercised: no request was PREPARING"), problems)


def make_library(path: Path, with_reason: bool = True, rows=()) -> Path:
    extra = ", state_reason TEXT, source_persisted INTEGER" if with_reason else ""
    with sqlite3.connect(path) as conn:
        conn.execute(f"CREATE TABLE request (id TEXT PRIMARY KEY, seq INTEGER NOT NULL UNIQUE, state TEXT{extra})")
        conn.execute("CREATE TABLE artifact (job_id TEXT, name TEXT, local_state TEXT, part_bytes INTEGER)")
        for row in rows:
            conn.execute(f"INSERT INTO request VALUES ({', '.join('?' * len(row))})", row)
        conn.execute("INSERT INTO artifact VALUES ('j1', 'pack.zip', 'READY', NULL)")
    conn.close()
    return path


class Snapshots(unittest.TestCase):
    def test_the_queue_is_read_with_state_reason_and_source_persisted(self):
        with tempfile.TemporaryDirectory() as tmp:
            database = make_library(Path(tmp) / "library.db", rows=[
                ("r2", 2, "COPY_FAILED", "copy_interrupted", 0), ("r1", 1, "WAITING", None, 1)])
            self.assertEqual(
                {"request r1": {"seq": "1", "state": "WAITING", "reason": "", "persisted": "1"},
                 "request r2": {"seq": "2", "state": "COPY_FAILED", "reason": "copy_interrupted", "persisted": "0"},
                 "artifact j1/pack.zip": {"state": "READY", "part": ""}},
                torture.read_queue(database),
            )
            self.assertEqual(["request r1", "request r2"], list(torture.read_queue(database))[:2])

    def test_an_older_schema_reads_empty_reasons_and_a_missing_table_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            old = make_library(Path(tmp) / "old.db", with_reason=False, rows=[("r1", 1, "PREPARING")])
            self.assertEqual(request("PREPARING", persisted=""), torture.read_queue(old)["request r1"])
            empty = Path(tmp) / "empty.db"
            sqlite3.connect(empty).close()
            with self.assertRaises(sqlite3.Error):
                torture.read_queue(empty)

    def test_snapshot_pulls_the_database_through_run_as(self):
        device = FakeDevice(emulator=True, packages=[_device.PACKAGE]).install(self)
        with tempfile.TemporaryDirectory() as tmp:
            database = make_library(Path(tmp) / "library.db", rows=[("r1", 1, "COPY_FAILED", "copy_interrupted", 0)])
            device.exec_out[f"run-as {_device.PACKAGE} cat databases/{_device.LIBRARY_DB}"] = database.read_bytes()
        self.assertEqual(request("COPY_FAILED", "copy_interrupted"), torture.snapshot()["request r1"])
        device.exec_out.clear()
        self.assertIsNone(torture.snapshot())


class Guard(unittest.TestCase):
    def test_refuses_anything_but_an_emulator(self):
        device = FakeDevice(emulator=False, packages=[_device.PACKAGE]).install(self)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"ANDROID_SERIAL": ""}):
            log = qa_log.QaLog(Path(tmp) / "log.md", denylist=[], serials=[])
            with self.assertRaises(SystemExit):
                torture.run_step(torture.STEPS["airplane"], 0, 0, log)
        self.assertFalse([c for c in device.commands if "airplane" in c or "reboot" in c])

    def test_a_step_on_the_emulator_changes_logs_and_restores(self):
        device = FakeDevice(emulator=True, packages=[_device.PACKAGE]).install(self)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"ANDROID_SERIAL": "emulator-5554"}), \
                mock.patch.object(torture, "snapshot", return_value={}), mock.patch.object(torture.time, "sleep"):
            log = qa_log.QaLog(Path(tmp) / "log.md", denylist=[], serials=[])
            with redirect_stdout(io.StringIO()):
                code = torture.run_step(torture.STEPS["wifi-drop"], 1, 0, log)
            text = log.path.read_text("utf-8")
        self.assertEqual(0, code)
        self.assertLess(device.commands.index("svc wifi disable"), device.commands.index("svc wifi enable"))
        self.assertIn("restore: svc wifi enable", text)

    def run_kill(self, before, after):
        device = FakeDevice(emulator=True, packages=[_device.PACKAGE]).install(self)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"ANDROID_SERIAL": "emulator-5554"}), \
                mock.patch.object(torture, "snapshot", side_effect=[before, after]), \
                mock.patch.object(torture.time, "sleep"):
            log = qa_log.QaLog(Path(tmp) / "log.md", denylist=[], serials=[])
            with redirect_stdout(io.StringIO()) as out:
                code = torture.run_step(torture.STEPS["process-kill"], 0, 0, log)
            text = log.path.read_text("utf-8")
        return device, code, out.getvalue(), text

    def test_process_kill_passes_when_the_app_behaves_as_specified(self):
        device, code, out, text = self.run_kill(
            {"request r1": request("PREPARING")}, {"request r1": request("COPY_FAILED", "copy_interrupted")})
        self.assertEqual((0, "PASS (automated checks)\n"), (code, out))
        self.assertIn("process-kill: restored=True; PASS", text)
        kill = next(i for i, c in enumerate(device.commands) if "kill -9" in c)
        launch = next(i for i, c in enumerate(device.commands) if c.startswith("monkey -p"))
        self.assertLess(kill, launch)

    def test_process_kill_fails_on_another_error_and_is_incomplete_without_a_database(self):
        _, code, out, _ = self.run_kill(
            {"request r1": request("PREPARING")}, {"request r1": request("COPY_FAILED", "copy_no_space")})
        self.assertEqual((1, "FAIL: request r1 entered COPY_FAILED (copy_no_space)\n"), (code, out))
        _, code, out, _ = self.run_kill(None, None)
        self.assertEqual(2, code)
        self.assertTrue(out.startswith("INCOMPLETE"), out)

    def test_the_plan_command_touches_no_device(self):
        with mock.patch.object(_device, "run", side_effect=AssertionError("no device access")):
            with redirect_stdout(io.StringIO()) as out:
                self.assertEqual(0, torture.main(["plan", "standby-rare"]))
        self.assertIn("am set-standby-bucket", out.getvalue())
        with mock.patch.object(_device, "run", side_effect=AssertionError("no device access")):
            with redirect_stdout(io.StringIO()) as out:
                self.assertEqual(0, torture.main(["plan", "process-kill"]))
        self.assertIn("expect   PREPARING -> COPY_FAILED (copy_interrupted), shared copies only, required",
                      out.getvalue())


if __name__ == "__main__":
    unittest.main()
