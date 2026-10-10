#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Palette gates for the PhonoTrainer Android design system ("Phonautograph").

Reads design-src/palette.json and re-runs the web client's palette gates
(web/src/theme/palette.test.ts, ported here without changing a threshold) plus the
mobile gates that the Android design adds. Exits with status 1 when any gate fails,
so a colour that breaks contrast, family separation or the hue reservation cannot be
committed unnoticed.

Colour maths follows culori 4.0.2, the library the web test uses:
- WCAG 2.x relative luminance and contrast ratio;
- OKLab / OKLCH (Ottosson), with culori's sign-preserving sRGB transfer function;
- tints are a linear OKLab mix of the sheet surface and the family colour;
- colour-vision deficiency simulation with the Machado, Oliveira and Fernandes (2009)
  matrices at severity 1.0 and 0.6, applied to gamma-encoded sRGB without clamping,
  exactly as culori's filterDeficiencyProt / Deuter / Trit do.

Gates (see android/docs/design.md, section "Contrast and palette gates"):
  G1  neutral text, status icons, focus and control outlines on every text surface
  G2  text on ink fills, on the inverse surface (snackbars) and on the opaque overlays
  G3  family marks >= 3:1 on every surface marks may sit on; ink on each tint >= 7:1;
      the tint hex in palette.json equals the OKLab mix
  G4  every pair of families >= 15 dEok under normal vision
  G5  every pair >= 8 (Standard) / 12 (colour-vision friendly) dEok under protan,
      deutan and tritan simulation, full and mild
  G6  each family is a real hue (chroma >= 0.10) inside the agreed lightness band
  G7  the articulator's teeth stand apart in lightness from lips and tongue
  G8  hue reservation (A-02): every non-family token stays near-neutral
  G9  overlays on video and posters keep text contrast over white, mid-grey and black
  G10 illustration line work and the launcher icon
  G11 the generated audio-only visual: pitch line and labels on every drum paper

Usage:
  python3 check_palette.py [path/to/palette.json] [--verbose] [--report]
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from itertools import combinations
from pathlib import Path

# --------------------------------------------------------------------------------------
# Colour maths (ported from culori 4.0.2)
# --------------------------------------------------------------------------------------

RGB = tuple[float, float, float]


