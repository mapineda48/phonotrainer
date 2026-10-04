/** What the comparison found, in the server's own words, grouped by whether the two
 *  recordings differ, are alike, or could not be measured. Icons name the topic (the
 *  ending, the peak…), never a verdict: there are no ticks, crosses or status colors. */

import { CircleHelp, Flag, Mountain, MoveVertical, Pause, Timer, type LucideIcon } from "lucide-react";
import { useId } from "react";

import type { ObservationKey, TakeObservation } from "../../../../types";
import { groupObservations } from "./recording";

const TOPIC_ICON: Record<ObservationKey, LucideIcon> = {
  ending: Flag,
  peak: Mountain,
  range: MoveVertical,
  length: Timer,
  pauses: Pause,
};

function Group({ title, items }: { title: string; items: TakeObservation[] }) {
  const id = useId();
  if (items.length === 0) return null;
  return (
    <div className="flex flex-col gap-1.5">
      <h5 id={id} className="text-sm font-semibold text-ink">
        {title}
      </h5>
      <ul aria-labelledby={id} className="flex flex-col gap-1.5">
        {items.map((item) => {
          const Icon = TOPIC_ICON[item.key] ?? CircleHelp;
          return (
            <li key={item.key} data-key={item.key} data-kind={item.kind} className="flex gap-2 text-sm text-ink">
              <Icon size={16} aria-hidden="true" className="mt-0.5 shrink-0 text-ink-2" />
              <span>{item.text}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function Observations({ observations }: { observations: TakeObservation[] }) {
  const groups = groupObservations(observations);
  return (
    <div className="flex flex-col gap-3">
      <Group title="How they differ" items={groups.differ} />
      <Group title="How they're alike" items={groups.alike} />
      <Group title="Couldn't measure" items={groups.unmeasured} />
      {groups.differ.length > 0 && (
        <p className="text-xs text-ink-muted">Try Alternate to hear the two back to back.</p>
      )}
    </div>
  );
}
