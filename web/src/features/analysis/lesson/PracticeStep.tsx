/** Lesson step 5 — Practice: a shadowing loop. The clip plays at a slower speed, then a
 *  silence of the same length leaves room to say it, three times; then once at full
 *  speed. The loop records nothing: it only paces.
 *
 *  Below it, "Record yourself" (record/RecordPanel) records the same span on request and
 *  compares the learner's pitch with the original's. */

import { Mic, Square } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { wordSpan } from "../../../lib/analysis";
import { usePlayer } from "../../../player/PlayerProvider";
import type { Segment, Word } from "../../../types";
import { Button, ButtonRow, ProgressBar, Segmented } from "../../../ui";
import { describePhase, shadowingPlan, type ShadowPhase } from "../lib/shadowing";
import { RecordPanel, type OriginalPlayer } from "./record/RecordPanel";

type What = "word" | "phrase";

interface Props {
  word: Word;
  next: Word | null;
  segment: Segment;
  canPlay: boolean;
  /** The analysis: needed to compare a recording with it. Without it, no recording. */
  jobId?: string;
}

/** Long phrases are hard to shadow in one breath: start from the word. */
const LONG_PHRASE_S = 6;

export function PracticeStep({ word, next, segment, canPlay, jobId }: Props) {
  const player = usePlayer();
  const playerRef = useRef(player);
  playerRef.current = player;

  const [what, setWhat] = useState<What>(segment.end - segment.start <= LONG_PHRASE_S ? "phrase" : "word");
  const [slow, setSlow] = useState<"0.5" | "0.75">("0.75");
  const [phase, setPhase] = useState<{ plan: ShadowPhase[]; at: number } | null>(null);
  const timer = useRef<number | null>(null);
  const savedRate = useRef<number | null>(null);

  const span =
    what === "phrase"
      ? { start: segment.start, end: segment.end }
      : next
        ? { start: wordSpan(word).start, end: wordSpan(next).end }
        : wordSpan(word);

  const clear = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  };

  const stop = () => {
    clear();
    setPhase(null);
    const p = playerRef.current;
    p.pause();
    if (savedRate.current !== null) p.setRate(savedRate.current);
    savedRate.current = null;
  };

  const run = (plan: ShadowPhase[], at: number) => {
    if (at >= plan.length) {
      stop();
      return;
    }
    const current = plan[at];
    setPhase({ plan, at });
    const p = playerRef.current;
    if (current.kind === "listen") {
      p.setRate(current.rate);
      p.play(span);
    } else {
      p.pause();
    }
    timer.current = window.setTimeout(() => run(plan, at + 1), current.ms);
  };

  const start = () => {
    clear();
    savedRate.current = player.rate;
    setShadowStarts((n) => n + 1); // a take playing back stops
    run(shadowingPlan(span, Number(slow)), 0);
  };

  /* Record yourself: the microphone must not hear the shadowing loop, and the original
     plays at normal speed through the same player, giving the learner's speed back. */
  const [recording, setRecording] = useState(false);
  const [shadowStarts, setShadowStarts] = useState(0);
  const recordRate = useRef<number | null>(null);
  const onRecordingBusy = useCallback((busy: boolean) => {
    setRecording(busy);
    if (busy && timer.current !== null) stopRef.current();
  }, []);
  const stopRef = useRef(stop);
  stopRef.current = stop;
  const original = useMemo<OriginalPlayer>(
    () => ({
      play: (target) => {
        const p = playerRef.current;
        if (recordRate.current === null) recordRate.current = p.rate;
        p.setRate(1);
        p.play(target);
        return Math.round((target.end - target.start) * 1000);
      },
      stop: () => {
        const p = playerRef.current;
        p.pause();
        if (recordRate.current !== null) p.setRate(recordRate.current);
        recordRate.current = null;
      },
      clock: player.clock,
    }),
    [player.clock],
  );

  // a different word (or leaving the lesson) ends the session
  useEffect(() => () => clear(), []);
  useEffect(() => {
    if (phase) stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [word, what]);

  const current = phase ? phase.plan[phase.at] : null;
  const percent = phase ? Math.round((phase.at / phase.plan.length) * 100) : 0;

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-ink-2">
        Shadowing: listen, then say it in the silence that follows — copying the rhythm and the reductions, not just the
        sounds. Start slow; the last round is at full speed.
      </p>
      <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
        <Segmented
          label="Practice"
          size="sm"
          options={[
            { id: "word", label: next ? `“${word.word} ${next.word}”` : `“${word.word}”` },
            { id: "phrase", label: "the whole phrase" },
          ]}
          value={what}
          onChange={setWhat}
        />
        <Segmented
          label="Slow speed"
          size="sm"
          options={[
            { id: "0.5", label: "0.5×" },
            { id: "0.75", label: "0.75×" },
          ]}
          value={slow}
          onChange={setSlow}
        />
      </div>
      <ButtonRow>
        {phase ? (
          <Button icon={Square} onPress={stop}>
            Stop shadowing
          </Button>
        ) : (
          <Button variant="primary" icon={Mic} isDisabled={!canPlay || recording} onPress={start}>
            Start shadowing
          </Button>
        )}
      </ButtonRow>
      <div role="status" aria-live="polite" className="min-h-6 text-base font-medium text-ink">
        {current ? describePhase(current) : ""}
      </div>
      {phase && <ProgressBar label="Shadowing progress" hideLabel value={percent} valueText={`${percent} %`} />}
      {jobId && canPlay && (
        <RecordPanel
          // a different span is a different exercise: start it afresh
          key={`${span.start}:${span.end}`}
          jobId={jobId}
          span={span}
          isSingleWord={what === "word"}
          original={original}
          onBusyChange={onRecordingBusy}
          stopSignal={shadowStarts}
        />
      )}
    </div>
  );
}
