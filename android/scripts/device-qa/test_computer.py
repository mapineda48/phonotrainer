"""Tests of computer.py: throwaway directories outside the repository, the command lines, cleanup."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import computer  # noqa: E402


class FakeServer:
    def __init__(self):
        self.signals = []
        self.running = True

    def poll(self):
        return None if self.running else 0

    def send_signal(self, signum):
        self.signals.append(signum)
        self.running = False

    def wait(self, timeout=None):
        return 0

    def kill(self):
        self.running = False


class Session(unittest.TestCase):
    def setUp(self) -> None:
        self.popen_calls, self.call_calls = [], []
        self.server = FakeServer()

    def popen(self, argv, **kwargs):
        self.popen_calls.append((argv, kwargs))
        return self.server

    def call(self, argv, **kwargs):
        self.call_calls.append((argv, kwargs))
        return 0

    def test_the_server_runs_on_throwaway_directories_outside_the_repository(self):
        session = computer.Session("/venv/bin/python", 8123, popen=self.popen, call=self.call)
        base = session.dirs.base
        self.assertFalse(computer.inside(base, computer.REPO_ROOT))
        session.start()
        argv, kwargs = self.popen_calls[0]
        self.assertEqual(["/venv/bin/python", "-m", "phonotrainer.cli", "ui", "--phone-access", "--no-open",
                          "--workspace", str(session.dirs.workspace), "--port", "8123"], argv)
        self.assertEqual(session.dirs.cwd, kwargs["cwd"])
        self.assertEqual(str(session.dirs.config), kwargs["env"]["PHONOTRAINER_CONFIG_DIR"])
        self.assertTrue(kwargs["env"]["PYTHONPATH"].startswith(str(computer.REPO_ROOT)))
        session.phone("pair")
        argv, kwargs = self.call_calls[0]
        self.assertEqual(["/venv/bin/python", "-m", "phonotrainer.cli", "phone", "pair"], argv)
        self.assertEqual(session.dirs.cwd, kwargs["cwd"])
        session.close()
        self.assertTrue(self.server.signals)
        self.assertFalse(base.exists())

    def test_keep_leaves_the_directories(self):
        session = computer.Session("python3", 8000, keep=True, popen=self.popen, call=self.call)
        self.addCleanup(lambda: __import__("shutil").rmtree(session.dirs.base, True))
        session.close()
        self.assertTrue(session.dirs.workspace.is_dir())

    def test_refuses_directories_inside_the_repository(self):
        with tempfile.TemporaryDirectory() as repo:
            with self.assertRaises(RuntimeError):
                computer.Session("python3", 8000, popen=self.popen, call=self.call, parent=repo, repo=Path(repo))
            self.assertEqual([], list(Path(repo).iterdir()))

    def test_only_known_operator_commands(self):
        self.assertEqual({"pair", "on", "off", "status", "list"}, set(computer.COMMANDS))
        self.assertIn("never", computer.BANNER)


if __name__ == "__main__":
    unittest.main()
