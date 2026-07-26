/** Pantalla de arranque: elegir material y opciones del pipeline.
 *
 *  Tres vías, en el orden en que se usan de verdad: arrastrar un archivo,
 *  elegir uno del equipo por ruta, o importar un `out/` que ya generó la CLI.
 */

import { useState } from "react";

import { api } from "../api";
import { useReference } from "../reference";
import type { Job, JobOptions } from "../types";
import { FileBrowser } from "./FileBrowser";

interface Props {
  onCreated: (job: Job) => void;
}

export function NewAnalysis({ onCreated }: Props) {
  // Modelos, motores y valores por defecto los publica el backend: si mañana
  // el pipeline admite otro modelo, aparece aquí sin tocar el frontend.
  const reference = useReference();
  const [options, setOptions] = useState<JobOptions>(reference.options.defaults);
  const [path, setPath] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [browsing, setBrowsing] = useState<"media" | "dir" | null>(null);

  const set = <K extends keyof JobOptions>(key: K, value: JobOptions[K]) =>
    setOptions((current) => ({ ...current, [key]: value }));

  const run = async (action: () => Promise<Job>) => {
    setBusy(true);
    setError(null);
    try {
      onCreated(await action());
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  const onDrop = (event: React.DragEvent) => {
    event.preventDefault();
    setDragging(false);
    const file = event.dataTransfer.files[0];
    if (file) void run(() => api.uploadJob(file, options));
  };

  return (
    <div className="scroll" style={{ padding: "24px 28px 60px" }}>
      <h2 style={{ margin: "0 0 4px", fontSize: 19 }}>Analizar habla nativa</h2>
      <p className="muted tiny" style={{ marginTop: 0 }}>
        Un video o audio en inglés → transcripción, fonos realmente pronunciados frente a los
        canónicos, fenómenos de <em>connected speech</em> y prosodia.
      </p>

      {error && <p className="error">{error}</p>}

      <div className="card">
        <strong>1 · Material</strong>

        <div
          className={`dropzone ${dragging ? "dropzone--over" : ""}`}
          style={{ marginTop: 10 }}
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
        >
          <p style={{ margin: "0 0 8px" }}>Arrastra aquí un video o audio</p>
          <label className="btn btn--sm">
            o elige un archivo
            <input
              type="file"
              accept="video/*,audio/*"
              className="sr-only"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) void run(() => api.uploadJob(file, options));
              }}
            />
          </label>
        </div>

        <div className="row" style={{ marginTop: 14 }}>
          <input
            type="text"
            className="input"
            style={{ flex: 1, minWidth: 180 }}
            placeholder="…o una ruta local: ~/videos/episodio.webm"
            value={path}
            onChange={(event) => setPath(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && path.trim()) {
                void run(() => api.createJob(path.trim(), options));
              }
            }}
          />
          <button
            type="button"
            className="btn"
            onClick={() => setBrowsing(browsing === "media" ? null : "media")}
            aria-pressed={browsing === "media"}
          >
            Explorar…
          </button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={!path.trim() || busy}
            onClick={() => void run(() => api.createJob(path.trim(), options))}
          >
            Analizar
          </button>
        </div>

        {browsing === "media" && (
          <div style={{ marginTop: 10 }}>
            <FileBrowser
              mode="media"
              onPick={(picked) => {
                setPath(picked);
                setBrowsing(null);
              }}
            />
          </div>
        )}
      </div>

      <div className="card">
        <strong>2 · Opciones</strong>
        <div className="row" style={{ marginTop: 10, alignItems: "flex-end" }}>
          <label className="field">
            Modelo de Whisper
            <select
              value={options.whisper_model}
              onChange={(event) =>
                set("whisper_model", event.target.value as JobOptions["whisper_model"])
              }
            >
              {reference.options.whisper_models.map((model) => (
                <option key={model} value={model}>
                  {model}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Motor de fonos
            <select
              value={options.phone_engine}
              onChange={(event) =>
                set("phone_engine", event.target.value as JobOptions["phone_engine"])
              }
            >
              {reference.options.phone_engines.map((engine) => (
                <option key={engine} value={engine}>
                  {engine}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Idioma
            <input
              type="text"
              size={4}
              value={options.language}
              onChange={(event) => set("language", event.target.value)}
            />
          </label>
        </div>
        <label className="row tiny" style={{ marginTop: 12, gap: 6 }}>
          <input
            type="checkbox"
            checked={options.attraction}
            onChange={(event) => set("attraction", event.target.checked)}
          />
          Atracción fonética hacia el canónico
          <span className="muted">
            — desactívala para comparar la salida cruda del reconocedor
          </span>
        </label>
      </div>

      <div className="card">
        <strong>3 · ¿Ya tienes resultados?</strong>
        <p className="tiny muted" style={{ margin: "6px 0 10px" }}>
          Importa un directorio con <code>analysis.json</code> (por ejemplo el <code>out/</code> que
          dejó <code>phonotrainer analyze</code>) y explóralo aquí sin volver a analizar.
        </p>
        <button
          type="button"
          className="btn"
          onClick={() => setBrowsing(browsing === "dir" ? null : "dir")}
          aria-pressed={browsing === "dir"}
        >
          Importar resultados…
        </button>
        {browsing === "dir" && (
          <div style={{ marginTop: 10 }}>
            <FileBrowser
              mode="dir"
              onPick={(picked) => {
                setBrowsing(null);
                void run(() => api.importJob(picked));
              }}
            />
          </div>
        )}
      </div>
    </div>
  );
}
