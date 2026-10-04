/** An analysis in flight (or one that failed) inside the workspace route: the pipeline
 *  steps, cancel/retry and the live log. The job arrives over the WebSocket channel,
 *  so every change shows up on its own, and the workspace opens by itself when it is
 *  done. The steps themselves are the library's stepper (read-only here). */

import { Ban, CircleX, LoaderCircle, RotateCcw, X } from "lucide-react";
import { useState } from "react";

import { api } from "../../../api";
import { useJob } from "../../../hooks/useJobs";
import { fmtDate } from "../../../lib/format";
import { paths } from "../../../paths";
import type { Job } from "../../../types";
import { Button, ButtonRow, Disclosure, JobStepper, LinkButton, Notice, PageHeader } from "../../../ui";

export function JobProgressView({ job: initial }: { job: Job }) {
  const live = useJob(initial.id);
  const job = live ?? initial;
  const [retrying, setRetrying] = useState(false);
  const running = job.status === "running" || job.status === "queued";

  /** Retry a failed download without retyping the URL; the failed job is removed so the
   *  library is not left with two identical rows. */
  const retry = async () => {
    if (!job.source_url) return;
    setRetrying(true);
    try {
      await api.createFromUrl(job.source_url, job.options, false);
      await api.deleteJob(job.id).catch(() => undefined);
    } finally {
      setRetrying(false);
    }
  };

  const options = [
    job.options.phone_engine && `engine ${job.options.phone_engine === "wav2vec2" ? "espeak" : job.options.phone_engine}`,
    job.options.whisper_model && `speech model ${job.options.whisper_model}`,
    job.options.separate_dialogue === false && "no dialogue separation",
  ].filter(Boolean);

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-8">
      <PageHeader
        title={<span className="break-words">{job.source}</span>}
        lede={
          running
            ? "This clip is being analyzed. You can keep using the app; this page opens the analysis by itself when it is ready."
            : job.status === "error"
              ? "The analysis stopped with an error."
              : "The analysis was cancelled."
        }
        eyebrow={
          <p className="text-xs text-ink-2">
            Started {fmtDate(job.created)}
            {options.length > 0 && <> · {options.join(" · ")}</>}
          </p>
        }
      />

      <section aria-label="Progress" className="flex flex-col gap-4 rounded-card bg-surface p-4 shadow-1">
        {running && (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="inline-flex items-center gap-2 text-lg font-semibold text-ink">
              <LoaderCircle size={20} aria-hidden="true" className="motion-ok:animate-spin" />
              {job.status === "queued" ? "Queued…" : "Analyzing…"}
            </p>
            <Button size="sm" icon={X} onPress={() => void api.cancelJob(job.id)}>
              Cancel
            </Button>
          </div>
        )}
        <JobStepper job={job} />
        {running && (
          <p className="text-xs text-ink-muted">The first run can take a while: about 1.8 GB of models are loaded.</p>
        )}
      </section>

      {job.status === "error" && (
        <Notice
          tone="caution"
          title={
            <span className="inline-flex items-center gap-1.5">
              <CircleX size={16} aria-hidden="true" /> Error
            </span>
          }
        >
          <p className="text-ink">{job.error}</p>
          {job.source_url && !job.has_media ? (
            <div className="mt-2 flex flex-wrap items-center gap-3">
              <span>
                The download failed: check the link and your connection. Private, paid or age-restricted videos cannot be
                downloaded.
              </span>
              <Button size="sm" icon={RotateCcw} onPress={() => void retry()} isDisabled={retrying}>
                Retry
              </Button>
            </div>
          ) : (
            <p className="mt-2">
              Check that <code>ffmpeg</code> is installed (and <code>espeak-ng</code>, for the espeak engine) and that
              the models were downloaded (<code>scripts/download_models.py</code>).
            </p>
          )}
        </Notice>
      )}

      {job.status === "cancelled" && (
        <Notice
          title={
            <span className="inline-flex items-center gap-1.5">
              <Ban size={16} aria-hidden="true" /> Cancelled
            </span>
          }
        >
          This analysis was cancelled before it finished.
        </Notice>
      )}

      {!running && (
        <ButtonRow>
          <LinkButton href={paths.library()} variant="secondary">
            Back to your analyses
          </LinkButton>
          <LinkButton href={paths.newAnalysis()} variant="quiet">
            Start a new analysis
          </LinkButton>
        </ButtonRow>
      )}

      {job.progress && job.progress.length > 0 && (
        <Disclosure title="Log" defaultExpanded level={2}>
          {/* newest first: that is the line that matters, without scrolling */}
          <ol role="log" aria-label="Log, newest first" className="flex flex-col gap-1 font-mono text-xs text-ink-2">
            {[...job.progress].reverse().map((entry, index) => (
              <li key={`${entry.at}-${index}`}>{entry.message}</li>
            ))}
          </ol>
        </Disclosure>
      )}
    </div>
  );
}
