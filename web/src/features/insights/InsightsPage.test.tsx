import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../api";
import { expectNoAxeViolations } from "../../test/axe";
import { phoneScreen } from "../../test/media";
import { metrics } from "../../test/fixtures";
import { fullReference, renderPage } from "../../test/render";
import type { CorpusMetrics, Occurrence } from "../../types";
import { InsightsPage } from ".";
import { takeaways } from "./ReductionSection";

vi.mock("../../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../../api")>();
  return {
    ...original,
    api: {
      ...original.api,
      corpusStats: vi.fn(),
      corpusAnalyses: vi.fn(),
      corpusOccurrences: vi.fn(),
      corpusVariants: vi.fn(),
      corpusMetrics: vi.fn(),
    },
  };
});

const stats = {
  analyses: 3,
  sources: 2,
  materials: 1,
  words: 540,
  segments: 81,
  duration: 128,
  phenomena: [
    { phenomenon: "linking", count: 90, analyses: 3 },
    { phenomenon: "t_deletion", count: 51, analyses: 3 },
    { phenomenon: "flapping", count: 18, analyses: 2 },
  ],
  top_words: [{ word: "to", count: 12 }],
};

const analyses = [
  { id: "/tmp/out", job_id: "job1", source: "ep1.webm", duration: 42.7, words: 182, segments: 27,
    attraction: false, duplicate_source: true, indexed_at: "2026-07-26T18:00:00+00:00",
    metrics: { ...metrics, engine: "timit61" } },
  { id: "/tmp/out_esp", job_id: "job2", source: "ep1.webm", duration: 42.7, words: 182, segments: 27,
    attraction: false, duplicate_source: true, indexed_at: "2026-07-26T18:01:00+00:00",
    metrics: { ...metrics, engine: "espeak" } },
];

const occurrence: Occurrence = {
  analysis_id: "/tmp/out",
  job_id: "job1",
  analysis_source: "ep1.webm",
  analysis_attraction: false,
  next_word: "you",
  segment: 4,
  word_idx: 2,
  word: "better",
  start: 8.94,
  end: 9.12,
  dict_ipa: "bɛtɚ",
  canonical_ipa: "bɛtɚ",
  realized_ipa: "bɛɾɚ",
  realized_raw_ipa: "",
  diff_cost: 0.3,
  attracted_count: 0,
  low_confidence: false,
  oov: false,
  lexical_form: null,
  phenomena: ["flapping"],
  too_short: false,
};

const recognizeOnly: Occurrence = {
  ...occurrence, word_idx: 5, word: "ten", realized_ipa: "tɛm", phenomena: ["place_assimilation"],
};

const corpusMetrics = (overrides: Partial<CorpusMetrics> = {}): CorpusMetrics => ({
  analyses: 3,
  materials: 2,
  measured: 3,
  missing: 0,
  by_engine: { timit61: metrics },
  reference: fullReference.metrics_reference!,
  ...overrides,
});

function setup(path = "/insights") {
  return renderPage(<InsightsPage />, { path, reference: fullReference });
}

beforeEach(() => {
  vi.mocked(api.corpusMetrics).mockRejectedValue(new Error("not there"));
  vi.mocked(api.corpusStats).mockResolvedValue(stats);
  vi.mocked(api.corpusAnalyses).mockResolvedValue({ items: analyses });
  vi.mocked(api.corpusOccurrences).mockResolvedValue({
    phenomenon: null, word: null, total: 1, items: [occurrence],
  });
  vi.mocked(api.corpusVariants).mockResolvedValue({
    word: "to",
    variants: [
      { realized_ipa: "tə", count: 9, analyses: 3, dict_ipa: "tu" },
      { realized_ipa: "tʊ", count: 1, analyses: 1, dict_ipa: "tu" },
    ],
  });
});

describe("InsightsPage — the corpus at a glance", () => {
  it("summarizes the whole corpus, not a single analysis", async () => {
    setup();
    expect(await screen.findByText(/3 analyses of 1 recording/)).toBeInTheDocument();
    expect(screen.getByText(/540 words/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /linking: 90 occurrences in 3 analyses/i })).toBeInTheDocument();
  });

  it("warns that a recording is counted twice and lets you see what the corpus is made of", async () => {
    setup();
    await screen.findByText(/3 analyses of 1 recording/);
    expect(screen.getByText(/counted more than once/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /What the corpus is made of/ }));
    const table = screen.getByRole("table", { name: /Every analysis in the corpus/ });
    expect(within(table).getAllByText("ep1.webm")).toHaveLength(2);
    expect(within(table).getAllByText(/repeated recording/)).toHaveLength(2);
    expect(within(table).getByText("TIMIT-61")).toBeInTheDocument();
    expect(within(table).getByText("espeak")).toBeInTheDocument();
  });

  it("says so when the corpus is empty, and points to a first analysis", async () => {
    vi.mocked(api.corpusStats).mockResolvedValue({
      ...stats, analyses: 0, sources: 0, materials: 0, words: 0, phenomena: [], top_words: [],
    });
    setup();
    expect(await screen.findByText(/Your corpus is empty/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New analysis" })).toHaveAttribute("href", "/new");
  });
});

