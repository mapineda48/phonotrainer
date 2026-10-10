#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Round-trip check for svg2vd.py: SVG -> VectorDrawable -> SVG, rendered and compared.

For every SVG given (default: tools/fixtures/*.svg and svg/brand/*.svg) this script
1. converts it with svg2vd.py (light, and dark when --palette is given),
2. renders the VectorDrawable back to SVG with vd2svg.py,
3. screenshots the original and the round trip with headless Chrome at the same size,
4. compares the two PNGs with ImageMagick (normalized RMSE) and writes a side-by-side
   montage for a human look.

A difference above --threshold fails the run (exit status 1). Chrome ("google-chrome")
and ImageMagick ("magick") must be on PATH; the script itself is standard-library Python.

Usage
  python3 roundtrip.py [files.svg ...] --out DIR [--scale 4] [--threshold 0.02]
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import svg2vd  # noqa: E402
import vd2svg  # noqa: E402


def svg_size(path: Path) -> tuple[float, float]:
    root = ET.parse(path).getroot()
    w, h = root.get("width"), root.get("height")
    if w and h and not w.endswith("%"):
        return float(re.match(r"[\d.]+", w).group()), float(re.match(r"[\d.]+", h).group())
    vb = [float(v) for v in re.findall(r"[-\d.]+", root.get("viewBox", "0 0 24 24"))]
    return vb[2], vb[3]


def shoot(svg: Path, png: Path, w: float, h: float, scale: float) -> None:
    # Wrap in a page with a mid-grey ground so white, black, light and dark art all show.
    page = svg.with_suffix(".html")
    page.write_text(
        f'<!doctype html><html><body style="margin:0;background:#8a8a8a">'
        f'<img src="{svg.name}" width="{w:g}" height="{h:g}" style="display:block"></body></html>',
        encoding="utf-8",
    )
    subprocess.run(
        ["google-chrome", "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--force-device-scale-factor={scale}",
         f"--window-size={int(round(w))},{int(round(h))}", "--virtual-time-budget=1000", "--allow-file-access-from-files",
         f"--screenshot={png}", page.as_uri()],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def rmse(a: Path, b: Path) -> float:
    proc = subprocess.run(["magick", "compare", "-metric", "RMSE", str(a), str(b), "null:"],
                          capture_output=True, text=True)
    m = re.search(r"\(([\d.eE+-]+)\)", proc.stderr)
    if not m:
        raise RuntimeError(f"magick compare failed: {proc.stderr.strip()}")
    return float(m.group(1))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("files", nargs="*")
    ap.add_argument("--out", required=True, help="working directory for the XML, SVG and PNG files")
    ap.add_argument("--scale", type=float, default=4.0)
    ap.add_argument("--threshold", type=float, default=0.02, help="maximum normalized RMSE (default 0.02)")
    ap.add_argument("--palette", help="palette.json: also convert each file to its dark twin and render it")
    args = ap.parse_args(argv)
    for tool in ("google-chrome", "magick"):
        if not shutil.which(tool):
            print(f"roundtrip: {tool} is not on PATH", file=sys.stderr)
            return 2
    files = [Path(f) for f in args.files] or sorted((HERE / "fixtures").glob("*.svg")) + sorted(
        (HERE.parent / "svg" / "brand").glob("*.svg"))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    failed = 0
    print(f"{'file':40} {'theme':6} {'paths':>5} {'xml bytes':>9} {'rmse':>8}  result")
    for src in files:
        variants = [("light", None)]
        if args.palette:
            variants.append(("dark", svg2vd.theme_map(Path(args.palette), "dark")))
        for theme, recolor in variants:
            stem = f"{src.stem}-{theme}"
            try:
                xml = svg2vd.convert_file(src, None, "#1b1a18", {}, None, 3, False, recolor)
            except svg2vd.ConversionError as exc:
                print(f"{src.name:40} {theme:6} conversion error: {exc}")
                failed += 1
                continue
            vd = out / f"{stem}.xml"
            vd.write_text(xml, encoding="utf-8")
            r = vd2svg.Renderer({}, None)
            back = out / f"{stem}.roundtrip.svg"
            back.write_text(r.render(vd2svg.find_vector(ET.parse(vd).getroot()), 1.0, None), encoding="utf-8")
            # The reference: the original SVG, recoloured the same way for the dark variant.
            ref = out / f"{stem}.source.svg"
            text = src.read_text(encoding="utf-8").replace("currentColor", "#1b1a18")
            if recolor:
                text = re.sub(r"#[0-9a-fA-F]{6}\b", lambda m: recolor.get(m.group().lower(), m.group()), text)
            ref.write_text(text, encoding="utf-8")
            w, h = svg_size(src)
            a_png, b_png = out / f"{stem}.source.png", out / f"{stem}.roundtrip.png"
            shoot(ref, a_png, w, h, args.scale)
            shoot(back, b_png, w, h, args.scale)
            value = rmse(a_png, b_png)
            subprocess.run(["magick", str(a_png), str(b_png), "+append", str(out / f"{stem}.compare.png")], check=True)
            ok = value <= args.threshold
            failed += 0 if ok else 1
            paths = xml.count("<path")
            print(f"{src.name:40} {theme:6} {paths:5d} {len(xml):9d} {value:8.4f}  {'ok' if ok else 'DIFFERS'}")
    print("\nall round trips match" if not failed else f"\n{failed} round trip(s) differ or failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
