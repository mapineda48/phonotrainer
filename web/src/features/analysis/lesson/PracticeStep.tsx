/** Lesson step 5 — Practice: a shadowing loop. The clip plays at a slower speed, then a
 *  silence of the same length leaves room to say it, three times; then once at full
 *  speed. Nothing is recorded: the learner speaks, the app only paces. */

import { Mic, Square } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { wordSpan } from "../../../lib/analysis";
import { usePlayer } from "../../../player/PlayerProvider";
import type { Segment, Word } from "../../../types";
import { Button, ButtonRow, ProgressBar, Segmented } from "../../../ui";
import { describePhase, shadowingPlan, type ShadowPhase } from "../lib/shadowing";

type What = "word" | "phrase";

interface Props {
  word: Word;
  next: Word | null;
  segment: Segment;
  canPlay: boolean;
}

/** Long phrases are hard to shadow in one breath: start from the word. */
const LONG_PHRASE_S = 6;

export function PracticeStep({ word, next, segment, canPlay }: Props) {
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
    run(shadowingPlan(span, Number(slow)), 0);
  };

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
          <Button variant="primary" icon={Mic} isDisabled={!canPlay} onPress={start}>
            Start shadowing
          </Button>
        )}
      </ButtonRow>
      <div role="status" aria-live="polite" className="min-h-6 text-base font-medium text-ink">
        {current ? describePhase(current) : ""}
      </div>
      {phase && <ProgressBar label="Shadowing progress" hideLabel value={percent} valueText={`${percent} %`} />}
    </div>
  );
}
