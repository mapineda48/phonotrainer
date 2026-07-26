/** El corpus: todo lo analizado, junto.
 *
 *  Un `analysis.json` contesta «qué pasa en este vídeo». Esta vista contesta lo
 *  que un análisis suelto no puede: cuántos flapping llevas oídos, en cuántos
 *  vídeos, y cómo se ha pronunciado realmente una palabra a lo largo de todos.
 *  Cada aparición abre su análisis en esa palabra exacta.
 */

import { useCallback, useEffect, useState } from "react";

import { api } from "../api";
import { fmtDuration, fmtTime } from "../lib/format";
import { familyColor, phenomenonDescription, phenomenonLabel, useReference } from "../reference";
import type { CorpusStats, Occurrence, WordVariant } from "../types";

interface Props {
  /** Abrir la aparición en su análisis, en esa palabra. */
  onOpen: (analysisId: string, selection: { segment: number; index: number }) => void;
}

export function CorpusView({ onOpen }: Props) {
  const reference = useReference();
  const [stats, setStats] = useState<CorpusStats | null>(null);
  const [phenomenon, setPhenomenon] = useState<string | null>(null);
  const [word, setWord] = useState("");
  const [items, setItems] = useState<Occurrence[]>([]);
  const [total, setTotal] = useState(0);
  const [variants, setVariants] = useState<WordVariant[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .corpusStats()
      .then(setStats)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  const load = useCallback(async (nextPhenomenon: string | null, nextWord: string) => {
    setBusy(true);
    setError(null);
    try {
      const query = { phenomenon: nextPhenomenon ?? undefined, word: nextWord || undefined };
      const [occurrences, variantes] = await Promise.all([
        api.corpusOccurrences({ ...query, limit: 200 }),
        nextWord ? api.corpusVariants(nextWord) : Promise.resolve(null),
      ]);
      setItems(occurrences.items);
      setTotal(occurrences.total);
      setVariants(variantes ? variantes.variants : null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    void load(phenomenon, "");
    // Solo al cambiar de fenómeno: la palabra se busca al enviar el formulario.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phenomenon]);

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

  return (
    <div className="scroll" style={{ padding: "20px 24px 60px" }}>
      <h2 style={{ margin: "0 0 2px", fontSize: 19 }}>Corpus</h2>
      <p className="muted tiny" style={{ marginTop: 0 }}>
        {stats.analyses} análisis · {stats.words} palabras ·{" "}
        {fmtDuration(stats.duration)} de habla
      </p>

      <div className="card">
        <strong className="tiny">Fenómenos en todo el corpus</strong>
        <div className="bars" style={{ marginTop: 8 }}>
          {stats.phenomena.map((row) => {
            const family = reference.family_of[row.phenomenon];
            const on = phenomenon === row.phenomenon;
            const max = stats.phenomena[0]?.count || 1;
            return (
              <button
                key={row.phenomenon}
                type="button"
                className="bars__row"
                aria-pressed={on}
                title={phenomenonDescription(reference, row.phenomenon)}
                onClick={() => setPhenomenon(on ? null : row.phenomenon)}
              >
                <span style={{ fontWeight: on ? 650 : 400 }}>
                  {phenomenonLabel(reference, row.phenomenon)}
                </span>
                <span
                  className="bar"
                  style={{
                    width: `${Math.max(2, (row.count / max) * 100)}%`,
                    background: family ? familyColor(family) : "var(--ink-muted)",
                    opacity: !phenomenon || on ? 1 : 0.35,
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
            void load(phenomenon, word.trim());
          }}
        >
          <input
            type="search"
            className="input"
            style={{ flex: 1, minWidth: 160 }}
            aria-label="Buscar una palabra en todo el corpus"
            placeholder="¿Cómo se ha pronunciado…? (p. ej. to)"
            value={word}
            onChange={(event) => setWord(event.target.value)}
          />
          <button type="submit" className="btn" disabled={busy}>
            Buscar
          </button>
          {(word || phenomenon) && (
            <button
              type="button"
              className="btn btn--sm"
              onClick={() => {
                setWord("");
                setPhenomenon(null);
                void load(null, "");
              }}
            >
              Limpiar
            </button>
          )}
        </form>

        {variants && variants.length > 0 && (
          <table className="detail" style={{ marginTop: 10 }}>
            <caption className="tiny muted" style={{ captionSide: "top", textAlign: "left" }}>
              Formas realmente pronunciadas de «{word}»
              {variants[0].dict_ipa && <> · diccionario /{variants[0].dict_ipa}/</>}
            </caption>
            <tbody>
              {variants.map((variant) => (
                <tr key={variant.realized_ipa ?? "∅"}>
                  <td className="ipa">[{variant.realized_ipa || "∅"}]</td>
                  <td className="num">{variant.count} veces</td>
                  <td className="num muted">en {variant.analyses} análisis</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <strong className="tiny">
          {total} apariciones
          {phenomenon && <> de «{phenomenonLabel(reference, phenomenon)}»</>}
          {word && <> de «{word}»</>}
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
              <th>real</th>
              <th>t</th>
              <th>análisis</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr
                key={`${item.analysis_id}:${item.segment}:${item.word_idx}`}
                className="corpus__row"
                onClick={() =>
                  onOpen(item.analysis_id, { segment: item.segment, index: item.word_idx })
                }
              >
                <td>
                  <strong>{item.word}</strong>
                  {item.phenomena.length > 0 && (
                    <div className="tiny muted">
                      {item.phenomena.map((p) => phenomenonLabel(reference, p)).join(", ")}
                    </div>
                  )}
                </td>
                <td className="ipa">/{item.dict_ipa}/</td>
                <td className="ipa">[{item.realized_ipa || "∅"}]</td>
                <td className="num">{fmtTime(item.start)}</td>
                <td className="tiny corpus__source" title={item.analysis_source}>
                  {item.analysis_source}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {items.length === 0 && !busy && (
          <p className="muted tiny">Sin apariciones para esa búsqueda.</p>
        )}
      </div>
    </div>
  );
}
