"""Prosody with parselmouth: F0 every 10 ms, intonation units, per-word
prominence and an approximate rhythm measure.

Adapts the OpenPronounce approach (F0 clamped to the speech range + interpolation
over unvoiced gaps) and adds what it was missing: an F0 range adapted to each
speaker turn, per-segment stats, a final contour measured in semitones (so the
same melody reads the same in a bass and in a child), sentence-sized intonation
units checked against the contour their type calls for, per-word prominence
with a function/content tag, and the variability of syllable intervals (nPVI).
"""

from __future__ import annotations

import numpy as np

from .canonical import clean_word
from .ipa_maps import SYLLABIC, is_vowel
# The function/content tag of each word comes from the closed-class list shared
# with phenomena and metrics; re-exported for the pipeline and the tests.
from .lexicon import FUNCTION_WORDS, word_class  # noqa: F401

# First pass: wide enough for a bass and for a pitched-up cartoon child. On
# the South Park clips 7–26 % of the voiced frames lie above 400 Hz (child
# voices at 250–600 Hz); the old fixed 65–400 Hz pass halved or dropped them.
F0_FLOOR = 50.0   # Hz
F0_CEIL = 800.0
TIME_STEP = 0.01  # 10 ms
INTENSITY_MIN_PITCH = 65.0  # Hz: sets Praat's intensity window (3.2 / 65 ≈ 49 ms)

# Second pass, per speaker turn (Whisper segment): Hirst's rule from the
# Momel/INTSINT Praat plugin (Hirst 2007) — floor = 0.75·q25 and
# ceiling = 1.5·q75 of the first pass. It is applied per segment, not per file:
# in a scene with Randy (~110 Hz) and the kids (~300 Hz+) a file-wide q25 would
# put the floor above Randy's voice and erase it.
RANGE_FLOOR_FACTOR = 0.75
RANGE_CEIL_FACTOR = 1.5
MIN_VOICED_FOR_RANGE = 30  # voiced frames (0.3 s) before trusting a turn's quantiles
# Octave errors: for a few frames the tracker locks onto the 2nd or 3rd harmonic
# (or a subharmonic). Left in the first pass they drag Hirst's q75 up: in
# "Oh, okay." (0.64 s) 14 of 34 frames sat at 630–785 Hz against a 280 Hz
# median, the ceiling hit 800 Hz and the reported range was 22.6 st. A SHORT run
# (≤ 150 ms) at least 9 st from its turn's median is folded by whole octaves
# toward it before the quantiles are taken. Long runs are left alone: a second
# voice in the same turn is a long run, not an error.
OCTAVE_SUSPECT_ST = 9.0
OCTAVE_SUSPECT_MAX_FRAMES = 15
# p5–p95 over fewer than 0.1 s of voicing is not a range
MIN_VOICED_FOR_RANGE_STATS = 10

# Final contour: F0 slope over the last voiced run, in semitones per second.
# Semitones make the threshold speaker-independent: the old 30 Hz/s was
# 4.3 st/s for a 120 Hz voice and 2.1 st/s for a 250 Hz one. 3 st/s is that
# same 30 Hz/s at ~170 Hz and above the perceptual glissando threshold for a
# 350 ms tail (≈1.3 st/s, 't Hart 1976). On nine real clips the share of
# rising/falling endings barely moves between 1.5 and 5 st/s: real final
# movements are much steeper than the threshold.
FINAL_TAIL = 0.35      # s
FINAL_SLOPE_ST = 3.0   # st/s
# The run is the last stretch of CONTINUOUS voicing: a gap longer than
# FINAL_MAX_GAP or a frame-to-frame jump larger than FINAL_MAX_JUMP_ST (an
# octave error, or the next speaker starting) ends it. Speech moves ≤ ~0.5 st
# per 10 ms even in a steep fall. Fitting across such breaks is what produced
# slopes of 80 st/s on the South Park clips.
FINAL_MAX_GAP = 0.03       # s (bridges one or two dropped frames)
FINAL_MAX_JUMP_ST = 4.0    # st between consecutive frames
# A run shorter than 100 ms is a blip: use the one before. On the South Park
# clips, final runs of 5–8 frames started past the Whisper segment end (the next
# speaker) in 48 % of the cases and a third of them "sloped" beyond 40 st/s.
FINAL_MIN_FRAMES = 10
# Forced-alignment word spans end at the last phone's CTC peak, which is
# earlier than the end of the voicing of the last syllable, where the final
# rise or fall happens: look this much past the last word, but never into the
# next unit nor past the end of the Whisper segment, even when the forced span
# overshoots it (that is where the next speaker's first frames leaked in).
UNIT_TAIL_PAD = 0.10   # s

