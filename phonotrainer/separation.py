"""Dialogue separation for film/TV audio: keep the speech, drop music and effects.

A soundtrack lays dialogue over music, effects and laugh tracks, and the phone
recognizer reads that bed as phones (or as nothing: the low-confidence words). This
stage runs Hybrid Transformer Demucs (htdemucs; Rouard, Massa & Défossez, ICASSP
2023) over the ORIGINAL media and keeps its "vocals" stem — where dialogue lands — as
audio_dialogue.wav. That file feeds ASR, phones and prosody; audio.wav (the original
mix) is still what gets played.

Why this model and why on by default (measured 2026-09, see the D2 report): speech
mixed with a film score at 0–10 dB SNR comes out of it about half as far from the
clean recording (phone edit distance 10.0→5.1 % at 10 dB, 26.5→10.5 % at 0 dB), and
low-confidence words halve on the noisiest stretches of real TV clips; on speech
that is already clean it moves ~2 % of the phones. MRX, the soundtrack-specific
"cocktail fork" model, was tried as well and removed a large part of the speech.

The stage never blocks an analysis: if demucs or its weights are missing, or the
separation fails (decode, model, resample, disk), it says so in the progress log and
the analysis carries on over the original mix (`meta.dialogue_separation.applied` is
then false). A dialogue track from an earlier run into the same directory is removed
first, and the new one is written under a temporary name: whatever sits at
audio_dialogue.wav always belongs to the analysis next to it.

Memory: the 44.1 kHz stereo decode is memory-mapped from a temporary file next to
the output (1.3 GB of disk per hour, deleted afterwards), the statistics are taken
block by block and each chunk is reduced to mono as it comes out of the model.
Measured with a stand-in model (2026-09): ≈0.6 GB of numpy per hour of audio plus
≈0.25 GB for the chunk in flight, ≈1 GB per hour with the resampling — it was ≈6 GB
per hour. Past MAX_SECONDS the stage is skipped with a warning rather than risk the
OOM killer taking the whole server down.
"""

from __future__ import annotations

import logging
import subprocess
import time
from functools import lru_cache
from pathlib import Path

import numpy as np

from .audio import SAMPLE_RATE, AudioExtractionError

logger = logging.getLogger("phonotrainer.separation")

DIALOGUE_WAV = "audio_dialogue.wav"
MODEL = "htdemucs"        # weights: HF hub, adefossez/HTDemucs (~80 MB)
MODEL_SR = 44100          # the rate htdemucs was trained at
DEFAULT_ENABLED = True

# A whole episode at once would not fit: htdemucs returns four stereo stems (≈5 GB for
# an hour of audio). 60 s chunks crossfaded over 1 s are inaudible in the output.
CHUNK_S = 60.0
OVERLAP_S = 1.0
# ≈1 GB of RAM per hour (see the module docstring): three hours is ≈3 GB on top of
# the model, about what a laptop can spare.
MAX_SECONDS = 3 * 3600.0


class SeparationUnavailable(RuntimeError):
    """The separator cannot run (no package, no weights, no network, a failing decode…)."""


def _noop(msg: str) -> None:
    pass


def prepare(media_path: str | Path, out_dir: str | Path, wav_path: Path,
            enabled: bool, progress=_noop) -> tuple[Path, dict]:
    """Pipeline hook, right after the audio extraction.

    Returns the WAV the analysis must read (the dialogue stem, or `wav_path` itself
    when separation is off or unavailable) and the `meta.dialogue_separation` entry.
    """
    out = Path(out_dir) / DIALOGUE_WAV
    # a track left by an earlier run into this directory is not this analysis'
    # dialogue: the interface would play it against the new one
    out.unlink(missing_ok=True)
    if not enabled:
        return wav_path, {"applied": False}
    progress(f"Separating dialogue from music and effects ({MODEL})…")
    try:
        _check_length(wav_path)
        info = separate_file(media_path, out)
    except SeparationUnavailable as exc:
        logger.warning("dialogue separation skipped: %s", exc)
        progress(f"Dialogue separation skipped ({exc}); analyzing the original mix.")
        return wav_path, {"applied": False, "model": MODEL, "error": str(exc)}
    return out, {"applied": True, "model": MODEL, "audio": DIALOGUE_WAV, **info}


def separate_file(media_path: str | Path, out_path: str | Path) -> dict:
    """Separate the dialogue of any media file into a 16 kHz mono WAV.

    Every failure past loading the model (decode, model, resample, a full disk) is
    a SeparationUnavailable, and a half-written file never reaches `out_path`.
    """
    speech_fn = _load()
    out_path = Path(out_path)
    partial = out_path.with_name(f"{out_path.stem}.partial{out_path.suffix}")
    raw = out_path.with_name(f"{out_path.stem}.decode.f32")
    t0 = time.monotonic()
    try:
        import soundfile as sf
        import torch
        import torchaudio

        out_path.parent.mkdir(parents=True, exist_ok=True)
        mix = _decode(media_path, raw)
        speech = speech_fn(mix)
        del mix
        y = torchaudio.functional.resample(torch.from_numpy(speech), MODEL_SR,
                                           SAMPLE_RATE)
        del speech
        y = np.clip(y.numpy(), -1.0, 1.0)
        sf.write(str(partial), y, SAMPLE_RATE, subtype="PCM_16")
        partial.replace(out_path)
    except AudioExtractionError as exc:
        partial.unlink(missing_ok=True)
        raise SeparationUnavailable(str(exc)) from exc
    except Exception as exc:                      # noqa: BLE001 — never abort the analysis
        partial.unlink(missing_ok=True)
        raise SeparationUnavailable(f"{type(exc).__name__}: {exc}") from exc
    finally:
        raw.unlink(missing_ok=True)
    return {"seconds": round(time.monotonic() - t0, 1)}


