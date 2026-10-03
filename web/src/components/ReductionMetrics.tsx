/** How reduced this speech is, measure by measure, next to the figure the
 *  report gives for conversational American English. Each row is a meter:
 *  the fill is the measured share, the outlined band is the report's range. */

import {
  EXTRA_METRIC_LABELS,
  FINAL_T_LABEL,
  fmtPct,
  metricRows,
  standing,
  STANDING_TEXT,
  type MetricRow,
} from "../lib/metrics";
import type { FinalTOutcome, MetricReference, Metrics, Reference } from "../types";

/** The reference key of a row (the variant weak-form rate has none). */
const referenceKey = (row: MetricRow): string => row.key;

export function MetricMeter({
  pct,
  reference,
  label,
}: {
  pct: number | null;
  reference?: MetricReference;
  label: string;
}) {
  const low = reference?.low ?? null;
  const high = reference?.high ?? null;
  return (
    <span
      className="meter"
      role="img"
      aria-label={`${label}: ${fmtPct(pct)}${reference ? `; report ${reference.display}` : ""}`}
    >
      {pct != null && (
        <span className="meter__fill" style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} />
      )}
      {low != null &&
        (high === low ? (
          <span className="meter__tick" style={{ left: `${low}%` }} />
        ) : (
          <span
            className="meter__band"
            style={{ left: `${low}%`, width: `${(high ?? 100) - low}%` }}
          />
        ))}
    </span>
  );
}

function RowView({ row, reference }: { row: MetricRow; reference: Reference }) {
  const ref = reference.metrics_reference?.[referenceKey(row)];
  const label =
    reference.metric_labels?.[row.key] ?? EXTRA_METRIC_LABELS[row.key] ?? row.key.replaceAll("_", " ");
  const where = standing(row.ratio.pct, ref);
  const title = [
    `${row.ratio.count} of ${row.ratio.of} ${row.unit}`,
    ref ? `Report: ${ref.display} — ${ref.source}. ${ref.note}` : "",
  ]
    .filter(Boolean)
    .join("\n");
  return (
    <div className="metric" title={title} data-testid={`metric-${row.key}`}>
      <div className="metric__head">
        <span className="metric__label">{label}</span>
        <span className="metric__value">{fmtPct(row.ratio.pct)}</span>
      </div>
      <MetricMeter pct={row.ratio.pct} reference={ref} label={label} />
      <div className="metric__foot tiny muted">
        {row.ratio.of > 0 ? `${row.ratio.count} of ${row.ratio.of} ${row.unit}` : `no ${row.unit}`}
        {ref && (
          <>
            {" "}
            · report {ref.display} ({ref.cite})
            {where && <> · {STANDING_TEXT[where]}</>}
          </>
        )}
      </div>
    </div>
  );
}

const FINAL_T_ORDER: FinalTOutcome[] = ["flap", "glottal", "unreleased", "released", "other"];

export function ReductionMetrics({
  metrics,
  reference,
}: {
  metrics: Metrics;
  reference: Reference;
}) {
  const finalT = metrics.final_t_prevocalic;
  const lowConf = metrics.words.low_confidence_pct;
  return (
    <div data-testid="reduction-metrics">
      <p className="tiny muted" style={{ margin: "0 0 8px" }}>
        Measured with the <strong>{metrics.engine ?? "espeak"}</strong> engine over{" "}
        {metrics.words.analyzed} words
        {lowConf != null && lowConf > 0 && <> ({lowConf.toFixed(1)} % left out as low confidence)</>}
        . The outlined band is the report's figure for conversational American English.
        {metrics.engine === "espeak" && (
          <> The espeak engine tends to hear the dictionary form, so expect lower figures.</>
        )}
      </p>
      {metricRows(metrics).map((row) => (
        <RowView key={row.key} row={row} reference={reference} />
      ))}
      {finalT.of > 0 && (
        <p className="tiny" style={{ margin: "10px 0 0" }} data-testid="final-t">
          <span className="muted">Final /t/ before a vowel ({finalT.of}):</span>{" "}
          {FINAL_T_ORDER.filter((key) => finalT[key] > 0)
            .map((key) => `${FINAL_T_LABEL[key]} ${finalT[key]}`)
            .join(" · ")}
        </p>
      )}
      {metrics.rhythm && (
        <p className="tiny muted" style={{ margin: "6px 0 0" }} title={metrics.rhythm.method}>
          Rhythm (approximate): nPVI {metrics.rhythm.npvi.toFixed(0)} over{" "}
          {metrics.rhythm.n_pairs} pairs of syllable intervals — a within-clip measure, not
          comparable to published values.
        </p>
      )}
    </div>
  );
}