def parse_hex(value: str) -> RGB:
    """'#rrggbb' -> sRGB in 0..1."""
    v = value.strip().lstrip("#")
    if len(v) == 3:
        v = "".join(c * 2 for c in v)
    if len(v) != 6:
        raise ValueError(f"not a #rrggbb colour: {value!r}")
    return tuple(int(v[i : i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]


def to_hex(rgb: RGB) -> str:
    return "#" + "".join(f"{round(min(1.0, max(0.0, c)) * 255):02x}" for c in rgb)


def _to_linear(c: float) -> float:
    a = abs(c)
    if a <= 0.04045:
        return c / 12.92
    return (math.copysign(1.0, c) if c != 0 else 1.0) * ((a + 0.055) / 1.055) ** 2.4


def _to_gamma(c: float) -> float:
    a = abs(c)
    if a > 0.0031308:
        return (math.copysign(1.0, c) if c != 0 else 1.0) * (1.055 * a ** (1 / 2.4) - 0.055)
    return c * 12.92


def luminance(rgb: RGB) -> float:
    r, g, b = (_to_linear(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: RGB, b: RGB) -> float:
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def _cbrt(x: float) -> float:
    return math.copysign(abs(x) ** (1 / 3), x)


def rgb_to_oklab(rgb: RGB) -> tuple[float, float, float]:
    r, g, b = (_to_linear(c) for c in rgb)
    l_ = _cbrt(0.412221469470763 * r + 0.5363325372617348 * g + 0.0514459932675022 * b)
    m_ = _cbrt(0.2119034958178252 * r + 0.6806995506452344 * g + 0.1073969535369406 * b)
    s_ = _cbrt(0.0883024591900564 * r + 0.2817188391361215 * g + 0.6299787016738222 * b)
    return (
        0.210454268309314 * l_ + 0.7936177747023054 * m_ - 0.0040720430116193 * s_,
        1.9779985324311684 * l_ - 2.4285922420485799 * m_ + 0.450593709617411 * s_,
        0.0259040424655478 * l_ + 0.7827717124575296 * m_ - 0.8086757549230774 * s_,
    )


def oklab_to_rgb(lab: tuple[float, float, float]) -> RGB:
    l, a, b = lab
    L = (l + 0.3963377773761749 * a + 0.2158037573099136 * b) ** 3
    M = (l - 0.1055613458156586 * a - 0.0638541728258133 * b) ** 3
    S = (l - 0.0894841775298119 * a - 1.2914855480194092 * b) ** 3
    lr = 4.0767416360759574 * L - 3.3077115392580616 * M + 0.2309699031821044 * S
    lg = -1.2684379732850317 * L + 2.6097573492876887 * M - 0.3413193760026573 * S
    lb = -0.0041960761386756 * L - 0.7034186179359362 * M + 1.7076146940746117 * S
    return (_to_gamma(lr), _to_gamma(lg), _to_gamma(lb))


def oklch(rgb: RGB) -> tuple[float, float, float]:
    l, a, b = rgb_to_oklab(rgb)
    c = math.hypot(a, b)
    h = (math.degrees(math.atan2(b, a)) + 360) % 360 if c else 0.0
    return l, c, h


def d_eok(a: RGB, b: RGB) -> float:
    x, y = rgb_to_oklab(a), rgb_to_oklab(b)
    return 100 * math.dist(x, y)


def mix_oklab(base: RGB, color: RGB, share: float) -> RGB:
    """culori interpolate([base, color], 'oklab')(share), back to sRGB (unclamped)."""
    x, y = rgb_to_oklab(base), rgb_to_oklab(color)
    return oklab_to_rgb(tuple(p + (q - p) * share for p, q in zip(x, y)))  # type: ignore[arg-type]


def over(fg: RGB, alpha: float, bg: RGB) -> RGB:
    """Source-over compositing in gamma-encoded sRGB, as Android and browsers blend."""
    return tuple(f * alpha + b * (1 - alpha) for f, b in zip(fg, bg))  # type: ignore[return-value]


# Machado 2009 matrices, severity 0.0 to 1.0 in steps of 0.1 (culori src/deficiency.js).
_PROT = [
    [1.0, 0.0, -0.0, 0.0, 1.0, 0.0, -0.0, -0.0, 1.0],
    [0.856167, 0.182038, -0.038205, 0.029342, 0.955115, 0.015544, -0.00288, -0.001563, 1.004443],
    [0.734766, 0.334872, -0.069637, 0.05184, 0.919198, 0.028963, -0.004928, -0.004209, 1.009137],
    [0.630323, 0.465641, -0.095964, 0.069181, 0.890046, 0.040773, -0.006308, -0.007724, 1.014032],
    [0.539009, 0.579343, -0.118352, 0.082546, 0.866121, 0.051332, -0.007136, -0.011959, 1.019095],
    [0.458064, 0.679578, -0.137642, 0.092785, 0.846313, 0.060902, -0.007494, -0.016807, 1.024301],
    [0.38545, 0.769005, -0.154455, 0.100526, 0.829802, 0.069673, -0.007442, -0.02219, 1.029632],
    [0.319627, 0.849633, -0.169261, 0.106241, 0.815969, 0.07779, -0.007025, -0.028051, 1.035076],
    [0.259411, 0.923008, -0.18242, 0.110296, 0.80434, 0.085364, -0.006276, -0.034346, 1.040622],
    [0.203876, 0.990338, -0.194214, 0.112975, 0.794542, 0.092483, -0.005222, -0.041043, 1.046265],
    [0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998],
]
_DEUTER = [
    [1.0, 0.0, -0.0, 0.0, 1.0, 0.0, -0.0, -0.0, 1.0],
    [0.866435, 0.177704, -0.044139, 0.049567, 0.939063, 0.01137, -0.003453, 0.007233, 0.99622],
    [0.760729, 0.319078, -0.079807, 0.090568, 0.889315, 0.020117, -0.006027, 0.013325, 0.992702],
    [0.675425, 0.43385, -0.109275, 0.125303, 0.847755, 0.026942, -0.00795, 0.018572, 0.989378],
    [0.605511, 0.52856, -0.134071, 0.155318, 0.812366, 0.032316, -0.009376, 0.023176, 0.9862],
    [0.547494, 0.607765, -0.155259, 0.181692, 0.781742, 0.036566, -0.01041, 0.027275, 0.983136],
    [0.498864, 0.674741, -0.173604, 0.205199, 0.754872, 0.039929, -0.011131, 0.030969, 0.980162],
    [0.457771, 0.731899, -0.18967, 0.226409, 0.731012, 0.042579, -0.011595, 0.034333, 0.977261],
    [0.422823, 0.781057, -0.203881, 0.245752, 0.709602, 0.044646, -0.011843, 0.037423, 0.974421],
    [0.392952, 0.82361, -0.216562, 0.263559, 0.69021, 0.046232, -0.01191, 0.040281, 0.97163],
    [0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.01182, 0.04294, 0.968881],
]
_TRIT = [
    [1.0, 0.0, -0.0, 0.0, 1.0, 0.0, -0.0, -0.0, 1.0],
    [0.92667, 0.092514, -0.019184, 0.021191, 0.964503, 0.014306, 0.008437, 0.054813, 0.93675],
    [0.89572, 0.13333, -0.02905, 0.029997, 0.9454, 0.024603, 0.013027, 0.104707, 0.882266],
    [0.905871, 0.127791, -0.033662, 0.026856, 0.941251, 0.031893, 0.01341, 0.148296, 0.838294],
    [0.948035, 0.08949, -0.037526, 0.014364, 0.946792, 0.038844, 0.010853, 0.193991, 0.795156],
    [1.017277, 0.027029, -0.044306, -0.006113, 0.958479, 0.047634, 0.006379, 0.248708, 0.744913],
    [1.104996, -0.046633, -0.058363, -0.032137, 0.971635, 0.060503, 0.001336, 0.317922, 0.680742],
    [1.193214, -0.109812, -0.083402, -0.058496, 0.97941, 0.079086, -0.002346, 0.403492, 0.598854],
    [1.257728, -0.139648, -0.118081, -0.078003, 0.975409, 0.102594, -0.003316, 0.501214, 0.502102],
    [1.278864, -0.125333, -0.153531, -0.084748, 0.957674, 0.127074, -0.000989, 0.601151, 0.399838],
    [1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.3039],
]
SIMULATIONS = {"protan": _PROT, "deutan": _DEUTER, "tritan": _TRIT}


def simulate(kind: str, severity: float, rgb: RGB) -> RGB:
    # culori: i = round(t / 0.1); its interpolation weight always rounds to 0.
    m = SIMULATIONS[kind][round(max(0.0, min(1.0, severity)) / 0.1)]
    r, g, b = rgb
    return (m[0] * r + m[1] * g + m[2] * b, m[3] * r + m[4] * g + m[5] * b, m[6] * r + m[7] * g + m[8] * b)


# --------------------------------------------------------------------------------------
# Gate runner
# --------------------------------------------------------------------------------------


class Gates:
    def __init__(self, verbose: bool) -> None:
        self.verbose = verbose
        self.failures: list[str] = []
        self.passes = 0
        self.section = ""

    def start(self, title: str) -> None:
        self.section = title
        print(f"\n== {title}")

    def check(self, ok: bool, label: str, value: str = "") -> None:
        if ok:
            self.passes += 1
            if self.verbose:
                print(f"  pass  {label} {value}".rstrip())
        else:
            self.failures.append(f"[{self.section}] {label} {value}".rstrip())
            print(f"  FAIL  {label} {value}".rstrip())

    def note(self, text: str) -> None:
        print(f"  {text}")


def load(path: Path) -> dict:
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def color_of(theme: dict, key: str) -> RGB:
    value = theme[key]
    if isinstance(value, dict):  # {"hex": ..., "alpha": ...} composited on its base surface
        base = parse_hex(theme[value.get("over", "page")])
        return over(parse_hex(value["hex"]), float(value["alpha"]), base)
    return parse_hex(value)


def run(p: dict, verbose: bool, report: bool) -> int:
    g = Gates(verbose)
    gates = p["gates"]
    themes = ("light", "dark")
    fam_keys = p["familyOrder"]
    share = p["tintShare"]

    # G1 ----------------------------------------------------------------------------
    g.start("G1 neutral text, status icons, focus and control outlines on every text surface")
    for theme in themes:
        n = p["neutrals"][theme]
        rows = []
        for surface in gates["textSurfaces"]:
            bg = color_of(n, surface)
            cells = []
            for key, minimum in gates["textMinimums"].items():
                ratio = contrast(color_of(n, key), bg)
                g.check(ratio >= minimum, f"{theme} {key} on {surface}", f"{ratio:.2f} (min {minimum})")
                cells.append(f"{key} {ratio:.2f}")
            rows.append(f"{theme:5} {surface:9} " + " · ".join(cells))
        if report:
            for row in rows:
                g.note(row)

    # G2 ----------------------------------------------------------------------------
    g.start("G2 text on ink fills and on the inverse surface")
    for theme in themes:
        n = p["neutrals"][theme]
        for fg, bg, minimum in gates["fillPairs"]:
            ratio = contrast(color_of(n, fg), color_of(n, bg))
            g.check(ratio >= minimum, f"{theme} {fg} on {bg}", f"{ratio:.2f} (min {minimum})")
            if report:
                g.note(f"{theme:5} {fg} on {bg}: {ratio:.2f}")

    # G3 to G6 --------------------------------------------------------------------------
    for palette_name, by_theme in p["families"].items():
        for theme in themes:
            n = p["neutrals"][theme]
            fam = {k: parse_hex(by_theme[theme][k]) for k in fam_keys}
            tints = p["tints"][palette_name][theme]
            label = f"{palette_name} {theme}"

            g.start(f"G3 family marks and tints ({label})")
            sheet = color_of(n, "sheet")
            for key in fam_keys:
                for surface in gates["markSurfaces"]:
                    ratio = contrast(fam[key], color_of(n, surface))
                    g.check(ratio >= 3.0, f"{key} mark on {surface}", f"{ratio:.2f} (min 3)")
                expected = mix_oklab(sheet, fam[key], share[theme])
                stored = parse_hex(tints[key])
                drift = max(abs(a - b) for a, b in zip(expected, stored)) * 255
                g.check(drift <= 1.01, f"{key} tint {tints[key]} equals the OKLab mix {to_hex(expected)}",
                        f"(drift {drift:.2f}/255)")
                ink_on_tint = contrast(color_of(n, "ink"), expected)
                g.check(ink_on_tint >= 7.0, f"ink on {key} tint", f"{ink_on_tint:.2f} (min 7)")
                if report:
                    others = ", ".join(
                        f"{s} {contrast(fam[key], color_of(n, s)):.2f}" for s in gates["markForbiddenSurfaces"]
                    )
                    own = contrast(fam[key], expected)
                    g.note(
                        f"{key:12} {by_theme[theme][key]} page {contrast(fam[key], color_of(n, 'page')):.2f} "
                        f"sheet {contrast(fam[key], sheet):.2f} | ink/tint {ink_on_tint:.2f} | "
                        f"ring on own tint {own:.2f} (decorative) | forbidden: {others}"
                    )

            g.start(f"G4 normal-vision separation ({label})")
            worst = min((d_eok(fam[a], fam[b]), f"{a}~{b}") for a, b in combinations(fam_keys, 2))
            for a, b in combinations(fam_keys, 2):
                value = d_eok(fam[a], fam[b])
                g.check(value >= gates["normalSeparation"], f"{a}~{b}", f"{value:.1f} dEok (min {gates['normalSeparation']})")
            if report:
                g.note(f"smallest pair {worst[1]} {worst[0]:.1f} dEok")

            target = gates["cvdSeparation"][palette_name]
            g.start(f"G5 simulated colour-vision deficiency, min {target} dEok ({label})")
            for kind in SIMULATIONS:
                smallest = (1e9, "")
                for severity in (1.0, 0.6):
                    for a, b in combinations(fam_keys, 2):
                        value = d_eok(simulate(kind, severity, fam[a]), simulate(kind, severity, fam[b]))
                        smallest = min(smallest, (value, f"{a}~{b}@{severity}"))
                        g.check(value >= target, f"{kind} {severity} {a}~{b}", f"{value:.1f} dEok")
                if report:
                    g.note(f"{kind:7} smallest {smallest[1]} {smallest[0]:.1f}")

            g.start(f"G6 real hue in the lightness band ({label})")
            low, high = gates["lightnessBand"][palette_name][theme]
            for key in fam_keys:
                l, c, h = oklch(fam[key])
                g.check(c >= gates["minChroma"], f"{key} chroma", f"{c:.3f} (min {gates['minChroma']})")
                g.check(low <= l <= high, f"{key} lightness", f"{l:.3f} (band {low}-{high})")
                if report:
                    g.note(f"{key:12} L {l:.3f} C {c:.3f} H {h:.0f}")

    # G7 ----------------------------------------------------------------------------
    g.start("G7 the articulator's teeth stand apart from lips and tongue (OKLab L > 0.15)")
    for theme in themes:
        tract = p["tract"][theme]
        bone = rgb_to_oklab(parse_hex(tract["bone"]))[0]
        for neighbour in ("lip", "tongue", "tongueDeep"):
            gap = abs(bone - rgb_to_oklab(parse_hex(tract[neighbour]))[0])
            g.check(gap > 0.15, f"{theme} teeth vs {neighbour}", f"{gap:.3f}")

    # G8 ----------------------------------------------------------------------------
    cap = gates["neutralChromaCap"]
    g.start(f"G8 hue reservation: non-family tokens stay near-neutral (OKLCH C <= {cap})")
    exempt = set(gates["chromaExempt"])
    for theme in themes:
        groups = {"neutrals": p["neutrals"][theme], "brand": p["brand"][theme]}
        for group, tokens in groups.items():
            for key, value in tokens.items():
                if key in exempt or key.startswith("_"):
                    continue
                hexes = value if isinstance(value, list) else [value["hex"] if isinstance(value, dict) else value]
                for i, hx in enumerate(hexes):
                    if not isinstance(hx, str) or not hx.startswith("#"):
                        continue
                    _, c, _ = oklch(parse_hex(hx))
                    name = f"{theme} {group}.{key}" + (f"[{i}]" if isinstance(value, list) else "")
                    g.check(c <= cap, name, f"C {c:.3f}")

    # G9 ----------------------------------------------------------------------------
    g.start("G9 overlays on video and posters, composited over white, mid-grey and black frames")
    frames = {name: parse_hex(hx) for name, hx in gates["videoFrames"].items()}
    for theme in themes:
        n = p["neutrals"][theme]
        for overlay in gates["overlays"]:
            fill = parse_hex(n[overlay["fill"]])
            for frame_name, frame in frames.items():
                plate = over(fill, overlay["alpha"], frame)
                for fg, minimum in overlay["text"].items():
                    ratio = contrast(color_of(n, fg), plate)
                    g.check(ratio >= minimum, f"{theme} {overlay['name']} {fg} over {frame_name}",
                            f"{ratio:.2f} (min {minimum})")
                if overlay.get("marks"):
                    for palette_name, by_theme in p["families"].items():
                        for key in fam_keys:
                            ratio = contrast(parse_hex(by_theme[theme][key]), plate)
                            g.check(ratio >= 3.0, f"{theme} {overlay['name']} {palette_name} {key} mark over {frame_name}",
                                    f"{ratio:.2f} (min 3)")

    # G10 ---------------------------------------------------------------------------
    g.start("G10 illustration line work and the launcher icon")
    for theme in themes:
        n = p["neutrals"][theme]
        b = p["brand"][theme]
        for surface in ("page", "sheet"):
            ratio = contrast(color_of(n, "ink"), color_of(n, surface))
            g.check(ratio >= 3.0, f"{theme} illustration ink line on {surface}", f"{ratio:.2f} (min 3)")
        ratio = contrast(parse_hex(b["kraft"]), color_of(n, "ink"))
        g.check(ratio >= 3.0, f"{theme} ink line on kraft fill", f"{ratio:.2f} (min 3)")
    icon = p["launcher"]
    tile, mark = parse_hex(icon["tile"]), parse_hex(icon["mark"])
    ratio = contrast(mark, tile)
    g.check(ratio >= 7.0, "launcher mark on its tile", f"{ratio:.2f} (min 7)")
    for name, hx in icon["shareSheets"].items():
        sheet_rgb = parse_hex(hx["color"])
        ratio = contrast(tile, sheet_rgb)
        g.check(ratio >= hx["min"], f"launcher tile against the {name} share sheet ({hx['color']})",
                f"{ratio:.2f} (min {hx['min']})")
    for name, hx in icon["splash"].items():
        ratio = contrast(parse_hex(hx["mark"]), parse_hex(hx["background"]))
        g.check(ratio >= 7.0, f"splash mark on the {name} window background", f"{ratio:.2f} (min 7)")

    # G11 ---------------------------------------------------------------------------
    g.start("G11 generated audio-only visual: pitch line on every drum paper, labels on the sheet strip")
    for theme in themes:
        n = p["neutrals"][theme]
        for i, hx in enumerate(p["brand"][theme]["drumPapers"]):
            paper = parse_hex(hx)
            ratio = contrast(color_of(n, "ink"), paper)
            g.check(ratio >= 7.0, f"{theme} pitch line on drum paper {i} {hx}", f"{ratio:.2f} (min 7)")
            _, c, _ = oklch(paper)
            g.check(c <= cap, f"{theme} drum paper {i} stays neutral", f"C {c:.3f}")

    # Summary -------------------------------------------------------------------------
    print()
    if g.failures:
        print(f"{len(g.failures)} FAILED, {g.passes} passed")
        for line in g.failures:
            print("  - " + line)
        return 1
    print(f"ALL GATES PASS ({g.passes} checks)")
    return 0


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("palette", nargs="?", default=str(here.parent / "palette.json"))
    parser.add_argument("--verbose", action="store_true", help="print every passing check")
    parser.add_argument("--report", action="store_true", help="print the measured values per section")
    args = parser.parse_args(argv)
    try:
        palette = load(Path(args.palette))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"cannot read {args.palette}: {exc}", file=sys.stderr)
        return 2
    try:
        return run(palette, args.verbose, args.report)
    except (KeyError, ValueError) as exc:
        print(f"palette.json is missing or has a bad value: {exc!r}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
