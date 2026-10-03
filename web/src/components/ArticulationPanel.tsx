/** What the mouth is doing, beside what was said.
 *
 *  The phone comparator answers *which* sounds came out; this answers what the
 *  tongue had to do to make them. It follows the playhead, so playing a word
 *  plays the movement as well, and any phone can be held still by clicking it
 *  — which is the gesture for "what exactly happens in this /ɾ/".
 *
 *  The track spans the whole segment (plus the next word when the phenomenon
 *  crosses the boundary), because coarticulation is a property of the phrase:
 *  the /k/ of "key" and the /k/ of "cool" are not the same /k/, and the only
 *  way to show that is to draw them with their neighbours around them.
 */

import { useMemo, useState } from "react";

import { VocalTract } from "../articulation/VocalTract";
import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import {
  buildTrack,
  phoneAt,
  phoneCenter,
  type TimedPhone,
  type TrackPhone,
} from "../articulation/track";
import type { Segment, Word } from "../types";

/** Padding when a single phone is played on its own, as in the comparator. */
const PAD = 0.02;

type Stream = "real" | "canonical";

interface Props {
  word: Word;
  /** The following word, when the phenomenon crosses the boundary. */
  next: Word | null;
  segment: Segment;
  canPlay: boolean;
}

const phonesOf = (word: Word, stream: Stream): TimedPhone[] =>
  (stream === "real" ? word.realized_aligned : word.canonical_aligned) as TimedPhone[];

export function ArticulationPanel({ word, next, segment, canPlay }: Props) {
  const player = usePlayer();
  const [stream, setStream] = useState<Stream>("real");
  /** Instant held while a phone is picked by hand; null = follow the playhead. */
  const [preview, setPreview] = useState<number | null>(null);

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
    // A word linking into the next segment: without its phones the movement
    // would stop exactly where the linking happens.
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
    ? `Midsagittal section of the mouth: ${articulation.symbol}, ${articulation.name}`
    : "Midsagittal section of the mouth, at rest";

  const pick = (phone: TrackPhone) => {
    setPreview(phoneCenter(phone));
    if (canPlay) player.play({ start: phone.start - PAD, end: phone.end + PAD });
  };

  return (
    <div>
      <div className="row" style={{ marginBottom: 6 }}>
        <span className="phones__label" style={{ margin: 0 }}>
          articulation
        </span>
        <span className="spacer" />
        <button
          type="button"
          className="btn btn--sm"
          aria-pressed={stream === "real"}
          title="What the recogniser heard"
          onClick={() => setStream("real")}
        >
          actual
        </button>
        <button
          type="button"
          className="btn btn--sm"
          aria-pressed={stream === "canonical"}
          title="What the aligner expected: the same word without the phenomenon"
          onClick={() => setStream("canonical")}
        >
          canonical
        </button>
      </div>

      <VocalTract track={track} previewTime={preview} height={232} label={label} />

      <div className="tract-panel__head">
        <span className="tract-panel__symbol ipa">{current?.symbol ?? "·"}</span>
        <span className="tiny muted">
          {articulation
            ? `${articulation.name} · as in ${articulation.example}`
            : current
              ? "outside the drawable inventory"
              : "silence"}
        </span>
      </div>
      <p className="tiny" style={{ margin: 0, minHeight: 34 }}>
        {articulation?.cue ?? "Play the word, or pick a phone below, to see the movement."}
      </p>

      <div className="tract-panel__strip">
        {track.phones.slice(own.from, own.from + own.count).map((phone) => (
          <button
            key={`${phone.index}-${phone.symbol}`}
            type="button"
            className="tract-phone"
            aria-pressed={phone.index === index}
            disabled={phone.articulation === null}
            title={phone.articulation?.name ?? "no articulation for this symbol"}
            onClick={() => pick(phone)}
          >
            {phone.symbol}
          </button>
        ))}
        {own.count === 0 && <span className="tiny muted">∅ nothing recognised</span>}
      </div>
      <p className="tiny dim" style={{ margin: "6px 0 0" }}>
        The mouth follows playback. Click a phone to hold it there. The shapes in
        between are not decoration: that is the coarticulation this analysis measures.
      </p>
    </div>
  );
}
