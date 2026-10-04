/** The palette gates of the redesign spec (§4.4, §9), run on tokens.css itself.
 *
 *  Same checks as $O/design/palette-check.mjs: WCAG contrast for text, marks and focus;
 *  ink legible on every family tint; and every pair of family colors kept apart under
 *  normal vision and under simulated protan, deutan and tritan vision (Machado 2009),
 *  both full (severity 1.0) and mild (0.6). Changing a color in tokens.css without
 *  passing these fails the suite. */

import {
  converter,
  filterDeficiencyDeuter,
  filterDeficiencyProt,
  filterDeficiencyTrit,
  interpolate,
  wcagContrast,
  type Color,
} from "culori";
import { describe, expect, it } from "vitest";

import tokens from "./tokens.css?raw";

const FAMILIES = ["reduction", "td", "assimilation", "boundary"] as const;
const MODES = ["light", "dark"] as const;
const PALETTES = ["standard", "cvd"] as const;
type Mode = (typeof MODES)[number];
type PaletteName = (typeof PALETTES)[number];

function declarations(body: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const match of body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) out[match[1]] = match[2];
  return out;
}

function neutrals(mode: Mode): Record<string, string> {
  const match = tokens.match(new RegExp(`/\\* @neutrals ${mode} \\*/\\s*[^{]*\\{([^}]*)\\}`));
  if (!match) throw new Error(`no @neutrals ${mode} block in tokens.css`);
  return declarations(match[1]);
}

function families(palette: PaletteName, mode: Mode): Record<string, string> {
  const match = tokens.match(new RegExp(`/\\* @palette ${palette} ${mode}[^*]*\\*/\\s*[^{]*\\{([^}]*)\\}`));
  if (!match) throw new Error(`no @palette ${palette} ${mode} block in tokens.css`);
  const decl = declarations(match[1]);
  return Object.fromEntries(FAMILIES.map((f) => [f, decl[`fam-${f}`]]));
}

const toLab = converter("oklab");
const toLch = converter("oklch");
const toRgb = converter("rgb");
const dEok = (a: Color | string, b: Color | string) => {
  const x = toLab(a)!;
  const y = toLab(b)!;
  return 100 * Math.hypot(x.l - y.l, x.a - y.a, x.b - y.b);
};
const TINT_SHARE: Record<Mode, number> = { light: 0.14, dark: 0.26 };
const tint = (family: string, surface: string, mode: Mode) =>
  toRgb(interpolate([surface, family], "oklab")(TINT_SHARE[mode]))!;
const pairs = <T,>(items: readonly T[]) => items.flatMap((a, i) => items.slice(i + 1).map((b) => [a, b] as const));
const SIMULATIONS = {
  protan: filterDeficiencyProt,
  deutan: filterDeficiencyDeuter,
  tritan: filterDeficiencyTrit,
};

describe("tokens.css parses", () => {
  it("has hex values for every tested token", () => {
    for (const mode of MODES) {
      const n = neutrals(mode);
      for (const key of ["page", "surface", "surface-2", "ink", "ink-2", "ink-muted", "focus"]) {
        expect(n[key], `${mode} --${key}`).toMatch(/^#[0-9a-f]{6}$/i);
      }
      for (const palette of PALETTES) {
        for (const family of FAMILIES) {
          expect(families(palette, mode)[family], `${palette} ${mode} ${family}`).toMatch(/^#[0-9a-f]{6}$/i);
        }
      }
    }
  });
});

describe.each(MODES)("neutrals (%s)", (mode) => {
  const n = neutrals(mode);
  const backgrounds = { page: n.page, surface: n.surface, "surface-2": n["surface-2"] };

  it.each(Object.entries(backgrounds))("text and focus are legible on %s", (_name, bg) => {
    expect(wcagContrast(n.ink, bg)).toBeGreaterThanOrEqual(7);
    expect(wcagContrast(n["ink-2"], bg)).toBeGreaterThanOrEqual(4.5);
    expect(wcagContrast(n["ink-muted"], bg)).toBeGreaterThanOrEqual(4.5);
    expect(wcagContrast(n.focus, bg)).toBeGreaterThanOrEqual(3);
  });
});

describe.each(PALETTES.flatMap((p) => MODES.map((m) => [p, m] as const)))("palette %s · %s", (palette, mode) => {
  const n = neutrals(mode);
  const fam = families(palette, mode);

  it.each(FAMILIES)("%s marks reach 3:1 and ink on its tint 7:1", (family) => {
    expect(wcagContrast(fam[family], n.page)).toBeGreaterThanOrEqual(3);
    expect(wcagContrast(fam[family], n.surface)).toBeGreaterThanOrEqual(3);
    expect(wcagContrast(n.ink, tint(fam[family], n.surface, mode))).toBeGreaterThanOrEqual(7);
  });

  it("every pair of families stays apart under normal vision (ΔEok ≥ 15)", () => {
    for (const [a, b] of pairs(FAMILIES)) {
      expect(dEok(fam[a], fam[b]), `${a}~${b}`).toBeGreaterThanOrEqual(15);
    }
  });

  const target = palette === "cvd" ? 12 : 8;
  it.each(Object.keys(SIMULATIONS) as (keyof typeof SIMULATIONS)[])(
    `every pair stays apart under %s simulation, full and mild (ΔEok ≥ ${target})`,
    (kind) => {
      for (const severity of [1, 0.6]) {
        const simulate = SIMULATIONS[kind](severity);
        for (const [a, b] of pairs(FAMILIES)) {
          expect(dEok(simulate(toRgb(fam[a])!), simulate(toRgb(fam[b])!)), `${kind} ${severity} ${a}~${b}`).toBeGreaterThanOrEqual(
            target,
          );
        }
      }
    },
  );

  it("each family is a real hue in the agreed lightness band", () => {
    const [low, high] = mode === "light" ? [0.43, 0.77] : [0.48, palette === "cvd" ? 0.82 : 0.67];
    for (const family of FAMILIES) {
      const { l, c } = toLch(fam[family])!;
      expect(c, `${family} chroma`).toBeGreaterThanOrEqual(0.1);
      expect(l, `${family} lightness`).toBeGreaterThanOrEqual(low);
      expect(l, `${family} lightness`).toBeLessThanOrEqual(high);
    }
  });
});

// The articulator's teeth sit between the lips and the tongue. In the first dark
// palette they were a mid-grey (L 0.52) between the lips (0.55) and the deep tongue
// (0.43) and barely showed. The articulator's own tests (articulation/palette.test.ts)
// keep the teeth apart from the gum; this keeps them apart from their other neighbours.
describe("the articulator's teeth", () => {
  it.each(MODES)("stand apart in lightness from the lips and the tongue (%s)", (mode) => {
    const tract = neutrals(mode);
    const lightness = (name: string) => toLab(tract[`tract-${name}`])!.l;
    for (const neighbour of ["lip", "tongue", "tongue-deep"]) {
      expect(Math.abs(lightness("bone") - lightness(neighbour)), `teeth vs ${neighbour}`).toBeGreaterThan(0.15);
    }
  });
});
