"""Tests de prosodia con audio sintético (glissandos generados con numpy)."""

import numpy as np
import pytest
import soundfile as sf

from phonotrainer.prosody import ProsodyExtractor

SR = 16000


def _glide(path, f0_start, f0_end, secs=1.2):
    """Glissando con armónicos para que el pitch tracker enganche bien."""
    t = np.linspace(0, secs, int(SR * secs), endpoint=False)
    freq = np.linspace(f0_start, f0_end, len(t))
    phase = 2 * np.pi * np.cumsum(freq) / SR
    sig = 0.4 * np.sin(phase) + 0.2 * np.sin(2 * phase) + 0.1 * np.sin(3 * phase)
    sf.write(str(path), sig.astype(np.float32), SR)


@pytest.fixture
def rising(tmp_path):
    p = tmp_path / "rising.wav"
    _glide(p, 110, 190)
    return ProsodyExtractor(p)


def test_contorno_ascendente(rising):
    stats = rising.segment_stats(0.0, 1.2)
    assert stats["final_contour"] == "rising"
    assert 100 < stats["mean"] < 200
    assert stats["range"] > 40


def test_contorno_descendente(tmp_path):
    p = tmp_path / "falling.wav"
    _glide(p, 190, 110)
    stats = ProsodyExtractor(p).segment_stats(0.0, 1.2)
    assert stats["final_contour"] == "falling"


def test_f0_track_interpolado(rising):
    track = rising.f0_track(0.0, 1.2)
    assert len(track) > 50
    times = [t for t, _ in track]
    assert times == sorted(times)
    values = [v for _, v in track]
    assert all(60 < v < 400 for v in values)


def test_enfasis_en_palabra_mas_prominente(tmp_path):
    # palabra 2 más aguda y fuerte que palabra 1
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


def test_segmento_sordo(tmp_path):
    p = tmp_path / "noise.wav"
    rng = np.random.default_rng(48)
    sf.write(str(p), (0.05 * rng.standard_normal(SR)).astype(np.float32), SR)
    stats = ProsodyExtractor(p).segment_stats(0.0, 1.0)
    assert stats["final_contour"] in {"flat", "rising", "falling"}
