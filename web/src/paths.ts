/** URL builders: the only place that knows the route shapes (src/routes.tsx matches the
 *  same patterns). Use these instead of writing paths by hand. */

const enc = encodeURIComponent;

function withQuery(path: string, query: Record<string, string | null | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) if (value) params.set(key, value);
  const text = params.toString();
  return text ? `${path}?${text}` : path;
}

export interface InsightsQuery {
  phenomenon?: string | null;
  word?: string | null;
  practice?: "produce" | "understand" | null;
}

export const paths = {
  library: () => "/",
  newAnalysis: () => "/new",
  analysis: (jobId: string) => `/analysis/${enc(jobId)}`,
  /** Deep link to one word's lesson. `from` remembers where the learner came from. */
  word: (jobId: string, segment: number, index: number, from?: "insights" | "learn" | "practice") =>
    withQuery(`/analysis/${enc(jobId)}/w/${segment}/${index}`, { from }),
  learn: () => "/learn",
  phenomenon: (name: string) => `/learn/${enc(name)}`,
  ipa: (symbol?: string) => withQuery("/learn/ipa", { symbol }),
  /** `focus` restricts a session to one phenomenon ("Practice this" on a Learn page). */
  practice: (focus?: string | null) => withQuery("/practice", { focus }),
  insights: (query: InsightsQuery = {}) =>
    withQuery("/insights", {
      phenomenon: query.phenomenon,
      word: query.word,
      practice: query.practice,
    }),
  settings: () => "/settings",
} as const;

/** Route patterns, in matching order (wouter syntax). */
export const ROUTES = {
  library: "/",
  newAnalysis: "/new",
  analysis: "/analysis/:id",
  word: "/analysis/:id/w/:segment/:index",
  learn: "/learn",
  ipa: "/learn/ipa",
  phenomenon: "/learn/:phenomenon",
  practice: "/practice",
  insights: "/insights",
  settings: "/settings",
} as const;
