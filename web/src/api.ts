/** HTTP client for the local API. Same origin as the SPA; under `npm run dev`
 *  Vite proxies /api to the backend. The analysis list does NOT come through
 *  here: it is pushed over the WebSocket (`jobs/channel.ts`). */

import type {
  Analysis,
  Browse,
  CorpusAnalysis,
  CorpusMetrics,
  CorpusStats,
  Job,
  JobOptions,
  NativePitchContour,
  Occurrence,
  Reference,
  Review,
  SampleItem,
  TakeComparison,
  VerdictValue,
  WordVariant,
} from "./types";

export type AudioTrack = "mix" | "dialogue";

/** API version this UI requires (see `server.API_VERSION`). */
export const REQUIRED_API_VERSION = 5;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    /** Machine-readable reason, when the endpoint gives one ("no_voice", "too_long"…). */
    readonly code: string | null = null,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    let code: string | null = null;
    try {
      const body = await res.json();
      if (body?.detail) detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail);
      if (typeof body?.code === "string") code = body.code;
    } catch {
      /* response with no JSON body */
    }
    throw new ApiError(detail, res.status, code);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const jsonInit = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const api = {
  reference: () => request<Reference>("/api/reference"),

  createJob: (path: string, options: Partial<JobOptions>) =>
    request<Job>("/api/jobs", jsonInit("POST", { path, options })),
  uploadJob: (file: File, options: Partial<JobOptions>) => {
    const form = new FormData();
    form.append("file", file);
    form.append("options", JSON.stringify(options));
    return request<Job>("/api/jobs/upload", { method: "POST", body: form });
  },
  importJob: (path: string) => request<Job>("/api/jobs/import", jsonInit("POST", { path })),
  createFromUrl: (url: string, options: Partial<JobOptions>, audioOnly: boolean) =>
    request<Job>("/api/jobs/youtube",
      jsonInit("POST", { url, options, audio_only: audioOnly })),
  cancelJob: (id: string) => request<Job>(`/api/jobs/${id}/cancel`, { method: "POST" }),
  deleteJob: (id: string) => request<void>(`/api/jobs/${id}`, { method: "DELETE" }),

  analysis: (id: string) => request<Analysis>(`/api/jobs/${id}/analysis`),
  browse: (path?: string) =>
    request<Browse>(`/api/browse${path ? `?path=${encodeURIComponent(path)}` : ""}`),

  review: (id: string) => request<Review>(`/api/jobs/${id}/review`),
  reviewSample: (id: string, n: number, seed: number) =>
    request<{ seed: number; n: number; items: SampleItem[] }>(
      `/api/jobs/${id}/review/sample?n=${n}&seed=${seed}`,
    ),
  saveReview: (
    id: string,
    seed: number,
    verdicts: { segment: number; word_idx: number; verdict: VerdictValue; note: string }[],
  ) => request<Review>(`/api/jobs/${id}/review`, jsonInit("PUT", { seed, verdicts })),

  corpusStats: () => request<CorpusStats>("/api/corpus/stats"),
  corpusMetrics: () => request<CorpusMetrics>("/api/corpus/metrics"),
  corpusAnalyses: () => request<{ items: CorpusAnalysis[] }>("/api/corpus/analyses"),
  corpusOccurrences: (params: { phenomenon?: string; word?: string; limit?: number }) => {
    const query = new URLSearchParams();
    if (params.phenomenon) query.set("phenomenon", params.phenomenon);
    if (params.word) query.set("word", params.word);
    query.set("limit", String(params.limit ?? 100));
    return request<{
      phenomenon: string | null;
      word: string | null;
      total: number;
      items: Occurrence[];
    }>(`/api/corpus/occurrences?${query}`);
  },
  corpusVariants: (word: string) =>
    request<{ word: string; variants: WordVariant[] }>(
      `/api/corpus/variants?word=${encodeURIComponent(word)}`,
    ),

  /** Record yourself: the clip's pitch over a span, measured the way a take is. */
  contour: (id: string, span: { start: number; end: number }, signal?: AbortSignal) =>
    request<NativePitchContour>(
      `/api/jobs/${id}/contour?start=${span.start.toFixed(3)}&end=${span.end.toFixed(3)}`,
      { signal },
    ),
  /** Record yourself: a take compared with the span it shadows. Measured on the local
   *  server and deleted there; nothing is stored. */
  compareTake: (id: string, take: Blob, span: { start: number; end: number }, signal?: AbortSignal) => {
    const form = new FormData();
    form.append("file", take, "take");
    form.append("start", span.start.toFixed(3));
    form.append("end", span.end.toFixed(3));
    return request<TakeComparison>(`/api/jobs/${id}/compare`, { method: "POST", body: form, signal });
  },

  /** "mix" = the original audio; "dialogue" = the separated speech the
   *  analysis actually read (only when the job has it). */
  audioUrl: (id: string, track: AudioTrack = "mix") =>
    track === "dialogue" ? `/api/jobs/${id}/audio?track=dialogue` : `/api/jobs/${id}/audio`,
  mediaUrl: (id: string) => `/api/jobs/${id}/media`,
  reportUrl: (id: string) => `/api/jobs/${id}/report`,
};