def _check_length(wav_path: str | Path) -> None:
    """Refuse (as unavailable, i.e. fall back to the mix) past MAX_SECONDS."""
    import soundfile as sf

    try:
        seconds = sf.info(str(wav_path)).duration
    except Exception:                             # noqa: BLE001 — unknown: let it try
        return
    if seconds > MAX_SECONDS:
        raise SeparationUnavailable(
            f"{seconds / 60:.0f} min of audio is past the {MAX_SECONDS / 3600:.0f} h "
            f"limit (≈1 GB of RAM per hour)")


def fetch_weights() -> None:
    """Download (once) the separator's weights into the Hugging Face hub cache."""
    _load()


@lru_cache(maxsize=1)
def _load():
    """The speech-stem function: stereo [2, n] at 44.1 kHz → mono [n]."""
    import torch

    try:
        from demucs.apply import apply_model
        from demucs.pretrained import get_model
    except ImportError as exc:
        raise SeparationUnavailable("the demucs package is not installed") from exc
    try:
        net = get_model(MODEL)
    except Exception as exc:                      # noqa: BLE001 — offline, 404…
        raise SeparationUnavailable(
            f"could not fetch the {MODEL} weights ({type(exc).__name__}: {exc})") from exc
    net.eval()
    vocals = net.sources.index("vocals")

    def run(chunk: np.ndarray) -> np.ndarray:
        # shifts=0: demucs' default (1) applies ONE random time offset and nothing
        # to average it with, so it buys no quality and makes two runs of the same
        # clip disagree (and, downstream, their transcripts)
        with torch.inference_mode():
            out = apply_model(net, torch.from_numpy(chunk)[None], shifts=0,
                              split=True, overlap=0.25, progress=False)[0]
        return out[vocals].numpy()

    def speech(mix: np.ndarray) -> np.ndarray:
        # standardized with whole-track statistics, as demucs' own CLI does; each
        # chunk is normalized, separated and downmixed on its own (all linear, so
        # the crossfade gives the same result as doing it over the whole track)
        mean, std = _mono_stats(mix)

        def chunk_speech(chunk: np.ndarray) -> np.ndarray:
            stems = run(((chunk - mean) / std).astype(np.float32, copy=False))
            return (stems * std + mean).mean(axis=0)

        return overlap_add(chunk_speech, mix, MODEL_SR)

    return speech


def _mono_stats(mix: np.ndarray, block: int = MODEL_SR * 60) -> tuple[float, float]:
    """Mean and std of the downmix, block by block: no full-length temporary."""
    n = mix.shape[-1]
    total = squares = 0.0
    for i in range(0, n, block):
        mono = mix[..., i:i + block].mean(axis=0, dtype=np.float64)
        total += float(mono.sum())
        squares += float((mono * mono).sum())
    mean = total / n
    return mean, float(np.sqrt(max(squares / n - mean * mean, 0.0))) + 1e-8


def _decode(media_path: str | Path, raw: Path) -> np.ndarray:
    """Any media → stereo float32 [2, n] at MODEL_SR (the model's own rate: the 16 kHz
    extraction would have thrown away the upper band it relies on). Mono sources come
    back duplicated; ffmpeg downmixes surround to stereo.

    The decode goes to `raw` on disk (next to the output, never /tmp: tmpfs is RAM)
    and comes back memory-mapped: an hour of it is 1.3 GB, and reading it through a
    pipe held it twice in the process. Mapped, its pages belong to the file.
    """
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
           "-i", str(media_path), "-vn", "-ac", "2", "-ar", str(MODEL_SR),
           "-f", "f32le", str(raw)]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0 or not raw.is_file() or raw.stat().st_size < 8:
        raise AudioExtractionError(
            f"ffmpeg could not decode {Path(media_path).name} for separation: "
            f"{proc.stderr.decode(errors='replace')[-400:]}")
    return np.memmap(raw, dtype=np.float32, mode="r").reshape(-1, 2).T


def overlap_add(fn, x: np.ndarray, sr: int, chunk_s: float = CHUNK_S,
                overlap_s: float = OVERLAP_S) -> np.ndarray:
    """Apply `fn` (array → array of the same length; time on the last axis, the
    leading axes may change, e.g. stereo in, mono out) in chunks, crossfading
    linearly over the overlaps so the fades always sum to one."""
    n = x.shape[-1]
    chunk, ov = int(chunk_s * sr), int(overlap_s * sr)
    if n <= chunk:
        return fn(np.ascontiguousarray(x))
    out = None
    start = 0
    while True:
        end = min(start + chunk, n)
        y = fn(np.ascontiguousarray(x[..., start:end]))
        if out is None:
            out = np.zeros(y.shape[:-1] + (n,), dtype=np.float32)
        w = np.ones(end - start, dtype=np.float32)
        if start > 0:
            w[:ov] = np.linspace(0.0, 1.0, ov, dtype=np.float32)
        if end < n:
            w[-ov:] *= np.linspace(1.0, 0.0, ov, dtype=np.float32)
        out[..., start:end] += y * w
        if end == n:
            return out
        start = end - ov
