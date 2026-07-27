/** Estado de un análisis en curso (o fallido): barra, log en vivo y cancelar. */

import { useState } from "react";

import { api } from "../api";
import { useJob } from "../hooks/useJobs";
import { fmtDate } from "../lib/format";
import type { Job } from "../types";

interface Props {
  job: Job;
  onChanged: () => void;
}

export function JobProgress({ job: initial, onChanged }: Props) {
  const { job: live } = useJob(initial.id);
  const [retrying, setRetrying] = useState(false);
  const job = live ?? initial;
  const running = job.status === "running" || job.status === "queued";

  const cancel = async () => {
    await api.cancelJob(job.id);
    onChanged();
  };

  /** Reintentar una descarga fallida sin tener que reescribir la URL: el job
   *  fallido se va, para no dejar dos filas idénticas en la lista. */
  const retry = async () => {
    if (!job.source_url) return;
    setRetrying(true);
    try {
      await api.createFromUrl(job.source_url, job.options, false);
      await api.deleteJob(job.id).catch(() => undefined);
      onChanged();
    } finally {
      setRetrying(false);
    }
  };

  return (
    <div className="scroll" style={{ padding: "24px 28px 60px" }}>
      <h2 style={{ margin: "0 0 2px", fontSize: 18 }}>{job.source}</h2>
      <p className="muted tiny" style={{ marginTop: 0 }}>
        Creado {fmtDate(job.created)}
        {job.options.whisper_model && ` · whisper ${job.options.whisper_model}`}
        {job.options.phone_engine && ` · ${job.options.phone_engine}`}
        {job.options.attraction === false && " · sin atracción"}
      </p>

      {running && (
        <div className="card">
          <div className="row">
            <strong>{job.status === "queued" ? "En cola…" : "Analizando…"}</strong>
            <span className="spacer" />
            <span className="num dim">{job.percent}%</span>
            <button type="button" className="btn btn--sm" onClick={() => void cancel()}>
              Cancelar
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
            {job.last_message ?? "Preparando…"}
          </p>
          <p className="tiny muted" style={{ margin: "4px 0 0" }}>
            La primera vez puede tardar: se cargan ~1.8 GB de modelos. Puedes seguir usando la
            interfaz mientras tanto.
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
                Falló la descarga: comprueba la URL y la conexión. Los vídeos privados, de pago o
                con restricción de edad no se pueden bajar.
              </p>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => void retry()}
                disabled={retrying}
              >
                Reintentar
              </button>
            </div>
          ) : (
            <p className="tiny muted" style={{ marginBottom: 0 }}>
              Comprueba que <code>ffmpeg</code> y <code>espeak-ng</code> están instalados y que los
              modelos se descargaron (<code>scripts/download_models.py</code>).
            </p>
          )}
        </div>
      )}

      {job.status === "cancelled" && (
        <div className="card">
          <p style={{ margin: 0 }}>Análisis cancelado.</p>
        </div>
      )}

      {job.progress && job.progress.length > 0 && (
        <div className="card">
          <strong className="tiny">Registro</strong>
          <div className="log" style={{ marginTop: 8 }}>
            {job.progress.map((entry, index) => (
              <div key={`${entry.at}-${index}`}>{entry.message}</div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
