/** Navigable transcript: the main view of an analysis.
 *
 *  Each segment subscribes to the player clock on its own, so highlighting the
 *  sounding word only re-renders that one segment.
 */

import { useEffect, useRef } from "react";

import { findActiveIndex, matchesFilter, wordSpan } from "../lib/analysis";
import { fmtTime } from "../lib/format";
import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import { useReference } from "../reference";
import type { Analysis, Segment } from "../types";
import { F0Chart } from "./F0Chart";
import { WordButton } from "./WordButton";

export interface Selection {
  segment: number;
  index: number;
}

interface Props {
  analysis: Analysis;
  selected: Selection | null;
  onSelect: (selection: Selection) => void;
  filter: ReadonlySet<string>;
  follow: boolean;
}

export function Transcript({ analysis, selected, onSelect, filter, follow }: Props) {
  return (
    <div className="scroll" data-testid="transcript">
      {analysis.segments.map((segment, index) => (
        <SegmentCard
          key={`${index}-${segment.start}`}
          segment={segment}
          index={index}
          selected={selected?.segment === index ? selected.index : null}
          onSelect={onSelect}
          filter={filter}
          follow={follow}
        />
      ))}
    </div>
  );
}

interface SegmentProps {
  segment: Segment;
  index: number;
  selected: number | null;
  onSelect: (selection: Selection) => void;
  filter: ReadonlySet<string>;
  follow: boolean;
}

const ARROW: Record<string, string> = {
  rising: "↗ rising",
  falling: "↘ falling",
  flat: "→ flat",
};

function SegmentCard({ segment, index, selected, onSelect, filter, follow }: SegmentProps) {
  const player = usePlayer();
  const reference = useReference();
  const ref = useRef<HTMLElement | null>(null);

  const playingIndex = useTimeSelector(player.clock, (time) =>
    time >= segment.start - 0.3 && time <= segment.end + 0.3
      ? findActiveIndex(segment.words, time)
      : -1,
  );
  const isActive = playingIndex >= 0;

  useEffect(() => {
    if (!follow || !isActive) return;
    const node = ref.current;
    if (node && typeof node.scrollIntoView === "function") {
      // "start": the sounding segment is pinned to the top, not merely "visible".
      node.scrollIntoView({ block: "start", behavior: "smooth" });
    }
  }, [follow, isActive]);

  const stats = segment.f0_stats;

  return (
    <section
      ref={ref}
      className={`segment ${isActive ? "segment--active" : ""}`}
      aria-label={`Segment ${index + 1}`}
    >
      <div className="segment__head">
        <button
          type="button"
          className="btn btn--ghost btn--sm num"
          title="Play this segment"
          onClick={() => player.play({ start: segment.start, end: segment.end })}
        >
          ▶ {fmtTime(segment.start)}–{fmtTime(segment.end)}
        </button>
        {stats.mean != null && (
          <span>
            F0 {stats.mean.toFixed(0)} Hz · range {stats.range?.toFixed(0)} Hz ·{" "}
            {ARROW[stats.final_contour] ?? stats.final_contour}
          </span>
        )}
      </div>

      <p className="segment__text">
        {segment.words.map((word, wordIndex) => (
          <WordButton
            key={`${wordIndex}-${word.start}`}
            word={word}
            reference={reference}
            selected={selected === wordIndex}
            playing={playingIndex === wordIndex}
            emphasis={segment.emphasis_word_idx === wordIndex}
            dimmed={!matchesFilter(word, filter)}
            onSelect={() => {
              onSelect({ segment: index, index: wordIndex });
              player.play(wordSpan(word));
            }}
          />
        ))}
      </p>

      <details className="phrase">
        <summary className="tiny muted">Phonetic transcription of the phrase</summary>
        <div className="tiny" style={{ marginTop: 4 }}>
          <div>
            <span className="phones__label">actual</span>{" "}
            <span className="ipa">[{joinIpa(segment, "realized_ipa")}]</span>
          </div>
          <div>
            <span className="phones__label">canonical</span>{" "}
            <span className="ipa">/{joinIpa(segment, "canonical_ipa")}/</span>
          </div>
        </div>
      </details>

      {isActive && <F0Chart segment={segment} width={320} />}
    </section>
  );
}

/** The whole phrase in IPA, with ‿ wherever the analysis detected linking. */
function joinIpa(segment: Segment, field: "realized_ipa" | "canonical_ipa"): string {
  return segment.words
    .map((word, index) => {
      const ipa = word[field] || "∅";
      const last = index === segment.words.length - 1;
      return last ? ipa : ipa + (word.boundary_link_next ? "‿" : " ");
    })
    .join("");
}
