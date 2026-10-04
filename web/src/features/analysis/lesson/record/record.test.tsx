/** Record yourself: the whole flow with a fake microphone (getUserMedia +
 *  MediaRecorder), a fake server (fetch) and a fake player for the original. */

import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Clock } from "../../../../player/clock";
import { expectNoAxeViolations } from "../../../../test/axe";
import { analysis, fakePlayer, reference } from "../../../../test/fixtures";
import { renderPage } from "../../../../test/render";
import type { NativePitchContour, PitchFrame, Reference, TakeComparison } from "../../../../types";
import { PracticeStep } from "../PracticeStep";
import { RecordPanel, type OriginalPlayer } from "./RecordPanel";

/* ---- fakes ---------------------------------------------------------------------- */

class FakeTrack {
  stop = vi.fn();
}

class FakeStream {
  tracks = [new FakeTrack()];
  getTracks() {
    return this.tracks;
  }
}

class FakeMediaRecorder {
  static instances: FakeMediaRecorder[] = [];
  static isTypeSupported = (type: string) => type.startsWith("audio/webm");
  state: "inactive" | "recording" = "inactive";
  mimeType: string;
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  constructor(
    readonly stream: FakeStream,
    options?: { mimeType?: string },
  ) {
    this.mimeType = options?.mimeType ?? "";
    FakeMediaRecorder.instances.push(this);
  }
  start() {
    this.state = "recording";
  }
  stop() {
    this.state = "inactive";
    // like the real one: the data and the stop event arrive after stop() returns
    void Promise.resolve().then(() => {
      this.ondataavailable?.({ data: new Blob([new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 1, 2, 3])], { type: this.mimeType }) });
      this.onstop?.();
    });
  }
}

const SPAN = { start: 0.4, end: 1.2 };

function frames(from: number, to: number, st: (t: number) => number | null): PitchFrame[] {
  const out: PitchFrame[] = [];
  for (let t = from; t <= to + 1e-9; t += 0.01) {
    const value = st(t);
    out.push([Math.round(t * 100) / 100, value === null ? null : 200, value, 70]);
  }
  return out;
}

const native: NativePitchContour = {
  start: 0.4,
  end: 1.2,
  speech: { start: 0.45, end: 1.15 },
  speech_s: 0.7,
  voiced_s: 0.65,
  median_hz: 210,
  f0_floor: 120,
  f0_ceiling: 400,
  range_st: 7.2,
  final_contour: "falling",
  final_slope_st: -9.4,
  peak: { time: 0.5, position: 0.07, where: "early", st: 3.5 },
  pauses: [],
  track: frames(0.4, 1.2, (t) => (t < 0.45 || t > 1.15 ? null : 4 - (t - 0.45) * 10)),
  audio: "mix",
  segment: 0,
};

const comparison: TakeComparison = {
  native,
  take: {
    start: 0,
    end: 1.9,
    speech: { start: 0.3, end: 1.5 },
    speech_s: 1.2,
    voiced_s: 1.1,
    median_hz: 150,
    f0_floor: 90,
    f0_ceiling: 250,
    range_st: 8.4,
    final_contour: "rising",
    final_slope_st: 6.5,
    peak: { time: 1.48, position: 0.98, where: "late", st: 4 },
    pauses: [],
    track: frames(0, 1.9, (t) => (t < 0.3 || t > 1.5 ? null : -4 + (t - 0.3) * 7)),
    duration_s: 1.9,
  },
  comparison: {
    time_scale: 1.71,
    overlay: Array.from({ length: 71 }, (_, i) => {
      const x = Math.round(i) / 100;
      return [x, 4 - x * 10, x >= 0.3 && x < 0.35 ? null : -4 + x * 11] as [number, number | null, number | null];
    }),
    observations: [
      { key: "ending", kind: "mismatch", native: "falling", take: "rising", text: "The original falls at the end; yours rises." },
      { key: "peak", kind: "different", native: "early", take: "late", text: "The original's pitch peaks early; yours peaks late." },
      {
        key: "range",
        kind: "similar",
        native: 7.2,
        take: 8.4,
        ratio: 1.17,
        text: "A similar pitch range: 8.4 semitones in yours, 7.2 in the original.",
      },
      {
        key: "length",
        kind: "longer",
        native: 0.7,
        take: 1.2,
        ratio: 1.71,
        text: "You took 1.7× as long: 1.20 s, against 0.70 s in the original.",
      },
    ],
  },
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

type Reply = () => Response | Promise<Response>;

function installServer(compare: Reply = () => json(comparison)) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/jobs/job1/contour")) return json(native);
    if (url === "/api/jobs/job1/compare" && init?.method === "POST") return compare();
    return json({ detail: `unexpected ${url}` }, 404);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function installMic(getUserMedia: () => Promise<unknown> = async () => new FakeStream()) {
  const spy = vi.fn(getUserMedia);
  Object.defineProperty(window, "isSecureContext", { configurable: true, value: true });
  Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: { getUserMedia: spy } });
  vi.stubGlobal("MediaRecorder", FakeMediaRecorder);
  return spy;
}

