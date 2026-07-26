"""CLI: phonotrainer analyze <media> -o out/ [--phone-engine wav2vec2|allosaurus]"""

from __future__ import annotations

import click
from rich.console import Console


@click.group()
def main() -> None:
    """PhonoTrainer: analizador fonético de habla nativa en inglés."""


@main.command()
@click.argument("media")
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
@click.option("--download-dir", default="downloads", show_default=True,
              type=click.Path(file_okay=False),
              help="Dónde dejar el vídeo si MEDIA es una URL.")
@click.option("--audio-only", is_flag=True, default=False,
              help="Si MEDIA es una URL, bajar solo el audio.")
@click.option("--no-index", is_flag=True, default=False,
              help="No añadir el resultado al corpus (data/phonotrainer.db).")
@click.option("--db", default=None, type=click.Path(dir_okay=False),
              help="Base del corpus (por defecto data/phonotrainer.db).")
def analyze(media: str, out_dir: str, phone_engine: str,
            whisper_model: str, language: str, no_attraction: bool,
            download_dir: str, audio_only: bool, no_index: bool,
            db: str | None) -> None:
    """Analiza un video, un audio o una URL de YouTube.

    MEDIA puede ser una ruta local o una URL: en ese caso se descarga primero
    con yt-dlp a --download-dir y se analiza el archivo resultante.
    """
    from pathlib import Path

    from .db import DEFAULT_DB
    from .download import DownloadError, download, is_url
    from .pipeline import analyze as run

    console = Console()
    with console.status("[bold]Iniciando…") as status:
        def progress(msg: str) -> None:
            status.update(f"[bold]{msg}")
            console.log(msg)

        if is_url(media):
            try:
                media = str(download(media, download_dir, audio_only=audio_only,
                                     progress=progress))
            except DownloadError as exc:
                raise click.ClickException(str(exc)) from exc
        elif not Path(media).is_file():
            raise click.ClickException(f"no existe el archivo: {media}")

        analysis = run(media, out_dir, phone_engine=phone_engine,
                       whisper_model=whisper_model, language=language,
                       attraction=not no_attraction, progress=progress)

    if not no_index:
        from .db import Corpus
        from .jobs import analysis_key

        corpus = None
        try:
            corpus = Corpus(db or DEFAULT_DB)
            # Misma identidad que usa la interfaz: analizar aquí e importar
            # después el mismo out/ no debe contar dos veces.
            corpus.index_analysis(analysis_key(out_dir), analysis,
                                  source=Path(media).name,
                                  result_dir=analysis_key(out_dir))
        except Exception as exc:                      # noqa: BLE001
            console.print(f"[yellow]Aviso:[/] no se pudo indexar en el corpus: {exc}")
        finally:
            if corpus is not None:
                corpus.close()

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
@click.argument("url")
@click.option("-o", "--out", "dest_dir", default="downloads", show_default=True,
              type=click.Path(file_okay=False), help="Dónde dejar el archivo.")
@click.option("--audio-only", is_flag=True, default=False,
              help="Bajar solo el audio (más rápido, sin vídeo).")
def download(url: str, dest_dir: str, audio_only: bool) -> None:
    """Descarga un vídeo de YouTube (o de cualquier sitio que soporte yt-dlp)."""
    from .download import DownloadError, download as run

    console = Console()
    with console.status("[bold]Descargando…") as status:
        def progress(msg: str) -> None:
            status.update(f"[bold]{msg}")

        try:
            path = run(url, dest_dir, audio_only=audio_only, progress=progress)
        except DownloadError as exc:
            raise click.ClickException(str(exc)) from exc

    # El nombre trae el id entre corchetes: hay que escaparlo o rich se lo come.
    from rich.markup import escape

    console.print(f"[bold green]Listo:[/] [cyan]{escape(str(path))}[/]")
    console.print("Analízalo con: "
                  f"[cyan]phonotrainer analyze \"{escape(str(path))}\" -o out/[/]")


@main.command()
@click.option("--phenomenon", "-p", default=None,
              help="Solo las apariciones de este fenómeno (p. ej. flapping).")
@click.option("--word", "-w", "word", default=None,
              help="Solo las apariciones de esta palabra.")
