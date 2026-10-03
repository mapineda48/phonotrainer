/** Central player: a single <audio> element governs the whole app.
 *
 *  It plays the WAV the pipeline analyzed (not the video), so what you hear is
 *  exactly what the timings were computed over. It can play a *span* (word,
 *  phone or segment) and loop it, which is the single most repeated gesture
 *  when studying pronunciation.
 *
 *  One material can have two tracks (the original mix and the isolated
 *  dialogue). `sourceKey` names the material: switching tracks keeps the
 *  position and the playing state; switching materials rewinds.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { Clock } from "./clock";

export interface Span {
  start: number;
  end: number;
}

export interface PlayerApi {
  clock: Clock;
  playing: boolean;
  rate: number;
  loop: boolean;
  span: Span | null;
  duration: number;
  /** Play a span (or everything, when `span` is null). */
  play: (span?: Span | null) => void;
  pause: () => void;
  /** Play/pause; if a span other than the current one is passed, jump to it. */
  toggle: (span?: Span | null) => void;
  seek: (time: number) => void;
  setRate: (rate: number) => void;
  setLoop: (loop: boolean) => void;
  /** Stop bounding playback to a span. */
  clearSpan: () => void;
}

const PlayerContext = createContext<PlayerApi | null>(null);

export function usePlayer(): PlayerApi {
  const api = useContext(PlayerContext);
  if (!api) throw new Error("usePlayer() requires a <PlayerProvider>");
  return api;
}

/** For tests: injects a fake player without touching the audio DOM. */
export const PlayerContextProvider = PlayerContext.Provider;

const SPAN_EPSILON = 0.015; // s of tolerance when comparing against the span end

export function PlayerProvider({
  src,
  sourceKey,
  children,
}: {
  src: string | null;
  /** Identity of the material; defaults to `src`. */
  sourceKey?: string | null;
  children: ReactNode;
}) {
  const key = sourceKey === undefined ? src : sourceKey;
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const clock = useMemo(() => new Clock(), []);
  const spanRef = useRef<Span | null>(null);
  const loopRef = useRef(false);
  const frameRef = useRef<number | null>(null);

  const [playing, setPlaying] = useState(false);
  const [rate, setRateState] = useState(1);
  const [loop, setLoopState] = useState(false);
  const [span, setSpanState] = useState<Span | null>(null);
  const [duration, setDuration] = useState(0);
  /** Read by the effects, which run after the <audio> already changed source. */
  const playingRef = useRef(false);
  playingRef.current = playing;
  /** Where to pick up once the other track of the same material has loaded. */
  const resumeRef = useRef<{ time: number; playing: boolean } | null>(null);

  const setSpan = useCallback((next: Span | null) => {
    spanRef.current = next;
    setSpanState(next);
  }, []);

  const pause = useCallback(() => {
    audioRef.current?.pause();
  }, []);

  const startPlayback = useCallback(() => {
    const audio = audioRef.current;
    if (!audio) return;
    try {
      const promise = audio.play() as Promise<void> | undefined;
      promise?.catch(() => setPlaying(false));
    } catch {
      setPlaying(false); // jsdom, and browsers that block autoplay
    }
  }, []);

  const seek = useCallback(
    (time: number) => {
      const audio = audioRef.current;
      if (!audio) return;
      audio.currentTime = Math.max(0, time);
      clock.set(audio.currentTime);
    },
    [clock],
  );

  const play = useCallback(
    (next?: Span | null) => {
      const target = next === undefined ? spanRef.current : next;
      setSpan(target ?? null);
      if (target) seek(target.start);
      startPlayback();
    },
    [seek, setSpan, startPlayback],
  );

  const toggle = useCallback(
    (next?: Span | null) => {
      const audio = audioRef.current;
      const current = spanRef.current;
      const sameSpan =
        next == null || (current && current.start === next.start && current.end === next.end);
      if (audio && !audio.paused && sameSpan) {
        pause();
        return;
      }
      play(next);
    },
    [pause, play],
  );

  const setRate = useCallback((value: number) => {
    setRateState(value);
    if (audioRef.current) audioRef.current.playbackRate = value;
  }, []);

  const setLoop = useCallback((value: boolean) => {
    loopRef.current = value;
    setLoopState(value);
  }, []);

  const clearSpan = useCallback(() => setSpan(null), [setSpan]);

  // Animation loop: publishes the time and enforces the active span.
  useEffect(() => {
    if (!playing) return;
    const tick = () => {
      const audio = audioRef.current;
      if (audio) {
        const now = audio.currentTime;
        const active = spanRef.current;
        if (active && now >= active.end - SPAN_EPSILON) {
          if (loopRef.current) {
            audio.currentTime = active.start;
            clock.set(active.start);
          } else {
            audio.pause();
            clock.set(active.end);
          }
        } else {
          clock.set(now);
        }
      }
      frameRef.current = requestAnimationFrame(tick);
    };
    frameRef.current = requestAnimationFrame(tick);
    return () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
    };
  }, [playing, clock]);

  // When SWITCHING analyses: stop and rewind to the start. Not on mount: this
  // effect (the parent's) would otherwise trample the one that opens a specific
  // word when arriving from the corpus, leaving the word selected but silent.
  // Another track of the SAME material keeps its place instead: the timings are
  // the same, only what you hear changes.
  const previous = useRef<{ key: string | null | undefined; src: string | null } | null>(null);
  useEffect(() => {
    const before = previous.current;
    previous.current = { key, src };
    if (!before || (before.key === key && before.src === src)) return;
    if (before.key === key) {
      resumeRef.current = { time: clock.getSnapshot(), playing: playingRef.current };
      return;
    }
    resumeRef.current = null;
    setSpan(null);
    setPlaying(false);
    clock.set(0);
    const audio = audioRef.current;
    if (audio) {
      audio.pause();
      audio.currentTime = 0;
    }
  }, [key, src, clock, setSpan]);

  const api = useMemo<PlayerApi>(
    () => ({
      clock,
      playing,
      rate,
      loop,
      span,
      duration,
      play,
      pause,
      toggle,
      seek,
      setRate,
      setLoop,
      clearSpan,
    }),
    [clock, playing, rate, loop, span, duration, play, pause, toggle, seek, setRate, setLoop, clearSpan],
  );

  return (
    <PlayerContext.Provider value={api}>
      <audio
        ref={audioRef}
        src={src ?? undefined}
        preload="auto"
        data-testid="player-audio"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onLoadedMetadata={(event) => {
          const audio = event.currentTarget;
          audio.playbackRate = rate;
          setDuration(Number.isFinite(audio.duration) ? audio.duration : 0);
          const resume = resumeRef.current;
          if (resume) {
            resumeRef.current = null;
            audio.currentTime = resume.time;
            clock.set(resume.time);
            if (resume.playing) startPlayback();
          }
        }}
        onSeeked={(event) => clock.set(event.currentTarget.currentTime)}
      />
      {children}
    </PlayerContext.Provider>
  );
}