describe("InsightsPage — filters live in the URL", () => {
  it("a phenomenon bar filters the occurrences, keeps the searched word, and is undone by pressing it again", async () => {
    const { history } = setup("/insights?word=to");
    await screen.findByText(/3 analyses of 1 recording/);
    await waitFor(() =>
      expect(api.corpusOccurrences).toHaveBeenLastCalledWith(expect.objectContaining({ word: "to" })),
    );

    const flapping = screen.getByRole("button", { name: /flapping: 18 occurrences/i });
    await userEvent.click(flapping);
    await waitFor(() =>
      expect(api.corpusOccurrences).toHaveBeenLastCalledWith(
        expect.objectContaining({ phenomenon: "flapping", word: "to" }),
      ),
    );
    expect(history.at(-1)).toBe("/insights?phenomenon=flapping&word=to");
    expect(screen.getByRole("button", { name: /flapping: 18 occurrences/i })).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(screen.getByRole("button", { name: /flapping: 18 occurrences/i }));
    expect(history.at(-1)).toBe("/insights?word=to");
  });

  it("searching a word shows how it was said, and each form narrows the list", async () => {
    const { history } = setup();
    await screen.findByText(/3 analyses of 1 recording/);

    await userEvent.type(screen.getByRole("searchbox", { name: /Word to look up/ }), "to");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    expect(history.at(-1)).toBe("/insights?word=to");

    const forms = await screen.findByRole("region", { name: /How “to” was said/ });
    const schwa = within(forms).getByRole("button", { name: /Said \[tə\]: 9 times, in 3 analyses/ });
    expect(within(forms).getByRole("button", { name: /Said \[tʊ\]: 1 time, in 1 analysis/ })).toBeInTheDocument();

    await userEvent.click(schwa);
    expect(schwa).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText(/pronounced/)).toBeInTheDocument();
    // the only loaded occurrence was said [bɛɾɚ], not [tə]
    expect(await screen.findByText(/Nothing to show for these filters/)).toBeInTheDocument();
  });

  it("rare forms of a word wait behind a button", async () => {
    vi.mocked(api.corpusVariants).mockResolvedValue({
      word: "to",
      variants: Array.from({ length: 15 }, (_, i) => ({
        realized_ipa: `t${"ə".repeat(i + 1)}`, count: 15 - i, analyses: 1, dict_ipa: "tu",
      })),
    });
    setup("/insights?word=to");
    const forms = await screen.findByRole("region", { name: /How “to” was said/ });
    expect(within(forms).getAllByRole("button", { name: /^Said/ })).toHaveLength(12);
    await userEvent.click(within(forms).getByRole("button", { name: "Show 3 rarer forms" }));
    expect(within(forms).getAllByRole("button", { name: /^Said/ })).toHaveLength(15);
  });

  it("the practice filter keeps only the changes the report says are safe to produce", async () => {
    vi.mocked(api.corpusOccurrences).mockResolvedValue({
      phenomenon: null, word: null, total: 2, items: [occurrence, recognizeOnly],
    });
    const { history } = setup("/insights?practice=produce");
    expect(await screen.findByRole("rowheader", { name: /better/ })).toBeInTheDocument();
    expect(screen.queryByRole("rowheader", { name: /^ten/ })).toBeNull();
    expect(screen.getByText(/marked “Safe to produce”/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("radio", { name: /Recognize only/ }));
    expect(history.at(-1)).toBe("/insights?practice=understand");
    expect(await screen.findByRole("rowheader", { name: /^ten/ })).toBeInTheDocument();
    expect(screen.queryByRole("rowheader", { name: /better/ })).toBeNull();
  });

  it("clearing the filters brings back everything", async () => {
    const { history } = setup("/insights?phenomenon=flapping&word=to&practice=produce");
    await screen.findByText(/3 analyses of 1 recording/);
    await userEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(history.at(-1)).toBe("/insights");
  });

  it("a response arriving late does not overwrite the current one", async () => {
    const pending: { resolve?: (value: never) => void } = {};
    vi.mocked(api.corpusOccurrences)
      .mockImplementationOnce(
        () => new Promise((resolve) => {
          pending.resolve = resolve as (value: never) => void;
        }),
      )
      .mockResolvedValue({
        phenomenon: "flapping", word: null, total: 1, items: [{ ...occurrence, word: "water" }],
      });

    setup();
    await screen.findByText(/3 analyses of 1 recording/);
    await userEvent.click(screen.getByRole("button", { name: /flapping: 18 occurrences/i }));
    await screen.findByRole("rowheader", { name: /water/ });

    pending.resolve?.({ phenomenon: null, word: null, total: 99, items: [occurrence] } as never);
    await waitFor(() => expect(screen.getByRole("rowheader", { name: /water/ })).toBeInTheDocument());
    expect(screen.queryByText(/99 occurrences/)).not.toBeInTheDocument();
  });
});

