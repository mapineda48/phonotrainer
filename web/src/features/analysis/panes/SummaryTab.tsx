/** What this clip shows: how reduced the speech is, next to published research, and
 *  which changes it contains — each row a filter for the transcript. */

import { Check, Ear, Mic, X } from "lucide-react";

import { Explain } from "../../../didactic/Explain";
import { metricTerm } from "../../../didactic/glossary";
import { phenomenaByFrequency } from "../../../lib/analysis";
import { fmtDuration, plural } from "../../../lib/format";
import { FINAL_T_LABEL, metricRows, standing, STANDING_TEXT, type MetricRow } from "../../../lib/metrics";
import { phenomenonLabel, phenomenonPractice, useReference } from "../../../reference";
import type { Analysis, FinalTOutcome, Metrics } from "../../../types";
import {
  Button,
  ButtonRow,
  ChartFrame,
  cn,
  DataTable,
  Disclosure,
  familyColor,
  MetricMeter,
  PhenomenonIcon,
  PRACTICE_TEXT,
  ToggleButton,
} from "../../../ui";
import { engineOf, phenomenaByPractice } from "../lib/words";

interface Props {
  analysis: Analysis;
  filter: ReadonlySet<string>;
  onToggle: (phenomenon: string) => void;
  onSetFilter: (phenomena: string[]) => void;
}

const FINAL_T_ORDER: FinalTOutcome[] = ["flap", "glottal", "unreleased", "released", "other"];

const sameSet = (names: string[], filter: ReadonlySet<string>) =>
  names.length > 0 && names.length === filter.size && names.every((name) => filter.has(name));

function Measures({ metrics }: { metrics: Metrics }) {
  const reference = useReference();
  const engine = metrics.engine ?? "espeak";
  return (
    <section aria-labelledby="measures-title" className="flex flex-col gap-4" data-testid="reduction-metrics">
      <div className="flex flex-col gap-1">
        <h3 id="measures-title" className="text-lg font-semibold text-ink">
          How reduced is this speech?
        </h3>
        <p className="text-sm text-ink-2">
          Measured over {metrics.words.analyzed} words with the{" "}
          <Explain term="engine">
            <span>
              <strong className="text-ink">{engine}</strong> engine
            </span>
          </Explain>
          {metrics.words.low_confidence > 0 && (
            <> ({plural(metrics.words.low_confidence, "low-confidence word")} left out)</>
          )}
          . The hatched band is the published figure for conversational American English.
          {engine === "espeak" && " The espeak engine tends to hear the dictionary form, so expect lower figures."}
        </p>
      </div>
      {metricRows(metrics).map((row: MetricRow) => {
        const ref = reference.metrics_reference?.[row.key];
        const label =
          reference.metric_labels?.[row.key] ??
          (row.key === "weak_forms_variant" ? "function words scored weak (form scoring)" : row.key.replaceAll("_", " "));
        const where = standing(row.ratio.pct, ref);
        return (
          <MetricMeter
            key={row.key}
            label={label.charAt(0).toUpperCase() + label.slice(1)}
            value={row.ratio.pct == null ? null : row.ratio.pct / 100}
            explain={<Explain term={metricTerm(row.key)} />}
            reference={
              ref && ref.low != null
                ? {
                    low: ref.low / 100,
                    high: ref.high == null ? undefined : ref.high / 100,
                    text: ref.display,
                    source: ref.cite,
                  }
                : undefined
            }
            detail={
              <span data-testid={`metric-${row.key}`}>
                {row.ratio.of > 0 ? `${row.ratio.count} of ${row.ratio.of} ${row.unit}` : `no ${row.unit}`}
                {where && <> · {STANDING_TEXT[where]}</>}
              </span>
            }
          />
        );
      })}
      {metrics.final_t_prevocalic.of > 0 && (
        <p className="text-sm text-ink" data-testid="final-t">
          <span className="text-ink-2">Final /t/ before a vowel ({metrics.final_t_prevocalic.of}):</span>{" "}
          {FINAL_T_ORDER.filter((key) => metrics.final_t_prevocalic[key] > 0)
            .map((key) => `${FINAL_T_LABEL[key]} ${metrics.final_t_prevocalic[key]}`)
            .join(" · ")}
        </p>
      )}
      {metrics.rhythm && (
        <p className="text-sm text-ink-2">
          <Explain term="rhythm">Rhythm</Explain> (approximate): nPVI {metrics.rhythm.npvi.toFixed(0)} over{" "}
          {metrics.rhythm.n_pairs} pairs of syllable intervals — a measure within this clip, not comparable to published
          values.
        </p>
      )}
    </section>
  );
}

