/** Starting screen: pick the material and the pipeline options.
 *
 *  Three routes, in the order they actually get used: drag a file in, pick one
 *  from this machine by path, or import an `out/` the CLI already produced.
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
  // Models, engines and defaults are published by the backend: if the pipeline
  // supports another model tomorrow, it shows up here without touching the
  // frontend.
  const reference = useReference();
  const [options, setOptions] = useState<JobOptions>(reference.options.defaults);
  const [path, setPath] = useState("");
  const [url, setUrl] = useState("");
  const [audioOnly, setAudioOnly] = useState(false);
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
      <h2 style={{ margin: "0 0 4px", fontSize: 19 }}>Analyze native speech</h2>
      <p className="muted tiny" style={{ marginTop: 0 }}>
        An English video or audio file → transcript, phones actually pronounced against the
        canonical ones, <em>connected speech</em> phenomena and prosody.
      </p>

      {error && <p className="error">{error}</p>}

      <div className="card">
        <strong>1 · Material</strong>

        <div className="row" style={{ marginTop: 10 }}>
          <input
            type="url"
            className="input"
            style={{ flex: 1, minWidth: 200 }}
            aria-label="YouTube URL"
            placeholder="Paste a YouTube URL…"
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && url.trim()) {
                void run(() => api.createFromUrl(url.trim(), options, audioOnly));
              }
            }}
          />
          <button
            type="button"
            className="btn btn--primary"
            disabled={!url.trim() || busy}
            onClick={() => void run(() => api.createFromUrl(url.trim(), options, audioOnly))}
          >
            Download and analyze
          </button>
        </div>
        <label className="row tiny" style={{ marginTop: 6, gap: 6 }}>
          <input
            type="checkbox"
            checked={audioOnly}
            onChange={(event) => setAudioOnly(event.target.checked)}
          />
          audio only when downloading
          <span className="muted">— faster; no video to watch alongside the transcript</span>
        </label>

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
          <p style={{ margin: "0 0 8px" }}>Drag a video or audio file here</p>
          <label className="btn btn--sm">
            or choose a file
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
            placeholder="…or a local path: ~/videos/episode.webm"
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
            Browse…
          </button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={!path.trim() || busy}
            onClick={() => void run(() => api.createJob(path.trim(), options))}
          >
            Analyze
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
        <strong>2 · Options</strong>
        <div className="row" style={{ marginTop: 10, alignItems: "flex-end" }}>
          <label className="field">
            Whisper model
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
            Phone engine
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
            Language
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
          Phonetic attraction toward the canonical form
          <span className="muted">
            — turn it off to compare the recognizer's raw output
          </span>
        </label>
      </div>

      <div className="card">
        <strong>3 · Already have results?</strong>
        <p className="tiny muted" style={{ margin: "6px 0 10px" }}>
          Import a directory containing <code>analysis.json</code> (for instance the{" "}
          <code>out/</code> that <code>phonotrainer analyze</code> left behind) and explore it here
          without analyzing again.
        </p>
        <button
          type="button"
          className="btn"
          onClick={() => setBrowsing(browsing === "dir" ? null : "dir")}
          aria-pressed={browsing === "dir"}
        >
          Import results…
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