# Uptalk (high rising terminal) is a property of declarative clauses; one- or
# two-word statements ("Okay.", "Of course.") rise routinely as continuers or
# backchannels and are not counted.
UPTALK_MIN_WORDS = 3

# Rhythm: an interval between two syllable nuclei longer than this is a pause,
# not a syllable, and starts a new stretch.
RHYTHM_MAX_INTERVAL = 0.5  # s


def _semitones(f0: np.ndarray) -> np.ndarray:
    return 12.0 * np.log2(f0)


def _voiced_runs(times: np.ndarray, st: np.ndarray) -> list[np.ndarray]:
    """Indices of the continuous voiced runs: a gap longer than FINAL_MAX_GAP or
    a frame-to-frame jump larger than FINAL_MAX_JUMP_ST starts a new one."""
    breaks = np.nonzero((np.diff(times) > FINAL_MAX_GAP + 1e-6)
                        | (np.abs(np.diff(st)) > FINAL_MAX_JUMP_ST))[0] + 1
    return np.split(np.arange(len(times)), breaks)


def fold_octave_errors(f0: np.ndarray, times: np.ndarray) -> np.ndarray:
    """A copy of `f0` (NaN = unvoiced) with the short runs that sit
    OCTAVE_SUSPECT_ST or more from the median moved by whole octaves toward it."""
    out = np.asarray(f0, dtype=float).copy()
    voiced = np.nonzero(~np.isnan(out))[0]
    if len(voiced) == 0:
        return out
    st = _semitones(out[voiced])
    median = float(np.median(st))
    for run in _voiced_runs(np.asarray(times)[voiced], st):
        if len(run) > OCTAVE_SUSPECT_MAX_FRAMES:
            continue
        offset = float(np.median(st[run])) - median
        if abs(offset) >= OCTAVE_SUSPECT_ST:
            out[voiced[run]] /= 2.0 ** round(offset / 12.0)
    return out


def contour_label(slope_st: float | None) -> str:
    """rising | falling | flat from a slope in semitones per second."""
    if slope_st is None:
        return "flat"
    if slope_st > FINAL_SLOPE_ST:
        return "rising"
    if slope_st < -FINAL_SLOPE_ST:
        return "falling"
    return "flat"


