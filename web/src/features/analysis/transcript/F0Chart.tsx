/** Pitch (F0) of a phrase: one series in ink (hue stays reserved for the families), a
 *  recessive axis, a crosshair with the value on hover or keyboard focus, the playhead
 *  synced to the audio, and the same data as a table. */

import { useMemo, useState } from "react";

import { Explain } from "../../../didactic/Explain";
import { fmtTime } from "../../../lib/format";
import { useTimeSelector } from "../../../player/clock";
import { usePlayer } from "../../../player/PlayerProvider";
import type { Segment } from "../../../types";
import { ChartFrame, DataTable } from "../../../ui";
import { CONTOUR } from "./Intonation";

const W = 560;
const H = 120;
const PAD = { top: 10, right: 10, bottom: 22, left: 44 };
const MAX_POINTS = 160;

interface Point {
  x: number;
  y: number;
  t: number;
  hz: number;
}

export function F0Chart({ segment }: { segment: Segment }) {
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
    const innerW = W - PAD.left - PAD.right;
    const innerH = H - PAD.top - PAD.bottom;
    const duration = Math.max(segment.end - segment.start, 1e-6);
    const toX = (t: number) => PAD.left + ((t - segment.start) / duration) * innerW;
    const points: Point[] = sampled.map(([t, hz]) => ({
      x: toX(t),
      y: PAD.top + (1 - (hz - lo) / span) * innerH,
      t,
      hz,
    }));
    // one row per ~100 ms for the table view
    const every = Math.max(1, Math.round(0.1 / Math.max(1e-3, (track[1][0] - track[0][0]) || 0.01)));
    const rows = track.filter((_, index) => index % every === 0);
    return { points, lo, hi, toX, rows, path: points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ") };
  }, [segment]);

  const playheadX = useTimeSelector(player.clock, (time) => {
    if (!chart || time < segment.start || time > segment.end) return null;
    return Math.round(chart.toX(time) * 10) / 10;
  });

  if (!chart) {
    return (
      <p className="text-sm text-ink-2">
        No measurable pitch in this phrase (whispered or voiceless speech, music or silence).
      </p>
    );
  }

  const stats = segment.f0_stats;
  const contour = CONTOUR[stats.final_contour as keyof typeof CONTOUR] ?? CONTOUR.flat;
  const summary = [
    `Pitch between ${chart.lo.toFixed(0)} and ${chart.hi.toFixed(0)} Hz`,
    stats.range_st != null ? `a range of ${stats.range_st.toFixed(1)} semitones` : null,
    `the phrase ${contour.verb} at the end`,
  ]
    .filter(Boolean)
    .join("; ")
    .concat(".");

  const nearest = (x: number) => {
    let best = chart.points[0];
    for (const point of chart.points) if (Math.abs(point.x - x) < Math.abs(best.x - x)) best = point;
    return best;
  };

  const onMove = (event: React.MouseEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const x = rect.width > 0 ? ((event.clientX - rect.left) / rect.width) * W : 0;
    setHover(nearest(x));
  };

  const onKey = (event: React.KeyboardEvent<SVGSVGElement>) => {
    if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") return;
    event.preventDefault();
    event.stopPropagation();
    const index = hover ? chart.points.indexOf(hover) : 0;
    const next = Math.max(0, Math.min(chart.points.length - 1, index + (event.key === "ArrowRight" ? 4 : -4)));
    setHover(chart.points[next]);
  };

  const baseline = H - PAD.bottom;

  return (
    <ChartFrame
      title={<Explain term="f0">Pitch (F0)</Explain>}
      summary={summary}
      table={
        <DataTable
          caption="Pitch every 0.1 s"
          columns={["Time", "Pitch (Hz)"]}
          rows={chart.rows.map(([t, hz]) => [fmtTime(t), Math.round(hz)])}
        />
      }
    >
      <div className="relative">
        <svg
          viewBox={`0 0 ${W} ${H}`}
          className="block h-auto w-full touch-none text-ink-muted"
          role="img"
          aria-label={summary}
          tabIndex={0}
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
          onFocus={() => setHover(chart.points[0])}
          onBlur={() => setHover(null)}
          onKeyDown={onKey}
          onClick={() => hover && player.seek(hover.t)}
        >
          <line x1={PAD.left} x2={W - PAD.right} y1={baseline} y2={baseline} stroke="var(--grid)" strokeWidth={1} />
          <line x1={PAD.left} x2={W - PAD.right} y1={PAD.top} y2={PAD.top} stroke="var(--grid)" strokeWidth={1} />
          <text x={PAD.left - 6} y={PAD.top + 4} textAnchor="end" fontSize={12} fill="var(--ink-muted)">
            {chart.hi.toFixed(0)}
          </text>
          <text x={PAD.left - 6} y={baseline} textAnchor="end" fontSize={12} fill="var(--ink-muted)">
            {chart.lo.toFixed(0)}
          </text>
          <text x={PAD.left - 6} y={H - 4} textAnchor="end" fontSize={12} fill="var(--ink-muted)">
            Hz
          </text>
          <text x={PAD.left} y={H - 4} fontSize={12} fill="var(--ink-muted)">
            {fmtTime(segment.start)}
          </text>
          <text x={W - PAD.right} y={H - 4} textAnchor="end" fontSize={12} fill="var(--ink-muted)">
            {fmtTime(segment.end)}
          </text>
          <polyline
            points={chart.path}
            fill="none"
            stroke="var(--ink-2)"
            strokeWidth={2}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
          {hover && (
            <>
              <line x1={hover.x} x2={hover.x} y1={PAD.top} y2={baseline} stroke="var(--line-strong)" strokeWidth={1} />
              <circle cx={hover.x} cy={hover.y} r={4.5} fill="var(--ink)" stroke="var(--surface)" strokeWidth={2} />
            </>
          )}
          {playheadX !== null && (
            <line x1={playheadX} x2={playheadX} y1={PAD.top - 4} y2={baseline + 2} stroke="var(--ink)" strokeWidth={2} />
          )}
        </svg>
        {hover && (
          <div
            role="status"
            className="pointer-events-none absolute top-0 rounded-control bg-ink px-2 py-0.5 text-xs tabular-nums text-page shadow-2"
            style={{ left: `min(calc(${(hover.x / W) * 100}% + 8px), calc(100% - 7rem))` }}
          >
            {fmtTime(hover.t)} · {Math.round(hover.hz)} Hz
          </div>
        )}
      </div>
    </ChartFrame>
  );
}
