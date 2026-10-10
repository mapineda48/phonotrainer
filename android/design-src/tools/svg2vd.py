#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Convert the SVG subset used by the PhonoTrainer asset makers to an Android VectorDrawable.

Supported input
- Elements: svg, g, path, circle, ellipse, rect (with rx/ry), line, polyline, polygon,
  defs, linearGradient, radialGradient, stop, clipPath (on g or on a shape).
  title, desc, metadata and editor namespaces (sodipodi, inkscape) are ignored.
- Transforms on any element: matrix, translate, scale, rotate (with centre), skewX, skewY.
  Transforms are flattened into the path coordinates, so skews and non-uniform scales
  are exact (VectorDrawable groups cannot express them). Stroke widths are scaled by
  sqrt(|det|).
- Paint: fill, stroke (colour, url(#gradient), none, currentColor), stroke-width,
  stroke-linecap, stroke-linejoin, stroke-miterlimit, fill-rule, fill-opacity,
  stroke-opacity, opacity (multiplied down into fill and stroke alpha), display:none,
  visibility:hidden. Both presentation attributes and style="…" are read.
- Colours: #rgb, #rgba, #rrggbb, #rrggbbaa, rgb()/rgba(), the CSS named colours most
  editors emit, var(--token[, fallback]) resolved with --color/--colors, and theme
  attributes (?attr/…) or colour resources (@color/…) passed through verbatim.
- Gradients: linear and radial, objectBoundingBox or userSpaceOnUse, gradientTransform,
  spreadMethod, href inheritance. Output uses aapt:attr inline gradients (API 24+).

Not supported (an error unless --lenient, which skips them with a warning): text, image,
use, mask, filter, pattern, marker, symbol, switch, foreignObject, <style> sheets.
Convert text to paths and expand symbols before exporting.

Usage
  python3 svg2vd.py in.svg [-o out.xml] [--size 24] [--current-color '#000000']
                   [--color ink='#1b1a18'] [--colors theme.json] [--tint '?attr/colorControlNormal']
                   [--precision 3] [--lenient] [--no-merge] [--max-path 3000]

Every pathData stays under Android lint's VectorPath limit (3000 characters, a run of blanks counting
once): merges stop short of it and a longer path is split at subpath boundaries when that cannot change
a pixel (otherwise the conversion fails).
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from xml.sax.saxutils import quoteattr

SVG_NS = "http://www.w3.org/2000/svg"
XLINK_NS = "http://www.w3.org/1999/xlink"
ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"

IGNORED = {"title", "desc", "metadata", "namedview", "defs"}
UNSUPPORTED = {"text", "image", "use", "mask", "filter", "pattern", "marker", "symbol", "switch",
               "foreignObject", "style", "tspan", "textPath", "a"}
SHAPES = {"path", "circle", "ellipse", "rect", "line", "polyline", "polygon"}

NAMED = {
    "black": "#000000", "white": "#ffffff", "red": "#ff0000", "green": "#008000", "blue": "#0000ff",
    "yellow": "#ffff00", "cyan": "#00ffff", "aqua": "#00ffff", "magenta": "#ff00ff", "fuchsia": "#ff00ff",
    "gray": "#808080", "grey": "#808080", "silver": "#c0c0c0", "maroon": "#800000", "olive": "#808000",
    "lime": "#00ff00", "navy": "#000080", "purple": "#800080", "teal": "#008080", "orange": "#ffa500",
    "darkgray": "#a9a9a9", "darkgrey": "#a9a9a9", "lightgray": "#d3d3d3", "lightgrey": "#d3d3d3",
    "dimgray": "#696969", "dimgrey": "#696969", "gainsboro": "#dcdcdc", "whitesmoke": "#f5f5f5",
    "ivory": "#fffff0", "beige": "#f5f5dc", "tan": "#d2b48c", "wheat": "#f5deb3", "linen": "#faf0e6",
}
INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
             "fill-opacity", "stroke-opacity", "fill-rule", "color", "visibility", "clip-rule", "stroke-dasharray")


class ConversionError(Exception):
    pass


def warn(msg: str) -> None:
    print(f"svg2vd: warning: {msg}", file=sys.stderr)


# --------------------------------------------------------------------------------------
# Affine matrices: (a, b, c, d, e, f) maps (x, y) -> (a x + c y + e, b x + d y + f)
# --------------------------------------------------------------------------------------

Matrix = tuple[float, float, float, float, float, float]
IDENTITY: Matrix = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def mul(m: Matrix, n: Matrix) -> Matrix:
    """m then applied after n: result(p) = m(n(p))."""
    a, b, c, d, e, f = m
    A, B, C, D, E, F = n
    return (a * A + c * B, b * A + d * B, a * C + c * D, b * C + d * D, a * E + c * F + e, b * E + d * F + f)


def apply(m: Matrix, x: float, y: float) -> tuple[float, float]:
    a, b, c, d, e, f = m
    return a * x + c * y + e, b * x + d * y + f


def apply_vec(m: Matrix, x: float, y: float) -> tuple[float, float]:
    a, b, c, d, _, _ = m
    return a * x + c * y, b * x + d * y


def det(m: Matrix) -> float:
    return m[0] * m[3] - m[1] * m[2]


def scale_factor(m: Matrix) -> float:
    return math.sqrt(abs(det(m)))


def is_conformal(m: Matrix, tol: float = 1e-3) -> bool:
    a, b, c, d, _, _ = m
    return abs(a * c + b * d) < tol * max(1.0, a * a + b * b) and abs((a * a + b * b) - (c * c + d * d)) < tol * max(1.0, a * a + b * b)


def inverse(m: Matrix) -> Matrix:
    a, b, c, d, e, f = m
    k = det(m)
    if abs(k) < 1e-12:
        raise ConversionError("a transform collapses the drawing to a line")
    return (d / k, -b / k, -c / k, a / k, (c * f - d * e) / k, (b * e - a * f) / k)


def decompose(m: Matrix) -> tuple[float, float, float, float]:
    """Linear part of m as R(phi) · diag(sx, sy) · R(theta); returns (phi, sx, sy, theta) in degrees.

    sy is negative when m mirrors. VectorDrawable groups apply scale, then rotation,
    then translation, so two nested groups express any affine transform exactly.
    """
    a, b, c, d = m[0], m[1], m[2], m[3]
    e_, f_ = (a + d) / 2, (a - d) / 2
    g_, h_ = (b + c) / 2, (b - c) / 2
    q, r = math.hypot(e_, h_), math.hypot(f_, g_)
    sx, sy = q + r, q - r
    a1, a2 = math.atan2(g_, f_), math.atan2(h_, e_)
    theta, phi = (a2 - a1) / 2, (a2 + a1) / 2
    return math.degrees(phi), sx, sy, math.degrees(theta)


_NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"


def parse_transform(text: str | None) -> Matrix:
    m = IDENTITY
    if not text:
        return m
    for name, args in re.findall(r"([a-zA-Z]+)\s*\(([^)]*)\)", text):
        v = [float(x) for x in re.findall(_NUM, args)]
        if name == "matrix" and len(v) == 6:
            t = tuple(v)
        elif name == "translate":
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == "scale":
            t = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == "rotate":
            r = math.radians(v[0])
            rot = (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)
            if len(v) == 3:
                t = mul((1, 0, 0, 1, v[1], v[2]), mul(rot, (1, 0, 0, 1, -v[1], -v[2])))
            else:
                t = rot
        elif name == "skewX":
            t = (1, 0, math.tan(math.radians(v[0])), 1, 0, 0)
        elif name == "skewY":
            t = (1, math.tan(math.radians(v[0])), 0, 1, 0, 0)
        else:
            raise ConversionError(f"unknown transform {name!r}")
        m = mul(m, t)  # type: ignore[arg-type]
    return m


# --------------------------------------------------------------------------------------
# Path data: parse to absolute M/L/C/Z segments
# --------------------------------------------------------------------------------------

Seg = tuple  # ("M", x, y) | ("L", x, y) | ("C", x1, y1, x2, y2, x, y) | ("Z",)


def _tokens(d: str):
    i, n = 0, len(d)
    num = re.compile(_NUM)
    while i < n:
        ch = d[i]
        if ch in " \t\r\n,":
            i += 1
        elif ch.isalpha():
            yield ch
            i += 1
        else:
            m = num.match(d, i)
            if not m:
                raise ConversionError(f"bad path data near {d[i:i + 20]!r}")
            yield float(m.group())
            i = m.end()


def _arc_flag_tokens(d: str):
    """Tokenizer that also splits compact arc flags like 'a10 10 0 0110 10'."""
    out = []
    i, n = 0, len(d)
    num = re.compile(_NUM)
    cmd = None
    argi = 0
    while i < n:
        ch = d[i]
        if ch in " \t\r\n,":
            i += 1
            continue
        if ch.isalpha():
            cmd = ch
            argi = 0
            out.append(ch)
            i += 1
            continue
        if cmd in ("a", "A") and argi % 7 in (3, 4) and ch in "01":
            out.append(float(ch))
            argi += 1
            i += 1
            continue
        m = num.match(d, i)
        if not m:
            raise ConversionError(f"bad path data near {d[i:i + 20]!r}")
        out.append(float(m.group()))
        argi += 1
        i = m.end()
    return out


def arc_to_cubics(x1, y1, rx, ry, phi_deg, large, sweep, x2, y2):
    """SVG elliptical arc (endpoint parameterisation) to a list of cubic segments."""
    if (x1, y1) == (x2, y2):
        return []
    if rx == 0 or ry == 0:
        return [("C", x1, y1, x2, y2, x2, y2)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(phi_deg % 360)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p = cp * dx + sp * dy
    y1p = -sp * dx + cp * dy
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        coef = -coef
    cxp = coef * rx * y1p / ry
    cyp = -coef * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    step = dt / n
    k = 4 / 3 * math.tan(step / 4)
    segs = []
    t = t1
    for _ in range(n):
        c1, s1 = math.cos(t), math.sin(t)
        c2, s2 = math.cos(t + step), math.sin(t + step)
        p1 = (c1 - k * s1, s1 + k * c1)
        p2 = (c2 + k * s2, s2 - k * c2)
        p3 = (c2, s2)
        pts = []
        for (ux, uy) in (p1, p2, p3):
            ex, ey = ux * rx, uy * ry
            pts += [cp * ex - sp * ey + cx, sp * ex + cp * ey + cy]
        segs.append(("C", *pts))
        t += step
    # snap the final point exactly
    last = list(segs[-1])
    last[5], last[6] = x2, y2
    segs[-1] = tuple(last)
    return segs


def parse_path(d: str) -> list[Seg]:
    toks = _arc_flag_tokens(d)
    segs: list[Seg] = []
    i = 0
    cmd = None
    cx = cy = sx = sy = 0.0
    last_c2 = None  # for S
    last_q = None  # for T

    def nums(k):
        nonlocal i
        vals = toks[i : i + k]
        if len(vals) < k or any(isinstance(v, str) for v in vals):
            raise ConversionError(f"path command {cmd} expects {k} numbers")
        i += k
        return vals

    while i < len(toks):
        t = toks[i]
        if isinstance(t, str):
            cmd = t
            i += 1
            if cmd in "Zz":
                segs.append(("Z",))
                cx, cy = sx, sy
                last_c2 = last_q = None
                continue
        elif cmd is None:
            raise ConversionError("path data must start with a command")
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = (cx, cy) if rel else (0.0, 0.0)
        if c == "M":
            x, y = nums(2)
            cx, cy = x + ox, y + oy
            sx, sy = cx, cy
            segs.append(("M", cx, cy))
            cmd = "l" if rel else "L"  # implicit lineto
            last_c2 = last_q = None
        elif c == "L":
            x, y = nums(2)
            cx, cy = x + ox, y + oy
            segs.append(("L", cx, cy))
            last_c2 = last_q = None
        elif c == "H":
            (x,) = nums(1)
            cx = x + ox
            segs.append(("L", cx, cy))
            last_c2 = last_q = None
        elif c == "V":
            (y,) = nums(1)
            cy = y + oy
            segs.append(("L", cx, cy))
            last_c2 = last_q = None
        elif c == "C":
            x1, y1, x2, y2, x, y = nums(6)
            segs.append(("C", x1 + ox, y1 + oy, x2 + ox, y2 + oy, x + ox, y + oy))
            last_c2 = (x2 + ox, y2 + oy)
            cx, cy = x + ox, y + oy
            last_q = None
        elif c == "S":
            x2, y2, x, y = nums(4)
            x1, y1 = (2 * cx - last_c2[0], 2 * cy - last_c2[1]) if last_c2 else (cx, cy)
            segs.append(("C", x1, y1, x2 + ox, y2 + oy, x + ox, y + oy))
            last_c2 = (x2 + ox, y2 + oy)
            cx, cy = x + ox, y + oy
            last_q = None
        elif c == "Q":
            qx, qy, x, y = nums(4)
            qx, qy, x, y = qx + ox, qy + oy, x + ox, y + oy
            segs.append(("C", cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy), x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), x, y))
            last_q = (qx, qy)
            cx, cy = x, y
            last_c2 = None
        elif c == "T":
            x, y = nums(2)
            x, y = x + ox, y + oy
            qx, qy = (2 * cx - last_q[0], 2 * cy - last_q[1]) if last_q else (cx, cy)
            segs.append(("C", cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy), x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), x, y))
            last_q = (qx, qy)
            cx, cy = x, y
            last_c2 = None
        elif c == "A":
            rx, ry, rot, large, sweep, x, y = nums(7)
            x, y = x + ox, y + oy
            segs += arc_to_cubics(cx, cy, rx, ry, rot, int(large), int(sweep), x, y)
            cx, cy = x, y
            last_c2 = last_q = None
        else:
            raise ConversionError(f"unknown path command {cmd!r}")
    return segs


