/** Phone-level helpers for the word lesson.
 *
 *  - `splitPhones` cuts an untimed IPA string (the dictionary form) into phones.
 *  - `compareWords` pairs the expected phones with the heard ones and gives every
 *    column an operation mark — same (=), changed (≠), dropped (–), added (+) — so the
 *    comparison never relies on color.
 *
 *  The times are CTC peaks (one ~20 ms frame), not segmentations: the same phone can be
 *  detected a frame apart in each row, which is why "at the same moment" allows a
 *  small tolerance. */

import type { AlignedPhone, Word } from "../../../types";

/** Marks that are not a phone: stress. */
const STRESS = /[ˈˌ]/;
/** Diacritics and modifier letters that belong to the symbol before them. */
const COMBINING = /[ʰ-˿̀-ͯ᷀-᷿ⁿːˑ]/;

/** /uː/ and /u/, /ˈɛ/ and /ɛ/ are the same phone when comparing rows. */
export const bareSymbol = (symbol: string): string => symbol.replace(/[ˈˌː]/g, "");

/**
 * Split an untimed IPA string into phones. `tokens` are the multi-character symbols of
 * the inventory (published by the backend): without them "aɪ" would be split in two
 * and half of a correctly spoken word would read as dropped.
 */
export function splitPhones(ipa: string, tokens: readonly string[] = []): string[] {
  const multi = [...tokens].sort((a, b) => b.length - a.length);
  const out: string[] = [];
  let pending = ""; // a stress mark waiting for its phone
  let i = 0;
  while (i < ipa.length) {
    const char = ipa[i];
    if (STRESS.test(char)) {
      pending += char;
      i += 1;
      continue;
    }
    const token = multi.find((candidate) => ipa.startsWith(candidate, i));
    if (token) {
      out.push(pending + token);
      pending = "";
      i += token.length;
      continue;
    }
    if (!pending && out.length > 0 && COMBINING.test(char)) out[out.length - 1] += char;
    else out.push(pending + char);
    pending = "";
    i += 1;
  }
  return out;
}

/** Same phone detected a frame apart in each row still counts as "the same moment". */
export const FRAME = 0.04;

const overlaps = (a: AlignedPhone, b: AlignedPhone): boolean =>
  a[1] < b[2] + FRAME && b[1] < a[2] + FRAME;

export type Op = "same" | "changed" | "dropped" | "added";

export const OP_MARK: Record<Op, string> = { same: "=", changed: "≠", dropped: "–", added: "+" };

export const OP_TEXT: Record<Op, string> = {
  same: "same",
  changed: "changed",
  dropped: "dropped",
  added: "added",
};

/** One column of the comparison: an expected phone, a heard one, or both. */
export interface Column {
  op: Op;
  expected: AlignedPhone | null;
  heard: AlignedPhone | null;
  /** 0 = the selected word, 1 = the next word (boundary phenomena). */
  word: 0 | 1;
  /** Instant used to order the columns. */
  at: number;
}

function compareOne(expected: AlignedPhone[], heard: AlignedPhone[], word: 0 | 1): Column[] {
  const usedHeard = new Set<number>();
  const pairOf = new Map<number, { heard: number; op: Op }>();

  // 1. the same symbol at the same moment
  expected.forEach((phone, e) => {
    const h = heard.findIndex(
      (candidate, index) =>
        !usedHeard.has(index) && bareSymbol(candidate[0]) === bareSymbol(phone[0]) && overlaps(phone, candidate),
    );
    if (h >= 0) {
      usedHeard.add(h);
      pairOf.set(e, { heard: h, op: "same" });
    }
  });
  // 2. something else at the same moment: a change
  expected.forEach((phone, e) => {
    if (pairOf.has(e)) return;
    const h = heard.findIndex((candidate, index) => !usedHeard.has(index) && overlaps(phone, candidate));
    if (h >= 0) {
      usedHeard.add(h);
      pairOf.set(e, { heard: h, op: "changed" });
    }
  });

  const columns: Column[] = expected.map((phone, e) => {
    const pair = pairOf.get(e);
    return pair
      ? { op: pair.op, expected: phone, heard: heard[pair.heard], word, at: phone[1] }
      : { op: "dropped", expected: phone, heard: null, word, at: phone[1] };
  });
  // 3. whatever was heard and expected nowhere
  heard.forEach((phone, h) => {
    if (!usedHeard.has(h)) columns.push({ op: "added", expected: null, heard: phone, word, at: phone[1] });
  });
  return columns.sort((a, b) => a.at - b.at || (a.expected ? -1 : 1));
}

export interface Comparison {
  columns: Column[];
  /** Index of the first column of the next word, when it is included. */
  boundaryAt: number | null;
  /** Silence between the two words, in ms (the measure of linking). */
  gapMs: number | null;
}

/** Expected vs heard for a word (and the next one, when the phenomenon crosses the
 *  boundary). Each word is paired on its own: the next word's /t/ must never stand in
 *  for a /t/ this word dropped. */
export function compareWords(word: Word, next: Word | null = null): Comparison {
  const own = compareOne(word.canonical_aligned, word.realized_aligned, 0);
  if (!next) return { columns: own, boundaryAt: null, gapMs: null };
  const theirs = compareOne(next.canonical_aligned, next.realized_aligned, 1);
  const lastHeard = word.realized_aligned.at(-1);
  const firstNext = next.realized_aligned[0];
  return {
    columns: [...own, ...theirs],
    boundaryAt: own.length,
    gapMs: lastHeard && firstNext ? Math.round((firstNext[1] - lastHeard[2]) * 1000) : null,
  };
}

/** Linked words run together: an unlinked boundary sits nearer 60 ms. */
export const LINKED_GAP_MS = 30;

/** The dictionary form, symbol by symbol, each marked heard or not — against what THIS
 *  word realized only. */
export function dictionaryPhones(
  word: Word,
  tokens: readonly string[],
): { symbol: string; heard: boolean }[] {
  const said = new Set(word.realized_aligned.map((phone) => bareSymbol(phone[0])));
  return splitPhones(word.dict_ipa, tokens).map((symbol) => ({ symbol, heard: said.has(bareSymbol(symbol)) }));
}
