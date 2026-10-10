#!/usr/bin/env python3
"""Prints the device queue and the library mirror of a debug build on an emulator (architecture 21.7).

Pulls library.db with its WAL files through `run-as` (a debuggable build only) into a temporary directory and prints
the tables `request`, `request_op`, `clip` and `artifact`, the ground truth for queue and sync checks. It never reads
the pairing store or the learning database, and it refuses to run on anything but an emulator.

Usage: db.py [table ...]   (default: every table above)
"""

from __future__ import annotations

import sqlite3
import sys
import tempfile
from pathlib import Path

import _device

TABLES = ("request", "request_op", "clip", "artifact")
MAX_CELL = 60


def pull(workdir: Path) -> Path:
    """Copies library.db, -wal and -shm out of the app's private storage."""
    target = workdir / _device.LIBRARY_DB
    for suffix in ("", "-wal", "-shm"):
        data = _device.adb("exec-out", f"run-as {_device.PACKAGE} cat databases/{_device.LIBRARY_DB}{suffix}").stdout
        if suffix and (not data or b"No such file" in data):
            continue
        if not suffix and (not data or not data.startswith(b"SQLite format 3")):
            raise RuntimeError("could not read library.db (is a debug build installed and has it started once?)")
        (workdir / f"{_device.LIBRARY_DB}{suffix}").write_bytes(data)
    return target


def cell(value) -> str:
    text = "NULL" if value is None else str(value)
    return text if len(text) <= MAX_CELL else text[: MAX_CELL - 3] + f"...(len={len(text)})"


def dump(database: Path, tables=TABLES) -> str:
    lines = []
    with sqlite3.connect(database) as conn:
        existing = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        for table in tables:
            if table not in TABLES:
                raise ValueError(f"{table} is not one of {', '.join(TABLES)}")
            if table not in existing:
                lines.append(f"{table}: (no such table)")
                continue
            cursor = conn.execute(f'SELECT * FROM "{table}"')
            columns = [d[0] for d in cursor.description]
            rows = cursor.fetchall()
            lines.append(f"{table}: {len(rows)} row(s)")
            for row in rows:
                lines.append("  " + " ".join(f"{c}={cell(v)}" for c, v in zip(columns, row) if v is not None))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    tables = (sys.argv[1:] if argv is None else argv) or list(TABLES)
    _device.require_emulator("db.py")
    with tempfile.TemporaryDirectory(prefix="phonotrainer-db-") as workdir:
        try:
            database = pull(Path(workdir))
            print(dump(database, tables))
        except (RuntimeError, ValueError, sqlite3.Error) as error:
            print(f"db.py: {error}")
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
