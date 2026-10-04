/** The anatomy is told apart by lightness, never by hue alone: these gates run
 *  on the --tract-* tokens of theme/tokens.css itself, in both themes, through
 *  the same derivation the renderer uses. A token change that makes the
 *  tongue melt into the airway, or the teeth into the gum, fails here. */

import { describe, expect, it } from "vitest";

import tokens from "../theme/tokens.css?raw";
import { derivePalette, lightness, mix, parseColor, readPalette, type Palette } from "./palette";

function tractTokens(mode: "light" | "dark"): Parameters<typeof derivePalette>[0] {
  const block = tokens.match(new RegExp(`/\\* @neutrals ${mode} \\*/\\s*[^{]*\\{([^}]*)\\}`));
  if (!block) throw new Error(`no @neutrals ${mode} block in tokens.css`);
  const read = (name: string): string => {
    const found = block[1].match(new RegExp(`--tract-${name}:\\s*(#[0-9a-fA-F]{6})`));
    if (!found) throw new Error(`--tract-${name} missing from the ${mode} block`);
    return found[1];
  };
  return {
    air: read("air"),
    tissue: read("tissue"),
    bone: read("bone"),
    tongue: read("tongue"),
    tongueDeep: read("tongue-deep"),
    lip: read("lip"),
    line: read("line"),
    flow: read("flow"),
    accent: read("accent"),
  };
}

const apart = (a: string, b: string): number => Math.abs(lightness(a) - lightness(b));

describe("colour arithmetic", () => {
  it("reads the colour syntaxes a stylesheet hands back", () => {
    expect(parseColor("#fff")).toEqual([1, 1, 1]);
    expect(parseColor("#000000")).toEqual([0, 0, 0]);
    expect(parseColor("rgb(255, 0, 0)")).toEqual([1, 0, 0]);
    expect(parseColor("color(srgb 0 0.5 1)")).toEqual([0, 0.5, 1]);
    expect(parseColor("not a colour")).toBeNull();
  });

  it("mixes from one end to the other", () => {
    expect(mix("#000000", "#ffffff", 0)).toBe("#000000");
    expect(mix("#000000", "#ffffff", 1)).toBe("#ffffff");
    const middle = lightness(mix("#000000", "#ffffff", 0.5));
    expect(middle).toBeGreaterThan(0.45);
    expect(middle).toBeLessThan(0.55);
  });

  it("falls back to a legible palette without a stylesheet", () => {
    const palette = readPalette(null);
    expect(lightness(palette.tongue)).toBeLessThan(lightness(palette.cavity));
  });
});

describe.each(["light", "dark"] as const)("the %s theme", (mode) => {
  const palette: Palette = derivePalette(tractTokens(mode));

  it("knows which theme it is in", () => {
    expect(palette.light).toBe(mode === "light");
  });

  it("keeps the tongue apart from the airway around it, deep or shallow", () => {
    expect(apart(palette.tongue, palette.cavity)).toBeGreaterThan(0.15);
    expect(apart(palette.tongue, palette.cavityDeep)).toBeGreaterThan(0.15);
    expect(apart(palette.tongue, palette.tissue)).toBeGreaterThan(0.2);
  });

  it("keeps the lips apart from the open mouth", () => {
    expect(apart(palette.lip, palette.cavity)).toBeGreaterThan(0.12);
  });

  it("keeps the teeth apart from the gum and the tissue apart from the air", () => {
    expect(apart(palette.bone, palette.tissue)).toBeGreaterThan(0.1);
    expect(apart(palette.tissue, palette.cavity)).toBeGreaterThan(0.04);
  });

  it("draws outlines that stand out from the tissue they outline", () => {
    expect(apart(palette.line, palette.tissue)).toBeGreaterThan(0.3);
  });
});
