/** The melody of a phrase: how each sentence ends, against how its type usually ends
 *  (report §4: statements and wh- questions fall, yes/no questions rise), plus uptalk —
 *  a statement that ends rising. Arrows are icons AND words, never a color. */

import { MoveRight, TrendingDown, TrendingUp, type LucideIcon } from "lucide-react";

import { Explain } from "../../../didactic/Explain";
import type { Contour, IntonationUnit, UnitType } from "../../../types";
import { cn } from "../../../ui";

export const CONTOUR: Record<Contour, { icon: LucideIcon; verb: string; arrow: string }> = {
  rising: { icon: TrendingUp, verb: "rises", arrow: "↗" },
  falling: { icon: TrendingDown, verb: "falls", arrow: "↘" },
  flat: { icon: MoveRight, verb: "stays flat", arrow: "→" },
};

export const UNIT_TYPE: Record<UnitType, string> = {
  statement: "statement",
  yes_no_question: "yes/no question",
  wh_question: "wh- question",
  exclamation: "exclamation",
  incomplete: "unfinished",
};

const asContour = (value: string): Contour =>
  value === "rising" || value === "falling" ? value : "flat";

/** Should this unit get a badge? A sentence cut off by the segment end says nothing
 *  about intonation — unless it still rose like uptalk. */
export const isJudged = (unit: IntonationUnit): boolean => unit.type !== "incomplete" || unit.uptalk;

function quote(text: string, max = 36): string {
  const clean = text.trim();
  return clean.length > max ? `${clean.slice(0, max - 1).trimEnd()}…` : clean;
}

export function IntonationBadge({ unit, showText }: { unit: IntonationUnit; showText?: boolean }) {
  const actual = CONTOUR[asContour(unit.final_contour)];
  const expected = unit.expected_contour ? CONTOUR[unit.expected_contour] : null;
  const Icon = actual.icon;
  const ExpectedIcon = expected?.icon;
  return (
    <span
      data-testid="intonation-unit"
      className={cn(
        "inline-flex flex-wrap items-center gap-x-1.5 gap-y-0.5 rounded-chip px-2.5 py-0.5 text-xs text-ink",
        unit.matches_expected === false
          ? "bg-surface shadow-[inset_0_0_0_1.5px_var(--ink-2)]"
          : "bg-surface-2",
      )}
    >
      {showText && <span className="text-ink-2">“{quote(unit.text)}”</span>}{" "}
      <span className="font-medium">{UNIT_TYPE[unit.type] ?? unit.type}</span>{" "}
      <span className="inline-flex items-center gap-1">
        <Icon size={14} aria-hidden="true" />
        {actual.verb}
      </span>
      {unit.matches_expected === true && <span className="text-ink-2"> · as expected</span>}
      {unit.matches_expected === false && expected && ExpectedIcon && (
        <span className="inline-flex items-center gap-1 font-medium">
          {" "}· usually {expected.verb}
          <ExpectedIcon size={14} aria-hidden="true" />
        </span>
      )}
      {unit.uptalk && " "}
      {unit.uptalk && (
        <Explain term="uptalk">
          <span className="rounded-chip bg-ink px-1.5 font-semibold text-page">uptalk</span>
        </Explain>
      )}
    </span>
  );
}
