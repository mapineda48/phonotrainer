import { afterEach, describe, expect, it, vi } from "vitest";

import { fullReference } from "../../test/render";
import type { Occurrence, Segment, Word } from "../../types";
import {
  accuracy,
  buildPool,
  changeOptions,
  countOptions,
  drawEntry,
  formOptions,
  loadStats,
  phraseAround,
  quizPhenomena,
  recordAnswer,
  saveStats,
  seededRng,
  STATS_KEY,
  type PoolEntry,
} from "./quiz";

function occurrence(over: Partial<Occurrence> = {}): Occurrence & { job_id: string } {
  return {
    analysis_id: "a1",
    job_id: "job-1",
    analysis_source: "clip.webm",
    analysis_attraction: false,
    next_word: null,
    segment: 0,
    word_idx: 2,
    word: "got",
    start: 1,
    end: 1.2,
    dict_ipa: "ɡˈɑt",
    canonical_ipa: "ɡɑt",
    realized_ipa: "ɡɑɾ",
    realized_raw_ipa: null,
    diff_cost: 0.3,
    attracted_count: 0,
    low_confidence: false,
    oov: false,
    lexical_form: null,
    phenomena: ["flapping"],
    too_short: false,
    ...over,
  } as Occurrence & { job_id: string };
}

const entry = (phenomenon: string, over: Partial<Occurrence> = {}): PoolEntry => ({
  phenomenon,
  occurrence: occurrence({ phenomena: [phenomenon], ...over }),
});

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("seededRng", () => {
  it("repeats exactly for the same seed", () => {
    const a = seededRng(42);
    const b = seededRng(42);
    const first = [a(), a(), a()];
    expect([b(), b(), b()]).toEqual(first);
    expect(first.every((value) => value >= 0 && value < 1)).toBe(true);
  });
});

describe("buildPool", () => {
  it("keeps only occurrences that can be played and trusted", () => {
    const pool = buildPool({
      flapping: [
        occurrence(),
        occurrence({ job_id: null }),
        occurrence({ low_confidence: true }),
        occurrence({ too_short: true }),
        occurrence({ realized_ipa: null }),
      ],
    });
    expect(pool).toHaveLength(1);
    expect(pool[0].phenomenon).toBe("flapping");
  });

  it("leaves out the elided word, which has nothing to hear", () => {
    expect(quizPhenomena(fullReference)).not.toContain("word_elision");
    expect(quizPhenomena(fullReference)).toContain("flapping");
  });
});

describe("changeOptions", () => {
  it("offers the right change and three it does not have, from other families", () => {
    const target = entry("flapping", { phenomena: ["flapping", "linking"] });
    const options = changeOptions(target, fullReference, seededRng(1));
    const ids = options.map((option) => option.id);
    expect(ids).toHaveLength(4);
    expect(ids).toContain("flapping");
    expect(ids).not.toContain("linking");
    const families = ids.map((id) => fullReference.family_of[id] ?? "lexical");
    expect(new Set(families).size).toBe(4);
  });
});

describe("formOptions", () => {
  it("offers what was said, the dictionary form without stress, and other ways the word was said", () => {
    const options = formOptions(
      occurrence(),
      [
        { realized_ipa: "ɡɑʔ", count: 3, analyses: 1, dict_ipa: "ɡˈɑt" },
        { realized_ipa: "ɡɑɾ", count: 9, analyses: 2, dict_ipa: "ɡˈɑt" }, // same as said
        { realized_ipa: "ɡɑt̚", count: 2, analyses: 1, dict_ipa: "ɡˈɑt" },
        { realized_ipa: "ɡɑ", count: 1, analyses: 1, dict_ipa: "ɡˈɑt" },
      ],
      seededRng(3),
    );
    expect(options).not.toBeNull();
    const byId = Object.fromEntries(options!.map((option) => [option.id, option.ipa]));
    expect(byId.said).toBe("ɡɑɾ");
    expect(byId.dictionary).toBe("ɡɑt");
    expect(options).toHaveLength(4);
    expect(new Set(options!.map((option) => option.ipa)).size).toBe(4);
  });

  it("gives up when there is nothing to choose between", () => {
    expect(formOptions(occurrence({ dict_ipa: "ɡˈɑɾ" }), [], seededRng(1))).toBeNull();
  });
});

describe("count items", () => {
  const word = (text: string, start: number): Word =>
    ({ word: text, start, end: start + 0.2, phenomena: [], low_confidence: false }) as unknown as Word;

  it("uses the intonation unit around the word when it is short", () => {
    const segment = {
      words: ["I", "got", "to", "go", "now", "OK", "then", "bye"].map((text, i) => word(text, i)),
      intonation_units: [
        { start: 0, end: 4.2, words: [0, 4] },
        { start: 5, end: 7.2, words: [5, 7] },
      ],
    } as unknown as Segment;
    expect(phraseAround(segment, 1)?.map((w) => w.word)).toEqual(["I", "got", "to", "go", "now"]);
  });

  it("falls back to a window of words in a long segment", () => {
    const segment = {
      words: Array.from({ length: 20 }, (_, i) => word(`w${i}`, i)),
    } as unknown as Segment;
    const phrase = phraseAround(segment, 10)!;
    expect(phrase).toHaveLength(6);
    expect(phrase.map((w) => w.word)).toContain("w10");
  });

  it("offers four plausible counts including the right one", () => {
    const options = countOptions(5, seededRng(9));
    expect(options.map((option) => Number(option.id)).sort()).toEqual([4, 5, 6, 7]);
    expect(countOptions(1, seededRng(9)).every((option) => Number(option.id) >= 1)).toBe(true);
  });
});

describe("drawEntry", () => {
  const pool = [entry("flapping"), entry("linking", { word_idx: 5 }), entry("h_dropping", { word_idx: 6 })];

  it("respects a focus", () => {
    for (let seed = 0; seed < 20; seed += 1) {
      expect(drawEntry(pool, seededRng(seed), { focus: "linking" })?.phenomenon).toBe("linking");
    }
    expect(drawEntry(pool, seededRng(1), { focus: "glottalization" })).toBeNull();
  });

  it("draws weak spots more often", () => {
    const stats = {
      flapping: { seen: 20, correct: 20 },
      linking: { seen: 20, correct: 2 },
      h_dropping: { seen: 20, correct: 20 },
    };
    const rng = seededRng(7);
    const counts: Record<string, number> = {};
    for (let i = 0; i < 600; i += 1) {
      const drawn = drawEntry(pool, rng, { stats, weakSpots: true })!;
      counts[drawn.phenomenon] = (counts[drawn.phenomenon] ?? 0) + 1;
    }
    expect(counts.linking).toBeGreaterThan(counts.flapping * 2);
  });
});

describe("stats", () => {
  it("records answers and survives a round trip through storage", () => {
    let stats = recordAnswer({}, "flapping", true);
    stats = recordAnswer(stats, "flapping", false);
    expect(accuracy(stats.flapping)).toBe(0.5);
    saveStats(stats);
    expect(loadStats()).toEqual(stats);
  });

  it("ignores broken data and blocked storage", () => {
    localStorage.setItem(STATS_KEY, "{not json");
    expect(loadStats()).toEqual({});
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(loadStats()).toEqual({});
    expect(() => saveStats({ flapping: { seen: 1, correct: 1 } })).not.toThrow();
  });
});
