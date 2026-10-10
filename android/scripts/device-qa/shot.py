#!/usr/bin/env python3
"""Screenshot evidence: <evidence>/<name>.png, plus a downscaled copy in <evidence>/small/ (architecture 21.7).

Usage:
  shot.py <name>              fast capture with `adb exec-out screencap -p` (about 0.5 s)
  shot.py <name> --annotate   capture with `android screen capture --annotate` (numbered elements, about 3 s)

The evidence directory is android/artifacts/qa-evidence/ (ignored by git); QA_EVIDENCE_DIR overrides it. The small
copy needs Pillow; without it only the full screenshot is saved. On ATD emulator images drawing is off until
`adb shell setprop debug.hwui.drawing_enabled 1` and an app restart, so screenshots come out black before that.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from _device import EVIDENCE_DIR, adb, android_cli

SMALL_SIZE = (540, 1170)


def evidence_paths(name: str, evidence_dir: Path = EVIDENCE_DIR) -> tuple[Path, Path]:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}", name):
        raise ValueError("an evidence name uses letters, digits, '.', '_' and '-' only")
    return evidence_dir / f"{name}.png", evidence_dir / "small" / f"{name}.png"


def downscale(full: Path, small: Path) -> bool:
    try:
        from PIL import Image  # optional: the kit is standard library otherwise
    except ImportError:
        return False
    image = Image.open(full)
    image.thumbnail(SMALL_SIZE)
    image.save(small)
    return True


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if not args or args[0].startswith("-"):
        print(__doc__)
        return 2
    full, small = evidence_paths(args[0])
    small.parent.mkdir(parents=True, exist_ok=True)
    if "--annotate" in args[1:]:
        android_cli("screen", "capture", "--annotate", f"--output={full}")
    else:
        data = adb("exec-out", "screencap", "-p").stdout
        if not data.startswith(b"\x89PNG"):
            print("!! screencap returned no PNG (is a device connected?)")
            return 1
        full.write_bytes(data)
    print(small if downscale(full, small) else full)
    return 0


if __name__ == "__main__":
    sys.exit(main())
