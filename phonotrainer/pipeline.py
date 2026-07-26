"""Orquestador: media → audio → ASR → canónico(t) + real(t) → diff → prosodia → reporte."""

from __future__ import annotations

import json
from pathlib import Path

from . import __version__, phenomena
from .align_canonical import align_words
from .asr import transcribe
from .audio import extract_audio, load_wav
from .canonical import dict_pronunciation
from .diff import assign_real_to_words
from .phones_real import attract_to_canonical, build_engine
from .prosody import ProsodyExtractor

SEG_PAD = 0.15   # s de contexto acústico extra por segmento
REAL_TRIM = 0.05  # s fuera del segmento a partir de los cuales un fono real se descarta


def _noop(msg: str) -> None:
    pass


def analyze(media_path: str | Path, out_dir: str | Path,
            phone_engine: str = "wav2vec2", whisper_model: str = "small",
            language: str = "en", device: str = "cpu",
            attraction: bool = True, progress=_noop) -> dict:
    media_path = Path(media_path)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    progress("Extrayendo audio (ffmpeg → WAV 16 kHz mono)…")
    wav_path = extract_audio(media_path, out_dir / "audio.wav")

    progress(f"Transcribiendo con faster-whisper {whisper_model}…")
    transcript = transcribe(wav_path, model_size=whisper_model,
                            language=language, device=device)
    _dump(out_dir / "transcript.json", transcript)

    progress(f"Cargando motor de fonos ({phone_engine})…")
    engine = build_engine(phone_engine, device=device)
    samples, sr = load_wav(wav_path)

    progress("Cargando prosodia (parselmouth)…")
    pros = ProsodyExtractor(wav_path)

    analysis_segments = []
    canonical_dump, real_dump = [], []
    normalized_phones = 0   # MEJORA 0: fonos crudos saneados al inventario inglés
    attracted_phones = 0    # MEJORA 1: fonos atraídos al canónico
    n_seg = len(transcript["segments"])
    for si, seg in enumerate(transcript["segments"]):
        progress(f"Segmento {si + 1}/{n_seg}: fonos + alineación…")
        i0 = max(0, int((seg["start"] - SEG_PAD) * sr))
        i1 = min(len(samples), int((seg["end"] + SEG_PAD) * sr))
        audio = samples[i0:i1]
        t0 = i0 / sr

        log_probs = engine.log_probs(audio)
        real_phones = [
            p for p in engine.greedy_phones(audio, t_offset=t0, log_probs=log_probs)
            if seg["start"] - REAL_TRIM <= (p["start"] + p["end"]) / 2 <= seg["end"] + REAL_TRIM
        ]
        canon_words = align_words(engine, audio, seg["words"],
                                  t_offset=t0, log_probs=log_probs)
        real_dump.append({"segment": si, "phones": real_phones})
        canonical_dump.append({"segment": si, "words": canon_words})
        normalized_phones += sum(
            1 for p in real_phones if p.get("raw_phone", p["phone"]) != p["phone"]
        )

        # ventanas de palabra: span canónico forzado; si no hay, tiempos Whisper
        word_entries = []
        for w, cw in zip(seg["words"], canon_words):
            if cw["phones"]:
                w_start, w_end = cw["phones"][0]["start"], cw["phones"][-1]["end"]
            else:
                w_start, w_end = w["start"], w["end"]
            dic = dict_pronunciation(w["word"])
            word_entries.append({
                "word": w["word"],
                "start": w_start,
                "end": w_end,
                "canonical": cw["phones"],
                "alignment_fallback": cw["fallback"],
                "dict_arpabet": dic["arpabet"],
                "_dict": dic,
            })
        buckets = assign_real_to_words(real_phones, word_entries)
        for entry, bucket in zip(word_entries, buckets):
            if attraction:
                bucket = attract_to_canonical(bucket, entry["canonical"])
                attracted_phones += sum(1 for p in bucket if p.get("attracted"))
            entry["real"] = bucket

        detected = phenomena.detect(word_entries, first_in_segment=True)

        f0_stats = pros.segment_stats(seg["start"], seg["end"])
        words_json = []
        for w in detected:
            dic = w["_dict"]
            realized_raw = "".join(p.get("raw_phone", p["phone"]) for p in w["real"])
            realized = "".join(p["phone"] for p in w["real"])
            words_json.append({
                "word": w["word"],
                "start": round(w["start"], 3),
                "end": round(w["end"], 3),
                "canonical_ipa": "".join(p["phone"] for p in w["canonical"]),
                "canonical_aligned": [
                    [p["phone"], p["start"], p["end"]] for p in w["canonical"]
                ],
                "realized_ipa": realized,
                "realized_aligned": [
                    [p["phone"], p["start"], p["end"]] for p in w["real"]
                ],
                "realized_raw_ipa": realized_raw if realized_raw != realized else "",
                "attracted_count": sum(1 for p in w["real"] if p.get("attracted")),
                "diff_cost": round(
                    sum(op["cost"] for op in w["ops"]) / max(len(w["canonical"]), 1), 3
                ),
                "dict_ipa": dic["ipa"],
                "oov": dic["oov"],
                "phenomena": w["phenomena"],
                "low_confidence": w["low_confidence"],
                "boundary_link_next": w["boundary_link_next"],
                "lexical_form": w.get("lexical_form"),
                "alignment_fallback": w["alignment_fallback"],
            })

        analysis_segments.append({
            "start": seg["start"],
            "end": seg["end"],
            "text": seg["text"],
            "f0_stats": f0_stats,
            "f0_track": pros.f0_track(seg["start"], seg["end"]),
            "emphasis_word_idx": pros.emphasis_word_idx(seg["words"]),
            "words": words_json,
        })

    analysis = {
        "meta": {
            "source": media_path.name,
            "duration": transcript["duration"],
            "language": transcript["language"],
            "phonotrainer_version": __version__,
            "models": {
                "asr": f"faster-whisper {whisper_model} (int8)",
                "phones": "facebook/wav2vec2-lv-60-espeak-cv-ft" if phone_engine == "wav2vec2" else phone_engine,
                "alignment": "torchaudio forced_align sobre emisiones wav2vec2-espeak",
            },
            "attraction": attraction,
            "phone_cleanup": {
                "normalized_phones": normalized_phones,
                "attracted_phones": attracted_phones,
            },
        },
        "segments": analysis_segments,
        "summary": {
            "phenomena_counts": dict(phenomena.count_phenomena(analysis_segments)),
        },
    }

    progress("Guardando salidas…")
    _dump(out_dir / "canonical.json", canonical_dump)
    _dump(out_dir / "phones_real.json", real_dump)
    _dump(out_dir / "analysis.json", analysis)

    progress("Generando report.html…")
    from .report import render_html

    (out_dir / "report.html").write_text(render_html(analysis), encoding="utf-8")
    return analysis


def _dump(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
