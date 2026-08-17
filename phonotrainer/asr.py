"""ASR with faster-whisper: words + timestamps, text left UNPOSTPROCESSED."""

from __future__ import annotations

from pathlib import Path


def transcribe(wav_path: str | Path, model_size: str = "small",
               language: str = "en", device: str = "cpu") -> dict:
    """Transcribe and return segments with their words and timings.

    The text is kept exactly as Whisper emits it (if it writes "gonna", "gonna" stays).
    """
    from faster_whisper import WhisperModel

    model = WhisperModel(model_size, device=device, compute_type="int8")
    segments, info = model.transcribe(
        str(wav_path),
        language=language,
        word_timestamps=True,
        vad_filter=True,
    )

    out_segments = []
    for seg in segments:
        words = [
            {
                "word": w.word.strip(),
                "start": round(w.start, 3),
                "end": round(w.end, 3),
                "probability": round(w.probability, 3),
            }
            for w in (seg.words or [])
            if w.word.strip()
        ]
        if not words:
            continue
        out_segments.append({
            "start": round(seg.start, 3),
            "end": round(seg.end, 3),
            "text": seg.text.strip(),
            "words": words,
        })

    return {
        "language": info.language,
        "language_probability": round(info.language_probability, 3),
        "duration": round(info.duration, 3),
        "segments": out_segments,
    }
