/** The transcript: every phrase as a card, every word a button.
 *
 *  Keyboard: Tab lands on one word (the selected one); ← / → move between words,
 *  ↑ / ↓ jump to the previous or next phrase, Home / End to the first or last word;
 *  Enter or Space opens the word's lesson and plays it. */

import { useCallback, useEffect, useRef, useState } from "react";

import type { Analysis } from "../../../types";
import { SegmentCard } from "./SegmentCard";

export interface Selection {
  segment: number;
  index: number;
}

interface Props {
  analysis: Analysis;
  selected: Selection | null;
  onSelect: (selection: Selection) => void;
  filter: ReadonlySet<string>;
  /** Search text (any case). */
  query: string;
  follow: boolean;
}

const keyOf = (s: Selection) => `${s.segment}:${s.index}`;

export function Transcript({ analysis, selected, onSelect, filter, query, follow }: Props) {
  const [focus, setFocus] = useState<Selection>(() => selected ?? { segment: 0, index: 0 });
  const box = useRef<HTMLDivElement | null>(null);

  // Tab follows the selection: after N, Tab lands on the word being studied.
  useEffect(() => {
    if (selected) setFocus(selected);
  }, [selected]);

  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;
  const select = useCallback((segment: number, index: number) => {
    setFocus({ segment, index });
    onSelectRef.current({ segment, index });
  }, []);

  const move = (to: Selection) => {
    setFocus(to);
    const node = box.current?.querySelector<HTMLElement>(`[data-word="${keyOf(to)}"]`);
    node?.focus();
    if (node && typeof node.scrollIntoView === "function") node.scrollIntoView({ block: "nearest" });
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    const at = target.dataset.word;
    if (!at || event.altKey || event.ctrlKey || event.metaKey) return;
    const [s, i] = at.split(":").map(Number);
    const segments = analysis.segments;
    let to: Selection | null = null;
    switch (event.key) {
      case "ArrowRight":
        if (i + 1 < segments[s].words.length) to = { segment: s, index: i + 1 };
        else if (s + 1 < segments.length) to = { segment: s + 1, index: 0 };
        break;
      case "ArrowLeft":
        if (i > 0) to = { segment: s, index: i - 1 };
        else if (s > 0) to = { segment: s - 1, index: segments[s - 1].words.length - 1 };
        break;
      case "ArrowDown":
        if (s + 1 < segments.length) to = { segment: s + 1, index: 0 };
        break;
      case "ArrowUp":
        if (s > 0) to = { segment: s - 1, index: 0 };
        break;
      case "Home":
        to = { segment: 0, index: 0 };
        break;
      case "End": {
        const last = segments.length - 1;
        to = { segment: last, index: segments[last].words.length - 1 };
        break;
      }
      default:
        return;
    }
    // handled here: the workspace's ←/→ (seek) must not fire as well
    event.preventDefault();
    event.stopPropagation();
    if (to && segments[to.segment]?.words[to.index]) move(to);
  };

  const needle = query.trim().toLowerCase();

  return (
    <div ref={box} onKeyDown={onKeyDown} className="flex flex-col gap-3" data-testid="transcript">
      {analysis.segments.map((segment, index) => (
        <SegmentCard
          key={`${index}-${segment.start}`}
          segment={segment}
          index={index}
          selected={selected?.segment === index ? selected.index : null}
          focusIndex={focus.segment === index ? focus.index : null}
          filter={filter}
          query={needle}
          follow={follow}
          onSelect={select}
        />
      ))}
    </div>
  );
}
