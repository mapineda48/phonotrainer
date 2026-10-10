#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Render an Android VectorDrawable (or the drawable inside an AnimatedVectorDrawable) back to SVG.

The output is for visual review: open it in a browser or screenshot it with Chrome.
It understands what svg2vd.py writes and what Android Studio's Vector Asset tool and
hand-written drawables usually contain:

- <vector> width/height (dp), viewportWidth/Height, alpha, tint (applied SrcIn-style:
  every colour is replaced by the tint, alpha kept), autoMirrored (ignored);
- <group> name, rotation, pivotX/Y, scaleX/Y, translateX/Y (Android's order:
  translate(pivot + translate) · rotate · scale · translate(-pivot));
- <clip-path> pathData: clips the rest of its group;
- <path> pathData, fillColor, fillAlpha, fillType, strokeColor, strokeAlpha, strokeWidth,
  strokeLineCap, strokeLineJoin, strokeMiterLimit, trimPathStart/End/Offset (drawn with
  pathLength=1 dashes);
- inline aapt:attr gradients: linear, radial, sweep (approximated by its first colour),
  with <item> stops or startColor/centerColor/endColor, and tileMode.

Colour references (?attr/…, @color/…, @android:color/…) are resolved with --attr NAME=#hex;
@android:color/white|black|transparent are built in. An unresolved reference is drawn in
magenta and reported, so a missing theme value is visible in the review.

Usage
  python3 vd2svg.py drawable.xml [-o out.svg] [--scale 4] [--background '#f8f6ef']
                    [--attr colorOnSurface='#1b1a18'] [--trim-end 1.0]
