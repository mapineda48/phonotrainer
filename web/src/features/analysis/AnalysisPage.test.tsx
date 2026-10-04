/** The workspace as a page: routing, deep links, keyboard, preferences — with the REST
 *  API stubbed and the real player (the audio element is jsdom's). */

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../api";
import { expectNoAxeViolations } from "../../test/axe";
import { analysis, job, wordButton } from "../../test/fixtures";
import { phoneScreen, setScreen } from "../../test/media";
import { renderPage } from "../../test/render";
import type { Job } from "../../types";
import { BREAKPOINTS } from "../../hooks/useMediaQuery";
import { AnalysisPage } from ".";

vi.mock("../../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../../api")>();
  return { ...original, api: { ...original.api, analysis: vi.fn(), review: vi.fn(), reviewSample: vi.fn() } };
});

const videoJob: Job = { ...job, is_video: true, has_media: true };
const audioEl = () => screen.getByTestId("player-audio") as HTMLAudioElement;

function open(
  { jobs = [job], selection = null, from = null, path }: {
    jobs?: Job[];
    selection?: { segment: number; index: number } | null;
    from?: string | null;
    path?: string;
  } = {},
) {
  const id = jobs[0]?.id ?? "missing";
  return renderPage(<AnalysisPage jobId={id} selection={selection} from={from} />, {
    jobs,
    path: path ?? `/analysis/${id}`,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.analysis).mockResolvedValue(analysis);
  vi.mocked(api.review).mockResolvedValue({ seed: 48, sampled: 0, ok: 0, wrong: 0, unsure: 0, accuracy: null, items: [] });
  vi.mocked(api.reviewSample).mockResolvedValue({ seed: 48, n: 20, items: [] });
});

