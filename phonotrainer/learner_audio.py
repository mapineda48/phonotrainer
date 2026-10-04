"""Record yourself and compare: a learner's take, measured exactly like the
native audio, and a plain-language comparison of the two pitch contours.

The question stays the one PhonoTrainer asks everywhere — what does the native
speaker actually do here? — so nothing in this module grades the learner. Both
recordings go through the same measurement (`prosody.ProsodyExtractor`: the same
two F0 passes, the same per-turn Hirst range, the same octave folding, the same
final-contour rule), and the differences are described in words: how each one
ends, where the pitch peaks, how wide the pitch range is, how long it took,
where it paused. There is no score and no percentage of "correctness".

Takes are never kept. The upload is written to a private temporary directory,
decoded there by the system ffmpeg (a separate process, exactly as in audio.py:
see THIRD-PARTY-NOTICES §5), measured, and the directory is removed before the
answer leaves the server.

Pitch is compared in **semitones relative to each recording's own median**, so a
low adult voice imitating a child's line, or the other way round, overlays
cleanly: what is compared is the melody, not the voice.
"""

from __future__ import annotations

import math
import subprocess
import tempfile
from pathlib import Path

import numpy as np

from .audio import SAMPLE_RATE
from .prosody import (MIN_VOICED_FOR_RANGE_STATS, TIME_STEP, ProsodyExtractor,
                      contour_label)

# --- limits (the server is local-only, but it must stay safe) -----------------
# 8 MB is ~25× what 30 s of MediaRecorder Opus needs (~0.5 MB at 128 kbit/s) and
# still holds 30 s of 48 kHz stereo 16-bit WAV (5.8 MB).
MAX_UPLOAD_BYTES = 8 * 1024 * 1024
# The interface stops recording on its own at MAX_TAKE_S. MediaRecorder may
# deliver a little more after stop(), hence the slack; ffmpeg never decodes more
# than MAX_TAKE_S + 2·slack, so a tiny file claiming hours of silence cannot
# fill the disk.
MAX_TAKE_S = 30.0
TAKE_SLACK_S = 0.5
MIN_TAKE_S = 0.25
# A native span longer than this is not something to shadow in one breath (a
# Whisper segment is at most 30 s).
MAX_SPAN_S = 30.0
MIN_SPAN_S = 0.05
DECODE_TIMEOUT_S = 30.0
# Fewer voiced frames than this (0.1 s) is not a voice: a click, a breath.
MIN_TAKE_VOICED_S = MIN_VOICED_FOR_RANGE_STATS * TIME_STEP
# Audio read around the native span and its turn so Praat has context.
NATIVE_CONTEXT_S = 0.5

# What MediaRecorder produces: Chrome and Firefox record WebM/Opus (older
# Firefox Ogg/Opus), Safari MP4/AAC. WAV is accepted for tests and tools. The
# declared MIME type must agree with the file's own signature, and ffmpeg is
# then told which demuxer to use: it never guesses the format of an upload (a
# guessed playlist or concat format could make it read other local files).
CONTAINER_OF_TYPE = {
    "audio/webm": "webm", "video/webm": "webm",
    "audio/ogg": "ogg", "application/ogg": "ogg", "audio/opus": "ogg",
    "audio/mp4": "mp4", "video/mp4": "mp4", "audio/x-m4a": "mp4", "audio/m4a": "mp4",
    "audio/wav": "wav", "audio/x-wav": "wav", "audio/wave": "wav", "audio/vnd.wave": "wav",
}
DEMUXER = {"webm": "matroska", "ogg": "ogg", "mp4": "mov", "wav": "wav"}

# Only for tests: where the private temporary directories are created
# (None = the system default).
TEMP_ROOT: str | None = None