"""

from __future__ import annotations

import argparse
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
BUILTIN = {
    "@android:color/white": "#FFFFFFFF",
    "@android:color/black": "#FF000000",
    "@android:color/transparent": "#00000000",
}
MISSING = "#FFFF00FF"


def a(el: ET.Element, name: str, default=None):
    return el.get(f"{{{ANDROID}}}{name}", default)


def num(value, default=0.0) -> float:
    if value is None:
        return default
    m = re.match(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", str(value).strip())
    return float(m.group()) if m else default


class Renderer:
    def __init__(self, attrs: dict[str, str], trim_end: float | None):
        self.attrs = attrs
        self.trim_end = trim_end
        self.defs: list[str] = []
        self.counter = 0
        self.unresolved: set[str] = set()
        self.tint: tuple[str, float] | None = None

    def uid(self, prefix: str) -> str:
        self.counter += 1
        return f"{prefix}{self.counter}"

    def color(self, value: str | None) -> tuple[str, float] | None:
        """VectorDrawable colour -> (#rrggbb, alpha) or None when absent/transparent."""
        if value is None:
            return None
        v = value.strip()
        if v.startswith("?") or v.startswith("@"):
            key = v.split("/")[-1]
            resolved = self.attrs.get(v) or self.attrs.get(key) or BUILTIN.get(v)
            if resolved is None:
                self.unresolved.add(v)
                resolved = MISSING
            v = resolved
        h = v.lstrip("#")
        if len(h) == 3:
            h = "F" + h
        if len(h) == 4:
            h = "".join(c * 2 for c in h)
        if len(h) == 6:
            alpha, rgb = 1.0, h
        elif len(h) == 8:
            alpha, rgb = int(h[:2], 16) / 255, h[2:]
        else:
            raise ValueError(f"bad colour {value!r}")
        if self.tint is not None:
            return self.tint[0], alpha * self.tint[1]
        return "#" + rgb.lower(), alpha

    def gradient(self, holder: ET.Element) -> str | None:
        g = next((c for c in holder if c.tag == "gradient"), None)
        if g is None:
            return None
        kind = a(g, "type", "linear")
        stops = []
        items = [c for c in g if c.tag == "item"]
        if items:
            for it in items:
                stops.append((num(a(it, "offset")), self.color(a(it, "color"))))
        else:
            seq = [a(g, "startColor"), a(g, "centerColor"), a(g, "endColor")]
            seq = [c for c in seq if c is not None]
            for i, c in enumerate(seq):
                stops.append((i / max(1, len(seq) - 1), self.color(c)))
        if kind == "sweep":
            return stops[0][1][0] if stops and stops[0][1] else None
        gid = self.uid("g")
        spread = {"clamp": "pad", "repeated": "repeat", "mirror": "reflect"}.get(a(g, "tileMode", "clamp"), "pad")
        stop_xml = "".join(
            f'<stop offset="{o:.4f}" stop-color="{c[0]}" stop-opacity="{c[1]:.4f}"/>' for o, c in stops if c
        )
        if kind == "radial":
            self.defs.append(
                f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{num(a(g, "centerX"))}" '
                f'cy="{num(a(g, "centerY"))}" r="{num(a(g, "gradientRadius"))}" spreadMethod="{spread}">{stop_xml}</radialGradient>'
            )
        else:
            self.defs.append(
                f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{num(a(g, "startX"))}" '
                f'y1="{num(a(g, "startY"))}" x2="{num(a(g, "endX"))}" y2="{num(a(g, "endY"))}" '
                f'spreadMethod="{spread}">{stop_xml}</linearGradient>'
            )
        return f"url(#{gid})"

    def path(self, el: ET.Element) -> str:
        d = a(el, "pathData", "")
        attrs = [f"d={quoteattr(d)}"]
        holders = {h.get("name"): h for h in el if h.tag == f"{{{AAPT}}}attr"}
        fill_ref = self.gradient(holders["android:fillColor"]) if "android:fillColor" in holders else None
        stroke_ref = self.gradient(holders["android:strokeColor"]) if "android:strokeColor" in holders else None
        fill = self.color(a(el, "fillColor"))
        fill_alpha = num(a(el, "fillAlpha"), 1.0)
        if fill_ref:
            attrs.append(f'fill="{fill_ref}"')
            if fill_alpha < 1:
                attrs.append(f'fill-opacity="{fill_alpha:.4f}"')
        elif fill and fill[1] * fill_alpha > 0:
            attrs.append(f'fill="{fill[0]}"')
            if fill[1] * fill_alpha < 1:
                attrs.append(f'fill-opacity="{fill[1] * fill_alpha:.4f}"')
        else:
            attrs.append('fill="none"')
        if a(el, "fillType") == "evenOdd":
            attrs.append('fill-rule="evenodd"')
        stroke = self.color(a(el, "strokeColor"))
        stroke_alpha = num(a(el, "strokeAlpha"), 1.0)
        width = num(a(el, "strokeWidth"), 0.0)
        if (stroke_ref or (stroke and stroke[1] * stroke_alpha > 0)) and width > 0:
            if stroke_ref:
                attrs.append(f'stroke="{stroke_ref}"')
                if stroke_alpha < 1:
                    attrs.append(f'stroke-opacity="{stroke_alpha:.4f}"')
            else:
                attrs.append(f'stroke="{stroke[0]}"')
                if stroke[1] * stroke_alpha < 1:
                    attrs.append(f'stroke-opacity="{stroke[1] * stroke_alpha:.4f}"')
            attrs.append(f'stroke-width="{width}"')
            attrs.append(f'stroke-linecap="{a(el, "strokeLineCap", "butt")}"')
            attrs.append(f'stroke-linejoin="{a(el, "strokeLineJoin", "miter")}"')
            attrs.append(f'stroke-miterlimit="{num(a(el, "strokeMiterLimit"), 4.0)}"')
            start = num(a(el, "trimPathStart"), 0.0)
            end = num(a(el, "trimPathEnd"), 1.0) if self.trim_end is None else self.trim_end
            offset = num(a(el, "trimPathOffset"), 0.0)
            if start > 0 or end < 1 or offset:
                visible = max(0.0, end - start)
                if visible <= 0:
                    # Android draws nothing for an empty trim; an SVG zero-length dash would
                    # still paint round or square caps as a dot.
                    attrs = [x for x in attrs if not x.startswith("stroke")]
                    attrs.append('stroke="none"')
                    name = a(el, "name")
                    if name:
                        attrs.append(f'id={quoteattr("p-" + name)}')
                    return "<path " + " ".join(attrs) + "/>"
                attrs.append('pathLength="1"')
                attrs.append(f'stroke-dasharray="{visible:.4f} {2:.4f}"')
                attrs.append(f'stroke-dashoffset="{-(start + offset):.4f}"')
        name = a(el, "name")
        if name:
            attrs.append(f'id={quoteattr("p-" + name)}')
        return "<path " + " ".join(attrs) + "/>"

    def group(self, el: ET.Element, depth: int) -> str:
        out: list[str] = []
        children = list(el)
        i = 0
        while i < len(children):
            c = children[i]
            if c.tag == "clip-path":
                cid = self.uid("c")
                self.defs.append(f'<clipPath id="{cid}"><path d={quoteattr(a(c, "pathData", ""))}/></clipPath>')
                rest = ET.Element("group")
                rest.extend(children[i + 1 :])
                out.append(f'<g clip-path="url(#{cid})">{self.group(rest, depth + 1)}</g>')
                break
            if c.tag == "path":
                out.append(self.path(c))
            elif c.tag == "group":
                tx, ty = num(a(c, "translateX")), num(a(c, "translateY"))
                px, py = num(a(c, "pivotX")), num(a(c, "pivotY"))
                sx, sy = num(a(c, "scaleX"), 1.0), num(a(c, "scaleY"), 1.0)
                rot = num(a(c, "rotation"))
                transform = (
                    f"translate({px + tx} {py + ty}) rotate({rot}) scale({sx} {sy}) translate({-px} {-py})"
                )
                gid = a(c, "name")
                ident = f" id={quoteattr('g-' + gid)}" if gid else ""
                out.append(f'<g{ident} transform="{transform}">{self.group(c, depth + 1)}</g>')
            i += 1
        return "".join(out)

    def render(self, vec: ET.Element, scale: float, background: str | None) -> str:
        w = num(a(vec, "width"), 24.0) * scale
        h = num(a(vec, "height"), 24.0) * scale
        vw = num(a(vec, "viewportWidth"), w)
        vh = num(a(vec, "viewportHeight"), h)
        tint = a(vec, "tint")
        if tint:
            self.tint = None
            self.tint = self.color(tint)
        alpha = num(a(vec, "alpha"), 1.0)
        body = self.group(vec, 0)
        bg = f'<rect x="0" y="0" width="{vw}" height="{vh}" fill="{background}"/>' if background else ""
        opacity = f' opacity="{alpha}"' if alpha < 1 else ""
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:g}" height="{h:g}" viewBox="0 0 {vw:g} {vh:g}">'
            f"<defs>{''.join(self.defs)}</defs>{bg}<g{opacity}>{body}</g></svg>\n"
        )