/** With shouldAdvanceTime, real time also moves the fake clock: any window a test checks
 *  in between must be far longer than a slow machine could take to get there. */
function fakeOriginal(ms = 800) {
  const play = vi.fn<OriginalPlayer["play"]>(() => ms);
  const stop = vi.fn<OriginalPlayer["stop"]>();
  return { play, stop, clock: new Clock() };
}

function renderPanel(options: { reference?: Reference; original?: ReturnType<typeof fakeOriginal>; single?: boolean } = {}) {
  const original = options.original ?? fakeOriginal();
  const tools = renderPage(
    <RecordPanel jobId="job1" span={SPAN} isSingleWord={options.single ?? false} original={original} />,
    { reference: options.reference ?? reference },
  );
  return { ...tools, original };
}

/** Visible text (the live region repeats titles for screen readers). */
const visible = (text: string) => screen.getAllByText(text).filter((el) => !el.closest(".sr-only"));
const status = () => screen.getAllByRole("status").find((el) => el.className.includes("sr-only"))!;
const mainButton = (name: RegExp | string) => screen.getByRole("button", { name });

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  FakeMediaRecorder.instances = [];
  URL.createObjectURL = vi.fn(() => "blob:take");
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: undefined });
  Object.defineProperty(window, "isSecureContext", { configurable: true, value: false });
});

/** Let a second of "speech" go by (takes under 0.25 s are refused before upload). */
async function speak(seconds = 1) {
  await act(async () => {
    vi.advanceTimersByTime(seconds * 1000);
  });
}

async function recordWithoutLeadIn(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("switch", { name: "Play the original first" }));
  await user.click(mainButton(/^Record/));
  await screen.findByRole("timer", { name: "Recording time" });
}

/* ---- tests ------------------------------------------------------------------------ */

describe("Record yourself — availability and privacy", () => {
  it("says so plainly when the browser cannot record, and offers no button", () => {
    installServer();
    Object.defineProperty(window, "isSecureContext", { configurable: true, value: true });
    renderPanel();
    expect(screen.getByText("Recording isn't available here")).toBeInTheDocument();
    expect(screen.getByText(/doesn't let pages use the microphone/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Record/ })).toBeNull();
  });

  it("outside a secure context, explains where it does work", () => {
    installServer();
    installMic();
    Object.defineProperty(window, "isSecureContext", { configurable: true, value: false });
    renderPanel();
    expect(screen.getByText(/http:\/\/127\.0\.0\.1 or http:\/\/localhost/)).toBeInTheDocument();
  });

  it("before recording: the privacy line, the permission note and the original's pitch", async () => {
    installServer();
    installMic();
    renderPanel({ single: true });
    expect(screen.getByRole("heading", { level: 4, name: "Record yourself" })).toBeInTheDocument();
    expect(screen.getByText("Stays on this computer.")).toBeInTheDocument();
    expect(screen.getByText(/deletes it right away; nothing is saved/)).toBeInTheDocument();
    expect(screen.getByText(/your browser will ask to use the microphone/)).toBeInTheDocument();
    expect(screen.getByText(/Pitch shapes need a few syllables/)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 5, name: "The original's pitch" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "The pitch peaks early and falls at the end." })).toBeInTheDocument();
    expect(mainButton(/^Record/)).toHaveAttribute("aria-keyshortcuts", "R");
  });
});

