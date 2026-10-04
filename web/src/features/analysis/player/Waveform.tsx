/** The recording as a waveform: a map for orientation and seeking.
 *
 *  It is context, not data: bars in a muted ink, the part already played in full ink,
 *  the bounded span shaded. Click to jump; arrow keys move one second. */

import { useEffect, useRef, useState } from "react";

import { fmtTime } from "../../../lib/format";
import { useTimeSelector } from "../../../player/clock";
import { usePlayer } from "../../../player/PlayerProvider";
import { cn } from "../../../ui";

const BUCKETS = 900;

/** Peak (absolute maximum) per bucket of the analyzed WAV, normalized to 0–1. */
async function loadPeaks(url: string, signal: AbortSignal): Promise<Float32Array | null> {
  const Ctor =
    window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null; // jsdom, or no WebAudio: the bar still works as a scrubber
  const response = await fetch(url, { signal });
  const buffer = await response.arrayBuffer();
  const context = new Ctor();
  try {
    const audio = await context.decodeAudioData(buffer);
    const data = audio.getChannelData(0);
    const size = Math.max(1, Math.floor(data.length / BUCKETS));
    const peaks = new Float32Array(BUCKETS);
    for (let i = 0; i < BUCKETS; i += 1) {
      let peak = 0;
      const start = i * size;
      for (let j = start; j < start + size && j < data.length; j += 1) {
        const value = Math.abs(data[j]);
        if (value > peak) peak = value;
      }
      peaks[i] = peak;
    }
    const max = peaks.reduce((a, b) => Math.max(a, b), 0) || 1;
    return peaks.map((p) => p / max);
  } finally {
    void context.close();
  }
}

function draw(canvas: HTMLCanvasElement | null, peaks: Float32Array | null, width: number, height: number, color: string) {
  if (!canvas) return;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.floor(width * ratio));
  canvas.height = Math.max(1, Math.floor(height * ratio));
  canvas.style.width = `${width}px`;
  canvas.style.height = `${height}px`;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.scale(ratio, ratio);
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = color;
  const middle = height / 2;
  if (!peaks) {
    ctx.fillRect(0, middle - 1, width, 2);
    return;
  }
  const step = width / peaks.length;
  for (let i = 0; i < peaks.length; i += 1) {
    const amplitude = Math.max(1.5, peaks[i] * (height - 6));
    ctx.fillRect(i * step, middle - amplitude / 2, Math.max(step - 0.5, 0.6), amplitude);
  }
}

export function Waveform({ src, duration, className }: { src: string; duration: number; className?: string }) {
  const player = usePlayer();
  const boxRef = useRef<HTMLDivElement | null>(null);
  const baseRef = useRef<HTMLCanvasElement | null>(null);
  const playedRef = useRef<HTMLCanvasElement | null>(null);
  const [peaks, setPeaks] = useState<Float32Array | null>(null);
  const [width, setWidth] = useState(0);
  const [hover, setHover] = useState<{ x: number; time: number } | null>(null);

  // per mille: the clip only re-renders when the playhead moves a visible step
  const progress = useTimeSelector(player.clock, (time) =>
    duration > 0 ? Math.round(Math.min(1, Math.max(0, time / duration)) * 1000) / 1000 : 0,
  );

  useEffect(() => {
    const controller = new AbortController();
    setPeaks(null);
    loadPeaks(src, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setPeaks(result);
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [src]);

  useEffect(() => {
    const box = boxRef.current;
    if (!box) return;
    const paint = () => {
      const w = box.clientWidth;
      const h = box.clientHeight;
      setWidth(w);
      const styles = getComputedStyle(box);
      draw(baseRef.current, peaks, w, h, styles.getPropertyValue("--ink-muted").trim() || "#8a877f");
      draw(playedRef.current, peaks, w, h, styles.getPropertyValue("--ink").trim() || "#1b1a18");
    };
    paint();
    const observer = new ResizeObserver(paint);
    observer.observe(box);
    // the theme changes the ink: repaint when <html> data-theme flips
    const themeObserver = new MutationObserver(paint);
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => {
      observer.disconnect();
      themeObserver.disconnect();
    };
  }, [peaks]);

  const timeAt = (event: React.MouseEvent<HTMLDivElement>): number => {
    const rect = event.currentTarget.getBoundingClientRect();
    const ratio = rect.width > 0 ? Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)) : 0;
    return ratio * duration;
  };

  const span = player.span;
  const now = progress * duration;

  return (
    <div
      ref={boxRef}
      role="slider"
      tabIndex={0}
      aria-label="Position in the recording"
      aria-valuemin={0}
      aria-valuemax={Math.round(duration)}
      aria-valuenow={Math.round(now)}
      aria-valuetext={`${fmtTime(now)} of ${fmtTime(duration)}`}
      className={cn("relative h-10 min-w-48 flex-1 cursor-pointer rounded-control bg-surface-2", className)}
      onClick={(event) => {
        player.clearSpan();
        player.seek(timeAt(event));
      }}
      onMouseMove={(event) => {
        const rect = event.currentTarget.getBoundingClientRect();
        setHover({ x: event.clientX - rect.left, time: timeAt(event) });
      }}
      onMouseLeave={() => setHover(null)}
      onKeyDown={(event) => {
        const step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
        const jump = event.key === "Home" ? 0 : event.key === "End" ? duration : null;
        if (step === 0 && jump === null) return;
        event.preventDefault();
        // the workspace's own ←/→ seeks 2 s: one gesture, one move
        event.stopPropagation();
        player.seek(jump ?? player.clock.getSnapshot() + step);
      }}
    >
      <canvas ref={baseRef} aria-hidden="true" className="absolute inset-0" />
      <div className="absolute inset-y-0 left-0 overflow-hidden" style={{ width: `${progress * 100}%` }} aria-hidden="true">
        <canvas ref={playedRef} className="absolute inset-y-0 left-0" style={{ width: width || undefined }} />
      </div>
      {span && duration > 0 && (
        <div
          aria-hidden="true"
          className="absolute inset-y-0 rounded-[3px] bg-ink/10 shadow-[inset_0_0_0_1px_var(--line-strong)]"
          style={{
            left: `${(span.start / duration) * 100}%`,
            width: `${Math.max(0.4, ((span.end - span.start) / duration) * 100)}%`,
          }}
        />
      )}
      <div
        aria-hidden="true"
        className="absolute inset-y-0 w-0.5 -translate-x-px bg-ink"
        style={{ left: `${progress * 100}%` }}
      />
      {hover && (
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -top-8 z-10 rounded-control bg-ink px-2 py-0.5 text-xs tabular-nums text-page"
          style={{ left: Math.max(0, hover.x - 24) }}
        >
          {fmtTime(hover.time)}
        </div>
      )}
    </div>
  );
}