class ProsodyExtractor:
    def __init__(self, wav_path: str, spans: list[tuple[float, float]] | None = None):
        """`spans` are the speaker turns (Whisper segments) whose F0 range is
        adapted separately; without them the whole file is one turn."""
        import parselmouth

        self.snd = parselmouth.Sound(str(wav_path))
        self.f0_times, self.f0 = self._pitch(self.snd, F0_FLOOR, F0_CEIL)
        # (t0, t1, floor, ceiling) of every turn that was re-tracked
        self.ranges: list[tuple[float, float, float, float]] = []
        first_pass = self.f0.copy()
        if spans is None:
            spans = [(self.snd.xmin, self.snd.xmax)]
        for t0, t1 in spans:
            self._retrack(first_pass, t0, t1)
        intensity = self.snd.to_intensity(time_step=TIME_STEP,
                                          minimum_pitch=INTENSITY_MIN_PITCH)
        self.int_times = intensity.xs()
        self.intensity = intensity.values[0].astype(float)

    # --- F0 tracking ---------------------------------------------------------
    @staticmethod
    def _pitch(snd, floor: float, ceiling: float) -> tuple[np.ndarray, np.ndarray]:
        pitch = snd.to_pitch(time_step=TIME_STEP, pitch_floor=floor,
                             pitch_ceiling=ceiling)
        f0 = pitch.selected_array["frequency"].astype(float)
        f0[f0 == 0] = np.nan  # unvoiced frames
        return np.asarray(pitch.xs()), f0

    def _retrack(self, first_pass: np.ndarray, t0: float, t1: float) -> None:
        """Second pass over one turn with its own Hirst range. Turns with too
        little voicing keep the wide first pass."""
        mask = (self.f0_times >= t0) & (self.f0_times <= t1)
        voiced = fold_octave_errors(first_pass[mask], self.f0_times[mask])
        voiced = voiced[~np.isnan(voiced)]
        if len(voiced) < MIN_VOICED_FOR_RANGE:
            return
        q_lo, q_hi = np.percentile(voiced, [25, 75])
        floor = max(F0_FLOOR, RANGE_FLOOR_FACTOR * q_lo)
        ceiling = min(F0_CEIL, RANGE_CEIL_FACTOR * q_hi)
        if ceiling <= floor:
            return
        pad = 3.0 / floor  # Praat's analysis window needs ~3 periods of context
        try:
            part = self.snd.extract_part(from_time=max(self.snd.xmin, t0 - pad),
                                         to_time=min(self.snd.xmax, t1 + pad),
                                         preserve_times=True)
            times, f0 = self._pitch(part, floor, ceiling)
        except Exception:
            return
        if len(times) == 0:
            return
        idx = np.nonzero(mask)[0]
        nearest = np.clip(np.rint((self.f0_times[idx] - times[0]) / TIME_STEP).astype(int),
                          0, len(times) - 1)
        self.f0[idx] = f0[nearest]
        self.ranges.append((t0, t1, round(float(floor), 1), round(float(ceiling), 1)))

    def range_at(self, t0: float, t1: float) -> tuple[float, float]:
        """F0 floor and ceiling in force over [t0, t1] (first pass if none)."""
        mid = (t0 + t1) / 2
        for r0, r1, floor, ceiling in self.ranges:
            if r0 <= mid <= r1:
                return floor, ceiling
        return F0_FLOOR, F0_CEIL

    # --- helpers -------------------------------------------------------------
    def _slice(self, arr: np.ndarray, times: np.ndarray, t0: float, t1: float):
        mask = (times >= t0) & (times <= t1)
        return arr[mask], times[mask]

    def f0_track(self, t0: float, t1: float) -> list[list[float]]:
        """[(t, f0), …] every 10 ms with unvoiced gaps interpolated (for the SVG)."""
        f0, times = self._slice(self.f0, self.f0_times, t0, t1)
        if len(f0) == 0 or np.all(np.isnan(f0)):
            return []
        voiced = ~np.isnan(f0)
        interp = np.interp(times, times[voiced], f0[voiced])
        return [[round(float(t), 3), round(float(v), 1)] for t, v in zip(times, interp)]

    # --- per-segment API -----------------------------------------------------
    def segment_stats(self, t0: float, t1: float) -> dict:
        floor, ceiling = self.range_at(t0, t1)
        f0, _ = self._slice(self.f0, self.f0_times, t0, t1)
        voiced = f0[~np.isnan(f0)]
        if len(voiced) < 3:
            return {"mean": None, "range": None, "final_contour": "flat",
                    "range_st": None, "final_slope_st": None,
                    "f0_floor": floor, "f0_ceiling": ceiling}
        slope = self.final_slope(t0, t1)
        span = span_st = None
        if len(voiced) >= MIN_VOICED_FOR_RANGE_STATS:
            p5, p95 = np.nanpercentile(voiced, [5, 95])
            span = round(float(p95 - p5), 1)
            span_st = round(float(12.0 * np.log2(p95 / p5)), 1)
        return {
            "mean": round(float(np.nanmean(voiced)), 1),
            "range": span,
            "final_contour": contour_label(slope),
            "range_st": span_st,
            "final_slope_st": None if slope is None else round(slope, 1),
            "f0_floor": floor,
            "f0_ceiling": ceiling,
        }

    def final_slope(self, t0: float, t1: float, tail: float = FINAL_TAIL) -> float | None:
        """F0 slope (st/s) over the last `tail` seconds of the last continuous
        voiced run of [t0, t1] (see FINAL_MAX_GAP / FINAL_MAX_JUMP_ST)."""
        f0, times = self._slice(self.f0, self.f0_times, t0, t1)
        voiced = ~np.isnan(f0)
        if voiced.sum() < FINAL_MIN_FRAMES:
            return None
        vt, vst = times[voiced], _semitones(f0[voiced])
        for run in reversed(_voiced_runs(vt, vst)):
            if len(run) >= FINAL_MIN_FRAMES:
                break
        else:
            return None
        rt, rst = vt[run], vst[run]
        tail_mask = rt >= rt[-1] - tail
        rt = rt[tail_mask]
        return float(np.polyfit(rt - rt.mean(), rst[tail_mask], 1)[0])

    def _final_contour(self, t0: float, t1: float, tail: float = FINAL_TAIL) -> str:
        return contour_label(self.final_slope(t0, t1, tail))

    # --- intonation units ----------------------------------------------------
    def intonation_units(self, words: list[dict], seg_end: float | None = None) -> list[dict]:
        """Split a segment's words into sentence-sized units at the punctuation
        Whisper writes on them, and read each unit's final contour against the
        one its type calls for (report §4: statement and wh-question fall,
        yes/no question rises). A clause-sized statement that rises is flagged
        as uptalk."""
        units = []
        for i0, i1 in split_units(words):
            unit_words = words[i0:i1 + 1]
            kind = unit_type([w["word"] for w in unit_words])
            start, end = unit_words[0]["start"], unit_words[-1]["end"]
            stop = end + UNIT_TAIL_PAD
            if i1 + 1 < len(words):
                stop = min(stop, words[i1 + 1]["start"])
            if seg_end is not None and seg_end > start:
                stop = min(stop, seg_end)
            slope = self.final_slope(start, stop)
            contour = contour_label(slope)
            expected = EXPECTED_CONTOUR.get(kind)
            units.append({
                "start": round(start, 3),
                "end": round(end, 3),
                "words": [i0, i1],
                "text": " ".join(w["word"] for w in unit_words),
                "type": kind,
                "final_contour": contour,
                "final_slope_st": None if slope is None else round(slope, 1),
                "expected_contour": expected,
                "matches_expected": None if expected is None else contour == expected,
                "uptalk": (kind == "statement" and contour == "rising"
                           and len(unit_words) >= UPTALK_MIN_WORDS),
            })
        return units

    # --- per-word prominence -------------------------------------------------
    def prominence_cues(self, words: list[dict]) -> np.ndarray:
        """The three cues of prominence per word, shape (3, len(words)): F0
        height (semitones above the segment's low F0, from the word's 90th
        percentile so an octave glitch cannot dominate), loudness (dB above the
        segment's quiet floor) and duration per phone (lengthening)."""
        if not words:
            return np.zeros((3, 0))
        f0_peaks, int_peaks, per_phone = [], [], []
        for w in words:
            f0, _ = self._slice(self.f0, self.f0_times, w["start"], w["end"])
            f0 = f0[~np.isnan(f0)]
            f0_peaks.append(float(np.percentile(f0, 90)) if len(f0) >= 2 else None)
            inten, _ = self._slice(self.intensity, self.int_times, w["start"], w["end"])
            int_peaks.append(float(np.max(inten)) if len(inten) else None)
            n_phones = max(len(w.get("canonical") or ()), 1)
            per_phone.append(max(w["end"] - w["start"], 0.0) / n_phones)

        # references: the segment's low F0 and its quiet floor (pauses included),
        # so the lowest word still scores above zero
        t0, t1 = words[0]["start"], words[-1]["end"]
        seg_f0, _ = self._slice(self.f0, self.f0_times, t0, t1)
        seg_f0 = seg_f0[~np.isnan(seg_f0)]
        f0_ref = float(np.percentile(seg_f0, 5)) if len(seg_f0) >= 2 else None
        f0_comp = [0.0 if v is None or f0_ref is None else max(12.0 * np.log2(v / f0_ref), 0.0)
                   for v in f0_peaks]
        seg_int, _ = self._slice(self.intensity, self.int_times, t0, t1)
        int_ref = float(np.percentile(seg_int, 10)) if len(seg_int) else None
        int_comp = [0.0 if v is None or int_ref is None else max(v - int_ref, 0.0)
                    for v in int_peaks]

        return np.asarray([f0_comp, int_comp, per_phone], dtype=float)

    def word_prominence(self, words: list[dict]) -> list[float]:
        """0–1 prominence of every word within its segment: the product of its
        three cues (prominence_cues), each scaled to the segment's maximum.
        1 = the most prominent word."""
        if not words:
            return []
        scores = np.ones(len(words))
        for cue in self.prominence_cues(words):
            top = cue.max()
            # a cue that does not vary in the segment tells nothing: neutral
            scores *= cue / top if top > 0 else 1.0
        top = scores.max()
        if top <= 0:
            return [0.0] * len(words)
        return [round(float(s / top), 3) for s in scores]

    def emphasis_word_idx(self, words: list[dict]) -> int | None:
        """Index of the most prominent word (see word_prominence)."""
        return peak_index(self.word_prominence(words))


