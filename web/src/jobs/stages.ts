/** The pipeline as a learner sees it: a few named stages instead of a log.
 *
 *  The backend publishes free-text progress messages (jobs.py `_STAGE_PERCENT`
 *  and pipeline.py); this module maps them onto stages, so the stepper can say
 *  "Separating the dialogue — done, now transcribing" without the server knowing
 *  anything about the interface. Pure functions: tested without rendering. */

import type { Job } from "../types";

export type StageKey = "download" | "extract" | "separate" | "transcribe" | "phones" | "save";

export type StageStatus = "done" | "current" | "pending" | "skipped" | "failed" | "cancelled";

export interface Stage {
  key: StageKey;
  label: string;
  /** One sentence: what happens here, for someone who has never seen a pipeline. */
  teaches: string;
  status: StageStatus;
  /** Extra detail for the current or skipped stage ("segment 3 of 10", "45 %"). */
  detail?: string;
}

const LABEL: Record<StageKey, string> = {
  download: "Download",
  extract: "Extract the audio",
  separate: "Separate the dialogue",
  transcribe: "Transcribe the words",
  phones: "Hear the phones",
  save: "Compare and save",
};

const TEACHES: Record<StageKey, string> = {
  download: "Fetching the video from YouTube.",
  extract: "Turning the file into a plain mono recording the models can read.",
  separate: "Removing music and effects so that only the voices remain.",
  transcribe: "Whisper writes down every word and when it is said.",
  phones: "The phone recognizer listens to each word and lines it up with its dictionary form.",
  save: "Labelling what changed in each word and saving the results.",
};

/** Which stage a progress message belongs to (by its prefix, as jobs.py does). */
const PREFIXES: [string, StageKey][] = [
  ["Looking up the URL", "download"],
  ["Downloading from YouTube", "download"],
  ["Download finished", "download"],
  ["Downloaded:", "download"],
  ["Extracting audio", "extract"],
  ["Separating dialogue", "separate"],
  ["Dialogue separation skipped", "separate"],
  ["Transcribing", "transcribe"],
  ["Loading phone engine", "phones"],
  ["Loading prosody", "phones"],
  ["Segment ", "phones"],
  ["Saving outputs", "save"],
  ["Generating report", "save"],
];

export function stageOfMessage(message: string | null | undefined): StageKey | null {
  if (!message) return null;
  for (const [prefix, key] of PREFIXES) if (message.startsWith(prefix)) return key;
  return null;
}

const SEGMENT_RE = /Segment (\d+)\/(\d+)/;
const DOWNLOAD_RE = /Downloading from YouTube… (\d+)%/;

function detailOf(key: StageKey, message: string | null | undefined): string | undefined {
  if (!message) return undefined;
  if (key === "phones") {
    const m = SEGMENT_RE.exec(message);
    if (m) return `phrase ${m[1]} of ${m[2]}`;
    if (message.startsWith("Loading")) return "loading the models";
  }
  if (key === "download") {
    const m = DOWNLOAD_RE.exec(message);
    if (m) return `${m[1]} %`;
  }
  return undefined;
}

function messages(job: Job): string[] {
  const log = (job.progress ?? []).map((entry) => entry.message);
  if (job.last_message && log[log.length - 1] !== job.last_message) log.push(job.last_message);
  return log;
}

/** The stages of this job, in order, each with its status. */
export function jobStages(job: Job): Stage[] {
  const keys: StageKey[] = [];
  if (job.source_url) keys.push("download");
  keys.push("extract", "separate", "transcribe", "phones", "save");

  const log = messages(job);
  const separationOff = job.options.separate_dialogue === false;
  const separationSkipped = log.some((m) => m.startsWith("Dialogue separation skipped"));

  // the furthest stage any message reached (messages only move forward)
  let reached = -1;
  let lastForReached: string | undefined;
  for (const message of log) {
    const key = stageOfMessage(message);
    if (!key) continue;
    const index = keys.indexOf(key);
    if (index >= reached) {
      reached = index;
      lastForReached = message;
    }
  }

  const finished = job.status === "done";
  return keys.map((key, index) => {
    const base = { key, label: LABEL[key], teaches: TEACHES[key] };
    if (key === "separate" && (separationOff || separationSkipped)) {
      return {
        ...base,
        status: "skipped" as const,
        detail: separationOff
          ? "turned off for this analysis"
          : "the separator was not available, so the original mix was analyzed",
      };
    }
    if (finished) return { ...base, status: "done" as const };
    if (job.status === "queued" || reached < 0) {
      // nothing has started yet (a queued job, or a job whose first message is still
      // "Queued → starting…"): the first stage is next, unless the job already failed
      if (index === 0 && (job.status === "error" || job.status === "cancelled")) {
        return { ...base, status: job.status === "error" ? ("failed" as const) : ("cancelled" as const) };
      }
      return { ...base, status: "pending" as const };
    }
    if (index < reached) return { ...base, status: "done" as const };
    if (index > reached) return { ...base, status: "pending" as const };
    if (job.status === "error") return { ...base, status: "failed" as const };
    if (job.status === "cancelled") return { ...base, status: "cancelled" as const };
    return { ...base, status: "current" as const, detail: detailOf(key, lastForReached) };
  });
}

/** The stage being worked on now (or the one that failed), for one-line summaries. */
export function currentStage(job: Job): Stage | null {
  return (
    jobStages(job).find((stage) =>
      stage.status === "current" || stage.status === "failed" || stage.status === "cancelled",
    ) ?? null
  );
}

/** "Separate the dialogue" → "Separating the dialogue" style one-liners for the library row. */
export function stageSummary(job: Job): string {
  if (job.status === "queued") return "Waiting in line";
  if (job.status === "done") return "Ready";
  const stage = currentStage(job);
  if (!stage) return job.status === "running" ? "Starting" : "";
  return stage.detail ? `${stage.label} · ${stage.detail}` : stage.label;
}