# --- speech span ----------------------------------------------------------------
# A frame is "sounding" when its intensity is within SILENCE_DB of the
# recording's loudest frame: Praat's own default for "To TextGrid (silences)"
# is -25 dB. The span is anchored on voicing (a voiced, sounding frame) and then
# grown outward over sounding frames — a voiceless onset or coda such as /s/,
# /t/ or /f/ — up to EDGE_UNVOICED_S past the voicing, bridging silent gaps up to
# BRIDGE_S (a stop closure before its release). Anything farther away is a
# breath, a click of the record button or the room, not speech.
SILENCE_DB = 25.0
EDGE_UNVOICED_S = 0.25
BRIDGE_S = 0.12
# With no voicing at all, a sounding run shorter than this is a click.
MIN_SOUNDING_S = 0.05
# A silence inside the speech lasting this long is a pause, not a stop closure
# (closures last well under 150 ms; 250 ms is the classic pause threshold of
# Goldman-Eisler's studies).
PAUSE_MIN_S = 0.25

# --- observations ---------------------------------------------------------------
# Length: within 20 % either way is "about the same". Repetitions of one phrase
# by one speaker vary by that much, so a smaller difference is not worth a remark.
LENGTH_SIMILAR_RATIO = 1.2
# Pitch range (p5–p95 of the voiced frames, in semitones): a range under
# LEVEL_RANGE_ST is "fairly level" and has no peak worth placing. Two ranges are
# reported as different only when they differ by RANGE_SIMILAR_RATIO or more AND
# by RANGE_MIN_DIFF_ST or more: Rietveld & Gussenhoven (1985) found ~1.5 st
# differences in excursion change perceived prominence, 't Hart (1981) put the
# differences listeners use nearer 3 st; 2 st sits between. The ratio alone
# would call 1 st against 2 st "twice as wide".
LEVEL_RANGE_ST = 2.0
RANGE_SIMILAR_RATIO = 1.5
RANGE_MIN_DIFF_ST = 2.0
# Peak: the highest point of the voiced contour after a 5-frame (50 ms) running
# median, which drops single-frame glitches; its place in the speech span is
# early / middle / late by thirds.
PEAK_SMOOTH_FRAMES = 5
PEAK_EARLY = 1 / 3
PEAK_LATE = 2 / 3

CONTOUR_VERB = {"rising": "rises", "falling": "falls", "flat": "stays level"}
PEAK_PLACE = {"early": "early", "middle": "in the middle", "late": "late"}


class TakeError(ValueError):
    """A recording or span that cannot be measured. `code` is stable (the
    interface branches on it); `status` is the HTTP status to answer with."""

    def __init__(self, code: str, message: str, status: int = 422):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


# --- the upload -----------------------------------------------------------------
def sniff_container(head: bytes) -> str | None:
    """The container a file's first bytes announce, or None."""
    if head.startswith(b"\x1a\x45\xdf\xa3"):                 # EBML: WebM / Matroska
        return "webm"
    if head.startswith(b"OggS"):
        return "ogg"
    if len(head) >= 12 and head[4:8] == b"ftyp":            # ISO BMFF: MP4 / M4A
        return "mp4"
    if head.startswith(b"RIFF") and head[8:12] == b"WAVE":
        return "wav"
    return None


def container_for(content_type: str | None, head: bytes) -> str:
    """Check the declared MIME type against the file's own signature."""
    base = (content_type or "").split(";")[0].strip().lower()
    declared = CONTAINER_OF_TYPE.get(base)
    if declared is None:
        raise TakeError("unsupported_type",
                        f"recordings must be WebM, Ogg, MP4 or WAV audio, not {base or 'untyped data'}",
                        415)
    found = sniff_container(head)
    if found is None:
        raise TakeError("unsupported_type",
                        "the file does not look like WebM, Ogg, MP4 or WAV audio", 415)
    if found != declared:
        raise TakeError("unsupported_type",
                        f"the file is declared as {base} but its contents are {found}", 415)
    return found


