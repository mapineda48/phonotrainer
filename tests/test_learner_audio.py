"""Record yourself: a learner's take measured like the native audio, and the
descriptive comparison. Synthetic audio only — numpy glissandi encoded by ffmpeg
into what MediaRecorder produces (WebM/Opus, Ogg/Opus, MP4/AAC)."""

import subprocess

import numpy as np
import pytest
import soundfile as sf

from phonotrainer import learner_audio as la
from phonotrainer.learner_audio import TakeError

SR = 48000          # what a browser records at
RNG = np.random.default_rng(7)


def tone(f0_start, f0_end, secs, sr=SR):
    """A glissando with harmonics so the pitch tracker locks on properly."""
    t = np.linspace(0, secs, int(sr * secs), endpoint=False)
    freq = np.linspace(f0_start, f0_end, len(t))
    phase = 2 * np.pi * np.cumsum(freq) / sr
    return 0.4 * np.sin(phase) + 0.2 * np.sin(2 * phase) + 0.1 * np.sin(3 * phase)


def hush(secs, sr=SR):
    """Room noise at -60 dB: digital silence is not what a microphone delivers."""
    return 0.001 * RNG.standard_normal(int(sr * secs))


def encode(tmp_path, samples, name, codec_args):
    """Encode float samples with ffmpeg and return the file's bytes."""
    wav = tmp_path / f"{name}.wav"
    sf.write(str(wav), np.asarray(samples, dtype=np.float32), SR)
    out = tmp_path / name
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-i", str(wav), *codec_args, str(out)], check=True)
    return out.read_bytes()


OPUS = ["-c:a", "libopus"]


