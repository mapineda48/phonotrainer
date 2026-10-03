"""Dialogue separation stage: synthetic audio and a fake separator (no model loaded)."""

import sys
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf

from phonotrainer import separation
from phonotrainer.audio import extract_audio, load_wav


def _tone(path, secs=2.0, sr=44100, freq=440.0, amp=0.3, channels=2):
    t = np.arange(int(sr * secs)) / sr
    tone = (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)
    sf.write(str(path), np.stack([tone] * channels, axis=1), sr)
    return path


def _stub_load(monkeypatch, fn):
    """Replace the model loader; `fn` plays the separator (stereo [2, n] → mono [n])."""
    def load():
        if isinstance(fn, Exception):
            raise fn
        return fn

    monkeypatch.setattr(separation, "_load", load)


@pytest.fixture
def half(monkeypatch):
    """A 'separator' that keeps half of the downmixed mix, so its effect is measurable."""
    _stub_load(monkeypatch, lambda mix: 0.5 * mix.mean(axis=0))


def test_disabled_leaves_the_mix(tmp_path):
    wav = tmp_path / "audio.wav"
    out, meta = separation.prepare(tmp_path / "clip.webm", tmp_path, wav, enabled=False)
    assert out == wav
    assert meta == {"applied": False}
    assert not (tmp_path / separation.DIALOGUE_WAV).exists()


def test_writes_a_16k_mono_dialogue_track(tmp_path, half):
    media = _tone(tmp_path / "clip.wav")
    wav = extract_audio(media, tmp_path / "audio.wav")
    messages = []
    out, meta = separation.prepare(media, tmp_path, wav, enabled=True,
                                   progress=messages.append)
    assert out == tmp_path / separation.DIALOGUE_WAV
    assert meta["applied"] is True and meta["model"] == separation.MODEL
    assert meta["audio"] == separation.DIALOGUE_WAV
    assert any("Separating dialogue" in m for m in messages)

    dialogue, sr = load_wav(out)
    mix, _ = load_wav(wav)
    assert sr == 16000 and dialogue.ndim == 1
    # same timeline as audio.wav: the analysis times are played back over the mix
    assert abs(len(dialogue) - len(mix)) <= 2
    # the fake kept half of the signal, and the level must show exactly that (−6 dB):
    # a different downmix than audio.wav's would show up here
    ratio = np.sqrt((dialogue ** 2).mean() / (mix ** 2).mean())
    assert ratio == pytest.approx(0.5, rel=0.05)
    assert (tmp_path / "audio.wav").exists()      # the mix stays, for playback


def test_unavailable_separator_falls_back_to_the_mix(tmp_path, monkeypatch):
    _stub_load(monkeypatch, separation.SeparationUnavailable("no network"))
    wav = tmp_path / "audio.wav"
    messages = []
    out, meta = separation.prepare(_tone(tmp_path / "clip.wav"), tmp_path, wav,
                                   enabled=True, progress=messages.append)
    assert out == wav
    assert meta["applied"] is False and "no network" in meta["error"]
    assert any("skipped" in m for m in messages)


def test_a_failing_separator_does_not_abort(tmp_path, monkeypatch):
    def boom(mix):
        raise MemoryError("out of memory")

    _stub_load(monkeypatch, boom)
    wav = tmp_path / "audio.wav"
    out, meta = separation.prepare(_tone(tmp_path / "clip.wav"), tmp_path, wav, enabled=True)
    assert out == wav and meta["applied"] is False
    assert "MemoryError" in meta["error"]


def test_undecodable_media_falls_back(tmp_path, half):
    bogus = tmp_path / "not_media.txt"
    bogus.write_text("hello")
    wav = tmp_path / "audio.wav"
    out, meta = separation.prepare(bogus, tmp_path, wav, enabled=True)
    assert out == wav and meta["applied"] is False


