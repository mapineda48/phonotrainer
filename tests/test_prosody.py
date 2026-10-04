"""Prosody tests on synthetic audio (glissandi generated with numpy) and on
hand-built F0 tracks injected into the extractor."""

import numpy as np
import pytest
import soundfile as sf

from phonotrainer import prosody
from phonotrainer.prosody import (ProsodyExtractor, peak_index, rhythm, rhythm_summary,
                                  split_units, unit_type, word_class)

SR = 16000


def _tone(f0_start, f0_end, secs):
    """A glissando with harmonics so the pitch tracker locks on properly."""
    t = np.linspace(0, secs, int(SR * secs), endpoint=False)
    freq = np.linspace(f0_start, f0_end, len(t))
    phase = 2 * np.pi * np.cumsum(freq) / SR
    return 0.4 * np.sin(phase) + 0.2 * np.sin(2 * phase) + 0.1 * np.sin(3 * phase)


def _glide(path, f0_start, f0_end, secs=1.2):
    sf.write(str(path), _tone(f0_start, f0_end, secs).astype(np.float32), SR)


@pytest.fixture
def rising(tmp_path):
    p = tmp_path / "rising.wav"
    _glide(p, 110, 190)
    return ProsodyExtractor(p)


@pytest.fixture
def blank(tmp_path):
    """An extractor whose F0 track each test overwrites with a known contour."""
    p = tmp_path / "blank.wav"
    _glide(p, 150, 150, secs=3.0)
    return ProsodyExtractor(p)


def _inject(ex, f0_values, t0=0.0):
    """Replace the extractor's F0 track with `f0_values` (NaN = unvoiced), 10 ms apart."""
    ex.f0 = np.asarray(f0_values, dtype=float)
    ex.f0_times = t0 + np.arange(len(ex.f0)) * prosody.TIME_STEP


def _line(f_start, f_end, n):
    """n frames moving linearly in semitones from f_start to f_end."""
    return list(f_start * 2 ** (np.linspace(0, 12 * np.log2(f_end / f_start), n) / 12))


# --- F0 range and final contour ------------------------------------------------

def test_rising_contour(rising):
    stats = rising.segment_stats(0.0, 1.2)
    assert stats["final_contour"] == "rising"
    assert stats["final_slope_st"] > prosody.FINAL_SLOPE_ST
    assert 100 < stats["mean"] < 200
    assert stats["range"] > 40
    assert stats["range_st"] > 5


def test_falling_contour(tmp_path):
    p = tmp_path / "falling.wav"
    _glide(p, 190, 110)
    stats = ProsodyExtractor(p).segment_stats(0.0, 1.2)
    assert stats["final_contour"] == "falling"


def test_interpolated_f0_track(rising):
    track = rising.f0_track(0.0, 1.2)
    assert len(track) > 50
    times = [t for t, _ in track]
    assert times == sorted(times)
    values = [v for _, v in track]
    assert all(60 < v < 400 for v in values)


def test_a_high_cartoon_voice_is_not_halved(tmp_path):
    # the old fixed 400 Hz ceiling halved or dropped a voice like this one
    p = tmp_path / "child.wav"
    _glide(p, 450, 600)
    stats = ProsodyExtractor(p).segment_stats(0.0, 1.2)
    assert 450 < stats["mean"] < 620
    assert stats["f0_ceiling"] > 600


def test_each_turn_gets_its_own_range(tmp_path):
    # a short low voice (an adult) among a dominant high one (the kids)
    sig = np.concatenate([_tone(100, 110, 0.5), _tone(430, 470, 1.5)]).astype(np.float32)
    p = tmp_path / "two_voices.wav"
    sf.write(str(p), sig, SR)
    ex = ProsodyExtractor(p, spans=[(0.0, 0.5), (0.5, 2.0)])
    low, high = ex.segment_stats(0.05, 0.45), ex.segment_stats(0.55, 1.95)
    assert 95 < low["mean"] < 115
    assert 420 < high["mean"] < 480
    assert low["f0_ceiling"] < high["f0_floor"]
    # the reason it is per turn: one file-wide range, whose floor comes from
    # the dominant high voice, erases the low one
    assert ProsodyExtractor(p).segment_stats(0.05, 0.45)["mean"] is None


