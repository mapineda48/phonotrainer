/** Playback controls: what gets touched every few seconds while studying. */

import { fmtTime } from "../lib/format";
import { useTime } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";

const RATES = [0.5, 0.75, 1];

interface Props {
  duration: number;
  /** Description of what is currently bounded ("word “does”"). */
  spanLabel: string | null;
  /** False when the analysis was imported without audio.wav: nothing to play. */
  enabled: boolean;
  /** The waveform: injected so the bar does not depend on how it is drawn. */
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
        aria-label={player.playing ? "Pause" : "Play"}
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
        title="Repeat the selected span (L key)"
        onClick={() => player.setLoop(!player.loop)}
      >
        ⟳ Loop
      </button>

      <label className="row tiny" style={{ gap: 4 }}>
        <span className="sr-only">Speed</span>
        <select
          className="input"
          style={{ width: "auto", padding: "3px 6px" }}
          aria-label="Playback speed"
          disabled={!enabled}
          value={player.rate}
          onChange={(event) => player.setRate(Number(event.target.value))}
          title="Playback speed (preserves pitch)"
        >
          {RATES.map((rate) => (
            <option key={rate} value={rate}>
              {rate}×
            </option>
          ))}
        </select>
      </label>

      {spanLabel && (
        <span className="chip" title="Bounded span">
          {spanLabel}
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            aria-label="Play everything"
            onClick={() => player.clearSpan()}
          >
            ✕
          </button>
        </span>
      )}
    </div>
  );
}
