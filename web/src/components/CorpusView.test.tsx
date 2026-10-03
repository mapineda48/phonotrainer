import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { metrics, reference, renderWith } from "../test/fixtures";
import { CorpusView, type CorpusFilters } from "./CorpusView";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
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
  { id: "/tmp/out", job_id: "job1", source: "ep1.webm", duration: 42.7, words: 182,
    segments: 27, attraction: true, duplicate_source: true, indexed_at: "2026-07-26T18:00:00+00:00" },
  { id: "/tmp/out_noattr", job_id: "job2", source: "ep1.webm", duration: 42.7, words: 182,
    segments: 27, attraction: false, duplicate_source: true, indexed_at: "2026-07-26T18:01:00+00:00" },
];

const occurrence = {
  analysis_id: "/tmp/out",
  job_id: "job1",
  analysis_source: "ep1.webm",
  analysis_attraction: true,
  next_word: "you",
  segment: 4,
  word_idx: 2,
  word: "better",
  start: 8.94,
  end: 9.12,
  dict_ipa: "bɛtɚ",
  canonical_ipa: "bɛɾɚ",
  realized_ipa: "bɛɾɚ",
  realized_raw_ipa: "",
  diff_cost: 0,
  attracted_count: 0,
  low_confidence: false,
  oov: false,
  lexical_form: null,
  phenomena: ["flapping"],
  too_short: false,
};

/** The view receives its filters from above: here we hold them like App does. */
function Host({ onOpen = vi.fn() }: { onOpen?: (id: string, s: unknown) => void }) {
  const [filters, setFilters] = useState<CorpusFilters>({ phenomenon: null, word: "" });
  return <CorpusView onOpen={onOpen} filters={filters} onFilters={setFilters} />;
}

