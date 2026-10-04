import json
import sys
import time
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def mk_phones(spaced: str, t0: float = 0.0, dur: float = 0.06) -> list[dict]:
    """'d ə z' → a list of phone dicts with synthetic consecutive timings."""
    phones = []
    t = t0
    for p in spaced.split():
        phones.append({"phone": p, "start": round(t, 3), "end": round(t + dur, 3), "score": 1.0})
        t += dur
    return phones


def mk_word(text: str, canonical: str, real: str, t0: float = 0.0, dur: float = 0.06,
            real_dur: float | None = None, dict_arpabet: list[str] | None = None) -> dict:
    """Build the word entry that phenomena.detect() consumes."""
    can = mk_phones(canonical, t0, dur)
    rea = mk_phones(real, t0, real_dur if real_dur is not None else dur)
    end = max([p["end"] for p in can + rea], default=t0)
    word = {
        "word": text,
        "start": t0,
        "end": round(end, 3),
        "canonical": can,
        "real": rea,
    }
    if dict_arpabet is not None:
        word["dict_arpabet"] = dict_arpabet
    return word


# --- fixtures for the web interface (jobs + API) -----------------------------
# The real pipeline loads ~1.8 GB of models: the server tests use this double,
# which writes the same artifacts to disk from synthetic data.

def mk_analysis_word(word: str, t0: float, canonical: str, realized: str,
                     **extra) -> dict:
    """A word shaped exactly the way pipeline.analyze writes it into analysis.json."""
    def aligned(ipa: str, start: float) -> list[list]:
        phones, t = [], start
        for p in ipa.split():
            phones.append([p, round(t, 3), round(t + 0.05, 3)])
            t += 0.06
        return phones

    can, real = aligned(canonical, t0), aligned(realized, t0)
    end = max((p[2] for p in can + real), default=t0 + 0.05)
    entry = {
        "word": word, "start": round(t0, 3), "end": round(end, 3),
        "canonical_ipa": canonical.replace(" ", ""), "canonical_aligned": can,
        "realized_ipa": realized.replace(" ", ""), "realized_aligned": real,
        "realized_raw_ipa": "", "attracted_count": 0, "diff_cost": 0.0,
        "dict_ipa": canonical.replace(" ", ""), "oov": False, "phenomena": [],
        "low_confidence": False, "boundary_link_next": False,
        "lexical_form": None, "alignment_fallback": False,
    }
    entry.update(extra)
    return entry


def mk_analysis(source: str = "clip.wav") -> dict:
    """A minimal yet complete analysis.json (2 segments, phenomena and prosody)."""
    seg0 = {
        "start": 0.0, "end": 1.2, "text": "does that work",
        "f0_stats": {"mean": 118.0, "range": 62.0, "final_contour": "rising"},
        "f0_track": [[round(0.1 * i, 3), 110.0 + i] for i in range(12)],
        "emphasis_word_idx": 2,
        "prominence": [0.4, 0.2, 1.0],
        "word_classes": ["function", "function", "content"],
        "intonation_units": [{
            "start": 0.0, "end": 1.1, "words": [0, 2], "text": "does that work",
            "type": "incomplete", "final_contour": "rising", "final_slope_st": 5.2,
            "expected_contour": None, "matches_expected": None, "uptalk": False,
        }],
        "rhythm": None,
        "words": [
            mk_analysis_word("does", 0.0, "d ʌ z", "d ə z",
                             phenomena=["vowel_reduction"], boundary_link_next=True),
            mk_analysis_word("that", 0.4, "ð æ t", "ð æ",
                             phenomena=["t_deletion"], diff_cost=0.8),
            mk_analysis_word("work", 0.8, "w ɝ k", "w ɝ k"),
        ],
    }
    seg1 = {
        "start": 2.0, "end": 3.0, "text": "wanna go",
        "f0_stats": {"mean": 130.0, "range": 20.0, "final_contour": "falling"},
        "f0_track": [[round(2.0 + 0.1 * i, 3), 130.0 - i] for i in range(10)],
        "emphasis_word_idx": 0,
        "prominence": [1.0, 0.6],
        "word_classes": ["function", "content"],
        "intonation_units": [{
            "start": 2.0, "end": 2.9, "words": [0, 1], "text": "wanna go",
            "type": "incomplete", "final_contour": "falling", "final_slope_st": -4.1,
            "expected_contour": None, "matches_expected": None, "uptalk": False,
        }],
        "rhythm": None,
        "words": [
            mk_analysis_word("wanna", 2.0, "w ɑ n ə", "w ɑ n ə",
                             phenomena=["contraction_lex"], lexical_form="want to",
                             attracted_count=1, realized_raw_ipa="wɑnə"),
            mk_analysis_word("go", 2.6, "ɡ oʊ", "ɡ oʊ", low_confidence=True),
        ],
    }
    return {
        "meta": {
            "source": source, "duration": 3.0, "language": "en",
            "phonotrainer_version": "0.1.0",
            "models": {"asr": "faster-whisper small (int8)",
                       "phones": "facebook/wav2vec2-lv-60-espeak-cv-ft",
                       "alignment": "torchaudio forced_align"},
            "attraction": True,
            "phone_cleanup": {"normalized_phones": 1, "attracted_phones": 1},
        },
        "segments": [seg0, seg1],
        "summary": {"phenomena_counts": {"vowel_reduction": 1, "t_deletion": 1,
                                         "contraction_lex": 1}},
    }


def write_silent_wav(path: Path, seconds: float = 1.0, rate: int = 16000) -> Path:
    """A real mono WAV (no numpy involved) for exercising Range serving."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as fh:
        fh.setnchannels(1)
        fh.setsampwidth(2)
        fh.setframerate(rate)
        fh.writeframes(b"\x00\x00" * int(rate * seconds))
    return path


def fake_analyze(media_path, out_dir, progress=lambda m: None, **options):
    """Double for `pipeline.analyze`: same artifacts, no models.

    The progress strings must match `pipeline.analyze` word for word: jobs.py parses
    them to derive the completion percentage.
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    progress("Extracting audio (ffmpeg → 16 kHz mono WAV)…")
    write_silent_wav(out_dir / "audio.wav")
    # like separation.prepare: the dialogue track exists only when this run made it
    (out_dir / "audio_dialogue.wav").unlink(missing_ok=True)
    if options.get("separate_dialogue"):
        progress("Separating dialogue from music and effects (htdemucs)…")
        write_silent_wav(out_dir / "audio_dialogue.wav", seconds=0.5)
        dialogue = {"applied": True, "model": "htdemucs", "audio": "audio_dialogue.wav"}
    else:
        dialogue = {"applied": False}
    progress("Transcribing with faster-whisper small…")
    progress("Segment 1/2: phones + alignment…")
    progress("Segment 2/2: phones + alignment…")
    analysis = mk_analysis(source=Path(media_path).name)
    analysis["meta"]["options"] = options
    analysis["meta"]["dialogue_separation"] = dialogue
    progress("Saving outputs…")
    (out_dir / "analysis.json").write_text(json.dumps(analysis, ensure_ascii=False),
                                           encoding="utf-8")
    progress("Generating report.html…")
    (out_dir / "report.html").write_text("<html>fake</html>", encoding="utf-8")
    return analysis


def wait_until(predicate, timeout: float = 10.0, interval: float = 0.02):
    """Short busy-wait for jobs that run on another thread."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError(f"condition not met within {timeout}s")
