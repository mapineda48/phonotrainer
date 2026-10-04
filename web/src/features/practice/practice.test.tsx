import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../api";
import { expectNoAxeViolations } from "../../test/axe";
import { fullReference, renderPage } from "../../test/render";
import type { CorpusStats, Occurrence } from "../../types";
import { PracticePage } from "./index";
import { STATS_KEY } from "./quiz";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      corpusStats: vi.fn(),
      corpusOccurrences: vi.fn(),
      corpusVariants: vi.fn(),
      analysis: vi.fn(),
    },
  };
});

const stats = (phenomena: CorpusStats["phenomena"]): CorpusStats => ({
  analyses: 1,
  sources: 1,
  materials: 1,
  words: 100,
  segments: 10,
  duration: 60,
  phenomena,
  top_words: [],
});

function occurrence(over: Partial<Occurrence>): Occurrence {
  return {
    analysis_id: "a1",
    job_id: "job-1",
    analysis_source: "clip.webm",
    analysis_attraction: false,
    next_word: null,
    segment: 0,
    word_idx: 1,
    word: "got",
    start: 1,
    end: 1.25,
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
  };
}

beforeEach(() => {
  vi.mocked(api.corpusStats).mockResolvedValue(
    stats([
      { phenomenon: "flapping", count: 4, analyses: 1 },
      { phenomenon: "h_dropping", count: 2, analyses: 1 },
    ]),
  );
  vi.mocked(api.corpusOccurrences).mockImplementation(async ({ phenomenon }) => {
    const items =
      phenomenon === "flapping"
        ? [occurrence({ word_idx: 1 }), occurrence({ word: "lot", word_idx: 4, dict_ipa: "lˈɑt", realized_ipa: "lɑɾ" })]
        : [
            occurrence({
              word: "him",
              word_idx: 6,
              dict_ipa: "hˈɪm",
              realized_ipa: "ɪm",
              phenomena: ["h_dropping"],
            }),
          ];
    return { phenomenon: phenomenon ?? null, word: null, total: items.length, items };
  });
  vi.mocked(api.corpusVariants).mockImplementation(async (word) => ({
    word,
    variants: [{ realized_ipa: word === "got" ? "ɡɑʔ" : "hɪm", count: 2, analyses: 1, dict_ipa: null }],
  }));
});