def ellipse_segs(cx, cy, rx, ry) -> list[Seg]:
    if rx <= 0 or ry <= 0:
        return []
    segs: list[Seg] = [("M", cx + rx, cy)]
    segs += arc_to_cubics(cx + rx, cy, rx, ry, 0, 0, 1, cx, cy + ry)
    segs += arc_to_cubics(cx, cy + ry, rx, ry, 0, 0, 1, cx - rx, cy)
    segs += arc_to_cubics(cx - rx, cy, rx, ry, 0, 0, 1, cx, cy - ry)
    segs += arc_to_cubics(cx, cy - ry, rx, ry, 0, 0, 1, cx + rx, cy)
    segs.append(("Z",))
    return segs


def rect_segs(x, y, w, h, rx, ry) -> list[Seg]:
    if w <= 0 or h <= 0:
        return []
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    if rx <= 0 or ry <= 0:
        return [("M", x, y), ("L", x + w, y), ("L", x + w, y + h), ("L", x, y + h), ("Z",)]
    s: list[Seg] = [("M", x + rx, y), ("L", x + w - rx, y)]
    s += arc_to_cubics(x + w - rx, y, rx, ry, 0, 0, 1, x + w, y + ry)
    s.append(("L", x + w, y + h - ry))
    s += arc_to_cubics(x + w, y + h - ry, rx, ry, 0, 0, 1, x + w - rx, y + h)
    s.append(("L", x + rx, y + h))
    s += arc_to_cubics(x + rx, y + h, rx, ry, 0, 0, 1, x, y + h - ry)
    s.append(("L", x, y + ry))
    s += arc_to_cubics(x, y + ry, rx, ry, 0, 0, 1, x + rx, y)
    s.append(("Z",))
    return s


def points_segs(text: str, close: bool) -> list[Seg]:
    v = [float(x) for x in re.findall(_NUM, text or "")]
    if len(v) < 4:
        return []
    s: list[Seg] = [("M", v[0], v[1])] + [("L", v[k], v[k + 1]) for k in range(2, len(v) - 1, 2)]
    if close:
        s.append(("Z",))
    return s


def transform_segs(segs: list[Seg], m: Matrix) -> list[Seg]:
    out: list[Seg] = []
    for s in segs:
        if s[0] == "Z":
            out.append(s)
        elif s[0] in ("M", "L"):
            out.append((s[0], *apply(m, s[1], s[2])))
        else:
            out.append(("C", *apply(m, s[1], s[2]), *apply(m, s[3], s[4]), *apply(m, s[5], s[6])))
    return out


def bbox(segs: list[Seg]) -> tuple[float, float, float, float]:
    xs, ys = [], []
    for s in segs:
        for k in range(1, len(s), 2):
            xs.append(s[k])
            ys.append(s[k + 1])
    if not xs:
        return (0, 0, 0, 0)
    return min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)


def fmt(v: float, precision: int) -> str:
    s = f"{v:.{precision}f}".rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    return s


def segs_to_d(segs: list[Seg], precision: int) -> str:
    parts = []
    for s in segs:
        if s[0] == "Z":
            parts.append("Z")
        else:
            parts.append(s[0] + " ".join(fmt(v, precision) for v in s[1:]))
    return "".join(parts)


# --------------------------------------------------------------------------------------
# Paint
# --------------------------------------------------------------------------------------


@dataclass
class Paint:
    kind: str  # "none" | "color" | "ref" | "gradient"
    value: str = ""  # #AARRGGBB, ?attr/..., @color/...
    alpha: float = 1.0
    gradient: str = ""


class Resolver:
    def __init__(self, current: str, variables: dict[str, str], recolor: dict[str, str] | None = None,
                 strict_palette: bool = False):
        self.current = current
        self.vars = variables
        self.recolor = recolor
        self.strict = strict_palette
        self.off_palette: set[str] = set()

    def _swap(self, hex6: str) -> str:
        """Theme swap for illustration tokens: light hex -> dark hex."""
        if self.recolor is None:
            return hex6
        key = hex6.lower()
        if key in self.recolor:
            return self.recolor[key].upper()
        self.off_palette.add(key)
        if self.strict:
            raise ConversionError(f"{key} is not an illustration token of palette.json")
        return hex6

    def color(self, text: str, current: str | None = None) -> Paint:
        t = (text or "").strip()
        if not t or t == "none" or t == "transparent":
            return Paint("none")
        m = re.fullmatch(r"var\(\s*--([\w-]+)\s*(?:,\s*(.+))?\)", t)
        if m:
            name, fallback = m.group(1), m.group(2)
            if name in self.vars:
                return self.color(self.vars[name], current)
            if fallback:
                return self.color(fallback, current)
            raise ConversionError(f"var(--{name}) has no value: pass --color {name}=#rrggbb")
        if t.startswith("url("):
            ref = re.fullmatch(r"url\(\s*['\"]?#([^'\")]+)['\"]?\s*\)(?:\s+(.*))?", t)
            if not ref:
                raise ConversionError(f"bad paint {t!r}")
            return Paint("gradient", gradient=ref.group(1))
        if t == "currentColor":
            return self.color(current or self.current)
        if t.startswith("?") or t.startswith("@"):
            return Paint("ref", value=t)
        if t.lower() in NAMED:
            t = NAMED[t.lower()]
        if t.startswith("#"):
            h = t[1:]
            if len(h) in (3, 4):
                h = "".join(c * 2 for c in h)
            if len(h) == 6:
                return Paint("color", self._swap("#" + h.upper()), 1.0)
            if len(h) == 8:
                return Paint("color", self._swap("#" + h[:6].upper()), int(h[6:], 16) / 255)
            raise ConversionError(f"bad colour {t!r}")
        m = re.fullmatch(r"rgba?\(([^)]*)\)", t)
        if m:
            parts = [p.strip() for p in re.split(r"[,\s/]+", m.group(1)) if p.strip()]
            vals = []
            for p in parts[:3]:
                vals.append(round(float(p[:-1]) * 2.55) if p.endswith("%") else round(float(p)))
            a = 1.0
            if len(parts) > 3:
                a = float(parts[3][:-1]) / 100 if parts[3].endswith("%") else float(parts[3])
            return Paint("color", self._swap("#" + "".join(f"{max(0, min(255, v)):02X}" for v in vals)), a)
        raise ConversionError(f"unsupported colour {t!r}")


