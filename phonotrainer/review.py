"""IMPROVEMENT 5: sampled human validation of an analysis.json.

`phonotrainer review out/analysis.json` samples N words (with a fixed seed),
giving priority to the suspicious ones (attracted, low_confidence, high diff),
asks for an ok/wrong/unsure verdict on stdin and writes review.json with the
accuracy rate. No playback: timestamps are included so the moment can be found
in any player.

The verdict values ("ok", "wrong", "unsure") are the wire format shared with
the web interface. review.json files written before the rename carry the old
values; nothing here reads them back, and the one place that does read a saved
review — `server.get_review`, which serves it to the interface — drops the
entries whose verdict is no longer part of this vocabulary.
"""

from __future__ import annotations

import json
import random
from pathlib import Path

DEFAULT_N = 20
DEFAULT_SEED = 48
VERDICTS = ("ok", "wrong", "unsure")


def word_weight(w: dict) -> float:
    """Sampling weight: words the pipeline had a hand in (attraction, low
    confidence) or that diverge a lot weigh more."""
    weight = 1.0
    if w.get("attracted_count", 0) > 0:
        weight += 3.0
    if w.get("low_confidence"):
        weight += 3.0
    weight += 2.0 * min(float(w.get("diff_cost", 0.0)), 1.5)
    return weight


def select_sample(analysis: dict, n: int = DEFAULT_N,
                  seed: int = DEFAULT_SEED) -> list[tuple[int, int, dict]]:
    """Weighted sampling without replacement (Efraimidis-Spirakis), made
    deterministic by the seed. Returns [(seg_idx, word_idx, word)] in temporal
    order."""
    rnd = random.Random(seed)
    keyed = []
    for si, seg in enumerate(analysis.get("segments") or []):
        for wi, w in enumerate(seg.get("words") or []):
            key = rnd.random() ** (1.0 / word_weight(w))
            keyed.append((key, si, wi, w))
    keyed.sort(key=lambda t: t[0], reverse=True)
    chosen = keyed[:n]
    return sorted([(si, wi, w) for _, si, wi, w in chosen],
                  key=lambda t: (t[0], t[1]))


def sample_for_ui(analysis: dict, n: int = DEFAULT_N,
                  seed: int = DEFAULT_SEED) -> list[dict]:
    """Sample for the UI: position + the complete word + its segment.

    The CLI uses `select_sample` directly; here we add the context the
    interface needs in order to display and play back each case.
    """
    out = []
    for si, wi, w in select_sample(analysis, n=n, seed=seed):
        seg = analysis["segments"][si]
        out.append({
            "segment": si, "word_idx": wi, "word": w,
            "segment_text": seg.get("text", ""),
            "segment_start": seg.get("start"), "segment_end": seg.get("end"),
        })
    return out


def item_from_word(segment: int, word_idx: int, w: dict,
                   verdict: str, note: str = "") -> dict:
    """A review.json entry (the same shape the CLI writes)."""
    return {
        "segment": segment, "word_idx": word_idx, "word": w["word"],
        "t_start": w["start"], "t_end": w["end"],
        "phenomena": w.get("phenomena", []),
        "attracted_count": w.get("attracted_count", 0),
        "low_confidence": bool(w.get("low_confidence")),
        "verdict": verdict, "note": note,
    }


def build_items(analysis: dict, verdicts: list[dict]) -> list[dict]:
    """Turn [{segment, word_idx, verdict, note}] into review.json entries,
    reading the word from the analysis itself (the UI does not make up phonetic
    data)."""
    items = []
    for v in verdicts:
        si, wi = int(v["segment"]), int(v["word_idx"])
        try:
            w = analysis["segments"][si]["words"][wi]
        except (IndexError, KeyError) as exc:
            raise ValueError(f"word out of range: segment {si}, idx {wi}") from exc
        verdict = v.get("verdict")
        if verdict not in VERDICTS:
            raise ValueError(f"invalid verdict: {verdict!r} (use {'/'.join(VERDICTS)})")
        items.append(item_from_word(si, wi, w, verdict, v.get("note", "") or ""))
    return items


def summarize(items: list[dict]) -> dict:
    """Counts and accuracy rate for a list of verdicts."""
    ok = sum(1 for i in items if i.get("verdict") == "ok")
    wrong = sum(1 for i in items if i.get("verdict") == "wrong")
    unsure = sum(1 for i in items if i.get("verdict") == "unsure")
    return {
        "ok": ok, "wrong": wrong, "unsure": unsure,
        "sampled": len(items),
        "accuracy": round(ok / (ok + wrong), 3) if (ok + wrong) else None,
    }


def save_review(analysis_path: str | Path, items: list[dict],
                seed: int = DEFAULT_SEED, out_path: str | Path | None = None) -> Path:
    """Write review.json next to the analysis.json (or at `out_path`)."""
    analysis_path = Path(analysis_path)
    out_path = Path(out_path) if out_path else analysis_path.parent / "review.json"
    payload = {"analysis": str(analysis_path), "seed": seed}
    payload.update(summarize(items))
    payload["items"] = items
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    return out_path


def run_review(analysis_path: str | Path, n: int = DEFAULT_N,
               seed: int = DEFAULT_SEED) -> Path:
    import click
    from rich.console import Console
    from rich.table import Table

    console = Console()
    analysis_path = Path(analysis_path)
    analysis = json.loads(analysis_path.read_text(encoding="utf-8"))
    sample = select_sample(analysis, n=n, seed=seed)

    console.print(f"[bold]Review of {len(sample)} words[/] "
                  f"(seed={seed}; priority: attracted / low confidence / high diff)\n")

    items = []
    for k, (si, wi, w) in enumerate(sample, 1):
        raw = w.get("realized_raw_ipa", "")
        final = w.get("realized_ipa", "")
        real = f"{raw} → {final}" if raw and raw != final else (final or "∅")
        flags = []
        if w.get("attracted_count"):
            flags.append(f"attracted×{w['attracted_count']}")
        if w.get("low_confidence"):
            flags.append("low conf.")

        table = Table(show_header=True, header_style="dim")
        for col in ("t (s)", "word", "dict.", "canonical", "realized (raw→final)",
                    "phenomena"):
            table.add_column(col)
        table.add_row(
            f"{w['start']:.2f}–{w['end']:.2f}",
            w["word"],
            f"/{w.get('dict_ipa', '')}/",
            w.get("canonical_ipa", ""),
            real,
            ", ".join(w.get("phenomena", [])) or "—",
        )
        console.print(f"[bold cyan]{k}/{len(sample)}[/]"
                      + (f"  [yellow]{' · '.join(flags)}[/]" if flags else ""))
        console.print(table)

        verdict = click.prompt("  verdict", default="ok",
                               type=click.Choice(list(VERDICTS)))
        note = click.prompt("  note", default="", show_default=False)
        items.append(item_from_word(si, wi, w, verdict, note))

    counts = summarize(items)
    out_path = save_review(analysis_path, items, seed=seed)

    accuracy = counts["accuracy"]
    console.print(f"\n[bold green]Saved {out_path}[/] — "
                  f"ok={counts['ok']} wrong={counts['wrong']} unsure={counts['unsure']}"
                  + (f" · accuracy={accuracy:.0%}" if accuracy is not None else ""))
    return out_path
