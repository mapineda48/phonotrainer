import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act, useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { analysis, fakePlayer, makeWord } from "../../../test/fixtures";
import { expectNoAxeViolations } from "../../../test/axe";
import { renderPage } from "../../../test/render";
import type { Segment, Word } from "../../../types";
import { stepsFor, WordLesson, type StepState } from "./WordLesson";

const segment = analysis.segments[0];
const that = segment.words[1]; // "that" → [ðæ], its /t/ dropped

interface Options {
  next?: Word | null;
  segment?: Segment;
  wordIndex?: number;
  canPlay?: boolean;
  narrow?: boolean;
  steps?: Partial<StepState>;
}

function Harness({ word, options }: { word: Word; options: Options }) {
  const [steps, setSteps] = useState<StepState>({ ...stepsFor("full"), ...options.steps });
  return (
    <WordLesson
      word={word}
      next={options.next ?? null}
      segment={options.segment ?? segment}
      segmentIndex={0}
      wordIndex={options.wordIndex ?? 1}
      position={{ n: 2, total: 5 }}
      onStep={vi.fn()}
      canPlay={options.canPlay ?? true}
      narrow={options.narrow ?? true}
      steps={steps}
      onStepsChange={(id, open) => setSteps((all) => ({ ...all, [id]: open }))}
    />
  );
}

function renderLesson(word: Word = that, options: Options = {}) {
  const player = fakePlayer();
  const tools = renderPage(<Harness word={word} options={options} />, { player });
  return { ...tools, player };
}

const step = (name: RegExp) => screen.getByRole("button", { name });

afterEach(() => {
  vi.useRealTimers();
});

describe("WordLesson — structure", () => {
  it("is five numbered steps, open by default with full guidance (practice starts closed)", () => {
    renderLesson();
    expect(screen.getByRole("heading", { level: 2, name: "“that”" })).toBeInTheDocument();
    for (const [n, title] of [[1, "Listen"], [2, "Compare"], [3, "Why it changes"], [4, "See the mouth"]] as const) {
      expect(step(new RegExp(`Step ${n}:\\s*${title}`))).toHaveAttribute("aria-expanded", "true");
    }
    expect(step(/Step 5:\s*Practice/)).toHaveAttribute("aria-expanded", "false");
  });

  it("names the word's changes right under its title", () => {
    renderLesson();
    expect(within(screen.getByRole("list", { name: "Changes on this word" })).getByText("t/d deletion")).toBeInTheDocument();
  });

  it("has no accessibility violations", async () => {
    const { container } = renderLesson();
    await expectNoAxeViolations(container);
  });
});

