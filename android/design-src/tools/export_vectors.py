#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate every staged VectorDrawable from its SVG master (design.md 13).

  svg/icons/*.svg          -> export/res/drawable/ic_*.xml                (precision 3)
  svg/illustrations/*.svg  -> export/res/drawable/ill_*.xml and drawable-night/ (precision 2, strict palette;
                              the night twin swaps the illustration tokens to the dark theme)
  svg/brand/*.svg          -> brand_export.py (launcher, notification, splash, adaptive icon, colours)

A master larger than lint's VectorRaster limit (200 dp a side) keeps its viewport and declares a smaller
intrinsic size, divided by the smallest whole number that fits (380 x 214 -> 190 x 107 dp): vectors are drawn
at the size they are given, so only the default bounds change. Every path stays under lint's VectorPath
limit (svg2vd.py). Finish with tools/lint_vectors.py and tools/roundtrip.py.

Usage (from anywhere)
  python3 tools/export_vectors.py [--out export/res] [--only icons|illustrations|brand]
"""

from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import brand_export  # noqa: E402
import svg2vd  # noqa: E402
import roundtrip  # noqa: E402  (svg_size)


def lint_size(svg: Path) -> tuple[float, float] | None:
    w, h = roundtrip.svg_size(svg)
    k = math.ceil(max(w, h) / svg2vd.LINT_VECTOR_DP)
    return None if k <= 1 else (w / k, h / k)


def write(src: Path, out: Path, precision: int, recolor=None, strict=False) -> None:
    xml = svg2vd.convert_file(src, lint_size(src), "#000000", {}, None, precision, False, recolor, strict)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(xml, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", default=str(ROOT / "export" / "res"))
    ap.add_argument("--only", choices=("icons", "illustrations", "brand"))
    args = ap.parse_args(argv)
    res = Path(args.out)
    count = 0
    if args.only in (None, "icons"):
        for src in sorted((ROOT / "svg" / "icons").glob("*.svg")):
            write(src, res / "drawable" / f"{src.stem}.xml", 3)
            count += 1
    if args.only in (None, "illustrations"):
        dark = svg2vd.theme_map(ROOT / "palette.json", "dark")
        for src in sorted((ROOT / "svg" / "illustrations").glob("*.svg")):
            write(src, res / "drawable" / f"{src.stem}.xml", 2, None, True)
            write(src, res / "drawable-night" / f"{src.stem}.xml", 2, dark, True)
            count += 2
    print(f"{count} vectors written to {res.name}/")
    if args.only in (None, "brand"):
        return brand_export.main(["--out", str(res)])
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except svg2vd.ConversionError as exc:
        print(f"export_vectors: error: {exc}", file=sys.stderr)
        sys.exit(1)