def test_missing_demucs_package_is_unavailable(monkeypatch):
    separation._load.cache_clear()
    monkeypatch.setitem(sys.modules, "demucs.apply", None)   # makes the import fail
    with pytest.raises(separation.SeparationUnavailable, match="demucs"):
        separation._load()
    separation._load.cache_clear()


def test_the_separator_is_deterministic(monkeypatch):
    """No random time shift: the same clip must give the same dialogue track."""
    import demucs.apply
    import demucs.pretrained
    import torch

    calls = []

    class Net:
        sources = ["drums", "bass", "other", "vocals"]

        def eval(self):
            return self

    def apply_model(net, mix, **kwargs):
        calls.append(kwargs)
        return torch.zeros((1, len(net.sources), *mix.shape[1:]))

    monkeypatch.setattr(demucs.pretrained, "get_model", lambda name: Net())
    monkeypatch.setattr(demucs.apply, "apply_model", apply_model)
    separation._load.cache_clear()
    try:
        separation._load()(np.zeros((2, 44100), dtype=np.float32))
    finally:
        separation._load.cache_clear()
    assert calls and all(c.get("shifts") == 0 for c in calls)


def test_its_progress_messages_keep_the_bar_moving_forward(tmp_path, monkeypatch):
    """jobs.py turns progress messages into a percentage: the separation stage must
    land between the extraction and the ASR, whether it runs or is skipped."""
    from phonotrainer.jobs import estimate_percent

    media = _tone(tmp_path / "clip.wav")
    messages = []
    _stub_load(monkeypatch, lambda mix: mix.mean(axis=0))
    separation.prepare(media, tmp_path, tmp_path / "audio.wav", True, progress=messages.append)
    _stub_load(monkeypatch, separation.SeparationUnavailable("offline"))
    separation.prepare(media, tmp_path, tmp_path / "audio.wav", True, progress=messages.append)
    lo = estimate_percent("Extracting audio (ffmpeg → 16 kHz mono WAV)…")
    hi = estimate_percent("Transcribing with faster-whisper small…")
    assert len(messages) == 3
    for msg in messages:
        assert lo < estimate_percent(msg) < hi, msg


@pytest.mark.parametrize("shape", [(1000,), (44100 * 3 + 17,), (2, 44100 * 7)])
def test_overlap_add_is_transparent(shape):
    """An identity 'model' run in chunks must give the input back: the crossfades
    sum to one and no sample is dropped or doubled (mono or stereo)."""
    x = np.random.default_rng(0).standard_normal(shape).astype(np.float32)
    calls = []

    def identity(chunk):
        calls.append(chunk.shape[-1])
        return chunk

    y = separation.overlap_add(identity, x, sr=44100, chunk_s=2.0, overlap_s=0.5)
    assert np.allclose(y, x, atol=1e-5)
    assert max(calls) <= 2 * 44100


@pytest.mark.parametrize("flag, expected", [("--separate-dialogue", True),
                                            ("--no-separate-dialogue", False),
                                            (None, separation.DEFAULT_ENABLED)])
def test_the_cli_flag_reaches_the_pipeline(tmp_path, monkeypatch, flag, expected):
    from click.testing import CliRunner
    from conftest import mk_analysis

    from phonotrainer import pipeline
    from phonotrainer.cli import main

    seen = {}

    def fake(media, out_dir, **kwargs):
        seen.update(kwargs)
        return mk_analysis()

    monkeypatch.setattr(pipeline, "analyze", fake)
    media = _tone(tmp_path / "clip.wav", secs=0.2)
    args = ["analyze", str(media), "-o", str(tmp_path / "out"), "--no-index"]
    result = CliRunner().invoke(main, args + ([flag] if flag else []))
    assert result.exit_code == 0, result.output
    assert seen["separate_dialogue"] is expected


def test_the_pipeline_and_the_server_agree_on_the_default():
    import inspect

    from phonotrainer import pipeline
    from phonotrainer.server import Options

    default = inspect.signature(pipeline.analyze).parameters["separate_dialogue"].default
    assert default is Options().separate_dialogue is separation.DEFAULT_ENABLED


