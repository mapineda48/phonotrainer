/** Helpers over analysis.json: flattening, lookup by time and filtering. */

import type { Analysis, Segment, Word } from "../types";

export interface FlatWord {
  word: Word;
  /** index of the segment, and of the word within it */
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

/** We treat a word as active slightly before it starts: clicking it plays with
 *  a bit of lead-in, and the highlight must land on that word, not the previous
 *  one. */
const LOOKAHEAD = 0.05;

/**
 * Index of the item "sounding" at `t`: the last one that started before `t`, as
 * long as it ended no more than `tolerance` seconds ago (so the highlight does
 * not flicker during the silences between words). -1 if there is none.
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

/** A word's phenomenon families (`contraction_lex` has no family). */
export function wordFamilies(word: Word, familyOf: Record<string, string>): string[] {
  const families = word.phenomena.map((p) => familyOf[p]).filter(Boolean);
  return [...new Set(families)];
}

/** Phenomena present in the analysis, ordered by frequency. */
export function phenomenaByFrequency(analysis: Analysis): [string, number][] {
  return Object.entries(analysis.summary.phenomena_counts).sort((a, b) => b[1] - a[1]);
}

/** Does the word pass the filter? An empty filter lets everything through. */
export function matchesFilter(word: Word, selected: ReadonlySet<string>): boolean {
  if (selected.size === 0) return true;
  return word.phenomena.some((p) => selected.has(p));
}

/** Words matching the filter, in time order (for jumping between them). */
export function filteredWords(analysis: Analysis, selected: ReadonlySet<string>): FlatWord[] {
  if (selected.size === 0) return [];
  return flattenWords(analysis).filter((fw) => matchesFilter(fw.word, selected));
}

/** Anything shorter than this is inaudible: some words span only one or two
 *  CTC peaks (20 ms), and playing them as-is was just silence. */
const MIN_AUDIBLE = 0.25;

/** A word's time span, padded so it can be heard in full: little at the front
 *  (so as not to intrude on the previous word) and a bit more at the back. */
export function wordSpan(word: Word, padStart = 0.02, padEnd = 0.06): { start: number; end: number } {
  const start = Math.max(0, word.start - padStart);
  const end = word.end + padEnd;
  if (end - start >= MIN_AUDIBLE) return { start, end };
  // Stretch around the midpoint, without going below zero.
  const center = (word.start + word.end) / 2;
  const from = Math.max(0, center - MIN_AUDIBLE / 2);
  return { start: from, end: from + MIN_AUDIBLE };
}

export function segmentSpan(segment: Segment): { start: number; end: number } {
  return { start: segment.start, end: segment.end };
}

/** Stable key for a word within the analysis. */
export const wordKey = (segment: number, index: number): string => `${segment}:${index}`;