describe("AnalysisPage", () => {
  it("opens the analysis: header, transcript and a lesson that teaches how to start", async () => {
    open();
    expect(await screen.findByRole("heading", { level: 1, name: "clip.wav" })).toBeInTheDocument();
    expect(wordButton("does")).toBeInTheDocument();
    expect(screen.getByText(/2 phrases · 5 words/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Pick a word to study" })).toBeInTheDocument();
  });

  it("“Start with the first change” opens the first word that carries one", async () => {
    open();
    await userEvent.click(await screen.findByRole("button", { name: "Start with the first change" }));
    expect(wordButton("does")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("heading", { level: 2, name: "“does”" })).toBeInTheDocument();
  });

  it("clicking a word opens its lesson, plays it and puts it in the URL", async () => {
    const play = vi.spyOn(window.HTMLMediaElement.prototype, "play");
    const tools = open();
    await userEvent.click(await screen.findByRole("button", { name: /^that,/ }));
    expect(screen.getByRole("heading", { level: 2, name: "“that”" })).toBeInTheDocument();
    expect(play).toHaveBeenCalled();
    expect(audioEl().currentTime).toBeCloseTo(0.36, 2);
    expect(tools.history.at(-1)).toBe(`/analysis/${job.id}/w/0/1`);
  });

  it("a deep link opens that word once and plays it once", async () => {
    const play = vi.spyOn(window.HTMLMediaElement.prototype, "play");
    open({ selection: { segment: 1, index: 0 }, from: "insights", path: `/analysis/${job.id}/w/1/0?from=insights` });
    await waitFor(() => expect(wordButton("wanna")).toHaveAttribute("aria-pressed", "true"));
    expect(screen.getByText("reduced form")).toBeInTheDocument();
    const calls = play.mock.calls.length;
    expect(calls).toBe(1);
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(play.mock.calls.length).toBe(calls); // the effect must not re-trigger itself
    expect(screen.getByRole("button", { name: "Back to Insights" })).toBeInTheDocument();
  });

  it("N walks to the next word and Shift+N back; P replays the word", async () => {
    open();
    await screen.findByRole("button", { name: /^does,/ });
    await userEvent.click(wordButton("does"));
    await userEvent.keyboard("n");
    expect(wordButton("that")).toHaveAttribute("aria-pressed", "true");
    await userEvent.keyboard("N");
    expect(wordButton("does")).toHaveAttribute("aria-pressed", "true");
    audioEl().currentTime = 2;
    await userEvent.keyboard("p");
    expect(audioEl().currentTime).toBeCloseTo(0, 1);
  });

  it("the lesson's arrows step through the words", async () => {
    open({ selection: { segment: 0, index: 1 } });
    await waitFor(() => expect(wordButton("that")).toHaveAttribute("aria-pressed", "true"));
    await userEvent.click(screen.getByRole("button", { name: "Next word (N)" }));
    expect(wordButton("work")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText(/Word 3 of 5/)).toBeInTheDocument();
  });

  it("searching jumps to the first match without playing; Enter goes on", async () => {
    const play = vi.spyOn(window.HTMLMediaElement.prototype, "play");
    open();
    await screen.findByRole("button", { name: /^does,/ });
    await userEvent.type(screen.getByRole("searchbox", { name: /Search for a word/ }), "wa");
    expect(wordButton("wanna")).toHaveAttribute("aria-pressed", "true");
    expect(play).not.toHaveBeenCalled();
    expect(screen.getByText("1 match")).toBeInTheDocument();
  });

  it("a change from the Summary filters the transcript and goes to its first word", async () => {
    open();
    await screen.findByRole("button", { name: /^does,/ });
    await userEvent.click(screen.getByRole("tab", { name: "Summary" }));
    await userEvent.click(screen.getByRole("button", { name: /^t\/d deletion, 2 occurrences/ }));
    expect(wordButton("that")).toHaveAttribute("aria-pressed", "true");
    expect(wordButton("does")).toHaveAttribute("data-dimmed", "true");
    const bar = screen.getByRole("region", { name: "Filter" });
    expect(bar).toHaveTextContent(/Showing: t\/d deletion · 1 words/);
    await userEvent.click(within(bar).getByRole("button", { name: "Clear" }));
    expect(wordButton("does")).not.toHaveAttribute("data-dimmed");
  });

  it("says so when the analysis does not exist", async () => {
    renderPage(<AnalysisPage jobId="gone" selection={null} from={null} />, { jobs: [job] });
    expect(await screen.findByRole("heading", { name: "This analysis no longer exists" })).toBeInTheDocument();
  });

  it("an analysis in flight shows its progress instead", async () => {
    const running: Job = { ...job, status: "running", percent: 40, last_message: "Segment 3/10: phones + alignment…" };
    open({ jobs: [running] });
    expect(await screen.findByText("Analyzing…")).toBeInTheDocument();
    expect(api.analysis).not.toHaveBeenCalled();
  });

  it("has no accessibility violations with a lesson open", async () => {
    const { container } = open({ selection: { segment: 0, index: 1 } });
    await waitFor(() => expect(wordButton("that")).toHaveAttribute("aria-pressed", "true"));
    await expectNoAxeViolations(container);
  });
});

describe("AnalysisPage on a narrow screen", () => {
  const panelTabs = () => screen.getByRole("tablist", { name: "Panel" });
  const tab = (name: string) => within(panelTabs()).getByRole("tab", { name });
  const lessonHeading = (word: string) => screen.queryByRole("heading", { level: 2, name: `“${word}”` });

  it("shows one pane at a time: the transcript, the lesson, the summary and review are tabs", async () => {
    phoneScreen();
    const { container } = open();
    await screen.findByRole("button", { name: /^does,/ });
    expect(within(panelTabs()).getAllByRole("tab").map((t) => t.textContent)).toEqual([
      "Transcript",
      "Lesson",
      "Summary",
      "Review",
    ]);
    expect(tab("Transcript")).toHaveAttribute("aria-selected", "true");
    // the player stays with the transcript
    expect(screen.getByRole("group", { name: "Playback" })).toBeInTheDocument();
    await expectNoAxeViolations(container);

    await userEvent.click(tab("Summary"));
    expect(screen.queryByRole("button", { name: /^does,/ })).toBeNull();
    expect(screen.getByRole("button", { name: /^t\/d deletion, 2 occurrences/ })).toBeInTheDocument();
  });

  it("a tapped word plays and offers its lesson one tap away; the Transcript tab goes back to it", async () => {
    phoneScreen();
    const play = vi.spyOn(window.HTMLMediaElement.prototype, "play");
    const tools = open();
    await userEvent.click(await screen.findByRole("button", { name: /^that,/ }));
    expect(play).toHaveBeenCalled();
    expect(tools.history.at(-1)).toBe(`/analysis/${job.id}/w/0/1`);
    // still reading: the transcript stays, the lesson waits in the bar below it
    expect(lessonHeading("that")).toBeNull();
    expect(screen.getByText(/one tap away/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Open lesson: “that”" }));
    expect(tab("Lesson")).toHaveAttribute("aria-selected", "true");
    expect(lessonHeading("that")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^that,/ })).toBeNull();
    await waitFor(() => expect(screen.getByRole("complementary", { name: /Word lesson/ })).toHaveFocus());

    // the lesson's own arrows walk on without leaving it
    await userEvent.click(screen.getByRole("button", { name: "Next word (N)" }));
    expect(lessonHeading("work")).toBeInTheDocument();

    await userEvent.click(tab("Transcript"));
    expect(lessonHeading("work")).toBeNull();
    expect(wordButton("work")).toHaveAttribute("aria-pressed", "true");
  });

  it("a deep link arrives on the word's lesson", async () => {
    phoneScreen();
    open({ selection: { segment: 1, index: 0 }, from: "insights", path: `/analysis/${job.id}/w/1/0?from=insights` });
    await waitFor(() => expect(lessonHeading("wanna")).toBeInTheDocument());
    expect(tab("Lesson")).toHaveAttribute("aria-selected", "true");
  });

  it("the bar under the transcript starts with the first change, and the skip link reaches the lesson", async () => {
    phoneScreen();
    open();
    await userEvent.click(await screen.findByRole("button", { name: "Start with the first change" }));
    expect(tab("Lesson")).toHaveAttribute("aria-selected", "true");
    expect(lessonHeading("does")).toBeInTheDocument();

    await userEvent.click(tab("Transcript"));
    await userEvent.click(screen.getByRole("link", { name: "Skip to the word lesson" }));
    expect(tab("Lesson")).toHaveAttribute("aria-selected", "true");
    await waitFor(() => expect(screen.getByRole("complementary", { name: /Word lesson/ })).toHaveFocus());
  });

  it("goes back to two panes when the window widens, keeping the word", async () => {
    const resize = setScreen([BREAKPOINTS.singlePane]);
    open({ selection: { segment: 0, index: 1 } });
    await waitFor(() => expect(lessonHeading("that")).toBeInTheDocument());
    expect(tab("Transcript")).toBeInTheDocument();
    resize([]);
    expect(screen.queryByRole("tab", { name: "Transcript" })).toBeNull();
    expect(lessonHeading("that")).toBeInTheDocument();
    expect(wordButton("that")).toHaveAttribute("aria-pressed", "true");
  });
});

describe("AnalysisPage — preferences", () => {
  const FLAG = "phonotrainer:show-video";
  const hasVideo = () => document.querySelector("video") !== null;

  it("hides the video by default; the button and V show it, and it is remembered", async () => {
    const tools = open({ jobs: [videoJob] });
    await screen.findByRole("button", { name: /^does,/ });
    expect(hasVideo()).toBe(false);
    await userEvent.click(screen.getByRole("button", { name: "Video" }));
    expect(hasVideo()).toBe(true);
    expect(window.localStorage.getItem(FLAG)).toBe("1");
    await userEvent.keyboard("v");
    expect(hasVideo()).toBe(false);
    await userEvent.keyboard("v");
    tools.unmount();

    open({ jobs: [{ ...videoJob, id: "other" }] });
    await screen.findByRole("button", { name: /^does,/ });
    expect(hasVideo()).toBe(true);
    await userEvent.click(screen.getByRole("button", { name: "Hide video" }));
    expect(hasVideo()).toBe(false);
    expect(window.localStorage.getItem(FLAG)).toBe("0");
  });

  it("with no video there is no video button, even if the preference is on", async () => {
    window.localStorage.setItem(FLAG, "1");
    open();
    await screen.findByRole("button", { name: /^does,/ });
    expect(screen.queryByRole("button", { name: "Video" })).toBeNull();
    expect(hasVideo()).toBe(false);
  });

  it("offers the isolated dialogue when the job has it, and remembers the choice", async () => {
    open({ jobs: [{ ...job, has_dialogue_audio: true }] });
    await screen.findByRole("button", { name: /^does,/ });
    expect(audioEl().getAttribute("src")).toBe(`/api/jobs/${job.id}/audio`);
    await userEvent.click(within(screen.getByRole("radiogroup", { name: "Listen to" })).getByRole("radio", { name: "Dialogue only" }));
    expect(audioEl().getAttribute("src")).toBe(`/api/jobs/${job.id}/audio?track=dialogue`);
    expect(window.localStorage.getItem("phonotrainer:dialogue-track")).toBe("1");
  });

  it("without a dialogue track there is no track choice", async () => {
    open();
    await screen.findByRole("button", { name: /^does,/ });
    expect(screen.queryByRole("radiogroup", { name: "Listen to" })).toBeNull();
  });

  it("? opens the keyboard shortcuts", async () => {
    open();
    await screen.findByRole("button", { name: /^does,/ });
    await userEvent.keyboard("?");
    expect(await screen.findByRole("dialog", { name: "Keyboard shortcuts" })).toBeInTheDocument();
  });
});
