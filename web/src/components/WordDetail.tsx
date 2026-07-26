/** Panel de detalle de una palabra: qué dice el diccionario, qué esperaba el
 *  alineador y qué se pronunció de verdad, con todo reproducible. */

import { fmtTime } from "../lib/format";
import { wordSpan } from "../lib/analysis";
import { usePlayer } from "../player/PlayerProvider";
import { familyColor, phenomenonLabel, useReference } from "../reference";
import type { Segment, Word } from "../types";
import { F0Chart } from "./F0Chart";
import { PhoneTimeline } from "./PhoneTimeline";

interface Props {
  word: Word;
  segment: Segment;
  segmentIndex: number;
  isEmphasis: boolean;
}

export function WordDetail({ word, segment, segmentIndex, isEmphasis }: Props) {
  const player = usePlayer();
  const reference = useReference();
  const span = wordSpan(word);

  const flags: string[] = [];
  if (word.low_confidence) flags.push("baja confianza (posible silencio o risas)");
  if (word.oov) flags.push("fuera de diccionario: pronunciación predicha por g2p");
  if (word.alignment_fallback) flags.push("alineación aproximada (se usaron los tiempos de Whisper)");
  if (word.attracted_count > 0)
    flags.push(`${word.attracted_count} fono(s) atraído(s) al canónico`);
  if (isEmphasis) flags.push("palabra enfatizada del segmento");
  if (word.boundary_link_next) flags.push("enlaza con la palabra siguiente");

  return (
    <div className="panel__body">
      <div className="row">
        <h3 style={{ margin: 0, fontSize: 18 }}>{word.word}</h3>
        <span className="muted tiny num">
          {fmtTime(word.start)}–{fmtTime(word.end)}
        </span>
        <span className="spacer" />
        <button
          type="button"
          className="btn btn--sm"
          onClick={() => player.play(span)}
          title="Reproducir la palabra (P)"
        >
          ▶ Palabra
        </button>
        <button
          type="button"
          className="btn btn--sm"
          onClick={() => player.play({ start: segment.start, end: segment.end })}
          title="Reproducir la frase entera (S)"
        >
          ▶ Frase
        </button>
      </div>

      <PhoneTimeline word={word} />

      <dl className="deflist" style={{ marginTop: 14 }}>
        <dt>diccionario</dt>
        <dd className="ipa">/{word.dict_ipa}/</dd>
        <dt>canónico</dt>
        <dd className="ipa">[{word.canonical_ipa}]</dd>
        <dt>real</dt>
        <dd className="ipa">[{word.realized_ipa || "∅"}]</dd>
        {word.realized_raw_ipa && (
          <>
            <dt>real (crudo)</dt>
            <dd className="ipa" title="Salida del reconocedor antes de sanear y atraer">
              [{word.realized_raw_ipa}]
            </dd>
          </>
        )}
        {word.lexical_form && (
          <>
            <dt>forma plena</dt>
            <dd>“{word.lexical_form}”</dd>
          </>
        )}
        <dt title="Coste medio del diff por fono canónico">divergencia</dt>
        <dd className="num">{word.diff_cost.toFixed(2)}</dd>
      </dl>

      {word.phenomena.length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div className="phones__label">fenómenos</div>
          <div className="chips">
            {word.phenomena.map((phenomenon) => {
              const family = reference.family_of[phenomenon];
              return (
                <span key={phenomenon} className="chip">
                  {family && (
                    <span
                      className="chip__dot"
                      style={{ background: familyColor(family) }}
                      aria-hidden="true"
                    />
                  )}
                  {phenomenonLabel(reference, phenomenon)}
                </span>
              );
            })}
          </div>
        </div>
      )}

      {flags.length > 0 && (
        <ul className="tiny dim" style={{ margin: "14px 0 0", paddingLeft: 18 }}>
          {flags.map((flag) => (
            <li key={flag}>{flag}</li>
          ))}
        </ul>
      )}

      <div style={{ marginTop: 18 }}>
        <div className="phones__label">
          prosodia del segmento {segmentIndex + 1} · {segment.f0_stats.mean != null
            ? `media ${segment.f0_stats.mean.toFixed(0)} Hz, rango ${segment.f0_stats.range?.toFixed(0)} Hz, final ${segment.f0_stats.final_contour}`
            : "sin F0"}
        </div>
        <F0Chart segment={segment} width={330} />
      </div>
    </div>
  );
}