describe("WordLesson — 1 Listen", () => {
  it("plays the word and the phrase", async () => {
    const { player } = renderLesson();
    await userEvent.click(screen.getByRole("button", { name: "Play the word" }));
    expect(player.play).toHaveBeenLastCalledWith(expect.objectContaining({ start: expect.closeTo(0.36, 2) }));
    await userEvent.click(screen.getByRole("button", { name: "Play the phrase" }));
    expect(player.play).toHaveBeenLastCalledWith({ start: 0, end: 1.2 });
  });

  it("with a boundary change it offers the word together with the next one", async () => {
    const thing = makeWord("thing", 5.9, "θ ɪ ŋ", "θ ɪ ŋ", { phenomena: ["linking"], boundary_link_next: true });
    const about = makeWord("about", 6.2, "ə b aʊ t", "ə b aʊ t");
    const { player } = renderLesson(thing, { next: about });

    await userEvent.click(screen.getByRole("button", { name: "Play with “about”" }));
    const span = vi.mocked(player.play).mock.calls[0][0]!;
    expect(span.start).toBeCloseTo(5.86, 2);
    expect(span.end).toBeGreaterThan(about.end); // reaches past the following word
  });

  it("without a boundary change there is no “play with” button", () => {
    renderLesson();
    expect(screen.queryByRole("button", { name: /Play with/ })).toBeNull();
  });

  it("disables playback when the analysis has no audio, and says why", () => {
    renderLesson(that, { canPlay: false });
    expect(screen.getByRole("button", { name: "Play the word" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Play the phrase" })).toBeDisabled();
    expect(screen.getByText(/imported without its audio/)).toBeInTheDocument();
  });

  it("speed and loop act on the player", async () => {
    const { player } = renderLesson();
    await userEvent.click(within(screen.getByRole("radiogroup", { name: "Speed" })).getByRole("radio", { name: "0.5×" }));
    expect(player.setRate).toHaveBeenCalledWith(0.5);
    await userEvent.click(screen.getByRole("switch", { name: /Loop/ }));
    expect(player.setLoop).toHaveBeenCalledWith(true);
  });
});

describe("WordLesson — 2 Compare", () => {
  it("shows the dictionary form with the dropped sound struck, and what was said", () => {
    renderLesson();
    const line = screen.getByTestId("dictionary-line");
    expect(line).toHaveTextContent("/ðæt (not heard)/");
    expect(screen.getByText("[ðæ]")).toBeInTheDocument();
  });

  it("compares sound by sound, with a mark that is a symbol and a word", () => {
    renderLesson();
    const table = screen.getByRole("table");
    const rows = within(table).getAllByRole("row");
    const change = rows.find((row) => within(row).queryByRole("rowheader", { name: "Change" }))!;
    expect(change).toHaveTextContent(/=.*=.*–\s*dropped/);
    const heard = rows.find((row) => within(row).queryByRole("rowheader", { name: "Heard" }))!;
    expect(within(heard).getByLabelText("nothing")).toBeInTheDocument();
  });

  it("with the espeak engine, the dictionary line exposes a change the expected form already applies", () => {
    // espeak expects "better" WITH the flap: only the dictionary has the /t/
    const better = makeWord("better", 8.9, "b ɛ ɾ ɚ", "b ɛ ɾ ɚ", { phenomena: ["flapping"], dict_ipa: "bɛtɚ" });
    renderLesson(better, { narrow: false });
    expect(screen.getByTestId("dictionary-line")).toHaveTextContent("/bɛt (not heard)ɚ/");
    expect(screen.getByText(/Expected by the aligner/)).toBeInTheDocument();
    expect(screen.getByText(/already includes some native changes/)).toBeInTheDocument();
  });

  it("with the narrow engine, there is no separate expected line", () => {
    renderLesson();
    expect(screen.queryByText(/Expected by the aligner/)).toBeNull();
    expect(screen.getByRole("rowheader", { name: "Dictionary, in time" })).toBeInTheDocument();
  });

  it("picking a sound plays it with padding and explains it", async () => {
    const { player } = renderLesson();
    const heard = screen.getAllByRole("row").find((row) => within(row).queryByRole("rowheader", { name: "Heard" }))!;
    await userEvent.click(within(heard).getByRole("button", { name: "æ" }));

    const span = vi.mocked(player.play).mock.calls[0][0]!;
    expect(span.start).toBeCloseTo(0.44, 2);
    expect(span.end).toBeCloseTo(0.53, 2);
    expect(screen.getAllByText(/near-open front unrounded vowel/).length).toBeGreaterThan(0);
    expect(screen.getByRole("link", { name: /Open in the IPA chart/ })).toHaveAttribute("href", "/learn/ipa?symbol=%C3%A6");
  });

  it("“See it in the mouth” opens step 4 holding that sound", async () => {
    renderLesson(that, { steps: { see: false } });
    const heard = screen.getAllByRole("row").find((row) => within(row).queryByRole("rowheader", { name: "Heard" }))!;
    await userEvent.click(within(heard).getByRole("button", { name: "æ" }));
    await userEvent.click(screen.getByRole("button", { name: "See it in the mouth" }));
    expect(step(/Step 4:\s*See the mouth/)).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("img", { name: /æ\], near-open front unrounded vowel/ })).toBeInTheDocument();
  });

  it("with a boundary change, both words are compared and the gap is measured", () => {
    const thing = makeWord("thing", 5.9, "θ ɪ ŋ", "θ ɪ ŋ", { phenomena: ["linking"], boundary_link_next: true });
    const about = makeWord("about", 6.03, "ə b aʊ t", "ə b aʊ t");
    renderLesson(thing, { next: about });
    expect(screen.getByRole("columnheader", { name: "about" })).toBeInTheDocument();
    expect(screen.getByTestId("boundary-gap")).toHaveTextContent(/Gap between “thing” and “about”: .*they run together/);
  });

  it("says how a numeral was said", () => {
    const nine = { ...makeWord("9.30", 1, "n aɪ n", "n aɪ n"), canonical_text: "nine thirty" };
    renderLesson(nine);
    expect(screen.getByTestId("spoken-form")).toHaveTextContent("Written “9.30”, said as “nine thirty”");
  });

  it("without a dictionary form there is nothing to compare, and it says so", () => {
    const pct = { ...makeWord("%", 1, "ə", "ə"), canonical_aligned: [], no_canonical: true };
    renderLesson(pct);
    expect(screen.getByText("No dictionary form")).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.getByText("no dictionary form")).toBeInTheDocument(); // divergence, in step 3
  });
});

