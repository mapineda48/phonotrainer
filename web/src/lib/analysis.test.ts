import { describe, expect, it } from "vitest";

import { analysis, makeWord } from "../test/fixtures";
import {
  filteredWords,
  findActiveIndex,
  flattenWords,
  matchesFilter,
  phenomenaByFrequency,
  wordSpan,
} from "./analysis";

const spans = [
  { start: 0, end: 0.5 },
  { start: 1, end: 1.4 },
  { start: 2, end: 2.9 },
];

describe("findActiveIndex", () => {
  it("finds the span containing the instant", () => {
    expect(findActiveIndex(spans, 0.2)).toBe(0);
    expect(findActiveIndex(spans, 1.3)).toBe(1);
    expect(findActiveIndex(spans, 2.5)).toBe(2);
  });

  it("holds the highlight across the gap between words", () => {
    expect(findActiveIndex(spans, 0.7)).toBe(0); // within the tolerance
    expect(findActiveIndex(spans, 0.9)).toBe(-1); // long silence
  });

  it("gets ahead of the word that is about to sound", () => {
    // clicking a word plays it 20 ms early: the highlight is already its own
    expect(findActiveIndex(spans, 0.98)).toBe(1);
  });

  it("returns -1 before anything starts", () => {
    expect(findActiveIndex(spans, -1)).toBe(-1);
    expect(findActiveIndex([], 5)).toBe(-1);
  });
});

describe("filtering", () => {
  const word = makeWord("that", 0, "ð æ t", "ð æ", { phenomena: ["t_deletion"] });

  it("an empty filter lets everything through", () => {
    expect(matchesFilter(word, new Set())).toBe(true);
  });

  it("filters by phenomenon", () => {
    expect(matchesFilter(word, new Set(["t_deletion"]))).toBe(true);
    expect(matchesFilter(word, new Set(["flapping"]))).toBe(false);
  });

  it("lists the filtered words in time order", () => {
    const matches = filteredWords(analysis, new Set(["vowel_reduction", "contraction_lex"]));
    expect(matches.map((match) => match.word.word)).toEqual(["does", "wanna"]);
    expect(matches[1]).toMatchObject({ segment: 1, index: 0 });
  });

  it("with no filter there is nothing to jump between", () => {
    expect(filteredWords(analysis, new Set())).toEqual([]);
  });
});

describe("assorted helpers", () => {
  it("flattens the words while preserving their position", () => {
    const flat = flattenWords(analysis);
    expect(flat).toHaveLength(5);
    expect(flat[3]).toMatchObject({ segment: 1, index: 0 });
  });

  it("orders phenomena from most to least frequent", () => {
    expect(phenomenaByFrequency(analysis)).toEqual([
      ["linking", 7],
      ["vowel_reduction", 4],
      ["t_deletion", 2],
      ["contraction_lex", 1],
    ]);
  });

  it("pads a word's span without going negative", () => {
    const long = makeWord("long", 1, "a b c d e f", "a b c d e f");   // ~0.4 s
    expect(wordSpan(long)).toEqual({ start: 0.98, end: long.end + 0.06 });
  });

  it("stretches inaudible spans: some words last a single frame", () => {
    // 50 ms plus padding was still silence when played back
    const short = makeWord("x", 0, "a", "a");
    const span = wordSpan(short);
    expect(span.end - span.start).toBeCloseTo(0.25, 3);
    expect(span.start).toBe(0);                       // without going below zero
    expect(wordSpan(makeWord("y", 10, "a", "a")).start).toBeCloseTo(9.9, 3);
  });
});
