#!/usr/bin/env python3
"""Records, changes and restores the device settings device QA may touch (architecture 21.7; spec NFR-14, X-AC10).

Only three kinds of setting can change: the font scale, the three animator scales and TalkBack. Each value is read
before its first change in a session and restored exactly afterwards ("null", an unset value, is restored by deleting
the setting). The TalkBack component is read from the device (`pm list packages`, the services that answer
android.accessibilityservice.AccessibilityService, and the current enabled_accessibility_services), never assumed,
because Samsung ships its own TalkBack build under another component name. Every change and the final restore table
go to the QA log (qa_log.py).

Usage:
  settings_guard.py status                 the current values
  settings_guard.py font-scale <scale>     for example 2.0
  settings_guard.py animations off|on|<scale>
  settings_guard.py talkback on|off
  settings_guard.py restore                restore everything this session changed, verify, log the table
The session's previous values live in android/artifacts/qa-evidence/settings-session.json until `restore`.
"""

from __future__ import annotations

import json
import shlex
import sys
from pathlib import Path

import _device
from qa_log import QaLog, restore_table

FONT_SCALE = ("system", "font_scale")
ANIMATION_SCALES = (
    ("global", "window_animation_scale"),
    ("global", "transition_animation_scale"),
    ("global", "animator_duration_scale"),
)
A11Y_SERVICES = ("secure", "enabled_accessibility_services")
A11Y_ENABLED = ("secure", "accessibility_enabled")
ALLOWED = {FONT_SCALE, *ANIMATION_SCALES, A11Y_SERVICES, A11Y_ENABLED}
SESSION_FILE = _device.EVIDENCE_DIR / "settings-session.json"
ACCESSIBILITY_ACTION = "android.accessibilityservice.AccessibilityService"


class SettingsGuard:
    def __init__(self, log: QaLog | None = None, session_file: Path = SESSION_FILE):
        self.log = log
        self.session_file = Path(session_file)
        self.rows: list[dict] = []
        if self.session_file.exists():
            self.rows = json.loads(self.session_file.read_text("utf-8"))["rows"]

    # -- device access ------------------------------------------------------------------------------------------------

    @staticmethod
    def get(namespace: str, key: str) -> str:
        return _device.shell(f"settings get {namespace} {key}") or "null"

    @staticmethod
    def _put(namespace: str, key: str, value: str) -> None:
        if value == "null":
            _device.shell(f"settings delete {namespace} {key}")
        else:
            _device.shell(f"settings put {namespace} {key} {shlex.quote(value)}")

    # -- the session --------------------------------------------------------------------------------------------------

    def _save(self) -> None:
        self.session_file.parent.mkdir(parents=True, exist_ok=True)
        self.session_file.write_text(json.dumps({"rows": self.rows}, indent=2) + "\n", encoding="utf-8")

    def _row(self, namespace: str, key: str) -> dict:
        name = f"{namespace}/{key}"
        for row in self.rows:
            if row["name"] == name:
                return row
        row = {"name": name, "before": self.get(namespace, key), "set": None, "restored": None}
        self.rows.append(row)
        return row

    def set(self, namespace: str, key: str, value: str) -> None:
        if (namespace, key) not in ALLOWED:
            raise PermissionError(f"{namespace}/{key} is not a setting device QA may change (NFR-14)")
        row = self._row(namespace, key)
        self._put(namespace, key, value)
        row["set"] = value
        row["restored"] = None
        self._save()
        if self.log:
            self.log.write(f"setting {row['name']}: {row['before']} -> {value} (through adb)")

    def restore(self) -> list[dict]:
        for row in self.rows:
            namespace, key = row["name"].split("/", 1)
            self._put(namespace, key, row["before"])
            row["restored"] = self.get(namespace, key)
        table = restore_table(self.rows)
        if self.log and self.rows:
            self.log.write_block("Device settings changed and restored", table)
        done = [dict(r) for r in self.rows]
        if all(r["restored"] == r["before"] for r in self.rows):
            self.rows = []
            self.session_file.unlink(missing_ok=True)
        else:
            self._save()
        return done

    def __enter__(self) -> "SettingsGuard":
        return self

    def __exit__(self, *exc) -> None:
        self.restore()

    # -- the three kinds of change ------------------------------------------------------------------------------------

    def font_scale(self, scale: float) -> None:
        if not 0.5 <= scale <= 2.0:
            raise ValueError("the font scale goes from 0.5 to 2.0")
        self.set(*FONT_SCALE, f"{scale:g}")

    def animations(self, scale: float) -> None:
        if not 0 <= scale <= 10:
            raise ValueError("an animator scale goes from 0 to 10")
        for namespace, key in ANIMATION_SCALES:
            self.set(namespace, key, f"{scale:g}")

    def talkback(self, on: bool) -> str:
        component = talkback_component()
        current = [c for c in self.get(*A11Y_SERVICES).split(":") if c and c != "null"]
        same = [c for c in current if _same_component(c, component)]
        if on:
            services = current if same else current + [component]
            self.set(*A11Y_SERVICES, ":".join(services))
            self.set(*A11Y_ENABLED, "1")
        else:
            services = [c for c in current if not _same_component(c, component)]
            self.set(*A11Y_SERVICES, ":".join(services) if services else "null")
        return component


