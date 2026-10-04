/** Detail panel for a word: what the dictionary says, what the aligner
 *  expected and what was actually pronounced, with all of it playable. */

import { usePersistentFlag } from "../hooks/usePersistentFlag";
import { wordSpan } from "../lib/analysis";
import { fmtTime } from "../lib/format";
import { usePlayer } from "../player/PlayerProvider";
import {
  familyColor,
  lexicalPractice,
  LINK_TYPE_LABEL,
  phenomenonDescription,
  phenomenonLabel,
  phenomenonPractice,
  useReference,
} from "../reference";
import type { Segment, Word } from "../types";
import { ArticulationPanel } from "./ArticulationPanel";
import { F0Chart } from "./F0Chart";
import { PhoneTimeline } from "./PhoneTimeline";
import { PracticeBadge } from "./PracticeBadge";

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
  /** The phone engine (meta.phone_engine); absent = espeak, as older analyses. */
  engine?: string;
}

export function WordDetail({ word, next, segment, segmentIndex, isEmphasis, canPlay,
                             engine = "espeak" }: Props) {
  // espeak's canonical already applies native processes ([bɛɾɚ]); timit61 forces
  // the dictionary form itself, so the two rows mean different things.
  const narrow = engine !== "espeak";
  const player = usePlayer();
  const reference = useReference();
  // On by default: seeing the tongue is the point of opening a word.
  const [showTract, toggleTract] = usePersistentFlag("phonotrainer:show-tract", true);
  const span = wordSpan(word);
  const wordIndex = segment.words.indexOf(word);
  const prominence = wordIndex >= 0 ? (segment.prominence?.[wordIndex] ?? null) : null;
  const wordClass = wordIndex >= 0 ? (segment.word_classes?.[wordIndex] ?? null) : null;

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
        <button
          type="button"
          className="btn btn--sm"
          aria-pressed={showTract}
          title="Show the tongue moving through this word"
          onClick={toggleTract}
        >
          Mouth
        </button>
      </div>

      {crossesBoundary && (
        <p className="tiny muted" style={{ margin: "8px 0 0" }}>
          Boundary phenomenon: the comparison includes “{next.word}”, because the linking happens
          between the two words.
          {word.boundary_link_type && (
            <>
              {" "}
              Link: <strong data-testid="link-type">{LINK_TYPE_LABEL[word.boundary_link_type]}</strong>.
            </>
          )}
        </p>
      )}

      {unreliable && (
        <p className="tiny dim" style={{ margin: "8px 0 0" }}>
          {noPhones
            ? "The recognizer found no phones here: the labels below are not verifiable (this may be silence, laughter or music)."
            : "Low confidence over this stretch: read the labels with caution."}
        </p>
      )}

      {showTract && (
        <div style={{ marginTop: 12 }}>
          <ArticulationPanel
            word={word}
            next={crossesBoundary ? next : null}
            segment={segment}
            canPlay={canPlay}
          />
        </div>
      )}

      <PhoneTimeline word={word} next={crossesBoundary ? next : null} />

      <dl className="deflist" style={{ marginTop: 14 }}>
        <dt title="CMUdict citation form">dictionary</dt>
        <dd className="ipa">/{word.dict_ipa}/</dd>
        <dt
          title={
            narrow
              ? "The dictionary form, forced in time onto the audio"
              : "What the forced aligner expected (espeak-ng, native processes already applied)"
          }
        >
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
              {word.lexical_expansion && <span className="muted"> ← “{word.lexical_expansion}”</span>}{" "}
              <PracticeBadge practice={lexicalPractice(reference, word.lexical_form)} />
            </dd>
          </>
        )}
        {word.form && (
          <>
            <dt title="Strong and weak pronunciations scored against the recognizer's own output (espeak engine)">
              form scoring
            </dt>
            <dd data-testid="form-scoring">
              <span className="ipa">[{word.form.ipa}]</span> — {word.form.weak ? "weak" : "strong"} form
              {word.form.weak_margin != null && (
                <span className="muted">
                  {" "}
                  · weak {word.form.weak_margin >= 0 ? "ahead by" : "behind by"}{" "}
                  {Math.abs(word.form.weak_margin).toFixed(1)} (citation{" "}
                  <span className="ipa">[{word.form.strong_ipa}]</span>)
                </span>
              )}
              {word.form.h_dropped && <span className="muted"> · /h/ dropped</span>}
            </dd>
          </>
        )}
        <dt>divergence</dt>
        <dd className="num">{word.diff_cost.toFixed(2)}</dd>
      </dl>
      <p className="tiny muted" style={{ margin: "4px 0 0" }}>
        <strong>dictionary</strong> = citation form · <strong>canonical</strong> ={" "}
        {narrow
          ? "that same form, placed in time on the audio"
          : "what the aligner expected (espeak already applies native processes)"}{" "}
        · <strong>actual</strong> = what was recognized. Divergence = mean actual↔canonical
        distance per phone (0 = identical).
      </p>

      {word.phenomena.length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div className="phones__label">phenomena</div>
          <div style={unreliable ? { opacity: 0.6 } : undefined}>
            {word.phenomena.map((phenomenon) => {
              const family = reference.family_of[phenomenon];
              const description = phenomenonDescription(reference, phenomenon);
              const fromVariants = word.variant_labels?.includes(phenomenon) ?? false;
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
                  </span>{" "}
                  <PracticeBadge practice={phenomenonPractice(reference, phenomenon)} />
                  {fromVariants && (
                    <span
                      className="tiny muted"
                      title="Not visible in the recognized phones: the weak form scored better than the strong one on the same audio."
                    >
                      {" "}
                      · from form scoring
                    </span>
                  )}
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
            ? `mean ${segment.f0_stats.mean.toFixed(0)} Hz${
                segment.f0_stats.range != null ? `, range ${segment.f0_stats.range.toFixed(0)} Hz` : ""
              }${
                segment.f0_stats.range_st != null ? ` (${segment.f0_stats.range_st.toFixed(1)} st)` : ""
              }, final ${segment.f0_stats.final_contour}`
            : "no F0"}
        </div>
        {prominence != null && (
          <p className="tiny muted" style={{ margin: "2px 0 6px" }} data-testid="word-prominence">
            This word's prominence: {Math.round(prominence * 100)} % of the segment's peak
            {wordClass && <> · {wordClass} word</>}
            {wordClass === "function" && prominence >= 0.999 && (
              <> — the peak fell on a function word: contrast or emphasis</>
            )}
          </p>
        )}
        <F0Chart segment={segment} width={330} />
      </div>
    </div>
  );
}
