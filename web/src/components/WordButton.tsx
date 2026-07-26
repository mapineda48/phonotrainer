/** Una palabra de la transcripción: color por familia de fenómeno, marcas no
 *  cromáticas (punteado, negrita, opacidad) y clic para oírla. */

import { memo } from "react";

import { familyColor, familyTint, phenomenonLabel } from "../reference";
import type { Reference, Word } from "../types";

export interface WordButtonProps {
  word: Word;
  reference: Reference;
  selected: boolean;
  playing: boolean;
  emphasis: boolean;
  /** Fuera del filtro activo: se atenúa, no se oculta (el texto sigue leyéndose). */
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

  // Diccionario → real: el par que enseña algo. El canónico solo cuando aporta
  // (si coincide con el real, repetirlo hacía que la mitad de los tooltips
  // dijeran "[X] vs [X]" justo en las palabras más interesantes).
  const realized = word.realized_ipa || "∅";
  const parts = [`${word.word} · /${word.dict_ipa}/ → [${realized}]`];
  if (word.canonical_ipa !== word.realized_ipa && word.canonical_ipa !== word.dict_ipa) {
    parts.push(`canónico [${word.canonical_ipa}]`);
  }
  if (names.length) parts.push(names.join(", "));
  const title = parts.join(" — ");

  // El color no puede ser el único portador de la identidad del fenómeno:
  // aquí va en el nombre accesible, no solo en el tooltip del ratón.
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
