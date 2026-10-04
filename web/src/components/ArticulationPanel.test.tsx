import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { analysis, fakePlayer, makeWord, renderWith } from "../test/fixtures";
import { ArticulationPanel } from "./ArticulationPanel";

/** jsdom has no WebGL, so what renders here is the SVG fallback — which is
 *  exactly the point: the panel has to stay usable without an engine. */
const segment = analysis.segments[0];
const word = segment.words[1]; // "that" → [ð æ], with its /t/ deleted

describe("ArticulationPanel", () => {
  it("names the phone under the playhead and says what the mouth does", () => {
    const player = fakePlayer();
    player.clock.set(segment.words[0].realized_aligned[0][1] + 0.01); // the /d/ of "does"
    renderWith(
      <ArticulationPanel word={word} next={null} segment={segment} canPlay />,
      { player },
    );

    expect(screen.getByText(/voiced alveolar stop/)).toBeInTheDocument();
    expect(screen.getByText(/the same seal as \/t\//i)).toBeInTheDocument();
  });

  it("draws a tongue, with no WebGL anywhere in sight", () => {
    renderWith(<ArticulationPanel word={word} next={null} segment={segment} canPlay />);
    const tongue = screen.getByTestId("tract-tongue");
    expect(tongue.getAttribute("d")?.length).toBeGreaterThan(50);
  });

  it("offers the phones of the selected word, and only those", () => {
    const { container } = renderWith(
      <ArticulationPanel word={word} next={null} segment={segment} canPlay />,
    );
    const strip = container.querySelector(".tract-panel__strip") as HTMLElement;
    expect(within(strip).getAllByRole("button").map((b) => b.textContent)).toEqual(["ð", "æ"]);
  });

  it("holding a phone plays it and shows that phone, not the playhead's", async () => {
    const player = fakePlayer();
    const { container } = renderWith(
      <ArticulationPanel word={word} next={null} segment={segment} canPlay />,
      { player },
    );
    const strip = container.querySelector(".tract-panel__strip") as HTMLElement;

    await userEvent.click(within(strip).getByRole("button", { name: "æ" }));

    expect(screen.getByText(/near-open front unrounded vowel/)).toBeInTheDocument();
    const span = (player.play as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(span.start).toBeLessThan(span.end);
  });

  it("does not try to play when the analysis has no audio", async () => {
    const player = fakePlayer();
    const { container } = renderWith(
      <ArticulationPanel word={word} next={null} segment={segment} canPlay={false} />,
      { player },
    );
    const strip = container.querySelector(".tract-panel__strip") as HTMLElement;

    await userEvent.click(within(strip).getByRole("button", { name: "æ" }));

    expect(player.play).not.toHaveBeenCalled();
    // …but it still shows the phone: the picture works without sound.
    expect(screen.getByText(/near-open front unrounded vowel/)).toBeInTheDocument();
  });

  it("can show the canonical form instead of what was said", async () => {
    // "that" lost its /t/: the actual row has two phones and the canonical
    // three. Switching streams is how you see the movement that went missing.
    const { container } = renderWith(
      <ArticulationPanel word={word} next={null} segment={segment} canPlay />,
    );
    const strip = () => container.querySelector(".tract-panel__strip") as HTMLElement;
    expect(within(strip()).getAllByRole("button")).toHaveLength(2);

    await userEvent.click(screen.getByRole("button", { name: "canonical" }));

    expect(within(strip()).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "ð",
      "æ",
      "t",
    ]);
  });

  it("says so when the word has no phones at all", () => {
    const silent = { ...makeWord("uh", 0.4, "ʌ", "ʌ"), realized_aligned: [], realized_ipa: "" };
    const lonely = { ...segment, words: [silent] };
    renderWith(<ArticulationPanel word={silent} next={null} segment={lonely} canPlay />);
    expect(screen.getByText(/nothing recognised/)).toBeInTheDocument();
  });

  it("reaches into the next word when the phenomenon crosses the boundary", async () => {
    // "does" links into "that": the tongue has to keep moving past the
    // boundary, so the next word's phones belong in the same track.
    const linking = analysis.segments[0].words[0];
    const { container } = renderWith(
      <ArticulationPanel word={linking} next={word} segment={segment} canPlay />,
    );
    const strip = container.querySelector(".tract-panel__strip") as HTMLElement;
    // The strip is still only this word's phones…
    expect(within(strip).getAllByRole("button")).toHaveLength(3);
    // …and the drawing survives being asked about a time inside the next word.
    expect(screen.getByTestId("tract-tongue")).toBeInTheDocument();
  });
});
