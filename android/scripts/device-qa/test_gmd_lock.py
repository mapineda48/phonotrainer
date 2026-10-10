"""Tests of gmd_lock.py: mutual exclusion, timeout, exit status, the lock file and util-linux flock interop."""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gmd_lock  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "gmd_lock.py"

HOLDER = textwrap.dedent("""
    import fcntl, os, sys, time
    fd = os.open(sys.argv[1], os.O_RDONLY | os.O_CREAT, 0o666)
    fcntl.flock(fd, fcntl.LOCK_EX)
    print("held", flush=True)
    time.sleep(float(sys.argv[2]))
""")


class Lock(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.lock = Path(self.tmp.name) / "locks" / "gmd.lock"

    def hold(self, seconds: float) -> subprocess.Popen:
        self.lock.parent.mkdir(parents=True, exist_ok=True)
        holder = subprocess.Popen([sys.executable, "-c", HOLDER, str(self.lock), str(seconds)],
                                  stdout=subprocess.PIPE, text=True)
        self.addCleanup(holder.wait)
        self.addCleanup(holder.kill)
        self.assertEqual("held", holder.stdout.readline().strip())
        return holder

    def run_script(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(SCRIPT), "--lock-file", str(self.lock), *args],
                              capture_output=True, text=True, timeout=60)

    def test_runs_the_command_and_returns_its_status(self):
        self.assertEqual(0, self.run_script("--", sys.executable, "-c", "pass").returncode)
        self.assertEqual(3, self.run_script("--", sys.executable, "-c", "raise SystemExit(3)").returncode)
        self.assertTrue(self.lock.exists())

    def test_waits_for_the_holder_and_times_out(self):
        self.hold(30)
        result = self.run_script("--timeout", "1", "--", sys.executable, "-c", "pass")
        self.assertEqual(gmd_lock.EX_TEMPFAIL, result.returncode)
        self.assertIn("still held", result.stderr)
        self.assertEqual(1, self.run_script("--status").returncode)

    def test_acquires_once_the_holder_ends(self):
        self.hold(1.5)
        start = time.monotonic()
        result = self.run_script("--timeout", "20", "--", sys.executable, "-c", "pass")
        self.assertEqual(0, result.returncode)
        self.assertGreater(time.monotonic() - start, 0.5)
        self.assertEqual(0, self.run_script("--status").returncode)

    def test_the_context_manager_excludes_another_holder(self):
        with gmd_lock.gmd_lock(self.lock, timeout=1, announce=None):
            self.assertFalse(gmd_lock.is_free(self.lock))
            with self.assertRaises(TimeoutError):
                with gmd_lock.gmd_lock(self.lock, timeout=0.3, poll=0.1, announce=None):
                    pass
        self.assertTrue(gmd_lock.is_free(self.lock))

    def test_the_child_does_not_inherit_the_lock(self):
        # the command starts a background process that outlives it; the lock must be free once the command ends
        code = "import subprocess, sys; subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(3)'])"
        self.assertEqual(0, self.run_script("--", sys.executable, "-c", code).returncode)
        self.assertTrue(gmd_lock.is_free(self.lock))

    @unittest.skipUnless(shutil.which("flock"), "util-linux flock is not installed")
    def test_util_linux_flock_and_this_script_exclude_each_other(self):
        self.lock.parent.mkdir(parents=True, exist_ok=True)
        # flock passes the lock to its child, so the whole process group is stopped to release it
        holder = subprocess.Popen(["flock", str(self.lock), sys.executable, "-c",
                                   "import time; print('in', flush=True); time.sleep(30)"],
                                  stdout=subprocess.PIPE, text=True, start_new_session=True)

        def stop() -> None:
            try:
                os.killpg(holder.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            holder.wait()

        self.addCleanup(stop)
        self.assertEqual("in", holder.stdout.readline().strip())
        self.assertEqual(gmd_lock.EX_TEMPFAIL,
                         self.run_script("--timeout", "1", "--", sys.executable, "-c", "pass").returncode)
        stop()
        with gmd_lock.gmd_lock(self.lock, timeout=5, announce=None):
            busy = subprocess.run(["flock", "-n", str(self.lock), "true"])
            self.assertNotEqual(0, busy.returncode)

    def test_default_lock_file(self):
        self.assertEqual(Path("/x/lock"), gmd_lock.default_lock_file({gmd_lock.ENV_VAR: "/x/lock", "HOME": "/h"}))
        expected = Path("/h/.cache/phonotrainer-orch/android/scratchpad/locks/gmd.lock")
        self.assertEqual(expected, gmd_lock.default_lock_file({"HOME": "/h"}))

    def test_usage(self):
        self.assertEqual(2, self.run_script().returncode)
        self.assertEqual(str(self.lock), self.run_script("--where").stdout.strip())


if __name__ == "__main__":
    unittest.main()
