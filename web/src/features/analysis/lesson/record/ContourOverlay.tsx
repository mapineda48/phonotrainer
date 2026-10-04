/** The learner's pitch over the original's, or the original's alone before there is a
 *  take. Hue stays reserved for the phenomenon families, so the two lines are told apart
 *  by line style (solid / dashed), weight, end-marker shape (circle / square), direct
 *  labels and a legend — never by color. Unvoiced frames are gaps: nothing is drawn
 *  where there was no pitch. The same data is always available as a table. */

import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent } from "react";

import { Explain } from "../../../../didactic/Explain";
import { Clock, useTimeSelector } from "../../../../player/clock";
import type { NativePitchContour, TakeComparison } from "../../../../types";
import { ChartFrame, DataTable } from "../../../../ui";
import { CONTOUR_ARROW, fmtSt } from "./recording";

/** The drawing is as wide as its box (within these bounds), so its 12–13 px labels stay
 *  12–13 px instead of shrinking with a narrow lesson pane. */
const DEFAULT_W = 420;
const MIN_W = 280;
const MAX_W = 720;
const H = 190;
const PAD = { top: 26, right: 84, bottom: 26, left: 34 };
const INNER_H = H - PAD.top - PAD.bottom;

function useBoxWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(DEFAULT_W);
  useEffect(() => {
    const element = ref.current;
    if (!element || typeof ResizeObserver !== "function") return;
    const measure = () => {
      const measured = Math.round(element.getBoundingClientRect().width);
      if (measured > 0) setWidth(Math.max(MIN_W, Math.min(MAX_W, measured)));
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return { ref, width };
}
/** More points than this are thinned for drawing (the table keeps the 0.1 s grid). */
const MAX_POINTS = 360;
/** End labels closer than this (px) are spread apart and tied to their lines. */
const LABEL_GAP = 16;

type Row = [x: number, original: number | null, take: number | null];

const STILL = new Clock();

export const SERIES = {
  original: { name: "Original", dash: undefined, width: 2.5, stroke: "var(--data-ink)" },
  take: { name: "You", dash: "6 4", width: 2, stroke: "var(--ink)" },
} as const;

/** The legend key: a short stroke in the series' style with its end marker. In the
 *  tooltip (ink background) it takes the text color instead. */
export function LineKey({ series, current = false }: { series: keyof typeof SERIES; current?: boolean }) {
  const s = SERIES[series];
  const color = current ? "currentColor" : s.stroke;
  return (
    <svg width="30" height="12" viewBox="0 0 30 12" aria-hidden="true" className="inline-block shrink-0">
      <line x1="1" x2="22" y1="6" y2="6" stroke={color} strokeWidth={s.width} strokeDasharray={s.dash} />
      {series === "original" ? (
        <circle cx="25" cy="6" r="4" fill={color} />
      ) : (
        <rect x="21" y="2" width="8" height="8" fill={color} />
      )}
    </svg>
  );
}

interface Props {
  native: NativePitchContour;
  /** The take and the comparison; without it the chart shows the original alone. */
  result?: TakeComparison | null;
  summary: string;
  playing?: "original" | "take" | null;
  /** The workspace player's clock (the original plays there). */
  originalClock?: Clock;
  /** The take's own clock (seconds into the take). */
  takeClock?: Clock;
}

function pathOf(points: { x: number; y: number | null }[]): string {
  let d = "";
  let pen = false;
  for (const p of points) {
    if (p.y === null) {
      pen = false;
      continue;
    }
    d += `${pen ? "L" : "M"}${p.x.toFixed(1)},${p.y.toFixed(1)}`;
    pen = true;
  }
  return d;
}

function lastPoint(points: { x: number; y: number | null }[]) {
  for (let i = points.length - 1; i >= 0; i -= 1) {
    const y = points[i].y;
    if (y !== null) return { x: points[i].x, y };
  }
  return null;
}

export function ContourOverlay({ native, result, summary, playing = null, originalClock, takeClock }: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const box = useBoxWidth();
  const W = box.width;
  const innerW = W - PAD.left - PAD.right;
  const hasTake = Boolean(result);

  const chart = useMemo(() => {
    const speech = native.speech;
    let rows: Row[];
    if (result) {
      rows = result.comparison.overlay;
    } else if (speech) {
      rows = native.track
        .filter(([t]) => t >= speech.start - 1e-9 && t <= speech.end + 1e-9)
        .map(([t, , st]) => [Math.round((t - speech.start) * 1000) / 1000, st, null] as Row);
    } else {
      rows = [];
    }
    if (!rows.some(([, a, b]) => a !== null || b !== null)) return null;
    const length = Math.max(rows[rows.length - 1][0], 0.01);
    const extreme = rows.reduce((m, [, a, b]) => Math.max(m, Math.abs(a ?? 0), Math.abs(b ?? 0)), 0);
    // gridlines every 3 st up to ±9, then every 6 or 12: never more than seven lines
    const reach = Math.max(6, Math.ceil(extreme / 3) * 3);
    const step = reach <= 9 ? 3 : reach <= 18 ? 6 : 12;
    const top = Math.ceil(reach / step) * step;
    const ticks: number[] = [];
    for (let v = -top; v <= top + 1e-9; v += step) ticks.push(v);
    const toX = (x: number) => PAD.left + (Math.max(0, Math.min(length, x)) / length) * innerW;
    const toY = (st: number) => PAD.top + ((top - st) / (2 * top)) * INNER_H;
    const thin = Math.max(1, Math.ceil(rows.length / MAX_POINTS));
    const drawn = rows.filter((_, i) => i % thin === 0 || i === rows.length - 1);
    const original = drawn.map(([x, a]) => ({ x: toX(x), y: a === null ? null : toY(a) }));
    const take = drawn.map(([x, , b]) => ({ x: toX(x), y: b === null ? null : toY(b) }));
    return {
      rows,
      length,
      ticks,
      toX,
      toY,
      original: { d: pathOf(original), end: lastPoint(original) },
      take: { d: pathOf(take), end: lastPoint(take) },
      tableRows: rows.filter((_, i) => i % 10 === 0),
    };
  }, [native, result, innerW]);

  const nativeStart = native.speech?.start ?? 0;
  const takeStart = result?.take.speech?.start ?? 0;
  const scale = result?.comparison.time_scale ?? null;
  const originalX = useTimeSelector(originalClock ?? STILL, (t) => {
    if (playing !== "original" || !chart) return null;
    const x = t - nativeStart;
    return x < 0 || x > chart.length ? null : Math.round(chart.toX(x) * 10) / 10;
  });
  const takeX = useTimeSelector(takeClock ?? STILL, (t) => {
    if (playing !== "take" || !chart || !scale) return null;
    const x = (t - takeStart) / scale;
    return x < 0 || x > chart.length ? null : Math.round(chart.toX(x) * 10) / 10;
  });

  if (!chart) {
    return <p className="text-sm text-ink-2">{summary}</p>;
  }

  const arrow = (contour: string | null | undefined) => (contour ? ` ${CONTOUR_ARROW[contour] ?? ""}` : "");
  const labels: { key: "original" | "take"; text: string; y: number; end: { x: number; y: number } }[] = [];
  if (chart.original.end) {
    labels.push({ key: "original", text: `Original${arrow(native.final_contour)}`, y: chart.original.end.y, end: chart.original.end });
  }
  if (hasTake && chart.take.end) {
    labels.push({ key: "take", text: `You${arrow(result?.take.final_contour)}`, y: chart.take.end.y, end: chart.take.end });
  }
  if (labels.length === 2 && Math.abs(labels[0].y - labels[1].y) < LABEL_GAP) {
    // never stacked on top of each other: spread them and tie each to its line
    const middle = (labels[0].y + labels[1].y) / 2;
    const [upper, lower] = labels[0].y <= labels[1].y ? [labels[0], labels[1]] : [labels[1], labels[0]];
    upper.y = middle - LABEL_GAP / 2;
    lower.y = middle + LABEL_GAP / 2;
  }
  const labelX = W - PAD.right + 10;
  const baseline = H - PAD.bottom;
  const hovered = hover === null ? null : chart.rows[hover];
  const hoverX = hovered ? chart.toX(hovered[0]) : null;

  const nearest = (svgX: number) => {
    let best = 0;
    for (let i = 0; i < chart.rows.length; i += 1) {
      if (Math.abs(chart.toX(chart.rows[i][0]) - svgX) < Math.abs(chart.toX(chart.rows[best][0]) - svgX)) best = i;
    }
    return best;
  };
  const onMove = (event: MouseEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    setHover(nearest(rect.width > 0 ? ((event.clientX - rect.left) / rect.width) * W : 0));
  };
  const onKey = (event: KeyboardEvent<SVGSVGElement>) => {
    const last = chart.rows.length - 1;
    const moves: Record<string, (i: number) => number> = {
      ArrowRight: (i) => Math.min(last, i + 5),
      ArrowLeft: (i) => Math.max(0, i - 5),
      Home: () => 0,
      End: () => last,
    };
    const move = moves[event.key];
    if (!move) return;
    // the workspace seeks on ←/→: this chart owns them while it has focus
    event.preventDefault();
    event.stopPropagation();
    setHover((i) => move(i ?? 0));
  };

  const legend = hasTake ? (
    <>
      <span className="inline-flex items-center gap-1.5">
        <LineKey series="original" /> Original
      </span>
      <span className="inline-flex items-center gap-1.5">
        <LineKey series="take" /> You
      </span>
      <span className="inline-flex items-center gap-0.5 text-ink-muted">
        <Explain term="semitone">Semitones</Explain>&nbsp;from each voice&rsquo;s middle
      </span>
      <span className="text-ink-muted">Gaps: no pitch (voiceless sounds or silence)</span>
    </>
  ) : undefined;

  const table = hasTake ? (
    <div className="flex flex-col gap-4">
      <DataTable
        caption={`Pitch every 0.1 s, in semitones from each voice's middle.${scale ? ` Your take is time-scaled ×${scale.toFixed(2)} to the original's length.` : ""}`}
        columns={["Time in the original (s)", "Original (st)", "You (st)"]}
        rows={chart.tableRows.map(([x, a, b]) => [x.toFixed(1), fmtSt(a), fmtSt(b)])}
      />
      <DataTable
        caption="Each voice's middle (median pitch)"
        columns={["Voice", "Middle"]}
        rows={[
          ["Original", native.median_hz === null ? "—" : `${Math.round(native.median_hz)} Hz`],
          ["You", result?.take.median_hz == null ? "—" : `${Math.round(result.take.median_hz)} Hz`],
        ]}
      />
    </div>
  ) : (
    <DataTable
      caption="Pitch every 0.1 s, in semitones from the voice's middle."
      columns={["Time (s)", "Pitch (st)"]}
      rows={chart.tableRows.map(([x, a]) => [x.toFixed(1), fmtSt(a)])}
    />
  );

  const takeLength = result?.take.speech_s ?? null;
  const nativeLength = native.speech_s ?? null;
  const fit =
    scale === null || takeLength === null || nativeLength === null
      ? null
      : Math.abs(scale - 1) < 0.05
        ? "lined up with"
        : scale > 1
          ? "squeezed to"
          : "stretched to";

  return (
    <ChartFrame
      level={5}
      title={hasTake ? "Your pitch and the original's" : "The original's pitch"}
      summary={summary}
      legend={legend}
      table={table}
    >
      <div ref={box.ref} className="relative">
        <svg
          viewBox={`0 0 ${W} ${H}`}
          className="block h-auto w-full touch-none"
          role="img"
          aria-label={summary}
          tabIndex={0}
          data-testid="contour-chart"
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
          onFocus={() => setHover((i) => i ?? 0)}
          onBlur={() => setHover(null)}
          onKeyDown={onKey}
        >
          {chart.ticks.map((v) => (
            <g key={v}>
              <line
                x1={PAD.left}
                x2={W - PAD.right}
                y1={chart.toY(v)}
                y2={chart.toY(v)}
                stroke={v === 0 ? "var(--line-strong)" : "var(--grid)"}
                strokeWidth={1}
              />
              <text x={PAD.left - 6} y={chart.toY(v) + 4} textAnchor="end" fontSize={12} fill="var(--ink-muted)">
                {v > 0 ? `+${v}` : v < 0 ? `−${-v}` : "0"}
              </text>
            </g>
          ))}
          <text x={PAD.left - 6} y={12} textAnchor="end" fontSize={12} fill="var(--ink-muted)">
            st
          </text>
          <text x={PAD.left} y={H - 6} fontSize={12} fill="var(--ink-muted)">
            0 s
          </text>
          <text x={W - PAD.right} y={H - 6} textAnchor="end" fontSize={12} fill="var(--ink-muted)">
            {chart.length.toFixed(2)} s
          </text>

          <path
            d={chart.original.d}
            data-series="original"
            fill="none"
            stroke={SERIES.original.stroke}
            strokeWidth={SERIES.original.width + (playing === "original" ? 1 : 0)}
            strokeLinejoin="round"
            strokeLinecap="round"
            className="patterns:[stroke-width:3px]"
          />
          {hasTake && (
            <path
              d={chart.take.d}
              data-series="take"
              data-dash={SERIES.take.dash}
              fill="none"
              stroke={SERIES.take.stroke}
              strokeWidth={SERIES.take.width + (playing === "take" ? 1 : 0)}
              strokeDasharray={SERIES.take.dash}
              strokeLinejoin="round"
              strokeLinecap="round"
              className="patterns:[stroke-width:2.5px] patterns:[stroke-dasharray:8_4]"
            />
          )}

          {labels.map((label) => (
            <g key={label.key} data-label={label.key}>
              <line
                x1={label.end.x + 6}
                x2={labelX - 3}
                y1={label.end.y}
                y2={label.y}
                stroke="var(--line-strong)"
                strokeWidth={1}
              />
              {label.key === "original" ? (
                <circle cx={label.end.x} cy={label.end.y} r={4} fill={SERIES.original.stroke} stroke="var(--surface)" strokeWidth={2} />
              ) : (
                <rect
                  x={label.end.x - 4}
                  y={label.end.y - 4}
                  width={8}
                  height={8}
                  fill={SERIES.take.stroke}
                  stroke="var(--surface)"
                  strokeWidth={2}
                />
              )}
              <text
                x={labelX}
                y={label.y + 4}
                fontSize={13}
                fontWeight={playing === label.key ? 700 : 500}
                fill="var(--ink)"
              >
                {playing === label.key ? "▶ " : ""}
                {label.text}
              </text>
            </g>
          ))}

          {hoverX !== null && (
            <line x1={hoverX} x2={hoverX} y1={PAD.top} y2={baseline} stroke="var(--line-strong)" strokeWidth={1} />
          )}
          {[originalX, takeX].map(
            (x, i) =>
              x !== null && (
                <line key={i} x1={x} x2={x} y1={PAD.top - 4} y2={baseline + 2} stroke="var(--ink)" strokeWidth={2} />
              ),
          )}
        </svg>
        {hovered && hoverX !== null && (
          <div
            role="status"
            className="pointer-events-none absolute top-0 flex w-max flex-col gap-0.5 rounded-control bg-ink px-2 py-1 text-xs tabular-nums text-page shadow-2"
            // on the far side of the crosshair, so it never hides the line ends it describes
            style={
              hoverX / W > 0.5
                ? { left: `calc(${(hoverX / W) * 100}% - 8px)`, transform: "translateX(-100%)" }
                : { left: `calc(${(hoverX / W) * 100}% + 8px)` }
            }
          >
            <span>{hovered[0].toFixed(2)} s</span>
            <span className="inline-flex items-center gap-1.5">
              <strong>{hovered[1] === null ? "no pitch" : `${fmtSt(hovered[1])} st`}</strong>
              <LineKey series="original" current /> Original
            </span>
            {hasTake && (
              <span className="inline-flex items-center gap-1.5">
                <strong>{hovered[2] === null ? "no pitch" : `${fmtSt(hovered[2])} st`}</strong>
                <LineKey series="take" current /> You
              </span>
            )}
          </div>
        )}
      </div>
      {fit && (
        <p className="text-xs text-ink-muted">
          Your take ({takeLength!.toFixed(2)} s) is shown {fit} the original&rsquo;s length ({nativeLength!.toFixed(2)} s)
          so the shapes line up; the real lengths are compared below.
        </p>
      )}
    </ChartFrame>
  );
}
