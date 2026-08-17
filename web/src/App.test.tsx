/** Walkthrough of the app: REST stubbed at the fetch level, and analysis state
 *  pushed by a fake WebSocket channel (just as in production, /api/jobs is not
 *  polled here either). */

import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { act } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { JobsProvider } from "./jobs/JobsProvider";
import { analysis, fakeJobsChannel, job, reference, wordButton } from "./test/fixtures";
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

/** The app with its channel: analyses arrive via the WebSocket snapshot. */
function renderApp(jobs: Job[]) {
  const tools = fakeJobsChannel(jobs);
  render(
    <JobsProvider channel={tools.channel}>
      <App />
    </JobsProvider>,
  );
  return tools;
}

beforeEach(() => {
  window.localStorage.clear();
});

describe("App", () => {
  it("offers to create one when there are no previous analyses", async () => {
    mockFetch({ "GET /api/reference": () => reference });
    renderApp([]);

    expect(await screen.findByText("Analyze native speech")).toBeInTheDocument();
    expect(screen.getByText(/No analyses yet/)).toBeInTheDocument();
  });

  it("opens the last analysis and shows the transcript", async () => {
    mockFetch({
      "GET /api/reference": () => reference,
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
    });
    renderApp([job]);

    await screen.findByRole("button", { name: /^does/ });
    expect(wordButton("does")).toBeInTheDocument();
    expect(wordButton("wanna")).toBeInTheDocument();
    expect(screen.getByText(/2 segments/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "report.html" })).toHaveAttribute(
      "href",
      `/api/jobs/${job.id}/report`,
    );
  });

  it("shows progress and the log for an analysis in flight", async () => {
    const running: Job = {
      ...job,
      status: "running",
      percent: 40,
      last_message: "Segment 3/10: phones + alignment…",
      has_analysis: false,
      progress: [{ at: "2026-07-26T12:00:02+00:00", message: "Transcribing…" }],
    };
    mockFetch({ "GET /api/reference": () => reference });
    renderApp([running]);

    expect(await screen.findByText("Analyzing…")).toBeInTheDocument();
    expect(screen.getByText("40%")).toBeInTheDocument();
    expect(screen.getByText(/Segment 3\/10/)).toBeInTheDocument();
    expect(await screen.findByText("Transcribing…")).toBeInTheDocument();
  });

  it("launches a new analysis with the chosen path and options", async () => {
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
      "POST /api/jobs": () => ({ ...job, id: "new", status: "queued", percent: 0 }),
    });
    renderApp([]);
    await screen.findByText("Analyze native speech");

    await userEvent.type(
      screen.getByPlaceholderText(/local path/),
      "/home/me/videos/ep1.webm",
    );
    await userEvent.selectOptions(screen.getByLabelText(/Whisper model/), "medium");
    await userEvent.click(screen.getByRole("button", { name: "Analyze" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).toBeDefined();
      expect(JSON.parse(String(call![1]!.body))).toEqual({
        path: "/home/me/videos/ep1.webm",
        options: {
          whisper_model: "medium",
          phone_engine: "wav2vec2",
          language: "en",
          attraction: true,
        },
      });
    });

    // the POST response is applied to the channel at once: its progress opens
    expect(await screen.findByText("Queued…")).toBeInTheDocument();
  });

  it("analyzes a YouTube URL without going through disk", async () => {
    const downloading: Job = {
      ...job, id: "yt", status: "running", percent: 8, has_analysis: false,
      source_url: "https://youtu.be/abc123",
      last_message: "Downloading from YouTube… 45%",
      progress: [{ at: "2026-07-26T12:00:02+00:00", message: "Downloading from YouTube… 45%" }],
    };
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
      "POST /api/jobs/youtube": () => ({ ...downloading, status: "queued", percent: 0 }),
    });
    const { socket } = renderApp([]);
    await screen.findByText("Analyze native speech");

    await userEvent.type(
      screen.getByLabelText("YouTube URL"),
      "https://youtu.be/abc123",
    );
    await userEvent.click(screen.getByRole("checkbox", { name: /audio only/ }));
    await userEvent.click(screen.getByRole("button", { name: "Download and analyze" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => String(url).includes("youtube"));
      expect(call).toBeDefined();
      expect(JSON.parse(String(call![1]!.body))).toEqual({
        url: "https://youtu.be/abc123",
        options: reference.options.defaults,
        audio_only: true,
      });
    });

    // download progress arrives pushed by the channel (status and log)
    act(() => socket.push({ type: "job", job: downloading }));
    expect(await screen.findAllByText(/Downloading from YouTube/)).toHaveLength(2);
  });

  it("opens an analysis on the chosen word when coming from the corpus", async () => {
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
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
    renderApp([job]);
    await screen.findByRole("button", { name: /^does/ });

    await userEvent.click(screen.getByRole("button", { name: /^Corpus/ }));
    const row = await screen.findByText("wanna");
    await userEvent.click(row);

    // returns to the analysis with that word already selected and its detail open
    await waitFor(() => expect(wordButton("wanna")).toHaveAttribute("aria-pressed", "true"));
    expect(screen.getByText("reduced form")).toBeInTheDocument();
    // and only once: the effect must not re-trigger itself
    const before = fetchMock.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(wordButton("wanna")).toHaveAttribute("aria-pressed", "true");
    expect(fetchMock.mock.calls.length - before).toBeLessThan(5);
  });

  it("warns when the server is older than the interface", async () => {
    // The real case: an earlier `phonotrainer ui` is left running on that port.
    // It serves the new dist/ from disk, so the UI loads and then requests
    // routes that server does not have ("Method Not Allowed").
    const { api_version: _omitted, ...old } = reference;
    mockFetch({ "GET /api/reference": () => old });
    renderApp([]);

    expect(await screen.findByText(/older than this interface/)).toBeInTheDocument();
    expect(screen.getByText(/Stop it \(Ctrl-C\)/)).toBeInTheDocument();
  });

  it("warns when the backend does not respond", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("connection refused");
      }),
    );
    renderApp([]);

    expect(await screen.findByText(/Could not reach the backend/)).toBeInTheDocument();
  });
});
