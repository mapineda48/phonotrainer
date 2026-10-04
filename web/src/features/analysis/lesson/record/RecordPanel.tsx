/** Record yourself and compare — the second half of the lesson's Practice step.
 *
 *  The learner records the same span they shadow; the local server measures the take
 *  exactly as it measured the clip and describes how the two differ (how each ends,
 *  where the pitch peaks, the range, the length, pauses). There is no score. The take
 *  lives in memory until the learner leaves the word; the server deletes its copy as
 *  soon as it has measured it.
 *
 *  One main button carries every state (Record → Cancel → Stop → Record again), so
 *  keyboard focus never jumps; one polite live region says what happens. */

import { ArrowRightLeft, CircleDot, Lock, Mic, Play, Square, Trash2, X } from "lucide-react";
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Toolbar } from "react-aria-components";

import { api, ApiError } from "../../../../api";
import { useHotkeys } from "../../../../hooks/useHotkeys";
import type { Clock } from "../../../../player/clock";
import type { Span } from "../../../../player/PlayerProvider";
import { useReference } from "../../../../reference";
import type { NativePitchContour, TakeComparison } from "../../../../types";
import { Button, Disclosure, Kbd, Notice, ProgressBar, Switch, ToggleButton } from "../../../../ui";
import { ContourOverlay } from "./ContourOverlay";
import { Observations } from "./Observations";
import {
  comparisonSummary,
  DEFAULT_LIMITS,
  fmtClock,
  levelPercent,
  levelWord,
  MIC_COPY,
  measureCopy,
  nativeSummary,
  privacyCopy,
  QUIET_DBFS,
  QUIET_HINT_AFTER_S,
  recordingSupport,
  takeGainDb,
  takeRange,
  type Copy,
  type Level,
} from "./recording";
import { TakeSound } from "./takeSound";
import { useRecorder, type RecordedTake } from "./useRecorder";

/** The clip, played by whoever owns the audio (the workspace player, a ClipPlayer…). */
export interface OriginalPlayer {
  /** Play the span at normal speed; returns how long it lasts, in ms. */
  play: (span: Span) => number;
  /** Stop it and give the learner's speed back. */
  stop: () => void;
  clock?: Clock;
}

interface Props {
  jobId: string;
  /** The span being shadowed: exactly what the player plays. */
  span: Span;
  /** A single word has little voicing to read a pitch shape from. */
  isSingleWord: boolean;
  original: OriginalPlayer;
  /** True while the microphone is in use (the shadowing loop must stay quiet). */
  onBusyChange?: (busy: boolean) => void;
  /** Changes when something else starts playing: our playback stops. */
  stopSignal?: number;
}

type Stage =
  | { kind: "idle" }
  | { kind: "processing"; take: TakeSound; auto: boolean }
  | { kind: "result"; take: TakeSound; result: TakeComparison; auto: boolean }
  | { kind: "error"; copy: Copy; take: TakeSound | null };

type Playing = "original" | "take" | null;

/** Pause between the end of the original and the start of recording (ms). */
const LISTEN_GAP_MS = 250;
/** Pause between the two in Alternate mode (ms), and how many rounds it plays. */
const ALTERNATE_GAP_MS = 500;
const ALTERNATE_ROUNDS = 3;
/** Keys that would start the clip while the microphone is open. */
const PLAYBACK_KEYS = new Set([" ", "p", "P", "s", "S", "l", "L"]);

/** "Play the original first" is remembered (a per-browser convenience, nothing more). */
const PLAY_FIRST_KEY = "phonotrainer:record-play-first";

function readPlayFirst(): boolean {
  try {
    return window.localStorage.getItem(PLAY_FIRST_KEY) !== "off";
  } catch {
    return true;
  }
}

function writePlayFirst(value: boolean) {
  try {
    window.localStorage.setItem(PLAY_FIRST_KEY, value ? "on" : "off");
  } catch {
    /* no storage: the switch still works for this word */
  }
}

