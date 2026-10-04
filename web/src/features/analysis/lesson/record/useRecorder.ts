/** The microphone side of "Record yourself": permission, an optional listen-first
 *  lead-in, MediaRecorder, the input level, the timer and the automatic stop at the
 *  server's limit. The microphone is released after every take, so the browser's
 *  recording indicator never stays on. */

import { useCallback, useEffect, useRef, useState } from "react";

import { levelOf, MIC_CONSTRAINTS, micProblem, pickMimeType, type Level, type MicProblem } from "./recording";

export type RecorderPhase = "idle" | "asking" | "listening" | "recording";

export interface RecordedTake {
  blob: Blob;
  seconds: number;
  /** Stopped by the time limit rather than by the learner. */
  auto: boolean;
}

export interface RecorderHandlers {
  onTake: (take: RecordedTake) => void;
  onProblem: (problem: MicProblem) => void;
  /** Called in the same update as the phase change, so whatever the caller derives from
   *  it (an announcement, stopping the clip before the microphone listens) commits
   *  together with the phase, never one render later. */
  onPhase?: (phase: RecorderPhase) => void;
}

export interface StartOptions {
  maxSeconds: number;
  /** Plays the original before recording and returns how long to wait (ms); null to
   *  record straight away. */
  listenFirst: (() => number) | null;
}

/** How often the timer and the level meter refresh (ms). */
const TICK_MS = 100;

type AudioContextClass = typeof AudioContext;

function audioContextClass(): AudioContextClass | null {
  const w = window as unknown as { AudioContext?: AudioContextClass; webkitAudioContext?: AudioContextClass };
  return w.AudioContext ?? w.webkitAudioContext ?? null;
}

