/** Diccionario vs canónico vs real, fono a fono, sobre el mismo eje de tiempo.
 *
 *  Es la vista que justifica el proyecto. Tres filas porque hacen falta tres:
 *
 *  - **diccionario** (CMUdict, sin tiempos): la forma de cita. Imprescindible
 *    porque espeak-ng ya aplica procesos nativos —el canónico de *better* es
 *    [bɛɾɚ], con flap—, así que sin esta fila el flapping es invisible.
 *  - **canónico alineado**: lo que el alineador forzado esperaba, en el tiempo.
 *  - **realmente pronunciado**: lo que reconoció el modelo acústico.
 *
 *  Los tiempos son picos de CTC (un frame de 20 ms), no segmentaciones: por eso
 *  las cajas se anotan como *instante detectado* y no como duración.
 */

import { useMemo } from "react";

import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import { useReference } from "../reference";
import type { AlignedPhone, Word } from "../types";

const PAD = 0.02; // s de margen al reproducir un fono suelto
/** Los tiempos son picos de un frame: dos fonos "a la vez" pueden no solaparse. */
const FRAME = 0.04;

interface Props {
  word: Word;
  /** Palabra siguiente, si el fenómeno cruza la frontera (linking, palatalización…). */
  next?: Word | null;
}

const overlaps = (a: AlignedPhone, b: AlignedPhone): boolean =>
  a[1] < b[2] + FRAME && b[1] < a[2] + FRAME;

function markDiff(own: AlignedPhone[], other: AlignedPhone[]): boolean[] {
  return own.map((phone) => !other.some((peer) => peer[0] === phone[0] && overlaps(phone, peer)));
}

/** Marcas que no son un fono: acento y longitud. */
const STRESS = /[ˈˌ]/;
/** Para comparar filas: /uː/ y /u/, /ˈɛ/ y /ɛ/ son el mismo fono. */
export const bareSymbol = (symbol: string): string => symbol.replace(/[ˈˌː]/g, "");

/**
 * Trocea una cadena IPA sin tiempos (la forma de diccionario) en fonos.
 *
 * `tokens` son los símbolos de más de un carácter del inventario inglés, que
 * publica el backend: sin ellos "aɪ" se partiría en dos y la fila de
 * diccionario marcaría como no pronunciada media palabra bien dicha.
 */
export function splitIpa(ipa: string, tokens: readonly string[] = []): string[] {
  const COMBINING = /[ʰ-˿̀-ͯ᷀-᷿ⁿːˑ]/;
  const multi = [...tokens].sort((a, b) => b.length - a.length);
  const out: string[] = [];
  let pending = "";                       // acento a la espera de su fono
  let i = 0;
  while (i < ipa.length) {
    const char = ipa[i];
    if (STRESS.test(char)) {
      pending += char;
      i += 1;
      continue;
    }
    const token = multi.find((candidate) => ipa.startsWith(candidate, i));
    if (token) {
      out.push(pending + token);
      pending = "";
      i += token.length;
      continue;
    }
    if (!pending && out.length > 0 && COMBINING.test(char)) out[out.length - 1] += char;
    else out.push(pending + char);
    pending = "";
    i += 1;
  }
  return out;
}

