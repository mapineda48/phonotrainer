"""Orchestrator: media → audio → ASR → canonical(t) + realized(t) → diff → prosody → report."""

from __future__ import annotations

import json
from pathlib import Path

from . import __version__, phenomena, separation, variants
from .align_canonical import align_words
from .asr import transcribe
from .audio import extract_audio, load_wav
from .canonical import dict_pronunciation
from .diff import assign_real_to_words
from .phones_real import DEFAULT_ENGINE, attract_to_canonical, build_engine
from .prosody import ProsodyExtractor, peak_index, rhythm, word_class

SEG_PAD = 0.15   # s of extra acoustic context per segment
REAL_TRIM = 0.05  # s outside the segment beyond which a realized phone is discarded
# Two neighboring segments decode their overlapping padding separately: a phone
# of each less than this apart is the same sound, heard twice (CTC peaks of one
# sound land within a frame or two of each other; distinct phones are ≥ ~40 ms apart).
DUPLICATE_TOL = 0.03  # s


def _noop(msg: str) -> None:
    pass


def analyze(media_path: str | Path, out_dir: str | Path,
            phone_engine: str = DEFAULT_ENGINE, whisper_model: str = "small",
            language: str = "en", device: str = "cpu",
            attraction: bool | None = None,
            separate_dialogue: bool = separation.DEFAULT_ENABLED,
            progress=_noop) -> dict:
    """`attraction` None = the engine's default (on for espeak, off for timit61)."""
    media_path = Path(media_path)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    progress("Extracting audio (ffmpeg → 16 kHz mono WAV)…")
    wav_path = extract_audio(media_path, out_dir / "audio.wav")
    # from here on, the analysis reads the dialogue stem; audio.wav stays for playback
    wav_path, dialogue = separation.prepare(media_path, out_dir, wav_path,
                                            separate_dialogue, progress=progress)

    progress(f"Transcribing with faster-whisper {whisper_model}…")
    transcript = transcribe(wav_path, model_size=whisper_model,
                            language=language, device=device)
    _dump(out_dir / "transcript.json", transcript)

    progress(f"Loading phone engine ({phone_engine})…")
    engine = build_engine(phone_engine, device=device)
    if attraction is None:
        attraction = engine.attraction_default
    samples, sr = load_wav(wav_path)

    progress("Loading prosody (parselmouth)…")
    # each Whisper segment is (usually) one speaker turn: its F0 range is adapted
    pros = ProsodyExtractor(wav_path,
                            spans=[(s["start"], s["end"]) for s in transcript["segments"]])

    analysis_segments = []
    canonical_dump, real_dump = [], []
    normalized_phones = 0   # IMPROVEMENT 0: raw phones mapped onto the English inventory
    attracted_phones = 0    # IMPROVEMENT 1: phones attracted to the canonical form
    segments = transcript["segments"]
    n_seg = len(segments)
    # pass 1, acoustic: each segment over its own padded window
    acoustic = []
    for si, seg in enumerate(segments):
        progress(f"Segment {si + 1}/{n_seg}: phones + alignment…")
        i0 = max(0, int((seg["start"] - SEG_PAD) * sr))
        i1 = min(len(samples), int((seg["end"] + SEG_PAD) * sr))
        audio = samples[i0:i1]
        t0 = i0 / sr

        log_probs = engine.log_probs(audio)
        canon_words = align_words(engine, audio, seg["words"],
                                  t_offset=t0, log_probs=log_probs)
        greedy = engine.greedy_phones(audio, t_offset=t0, log_probs=log_probs)
        if engine.form_scoring:
            forms = variants.score_segment(engine.tokenizer, engine.blank_id, log_probs,
                                           [w["word"] for w in seg["words"]])
        else:
            forms = [None] * len(seg["words"])
        acoustic.append((canon_words, greedy, forms))
    # keep the realized phones inside each segment — as Whisper bounds it, or as
    # far as the forced canonical reaches: Whisper often closes a segment before
    # its last consonant ("scene!" ends at 5.36 s, its /n/ peaks at 5.47 s). Where
    # two neighbors' windows overlap, a sound both decodes heard is kept once.
    kept = [_kept(greedy, *_keep_window(seg, canon_words))
            for seg, (canon_words, greedy, _) in zip(segments, acoustic)]
    for i in range(n_seg - 1):
        cut = _segment_boundary(segments[i], acoustic[i][0], segments[i + 1],
                                acoustic[i + 1][0])
        kept[i], kept[i + 1] = _dedupe_overlap(kept[i], kept[i + 1], cut)

    # pass 2: phones → words → phenomena, segment by segment
    for si, (seg, (canon_words, _, forms), real_phones) in enumerate(
            zip(segments, acoustic, kept)):
        real_dump.append({"segment": si, "phones": real_phones})
        canonical_dump.append({"segment": si, "words": canon_words})
        normalized_phones += sum(
            1 for p in real_phones if p.get("raw_phone", p["phone"]) != p["phone"]
        )

        # word windows: the forced canonical span; failing that, Whisper's times
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
                "oov": dic["oov"],
                "_dict": dic,
            })
        buckets = assign_real_to_words(real_phones, word_entries)
        for entry, bucket in zip(word_entries, buckets):
            if attraction:
                bucket = attract_to_canonical(bucket, entry["canonical"])
                attracted_phones += sum(1 for p in bucket if p.get("attracted"))
            entry["real"] = bucket

        detected = phenomena.detect(word_entries, first_in_segment=True,
                                    narrow=engine.narrow)

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
                "boundary_link_type": w["boundary_link_type"],
                "lexical_form": w.get("lexical_form"),
                "lexical_expansion": w.get("lexical_expansion"),
                "alignment_fallback": w["alignment_fallback"],
            })
        # weak forms the greedy decoding hid (after detect, before the counts)
        variants.apply(words_json, forms, first_in_segment=True)

        prominence = pros.word_prominence(detected)
        analysis_segments.append({
            "start": seg["start"],
            "end": seg["end"],
            "text": seg["text"],
            "f0_stats": f0_stats,
            "f0_track": pros.f0_track(seg["start"], seg["end"]),
            "emphasis_word_idx": peak_index(prominence),
            # parallel to "words": one entry per word
            "prominence": prominence,
            "word_classes": [word_class(w["word"]) for w in detected],
            "intonation_units": pros.intonation_units(detected, seg_end=seg["end"]),
            "rhythm": rhythm(detected),
            "words": words_json,
        })

    analysis = {
        "meta": {
            "source": media_path.name,
            "duration": transcript["duration"],
            "language": transcript["language"],
            "phonotrainer_version": __version__,
            "rules_version": phenomena.RULES_VERSION,
            "phone_engine": engine.name,
            "models": {
                "asr": f"faster-whisper {whisper_model} (int8)",
                "phones": engine.model_id,
                "alignment": engine.alignment,
            },
            "attraction": attraction,
            "dialogue_separation": dialogue,
            "form_scoring": ({"weak_margin": variants.WEAK_MARGIN,
                              "h_drop_margin": variants.H_DROP_MARGIN}
                             if engine.form_scoring else None),
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
    from .metrics import safe_compute
    analysis["summary"]["metrics"] = safe_compute(analysis)

    progress("Saving outputs…")
    _dump(out_dir / "canonical.json", canonical_dump)
    _dump(out_dir / "phones_real.json", real_dump)
    _dump(out_dir / "analysis.json", analysis)

    progress("Generating report.html…")
    from .report import render_html

    (out_dir / "report.html").write_text(render_html(analysis), encoding="utf-8")
    return analysis


def _keep_window(seg: dict, canon_words: list[dict]) -> tuple[float, float]:
    """[lo, hi] for realized phone midpoints: the union of Whisper's segment and
    the extent of the aligned canonical (fallback spans excluded), ±REAL_TRIM."""
    lo, hi = _aligned_extent(seg, canon_words)
    return lo - REAL_TRIM, hi + REAL_TRIM


def _aligned_extent(seg: dict, canon_words: list[dict]) -> tuple[float, float]:
    """Whisper's segment, widened to wherever the forced canonical reaches."""
    lo, hi = seg["start"], seg["end"]
    spans = [p for cw in canon_words if not cw["fallback"] for p in cw["phones"]]
    if spans:
        lo = min(lo, spans[0]["start"])
        hi = max(hi, spans[-1]["end"])
    return lo, hi


def _segment_boundary(seg: dict, canon_words: list[dict], nxt: dict,
                      nxt_canon_words: list[dict]) -> float:
    """Where a segment hands its phones over to the next one: halfway between the
    end of its forced canonical and the start of the next one's.

    Not Whisper's boundary: it is off either way (it closed "scene!" before its /n/
    and opened "blowing" after its /b/). Each forced canonical knows its own
    words, but may stretch into the neighbor's padding — so where the two overlap,
    or leave a gap, the difference is split.
    """
    end = _aligned_extent(seg, canon_words)[1]
    start = _aligned_extent(nxt, nxt_canon_words)[0]
    return (end + start) / 2


def _kept(phones: list[dict], lo: float, hi: float) -> list[dict]:
    """The phones whose midpoint falls in [lo, hi]."""
    return [p for p in phones if lo <= _mid(p) <= hi]


def _mid(phone: dict) -> float:
    return (phone["start"] + phone["end"]) / 2


def _dedupe_overlap(prev: list[dict], nxt: list[dict], cut: float,
                    tol: float = DUPLICATE_TOL) -> tuple[list[dict], list[dict]]:
    """Keep once what both neighbors' decodes heard.

    A phone of `prev` and one of `nxt` less than `tol` apart are the same sound
    decoded twice (their padded windows overlap): the pair is kept on its side of
    `cut`, by one segment. A phone only one decode heard stays with it — the other
    decode, seeing it at the edge of its window, may simply have missed it.
    """
    drop_prev, drop_next = set(), set()
    used = set()
    for i, p in enumerate(prev):
        m = _mid(p)
        best = None
        for j, q in enumerate(nxt):
            if j in used:
                continue
            d = abs(_mid(q) - m)
            if d <= tol and (best is None or d < best[0]):
                best = (d, j)
        if best is None:
            continue
        j = best[1]
        used.add(j)
        if (m + _mid(nxt[j])) / 2 <= cut:
            drop_next.add(j)
        else:
            drop_prev.add(i)
    return ([p for i, p in enumerate(prev) if i not in drop_prev],
            [q for j, q in enumerate(nxt) if j not in drop_next])


def _dump(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
