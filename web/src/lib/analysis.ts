/** Utilidades sobre analysis.json: aplanado, búsqueda por tiempo y filtros. */

import type { Analysis, Segment, Word } from "../types";

export interface FlatWord {
  word: Word;
  /** índice del segmento y de la palabra dentro de él */
  segment: number;
  index: number;
}

export function flattenWords(analysis: Analysis): FlatWord[] {
  const out: FlatWord[] = [];
  analysis.segments.forEach((segment, si) =>
    segment.words.forEach((word, wi) => out.push({ word, segment: si, index: wi })),
  );
  return out;
}

/** Un poco antes de que empiece una palabra ya la damos por activa: al pulsarla
 *  se reproduce con un margen previo y el resaltado debe caer en ella, no en la
 *  anterior. */
const LOOKAHEAD = 0.05;

/**
 * Índice del elemento "sonando" en `t`: el último que empezó antes de `t`,
 * siempre que no haga más de `tolerance` segundos que terminó (así el resaltado
 * no parpadea en los silencios entre palabras). -1 si no hay ninguno.
 */
export function findActiveIndex(
  spans: readonly { start: number; end: number }[],
  t: number,
  tolerance = 0.35,
): number {
  let lo = 0;
  let hi = spans.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (spans[mid].start <= t) {
      found = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  const next = spans[found + 1];
  if (next && next.start - t <= LOOKAHEAD) return found + 1;
  if (found === -1) return -1;
  return t <= spans[found].end + tolerance ? found : -1;
}

/** Familias de fenómenos de una palabra (`contraction_lex` no tiene familia). */
export function wordFamilies(word: Word, familyOf: Record<string, string>): string[] {
  const families = word.phenomena.map((p) => familyOf[p]).filter(Boolean);
  return [...new Set(families)];
}

/** Fenómenos presentes en el análisis, en orden de frecuencia. */
export function phenomenaByFrequency(analysis: Analysis): [string, number][] {
  return Object.entries(analysis.summary.phenomena_counts).sort((a, b) => b[1] - a[1]);
}

/** ¿La palabra pasa el filtro? Un filtro vacío deja pasar todo. */
export function matchesFilter(word: Word, selected: ReadonlySet<string>): boolean {
  if (selected.size === 0) return true;
  return word.phenomena.some((p) => selected.has(p));
}

/** Palabras que cumplen el filtro, en orden temporal (para saltar entre ellas). */
export function filteredWords(analysis: Analysis, selected: ReadonlySet<string>): FlatWord[] {
  if (selected.size === 0) return [];
  return flattenWords(analysis).filter((fw) => matchesFilter(fw.word, selected));
}

/** Span temporal de una palabra con un margen para que se oiga entera: poco por
 *  delante (para no invadir la palabra anterior) y algo más por detrás. */
export function wordSpan(word: Word, padStart = 0.02, padEnd = 0.06): { start: number; end: number } {
  return { start: Math.max(0, word.start - padStart), end: word.end + padEnd };
}

export function segmentSpan(segment: Segment): { start: number; end: number } {
  return { start: segment.start, end: segment.end };
}

/** Clave estable de una palabra dentro del análisis. */
export const wordKey = (segment: number, index: number): string => `${segment}:${index}`;