@pytest.fixture(scope="module")
def media(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("takes")
    rising = np.concatenate([hush(0.3), tone(110, 190, 1.0), hush(0.4)])
    falling = np.concatenate([hush(0.2), tone(260, 160, 0.8), hush(0.3)])
    return {
        "rising_webm": encode(tmp, rising, "rising.webm", OPUS),
        "falling_ogg": encode(tmp, falling, "falling.ogg", OPUS),
        "rising_m4a": encode(tmp, rising, "rising.m4a", ["-c:a", "aac"]),
    }


# --- measuring a take -------------------------------------------------------------

def test_a_rising_webm_take(media):
    take = la.measure_take(media["rising_webm"], "audio/webm;codecs=opus")
    assert take["final_contour"] == "rising"
    assert take["final_slope_st"] > 3
    assert take["peak"]["where"] == "late"
    # leading and trailing silence trimmed: the tone runs 0.3–1.3 s
    assert take["speech"]["start"] == pytest.approx(0.3, abs=0.05)
    assert take["speech"]["end"] == pytest.approx(1.3, abs=0.05)
    assert take["speech_s"] == pytest.approx(1.0, abs=0.08)
    assert take["voiced_s"] == pytest.approx(1.0, abs=0.1)
    assert take["duration_s"] == pytest.approx(1.7, abs=0.05)
    assert 110 < take["median_hz"] < 190
    assert take["range_st"] > 5
    assert take["pauses"] == []
    # one row per 10 ms frame; unvoiced frames carry no pitch, nothing is interpolated
    t, hz, st, db = take["track"][0]
    assert t < 0.1 and hz is None and st is None and db is not None
    voiced = [row for row in take["track"] if row[1] is not None]
    assert len(voiced) > 80
    # semitones from the take's own median: centered on zero
    assert np.median([row[2] for row in voiced]) == pytest.approx(0, abs=0.3)


def test_a_falling_ogg_take(media):
    take = la.measure_take(media["falling_ogg"], "audio/ogg; codecs=opus")
    assert take["final_contour"] == "falling"
    assert take["peak"]["where"] == "early"
    assert take["speech"]["start"] == pytest.approx(0.2, abs=0.05)


def test_an_mp4_aac_take_decodes(media):
    """What Safari's MediaRecorder produces."""
    take = la.measure_take(media["rising_m4a"], "audio/mp4")
    assert take["final_contour"] == "rising"


def test_a_wav_take(tmp_path):
    wav = tmp_path / "take.wav"
    sf.write(str(wav), np.concatenate([hush(0.2), tone(200, 140, 0.8), hush(0.2)])
             .astype(np.float32), SR)
    assert la.measure_take(wav.read_bytes(), "audio/wav")["final_contour"] == "falling"


def test_semitones_make_two_voices_comparable(tmp_path):
    """The same melody an octave apart reads the same: compared is the tune, not the voice."""
    low = encode(tmp_path, np.concatenate([hush(0.2), tone(100, 160, 0.9), hush(0.2)]),
                 "low.webm", OPUS)
    high = encode(tmp_path, np.concatenate([hush(0.2), tone(200, 320, 0.9), hush(0.2)]),
                  "high.webm", OPUS)
    a, b = la.measure_take(low, "audio/webm"), la.measure_take(high, "audio/webm")
    assert b["median_hz"] == pytest.approx(2 * a["median_hz"], rel=0.05)
    assert b["range_st"] == pytest.approx(a["range_st"], abs=0.5)
    comparison = la.compare(a, b)
    kinds = {o["key"]: o["kind"] for o in comparison["observations"]}
    assert kinds == {"ending": "match", "peak": "same", "range": "similar", "length": "similar"}
    voiced = [(n, t) for _, n, t in comparison["overlay"] if n is not None and t is not None]
    assert max(abs(n - t) for n, t in voiced) < 1.0


# --- what is refused ---------------------------------------------------------------

@pytest.mark.parametrize("content_type", ["audio/ogg", "text/plain", "application/octet-stream",
                                          "", None, "audio/mpeg"])
def test_the_declared_type_must_be_audio_and_match_the_file(media, content_type):
    with pytest.raises(TakeError) as err:
        la.measure_take(media["rising_webm"], content_type)
    assert err.value.code == "unsupported_type" and err.value.status == 415


def test_unknown_contents_are_refused_before_ffmpeg_sees_them(monkeypatch):
    def no_ffmpeg(*args, **kwargs):
        raise AssertionError("ffmpeg must not run on an unrecognized file")

    monkeypatch.setattr(la.subprocess, "run", no_ffmpeg)
    for blob in (b"#EXTM3U\nfile:///etc/passwd\n", b"ffconcat version 1.0\n", b"\x00" * 64):
        with pytest.raises(TakeError) as err:
            la.measure_take(blob, "audio/webm")
        assert err.value.code == "unsupported_type"


def test_a_corrupt_file_is_undecodable():
    with pytest.raises(TakeError) as err:
        la.measure_take(b"\x1a\x45\xdf\xa3" + b"garbage" * 50, "audio/webm")
    assert err.value.code == "undecodable" and err.value.status == 422


def test_an_empty_or_oversized_upload(monkeypatch):
    with pytest.raises(TakeError) as err:
        la.measure_take(b"", "audio/webm")
    assert err.value.code == "empty"
    monkeypatch.setattr(la, "MAX_UPLOAD_BYTES", 1000)
    with pytest.raises(TakeError) as err:
        la.measure_take(b"\x1a\x45\xdf\xa3" + b"\x00" * 1000, "audio/webm")
    assert err.value.code == "too_large" and err.value.status == 413


def test_a_take_longer_than_the_limit(tmp_path, monkeypatch):
    monkeypatch.setattr(la, "MAX_TAKE_S", 2.0)
    blob = encode(tmp_path, np.concatenate([tone(150, 150, 3.5), hush(0.5)]), "long.webm", OPUS)
    with pytest.raises(TakeError) as err:
        la.measure_take(blob, "audio/webm")
    assert err.value.code == "too_long" and err.value.status == 413


def test_a_take_too_short(tmp_path):
    blob = encode(tmp_path, tone(150, 150, 0.12), "short.webm", OPUS)
    with pytest.raises(TakeError) as err:
        la.measure_take(blob, "audio/webm")
    assert err.value.code == "too_short"


def test_a_take_with_no_voice(tmp_path):
    """A muted or wrong microphone: noise, no voice."""
    blob = encode(tmp_path, 0.05 * RNG.standard_normal(SR), "noise.webm", OPUS)
    with pytest.raises(TakeError) as err:
        la.measure_take(blob, "audio/webm")
    assert err.value.code == "no_voice"


def test_nothing_is_left_on_disk(media, tmp_path, monkeypatch):
    root = tmp_path / "tmp"
    root.mkdir()
    monkeypatch.setattr(la, "TEMP_ROOT", str(root))
    la.measure_take(media["rising_webm"], "audio/webm")
    with pytest.raises(TakeError):
        la.measure_take(b"\x1a\x45\xdf\xa3" + b"garbage" * 50, "audio/webm")
    assert list(root.iterdir()) == []


# --- the native side -----------------------------------------------------------------

def test_the_native_span_keeps_the_clip_times(tmp_path):
    sr = 16000
    clip = np.concatenate([hush(1.0, sr), tone(240, 150, 0.8, sr), hush(1.2, sr)])
    wav = tmp_path / "audio.wav"
    sf.write(str(wav), clip.astype(np.float32), sr)
    native = la.measure_native(wav, 0.9, 1.9, turn=(0.8, 2.1))
    assert native["start"] == 0.9 and native["end"] == 1.9
    assert native["speech"]["start"] == pytest.approx(1.0, abs=0.05)
    assert native["speech"]["end"] == pytest.approx(1.8, abs=0.05)
    assert native["final_contour"] == "falling"
    assert native["peak"]["where"] == "early"
    assert native["track"][0][0] >= 0.9 and native["track"][-1][0] <= 1.9


@pytest.mark.parametrize("start,end", [(-1, 1), (2, 1), (1, 1), (0, 31), (float("nan"), 1)])
def test_a_bad_span(tmp_path, start, end):
    with pytest.raises(TakeError) as err:
        la.check_span(start, end)
    assert err.value.code == "bad_span" and err.value.status == 400


# --- the speech span ---------------------------------------------------------------

def _frames(n):
    return np.round(np.arange(n) * la.TIME_STEP, 3)


def test_the_speech_span_keeps_unvoiced_edges_and_drops_clicks():
    times = _frames(200)
    hz = np.full(200, np.nan)
    hz[50:120] = 150.0                       # voicing 0.50–1.19 s
    sounding = np.zeros(200, dtype=bool)
    sounding[35:120] = True                  # an /s/ before the voicing (0.15 s)
    sounding[124:128] = True                 # a release after a 40 ms closure: bridged
    sounding[180:186] = True                 # the stop button's click, 0.5 s later
    start, end = la.speech_span(times, hz, sounding)
    assert start == pytest.approx(0.35)
    assert end == pytest.approx(1.27)


def test_a_long_silence_is_not_bridged():
    times = _frames(200)
    hz = np.full(200, np.nan)
    hz[50:100] = 150.0
    sounding = np.zeros(200, dtype=bool)
    sounding[50:100] = True
    sounding[115:120] = True                 # 150 ms of silence first: not part of it
    assert la.speech_span(times, hz, sounding) == (pytest.approx(0.5), pytest.approx(0.99))


def test_without_voicing_the_span_is_the_sounding_runs():
    times = _frames(100)
    sounding = np.zeros(100, dtype=bool)
    sounding[10:12] = True                   # a click: too short
    sounding[30:60] = True
    assert la.speech_span(times, np.full(100, np.nan), sounding) == (pytest.approx(0.3),
                                                                      pytest.approx(0.59))
    assert la.speech_span(times, np.full(100, np.nan), np.zeros(100, dtype=bool)) is None


# --- the comparison ------------------------------------------------------------------

def contour(speech=(0.0, 1.0), range_st=6.0, final="falling", where="early", pauses=0,
            track=None):
    """A hand-built contour with exactly the fields compare() reads."""
    span = None if speech is None else {"start": speech[0], "end": speech[1]}
    return {
        "speech": span,
        "speech_s": None if speech is None else round(speech[1] - speech[0], 3),
        "range_st": range_st,
        "final_contour": final,
        "peak": None if where in (None, "level") else {"where": where},
        "pauses": [{"start": 0.4, "end": 0.7}] * pauses,
        "track": track or [],
    }


def observation(native, take, key):
    found = [o for o in la.compare(native, take)["observations"] if o["key"] == key]
    return found[0] if found else None


def test_the_ending():
    o = observation(contour(final="falling"), contour(final="rising"), "ending")
    assert o["kind"] == "mismatch"
    assert o["text"] == "The original falls at the end; yours rises."
    o = observation(contour(final="flat"), contour(final="flat"), "ending")
    assert o["kind"] == "match" and o["text"] == "Both stay level at the end."
    assert observation(contour(), contour(), "ending")["text"] == "Both fall at the end."
    o = observation(contour(), contour(final=None), "ending")
    assert o["kind"] == "unmeasured" and "your take" in o["text"]


@pytest.mark.parametrize("native_s,take_s,kind", [
    (1.0, 1.0, "similar"),
    (1.0, 1.2, "similar"),
    (1.0, 1.24, "similar"),     # shown as 1.2×: the decision follows what is shown
    (1.0, 1.26, "longer"),
    (1.0, 1.6, "longer"),
    (1.0, 0.86, "similar"),
    (1.0, 0.84, "shorter"),
    (1.0, 0.5, "shorter"),
])
def test_the_length(native_s, take_s, kind):
    o = observation(contour(speech=(0, native_s)), contour(speech=(0, take_s)), "length")
    assert o["kind"] == kind
    assert o["ratio"] == pytest.approx(take_s / native_s, abs=0.01)


def test_the_length_reads_plainly():
    o = observation(contour(speech=(0, 0.8)), contour(speech=(0.3, 1.58)), "length")
    assert o["text"] == "You took 1.6× as long: 1.28 s, against 0.80 s in the original."


@pytest.mark.parametrize("native_st,take_st,kind", [
    (1.0, 1.8, "similar"),      # both fairly level
    (6.0, 3.0, "narrower"),     # half, 3 st apart
    (6.0, 8.0, "similar"),      # 1.33×: within the ratio
    (1.0, 2.5, "similar"),      # 2.5× but only 1.5 st apart
    (6.0, 9.0, "wider"),        # 1.5× and 3 st apart
    (6.0, None, "unmeasured"),
])
def test_the_pitch_range(native_st, take_st, kind):
    o = observation(contour(range_st=native_st), contour(range_st=take_st), "range")
    assert o["kind"] == kind


def test_the_peak():
    o = observation(contour(where="early"), contour(where="late"), "peak")
    assert o["kind"] == "different"
    assert o["text"] == "The original's pitch peaks early; yours peaks late."
    o = observation(contour(where="middle"), contour(where="middle"), "peak")
    assert o["text"] == "Both reach their highest pitch in the middle."
    o = observation(contour(where="early"), contour(range_st=1.0, where="level"), "peak")
    assert o["text"] == "The original's pitch peaks early; yours stays fairly level."
    assert observation(contour(), contour(range_st=None), "peak")["kind"] == "unmeasured"


@pytest.mark.parametrize("position,where", [(0.0, "early"), (0.32, "early"), (0.34, "middle"),
                                            (0.66, "middle"), (0.67, "late"), (1.0, "late")])
def test_the_peak_falls_in_thirds(position, where):
    times = np.round(np.arange(101) * 0.01, 2)
    st = -np.abs(times - position) * 10      # a single peak at `position`
    assert la._peak(times, st, (0.0, 1.0))["where"] == where


def test_a_one_frame_glitch_is_not_the_peak():
    times = np.round(np.arange(100) * 0.01, 2)
    st = np.linspace(3, -3, 100)             # falls throughout: the peak is early
    st[90] = 12.0                            # an octave jump for one frame
    assert la._peak(times, st, (0.0, 0.99))["where"] == "early"


def test_pauses_are_mentioned_only_when_they_differ():
    o = observation(contour(pauses=0), contour(pauses=1), "pauses")
    assert o["kind"] == "more"
    assert o["text"] == "You paused once inside it (0.31 s in all); the original runs straight through."
    assert observation(contour(pauses=1), contour(pauses=1), "pauses") is None
    assert observation(contour(pauses=2), contour(pauses=0), "pauses")["text"] == (
        "The original pauses twice; you ran straight through.")


def test_the_overlay_stretches_the_take_linearly():
    """Native speech 0–1 s; the take says it in 0.5–2.5 s (twice as slow). Each
    take frame lands at x = (t − 0.5) / 2 on the native axis."""
    native_track = [[round(t, 2), 150.0, round(t * 4, 2), 60.0] for t in np.arange(0, 1.001, 0.01)]
    take_track = [[round(t, 2), 150.0, round((t - 0.5) * 2, 2), 60.0]
                  for t in np.arange(0, 3.001, 0.01)]
    native = contour(speech=(0.0, 1.0), track=native_track)
    take = contour(speech=(0.5, 2.5), track=take_track)
    result = la.compare(native, take)
    assert result["time_scale"] == 2.0
    for x, n, t in result["overlay"]:
        assert n == pytest.approx(x * 4, abs=0.02)
        assert t == pytest.approx(x * 4, abs=0.05)   # the take's value at 0.5 + 2x
    assert len(result["overlay"]) == 101


def test_unvoiced_stretches_stay_empty_in_the_overlay():
    native_track = [[round(t, 2), 150.0, 0.0, 60.0] for t in np.arange(0, 1.001, 0.01)]
    take_track = [[round(t, 2), None if 0.4 <= t <= 0.6 else 150.0,
                   None if 0.4 <= t <= 0.6 else 0.0, 60.0] for t in np.arange(0, 1.001, 0.01)]
    result = la.compare(contour(track=native_track), contour(track=take_track))
    gaps = [x for x, _, t in result["overlay"] if t is None]
    assert gaps and min(gaps) >= 0.39 and max(gaps) <= 0.61


def test_no_overlay_without_speech():
    result = la.compare(contour(), contour(speech=None))
    assert result["overlay"] == [] and result["time_scale"] is None
    length = [o for o in result["observations"] if o["key"] == "length"][0]
    assert length["kind"] == "unmeasured"


def test_nothing_in_the_comparison_is_a_score():
    """The tool describes; it does not grade."""
    result = la.compare(contour(), contour(final="rising", where="late"))
    flat = repr(result).lower()
    for word in ("score", "correct", "wrong", "error", "accuracy", "percent", "grade"):
        assert word not in flat
