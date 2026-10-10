"""Tests of _device.py, ui.py, a11y.py, shot.py and db.py against a scripted device (no adb, no device)."""

from __future__ import annotations

import io
import json
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
import a11y  # noqa: E402
import db  # noqa: E402
import shot  # noqa: E402
import ui  # noqa: E402
from fakedevice import FakeDevice  # noqa: E402

LAYOUT = [
    {"window-title": "StatusBar", "content": []},
    {
        "window-title": "PhonoTrainer",
        "content": [
            {"resource-id": "home.feed.play", "content-desc": "Play", "interactions": ["CLICKABLE"],
             "bounds": "[0,0][144,144]", "center": "[72,72]", "class": "android.view.View"},
            {"resource-id": "home.feed.more", "interactions": ["CLICKABLE"], "bounds": "[200,0][240,40]",
             "center": "[220,20]", "class": "android.view.View"},
            {"text": "Watch", "bounds": "[0,200][300,300]", "center": "[150,250]", "class": "android.widget.TextView"},
            {"resource-id": "home.tab.watch", "interactions": ["CLICKABLE", "FOCUSABLE"], "bounds": "[0,200][300,300]",
             "center": "[150,250]", "class": "android.view.View", "state": ["SELECTED"]},
        ],
    },
]


class Device(unittest.TestCase):
    def test_constants_come_from_one_place(self):
        self.assertEqual("com.mapineda48.phonotrainer", _device.PACKAGE)
        self.assertEqual("PhonoTrainer", _device.APP_LABEL)
        self.assertEqual("library.db", _device.LIBRARY_DB)
        self.assertEqual(("artifacts", "qa-evidence"), _device.EVIDENCE_DIR.parts[-2:])

    def test_serial_comes_from_the_environment(self):
        calls = []
        with mock.patch.object(_device, "run", lambda argv, **kw: calls.append(argv)):
            with mock.patch.dict(os.environ, {"ANDROID_SERIAL": "emulator-5554"}):
                _device.adb("shell", "true")
            with mock.patch.dict(os.environ, {"ANDROID_SERIAL": ""}):
                _device.adb("shell", "true")
        self.assertEqual(["adb", "-s", "emulator-5554", "shell", "true"], calls[0])
        self.assertEqual(["adb", "shell", "true"], calls[1])

    def test_installed_smoke_check(self):
        FakeDevice(packages=[_device.PACKAGE]).install(self)
        self.assertTrue(_device.installed())
        FakeDevice(packages=["com.other"]).install(self)
        self.assertFalse(_device.installed())

    def test_emulator_guard(self):
        FakeDevice(emulator=False).install(self)
        with mock.patch.dict(os.environ, {"ANDROID_SERIAL": ""}):
            with self.assertRaises(SystemExit):
                _device.require_emulator("db.py")
        FakeDevice(emulator=True).install(self)
        with mock.patch.dict(os.environ, {"ANDROID_SERIAL": "R5" + "CX0000000"}):
            self.assertFalse(_device.is_emulator())  # a phone serial is never trusted, whatever it reports
        with mock.patch.dict(os.environ, {"ANDROID_SERIAL": "emulator-5554"}):
            self.assertTrue(_device.is_emulator())


class Ui(unittest.TestCase):
    def test_parse_find_and_format(self):
        windows = ui.parse_layout("Connecting...\n" + json.dumps(LAYOUT))
        elements = ui.app_elements(windows)
        self.assertEqual(4, len(elements))
        self.assertEqual("home.tab.watch", ui.find(elements, "home.tab.watch")["resource-id"])
        self.assertEqual("Play", ui.find(elements, "Pla")["content-desc"])
        self.assertIsNone(ui.find(elements, "nothing"))
        self.assertEqual((150, 250), ui.center(ui.find(elements, "home.tab.watch")))
        line = ui.fmt(3, elements[3])
        self.assertIn("#home.tab.watch", line)
        self.assertIn("st=SELECTED", line)
        self.assertIn("i=CF", line)

    def test_missing_window(self):
        self.assertIsNone(ui.app_elements([{"window-title": "Launcher", "content": []}]))


