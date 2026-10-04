/** Phenomenon taxonomy served by the backend (`/api/reference`).
 *
 *  The UI keeps no copy of its own for labels or families: if `phenomena.py`
 *  gains a rule tomorrow, it shows up here without touching the frontend.
 */

import { createContext, useContext } from "react";

import type { LinkType, Practice, Reference, Word } from "./types";

const ReferenceContext = createContext<Reference | null>(null);

export const ReferenceProvider = ReferenceContext.Provider;

export function useReference(): Reference {
  const reference = useContext(ReferenceContext);
  if (!reference) throw new Error("useReference() requires a <ReferenceProvider>");
  return reference;
}

/** Family color as a CSS variable (styles.css defines them). */
export const familyColor = (family: string): string => `var(--fam-${family})`;
export const familyTint = (family: string): string => `var(--tint-${family})`;

export const phenomenonLabel = (reference: Reference, name: string): string =>
  reference.labels[name] ?? name;

export const phenomenonDescription = (reference: Reference, name: string): string =>
  reference.descriptions?.[name] ?? "";

/** A word's first color-bearing family (the rest are named in text). */
export function primaryFamily(reference: Reference, word: Word): string | null {
  for (const phenomenon of word.phenomena) {
    const family = reference.family_of[phenomenon];
    if (family) return family;
  }
  return null;
}

/** The report's advice for a phenomenon (absent on older servers). */
export const phenomenonPractice = (reference: Reference, name: string): Practice | null =>
  reference.practice?.[name] ?? null;

/** The advice for a lexical reduced form ("gonna", "tryna"). */
export const lexicalPractice = (reference: Reference, form: string): Practice | null =>
  reference.lexical_practice?.[form.toLowerCase()] ?? null;

export const PRACTICE_LABEL: Record<Practice["practice"], string> = {
  produce: "safe to produce",
  understand: "recognize only",
};

export const LINK_TYPE_LABEL: Record<LinkType, string> = {
  consonant: "consonant → vowel",
  r: "linking r",
  glide_w: "glide [w]",
  glide_j: "glide [j]",
};

/** Phenomena present in `names` that the report says are safe to say. */
export function phenomenaToProduce(reference: Reference, names: Iterable<string>): string[] {
  return [...names].filter((name) => phenomenonPractice(reference, name)?.practice === "produce");
}