export function PhoneTimeline({ word, next }: Props) {
  const player = usePlayer();
  const reference = useReference();

  const view = useMemo(() => {
    const words = next ? [word, next] : [word];
    const canonical = words.flatMap((w) => w.canonical_aligned);
    const real = words.flatMap((w) => w.realized_aligned);
    const phones = [...canonical, ...real];
    const start = Math.min(...words.map((w) => w.start), ...phones.map((p) => p[1]));
    const end = Math.max(...words.map((w) => w.end), ...phones.map((p) => p[2]));
    const span = Math.max(end - start, 1e-3);
    const dictionary = splitIpa(word.dict_ipa, reference.ipa_tokens);
    // Solo contra lo pronunciado de ESTA palabra: si contáramos la siguiente,
    // su /t/ taparía la /t/ elidida de la nuestra.
    const saidHere = new Set(word.realized_aligned.map((p) => bareSymbol(p[0])));
    const lastReal = word.realized_aligned.at(-1);
    const firstNext = next?.realized_aligned[0];
    return {
      start,
      end,
      span,
      canonical,
      real,
      dictionary,
      // Un símbolo del diccionario que no aparece en lo pronunciado es justo lo
      // que el estudiante busca (la /t/ de "better", la /d/ de "and").
      dictionaryDiff: dictionary.map((symbol) => !saidHere.has(bareSymbol(symbol))),
      canonicalDiff: markDiff(canonical, real),
      realDiff: markDiff(real, canonical),
      boundary: next ? (next.start - start) / span : null,
      // Hueco real en la frontera: es la medida del enlace (con linking ronda
      // los 20 ms; una frontera normal, los 60).
      gapMs: lastReal && firstNext ? Math.round((firstNext[1] - lastReal[2]) * 1000) : null,
      tie:
        lastReal && firstNext
          ? (lastReal[2] + firstNext[1]) / 2
          : null,
    };
  }, [word, next, reference.ipa_tokens]);

  const playheadPct = useTimeSelector(player.clock, (time) => {
    if (time < view.start || time > view.end) return null;
    return Math.round(((time - view.start) / view.span) * 1000) / 10;
  });

  const row = (phones: AlignedPhone[], diff: boolean[], label: string, kind: string) => (
    <div>
      <div className="phones__label">{label}</div>
      <div className="phones__row">
        {phones.length === 0 && (
          <span className="tiny muted" style={{ position: "absolute", top: 4 }}>
            ∅ nada reconocido
          </span>
        )}
        {phones.map(([symbol, start, end], index) => (
          <button
            key={`${kind}-${index}-${symbol}`}
            type="button"
            className={`phone ${diff[index] ? "phone--diff" : ""}`}
            style={{
              left: `${((start - view.start) / view.span) * 100}%`,
              width: `${Math.max(((end - start) / view.span) * 100, 3)}%`,
            }}
            title={`${symbol} · detectado en ${start.toFixed(2)} s${
              diff[index] ? " · sin equivalente en la otra fila" : ""
            }`}
            onClick={() => player.play({ start: start - PAD, end: end + PAD })}
          >
            {symbol}
          </button>
        ))}
      </div>
    </div>
  );

  return (
    <div className="phones">
      <div className="phones__label">diccionario (forma de cita, sin tiempos)</div>
      <div className="phones__dict">
        {view.dictionary.map((symbol, index) => (
          <span
            key={`dict-${index}-${symbol}`}
            className={`phone phone--static ${view.dictionaryDiff[index] ? "phone--diff" : ""}`}
            title={
              view.dictionaryDiff[index]
                ? `${symbol}: en el diccionario pero no en lo pronunciado`
                : symbol
            }
          >
            {symbol}
          </span>
        ))}
      </div>

      <div className="phones__timed">
        {row(view.canonical, view.canonicalDiff, "canónico alineado", "can")}
        {row(view.real, view.realDiff, "realmente pronunciado", "real")}

        {view.tie !== null && view.gapMs !== null && (
          <span
            className="phones__tie"
            style={{ left: `${((view.tie - view.start) / view.span) * 100}%` }}
            title={`${view.gapMs} ms entre las dos palabras`}
          >
            ‿
          </span>
        )}

        <div className="phones__axis">
          <span className="phones__tick" style={{ left: 0, transform: "none" }}>
            {view.start.toFixed(2)} s
          </span>
          {view.boundary !== null && (
            <span className="phones__tick" style={{ left: `${view.boundary * 100}%` }}>
              frontera
            </span>
          )}
          <span className="phones__tick" style={{ right: 0, transform: "none" }}>
            {view.end.toFixed(2)} s
          </span>
        </div>
        {view.boundary !== null && (
          <div className="phones__boundary" style={{ left: `${view.boundary * 100}%` }} />
        )}
        {playheadPct !== null && (
          <div className="phones__playhead" style={{ left: `${playheadPct}%` }} />
        )}
      </div>
      {view.gapMs !== null && (
        <p className="tiny dim" style={{ margin: "10px 0 0" }}>
          Hueco en la frontera: <strong>{view.gapMs} ms</strong>
          {view.gapMs <= 30
            ? " — van pegadas, eso es el enlace (una frontera sin enlazar ronda los 60 ms)."
            : " — sin enlace apreciable."}
        </p>
      )}
      <p className="tiny muted" style={{ margin: "10px 0 0" }}>
        Cada caja marca el <strong>instante detectado</strong> (pico CTC de 20 ms), no la duración
        del fono. Pulsa una para oírla.
      </p>
    </div>
  );
}
