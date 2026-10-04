import { describe, expect, it } from "vitest";

import type { PitchContour, TakeObservation } from "../../../../types";
import {
  comparisonSummary,
  fmtClock,
  fmtSt,
  groupObservations,
  isLoopback,
  levelOf,
  levelPercent,
  levelWord,
  measureCopy,
  micProblem,
  nativeSummary,
  pickMimeType,
  privacyCopy,
  recordingSupport,
  takeGainDb,
  takeRange,
} from "./recording";

const obs = (key: TakeObservation["key"], kind: TakeObservation["kind"], text = `${key} ${kind}`): TakeObservation => ({
  key,
  kind,
  native: null,
  take: null,
  text,
});

function contour(over: Partial<PitchContour> = {}): PitchContour {
  return {
    start: 0,
    end: 1,
    speech: { start: 0.1, end: 0.9 },
    speech_s: 0.8,
    voiced_s: 0.7,
    median_hz: 200,
    f0_floor: 100,
    f0_ceiling: 400,
    range_st: 6,
    final_contour: "falling",
    final_slope_st: -8,
    peak: { time: 0.2, position: 0.1, where: "early", st: 3 },
    pauses: [],
    track: [
      [0.05, null, null, 40],
      [0.5, 200, 0, 70],
      [0.95, null, null, 80], // outside the speech: ignored for loudness
    ],
    ...over,
  };
}

describe("support", () => {
  const win = (over: Record<string, unknown>) =>
    ({
      isSecureContext: true,
      navigator: { mediaDevices: { getUserMedia: () => undefined } },
      MediaRecorder: function MediaRecorder() {},
      ...over,
    }) as unknown as Window & typeof globalThis;

  it("needs a secure context, getUserMedia and MediaRecorder", () => {
    expect(recordingSupport(win({}))).toBe("ok");
    expect(recordingSupport(win({ isSecureContext: false }))).toBe("insecure");
    expect(recordingSupport(win({ MediaRecorder: undefined }))).toBe("unsupported");
    expect(recordingSupport(win({ navigator: {} }))).toBe("unsupported");
  });

  it("picks the first container the server reads that the browser records", () => {
    expect(pickMimeType((t) => t.startsWith("audio/webm"))).toBe("audio/webm;codecs=opus");
    expect(pickMimeType((t) => t === "audio/mp4")).toBe("audio/mp4"); // Safari
    expect(pickMimeType(() => false)).toBeUndefined();
    expect(pickMimeType(undefined)).toBeUndefined();
  });
});

describe("level", () => {
  it("measures RMS and peak in dBFS and names them in words", () => {
    const loud = levelOf(new Float32Array(100).fill(0.5));
    expect(loud.rmsDb).toBeCloseTo(-6.02, 1);
    expect(levelWord(loud)).toBe("good");
    expect(levelWord(levelOf(new Float32Array(100).fill(0.001)))).toBe("quiet");
    expect(levelWord(levelOf(Float32Array.from([0.1, -1, 0.1])))).toBe("very loud");
    expect(levelPercent(levelOf(new Float32Array(10)))).toBe(0);
    expect(levelPercent({ rmsDb: -30, peakDb: -20 })).toBe(50);
  });
});

describe("problems", () => {
  it("maps getUserMedia errors to what the learner can do", () => {
    expect(micProblem(new DOMException("no", "NotAllowedError"))).toBe("blocked");
    expect(micProblem(new DOMException("no", "NotFoundError"))).toBe("no-device");
    expect(micProblem(new DOMException("no", "NotReadableError"))).toBe("busy");
    expect(micProblem(new Error("??"))).toBe("other");
    expect(micProblem(undefined)).toBe("other");
  });

  it("has plain copy for every server code", () => {
    expect(measureCopy("no_voice", "", 30).title).toBe("We couldn't hear a voice");
    expect(measureCopy("too_long", "", 30).body).toBe("Takes stop at 30 seconds.");
    expect(measureCopy("undecodable", "bad file", 30).body).toContain("bad file");
    expect(measureCopy(null, "connection refused", 30)).toEqual({
      title: "Couldn't measure the take",
      body: "connection refused",
    });
  });
});

describe("privacy", () => {
  it("only says 'on this computer' when the server is", () => {
    for (const host of ["localhost", "127.0.0.1", "127.1.2.3", "::1", "[::1]"]) expect(isLoopback(host)).toBe(true);
    for (const host of ["192.168.1.5", "phono.example", "127.0.0.1.example"]) expect(isLoopback(host)).toBe(false);
    expect(privacyCopy("127.0.0.1").strong).toBe("Stays on this computer.");
    expect(privacyCopy("192.168.1.5").strong).toContain("192.168.1.5");
    expect(privacyCopy("192.168.1.5").rest).toContain("nothing is saved");
  });
});

describe("observations", () => {
  const list = [
    obs("ending", "mismatch"),
    obs("peak", "same"),
    obs("range", "unmeasured"),
    obs("length", "longer"),
    obs("pauses", "more"),
  ];

  it("groups by what differs, what is alike and what couldn't be measured, in order", () => {
    const groups = groupObservations(list);
    expect(groups.differ.map((o) => o.key)).toEqual(["ending", "length", "pauses"]);
    expect(groups.alike.map((o) => o.key)).toEqual(["peak"]);
    expect(groups.unmeasured.map((o) => o.key)).toEqual(["range"]);
  });

  it("summarizes the chart with the ending and the peak", () => {
    expect(comparisonSummary([obs("ending", "mismatch", "A."), obs("peak", "different", "B."), obs("length", "longer")])).toBe(
      "A. B.",
    );
    expect(comparisonSummary([obs("ending", "unmeasured"), obs("peak", "unmeasured")])).toMatch(/too little voicing/);
  });

  it("describes the clip on its own", () => {
    expect(nativeSummary(contour())).toBe("The pitch peaks early and falls at the end.");
    expect(nativeSummary(contour({ range_st: 1, peak: null, final_contour: "flat" }))).toBe(
      "The pitch stays fairly level and stays level at the end.",
    );
    expect(nativeSummary(contour({ peak: null, final_contour: "rising" }))).toBe("The pitch rises at the end.");
    expect(nativeSummary(contour({ median_hz: null }))).toMatch(/No pitch could be measured/);
  });
});

describe("playback", () => {
  it("matches the take's loudness to the clip's, within ±12 dB", () => {
    expect(takeGainDb(contour(), contour({ track: [[0.5, 150, 0, 60]] }))).toBe(10);
    expect(takeGainDb(contour(), contour({ track: [[0.5, 150, 0, 40]] }))).toBe(12);
    expect(takeGainDb(contour(), contour({ track: [[0.5, 150, 0, 85]] }))).toBe(-12);
    expect(takeGainDb(contour(), contour({ speech: null }))).toBe(0);
  });

  it("plays the learner's speech with a margin, or the whole take", () => {
    const take = { ...contour({ speech: { start: 0.4, end: 1.5 } }), duration_s: 1.6 };
    expect(takeRange(take, 1.6)).toEqual({ start: 0.25, end: 1.6 });
    expect(takeRange(null, 2.2)).toEqual({ start: 0, end: 2.2 });
  });

  it("formats times and semitones", () => {
    expect(fmtClock(2.46)).toBe("0:02.4");
    expect(fmtClock(30, false)).toBe("0:30");
    expect(fmtClock(61.2)).toBe("1:01.2");
    expect(fmtSt(2.14)).toBe("+2.1");
    expect(fmtSt(-0.46)).toBe("−0.5");
    expect(fmtSt(0.01)).toBe("0.0");
    expect(fmtSt(null)).toBe("—");
  });
});