def test_the_contour_is_speaker_independent(tmp_path):
    # the same +20 % glide in a low and in a high voice: 16.7 vs 50 Hz/s, which
    # the old Hz threshold (30 Hz/s) read as flat vs rising
    slopes = []
    for f0 in (100, 300):
        p = tmp_path / f"glide{f0}.wav"
        _glide(p, f0, f0 * 1.2)
        slopes.append(ProsodyExtractor(p).final_slope(0.0, 1.2))
    assert slopes[0] == pytest.approx(slopes[1], rel=0.1)
    assert prosody.contour_label(slopes[0]) == prosody.contour_label(slopes[1])


def test_the_final_slope_ignores_a_blip_from_another_voice(blank):
    # a clear fall, then 6 frames of another voice an octave up
    _inject(blank, _line(220, 160, 40) + _line(330, 340, 6))
    assert blank.final_slope(0.0, 0.46) < -prosody.FINAL_SLOPE_ST


def test_the_final_slope_reads_the_last_voiced_run(blank):
    # a fall, an unvoiced consonant, then a final syllable that rises
    _inject(blank, _line(220, 160, 30) + [np.nan] * 6 + _line(150, 190, 20))
    assert blank.final_slope(0.0, 0.56) > prosody.FINAL_SLOPE_ST


def _frames(n, t0=0.0):
    return t0 + np.arange(n) * prosody.TIME_STEP


def test_short_octave_errors_are_folded_toward_the_median():
    # 280 Hz speech with two short harmonic bursts (×2, ×3-ish) and a subharmonic blip
    f0 = [560.0] * 4 + [280.0] * 10 + [600.0] * 8 + [270.0] * 12 + [70.0] * 3
    folded = prosody.fold_octave_errors(np.array(f0), _frames(len(f0)))
    assert np.all((folded > 200) & (folded < 400))
    assert folded[4:14] == pytest.approx(f0[4:14])  # the real run is untouched


def test_a_second_voice_is_not_an_octave_error():
    # a long run in another register is another speaker, not a tracking error
    f0 = [110.0] * 40 + [np.nan] * 5 + [250.0] * 30
    folded = prosody.fold_octave_errors(np.array(f0), _frames(len(f0)))
    assert np.array_equal(np.isnan(folded), np.isnan(f0))
    assert folded[~np.isnan(folded)] == pytest.approx(np.array(f0)[~np.isnan(f0)])


def test_octave_errors_do_not_inflate_the_turn_ceiling(blank):
    # 40 % of the turn's first-pass frames doubled in short bursts: Hirst's
    # 1.5·q75 must come from the real 150 Hz voice, not from the 300 Hz errors
    first_pass = blank.f0.copy()
    turn = np.nonzero((blank.f0_times >= 0.5) & (blank.f0_times <= 1.5))[0]
    first_pass[turn] = 150.0
    for start in range(0, len(turn), 25):
        first_pass[turn[start:start + 10]] = 300.0
    blank._retrack(first_pass, 0.5, 1.5)
    _, _, floor, ceiling = blank.ranges[-1]
    assert ceiling < 300 and floor < 150


def test_no_range_from_a_blip_of_voicing(blank):
    _inject(blank, [np.nan] * 20 + [200.0, 210, 205, 220, 215, 210] + [np.nan] * 20)
    stats = blank.segment_stats(0.0, 0.46)
    assert stats["mean"] is not None
    assert stats["range"] is None and stats["range_st"] is None


def test_unvoiced_segment(tmp_path):
    p = tmp_path / "noise.wav"
    rng = np.random.default_rng(48)
    sf.write(str(p), (0.05 * rng.standard_normal(SR)).astype(np.float32), SR)
    stats = ProsodyExtractor(p).segment_stats(0.0, 1.0)
    assert stats["final_contour"] in {"flat", "rising", "falling"}


# --- intonation units ------------------------------------------------------------

