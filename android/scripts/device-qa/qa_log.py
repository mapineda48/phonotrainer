#!/usr/bin/env python3
"""QA log writer that redacts before it writes (architecture 21.7, 20.7; spec X-AC10, NFR-14).

Every line goes through the redaction of android/scripts/hygiene/sensitive.py: credentials (pairing codes and tokens,
signed request headers, Bearer values), server, device and library ids, this computer's identifiers (the local
denylist: host name, user name, home directory, git e-mail, adb serials, host addresses and MACs), the target
device's serial, home paths, IPv4 and IPv6 addresses (replaced by their class), MAC addresses and video or post links.

The log is a Markdown file in android/artifacts/qa-evidence/ (ignored by git): docs/qa-report.md keeps the findings,
never the raw log. The device settings a session changed are listed with their restored values (settings_guard.py).

Usage:
  qa_log.py note "<text>"     append a redacted note
  qa_log.py filter            redact stdin to stdout (for tool output pasted into the log or a report)
  qa_log.py show              print the log
QA_LOG overrides the log file.
"""

from __future__ import annotations

import datetime as dt
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "hygiene"))
import sensitive  # noqa: E402
from _device import EVIDENCE_DIR, serial  # noqa: E402

DEFAULT_LOG = Path(os.environ.get("QA_LOG", EVIDENCE_DIR / "qa-log.md"))


class QaLog:
    def __init__(self, path: Path | None = None, denylist: list | None = None, serials: list[str] | None = None,
                 clock=None):
        self.path = Path(path or DEFAULT_LOG)
        self.denylist = sensitive.local_denylist() if denylist is None else denylist
        self.serials = [s for s in (serials if serials is not None else [serial()]) if s]
        self.clock = clock or (lambda: dt.datetime.now(dt.timezone.utc))

    def redact(self, text: str) -> str:
        return sensitive.redact(text, self.denylist, self.serials)

    def write(self, text: str) -> str:
        """Appends one redacted entry and returns what was written."""
        stamp = self.clock().strftime("%Y-%m-%dT%H:%M:%SZ")
        entry = f"- {stamp} " + self.redact(text).replace("\n", "\n  ")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.path.write_text("# QA log\n\n", encoding="utf-8")
        with self.path.open("a", encoding="utf-8") as log:
            log.write(entry + "\n")
        return entry

    def write_block(self, title: str, text: str) -> str:
        """Appends a redacted block (a table, tool output) under a title."""
        body = self.redact(text).rstrip("\n")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.path.write_text("# QA log\n\n", encoding="utf-8")
        with self.path.open("a", encoding="utf-8") as log:
            log.write(f"\n## {self.redact(title)}\n\n{body}\n\n")
        return body


def restore_table(rows: list[dict]) -> str:
    """Markdown table of settings changes: setting, value before, value set, value restored, verified."""
    lines = ["| Setting | Before | Set to | Restored to | Restored |", "|---|---|---|---|---|"]
    for row in rows:
        restored = row.get("restored")
        verified = "yes" if restored is not None and restored == row.get("before") else "NO"
        lines.append(
            f"| {row['name']} | {row.get('before')} | {row.get('set')} | "
            f"{'not restored' if restored is None else restored} | {verified} |"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if not args:
        print(__doc__)
        return 2
    command = args[0]
    if command == "note" and len(args) > 1:
        print(QaLog().write(" ".join(args[1:])))
        return 0
    if command == "filter":
        log = QaLog()
        for line in sys.stdin:
            sys.stdout.write(log.redact(line))
        return 0
    if command == "show":
        path = DEFAULT_LOG
        print(path.read_text("utf-8") if path.exists() else f"(no log yet at {path.name})")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
