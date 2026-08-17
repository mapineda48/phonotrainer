"""Audio extraction: any media file (video or audio) → 16 kHz mono WAV."""

from __future__ import annotations

import subprocess
from pathlib import Path

SAMPLE_RATE = 16000


class AudioExtractionError(RuntimeError):
    pass


def extract_audio(media_path: str | Path, out_path: str | Path,
                  sample_rate: int = SAMPLE_RATE) -> Path:
    """Convert any file carrying an audio track (webm, mp4, mp3, wav…) to a mono
    WAV at `sample_rate`. Returns the output path."""
    media_path = Path(media_path)
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(media_path),
        "-vn", "-ac", "1", "-ar", str(sample_rate),
        "-f", "wav", str(out_path),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise AudioExtractionError(
            f"ffmpeg failed ({proc.returncode}) on {media_path.name}: {proc.stderr[-800:]}"
        )
    return out_path


def load_wav(wav_path: str | Path):
    """Load a WAV file as mono float32. Returns (samples, sample_rate)."""
    import numpy as np
    import soundfile as sf

    samples, sr = sf.read(str(wav_path), dtype="float32")
    if samples.ndim > 1:
        samples = samples.mean(axis=1)
    return np.ascontiguousarray(samples), sr
