import json
import sys
import time
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def mk_phones(spaced: str, t0: float = 0.0, dur: float = 0.06) -> list[dict]:
    """'d ə z' → lista de dicts de fonos con tiempos sintéticos consecutivos."""
    phones = []
    t = t0
    for p in spaced.split():
        phones.append({"phone": p, "start": round(t, 3), "end": round(t + dur, 3), "score": 1.0})
        t += dur
    return phones


def mk_word(text: str, canonical: str, real: str, t0: float = 0.0, dur: float = 0.06,
            real_dur: float | None = None, dict_arpabet: list[str] | None = None) -> dict:
    """Construye la entrada de palabra que consume phenomena.detect()."""
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


# --- fixtures de la interfaz web (jobs + API) --------------------------------
# El pipeline real carga ~1.8 GB de modelos: los tests del servidor usan este
# doble, que produce las mismas salidas en disco con datos sintéticos.

def mk_analysis_word(word: str, t0: float, canonical: str, realized: str,
                     **extra) -> dict:
    """Palabra con la forma exacta que escribe pipeline.analyze en analysis.json."""
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
    """analysis.json mínimo pero completo (2 segmentos, fenómenos y prosodia)."""
    seg0 = {
        "start": 0.0, "end": 1.2, "text": "does that work",
        "f0_stats": {"mean": 118.0, "range": 62.0, "final_contour": "rising"},
        "f0_track": [[round(0.1 * i, 3), 110.0 + i] for i in range(12)],
        "emphasis_word_idx": 2,
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
    """WAV mono real (sin numpy) para probar el servido con Range."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as fh:
        fh.setnchannels(1)
        fh.setsampwidth(2)
        fh.setframerate(rate)
        fh.writeframes(b"\x00\x00" * int(rate * seconds))
    return path


def fake_analyze(media_path, out_dir, progress=lambda m: None, **options):
    """Doble de `pipeline.analyze`: mismos artefactos, sin modelos."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    progress("Extrayendo audio (ffmpeg → WAV 16 kHz mono)…")
    write_silent_wav(out_dir / "audio.wav")
    progress("Transcribiendo con faster-whisper small…")
    progress("Segmento 1/2: fonos + alineación…")
    progress("Segmento 2/2: fonos + alineación…")
    analysis = mk_analysis(source=Path(media_path).name)
    analysis["meta"]["options"] = options
    progress("Guardando salidas…")
    (out_dir / "analysis.json").write_text(json.dumps(analysis, ensure_ascii=False),
                                           encoding="utf-8")
    progress("Generando report.html…")
    (out_dir / "report.html").write_text("<html>fake</html>", encoding="utf-8")
    return analysis


def wait_until(predicate, timeout: float = 10.0, interval: float = 0.02):
    """Espera activa breve para jobs que corren en otro hilo."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError(f"condición no cumplida en {timeout}s")
