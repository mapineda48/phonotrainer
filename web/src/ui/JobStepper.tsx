/** The pipeline as numbered steps: what is done, what is happening now, what comes
 *  next — each with an icon AND a word, never a color alone. Stage changes are
 *  announced politely to screen readers (not every percent tick).
 *
 *  Shared by the "Analysis started" view (/new) and the analysis progress view
 *  (/analysis/:id while the job runs). The stage mapping lives in jobs/stages.ts. */

import { Check, Circle, CircleSlash, LoaderCircle, SkipForward, X, type LucideIcon } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { jobStages, stageSummary, type StageStatus } from "../jobs/stages";
import type { Job } from "../types";
import { cn } from "./cn";
import { ProgressBar } from "./Layout";

const STEP_STATUS: Record<StageStatus, { icon: LucideIcon; text: string }> = {
  done: { icon: Check, text: "done" },
  current: { icon: LoaderCircle, text: "in progress" },
  pending: { icon: Circle, text: "next" },
  skipped: { icon: SkipForward, text: "skipped" },
  failed: { icon: X, text: "failed" },
  cancelled: { icon: CircleSlash, text: "cancelled" },
};

/** What a screen reader hears when the job moves on. */
export function announcement(job: Job): string {
  if (job.status === "done") return "The analysis is ready.";
  if (job.status === "error") return `The analysis failed: ${job.error ?? "unknown error"}.`;
  if (job.status === "cancelled") return "The analysis was cancelled.";
  if (job.status === "queued") return "Waiting in line.";
  const now = stageSummary(job);
  return now ? `Now: ${now.split(" · ")[0]}.` : "";
}

interface Props {
  job: Job;
  className?: string;
}

export function JobStepper({ job, className }: Props) {
  const stages = jobStages(job);
  const message = announcement(job);
  const [spoken, setSpoken] = useState("");
  const previous = useRef<string | null>(null);

  useEffect(() => {
    // the first render is not news; every later change of stage or status is
    if (previous.current !== null && previous.current !== message) setSpoken(message);
    previous.current = message;
  }, [message]);

  return (
    <div className={cn("flex flex-col gap-4", className)}>
      {job.status === "running" && (
        <ProgressBar
          label="Overall progress"
          value={job.percent}
          valueText={`${job.percent} % · ${stageSummary(job)}`}
        />
      )}
      {job.status === "queued" && (
        <p className="text-sm text-ink-2">
          <span className="font-semibold text-ink">Waiting in line.</span> Analyses run one at a time; this one
          starts when the previous one finishes.
        </p>
      )}
      <ol className="flex flex-col gap-1" aria-label="Analysis steps">
        {stages.map((stage, index) => {
          const visual = STEP_STATUS[stage.status];
          const Icon = visual.icon;
          const isCurrent = stage.status === "current";
          return (
            <li
              key={stage.key}
              aria-current={isCurrent ? "step" : undefined}
              className={cn(
                "flex gap-3 rounded-card px-3 py-2",
                isCurrent && "bg-surface-2 shadow-[inset_3px_0_0_var(--ink)]",
              )}
            >
              <span
                aria-hidden="true"
                className={cn(
                  "mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full text-sm font-semibold",
                  stage.status === "done" && "bg-ink text-page",
                  stage.status === "current" && "bg-surface text-ink shadow-[inset_0_0_0_2px_var(--ink)]",
                  (stage.status === "pending" || stage.status === "skipped") &&
                    "bg-surface text-ink-muted shadow-[inset_0_0_0_1px_var(--line-strong)]",
                  (stage.status === "failed" || stage.status === "cancelled") &&
                    "bg-surface text-ink shadow-[inset_0_0_0_2px_var(--critical)]",
                )}
              >
                {stage.status === "pending" ? (
                  index + 1
                ) : (
                  <Icon size={16} className={cn(isCurrent && "motion-ok:animate-spin")} />
                )}
              </span>
              <span className="flex min-w-0 flex-col">
                <span className={cn("text-sm text-ink", isCurrent ? "font-semibold" : "font-medium")}>
                  {stage.label}
                  <span className="text-ink-muted"> — {visual.text}</span>
                  {stage.detail && <span className="text-ink-2"> · {stage.detail}</span>}
                </span>
                <span className="text-xs text-ink-muted">{stage.teaches}</span>
              </span>
            </li>
          );
        })}
      </ol>
      <div role="status" aria-live="polite" className="sr-only">
        {spoken}
      </div>
    </div>
  );
}