def decode(src: Path, container: str, dest: Path) -> float:
    """Decode the take to 16 kHz mono WAV — the rate the analysis reads — with
    the demuxer forced and the length capped. Returns the duration in seconds."""
    cmd = [
        "ffmpeg", "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
        "-protocol_whitelist", "file", "-f", DEMUXER[container], "-i", str(src),
        "-map", "0:a:0", "-vn", "-sn", "-dn", "-ac", "1", "-ar", str(SAMPLE_RATE),
        "-t", f"{MAX_TAKE_S + 2 * TAKE_SLACK_S:.2f}", "-f", "wav", str(dest),
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=DECODE_TIMEOUT_S)
    except subprocess.TimeoutExpired as exc:
        raise TakeError("undecodable", "decoding the recording took too long") from exc
    except FileNotFoundError as exc:
        raise TakeError("ffmpeg_missing", "ffmpeg is not installed on this computer", 500) from exc
    if proc.returncode != 0 or not dest.is_file():
        raise TakeError("undecodable",
                        f"the recording could not be decoded: {proc.stderr.strip()[-300:]}")
    import soundfile as sf

    duration = float(sf.info(str(dest)).duration)
    if duration > MAX_TAKE_S + TAKE_SLACK_S:
        raise TakeError("too_long", f"recordings are limited to {MAX_TAKE_S:.0f} seconds", 413)
    if duration < MIN_TAKE_S:
        raise TakeError("too_short", "the recording is too short to measure")
    return duration


def measure_take(data: bytes, content_type: str | None) -> dict:
    """The learner's take, measured like the native audio (see `measure`).

    The take is one speaker turn: its F0 range is adapted over the whole
    recording, as the pipeline does per Whisper segment. Raises TakeError."""
    if not data:
        raise TakeError("empty", "the recording arrived empty")
    if len(data) > MAX_UPLOAD_BYTES:
        raise TakeError("too_large",
                        f"recordings are limited to {MAX_UPLOAD_BYTES // (1024 * 1024)} MB", 413)
    container = container_for(content_type, data[:16])
    with tempfile.TemporaryDirectory(prefix="phonotrainer-take-", dir=TEMP_ROOT) as tmp:
        src = Path(tmp) / f"upload.{container}"
        src.write_bytes(data)
        wav = Path(tmp) / "decoded.wav"
        duration = decode(src, container, wav)
        extractor = ProsodyExtractor(wav)          # parselmouth reads it into memory
        contour = measure(extractor, extractor.snd.xmin, extractor.snd.xmax)
    contour["duration_s"] = round(duration, 3)
    if contour["voiced_s"] < MIN_TAKE_VOICED_S:
        raise TakeError("no_voice", "no voice could be heard in the recording")
    return contour


# --- the native audio --------------------------------------------------------------
def measure_native(wav_path: str | Path, start: float, end: float,
                   turn: tuple[float, float] | None = None) -> dict:
    """The native contour of [start, end] in the analysis audio, measured like a
    take. `turn` is the Whisper segment the span belongs to: its F0 range is
    adapted over that turn, exactly as the pipeline did."""
    import parselmouth
    import soundfile as sf

    check_span(start, end)
    info = sf.info(str(wav_path))
    sr = info.samplerate
    lo = min(start, turn[0]) if turn else start
    hi = max(end, turn[1]) if turn else end
    lo = max(0.0, lo - NATIVE_CONTEXT_S)
    hi = min(float(info.duration), hi + NATIVE_CONTEXT_S)
    if end <= lo or start >= hi or hi - lo <= MIN_SPAN_S:
        raise TakeError("bad_span", "the span lies outside this analysis's audio", 400)
    i0, i1 = int(lo * sr), int(math.ceil(hi * sr))
    samples, _ = sf.read(str(wav_path), start=i0, stop=i1, dtype="float64", always_2d=True)
    sound = parselmouth.Sound(np.ascontiguousarray(samples.mean(axis=1)),
                              sampling_frequency=sr, start_time=i0 / sr)
    extractor = ProsodyExtractor(sound, spans=[turn] if turn else None)
    return measure(extractor, max(start, lo), min(end, hi))


def check_span(start: float, end: float) -> None:
    if not (math.isfinite(start) and math.isfinite(end)) or start < 0 or end <= start:
        raise TakeError("bad_span", "the span must satisfy 0 ≤ start < end", 400)
    if end - start > MAX_SPAN_S:
        raise TakeError("bad_span", f"spans are limited to {MAX_SPAN_S:.0f} seconds", 400)
    if end - start < MIN_SPAN_S:
        raise TakeError("bad_span", "the span is too short to measure", 400)


