"""CLI: phonotrainer analyze <media> -o out/ [--phone-engine wav2vec2|allosaurus]"""

from __future__ import annotations

import click
from rich.console import Console


@click.group()
def main() -> None:
    """PhonoTrainer: analizador fonético de habla nativa en inglés."""


@main.command()
@click.argument("media", type=click.Path(exists=True, dir_okay=False))
@click.option("-o", "--out", "out_dir", default="out", show_default=True,
              help="Directorio de salida.")
@click.option("--phone-engine", type=click.Choice(["wav2vec2", "allosaurus"]),
              default="wav2vec2", show_default=True,
              help="Motor de reconocimiento de fonos reales.")
@click.option("--whisper-model", default="small", show_default=True,
              help="Tamaño del modelo faster-whisper (tiny/base/small/medium).")
@click.option("--language", default="en", show_default=True)
@click.option("--no-attraction", is_flag=True, default=False,
              help="Desactiva la atracción fonética hacia el canónico (para comparar).")
def analyze(media: str, out_dir: str, phone_engine: str,
            whisper_model: str, language: str, no_attraction: bool) -> None:
    """Analiza un video o audio en inglés y genera analysis.json + report.html."""
    from .pipeline import analyze as run

    console = Console()
    with console.status("[bold]Iniciando…") as status:
        def progress(msg: str) -> None:
            status.update(f"[bold]{msg}")
            console.log(msg)

        analysis = run(media, out_dir, phone_engine=phone_engine,
                       whisper_model=whisper_model, language=language,
                       attraction=not no_attraction, progress=progress)

    counts = analysis["summary"]["phenomena_counts"]
    console.print(f"\n[bold green]Listo.[/] {len(analysis['segments'])} segmentos, "
                  f"{sum(len(s['words']) for s in analysis['segments'])} palabras.")
    if counts:
        console.print("[bold]Fenómenos:[/] " +
                      ", ".join(f"{k}×{v}" for k, v in
                                sorted(counts.items(), key=lambda kv: -kv[1])))
    cleanup = analysis["meta"]["phone_cleanup"]
    console.print(f"Fonos saneados: {cleanup['normalized_phones']} · "
                  f"atraídos al canónico: {cleanup['attracted_phones']}")
    console.print(f"Reporte: [cyan]{out_dir}/report.html[/]")


@main.command()
@click.argument("analysis_path", type=click.Path(exists=True, dir_okay=False),
                default="out/analysis.json", required=False)
@click.option("-n", "--num", default=20, show_default=True,
              help="Palabras a muestrear.")
@click.option("--seed", default=48, show_default=True,
              help="Semilla del muestreo (reproducible).")
def review(analysis_path: str, num: int, seed: int) -> None:
    """Validación humana muestreada de un analysis.json → review.json."""
    from .review import run_review

    run_review(analysis_path, n=num, seed=seed)


if __name__ == "__main__":
    main()
