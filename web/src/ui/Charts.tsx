/** Chart primitives (dataviz rules): hue stays reserved for families, so a measure is ONE
 *  series in ink against a hatched reference band, with direct labels, a source, and a
 *  table view on every chart. */

import { Table2, ChartColumn } from "lucide-react";
import { useId, useState, type ReactNode } from "react";

import { cn } from "./cn";
import { ToggleButton } from "./Button";

export interface MetricReferenceBand {
  /** Lower bound of the published value, 0–1. */
  low?: number;
  /** Upper bound, 0–1. Omit for "more than `low`". */
  high?: number;
  /** How the figure is quoted ("> 60 %", "≈ 25 %"). */
  text: string;
  /** Short source ("Johnson 2004"). */
  source?: string;
  url?: string;
}

interface MetricMeterProps {
  label: ReactNode;
  /** Accessible name when `label` is not plain text. */
  ariaLabel?: string;
  /** 0–1, or null when it cannot be measured. */
  value: number | null;
  /** "126 of 175 words" — shown under the bar. */
  detail?: ReactNode;
  reference?: MetricReferenceBand;
  /** Explanation affordance placed after the label (e.g. <Explain term=… />). */
  explain?: ReactNode;
  className?: string;
}

const pct = (v: number) => `${Math.round(v * 1000) / 10} %`;

export function MetricMeter({ label, ariaLabel, value, detail, reference, explain, className }: MetricMeterProps) {
  const labelId = useId();
  const band = reference
    ? {
        left: Math.max(0, Math.min(1, reference.low ?? 0)),
        right: Math.max(0, Math.min(1, reference.high ?? 1)),
      }
    : null;
  const valueText =
    value === null
      ? "not measured"
      : `${pct(value)}${reference ? `; published figure ${reference.text}${reference.source ? ` (${reference.source})` : ""}` : ""}`;
  // A plain role="meter" rather than react-aria's Meter: that one renders
  // role="meter progressbar", which axe-core flags (aria-allowed-attr, critical).
  // Only the bar carries the role: a meter's children are presentational, so the
  // label's (?) button and the source link have to live outside it.
  const name = ariaLabel ?? (typeof label === "string" ? label : undefined);
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <span className="inline-flex items-baseline gap-1 text-sm font-medium text-ink">
          <span id={labelId}>{label}</span>
          {explain}
        </span>
        <span className="text-lg font-semibold tabular-nums text-ink" aria-hidden="true">
          {value === null ? "—" : pct(value)}
        </span>
      </div>
      <div
        role="meter"
        aria-valuenow={value === null ? 0 : Math.round(value * 1000) / 10}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuetext={valueText}
        aria-label={name}
        aria-labelledby={name ? undefined : labelId}
        className="relative h-3.5 rounded-chip bg-surface-2"
      >
        {band && (
          <div
            className="ref-band absolute inset-y-0 rounded-[3px] shadow-[inset_0_0_0_1px_var(--data-ref-line)]"
            style={{ left: `${band.left * 100}%`, width: `${Math.max(0.01, band.right - band.left) * 100}%` }}
          />
        )}
        {value !== null && (
          <div
            className="absolute inset-y-[3px] left-0 rounded-e-[4px] rounded-s-chip bg-data-ink"
            style={{ width: `${Math.max(0, Math.min(1, value)) * 100}%` }}
          />
        )}
      </div>
      <div className="flex flex-wrap justify-between gap-x-3 text-xs text-ink-muted">
        <span>{detail}</span>
        {reference && (
          <span>
            Published: <span className="text-ink-2">{reference.text}</span>
            {reference.source && (
              <>
                {" "}
                ·{" "}
                {reference.url ? (
                  <a className="underline underline-offset-2" href={reference.url} target="_blank" rel="noreferrer">
                    {reference.source}
                  </a>
                ) : (
                  reference.source
                )}
              </>
            )}
          </span>
        )}
      </div>
    </div>
  );
}

interface ChartFrameProps {
  title: ReactNode;
  /** One or two sentences saying what the chart shows — also the screen-reader summary. */
  summary: ReactNode;
  /** The same data as a table (always available). */
  table: ReactNode;
  children: ReactNode;
  legend?: ReactNode;
  className?: string;
  /** Heading level of the title: 3 by default; deeper inside nested sections. */
  level?: 2 | 3 | 4 | 5;
}

export function ChartFrame({ title, summary, table, children, legend, className, level = 3 }: ChartFrameProps) {
  const [asTable, setAsTable] = useState(false);
  const summaryId = useId();
  const H = `h${level}` as "h2" | "h3" | "h4" | "h5";
  return (
    <figure className={cn("flex flex-col gap-3", className)} aria-describedby={summaryId}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <H className="text-base font-semibold text-ink">{title}</H>
          <figcaption id={summaryId} className="text-sm text-ink-2">
            {summary}
          </figcaption>
        </div>
        <ToggleButton
          size="sm"
          variant="secondary"
          isSelected={asTable}
          onChange={setAsTable}
          icon={asTable ? ChartColumn : Table2}
        >
          {asTable ? "Show chart" : "Show as table"}
        </ToggleButton>
      </div>
      {legend && !asTable && <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-2">{legend}</div>}
      {asTable ? <div className="relative overflow-x-auto">{table}</div> : children}
    </figure>
  );
}

/** Minimal styled table for chart table views and small data lists. */
export function DataTable({
  columns,
  rows,
  caption,
}: {
  columns: ReactNode[];
  rows: ReactNode[][];
  caption?: ReactNode;
}) {
  return (
    <table className="w-full border-collapse text-sm">
      {caption && <caption className="pb-2 text-left text-xs text-ink-muted">{caption}</caption>}
      <thead>
        <tr>
          {columns.map((column, i) => (
            <th key={i} scope="col" className="border-b border-line-strong px-2 py-1.5 text-left font-semibold text-ink">
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, r) => (
          <tr key={r} className="border-b border-line last:border-b-0">
            {row.map((cell, c) => (
              <td key={c} className="px-2 py-1.5 tabular-nums text-ink-2">
                {cell}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