@pytest.mark.parametrize("tokens, kind", [
    (["I'm", "fine."], "statement"),
    (["Wow!"], "exclamation"),
    (["Are", "you", "coming?"], "yes_no_question"),
    (["What", "are", "you", "doing?"], "wh_question"),
    (["So,", "why", "not?"], "wh_question"),
    (["Randy,", "what?"], "wh_question"),
    (["Oh", "how", "nice?"], "wh_question"),
    (["Is", "that", "what", "you", "want?"], "yes_no_question"),
    (["What's", "up?"], "wh_question"),
    (["You're", "leaving?"], "yes_no_question"),
    (["I", "think"], "incomplete"),
    (["I", "don't", "know..."], "incomplete"),
])
def test_unit_type(tokens, kind):
    assert unit_type(tokens) == kind


def test_units_split_at_sentence_punctuation():
    words = [{"word": w} for w in ["Mr.", "Smith", "left.", "Did", "he?", "Well"]]
    assert split_units(words) == [(0, 2), (3, 4), (5, 5)]


def _words(spec):
    """[(word, start, end), …] → word dicts."""
    return [{"word": w, "start": s, "end": e} for w, s, e in spec]


def test_intonation_units_check_the_expected_contour(blank):
    # 0.0–1.0 s: a statement that falls; 1.2–2.0 s: a yes/no question that rises
    _inject(blank, _line(200, 150, 100) + [np.nan] * 20 + _line(150, 210, 90))
    words = _words([("I", 0.0, 0.3), ("left", 0.35, 0.6), ("early.", 0.65, 0.95),
                    ("Did", 1.2, 1.4), ("you?", 1.5, 1.85)])
    first, second = blank.intonation_units(words, seg_end=2.1)
    assert first["type"] == "statement" and first["words"] == [0, 2]
    assert first["final_contour"] == "falling" and first["matches_expected"] is True
    assert first["uptalk"] is False
    assert second["type"] == "yes_no_question" and second["expected_contour"] == "rising"
    assert second["final_contour"] == "rising" and second["matches_expected"] is True


def test_a_rising_statement_is_uptalk(blank):
    _inject(blank, _line(150, 200, 110))
    words = _words([("I", 0.0, 0.2), ("live", 0.25, 0.5), ("in", 0.55, 0.65),
                    ("Denver.", 0.7, 1.0)])
    unit, = blank.intonation_units(words, seg_end=1.1)
    assert unit["uptalk"] is True and unit["matches_expected"] is False


def test_a_rising_backchannel_is_not_uptalk(blank):
    _inject(blank, _line(150, 200, 60))
    unit, = blank.intonation_units(_words([("Okay,", 0.0, 0.2), ("okay.", 0.25, 0.55)]),
                                   seg_end=0.6)
    assert unit["final_contour"] == "rising" and unit["uptalk"] is False


def test_an_incomplete_unit_expects_nothing(blank):
    _inject(blank, _line(150, 200, 60))
    unit, = blank.intonation_units(_words([("and", 0.0, 0.2), ("then", 0.25, 0.55)]))
    assert unit["type"] == "incomplete"
    assert unit["expected_contour"] is None and unit["matches_expected"] is None


# --- prominence and word classes ---------------------------------------------------

def test_emphasis_on_the_most_prominent_word(tmp_path):
    # word 2 is higher-pitched and louder than word 1
    t1 = np.linspace(0, 0.5, int(SR * 0.5), endpoint=False)
    quiet = 0.15 * np.sin(2 * np.pi * 120 * t1)
    loud = 0.6 * np.sin(2 * np.pi * 220 * t1)
    gap = np.zeros(int(SR * 0.1))
    sig = np.concatenate([quiet, gap, loud]).astype(np.float32)
    p = tmp_path / "two.wav"
    sf.write(str(p), sig, SR)
    pros = ProsodyExtractor(p)
    idx = pros.emphasis_word_idx([
        {"start": 0.0, "end": 0.5},
        {"start": 0.6, "end": 1.1},
    ])
    assert idx == 1


