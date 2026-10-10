#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Extract the PhonoTrainer icon set from a Lucide install, read-only.

Reads design-src/icons.json (the inventory) and a Lucide distribution in either form:
- lucide-static: a directory of <name>.svg files (e.g. node_modules/lucide-static/icons), or
- lucide-react:  the ESM modules (e.g. node_modules/lucide-react/dist/esm/icons), whose
  `__iconData.node` arrays hold the same geometry.

Writes, on request:
  --svg-dir DIR   one SVG source per inventory entry, named <res>.svg, 24 x 24, stroke
                  #000000 at the inventory's width, round caps and joins (feed these to
                  svg2vd.py; the Android build tints them);
  --js FILE       window.ICONS = {lucide-name: inner SVG markup} for the HTML mockups.

Usage
  python3 lucide_icons.py --lucide PATH [--inventory icons.json] [--svg-dir DIR] [--js FILE]
                          [--extra name ...]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

HERE = Path(__file__).resolve().parent
SVG_NS = "http://www.w3.org/2000/svg"


def from_static(path: Path) -> list[tuple[str, dict]]:
    root = ET.parse(path).getroot()
    out = []
    for el in root:
        tag = el.tag.split("}", 1)[-1]
        out.append((tag, dict(el.attrib)))
    return out


def from_esm(path: Path, base: Path, depth: int = 0) -> list[tuple[str, dict]]:
    src = path.read_text(encoding="utf-8")
    m = re.search(r"node:\s*\[", src)
    if not m:
        alias = re.search(r"from '\./([\w-]+)\.mjs'", src)
        if alias and depth < 3:
            return from_esm(base / f"{alias.group(1)}.mjs", base, depth + 1)
        raise ValueError(f"no icon data in {path}")
    start = m.end() - 1
    level, i = 0, start
    while i < len(src):  # bracket matching; the data holds no brackets inside strings
        if src[i] == "[":
            level += 1
        elif src[i] == "]":
            level -= 1
            if level == 0:
                break
        i += 1
    js = src[start : i + 1]
    js = re.sub(r"([{,]\s*)([A-Za-z][\w-]*)\s*:", r'\1"\2":', js)  # quote keys
    js = re.sub(r",\s*([\]}])", r"\1", js)  # trailing commas
    nodes = json.loads(js)
    return [(tag, {k: v for k, v in attrs.items() if k != "key"}) for tag, attrs in nodes]


def load(lucide: Path, name: str) -> list[tuple[str, dict]]:
    svg = lucide / f"{name}.svg"
    if svg.exists():
        return from_static(svg)
    mjs = lucide / f"{name}.mjs"
    if mjs.exists():
        return from_esm(mjs, lucide)
    raise FileNotFoundError(f"Lucide icon {name!r} not found in {lucide}")


def inner(nodes: list[tuple[str, dict]]) -> str:
    return "".join(f"<{tag} " + " ".join(f"{k}={quoteattr(str(v))}" for k, v in attrs.items()) + "/>"
                   for tag, attrs in nodes)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--lucide", required=True, help="lucide-static icons dir or lucide-react dist/esm/icons dir")
    ap.add_argument("--inventory", default=str(HERE.parent / "icons.json"))
    ap.add_argument("--svg-dir", help="write <res>.svg sources here")
    ap.add_argument("--js", help="write window.ICONS for the mockups here")
    ap.add_argument("--extra", nargs="*", default=[], help="more Lucide names for the mockups only")
    args = ap.parse_args(argv)
    lucide = Path(args.lucide)
    inv = json.loads(Path(args.inventory).read_text(encoding="utf-8"))
    version = inv.get("lucideVersion", "?")
    missing = []
    shapes: dict[str, str] = {}
    for item in inv["icons"]:
        try:
            shapes[item["lucide"]] = inner(load(lucide, item["lucide"]))
        except (FileNotFoundError, ValueError) as exc:
            missing.append(str(exc))
    for name in args.extra:
        try:
            shapes[name] = inner(load(lucide, name))
        except (FileNotFoundError, ValueError) as exc:
            missing.append(str(exc))
    if missing:
        print("\n".join(missing), file=sys.stderr)
        return 1
    if args.svg_dir:
        out = Path(args.svg_dir)
        out.mkdir(parents=True, exist_ok=True)
        for item in inv["icons"]:
            text = (
                f'<svg xmlns="{SVG_NS}" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#000000" '
                f'stroke-width="{item["stroke"]}" stroke-linecap="round" stroke-linejoin="round">\n'
                f'  <title>{item["res"]}: {item["meaning"]} (Lucide "{item["lucide"]}" v{version}, ISC)</title>\n'
                f'  {shapes[item["lucide"]]}\n</svg>\n'
            )
            (out / f'{item["res"]}.svg').write_text(text, encoding="utf-8")
        print(f"wrote {len(inv['icons'])} SVG sources to {out}")
    if args.js:
        header = (
            f"// Icon geometry from Lucide v{version} (https://lucide.dev), ISC License, Copyright (c) Lucide Icons\n"
            "// and Contributors; glyphs derived from Feather are MIT, Copyright (c) 2013-present Cole Bemis.\n"
            "// Licence text: design-src/export/assets/licenses/lucide-LICENSE.txt. Generated by tools/lucide_icons.py.\n"
        )
        Path(args.js).write_text(header + "window.ICONS=" + json.dumps(shapes, ensure_ascii=False, indent=0) + ";\n",
                                 encoding="utf-8")
        print(f"wrote {len(shapes)} icons to {args.js}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