def argb(paint: Paint, alpha: float) -> str:
    a = max(0.0, min(1.0, paint.alpha * alpha))
    return f"#{round(a * 255):02X}{paint.value[1:]}"


# --------------------------------------------------------------------------------------
# Conversion
# --------------------------------------------------------------------------------------


def local(tag: str) -> str:
    return tag.split("}", 1)[1] if tag.startswith("{") else tag


def length(value: str | None, default: float = 0.0, ref: float | None = None) -> float:
    if value is None or value == "":
        return default
    v = value.strip()
    if v.endswith("%"):
        if ref is None:
            raise ConversionError(f"percentage length {v!r} is not supported here")
        return float(v[:-1]) / 100 * ref
    m = re.match(_NUM, v)
    if not m:
        raise ConversionError(f"bad length {value!r}")
    num = float(m.group())
    unit = v[m.end():].strip()
    if unit in ("", "px"):
        return num
    if unit == "pt":
        return num * 4 / 3
    raise ConversionError(f"unsupported unit {unit!r} in {value!r}")


def styles(el: ET.Element) -> dict[str, str]:
    out = {k: v for k, v in el.attrib.items() if not k.startswith("{")}
    for decl in (el.get("style") or "").split(";"):
        if ":" in decl:
            k, v = decl.split(":", 1)
            out[k.strip()] = v.strip()
    return out


@dataclass
class Node:
    tag: str  # "path" | "group"
    attrs: dict = field(default_factory=dict)
    children: list = field(default_factory=list)
    gradient_fill: ET.Element | None = None
    gradient_stroke: ET.Element | None = None
    clip: str | None = None


