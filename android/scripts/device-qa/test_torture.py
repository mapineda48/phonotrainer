"""Tests of torture.py: the emulator guard, every change has its restore, and the stop paths of the architecture."""

from __future__ import annotations

import datetime as dt
import io
import os
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

    def test_the_plan_command_touches_no_device(self):
        with mock.patch.object(_device, "run", side_effect=AssertionError("no device access")):
            with redirect_stdout(io.StringIO()) as out:
                self.assertEqual(0, torture.main(["plan", "standby-rare"]))
        self.assertIn("am set-standby-bucket", out.getvalue())


if __name__ == "__main__":
    unittest.main()
