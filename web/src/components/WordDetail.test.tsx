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

  it("shows the mouth by default and remembers it being put away", async () => {
    // Seeing the tongue is the reason to open a word, so it starts open; but
    // the panel is tall, and someone comparing transcriptions wants it gone.
    window.localStorage.removeItem("phonotrainer:show-tract");
    const { unmount } = render();
    expect(screen.getByText("articulation")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Mouth" }));
    expect(screen.queryByText("articulation")).not.toBeInTheDocument();

    unmount();
    render();
    expect(screen.queryByText("articulation")).not.toBeInTheDocument();
    window.localStorage.removeItem("phonotrainer:show-tract");
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

describe("WordDetail — what to do with it", () => {
  it("says whether each phenomenon is safe to produce or only to recognize", () => {
    const that = makeWord("that", 0.4, "ð æ t", "ð æ", { phenomena: ["t_deletion", "flapping"] });
    render(that);

    const badges = screen.getAllByTestId("practice-badge");
    expect(badges.map((badge) => badge.textContent)).toEqual([
      "recognize only · casual",
      "safe to produce · universal",
    ]);
    // the reason is the report's, one hover away
    expect(badges[0]).toHaveAttribute("title", "Casual: recognize it first.");
  });

  it("gives the advice for the reduced form itself, not the generic label", () => {
    const tryna = makeWord("tryna", 2, "t ɹ aɪ n ə", "t ɹ aɪ n ə", {
      phenomena: ["contraction_lex"],
      lexical_form: "tryna",
      lexical_expansion: "trying to",
    });
    render(tryna);

    const advice = screen.getAllByTestId("practice-badge").map((badge) => badge.textContent);
    expect(advice).toContain("recognize only · marked");
  });

  it("names the kind of link on a boundary", () => {
    const go = makeWord("go", 5.9, "ɡ oʊ", "ɡ oʊ", {
      phenomena: ["linking"],
      boundary_link_next: true,
      boundary_link_type: "glide_w",
    });
    const on = makeWord("on", 6.2, "ɑ n", "ɑ n");
    render(go, on);

    expect(screen.getByTestId("link-type")).toHaveTextContent("glide [w]");
  });

  it("tells a label from form scoring apart from one read off the phones", () => {
    const of = makeWord("of", 1, "ʌ v", "ʌ v", {
      phenomena: ["vowel_reduction"],
      variant_labels: ["vowel_reduction"],
      form: {
        ipa: "əv",
        strong_ipa: "ʌv",
        weak: true,
        weak_margin: 2.7,
        scores: { ʌv: -2.7, əv: 0 },
      },
    });
    render(of);

    expect(screen.getByText(/from form scoring/)).toBeInTheDocument();
    expect(screen.getByTestId("form-scoring")).toHaveTextContent(/weak form/);
    expect(screen.getByTestId("form-scoring")).toHaveTextContent(/ahead by 2\.7/);
  });

  it("shows how prominent the word was and whether it is a function word", () => {
    const withProminence = {
      ...segment,
      prominence: [1, 0.4, 0.8],
      word_classes: ["function", "function", "content"] as ("function" | "content")[],
    };
    renderWith(
      <WordDetail
        word={withProminence.words[0]}
        next={null}
        segment={withProminence}
        segmentIndex={0}
        isEmphasis
        canPlay
      />,
    );

    expect(screen.getByTestId("word-prominence")).toHaveTextContent(
      /100 % of the segment's peak · function word — the peak fell on a function word/,
    );
  });
});
