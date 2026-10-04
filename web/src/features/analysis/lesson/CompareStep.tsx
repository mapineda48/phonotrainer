/** Lesson step 2 — Compare: the dictionary form against what was said, first as two
 *  lines, then sound by sound. Every column gets a mark that is a symbol and a word,
 *  never a color: = same, ≠ changed, – dropped, + added. Each sound is a button that
 *  plays it and explains it. */

import { ArrowRight, Eye, Play } from "lucide-react";
import { useMemo } from "react";

import { lookupPhone } from "../../../articulation/phones";
import { Explain } from "../../../didactic/Explain";
import { ipaTerm } from "../../../didactic/glossary";
import { paths } from "../../../paths";
import { usePlayer } from "../../../player/PlayerProvider";
import { useReference } from "../../../reference";
import type { AlignedPhone, Word } from "../../../types";
import { Button, ButtonRow, cn, Ipa, Notice, TextLink } from "../../../ui";
import {
  compareWords,
  dictionaryPhones,
  LINKED_GAP_MS,
  OP_MARK,
  OP_TEXT,
  type Column,
} from "../lib/phones";
import { hasNoCanonical, spokenForm } from "../lib/words";

/** A sound picked in the table: which one, and when it was detected. */
export interface PickedPhone {
  symbol: string;
  start: number;
  end: number;
  stream: "real" | "canonical";
}

interface Props {
  word: Word;
  /** The next word, when the phenomenon crosses the boundary. */
  next: Word | null;
  /** narrow engine: the expected row IS the dictionary form, placed in time. */
  narrow: boolean;
  canPlay: boolean;
  picked: PickedPhone | null;
  onPick: (phone: PickedPhone) => void;
  /** Open step 4 holding the picked sound. */
  onSeeMouth: () => void;
}

const PAD = 0.02; // s around a single phone, so it is audible

function PhoneButton({
  phone,
  stream,
  picked,
  onPick,
  struck,
}: {
  phone: AlignedPhone;
  stream: PickedPhone["stream"];
  picked: PickedPhone | null;
  onPick: Props["onPick"];
  struck?: boolean;
}) {
  const [symbol, start, end] = phone;
  const isPicked = picked?.stream === stream && picked.start === start && picked.symbol === symbol;
  return (
    <button
      type="button"
      aria-pressed={isPicked}
      onClick={() => onPick({ symbol, start, end, stream })}
      className={cn(
        "ipa-text inline-flex min-h-9 min-w-9 items-center justify-center rounded-control px-1.5 text-lg text-ink",
        "bg-surface shadow-[inset_0_0_0_1px_var(--line-strong)] hover:bg-surface-2",
        struck && "bg-surface-2 text-ink-2 line-through decoration-2 shadow-[inset_0_0_0_1.5px_var(--ink-2)]",
        isPicked && "bg-ink text-page shadow-none hover:bg-ink",
      )}
    >
      <span lang="und-fonipa">{symbol}</span>
    </button>
  );
}

/** The column's change as a symbol; its word is for screen readers only. Printed under
 *  every column, the words ran together ("changed changed changed") and read as one
 *  phrase; the legend under the table says what each symbol means. */
function OpMark({ column }: { column: Column }) {
  return (
    <span className="inline-flex flex-col items-center leading-tight">
      <span aria-hidden="true" className={cn("text-lg font-bold", column.op === "same" ? "text-ink-muted" : "text-ink")}>
        {OP_MARK[column.op]}
      </span>
      <span className="sr-only">{OP_TEXT[column.op]}</span>
    </span>
  );
}

function PhoneInfo({
  picked,
  canPlay,
  onPlay,
  onSeeMouth,
}: {
  picked: PickedPhone;
  canPlay: boolean;
  onPlay: () => void;
  onSeeMouth: () => void;
}) {
  const info = lookupPhone(picked.symbol);
  return (
    <div className="flex flex-col gap-2 rounded-card bg-surface-2 p-3" aria-live="polite">
      <p className="flex flex-wrap items-baseline gap-x-2">
        <Ipa kind="phonetic" className="text-2xl">
          {picked.symbol}
        </Ipa>
        <span className="font-semibold text-ink">{info?.name ?? "a sound outside the drawable inventory"}</span>
        <Explain term={ipaTerm(picked.symbol)} buttonLabel={`What's this: [${picked.symbol}]`} />
      </p>
      {info && (
        <p className="text-sm text-ink-2">
          {info.cue} <span className="text-ink">As in “{info.example}”.</span>
        </p>
      )}
      <ButtonRow>
        <Button size="sm" icon={Play} isDisabled={!canPlay} onPress={onPlay}>
          Play this sound
        </Button>
        {info && (
          <Button size="sm" icon={Eye} onPress={onSeeMouth}>
            See it in the mouth
          </Button>
        )}
        <TextLink href={paths.ipa(picked.symbol)} className="inline-flex items-center gap-1 text-sm">
          Open in the IPA chart <ArrowRight size={14} aria-hidden="true" />
        </TextLink>
      </ButtonRow>
    </div>
  );
}