describe("InsightsPage — occurrences", () => {
  it("each occurrence opens its word lesson, by keyboard too, and remembers where you came from", async () => {
    const { history } = setup();
    const open = await screen.findByRole("link", { name: /Open “better” in ep1\.webm at 0:08\.9/ });
    expect(open).toHaveAttribute("href", "/analysis/job1/w/4/2?from=insights");

    open.focus();
    await userEvent.keyboard("{Enter}");
    expect(history.at(-1)).toBe("/analysis/job1/w/4/2?from=insights");
  });

  it("plays the word on the spot", async () => {
    setup();
    expect(await screen.findByRole("button", { name: /Play “better” from ep1\.webm/ })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("is honest about words it cannot compare or open", async () => {
    vi.mocked(api.corpusOccurrences).mockResolvedValue({
      phenomenon: null, word: "9", total: 2,
      items: [
        { ...occurrence, word: "9", dict_ipa: "", canonical_ipa: "", phenomena: [], low_confidence: true },
        { ...occurrence, word_idx: 7, word: "cliword", job_id: null, too_short: true },
      ],
    });
    setup();
    const numeral = (await screen.findByRole("rowheader", { name: "9" })).closest("tr")!;
    expect(within(numeral).getByText("no dictionary form")).toBeInTheDocument();
    expect(within(numeral).getByText(/low confidence/)).toBeInTheDocument();

    const cli = screen.getByRole("rowheader", { name: /cliword/ }).closest("tr")!;
    expect(within(cli).getByText(/Indexed from the command line/)).toBeInTheDocument();
    expect(within(cli).queryByRole("link")).toBeNull();
    expect(within(cli).getByText(/very short/)).toBeInTheDocument();
  });

  it("a long list grows on demand instead of rendering every row at once", async () => {
    const many = Array.from({ length: 60 }, (_, i) => ({ ...occurrence, word_idx: i, word: `w${i}` }));
    vi.mocked(api.corpusOccurrences).mockResolvedValue({ phenomenon: null, word: null, total: 60, items: many });
    setup();
    await screen.findByRole("rowheader", { name: "w0" });
    expect(screen.queryByRole("rowheader", { name: "w50" })).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Show 10 more (10 left)" }));
    expect(screen.getByRole("rowheader", { name: "w59" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Show \d+ more/ })).toBeNull();
  });

  it("with boundary phenomena the following word is part of the example", async () => {
    vi.mocked(api.corpusOccurrences).mockResolvedValue({
      phenomenon: null, word: null, total: 1, items: [{ ...occurrence, phenomena: ["linking"] }],
    });
    setup();
    expect(await screen.findByRole("rowheader", { name: /better‿you/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Play “better you”/ })).toBeInTheDocument();
  });
});

describe("InsightsPage on a narrow screen", () => {
  it("lists the occurrences one per item instead of a six-column table", async () => {
    phoneScreen();
    vi.mocked(api.corpusOccurrences).mockResolvedValue({
      phenomenon: null, word: null, total: 2,
      items: [occurrence, { ...occurrence, word_idx: 7, word: "cliword", job_id: null, phenomena: ["linking"] }],
    });
    const { container } = setup();
    const list = await screen.findByRole("list", { name: /Occurrences, most different/ });
    expect(screen.queryByRole("table", { name: /Occurrences/ })).toBeNull();
    const [better, cli] = within(list).getAllByRole("listitem");

    // the same facts as a table row: the word, dictionary → heard, the changes, where
    expect(within(better).getByText("Dictionary:")).toBeInTheDocument();
    expect(within(better).getByText("Heard:")).toBeInTheDocument();
    expect(within(better).getByText("flapping")).toBeInTheDocument();
    expect(within(better).getByText(/ep1\.webm/)).toBeInTheDocument();
    expect(within(better).getByRole("button", { name: /Play “better” from ep1\.webm/ })).toBeInTheDocument();
    expect(within(better).getByRole("link", { name: /Open “better” in ep1\.webm at 0:08\.9/ })).toHaveAttribute(
      "href",
      "/analysis/job1/w/4/2?from=insights",
    );
    expect(within(cli).getByText(/cliword/)).toBeInTheDocument();
    expect(within(cli).getByText(/‿you/)).toBeInTheDocument();
    expect(within(cli).getByText(/Indexed from the command line/)).toBeInTheDocument();
    expect(within(cli).queryByRole("link")).toBeNull();
    await expectNoAxeViolations(container);
  });
});

describe("InsightsPage — how reduced is what you hear", () => {
  it("each measure stands next to the published figure and its source", async () => {
    vi.mocked(api.corpusMetrics).mockResolvedValue(corpusMetrics());
    setup();

    const meter = await screen.findByRole("meter", { name: /Words that differ from the dictionary, TIMIT-61/ });
    expect(meter).toHaveAttribute("aria-valuetext", expect.stringMatching(/72 %; published figure > 60 % \(Johnson 2004\)/));
    expect(screen.getByText(/126 of 175 words · in line with the report/)).toBeInTheDocument();
  });

  it("one engine at a time, never added up; the table shows them side by side", async () => {
    const espeak = { ...metrics, engine: "espeak", deviate: { count: 42, of: 100, pct: 42 } };
    vi.mocked(api.corpusMetrics).mockResolvedValue(
      corpusMetrics({ by_engine: { timit61: metrics, espeak }, missing: 1 }),
    );
    setup();

    await screen.findByRole("meter", { name: /Words that differ from the dictionary, TIMIT-61/ });
    expect(screen.getByText(/1 could not be measured/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("radio", { name: "espeak" }));
    const meter = screen.getByRole("meter", { name: /Words that differ from the dictionary, espeak/ });
    expect(meter).toHaveAttribute("aria-valuetext", expect.stringMatching(/^42 %/));

    // the first chart on the page is the measures one
    await userEvent.click(screen.getAllByRole("button", { name: "Show as table" })[0]);
    const table = screen.getByRole("table", { name: /never added together/ });
    const headers = within(table).getAllByRole("columnheader").map((th) => th.textContent);
    expect(headers).toEqual(["Measure", "TIMIT-61 (175 words)", "espeak (175 words)", "Published"]);
    const row = within(table).getByRole("row", { name: /Words that differ from the dictionary/ });
    expect(row).toHaveTextContent(/72\.0 %.*42\.0 %.*> 60 % \(Johnson 2004\)/);
  });

  it("explains each measure in plain words", async () => {
    vi.mocked(api.corpusMetrics).mockResolvedValue(corpusMetrics());
    setup();
    const listening = await screen.findByRole("region", { name: "What this means for your listening" });
    expect(listening).toHaveTextContent(/About 7 in 10 words you hear do not sound the way the dictionary writes them/);

    await userEvent.click(screen.getByRole("button", { name: "What's this: Words that differ from the dictionary" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent(/at least one sound was not the one the dictionary gives/);
  });

  it("warns when the pool mixes rule versions", async () => {
    vi.mocked(api.corpusMetrics).mockResolvedValue(
      corpusMetrics({ by_engine: { timit61: { ...metrics, mixed_rules: true } } }),
    );
    setup();
    expect(await screen.findByText(/Mixed rule versions/)).toBeInTheDocument();
  });

  it("the rest of the corpus still shows when the measures are unavailable", async () => {
    setup();
    expect(await screen.findByText(/3 analyses of 1 recording/)).toBeInTheDocument();
    expect(screen.queryByText("How reduced is what you hear")).toBeNull();
  });

  it("turns the measures into plain listening advice, skipping what was not measured", () => {
    const lines = takeaways({ ...metrics, flapping: { count: 0, of: 0, pct: null } });
    expect(lines[0]).toMatch(/About 7 in 10 words/);
    expect(lines.some((line) => /quick tap/.test(line))).toBe(false);
    expect(lines.some((line) => /\[tə\]/.test(line))).toBe(true);
  });
});

describe("InsightsPage — accessibility", () => {
  it("has no serious axe violations", async () => {
    vi.mocked(api.corpusMetrics).mockResolvedValue(corpusMetrics());
    const { container } = setup();
    await screen.findByRole("meter", { name: /Words that differ from the dictionary, TIMIT-61/ });
    await screen.findByRole("rowheader", { name: /better/ });
    await expectNoAxeViolations(container);
  });
});