describe("WordLesson — 3 Why", () => {
  it("explains each change, with the report's advice and its reason", () => {
    const both = makeWord("that", 0.4, "ð æ t", "ð æ", { phenomena: ["t_deletion", "flapping"] });
    renderLesson(both);
    expect(document.body).toHaveTextContent(/A word-final \/t\/ or \/d\/ is never actually pronounced/);
    expect(screen.getByText("Recognize only")).toBeInTheDocument();
    expect(screen.getByText("Casual: recognize it first.")).toBeInTheDocument();
    expect(screen.getByText("Safe to produce")).toBeInTheDocument();
    expect(screen.getByText("Safe and high-yield.")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /More examples in Learn/ })[0]).toHaveAttribute("href", "/learn/t_deletion");
  });

  it("calls lexical_form the reduced form, shows the full form, and gives that form's own advice", () => {
    const tryna = makeWord("tryna", 2, "t ɹ aɪ n ə", "t ɹ aɪ n ə", {
      phenomena: ["contraction_lex"],
      lexical_form: "tryna",
      lexical_expansion: "trying to",
    });
    renderLesson(tryna);
    expect(screen.getByText("reduced form")).toBeInTheDocument();
    expect(screen.getByText("reduced form").nextElementSibling).toHaveTextContent("“tryna” ← “trying to”");
    expect(screen.getByText("Marked: recognize it.")).toBeInTheDocument();
  });

  it("names the kind of link on a boundary", () => {
    const go = makeWord("go", 5.9, "ɡ oʊ", "ɡ oʊ", {
      phenomena: ["linking"],
      boundary_link_next: true,
      boundary_link_type: "glide_w",
    });
    renderLesson(go, { next: makeWord("on", 6.2, "ɑ n", "ɑ n") });
    expect(screen.getByTestId("link-type")).toHaveTextContent("glide [w]");
  });

  it("tells a label from form scoring apart from one read off the sounds", () => {
    const of = makeWord("of", 1, "ʌ v", "ʌ v", {
      phenomena: ["vowel_reduction"],
      variant_labels: ["vowel_reduction"],
      form: { ipa: "əv", strong_ipa: "ʌv", weak: true, weak_margin: 2.7, scores: { ʌv: -2.7, əv: 0 } },
    });
    renderLesson(of, { narrow: false });
    expect(screen.getByText(/From form scoring:/)).toBeInTheDocument();
    expect(screen.getByTestId("form-scoring")).toHaveTextContent(/weak form/);
    expect(screen.getByTestId("form-scoring")).toHaveTextContent(/ahead by 2\.7/);
  });

  it("warns, calmly and first, that the labels cannot be checked when nothing was heard", () => {
    const empty = {
      ...makeWord("and", 3, "æ n d", "æ n d", { phenomena: ["t_deletion", "word_elision"], low_confidence: true }),
      realized_aligned: [],
      realized_ipa: "",
    };
    renderLesson(empty);
    expect(screen.getByText(/not verifiable/)).toBeInTheDocument();
    expect(screen.getByText("Nothing heard here")).toBeInTheDocument();
  });

  it("says when nothing changed", () => {
    renderLesson(segment.words[2], { wordIndex: 2 });
    expect(screen.getByText(/Nothing changed: this word was said the way the dictionary says it/)).toBeInTheDocument();
  });

  it("shows how prominent the word was and whether it is a function word", () => {
    const withProminence: Segment = { ...segment, prominence: [1, 0.4, 0.8], word_classes: ["function", "function", "content"] };
    renderLesson(withProminence.words[0], { segment: withProminence, wordIndex: 0 });
    expect(screen.getByTestId("word-prominence")).toHaveTextContent(
      /100 % of the phrase's peak · function word — the peak fell on a grammar word/,
    );
  });
});

