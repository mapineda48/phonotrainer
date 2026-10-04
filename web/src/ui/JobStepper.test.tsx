import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { job } from "../test/fixtures";
import { renderPage } from "../test/render";
import type { Job } from "../types";
import { announcement, JobStepper } from "./JobStepper";

const running: Job = {
  ...job,
  status: "running",
  percent: 18,
  options: { ...job.options, separate_dialogue: true },
  progress: [
    { at: "t", message: "Extracting audio…" },
    { at: "t", message: "Separating dialogue from music and effects (htdemucs)…" },
  ],
  last_message: "Separating dialogue from music and effects (htdemucs)…",
};

describe("JobStepper", () => {
  it("names every step with its state in words, and marks the current one", () => {
    renderPage(<JobStepper job={running} />);
    const items = within(screen.getByRole("list", { name: "Analysis steps" })).getAllByRole("listitem");

    const labels = ["Extract the audio", "Separate the dialogue", "Transcribe the words", "Hear the phones", "Compare and save"];
    expect(items).toHaveLength(labels.length);
    labels.forEach((label, index) => expect(items[index]).toHaveTextContent(label));
    expect(items[0]).toHaveTextContent("— done");
    expect(items[1]).toHaveAttribute("aria-current", "step");
    expect(items[1]).toHaveTextContent("— in progress");
    expect(items[2]).toHaveTextContent("— next");
    // each step teaches what it is for
    expect(items[1]).toHaveTextContent(/only the voices remain/);
    expect(screen.getByRole("progressbar", { name: "Overall progress" })).toHaveAttribute("aria-valuenow", "18");
  });

  it("does not announce the state it opens with, only what changes", () => {
    const { rerender } = renderPage(<JobStepper job={running} />);
    expect(screen.getByRole("status")).toBeEmptyDOMElement();

    rerender(<JobStepper job={{ ...running, status: "error", error: "disk full" }} />);
    expect(screen.getByRole("status")).toHaveTextContent("The analysis failed: disk full.");
  });

  it("says what happened in words for every final state", () => {
    expect(announcement({ ...job, status: "done" })).toBe("The analysis is ready.");
    expect(announcement({ ...job, status: "cancelled" })).toBe("The analysis was cancelled.");
    expect(announcement({ ...job, status: "queued" })).toBe("Waiting in line.");
    expect(announcement(running)).toBe("Now: Separate the dialogue.");
  });
});
