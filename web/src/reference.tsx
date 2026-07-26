/** Taxonomía de fenómenos servida por el backend (`/api/reference`).
 *
 *  La UI no guarda su propia copia de etiquetas ni de familias: si mañana
 *  `phenomena.py` gana una regla, aparece aquí sin tocar el frontend.
 */

import { createContext, useContext } from "react";

import type { Reference, Word } from "./types";

const ReferenceContext = createContext<Reference | null>(null);

export const ReferenceProvider = ReferenceContext.Provider;

export function useReference(): Reference {
  const reference = useContext(ReferenceContext);
  if (!reference) throw new Error("useReference() necesita un <ReferenceProvider>");
  return reference;
}

/** Color de la familia como variable CSS (las define styles.css). */
export const familyColor = (family: string): string => `var(--fam-${family})`;
export const familyTint = (family: string): string => `var(--tint-${family})`;

export const phenomenonLabel = (reference: Reference, name: string): string =>
  reference.labels[name] ?? name;

export const phenomenonDescription = (reference: Reference, name: string): string =>
  reference.descriptions?.[name] ?? "";

/** Primera familia con color de una palabra (el resto se nombra por texto). */
export function primaryFamily(reference: Reference, word: Word): string | null {
  for (const phenomenon of word.phenomena) {
    const family = reference.family_of[phenomenon];
    if (family) return family;
  }
  return null;
}
