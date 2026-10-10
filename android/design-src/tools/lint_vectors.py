#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check every staged VectorDrawable against the two Android lint vector checks that would fail the build.

The app runs lint with warningsAsErrors (architecture 20.7). lint-checks 32.4.1, the version AGP 9.4.1 runs,
with minSdk 29:
- VectorPath (VectorPathDetector): a `pathData` of 3000 characters or more in a file whose root is <vector>.
  Lint counts every character, except that a run of whitespace counts once.
- VectorRaster (VectorDetector): a <vector> whose android:width or android:height is over 200 dp.

This tool applies both rules to every XML file under the given directories. It also holds the vector inside
an animated-vector (aapt:attr) to them, although lint only looks at files whose root is <vector>. Exit 1 if
anything would be reported.

Usage (from android/design-src/)
  python3 tools/lint_vectors.py [export/res ...] [--top 5]
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from svg2vd import LINT_PATH_LIMIT, LINT_VECTOR_DP, lint_path_length  # noqa: E402

A = "{http://schemas.android.com/apk/res/android}"


def dp(value: str | None) -> float:
    m = re.fullmatch(r"\s*([-\d.]+)\s*(dp|dip)\s*", value or "")
    return float(m.group(1)) if m else 0.0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dirs", nargs="*", default=[str(HERE.parent / "export" / "res")])
    ap.add_argument("--top", type=int, default=5, help="list the N longest paths")
    args = ap.parse_args(argv)
    rows: list[tuple[int, str]] = []
    problems: list[str] = []
    vectors = 0
    for d in args.dirs:
        for f in sorted(Path(d).rglob("*.xml")):
            name = os.path.relpath(f)
            root = ET.parse(f).getroot()
            for vec in (el for el in root.iter() if el.tag == "vector"):
                vectors += 1
                w, h = dp(vec.get(A + "width")), dp(vec.get(A + "height"))
                if max(w, h) > LINT_VECTOR_DP:
                    problems.append(f"VectorRaster: {w:g} x {h:g} dp (limit {LINT_VECTOR_DP}): {name}")
            for el in root.iter():
                if el.get(A + "pathData"):
                    n = lint_path_length(el.get(A + "pathData"))
                    rows.append((n, name))
                    if n >= LINT_PATH_LIMIT:
                        problems.append(f"VectorPath: {n} characters (limit {LINT_PATH_LIMIT - 1}): {name}")
    rows.sort(reverse=True)
    for n, f in rows[: args.top]:
        print(f"{n:6d}  {f}")
    print(f"{vectors} vectors, {len(rows)} paths; longest path {rows[0][0] if rows else 0} "
          f"(limit {LINT_PATH_LIMIT - 1}); every intrinsic size within {LINT_VECTOR_DP} dp: "
          f"{'no' if any(p.startswith('VectorRaster') for p in problems) else 'yes'}")
    for p in problems:
        print(p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