describe("PracticePage", () => {
  it("explains the session before starting and passes axe", async () => {
    const { container } = renderPage(<PracticePage />, { path: "/practice", reference: fullReference });
    expect(await screen.findByRole("button", { name: /start practicing/i })).toBeInTheDocument();
    expect(screen.getByText("Which pronunciation?")).toBeInTheDocument();
    expect(screen.getByText("Which change?")).toBeInTheDocument();
    expect(screen.getByText("How many words?")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: /focus on my weak spots/i })).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("asks, gives feedback, explains and keeps the result", async () => {
    vi.spyOn(Math, "random").mockReturnValue(0); // seed 0: the session opens with "which pronunciation?"
    const user = userEvent.setup();
    const { container } = renderPage(<PracticePage />, { path: "/practice", reference: fullReference });
    await user.click(await screen.findByRole("button", { name: /start practicing/i }));

    const prompt = await screen.findByRole("heading", { name: /which pronunciation did you hear/i });
    const group = screen.getByRole("group", { name: prompt.textContent ?? "" });
    expect(within(group).getAllByRole("button").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByRole("button", { name: /^play$/i })).toBeInTheDocument();

    const said = within(group).getByRole("button", { name: /\[(ɡɑɾ|lɑɾ|ɪm)\]/ });
    await user.click(said);
    expect(screen.getByRole("status")).toHaveTextContent("Correct!");
    expect(within(group).getByText("Correct answer")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /learn about/i })).toHaveAttribute("href", expect.stringMatching(/^\/learn\//));
    const next = screen.getByRole("button", { name: /next question/i });
    expect(next).toHaveFocus();
    const saved = JSON.parse(localStorage.getItem(STATS_KEY) ?? "{}");
    expect(Object.values(saved)).toEqual([{ seen: 1, correct: 1 }]);
    await expectNoAxeViolations(container);

    // Enter on the focused button moves on; the next question is "which change?"
    await user.keyboard("{Enter}");
    const nextPrompt = await screen.findByRole("heading", { name: /which change did you hear/i });
    expect(nextPrompt).toHaveFocus(); // the learner lands on the new question
    expect(screen.getByText(/1 right/)).toBeInTheDocument();
  });

  it("answers with the number keys and says what the right answer was", async () => {
    vi.spyOn(Math, "random").mockReturnValue(0.34); // seed % 3 = 1: opens with "which change?"
    const user = userEvent.setup();
    renderPage(<PracticePage />, { path: "/practice", reference: fullReference });
    await user.click(await screen.findByRole("button", { name: /start practicing/i }));
    const prompt = await screen.findByRole("heading", { name: /which (change|pronunciation)/i });
    const group = screen.getByRole("group", { name: prompt.textContent ?? "" });
    const buttons = within(group).getAllByRole("button");
    const wrongIndex = buttons.findIndex((button) => !/flapping|h-dropping|\[(ɡɑɾ|lɑɾ|ɪm)\]/i.test(button.textContent ?? ""));
    await user.keyboard(String(wrongIndex + 1));
    expect(screen.getByRole("status")).toHaveTextContent(/Not quite — the answer was/);
    expect(within(group).getByText("Your answer")).toBeInTheDocument();
    expect(within(group).getByText("Correct answer")).toBeInTheDocument();
  });

  it("asks how many words were in the phrase and shows them, marking the changed ones", async () => {
    vi.spyOn(Math, "random").mockReturnValue(2e-9); // seed 2: opens with "how many words?"
    const words = ["I", "got", "to", "go", "now"].map((word, i) => ({
      word,
      start: i * 0.3,
      end: i * 0.3 + 0.25,
      phenomena: word === "got" || word === "to" ? ["vowel_reduction"] : [],
      low_confidence: false,
    }));
    vi.mocked(api.analysis).mockResolvedValue({
      meta: {} as never,
      summary: { phenomena_counts: {} },
      segments: [{ start: 0, end: 1.5, text: "I got to go now", words, intonation_units: [{ start: 0, end: 1.5, words: [0, 4] }] }],
    } as never);
    const user = userEvent.setup();
    renderPage(<PracticePage />, { path: "/practice", reference: fullReference });
    await user.click(await screen.findByRole("button", { name: /start practicing/i }));
    const prompt = await screen.findByRole("heading", { name: /how many words did you hear/i });
    const group = screen.getByRole("group", { name: prompt.textContent ?? "" });
    await user.click(within(group).getByRole("button", { name: /5 words/ }));
    expect(screen.getByRole("status")).toHaveTextContent("Correct!");
    const explanation = screen.getByRole("region", { name: "Explanation" });
    expect(within(explanation).getAllByRole("listitem")).toHaveLength(5);
    expect(within(explanation).getAllByText("(changed)")).toHaveLength(2);
  });

  it("shows a focus from the URL and can drop it", async () => {
    const user = userEvent.setup();
    const { history } = renderPage(<PracticePage />, { path: "/practice?focus=flapping", reference: fullReference });
    await screen.findByRole("button", { name: /start practicing/i });
    expect(screen.getByText("Only:")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /practice everything instead/i }));
    expect(history.at(-1)).toBe("/practice");
  });

  it("teaches what to do when there is nothing to practice", async () => {
    vi.mocked(api.corpusStats).mockResolvedValue(stats([]));
    renderPage(<PracticePage />, { path: "/practice", reference: fullReference });
    expect(await screen.findByText("Nothing to practice yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /analyze a clip/i })).toHaveAttribute("href", "/new");
  });
});
