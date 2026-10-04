import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../../api";
import { analysis, fakePlayer, job, metrics } from "../../../test/fixtures";
import { renderPage } from "../../../test/render";
import type { Job } from "../../../types";
import { SyncedVideo } from "../player/VideoDock";
import { JobProgressView } from "./JobProgressView";
import { ReviewMode } from "./ReviewMode";
import { SummaryTab } from "./SummaryTab";

vi.mock("../../../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../../../api")>();
  return {
    ...original,
    api: {
      ...original.api,
      review: vi.fn(),
      reviewSample: vi.fn(),
      saveReview: vi.fn(),
      cancelJob: vi.fn(),
      createFromUrl: vi.fn(),
      deleteJob: vi.fn(),
    },
  };
});

describe("SummaryTab", () => {
  const renderSummary = (props: Partial<Parameters<typeof SummaryTab>[0]> = {}) => {
    const onToggle = vi.fn();
    const onSetFilter = vi.fn();
    renderPage(
      <SummaryTab analysis={analysis} filter={new Set()} onToggle={onToggle} onSetFilter={onSetFilter} {...props} />,
      { player: fakePlayer() },
    );
    return { onToggle, onSetFilter };
  };

  it("lists the changes with their counts; each row toggles the transcript filter", async () => {
    const { onToggle } = renderSummary();
    const row = screen.getByRole("button", { name: /^t\/d deletion, 2 occurrences, recognize only$/ });
    await userEvent.click(row);
    expect(onToggle).toHaveBeenCalledWith("t_deletion");
    expect(screen.getByRole("button", { name: /^linking, 7 occurrences/ })).toBeInTheDocument();
  });

  it("marks the active filter as pressed and offers to clear it", async () => {
    const { onSetFilter } = renderSummary({ filter: new Set(["t_deletion"]) });
    expect(screen.getByRole("button", { name: /^t\/d deletion/ })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: "Clear filter" }));
    expect(onSetFilter).toHaveBeenCalledWith([]);
  });

  it("one click keeps only what is safe to produce, another only what to recognize", async () => {
    const { onSetFilter } = renderSummary();
    const practice = within(screen.getByTestId("practice-filter"));
    await userEvent.click(practice.getByRole("button", { name: "Safe to produce" }));
    expect(onSetFilter).toHaveBeenLastCalledWith(["linking", "vowel_reduction", "contraction_lex"]);
    await userEvent.click(practice.getByRole("button", { name: "Recognize only" }));
    expect(onSetFilter).toHaveBeenLastCalledWith(["t_deletion"]);
  });

  it("the practice shortcut turns off when it already is the filter", async () => {
    const { onSetFilter } = renderSummary({ filter: new Set(["t_deletion"]) });
    const button = within(screen.getByTestId("practice-filter")).getByRole("button", { name: "Recognize only" });
    expect(button).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(button);
    expect(onSetFilter).toHaveBeenLastCalledWith([]);
  });

  it("measures how reduced the speech is, next to the published figures", () => {
    renderSummary({ analysis: { ...analysis, summary: { ...analysis.summary, metrics } } });
    const deviate = screen.getByRole("meter", { name: "Words that differ from the citation form" });
    expect(deviate).toHaveAttribute("aria-valuetext", "72 %; published figure > 60 % (Johnson 2004)");
    expect(screen.getByTestId("metric-deviate")).toHaveTextContent("126 of 175 words · in line with the report");
    expect(screen.getByTestId("reduction-metrics")).toHaveTextContent(/timit61 engine/);
    expect(screen.getByTestId("final-t")).toHaveTextContent("flapped 2 · unreleased 1");
  });

  it("older analyses without metrics show no measures", () => {
    renderSummary();
    expect(screen.queryByTestId("reduction-metrics")).toBeNull();
  });

  it("says how the analysis was made", async () => {
    renderSummary();
    await userEvent.click(screen.getByRole("button", { name: /About this analysis/ }));
    expect(screen.getByText("faster-whisper small (int8)")).toBeInTheDocument();
    expect(screen.getByText(/^on ·/)).toBeInTheDocument(); // attraction
  });
});