class Converter:
    def __init__(self, root: ET.Element, resolver: Resolver, precision: int, lenient: bool):
        self.root = root
        self.r = resolver
        self.precision = precision
        self.lenient = lenient
        self.ids: dict[str, ET.Element] = {}
        for el in root.iter():
            if el.get("id"):
                self.ids[el.get("id")] = el
        self.used_gradients = False

    # ---- shapes ----
    def shape_segs(self, el: ET.Element, vw: float, vh: float) -> list[Seg]:
        tag = local(el.tag)
        diag = math.hypot(vw, vh) / math.sqrt(2)
        if tag == "path":
            return parse_path(el.get("d") or "")
        if tag == "circle":
            r = length(el.get("r"), 0, diag)
            return ellipse_segs(length(el.get("cx"), 0, vw), length(el.get("cy"), 0, vh), r, r)
        if tag == "ellipse":
            return ellipse_segs(length(el.get("cx"), 0, vw), length(el.get("cy"), 0, vh),
                                length(el.get("rx"), 0, vw), length(el.get("ry"), 0, vh))
        if tag == "rect":
            rx_s, ry_s = el.get("rx"), el.get("ry")
            rx = length(rx_s, 0, vw) if rx_s not in (None, "auto") else None
            ry = length(ry_s, 0, vh) if ry_s not in (None, "auto") else None
            rx = rx if rx is not None else (ry if ry is not None else 0.0)
            ry = ry if ry is not None else rx
            return rect_segs(length(el.get("x"), 0, vw), length(el.get("y"), 0, vh),
                             length(el.get("width"), 0, vw), length(el.get("height"), 0, vh), rx, ry)
        if tag == "line":
            return [("M", length(el.get("x1"), 0, vw), length(el.get("y1"), 0, vh)),
                    ("L", length(el.get("x2"), 0, vw), length(el.get("y2"), 0, vh))]
        if tag == "polyline":
            return points_segs(el.get("points", ""), close=False)
        if tag == "polygon":
            return points_segs(el.get("points", ""), close=True)
        raise ConversionError(f"not a shape: {tag}")

    # ---- gradients ----
    def gradient_chain(self, gid: str) -> list[ET.Element]:
        chain = []
        seen = set()
        el = self.ids.get(gid)
        while el is not None and gid not in seen:
            seen.add(gid)
            chain.append(el)
            href = el.get(f"{{{XLINK_NS}}}href") or el.get("href")
            if not href or not href.startswith("#"):
                break
            gid = href[1:]
            el = self.ids.get(gid)
        if not chain:
            raise ConversionError(f"gradient #{gid} not found")
        return chain

    def gradient(self, gid: str, local_segs: list[Seg], ctm: Matrix, opacity: float,
                 allow_frame: bool = False) -> tuple[ET.Element, Matrix | None]:
        """Build an aapt gradient. Returns (gradient, frame).

        frame is None when the gradient is expressed in output coordinates. For a radial
        gradient under a non-uniform transform (an ellipse) and allow_frame=True, the
        gradient is built in its own circular space and frame is the transform from that
        space to the output: the caller draws the path in gradient space inside groups
        that apply frame, which keeps the ellipse exact.
        """
        chain = self.gradient_chain(gid)
        kind = local(chain[0].tag)
        if kind not in ("linearGradient", "radialGradient"):
            raise ConversionError(f"#{gid} is a {kind}, not a gradient")

        def attr(name, default=None):
            for el in chain:
                if el.get(name) is not None:
                    return el.get(name)
            return default

        stops_el = next((el for el in chain if any(local(c.tag) == "stop" for c in el)), None)
        if stops_el is None:
            raise ConversionError(f"gradient #{gid} has no stops")
        units = attr("gradientUnits", "objectBoundingBox")
        gt = parse_transform(attr("gradientTransform"))
        bx, by, bw, bh = bbox(local_segs)
        if units == "objectBoundingBox":
            unit_m = (bw, 0, 0, bh, bx, by)
            to_user = mul(unit_m, gt)

            def coord(v, d, axis):
                v = v if v is not None else d
                return float(v[:-1]) / 100 if v.endswith("%") else float(v)
        else:
            to_user = gt
            vw, vh = self.viewport

            def coord(v, d, axis):
                v = v if v is not None else d
                ref = vw if axis == "x" else vh if axis == "y" else math.hypot(vw, vh) / math.sqrt(2)
                return length(v, 0, ref)
        full = mul(ctm, to_user)
        frame: Matrix | None = None
        g = ET.Element("gradient")
        spread = {"pad": "clamp", "repeat": "repeated", "reflect": "mirror"}[attr("spreadMethod", "pad")]
        if kind == "linearGradient":
            x1, y1 = coord(attr("x1"), "0%", "x"), coord(attr("y1"), "0%", "y")
            x2, y2 = coord(attr("x2"), "100%", "x"), coord(attr("y2"), "0%", "y")
            # Exact mapping under any affine: keep the iso-lines. In gradient space the
            # iso-lines run along d (perpendicular to v). After the transform they run along
            # A·d; the VectorDrawable vector must be perpendicular to that direction.
            q1 = apply(full, x1, y1)
            vx, vy = x2 - x1, y2 - y1
            dx, dy = apply_vec(full, -vy, vx)
            wx, wy = apply_vec(full, vx, vy)
            ln = math.hypot(dx, dy)
            if ln == 0:
                q2 = apply(full, x2, y2)
            else:
                nx, ny = dy / ln, -dx / ln
                proj = wx * nx + wy * ny
                q2 = (q1[0] + nx * proj, q1[1] + ny * proj)
            g.set(f"{{{ANDROID}}}type", "linear")
            g.set(f"{{{ANDROID}}}startX", fmt(q1[0], self.precision))
            g.set(f"{{{ANDROID}}}startY", fmt(q1[1], self.precision))
            g.set(f"{{{ANDROID}}}endX", fmt(q2[0], self.precision))
            g.set(f"{{{ANDROID}}}endY", fmt(q2[1], self.precision))
        else:
            cx, cy = coord(attr("cx"), "50%", "x"), coord(attr("cy"), "50%", "y")
            r = coord(attr("r"), "50%", "r")
            if attr("fx") is not None or attr("fy") is not None:
                warn(f"radial gradient #{gid}: focal point fx/fy is ignored")
            if is_conformal(full):
                c = apply(full, cx, cy)
                radius = r * scale_factor(full)
            elif allow_frame:
                frame = full
                c, radius = (cx, cy), r
            else:
                warn(f"radial gradient #{gid}: non-uniform transform on a stroke; drawn as a circle")
                c = apply(full, cx, cy)
                radius = r * scale_factor(full)
            g.set(f"{{{ANDROID}}}type", "radial")
            g.set(f"{{{ANDROID}}}centerX", fmt(c[0], self.precision))
            g.set(f"{{{ANDROID}}}centerY", fmt(c[1], self.precision))
            g.set(f"{{{ANDROID}}}gradientRadius", fmt(max(1e-3, radius), self.precision))
        g.set(f"{{{ANDROID}}}tileMode", spread)
        last = -1.0
        for stop in stops_el:
            if local(stop.tag) != "stop":
                continue
            st = styles(stop)
            off = st.get("offset", "0")
            off_v = float(off[:-1]) / 100 if off.endswith("%") else float(off)
            off_v = max(last, max(0.0, min(1.0, off_v)))
            last = off_v
            paint = self.r.color(st.get("stop-color", "black"))
            if paint.kind != "color":
                raise ConversionError(f"gradient #{gid}: stop colours must be literal colours")
            a = float(st.get("stop-opacity", "1")) * opacity
            item = ET.SubElement(g, "item")
            item.set(f"{{{ANDROID}}}offset", fmt(off_v, 4))
            item.set(f"{{{ANDROID}}}color", argb(paint, a))
        self.used_gradients = True
        return g, frame

    # ---- clip paths ----
    def clip_data(self, ref: str, ctm: Matrix) -> str:
        m = re.fullmatch(r"url\(\s*['\"]?#([^'\")]+)['\"]?\s*\)", ref.strip())
        if not m or m.group(1) not in self.ids:
            raise ConversionError(f"clip-path {ref!r} not found")
        cp = self.ids[m.group(1)]
        if cp.get("clipPathUnits", "userSpaceOnUse") != "userSpaceOnUse":
            raise ConversionError("clipPathUnits=objectBoundingBox is not supported")
        base = mul(ctm, parse_transform(cp.get("transform")))
        segs: list[Seg] = []
        vw, vh = self.viewport
        for child in cp.iter():
            if child is cp:
                continue
            tag = local(child.tag)
            if tag in SHAPES:
                own = parse_transform(child.get("transform"))
                segs += transform_segs(self.shape_segs(child, vw, vh), mul(base, own))
            elif tag == "g":
                if child.get("transform"):
                    raise ConversionError("transformed groups inside clipPath are not supported")
            elif tag not in IGNORED:
                raise ConversionError(f"<{tag}> inside clipPath is not supported")
        return segs_to_d(segs, self.precision)

    # ---- tree walk ----
    def walk(self, el: ET.Element, ctm: Matrix, inherited: dict[str, str], opacity: float) -> list[Node]:
        tag = local(el.tag)
        if tag in IGNORED or tag in ("linearGradient", "radialGradient", "clipPath", "stop"):
            return []
        if el.tag.startswith("{") and not el.tag.startswith(f"{{{SVG_NS}}}"):
            return []  # editor namespaces
        if tag in UNSUPPORTED:
            msg = f"<{tag}> is not supported (convert it to paths)"
            if self.lenient:
                warn(msg + "; skipped")
                return []
            raise ConversionError(msg)
        st = styles(el)
        if st.get("display") == "none":
            return []
        props = dict(inherited)
        for k in INHERITED:
            if k in st and st[k] != "inherit":
                props[k] = st[k]
        if props.get("visibility") in ("hidden", "collapse") and tag in SHAPES:
            return []
        own = parse_transform(st.get("transform"))
        m = mul(ctm, own)
        op = opacity * float(st.get("opacity", "1"))
        nodes: list[Node] = []
        if tag in ("svg", "g"):
            for child in el:
                nodes += self.walk(child, m, props, op)
        elif tag in SHAPES:
            node = self.shape_node(el, m, props, op)
            if node is not None:
                nodes.append(node)
        else:
            msg = f"<{tag}> is not supported"
            if self.lenient:
                warn(msg + "; skipped")
                return []
            raise ConversionError(msg)
        clip = st.get("clip-path")
        if clip and clip != "none" and nodes:
            group = Node("group", {}, nodes)
            group.clip = self.clip_data(clip, m)  # clip coordinates are in the element's user space
            if el.get("id"):
                group.attrs["name"] = el.get("id")
            return [group]
        if tag == "g" and el.get("id") and nodes:
            return [Node("group", {"name": el.get("id")}, nodes)]
        return nodes

    def shape_node(self, el: ET.Element, m: Matrix, props: dict, opacity: float) -> Node | None:
        vw, vh = self.viewport
        segs = self.shape_segs(el, vw, vh)
        if not segs:
            return None
        tag = local(el.tag)
        current = props.get("color")
        fill = Paint("none") if tag == "line" else self.r.color(props.get("fill", "black"), current)
        stroke = self.r.color(props.get("stroke", "none"), current)
        node = Node("path")
        frame: Matrix | None = None
        a = node.attrs
        if el.get("id"):
            a["name"] = el.get("id")
        a["pathData"] = segs_to_d(transform_segs(segs, m), self.precision)
        fill_op = float(props.get("fill-opacity", "1")) * opacity
        stroke_op = float(props.get("stroke-opacity", "1")) * opacity
        if fill.kind == "color":
            a["fillColor"] = argb(fill, fill_op)
        elif fill.kind == "ref":
            a["fillColor"] = fill.value
            if fill_op < 1:
                a["fillAlpha"] = fmt(fill_op, 3)
        elif fill.kind == "gradient":
            node.gradient_fill, frame = self.gradient(fill.gradient, segs, m, fill_op, allow_frame=True)
        dash = props.get("stroke-dasharray", "none").strip()
        if stroke.kind != "none" and dash not in ("none", "", "0"):
            msg = (f"{tag}#{el.get('id', '?')}: stroke-dasharray has no VectorDrawable equivalent; "
                   "expand the dashes or dots into separate paths")
            if not self.lenient:
                raise ConversionError(msg)
            warn(msg + "; drawn solid")
        if stroke.kind != "none":
            if stroke.kind == "color":
                a["strokeColor"] = argb(stroke, stroke_op)
            elif stroke.kind == "ref":
                a["strokeColor"] = stroke.value
                if stroke_op < 1:
                    a["strokeAlpha"] = fmt(stroke_op, 3)
            else:
                node.gradient_stroke, _ = self.gradient(stroke.gradient, segs, m, stroke_op)
            width = length(props.get("stroke-width", "1"), 1, math.hypot(vw, vh) / math.sqrt(2))
            if not is_conformal(m):
                warn(f"{tag}#{el.get('id', '?')}: stroke under a non-uniform transform uses the mean scale")
            a["strokeWidth"] = fmt(width * scale_factor(m), self.precision)
            cap = props.get("stroke-linecap", "butt")
            join = props.get("stroke-linejoin", "miter")
            if cap != "butt":
                a["strokeLineCap"] = cap
            if join != "miter":
                a["strokeLineJoin"] = {"round": "round", "bevel": "bevel"}.get(join, "miter")
            if "stroke-miterlimit" in props and props["stroke-miterlimit"] != "4":
                a["strokeMiterLimit"] = props["stroke-miterlimit"]
        if props.get("fill-rule") == "evenodd":
            a["fillType"] = "evenOdd"
        if "fillColor" not in a and "strokeColor" not in a and node.gradient_fill is None and node.gradient_stroke is None:
            return None
        if frame is not None:
            # Elliptical radial gradient: draw the path in the gradient's circular space and let
            # two nested groups apply the frame exactly (VectorDrawable has no elliptical gradient).
            if node.gradient_stroke is not None:
                warn(f"{tag}#{el.get('id', '?')}: a gradient stroke inside an elliptical gradient frame is approximate")
            to_space = mul(inverse(frame), m)
            a["pathData"] = segs_to_d(transform_segs(segs, to_space), max(self.precision, 4))
            if "strokeWidth" in a:
                a["strokeWidth"] = fmt(float(a["strokeWidth"]) / scale_factor(frame), self.precision + 1)
            phi, sx, sy, theta = decompose(frame)
            inner = Node("group", {"rotation": theta}, [node])
            outer = Node("group", {"translateX": frame[4], "translateY": frame[5], "rotation": phi,
                                   "scaleX": sx, "scaleY": sy}, [inner])
            return outer
        return node

    def convert(self, size: tuple[float, float] | None, tint: str | None) -> ET.Element:
        root = self.root
        if local(root.tag) != "svg":
            raise ConversionError("the root element must be <svg>")
        vb = [float(v) for v in re.findall(_NUM, root.get("viewBox", ""))]
        w_attr, h_attr = root.get("width"), root.get("height")
        if len(vb) == 4:
            minx, miny, vw, vh = vb
        else:
            minx = miny = 0.0
            vw, vh = length(w_attr, 24), length(h_attr, 24)
        self.viewport = (vw, vh)
        if size:
            width_dp, height_dp = size
        elif w_attr and not w_attr.endswith("%") and h_attr and not h_attr.endswith("%"):
            width_dp, height_dp = length(w_attr), length(h_attr)
        else:
            width_dp, height_dp = vw, vh
        if max(width_dp, height_dp) > LINT_VECTOR_DP:
            warn(f"intrinsic size {fmt(width_dp, 2)} x {fmt(height_dp, 2)} dp is over lint's VectorRaster limit "
                 f"({LINT_VECTOR_DP} dp a side); pass --size (the viewport, and so the geometry, stays the same)")
        start = (1.0, 0.0, 0.0, 1.0, -minx, -miny)
        nodes = self.walk(root, start, {}, 1.0)
        if not nodes:
            raise ConversionError("nothing to draw")
        vec = ET.Element("vector")
        vec.set(f"{{{ANDROID}}}width", f"{fmt(width_dp, 2)}dp")
        vec.set(f"{{{ANDROID}}}height", f"{fmt(height_dp, 2)}dp")
        vec.set(f"{{{ANDROID}}}viewportWidth", fmt(vw, 3))
        vec.set(f"{{{ANDROID}}}viewportHeight", fmt(vh, 3))
        if tint:
            vec.set(f"{{{ANDROID}}}tint", tint)
        for n in nodes:
            self.emit(vec, n)
        return vec

    def emit(self, parent: ET.Element, node: Node) -> None:
        if node.tag == "group":
            g = ET.SubElement(parent, "group")
            if "name" in node.attrs:
                g.set(f"{{{ANDROID}}}name", sanitize(node.attrs["name"]))
            for key in ("rotation", "scaleX", "scaleY", "translateX", "translateY"):
                if key in node.attrs:
                    default = 1.0 if key.startswith("scale") else 0.0
                    if abs(node.attrs[key] - default) > 1e-9:
                        g.set(f"{{{ANDROID}}}{key}", fmt(node.attrs[key], 6))
            if node.clip:
                cp = ET.SubElement(g, "clip-path")
                cp.set(f"{{{ANDROID}}}pathData", node.clip)
            for child in node.children:
                self.emit(g, child)
            return
        p = ET.SubElement(parent, "path")
        order = ("name", "pathData", "fillColor", "fillAlpha", "fillType", "strokeColor", "strokeAlpha",
                 "strokeWidth", "strokeLineCap", "strokeLineJoin", "strokeMiterLimit")
        for k in order:
            if k in node.attrs:
                v = sanitize(node.attrs[k]) if k == "name" else node.attrs[k]
                p.set(f"{{{ANDROID}}}{k}", v)
        for which, grad in (("fillColor", node.gradient_fill), ("strokeColor", node.gradient_stroke)):
            if grad is not None:
                holder = ET.SubElement(p, f"{{{AAPT}}}attr")
                holder.set("name", f"android:{which}")
                holder.append(grad)


