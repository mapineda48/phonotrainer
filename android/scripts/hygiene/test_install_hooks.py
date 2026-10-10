"""Tests of install-hooks.sh: the hooks installed into a temporary repository block a planted violation."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from testsupport import HYGIENE_DIR, TempRepo, planted  # noqa: E402

INSTALL = HYGIENE_DIR / "install-hooks.sh"


@unittest.skipUnless(shutil.which("sh") and shutil.which("python3"), "needs sh and python3")
class Hooks(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = TempRepo()
        self.addCleanup(self.repo.cleanup)
        self.repo.copy_scripts()
        self.repo.env["GITHUB_ACTIONS"] = "true"  # a CI-like run: no local denylist, so the test is deterministic

    def install(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(["sh", str(self.repo.path / "android/scripts/hygiene/install-hooks.sh"), *args],
                              cwd=self.repo.path / "android", env=self.repo.env, capture_output=True, text=True)

    def test_pre_commit_blocks_a_planted_violation_and_lets_a_clean_commit_through(self):
        self.assertEqual(0, self.install().returncode)
        self.repo.git("add", "-A")
        clean = self.repo.git("commit", "-q", "-m", "Clean", check=False)
        self.assertEqual(0, clean.returncode, clean.stdout + clean.stderr)
        self.repo.write("android/docs/notes.md", f"gateway {planted('mac')}\n")
        self.repo.git("add", "-A")
        blocked = self.repo.git("commit", "-q", "-m", "Leak", check=False)
        self.assertNotEqual(0, blocked.returncode)
        self.assertIn("mac", blocked.stdout + blocked.stderr)
        self.assertEqual("Clean", self.repo.git("log", "-1", "--format=%s").stdout.strip())

    def test_pre_push_blocks_a_violation_that_skipped_the_commit_hook(self):
        self.assertEqual(0, self.install().returncode)
        remote = tempfile.mkdtemp(prefix="hygiene-remote-")
        self.addCleanup(shutil.rmtree, remote, True)
        subprocess.run(["git", "init", "-q", "--bare", remote], check=True, capture_output=True)
        self.repo.git("remote", "add", "origin", remote)
        self.repo.commit("Clean")
        ok = self.repo.git("push", "-q", "origin", "main", check=False)
        self.assertEqual(0, ok.returncode, ok.stderr)
        self.repo.write("android/docs/notes.md", f"token {planted('token')}\n")
        self.repo.git("add", "-A")
        self.repo.git("commit", "-q", "--no-verify", "-m", "Leak")
        blocked = self.repo.git("push", "-q", "origin", "main", check=False)
        self.assertNotEqual(0, blocked.returncode)
        remote_head = subprocess.run(["git", "--git-dir", remote, "log", "-1", "--format=%s", "main"],
                                     capture_output=True, text=True).stdout.strip()
        self.assertEqual("Clean", remote_head)

    def test_a_foreign_hook_is_kept_unless_forced(self):
        hooks = self.repo.path / ".git" / "hooks"
        hooks.mkdir(exist_ok=True)
        (hooks / "pre-commit").write_text("#!/bin/sh\nexit 0\n")
        refused = self.install()
        self.assertEqual(1, refused.returncode)
        self.assertEqual(0, self.install("--force").returncode)
        self.assertTrue((hooks / "pre-commit.pre-hygiene").exists())
        self.assertIn("phonotrainer-hygiene-hook", (hooks / "pre-commit").read_text())
        self.assertEqual(0, self.install().returncode)  # reinstalling over its own hooks is fine

    def test_a_checkout_without_the_script_is_not_checked(self):
        self.assertEqual(0, self.install().returncode)
        shutil.rmtree(self.repo.path / "android" / "scripts")
        self.repo.write("notes.md", f"gateway {planted('mac')}\n")
        self.repo.git("add", "-A")
        result = self.repo.git("commit", "-q", "-m", "No script here", check=False)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_the_installer_is_executable_and_posix_sh(self):
        self.assertTrue(os.access(INSTALL, os.X_OK))
        self.assertTrue(INSTALL.read_text().startswith("#!/bin/sh"))


if __name__ == "__main__":
    unittest.main()
