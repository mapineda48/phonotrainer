/** Analysis summary: how often each phenomenon occurs, plus a filter over them.
 *  The bars act as the index of the class: click one and the transcript narrows
 *  to those words, which you can then walk through with N. */

import { phenomenaByFrequency } from "../lib/analysis";
import { plural } from "../lib/format";
import {
  familyColor,
  phenomenonLabel,
  phenomenonPractice,
  PRACTICE_LABEL,
  useReference,
} from "../reference";
import type { Analysis, Practice } from "../types";
import { ReductionMetrics } from "./ReductionMetrics";

interface Props {
  analysis: Analysis;
  filter: ReadonlySet<string>;
  onToggle: (phenomenon: string) => void;
  onClear: () => void;
  /** Replace the whole filter (the "safe to produce" shortcut). */
  onSetFilter?: (phenomena: string[]) => void;
}

const PRACTICE_TAG: Record<Practice["practice"], string> = { produce: "say", understand: "hear" };

export function SummaryPanel({ analysis, filter, onToggle, onClear, onSetFilter }: Props) {
  const reference = useReference();
  const counts = phenomenaByFrequency(analysis);
  const byPractice = (kind: Practice["practice"]) =>
    counts
      .map(([name]) => name)
      .filter((name) => phenomenonPractice(reference, name)?.practice === kind);
  const toProduce = byPractice("produce");
  const toRecognize = byPractice("understand");
  const sameSet = (names: string[]) =>
    names.length > 0 && names.length === filter.size && names.every((name) => filter.has(name));
  const max = counts.length ? counts[0][1] : 1;
  const meta = analysis.meta;
  // Phenomena with no color of their own (marked typographically, not by hue).
  const noFamily = counts
    .filter(([name]) => !reference.family_of[name])
    .map(([name]) => phenomenonLabel(reference, name));

  return (
    <div className="panel__body">
      <div className="row" style={{ marginBottom: 8 }}>
        <strong className="tiny">Phenomena detected</strong>
        <span className="spacer" />
        {filter.size > 0 && (
          <button type="button" className="btn btn--sm" onClick={onClear}>
            Clear filter
          </button>
        )}
      </div>

      {counts.length === 0 && <p className="muted tiny">No phenomena were detected.</p>}

      {onSetFilter && reference.practice && counts.length > 0 && (
        <div className="row tiny" style={{ gap: 6, marginBottom: 8 }} data-testid="practice-filter">
          <span className="muted">Show only:</span>
          <button
            type="button"
            className="btn btn--sm"
            aria-pressed={sameSet(toProduce)}
            disabled={toProduce.length === 0}
            title="Phenomena the report says a learner can safely produce"
            onClick={() => onSetFilter(sameSet(toProduce) ? [] : toProduce)}
          >
            {PRACTICE_LABEL.produce}
          </button>
          <button
            type="button"
            className="btn btn--sm"
            aria-pressed={sameSet(toRecognize)}
            disabled={toRecognize.length === 0}
            title="Phenomena to learn to recognize, not to imitate"
            onClick={() => onSetFilter(sameSet(toRecognize) ? [] : toRecognize)}
          >
            {PRACTICE_LABEL.understand}
          </button>
        </div>
      )}

      <div className="bars">
        {counts.map(([name, count]) => {
          const family = reference.family_of[name];
          const on = filter.has(name);
          const practice = phenomenonPractice(reference, name);
          return (
            <button
              key={name}
              type="button"
              className="bars__row"
              aria-pressed={on}
              title={`${plural(count, "occurrence")} · click to ${on ? "remove from" : "add to"} the filter${
                practice ? `\n${PRACTICE_LABEL[practice.practice]} (${practice.register}): ${practice.why}` : ""
              }`}
              onClick={() => onToggle(name)}
            >
              <span style={{ fontWeight: on ? 650 : 400 }}>
                {phenomenonLabel(reference, name)}
                {practice && " "}
                {practice && (
                  <span className={`bars__tag bars__tag--${practice.practice}`}>
                    {PRACTICE_TAG[practice.practice]}
                  </span>
                )}
              </span>
              <span
                className="bar"
                style={{
                  width: `${Math.max(2, (count / max) * 100)}%`,
                  background: family ? familyColor(family) : "var(--ink-muted)",
                  opacity: filter.size === 0 || on ? 1 : 0.35,
                }}
              />
              <span className="bars__n">{count}</span>
            </button>
          );
        })}
      </div>

      <div style={{ marginTop: 18 }}>
        <div className="phones__label">color families</div>
        <div className="legend">
          {reference.families.map((family) => (
            <span key={family.key} className="legend__item">
              <span
                className="chip__dot"
                style={{ background: familyColor(family.key) }}
                aria-hidden="true"
              />
              <span>
                {family.label} <span className="muted">({family.member_labels.join(", ")})</span>
              </span>
            </span>
          ))}
          <span className="legend__item">
            <span
              className="chip__dot"
              style={{ background: "var(--ink-muted)" }}
              aria-hidden="true"
            />
            <span>
              No family{" "}
              <span className="muted">({noFamily.join(", ") || "—"}): marked in the text</span>
            </span>
          </span>
        </div>
        <p className="tiny muted" style={{ marginTop: 8 }}>
          ⋯ dotted underline = lexical contraction · ‿ = linking to the next word · bold =
          emphasized word · dimmed = low confidence
          {reference.practice && (
            <> · “say” = safe to produce, “hear” = recognize it, don't imitate it</>
          )}
        </p>
      </div>

      {analysis.summary.metrics && (
        <div style={{ marginTop: 18 }}>
          <div className="phones__label">how reduced is this speech</div>
          <ReductionMetrics metrics={analysis.summary.metrics} reference={reference} />
        </div>
      )}

      <div style={{ marginTop: 18 }}>
        <div className="phones__label">analysis</div>
        <dl className="deflist">
          <dt>duration</dt>
          <dd className="num">{meta.duration.toFixed(1)} s</dd>
          <dt>segments</dt>
          <dd className="num">{analysis.segments.length}</dd>
          <dt>words</dt>
          <dd className="num">
            {analysis.segments.reduce((total, segment) => total + segment.words.length, 0)}
          </dd>
          <dt>ASR</dt>
          <dd>{meta.models.asr}</dd>
          {meta.phone_engine && (
            <>
              <dt>engine</dt>
              <dd>{meta.phone_engine}</dd>
            </>
          )}
          <dt>phones</dt>
          <dd style={{ wordBreak: "break-all" }}>{meta.models.phones}</dd>
          {meta.dialogue_separation && (
            <>
              <dt>dialogue</dt>
              <dd>
                {meta.dialogue_separation.applied
                  ? `isolated from music and effects (${meta.dialogue_separation.model ?? "separator"}${
                      meta.dialogue_separation.seconds != null
                        ? `, ${meta.dialogue_separation.seconds.toFixed(0)} s`
                        : ""
                    })`
                  : meta.dialogue_separation.error
                    ? `not separated: ${meta.dialogue_separation.error}`
                    : "not separated (original mix)"}
              </dd>
            </>
          )}
          <dt>attraction</dt>
          <dd>
            {meta.attraction ? "enabled" : "disabled"} ·{" "}
            {meta.phone_cleanup.attracted_phones} phones attracted,{" "}
            {meta.phone_cleanup.normalized_phones} cleaned up
          </dd>
        </dl>
      </div>
    </div>
  );
}
