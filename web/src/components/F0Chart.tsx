/** F0 contour of the segment: a single series, a recessive axis, a crosshair on
 *  hover and a playhead synced to playback. */

import { useMemo, useState } from "react";

import { fmtTime } from "../lib/format";
import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import type { Segment } from "../types";

const HEIGHT = 62;
const PAD = { top: 6, right: 6, bottom: 14, left: 32 };
const MAX_POINTS = 140;

interface Point {
  x: number;
  y: number;
  t: number;
  hz: number;
}

export function F0Chart({ segment, width = 340 }: { segment: Segment; width?: number }) {
  const player = usePlayer();
  const [hover, setHover] = useState<Point | null>(null);

  const chart = useMemo(() => {
    const track = segment.f0_track;
    if (track.length < 2) return null;
    const step = Math.max(1, Math.ceil(track.length / MAX_POINTS));
    const sampled = track.filter((_, index) => index % step === 0);
    const values = sampled.map(([, hz]) => hz);
    const lo = Math.min(...values);
    const hi = Math.max(...values);
    const span = Math.max(hi - lo, 1);
    const innerW = width - PAD.left - PAD.right;
    const innerH = HEIGHT - PAD.top - PAD.bottom;
    const toX = (t: number) =>
      PAD.left + ((t - segment.start) / Math.max(segment.end - segment.start, 1e-6)) * innerW;
    const points: Point[] = sampled.map(([t, hz]) => ({
      x: toX(t),
      y: PAD.top + (1 - (hz - lo) / span) * innerH,
      t,
      hz,
    }));
    return { points, lo, hi, toX, path: points.map((p) => `${p.x},${p.y}`).join(" ") };
  }, [segment, width]);

  const playheadX = useTimeSelector(player.clock, (time) => {
    if (!chart || time < segment.start || time > segment.end) return null;
    return Math.round(chart.toX(time) * 10) / 10;
  });

  if (!chart) {
    return (
      <p className="tiny muted" style={{ margin: "6px 0" }}>
        No measurable F0 in this segment (voiceless speech, music or silence).
      </p>
    );
  }

  const onMove = (event: React.MouseEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const x = event.clientX - rect.left;
    let best = chart.points[0];
    for (const point of chart.points) {
      if (Math.abs(point.x - x) < Math.abs(best.x - x)) best = point;
    }
    setHover(best);
  };

  return (
    <div className="chart" style={{ width }}>
      <svg
        width={width}
        height={HEIGHT}
        role="img"
        aria-label={`F0 contour between ${chart.lo.toFixed(0)} and ${chart.hi.toFixed(0)} Hz`}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
        onClick={() => hover && player.seek(hover.t)}
      >
        <line
          className="axis"
          x1={PAD.left}
          y1={HEIGHT - PAD.bottom + 2}
          x2={width - PAD.right}
          y2={HEIGHT - PAD.bottom + 2}
        />
        <text x={PAD.left - 5} y={PAD.top + 7} textAnchor="end">
          {chart.hi.toFixed(0)}
        </text>
        <text x={PAD.left - 5} y={HEIGHT - PAD.bottom} textAnchor="end">
          {chart.lo.toFixed(0)}
        </text>
        <text x={PAD.left - 5} y={HEIGHT - 2} textAnchor="end">
          Hz
        </text>
        {hover && (
          <line className="cross" x1={hover.x} x2={hover.x} y1={PAD.top} y2={HEIGHT - PAD.bottom} />
        )}
        <polyline className="line" points={chart.path} />
        {playheadX !== null && (
          <line
            x1={playheadX}
            x2={playheadX}
            y1={PAD.top - 2}
            y2={HEIGHT - PAD.bottom + 2}
            stroke="var(--ink)"
            strokeWidth={2}
          />
        )}
      </svg>
      {hover && (
        <div
          className="chart__tip"
          style={{ left: Math.min(hover.x + 8, width - 86), top: 0 }}
        >
          {fmtTime(hover.t)} · {Math.round(hover.hz)} Hz
        </div>
      )}
    </div>
  );
}
