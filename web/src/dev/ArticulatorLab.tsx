/** Bench for the articulator: every phone of the inventory, one at a time or
 *  strung into a word, plus the raw articulator sliders.
 *
 *  The engine is meant to be judged by eye — whether /t/ really lands on the
 *  ridge, whether /aɪ/ actually travels — and that cannot be asserted in a
 *  test. This page exists so it can be looked at, away from an analysis, a
 *  server or an audio file. Development only; it is not part of the build.
 */

import { useEffect, useMemo, useRef, useState } from "react";

import { VIEW_HEAD } from "../articulation/anatomy";
import { TractSvg, VocalTract } from "../articulation/VocalTract";
import { PHONE_TABLE, type PhoneArticulation } from "../articulation/phones";
import { POSE_KEYS, REST_POSE, type Pose } from "../articulation/pose";
import { buildTrack, phoneAt, staticTrack, type TimedPhone } from "../articulation/track";
import { Clock } from "../player/clock";
import { PlayerContextProvider, type PlayerApi, type Span } from "../player/PlayerProvider";

type Mode = "phone" | "word" | "pose" | "sheet";

/** A player with no audio behind it: the clock is advanced by hand, which is
 *  all the tract needs to animate. */
function useLabPlayer(): PlayerApi {
  const clock = useMemo(() => new Clock(), []);
  const [playing, setPlaying] = useState(false);
  const [rate, setRate] = useState(1);
  const [loop, setLoop] = useState(true);
  const [span, setSpan] = useState<Span | null>(null);
  const spanRef = useRef<Span | null>(null);
  const loopRef = useRef(true);
  spanRef.current = span;
  loopRef.current = loop;

  useEffect(() => {
    if (!playing) return;
    let last = performance.now();
    let frame = requestAnimationFrame(function step(now: number) {
      const delta = ((now - last) / 1000) * rate;
      last = now;
      const active = spanRef.current;
      const next = clock.getSnapshot() + delta;
      if (active && next >= active.end) {
        if (loopRef.current) clock.set(active.start);
        else {
          clock.set(active.end);
          setPlaying(false);
          return;
        }
      } else {
        clock.set(next);
      }
      frame = requestAnimationFrame(step);
    });
    return () => cancelAnimationFrame(frame);
  }, [playing, rate, clock]);

  return useMemo<PlayerApi>(
    () => ({
      clock,
      playing,
      rate,
      loop,
      span,
      duration: span?.end ?? 1,
      play: (next?: Span | null) => {
        const target = next === undefined ? spanRef.current : next;
        setSpan(target ?? null);
        if (target) clock.set(target.start);
        setPlaying(true);
      },
      pause: () => setPlaying(false),
      toggle: () => setPlaying((value) => !value),
      seek: (time: number) => clock.set(time),
      setRate,
      setLoop,
      clearSpan: () => setSpan(null),
    }),
    [clock, playing, rate, loop, span],
  );
}