describe("WordLesson — 4 See the mouth", () => {
  it("draws the mouth (SVG without WebGL) and offers this word's sounds only", () => {
    renderLesson();
    expect(screen.getByTestId("tract-tongue")).toBeInTheDocument();
    const sounds = screen.getByRole("group", { name: "Sounds of this word" });
    expect(within(sounds).getAllByRole("button").map((b) => b.textContent)).toEqual(["ð", "æ"]);
  });

  it("holding a sound plays it and shows that sound", async () => {
    const { player } = renderLesson();
    const sounds = screen.getByRole("group", { name: "Sounds of this word" });
    await userEvent.click(within(sounds).getByRole("button", { name: "æ" }));
    expect(player.play).toHaveBeenCalled();
    expect(screen.getByRole("img", { name: /near-open front unrounded vowel/ })).toBeInTheDocument();
  });

  it("does not try to play without audio, but still shows the sound", async () => {
    const { player } = renderLesson(that, { canPlay: false });
    const sounds = screen.getByRole("group", { name: "Sounds of this word" });
    await userEvent.click(within(sounds).getByRole("button", { name: "æ" }));
    expect(player.play).not.toHaveBeenCalled();
    expect(screen.getByRole("img", { name: /near-open front unrounded vowel/ })).toBeInTheDocument();
  });

  it("can show the dictionary form instead, where the missing /t/ is", async () => {
    renderLesson();
    await userEvent.click(within(screen.getByRole("radiogroup", { name: "Show the mouth for" })).getByRole("radio", { name: "Dictionary form" }));
    const sounds = screen.getByRole("group", { name: "Sounds of this word" });
    expect(within(sounds).getAllByRole("button").map((b) => b.textContent)).toEqual(["ð", "æ", "t"]);
  });

  it("says so when the word has no sounds at all", () => {
    const silent = { ...makeWord("uh", 0.4, "ʌ", "ʌ"), realized_aligned: [], realized_ipa: "" };
    renderLesson(silent, { segment: { ...segment, words: [silent] }, wordIndex: 0 });
    expect(screen.getByText(/nothing recognized/)).toBeInTheDocument();
  });

  it("stays unmounted while its step is closed (the mouth is heavy)", () => {
    renderLesson(that, { steps: { see: false } });
    expect(screen.queryByTestId("tract-tongue")).toBeNull();
  });
});

describe("WordLesson — 5 Practice", () => {
  it("runs the shadowing loop: listen slowly, your turn, …, then full speed", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { player } = renderLesson(that, { steps: { practice: true } });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    await user.click(screen.getByRole("button", { name: "Start shadowing" }));
    expect(player.setRate).toHaveBeenLastCalledWith(0.75);
    expect(player.play).toHaveBeenLastCalledWith({ start: 0, end: 1.2 }); // the whole (short) phrase
    expect(screen.getByRole("status", { name: "" })).toBeDefined();
    expect(screen.getByText("Round 1 of 4: listen at 0.75×")).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(1600 + 10); // 1.2 s at 0.75×
    });
    expect(screen.getByText("Round 1 of 4: your turn — say it now")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Stop shadowing" }));
    expect(screen.getByRole("button", { name: "Start shadowing" })).toBeInTheDocument();
    expect(player.setRate).toHaveBeenLastCalledWith(1); // the speed before the session
  });
});

describe("WordLesson — guidance", () => {
  it("with compact guidance only the step headers show", () => {
    renderLesson(that, { steps: stepsFor("compact") });
    expect(step(/Step 1:\s*Listen/)).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: "Play the word" })).toBeNull();
  });
});
