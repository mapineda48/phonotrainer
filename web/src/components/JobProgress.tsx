/** State of a running (or failed) analysis: progress bar, live log and cancel.
 *  The job arrives over the WebSocket channel, so cancelling or retrying shows
 *  up on its own. */

import { useState } from "react";

import { api } from "../api";
import { useJob } from "../hooks/useJobs";
import { fmtDate } from "../lib/format";
import type { Job } from "../types";

export function JobProgress({ job: initial }: { job: Job }) {
  const live = useJob(initial.id);
  const [retrying, setRetrying] = useState(false);
  const job = live ?? initial;
  const running = job.status === "running" || job.status === "queued";

  const cancel = async () => {
    await api.cancelJob(job.id);
  };

  /** Retry a failed download without having to retype the URL: the failed job
   *  is removed, so the list is not left with two identical rows. */
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

  return (
    <div className="scroll" style={{ padding: "24px 28px 60px" }}>
      <h2 style={{ margin: "0 0 2px", fontSize: 18 }}>{job.source}</h2>
      <p className="muted tiny" style={{ marginTop: 0 }}>
        Created {fmtDate(job.created)}
        {job.options.whisper_model && ` · whisper ${job.options.whisper_model}`}
        {job.options.phone_engine && ` · ${job.options.phone_engine}`}
        {job.options.attraction === false && " · no attraction"}
      </p>

      {running && (
        <div className="card">
          <div className="row">
            <strong>{job.status === "queued" ? "Queued…" : "Analyzing…"}</strong>
            <span className="spacer" />
            <span className="num dim">{job.percent}%</span>
            <button type="button" className="btn btn--sm" onClick={() => void cancel()}>
              Cancel
            </button>
          </div>
          <div
            className="progressbar"
            role="progressbar"
            aria-valuenow={job.percent}
            aria-valuemin={0}
            aria-valuemax={100}
          >
            <div style={{ width: `${job.percent}%` }} />
          </div>
          <p className="tiny dim" style={{ margin: "8px 0 0" }}>
            {job.last_message ?? "Preparing…"}
          </p>
          <p className="tiny muted" style={{ margin: "4px 0 0" }}>
            The first run can take a while: about 1.8 GB of models are loaded. You can keep using
            the interface in the meantime.
          </p>
        </div>
      )}

      {job.status === "error" && (
        <div className="card">
          <p className="error" style={{ margin: 0 }}>
            {job.error}
          </p>
          {job.source_url && !job.has_media ? (
            <div className="row" style={{ marginTop: 8 }}>
              <p className="tiny muted" style={{ margin: 0, flex: 1 }}>
                The download failed: check the URL and your connection. Private, paid or
                age-restricted videos cannot be downloaded.
              </p>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => void retry()}
                disabled={retrying}
              >
                Retry
              </button>
            </div>
          ) : (
            <p className="tiny muted" style={{ marginBottom: 0 }}>
              Check that <code>ffmpeg</code> is installed (and <code>espeak-ng</code>, for the
              espeak engine) and that the models were downloaded
              (<code>scripts/download_models.py</code>).
            </p>
          )}
        </div>
      )}

      {job.status === "cancelled" && (
        <div className="card">
          <p style={{ margin: 0 }}>Analysis cancelled.</p>
        </div>
      )}

      {job.progress && job.progress.length > 0 && (
        <div className="card">
          <strong className="tiny">Log</strong>
          <div className="log" style={{ marginTop: 8 }}>
            {/* Newest at the top: that is the line that matters, without scrolling. */}
            {[...job.progress].reverse().map((entry, index) => (
              <div key={`${entry.at}-${index}`}>{entry.message}</div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