function PhenomenaChart({ analysis, filter, onToggle }: Omit<Props, "onSetFilter">) {
  const reference = useReference();
  const counts = phenomenaByFrequency(analysis);
  const max = counts.length ? counts[0][1] : 1;
  if (counts.length === 0) return <p className="text-sm text-ink-2">No changes were detected in this clip.</p>;
  return (
    <ChartFrame
      title="Changes in this clip"
      summary={`${counts.length} kinds of change; the most frequent is ${phenomenonLabel(reference, counts[0][0])} (${counts[0][1]}). Press a row to show only those words in the transcript.`}
      table={
        <DataTable
          columns={["Change", "Family", "Count", "Advice"]}
          rows={counts.map(([name, count]) => {
            const family = reference.family_of[name];
            const familyLabel = reference.families.find((f) => f.key === family)?.label ?? "—";
            const practice = phenomenonPractice(reference, name);
            return [phenomenonLabel(reference, name), familyLabel, count, practice ? PRACTICE_TEXT[practice.practice] : "—"];
          })}
        />
      }
    >
      <ul className="flex flex-col gap-1" aria-label="Filter the transcript by change">
        {counts.map(([name, count]) => {
          const family = reference.family_of[name] ?? null;
          const on = filter.has(name);
          const practice = phenomenonPractice(reference, name);
          const PracticeIcon = practice?.practice === "produce" ? Mic : Ear;
          return (
            <li key={name}>
              <ToggleButton
                variant="quiet"
                isSelected={on}
                onChange={() => onToggle(name)}
                aria-label={`${phenomenonLabel(reference, name)}, ${plural(count, "occurrence")}${
                  practice ? `, ${PRACTICE_TEXT[practice.practice].toLowerCase()}` : ""
                }`}
                className="grid h-auto w-full grid-cols-[minmax(0,11rem)_1fr_auto] items-center gap-3 px-2 py-1.5 text-left font-normal data-[selected]:bg-surface-2 data-[selected]:text-ink data-[selected]:shadow-[inset_0_0_0_2px_var(--ink)]"
              >
                <span className="flex min-w-0 items-center gap-1.5">
                  {on && <Check size={15} aria-hidden="true" className="shrink-0" />}
                  <PhenomenonIcon name={name} size={15} />
                  <span className={cn("truncate text-sm", on && "font-semibold")}>{phenomenonLabel(reference, name)}</span>
                  {practice && <PracticeIcon size={13} aria-hidden="true" className="shrink-0 opacity-80" />}
                </span>
                <span className="relative h-3 rounded-e-[4px]" aria-hidden="true">
                  <span
                    className="absolute inset-y-0 left-0 rounded-e-[4px]"
                    style={{
                      width: `${Math.max(2, (count / max) * 100)}%`,
                      background: family ? familyColor(family) : "var(--ink-muted)",
                    }}
                  />
                </span>
                <span className="text-sm tabular-nums">{count}</span>
              </ToggleButton>
            </li>
          );
        })}
      </ul>
    </ChartFrame>
  );
}

function AboutAnalysis({ analysis }: { analysis: Analysis }) {
  const meta = analysis.meta;
  const words = analysis.segments.reduce((total, segment) => total + segment.words.length, 0);
  const separation = meta.dialogue_separation;
  return (
    <Disclosure title="About this analysis" defaultExpanded={false}>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
        <dt className="text-ink-2">Length</dt>
        <dd className="m-0 tabular-nums">
          {fmtDuration(meta.duration)} · {plural(analysis.segments.length, "phrase")} · {plural(words, "word")}
        </dd>
        <dt className="text-ink-2">
          <Explain term="engine">Phone engine</Explain>
        </dt>
        <dd className="m-0">{engineOf(analysis)}</dd>
        <dt className="text-ink-2">Sounds model</dt>
        <dd className="m-0 break-all">{meta.models.phones}</dd>
        <dt className="text-ink-2">Speech model</dt>
        <dd className="m-0">{meta.models.asr}</dd>
        {separation && (
          <>
            <dt className="text-ink-2">
              <Explain term="separation">Dialogue</Explain>
            </dt>
            <dd className="m-0">
              {separation.applied
                ? `separated from music and effects (${separation.model ?? "separator"}${
                    separation.seconds != null ? `, ${separation.seconds.toFixed(0)} s` : ""
                  })`
                : separation.error
                  ? `not separated: ${separation.error}`
                  : "not separated (original mix)"}
            </dd>
          </>
        )}
        <dt className="text-ink-2">
          <Explain term="attraction">Attraction</Explain>
        </dt>
        <dd className="m-0">
          {meta.attraction ? "on" : "off"} · {plural(meta.phone_cleanup.attracted_phones, "sound")} kept as expected,{" "}
          {meta.phone_cleanup.normalized_phones} cleaned up
        </dd>
      </dl>
    </Disclosure>
  );
}

export function SummaryTab({ analysis, filter, onToggle, onSetFilter }: Props) {
  const reference = useReference();
  const toProduce = phenomenaByPractice(analysis, reference, "produce");
  const toRecognize = phenomenaByPractice(analysis, reference, "understand");
  const metrics = analysis.summary.metrics;

  return (
    <div className="flex flex-col gap-6">
      {/* the pane's own level-2 heading: its sections are h3, and on a narrow screen the
          transcript's h2 is in another tab, so nothing else would sit between them and the h1 */}
      <h2 className="sr-only">Summary of this clip</h2>
      <p className="text-sm text-ink-2">What this clip shows, next to published figures.</p>

      {reference.practice && (toProduce.length > 0 || toRecognize.length > 0) && (
        <div className="flex flex-col gap-2" data-testid="practice-filter">
          <p className="text-sm font-semibold text-ink">Show in the transcript only</p>
          <ButtonRow>
            <ToggleButton
              size="sm"
              icon={Mic}
              isSelected={sameSet(toProduce, filter)}
              isDisabled={toProduce.length === 0}
              onChange={() => onSetFilter(sameSet(toProduce, filter) ? [] : toProduce)}
            >
              {PRACTICE_TEXT.produce}
            </ToggleButton>
            <ToggleButton
              size="sm"
              icon={Ear}
              isSelected={sameSet(toRecognize, filter)}
              isDisabled={toRecognize.length === 0}
              onChange={() => onSetFilter(sameSet(toRecognize, filter) ? [] : toRecognize)}
            >
              {PRACTICE_TEXT.understand}
            </ToggleButton>
            {filter.size > 0 && (
              <Button size="sm" variant="quiet" icon={X} onPress={() => onSetFilter([])}>
                Clear filter
              </Button>
            )}
          </ButtonRow>
        </div>
      )}

      <PhenomenaChart analysis={analysis} filter={filter} onToggle={onToggle} />

      {metrics && <Measures metrics={metrics} />}

      <AboutAnalysis analysis={analysis} />
    </div>
  );
}