def find_vector(root: ET.Element) -> ET.Element:
    if root.tag == "vector":
        return root
    if root.tag == "animated-vector":
        for holder in root.iter(f"{{{AAPT}}}attr"):
            if holder.get("name") == "android:drawable":
                vec = next((c for c in holder if c.tag == "vector"), None)
                if vec is not None:
                    return vec
        raise ValueError("this animated-vector references an external drawable; render that file instead")
    raise ValueError(f"root element <{root.tag}> is not a vector")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Render a VectorDrawable back to SVG for visual review.")
    ap.add_argument("xml")
    ap.add_argument("-o", "--output", help="output .svg (default: stdout)")
    ap.add_argument("--scale", type=float, default=1.0, help="pixels per dp (default 1)")
    ap.add_argument("--background", help="fill the viewport with this colour first")
    ap.add_argument("--attr", action="append", default=[], help="resolve a reference: NAME=#rrggbb (repeatable)")
    ap.add_argument("--trim-end", type=float, help="override trimPathEnd on every path (preview an animation frame)")
    args = ap.parse_args(argv)
    attrs = {}
    for item in args.attr:
        k, _, v = item.partition("=")
        attrs[k.strip()] = v.strip()
    try:
        vec = find_vector(ET.parse(args.xml).getroot())
        r = Renderer(attrs, args.trim_end)
        svg = r.render(vec, args.scale, args.background)
    except (ET.ParseError, ValueError) as exc:
        print(f"vd2svg: error: {exc}", file=sys.stderr)
        return 1
    for ref in sorted(r.unresolved):
        print(f"vd2svg: warning: {ref} is unresolved and drawn in magenta (pass --attr)", file=sys.stderr)
    if args.output:
        Path(args.output).write_text(svg, encoding="utf-8")
    else:
        sys.stdout.write(svg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
