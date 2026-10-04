/** The Insights filters live in the URL (/insights?phenomenon=&word=&practice=), so a
 *  filtered view can be bookmarked, and coming back from a word lesson restores it. */

import { useCallback, useMemo } from "react";
import { useLocation, useSearch } from "wouter";

import { paths, type InsightsQuery } from "../../paths";

export type PracticeFilter = "produce" | "understand";

export interface InsightsFilters {
  phenomenon: string | null;
  word: string;
  practice: PracticeFilter | null;
}

export function parseFilters(search: string): InsightsFilters {
  const params = new URLSearchParams(search);
  const practice = params.get("practice");
  return {
    phenomenon: params.get("phenomenon") || null,
    word: (params.get("word") ?? "").trim(),
    practice: practice === "produce" || practice === "understand" ? practice : null,
  };
}

export function useInsightsFilters(): [InsightsFilters, (patch: Partial<InsightsFilters>) => void] {
  const search = useSearch();
  const [, navigate] = useLocation();
  const filters = useMemo(() => parseFilters(search), [search]);
  const update = useCallback(
    (patch: Partial<InsightsFilters>) => {
      const next = { ...filters, ...patch };
      const query: InsightsQuery = {
        phenomenon: next.phenomenon,
        word: next.word || null,
        practice: next.practice,
      };
      // replace: a filter is a refinement of the same page, not a new step in history
      navigate(paths.insights(query), { replace: true });
    },
    [filters, navigate],
  );
  return [filters, update];
}
