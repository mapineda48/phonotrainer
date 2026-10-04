/** A word in the transcript.
 *
 *  Its phenomenon family is shown three ways that do not depend on seeing color: the
 *  underline STYLE (solid, dashed, double, wavy; dotted for a lexical contraction,
 *  dash-dot for a change outside the families), the family icon after the word
 *  (with pattern emphasis on) and the accessible name, which states every phenomenon.
 *  The text itself always stays ink. State is shown by shape: selected = a 2 px ink
 *  ring, playing = an ink fill, low confidence = a dashed outline, outside the filter =
 *  faded AND without its underline. */

import { memo } from "react";

import type { Word } from "../../../types";
import { cn, FamilyInlineIcon, familyUnderlineClass } from "../../../ui";

export interface WordTokenProps {
  word: Word;
  segment: number;
  index: number;
  /** "that, t/d deletion" — computed once by the segment. */
  name: string;
  /** The family that draws the underline; null = none (a lexical contraction is dotted). */
  family: string | null;
  /** Every family on the word, for the inline icons. */
  families: readonly string[];
  /** It also carries a change outside the families that is not a contraction (an elided
   *  word): the neutral mark (dash-dot, CircleDashed). */
  neutral?: boolean;
  selected: boolean;
  playing: boolean;
  /** Outside the active filter or search: faded, not hidden (the text stays readable). */
  dimmed: boolean;
  /** The most prominent word of its phrase. */
  emphasis: boolean;
  /** The one word of the transcript that Tab lands on (roving focus). */
  focusable: boolean;
  onSelect: (segment: number, index: number) => void;
}

function WordTokenImpl({
  word,
  segment,
  index,
  name,
  family,
  families,
  neutral = false,
  selected,
  playing,
  dimmed,
  emphasis,
  focusable,
  onSelect,
}: WordTokenProps) {
  const lexical = word.phenomena.includes("contraction_lex") || Boolean(word.lexical_form);
  const mark = family ?? (lexical ? "lexical" : neutral ? "none" : null);
  const underline = dimmed ? null : mark;
  const lowConfidence = word.low_confidence || word.realized_aligned.length === 0;
  const chip = word.lexical_form
    ? word.lexical_expansion && word.lexical_expansion !== word.lexical_form
      ? `${word.lexical_form} ← ${word.lexical_expansion}`
      : word.lexical_form
    : null;

  return (
    <>
      <button
        type="button"
        data-word={`${segment}:${index}`}
        data-family={family ?? undefined}
        data-mark={mark ?? undefined}
        data-selected={selected || undefined}
        data-playing={playing || undefined}
        data-dimmed={dimmed || undefined}
        data-low-confidence={lowConfidence || undefined}
        aria-pressed={selected}
        aria-label={name}
        tabIndex={focusable ? 0 : -1}
        onClick={() => onSelect(segment, index)}
        className={cn(
          "inline rounded-[4px] px-[3px] py-px text-ink outline-offset-2",
          "cursor-pointer transition-[background-color,opacity] duration-(--dur-fast)",
          "hover:bg-surface-2",
          underline && familyUnderlineClass(underline),
          emphasis && "font-semibold",
          dimmed && "opacity-45",
          lowConfidence &&
            "not-focus-visible:outline-1 not-focus-visible:outline-dashed not-focus-visible:outline-ink-muted not-focus-visible:-outline-offset-1",
          selected && "shadow-[0_0_0_2px_var(--ink)]",
          playing && "bg-ink text-page hover:bg-ink",
        )}
      >
        {word.word}
        {!dimmed && families.map((f) => <FamilyInlineIcon key={f} family={f} />)}
        {!dimmed && families.length === 0 && lexical && <FamilyInlineIcon family="lexical" />}
        {!dimmed && neutral && <FamilyInlineIcon family="none" />}
      </button>
      {chip && (
        <span className="ms-1 rounded-chip bg-surface-2 px-1.5 py-px align-[0.1em] text-xs text-ink-2">
          {chip}
        </span>
      )}
      {word.boundary_link_next ? (
        <span aria-hidden="true" className="px-px text-ink-muted">
          ‿
        </span>
      ) : (
        " "
      )}
    </>
  );
}

export const WordToken = memo(WordTokenImpl);
