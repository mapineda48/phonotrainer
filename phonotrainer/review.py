"""MEJORA 5: validación humana muestreada de un analysis.json.

`phonotrainer review out/analysis.json` muestrea N palabras (con seed fija),
priorizando las sospechosas (attracted, low_confidence, diff alto), pide veredicto
ok/mal/dudosa por stdin y guarda review.json con el % de acierto. Sin playback:
se incluyen timestamps para buscar el momento en cualquier reproductor.
"""

from __future__ import annotations

import json
import random
from pathlib import Path

DEFAULT_N = 20
DEFAULT_SEED = 48
VERDICTS = ("ok", "mal", "dudosa")


def word_weight(w: dict) -> float:
    """Peso de muestreo: las palabras que pasaron por manos del pipeline
    (atracción, baja confianza) o divergen mucho pesan más."""
    weight = 1.0
    if w.get("attracted_count", 0) > 0:
        weight += 3.0
    if w.get("low_confidence"):
        weight += 3.0
    weight += 2.0 * min(float(w.get("diff_cost", 0.0)), 1.5)
    return weight


def select_sample(analysis: dict, n: int = DEFAULT_N,
                  seed: int = DEFAULT_SEED) -> list[tuple[int, int, dict]]:
    """Muestreo ponderado sin reemplazo (Efraimidis-Spirakis), determinista con
    seed. Devuelve [(seg_idx, word_idx, word)] en orden temporal."""
    rnd = random.Random(seed)
    keyed = []
    for si, seg in enumerate(analysis["segments"]):
        for wi, w in enumerate(seg["words"]):
            key = rnd.random() ** (1.0 / word_weight(w))
            keyed.append((key, si, wi, w))
    keyed.sort(key=lambda t: t[0], reverse=True)
    chosen = keyed[:n]
    return sorted([(si, wi, w) for _, si, wi, w in chosen],
                  key=lambda t: (t[0], t[1]))


def sample_for_ui(analysis: dict, n: int = DEFAULT_N,
                  seed: int = DEFAULT_SEED) -> list[dict]:
    """Muestra para la UI: posición + la palabra completa + su segmento.

    La CLI usa `select_sample` directamente; aquí añadimos el contexto que la
    interfaz necesita para mostrar y reproducir cada caso.
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
    """Entrada de review.json (misma forma que la escribe la CLI)."""
    return {
        "segment": segment, "word_idx": word_idx, "word": w["word"],
        "t_start": w["start"], "t_end": w["end"],
        "phenomena": w.get("phenomena", []),
        "attracted_count": w.get("attracted_count", 0),
        "low_confidence": bool(w.get("low_confidence")),
        "verdict": verdict, "note": note,
    }


def build_items(analysis: dict, verdicts: list[dict]) -> list[dict]:
    """Convierte [{segment, word_idx, verdict, note}] en entradas de review.json,
    leyendo la palabra del propio análisis (la UI no fabrica datos fonéticos)."""
    items = []
    for v in verdicts:
        si, wi = int(v["segment"]), int(v["word_idx"])
        try:
            w = analysis["segments"][si]["words"][wi]
        except (IndexError, KeyError) as exc:
            raise ValueError(f"palabra fuera de rango: segmento {si}, idx {wi}") from exc
        verdict = v.get("verdict")
        if verdict not in VERDICTS:
            raise ValueError(f"veredicto inválido: {verdict!r} (usa {'/'.join(VERDICTS)})")
        items.append(item_from_word(si, wi, w, verdict, v.get("note", "") or ""))
    return items


def summarize(items: list[dict]) -> dict:
    """Conteos y % de acierto de una lista de veredictos."""
    ok = sum(1 for i in items if i.get("verdict") == "ok")
    mal = sum(1 for i in items if i.get("verdict") == "mal")
    dudosa = sum(1 for i in items if i.get("verdict") == "dudosa")
    return {
        "ok": ok, "mal": mal, "dudosa": dudosa,
        "sampled": len(items),
        "accuracy": round(ok / (ok + mal), 3) if (ok + mal) else None,
    }


def save_review(analysis_path: str | Path, items: list[dict],
                seed: int = DEFAULT_SEED, out_path: str | Path | None = None) -> Path:
    """Escribe review.json junto al analysis.json (o en `out_path`)."""
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

    console.print(f"[bold]Revisión de {len(sample)} palabras[/] "
                  f"(seed={seed}; prioridad: atraídas/baja confianza/diff alto)\n")

    items = []
    for k, (si, wi, w) in enumerate(sample, 1):
        raw = w.get("realized_raw_ipa", "")
        final = w.get("realized_ipa", "")
        real = f"{raw} → {final}" if raw and raw != final else (final or "∅")
        flags = []
        if w.get("attracted_count"):
            flags.append(f"atraídos×{w['attracted_count']}")
        if w.get("low_confidence"):
            flags.append("baja conf.")

        table = Table(show_header=True, header_style="dim")
        for col in ("t (s)", "palabra", "dicc.", "canónico", "real (crudo→final)", "fenómenos"):
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

        verdict = click.prompt("  veredicto", default="ok",
                               type=click.Choice(list(VERDICTS)))
        note = click.prompt("  nota", default="", show_default=False)
        items.append(item_from_word(si, wi, w, verdict, note))

    counts = summarize(items)
    out_path = save_review(analysis_path, items, seed=seed)

    accuracy = counts["accuracy"]
    console.print(f"\n[bold green]Guardado {out_path}[/] — "
                  f"ok={counts['ok']} mal={counts['mal']} dudosa={counts['dudosa']}"
                  + (f" · acierto={accuracy:.0%}" if accuracy is not None else ""))
    return out_path
