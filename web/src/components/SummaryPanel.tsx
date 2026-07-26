/** Resumen del análisis: cuántas veces aparece cada fenómeno y filtro por ellos.
 *  Las barras son el índice de la clase: al pulsar una, la transcripción se queda
 *  con esas palabras y se puede saltar de una a otra con N. */

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
  // Fenómenos que no tienen color propio (se marcan con tipografía, no con tono).
  const noFamily = counts
    .filter(([name]) => !reference.family_of[name])
    .map(([name]) => phenomenonLabel(reference, name));

  return (
    <div className="panel__body">
      <div className="row" style={{ marginBottom: 8 }}>
        <strong className="tiny">Fenómenos detectados</strong>
        <span className="spacer" />
        {filter.size > 0 && (
          <button type="button" className="btn btn--sm" onClick={onClear}>
            Quitar filtro
          </button>
        )}
      </div>

      {counts.length === 0 && <p className="muted tiny">No se detectó ningún fenómeno.</p>}

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
              title={`${count} apariciones · pulsa para ${on ? "quitar del" : "añadir al"} filtro`}
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
        <div className="phones__label">familias de color</div>
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
              Sin familia{" "}
              <span className="muted">({noFamily.join(", ") || "—"}): se marcan en el texto</span>
            </span>
          </span>
        </div>
        <p className="tiny muted" style={{ marginTop: 8 }}>
          ⋯ subrayado punteado = contracción léxica · ‿ = enlace con la siguiente · negrita =
          palabra enfatizada · atenuada = baja confianza
        </p>
      </div>

      <div style={{ marginTop: 18 }}>
        <div className="phones__label">análisis</div>
        <dl className="deflist">
          <dt>duración</dt>
          <dd className="num">{meta.duration.toFixed(1)} s</dd>
          <dt>segmentos</dt>
          <dd className="num">{analysis.segments.length}</dd>
          <dt>palabras</dt>
          <dd className="num">
            {analysis.segments.reduce((total, segment) => total + segment.words.length, 0)}
          </dd>
          <dt>ASR</dt>
          <dd>{meta.models.asr}</dd>
          <dt>fonos</dt>
          <dd style={{ wordBreak: "break-all" }}>{meta.models.phones}</dd>
          <dt>atracción</dt>
          <dd>
            {meta.attraction ? "activada" : "desactivada"} ·{" "}
            {meta.phone_cleanup.attracted_phones} fonos atraídos,{" "}
            {meta.phone_cleanup.normalized_phones} saneados
          </dd>
        </dl>
      </div>
    </div>
  );
}