export function useRecorder(handlers: RecorderHandlers) {
  const [phase, setPhaseState] = useState<RecorderPhase>("idle");
  const [elapsed, setElapsed] = useState(0);
  const [level, setLevel] = useState<Level | null>(null);
  /** The loudest RMS so far in this take and whether it clipped (for the hints). */
  const [loudest, setLoudest] = useState(-Infinity);
  const [clipped, setClipped] = useState(false);

  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;
  const phaseRef = useRef<RecorderPhase>("idle");
  const mounted = useRef(true);
  const token = useRef(0);
  const stream = useRef<MediaStream | null>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const ctx = useRef<AudioContext | null>(null);
  const analyser = useRef<AnalyserNode | null>(null);
  const ticker = useRef<number | null>(null);
  const listenTimer = useRef<number | null>(null);
  const startedAt = useRef(0);
  const stoppedAt = useRef(0);
  const discard = useRef(false);
  const auto = useRef(false);

  const setPhase = useCallback((next: RecorderPhase) => {
    const changed = phaseRef.current !== next;
    phaseRef.current = next;
    if (!mounted.current) return;
    setPhaseState(next);
    if (changed) handlersRef.current.onPhase?.(next);
  }, []);

  /** Let go of the microphone, the level meter and the timers. */
  const release = useCallback(() => {
    if (ticker.current !== null) window.clearInterval(ticker.current);
    ticker.current = null;
    if (listenTimer.current !== null) window.clearTimeout(listenTimer.current);
    listenTimer.current = null;
    stream.current?.getTracks().forEach((track) => track.stop());
    stream.current = null;
    analyser.current = null;
    const context = ctx.current;
    ctx.current = null;
    if (context) void context.close().catch(() => undefined);
  }, []);

  const meter = (source: MediaStream) => {
    const Ctx = audioContextClass();
    if (!Ctx) return;
    try {
      const context = new Ctx();
      const node = context.createAnalyser();
      node.fftSize = 2048;
      context.createMediaStreamSource(source).connect(node);
      ctx.current = context;
      analyser.current = node;
    } catch {
      /* no meter: recording works without it */
    }
  };

  const stop = useCallback(() => {
    const active = recorder.current;
    if (!active) return;
    recorder.current = null;
    stoppedAt.current = Date.now();
    if (ticker.current !== null) window.clearInterval(ticker.current);
    ticker.current = null;
    if (active.state !== "inactive") active.stop(); // onstop delivers the take
  }, []);

  const begin = useCallback(
    (own: number, maxSeconds: number) => {
      const source = stream.current;
      if (own !== token.current || !source) return;
      const type = pickMimeType(
        typeof MediaRecorder.isTypeSupported === "function" ? (t) => MediaRecorder.isTypeSupported(t) : undefined,
      );
      let rec: MediaRecorder;
      try {
        rec = type ? new MediaRecorder(source, { mimeType: type }) : new MediaRecorder(source);
      } catch {
        release();
        setPhase("idle");
        handlersRef.current.onProblem("other");
        return;
      }
      const chunks: Blob[] = [];
      discard.current = false;
      auto.current = false;
      rec.ondataavailable = (event: BlobEvent) => {
        if (event.data && event.data.size > 0) chunks.push(event.data);
      };
      rec.onstop = () => {
        // a cancelled take (or one replaced by a newer session) has already let go
        if (own !== token.current) return;
        const seconds = Math.max(0, (stoppedAt.current || Date.now()) - startedAt.current) / 1000;
        release();
        if (mounted.current) setLevel(null);
        setPhase("idle");
        if (discard.current || !mounted.current) return;
        const blobType = rec.mimeType || type || chunks[0]?.type || "audio/webm";
        handlersRef.current.onTake({ blob: new Blob(chunks, { type: blobType }), seconds, auto: auto.current });
      };
      meter(source);
      recorder.current = rec;
      rec.start();
      startedAt.current = Date.now();
      stoppedAt.current = 0;
      setElapsed(0);
      setLoudest(-Infinity);
      setClipped(false);
      setPhase("recording");
      const buffer = new Float32Array(2048);
      ticker.current = window.setInterval(() => {
        const seconds = (Date.now() - startedAt.current) / 1000;
        if (mounted.current) setElapsed(seconds);
        const node = analyser.current;
        if (node && mounted.current) {
          node.getFloatTimeDomainData(buffer);
          const now = levelOf(buffer);
          setLevel(now);
          setLoudest((value) => Math.max(value, now.rmsDb));
          if (now.peakDb > -1) setClipped(true);
        }
        if (seconds >= maxSeconds) {
          auto.current = true;
          stop();
        }
      }, TICK_MS);
    },
    [release, setPhase, stop],
  );

  const start = useCallback(
    async ({ maxSeconds, listenFirst }: StartOptions) => {
      if (phaseRef.current !== "idle") return;
      const own = ++token.current;
      setPhase("asking");
      let media: MediaStream;
      try {
        media = await navigator.mediaDevices.getUserMedia(MIC_CONSTRAINTS);
      } catch (error) {
        if (own !== token.current) return;
        setPhase("idle");
        handlersRef.current.onProblem(micProblem(error));
        return;
      }
      if (own !== token.current || !mounted.current) {
        media.getTracks().forEach((track) => track.stop()); // cancelled while the browser asked
        return;
      }
      stream.current = media;
      if (listenFirst) {
        setPhase("listening");
        const wait = listenFirst();
        listenTimer.current = window.setTimeout(() => begin(own, maxSeconds), wait);
      } else {
        begin(own, maxSeconds);
      }
    },
    [begin, setPhase],
  );

  /** Stop without keeping anything. */
  const cancel = useCallback(() => {
    token.current += 1;
    discard.current = true;
    const active = recorder.current;
    recorder.current = null;
    if (active && active.state !== "inactive") active.stop();
    release();
    if (mounted.current) setLevel(null);
    setPhase("idle");
  }, [release, setPhase]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      cancel();
    };
  }, [cancel]);

  return { phase, elapsed, level, loudest, clipped, start, stop, cancel };
}
