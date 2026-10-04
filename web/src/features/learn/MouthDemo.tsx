/** The mouth saying a short IPA sequence, with no audio behind it: Learn uses it to show
 *  what a change does to the gesture ("water" with a full t, then with a flap).
 *
 *  The articulator is driven by a player clock; here the clock is advanced by hand
 *  (the same approach as the articulator bench), so no recording is needed. */

import { Pause, Play } from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import { VIEW_MOUTH } from "../../articulation/anatomy";
import { lookupPhone } from "../../articulation/phones";
import { buildTrack, phoneCenter, type TimedPhone } from "../../articulation/track";
import { VocalTract } from "../../articulation/VocalTract";
import { Clock } from "../../player/clock";
import { PlayerContextProvider, type PlayerApi, type Span } from "../../player/PlayerProvider";
import { Button, cn, Ipa, Segmented } from "../../ui";

/** Seconds per phone at normal speed: slow enough to follow, fast enough to read as a word. */
const STEP = 0.2;
const LEAD = 0.15;

/** A player with no audio: requestAnimationFrame moves the clock through the span. */
function useSilentPlayer(): PlayerApi {
  const clock = useMemo(() => new Clock(), []);
  const [playing, setPlaying] = useState(false);
  const [rate, setRate] = useState(1);
  const [loop, setLoop] = useState(false);
  const [span, setSpan] = useState<Span | null>(null);
  const spanRef = useRef<Span | null>(null);
  const loopRef = useRef(false);
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

type Version = "said" | "dictionary";

interface Props {
  /** The words shown ("water"). */
  word: string;
  dictionary: readonly string[];
  said: readonly string[];
  /** The phone that changes (dictionary) and what it becomes (null: dropped). */
  from: string;
  to: string | null;
  /** Height of the mouth drawing in px. */
  height?: number;
  className?: string;
}

/** Turn a phone sequence into the timed list the articulator reads. */
function timed(symbols: readonly string[]): TimedPhone[] {
  return symbols.map((symbol, index) => [
    symbol,
    Number((LEAD + index * STEP).toFixed(3)),
    Number((LEAD + (index + 1) * STEP - 0.02).toFixed(3)),
  ]);
}

export function MouthDemo({ word, dictionary, said, from, to, height = 240, className }: Props) {
  const player = useSilentPlayer();
  const [version, setVersion] = useState<Version>("said");
  const [speed, setSpeed] = useState<"1" | "0.5">("0.5");
  const [held, setHeld] = useState<number | null>(null);
  const phonesId = useId();

  const symbols = version === "said" ? said : dictionary;
  const key = version === "said" ? to : from;
  const keyIndex = key === null ? -1 : symbols.indexOf(key);
  const track = useMemo(() => buildTrack(timed(symbols)), [symbols]);

  useEffect(() => {
    player.setRate(Number(speed));
  }, [speed, player.setRate]); // eslint-disable-line react-hooks/exhaustive-deps

  // A new version starts from rest, on the changed phone if there is one.
  useEffect(() => {
    player.pause();
    const centre = keyIndex >= 0 ? track.phones[keyIndex] : null;
    setHeld(centre ? phoneCenter(centre) : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [track]);

  // When the movement ends, settle back on the changed phone instead of the rest pose.
  useEffect(() => {
    if (player.playing) return;
    setHeld((current) => {
      if (current !== null) return current;
      const centre = keyIndex >= 0 ? track.phones[keyIndex] : null;
      return centre ? phoneCenter(centre) : null;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player.playing]);

  const play = () => {
    setHeld(null);
    player.play({ start: track.start - LEAD, end: track.end + LEAD });
  };

  const hold = (index: number) => {
    const phone = track.phones[index];
    if (!phone) return;
    player.pause();
    setHeld(phoneCenter(phone));
  };

  const versionText = version === "said" ? "what is usually said" : "the dictionary form";
  const label = `The mouth saying “${word}” as ${versionText}: ${symbols.join(" ")}`;

  return (
    <PlayerContextProvider value={player}>
      <div className={cn("flex flex-col gap-3", className)}>
        <div className="flex flex-wrap items-end gap-3">
          <Segmented<Version>
            label="Show"
            value={version}
            onChange={setVersion}
            options={[
              { id: "said", label: "What is said" },
              { id: "dictionary", label: "Dictionary form" },
            ]}
            size="sm"
          />
          <Segmented<"1" | "0.5">
            label="Speed"
            value={speed}
            onChange={setSpeed}
            options={[
              { id: "0.5", label: "Slow" },
              { id: "1", label: "Normal" },
            ]}
            size="sm"
          />
          <Button
            size="sm"
            variant="secondary"
            icon={player.playing ? Pause : Play}
            onPress={() => (player.playing ? player.pause() : play())}
          >
            {player.playing ? "Pause" : "Play the movement"}
          </Button>
        </div>

        <VocalTract track={track} previewTime={held} height={height} label={label} view={VIEW_MOUTH} />

        <div>
          <p className="mb-1 text-sm text-ink-2" id={phonesId}>
            Select a sound to hold the mouth on it:
          </p>
          <ul className="flex flex-wrap gap-1.5" aria-labelledby={phonesId}>
            {symbols.map((symbol, index) => {
              const articulation = lookupPhone(symbol);
              const isKey = index === keyIndex;
              return (
                <li key={`${symbol}-${index}`}>
                  <button
                    type="button"
                    onClick={() => hold(index)}
                    aria-label={`Hold [${symbol}]${articulation ? `, ${articulation.name}` : ""}${isKey ? " — the sound that changes" : ""}`}
                    className={cn(
                      "inline-flex min-h-10 min-w-10 items-center justify-center rounded-control px-2 text-lg",
                      "bg-surface shadow-[inset_0_0_0_1px_var(--line-strong)] hover:bg-surface-2",
                      isKey && "font-bold shadow-[inset_0_0_0_3px_var(--ink)]",
                    )}
                  >
                    <Ipa>{symbol}</Ipa>
                  </button>
                </li>
              );
            })}
          </ul>
          <p className="mt-2 text-sm text-ink-2">
            {version === "said" ? (
              to === null ? (
                <>
                  The <Ipa kind="phonetic">{from}</Ipa> of the dictionary form is not said at all.
                </>
              ) : to === from ? (
                <>The sounds stay the same; what changes is that they run together without a break.</>
              ) : (
                <>
                  The thick outline marks <Ipa kind="phonetic">{to}</Ipa>, which replaces the dictionary's{" "}
                  <Ipa kind="phonetic">{from}</Ipa>.
                </>
              )
            ) : (
              <>
                The thick outline marks <Ipa kind="phonetic">{from}</Ipa>, the sound that changes in fast speech.
              </>
            )}
          </p>
        </div>
      </div>
    </PlayerContextProvider>
  );
}

/** The mouth making one sound, from rest and back: the IPA chart's detail panel. */
export function PhoneMouth({ symbol, height = 220 }: { symbol: string; height?: number }) {
  const player = useSilentPlayer();
  const track = useMemo(() => buildTrack(timed([symbol])), [symbol]);
  const centre = track.phones[0] ? phoneCenter(track.phones[0]) : null;
  const [held, setHeld] = useState<number | null>(centre);

  useEffect(() => {
    player.pause();
    setHeld(track.phones[0] ? phoneCenter(track.phones[0]) : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [track]);

  useEffect(() => {
    player.setRate(0.5);
  }, [player.setRate]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!player.playing) setHeld((current) => current ?? centre);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player.playing]);

  const articulation = lookupPhone(symbol);
  const label = `The mouth making [${symbol}]${articulation ? `, ${articulation.name}` : ""}`;

  return (
    <PlayerContextProvider value={player}>
      <div className="flex flex-col gap-2">
        <VocalTract track={track} previewTime={held} height={height} label={label} view={VIEW_MOUTH} />
        <div>
          <Button
            size="sm"
            variant="secondary"
            icon={player.playing ? Pause : Play}
            onPress={() => {
              if (player.playing) {
                player.pause();
                return;
              }
              setHeld(null);
              player.play({ start: track.start - LEAD, end: track.end + LEAD });
            }}
          >
            {player.playing ? "Pause" : "Play the movement"}
          </Button>
        </div>
      </div>
    </PlayerContextProvider>
  );
}
