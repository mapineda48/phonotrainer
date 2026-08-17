/** Waveform of the analyzed audio: a map of the recording, for orientation and
 *  seeking.
 *
 *  It is context, not categorical data: recessive color (--wave), the playhead
 *  in ink and the active span shaded. Click = jump there; hover = the time under
 *  the pointer.
 */

import { useEffect, useRef, useState } from "react";

import { fmtTime } from "../lib/format";
import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";

const BUCKETS = 1100;

/** Peaks (absolute maximum per bucket) of the already-analyzed WAV. */
async function loadPeaks(url: string, signal: AbortSignal): Promise<Float32Array | null> {
  const Ctor = window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null; // jsdom or a browser without WebAudio: the waveform is decorative
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

export function Waveform({ src, duration }: { src: string; duration: number }) {
  const player = usePlayer();
  const boxRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [peaks, setPeaks] = useState<Float32Array | null>(null);
  const [hover, setHover] = useState<{ x: number; time: number } | null>(null);

  const progress = useTimeSelector(player.clock, (time) =>
    duration > 0 ? Math.min(1, time / duration) : 0,
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
    const canvas = canvasRef.current;
    const box = boxRef.current;
    if (!canvas || !box) return;

    const draw = () => {
      const ratio = window.devicePixelRatio || 1;
      const width = box.clientWidth;
      const height = box.clientHeight;
      canvas.width = Math.max(1, Math.floor(width * ratio));
      canvas.height = Math.max(1, Math.floor(height * ratio));
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.scale(ratio, ratio);
      ctx.clearRect(0, 0, width, height);
      ctx.fillStyle = getComputedStyle(box).getPropertyValue("--wave").trim() || "#b9b8ae";
      const middle = height / 2;
      if (!peaks) {
        ctx.fillRect(0, middle - 1, width, 2); // no WebAudio: a flat track still works as a scrub bar
        return;
      }
      const step = width / peaks.length;
      for (let i = 0; i < peaks.length; i += 1) {
        const amplitude = Math.max(1, peaks[i] * (height - 6));
        ctx.fillRect(i * step, middle - amplitude / 2, Math.max(step - 0.4, 0.6), amplitude);
      }
    };

    draw();
    const observer = new ResizeObserver(draw);
    observer.observe(box);
    return () => observer.disconnect();
  }, [peaks]);

  const timeAt = (event: React.MouseEvent<HTMLDivElement>): number => {
    const rect = event.currentTarget.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    return ratio * duration;
  };

  const span = player.span;

  return (
    <div
      ref={boxRef}
      className="waveform"
      role="slider"
      tabIndex={0}
      aria-label="Position in the recording"
      aria-valuemin={0}
      aria-valuemax={Math.round(duration)}
      aria-valuenow={Math.round(progress * duration)}
      aria-valuetext={fmtTime(progress * duration)}
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
        if (event.key === "ArrowRight") player.seek(player.clock.getSnapshot() + 1);
        if (event.key === "ArrowLeft") player.seek(player.clock.getSnapshot() - 1);
      }}
    >
      <canvas ref={canvasRef} aria-hidden="true" />
      {span && duration > 0 && (
        <div
          className="waveform__span"
          style={{
            left: `${(span.start / duration) * 100}%`,
            width: `${Math.max(0.4, ((span.end - span.start) / duration) * 100)}%`,
          }}
        />
      )}
      <div className="waveform__playhead" style={{ left: `${progress * 100}%` }} />
      {hover && (
        <div className="chart__tip" style={{ left: Math.min(hover.x + 8, 9999), top: 2 }}>
          {fmtTime(hover.time)}
        </div>
      )}
    </div>
  );
}