describe("Record yourself — the flow", () => {
  it("asks, records, measures and describes; the microphone is released after the take", async () => {
    const fetchMock = installServer();
    let grant: (stream: FakeStream) => void = () => undefined;
    installMic(() => new Promise((resolve) => (grant = resolve)));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();

    await user.click(screen.getByRole("switch", { name: "Play the original first" }));
    await user.click(mainButton(/^Record/));
    expect(mainButton(/^Cancel/)).toBeInTheDocument();
    expect(screen.getByText(/Your browser is asking whether PhonoTrainer may use it/)).toBeInTheDocument();
    expect(status()).toHaveTextContent("Waiting for microphone permission");

    const stream = new FakeStream();
    await act(async () => grant(stream));
    expect(screen.getByRole("timer", { name: "Recording time" })).toHaveTextContent("/ 0:30");
    expect(status()).toHaveTextContent("Recording. Speak now.");
    expect(FakeMediaRecorder.instances[0].mimeType).toBe("audio/webm;codecs=opus");

    await speak();
    await user.click(mainButton(/^Stop/));
    expect(await screen.findByRole("heading", { level: 5, name: "Your pitch and the original's" })).toBeInTheDocument();
    expect(stream.tracks[0].stop).toHaveBeenCalled();

    const [url, init] = fetchMock.mock.calls.find(([u]) => u === "/api/jobs/job1/compare")!;
    expect(url).toBe("/api/jobs/job1/compare");
    const form = init!.body as FormData;
    expect(form.get("start")).toBe("0.400");
    expect(form.get("end")).toBe("1.200");
    expect((form.get("file") as File).type).toBe("audio/webm;codecs=opus");

    expect(status()).toHaveTextContent("Comparison ready. The original falls at the end; yours rises.");
    const differ = screen.getByRole("list", { name: "How they differ" });
    expect(within(differ).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "The original falls at the end; yours rises.",
      "The original's pitch peaks early; yours peaks late.",
      "You took 1.7× as long: 1.20 s, against 0.70 s in the original.",
    ]);
    expect(within(screen.getByRole("list", { name: "How they're alike" })).getByRole("listitem")).toHaveTextContent(
      "A similar pitch range",
    );
    expect(mainButton(/^Record again/)).toBeInTheDocument();
  });

  it("plays the original first, then records when it ends", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    // a 60 s clip: the listening window can't run out while the test looks at it
    const { original } = renderPanel({ original: fakeOriginal(60_000) });

    await user.click(mainButton(/^Record/));
    await screen.findByText(/recording starts when the original ends/);
    expect(original.play).toHaveBeenCalledWith(SPAN);
    expect(status()).toHaveTextContent("Listen to the original");
    expect(screen.queryByRole("timer")).toBeNull();

    await act(async () => {
      vi.advanceTimersByTime(60_000 + 250 + 10);
    });
    expect(screen.getByRole("timer", { name: "Recording time" })).toBeInTheDocument();
    expect(original.stop).toHaveBeenCalled(); // the speed is given back before the mic listens
  });

  it("stops by itself at the server's limit and says why", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel({
      reference: {
        ...reference,
        recording: { max_seconds: 25, min_seconds: 0.25, max_bytes: 1e6, max_span_seconds: 30, accepted_types: [] },
      },
    });
    await recordWithoutLeadIn(user);
    expect(screen.getByRole("timer")).toHaveTextContent("/ 0:25");
    await act(async () => {
      vi.advanceTimersByTime(25_100);
    });
    await screen.findByRole("heading", { level: 5, name: "Your pitch and the original's" });
    expect(screen.getByText(/Stopped at 25 seconds, the longest a take can be/)).toBeInTheDocument();
  });

  it("works from the keyboard: R records and stops, Esc cancels and keeps nothing", async () => {
    const fetchMock = installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();
    // straight after toggling the switch (focus stays on it): R must still record
    await user.click(screen.getByRole("switch", { name: "Play the original first" }));
    expect(screen.getByRole("switch", { name: "Play the original first" })).toHaveFocus();

    await user.keyboard("r");
    await screen.findByRole("timer");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("timer")).toBeNull();
    expect(status()).toHaveTextContent("Recording cancelled");
    expect(FakeMediaRecorder.instances[0].stream.tracks[0].stop).toHaveBeenCalled();

    await user.keyboard("r");
    await screen.findByRole("timer");
    await act(async () => {
      vi.advanceTimersByTime(1000);
    });
    await user.keyboard("R");
    await screen.findByRole("heading", { level: 5, name: "Your pitch and the original's" });
    expect(fetchMock.mock.calls.filter(([u]) => u === "/api/jobs/job1/compare")).toHaveLength(1);
  });

  it("while the microphone is open, the clip's playback keys do nothing", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();
    await recordWithoutLeadIn(user);
    for (const key of [" ", "p", "s"]) {
      const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
      document.body.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    }
  });
});

