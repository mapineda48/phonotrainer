/** Record yourself — the pure parts: browser support, microphone settings, the input
 *  level, the copy for every problem, the privacy line and how the server's observations
 *  are grouped. Nothing here touches the microphone; useRecorder does. */

import type { PitchContour, RecordingLimits, TakeObservation, TakePitchContour } from "../../../../types";

/** What the server allows when it does not say (servers before API 5 cannot compare). */
export const DEFAULT_LIMITS: RecordingLimits = {
  max_seconds: 30,
  min_seconds: 0.25,
  max_bytes: 8 * 1024 * 1024,
  max_span_seconds: 30,
  accepted_types: ["audio/webm", "audio/ogg", "audio/mp4", "audio/wav"],
};

/* ---- support ------------------------------------------------------------------------ */

export type Support = "ok" | "insecure" | "unsupported";

/** Recording needs a secure context (127.0.0.1 and localhost count), getUserMedia and
 *  MediaRecorder. */
export function recordingSupport(win: Window & typeof globalThis = window): Support {
  const hasApis =
    typeof win.navigator?.mediaDevices?.getUserMedia === "function" && typeof win.MediaRecorder === "function";
  // browsers hide mediaDevices altogether outside a secure context
  if (!win.isSecureContext) return "insecure";
  return hasApis ? "ok" : "unsupported";
}

/** Raw input: noise suppression and automatic gain damage quiet voicing and the pitch
 *  track, and nothing plays while recording, so echo cancellation is not needed. */
export const MIC_CONSTRAINTS: MediaStreamConstraints = {
  audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
};

/** What the server reads: WebM/Opus (Chrome, Firefox), Ogg/Opus, MP4/AAC (Safari). */
const PREFERRED_TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4"];

export function pickMimeType(isTypeSupported: ((type: string) => boolean) | undefined): string | undefined {
  if (!isTypeSupported) return undefined;
  return PREFERRED_TYPES.find((type) => {
    try {
      return isTypeSupported(type);
    } catch {
      return false;
    }
  });
}

/* ---- input level ---------------------------------------------------------------------- */

/** Below this RMS (dBFS) the voice is barely there. */
export const QUIET_DBFS = -45;
/** A peak above this is clipping. */
export const CLIP_DBFS = -1;
/** How long a take may stay quiet before the hint shows (s). */
export const QUIET_HINT_AFTER_S = 2;

export interface Level {
  rmsDb: number;
  peakDb: number;
}

export function levelOf(samples: ArrayLike<number>): Level {
  let sum = 0;
  let peak = 0;
  for (let i = 0; i < samples.length; i += 1) {
    const v = samples[i];
    sum += v * v;
    peak = Math.max(peak, Math.abs(v));
  }
  const rms = samples.length ? Math.sqrt(sum / samples.length) : 0;
  const db = (x: number) => (x > 0 ? 20 * Math.log10(x) : -Infinity);
  return { rmsDb: db(rms), peakDb: db(peak) };
}

export type LevelWord = "quiet" | "good" | "very loud";

export function levelWord(level: Level): LevelWord {
  if (level.peakDb > CLIP_DBFS) return "very loud";
  if (level.rmsDb < QUIET_DBFS) return "quiet";
  return "good";
}

/** 0–100 for the meter: −60 dBFS is empty, 0 dBFS full. */
export function levelPercent(level: Level): number {
  if (!Number.isFinite(level.rmsDb)) return 0;
  return Math.max(0, Math.min(100, Math.round(((level.rmsDb + 60) / 60) * 100)));
}

/* ---- problems ------------------------------------------------------------------------- */

export interface Copy {
  title: string;
  body: string;
}

export type MicProblem = "blocked" | "no-device" | "busy" | "other";

/** getUserMedia's DOMException → what the learner can do about it. */
export function micProblem(error: unknown): MicProblem {
  const name = typeof error === "object" && error !== null ? (error as { name?: string }).name : "";
  if (name === "NotAllowedError" || name === "SecurityError" || name === "PermissionDeniedError") return "blocked";
  if (name === "NotFoundError" || name === "OverconstrainedError" || name === "DevicesNotFoundError") return "no-device";
  if (name === "NotReadableError" || name === "AbortError" || name === "TrackStartError") return "busy";
  return "other";
}

export const MIC_COPY: Record<MicProblem, Copy> = {
  blocked: {
    title: "Microphone blocked",
    body: "Your browser blocked the microphone for this page. Click the icon at the left of the address bar, allow the microphone, then press Try again.",
  },
  "no-device": {
    title: "No microphone found",
    body: "Connect a microphone or a headset, then press Try again.",
  },
  busy: {
    title: "Microphone busy",
    body: "Another app is using the microphone. Close it, then press Try again.",
  },
  other: {
    title: "The microphone didn't start",
    body: "The browser couldn't open the microphone. Press Try again; if it keeps failing, reload the page.",
  },
};

/** The server's `code` (or none, for a network error) → copy. */
export function measureCopy(code: string | null, detail: string, maxSeconds: number): Copy {
  switch (code) {
    case "no_voice":
      return {
        title: "We couldn't hear a voice",
        body: "Check that the right microphone is selected and not muted. Play your take to hear what was recorded.",
      };
    case "too_short":
      return { title: "That was very short", body: "Record the whole word or phrase." };
    case "too_long":
      return { title: "Too long", body: `Takes stop at ${Math.round(maxSeconds)} seconds.` };
    case "too_large":
    case "unsupported_type":
    case "undecodable":
    case "empty":
      return {
        title: "Couldn't read the recording",
        body: `This browser recorded in a format PhonoTrainer couldn't read (${detail}).`,
      };
    case "ffmpeg_missing":
      return {
        title: "Can't measure recordings",
        body: "ffmpeg isn't installed on this computer; PhonoTrainer needs it to read recordings.",
      };
    default:
      return { title: "Couldn't measure the take", body: detail };
  }
}

