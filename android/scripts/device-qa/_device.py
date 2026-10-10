"""Shared settings of the device QA scripts (architecture 21.7).

The target device comes from ANDROID_SERIAL (never a committed serial); without it, adb and the Android CLI use the
only connected device. `adb` and `android` (Android SDK platform-tools and cmdline-tools) must be on PATH.

The package and the launcher label are defined here once, so no script can drift from the app (Artemi's stale
constant). `python3 _device.py` checks that the app is installed on the target device.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.mapineda48.phonotrainer"
APP_LABEL = "PhonoTrainer"
LIBRARY_DB = "library.db"  # architecture 4.1; the learning database and the pairing store are never pulled

SCRIPTS_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPTS_DIR.parent.parent  # android/
EVIDENCE_DIR = Path(os.environ.get("QA_EVIDENCE_DIR", ANDROID_ROOT / "artifacts" / "qa-evidence"))


def serial() -> str | None:
    return os.environ.get("ANDROID_SERIAL") or None


def run(argv: list[str], **kwargs) -> subprocess.CompletedProcess:
    """The one place the scripts start a process (tests replace it)."""
    kwargs.setdefault("capture_output", True)
    return subprocess.run(argv, **kwargs)


def adb(*args: str, **kwargs) -> subprocess.CompletedProcess:
    """Runs adb against the target device."""
    target = ["-s", serial()] if serial() else []
    return run(["adb", *target, *args], **kwargs)


def android_cli(*args: str, **kwargs) -> subprocess.CompletedProcess:
    """Runs an Android CLI command (`layout`, `screen capture`) against the target device."""
    device = [f"--device={serial()}"] if serial() else []
    return run(["android", *args, *device], **kwargs)


def shell(command: str) -> str:
    """`adb shell <command>`, stdout as text without the trailing newline."""
    return adb("shell", command, text=True).stdout.strip()


def is_emulator() -> bool:
    """True only for an emulator: its serial and the kernel property must both say so."""
    target = serial() or ""
    if target and not target.startswith("emulator-"):
        return False
    return shell("getprop ro.kernel.qemu") == "1" or shell("getprop ro.boot.qemu") == "1"


def require_emulator(action: str) -> None:
    """Stops the script unless the target is an emulator (spec NFR-14: never on the test phone)."""
    if not is_emulator():
        sys.exit(f"{action} runs on an emulator only (spec NFR-14); the target device is not an emulator.")


def installed() -> bool:
    return "package:" in shell(f"pm path {PACKAGE}")


def require_installed() -> None:
    if not installed():
        sys.exit(f"{PACKAGE} is not installed on the target device.")


def main() -> int:
    if installed():
        print(f"{PACKAGE} ({APP_LABEL}) is installed.")
        return 0
    print(f"{PACKAGE} ({APP_LABEL}) is not installed on the target device.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
