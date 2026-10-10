"""Helpers for the unittest suites of the scripts: temporary git repositories and planted values.

Planted sensitive values are assembled at run time from fragments, so no committed test file holds one.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

HYGIENE_DIR = Path(__file__).resolve().parent
SCRIPTS_DIR = HYGIENE_DIR.parent
REPO_ROOT = SCRIPTS_DIR.parent.parent

# android/.gitignore as architecture 2.6 gives it.
ANDROID_GITIGNORE = """\
.gradle/
build/
.kotlin/
.cxx/
.externalNativeBuild/
captures/
*.apk
*.aab
*.iml
.idea/
local.properties
__pycache__/
*.pyc
*.jks
*.keystore
keystore.properties
*.p12
*.pfx
*.pem
*.pepk
*.preferences_pb
*.db-wal
*.db-shm
*.db-journal
*.hprof
bugreport*.zip
logcat*.txt
/artifacts/
"""

GIT_ENV = {
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "QA",
    "GIT_AUTHOR_EMAIL": "qa@example.invalid",
    "GIT_COMMITTER_NAME": "QA",
    "GIT_COMMITTER_EMAIL": "qa@example.invalid",
}


def planted(kind: str) -> str:
    """A sensitive-looking value assembled from fragments."""
    values = {
        "private-ipv4": ".".join(["192", "168", "7", "23"]),
        "public-ipv4": ".".join(["8", "8", "4", "4"]),
        "ipv6": ":".join(["fe80", "", "1c2b", "3d4e"]),
        "mac": ":".join(["3c", "22", "fb", "12", "34", "56"]),
        "home": "/" + "home" + "/" + "alice" + "/projects",
        "users": "/" + "Users" + "/" + "bob" + "/code",
        "token": "pt1" + "_" + ("Ab1-" * 11)[:43],
        "bearer": "Bear" + "er " + "eyJ0" + "eXAiOi" + "9876543210abcdef",
        "header": "PT1 " + "device=dev_x, ts=1, nonce=" + "a" * 32 + ", mac=" + "b" * 64,
        "code-json": '{"co' + 'de": "' + "65" + "4321" + '"}',
        "code-text": "Pairing" + " code: " + "654" + " 321",
        "server-id": "srv" + "_" + "abcdefghijklmnopqrstuvwxyz"[:26],
        "private-key": "-----BEGIN " + "RSA " + "PRIVATE KEY-----",
        "random-token": "Q3m" + "Zx9" + "L1pV" + "t7Rk" + "W2sN" + "y8Ha" + "C4dJ" + "u6Fe" + "B0gT" + "o5Mi",
        "spanish": "".join(chr(c) for c in [69, 115, 116, 97, 32, 102, 117, 110, 99, 105, 243, 110, 32, 100, 101, 115,
                                             99, 97, 114, 103, 97, 32, 101, 108, 32, 97, 114, 99, 104, 105, 118, 111]),
        "video-url": "https://" + "www.you" + "tube.com/watch?v=" + "AbCdEfGhIjK",
    }
    return values[kind]


class TempRepo:
    """A throwaway git repository with the real root .gitignore (so the ignore-rule trap behaves as in the repo)."""

    def __init__(self, android_gitignore: bool = True):
        self._dir = tempfile.mkdtemp(prefix="hygiene-test-")
        self.path = Path(self._dir)
        self.env = {**os.environ, **GIT_ENV}
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "qa@example.invalid")
        self.git("config", "user.name", "QA")
        self.git("config", "commit.gpgsign", "false")
        self.write(".gitignore", (REPO_ROOT / ".gitignore").read_text("utf-8"))
        if android_gitignore:
            self.write("android/.gitignore", ANDROID_GITIGNORE)
        self.write("android/README.md", "# Android\n\nThe Android client.\n")

    def write(self, rel: str, content: str | bytes) -> Path:
        target = self.path / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            target.write_bytes(content)
        else:
            target.write_text(content, encoding="utf-8")
        return target

    def git(self, *args: str, check: bool = True, input: str | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(["git", *args], cwd=self.path, env=self.env, capture_output=True, text=True,
                              check=check, input=input)

    def commit(self, message: str = "Change") -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD").stdout.strip()

    def copy_scripts(self) -> None:
        """Copies android/scripts/hygiene into the repository (the hooks run the copy)."""
        shutil.copytree(HYGIENE_DIR, self.path / "android" / "scripts" / "hygiene",
                        ignore=shutil.ignore_patterns("__pycache__", "test_*.py"))

    def cleanup(self) -> None:
        shutil.rmtree(self._dir, ignore_errors=True)
