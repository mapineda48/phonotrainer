/** Analysis summary: how often each phenomenon occurs, plus a filter over them.
 *  The bars act as the index of the class: click one and the transcript narrows
 *  to those words, which you can then walk through with N. */

import { phenomenaByFrequency } from "../lib/analysis";
import { familyColor, phenomenonLabel, useReference } from "../reference";
import type { Analysis } from "../types";

interface Props {
  analysis: Analysis;
  filter: ReadonlySet<string>;
  onToggle: (phenomenon: string) => void;
  onClear: () => void;
}

export function SummaryPanel({ analysis, filter, onToggle, onClear }: Props) {
  const reference = useReference();
  const counts = phenomenaByFrequency(analysis);
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

      <div className="bars">
        {counts.map(([name, count]) => {
          const family = reference.family_of[name];
          const on = filter.has(name);
          return (
            <button
              key={name}
              type="button"
              className="bars__row"
              aria-pressed={on}
              title={`${count} occurrences · click to ${on ? "remove from" : "add to"} the filter`}
              onClick={() => onToggle(name)}
            >
              <span style={{ fontWeight: on ? 650 : 400 }}>
                {phenomenonLabel(reference, name)}
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
        </p>
      </div>

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
          <dt>phones</dt>
          <dd style={{ wordBreak: "break-all" }}>{meta.models.phones}</dd>
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
