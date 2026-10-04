import { describe, expect, it } from "vitest";

import { job } from "../test/fixtures";
import type { Job } from "../types";
import { currentStage, jobStages, stageOfMessage, stageSummary } from "./stages";

const at = "2026-07-26T12:00:02+00:00";
const running = (messages: string[], extra: Partial<Job> = {}): Job => ({
  ...job,
  status: "running",
  percent: 40,
  has_analysis: false,
  options: { ...job.options, separate_dialogue: true },
  progress: messages.map((message) => ({ at, message })),
  last_message: messages[messages.length - 1] ?? null,
  ...extra,
});

const statuses = (j: Job) => Object.fromEntries(jobStages(j).map((s) => [s.key, s.status]));

describe("pipeline stages", () => {
  it("maps every backend progress message to its stage", () => {
    expect(stageOfMessage("Extracting audio (ffmpeg → 16 kHz mono WAV)…")).toBe("extract");
    expect(stageOfMessage("Separating dialogue from music and effects (htdemucs)…")).toBe("separate");
    expect(stageOfMessage("Transcribing with faster-whisper small…")).toBe("transcribe");
    expect(stageOfMessage("Segment 3/10: phones + alignment…")).toBe("phones");
    expect(stageOfMessage("Generating report.html…")).toBe("save");
    expect(stageOfMessage("Downloading from YouTube… 45%")).toBe("download");
    expect(stageOfMessage("Queued → starting…")).toBeNull();
  });

  it("marks what is done, what runs now and what comes next", () => {
    const j = running([
      "Queued → starting…",
      "Extracting audio (ffmpeg → 16 kHz mono WAV)…",
      "Separating dialogue from music and effects (htdemucs)…",
      "Transcribing with faster-whisper small…",
      "Segment 3/10: phones + alignment…",
    ]);
    expect(statuses(j)).toEqual({
      extract: "done",
      separate: "done",
      transcribe: "done",
      phones: "current",
      save: "pending",
    });
    expect(currentStage(j)?.detail).toBe("phrase 3 of 10");
    expect(stageSummary(j)).toBe("Hear the phones · phrase 3 of 10");
  });

  it("adds a download step for YouTube jobs and shows its percentage", () => {
    const j = running(["Looking up the URL…", "Downloading from YouTube… 45%"], {
      source_url: "https://youtu.be/abc",
    });
    expect(jobStages(j)[0]).toMatchObject({ key: "download", status: "current", detail: "45 %" });
    expect(statuses(j).extract).toBe("pending");
  });

  it("says separation was skipped, and why", () => {
    const off = running(["Extracting audio…"], { options: { ...job.options, separate_dialogue: false } });
    expect(jobStages(off).find((s) => s.key === "separate")).toMatchObject({
      status: "skipped",
      detail: "turned off for this analysis",
    });

    const unavailable = running([
      "Extracting audio…",
      "Separating dialogue from music and effects (htdemucs)…",
      "Dialogue separation skipped (offline); analyzing the original mix.",
      "Transcribing with faster-whisper small…",
    ]);
    expect(statuses(unavailable)).toMatchObject({ separate: "skipped", transcribe: "current" });
    expect(jobStages(unavailable).find((s) => s.key === "separate")?.detail).toMatch(/original mix/);
  });

  it("a queued job has every step still ahead", () => {
    const queued: Job = { ...running([]), status: "queued", percent: 0 };
    expect(Object.values(statuses(queued)).every((s) => s === "pending")).toBe(true);
    expect(stageSummary(queued)).toBe("Waiting in line");
  });

  it("a failed job marks the step it failed in", () => {
    const failed = running(["Extracting audio…", "Transcribing with faster-whisper small…"], {
      status: "error",
      error: "out of memory",
    });
    expect(statuses(failed)).toMatchObject({ extract: "done", transcribe: "failed", phones: "pending" });
  });

  it("a finished job has every step done, skipped ones still skipped", () => {
    const done: Job = { ...job, options: { ...job.options, separate_dialogue: false } };
    expect(statuses(done)).toEqual({
      extract: "done",
      separate: "skipped",
      transcribe: "done",
      phones: "done",
      save: "done",
    });
    expect(stageSummary(done)).toBe("Ready");
  });
});
