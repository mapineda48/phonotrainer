/** Phenomena across the corpus: one bar per phenomenon in its family color, always with
 *  the family icon and a direct label (color is never the only cue), hatched with pattern
 *  emphasis on, and a table view. Each bar is also the filter for the occurrences list. */

import { Button as AriaButton } from "react-aria-components";

import { Explain } from "../../didactic/Explain";
import { phenomenonLabel, useReference } from "../../reference";
import type { CorpusStats } from "../../types";
import { ChartFrame, cn, DataTable, familyFillClass, isFamilyKey, PhenomenonIcon } from "../../ui";
import { count } from "./ReductionSection";

interface Props {
  phenomena: CorpusStats["phenomena"];
  selected: string | null;
  onSelect: (phenomenon: string | null) => void;
}

export function PhenomenaChart({ phenomena, selected, onSelect }: Props) {
  const reference = useReference();
  if (phenomena.length === 0) return null;
  const max = Math.max(...phenomena.map((row) => row.count), 1);
  const familyName = (key: string | undefined) =>
    reference.families.find((family) => family.key === key)?.label ?? "No family";

  return (
    <ChartFrame
      level={2}
      title="Phenomena across your corpus"
      summary={
        <>
          How many times each change was found, and in how many analyses. Choose one to list
          its occurrences below; choose it again to see them all.
        </>
      }
      table={
        <DataTable
          columns={["Phenomenon", "Family", "Occurrences", "Analyses"]}
          rows={phenomena.map((row) => [
            phenomenonLabel(reference, row.phenomenon),
            familyName(reference.family_of[row.phenomenon]),
            row.count.toLocaleString("en-US"),
            row.analyses,
          ])}
        />
      }
    >
      <ul className="flex flex-col gap-[2px]" aria-label="Phenomena, most frequent first">
        {phenomena.map((row) => {
          const family = reference.family_of[row.phenomenon];
          const label = phenomenonLabel(reference, row.phenomenon);
          const on = selected === row.phenomenon;
          const width = Math.max(1, (row.count / max) * 100);
          // no family: an outlined bar, so its shape (not a hue that may collide with a
          // family under color-vision deficiency) says "outside the four families"
          const fill = isFamilyKey(family)
            ? familyFillClass(family)
            : "fill-surface-2 stroke-ink-2 [stroke-width:1.5px]";
          return (
            <li key={row.phenomenon} className="flex items-center gap-1">
              <AriaButton
                aria-pressed={on}
                aria-label={`${label}: ${row.count} occurrences in ${count(row.analyses, "analysis", "analyses")}${
                  on ? ", showing its occurrences" : ""
                }`}
                onPress={() => onSelect(on ? null : row.phenomenon)}
                className={cn(
                  // a phone puts the bar on a line of its own, under the name and the count
                  "grid min-h-10 min-w-0 flex-1 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 rounded-control px-2 py-1 text-left outline-none",
                  "sm:grid-cols-[minmax(9rem,14rem)_1fr_auto]",
                  "hover:bg-surface-2",
                  on && "bg-surface-2 shadow-[inset_0_0_0_2px_var(--ink)]",
                )}
              >
                <span className={cn("inline-flex min-w-0 items-center gap-1.5 text-sm text-ink", on && "font-semibold")}>
                  <PhenomenonIcon name={row.phenomenon} />
                  {/* wraps on a phone: there a cut name would lose what the row is */}
                  <span className="break-words sm:truncate">{label}</span>
                </span>
                <svg
                  className="h-3.5 w-full overflow-visible max-sm:col-span-2 max-sm:row-start-2"
                  aria-hidden="true"
                  focusable="false"
                >
                  {/* rounded data end, square at the baseline */}
                  <rect x="0" y="0" width={`${width}%`} height="100%" rx="4" className={fill} />
                  {isFamilyKey(family) && <rect x="0" y="0" width="4" height="100%" className={fill} />}
                </svg>
                <span
                  className="text-sm tabular-nums text-ink-2 max-sm:col-start-2 max-sm:row-start-1"
                  aria-hidden="true"
                >
                  <span className="font-semibold text-ink">{row.count.toLocaleString("en-US")}</span>
                  {" · "}
                  {count(row.analyses, "analysis", "analyses")}
                </span>
              </AriaButton>
              <Explain phenomenon={row.phenomenon} buttonLabel={`What's this: ${label}`} />
            </li>
          );
        })}
      </ul>
    </ChartFrame>
  );
}