const sample = {
  seed: 48,
  n: 20,
  items: [
    { segment: 0, word_idx: 1, word: analysis.segments[0].words[1], segment_text: "does that work", segment_start: 0, segment_end: 1.2 },
    { segment: 1, word_idx: 0, word: analysis.segments[1].words[0], segment_text: "wanna go", segment_start: 2, segment_end: 3 },
  ],
};

const emptyReview = { seed: 48, sampled: 0, ok: 0, wrong: 0, unsure: 0, accuracy: null, items: [] };

describe("ReviewMode", () => {
  beforeEach(() => {
    vi.mocked(api.review).mockResolvedValue(emptyReview);
    vi.mocked(api.reviewSample).mockResolvedValue(sample);
    vi.mocked(api.saveReview).mockResolvedValue({ ...emptyReview, sampled: 1, ok: 1, accuracy: 1 });
  });

  const renderReview = () => renderPage(<ReviewMode jobId="j1" />, { player: fakePlayer() });
  const verdicts = (word: string) => within(screen.getByRole("radiogroup", { name: `Verdict for “${word}”` }));

  it("shows the prioritized sample with its phonetic context", async () => {
    renderReview();
    expect(await screen.findByText("that")).toBeInTheDocument();
    expect(screen.getByText("wanna")).toBeInTheDocument();
    expect(screen.getByText("[ðæ]")).toBeInTheDocument();
    expect(screen.getByText("0/2 reviewed")).toBeInTheDocument();
  });

  it("the keyboard records the verdict and advances", async () => {
    renderReview();
    await screen.findByText("that");
    await userEvent.keyboard("1"); // ok for the first
    expect(screen.getByText("1/2 reviewed")).toBeInTheDocument();
    await userEvent.keyboard("2"); // wrong for the second
    expect(screen.getByText("2/2 reviewed")).toBeInTheDocument();
    expect(verdicts("wanna").getByRole("radio", { name: "wrong" })).toBeChecked();
  });

  it("saves only what was decided and shows the accuracy", async () => {
    renderReview();
    await screen.findByText("that");
    await userEvent.click(verdicts("that").getByRole("radio", { name: "ok" }));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.saveReview).toHaveBeenCalled());
    expect(vi.mocked(api.saveReview).mock.calls[0][2]).toEqual([{ segment: 0, word_idx: 1, verdict: "ok", note: "" }]);
    expect(await screen.findByText(/accuracy 100 %/)).toBeInTheDocument();
  });

  it("recovers a previous review with its seed", async () => {
    vi.mocked(api.review).mockResolvedValue({
      seed: 7, sampled: 1, ok: 1, wrong: 0, unsure: 0, accuracy: 1,
      items: [
        {
          segment: 0, word_idx: 1, word: "that", t_start: 0.4, t_end: 0.5, phenomena: ["t_deletion"],
          attracted_count: 0, low_confidence: false, verdict: "ok", note: "good",
        },
      ],
    });
    renderReview();
    await waitFor(() => expect(api.reviewSample).toHaveBeenCalledWith("j1", 1, 7));
    expect(await screen.findByDisplayValue("good")).toBeInTheDocument();
    expect(verdicts("that").getByRole("radio", { name: "ok" })).toBeChecked();
  });

  it("re-samples with a different seed", async () => {
    renderReview();
    await screen.findByText("that");
    const seed = screen.getByRole("spinbutton", { name: "Seed" });
    await userEvent.clear(seed);
    await userEvent.type(seed, "99");
    await userEvent.click(screen.getByRole("button", { name: "Sample" }));
    await waitFor(() => expect(api.reviewSample).toHaveBeenLastCalledWith("j1", 20, 99));
  });
});