def peak_index(scores: list[float]) -> int | None:
    if not scores or max(scores) <= 0:
        return None
    return int(np.argmax(scores))


# --- intonation unit typing --------------------------------------------------
EXPECTED_CONTOUR = {
    "statement": "falling",
    "wh_question": "falling",
    "yes_no_question": "rising",
}
WH_WORDS = {"what", "when", "where", "who", "whom", "whose", "which", "why", "how",
            "whaddya", "whatcha"}
# Leading words that do not decide the type: discourse markers and fillers
# ("So, why not?", "Oh what now?"); a word ending in a comma (vocatives such as
# "Randy, what?") is skipped as well.
LEAD_SKIP = {"oh", "well", "so", "and", "but", "okay", "ok", "hey", "uh", "um", "now",
             "yeah", "look", "dude", "man", "alright", "right", "then", "or"}
ABBREVIATIONS = {"mr.", "mrs.", "ms.", "dr.", "st.", "jr.", "sr.", "vs.", "etc."}
_TRAILING = "\"')]”’»"


def _terminal(token: str) -> str | None:
    """'.', '?', '!', '…' when the token closes a sentence, else None."""
    t = token.rstrip(_TRAILING)
    if t.endswith(("...", "…")):
        return "…"
    if t.endswith("?") or t.endswith("?!"):
        return "?"
    if t.endswith("!"):
        return "!"
    if t.endswith(".") and t.lower() not in ABBREVIATIONS and "." not in t[:-1]:
        return "."
    return None


