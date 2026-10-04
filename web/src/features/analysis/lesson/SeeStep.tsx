/** Lesson step 4 — See the mouth: what the tongue, lips and velum do for this word.
 *
 *  It follows the playhead, so playing the word plays the movement too, and any sound
 *  can be held still by picking it. The track spans the whole phrase (plus the next word
 *  when the change crosses the boundary), because coarticulation belongs to the phrase.
 *  The articulator itself (src/articulation) is used as is. */

import { useMemo, useState } from "react";

import {
  buildTrack,
  phoneAt,
  phoneCenter,
  type TimedPhone,
  type TrackPhone,
} from "../../../articulation/track";
import { VocalTract } from "../../../articulation/VocalTract";
import { Explain } from "../../../didactic/Explain";
import { ipaTerm } from "../../../didactic/glossary";
import { useTimeSelector } from "../../../player/clock";
import { usePlayer } from "../../../player/PlayerProvider";
import type { Segment, Word } from "../../../types";
import { cn, Ipa, Segmented } from "../../../ui";

const PAD = 0.02;

export type Stream = "real" | "canonical";

interface Props {
  word: Word;
  next: Word | null;
  segment: Segment;
  canPlay: boolean;
  /** Which row to draw ("What was said" or the dictionary form). */
  stream: Stream;
  onStreamChange: (stream: Stream) => void;
  /** A sound held from step 2 (its center instant), or null to follow the playhead. */
  held: number | null;
  onHold: (time: number | null) => void;
}

const phonesOf = (word: Word, stream: Stream): TimedPhone[] =>
  (stream === "real" ? word.realized_aligned : word.canonical_aligned) as TimedPhone[];

export function SeeStep({ word, next, segment, canPlay, stream, onStreamChange, held, onHold }: Props) {
  const player = usePlayer();
  const [picked, setPicked] = useState<number | null>(null);
  const preview = picked ?? held;

  const { track, own } = useMemo(() => {
    const phones: TimedPhone[] = [];
    let from = 0;
    let count = 0;
    for (const item of segment.words) {
      if (item === word) {
        from = phones.length;
        count = phonesOf(item, stream).length;
      }
      phones.push(...phonesOf(item, stream));
    }
    // a word linking into the next phrase: without its sounds the movement would stop
    // exactly where the linking happens
    if (next && !segment.words.includes(next)) phones.push(...phonesOf(next, stream));
    return { track: buildTrack(phones), own: { from, count } };
  }, [segment, word, next, stream]);

  const playing = player.playing;
  const index = useTimeSelector(player.clock, (time) => {
    const at = playing ? time : (preview ?? time);
    return phoneAt(track, at)?.index ?? -1;
  });
  const current: TrackPhone | null = index >= 0 ? track.phones[index] : null;
  const articulation = current?.articulation ?? null;

  const label = articulation
    ? `Side view of the mouth: [${articulation.symbol}], ${articulation.name}`
    : "Side view of the mouth, at rest";

  const pick = (phone: TrackPhone) => {
    const center = phoneCenter(phone);
    setPicked(center);
    onHold(center);
    if (canPlay) player.play({ start: phone.start - PAD, end: phone.end + PAD });
  };

  return (
    <div className="flex flex-col gap-3">
      <Segmented
        label="Show the mouth for"
        size="sm"
        options={[
          { id: "real", label: "What was said" },
          { id: "canonical", label: "Dictionary form" },
        ]}
        value={stream}
        onChange={(value) => {
          setPicked(null);
          onStreamChange(value);
        }}
      />

      <VocalTract track={track} previewTime={preview} height={240} label={label} />

      <div aria-live="polite" className="flex min-h-14 flex-col gap-0.5">
        <p className="flex flex-wrap items-baseline gap-x-2">
          <Ipa kind="phonetic" className="text-2xl text-ink">
            {current?.symbol ?? "·"}
          </Ipa>
          <span className="text-sm text-ink-2">
            {articulation
              ? `${articulation.name} · as in “${articulation.example}”`
              : current
                ? "outside the drawable inventory"
                : "silence"}
          </span>
          {articulation && <Explain term={ipaTerm(articulation.symbol)} buttonLabel={`What's this: [${articulation.symbol}]`} />}
        </p>
        <p className="text-sm text-ink">
          {articulation?.cue ?? "Play the word, or pick a sound below, to see the movement."}
        </p>
      </div>

      <div role="group" aria-label="Sounds of this word" className="flex flex-wrap gap-1.5">
        {track.phones.slice(own.from, own.from + own.count).map((phone) => (
          <button
            key={`${phone.index}-${phone.symbol}`}
            type="button"
            aria-pressed={phone.index === index}
            disabled={phone.articulation === null}
            onClick={() => pick(phone)}
            className={cn(
              "ipa-text inline-flex min-h-9 min-w-9 items-center justify-center rounded-control px-1.5 text-lg",
              "bg-surface text-ink shadow-[inset_0_0_0_1px_var(--line-strong)] hover:bg-surface-2",
              "disabled:cursor-not-allowed disabled:opacity-45",
              phone.index === index && "bg-ink text-page shadow-none hover:bg-ink",
            )}
          >
            <span lang="und-fonipa">{phone.symbol}</span>
          </button>
        ))}
        {own.count === 0 && <span className="text-sm text-ink-2">∅ nothing recognized</span>}
      </div>
      {(picked !== null || held !== null) && (
        <button
          type="button"
          className="w-fit text-sm text-ink underline underline-offset-2"
          onClick={() => {
            setPicked(null);
            onHold(null);
          }}
        >
          Follow the playback again
        </button>
      )}
      <p className="text-xs text-ink-muted">
        The shapes in between the sounds are not decoration: they are the coarticulation this analysis measures.
      </p>
    </div>
  );
}
