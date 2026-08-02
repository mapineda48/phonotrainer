/** Cliente HTTP de la API local. Mismo origen que la SPA; en `npm run dev`
 *  Vite hace proxy de /api al backend. La lista de análisis NO va por aquí:
 *  llega empujada por el WebSocket (`jobs/channel.ts`). */

import type {
  Analysis,
  Browse,
  CorpusAnalysis,
  CorpusStats,
  Job,
  JobOptions,
  Occurrence,
  Reference,
  Review,
  SampleItem,
  VerdictValue,
  WordVariant,
} from "./types";

/** Versión de la API que necesita esta interfaz (ver `server.API_VERSION`). */
export const REQUIRED_API_VERSION = 2;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body?.detail) detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail);
    } catch {
      /* respuesta sin cuerpo JSON */
    }
    throw new ApiError(detail, res.status);
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

  audioUrl: (id: string) => `/api/jobs/${id}/audio`,
  mediaUrl: (id: string) => `/api/jobs/${id}/media`,
  reportUrl: (id: string) => `/api/jobs/${id}/report`,
};
