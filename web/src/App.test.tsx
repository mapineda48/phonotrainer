/** Recorrido de la app contra un backend simulado a nivel de fetch. */

import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { analysis, job, reference, wordButton } from "./test/fixtures";
import type { Job } from "./types";

type Handler = (url: string, init?: RequestInit) => unknown;

function mockFetch(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : String(input);
    const path = url.split("?")[0];
    const handler = routes[`${init?.method ?? "GET"} ${path}`] ?? routes[`GET ${path}`];
    if (!handler) {
      return { ok: false, status: 404, statusText: "Not Found", json: async () => ({}) } as Response;
    }
    const body = handler(url, init);
    return { ok: true, status: 200, json: async () => body } as Response;
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

beforeEach(() => {
  window.localStorage.clear();
});

describe("App", () => {
  it("sin análisis previos ofrece crear uno", async () => {
    mockFetch({
      "GET /api/reference": () => reference,
      "GET /api/jobs": () => [],
    });

    render(<App />);

    expect(await screen.findByText("Analizar habla nativa")).toBeInTheDocument();
    expect(screen.getByText(/Todavía no hay análisis/)).toBeInTheDocument();
  });

  it("abre el último análisis y muestra la transcripción", async () => {
    mockFetch({
      "GET /api/reference": () => reference,
      "GET /api/jobs": () => [job],
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
    });

    render(<App />);

    await screen.findByRole("button", { name: /^does/ });
    expect(wordButton("does")).toBeInTheDocument();
    expect(wordButton("wanna")).toBeInTheDocument();
    expect(screen.getByText(/2 segmentos/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "report.html" })).toHaveAttribute(
      "href",
      `/api/jobs/${job.id}/report`,
    );
  });

  it("un análisis en curso muestra progreso y registro", async () => {
    const running: Job = {
      ...job,
      status: "running",
      percent: 40,
      last_message: "Segmento 3/10: fonos + alineación…",
      has_analysis: false,
      progress: [{ at: "2026-07-26T12:00:02+00:00", message: "Transcribiendo…" }],
    };
    mockFetch({
      "GET /api/reference": () => reference,
      "GET /api/jobs": () => [running],
      [`GET /api/jobs/${running.id}`]: () => running,
    });

    render(<App />);

    expect(await screen.findByText("Analizando…")).toBeInTheDocument();
    expect(screen.getByText("40%")).toBeInTheDocument();
    expect(screen.getByText(/Segmento 3\/10/)).toBeInTheDocument();
    expect(await screen.findByText("Transcribiendo…")).toBeInTheDocument();
  });

  it("lanza un análisis nuevo con la ruta y las opciones elegidas", async () => {
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
      "GET /api/jobs": () => [],
      "POST /api/jobs": () => ({ ...job, id: "nuevo", status: "queued" }),
      "GET /api/jobs/nuevo": () => ({ ...job, id: "nuevo", status: "queued", percent: 0 }),
    });

    render(<App />);
    await screen.findByText("Analizar habla nativa");

    await userEvent.type(
      screen.getByPlaceholderText(/ruta local/),
      "/home/yo/videos/ep1.webm",
    );
    await userEvent.selectOptions(screen.getByLabelText(/Modelo de Whisper/), "medium");
    await userEvent.click(screen.getByRole("button", { name: "Analizar" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).toBeDefined();
      expect(JSON.parse(String(call![1]!.body))).toEqual({
        path: "/home/yo/videos/ep1.webm",
        options: {
          whisper_model: "medium",
          phone_engine: "wav2vec2",
          language: "en",
          attraction: true,
        },
      });
    });
  });

  it("analiza una URL de YouTube sin pasar por el disco", async () => {
    const descargando: Job = {
      ...job, id: "yt", status: "running", percent: 8, has_analysis: false,
      source_url: "https://youtu.be/abc123",
      last_message: "Descargando de YouTube… 45 %",
      progress: [{ at: "2026-07-26T12:00:02+00:00", message: "Descargando de YouTube… 45 %" }],
    };
    let creado = false;
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
      "GET /api/jobs": () => (creado ? [descargando] : []),
      "POST /api/jobs/youtube": () => {
        creado = true;
        return { ...descargando, status: "queued" };
      },
      "GET /api/jobs/yt": () => descargando,
    });

    render(<App />);
    await screen.findByText("Analizar habla nativa");

    await userEvent.type(
      screen.getByLabelText("URL de YouTube"),
      "https://youtu.be/abc123",
    );
    await userEvent.click(screen.getByRole("checkbox", { name: /solo audio/ }));
    await userEvent.click(screen.getByRole("button", { name: "Descargar y analizar" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => String(url).includes("youtube"));
      expect(call).toBeDefined();
      expect(JSON.parse(String(call![1]!.body))).toEqual({
        url: "https://youtu.be/abc123",
        options: reference.options.defaults,
        audio_only: true,
      });
    });

    // y el progreso de la descarga se ve como una etapa más (estado y registro)
    expect(await screen.findAllByText(/Descargando de YouTube/)).toHaveLength(2);
  });

  it("desde el corpus se abre un análisis en la palabra elegida", async () => {
    mockFetch({
      "GET /api/reference": () => reference,
      "GET /api/jobs": () => [job],
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
      "GET /api/corpus/stats": () => ({
        analyses: 1, sources: 1, words: 5, segments: 2, duration: 3,
        phenomena: [{ phenomenon: "t_deletion", count: 1, analyses: 1 }],
        top_words: [],
      }),
      "GET /api/corpus/analyses": () => ({
        items: [{
          id: "/tmp/out", job_id: job.id, source: "clip.wav", duration: 3, words: 5,
          segments: 2, attraction: true, duplicate_source: false,
          indexed_at: "2026-07-26T18:00:00+00:00",
        }],
      }),
      "GET /api/corpus/occurrences": () => ({
        phenomenon: null, word: null, total: 1,
        items: [{
          analysis_id: "/tmp/out", job_id: job.id, analysis_source: "clip.wav",
          analysis_attraction: true, next_word: "go", segment: 1, word_idx: 0,
          word: "wanna", start: 2, end: 2.3, dict_ipa: "wɑnə", canonical_ipa: "wɑnə",
          realized_ipa: "wɑnə", realized_raw_ipa: "", diff_cost: 0, attracted_count: 1,
          low_confidence: false, oov: false, lexical_form: "want to",
          phenomena: ["contraction_lex"],
        }],
      }),
    });

    render(<App />);
    await screen.findByRole("button", { name: /^does/ });

    await userEvent.click(screen.getByRole("button", { name: /^Corpus/ }));
    const fila = await screen.findByText("wanna");
    await userEvent.click(fila);

    // vuelve al análisis con esa palabra ya seleccionada y su detalle abierto
    await waitFor(() => expect(wordButton("wanna")).toHaveAttribute("aria-pressed", "true"));
    expect(screen.getByText("forma reducida")).toBeInTheDocument();
  });

  it("avisa si el backend no responde", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("conexión rechazada");
      }),
    );

    render(<App />);

    expect(await screen.findByText(/No se pudo hablar con el backend/)).toBeInTheDocument();
  });
});