/* ---- privacy ------------------------------------------------------------------------ */

export function isLoopback(hostname: string): boolean {
  const host = hostname.replace(/^\[|\]$/g, "");
  return host === "localhost" || host === "::1" || /^127(\.\d{1,3}){3}$/.test(host);
}

/** The privacy line has to stay true: "on this computer" only when the server is. */
export function privacyCopy(hostname: string): { strong: string; rest: string } {
  if (isLoopback(hostname)) {
    return {
      strong: "Stays on this computer.",
      rest: "PhonoTrainer measures your recording on this machine and deletes it right away; nothing is saved. You can replay it until you leave this word.",
    };
  }
  return {
    strong: `Sent only to the PhonoTrainer server at ${hostname},`,
    rest: "measured there and deleted right away; nothing is saved. You can replay it until you leave this word.",
  };
}

/* ---- the comparison ---------------------------------------------------------------- */

const DIFFER = new Set(["mismatch", "different", "narrower", "wider", "longer", "shorter", "more", "fewer"]);
const ALIKE = new Set(["match", "same", "similar"]);

export interface ObservationGroups {
  differ: TakeObservation[];
  alike: TakeObservation[];
  unmeasured: TakeObservation[];
}

/** Group by what the server says (`kind`), keeping its order. Never a verdict. */
export function groupObservations(observations: TakeObservation[]): ObservationGroups {
  return {
    differ: observations.filter((o) => DIFFER.has(o.kind)),
    alike: observations.filter((o) => ALIKE.has(o.kind)),
    unmeasured: observations.filter((o) => !DIFFER.has(o.kind) && !ALIKE.has(o.kind)),
  };
}

/** The chart's summary: how each one ends and where it peaks. */
export function comparisonSummary(observations: TakeObservation[]): string {
  const said = observations
    .filter((o) => (o.key === "ending" || o.key === "peak") && o.kind !== "unmeasured")
    .map((o) => o.text);
  return said.length ? said.join(" ") : "There's too little voicing to compare the pitch shapes; see the notes below.";
}

const ENDING: Record<string, string> = { rising: "rises", falling: "falls", flat: "stays level" };
const PLACE: Record<string, string> = { early: "early", middle: "in the middle", late: "late" };

/** The clip's own summary, before there is a take to compare. */
export function nativeSummary(native: PitchContour): string {
  if (native.median_hz === null) {
    return "No pitch could be measured in the original here (whispered speech, music or noise).";
  }
  const parts: string[] = [];
  if (native.range_st !== null && native.range_st < 2) parts.push("The pitch stays fairly level");
  else if (native.peak) parts.push(`The pitch peaks ${PLACE[native.peak.where]}`);
  if (native.final_contour) parts.push(`${parts.length ? "and" : "The pitch"} ${ENDING[native.final_contour]} at the end`);
  return parts.length ? `${parts.join(" ")}.` : "There's too little voicing here to read a pitch shape.";
}

export const CONTOUR_ARROW: Record<string, string> = { rising: "↗", falling: "↘", flat: "→" };

/** Loudness match for "Yours": the take's loudest speech frame is brought to the
 *  clip's, within ±12 dB (both intensities come from the same measurement). */
export const MAX_GAIN_DB = 12;

function maxDbInSpeech(contour: PitchContour): number | null {
  if (!contour.speech) return null;
  let best: number | null = null;
  for (const [t, , , db] of contour.track) {
    if (db === null || t < contour.speech.start || t > contour.speech.end) continue;
    if (best === null || db > best) best = db;
  }
  return best;
}

export function takeGainDb(native: PitchContour, take: PitchContour): number {
  const a = maxDbInSpeech(native);
  const b = maxDbInSpeech(take);
  if (a === null || b === null) return 0;
  return Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, a - b));
}

/** Seconds of margin kept around the learner's speech when playing it back. */
export const TAKE_MARGIN_S = 0.15;

/** The part of the take to play: the speech plus a margin, or all of it. */
export function takeRange(take: TakePitchContour | null, seconds: number): { start: number; end: number } {
  if (!take?.speech) return { start: 0, end: seconds };
  return {
    start: Math.max(0, take.speech.start - TAKE_MARGIN_S),
    end: Math.min(take.duration_s || seconds, take.speech.end + TAKE_MARGIN_S),
  };
}

/** 2.4 → "0:02.4"; the limit 30 → "0:30". */
export function fmtClock(seconds: number, tenths = true): string {
  const s = Math.max(0, seconds);
  const min = Math.floor(s / 60);
  const rest = s - min * 60;
  const text = tenths ? (Math.floor(rest * 10) / 10).toFixed(1).padStart(4, "0") : String(Math.floor(rest)).padStart(2, "0");
  return `${min}:${text}`;
}

/** "+2.1", "−0.5", "—" (a typographic minus, as in the rest of the UI). */
export function fmtSt(value: number | null): string {
  if (value === null) return "—";
  const rounded = Math.round(value * 10) / 10;
  if (rounded === 0) return "0.0";
  return `${rounded > 0 ? "+" : "−"}${Math.abs(rounded).toFixed(1)}`;
}