def _expand(component: str) -> str:
    package, _, cls = component.partition("/")
    return f"{package}/{package}{cls}" if cls.startswith(".") else component


def _same_component(a: str, b: str) -> bool:
    return _expand(a) == _expand(b)


def talkback_packages() -> list[str]:
    out = _device.shell("pm list packages")
    return sorted(l.split(":", 1)[1] for l in out.splitlines() if l.startswith("package:") and "talkback" in l)


def parse_services(output: str) -> list[str]:
    """Components from `cmd package query-services` (the --components form or the verbose one)."""
    components = [l.strip() for l in output.splitlines() if "/" in l and " " not in l.strip() and l.strip()]
    if components:
        return components
    found, name = [], None
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("name="):
            name = line[5:]
        elif line.startswith("packageName=") and name:
            found.append(f"{line[12:]}/{name}")
            name = None
    return found


def talkback_component() -> str:
    """The TalkBack accessibility service installed on the device."""
    packages = talkback_packages()
    if not packages:
        raise LookupError("no TalkBack package is installed on the device")
    enabled = [c for c in SettingsGuard.get(*A11Y_SERVICES).split(":") if c.split("/")[0] in packages]
    if enabled:
        return enabled[0]
    services = parse_services(
        _device.shell(f"cmd package query-services --components -a {ACCESSIBILITY_ACTION}")
    )
    for component in services:
        if component.split("/")[0] in packages:
            return component
    raise LookupError("the TalkBack package declares no accessibility service")


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if not args:
        print(__doc__)
        return 2
    command = args[0]
    if command == "status":
        for namespace, key in [FONT_SCALE, *ANIMATION_SCALES, A11Y_SERVICES, A11Y_ENABLED]:
            print(f"{namespace}/{key} = {SettingsGuard.get(namespace, key)}")
        return 0
    guard = SettingsGuard(QaLog())
    try:
        if command == "font-scale" and len(args) == 2:
            guard.font_scale(float(args[1]))
        elif command == "animations" and len(args) == 2:
            guard.animations({"off": 0.0, "on": 1.0}.get(args[1]) if args[1] in ("off", "on") else float(args[1]))
        elif command == "talkback" and len(args) == 2 and args[1] in ("on", "off"):
            print(f"TalkBack component: {guard.talkback(args[1] == 'on')}")
        elif command == "restore":
            rows = guard.restore()
            print(restore_table(rows) if rows else "Nothing to restore.")
            return 0 if all(r["restored"] == r["before"] for r in rows) else 1
        else:
            print(__doc__)
            return 2
    except (ValueError, LookupError, PermissionError) as error:
        print(f"settings_guard: {error}")
        return 1
    print("Changed. Run `settings_guard.py restore` before the session ends.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