def split_units(words: list[dict]) -> list[tuple[int, int]]:
    """(first, last) word indices of every unit, in order."""
    units, start = [], 0
    for i, w in enumerate(words):
        if _terminal(w["word"]) is not None:
            units.append((start, i))
            start = i + 1
    if start < len(words):
        units.append((start, len(words) - 1))
    return units


def unit_type(tokens: list[str]) -> str:
    """statement | yes_no_question | wh_question | exclamation | incomplete
    (no sentence-final punctuation, or trailing off with an ellipsis: no
    expected contour)."""
    if not tokens:
        return "incomplete"
    end = _terminal(tokens[-1])
    if end == ".":
        return "statement"
    if end == "!":
        return "exclamation"
    if end != "?":
        return "incomplete"
    for k, tok in enumerate(tokens):
        cw = clean_word(tok)
        if not cw:
            continue
        if k < len(tokens) - 1 and tok.rstrip(_TRAILING).endswith(","):
            continue
        if cw in LEAD_SKIP:
            continue
        return "wh_question" if cw.split("'")[0] in WH_WORDS else "yes_no_question"
    return "yes_no_question"


# --- rhythm --------------------------------------------------------------------
def _is_nucleus(phone: str) -> bool:
    return is_vowel(phone) or phone in SYLLABIC