def sanitize(name: str) -> str:
    s = re.sub(r"[^A-Za-z0-9_]", "_", name)
    return s if not s[:1].isdigit() else "_" + s


def _qname(tag: str) -> str:
    if tag.startswith(f"{{{ANDROID}}}"):
        return "android:" + tag.split("}", 1)[1]
    if tag.startswith(f"{{{AAPT}}}"):
        return "aapt:" + tag.split("}", 1)[1]
    return tag


def _write(el: ET.Element, depth: int, out: list[str], root_ns: str = "") -> None:
    pad = "    " * depth
    attrs = [f'{_qname(k)}={quoteattr(v)}' for k, v in el.attrib.items()]
    if root_ns:
        attrs = [root_ns] + attrs
    head = f"{pad}<{_qname(el.tag)}"
    children = list(el)
    if len(attrs) <= 1 and not root_ns:
        line = head + ("" if not attrs else " " + attrs[0])
    else:
        line = head + "".join(f"\n{pad}    {x}" for x in attrs)
    if children:
        out.append(line + ">")
        for c in children:
            _write(c, depth + 1, out)
        out.append(f"{pad}</{_qname(el.tag)}>")
    else:
        out.append(line + " />")


def _box(d: str, pad: float) -> tuple[float, float, float, float]:
    v = [float(x) for x in re.findall(_NUM, d)]
    xs, ys = v[0::2], v[1::2]
    return min(xs) - pad, min(ys) - pad, max(xs) + pad, max(ys) + pad


