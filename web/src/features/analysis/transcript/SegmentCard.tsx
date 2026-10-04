/** One phrase of the transcript: its words, how its sentences end, and — on demand —
 *  its pitch, the prominence of each word and the whole phrase in IPA.
 *
 *  Each card subscribes to the player clock on its own, so highlighting the sounding
 *  word re-renders only this card. */

import { Play } from "lucide-react";
import { memo, useEffect, useMemo, useRef, useState } from "react";

import { Explain } from "../../../didactic/Explain";
import { findActiveIndex, matchesFilter } from "../../../lib/analysis";
import { fmtTime } from "../../../lib/format";
import { useTimeSelector } from "../../../player/clock";
import { usePlayer } from "../../../player/PlayerProvider";
import { useReference } from "../../../reference";
import type { Segment } from "../../../types";
import { Button, cn, Disclosure, Ipa } from "../../../ui";
import { hasNeutralMark, primaryFamily, wordAccessibleName, wordFamilies } from "../lib/words";
import { F0Chart } from "./F0Chart";
import { CONTOUR, IntonationBadge, isJudged } from "./Intonation";
import { ProminenceStrip } from "./ProminenceStrip";
import { WordToken } from "./WordToken";

export interface SegmentCardProps {
  segment: Segment;
  index: number;
  /** Index of the selected word in this segment, or null. */
  selected: number | null;
  /** Index of the word Tab lands on, when it is in this segment. */
  focusIndex: number | null;
  /** Words outside it are faded; empty = no filter. */
  filter: ReadonlySet<string>;
  /** Lower-case search text; words that do not contain it are faded. */
  query: string;
  follow: boolean;
  onSelect: (segment: number, index: number) => void;
}

/** The whole phrase in IPA, with ‿ wherever the analysis detected linking. */
export function joinIpa(segment: Segment, field: "realized_ipa" | "dict_ipa"): string {
  return segment.words
    .map((word, index) => {
      const ipa = word[field] || "∅";
      const last = index === segment.words.length - 1;
      return last ? ipa : ipa + (word.boundary_link_next ? "‿" : " ");
    })
    .join("");
}

const prefersReducedMotion = (): boolean =>
  document.documentElement.getAttribute("data-motion") === "reduce" ||
  (typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches);

function SegmentCardImpl({ segment, index, selected, focusIndex, filter, query, follow, onSelect }: SegmentCardProps) {
  const player = usePlayer();
  const reference = useReference();
  const ref = useRef<HTMLElement | null>(null);
  const [details, setDetails] = useState(false);

  const playingIndex = useTimeSelector(player.clock, (time) =>
    time >= segment.start - 0.3 && time <= segment.end + 0.3 ? findActiveIndex(segment.words, time) : -1,
  );
  const isActive = playingIndex >= 0;

  useEffect(() => {
    if (!follow || !isActive) return;
    const node = ref.current;
    if (node && typeof node.scrollIntoView === "function") {
      // "start": the sounding phrase is pinned to the top, not merely "visible"
      node.scrollIntoView({ block: "start", behavior: prefersReducedMotion() ? "auto" : "smooth" });
    }
  }, [follow, isActive]);

  const words = useMemo(
    () =>
      segment.words.map((word) => ({
        name: wordAccessibleName(word, reference),
        family: primaryFamily(word, reference),
        families: wordFamilies(word, reference),
        neutral: hasNeutralMark(word, reference),
      })),
    [segment, reference],
  );

  const units = (segment.intonation_units ?? []).filter(isJudged);
  const stats = segment.f0_stats;
  const contour = CONTOUR[stats.final_contour as keyof typeof CONTOUR];
  const ContourIcon = contour?.icon;
  const range = `${fmtTime(segment.start)}–${fmtTime(segment.end)}`;

  return (
    <section
      ref={ref}
      aria-label={`Phrase ${index + 1}`}
      data-active={isActive || undefined}
      className={cn(
        "scroll-mt-2 rounded-card bg-surface p-4 shadow-1",
        isActive && "shadow-[inset_4px_0_0_var(--ink),var(--shadow-1)]",
      )}
    >
      <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <Button
          size="sm"
          variant="quiet"
          icon={Play}
          aria-label={`Play phrase ${index + 1}, ${range}`}
          onPress={() => player.play({ start: segment.start, end: segment.end })}
          className="tabular-nums text-ink-2"
        >
          {range}
        </Button>
        {units.length > 0
          ? units.map((unit, unitIndex) => (
              <IntonationBadge key={`${unitIndex}-${unit.start}`} unit={unit} showText={units.length > 1} />
            ))
          : contour &&
            ContourIcon &&
            stats.mean != null && (
              <span className="inline-flex items-center gap-1 text-xs text-ink-2">
                <ContourIcon size={14} aria-hidden="true" /> ends: {contour.verb}
              </span>
            )}
      </div>

      <p className="text-lg leading-[2.1] text-ink">
        {segment.words.map((word, wordIndex) => (
          <WordToken
            key={`${wordIndex}-${word.start}`}
            word={word}
            segment={index}
            index={wordIndex}
            name={words[wordIndex].name}
            family={words[wordIndex].family}
            families={words[wordIndex].families}
            neutral={words[wordIndex].neutral}
            selected={selected === wordIndex}
            playing={playingIndex === wordIndex}
            emphasis={segment.emphasis_word_idx === wordIndex}
            dimmed={!matchesFilter(word, filter) || (query !== "" && !word.word.toLowerCase().includes(query))}
            focusable={focusIndex === wordIndex}
            onSelect={onSelect}
          />
        ))}
      </p>

      <Disclosure
        title={<span className="text-sm">Pitch, stress and IPA of this phrase</span>}
        defaultExpanded={false}
        isExpanded={details}
        onExpandedChange={setDetails}
        className="mt-2 border-b-0"
      >
        {details && (
          <div className="flex flex-col gap-4">
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-ink-2">
                <Explain term="what-was-said">What was said</Explain>
              </dt>
              <dd className="m-0 text-base">
                <Ipa kind="phonetic">{joinIpa(segment, "realized_ipa")}</Ipa>
              </dd>
              <dt className="text-ink-2">
                <Explain term="dictionary-form">Dictionary</Explain>
              </dt>
              <dd className="m-0 text-base">
                <Ipa kind="phonemic">{joinIpa(segment, "dict_ipa")}</Ipa>
              </dd>
              {stats.mean != null && (
                <>
                  <dt className="text-ink-2">Voice</dt>
                  <dd className="m-0" data-testid="segment-f0">
                    average pitch {stats.mean.toFixed(0)} Hz
                    {stats.range != null && <>, range {stats.range.toFixed(0)} Hz</>}
                    {stats.range_st != null && <> ({stats.range_st.toFixed(1)} semitones)</>}
                  </dd>
                </>
              )}
              {segment.rhythm && (
                <>
                  <dt className="text-ink-2">
                    <Explain term="rhythm">Rhythm</Explain>
                  </dt>
                  <dd className="m-0" data-testid="segment-rhythm">
                    nPVI {segment.rhythm.npvi.toFixed(0)}
                    {segment.rhythm.varco != null && <> · Varco {segment.rhythm.varco.toFixed(0)}</>} ·{" "}
                    {segment.rhythm.n_intervals} syllable intervals —{" "}
                    <span className="text-ink-2">approximate: from the spacing of syllables, not measured durations</span>
                  </dd>
                </>
              )}
            </dl>
            <ProminenceStrip segment={segment} />
            <F0Chart segment={segment} />
          </div>
        )}
      </Disclosure>
    </section>
  );
}

export const SegmentCard = memo(SegmentCardImpl);