@click.option("-n", "--num", default=15, show_default=True, help="Cuántas listar.")
@click.option("--db", default=None, type=click.Path(dir_okay=False),
              help="Ruta de la base de datos (por defecto data/phonotrainer.db).")
def corpus(phenomenon: str | None, word: str | None, num: int, db: str | None) -> None:
    """Consulta el corpus: todo lo analizado hasta ahora, junto."""
    from rich.table import Table

    from .db import DEFAULT_DB, Corpus
    from .report import PHENOMENON_LABEL

    console = Console()
    corpus_db = Corpus(db or DEFAULT_DB)
    try:
        stats = corpus_db.stats()
        if not stats["analyses"]:
            console.print("El corpus está vacío. Analiza algo primero "
                          "([cyan]phonotrainer analyze …[/]).")
            return

        console.print(f"[bold]{stats['analyses']}[/] análisis · "
                      f"[bold]{stats['words']}[/] palabras · "
                      f"{stats['duration'] / 60:.1f} min de habla")

        if word:
            variants = corpus_db.word_variants(word)
            table = Table(title=f"«{word}»: cómo se ha pronunciado", show_header=True,
                          header_style="dim")
            for column in ("real", "veces", "análisis"):
                table.add_column(column)
            for variant in variants:
                table.add_row(f"[{variant['realized_ipa'] or '∅'}]",
                              str(variant["count"]), str(variant["analyses"]))
            console.print(table)
        elif not phenomenon:
            table = Table(show_header=True, header_style="dim")
            for column in ("fenómeno", "veces", "en n análisis"):
                table.add_column(column)
            for row in stats["phenomena"]:
                table.add_row(PHENOMENON_LABEL.get(row["phenomenon"], row["phenomenon"]),
                              str(row["count"]), str(row["analyses"]))
            console.print(table)

        rows = corpus_db.occurrences(phenomenon=phenomenon, word=word, limit=num)
        if rows:
            table = Table(title="Apariciones (las más divergentes primero)",
                          show_header=True, header_style="dim")
            for column in ("palabra", "dicc.", "real", "t (s)", "análisis"):
                table.add_column(column)
            for row in rows:
                table.add_row(row["word"], f"/{row['dict_ipa'] or ''}/",
                              f"[{row['realized_ipa'] or '∅'}]",
                              f"{row['start']:.2f}", row["analysis_source"][:40])
            console.print(table)
    finally:
        corpus_db.close()


@main.command()
@click.option("-w", "--workspace", default="workspace", show_default=True,
              type=click.Path(file_okay=False),
              help="Directorio donde se guardan los análisis de la interfaz.")
@click.option("-p", "--port", default=8000, show_default=True)
@click.option("--host", default="127.0.0.1", show_default=True)
@click.option("--no-open", is_flag=True, default=False,
              help="No abrir el navegador al arrancar.")
@click.option("--reload", is_flag=True, default=False,
              help="Recarga en caliente del backend (desarrollo).")
@click.option("--import-dir", "import_dirs", multiple=True,
              type=click.Path(exists=True, file_okay=False),
              help="Registra un out/ ya existente al arrancar (repetible).")
@click.option("--allow-dir", "allow_dirs", multiple=True,
              type=click.Path(exists=True, file_okay=False),
              help="Directorio extra que la interfaz podrá abrir, además de "
                   "$HOME y el directorio actual (p. ej. un disco en /mnt).")
def ui(workspace: str, port: int, host: str, no_open: bool, reload: bool,
       import_dirs: tuple[str, ...], allow_dirs: tuple[str, ...]) -> None:
    """Interfaz web: analizar, explorar el resultado con audio y revisar."""
    from .jobs import JobStore
    from .server import serve

    console = Console()
    if import_dirs:
        store = JobStore(workspace)
        for path in import_dirs:
            job = store.import_dir(path)
            console.print(f"Importado [cyan]{path}[/] → job {job.id}")
        store.shutdown()

    from pathlib import Path

    roots = [Path.home(), Path.cwd(), *(Path(d) for d in allow_dirs)]
    console.print(f"[bold]PhonoTrainer UI[/] → [cyan]http://{host}:{port}[/]  "
                  f"(workspace: {workspace})")
    serve(workspace=workspace, host=host, port=port, reload=reload,
          open_browser=not no_open, allowed_roots=roots)


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
