/** The corpus: everything analyzed so far, together.
 *
 *  An `analysis.json` answers "what happens in this video". This view answers
 *  what a single analysis cannot: how many flaps you have heard, across how
 *  many videos, and how a word has actually been pronounced across all of them.
 *  Every occurrence opens its analysis on that exact word.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "../api";
import { fmtDuration, fmtTime, plural } from "../lib/format";
import { familyColor, phenomenonDescription, phenomenonLabel, useReference } from "../reference";
import type {
  CorpusAnalysis,
  CorpusMetrics,
  CorpusStats,
  Occurrence,
  WordVariant,
} from "../types";
import { CorpusMetricsCard } from "./CorpusMetricsCard";

export interface CorpusFilters {
  phenomenon: string | null;
  word: string;
}

interface Props {
  /** Open the occurrence in its analysis, on that word. */
  onOpen: (jobId: string, selection: { segment: number; index: number }) => void;
  /** Filters preserved across a round trip into an analysis and back. */
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
  const [metrics, setMetrics] = useState<CorpusMetrics | null>(null);
  /** Discards responses from requests that have already been superseded. */
  const request = useRef(0);

  useEffect(() => {
    Promise.all([api.corpusStats(), api.corpusAnalyses()])
      .then(([summary, list]) => {
        setStats(summary);
        setAnalyses(list.items);
      })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
    // The metrics may take a moment (old analyses are measured on first ask):
    // they must not hold the rest of the view back, nor break it.
    api
      .corpusMetrics()
      .then(setMetrics)
      .catch(() => setMetrics(null));
  }, []);

  const load = useCallback(async (phenomenon: string | null, word: string) => {
    const token = ++request.current;
    setBusy(true);
    setError(null);
    try {
      const query = { phenomenon: phenomenon ?? undefined, word: word || undefined };
      const [occurrences, wordVariants] = await Promise.all([
        api.corpusOccurrences({ ...query, limit: 200 }),
        word ? api.corpusVariants(word) : Promise.resolve(null),
      ]);
      if (token !== request.current) return;      // arrived late: the newest wins
      setItems(occurrences.items);
      setTotal(occurrences.total);
      setVariants(wordVariants ? wordVariants.variants : null);
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
  if (!stats) return <div className="empty">Loading the corpus…</div>;

  if (stats.analyses === 0) {
    return (
      <div className="empty">
        <p>The corpus is empty.</p>
        <p className="tiny muted">
          Every analysis you finish is added here, and you will be able to compare them.
        </p>
      </div>
    );
  }

  const visibleItems = variantFilter
    ? items.filter((item) => (item.realized_ipa || "") === variantFilter)
    : items;
  const hasDuplicates = analyses.some((entry) => entry.duplicate_source);

  return (
    <div className="scroll" style={{ padding: "20px 24px 60px" }}>
      <h2 style={{ margin: "0 0 2px", fontSize: 19 }}>Corpus</h2>
      <p className="muted tiny" style={{ marginTop: 0 }}>
        {stats.analyses} {stats.analyses === 1 ? "analysis" : "analyses"} of {stats.materials}{" "}
        {stats.materials === 1 ? "recording" : "recordings"} · {stats.words} words ·{" "}
        {fmtDuration(stats.duration)} of speech ·{" "}
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          aria-expanded={showAnalyses}
          onClick={() => setShowAnalyses((value) => !value)}
        >
          {showAnalyses ? "hide" : "see what it is made of"}
        </button>
      </p>
      <p className="tiny muted" style={{ margin: "0 0 6px" }}>
        Index derived from your analyses (<code>data/phonotrainer.db</code>): you can delete it
        and it rebuilds itself.
        {hasDuplicates && (
          <>
            {" "}
            <strong>Careful:</strong> the same recording has been analyzed more than once (e.g.
            with and without attraction, or a clip of it); those occurrences and the counts above
            each count it more than once.
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
                  <td className="num muted">{entry.words} words</td>
                  <td className="tiny muted">
                    {engineOf(entry)}
                    {entry.attraction ? "" : " · no attraction"}
                    {entry.duplicate_source ? " · repeated material" : ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {metrics && <CorpusMetricsCard metrics={metrics} />}

      <div className="card">
        <strong className="tiny">Phenomena across the whole corpus</strong>
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
                  <span className="muted">
                    {" · "}
                    {row.analyses} {row.analyses === 1 ? "analysis" : "analyses"}
                  </span>
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
            aria-label="Search for a word across the whole corpus"
            placeholder="How has it been pronounced…? (a single word: to, that…)"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          <button type="submit" className="btn" disabled={busy}>
            Search
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
              Clear
            </button>
          )}
        </form>

        {!filters.word && stats.top_words.length > 0 && (
          <div className="chips" style={{ marginTop: 10 }}>
            <span className="tiny muted">The ones accumulating the most phenomena:</span>
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
                {entry.word} <span className="muted">· {entry.count} phenomena</span>
              </button>
            ))}
          </div>
        )}

        {variants && variants.length > 0 && (
          <table className="detail" style={{ marginTop: 10 }}>
            <caption className="tiny muted" style={{ captionSide: "top", textAlign: "left" }}>
              Forms actually pronounced for “{filters.word}”
              {variants[0].dict_ipa && <> · dictionary /{variants[0].dict_ipa}/</>}
              {" — click one to keep only those occurrences"}
            </caption>
            <tbody>
              {variants.map((variant) => {
                const key = variant.realized_ipa || "";
                const on = variantFilter === key;
                const toggle = () => setVariantFilter(on ? null : key);
                return (
                  <tr
                    key={key || "∅"}
                    className="corpus__row"
                    role="button"
                    tabIndex={0}
                    aria-pressed={on}
                    aria-label={`keep only the ones pronounced ${
                      variant.realized_ipa || "with no phones"}, ${variant.count} ${
                      variant.count === 1 ? "time" : "times"}`}
                    style={on ? { fontWeight: 600 } : undefined}
                    onClick={toggle}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        toggle();
                      }
                    }}
                  >
                    <td className="ipa">
                      {variant.realized_ipa ? `[${variant.realized_ipa}]`
                                            : <span className="muted">(no phones)</span>}
                    </td>
                    <td className="num">
                      {variant.count} {variant.count === 1 ? "time" : "times"}
                    </td>
                    <td className="num muted">
                      in {variant.analyses} {variant.analyses === 1 ? "analysis" : "analyses"}
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
          {plural(variantFilter !== null ? visibleItems.length : total, "occurrence")}
          {filters.phenomenon && (
            <> of “{phenomenonLabel(reference, filters.phenomenon)}”</>
          )}
          {filters.word && <> of “{filters.word}”</>}
          {variantFilter !== null && (
            <> pronounced {variantFilter ? `[${variantFilter}]` : "with no phones"}</>
          )}
        </strong>
        <p className="tiny muted" style={{ margin: "2px 0 8px" }}>
          Sorted by divergence: those furthest from the canonical form first, and at the end the
          ones lasting one or two frames (⏱), which are almost always alignment failures. Click one
          to open it in its analysis.
          {variantFilter === null && total > items.length && (
            <> Showing the first {items.length}.</>
          )}
        </p>
        <p className="tiny muted" style={{ margin: "0 0 8px" }}>
          <strong>dict.</strong> = citation form · <strong>canonical</strong> = what the aligner
          expected (the dictionary form with timit61; with espeak, a form that already applies
          native processes) · <strong>actual</strong> = what was recognized.
        </p>
        <table className="detail">
          <thead>
            <tr>
              <th>word</th>
              <th>dict.</th>
              <th>canonical</th>
              <th>actual</th>
              <th>t</th>
              <th>analysis</th>
            </tr>
          </thead>
          <tbody>
            {visibleItems.map((item) => {
              const label = `open “${item.word}” in ${item.analysis_source} at ${fmtTime(item.start)}`;
              const open = () =>
                item.job_id && onOpen(item.job_id, { segment: item.segment, index: item.word_idx });
              return (
                <tr
                  key={`${item.analysis_id}:${item.segment}:${item.word_idx}`}
                  className="corpus__row"
                  role="button"
                  tabIndex={0}
                  aria-label={label}
                  title={item.job_id ? label : "analysis indexed from the CLI: it is not open in the interface"}
                  onClick={open}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      open();
                    }
                  }}
                >
                  <td>
                    <strong>{item.word}</strong>
                    {/* With linking or palatalization the phenomenon happens
                        BETWEEN two words: listing only the first strips the
                        context away. */}
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
                  <td className="ipa">
                    {item.realized_ipa ? `[${item.realized_ipa}]`
                                       : <span className="muted">(no phones)</span>}
                    {(item.low_confidence || item.oov) && (
                      <span className="muted" title={item.low_confidence
                        ? "low confidence: this may be silence or noise"
                        : "out of dictionary"}> ⚠</span>
                    )}
                    {item.too_short && (
                      <span className="muted" title={
                        "shorter than 60 ms: probably an alignment failure, "
                        + "not a phenomenon"}> ⏱</span>
                    )}
                  </td>
                  <td className="num">{fmtTime(item.start)}</td>
                  <td className="tiny">
                    <div className="corpus__source" title={item.analysis_source}>
                      {item.analysis_source}
                    </div>
                    {/* Two passes over the same audio with different settings are
                        not native variation: they must be tellable apart. */}
                    {!item.analysis_attraction && <div className="muted">no attraction</div>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {visibleItems.length === 0 && !busy && (
          <p className="muted tiny">
            No occurrences for that search. Search works on a single word.
          </p>
        )}
      </div>
    </div>
  );
}

/** Phenomena that occur between two words. */
const BOUNDARY = new Set(["linking", "palatalization", "h_dropping"]);

/** The phone engine an indexed analysis was made with. */
function engineOf(entry: CorpusAnalysis): string {
  if (entry.metrics?.engine) return entry.metrics.engine;
  if (entry.phone_model) return entry.phone_model.toLowerCase().includes("timit") ? "timit61" : "espeak";
  return "espeak";
}
