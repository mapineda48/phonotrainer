/** The video over the transcript: a persistent preference, off by default and
 *  only available when the analysis actually has video. */

import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { analysis, job, renderWith } from "../test/fixtures";
import type { Job } from "../types";
import { AnalysisView } from "./AnalysisView";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return { ...original, api: { ...original.api, analysis: vi.fn() } };
});

const videoJob: Job = { ...job, is_video: true, has_media: true };
const FLAG = "phonotrainer:show-video";
const hasVideo = () => document.querySelector("video") !== null;

beforeEach(() => {
  window.localStorage.clear();
  vi.mocked(api.analysis).mockResolvedValue(analysis);
});

describe("AnalysisView — configurable video", () => {
  it("hides the video by default and offers it through the button", async () => {
    renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });

    expect(hasVideo()).toBe(false);
    expect(screen.getByRole("button", { name: "Video" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("the button shows it and the preference survives a remount", async () => {
    const tools = renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });

    await userEvent.click(screen.getByRole("button", { name: "Video" }));
    expect(hasVideo()).toBe(true);
    expect(window.localStorage.getItem(FLAG)).toBe("1");

    // Opening another analysis with video, the preference is remembered.
    tools.unmount();
    renderWith(<AnalysisView job={{ ...videoJob, id: "other" }} />);
    await screen.findByRole("button", { name: /^does/ });
    expect(hasVideo()).toBe(true);
  });

  it("the V key toggles it just like the button", async () => {
    renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });

    await userEvent.keyboard("v");
    expect(hasVideo()).toBe(true);
    await userEvent.keyboard("v");
    expect(hasVideo()).toBe(false);
  });

  it("the dock's ✕ button hides it and turns the preference off", async () => {
    window.localStorage.setItem(FLAG, "1");
    renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });
    expect(hasVideo()).toBe(true);

    await userEvent.click(screen.getByRole("button", { name: "Hide video" }));
    expect(hasVideo()).toBe(false);
    expect(window.localStorage.getItem(FLAG)).toBe("0");
  });

  it("with no video available there is no button, even if the preference is on", async () => {
    window.localStorage.setItem(FLAG, "1");
    renderWith(<AnalysisView job={{ ...videoJob, is_video: false }} />);
    await screen.findByRole("button", { name: /^does/ });

    expect(screen.queryByRole("button", { name: "Video" })).toBeNull();
    expect(hasVideo()).toBe(false);
  });
});