def test_a_dialogue_track_from_an_earlier_run_does_not_survive(tmp_path, monkeypatch):
    """Re-analyzing into the same directory without separation (or with a
    separation that fails) must not leave the previous run's dialogue behind:
    the interface would play it against the new analysis."""
    media = _tone(tmp_path / "clip.wav")
    wav = extract_audio(media, tmp_path / "audio.wav")
    stale = tmp_path / separation.DIALOGUE_WAV

    stale.write_bytes(b"an earlier run")
    separation.prepare(media, tmp_path, wav, enabled=False)
    assert not stale.exists()

    stale.write_bytes(b"an earlier run")
    _stub_load(monkeypatch, separation.SeparationUnavailable("offline"))
    separation.prepare(media, tmp_path, wav, enabled=True)
    assert not stale.exists()


def test_a_failing_write_leaves_no_partial_track_and_does_not_abort(tmp_path, monkeypatch, half):
    import soundfile as sf

    media = _tone(tmp_path / "clip.wav")
    wav = extract_audio(media, tmp_path / "audio.wav")

    def disk_full(path, *args, **kwargs):
        Path(path).write_bytes(b"half a file")
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(sf, "write", disk_full)
    out, meta = separation.prepare(media, tmp_path, wav, enabled=True)
    assert out == wav and meta["applied"] is False
    assert "No space left" in meta["error"]
    assert list(tmp_path.glob("audio_dialogue*")) == []


def test_a_failing_resample_does_not_abort(tmp_path, monkeypatch, half):
    import torchaudio

    def broken(*args, **kwargs):
        raise RuntimeError("resampler exploded")

    monkeypatch.setattr(torchaudio.functional, "resample", broken)
    media = _tone(tmp_path / "clip.wav")
    wav = extract_audio(media, tmp_path / "audio.wav")
    out, meta = separation.prepare(media, tmp_path, wav, enabled=True)
    assert out == wav and meta["applied"] is False


def test_block_statistics_match_the_whole_track():
    x = np.random.default_rng(1).standard_normal((2, 44100 * 5 + 123)).astype(np.float32)
    mean, std = separation._mono_stats(x, block=44100)
    mono = x.mean(axis=0, dtype=np.float64)
    assert mean == pytest.approx(mono.mean(), abs=1e-9)
    assert std == pytest.approx(mono.std(), rel=1e-6)


def test_overlap_add_can_downmix_as_it_goes():
    """Stereo in, mono out per chunk (how the separator saves memory): the result
    is the same as downmixing the whole track afterwards."""
    x = np.random.default_rng(2).standard_normal((2, 44100 * 7)).astype(np.float32)
    y = separation.overlap_add(lambda c: c.mean(axis=0), x.T.copy().T, sr=44100,
                               chunk_s=2.0, overlap_s=0.5)
    assert y.shape == (x.shape[1],)
    assert np.allclose(y, x.mean(axis=0), atol=1e-5)


def test_a_track_past_the_length_limit_falls_back_to_the_mix(tmp_path, monkeypatch, half):
    media = _tone(tmp_path / "clip.wav")
    wav = extract_audio(media, tmp_path / "audio.wav")
    monkeypatch.setattr(separation, "MAX_SECONDS", 1.0)      # the clip lasts 2 s
    out, meta = separation.prepare(media, tmp_path, wav, enabled=True)
    assert out == wav and meta["applied"] is False and "limit" in meta["error"]


def test_the_decode_leaves_no_temporary_file_behind(tmp_path, half):
    media = _tone(tmp_path / "clip.wav")
    wav = extract_audio(media, tmp_path / "audio.wav")
    separation.prepare(media, tmp_path, wav, enabled=True)
    assert sorted(p.name for p in tmp_path.iterdir()) == [
        "audio.wav", "audio_dialogue.wav", "clip.wav"]