describe("CorpusView", () => {
  beforeEach(() => {
    vi.mocked(api.corpusMetrics).mockRejectedValue(new Error("not there"));
    vi.mocked(api.corpusStats).mockResolvedValue(stats);
    vi.mocked(api.corpusAnalyses).mockResolvedValue({ items: analyses });
    vi.mocked(api.corpusOccurrences).mockResolvedValue({
      phenomenon: null,
      word: null,
      total: 1,
      items: [occurrence],
    });
    vi.mocked(api.corpusVariants).mockResolvedValue({
      word: "to",
      variants: [
        { realized_ipa: "tə", count: 9, analyses: 3, dict_ipa: "tu" },
        { realized_ipa: "tʊ", count: 1, analyses: 1, dict_ipa: "tu" },
      ],
    });
  });

  it("summarizes the whole corpus, not a single analysis", async () => {
    renderWith(<Host />);

    expect(await screen.findByText(/3 analyses of 1 recording/)).toBeInTheDocument();
    expect(screen.getByText(/540 words/)).toBeInTheDocument();
    const linking = screen.getByRole("button", { name: /linking/ });
    expect(within(linking).getByText(/90/)).toBeInTheDocument();
    expect(within(linking).getByText(/3 analyses/)).toBeInTheDocument();
  });

  it("warns that some material is counted twice and lets you see it", async () => {
    renderWith(<Host />);
    await screen.findByText(/3 analyses of 1 recording/);

    expect(screen.getByText(/each count it more than once/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /see what it is made of/ }));
    const table = screen.getByTestId("corpus-analyses");
    expect(within(table).getAllByText("ep1.webm")).toHaveLength(2);
    expect(within(table).getByText(/no attraction/)).toBeInTheDocument();
    expect(within(table).getAllByText(/repeated material/)).toHaveLength(2);
  });

  it("filtering by phenomenon keeps the searched word and the header stays honest", async () => {
    renderWith(<Host />);
    await screen.findByText(/3 analyses of 1 recording/);

    await userEvent.type(screen.getByRole("searchbox"), "to");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() =>
      expect(api.corpusOccurrences).toHaveBeenLastCalledWith(
        expect.objectContaining({ word: "to" }),
      ),
    );

    await userEvent.click(screen.getByRole("button", { name: /flapping/ }));
    await waitFor(() =>
      expect(api.corpusOccurrences).toHaveBeenLastCalledWith(
        expect.objectContaining({ phenomenon: "flapping", word: "to" }),
      ),
    );
  });

  it("answers how a word has been pronounced, and each form filters the list", async () => {
    renderWith(<Host />);
    await screen.findByText(/3 analyses of 1 recording/);

    await userEvent.type(screen.getByRole("searchbox"), "to");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText("[tə]")).toBeInTheDocument();
    expect(screen.getByText("9 times")).toBeInTheDocument();
    expect(screen.getByText("1 time")).toBeInTheDocument();     // singular, not "1 times"

    await userEvent.click(screen.getByText("[tə]"));
    expect(screen.getByText(/pronounced \[tə\]/)).toBeInTheDocument();
  });

  it("each occurrence opens its analysis on that word, by keyboard too", async () => {
    const onOpen = vi.fn();
    renderWith(<Host onOpen={onOpen} />);

    const row = await screen.findByRole("button", { name: /open “better”/ });
    await userEvent.click(row);
    expect(onOpen).toHaveBeenCalledWith("job1", { segment: 4, index: 2 });

    onOpen.mockClear();
    row.focus();
    await userEvent.keyboard("{Enter}");
    expect(onOpen).toHaveBeenCalledWith("job1", { segment: 4, index: 2 });
  });

  it("says so when the corpus is empty instead of showing empty tables", async () => {
    vi.mocked(api.corpusStats).mockResolvedValue({
      ...stats, analyses: 0, sources: 0, materials: 0, words: 0, phenomena: [], top_words: [],
    });
    renderWith(<Host />);

    expect(await screen.findByText(/The corpus is empty/)).toBeInTheDocument();
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
        phenomenon: "flapping", word: null, total: 1,
        items: [{ ...occurrence, word: "water" }],
      });

    renderWith(<Host />);
    await screen.findByText(/3 analyses of 1 recording/);
    await userEvent.click(screen.getByRole("button", { name: /flapping/ }));
    await screen.findByText("water");

    pending.resolve?.({ phenomenon: null, word: null, total: 99,
                        items: [occurrence] } as never);

    await waitFor(() => expect(screen.getByText("water")).toBeInTheDocument());
    expect(screen.queryByText(/99 occurrences/)).not.toBeInTheDocument();
  });
});

describe("CorpusView — how reduced the corpus is", () => {
  beforeEach(() => {
    vi.mocked(api.corpusStats).mockResolvedValue(stats);
    vi.mocked(api.corpusAnalyses).mockResolvedValue({ items: analyses });
    vi.mocked(api.corpusOccurrences).mockResolvedValue({
      phenomenon: null, word: null, total: 0, items: [],
    });
  });

  it("one column per engine, never added up, next to the report", async () => {
    const espeak = { ...metrics, engine: "espeak", deviate: { count: 42, of: 100, pct: 42 } };
    vi.mocked(api.corpusMetrics).mockResolvedValue({
      analyses: 3, materials: 2, measured: 3, missing: 1,
      by_engine: { timit61: metrics, espeak },
      reference: reference.metrics_reference!,
    });
    renderWith(<Host />);

    const card = await screen.findByTestId("corpus-metrics");
    const headers = within(card).getAllByRole("columnheader").map((th) => th.textContent);
    expect(headers).toEqual(["measure", "timit61 (175 words)", "espeak (175 words)", "report"]);
    const deviate = within(card).getByRole("row", { name: /words that differ/ });
    expect(deviate).toHaveTextContent(/72\.0 %.*42\.0 %.*> 60 % \(Johnson 2004\)/);
    expect(card).toHaveTextContent(/1 could not be measured/);
  });

  it("the rest of the corpus still shows when the metrics are unavailable", async () => {
    vi.mocked(api.corpusMetrics).mockRejectedValue(new Error("503"));
    renderWith(<Host />);

    expect(await screen.findByText(/3 analyses of 1 recording/)).toBeInTheDocument();
    expect(screen.queryByTestId("corpus-metrics")).toBeNull();
  });
});