# --- one contour --------------------------------------------------------------------
def measure(extractor: ProsodyExtractor, start: float, end: float) -> dict:
    """The contour of [start, end], in the shape the API returns for both the
    native span and the take:

    - `track`: one row per 10 ms frame, `[t, hz, st, db]`; `hz` and `st` are
      null on unvoiced frames (nothing is interpolated: the chart draws the gaps),
      `st` is semitones from this recording's median F0, `db` the intensity;
    - `speech`: the span without leading and trailing silence (see SILENCE_DB),
      `speech_s` its length, `voiced_s` the voiced time inside it;
    - `range_st`, `final_contour` (null when the end has too little voicing to
      tell — not "flat"), `final_slope_st`, `peak`, `pauses`.
    """
    mask = (extractor.f0_times >= start) & (extractor.f0_times <= end)
    times = np.asarray(extractor.f0_times[mask], dtype=float)
    hz = np.asarray(extractor.f0[mask], dtype=float)
    db = _intensity_at(extractor, times)
    sounding = _sounding(db)
    span = speech_span(times, hz, sounding)
    floor, ceiling = extractor.range_at(start, end)

    out = {
        "start": round(float(start), 3),
        "end": round(float(end), 3),
        "speech": None, "speech_s": None, "voiced_s": 0.0, "median_hz": None,
        "f0_floor": floor, "f0_ceiling": ceiling,
        "range_st": None, "final_contour": None, "final_slope_st": None,
        "peak": None, "pauses": [],
        "track": [],
    }
    inside = np.zeros(len(times), dtype=bool)
    if span is not None:
        inside = (times >= span[0] - 1e-9) & (times <= span[1] + 1e-9)
        out["speech"] = {"start": round(span[0], 3), "end": round(span[1], 3)}
        out["speech_s"] = round(span[1] - span[0], 3)
        out["pauses"] = _pauses(times, sounding, inside)

    voiced = ~np.isnan(hz)
    core = voiced & inside
    st = np.full(len(times), np.nan)
    if core.any():
        median = float(np.median(hz[core]))
        out["median_hz"] = round(median, 1)
        st[voiced] = 12.0 * np.log2(hz[voiced] / median)
        out["voiced_s"] = round(int(core.sum()) * TIME_STEP, 2)
        if core.sum() >= MIN_VOICED_FOR_RANGE_STATS:
            p5, p95 = np.percentile(st[core], [5, 95])
            out["range_st"] = round(float(p95 - p5), 1)
        slope = extractor.final_slope(span[0], span[1])
        if slope is not None:
            out["final_contour"] = contour_label(slope)
            out["final_slope_st"] = round(slope, 1)
        if out["range_st"] is not None and out["range_st"] >= LEVEL_RANGE_ST:
            out["peak"] = _peak(times[core], st[core], span)

    out["track"] = [
        [round(float(t), 3),
         None if np.isnan(f) else round(float(f), 1),
         None if np.isnan(s) else round(float(s), 2),
         None if not np.isfinite(d) else round(float(d), 1)]
        for t, f, s, d in zip(times, hz, st, db)
    ]
    return out


def _intensity_at(extractor: ProsodyExtractor, times: np.ndarray) -> np.ndarray:
    values = np.asarray(extractor.intensity, dtype=float)
    at = np.asarray(extractor.int_times, dtype=float)
    finite = np.isfinite(values)
    if len(times) == 0 or not finite.any():
        return np.full(len(times), np.nan)
    return np.interp(times, at[finite], values[finite])


def _sounding(db: np.ndarray) -> np.ndarray:
    finite = np.isfinite(db)
    if not finite.any():
        return np.zeros(len(db), dtype=bool)
    return finite & (db >= np.max(db[finite]) - SILENCE_DB)


