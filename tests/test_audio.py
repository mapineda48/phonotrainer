"""Audio extraction tests on synthetic fixtures (no copyrighted material)."""

import subprocess

import numpy as np
import pytest
import soundfile as sf

from phonotrainer.audio import AudioExtractionError, extract_audio, load_wav


def _make_tone(path, sr=44100, secs=1.0, freq=440.0, channels=2):
    t = np.linspace(0, secs, int(sr * secs), endpoint=False)
    tone = 0.3 * np.sin(2 * np.pi * freq * t).astype(np.float32)
    data = np.stack([tone] * channels, axis=1) if channels > 1 else tone
    sf.write(str(path), data, sr)


def test_stereo_audio_to_16k_mono(tmp_path):
    src = tmp_path / "tone.wav"
    _make_tone(src, sr=44100, channels=2)
    out = extract_audio(src, tmp_path / "out.wav")
    samples, sr = load_wav(out)
    assert sr == 16000
    assert samples.ndim == 1
    assert abs(len(samples) - 16000) < 200


def test_video_with_an_audio_track(tmp_path):
    # build a synthetic mp4 (solid-color video + tone) and extract its audio
    src_wav = tmp_path / "tone.wav"
    _make_tone(src_wav, channels=1)
    video = tmp_path / "clip.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-f", "lavfi", "-i", "color=c=blue:s=64x64:d=1",
         "-i", str(src_wav), "-shortest", str(video)],
        check=True,
    )
    out = extract_audio(video, tmp_path / "out.wav")
    samples, sr = load_wav(out)
    assert sr == 16000
    assert len(samples) > 8000


def test_file_without_audio_fails_clearly(tmp_path):
    bogus = tmp_path / "not_media.txt"
    bogus.write_text("hello")
    with pytest.raises(AudioExtractionError):
        extract_audio(bogus, tmp_path / "out.wav")
