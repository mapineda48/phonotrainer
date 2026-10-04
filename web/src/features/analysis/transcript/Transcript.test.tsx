import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { describe, expect, it, vi } from "vitest";

import { fullReference, renderPage } from "../../../test/render";
import { analysis, fakePlayer, wordButton } from "../../../test/fixtures";
import type { Analysis } from "../../../types";
import { Transcript } from "./Transcript";

const noop = () => undefined;

function renderTranscript(props: Partial<Parameters<typeof Transcript>[0]> = {}, player = fakePlayer()) {
  const tools = renderPage(
    <Transcript
      analysis={analysis}
      selected={null}
      onSelect={noop}
      filter={new Set()}
      query=""
      follow={false}
      {...props}
    />,
    { player },
  );
  return { ...tools, player };
}

function stubScroll() {
  const scrollIntoView = vi.fn();
  Object.defineProperty(window.Element.prototype, "scrollIntoView", {
    configurable: true,
    writable: true,
    value: scrollIntoView,
  });
  return scrollIntoView;
}

describe("Transcript", () => {
  it("renders every word and names its changes: identity never relies on color", () => {
    renderTranscript();
    expect(wordButton("that")).toHaveAccessibleName("that, t/d deletion");
    expect(wordButton("does")).toHaveAccessibleName("does, vowel reduction");
    expect(wordButton("work")).toHaveAccessibleName("work");
  });

  it("marks a word with its family (underline style + icon), and an unmarked one with none", () => {
    renderTranscript();
    expect(wordButton("does")).toHaveAttribute("data-family", "reduction");
    expect(wordButton("that")).toHaveAttribute("data-family", "td");
    expect(wordButton("work")).not.toHaveAttribute("data-family");
    // the inline family icon (shown with pattern emphasis) rides inside the button
    expect(wordButton("does").querySelector("svg")).not.toBeNull();
    expect(wordButton("work").querySelector("svg")).toBeNull();
  });

  it("an elided word gets the neutral mark, a contraction its own: neither borrows the other's", () => {
    const elided: Analysis = {
      ...analysis,
      segments: [
        analysis.segments[0],
        {
          ...analysis.segments[1],
          words: analysis.segments[1].words.map((word) =>
            word.word === "go" ? { ...word, phenomena: ["word_elision"], low_confidence: false } : word,
          ),
        },
      ],
    };
    renderPage(
      <Transcript analysis={elided} selected={null} onSelect={noop} filter={new Set()} query="" follow={false} />,
      { player: fakePlayer(), reference: fullReference },
    );
    expect(wordButton("go")).toHaveAttribute("data-mark", "none");
    expect(wordButton("go")).not.toHaveAttribute("data-family");
    expect(wordButton("go").querySelector("svg")).toHaveClass("lucide-circle-dashed");
    expect(wordButton("wanna")).toHaveAttribute("data-mark", "lexical");
    expect(wordButton("wanna").querySelector("svg")).not.toHaveClass("lucide-circle-dashed");
  });

  it("clicking a word selects it", async () => {
    const onSelect = vi.fn();
    renderTranscript({ onSelect });
    await userEvent.click(wordButton("that"));
    expect(onSelect).toHaveBeenCalledWith({ segment: 0, index: 1 });
  });

  it("shows the selected word as pressed", () => {
    renderTranscript({ selected: { segment: 0, index: 1 } });
    expect(wordButton("that")).toHaveAttribute("aria-pressed", "true");
    expect(wordButton("does")).toHaveAttribute("aria-pressed", "false");
  });

  it("fades what falls outside the filter without hiding it", () => {
    renderTranscript({ filter: new Set(["t_deletion"]) });
    expect(wordButton("that")).not.toHaveAttribute("data-dimmed");
    expect(wordButton("does")).toHaveAttribute("data-dimmed", "true");
    expect(wordButton("does")).toBeVisible();
    // two cues: faded AND without its family underline
    expect(wordButton("does")).not.toHaveClass("fam-u");
  });

  it("fades the words that do not match the search", () => {
    renderTranscript({ query: "WA" });
    expect(wordButton("wanna")).not.toHaveAttribute("data-dimmed");
    expect(wordButton("work")).toHaveAttribute("data-dimmed", "true");
  });

  it("follows playback by highlighting the sounding word", () => {
    const player = fakePlayer();
    renderTranscript({}, player);

    act(() => player.clock.set(0.45));
    expect(wordButton("that")).toHaveAttribute("data-playing", "true");
    expect(wordButton("does")).not.toHaveAttribute("data-playing");

    act(() => player.clock.set(2.05));
    expect(wordButton("wanna")).toHaveAttribute("data-playing", "true");
    expect(wordButton("that")).not.toHaveAttribute("data-playing");
  });

  it("with “follow” on, the sounding phrase pins to the top", () => {
    const scrollIntoView = stubScroll();
    const player = fakePlayer();
    renderTranscript({ follow: true }, player);

    // the first phrase is already active on load: it pins without waiting
    expect(scrollIntoView).toHaveBeenCalledWith({ block: "start", behavior: "smooth" });
    act(() => player.clock.set(2.05));
    expect(scrollIntoView).toHaveBeenLastCalledWith({ block: "start", behavior: "smooth" });
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
  });

  it("with reduced motion, following does not animate the scroll", () => {
    const scrollIntoView = stubScroll();
    renderPage(
      <Transcript analysis={analysis} selected={null} onSelect={noop} filter={new Set()} query="" follow />,
      { player: fakePlayer(), settings: { motion: "reduce" } },
    );
    expect(scrollIntoView).toHaveBeenCalledWith({ block: "start", behavior: "auto" });
  });

  it("with “follow” off, it does not scroll", () => {
    const scrollIntoView = stubScroll();
    const player = fakePlayer();
    renderTranscript({ follow: false }, player);
    act(() => player.clock.set(2.05));
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it("the phrase's time button plays the whole phrase", async () => {
    const { player } = renderTranscript();
    await userEvent.click(screen.getByRole("button", { name: /Play phrase 1, 0:00.0–0:01.2/ }));
    expect(player.play).toHaveBeenCalledWith({ start: 0, end: 1.2 });
  });

  it("shows a contraction's reduced form and the link between words", () => {
    renderTranscript();
    expect(screen.getByText("want to")).toBeInTheDocument();
    expect(screen.getAllByText("‿").length).toBeGreaterThan(0);
  });

  it("offers the phrase in IPA, said and dictionary, on demand", async () => {
    renderTranscript();
    const [first] = screen.getAllByRole("button", { name: /Pitch, stress and IPA of this phrase/ });
    expect(screen.queryByText("[dəz‿ðæ wɝk]")).toBeNull();
    await userEvent.click(first);
    expect(screen.getByText("[dəz‿ðæ wɝk]")).toBeInTheDocument();
    expect(screen.getByText("/dʌz‿ðæt wɝk/")).toBeInTheDocument();
    expect(screen.getByTestId("segment-f0")).toHaveTextContent("average pitch 118 Hz, range 62 Hz");
  });

  it("a phrase with too little voicing for a range shows only its mean", async () => {
    const [first, ...rest] = analysis.segments;
    const short = { ...first, f0_stats: { ...first.f0_stats, range: null, range_st: null } };
    renderTranscript({ analysis: { ...analysis, segments: [short, ...rest] } });
    await userEvent.click(screen.getAllByRole("button", { name: /Pitch, stress and IPA/ })[0]);
    expect(screen.getByTestId("segment-f0").textContent).not.toMatch(/range/);
  });

  describe("keyboard", () => {
    it("Tab lands on one word only; the arrows move between words and phrases", async () => {
      renderTranscript({ selected: { segment: 0, index: 1 } });
      const tabbable = screen.getAllByRole("button").filter((b) => b.dataset.word && b.tabIndex === 0);
      expect(tabbable).toEqual([wordButton("that")]);

      wordButton("that").focus();
      await userEvent.keyboard("{ArrowRight}");
      expect(wordButton("work")).toHaveFocus();
      await userEvent.keyboard("{ArrowRight}"); // across the phrase boundary
      expect(wordButton("wanna")).toHaveFocus();
      await userEvent.keyboard("{ArrowUp}");
      expect(wordButton("does")).toHaveFocus();
      await userEvent.keyboard("{End}");
      expect(wordButton("go")).toHaveFocus();
      await userEvent.keyboard("{Home}");
      expect(wordButton("does")).toHaveFocus();
    });

    it("Enter on a focused word selects it", async () => {
      const onSelect = vi.fn();
      renderTranscript({ onSelect });
      wordButton("does").focus();
      await userEvent.keyboard("{ArrowRight}{Enter}");
      expect(onSelect).toHaveBeenCalledWith({ segment: 0, index: 1 });
    });
  });
});

describe("Transcript — intonation and prominence", () => {
  const withProsody: Analysis = {
    ...analysis,
    segments: [
      {
        ...analysis.segments[0],
        prominence: [0.3, 0.5, 1],
        word_classes: ["function", "function", "content"],
        intonation_units: [
          {
            start: 0, end: 1.2, words: [0, 2], text: "does that work",
            type: "yes_no_question", final_contour: "falling",
            final_slope_st: -6.1, expected_contour: "rising",
            matches_expected: false, uptalk: false,
          },
        ],
        rhythm: {
          npvi: 37.4, varco: 28.7, n_intervals: 12, n_pairs: 11, mean_ms: 153.1, sd_ms: 44,
          approximate: true, method: "inter-nucleus intervals (CTC peaks)",
        },
      },
      {
        ...analysis.segments[1],
        intonation_units: [
          {
            start: 2, end: 3, words: [0, 1], text: "wanna go",
            type: "statement", final_contour: "rising",
            final_slope_st: 7.9, expected_contour: "falling",
            matches_expected: false, uptalk: true,
          },
        ],
      },
    ],
  };

  it("reads each sentence's ending against the one its type calls for, in words", () => {
    renderTranscript({ analysis: withProsody });
    const [question, statement] = screen.getAllByTestId("intonation-unit");
    expect(question).toHaveTextContent("yes/no question falls · usually rises");
    expect(statement).toHaveTextContent(/statement rises · usually falls.*uptalk/);
  });

  it("a sentence that ends as expected says so", () => {
    const fine: Analysis = {
      ...withProsody,
      segments: [
        {
          ...withProsody.segments[0],
          intonation_units: [{ ...withProsody.segments[0].intonation_units![0], final_contour: "rising", matches_expected: true }],
        },
      ],
    };
    renderTranscript({ analysis: fine });
    expect(screen.getByTestId("intonation-unit")).toHaveTextContent("yes/no question rises · as expected");
  });

  it("the phrase details show prominence per word and the rhythm, marked approximate", async () => {
    renderTranscript({ analysis: withProsody });
    await userEvent.click(screen.getAllByRole("button", { name: /Pitch, stress and IPA/ })[0]);
    const strip = screen.getByTestId("prominence-strip");
    expect(within(strip).getByRole("listitem", { name: "work: 100 %, content word" })).toBeInTheDocument();
    expect(within(strip).getByRole("listitem", { name: "does: 30 %, function word" })).toBeInTheDocument();
    expect(screen.getByTestId("segment-rhythm")).toHaveTextContent(/nPVI 37.*approximate/);
  });

  it("a sentence cut off by the phrase end is not judged", () => {
    const cut: Analysis = {
      ...analysis,
      segments: [
        {
          ...analysis.segments[0],
          intonation_units: [
            {
              start: 0, end: 1.2, words: [0, 2], text: "does that work",
              type: "incomplete", final_contour: "rising",
              final_slope_st: 4, expected_contour: null, matches_expected: null, uptalk: false,
            },
          ],
        },
      ],
    };
    renderTranscript({ analysis: cut });
    expect(screen.queryByTestId("intonation-unit")).toBeNull();
  });

  it("older analyses without these fields fall back to the phrase's final contour", async () => {
    renderTranscript();
    expect(screen.queryByTestId("intonation-unit")).toBeNull();
    expect(screen.getByText(/ends: rises/)).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole("button", { name: /Pitch, stress and IPA/ })[0]);
    expect(screen.queryByTestId("prominence-strip")).toBeNull();
  });
});