def test_prominence_is_relative_to_the_segment(tmp_path):
    # the middle word is higher, louder and longer per phone than its neighbors
    def word(f0, amp, secs):
        t = np.linspace(0, secs, int(SR * secs), endpoint=False)
        return amp * (np.sin(2 * np.pi * f0 * t) + 0.5 * np.sin(4 * np.pi * f0 * t))

    gap = np.zeros(int(SR * 0.05))
    sig = np.concatenate([word(120, 0.1, 0.2), gap, word(180, 0.5, 0.45), gap,
                          word(125, 0.12, 0.2)]).astype(np.float32)
    p = tmp_path / "three.wav"
    sf.write(str(p), sig, SR)
    ex = ProsodyExtractor(p)
    words = [{"start": 0.0, "end": 0.2, "canonical": ["ð", "ə"]},
             {"start": 0.25, "end": 0.7, "canonical": ["k", "æ", "t"]},
             {"start": 0.75, "end": 0.95, "canonical": ["ɪ", "z"]}]
    scores = ex.word_prominence(words)
    assert scores[1] == 1.0
    assert all(0.0 <= s < 1.0 for s in (scores[0], scores[2]))
    assert peak_index(scores) == 1
    assert ex.word_prominence([]) == []


@pytest.mark.parametrize("word, cls", [
    ("the", "function"), ("I'm", "function"), ("gonna", "function"), ("of", "function"),
    ("Them,", "function"), ("can't", "content"), ("why", "content"), ("Water.", "content"),
])
def test_word_class(word, cls):
    assert word_class(word) == cls


# --- rhythm --------------------------------------------------------------------------

def _syllables(times, low_confidence=False):
    """One word whose realized phones put a vowel peak at each time in `times`."""
    real = []
    for t in times:
        real += [{"phone": "t", "start": t - 0.05, "end": t - 0.03},
                 {"phone": "ə", "start": t - 0.01, "end": t + 0.01}]
    return {"real": real, "low_confidence": low_confidence}


def test_even_syllables_have_zero_npvi():
    r = rhythm([_syllables([0.2, 0.4, 0.6, 0.8, 1.0])])
    assert r["npvi"] == 0.0 and r["n_intervals"] == 4 and r["n_pairs"] == 3
    assert r["approximate"] is True


def test_long_short_alternation_raises_npvi():
    # intervals 300, 100, 300, 100 ms: |d1-d2| / mean = 1 for every pair
    r = rhythm([_syllables([0.0, 0.3, 0.4, 0.7, 0.8])])
    assert r["npvi"] == pytest.approx(100.0)
    assert r["mean_ms"] == pytest.approx(200.0)


def test_pauses_and_unreliable_words_break_the_stretch():
    words = [_syllables([0.0, 0.2, 0.4]), _syllables([0.6, 0.8], low_confidence=True),
             _syllables([2.0, 2.2, 2.4])]
    r = rhythm(words)
    # two stretches of 2 intervals each; the gap to 2.0 s is a pause
    assert r["n_intervals"] == 4 and r["n_pairs"] == 2
    assert rhythm([_syllables([0.0, 0.2])]) is None


def test_rhythm_summary_pools_the_segments():
    a = rhythm([_syllables([0.0, 0.3, 0.4, 0.7])])
    b = rhythm([_syllables([5.0, 5.2, 5.4, 5.6])])
    pooled = rhythm_summary([a, None, b])
    assert pooled["n_pairs"] == a["n_pairs"] + b["n_pairs"]
    assert pooled["npvi"] == pytest.approx((a["npvi"] * a["n_pairs"] + b["npvi"] * b["n_pairs"])
                                           / pooled["n_pairs"], abs=0.1)
    ivs = np.array([300, 100, 300, 200, 200, 200], dtype=float)
    assert pooled["mean_ms"] == pytest.approx(ivs.mean(), abs=0.1)
    assert pooled["varco"] == pytest.approx(100 * ivs.std() / ivs.mean(), abs=0.2)
    assert rhythm_summary([None]) is None


def test_every_syllabic_consonant_is_a_nucleus_for_rhythm():
    """The TIMIT engine writes a syllabic ŋ (eng) as ŋ̍: rhythm must count it as the
    rules and the metrics do."""
    from phonotrainer.ipa_maps import SYLLABIC

    for phone in SYLLABIC:
        assert prosody._is_nucleus(phone), phone