export function ArticulatorLab() {
  const player = useLabPlayer();
  const [mode, setMode] = useState<Mode>("phone");
  const [symbol, setSymbol] = useState("t");
  const [sequence, setSequence] = useState("b ɛ ɾ ɚ");
  const [step, setStep] = useState(0.16);
  const [pose, setPose] = useState<Pose>(REST_POSE);
  const [svg, setSvg] = useState(false);
  /** Instant being held while scrubbing; null means "follow the clock". */
  const [scrub, setScrub] = useState<number | null>(null);

  const phone = PHONE_TABLE.find((entry) => entry.symbol === symbol) ?? PHONE_TABLE[0];

  const track = useMemo(() => {
    if (mode === "pose") return staticTrack(pose);
    const symbols = mode === "phone" ? [symbol] : sequence.split(/\s+/).filter(Boolean);
    const timed: TimedPhone[] = symbols.map((entry, index) => [
      entry,
      Number((0.2 + index * step).toFixed(3)),
      Number((0.22 + index * step).toFixed(3)),
    ]);
    return buildTrack(timed);
  }, [mode, symbol, sequence, step, pose]);

  // Every change of subject plays it: watching a phone is watching it move.
  // Through a ref, because calling play() re-creates the player API and
  // depending on its identity would loop.
  const playRef = useRef(player.play);
  playRef.current = player.play;
  useEffect(() => {
    if (mode === "pose" || track.keys.length === 0) return;
    setScrub(null);
    playRef.current({ start: track.start - 0.12, end: track.end + 0.2 });
  }, [track, mode]);

  const from = track.start - 0.12;
  const to = track.end + 0.2;
  const held = scrub ?? player.clock.getSnapshot();
  const phoneNow = mode === "pose" ? null : phoneAt(track, held);

  return (
    <PlayerContextProvider value={player}>
      <div style={{ display: "grid", gridTemplateColumns: "minmax(320px, 1fr) 420px", gap: 20, padding: 20, height: "100vh", boxSizing: "border-box" }}>
        <div style={{ minWidth: 0, display: "flex", flexDirection: "column", gap: 10 }}>
          <h1 style={{ margin: 0, fontSize: 17 }}>Articulator lab</h1>
          {mode === "sheet" && <ContactSheet />}

          {/* The section is nearly square: framed any wider and the head sits
              lost in the middle of an empty field. */}
          <div style={{ width: "min(100%, 620px)", display: mode === "sheet" ? "none" : undefined }}>
            {svg ? (
              <div className="tract" style={{ height: 620 }}>
                <TractSvg track={track} previewTime={scrub} view={VIEW_HEAD} />
              </div>
            ) : (
              <VocalTract
                track={track}
                previewTime={scrub}
                height={620}
                view={VIEW_HEAD}
                label={`${phone.symbol}: ${phone.name}`}
              />
            )}
          </div>
          {mode !== "pose" && mode !== "sheet" && (
            <label className="tiny muted" style={{ display: "block" }}>
              {scrub === null ? "following the clock" : `held at ${held.toFixed(3)} s`}
              {phoneNow && <span className="ipa"> · {phoneNow.symbol}</span>}
              <input
                type="range"
                min={from}
                max={to}
                step={0.005}
                value={held}
                style={{ width: "100%" }}
                onChange={(event) => {
                  player.pause();
                  setScrub(Number(event.target.value));
                }}
              />
            </label>
          )}

          <div className="row">
            <button
              type="button"
              className="btn btn--sm"
              onClick={() => {
                setScrub(null);
                player.toggle();
              }}
            >
              {player.playing ? "❚❚ Pause" : "▶ Play"}
            </button>
            <button
              type="button"
              className="btn btn--sm"
              aria-pressed={player.rate === 0.35}
              onClick={() => player.setRate(player.rate === 1 ? 0.35 : 1)}
            >
              0.35×
            </button>
            <button
              type="button"
              className="btn btn--sm"
              aria-pressed={svg}
              onClick={() => setSvg((value) => !value)}
              title="Draw with the SVG fallback instead of WebGL"
            >
              SVG fallback
            </button>
            <span className="spacer" />
            <span className="tiny muted">
              {mode === "pose" ? "sliders" : `${track.phones.length} phone(s)`}
            </span>
          </div>
        </div>

        <div className="scroll" style={{ minWidth: 0 }}>
          <div className="tabs" role="tablist">
            {(["phone", "word", "pose", "sheet"] as Mode[]).map((value) => (
              <button
                key={value}
                type="button"
                role="tab"
                className="tab"
                aria-selected={mode === value}
                onClick={() => setMode(value)}
              >
                {value}
              </button>
            ))}
          </div>

          {mode === "phone" && <PhonePicker current={phone} onPick={setSymbol} />}

          {mode === "sheet" && (
            <p className="tiny muted" style={{ padding: 12 }}>
              Every phone at its target, side by side. The fastest way to catch
              one that has drifted: a tongue through the palate, a mouth that
              never closes, two phones that came out identical.
            </p>
          )}

          {mode === "word" && (
            <div style={{ padding: 12 }}>
              <p className="tiny muted">Space-separated IPA, the way the analysis emits it.</p>
              <input
                className="input"
                style={{ width: "100%" }}
                value={sequence}
                onChange={(event) => setSequence(event.target.value)}
              />
              <label className="tiny muted" style={{ display: "block", marginTop: 10 }}>
                {Math.round(step * 1000)} ms per phone
                <input
                  type="range"
                  min={40}
                  max={400}
                  value={step * 1000}
                  style={{ width: "100%" }}
                  onChange={(event) => setStep(Number(event.target.value) / 1000)}
                />
              </label>
              <div className="tract-panel__strip">
                {["b ɛ ɾ ɚ", "w ɑ n ə", "ð æ t", "tʃ ɜ tʃ", "f aɪ v", "j u", "s ɪ ŋ", "h i", "ɡ oʊ"].map(
                  (preset) => (
                    <button
                      key={preset}
                      type="button"
                      className="tract-phone"
                      onClick={() => setSequence(preset)}
                    >
                      {preset}
                    </button>
                  ),
                )}
              </div>
            </div>
          )}

          {mode === "pose" && (
            <div style={{ padding: 12 }}>
              {POSE_KEYS.map((key) => (
                <label key={key} className="tiny" style={{ display: "block", marginBottom: 6 }}>
                  <span className="muted">
                    {key} — {pose[key].toFixed(2)}
                  </span>
                  <input
                    type="range"
                    min={0}
                    max={100}
                    value={pose[key] * 100}
                    style={{ width: "100%" }}
                    onChange={(event) =>
                      setPose((current) => ({ ...current, [key]: Number(event.target.value) / 100 }))
                    }
                  />
                </label>
              ))}
              <button type="button" className="btn btn--sm" onClick={() => setPose(REST_POSE)}>
                Reset
              </button>
            </div>
          )}
        </div>
      </div>
    </PlayerContextProvider>
  );
}

