/** What the library says about a job: status (icon + word, never a hue), engine,
 *  rules version, and the "continue where you left off" memory. */

import { CircleAlert, CircleCheck, CircleSlash, Clock, LoaderCircle, type LucideIcon } from "lucide-react";

import type { Job, JobStatus } from "../../types";

export const STATUS: Record<JobStatus, { label: string; icon: LucideIcon; spins?: boolean }> = {
  queued: { label: "Queued", icon: Clock },
  running: { label: "Analyzing", icon: LoaderCircle, spins: true },
  done: { label: "Ready", icon: CircleCheck },
  error: { label: "Error", icon: CircleAlert },
  cancelled: { label: "Cancelled", icon: CircleSlash },
};

export const isActive = (job: Job): boolean => job.status === "queued" || job.status === "running";

/** "timit61" → "TIMIT-61"; the old name "wav2vec2" was the espeak engine. */
export function engineLabel(engine: string | null | undefined): string {
  if (!engine) return "";
  if (engine === "timit61") return "TIMIT-61";
  if (engine === "espeak" || engine === "wav2vec2") return "espeak";
  return engine;
}

/** The engine that produced the results (meta), or the one requested (options). */
export function jobEngine(job: Job): string | null {
  const engine = job.meta?.phone_engine ?? job.options.phone_engine ?? null;
  return engine === "wav2vec2" ? "espeak" : engine;
}

/** Version of the labelling rules an analysis was made with (absent before v2). */
export function rulesVersion(job: Job): number | null {
  return job.meta?.rules_version ?? null;
}

/** Same key the pre-redesign workspace used, so the memory survives the redesign. */
export const LAST_JOB_KEY = "phonotrainer:last-job";

export function rememberJob(id: string): void {
  try {
    window.localStorage.setItem(LAST_JOB_KEY, id);
  } catch {
    /* storage blocked: the library simply will not offer to continue */
  }
}

export function lastJobId(): string | null {
  try {
    return window.localStorage.getItem(LAST_JOB_KEY);
  } catch {
    return null;
  }
}

/** "Jul 26, 03:40 PM" → "2 h ago" style when recent; the date otherwise. */
export function relativeTime(iso: string | null, now: number = Date.now()): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const seconds = Math.max(0, Math.round((now - then) / 1000));
  if (seconds < 60) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  if (days < 7) return days === 1 ? "yesterday" : `${days} days ago`;
  return new Date(iso).toLocaleDateString("en", { day: "numeric", month: "short", year: "numeric" });
}