def _runs(flags: np.ndarray) -> list[np.ndarray]:
    """Index arrays of the consecutive True stretches of `flags`."""
    idx = np.nonzero(flags)[0]
    if len(idx) == 0:
        return []
    return np.split(idx, np.nonzero(np.diff(idx) > 1)[0] + 1)


def speech_span(times: np.ndarray, hz: np.ndarray,
                sounding: np.ndarray) -> tuple[float, float] | None:
    """(start, end) of the speech: anchored on voiced sounding frames and grown
    over the sounding frames next to them (see SILENCE_DB and EDGE_UNVOICED_S).
    Without any voicing, the first to the last sounding run longer than a click.
    None when nothing sounds."""
    if len(times) == 0:
        return None
    anchor = np.nonzero(~np.isnan(hz) & sounding)[0]
    if len(anchor):
        first = _grow(times, sounding, int(anchor[0]), -1)
        last = _grow(times, sounding, int(anchor[-1]), +1)
        return float(times[first]), float(times[last])
    runs = [r for r in _runs(sounding) if len(r) * TIME_STEP >= MIN_SOUNDING_S]
    if not runs:
        return None
    return float(times[runs[0][0]]), float(times[runs[-1][-1]])


def _grow(times: np.ndarray, sounding: np.ndarray, i: int, step: int) -> int:
    """Walk from frame `i` in direction `step` over sounding frames, bridging
    short silences, never farther than EDGE_UNVOICED_S."""
    best, gap, j = i, 0, i + step
    while 0 <= j < len(times) and abs(times[j] - times[i]) <= EDGE_UNVOICED_S + 1e-9:
        if sounding[j]:
            best, gap = j, 0
        else:
            gap += 1
            if gap * TIME_STEP > BRIDGE_S:
                break
        j += step
    return best


def _pauses(times: np.ndarray, sounding: np.ndarray, inside: np.ndarray) -> list[dict]:
    return [{"start": round(float(times[r[0]]), 3), "end": round(float(times[r[-1]]), 3)}
            for r in _runs(inside & ~sounding) if len(r) * TIME_STEP >= PAUSE_MIN_S]


def _running_median(values: np.ndarray, width: int) -> np.ndarray:
    if len(values) < width:
        return values
    half = width // 2
    padded = np.pad(values, half, mode="edge")
    return np.median(np.lib.stride_tricks.sliding_window_view(padded, width), axis=1)


