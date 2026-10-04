/** Corpus data for Learn and Practice: loading hooks, picking clean examples, and
 *  splitting an IPA string into the inventory's symbols. */

import { useEffect, useState } from "react";

import { api } from "../../api";
import { bareSymbol } from "../../articulation/phones";
import type { CorpusStats, Occurrence } from "../../types";

export type Loadable<T> =
  | { status: "loading" }
  | { status: "ready"; data: T }
  | { status: "error"; message: string };

/** Run `load` once per `key`; a newer key wins over a slower older request. */
export function useLoad<T>(key: string, load: () => Promise<T>): Loadable<T> {
  const [state, setState] = useState<Loadable<T>>({ status: "loading" });
  useEffect(() => {
    let active = true;
    setState({ status: "loading" });
    load().then(
      (data) => active && setState({ status: "ready", data }),
      (error: unknown) =>
        active && setState({ status: "error", message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      active = false;
    };
    // `load` is described by `key`
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return state;
}

export function useCorpusStats(): Loadable<CorpusStats> {
  return useLoad("corpus-stats", () => api.corpusStats());
}

/** How many times each phenomenon was found, or an empty map while loading/on error. */
export function phenomenonCounts(stats: Loadable<CorpusStats>): Map<string, number> {
  const counts = new Map<string, number>();
  if (stats.status === "ready") for (const row of stats.data.phenomena) counts.set(row.phenomenon, row.count);
  return counts;
}

/** Can this occurrence be played and trusted as an example? */
export function isPlayable(occurrence: Occurrence): occurrence is Occurrence & { job_id: string } {
  return Boolean(occurrence.job_id) && !occurrence.low_confidence && !occurrence.too_short;
}

/** Up to `limit` clean examples: playable, one per word, clearest alignment first (the
 *  occurrences arrive most divergent first, and the most divergent are mostly noise). */
export function pickExamples(occurrences: readonly Occurrence[], limit = 8): Occurrence[] {
  const seen = new Set<string>();
  const out: Occurrence[] = [];
  const sorted = occurrences.filter(isPlayable).sort((a, b) => a.diff_cost - b.diff_cost);
  for (const occurrence of sorted) {
    const key = occurrence.word.toLowerCase().replace(/[^a-z']/g, "");
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(occurrence);
    if (out.length >= limit) break;
  }
  return out;
}

/** Boundary phenomena happen between two words: the clip runs into the next one. */
export function clipPadding(family: string | null | undefined): { padBefore: number; padAfter: number } {
  return family === "boundary" ? { padBefore: 0.12, padAfter: 0.45 } : { padBefore: 0.1, padAfter: 0.12 };
}

const COMBINING = /[̀-ͯːˑ]/;
const IGNORED = /[\sˈˌ‿.|]/;

/** Split an IPA string into symbols: the inventory's multi-character symbols first
 *  (`multi`, longest first — the reference's `ipa_tokens`), otherwise one character plus
 *  any combining marks and length marks that follow it. Stress marks are dropped. */
export function tokenizeIpa(text: string, multi: readonly string[]): string[] {
  const out: string[] = [];
  let i = 0;
  while (i < text.length) {
    const char = text[i];
    if (IGNORED.test(char)) {
      i += 1;
      continue;
    }
    const token = multi.find((candidate) => text.startsWith(candidate, i));
    if (token) {
      out.push(token);
      i += token.length;
      continue;
    }
    let j = i + 1;
    while (j < text.length && COMBINING.test(text[j])) j += 1;
    out.push(text.slice(i, j));
    i = j;
  }
  return out;
}

/** Does this IPA string contain the symbol (ignoring length marks)? */
export function containsSymbol(text: string | null | undefined, symbol: string, multi: readonly string[]): boolean {
  if (!text) return false;
  const target = bareSymbol(symbol);
  return tokenizeIpa(text, multi).some((token) => bareSymbol(token) === target);
}

/** Dictionary IPA without stress marks: comparable with a realized string. */
export const stripStress = (ipa: string): string => ipa.replace(/[ˈˌ]/g, "");

/** A transcript word without the punctuation Whisper attaches to it ("order." → "order"). */
export function displayWord(word: string): string {
  return word.replace(/^[^\p{L}\p{N}']+|[^\p{L}\p{N}']+$/gu, "") || word;
}

export function capitalize(text: string): string {
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}
