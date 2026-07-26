/** Cliente HTTP de la API local. Mismo origen que la SPA; en `npm run dev`
 *  Vite hace proxy de /api al backend. */

import type {
  Analysis,
  Browse,
  Job,
  JobOptions,
  Reference,
  Review,
  SampleItem,
  VerdictValue,
} from "./types";

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

  listJobs: () => request<Job[]>("/api/jobs"),
  getJob: (id: string) => request<Job>(`/api/jobs/${id}`),
  createJob: (path: string, options: Partial<JobOptions>) =>
    request<Job>("/api/jobs", jsonInit("POST", { path, options })),
  uploadJob: (file: File, options: Partial<JobOptions>) => {
    const form = new FormData();
    form.append("file", file);
    form.append("options", JSON.stringify(options));
    return request<Job>("/api/jobs/upload", { method: "POST", body: form });
  },
  importJob: (path: string) => request<Job>("/api/jobs/import", jsonInit("POST", { path })),
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

  audioUrl: (id: string) => `/api/jobs/${id}/audio`,
  mediaUrl: (id: string) => `/api/jobs/${id}/media`,
  reportUrl: (id: string) => `/api/jobs/${id}/report`,
};