def _overlaps(b, boxes) -> bool:
    return any(b[0] < o[2] and o[0] < b[2] and b[1] < o[3] and o[1] < b[3] for o in boxes)


# Android lint's VectorPath check (lint-checks 32.4.1, the version AGP 9.4.1 runs) reports any pathData whose
# length is 3000 or more when minSdk >= 21, and the app runs lint with warningsAsErrors. Lint counts every
# character except that a run of whitespace counts once (VectorPathDetector.countPathLength).
LINT_PATH_LIMIT = 3000
# Its VectorRaster check (VectorDetector) reports a <vector> whose android:width or android:height is over 200 dp.
# The intrinsic size only sets the default bounds: a vector is drawn at the size it is given, so a large picture
# keeps its viewport and declares a smaller intrinsic size (--size).
LINT_VECTOR_DP = 200


def lint_path_length(d: str) -> int:
    """The length lint's VectorPath check measures: a run of whitespace counts as one character."""
    n, prev_ws = 0, False
    for ch in d:
        ws = ch.isspace()
        if not ws or not prev_ws:
            n += 1
        prev_ws = ws
    return n


def _subpaths(d: str) -> list[str]:
    """Split absolute path data (M/L/C/Z only, as written by segs_to_d) at each moveto."""
    return [part for part in re.split(r"(?=M)", d) if part]


def split_long_paths(parent: ET.Element, limit: int) -> None:
    """Split any path whose data reaches `limit` into consecutive paths with the same paint.

    The pieces are whole subpaths, so the geometry is untouched. Splitting is only done when it cannot
    change a pixel: an opaque stroke-only path (a union of strokes in one colour), or a path whose
    subpaths in different pieces do not overlap (no double alpha, no winding interaction between them).
    Otherwise the conversion fails and the master must be split by hand.
    """
    data_key = f"{{{ANDROID}}}pathData"
    for c in list(parent):
        if c.tag == "group":
            split_long_paths(c, limit)
            continue
        d = c.get(data_key)
        if c.tag != "path" or d is None or lint_path_length(d) < limit:
            continue
        subs = _subpaths(d)
        pieces: list[list[str]] = [[]]
        for sub in subs:
            if lint_path_length(sub) >= limit:
                raise ConversionError(f"a single subpath of {lint_path_length(sub)} characters reaches lint's "
                                      f"VectorPath limit ({limit}); simplify it in the SVG master")
            if pieces[-1] and lint_path_length("".join(pieces[-1]) + sub) >= limit:
                pieces.append([])
            pieces[-1].append(sub)
        stroke_only = f"{{{ANDROID}}}fillColor" not in c.attrib and not list(c)
        opaque = all(not v.startswith("#") or len(v) != 9 or v[1:3] == "FF"
                     for k, v in c.attrib.items() if k.endswith("Color")) and not any(
            k.endswith("Alpha") for k in c.attrib)
        if not (stroke_only and opaque):
            pad = float(c.get(f"{{{ANDROID}}}strokeWidth", "0")) / 2
            boxes = [[_box(sub, pad) for sub in piece] for piece in pieces]
            for i, a in enumerate(boxes):
                for b in boxes[i + 1:]:
                    if any(_overlaps(box, b) for box in a):
                        raise ConversionError(f"a path of {lint_path_length(d)} characters reaches lint's "
                                              f"VectorPath limit ({limit}) and its parts overlap, so splitting "
                                              "could change the picture; split it in the SVG master")
        if c.get(f"{{{ANDROID}}}name"):
            raise ConversionError(f"named path {c.get(f'{{{ANDROID}}}name')!r} reaches lint's VectorPath limit")
        index = list(parent).index(c)
        parent.remove(c)
        for k, piece in enumerate(pieces):
            twin = ET.Element(c.tag, dict(c.attrib))
            twin.set(data_key, "".join(piece))
            for child in c:
                twin.append(child)
            parent.insert(index + k, twin)