def _peak(times: np.ndarray, st: np.ndarray, span: tuple[float, float]) -> dict:
    smooth = _running_median(st, PEAK_SMOOTH_FRAMES)
    # the median flattens a sharp top into a short plateau: take its middle
    tops = np.flatnonzero(smooth >= smooth.max() - 1e-9)
    k = int(tops[len(tops) // 2])
    length = span[1] - span[0]
    position = 0.5 if length <= 0 else float(np.clip((times[k] - span[0]) / length, 0, 1))
    where = "early" if position < PEAK_EARLY else "late" if position > PEAK_LATE else "middle"
    return {"time": round(float(times[k]), 3), "position": round(position, 2),
            "where": where, "st": round(float(smooth[k]), 1)}


# --- the comparison -----------------------------------------------------------------
def compare(native: dict, take: dict) -> dict:
    """Overlay the two contours and describe how they differ. No score.

    **Time normalization: a linear stretch, not DTW.** The take's speech span is
    stretched or squeezed uniformly onto the native one, so the overlay starts
    and ends together and everything in between keeps the learner's own timing.
    DTW would bend the take until it resembled the original, which is the wrong
    thing to do here:
    - it erases the timing differences the comparison exists to show (a peak
      placed late, a stretched final syllable, a pause): DTW absorbs them into
      the warp, and the overlay looks closer than the speech was;
    - on F0 alone it is ill-posed: a contour is smooth and has unvoiced gaps, so
      DTW matches pitch *values*, and a take that rises where the original falls
      gets warped onto whichever native frames happen to share its pitch;
    - aligning by spectra (MFCC DTW) would fix the second point but not the
      first, and adds a feature extractor for little gain on a 1–5 s span.
    The uniform stretch factor is reported (`time_scale`, the length ratio),
    so nothing is hidden.

    `overlay` rows are `[x, native_st, take_st]` every 10 ms of native speech:
    `x` is seconds from the start of the native speech; the take is sampled at
    `take.speech.start + x · time_scale`. Either value is null where that
    recording is unvoiced.
    """
    overlay, scale = _overlay(native, take)
    observations = [o for o in (
        _ending(native, take),
        _peak_observation(native, take),
        _range_observation(native, take),
        _length_observation(native, take),
        _pause_observation(native, take),
    ) if o is not None]
    return {"time_scale": scale, "overlay": overlay, "observations": observations}


def _overlay(native: dict, take: dict) -> tuple[list[list], float | None]:
    ns, ts = native.get("speech"), take.get("speech")
    if not ns or not ts:
        return [], None
    n_len, t_len = ns["end"] - ns["start"], ts["end"] - ts["start"]
    if n_len <= 0 or t_len <= 0:
        return [], None
    scale = t_len / n_len
    take_t = np.array([row[0] for row in take["track"]], dtype=float)
    take_st = np.array([np.nan if row[2] is None else row[2] for row in take["track"]],
                       dtype=float)
    rows = []
    for t, _hz, st, _db in native["track"]:
        if t < ns["start"] - 1e-9 or t > ns["end"] + 1e-9:
            continue
        x = t - ns["start"]
        mapped = min(max(ts["start"] + x * scale, ts["start"]), ts["end"])
        rows.append([round(x, 3), st, _sample(take_t, take_st, mapped)])
    return rows, round(scale, 2)


def _sample(times: np.ndarray, values: np.ndarray, t: float) -> float | None:
    """`values` at time `t`: linear between two voiced neighbors, the nearer
    voiced one within one frame, else None (unvoiced)."""
    if len(times) == 0:
        return None
    j = int(np.searchsorted(times, t))
    lo, hi = max(j - 1, 0), min(j, len(times) - 1)
    a, b = values[lo], values[hi]
    if not np.isnan(a) and not np.isnan(b) and times[hi] > times[lo]:
        w = (t - times[lo]) / (times[hi] - times[lo])
        return round(float(a + w * (b - a)), 2)
    for k in sorted((lo, hi), key=lambda k: abs(times[k] - t)):
        if not np.isnan(values[k]) and abs(times[k] - t) <= TIME_STEP + 1e-9:
            return round(float(values[k]), 2)
    return None


def _who(native_missing: bool, take_missing: bool) -> str:
    if native_missing and take_missing:
        return "either recording"
    return "the original" if native_missing else "your take"


def _ending(native: dict, take: dict) -> dict:
    n, t = native["final_contour"], take["final_contour"]
    obs = {"key": "ending", "native": n, "take": t}
    if n is None or t is None:
        return {**obs, "kind": "unmeasured",
                "text": f"There is too little continuous voicing at the end of "
                        f"{_who(n is None, t is None)} to tell how it ends."}
    if n == t:
        return {**obs, "kind": "match", "text": f"Both {_plural(CONTOUR_VERB[n])} at the end."}
    return {**obs, "kind": "mismatch",
            "text": f"The original {CONTOUR_VERB[n]} at the end; yours {CONTOUR_VERB[t]}."}


def _plural(verb: str) -> str:
    """'rises' → 'rise', 'stays level' → 'stay level' (after "Both")."""
    head, _, rest = verb.partition(" ")
    head = head[:-1] if head.endswith("s") else head
    return f"{head} {rest}".strip()


def _peak_where(contour: dict) -> str | None:
    if contour["range_st"] is None:
        return None
    return contour["peak"]["where"] if contour["peak"] else "level"


def _peak_observation(native: dict, take: dict) -> dict:
    n, t = _peak_where(native), _peak_where(take)
    obs = {"key": "peak", "native": n, "take": t}
    if n is None or t is None:
        return {**obs, "kind": "unmeasured",
                "text": f"There is too little voicing in {_who(n is None, t is None)} "
                        "to place a pitch peak."}
    if n == t:
        text = ("Both stay fairly level, with no clear pitch peak." if n == "level"
                else f"Both reach their highest pitch {PEAK_PLACE[n]}.")
        return {**obs, "kind": "same", "text": text}
    native_part = ("The original stays fairly level" if n == "level"
                   else f"The original's pitch peaks {PEAK_PLACE[n]}")
    take_part = "yours stays fairly level" if t == "level" else f"yours peaks {PEAK_PLACE[t]}"
    return {**obs, "kind": "different", "text": f"{native_part}; {take_part}."}


def _range_observation(native: dict, take: dict) -> dict:
    n, t = native["range_st"], take["range_st"]
    obs = {"key": "range", "native": n, "take": t, "ratio": None}
    if n is None or t is None:
        return {**obs, "kind": "unmeasured",
                "text": f"There is too little voicing in {_who(n is None, t is None)} "
                        "to measure a pitch range."}
    ratio = round(t / n, 2) if n > 0 else None
    obs["ratio"] = ratio
    if n < LEVEL_RANGE_ST and t < LEVEL_RANGE_ST:
        return {**obs, "kind": "similar",
                "text": f"Both stay within about {LEVEL_RANGE_ST:.0f} semitones: "
                        "a fairly level pitch."}
    different = abs(t - n) >= RANGE_MIN_DIFF_ST and (
        ratio is None or ratio >= RANGE_SIMILAR_RATIO or ratio <= 1 / RANGE_SIMILAR_RATIO)
    if not different:
        return {**obs, "kind": "similar",
                "text": f"A similar pitch range: {t:.1f} semitones in yours, "
                        f"{n:.1f} in the original."}
    kind = "narrower" if t < n else "wider"
    times = f" ({ratio:.1f}×)" if ratio is not None else ""
    return {**obs, "kind": kind,
            "text": f"Your pitch moves over a {kind} range: {t:.1f} semitones, "
                    f"against {n:.1f} in the original{times}."}


def _length_observation(native: dict, take: dict) -> dict:
    n, t = native["speech_s"], take["speech_s"]
    obs = {"key": "length", "native": n, "take": t, "ratio": None}
    if not n or not t:
        return {**obs, "kind": "unmeasured",
                "text": f"Could not tell where the speech starts and ends in "
                        f"{_who(not n, not t)}."}
    ratio = round(t / n, 2)
    obs["ratio"] = ratio
    # decided on the ratio as the text shows it (one decimal): "1.2× as long" is
    # never "about the same" for one take and "longer" for another
    if 1 / LENGTH_SIMILAR_RATIO <= round(ratio, 1) <= LENGTH_SIMILAR_RATIO:
        return {**obs, "kind": "similar",
                "text": f"About the same length: {t:.2f} s, against {n:.2f} s in the original."}
    if round(ratio, 1) > LENGTH_SIMILAR_RATIO:
        return {**obs, "kind": "longer",
                "text": f"You took {ratio:.1f}× as long: {t:.2f} s, "
                        f"against {n:.2f} s in the original."}
    return {**obs, "kind": "shorter",
            "text": f"You were quicker: {t:.2f} s, against {n:.2f} s in the original "
                    f"({ratio:.1f}× as long)."}


def _times(n: int) -> str:
    return {1: "once", 2: "twice"}.get(n, f"{n} times")


def _pause_observation(native: dict, take: dict) -> dict | None:
    n, t = len(native["pauses"]), len(take["pauses"])
    if n == t:
        return None
    obs = {"key": "pauses", "native": n, "take": t}
    total = sum(p["end"] - p["start"] + TIME_STEP for p in take["pauses"])
    native_part = "the original runs straight through" if n == 0 else f"the original pauses {_times(n)}"
    if t > n:
        return {**obs, "kind": "more",
                "text": f"You paused {_times(t)} inside it ({total:.2f} s in all); {native_part}."}
    take_part = "you ran straight through" if t == 0 else f"you paused {_times(t)}"
    return {**obs, "kind": "fewer", "text": f"The original pauses {_times(n)}; {take_part}."}
