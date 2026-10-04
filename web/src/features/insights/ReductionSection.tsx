/** "How reduced is what you hear": the corpus measures of one phone engine, each against
 *  the report's published figure, plus what they mean for listening. Engines are never
 *  pooled: a different recognizer measures something different. */

import { useState } from "react";

import { Explain } from "../../didactic/Explain";
import { lookupGlossary, metricTerm } from "../../didactic/glossary";
import { plural } from "../../lib/format";
import { EXTRA_METRIC_LABELS, fmtPct, metricRows, standing, STANDING_TEXT, type MetricRow } from "../../lib/metrics";
import { useReference } from "../../reference";
import type { CorpusMetrics, MetricReference, Metrics, Reference } from "../../types";
import { Card, ChartFrame, DataTable, MetricMeter, Notice, RichText, Segmented, type MetricReferenceBand } from "../../ui";
import { engineName } from "./data";

/** The meter label: the glossary's plain title first, the backend's wording otherwise. */
export function metricLabel(reference: Reference, key: string): string {
  const fromGlossary = lookupGlossary(metricTerm(key))?.title;
  if (fromGlossary) return fromGlossary;
  const text = reference.metric_labels?.[key] ?? EXTRA_METRIC_LABELS[key] ?? key.replaceAll("_", " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** The report's figure as a meter band (0–1). A point estimate ("≈ 25 %") is drawn ±2
 *  points wide: the same tolerance the standing ("in line with the report") uses. */
export function referenceBand(ref: MetricReference | undefined): MetricReferenceBand | undefined {
  if (!ref || ref.low == null) return undefined;
  const point = ref.high != null && ref.high === ref.low;
  const low = point ? ref.low - 2 : ref.low;
  const high = ref.high == null ? undefined : point ? ref.high + 2 : ref.high;
  return {
    low: Math.max(0, low) / 100,
    high: high == null ? undefined : Math.min(100, high) / 100,
    text: ref.display,
    source: ref.cite,
  };
}

/** "1 analysis", "3 analyses": the irregular plurals the page needs. */
export function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

function rowDetail(row: MetricRow, ref: MetricReference | undefined): string {
  const base = `${row.ratio.count} of ${row.ratio.of} ${row.unit}`;
  const where = standing(row.ratio.pct, ref);
  return where ? `${base} · ${STANDING_TEXT[where]}` : base;
}

export function ReductionSection({ metrics }: { metrics: CorpusMetrics }) {
  const reference = useReference();
  const engines = Object.entries(metrics.by_engine).filter(
    (entry): entry is [string, Metrics] => entry[1] != null,
  );
  const [chosen, setChosen] = useState<string | null>(null);
  if (engines.length === 0) return null;

  const engine = chosen && metrics.by_engine[chosen] ? chosen : engines[0][0];
  const pool = metrics.by_engine[engine] as Metrics;
  const rows = metricRows(pool);

  // every measure any engine has, so one only an engine reports still gets a row
  const keys: string[] = [];
  for (const [, m] of engines) for (const row of metricRows(m)) if (!keys.includes(row.key)) keys.push(row.key);

  return (
    <Card
      title="How reduced is what you hear"
      description={
        <>
          Measured word by word over {count(metrics.measured, "analysis", "analyses")} of{" "}
          {plural(metrics.materials, "recording")}, next to what published research found in
          conversation.
          {metrics.missing > 0 && <> {metrics.missing} could not be measured: their analysis.json is gone.</>}
        </>
      }
    >
      <div className="flex flex-col gap-5">
        {engines.length > 1 && (
          <div className="flex flex-wrap items-end gap-2">
            <Segmented
              label="Phone engine"
              options={engines.map(([key]) => ({ id: key, label: engineName(key) }))}
              value={engine}
              onChange={setChosen}
            />
            <Explain term="engine" buttonLabel="What's this: phone engine" />
            <p className="basis-full text-sm text-ink-2">
              Each engine is measured on its own: they hear differently, so their numbers are
              never added together.
            </p>
          </div>
        )}

        {pool.mixed_rules && (
          <Notice tone="caution" title="Mixed rule versions">
            Some of these analyses were labeled by an older version of the rules. The measures
            still count every word, but analyze those recordings again for figures that compare
            like with like.
          </Notice>
        )}

        <ChartFrame
          level={3}
          title={`Your corpus with ${engineName(engine)}`}
          summary={
            <>
              Each bar is your corpus; the hatched band is the published figure. Measured on{" "}
              {plural(pool.words.analyzed, "word")}
              {pool.words.low_confidence > 0 && (
                <> ({pool.words.low_confidence} low-confidence words left out)</>
              )}
              .
            </>
          }
          table={
            <DataTable
              caption="One column per phone engine; engines are never added together."
              columns={[
                "Measure",
                ...engines.map(([key, m]) => `${engineName(key)} (${plural(m.words.analyzed, "word")})`),
                "Published",
              ]}
              rows={keys.map((key) => {
                const ref = metrics.reference[key];
                return [
                  metricLabel(reference, key),
                  ...engines.map(([, m]) => {
                    const row = metricRows(m).find((r) => r.key === key);
                    return row ? fmtPct(row.ratio.pct) : "—";
                  }),
                  ref ? `${ref.display} (${ref.cite})` : "—",
                ];
              })}
            />
          }
        >
          <div className="grid gap-x-8 gap-y-5 md:grid-cols-2">
            {rows.map((row) => {
              const ref = metrics.reference[row.key];
              const label = metricLabel(reference, row.key);
              return (
                <MetricMeter
                  key={row.key}
                  label={label}
                  ariaLabel={`${label}, ${engineName(engine)}`}
                  value={row.ratio.pct == null ? null : row.ratio.pct / 100}
                  detail={rowDetail(row, ref)}
                  reference={referenceBand(ref)}
                  explain={<Explain term={metricTerm(row.key)} buttonLabel={`What's this: ${label}`} />}
                />
              );
            })}
          </div>
        </ChartFrame>

        <Takeaways pool={pool} />
      </div>
    </Card>
  );
}

/** The measures turned into what to expect when listening, in plain words. */
export function takeaways(pool: Metrics): string[] {
  const out: string[] = [];
  const pct = (r: { pct: number | null; of: number }) => (r.of > 0 ? r.pct : null);
  const deviate = pct(pool.deviate);
  if (deviate != null && deviate >= 5) {
    out.push(
      `About ${Math.max(1, Math.round(deviate / 10))} in 10 words you hear do not sound the way the dictionary writes them.`,
    );
  }
  const segment = pct(pool.segment_loss);
  if (segment != null && segment > 0) {
    out.push(
      `About 1 word in ${Math.max(2, Math.round(100 / segment))} loses a whole sound: listen for what is missing, not only for what is there.`,
    );
  }
  const syllable = pct(pool.syllable_loss);
  if (syllable != null && syllable > 0) {
    out.push(
      `About 1 word in ${Math.max(2, Math.round(100 / syllable))} loses a whole syllable, so words come out shorter than you expect.`,
    );
  }
  const weak = pct(pool.weak_forms.greedy);
  if (weak != null) {
    out.push(
      `${Math.round(weak)} % of the small grammar words (to, of, and…) came out weak: expect [tə], [əv], [ən] rather than the dictionary vowel.`,
    );
  }
  const flap = pct(pool.flapping);
  if (flap != null) {
    out.push(
      `When a t or d sat between vowels, it became a quick tap [ɾ] ${Math.round(flap)} % of the time, like the r in Spanish "pero".`,
    );
  }
  return out;
}

function Takeaways({ pool }: { pool: Metrics }) {
  const lines = takeaways(pool);
  if (lines.length === 0) return null;
  return (
    <section aria-labelledby="insights-takeaways" className="rounded-card bg-surface-2 p-4">
      <h3 id="insights-takeaways" className="text-base font-semibold text-ink">
        What this means for your listening
      </h3>
      <ul className="mt-2 flex list-disc flex-col gap-1.5 ps-5 text-base text-ink-2">
        {lines.map((line) => (
          <li key={line}>
            <RichText text={line} />
          </li>
        ))}
      </ul>
    </section>
  );
}