describe("Record yourself — honest problems", () => {
  it("a blocked microphone says how to allow it", async () => {
    installServer();
    installMic(async () => {
      throw new DOMException("Permission denied", "NotAllowedError");
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();
    await user.click(mainButton(/^Record/));
    await waitFor(() => expect(visible("Microphone blocked")).toHaveLength(1));
    expect(screen.getByText(/allow the microphone, then press Try again/)).toBeInTheDocument();
    expect(mainButton(/^Try again/)).toBeInTheDocument();
    expect(status()).toHaveTextContent("Microphone blocked");
  });

  it("no microphone at all", async () => {
    installServer();
    installMic(async () => {
      throw new DOMException("none", "NotFoundError");
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();
    await user.click(mainButton(/^Record/));
    await waitFor(() => expect(visible("No microphone found")).toHaveLength(1));
  });

  it("a take with no voice can still be played back to hear what went wrong", async () => {
    installServer(() => json({ detail: "no voice could be heard in the recording", code: "no_voice" }, 422));
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();
    await recordWithoutLeadIn(user);
    await speak();
    await user.click(mainButton(/^Stop/));
    await waitFor(() => expect(visible("We couldn't hear a voice")).toHaveLength(1));
    expect(screen.getByRole("button", { name: /^Yours/ })).toBeInTheDocument();
    expect(mainButton(/^Try again/)).toBeInTheDocument();
  });
});

describe("Record yourself — listening and the chart", () => {
  async function toResult(user: ReturnType<typeof userEvent.setup>) {
    await recordWithoutLeadIn(user);
    await speak();
    await user.click(mainButton(/^Stop/));
    await screen.findByRole("heading", { level: 5, name: "Your pitch and the original's" });
  }

  it("A/B: the original through the player, yours from memory, one at a time", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { original } = renderPanel();
    await toResult(user);
    const toolbar = screen.getByRole("toolbar", { name: "Listen to both" });

    await user.click(within(toolbar).getByRole("button", { name: /^Original/ }));
    expect(original.play).toHaveBeenLastCalledWith(SPAN);
    expect(within(toolbar).getByRole("button", { name: /^Original/ })).toHaveAttribute("aria-pressed", "true");

    await user.click(within(toolbar).getByRole("button", { name: /^Yours/ }));
    expect(original.stop).toHaveBeenCalled();
    expect(within(toolbar).getByRole("button", { name: /^Yours/ })).toHaveAttribute("aria-pressed", "true");
    expect(within(toolbar).getByRole("button", { name: /^Original/ })).toHaveAttribute("aria-pressed", "false");

    await user.keyboard("a");
    expect(original.play).toHaveBeenCalledTimes(2);
  });

  it("Alternate plays original, yours, original… and says which", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { original } = renderPanel({ original: fakeOriginal(60_000) });
    await toResult(user);

    await user.keyboard("A"); // Shift+A
    expect(screen.getByRole("button", { name: /^Alternate/ })).toHaveAttribute("aria-pressed", "true");
    expect(status()).toHaveTextContent("Original");
    await act(async () => {
      vi.advanceTimersByTime(60_000 + 50 + 500 + 10);
    });
    expect(status()).toHaveTextContent("Yours");
    expect(screen.getByRole("button", { name: /^Yours/ })).toHaveAttribute("aria-pressed", "false"); // alternate, not B
    await user.click(screen.getByRole("button", { name: /^Alternate/ }));
    expect(screen.getByRole("button", { name: /^Alternate/ })).toHaveAttribute("aria-pressed", "false");
    expect(original.play).toHaveBeenCalledTimes(1);
  });

  it("tells the lines apart without color: style, markers, labels, legend and a table", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();
    await toResult(user);

    const chart = screen.getByRole("img", {
      name: "The original falls at the end; yours rises. The original's pitch peaks early; yours peaks late.",
    });
    const original = chart.querySelector('[data-series="original"]')!;
    const take = chart.querySelector('[data-series="take"]')!;
    expect(original.getAttribute("stroke-dasharray")).toBeNull();
    expect(take.getAttribute("stroke-dasharray")).toBe("6 4");
    // no hue: both lines are ink tokens (hue is reserved for the phenomenon families)
    expect(original.getAttribute("stroke")).toBe("var(--data-ink)");
    expect(take.getAttribute("stroke")).toBe("var(--ink)");
    // a gap where the take is unvoiced: the path lifts the pen (two subpaths)
    expect(take.getAttribute("d")!.match(/M/g)!.length).toBe(2);
    expect(chart.querySelector('[data-label="original"] circle')).not.toBeNull();
    expect(chart.querySelector('[data-label="take"] rect')).not.toBeNull();
    expect(within(chart).getByText("Original ↘")).toBeInTheDocument();
    expect(within(chart).getByText("You ↗")).toBeInTheDocument();
    expect(screen.getByText(/is shown squeezed to the original’s length/)).toBeInTheDocument();

    // keyboard crosshair: the tooltip lists both lines, and ← stays in the chart
    chart.focus();
    fireEvent.keyDown(chart, { key: "End" });
    const tip = screen.getAllByRole("status").find((el) => el.textContent?.includes("Original") && el.textContent.includes("You"))!;
    expect(tip).toHaveTextContent("0.70 s");

    await user.click(screen.getAllByRole("button", { name: "Show as table" })[0]);
    expect(screen.getByRole("columnheader", { name: "Time in the original (s)" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "You (st)" })).toBeInTheDocument();
    expect(screen.getAllByRole("cell", { name: "—" }).length).toBeGreaterThan(0);
    expect(screen.getByRole("cell", { name: "150 Hz" })).toBeInTheDocument();
  });

  it("Discard forgets the take and gives focus back to Record", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPanel();
    await toResult(user);
    await user.click(screen.getByRole("button", { name: "Discard" }));
    expect(screen.queryByRole("heading", { name: "Your pitch and the original's" })).toBeNull();
    expect(mainButton(/^Record/)).toHaveFocus();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:take");
  });

  it("has no axe violations before and after a take", async () => {
    installServer();
    installMic();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { container } = renderPanel();
    await screen.findByRole("heading", { level: 5, name: "The original's pitch" });
    await expectNoAxeViolations(container);
    await toResult(user);
    await user.click(screen.getByRole("button", { name: /About this comparison/ }));
    await expectNoAxeViolations(container);
  });
});

