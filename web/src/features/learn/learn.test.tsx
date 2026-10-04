import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../api";
import { lookupPhone, PHONE_TABLE } from "../../articulation/phones";
import { ipaTerm, lookupGlossary } from "../../didactic/glossary";
import { expectNoAxeViolations } from "../../test/axe";
import { phoneScreen } from "../../test/media";
import { fullReference, renderPage } from "../../test/render";
import type { CorpusStats, Occurrence } from "../../types";
import { LESSONS } from "./content";
import { containsSymbol, pickExamples, tokenizeIpa } from "./corpus";
import { IpaChartPage, LearnHomePage, PhenomenonPage } from "./index";
import { placedSymbols, unplacedSymbols } from "./ipaLayout";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: { ...actual.api, corpusStats: vi.fn(), corpusOccurrences: vi.fn() },
  };
});

const stats: CorpusStats = {
  analyses: 2,
  sources: 2,
  materials: 2,
  words: 900,
  segments: 80,
  duration: 300,
  phenomena: [
    { phenomenon: "flapping", count: 23, analyses: 2 },
    { phenomenon: "linking", count: 140, analyses: 2 },
  ],
  top_words: [],
};

function occurrence(over: Partial<Occurrence>): Occurrence {
  return {
    analysis_id: "a1",
    job_id: "job-1",
    analysis_source: "clip.webm",
    analysis_attraction: false,
    next_word: "it",
    segment: 0,
    word_idx: 0,
    word: "get",
    start: 1.0,
    end: 1.3,
    dict_ipa: "ɡˈɛt",
    canonical_ipa: "ɡɛt",
    realized_ipa: "ɡɛɾ",
    realized_raw_ipa: null,
    diff_cost: 0.2,
    attracted_count: 0,
    low_confidence: false,
    oov: false,
    lexical_form: null,
    phenomena: ["flapping"],
    too_short: false,
    ...over,
  };
}

const flaps = [
  occurrence({ word: "get", word_idx: 3, diff_cost: 0.3 }),
  occurrence({ word: "water", word_idx: 7, dict_ipa: "wˈɔtɚ", realized_ipa: "wɔɾɚ", diff_cost: 0.1 }),
  occurrence({ word: "Get", word_idx: 9, diff_cost: 0.05 }), // same word: shown once
  occurrence({ word: "lot", word_idx: 11, low_confidence: true }), // not trustworthy
  occurrence({ word: "it", word_idx: 12, job_id: null }), // cannot be played
];

beforeEach(() => {
  vi.mocked(api.corpusStats).mockResolvedValue(stats);
  vi.mocked(api.corpusOccurrences).mockResolvedValue({ phenomenon: null, word: null, total: flaps.length, items: flaps });
});

describe("Learn content", () => {
  it("has a lesson for every phenomenon the backend can label", () => {
    expect(Object.keys(LESSONS).sort()).toEqual(Object.keys(fullReference.labels).sort());
  });

  it("only demonstrates sounds the mouth can draw", () => {
    for (const [name, lesson] of Object.entries(LESSONS)) {
      if (!lesson.demo) continue;
      for (const symbol of [...lesson.demo.dictionary, ...lesson.demo.said]) {
        expect(lookupPhone(symbol), `${name}: ${symbol}`).not.toBeNull();
      }
      expect(lesson.demo.dictionary, name).toContain(lesson.demo.from);
      if (lesson.demo.to !== null) expect(lesson.demo.said, name).toContain(lesson.demo.to);
    }
  });

  it("explains every symbol of the inventory, including the length-marked spellings", () => {
    for (const phone of PHONE_TABLE) expect(lookupGlossary(ipaTerm(phone.symbol)), phone.symbol).not.toBeNull();
    expect(lookupGlossary(ipaTerm("iː"))?.title).toBe(lookupGlossary(ipaTerm("i"))?.title);
    expect(lookupGlossary(ipaTerm("ɾ"))?.body).toMatch(/pero/);
  });

  it("places every symbol of the inventory in the IPA chart exactly once", () => {
    expect(unplacedSymbols()).toEqual([]);
    const placed = placedSymbols();
    expect(new Set(placed).size).toBe(placed.length);
  });
});

describe("corpus helpers", () => {
  const multi = fullReference.ipa_tokens;

  it("splits IPA into inventory symbols, keeping diacritics and dropping stress", () => {
    expect(tokenizeIpa("ðæt̚", multi)).toEqual(["ð", "æ", "t̚"]);
    expect(tokenizeIpa("ˈbʌʔn̩", multi)).toEqual(["b", "ʌ", "ʔ", "n̩"]);
    expect(tokenizeIpa("ɡɑtʃə", multi)).toEqual(["ɡ", "ɑ", "tʃ", "ə"]);
  });

  it("does not mistake t̚ or tʃ for a plain t", () => {
    expect(containsSymbol("ðæt̚", "t", multi)).toBe(false);
    expect(containsSymbol("ðæt̚", "t̚", multi)).toBe(true);
    expect(containsSymbol("ɡɑtʃə", "t", multi)).toBe(false);
    expect(containsSymbol("biːt", "i", multi)).toBe(true);
  });

  it("picks clean, playable examples, one per word, clearest first", () => {
    expect(pickExamples(flaps).map((o) => o.word)).toEqual(["Get", "water"]);
  });
});

