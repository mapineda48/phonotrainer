/** The four families as they appear in a transcript: icon + name + a sample word with
 *  the family's underline. Used by Settings (palette choice, CVD previews) and handy for
 *  any legend that wants the full encoding. */

import { useReference } from "../reference";
import { cn, FAMILY_KEYS, FamilyIcon, familyUnderlineClass } from "../ui";

const SAMPLES: Record<string, string> = {
  reduction: "does",
  td: "water",
  assimilation: "did you",
  boundary: "pick it up",
};

export function FamilyPreview({ className, compact }: { className?: string; compact?: boolean }) {
  const reference = useReference();
  const labelOf = (key: string) => reference.families.find((f) => f.key === key)?.label ?? key;
  return (
    <ul className={cn("m-0 grid list-none gap-2 p-0", compact ? "grid-cols-2" : "grid-cols-2 sm:grid-cols-4", className)}>
      {FAMILY_KEYS.map((key) => (
        <li key={key} className="flex flex-col gap-1 rounded-control bg-surface p-2 shadow-1">
          <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-ink">
            <FamilyIcon family={key} size={15} />
            {labelOf(key)}
          </span>
          <span className={cn("text-base text-ink", familyUnderlineClass(key))}>{SAMPLES[key]}</span>
        </li>
      ))}
    </ul>
  );
}
