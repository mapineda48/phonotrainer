/** Canónico vs real, fono a fono, sobre el mismo eje de tiempo.
 *
 *  Es la vista que justifica el proyecto: se *ve* dónde el hablante nativo se
 *  aparta de la pronunciación de diccionario y se puede oír cada fono suelto.
 *  Un fono se marca como divergente cuando ningún fono de la otra fila coincide
 *  con él en símbolo y se solapa en el tiempo.
 */

import { useMemo } from "react";

import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import type { AlignedPhone, Word } from "../types";

const PAD = 0.02; // s de margen al reproducir un fono suelto

interface Props {
  word: Word;
}

const overlaps = (a: AlignedPhone, b: AlignedPhone): boolean => a[1] < b[2] && b[1] < a[2];

function markDiff(own: AlignedPhone[], other: AlignedPhone[]): boolean[] {
  return own.map((phone) => !other.some((peer) => peer[0] === phone[0] && overlaps(phone, peer)));
}

export function PhoneTimeline({ word }: Props) {
  const player = usePlayer();

  const view = useMemo(() => {
    const phones = [...word.canonical_aligned, ...word.realized_aligned];
    const start = Math.min(word.start, ...phones.map((p) => p[1]));
    const end = Math.max(word.end, ...phones.map((p) => p[2]));
    const span = Math.max(end - start, 1e-3);
    return {
      start,
      end,
      span,
      canonicalDiff: markDiff(word.canonical_aligned, word.realized_aligned),
      realDiff: markDiff(word.realized_aligned, word.canonical_aligned),
    };
  }, [word]);

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
            title={`${symbol} · ${start.toFixed(2)}–${end.toFixed(2)} s${
              diff[index] ? " · no coincide con la otra fila" : ""
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
      {row(word.canonical_aligned, view.canonicalDiff, "canónico alineado", "can")}
      {row(word.realized_aligned, view.realDiff, "realmente pronunciado", "real")}
      <div className="phones__axis">
        <span className="phones__tick" style={{ left: 0, transform: "none" }}>
          {view.start.toFixed(2)} s
        </span>
        <span className="phones__tick" style={{ right: 0, transform: "none" }}>
          {view.end.toFixed(2)} s
        </span>
      </div>
      {playheadPct !== null && (
        <div className="phones__playhead" style={{ left: `${playheadPct}%`, top: 16 }} />
      )}
    </div>
  );
}
