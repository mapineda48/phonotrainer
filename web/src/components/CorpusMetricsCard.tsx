/** How reduced everything you have analyzed is, pooled word by word, one
 *  column per phone engine (they measure different things and are never added
 *  up), next to the report's figures and their sources. */

import { EXTRA_METRIC_LABELS, fmtPct, metricRows, type MetricRow } from "../lib/metrics";
import { useReference } from "../reference";
import type { CorpusMetrics, Metrics } from "../types";
import { MetricMeter } from "./ReductionMetrics";

export function CorpusMetricsCard({ metrics }: { metrics: CorpusMetrics }) {
  const reference = useReference();
  const engines = Object.entries(metrics.by_engine).filter(
    (entry): entry is [string, Metrics] => entry[1] != null,
  );
  if (engines.length === 0) return null;

  // Rows from the richest engine, so a measure only one of them has still shows.
  const keys: string[] = [];
  for (const [, m] of engines) {
    for (const row of metricRows(m)) if (!keys.includes(row.key)) keys.push(row.key);
  }
  const rowOf = (m: Metrics, key: string): MetricRow | undefined =>
    metricRows(m).find((row) => row.key === key);

  return (
    <div className="card" data-testid="corpus-metrics">
      <strong className="tiny">How reduced is what you have heard</strong>
      <p className="tiny muted" style={{ margin: "4px 0 8px" }}>
        Pooled word by word over {metrics.measured}{" "}
        {metrics.measured === 1 ? "analysis" : "analyses"} of {metrics.materials}{" "}
        {metrics.materials === 1 ? "recording" : "recordings"}
        {metrics.missing > 0 && <> ({metrics.missing} could not be measured: analysis.json gone)</>}
        . One column per phone engine: a different recognizer measures something different, so
        they are never added together.
      </p>
      <table className="detail metrics-table">
        <thead>
          <tr>
            <th scope="col">measure</th>
            {engines.map(([engine, m]) => (
              <th key={engine} scope="col">
                {engine} <span className="muted">({m.words.analyzed} words)</span>
              </th>
            ))}
            <th scope="col">report</th>
          </tr>
        </thead>
        <tbody>
          {keys.map((key) => {
            const ref = metrics.reference[key];
            const label =
              reference.metric_labels?.[key] ?? EXTRA_METRIC_LABELS[key] ?? key.replaceAll("_", " ");
            return (
              <tr key={key}>
                <th scope="row" className="tiny" style={{ fontWeight: 400 }}>
                  {label}
                </th>
                {engines.map(([engine, m]) => {
                  const row = rowOf(m, key);
                  return (
                    <td
                      key={engine}
                      title={row ? `${row.ratio.count} of ${row.ratio.of} ${row.unit}` : undefined}
                    >
                      {row ? (
                        <>
                          <span className="num">{fmtPct(row.ratio.pct)}</span>
                          <MetricMeter pct={row.ratio.pct} reference={ref} label={`${label}, ${engine}`} />
                        </>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  );
                })}
                <td className="tiny muted" title={ref ? `${ref.source}. ${ref.note}` : undefined}>
                  {ref ? `${ref.display} (${ref.cite})` : "—"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
