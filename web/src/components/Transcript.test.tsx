import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { describe, expect, it, vi } from "vitest";

import { analysis, fakePlayer, renderWith, wordButton } from "../test/fixtures";
import { Transcript } from "./Transcript";

const noop = () => undefined;

const renderTranscript = (props: Partial<Parameters<typeof Transcript>[0]> = {}, player?: ReturnType<typeof fakePlayer>) =>
  renderWith(
    <Transcript
      analysis={analysis}
      selected={null}
      onSelect={noop}
      filter={new Set()}
      follow={false}
      {...props}
    />,
    player ? { player } : {},
  );

describe("Transcript", () => {
  it("renders every word and marks the ones carrying a phenomenon", () => {
    renderTranscript();

    expect(wordButton("does")).toBeInTheDocument();
    expect(wordButton("work")).toBeInTheDocument();
    // "does" has vowel reduction → it gets a family color
    expect(wordButton("does")).toHaveClass("w--fam");
    expect(wordButton("work")).not.toHaveClass("w--fam");
  });

  it("a segment with too little voicing for a range shows only its mean", () => {
    const [first, ...rest] = analysis.segments;
    const short = { ...first, f0_stats: { ...first.f0_stats, range: null, range_st: null } };
    renderTranscript({ analysis: { ...analysis, segments: [short, ...rest] } });
    expect(screen.getByText(/F0 118 Hz/).textContent).not.toMatch(/range/);
    expect(screen.getByText(/F0 130 Hz/).textContent).toMatch(/range 20 Hz/); // the other segment
  });

  it("the accessible name states the phenomenon: identity does not rely on color", () => {
    renderTranscript();
    expect(wordButton("that")).toHaveAccessibleName("that, t/d deletion");
    expect(wordButton("work")).toHaveAccessibleName("work");
  });

  it("clicking a word selects it and plays it", async () => {
    const onSelect = vi.fn();
    const { player } = renderTranscript({ onSelect });

    await userEvent.click(wordButton("that"));

    expect(onSelect).toHaveBeenCalledWith({ segment: 0, index: 1 });
    // "that" lasts 110 ms: the span stretches until audible, centered on it
    expect(player.play).toHaveBeenCalledWith(
      expect.objectContaining({ start: expect.closeTo(0.36, 2) }),
    );
  });

  it("dims what falls outside the filter without hiding it", () => {
    renderTranscript({ filter: new Set(["t_deletion"]) });

    expect(wordButton("that")).not.toHaveClass("w--muted");
    expect(wordButton("does")).toHaveClass("w--muted");
    expect(wordButton("does")).toBeVisible();
  });

  it("follows playback by highlighting the sounding word", () => {
    const player = fakePlayer();
    renderTranscript({}, player);

    act(() => player.clock.set(0.45));
    expect(wordButton("that")).toHaveClass("w--playing");
    expect(wordButton("does")).not.toHaveClass("w--playing");

    act(() => player.clock.set(2.05));
    expect(wordButton("wanna")).toHaveClass("w--playing");
    expect(wordButton("that")).not.toHaveClass("w--playing");
  });

  it("with “follow” on, the sounding segment pins to the top", () => {
    const scrollIntoView = vi.fn();
    Object.defineProperty(window.Element.prototype, "scrollIntoView", {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    });
    const player = fakePlayer();
    renderTranscript({ follow: true }, player);

    // The first segment is already active on load: it pins without waiting.
    expect(scrollIntoView).toHaveBeenCalledWith({ block: "start", behavior: "smooth" });

    act(() => player.clock.set(2.05)); // the second segment comes in

    expect(scrollIntoView).toHaveBeenLastCalledWith({ block: "start", behavior: "smooth" });
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
  });

  it("with “follow” off, it does not scroll the transcript", () => {
    const scrollIntoView = vi.fn();
    Object.defineProperty(window.Element.prototype, "scrollIntoView", {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    });
    const player = fakePlayer();
    renderTranscript({ follow: false }, player);

    act(() => player.clock.set(2.05));

    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it("the time button plays the whole segment", async () => {
    const { player } = renderTranscript();

    await userEvent.click(screen.getByRole("button", { name: /0:00.0–0:01.2/ }));
    expect(player.play).toHaveBeenCalledWith({ start: 0, end: 1.2 });
  });

  it("shows a contraction's reduced form and the link between words", () => {
    renderTranscript();
    expect(screen.getByText("want to")).toBeInTheDocument();
    expect(screen.getAllByText("‿").length).toBeGreaterThan(0);
  });

  it("the tooltip compares dictionary with what was said, not canonical with itself", () => {
    renderTranscript();
    // "work" is pronounced just like its canonical form: repeating it taught nothing
    expect(wordButton("work")).toHaveAttribute("title", "work · /wɝk/ → [wɝk]");
    expect(wordButton("that")).toHaveAttribute(
      "title",
      "that · /ðæt/ → [ðæ] — t/d deletion",
    );
  });

  it("offers the phonetic transcription of the whole phrase", () => {
    renderTranscript();
    const details = screen.getAllByText("Phonetic transcription of the phrase");
    expect(details).toHaveLength(2); // one per segment
    expect(screen.getByText("[dəz‿ðæ wɝk]")).toBeInTheDocument();
    expect(screen.getByText("/dʌz‿ðæt wɝk/")).toBeInTheDocument();
  });
});

describe("Transcript — intonation and prominence", () => {
  const withProsody = {
    ...analysis,
    segments: [
      {
        ...analysis.segments[0],
        prominence: [0.3, 0.5, 1],
        word_classes: ["function", "function", "content"] as ("function" | "content")[],
        intonation_units: [
          {
            start: 0, end: 1.2, words: [0, 2] as [number, number], text: "does that work",
            type: "yes_no_question" as const, final_contour: "falling" as const,
            final_slope_st: -6.1, expected_contour: "rising" as const,
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
            start: 2, end: 3, words: [0, 1] as [number, number], text: "wanna go",
            type: "statement" as const, final_contour: "rising" as const,
            final_slope_st: 7.9, expected_contour: "falling" as const,
            matches_expected: false, uptalk: true,
          },
        ],
      },
    ],
  };

  it("reads each sentence's final contour against the one its type calls for", () => {
    renderTranscript({ analysis: withProsody });

    const [question, statement] = screen.getAllByTestId("intonation-unit");
    expect(question).toHaveTextContent("yes/no question ↘ expected ↗");
    expect(statement).toHaveTextContent(/statement ↗ expected ↘\s*uptalk/);
  });

  it("the phrase details show prominence per word and the rhythm, marked approximate", () => {
    renderTranscript({ analysis: withProsody });

    const strip = screen.getByTestId("prominence-strip");
    expect(strip).toHaveTextContent("does");
    expect(screen.getByLabelText("work: 100 %, content word")).toBeInTheDocument();
    expect(screen.getByTestId("segment-rhythm")).toHaveTextContent(/rhythm \(approximate\) nPVI 37/);
  });

  it("a sentence cut off by the segment boundary is not judged", () => {
    const cut = {
      ...analysis,
      segments: [
        {
          ...analysis.segments[0],
          intonation_units: [
            {
              start: 0, end: 1.2, words: [0, 2] as [number, number], text: "does that work",
              type: "incomplete" as const, final_contour: "rising" as const,
              final_slope_st: 4, expected_contour: null, matches_expected: null, uptalk: false,
            },
          ],
        },
      ],
    };
    renderTranscript({ analysis: cut });
    expect(screen.queryByTestId("intonation-unit")).toBeNull();
  });

  it("older analyses without these fields render as before", () => {
    renderTranscript();
    expect(screen.queryByTestId("intonation-unit")).toBeNull();
    expect(screen.queryByTestId("prominence-strip")).toBeNull();
  });
});
