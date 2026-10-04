/** End-to-end walkthroughs of the whole app: the real App (settings, router, shell and
 *  every page), REST stubbed at the fetch level, and analysis state pushed by a fake
 *  WebSocket channel (just as in production, /api/jobs is not polled here either).
 *  The page tests cover each screen in depth; these follow a learner across screens. */

import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { TOUR } from "./didactic/tour-ids";
import { resolveSteps } from "./features/tour/steps";
import { JobsProvider } from "./jobs/JobsProvider";
import { analysis, fakeJobsChannel, job, reference, wordButton } from "./test/fixtures";
import { fullReference } from "./test/render";
import type { CorpusStats, Job, Occurrence } from "./types";

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

/** The app with its channel, opened at `path`: analyses arrive via the WebSocket snapshot. */
function renderApp(jobs: Job[], path = "/") {
  window.history.pushState(null, "", path);
  const tools = fakeJobsChannel(jobs);
  render(
    <JobsProvider channel={tools.channel}>
      <App />
    </JobsProvider>,
  );
  return tools;
}

const nav = () => screen.getByRole("navigation", { name: "Main" });
const here = () => `${window.location.pathname}${window.location.search}`;

const corpusStats = (phenomena: CorpusStats["phenomena"]): CorpusStats => ({
  analyses: 1,
  sources: 1,
  materials: 1,
  words: 5,
  segments: 2,
  duration: 3,
  phenomena,
  top_words: [],
});

function occurrence(over: Partial<Occurrence>): Occurrence {
  return {
    analysis_id: "/tmp/out",
    job_id: job.id,
    analysis_source: "clip.wav",
    analysis_attraction: true,
    next_word: null,
    segment: 0,
    word_idx: 1,
    word: "that",
    start: 0.4,
    end: 0.55,
    dict_ipa: "ðæt",
    canonical_ipa: "ðæt",
    realized_ipa: "ðæ",
    realized_raw_ipa: "",
    diff_cost: 0.8,
    attracted_count: 0,
    low_confidence: false,
    oov: false,
    lexical_form: null,
    phenomena: ["t_deletion"],
    too_short: false,
    ...over,
  };
}

beforeEach(() => {
  window.localStorage.clear();
});