describe("Record yourself — inside the Practice step", () => {
  it("plays the original at normal speed, gives the speed back, and silences shadowing while recording", async () => {
    installServer();
    installMic();
    const player = fakePlayer({ rate: 0.75 });
    // a 5.9 s phrase (still "the whole phrase": ≤ 6 s), so the listening window is wide
    const segment = { ...analysis.segments[0], end: analysis.segments[0].start + 5.9 };
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderPage(<PracticeStep word={segment.words[1]} next={null} segment={segment} canPlay jobId="job1" />, {
      player,
    });

    await user.click(screen.getByRole("button", { name: /^Record/ }));
    await screen.findByText(/recording starts when the original ends/);
    expect(player.setRate).toHaveBeenLastCalledWith(1);
    expect(player.play).toHaveBeenLastCalledWith({ start: segment.start, end: segment.end }); // the phrase
    expect(screen.getByRole("button", { name: "Start shadowing" })).toBeDisabled();

    await act(async () => {
      vi.advanceTimersByTime(5900 + 250 + 10);
    });
    expect(player.setRate).toHaveBeenLastCalledWith(0.75);
    await user.click(screen.getByRole("button", { name: /^Stop/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Start shadowing" })).toBeEnabled());
  });

  it("without the analysis id there is nothing to compare against: no recording", () => {
    installServer();
    installMic();
    const segment = analysis.segments[0];
    renderPage(<PracticeStep word={segment.words[1]} next={null} segment={segment} canPlay />, { player: fakePlayer() });
    expect(screen.queryByRole("heading", { name: "Record yourself" })).toBeNull();
  });
});
