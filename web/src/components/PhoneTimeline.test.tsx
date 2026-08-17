import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { makeWord, reference, renderWith } from "../test/fixtures";
import { PhoneTimeline, splitIpa } from "./PhoneTimeline";

describe("PhoneTimeline", () => {
  const word = makeWord("that", 0.4, "ð æ t", "ð æ", { phenomena: ["t_deletion"] });

  it("shows the three rows: dictionary, canonical and actual", () => {
    renderWith(<PhoneTimeline word={word} />);

    expect(screen.getByText(/dictionary/)).toBeInTheDocument();
    expect(screen.getByText("aligned canonical")).toBeInTheDocument();
    expect(screen.getByText("actually pronounced")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "ð" })).toHaveLength(2); // the timed rows
  });

  it("the dictionary row exposes the phenomenon when the canonical already applies it", () => {
    // espeak canonicalizes "better" WITH the flap: canonical and actual are
    // identical and the /t/ exists only in the dictionary. Without that row the
    // flapping would be invisible.
    const better = makeWord("better", 8.9, "b ɛ ɾ ɚ", "b ɛ ɾ ɚ", {
      phenomena: ["flapping"],
      dict_ipa: "bɛtɚ",
    });
    const { container } = renderWith(<PhoneTimeline word={better} />);

    const dict = container.querySelector(".phones__dict") as HTMLElement;
    expect(within(dict).getByText("t")).toHaveClass("phone--diff");
    expect(within(dict).getByText("b")).not.toHaveClass("phone--diff");
  });

  it("marks the phone that has no counterpart in the other row", () => {
    renderWith(<PhoneTimeline word={word} />);
    expect(screen.getByRole("button", { name: "t" })).toHaveClass("phone--diff");
    expect(screen.getAllByRole("button", { name: "ð" })[0]).not.toHaveClass("phone--diff");
  });

  it("does not flag as divergent phones that merely graze each other in time", () => {
    // times are single-frame peaks: the same phone can show up 20 ms offset in
    // each row without that being a divergence
    const offset = {
      ...makeWord("don't", 0.6, "d oʊ n", "d oʊ n"),
      canonical_aligned: [["n", 0.684, 0.704]] as [string, number, number][],
      realized_aligned: [["n", 0.704, 0.744]] as [string, number, number][],
    };
    renderWith(<PhoneTimeline word={offset} />);
    for (const button of screen.getAllByRole("button", { name: "n" })) {
      expect(button).not.toHaveClass("phone--diff");
    }
  });

  it("clicking a phone plays it with some padding", async () => {
    const { player } = renderWith(<PhoneTimeline word={word} />);

    await userEvent.click(screen.getByRole("button", { name: "t" }));

    const span = (player.play as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(span.start).toBeCloseTo(0.5, 2);
    expect(span.end).toBeGreaterThan(span.start);
  });

  it("does not over-mark a word pronounced exactly as written", () => {
    // "I" = /aɪ/ → [aɪ]: nothing to point out. Both halves of the diphthong used
    // to come out marked because the dictionary row tokenized differently.
    const i = makeWord("I", 1, "aɪ", "aɪ", { dict_ipa: "aɪ" });
    const { container } = renderWith(<PhoneTimeline word={i} />);

    const dict = container.querySelector(".phones__dict") as HTMLElement;
    expect(dict.textContent).toBe("aɪ");
    expect(dict.querySelectorAll(".phone--diff")).toHaveLength(0);
  });

  it("with a boundary phenomenon it includes the next word, marks it and measures the gap", () => {
    const thing = makeWord("thing", 5.9, "θ ɪ ŋ", "θ ɪ ŋ", {
      phenomena: ["linking"],
      boundary_link_next: true,
    });
    const about = makeWord("about", 6.03, "ə b aʊ t", "ə b aʊ t");
    renderWith(<PhoneTimeline word={thing} next={about} />);

    expect(screen.getByText("boundary")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "aʊ" }).length).toBeGreaterThan(0);
    // the real gap is what makes the linking visible
    expect(screen.getByText(/Gap at the boundary/)).toBeInTheDocument();
    expect(screen.getByText(/that is the linking/)).toBeInTheDocument();
  });

  it("the next word does not mask what is missing from the one beside it", () => {
    // "that" loses its /t/; "time" starts with /t/: it must not count as spoken
    const that = makeWord("that", 0.4, "ð æ t", "ð æ", {
      dict_ipa: "ðæt",
      phenomena: ["t_deletion", "linking"],
    });
    const time = makeWord("time", 0.7, "t aɪ m", "t aɪ m");
    const { container } = renderWith(<PhoneTimeline word={that} next={time} />);

    const dict = container.querySelector(".phones__dict") as HTMLElement;
    expect(within(dict).getByText("t")).toHaveClass("phone--diff");
  });

  it("warns when nothing was recognized", () => {
    const silent = { ...makeWord("uh", 1, "ʌ", "ʌ"), realized_aligned: [], realized_ipa: "" };
    renderWith(<PhoneTimeline word={silent} />);
    expect(screen.getByText(/nothing recognized/)).toBeInTheDocument();
  });

  it("does not sell duration where there is only a detected instant", () => {
    renderWith(<PhoneTimeline word={word} />);
    expect(screen.getByText(/detected instant/)).toBeInTheDocument();
  });

  it("positions each phone according to its instant", () => {
    const { container } = renderWith(<PhoneTimeline word={word} />);
    const rows = container.querySelectorAll(".phones__row");
    const first = within(rows[0] as HTMLElement).getAllByRole("button")[0];
    expect((first as HTMLElement).style.left).toBe("0%");
  });
});

describe("splitIpa", () => {
  const tokens = reference.ipa_tokens;

  it("keeps diacritics with their base symbol", () => {
    expect(splitIpa("bɛtɚ", tokens)).toEqual(["b", "ɛ", "t", "ɚ"]);
    expect(splitIpa("", tokens)).toEqual([]);
  });

  it("does not split multi-character symbols", () => {
    // splitting "aɪ" into "a" + "ɪ" flagged half a correctly spoken word as unpronounced
    expect(splitIpa("baɪ", tokens)).toEqual(["b", "aɪ"]);
    expect(splitIpa("ɡoʊ", tokens)).toEqual(["ɡ", "oʊ"]);
    expect(splitIpa("bʌdʒɪt", tokens)).toEqual(["b", "ʌ", "dʒ", "ɪ", "t"]);
    expect(splitIpa("ɑːɹ", tokens)).toEqual(["ɑːɹ"]);
    expect(splitIpa("tʃiːz", tokens)).toEqual(["tʃ", "iː", "z"]);
  });

  it("attaches the stress mark to the phone that carries it", () => {
    expect(splitIpa("bˈɛtɚ", tokens)).toEqual(["b", "ˈɛ", "t", "ɚ"]);
    expect(splitIpa("tənˈaɪt", tokens)).toEqual(["t", "ə", "n", "ˈaɪ", "t"]);
  });
});
