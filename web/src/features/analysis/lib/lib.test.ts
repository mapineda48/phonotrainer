import { describe, expect, it } from "vitest";

import { makeWord, reference } from "../../../test/fixtures";
import { compareWords, dictionaryPhones, splitPhones, type Column } from "./phones";
import { describePhase, REPEAT_MARGIN_MS, shadowingPlan } from "./shadowing";
import { crossesBoundary, hasNoCanonical, reliability, spokenForm, wordAccessibleName } from "./words";

const ops = (columns: Column[]) =>
  columns.map((c) => `${c.expected?.[0] ?? "∅"}${c.op === "same" ? "=" : c.op === "changed" ? "≠" : c.op === "dropped" ? "–" : "+"}${c.heard?.[0] ?? "∅"}`);

describe("splitPhones", () => {
  const tokens = reference.ipa_tokens;

  it("keeps diacritics with their base symbol", () => {
    expect(splitPhones("bɛtɚ", tokens)).toEqual(["b", "ɛ", "t", "ɚ"]);
    expect(splitPhones("bʌʔn̩", tokens)).toEqual(["b", "ʌ", "ʔ", "n̩"]);
    expect(splitPhones("", tokens)).toEqual([]);
  });

  it("does not split multi-character symbols", () => {
    // "aɪ" split in two flagged half of a correctly spoken word as dropped
    expect(splitPhones("baɪ", tokens)).toEqual(["b", "aɪ"]);
    expect(splitPhones("bʌdʒɪt", tokens)).toEqual(["b", "ʌ", "dʒ", "ɪ", "t"]);
    expect(splitPhones("ɑːɹ", tokens)).toEqual(["ɑːɹ"]);
  });

  it("attaches the stress mark to the phone that carries it", () => {
    expect(splitPhones("bˈɛtɚ", tokens)).toEqual(["b", "ˈɛ", "t", "ɚ"]);
    expect(splitPhones("tənˈaɪt", tokens)).toEqual(["t", "ə", "n", "ˈaɪ", "t"]);
  });
});

describe("compareWords", () => {
  it("marks a dropped final t, and nothing else", () => {
    const that = makeWord("that", 0.4, "ð æ t", "ð æ");
    expect(ops(compareWords(that).columns)).toEqual(["ð=ð", "æ=æ", "t–∅"]);
  });

  it("pairs a different sound at the same moment as a change", () => {
    const better = makeWord("better", 1, "b ɛ t ɚ", "b ɛ ɾ ɚ");
    expect(ops(compareWords(better).columns)).toEqual(["b=b", "ɛ=ɛ", "t≠ɾ", "ɚ=ɚ"]);
  });

  it("marks a sound heard where nothing was expected as added", () => {
    const word = makeWord("film", 1, "f ɪ l m", "f ɪ l m");
    word.realized_aligned = [...word.realized_aligned, ["ə", 1.3, 1.35]];
    expect(ops(compareWords(word).columns).at(-1)).toBe("∅+ə");
  });

  it("does not call two phones that merely graze each other in time a change", () => {
    // CTC peaks: the same phone can be detected a frame apart in each row
    const offset = {
      ...makeWord("don't", 0.6, "d oʊ n", "d oʊ n"),
      canonical_aligned: [["n", 0.684, 0.704]] as [string, number, number][],
      realized_aligned: [["n", 0.704, 0.744]] as [string, number, number][],
    };
    expect(ops(compareWords(offset).columns)).toEqual(["n=n"]);
  });

  it("with a boundary, compares each word on its own and measures the gap", () => {
    // "that" loses its /t/; "time" starts with /t/: it must not stand in for the lost one
    const that = makeWord("that", 0.4, "ð æ t", "ð æ");
    const time = makeWord("time", 0.6, "t aɪ m", "t aɪ m");
    const result = compareWords(that, time);
    expect(ops(result.columns)).toEqual(["ð=ð", "æ=æ", "t–∅", "t=t", "aɪ=aɪ", "m=m"]);
    expect(result.boundaryAt).toBe(3);
    expect(result.gapMs).toBe(90);
  });

  it("checks the dictionary form against what this word realized only", () => {
    const better = makeWord("better", 8.9, "b ɛ ɾ ɚ", "b ɛ ɾ ɚ", { dict_ipa: "bɛtɚ" });
    expect(dictionaryPhones(better, reference.ipa_tokens)).toEqual([
      { symbol: "b", heard: true },
      { symbol: "ɛ", heard: true },
      { symbol: "t", heard: false },
      { symbol: "ɚ", heard: true },
    ]);
  });
});

describe("word helpers", () => {
  it("names the phenomena in the accessible name", () => {
    const that = makeWord("that", 0, "ð æ t", "ð æ", { phenomena: ["t_deletion"] });
    expect(wordAccessibleName(that, reference)).toBe("that, t/d deletion");
    expect(wordAccessibleName(makeWord("work", 0, "w ɝ k", "w ɝ k"), reference)).toBe("work");
  });

  it("treats linking and palatalization as boundary changes, h-dropping not", () => {
    const next = makeWord("about", 1, "ə b aʊ t", "ə b aʊ t");
    expect(crossesBoundary(makeWord("thing", 0, "θ ɪ ŋ", "θ ɪ ŋ", { phenomena: ["linking"] }), next)).toBe(true);
    expect(crossesBoundary(makeWord("him", 0, "h ɪ m", "ɪ m", { phenomena: ["h_dropping"] }), next)).toBe(false);
    expect(crossesBoundary(makeWord("did", 0, "d ɪ d", "d ɪ dʒ", { boundary_link_next: true }), null)).toBe(false);
  });

  it("knows when a word cannot be checked", () => {
    const silent = { ...makeWord("uh", 0, "ʌ", "ʌ"), realized_aligned: [] };
    expect(reliability(silent)).toBe("no-phones");
    expect(reliability(makeWord("go", 0, "ɡ oʊ", "ɡ oʊ", { low_confidence: true }))).toBe("low-confidence");
    expect(reliability(makeWord("go", 0, "ɡ oʊ", "ɡ oʊ"))).toBe("ok");
  });

  it("reads the numeral fields, and an empty canonical from older analyses", () => {
    const nine = { ...makeWord("9.30", 0, "n aɪ n", "n aɪ n"), canonical_text: "nine thirty" };
    expect(spokenForm(nine)).toBe("nine thirty");
    expect(spokenForm(makeWord("nine", 0, "n aɪ n", "n aɪ n"))).toBeNull();
    expect(hasNoCanonical({ ...makeWord("%", 0, "ə", "ə"), no_canonical: true })).toBe(true);
    expect(hasNoCanonical({ ...makeWord("#", 0, "ə", "ə"), canonical_aligned: [] })).toBe(true);
  });
});

describe("shadowing plan", () => {
  it("listens slowly three times, then once at full speed, each followed by a gap", () => {
    const plan = shadowingPlan({ start: 1, end: 2 }, 0.5);
    expect(plan).toHaveLength(8);
    expect(plan.map((p) => `${p.kind}@${p.rate}`)).toEqual([
      "listen@0.5", "repeat@0.5", "listen@0.5", "repeat@0.5", "listen@0.5", "repeat@0.5", "listen@1", "repeat@1",
    ]);
    expect(plan[0].ms).toBe(2000); // 1 s at half speed
    expect(plan[1].ms).toBe(2000 + REPEAT_MARGIN_MS);
    expect(plan[6].ms).toBe(1000);
    expect(describePhase(plan[1])).toBe("Round 1 of 4: your turn — say it now");
    expect(describePhase(plan[6])).toBe("Round 4 of 4: listen at full speed");
  });
});
