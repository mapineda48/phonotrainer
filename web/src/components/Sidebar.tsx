/** Analysis list: live status, progress and selection. */

import { api } from "../api";
import { fmtDate, fmtDuration } from "../lib/format";
import type { Job } from "../types";

const STATUS_LABEL: Record<Job["status"], string> = {
  queued: "queued",
  running: "analyzing",
  done: "ready",
  error: "error",
  cancelled: "cancelled",
};

interface Props {
  jobs: Job[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onCorpus: () => void;
  corpusOpen: boolean;
  onChanged: () => void;
}

export function Sidebar({ jobs, selectedId, onSelect, onNew, onCorpus, corpusOpen,
                          onChanged }: Props) {
  const remove = async (job: Job) => {
    const what = job.imported
      ? `remove “${job.source}” from the list`
      : `delete the results of “${job.source}”`;
    if (!window.confirm(`Are you sure you want to ${what}?`)) return;
    await api.deleteJob(job.id);
    onChanged();
  };

  return (
    <aside className="sidebar">
      <div className="sidebar__head">
        <h1 className="sidebar__title">PhonoTrainer</h1>
        <button type="button" className="btn btn--primary btn--sm" onClick={onNew}>
          + Analyze
        </button>
      </div>

      <div style={{ padding: "0 8px 8px" }}>
        <button
          type="button"
          className="job"
          aria-current={corpusOpen}
          onClick={onCorpus}
        >
          <span className="job__name">Corpus</span>
          <span className="job__meta">
            <span>everything analyzed, together</span>
          </span>
        </button>
      </div>

      <div className="sidebar__list">
        {jobs.length === 0 && (
          <p className="muted tiny" style={{ padding: "8px 10px" }}>
            No analyses yet. Start with “+ Analyze”.
          </p>
        )}
        {jobs.map((job) => (
          <div key={job.id} style={{ position: "relative" }}>
            <button
              type="button"
              className="job"
              aria-current={job.id === selectedId}
              onClick={() => onSelect(job.id)}
            >
              <span className="job__name" title={job.source}>
                {job.source}
              </span>
              <span className="job__meta">
                <span className={`status status--${job.status}`}>{STATUS_LABEL[job.status]}</span>
                <span aria-hidden="true">·</span>
                <span>{fmtDate(job.created)}</span>
                {job.summary?.duration != null && (
                  <>
                    <span aria-hidden="true">·</span>
                    <span>{fmtDuration(job.summary.duration)}</span>
                  </>
                )}
              </span>
              {(job.status === "running" || job.status === "queued") && (
                <div
                  className="progressbar"
                  role="progressbar"
                  aria-valuenow={job.percent}
                  aria-valuemin={0}
                  aria-valuemax={100}
                >
                  <div style={{ width: `${job.percent}%` }} />
                </div>
              )}
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              style={{ position: "absolute", top: 6, right: 4 }}
              aria-label={`Delete ${job.source}`}
              title="Delete"
              onClick={() => void remove(job)}
            >
              ✕
            </button>
          </div>
        ))}
      </div>
    </aside>
  );
}
