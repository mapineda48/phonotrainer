/** Display settings: what they are, how they persist, and how they reach the page.
 *
 *  They live in localStorage["phonotrainer:settings"] (versioned JSON) and are applied
 *  as data-* attributes on <html>, which tokens.css and index.css read. The same
 *  resolution runs twice: in public/theme-init.js, synchronously before the first paint
 *  (no flash of the wrong theme), and here, whenever a setting changes. A test keeps
 *  the two in step. */

export type Appearance = "system" | "light" | "dark";
export type PaletteName = "standard" | "cvd";
/** "auto" = on with the color-vision palette, off with the standard one. */
export type PatternEmphasis = "auto" | "on" | "off";
export type TextSize = "default" | "large" | "larger";
export type MotionPref = "system" | "reduce";
/** How much of each lesson step is open by default. */
export type LessonGuidance = "full" | "compact";

export interface Settings {
  appearance: Appearance;
  palette: PaletteName;
  patterns: PatternEmphasis;
  text: TextSize;
  motion: MotionPref;
  guidance: LessonGuidance;
}

export const SETTINGS_KEY = "phonotrainer:settings";
export const SETTINGS_VERSION = 1;

export const DEFAULT_SETTINGS: Settings = {
  appearance: "system",
  palette: "standard",
  patterns: "auto",
  text: "default",
  motion: "system",
  guidance: "full",
};

const ALLOWED: { [K in keyof Settings]: readonly Settings[K][] } = {
  appearance: ["system", "light", "dark"],
  palette: ["standard", "cvd"],
  patterns: ["auto", "on", "off"],
  text: ["default", "large", "larger"],
  motion: ["system", "reduce"],
  guidance: ["full", "compact"],
};

/** Whatever was stored, coerced field by field: an unknown value falls back to its
 *  default instead of discarding the rest. */
export function parseSettings(raw: unknown): Settings {
  const source = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const out = { ...DEFAULT_SETTINGS };
  for (const key of Object.keys(ALLOWED) as (keyof Settings)[]) {
    const value = source[key];
    if ((ALLOWED[key] as readonly unknown[]).includes(value)) {
      (out as Record<string, unknown>)[key] = value;
    }
  }
  return out;
}

export function loadSettings(storage: Storage | null = safeStorage()): Settings {
  if (!storage) return { ...DEFAULT_SETTINGS };
  try {
    const text = storage.getItem(SETTINGS_KEY);
    return text ? parseSettings(JSON.parse(text)) : { ...DEFAULT_SETTINGS };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

export function saveSettings(settings: Settings, storage: Storage | null = safeStorage()): void {
  try {
    storage?.setItem(SETTINGS_KEY, JSON.stringify({ v: SETTINGS_VERSION, ...settings }));
  } catch {
    /* storage blocked: the setting lasts as long as the tab */
  }
}

/** The attributes <html> carries for these settings. */
export function resolveAttributes(settings: Settings, prefersDark: boolean): Record<string, string> {
  const dark = settings.appearance === "dark" || (settings.appearance === "system" && prefersDark);
  const patterns =
    settings.patterns === "auto" ? (settings.palette === "cvd" ? "on" : "off") : settings.patterns;
  return {
    "data-theme": dark ? "dark" : "light",
    "data-palette": settings.palette,
    "data-patterns": patterns,
    "data-text": settings.text,
    "data-motion": settings.motion,
  };
}

export function applyAttributes(root: HTMLElement, attributes: Record<string, string>): void {
  for (const [name, value] of Object.entries(attributes)) root.setAttribute(name, value);
}

export const DARK_QUERY = "(prefers-color-scheme: dark)";

export function systemPrefersDark(): boolean {
  try {
    return typeof window.matchMedia === "function" && window.matchMedia(DARK_QUERY).matches;
  } catch {
    return false;
  }
}

function safeStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}
