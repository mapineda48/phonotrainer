/** A word in the transcript: colored by phenomenon family, with non-chromatic
 *  marks (dotted underline, bold, opacity) and a click to hear it. */

import { memo } from "react";

import { familyColor, familyTint, phenomenonLabel } from "../reference";
import type { Reference, Word } from "../types";

export interface WordButtonProps {
  word: Word;
  reference: Reference;
  selected: boolean;
  playing: boolean;
  emphasis: boolean;
  /** Outside the active filter: dimmed, not hidden (the text stays readable). */
  dimmed: boolean;
  onSelect: () => void;
}

function WordButtonImpl({
  word,
  reference,
  selected,
  playing,
  emphasis,
  dimmed,
  onSelect,
}: WordButtonProps) {
  const family = word.phenomena.map((p) => reference.family_of[p]).find(Boolean) ?? null;
  const names = word.phenomena.map((p) => phenomenonLabel(reference, p));

  const classes = ["w"];
  if (family) classes.push("w--fam");
  if (word.phenomena.includes("contraction_lex")) classes.push("w--contr");
  if (emphasis) classes.push("w--emph");
  if (word.low_confidence) classes.push("w--lowconf");
  if (dimmed) classes.push("w--muted");
  if (selected) classes.push("w--selected");
  if (playing) classes.push("w--playing");

  // Dictionary → actual: the pair that teaches something. The canonical form
  // only when it adds anything (when it matches the actual one, repeating it
  // made half the tooltips read "[X] vs [X]" on the most interesting words).
  const realized = word.realized_ipa || "∅";
  const parts = [`${word.word} · /${word.dict_ipa}/ → [${realized}]`];
  if (word.canonical_ipa !== word.realized_ipa && word.canonical_ipa !== word.dict_ipa) {
    parts.push(`canonical [${word.canonical_ipa}]`);
  }
  if (names.length) parts.push(names.join(", "));
  const title = parts.join(" — ");

  // Color cannot be the sole carrier of the phenomenon's identity: it goes into
  // the accessible name here, not only into the mouse tooltip.
  const label = names.length ? `${word.word}, ${names.join(", ")}` : word.word;

  return (
    <>
      <button
        type="button"
        className={classes.join(" ")}
        style={
          family
            ? ({ "--fam": familyColor(family), "--tint": familyTint(family) } as React.CSSProperties)
            : undefined
        }
        aria-pressed={selected}
        aria-label={label}
        title={title}
        onClick={onSelect}
      >
        {word.word}
      </button>
      {word.lexical_form && <span className="w__chip">{word.lexical_form}</span>}
      {word.boundary_link_next ? <span className="w__tie">‿</span> : " "}
    </>
  );
}

export const WordButton = memo(WordButtonImpl);