export function CompareStep({ word, next, narrow, canPlay, picked, onPick, onSeeMouth }: Props) {
  const reference = useReference();
  const player = usePlayer();
  const comparison = useMemo(() => compareWords(word, next), [word, next]);
  const dictionary = useMemo(() => dictionaryPhones(word, reference.ipa_tokens), [word, reference.ipa_tokens]);
  const spoken = spokenForm(word);
  const noCanonical = hasNoCanonical(word);
  const { columns, boundaryAt, gapMs } = comparison;
  const ownCount = boundaryAt ?? columns.length;

  const play = (phone: PickedPhone) => {
    if (canPlay) player.play({ start: phone.start - PAD, end: phone.end + PAD });
  };

  const pick = (phone: PickedPhone) => {
    onPick(phone);
    play(phone);
  };

  const cells = (row: "expected" | "heard") =>
    columns.map((column, index) => {
      const phone = row === "expected" ? column.expected : column.heard;
      const isBoundary = boundaryAt !== null && index === boundaryAt;
      return (
        <td
          key={`${row}-${index}`}
          className={cn("px-0.5 py-1 text-center", isBoundary && "border-l-2 border-dashed border-line-strong ps-2")}
        >
          {phone ? (
            <PhoneButton
              phone={phone}
              stream={row === "expected" ? "canonical" : "real"}
              picked={picked}
              onPick={pick}
              struck={row === "expected" && column.op === "dropped"}
            />
          ) : (
            <span className="ipa-text text-lg text-ink-muted" aria-label="nothing">
              ∅
            </span>
          )}
        </td>
      );
    });

  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-[auto_1fr] items-baseline gap-x-4 gap-y-2">
        <dt className="text-sm text-ink-2">
          <Explain term="dictionary-form">Dictionary</Explain>
        </dt>
        <dd className="m-0">
          <span lang="und-fonipa" className="ipa-text text-2xl text-ink" data-testid="dictionary-line">
            /
            {dictionary.map((phone, index) =>
              phone.heard ? (
                <span key={index}>{phone.symbol}</span>
              ) : (
                <span key={index}>
                  <s className="decoration-2">{phone.symbol}</s>
                  <span className="sr-only"> (not heard)</span>
                </span>
              ),
            )}
            /
          </span>
        </dd>
        <dt className="text-sm text-ink-2">
          <Explain term="what-was-said">What was said</Explain>
        </dt>
        <dd className="m-0">
          <Ipa kind="phonetic" className="text-2xl text-ink">
            {word.realized_ipa || "∅"}
          </Ipa>
        </dd>
        {!narrow && (
          <>
            <dt className="text-sm text-ink-2">
              <Explain term="canonical">Expected by the aligner</Explain>
            </dt>
            <dd className="m-0">
              <Ipa kind="phonetic" className="text-xl text-ink-2">
                {word.canonical_ipa || "∅"}
              </Ipa>
            </dd>
          </>
        )}
      </dl>

      {spoken && (
        <p className="text-sm text-ink-2" data-testid="spoken-form">
          Written “{word.word}”, said as “<span className="text-ink">{spoken}</span>”: the comparison uses the spoken
          form.
        </p>
      )}

      {noCanonical ? (
        <Notice title="No dictionary form">
          “{word.word}” is a number or symbol the dictionary cannot spell out, so there is nothing to compare what was
          said against.
        </Notice>
      ) : (
        <>
          <div className="relative overflow-x-auto">
            <table className="border-separate border-spacing-y-1">
              <caption className="sr-only">
                Sound by sound: the expected sounds, what was heard at the same moment, and what changed
              </caption>
              {boundaryAt !== null && next && (
                <thead>
                  <tr>
                    <td />
                    <th scope="colgroup" colSpan={ownCount} className="px-1 pb-1 text-left text-xs font-semibold text-ink-2">
                      {word.word}
                    </th>
                    <th
                      scope="colgroup"
                      colSpan={columns.length - ownCount}
                      className="border-l-2 border-dashed border-line-strong px-1 ps-2 pb-1 text-left text-xs font-semibold text-ink-2"
                    >
                      {next.word}
                    </th>
                  </tr>
                </thead>
              )}
              <tbody>
                <tr>
                  <th scope="row" className="pe-3 text-left text-xs font-semibold text-ink-2">
                    {narrow ? "Dictionary, in time" : "Expected"}
                  </th>
                  {cells("expected")}
                </tr>
                <tr>
                  <th scope="row" className="pe-3 text-left text-xs font-semibold text-ink-2">
                    Heard
                  </th>
                  {cells("heard")}
                </tr>
                <tr>
                  <th scope="row" className="pe-3 text-left text-xs font-semibold text-ink-2">
                    Change
                  </th>
                  {columns.map((column, index) => (
                    <td
                      key={`op-${index}`}
                      className={cn(
                        "px-0.5 text-center",
                        boundaryAt !== null && index === boundaryAt && "border-l-2 border-dashed border-line-strong ps-2",
                      )}
                    >
                      <OpMark column={column} />
                    </td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
          <p className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-2" aria-label="What the marks mean">
            {(["same", "changed", "dropped", "added"] as const).map((op) => (
              <span key={op} className="inline-flex items-center gap-1">
                <span aria-hidden="true" className="font-bold text-ink">
                  {OP_MARK[op]}
                </span>
                {OP_TEXT[op]}
              </span>
            ))}
          </p>
          {gapMs !== null && next && (
            <p className="text-sm text-ink" data-testid="boundary-gap">
              Gap between “{word.word}” and “{next.word}”:{" "}
              <strong>{gapMs <= 0 ? "none, the sounds overlap" : `${gapMs} ms`}</strong>
              {gapMs <= LINKED_GAP_MS
                ? " — they run together: that is the linking (an unlinked boundary sits nearer 60 ms)."
                : " — no clear linking at this boundary."}
            </p>
          )}
          <p className="text-xs text-ink-muted">
            {narrow
              ? "Each column pairs a sound the dictionary expects with what was heard at the same moment. Pick a sound to hear it and see what it is."
              : "With the espeak engine the expected row already includes some native changes (better → [bɛɾɚ]); the dictionary line above is the citation form."}
          </p>
        </>
      )}

      {picked && (
        <PhoneInfo
          picked={picked}
          canPlay={canPlay}
          onPlay={() => play(picked)}
          onSeeMouth={onSeeMouth}
        />
      )}
    </div>
  );
}