export function RecordPanel({ jobId, span, isSingleWord, original, onBusyChange, stopSignal }: Props) {
  const reference = useReference();
  const limits = reference.recording ?? DEFAULT_LIMITS;
  const headingId = useId();
  const buttonBox = useRef<HTMLDivElement>(null);
  const support = recordingSupport();
  const privacy = privacyCopy(window.location.hostname);

  const [stage, setStageState] = useState<Stage>({ kind: "idle" });
  const stageRef = useRef(stage);
  const setStage = useCallback((next: Stage) => {
    stageRef.current = next;
    setStageState(next);
  }, []);
  const [native, setNative] = useState<NativePitchContour | null>(null);
  const [playFirst, setPlayFirst] = useState(readPlayFirst);
  const [playing, setPlaying] = useState<Playing>(null);
  const [alternating, setAlternating] = useState(false);
  const [said, setSaid] = useState("");
  const request = useRef<AbortController | null>(null);
  const timer = useRef<number | null>(null);
  const playingRef = useRef<Playing>(null);
  const originalRef = useRef(original);
  originalRef.current = original;

  const announce = useCallback((text: string) => {
    // the same sentence twice still has to be read out
    setSaid((previous) => (previous === text ? `${text}​` : text));
  }, []);

  const takeOf = (s: Stage) => (s.kind === "idle" ? null : s.take);

  /* ---- playback -------------------------------------------------------------- */

  const stopPlayback = useCallback(() => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
    if (playingRef.current === "original") originalRef.current.stop();
    takeOf(stageRef.current)?.stop();
    playingRef.current = null;
    setPlaying(null);
    setAlternating(false);
  }, []);

  const setNowPlaying = (value: Playing) => {
    playingRef.current = value;
    setPlaying(value);
  };

  const playOriginal = (then?: () => void) => {
    takeOf(stageRef.current)?.stop();
    const ms = originalRef.current.play(span);
    setNowPlaying("original");
    timer.current = window.setTimeout(() => {
      timer.current = null;
      originalRef.current.stop();
      setNowPlaying(null);
      then?.();
    }, ms + 50);
  };

  const playTake = (then?: () => void) => {
    const current = stageRef.current;
    const take = takeOf(current);
    if (!take) return;
    if (playingRef.current === "original") originalRef.current.stop();
    const result = current.kind === "result" ? current.result : null;
    const range = takeRange(result?.take ?? null, take.seconds);
    const gain = result ? takeGainDb(result.native, result.take) : 0;
    setNowPlaying("take");
    take.play(range, gain, () => {
      setNowPlaying(null);
      then?.();
    });
  };

  const toggleOriginal = () => {
    const was = playingRef.current === "original" && !alternating;
    stopPlayback();
    if (!was) playOriginal();
  };

  const toggleTake = () => {
    const was = playingRef.current === "take" && !alternating;
    stopPlayback();
    if (!was) playTake();
  };

  const toggleAlternate = () => {
    if (alternating) {
      stopPlayback();
      return;
    }
    stopPlayback();
    setAlternating(true);
    const order: ("original" | "take")[] = [];
    for (let i = 0; i < ALTERNATE_ROUNDS; i += 1) order.push("original", "take");
    const run = (index: number) => {
      if (index >= order.length) {
        setAlternating(false);
        return;
      }
      const which = order[index];
      announce(which === "original" ? "Original" : "Yours");
      const next = () => {
        timer.current = window.setTimeout(() => run(index + 1), ALTERNATE_GAP_MS);
      };
      if (which === "original") playOriginal(next);
      else playTake(next);
    };
    run(0);
  };

  useEffect(() => {
    if (stopSignal !== undefined) stopPlayback();
  }, [stopSignal, stopPlayback]);

  /* ---- recording and measuring ------------------------------------------------- */

  const measure = async (recorded: RecordedTake) => {
    const take = new TakeSound(recorded.blob, recorded.seconds);
    setStage({ kind: "processing", take, auto: recorded.auto });
    announce(
      recorded.auto
        ? `Stopped at ${Math.round(limits.max_seconds)} seconds, the longest a take can be. Measuring.`
        : "Measuring",
    );
    if (recorded.blob.size === 0 || recorded.seconds < limits.min_seconds) {
      const copy = measureCopy(recorded.blob.size === 0 ? "empty" : "too_short", "nothing was recorded", limits.max_seconds);
      setStage({ kind: "error", copy, take: recorded.blob.size ? take : null });
      announce(copy.title);
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    try {
      const result = await api.compareTake(jobId, recorded.blob, span, controller.signal);
      if (controller.signal.aborted || stageRef.current.kind !== "processing" || stageRef.current.take !== take) return;
      setStage({ kind: "result", take, result, auto: recorded.auto });
      setNative(result.native);
      const first = result.comparison.observations[0];
      announce(`Comparison ready. ${first ? first.text : ""}`.trim());
    } catch (error) {
      if (controller.signal.aborted || stageRef.current.kind !== "processing" || stageRef.current.take !== take) return;
      const copy =
        error instanceof ApiError
          ? measureCopy(error.code, error.message, limits.max_seconds)
          : measureCopy(null, error instanceof Error ? error.message : String(error), limits.max_seconds);
      setStage({ kind: "error", copy, take });
      announce(copy.title);
    } finally {
      if (request.current === controller) request.current = null;
    }
  };

  const recorder = useRecorder({
    onTake: (recorded) => void measure(recorded),
    // in the same update as the phase: what the live region says always matches what
    // is on screen, and the clip is silent before the microphone listens
    onPhase: (phase) => {
      if (phase === "recording" && playingRef.current === "original") {
        originalRef.current.stop();
        setNowPlaying(null);
      }
      if (phase === "asking") announce("Waiting for microphone permission");
      else if (phase === "listening") announce("Listen to the original");
      else if (phase === "recording") announce("Recording. Speak now.");
    },
    onProblem: (problem) => {
      setStage({ kind: "error", copy: MIC_COPY[problem], take: null });
      announce(MIC_COPY[problem].title);
    },
  });
  const busy = recorder.phase !== "idle";

  /** Forget the current take (and any measurement still running). */
  const dropTake = () => {
    request.current?.abort();
    request.current = null;
    stopPlayback();
    takeOf(stageRef.current)?.dispose();
    setStage({ kind: "idle" });
  };

  const record = () => {
    dropTake();
    void recorder.start({
      maxSeconds: limits.max_seconds,
      listenFirst: playFirst
        ? () => {
            const ms = originalRef.current.play(span);
            setNowPlaying("original");
            return ms + LISTEN_GAP_MS;
          }
        : null,
    });
  };

  const cancel = () => {
    if (playingRef.current === "original") originalRef.current.stop();
    setNowPlaying(null);
    recorder.cancel();
    announce("Recording cancelled");
  };

  const mainAction = () => {
    if (recorder.phase === "recording") recorder.stop();
    else if (busy) cancel();
    else record();
  };

  const discard = () => {
    dropTake();
    announce("Take discarded");
    buttonBox.current?.querySelector("button")?.focus();
  };

  useEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);

  // the clip of the span, before there is a take
  useEffect(() => {
    if (support !== "ok") return;
    const controller = new AbortController();
    api
      .contour(jobId, span, controller.signal)
      .then((contour) => {
        if (!controller.signal.aborted) setNative((current) => current ?? contour);
      })
      .catch(() => undefined); // no preview: recording still works
    return () => controller.abort();
  }, [jobId, span.start, span.end, support]); // eslint-disable-line react-hooks/exhaustive-deps

  // leaving the word (or the lesson) forgets everything
  useEffect(
    () => () => {
      request.current?.abort();
      if (timer.current !== null) window.clearTimeout(timer.current);
      if (playingRef.current === "original") originalRef.current.stop();
      takeOf(stageRef.current)?.dispose();
    },
    [],
  );

  // while the microphone is open, nothing may start the clip (it would be recorded)
  useEffect(() => {
    if (!busy) return;
    const block = (event: KeyboardEvent) => {
      if (!PLAYBACK_KEYS.has(event.key) || event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (event.key === " " && target && (target.tagName === "BUTTON" || target.getAttribute("role"))) return;
      if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable)) return;
      event.preventDefault();
    };
    window.addEventListener("keydown", block, true);
    return () => window.removeEventListener("keydown", block, true);
  }, [busy]);

  const hasTake = stage.kind !== "idle" && stage.take !== null;
  useHotkeys(
    {
      r: mainAction,
      R: mainAction,
      ...(busy ? { Escape: cancel } : {}),
      ...(hasTake && !busy ? { a: toggleOriginal, b: toggleTake, B: toggleTake } : {}),
      ...(stage.kind === "result" && !busy ? { A: toggleAlternate } : {}),
    },
    support === "ok",
  );

  /* ---- render ------------------------------------------------------------------ */

  if (support !== "ok") {
    return (
      <section aria-labelledby={headingId} className="flex flex-col gap-3 border-t border-line pt-4">
        <h4 id={headingId} className="text-base font-semibold text-ink">
          Record yourself
        </h4>
        <Notice title="Recording isn't available here">
          {support === "insecure"
            ? "Browsers only allow the microphone on a secure page. Open PhonoTrainer at http://127.0.0.1 or http://localhost on this computer. Shadowing above works the same."
            : "This browser doesn't let pages use the microphone. Shadowing above works the same."}
        </Notice>
      </section>
    );
  }

  const main =
    recorder.phase === "recording"
      ? { label: "Stop", icon: Square }
      : busy
        ? { label: "Cancel", icon: X }
        : stage.kind === "error"
          ? { label: "Try again", icon: Mic }
          : stage.kind === "idle"
            ? { label: "Record", icon: Mic }
            : { label: "Record again", icon: Mic };

  const takeButton = (take: TakeSound | null) =>
    take && (
      <Shortcut keys="B">
        <ToggleButton size="sm" icon={Play} isSelected={playing === "take" && !alternating} onChange={toggleTake}>
          Yours <KeyHint>B</KeyHint>
        </ToggleButton>
      </Shortcut>
    );

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 border-t border-line pt-4">
      <h4 id={headingId} className="text-base font-semibold text-ink">
        Record yourself
      </h4>
      <p className="text-sm text-ink-2">Say it, then see your pitch next to the original&rsquo;s.</p>
      <p className="flex gap-2 text-sm text-ink-2">
        <Lock size={16} aria-hidden="true" className="mt-0.5 shrink-0" />
        <span>
          <strong className="font-semibold text-ink">{privacy.strong}</strong> {privacy.rest}
        </span>
      </p>
      {isSingleWord && <Notice>Pitch shapes need a few syllables. For intonation, record the whole phrase.</Notice>}

      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <div ref={buttonBox} className="flex items-center gap-2">
          <Shortcut keys="R">
            <Button variant="primary" size="lg" icon={main.icon} onPress={mainAction}>
              {main.label} <KeyHint>R</KeyHint>
            </Button>
          </Shortcut>
          {!busy && hasTake && (
            <Button variant="quiet" size="sm" icon={Trash2} onPress={discard}>
              Discard
            </Button>
          )}
        </div>
        {!busy && (
          <Switch
            isSelected={playFirst}
            onChange={(value) => {
              writePlayFirst(value);
              setPlayFirst(value);
            }}
          >
            Play the original first
          </Switch>
        )}
      </div>

      {recorder.phase === "idle" && stage.kind === "idle" && (
        <p className="text-xs text-ink-muted">The first time, your browser will ask to use the microphone.</p>
      )}
      {recorder.phase === "asking" && (
        <p className="text-sm text-ink">Waiting for the microphone… Your browser is asking whether PhonoTrainer may use it.</p>
      )}
      {recorder.phase === "listening" && (
        <p className="text-sm text-ink">Listen… recording starts when the original ends.</p>
      )}
      {recorder.phase === "recording" && (
        <RecordingStatus
          elapsed={recorder.elapsed}
          maxSeconds={limits.max_seconds}
          level={recorder.level}
          quiet={recorder.elapsed >= QUIET_HINT_AFTER_S && recorder.level !== null && recorder.loudest < QUIET_DBFS}
          clipped={recorder.clipped}
        />
      )}

      {(stage.kind === "processing" || stage.kind === "result") && stage.auto && !busy && (
        <p className="text-sm text-ink">
          Stopped at {Math.round(limits.max_seconds)} seconds, the longest a take can be.
        </p>
      )}

      {stage.kind === "processing" && !busy && (
        <div className="flex flex-col gap-3">
          <ProgressBar label="Measuring your pitch…" value={null} />
          <div className="flex items-center gap-2">{takeButton(stage.take)}</div>
        </div>
      )}

      {stage.kind === "error" && !busy && (
        <Notice tone="caution" title={stage.copy.title}>
          <span className="flex flex-col items-start gap-2">
            <span>{stage.copy.body}</span>
            {takeButton(stage.take)}
          </span>
        </Notice>
      )}

      {stage.kind === "result" && !busy && (
        <div className="flex flex-col gap-4">
          <Toolbar aria-label="Listen to both" className="flex flex-wrap items-center gap-2">
            <Shortcut keys="A">
              <ToggleButton
                size="sm"
                icon={Play}
                isSelected={playing === "original" && !alternating}
                onChange={toggleOriginal}
              >
                Original <KeyHint>A</KeyHint>
              </ToggleButton>
            </Shortcut>
            {takeButton(stage.take)}
            <Shortcut keys="Shift+A">
              <ToggleButton size="sm" icon={ArrowRightLeft} isSelected={alternating} onChange={toggleAlternate}>
                Alternate <KeyHint>Shift+A</KeyHint>
              </ToggleButton>
            </Shortcut>
          </Toolbar>
          <ContourOverlay
            native={stage.result.native}
            result={stage.result}
            summary={comparisonSummary(stage.result.comparison.observations)}
            playing={playing}
            originalClock={original.clock}
            takeClock={stage.take.clock}
          />
          <Observations observations={stage.result.comparison.observations} />
          <Disclosure title="About this comparison" defaultExpanded={false} level={5}>
            <ul className="flex list-disc flex-col gap-1.5 pb-3 pl-5 text-sm text-ink-2">
              <li>
                Pitch is shown in <strong className="text-ink">semitones from each voice&rsquo;s own middle</strong>, so
                a low voice and a high voice can be compared: what matters is the shape, not how high it is.
              </li>
              <li>
                Silence before and after you speak is trimmed. Your take is then stretched or squeezed{" "}
                <strong className="text-ink">evenly</strong> so the two start and end together; its real length is
                compared separately.
              </li>
              <li>Both recordings are measured the same way PhonoTrainer measures the clip.</li>
              <li>
                <strong className="text-ink">This is a description, not a grade.</strong> The same native speaker saying
                it twice would differ too. Use it to hear and see where your version departs from this one.
              </li>
              <li>Single short words often have too little voicing to read a pitch shape; record the phrase for intonation.</li>
            </ul>
          </Disclosure>
        </div>
      )}

      {stage.kind !== "result" && native && (
        <ContourOverlay
          native={native}
          summary={nativeSummary(native)}
          playing={playing === "original" ? "original" : null}
          originalClock={original.clock}
        />
      )}

      <div role="status" className="sr-only">
        {said}
      </div>
    </section>
  );
}

