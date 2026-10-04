/** The word lesson: the heart of the workspace. Five numbered steps, top to bottom —
 *  listen, compare, why, see the mouth, practice — each one a disclosure. With Lesson
 *  guidance = Full they open by default; with Compact only their headers show. */

import { ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";

import { fmtTime } from "../../../lib/format";
import type { Segment, Word } from "../../../types";
import { Disclosure, IconButton, PhenomenonBadge } from "../../../ui";
import { reliability } from "../lib/words";
import { CompareStep, type PickedPhone } from "./CompareStep";
import { ListenStep } from "./ListenStep";
import { PracticeStep } from "./PracticeStep";
import { SeeStep, type Stream } from "./SeeStep";
import { WhyStep } from "./WhyStep";

export type StepId = "listen" | "compare" | "why" | "see" | "practice";
export type StepState = Record<StepId, boolean>;

export const stepsFor = (guidance: "full" | "compact"): StepState => ({
  listen: guidance === "full",
  compare: guidance === "full",
  why: guidance === "full",
  see: guidance === "full",
  practice: false,
});

const TITLES: Record<StepId, string> = {
  listen: "Listen",
  compare: "Compare",
  why: "Why it changes",
  see: "See the mouth",
  practice: "Practice",
};

interface Props {
  word: Word;
  /** The next word when the phenomenon crosses the boundary, else null. */
  next: Word | null;
  segment: Segment;
  segmentIndex: number;
  wordIndex: number;
  /** "Word 12 of 431". */
  position: { n: number; total: number };
  onStep: (delta: 1 | -1) => void;
  canPlay: boolean;
  narrow: boolean;
  steps: StepState;
  onStepsChange: (id: StepId, open: boolean) => void;
  /** The analysis, for recording yourself against it (step 5). */
  jobId?: string;
}

export function WordLesson({
  word,
  next,
  segment,
  segmentIndex,
  wordIndex,
  position,
  onStep,
  canPlay,
  narrow,
  steps,
  onStepsChange,
  jobId,
}: Props) {
  const [picked, setPicked] = useState<PickedPhone | null>(null);
  const [held, setHeld] = useState<number | null>(null);
  const [stream, setStream] = useState<Stream>("real");
  const status = reliability(word);

  const step = (id: StepId, number: number, body: React.ReactNode) => (
    <Disclosure
      key={id}
      step={number}
      title={TITLES[id]}
      isExpanded={steps[id]}
      onExpandedChange={(open) => onStepsChange(id, open)}
    >
      {steps[id] ? body : null}
    </Disclosure>
  );

  return (
    <article aria-labelledby="lesson-title" className="flex flex-col">
      <header className="flex flex-col gap-2 border-b border-line pb-3">
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 flex-col">
            <p className="text-xs text-ink-2">
              Word {position.n} of {position.total} · phrase {segmentIndex + 1} ·{" "}
              <span className="tabular-nums">
                {fmtTime(word.start)}–{fmtTime(word.end)}
              </span>
            </p>
            <h2 id="lesson-title" className="text-3xl font-bold text-ink">
              “{word.word}”
            </h2>
          </div>
          <div className="flex shrink-0 gap-1">
            <IconButton icon={ChevronLeft} label="Previous word (Shift+N)" variant="secondary" onPress={() => onStep(-1)} />
            <IconButton icon={ChevronRight} label="Next word (N)" variant="secondary" onPress={() => onStep(1)} />
          </div>
        </div>
        {word.phenomena.length > 0 && (
          <ul className="flex flex-wrap gap-1.5" aria-label="Changes on this word">
            {word.phenomena.map((name) => (
              <li key={name}>
                <PhenomenonBadge name={name} />
              </li>
            ))}
          </ul>
        )}
        {status !== "ok" && (
          <p className="inline-flex w-fit items-center gap-1.5 rounded-control px-2 py-0.5 text-sm text-ink outline-1 outline-dashed outline-ink-muted">
            {status === "no-phones" ? "Nothing heard here" : "Low confidence"}
          </p>
        )}
      </header>

      {step("listen", 1, <ListenStep word={word} next={next} segment={segment} canPlay={canPlay} />)}
      {step(
        "compare",
        2,
        <CompareStep
          word={word}
          next={next}
          narrow={narrow}
          canPlay={canPlay}
          picked={picked}
          onPick={(phone) => {
            setPicked(phone);
            setHeld((phone.start + phone.end) / 2);
          }}
          onSeeMouth={() => {
            if (picked) {
              setStream(picked.stream);
              setHeld((picked.start + picked.end) / 2);
            }
            onStepsChange("see", true);
          }}
        />,
      )}
      {step("why", 3, <WhyStep word={word} segment={segment} wordIndex={wordIndex} narrow={narrow} />)}
      {step(
        "see",
        4,
        <SeeStep
          word={word}
          next={next}
          segment={segment}
          canPlay={canPlay}
          stream={stream}
          onStreamChange={setStream}
          held={held}
          onHold={setHeld}
        />,
      )}
      {step(
        "practice",
        5,
        <PracticeStep word={word} next={next} segment={segment} canPlay={canPlay} jobId={jobId} />,
      )}
    </article>
  );
}
