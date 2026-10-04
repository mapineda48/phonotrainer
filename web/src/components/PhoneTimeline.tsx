/** Dictionary vs canonical vs actual, phone by phone, on one shared time axis.
 *
 *  This is the view that justifies the project. Three rows because three are
 *  needed:
 *
 *  - **dictionary** (CMUdict, untimed): the citation form. Indispensable with
 *    the espeak engine, whose canonical already applies native processes — the
 *    canonical form of *better* is [bɛɾɚ], with a flap — so without this row
 *    flapping is invisible. With timit61 the canonical IS this form, in time.
 *  - **aligned canonical**: what the forced aligner expected, placed in time.
 *  - **actually pronounced**: what the acoustic model recognized.
 *
 *  The times are CTC peaks (a single 20 ms frame), not segmentations: that is
 *  why the boxes are annotated as a *detected instant* and not as a duration.
 */

import { useMemo } from "react";

import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import { useReference } from "../reference";
import type { AlignedPhone, Word } from "../types";

const PAD = 0.02; // s of padding when playing a single phone
/** Times are single-frame peaks: two phones "at once" may not actually overlap. */
const FRAME = 0.04;

interface Props {
  word: Word;
  /** The following word, when the phenomenon crosses the boundary (linking, palatalization…). */
  next?: Word | null;
}

const overlaps = (a: AlignedPhone, b: AlignedPhone): boolean =>
  a[1] < b[2] + FRAME && b[1] < a[2] + FRAME;

function markDiff(own: AlignedPhone[], other: AlignedPhone[]): boolean[] {
  return own.map((phone) => !other.some((peer) => peer[0] === phone[0] && overlaps(phone, peer)));
}

/** Marks that are not a phone: stress and length. */
const STRESS = /[ˈˌ]/;
/** For comparing rows: /uː/ and /u/, /ˈɛ/ and /ɛ/ are the same phone. */
export const bareSymbol = (symbol: string): string => symbol.replace(/[ˈˌː]/g, "");

/**
 * Split an untimed IPA string (the dictionary form) into phones.
 *
 * `tokens` are the multi-character symbols of the English inventory, published
 * by the backend: without them "aɪ" would be split in two and the dictionary
 * row would flag half of a correctly spoken word as unpronounced.
 */
export function splitIpa(ipa: string, tokens: readonly string[] = []): string[] {
  const COMBINING = /[ʰ-˿̀-ͯ᷀-᷿ⁿːˑ]/;
  const multi = [...tokens].sort((a, b) => b.length - a.length);
  const out: string[] = [];
  let pending = "";                       // a stress mark waiting for its phone
  let i = 0;
  while (i < ipa.length) {
    const char = ipa[i];
    if (STRESS.test(char)) {
      pending += char;
      i += 1;
      continue;
    }
    const token = multi.find((candidate) => ipa.startsWith(candidate, i));
    if (token) {
      out.push(pending + token);
      pending = "";
      i += token.length;
      continue;
    }
    if (!pending && out.length > 0 && COMBINING.test(char)) out[out.length - 1] += char;
    else out.push(pending + char);
    pending = "";
    i += 1;
  }
  return out;
}

export function PhoneTimeline({ word, next }: Props) {
  const player = usePlayer();
  const reference = useReference();

  const view = useMemo(() => {
    const words = next ? [word, next] : [word];
    const canonical = words.flatMap((w) => w.canonical_aligned);
    const real = words.flatMap((w) => w.realized_aligned);
    const phones = [...canonical, ...real];
    const start = Math.min(...words.map((w) => w.start), ...phones.map((p) => p[1]));
    const end = Math.max(...words.map((w) => w.end), ...phones.map((p) => p[2]));
    const span = Math.max(end - start, 1e-3);
    const dictionary = splitIpa(word.dict_ipa, reference.ipa_tokens);
    // Only against what THIS word realized: counting the next one would let
    // its /t/ mask our own deleted /t/.
    const saidHere = new Set(word.realized_aligned.map((p) => bareSymbol(p[0])));
    const lastReal = word.realized_aligned.at(-1);
    const firstNext = next?.realized_aligned[0];
    return {
      start,
      end,
      span,
      canonical,
      real,
      dictionary,
      // A dictionary symbol absent from what was pronounced is exactly what the
      // learner is looking for (the /t/ in "better", the /d/ in "and").
      dictionaryDiff: dictionary.map((symbol) => !saidHere.has(bareSymbol(symbol))),
      canonicalDiff: markDiff(canonical, real),
      realDiff: markDiff(real, canonical),
      boundary: next ? (next.start - start) / span : null,
      // The real gap at the boundary: this is the measure of linking (around
      // 20 ms when linked; a plain boundary sits nearer 60).
      gapMs: lastReal && firstNext ? Math.round((firstNext[1] - lastReal[2]) * 1000) : null,
      tie:
        lastReal && firstNext
          ? (lastReal[2] + firstNext[1]) / 2
          : null,
    };
  }, [word, next, reference.ipa_tokens]);

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
            ∅ nothing recognized
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
            title={`${symbol} · detected at ${start.toFixed(2)} s${
              diff[index] ? " · no counterpart in the other row" : ""
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
      <div className="phones__label">dictionary (citation form, untimed)</div>
      <div className="phones__dict">
        {view.dictionary.map((symbol, index) => (
          <span
            key={`dict-${index}-${symbol}`}
            className={`phone phone--static ${view.dictionaryDiff[index] ? "phone--diff" : ""}`}
            title={
              view.dictionaryDiff[index]
                ? `${symbol}: in the dictionary but not in what was pronounced`
                : symbol
            }
          >
            {symbol}
          </span>
        ))}
      </div>

      <div className="phones__timed">
        {row(view.canonical, view.canonicalDiff, "aligned canonical", "can")}
        {row(view.real, view.realDiff, "actually pronounced", "real")}

        {view.tie !== null && view.gapMs !== null && (
          <span
            className="phones__tie"
            style={{ left: `${((view.tie - view.start) / view.span) * 100}%` }}
            title={`${view.gapMs} ms between the two words`}
          >
            ‿
          </span>
        )}

        <div className="phones__axis">
          <span className="phones__tick" style={{ left: 0, transform: "none" }}>
            {view.start.toFixed(2)} s
          </span>
          {view.boundary !== null && (
            <span className="phones__tick" style={{ left: `${view.boundary * 100}%` }}>
              boundary
            </span>
          )}
          <span className="phones__tick" style={{ right: 0, transform: "none" }}>
            {view.end.toFixed(2)} s
          </span>
        </div>
        {view.boundary !== null && (
          <div className="phones__boundary" style={{ left: `${view.boundary * 100}%` }} />
        )}
        {playheadPct !== null && (
          <div className="phones__playhead" style={{ left: `${playheadPct}%` }} />
        )}
      </div>
      {view.gapMs !== null && (
        <p className="tiny dim" style={{ margin: "10px 0 0" }}>
          Gap at the boundary: <strong>{view.gapMs} ms</strong>
          {view.gapMs <= 30
            ? " — they run together, and that is the linking (an unlinked boundary sits nearer 60 ms)."
            : " — no appreciable linking."}
        </p>
      )}
      <p className="tiny muted" style={{ margin: "10px 0 0" }}>
        Each box marks the <strong>detected instant</strong> (a 20 ms CTC peak), not the duration
        of the phone. Click one to hear it.
      </p>
    </div>
  );
}
