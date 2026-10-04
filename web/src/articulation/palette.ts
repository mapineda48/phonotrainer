/** Colours of the vocal tract, read from the stylesheet.
 *
 *  They are NOT the four categorical family colours: on the rest of this
 *  screen a colour means "phenomenon family", and an anatomy that borrowed one
 *  would be claiming a meaning it does not have. The tract is drawn in values
 *  (tissue, bone, tongue) plus a single accent for wherever the tract is
 *  closed, and `theme/tokens.css` defines them for both themes.
 *
 *  Only those base tokens are read. Everything else the drawing needs — the
 *  inside of the airway, cartilage, marrow, the root of a tooth — is mixed
 *  here out of them, so a theme that changes its tokens changes the whole
 *  anatomy with them, and the articulators stay apart by LIGHTNESS (and by
 *  shape and outline), never by hue alone.
 *
 *  There is no colour for the air outside the head: the canvas is transparent
 *  and what shows through is the panel's own background (`--tract-air`).
 */

export interface Palette {
  tissue: string;
  bone: string;
  tongue: string;
  tongueDeep: string;
  lip: string;
  line: string;
  flow: string;
  accent: string;
  /** Air inside the head, where you see the far wall of the airway. */
  cavity: string;
  /** The deepest part of that airway, along its middle. */
  cavityDeep: string;
  /** The lateral wall of the nose (the conchae), catching some light. */
  concha: string;
  /** Cut bone: spongy inside, with `bone` as its cortical rim. */
  marrow: string;
  /** Epiglottis and the cartilages of the larynx. */
  cartilage: string;
  /** Root of a tooth (dentine) — the crown is `bone`, the enamel. */
  dentine: string;
  /** True when the theme is light: the relief lights and shades differently. */
  light: boolean;
}

/** The tokens this module reads; the rest of `Palette` is derived from them. */
type BaseKey = "tissue" | "bone" | "tongue" | "tongueDeep" | "lip" | "line" | "flow" | "accent";

const BASE_FALLBACK: Record<BaseKey | "air", string> = {
  air: "#e7ecef",
  tissue: "#ddd4c1",
  bone: "#fdfdfb",
  tongue: "#c9755f",
  tongueDeep: "#8f4633",
  lip: "#d8a695",
  line: "#5c5a54",
  flow: "#6e8fa8",
  accent: "#1a1a1a",
};

const VARIABLES: Record<BaseKey | "air", string> = {
  air: "--tract-air",
  tissue: "--tract-tissue",
  bone: "--tract-bone",
  tongue: "--tract-tongue",
  tongueDeep: "--tract-tongue-deep",
  lip: "--tract-lip",
  line: "--tract-line",
  flow: "--tract-flow",
  accent: "--tract-accent",
};

// --- colour arithmetic -------------------------------------------------------

type Rgb = [number, number, number];

/** sRGB in 0..1 from the colour syntaxes a stylesheet hands back. */
export function parseColor(value: string): Rgb | null {
  const text = value.trim().toLowerCase();
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(text);
  if (hex) {
    const digits = hex[1].length === 3 ? hex[1].replace(/./g, "$&$&") : hex[1];
    return [0, 2, 4].map((i) => parseInt(digits.slice(i, i + 2), 16) / 255) as Rgb;
  }
  const rgb = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/.exec(text);
  if (rgb) return [Number(rgb[1]) / 255, Number(rgb[2]) / 255, Number(rgb[3]) / 255];
  const srgb = /^color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)/.exec(text);
  if (srgb) return [Number(srgb[1]), Number(srgb[2]), Number(srgb[3])];
  return null;
}

const toLinear = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const toGamma = (c: number) => (c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055);

/** OKLab, where a 50/50 mix looks halfway — mixing in sRGB muddies. */
export function toOklab([r, g, b]: Rgb): Rgb {
  const lr = toLinear(r);
  const lg = toLinear(g);
  const lb = toLinear(b);
  const l = Math.cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb);
  const m = Math.cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb);
  const s = Math.cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function fromOklab([L, a, b]: Rgb): Rgb {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const clamp = (c: number) => Math.min(1, Math.max(0, toGamma(c)));
  return [
    clamp(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    clamp(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    clamp(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ];
}

const toHex = (rgb: Rgb): string =>
  `#${rgb.map((c) => Math.round(c * 255).toString(16).padStart(2, "0")).join("")}`;

/** `a` moved towards `b` by `t` (0 = a, 1 = b), in OKLab. */
export function mix(a: string, b: string, t: number): string {
  const ca = parseColor(a);
  const cb = parseColor(b);
  if (!ca || !cb) return a;
  const la = toOklab(ca);
  const lb = toOklab(cb);
  return toHex(fromOklab([0, 1, 2].map((i) => la[i] + (lb[i] - la[i]) * t) as Rgb));
}

/** Perceptual lightness, 0..1. The articulators are told apart by this. */
export function lightness(color: string): number {
  const rgb = parseColor(color);
  return rgb ? toOklab(rgb)[0] : 0.5;
}

/** The full palette from the base tokens: every derived tone in one place. */
export function derivePalette(base: Record<BaseKey | "air", string>): Palette {
  const light = lightness(base.air) > 0.5;
  // Inside the head the air is the far wall of the airway seen in shade: a
  // step darker than the air outside, never as dark as the outline, so the
  // tongue (the subject) keeps its contrast against it in both themes.
  const cavity = mix(base.air, base.line, light ? 0.05 : 0.12);
  return {
    tissue: base.tissue,
    bone: base.bone,
    tongue: base.tongue,
    tongueDeep: base.tongueDeep,
    lip: base.lip,
    line: base.line,
    flow: base.flow,
    accent: base.accent,
    cavity,
    cavityDeep: mix(cavity, light ? base.line : "#000000", light ? 0.16 : 0.45),
    concha: mix(cavity, base.tissue, light ? 0.3 : 0.32),
    marrow: mix(base.bone, base.tissue, light ? 0.62 : 0.4),
    cartilage: mix(base.bone, base.tissue, light ? 0.7 : 0.55),
    dentine: mix(base.bone, base.tissue, light ? 0.28 : 0.2),
    light,
  };
}

export const FALLBACK_PALETTE: Palette = derivePalette(BASE_FALLBACK);

/** The palette in force for `element`. Falls back entry by entry, so a
 *  stylesheet that has not been loaded yet still gives a legible drawing. */
export function readPalette(element: Element | null): Palette {
  if (!element || typeof window === "undefined" || !window.getComputedStyle) return FALLBACK_PALETTE;
  const styles = window.getComputedStyle(element);
  const base = { ...BASE_FALLBACK };
  for (const key of Object.keys(VARIABLES) as (keyof typeof VARIABLES)[]) {
    const value = styles.getPropertyValue(VARIABLES[key]).trim();
    if (value && parseColor(value)) base[key] = value;
  }
  return derivePalette(base);
}

/** Same palette, compared by value: a theme switch that changes nothing the
 *  tract uses should not cost a redraw. */
export const samePalette = (a: Palette | null, b: Palette | null): boolean =>
  !!a && !!b && (Object.keys(a) as (keyof Palette)[]).every((key) => a[key] === b[key]);
