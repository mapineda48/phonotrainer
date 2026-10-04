/** Still frames of the articulator, laid out for a screenshot.
 *
 *  `/articulator.html?grid=p,t,k` draws each phone at its target, and
 *  `?seq=b ʌ ɾ ɚ&frames=8` samples a word through time, coarticulation
 *  included. `theme`, `palette` and `motion` override the saved settings, so
 *  a headless browser can shoot every combination without touching storage.
 *  Development only, like the rest of the lab.
 */

import { useMemo } from "react";

import { VIEW_HEAD, VIEW_MOUTH } from "../articulation/anatomy";
import { lookupPhone } from "../articulation/phones";
import { REST_POSE } from "../articulation/pose";
import { TractSvg, VocalTract } from "../articulation/VocalTract";
import { buildTrack, phoneAt, staticTrack, type TimedPhone } from "../articulation/track";
import { Clock } from "../player/clock";
import { PlayerContextProvider, type PlayerApi } from "../player/PlayerProvider";

/** A player that never moves: every cell holds its own instant. */
function stillPlayer(): PlayerApi {
  const clock = new Clock();
  const noop = () => undefined;
  return {
    clock,
    playing: false,
    rate: 1,
    loop: false,
    span: null,
    duration: 1,
    play: noop,
    pause: noop,
    toggle: noop,
    seek: noop,
    setRate: noop,
    setLoop: noop,
    clearSpan: noop,
  };
}

/** Applies the theme overrides from the query string to <html>. */
export function applyQueryTheme(params: URLSearchParams): void {
  const root = document.documentElement;
  for (const key of ["theme", "palette", "motion", "patterns"]) {
    const value = params.get(key);
    if (value) root.setAttribute(`data-${key}`, value);
  }
}

export function Snapshots({ params }: { params: URLSearchParams }) {
  const player = useMemo(stillPlayer, []);
  const view = params.get("view") === "head" ? VIEW_HEAD : VIEW_MOUTH;
  const size = Number(params.get("size") ?? 220);
  const svg = params.get("svg") === "1";
  const labels = params.get("labels") === "1";

  const cells = useMemo(() => {
    const grid = params.get("grid");
    if (grid) {
      return grid
        .split(",")
        .filter(Boolean)
        .map((symbol) => {
          const phone = lookupPhone(symbol);
          const pose = phone?.gestures[phone.gestures.length - 1]?.pose ?? REST_POSE;
          return { caption: symbol, track: staticTrack(pose), time: 0 };
        });
    }
    const sequence = (params.get("seq") ?? "b ʌ ɾ ɚ").split(/\s+/).filter(Boolean);
    const step = Number(params.get("step") ?? 0.16);
    const timed: TimedPhone[] = sequence.map((symbol, index) => [
      symbol,
      0.2 + index * step,
      0.22 + index * step,
    ]);
    const track = buildTrack(timed);
    const frames = Number(params.get("frames") ?? 8);
    const from = track.start - 0.06;
    const to = track.end;
    return Array.from({ length: frames }, (_, index) => {
      const time = from + ((to - from) * index) / Math.max(frames - 1, 1);
      const phone = phoneAt(track, time);
      return { caption: `${time.toFixed(2)} s ${phone ? phone.symbol : "·"}`, track, time };
    });
  }, [params]);

  const columns = Number(params.get("cols") ?? 4);

  return (
    <PlayerContextProvider value={player}>
      <div
        data-snapshots-ready
        style={{
          display: "grid",
          gridTemplateColumns: `repeat(${columns}, ${size}px)`,
          gap: 10,
          padding: 12,
          background: "var(--page)",
          color: "var(--ink)",
          fontFamily: "var(--font-ui)",
        }}
      >
        {cells.map((cell, index) => (
          <figure key={index} style={{ margin: 0 }}>
            {svg ? (
              <div className="tract" style={{ height: size }}>
                <TractSvg track={cell.track} previewTime={cell.time} view={view} />
              </div>
            ) : (
              <VocalTract
                track={cell.track}
                previewTime={cell.time}
                height={size}
                view={view}
                label={cell.caption}
                labels={labels}
              />
            )}
            <figcaption style={{ textAlign: "center", fontSize: 13, fontFamily: "var(--ipa)" }}>
              {cell.caption}
            </figcaption>
          </figure>
        ))}
      </div>
    </PlayerContextProvider>
  );
}
