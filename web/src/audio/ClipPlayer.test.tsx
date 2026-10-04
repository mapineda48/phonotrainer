import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderPage } from "../test/render";
import { clipAudio, ClipPlayer } from "./ClipPlayer";

afterEach(() => clipAudio.stop());

describe("ClipPlayer", () => {
  it("plays the job's audio from the start of the span, and the same button stops it", async () => {
    const play = vi.spyOn(window.HTMLMediaElement.prototype, "play");
    renderPage(<ClipPlayer jobId="job1" start={2.31} end={2.52} label="Play “get it”" />);

    await userEvent.click(screen.getByRole("button", { name: "Play “get it”" }));
    // the source loads first; playback starts once its metadata is known
    const audio = (clipAudio as unknown as { audio: HTMLAudioElement }).audio;
    expect(audio.src).toMatch(/\/api\/jobs\/job1\/audio$/);
    act(() => {
      audio.dispatchEvent(new Event("loadedmetadata"));
    });
    expect(play).toHaveBeenCalled();
    expect(audio.currentTime).toBeCloseTo(2.26, 2);

    const stop = screen.getByRole("button", { name: "Stop: Play “get it”" });
    expect(stop).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(stop);
    expect(screen.getByRole("button", { name: "Play “get it”" })).toHaveAttribute("aria-pressed", "false");
  });

  it("only one clip plays at a time", async () => {
    renderPage(
      <>
        <ClipPlayer jobId="job1" start={1} end={2} label="first" />
        <ClipPlayer jobId="job1" start={3} end={4} label="second" />
      </>,
    );
    await userEvent.click(screen.getByRole("button", { name: "first" }));
    await userEvent.click(screen.getByRole("button", { name: "second" }));
    expect(screen.getByRole("button", { name: "first" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Stop: second" })).toHaveAttribute("aria-pressed", "true");
  });
});
