/** Controles de reproducción: lo que se toca cada pocos segundos al estudiar. */

import { fmtTime } from "../lib/format";
import { useTime } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";

const RATES = [0.5, 0.75, 1];

interface Props {
  duration: number;
  /** Descripción de lo que está acotado ahora mismo ("palabra «does»"). */
  spanLabel: string | null;
  /** Falso cuando el análisis se importó sin audio.wav: nada que reproducir. */
  enabled: boolean;
  /** La onda: se inyecta para que la barra no dependa de cómo se dibuja. */
  children?: React.ReactNode;
}

export function PlayerBar({ duration, spanLabel, enabled, children }: Props) {
  const player = usePlayer();
  const time = useTime(player.clock);

  return (
    <div className="player">
      <button
        type="button"
        className="btn btn--icon btn--play"
        aria-label={player.playing ? "Pausa" : "Reproducir"}
        disabled={!enabled}
        onClick={() => player.toggle()}
      >
        {player.playing ? "❚❚" : "▶"}
      </button>

      <span className="player__time num">
        {fmtTime(time)} <span className="muted">/ {fmtTime(duration)}</span>
      </span>

      {children}

      <button
        type="button"
        className="btn btn--sm"
        aria-pressed={player.loop}
        disabled={!enabled}
        title="Repetir el fragmento seleccionado (tecla L)"
        onClick={() => player.setLoop(!player.loop)}
      >
        ⟳ Bucle
      </button>

      <label className="row tiny" style={{ gap: 4 }}>
        <span className="sr-only">Velocidad</span>
        <select
          className="input"
          style={{ width: "auto", padding: "3px 6px" }}
          aria-label="Velocidad de reproducción"
          disabled={!enabled}
          value={player.rate}
          onChange={(event) => player.setRate(Number(event.target.value))}
          title="Velocidad de reproducción (mantiene el tono)"
        >
          {RATES.map((rate) => (
            <option key={rate} value={rate}>
              {rate}×
            </option>
          ))}
        </select>
      </label>

      {spanLabel && (
        <span className="chip" title="Fragmento acotado">
          {spanLabel}
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            aria-label="Reproducir todo"
            onClick={() => player.clearSpan()}
          >
            ✕
          </button>
        </span>
      )}
    </div>
  );
}
