/** Explorador de archivos del propio equipo (limitado a $HOME por el backend).
 *  Evita tener que copiar rutas a mano para analizar un episodio local. */

import { useCallback, useEffect, useState } from "react";

import { api } from "../api";
import { fmtBytes } from "../lib/format";
import type { Browse } from "../types";

interface Props {
  /** "media": elegir un video/audio · "dir": elegir un directorio con analysis.json */
  mode: "media" | "dir";
  onPick: (path: string) => void;
}

export function FileBrowser({ mode, onPick }: Props) {
  const [listing, setListing] = useState<Browse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (path?: string) => {
    try {
      setListing(await api.browse(path));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) return <p className="error">{error}</p>;
  if (!listing) return <p className="muted tiny">Cargando…</p>;

  return (
    <div>
      <div className="row tiny muted" style={{ marginBottom: 6 }}>
        <span className="ipa" style={{ wordBreak: "break-all" }}>
          {listing.path}
        </span>
      </div>
      <div className="browser">
        {listing.parent && (
          <button type="button" className="browser__row" onClick={() => void load(listing.parent!)}>
            <span aria-hidden="true">↑</span>
            <span>..</span>
          </button>
        )}
        {listing.dirs.map((dir) => (
          <div key={dir.path} className="browser__row">
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              style={{ flex: 1, textAlign: "left" }}
              onClick={() => void load(dir.path)}
            >
              📁 {dir.name}
            </button>
            {mode === "dir" && dir.has_analysis && (
              <button type="button" className="btn btn--sm" onClick={() => onPick(dir.path)}>
                Importar
              </button>
            )}
          </div>
        ))}
        {mode === "media" &&
          listing.files.map((file) => (
            <button
              key={file.path}
              type="button"
              className="browser__row"
              onClick={() => onPick(file.path)}
            >
              <span aria-hidden="true">🎬</span>
              <span style={{ flex: 1 }}>{file.name}</span>
              <span className="muted tiny num">{fmtBytes(file.size)}</span>
            </button>
          ))}
        {mode === "media" && listing.dirs.length === 0 && listing.files.length === 0 && (
          <p className="muted tiny" style={{ padding: "8px 10px" }}>
            Aquí no hay video ni audio.
          </p>
        )}
      </div>
    </div>
  );
}
