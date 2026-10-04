/** What the workspace needs to know about a word beyond the raw analysis.json fields. */

import { lexicalPractice, phenomenonLabel, phenomenonPractice } from "../../../reference";
import type { Analysis, Practice, Reference, Word } from "../../../types";
import { markOfPhenomenon } from "../../../ui";

/** True when there is nothing to compare this word against. */
export const hasNoCanonical = (word: Word): boolean =>
  Boolean(word.no_canonical) || word.canonical_aligned.length === 0;

/** The spoken form of a numeral, when it differs from what was written. */
export function spokenForm(word: Word): string | null {
  const text = word.canonical_text?.trim();
  if (!text) return null;
  return text.toLowerCase() === word.word.toLowerCase() ? null : text;
}

/** Phenomena that happen at the boundary with the FOLLOWING word: they can only be
 *  heard together with it. (h-dropping is word-internal; its context is the word
 *  before.) */
const BOUNDARY = new Set(["linking", "palatalization"]);

export function crossesBoundary(word: Word, next: Word | null): next is Word {
  return next !== null && (word.boundary_link_next || word.phenomena.some((p) => BOUNDARY.has(p)));
}

/** Labels that cannot be checked: nothing recognized, or the recognizer was unsure. */
export type Reliability = "ok" | "no-phones" | "low-confidence";

export function reliability(word: Word): Reliability {
  if (word.realized_aligned.length === 0) return "no-phones";
  if (word.low_confidence || word.phenomena.includes("word_elision")) return "low-confidence";
  return "ok";
}

/** "that, t/d deletion": a word's accessible name states its phenomena, so its
 *  identity never relies on the underline color. */
export function wordAccessibleName(word: Word, reference: Reference): string {
  const names = word.phenomena.map((p) => phenomenonLabel(reference, p));
  return names.length ? `${word.word}, ${names.join(", ")}` : word.word;
}

/** The first family that colors the word (the rest are named in text and icons). */
export function primaryFamily(word: Word, reference: Reference): string | null {
  for (const phenomenon of word.phenomena) {
    const family = reference.family_of[phenomenon];
    if (family) return family;
  }
  return null;
}

/** True when the word carries a change outside the four families that is not a lexical
 *  contraction (e.g. word_elision): it gets the neutral mark, never the contraction's. */
export function hasNeutralMark(word: Word, reference: Reference): boolean {
  return word.phenomena.some((phenomenon) => markOfPhenomenon(phenomenon, reference.family_of) === "none");
}

/** Every family on the word, without repeats, in label order. */
export function wordFamilies(word: Word, reference: Reference): string[] {
  const out: string[] = [];
  for (const phenomenon of word.phenomena) {
    const family = reference.family_of[phenomenon];
    if (family && !out.includes(family)) out.push(family);
  }
  return out;
}

/** The report's advice for one label on one word: a lexical contraction takes the
 *  advice of its own reduced form ("tryna" is marked even though "contraction" is not). */
export function adviceFor(reference: Reference, word: Word, phenomenon: string): Practice | null {
  if (phenomenon === "contraction_lex" && word.lexical_form) {
    return lexicalPractice(reference, word.lexical_form) ?? phenomenonPractice(reference, phenomenon);
  }
  return phenomenonPractice(reference, phenomenon);
}

/** Phenomena of this analysis with the given advice, most frequent first. */
export function phenomenaByPractice(
  analysis: Analysis,
  reference: Reference,
  kind: Practice["practice"],
): string[] {
  return Object.entries(analysis.summary.phenomena_counts)
    .sort((a, b) => b[1] - a[1])
    .map(([name]) => name)
    .filter((name) => phenomenonPractice(reference, name)?.practice === kind);
}

/** The engine that produced an analysis; analyses from before the field were espeak. */
export const engineOf = (analysis: Analysis): string => analysis.meta.phone_engine ?? "espeak";

/** With the narrow engine the expected row IS the dictionary form, placed in time;
 *  espeak's expected form already applies some native processes ([bɛɾɚ]). */
export const isNarrowEngine = (engine: string): boolean => engine !== "espeak" && engine !== "wav2vec2";