def merge_paths(parent: ET.Element, limit: int = LINT_PATH_LIMIT) -> None:
    """Join adjacent paths that share every attribute except pathData (fewer nodes to draw).

    A merge never makes a path reach `limit` (lint's VectorPath length, see LINT_PATH_LIMIT).

    Opaque stroke-only paths always merge. Filled or translucent ones merge only when their boxes
    do not overlap: overlapping translucent shapes would lose their double-alpha look, and a merged
    filled path paints every fill before every stroke (and nonzero winding can open holes).
    """
    kids = list(parent)
    merged: list[ET.Element] = []
    boxes: dict[int, list] = {}
    name_key = f"{{{ANDROID}}}name"
    data_key = f"{{{ANDROID}}}pathData"

    def key(e):
        return {k: v for k, v in e.attrib.items() if k != data_key}

    def opaque(e):
        return all(not v.startswith("#") or len(v) != 9 or v[1:3] == "FF"
                   for k, v in e.attrib.items() if k.endswith("Color")) and not any(
            k.endswith("Alpha") for k in e.attrib)

    for c in kids:
        if c.tag == "group":
            merge_paths(c, limit)
        prev = merged[-1] if merged else None
        mergeable = (prev is not None and c.tag == "path" and prev.tag == "path" and not list(c) and not list(prev)
                     and key(c) == key(prev) and name_key not in c.attrib and name_key not in prev.attrib
                     and (not limit or lint_path_length(prev.get(data_key) + c.get(data_key)) < limit))
        # Overlapping shapes may only merge when they are opaque strokes alone: a merged path paints all its
        # fills before all its strokes (an earlier outline would show through a later fill), nonzero winding
        # can punch holes where opposite-wound fills overlap, and translucent paint would double up.
        if mergeable and (not opaque(c) or f"{{{ANDROID}}}fillColor" in c.attrib):
            pad = float(c.get(f"{{{ANDROID}}}strokeWidth", "0")) / 2
            box = _box(c.get(data_key), pad)
            prev_boxes = boxes.setdefault(id(prev), [_box(prev.get(data_key), pad)])
            mergeable = not _overlaps(box, prev_boxes)
            if mergeable:
                prev_boxes.append(box)
        if mergeable:
            prev.set(data_key, prev.get(data_key) + c.get(data_key))
        else:
            merged.append(c)
    for c in kids:
        parent.remove(c)
    parent.extend(merged)


def serialize(vec: ET.Element, source: str, uses_aapt: bool) -> str:
    ns = f'xmlns:android="{ANDROID}"' + (f'\n    xmlns:aapt="{AAPT}"' if uses_aapt else "")
    out: list[str] = ['<?xml version="1.0" encoding="utf-8"?>',
                      f"<!-- Generated by design-src/tools/svg2vd.py from {source}. Edit the SVG, not this file. -->"]
    _write(vec, 0, out, ns)
    return "\n".join(out) + "\n"


def load_colors(args) -> dict[str, str]:
    out: dict[str, str] = {}
    if args.colors:
        data = json.loads(Path(args.colors).read_text(encoding="utf-8"))
        for k, v in data.items():
            out[k.lstrip("-")] = v
    for item in args.color or []:
        if "=" not in item:
            raise SystemExit(f"--color expects NAME=VALUE, got {item!r}")
        k, v = item.split("=", 1)
        out[k.strip().lstrip("-")] = v.strip()
    return out


def theme_map(palette: Path, theme: str) -> dict[str, str]:
    """Light-to-<theme> hex map from palette.json's illustration tokens."""
    data = json.loads(palette.read_text(encoding="utf-8"))["illustration"]
    light, target = data["light"], data[theme]
    out: dict[str, str] = {}
    for key, hx in light.items():
        if key.startswith("_"):
            continue
        prev = out.get(hx.lower())
        if prev is not None and prev != target[key].lower():
            raise ConversionError(f"palette.json: light {hx} maps to two {theme} colours")
        out[hx.lower()] = target[key].lower()
    return out


def convert_file(src: Path, size, current, variables, tint, precision, lenient,
                 recolor: dict[str, str] | None = None, strict_palette: bool = False, merge: bool = True,
                 max_path: int = LINT_PATH_LIMIT) -> str:
    try:
        tree = ET.parse(src)
    except ET.ParseError as exc:
        raise ConversionError(f"{src}: not well-formed XML: {exc}") from exc
    resolver = Resolver(current, variables, recolor, strict_palette)
    conv = Converter(tree.getroot(), resolver, precision, lenient)
    vec = conv.convert(size, tint)
    if max_path:
        split_long_paths(vec, max_path)
    if merge:
        merge_paths(vec, max_path)
    for hx in sorted(resolver.off_palette):
        warn(f"{hx} is not an illustration token; it was kept as is in the themed output")
    return serialize(vec, src.name, conv.used_gradients)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Convert an SVG (subset) to an Android VectorDrawable.")
    ap.add_argument("svg")
    ap.add_argument("-o", "--output", help="output .xml (default: stdout)")
    ap.add_argument("--size", help="width[xheight] in dp, e.g. 24 or 108x108 (default: the SVG's width/height)")
    ap.add_argument("--current-color", default="#000000", help="value of currentColor (default #000000)")
    ap.add_argument("--color", action="append", help="resolve var(--NAME): NAME=#rrggbb or NAME=?attr/x (repeatable)")
    ap.add_argument("--colors", help="JSON object {name: colour} used to resolve var(--name)")
    ap.add_argument("--tint", help="android:tint for the vector, e.g. ?attr/colorControlNormal")
    ap.add_argument("--precision", type=int, default=3, help="decimals in path data (default 3)")
    ap.add_argument("--lenient", action="store_true", help="skip unsupported elements with a warning")
    ap.add_argument("--palette", help="palette.json; with --theme, swap illustration tokens to that theme")
    ap.add_argument("--theme", choices=("light", "dark"), help="target theme for --palette (light keeps colours)")
    ap.add_argument("--strict-palette", action="store_true", help="fail on colours that are not illustration tokens")
    ap.add_argument("--no-merge", action="store_true", help="keep one <path> per SVG shape")
    ap.add_argument("--max-path", type=int, default=LINT_PATH_LIMIT,
                    help=f"keep every pathData under this lint VectorPath length (default {LINT_PATH_LIMIT}; 0: no cap)")
    args = ap.parse_args(argv)
    size = None
    if args.size:
        parts = args.size.lower().split("x")
        size = (float(parts[0]), float(parts[-1]))
    try:
        recolor = None
        if args.palette and args.theme:
            recolor = theme_map(Path(args.palette), args.theme)
        xml = convert_file(Path(args.svg), size, args.current_color, load_colors(args), args.tint,
                           args.precision, args.lenient, recolor, args.strict_palette, not args.no_merge,
                           args.max_path)
    except ConversionError as exc:
        print(f"svg2vd: error: {exc}", file=sys.stderr)
        return 1
    if args.output:
        Path(args.output).write_text(xml, encoding="utf-8")
    else:
        sys.stdout.write(xml)
    return 0


if __name__ == "__main__":
    sys.exit(main())
