/** The four phenomenon families and their REDUNDANT encodings.
 *
 *  Color alone never identifies a family. Each one also has an icon, an underline
 *  style and a text label; pattern emphasis adds a fourth cue (an icon after each
 *  marked word, thicker strokes, hatched chart fills). Labels come from
 *  /api/reference; only the visual vocabulary lives here. */

import {
  ChevronsDown,
  CircleDashed,
  Link2,
  Merge,
  Scissors,
  Shrink,
  type LucideIcon,
  type LucideProps,
} from "lucide-react";

import { cn } from "./cn";

export type FamilyKey = "reduction" | "td" | "assimilation" | "boundary";
/** The four families, plus two marks without a hue: the lexical contraction ("lexical":
 *  ink, dotted, Shrink) and any other phenomenon outside the families ("none": e.g. an
 *  elided word; ink, dash-dot, CircleDashed), so neither borrows the other's meaning. */
export type MarkKey = FamilyKey | "lexical" | "none";

export const FAMILY_KEYS: readonly FamilyKey[] = ["reduction", "td", "assimilation", "boundary"];

export interface FamilyVisual {
  icon: LucideIcon;
  /** Underline style name, for legends and docs. */
  underline: "solid" | "dashed" | "double" | "wavy" | "dotted" | "dash-dot";
  /** What the icon means, in a few words (used in legends and tooltips). */
  iconMeaning: string;
  /** Chart texture with pattern emphasis on. */
  texture: "solid" | "lines-45" | "dots" | "lines-135" | "none";
  /** Label when the reference has none (it always has one in practice). */
  fallbackLabel: string;
}

export const FAMILY_VISUALS: Record<MarkKey, FamilyVisual> = {
  reduction: {
    icon: ChevronsDown,
    underline: "solid",
    iconMeaning: "the sound weakens",
    texture: "solid",
    fallbackLabel: "Reduction",
  },
  td: {
    icon: Scissors,
    underline: "dashed",
    iconMeaning: "the stop is clipped",
    texture: "lines-45",
    fallbackLabel: "t/d processes",
  },
  assimilation: {
    icon: Merge,
    underline: "double",
    iconMeaning: "two sounds blend",
    texture: "dots",
    fallbackLabel: "Assimilation",
  },
  boundary: {
    icon: Link2,
    underline: "wavy",
    iconMeaning: "words run together",
    texture: "lines-135",
    fallbackLabel: "Word boundary",
  },
  lexical: {
    icon: Shrink,
    underline: "dotted",
    iconMeaning: "a reduced form with its own spelling",
    texture: "none",
    fallbackLabel: "Lexical contraction",
  },
  none: {
    icon: CircleDashed,
    underline: "dash-dot",
    iconMeaning: "a change outside the four families",
    texture: "none",
    fallbackLabel: "Other change",
  },
};

export function isFamilyKey(value: string | null | undefined): value is FamilyKey {
  return value === "reduction" || value === "td" || value === "assimilation" || value === "boundary";
}

/** The mark for a family key (or an explicit "lexical" / "none"). Anything else — no
 *  family, an unknown one — is the neutral "none": the lexical contraction's mark is
 *  only ever given on purpose, never as a fallback. */
export function markOf(family: string | null | undefined): MarkKey {
  if (isFamilyKey(family)) return family;
  return family === "lexical" ? "lexical" : "none";
}

/** The mark of a phenomenon: its family's; the lexical contraction's own; the neutral
 *  one for anything else outside the families (word_elision). Use this, not
 *  `family_of[name]`, whenever a phenomenon is drawn. */
export function markOfPhenomenon(name: string, familyOf: Readonly<Record<string, string>>): MarkKey {
  const family = familyOf[name];
  if (isFamilyKey(family)) return family;
  return name === "contraction_lex" ? "lexical" : "none";
}

/** Classes that underline a word in its family's style and color (text stays ink). */
export function familyUnderlineClass(family: string | null | undefined): string {
  if (!family) return "";
  return cn("fam-u", `fam-u-${markOf(family)}`);
}

/** The family color as a CSS value (for SVG strokes, swatches, borders). The marks
 *  without a family are ink (lexical) and muted ink (none). */
export const familyColor = (family: string): string =>
  isFamilyKey(family) ? `var(--fam-${family})` : family === "none" ? "var(--ink-muted)" : "var(--ink)";

/** The family tint (ink text on it stays ≥ 7:1). */
export const familyTint = (family: string): string =>
  isFamilyKey(family) ? `var(--fam-${family}-tint)` : "var(--surface-2)";

/** Chart fill class: solid family color, hatched with pattern emphasis on. */
export const familyFillClass = (family: string): string =>
  isFamilyKey(family) ? `fam-fill-${family}` : "";

interface FamilyIconProps extends Omit<LucideProps, "ref"> {
  family: string | null | undefined;
  /** Accessible name; without it the icon is decorative (aria-hidden). */
  label?: string;
  /** Paint the icon in the family color (default) or in ink. */
  tone?: "family" | "ink";
}

export function FamilyIcon({ family, label, tone = "family", size = 16, className, ...rest }: FamilyIconProps) {
  const mark = markOf(family);
  const Icon = FAMILY_VISUALS[mark].icon;
  return (
    <Icon
      size={size}
      strokeWidth={2.25}
      className={cn("shrink-0", className)}
      style={{ color: tone === "family" ? familyColor(mark) : "var(--ink)" }}
      aria-hidden={label ? undefined : true}
      aria-label={label}
      role={label ? "img" : undefined}
      focusable="false"
      {...rest}
    />
  );
}

/** The icon that follows a marked word when pattern emphasis is on. Rendered always,
 *  displayed by CSS only with data-patterns="on" — no re-render on toggling. */
export function FamilyInlineIcon({ family, className }: { family: string; className?: string }) {
  return (
    <span className={cn("pattern-only ms-0.5 align-[-0.1em]", className)} aria-hidden="true">
      <FamilyIcon family={family} size={12} />
    </span>
  );
}

/** Legend swatch: icon + a short sample underlined in the family style. */
export function FamilySwatch({ family, className }: { family: string; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5", className)} aria-hidden="true">
      <FamilyIcon family={family} size={14} />
      <span className={cn(familyUnderlineClass(family), "text-ink-2 text-xs leading-none")}>abc</span>
    </span>
  );
}

/** SVG <pattern>s for hatched chart fills (pattern emphasis). Rendered once, in the
 *  shell; .fam-fill-* classes refer to them by id. */
export function FamilyPatternDefs() {
  const lines = (id: string, family: FamilyKey, angle: number) => (
    <pattern
      id={id}
      key={id}
      width="6"
      height="6"
      patternUnits="userSpaceOnUse"
      patternTransform={`rotate(${angle})`}
    >
      <rect width="6" height="6" style={{ fill: familyTint(family) }} />
      <line x1="0" y1="0" x2="0" y2="6" style={{ stroke: familyColor(family) }} strokeWidth="3" />
    </pattern>
  );
  return (
    <svg width="0" height="0" aria-hidden="true" focusable="false" className="absolute">
      <defs>
        {lines("pt-fam-td", "td", 45)}
        <pattern id="pt-fam-assimilation" width="6" height="6" patternUnits="userSpaceOnUse">
          <rect width="6" height="6" style={{ fill: familyTint("assimilation") }} />
          <circle cx="3" cy="3" r="1.6" style={{ fill: familyColor("assimilation") }} />
        </pattern>
        {lines("pt-fam-boundary", "boundary", 135)}
      </defs>
    </svg>
  );
}
