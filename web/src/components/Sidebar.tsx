/** Lista de análisis: estado en vivo, progreso y selección. */

import { api } from "../api";
import { fmtDate, fmtDuration } from "../lib/format";
import type { Job } from "../types";

const STATUS_LABEL: Record<Job["status"], string> = {
  queued: "en cola",
  running: "analizando",
  done: "listo",
  error: "error",
  cancelled: "cancelado",
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
    const what = job.imported ? "quitar de la lista" : "borrar los resultados de";
    if (!window.confirm(`¿Seguro que quieres ${what} “${job.source}”?`)) return;
    await api.deleteJob(job.id);
    onChanged();
  };

  return (
    <aside className="sidebar">
      <div className="sidebar__head">
        <h1 className="sidebar__title">PhonoTrainer</h1>
        <button type="button" className="btn btn--primary btn--sm" onClick={onNew}>
          + Analizar
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
            <span>todo lo analizado, junto</span>
          </span>
        </button>
      </div>

      <div className="sidebar__list">
        {jobs.length === 0 && (
          <p className="muted tiny" style={{ padding: "8px 10px" }}>
            Todavía no hay análisis. Empieza por “+ Analizar”.
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
              aria-label={`Borrar ${job.source}`}
              title="Borrar"
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