describe("App", () => {
  it("offers to create one when there are no previous analyses", async () => {
    mockFetch({ "GET /api/reference": () => reference });
    renderApp([]);

    expect(await screen.findByRole("heading", { name: "Your library is empty" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Analyze your first clip/ })).toHaveAttribute("href", "/new");
    // the shell is there too: the rail and its current page
    expect(within(nav()).getByRole("link", { name: "Library" })).toHaveAttribute("aria-current", "page");
  });

  it("opens an analysis from the library and shows the transcript", async () => {
    mockFetch({
      "GET /api/reference": () => reference,
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
    });
    renderApp([job]);

    // "/" lists the analyses and no longer opens the last one by itself
    await userEvent.click(await screen.findByText("clip.wav"));
    expect(here()).toBe(`/analysis/${job.id}`);

    expect(await screen.findByRole("heading", { level: 1, name: "clip.wav" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /^does,/ })).toBeInTheDocument();
    expect(wordButton("wanna")).toBeInTheDocument();
    expect(screen.getByText(/2 phrases · 5 words/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Pick a word to study" })).toBeInTheDocument();

    // the report is one menu away
    await userEvent.click(screen.getByRole("button", { name: "More actions" }));
    expect(screen.getByRole("menuitem", { name: /Open report\.html/ })).toHaveAttribute(
      "href",
      `/api/jobs/${job.id}/report`,
    );
  });

  it("shows progress and the log for an analysis in flight, then opens it when done", async () => {
    const running: Job = {
      ...job,
      status: "running",
      percent: 40,
      has_analysis: false,
      has_report: false,
      last_message: "Segment 3/10: phones + alignment…",
      progress: [
        { at: "2026-07-26T12:00:01+00:00", message: "Extracting audio…" },
        { at: "2026-07-26T12:00:02+00:00", message: "Transcribing…" },
        { at: "2026-07-26T12:00:03+00:00", message: "Segment 3/10: phones + alignment…" },
      ],
    };
    mockFetch({
      "GET /api/reference": () => reference,
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
    });
    const { socket } = renderApp([running]);

    const row = await screen.findByRole("row", { name: /clip\.wav/ });
    expect(within(row).getByText("Analyzing")).toBeInTheDocument();
    expect(within(row).getByText("40 % · Hear the phones · phrase 3 of 10")).toBeInTheDocument();

    // the analysis page shows the steps and the log while the job runs
    await userEvent.click(within(row).getByText("clip.wav"));
    expect(await screen.findByText("Transcribing…")).toBeInTheDocument();
    expect(screen.getByRole("log", { name: /Log/ })).toBeInTheDocument();
    const steps = screen.getByRole("list", { name: "Analysis steps" });
    expect(within(steps).getByText(/Hear the phones/).closest("li")).toHaveAttribute("aria-current", "step");

    // the channel says it finished: the workspace opens in place
    act(() => socket.push({ type: "job", job }));
    expect(await screen.findByRole("button", { name: /^does,/ })).toBeInTheDocument();
  });

  it("launches a new analysis with the chosen path and options", async () => {
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
      "POST /api/jobs": () => ({ ...job, id: "new", status: "queued", percent: 0, has_analysis: false }),
    });
    renderApp([]);

    await userEvent.click(await screen.findByRole("link", { name: /Analyze your first clip/ }));
    expect(here()).toBe("/new");

    await userEvent.type(screen.getByRole("textbox", { name: "File path" }), "/home/me/videos/ep1.webm");
    await userEvent.click(screen.getByRole("button", { name: /Speech model/ }));
    await userEvent.click(screen.getByRole("option", { name: "medium" }));
    await userEvent.click(screen.getByRole("button", { name: "Start analysis" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).toBeDefined();
      expect(JSON.parse(String(call![1]!.body))).toEqual({
        path: "/home/me/videos/ep1.webm",
        options: {
          whisper_model: "medium",
          phone_engine: "timit61",
          language: "en",
          attraction: null,
          separate_dialogue: true,
        },
      });
    });

    // the POST response is applied to the channel at once: its steps show
    expect(await screen.findByRole("heading", { name: "Analysis started" })).toBeInTheDocument();
    expect(screen.getByText("Waiting in line.")).toBeInTheDocument();
  });

  it("analyzes a YouTube URL without going through disk", async () => {
    const downloading: Job = {
      ...job,
      id: "yt",
      status: "running",
      percent: 8,
      has_analysis: false,
      source_url: "https://youtu.be/abc123",
      last_message: "Downloading from YouTube… 45%",
      progress: [{ at: "2026-07-26T12:00:02+00:00", message: "Downloading from YouTube… 45%" }],
    };
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
      "POST /api/jobs/youtube": () => ({ ...downloading, status: "queued", percent: 0, progress: [] }),
    });
    const { socket } = renderApp([], "/new");

    await userEvent.click(await screen.findByRole("tab", { name: /YouTube link/ }));
    await userEvent.type(screen.getByRole("textbox", { name: "YouTube URL" }), "https://youtu.be/abc123");
    await userEvent.click(screen.getByRole("switch", { name: /audio only/ }));
    await userEvent.click(screen.getByRole("button", { name: "Start analysis" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => String(url).includes("youtube"));
      expect(call).toBeDefined();
      expect(JSON.parse(String(call![1]!.body))).toEqual({
        url: "https://youtu.be/abc123",
        options: reference.options.defaults,
        audio_only: true,
      });
    });

    // download progress arrives pushed by the channel
    act(() => socket.push({ type: "job", job: downloading }));
    const steps = await screen.findByRole("list", { name: "Analysis steps" });
    expect(await within(steps).findByText(/45 %/)).toBeInTheDocument();
  });

  it("Insights → Open → the word lesson, and back to Insights", async () => {
    const fetchMock = mockFetch({
      "GET /api/reference": () => reference,
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
      "GET /api/corpus/stats": () =>
        corpusStats([{ phenomenon: "contraction_lex", count: 1, analyses: 1 }]),
      "GET /api/corpus/analyses": () => ({
        items: [
          {
            id: "/tmp/out", job_id: job.id, source: "clip.wav", duration: 3, words: 5, segments: 2,
            attraction: true, duplicate_source: false, indexed_at: "2026-07-26T18:00:00+00:00",
          },
        ],
      }),
      "GET /api/corpus/occurrences": () => ({
        phenomenon: null,
        word: null,
        total: 1,
        items: [
          occurrence({
            next_word: "go", segment: 1, word_idx: 0, word: "wanna", start: 2, end: 2.3,
            dict_ipa: "wɑnə", canonical_ipa: "wɑnə", realized_ipa: "wɑnə", diff_cost: 0, attracted_count: 1,
            lexical_form: "want to", phenomena: ["contraction_lex"],
          }),
        ],
      }),
    });
    renderApp([job]);
    await screen.findByRole("grid", { name: "Your analyses" });

    await userEvent.click(within(nav()).getByRole("link", { name: "Insights" }));
    expect(here()).toBe("/insights");
    await userEvent.click(await screen.findByRole("link", { name: /Open “wanna”/ }));

    // the analysis opens on that word with its lesson, and says where you came from
    expect(here()).toBe(`/analysis/${job.id}/w/1/0?from=insights`);
    await waitFor(() => expect(wordButton("wanna")).toHaveAttribute("aria-pressed", "true"));
    expect(screen.getByRole("heading", { level: 2, name: "“wanna”" })).toBeInTheDocument();
    expect(screen.getByText("reduced form")).toBeInTheDocument();
    // and only once: the deep link must not re-trigger itself
    const before = fetchMock.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(wordButton("wanna")).toHaveAttribute("aria-pressed", "true");
    expect(fetchMock.mock.calls.length - before).toBeLessThan(5);

    await userEvent.click(screen.getByRole("button", { name: "Back to Insights" }));
    await waitFor(() => expect(window.location.pathname).toBe("/insights"));
    expect(await screen.findByRole("link", { name: /Open “wanna”/ })).toBeInTheDocument();
  });

  it("Learn → a phenomenon → “Practice this” → a practice session on it", async () => {
    vi.spyOn(Math, "random").mockReturnValue(0);
    mockFetch({
      "GET /api/reference": () => fullReference,
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
      "GET /api/corpus/stats": () => corpusStats([{ phenomenon: "t_deletion", count: 2, analyses: 1 }]),
      "GET /api/corpus/occurrences": () => ({
        phenomenon: "t_deletion",
        word: null,
        total: 2,
        items: [occurrence({}), occurrence({ segment: 0, word_idx: 0, word: "does", start: 0, end: 0.3 })],
      }),
      "GET /api/corpus/variants": (url) => ({
        word: new URL(url, "http://x").searchParams.get("word"),
        variants: [{ realized_ipa: "ðæʔ", count: 1, analyses: 1, dict_ipa: null }],
      }),
    });
    renderApp([job]);
    await screen.findByRole("grid", { name: "Your analyses" });

    await userEvent.click(within(nav()).getByRole("link", { name: "Learn" }));
    expect(here()).toBe("/learn");
    await userEvent.click(await screen.findByRole("link", { name: /^T\/d deletion$/i }));
    expect(here()).toBe("/learn/t_deletion");
    expect(await screen.findByRole("heading", { level: 1, name: /t\/d deletion/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("link", { name: "Practice this" }));
    expect(here()).toBe("/practice?focus=t_deletion");
    const start = await screen.findByRole("button", { name: /start practicing/i });
    expect(screen.getByText("Only:")).toBeInTheDocument();
    await userEvent.click(start);

    // with a focus, the session asks "which pronunciation?" or "how many words?"
    expect(
      await screen.findByRole("heading", { name: /which pronunciation did you hear|how many words did you hear/i }),
    ).toBeInTheDocument();
  });

  it("the tour finds every one of its anchors on the real pages", async () => {
    mockFetch({
      "GET /api/reference": () => reference,
      [`GET /api/jobs/${job.id}/analysis`]: () => analysis,
    });
    renderApp([job], `/analysis/${job.id}/w/0/1`);
    await waitFor(() => expect(wordButton("that")).toHaveAttribute("aria-pressed", "true"));
    const anchored = () =>
      resolveSteps()
        .map(({ step, element }) => (element ? step.anchor : null))
        .filter(Boolean);

    // the workspace: rail, transcript, lesson and speed
    expect(anchored()).toEqual([TOUR.nav, TOUR.transcript, TOUR.wordLesson, TOUR.playerSpeed, TOUR.learnNav]);

    // the library: its "New analysis"
    await userEvent.click(within(nav()).getByRole("link", { name: "Library" }));
    await screen.findByRole("grid", { name: "Your analyses" });
    expect(anchored()).toEqual([TOUR.nav, TOUR.libraryNew, TOUR.learnNav]);

    // settings: the colors
    await userEvent.click(within(nav()).getByRole("link", { name: "Settings" }));
    await screen.findByRole("heading", { level: 1, name: "Settings" });
    expect(anchored()).toEqual([TOUR.nav, TOUR.learnNav, TOUR.settingsColors]);
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

  it("a server from before the timit61 engine is also too old", async () => {
    mockFetch({ "GET /api/reference": () => ({ ...reference, api_version: 3 }) });
    renderApp([]);

    expect(await screen.findByText(/older than this interface/)).toBeInTheDocument();
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
