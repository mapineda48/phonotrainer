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
