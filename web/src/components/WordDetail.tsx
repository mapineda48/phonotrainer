/** Detail panel for a word: what the dictionary says, what the aligner
 *  expected and what was actually pronounced, with all of it playable. */

import { wordSpan } from "../lib/analysis";
import { fmtTime } from "../lib/format";
import { usePlayer } from "../player/PlayerProvider";
import { familyColor, phenomenonDescription, phenomenonLabel, useReference } from "../reference";
import type { Segment, Word } from "../types";
import { F0Chart } from "./F0Chart";
import { PhoneTimeline } from "./PhoneTimeline";

/** Phenomena occurring at the boundary with the FOLLOWING word: they can only
 *  be heard together with it. (`h_dropping` is absent: it is word-internal, and
 *  its context is the preceding word.) */
const BOUNDARY = new Set(["linking", "palatalization"]);

interface Props {
  word: Word;
  /** The next word in the segment, so boundary phenomena can be heard and seen. */
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
  if (word.oov) flags.push("out of dictionary: pronunciation predicted by g2p");
  if (word.alignment_fallback) flags.push("approximate alignment (Whisper timings)");
  if (word.attracted_count > 0)
    flags.push(`${word.attracted_count} phone(s) attracted to the canonical form`);
  if (isEmphasis) flags.push("emphasized word of the segment");

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
          title="Play the word (P)"
        >
          ▶ Word
        </button>
        {crossesBoundary && (
          <button
            type="button"
            className="btn btn--sm"
            disabled={!canPlay}
            title={`Hear the linking with “${next.word}”`}
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
          title="Play the whole phrase (S)"
        >
          ▶ Phrase
        </button>
      </div>

      {crossesBoundary && (
        <p className="tiny muted" style={{ margin: "8px 0 0" }}>
          Boundary phenomenon: the comparison includes “{next.word}”, because the linking happens
          between the two words.
        </p>
      )}

      {unreliable && (
        <p className="tiny dim" style={{ margin: "8px 0 0" }}>
          {noPhones
            ? "The recognizer found no phones here: the labels below are not verifiable (this may be silence, laughter or music)."
            : "Low confidence over this stretch: read the labels with caution."}
        </p>
      )}

      <PhoneTimeline word={word} next={crossesBoundary ? next : null} />

      <dl className="deflist" style={{ marginTop: 14 }}>
        <dt title="CMUdict citation form">dictionary</dt>
        <dd className="ipa">/{word.dict_ipa}/</dd>
        <dt title="What the forced aligner expected (espeak-ng, native processes already applied)">
          canonical
        </dt>
        <dd className="ipa">[{word.canonical_ipa}]</dd>
        <dt title="What the acoustic model recognized">actual</dt>
        <dd className="ipa">[{word.realized_ipa || "∅"}]</dd>
        {word.realized_raw_ipa && (
          <>
            <dt>actual (raw)</dt>
            <dd className="ipa" title="Recognizer output before cleanup and attraction">
              [{word.realized_raw_ipa}]
            </dd>
          </>
        )}
        {word.lexical_form && (
          <>
            <dt>reduced form</dt>
            <dd>
              “{word.lexical_form}”
              {word.lexical_expansion && <span className="muted"> ← “{word.lexical_expansion}”</span>}
            </dd>
          </>
        )}
        <dt>divergence</dt>
        <dd className="num">{word.diff_cost.toFixed(2)}</dd>
      </dl>
      <p className="tiny muted" style={{ margin: "4px 0 0" }}>
        <strong>dictionary</strong> = citation form · <strong>canonical</strong> = what the
        aligner expected (espeak already applies native processes) · <strong>actual</strong> = what
        was recognized. Divergence = mean actual↔canonical distance per phone (0 = identical).
      </p>

      {word.phenomena.length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div className="phones__label">phenomena</div>
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
                  {/* Definition in plain sight: "glottalization" means nothing
                      to someone who is still learning. */}
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
          prosody of segment {segmentIndex + 1} ·{" "}
          {segment.f0_stats.mean != null
            ? `mean ${segment.f0_stats.mean.toFixed(0)} Hz, range ${segment.f0_stats.range?.toFixed(0)} Hz, final ${segment.f0_stats.final_contour}`
            : "no F0"}
        </div>
        <F0Chart segment={segment} width={330} />
      </div>
    </div>
  );
}
