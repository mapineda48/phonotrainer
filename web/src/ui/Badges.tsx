/** Phenomenon and practice badges. A phenomenon is ALWAYS shown as icon + name (+ the
 *  family color as a third cue); the practice advice as an icon + words, in ink. */

import { Ear, Mic } from "lucide-react";

import { phenomenonLabel, phenomenonPractice, useReference } from "../reference";
import type { Practice } from "../types";
import { cn } from "./cn";
import { FamilyIcon, familyTint, isFamilyKey, markOfPhenomenon } from "./families";

export const PRACTICE_TEXT: Record<Practice["practice"], string> = {
  produce: "Safe to produce",
  understand: "Recognize only",
};

export const REGISTER_TEXT: Record<Practice["register"], string> = {
  universal: "universal",
  casual: "casual",
  marked: "marked",
};

interface PracticeBadgeProps {
  practice: Practice | null | undefined;
  /** Also show the register ("universal", "casual", "marked"). */
  showRegister?: boolean;
  size?: "sm" | "md";
  className?: string;
}

/** 🎤 Safe to produce / 👂 Recognize only — the report's advice for a phenomenon. */
export function PracticeBadge({ practice, showRegister, size = "sm", className }: PracticeBadgeProps) {
  if (!practice) return null;
  const Icon = practice.practice === "produce" ? Mic : Ear;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-chip bg-surface-2 text-ink",
        size === "sm" ? "min-h-6 px-2 text-xs" : "min-h-7 px-2.5 text-sm",
        className,
      )}
    >
      <Icon size={size === "sm" ? 13 : 15} aria-hidden="true" />
      <span className="font-medium">{PRACTICE_TEXT[practice.practice]}</span>
      {showRegister && (
        <span className="text-ink-2">
          <span aria-hidden="true">· </span>
          <span className="sr-only">, register: </span>
          {REGISTER_TEXT[practice.register]}
        </span>
      )}
    </span>
  );
}

/** The mark of one phenomenon: its family icon, the lexical contraction's Shrink, or the
 *  neutral CircleDashed for a phenomenon outside the families (e.g. word_elision). The
 *  one place a phenomenon's icon is decided, so every screen draws it the same way. */
export function PhenomenonIcon({
  name,
  size = 16,
  label,
  className,
}: {
  name: string;
  size?: number;
  /** Accessible name; without it the icon is decorative. */
  label?: string;
  className?: string;
}) {
  const reference = useReference();
  return <FamilyIcon family={markOfPhenomenon(name, reference.family_of)} size={size} label={label} className={className} />;
}

interface PhenomenonBadgeProps {
  /** Backend phenomenon key ("flapping", "t_unreleased", "contraction_lex"…). */
  name: string;
  /** Add the practice advice after the name. */
  showPractice?: boolean;
  size?: "sm" | "md";
  /** "tint" puts the family tint behind the chip (ink text stays ≥ 7:1). */
  tone?: "outline" | "tint";
  className?: string;
}

export function PhenomenonBadge({
  name,
  showPractice,
  size = "sm",
  tone = "outline",
  className,
}: PhenomenonBadgeProps) {
  const reference = useReference();
  const family = reference.family_of[name] ?? null;
  const label = phenomenonLabel(reference, name);
  return (
    <span className={cn("inline-flex flex-wrap items-center gap-1.5", className)}>
      <span
        className={cn(
          "inline-flex items-center gap-1.5 rounded-chip font-medium text-ink",
          size === "sm" ? "min-h-7 px-2.5 text-xs" : "min-h-8 px-3 text-sm",
          tone === "outline" && "bg-surface shadow-[inset_0_0_0_1px_var(--line-strong)]",
        )}
        style={
          tone === "tint"
            ? {
                background: familyTint(family ?? ""),
                boxShadow: isFamilyKey(family) ? `inset 0 0 0 1px var(--fam-${family})` : undefined,
              }
            : undefined
        }
      >
        <PhenomenonIcon name={name} size={size === "sm" ? 14 : 16} />
        {label}
      </span>
      {showPractice && <PracticeBadge practice={phenomenonPractice(reference, name)} size={size} />}
    </span>
  );
}