/** Tells assistive technology the key that presses the button inside (React Aria's
 *  buttons do not pass aria-keyshortcuts through, so it is set on the element). */
function Shortcut({ keys, children }: { keys: string; children: ReactNode }) {
  const box = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    box.current?.querySelector("button")?.setAttribute("aria-keyshortcuts", keys);
  });
  return (
    <span ref={box} className="contents">
      {children}
    </span>
  );
}

/** A visible key hint inside a button; aria-keyshortcuts (Shortcut) says it to
 *  assistive technology, so the hint stays out of the accessible name. */
function KeyHint({ children }: { children: string }) {
  return (
    <span aria-hidden="true">
      <Kbd>{children}</Kbd>
    </span>
  );
}

function RecordingStatus({
  elapsed,
  maxSeconds,
  level,
  quiet,
  clipped,
}: {
  elapsed: number;
  maxSeconds: number;
  level: Level | null;
  quiet: boolean;
  clipped: boolean;
}) {
  const labelId = useId();
  return (
    <div className="flex flex-col gap-2">
      <p className="flex flex-wrap items-center gap-2 text-base font-medium text-ink">
        <CircleDot size={18} aria-hidden="true" className="motion-ok:animate-pulse" />
        Recording
        <span role="timer" aria-label="Recording time" className="tabular-nums text-ink-2">
          {fmtClock(elapsed)} / {fmtClock(maxSeconds, false)}
        </span>
      </p>
      {level && (
        <div className="flex items-center gap-3">
          <span id={labelId} className="text-sm text-ink-2">
            Microphone level
          </span>
          <div
            role="meter"
            aria-labelledby={labelId}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={levelPercent(level)}
            aria-valuetext={levelWord(level)}
            className="relative h-3 w-36 rounded-chip bg-surface-2 shadow-[inset_0_0_0_1px_var(--line)]"
          >
            <div
              className="absolute inset-y-0.5 left-0.5 rounded-chip bg-data-ink"
              style={{ width: `calc(${levelPercent(level)}% - 4px)` }}
            />
          </div>
          <span aria-hidden="true" className="text-sm text-ink">
            {levelWord(level)}
          </span>
        </div>
      )}
      <p className="text-sm text-ink-2">Speak now. Press R or Stop when you&rsquo;re done; Esc cancels.</p>
      {quiet && <p className="text-sm font-medium text-ink">We can barely hear you: move closer or speak up.</p>}
      {clipped && <p className="text-sm font-medium text-ink">Too loud: move back a little.</p>}
    </div>
  );
}