describe("JobProgressView", () => {
  const running: Job = {
    ...job,
    status: "running",
    percent: 8,
    source_url: "https://youtu.be/abc",
    last_message: "Downloading from YouTube… 8%",
    has_analysis: false,
    progress: [
      { at: "2026-07-26T12:00:01+00:00", message: "Queued → starting…" },
      { at: "2026-07-26T12:00:02+00:00", message: "Looking up the URL…" },
      { at: "2026-07-26T12:00:03+00:00", message: "Downloading from YouTube… 8%" },
    ],
  };

  it("shows the steps and the log, newest line first", async () => {
    renderPage(<JobProgressView job={running} />, { jobs: [running] });
    expect(screen.getByText("Analyzing…")).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Analysis steps" })).toBeInTheDocument();
    const log = screen.getByRole("log");
    expect(within(log).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "Downloading from YouTube… 8%",
      "Looking up the URL…",
      "Queued → starting…",
    ]);
  });

  it("updates when the server pushes over the channel, with no polling", async () => {
    const tools = renderPage(<JobProgressView job={{ ...running, progress: [] }} />, { jobs: [running] });
    await screen.findByRole("log");
    act(() =>
      tools.jobs.socket.push({
        type: "job",
        job: { ...running, percent: 20, last_message: "Downloading from YouTube… 20%" },
      }),
    );
    expect(await screen.findByRole("progressbar", { name: "Overall progress" })).toHaveAttribute("aria-valuenow", "20");
  });

  it("cancels on request", async () => {
    renderPage(<JobProgressView job={running} />, { jobs: [running] });
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(api.cancelJob).toHaveBeenCalledWith(running.id);
  });

  it("a failed download can be retried without retyping the link", async () => {
    vi.mocked(api.createFromUrl).mockResolvedValue({ ...running, id: "new" });
    vi.mocked(api.deleteJob).mockResolvedValue(undefined);
    const failed: Job = { ...running, status: "error", error: "HTTP Error 403", has_media: false };
    renderPage(<JobProgressView job={failed} />, { jobs: [failed] });
    expect(screen.getByText("HTTP Error 403")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(api.createFromUrl).toHaveBeenCalledWith(running.source_url, running.options, false));
    expect(api.deleteJob).toHaveBeenCalledWith(running.id);
  });

  it("a cancelled analysis says so and offers a way on", () => {
    const cancelled: Job = { ...running, status: "cancelled" };
    renderPage(<JobProgressView job={cancelled} />, { jobs: [cancelled] });
    expect(screen.getByText(/cancelled before it finished/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to your analyses" })).toHaveAttribute("href", "/");
  });
});

describe("SyncedVideo", () => {
  const video = (): HTMLVideoElement => document.querySelector("video")!;

  it("stays muted and follows the player speed", () => {
    renderPage(<SyncedVideo src="/x.mp4" />, { player: fakePlayer({ rate: 0.5 }) });
    expect(video().muted).toBe(true);
    expect(video().playbackRate).toBe(0.5);
  });

  it("plays and pauses on the player's command", () => {
    const playSpy = vi.spyOn(window.HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
    const pauseSpy = vi.spyOn(window.HTMLMediaElement.prototype, "pause").mockReturnValue(undefined);
    const player = fakePlayer({ playing: false });
    const tools = renderPage(<SyncedVideo src="/x.mp4" />, { player });
    expect(pauseSpy).toHaveBeenCalled();
    player.playing = true;
    tools.rerender(<SyncedVideo src="/x.mp4" />);
    expect(playSpy).toHaveBeenCalled();
  });

  it("corrects the position only when the drift passes 200 ms", () => {
    const player = fakePlayer();
    renderPage(<SyncedVideo src="/x.mp4" />, { player });
    act(() => player.clock.set(5.9));
    expect(video().currentTime).toBeCloseTo(5.9);
    act(() => player.clock.set(6.05)); // 150 ms: within tolerance
    expect(video().currentTime).toBeCloseTo(5.9);
    act(() => player.clock.set(6.5)); // 600 ms: corrected
    expect(video().currentTime).toBeCloseTo(6.5);
  });
});
