import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { analysis, makeWord, renderWith } from "../test/fixtures";
import { WordDetail } from "./WordDetail";

const segment = analysis.segments[0];

const render = (word = segment.words[1], next: Parameters<typeof WordDetail>[0]["next"] = null,
                canPlay = true) =>
  renderWith(
    <WordDetail
      word={word}
      next={next}
      segment={segment}
      segmentIndex={0}
      isEmphasis={false}
      canPlay={canPlay}
    />,
  );

describe("WordDetail", () => {
  it("distinguishes dictionary, canonical and actual, and explains it in plain sight", () => {
    render();

    expect(screen.getByText("/ðæt/")).toBeInTheDocument();
    expect(screen.getByText("[ðæt]")).toBeInTheDocument();
    expect(screen.getByText("[ðæ]")).toBeInTheDocument();
    // the difference between the three concepts cannot live in a tooltip alone
    expect(screen.getByText(/espeak already applies native processes/)).toBeInTheDocument();
  });

  it("calls lexical_form “reduced form” and shows the full form when present", () => {
    const wanna = makeWord("wanna", 2, "w ɑ n ə", "w ɑ n ə", {
      phenomena: ["contraction_lex"],
      lexical_form: "wanna",
      lexical_expansion: "want to",
    });
    render(wanna);

    expect(screen.getByText("reduced form")).toBeInTheDocument();
    expect(screen.getByText(/“wanna”/)).toBeInTheDocument();
    expect(screen.getByText(/“want to”/)).toBeInTheDocument();
  });

  it("with a boundary phenomenon it offers to hear the link with the next word", async () => {
    const thing = makeWord("thing", 5.9, "θ ɪ ŋ", "θ ɪ ŋ", {
      phenomena: ["linking"],
      boundary_link_next: true,
    });
    const about = makeWord("about", 6.2, "ə b aʊ t", "ə b aʊ t");
    const { player } = render(thing, about);

    const button = screen.getByRole("button", { name: "▶ + about" });
    await userEvent.click(button);

    const span = (player.play as ReturnType<typeof import("vitest").vi.fn>).mock.calls[0][0];
    expect(span.start).toBeCloseTo(5.86, 2);
    expect(span.end).toBeGreaterThan(about.end); // reaches past the following word
  });

  it("warns that the labels are unverifiable when no phone was recognized", () => {
    const empty = {
      ...makeWord("and", 3, "æ n d", "æ n d", {
        phenomena: ["t_deletion", "word_elision"],
        low_confidence: true,
      }),
      realized_aligned: [],
      realized_ipa: "",
    };
    render(empty);

    expect(screen.getByText(/not verifiable/)).toBeInTheDocument();
  });

  it("disables the playback buttons when there is no audio", () => {
    render(segment.words[1], null, false);
    expect(screen.getByRole("button", { name: "▶ Word" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "▶ Phrase" })).toBeDisabled();
  });

  it("explains each phenomenon rather than merely naming it", () => {
    render();
    // "t/d deletion" means nothing to someone who is still learning
    expect(
      screen.getByText(/A word-final \/t\/ or \/d\/ is never actually pronounced/),
    ).toBeInTheDocument();
  });

  it("does not treat h-dropping as a boundary with the following word", () => {
    // it is word-internal: its useful context is the preceding word ("tell him")
    const him = makeWord("him", 3, "h ɪ m", "ɪ m", { phenomena: ["h_dropping"] });
    const back = makeWord("back", 3.3, "b æ k", "b æ k");
    render(him, back);

    expect(screen.queryByRole("button", { name: "▶ + back" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Boundary phenomenon/)).not.toBeInTheDocument();
  });
});
