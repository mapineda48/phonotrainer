/** Panel de detalle de una palabra: qué dice el diccionario, qué esperaba el
 *  alineador y qué se pronunció de verdad, con todo reproducible. */

import { wordSpan } from "../lib/analysis";
import { fmtTime } from "../lib/format";
import { usePlayer } from "../player/PlayerProvider";
import { familyColor, phenomenonDescription, phenomenonLabel, useReference } from "../reference";
import type { Segment, Word } from "../types";
import { F0Chart } from "./F0Chart";
import { PhoneTimeline } from "./PhoneTimeline";

/** Fenómenos que ocurren en la frontera con la palabra SIGUIENTE: solo se oyen
 *  con ella. (`h_dropping` no está: es intra-palabra, su contexto es la anterior.) */
const BOUNDARY = new Set(["linking", "palatalization"]);

interface Props {
  word: Word;
  /** La siguiente del segmento, para poder oír y ver los fenómenos de frontera. */
  next: Word | null;
  segment: Segment;
  segmentIndex: number;
  isEmphasis: boolean;
  canPlay: boolean;
}

export function WordDetail({ word, next, segment, segmentIndex, isEmphasis, canPlay }: Props) {
  const player = usePlayer();
  const reference = useReference();
  const span = wordSpan(word);

  const crossesBoundary =
    next != null && (word.boundary_link_next || word.phenomena.some((p) => BOUNDARY.has(p)));
  const noPhones = word.realized_aligned.length === 0;
  const unreliable = noPhones || word.low_confidence || word.phenomena.includes("word_elision");

  const flags: string[] = [];
  if (word.oov) flags.push("fuera de diccionario: pronunciación predicha por g2p");
  if (word.alignment_fallback) flags.push("alineación aproximada (tiempos de Whisper)");
  if (word.attracted_count > 0)
    flags.push(`${word.attracted_count} fono(s) atraído(s) al canónico`);
  if (isEmphasis) flags.push("palabra enfatizada del segmento");

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
          disabled={!canPlay}
          onClick={() => player.play(span)}
          title="Reproducir la palabra (P)"
        >
          ▶ Palabra
        </button>
        {crossesBoundary && (
          <button
            type="button"
            className="btn btn--sm"
            disabled={!canPlay}
            title={`Oír el enlace con «${next.word}»`}
            onClick={() => player.play({ start: span.start, end: wordSpan(next).end })}
          >
            ▶ + {next.word}
          </button>
        )}
        <button
          type="button"
          className="btn btn--sm"
          disabled={!canPlay}
          onClick={() => player.play({ start: segment.start, end: segment.end })}
          title="Reproducir la frase entera (S)"
        >
          ▶ Frase
        </button>
      </div>

      {crossesBoundary && (
        <p className="tiny muted" style={{ margin: "8px 0 0" }}>
          Fenómeno de frontera: la comparación incluye «{next.word}», porque el enlace ocurre
          entre las dos palabras.
        </p>
      )}

      {unreliable && (
        <p className="tiny dim" style={{ margin: "8px 0 0" }}>
          {noPhones
            ? "El reconocedor no encontró ningún fono aquí: las etiquetas de abajo no son verificables (puede ser silencio, risas o música)."
            : "Confianza baja en este tramo: interpreta las etiquetas con cautela."}
        </p>
      )}

      <PhoneTimeline word={word} next={crossesBoundary ? next : null} />

      <dl className="deflist" style={{ marginTop: 14 }}>
        <dt title="Forma de cita de CMUdict">diccionario</dt>
        <dd className="ipa">/{word.dict_ipa}/</dd>
        <dt title="Lo que el alineador forzado esperaba (espeak-ng, ya con procesos nativos)">
          canónico
        </dt>
        <dd className="ipa">[{word.canonical_ipa}]</dd>
        <dt title="Lo que reconoció el modelo acústico">real</dt>
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
            <dt>forma reducida</dt>
            <dd>
              “{word.lexical_form}”
              {word.lexical_expansion && <span className="muted"> ← “{word.lexical_expansion}”</span>}
            </dd>
          </>
        )}
        <dt>divergencia</dt>
        <dd className="num">{word.diff_cost.toFixed(2)}</dd>
      </dl>
      <p className="tiny muted" style={{ margin: "4px 0 0" }}>
        <strong>diccionario</strong> = forma de cita · <strong>canónico</strong> = lo esperado por
        el alineador (espeak ya aplica procesos nativos) · <strong>real</strong> = lo reconocido.
        Divergencia = distancia media real↔canónico por fono (0 = idénticos).
      </p>

      {word.phenomena.length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div className="phones__label">fenómenos</div>
          <div style={unreliable ? { opacity: 0.6 } : undefined}>
            {word.phenomena.map((phenomenon) => {
              const family = reference.family_of[phenomenon];
              const description = phenomenonDescription(reference, phenomenon);
              return (
                <div key={phenomenon} style={{ marginBottom: 6 }}>
                  <span className="chip" title={description}>
                    {family && (
                      <span
                        className="chip__dot"
                        style={{ background: familyColor(family) }}
                        aria-hidden="true"
                      />
                    )}
                    {phenomenonLabel(reference, phenomenon)}
                  </span>
                  {/* La definición a la vista: "glotalización" no le dice nada
                      a quien está aprendiendo. */}
                  {description && (
                    <div className="tiny dim" style={{ marginTop: 2 }}>
                      {description}
                    </div>
                  )}
                </div>
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
          prosodia del segmento {segmentIndex + 1} ·{" "}
          {segment.f0_stats.mean != null
            ? `media ${segment.f0_stats.mean.toFixed(0)} Hz, rango ${segment.f0_stats.range?.toFixed(0)} Hz, final ${segment.f0_stats.final_contour}`
            : "sin F0"}
        </div>
        <F0Chart segment={segment} width={330} />
      </div>
    </div>
  );
}
