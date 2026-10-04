/** Corpus data for the Insights page: the overview (stats, analyses, metrics) once, and
 *  the occurrences + word variants whenever the server-side filters change. */

import { useEffect, useRef, useState } from "react";

import { api } from "../../api";
import type { CorpusAnalysis, CorpusMetrics, CorpusStats, Occurrence, WordVariant } from "../../types";

export interface Overview {
  stats: CorpusStats | null;
  analyses: CorpusAnalysis[];
  /** null while loading or when unavailable: the rest of the page must not wait for it. */
  metrics: CorpusMetrics | null;
  error: string | null;
}

export function useOverview(): Overview {
  const [state, setState] = useState<Overview>({ stats: null, analyses: [], metrics: null, error: null });
  useEffect(() => {
    let alive = true;
    Promise.all([api.corpusStats(), api.corpusAnalyses()])
      .then(([stats, list]) => alive && setState((s) => ({ ...s, stats, analyses: list.items })))
      .catch((err: unknown) =>
        alive && setState((s) => ({ ...s, error: err instanceof Error ? err.message : String(err) })),
      );
    // Old analyses are measured on first ask: this may take a moment, and it may fail
    // without taking the rest of the page down.
    api
      .corpusMetrics()
      .then((metrics) => alive && setState((s) => ({ ...s, metrics })))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, []);
  return state;
}

export interface OccurrenceState {
  items: Occurrence[];
  total: number;
  variants: WordVariant[] | null;
  busy: boolean;
  error: string | null;
}

/** How many occurrences one request brings back (most divergent first). */
export const OCCURRENCE_LIMIT = 200;

export function useOccurrences(phenomenon: string | null, word: string): OccurrenceState {
  const [state, setState] = useState<OccurrenceState>({
    items: [], total: 0, variants: null, busy: true, error: null,
  });
  /** Responses to superseded requests are dropped: the newest always wins. */
  const request = useRef(0);

  useEffect(() => {
    const token = ++request.current;
    setState((s) => ({ ...s, busy: true, error: null }));
    const query = { phenomenon: phenomenon ?? undefined, word: word || undefined, limit: OCCURRENCE_LIMIT };
    Promise.all([api.corpusOccurrences(query), word ? api.corpusVariants(word) : Promise.resolve(null)])
      .then(([occurrences, variants]) => {
        if (token !== request.current) return;
        setState({
          items: occurrences.items,
          total: occurrences.total,
          variants: variants ? variants.variants : null,
          busy: false,
          error: null,
        });
      })
      .catch((err: unknown) => {
        if (token !== request.current) return;
        setState((s) => ({ ...s, busy: false, error: err instanceof Error ? err.message : String(err) }));
      });
  }, [phenomenon, word]);

  return state;
}

/** The phone engine an indexed analysis was made with. */
export function engineOf(entry: CorpusAnalysis): string {
  if (entry.metrics?.engine) return entry.metrics.engine;
  if (entry.phone_model) return entry.phone_model.toLowerCase().includes("timit") ? "timit61" : "espeak";
  return "espeak";
}

/** Short display names; the key stays visible next to it where precision matters. */
export const ENGINE_NAME: Record<string, string> = {
  timit61: "TIMIT-61",
  espeak: "espeak",
};

export const engineName = (engine: string): string => ENGINE_NAME[engine] ?? engine;