class A11y(unittest.TestCase):
    def test_unlabelled_and_small_targets_are_flagged(self):
        elements = ui.app_elements(LAYOUT)
        results = {name: (label, problems) for name, label, _w, _h, problems in a11y.audit(elements, 1.0)}
        self.assertEqual(("Play", []), results["home.feed.play"])
        self.assertIn("NO LABEL", results["home.feed.more"][1])
        self.assertTrue(any("small target" in p for p in results["home.feed.more"][1]))
        self.assertEqual(("Watch", []), results["home.tab.watch"])  # labelled by the text inside it

    def test_density_scales_sizes(self):
        elements = ui.app_elements(LAYOUT)
        sizes = {n: (w, h) for n, _l, w, h, _p in a11y.audit(elements, 3.0)}
        self.assertEqual((48.0, 48.0), sizes["home.feed.play"])


class Shot(unittest.TestCase):
    def test_evidence_names_are_plain(self):
        full, small = shot.evidence_paths("home-01", Path("/tmp/e"))
        self.assertEqual(Path("/tmp/e/home-01.png"), full)
        self.assertEqual(Path("/tmp/e/small/home-01.png"), small)
        for bad in ("../x", "a/b", "", "-x"):
            with self.assertRaises(ValueError):
                shot.evidence_paths(bad, Path("/tmp/e"))

    def test_a_capture_without_a_png_is_refused(self):
        device = FakeDevice().install(self)
        device.exec_out["screencap -p"] = b"error"
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(shot, "EVIDENCE_DIR", Path(tmp)), \
                mock.patch.object(shot, "evidence_paths", lambda n: (Path(tmp) / "a.png", Path(tmp) / "s" / "a.png")):
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, shot.main(["a"]))


class Db(unittest.TestCase):
    def make_db(self, path: Path) -> None:
        with sqlite3.connect(path) as conn:
            conn.execute("CREATE TABLE request (id TEXT, seq INTEGER, state TEXT, link_shared TEXT)")
            conn.execute("INSERT INTO request VALUES ('r1', 1, 'WAITING', NULL)")
            conn.execute("CREATE TABLE clip (job_id TEXT, title TEXT)")
            conn.execute("INSERT INTO clip VALUES ('20261010-091503-4be1', ?)", ("x" * 100,))

    def test_dump_prints_the_tables(self):
        with tempfile.TemporaryDirectory() as tmp:
            database = Path(tmp) / "library.db"
            self.make_db(database)
            text = db.dump(database)
        self.assertIn("request: 1 row(s)", text)
        self.assertIn("id=r1 seq=1 state=WAITING", text)
        self.assertNotIn("link_shared", text)  # NULL cells are left out
        self.assertIn("(len=100)", text)
        self.assertIn("request_op: (no such table)", text)

    def test_only_the_queue_and_library_tables(self):
        with tempfile.TemporaryDirectory() as tmp:
            database = Path(tmp) / "library.db"
            self.make_db(database)
            with self.assertRaises(ValueError):
                db.dump(database, ["pairing"])

    def test_refuses_a_phone(self):
        FakeDevice(emulator=False).install(self)
        with mock.patch.dict(os.environ, {"ANDROID_SERIAL": ""}), self.assertRaises(SystemExit):
            db.main([])

    def test_pull_reads_the_database_through_run_as(self):
        device = FakeDevice().install(self)
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "src.db"
            self.make_db(source)
            device.exec_out[f"run-as {_device.PACKAGE} cat databases/library.db"] = source.read_bytes()
            out = Path(tmp) / "out"
            out.mkdir()
            pulled = db.pull(out)
            self.assertIn("request: 1 row(s)", db.dump(pulled, ["request"]))
        self.assertTrue(all("pairing" not in c for c in device.commands))


if __name__ == "__main__":
    unittest.main()