describe("LearnHomePage", () => {
  it("groups the changes by family, with the report's advice and the counts from your clips", async () => {
    const { container } = renderPage(<LearnHomePage />, { path: "/learn", reference: fullReference });
    const td = screen.getByRole("heading", { name: /t\/d processes/i, level: 2 });
    const section = td.closest("section") as HTMLElement;
    const flapping = within(section).getByRole("link", { name: /flapping/i });
    expect(flapping).toHaveAttribute("href", "/learn/flapping");
    expect(await screen.findByText("23 in your clips")).toBeInTheDocument();
    expect(screen.getAllByText("Safe to produce").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Recognize only").length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: /whole-word changes/i })).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("still teaches when the counts cannot be loaded", async () => {
    vi.mocked(api.corpusStats).mockRejectedValue(new Error("offline"));
    renderPage(<LearnHomePage />, { path: "/learn", reference: fullReference });
    expect(await screen.findByText(/could not be loaded/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /flapping/i })).toBeInTheDocument();
  });
});

describe("PhenomenonPage", () => {
  it("explains the change, the advice and its why, with examples from your clips", async () => {
    const { container } = renderPage(<PhenomenonPage name="flapping" />, {
      path: "/learn/flapping",
      reference: fullReference,
    });
    expect(screen.getByRole("heading", { level: 1, name: "Flapping" })).toBeInTheDocument();
    expect(screen.getByText(/single r of Spanish “pero”/)).toBeInTheDocument();
    expect(screen.getByText(fullReference.practice!.flapping.why)).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /mouth saying “water”/i })).toBeInTheDocument();

    const list = await screen.findByRole("list", { name: /examples of flapping/i });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByRole("button", { name: /play “get”/i })).toBeInTheDocument();
    expect(within(items[0]).getByRole("link", { name: /open in its lesson/i })).toHaveAttribute(
      "href",
      "/analysis/job-1/w/0/9?from=learn",
    );
    expect(screen.getByRole("link", { name: /practice this/i })).toHaveAttribute("href", "/practice?focus=flapping");
    await expectNoAxeViolations(container);
  });

  it("switches the mouth between the dictionary form and what is said", async () => {
    const user = userEvent.setup();
    renderPage(<PhenomenonPage name="flapping" />, { path: "/learn/flapping", reference: fullReference });
    expect(screen.getByRole("button", { name: /hold \[ɾ\].*the sound that changes/i })).toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "Dictionary form" }));
    expect(screen.getByRole("button", { name: /hold \[t\].*the sound that changes/i })).toBeInTheDocument();
  });

  it("says so when there are no examples yet", async () => {
    vi.mocked(api.corpusOccurrences).mockResolvedValue({ phenomenon: "flapping", word: null, total: 0, items: [] });
    renderPage(<PhenomenonPage name="flapping" />, { path: "/learn/flapping", reference: fullReference });
    expect(await screen.findByText("No examples in your clips yet")).toBeInTheDocument();
  });

  it("handles a name that is not a phenomenon", () => {
    renderPage(<PhenomenonPage name="nonsense" />, { path: "/learn/nonsense", reference: fullReference });
    expect(screen.getByText("There is no lesson with that name")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /see every change/i })).toHaveAttribute("href", "/learn");
  });
});

describe("IpaChartPage", () => {
  it("lists every symbol and shows the mouth, the explanation and your words for the one selected", async () => {
    const user = userEvent.setup();
    const { container, history } = renderPage(<IpaChartPage />, { path: "/learn/ipa", reference: fullReference });
    for (const phone of PHONE_TABLE) {
      expect(screen.getByRole("button", { name: new RegExp(`^\\[${escape(phone.symbol)}\\]`) })).toBeInTheDocument();
    }
    expect(screen.getByText("Choose a symbol")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^\[ɾ\] alveolar tap/ }));
    expect(history.at(-1)).toBe("/learn/ipa?symbol=%C9%BE");
    expect(screen.getByRole("button", { name: /^\[ɾ\] alveolar tap/ })).toHaveAttribute("aria-pressed", "true");
    const panel = screen.getByRole("complementary", { name: "Selected symbol" });
    expect(within(panel).getByRole("heading", { name: "Alveolar tap" })).toBeInTheDocument();
    expect(within(panel).getByRole("link", { name: /sound of flapping/i })).toHaveAttribute("href", "/learn/flapping");
    const words = await within(panel).findByRole("list", { name: /words from your clips with \[ɾ\]/i });
    expect(within(words).getAllByRole("listitem")).toHaveLength(2);
    await expectNoAxeViolations(container);
  });

  it("opens on the symbol in the URL", () => {
    renderPage(<IpaChartPage />, { path: `/learn/ipa?symbol=${encodeURIComponent("t̚")}`, reference: fullReference });
    expect(screen.getByRole("heading", { name: /unreleased voiceless alveolar stop/i })).toBeInTheDocument();
  });

  it("on a narrow screen shows the selected symbol in a sheet over the chart, not out of sight below it", async () => {
    phoneScreen();
    const user = userEvent.setup();
    const { container, history } = renderPage(<IpaChartPage />, { path: "/learn/ipa", reference: fullReference });
    expect(screen.queryByRole("complementary", { name: "Selected symbol" })).toBeNull();

    const tap = screen.getByRole("button", { name: /^\[ɾ\] alveolar tap/ });
    await user.click(tap);
    const sheet = await screen.findByRole("dialog", { name: /\[ɾ\] alveolar tap/i });
    expect(within(sheet).getByRole("heading", { name: "Alveolar tap" })).toBeInTheDocument();
    expect(within(sheet).getByRole("link", { name: /sound of flapping/i })).toHaveAttribute("href", "/learn/flapping");
    await expectNoAxeViolations(container);

    // Esc closes it, clears the symbol from the URL and goes back to the symbol
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(history.at(-1)).toBe("/learn/ipa");
    await waitFor(() => expect(tap).toHaveFocus());
  });
});

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

