/** El corpus: todo lo analizado, junto.
 *
 *  Un `analysis.json` contesta «qué pasa en este vídeo». Esta vista contesta lo
 *  que un análisis suelto no puede: cuántos flapping llevas oídos, en cuántos
 *  vídeos, y cómo se ha pronunciado realmente una palabra a lo largo de todos.
 *  Cada aparición abre su análisis en esa palabra exacta.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "../api";
import { fmtDuration, fmtTime } from "../lib/format";
import { familyColor, phenomenonDescription, phenomenonLabel, useReference } from "../reference";
import type { CorpusAnalysis, CorpusStats, Occurrence, WordVariant } from "../types";

export interface CorpusFilters {
  phenomenon: string | null;
  word: string;
}

interface Props {
  /** Abrir la aparición en su análisis, en esa palabra. */
  onOpen: (jobId: string, selection: { segment: number; index: number }) => void;
  /** Filtros conservados al ir y volver del análisis. */
  filters: CorpusFilters;
  onFilters: (filters: CorpusFilters) => void;
}

export function CorpusView({ onOpen, filters, onFilters }: Props) {
  const reference = useReference();
  const [stats, setStats] = useState<CorpusStats | null>(null);
  const [analyses, setAnalyses] = useState<CorpusAnalysis[]>([]);
  const [draft, setDraft] = useState(filters.word);
  const [items, setItems] = useState<Occurrence[]>([]);
  const [total, setTotal] = useState(0);
  const [variants, setVariants] = useState<WordVariant[] | null>(null);
  const [variantFilter, setVariantFilter] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [showAnalyses, setShowAnalyses] = useState(false);
  /** Descarta respuestas de peticiones ya superadas. */
  const request = useRef(0);

  useEffect(() => {
    Promise.all([api.corpusStats(), api.corpusAnalyses()])
      .then(([resumen, lista]) => {
        setStats(resumen);
        setAnalyses(lista.items);
      })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  const load = useCallback(async (phenomenon: string | null, word: string) => {
    const token = ++request.current;
    setBusy(true);
    setError(null);
    try {
      const query = { phenomenon: phenomenon ?? undefined, word: word || undefined };
      const [occurrences, variantes] = await Promise.all([
        api.corpusOccurrences({ ...query, limit: 200 }),
        word ? api.corpusVariants(word) : Promise.resolve(null),
      ]);
      if (token !== request.current) return;      // llegó tarde: manda la última
      setItems(occurrences.items);
      setTotal(occurrences.total);
      setVariants(variantes ? variantes.variants : null);
      setVariantFilter(null);
    } catch (err) {
      if (token === request.current) {
        setError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      if (token === request.current) setBusy(false);
    }
  }, []);

  useEffect(() => {
    void load(filters.phenomenon, filters.word);
  }, [load, filters.phenomenon, filters.word]);

  if (error) {
    return (
      <div className="empty">
        <p className="error">{error}</p>
      </div>
    );
  }
  if (!stats) return <div className="empty">Cargando el corpus…</div>;

  if (stats.analyses === 0) {
    return (
      <div className="empty">
        <p>El corpus está vacío.</p>
        <p className="tiny muted">
          Cada análisis que termines se añade aquí y podrás compararlos entre sí.
        </p>
      </div>
    );
  }

  const visibles = variantFilter
    ? items.filter((item) => (item.realized_ipa || "") === variantFilter)
    : items;
  const duplicados = analyses.some((entry) => entry.duplicate_source);

  return (
    <div className="scroll" style={{ padding: "20px 24px 60px" }}>
      <h2 style={{ margin: "0 0 2px", fontSize: 19 }}>Corpus</h2>
      <p className="muted tiny" style={{ marginTop: 0 }}>
        {stats.analyses} análisis de {stats.sources}{" "}
        {stats.sources === 1 ? "fuente" : "fuentes"} · {stats.words} palabras ·{" "}
        {fmtDuration(stats.duration)} de habla ·{" "}
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          aria-expanded={showAnalyses}
          onClick={() => setShowAnalyses((value) => !value)}
        >
          {showAnalyses ? "ocultar" : "ver de qué se compone"}
        </button>
      </p>
      <p className="tiny muted" style={{ margin: "0 0 6px" }}>
        Índice derivado de tus análisis (<code>data/phonotrainer.db</code>): se puede borrar y se
        reconstruye solo.
        {duplicados && (
          <>
            {" "}
            <strong>Ojo:</strong> hay material analizado más de una vez (p. ej. con y sin
            atracción); esas apariciones cuentan doble.
          </>
        )}
      </p>

      {showAnalyses && (
        <div className="card">
          <table className="detail" data-testid="corpus-analyses">
            <tbody>
              {analyses.map((entry) => (
                <tr key={entry.id}>
                  <td>{entry.source}</td>
                  <td className="num muted">{fmtDuration(entry.duration)}</td>
                  <td className="num muted">{entry.words} palabras</td>
                  <td className="tiny muted">
                    {entry.attraction ? "" : "sin atracción · "}
                    {entry.duplicate_source ? "material repetido" : ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <strong className="tiny">Fenómenos en todo el corpus</strong>
        <div className="bars" style={{ marginTop: 8 }}>
          {stats.phenomena.map((row) => {
            const family = reference.family_of[row.phenomenon];
            const on = filters.phenomenon === row.phenomenon;
            const max = stats.phenomena[0]?.count || 1;
            return (
              <button
                key={row.phenomenon}
                type="button"
                className="bars__row"
                aria-pressed={on}
                title={phenomenonDescription(reference, row.phenomenon)}
                onClick={() =>
                  onFilters({ ...filters, phenomenon: on ? null : row.phenomenon })
                }
              >
                <span style={{ fontWeight: on ? 650 : 400 }}>
                  {phenomenonLabel(reference, row.phenomenon)}
                </span>
                <span
                  className="bar"
                  style={{
                    width: `${Math.max(2, (row.count / max) * 100)}%`,
                    background: family ? familyColor(family) : "var(--ink-muted)",
                    opacity: !filters.phenomenon || on ? 1 : 0.35,
                  }}
                />
                <span className="bars__n">
                  {row.count}
                  <span className="muted"> · {row.analyses} an.</span>
                </span>
              </button>
            );
          })}
        </div>
      </div>

      <div className="card">
        <form
          className="row"
          onSubmit={(event) => {
            event.preventDefault();
            onFilters({ ...filters, word: draft.trim() });
          }}
        >
          <input
            type="search"
            className="input"
            style={{ flex: 1, minWidth: 160 }}
            aria-label="Buscar una palabra en todo el corpus"
            placeholder="¿Cómo se ha pronunciado…? (una palabra: to, that…)"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          <button type="submit" className="btn" disabled={busy}>
            Buscar
          </button>
          {(filters.word || filters.phenomenon) && (
            <button
              type="button"
              className="btn btn--sm"
              onClick={() => {
                setDraft("");
                onFilters({ phenomenon: null, word: "" });
              }}
            >
              Limpiar
            </button>
          )}
        </form>

        {!filters.word && stats.top_words.length > 0 && (
          <div className="chips" style={{ marginTop: 10 }}>
            <span className="tiny muted">Las que más fenómenos acumulan:</span>
            {stats.top_words.slice(0, 10).map((entry) => (
              <button
                key={entry.word}
                type="button"
                className="chip"
                onClick={() => {
                  setDraft(entry.word);
                  onFilters({ ...filters, word: entry.word });
                }}
              >
                {entry.word} <span className="muted">×{entry.count}</span>
              </button>
            ))}
          </div>
        )}

        {variants && variants.length > 0 && (
          <table className="detail" style={{ marginTop: 10 }}>
            <caption className="tiny muted" style={{ captionSide: "top", textAlign: "left" }}>
              Formas realmente pronunciadas de «{filters.word}»
              {variants[0].dict_ipa && <> · diccionario /{variants[0].dict_ipa}/</>}
              {" — pulsa una para quedarte solo con esas apariciones"}
            </caption>
            <tbody>
              {variants.map((variant) => {
                const key = variant.realized_ipa || "";
                const on = variantFilter === key;
                return (
                  <tr
                    key={key || "∅"}
                    className="corpus__row"
                    aria-pressed={on}
                    style={on ? { fontWeight: 600 } : undefined}
                    onClick={() => setVariantFilter(on ? null : key)}
                  >
                    <td className="ipa" title={key ? undefined : "sin fonos reconocidos"}>
                      [{variant.realized_ipa || "∅"}]
                    </td>
                    <td className="num">
                      {variant.count} {variant.count === 1 ? "vez" : "veces"}
                    </td>
                    <td className="num muted">
                      en {variant.analyses} {variant.analyses === 1 ? "análisis" : "análisis"}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <strong className="tiny">
          {total} apariciones
          {filters.phenomenon && (
            <> de «{phenomenonLabel(reference, filters.phenomenon)}»</>
          )}
          {filters.word && <> de «{filters.word}»</>}
          {variantFilter !== null && <> pronunciadas [{variantFilter || "∅"}]</>}
        </strong>
        <p className="tiny muted" style={{ margin: "2px 0 8px" }}>
          Ordenadas por divergencia: primero las que más se apartan del canónico. Pulsa una para
          abrirla en su análisis.
          {total > items.length && <> Se muestran las {items.length} primeras.</>}
        </p>
        <table className="detail">
          <thead>
            <tr>
              <th>palabra</th>
              <th>dicc.</th>
              <th>canónico</th>
              <th>real</th>
              <th>t</th>
              <th>análisis</th>
            </tr>
          </thead>
          <tbody>
            {visibles.map((item) => {
              const etiqueta = `abrir «${item.word}» en ${item.analysis_source} a ${fmtTime(item.start)}`;
              const abrir = () =>
                item.job_id && onOpen(item.job_id, { segment: item.segment, index: item.word_idx });
              return (
                <tr
                  key={`${item.analysis_id}:${item.segment}:${item.word_idx}`}
                  className="corpus__row"
                  role="button"
                  tabIndex={0}
                  aria-label={etiqueta}
                  title={item.job_id ? etiqueta : "análisis indexado desde la CLI: no está abierto en la interfaz"}
                  onClick={abrir}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      abrir();
                    }
                  }}
                >
                  <td>
                    <strong>{item.word}</strong>
                    {/* En linking o palatalización el fenómeno ocurre ENTRE dos
                        palabras: listar solo la primera lo descontextualiza. */}
                    {item.next_word && item.phenomena.some((p) => BOUNDARY.has(p)) && (
                      <span className="muted">‿{item.next_word}</span>
                    )}
                    {item.phenomena.length > 0 && (
                      <div className="tiny muted">
                        {item.phenomena.map((p) => phenomenonLabel(reference, p)).join(", ")}
                      </div>
                    )}
                  </td>
                  <td className="ipa">/{item.dict_ipa}/</td>
                  <td className="ipa muted">[{item.canonical_ipa}]</td>
                  <td className="ipa" title={item.realized_ipa ? undefined : "sin fonos reconocidos"}>
                    [{item.realized_ipa || "∅"}]
                    {(item.low_confidence || item.oov) && (
                      <span className="muted" title={item.low_confidence
                        ? "baja confianza: puede ser silencio o ruido"
                        : "fuera de diccionario"}> ⚠</span>
                    )}
                  </td>
                  <td className="num">{fmtTime(item.start)}</td>
                  <td className="tiny">
                    <div className="corpus__source" title={item.analysis_source}>
                      {item.analysis_source}
                    </div>
                    {/* Dos pasadas del mismo audio con distinta configuración no
                        son variación nativa: hay que poder distinguirlas. */}
                    {!item.analysis_attraction && <div className="muted">sin atracción</div>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {visibles.length === 0 && !busy && (
          <p className="muted tiny">
            Sin apariciones para esa búsqueda. La búsqueda es por palabra suelta.
          </p>
        )}
      </div>
    </div>
  );
}

/** Fenómenos que ocurren entre dos palabras. */
const BOUNDARY = new Set(["linking", "palatalization", "h_dropping"]);