def rhythm(words: list[dict]) -> dict | None:
    """Variability of the intervals between consecutive syllable nuclei.

    APPROXIMATE by construction: greedy CTC spans are 20–40 ms spikes, not
    segment durations, so vocalic/consonantal durations (%V, ΔC, nPVI-V) cannot
    be measured from them. What the spikes do locate is each nucleus in time, so
    the interval from one vowel peak to the next stands in for the duration of a
    syllable. nPVI and Varco over those intervals rise when stressed syllables
    stretch and reduced ones shrink — the report's stress-timing contrast — but
    their values are not comparable with published nPVI-V figures.

    Nuclei are taken from the realized phones (`real`) of reliable words; a
    low-confidence word or a gap longer than RHYTHM_MAX_INTERVAL breaks the
    stretch, since pauses are not syllables.
    """
    stretches: list[list[float]] = []
    current: list[float] = []

    def close():
        nonlocal current
        if len(current) >= 2:
            stretches.append(current)
        current = []

    for w in words:
        if w.get("low_confidence"):
            close()
            continue
        for p in w.get("real") or ():
            if not _is_nucleus(p["phone"]):
                continue
            t = (p["start"] + p["end"]) / 2
            if current and t - current[-1] > RHYTHM_MAX_INTERVAL:
                close()
            current.append(t)
    close()

    intervals = [np.diff(s) for s in stretches]
    pairs = [np.abs(d[1:] - d[:-1]) / ((d[1:] + d[:-1]) / 2) for d in intervals if len(d) >= 2]
    if not pairs:
        return None
    pair_terms = np.concatenate(pairs)
    all_iv = np.concatenate(intervals)
    mean = float(np.mean(all_iv))
    sd = float(np.std(all_iv))
    return {
        "npvi": round(100.0 * float(np.mean(pair_terms)), 1),
        "varco": round(100.0 * sd / mean, 1) if mean > 0 else None,
        "n_intervals": int(len(all_iv)),
        "n_pairs": int(len(pair_terms)),
        "mean_ms": round(1000 * mean, 1),
        "sd_ms": round(1000 * sd, 1),
        "approximate": True,
        "method": "inter-nucleus intervals (CTC peaks)",
    }


def rhythm_summary(rhythms: list[dict | None]) -> dict | None:
    """Pool per-segment rhythm dicts into one for the whole analysis: nPVI
    weighted by its pairs (exactly the pooled mean), Varco from the pooled
    mean and variance of the intervals."""
    rows = [r for r in rhythms if r]
    if not rows:
        return None
    n_pairs = sum(r["n_pairs"] for r in rows)
    n = sum(r["n_intervals"] for r in rows)
    mean = sum(r["mean_ms"] * r["n_intervals"] for r in rows) / n
    # pooled variance: E[x²] − E[x]²
    ex2 = sum((r["sd_ms"] ** 2 + r["mean_ms"] ** 2) * r["n_intervals"] for r in rows) / n
    sd = max(ex2 - mean ** 2, 0.0) ** 0.5
    return {
        "npvi": round(sum(r["npvi"] * r["n_pairs"] for r in rows) / n_pairs, 1),
        "varco": round(100.0 * sd / mean, 1) if mean > 0 else None,
        "n_intervals": n,
        "n_pairs": n_pairs,
        "mean_ms": round(mean, 1),
        "sd_ms": round(sd, 1),
        "approximate": True,
        "method": "inter-nucleus intervals (CTC peaks)",
    }
