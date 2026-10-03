/** Colours of the vocal tract, read from the stylesheet.
 *
 *  They are NOT the four categorical family colours: on the rest of this
 *  screen a colour means "phenomenon family", and an anatomy that borrowed one
 *  would be claiming a meaning it does not have. The tract is drawn in values
 *  (tissue, bone, tongue) plus a single accent for wherever the tract is
 *  closed, and `styles.css` defines them for both themes.
 *
 *  There is no colour for the air: the canvas is transparent and what shows
 *  through is the panel's own background (`--tract-air`), so the cavity is
 *  simply where nothing was drawn.
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
}

const FALLBACK: Palette = {
  tissue: "#ded9cc",
  bone: "#fbfaf6",
  tongue: "#c4705c",
  tongueDeep: "#8e4433",
  lip: "#d6a292",
  line: "#6f6d68",
  flow: "#7f9bb3",
  accent: "#0b0b0b",
};

const VARIABLES: Record<keyof Palette, string> = {
  tissue: "--tract-tissue",
  bone: "--tract-bone",
  tongue: "--tract-tongue",
  tongueDeep: "--tract-tongue-deep",
  lip: "--tract-lip",
  line: "--tract-line",
  flow: "--tract-flow",
  accent: "--tract-accent",
};

/** The palette in force for `element`. Falls back entry by entry, so a
 *  stylesheet that has not been loaded yet still gives a legible drawing. */
export function readPalette(element: Element | null): Palette {
  if (!element || typeof window === "undefined" || !window.getComputedStyle) return FALLBACK;
  const styles = window.getComputedStyle(element);
  const out = { ...FALLBACK };
  for (const key of Object.keys(VARIABLES) as (keyof Palette)[]) {
    const value = styles.getPropertyValue(VARIABLES[key]).trim();
    if (value) out[key] = value;
  }
  return out;
}