function PhonePicker({
  current,
  onPick,
}: {
  current: PhoneArticulation;
  onPick: (symbol: string) => void;
}) {
  return (
    <div style={{ padding: 12 }}>
      <div className="tract-panel__strip">
        {PHONE_TABLE.map((phone) => (
          <button
            key={phone.symbol}
            type="button"
            className="tract-phone"
            aria-pressed={phone.symbol === current.symbol}
            onClick={() => onPick(phone.symbol)}
            title={phone.name}
          >
            {phone.symbol}
          </button>
        ))}
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="row">
          <span className="ipa" style={{ fontSize: 24 }}>
            {current.symbol}
          </span>
          <span className="tiny muted">{current.name}</span>
        </div>
        <p className="tiny" style={{ margin: "6px 0 0" }}>
          {current.cue}
        </p>
        <p className="tiny muted" style={{ margin: "6px 0 0" }}>
          as in <strong>{current.example}</strong> · {current.manner} ·{" "}
          {current.voiced ? "voiced" : "voiceless"}
        </p>
      </div>
    </div>
  );
}

/** All of the inventory at once, drawn with the SVG path: a contact sheet is
 *  the only way to compare sixty mouths without clicking sixty times. */
function ContactSheet() {
  return (
    <div
      className="scroll"
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fill, minmax(120px, 1fr))",
        gap: 8,
        maxHeight: "82vh",
      }}
    >
      {PHONE_TABLE.map((phone) => (
        <figure key={phone.symbol} style={{ margin: 0 }}>
          <div className="tract" style={{ height: 120 }}>
            <TractSvg
              track={staticTrack(phone.gestures[phone.gestures.length - 1]?.pose ?? REST_POSE)}
              previewTime={0}
              view={VIEW_HEAD}
            />
          </div>
          <figcaption className="tiny muted" style={{ textAlign: "center" }}>
            <span className="ipa">{phone.symbol}</span> · {phone.example}
          </figcaption>
        </figure>
      ))}
    </div>
  );
}
