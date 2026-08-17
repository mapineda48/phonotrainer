"""CLI: phonotrainer analyze <media> -o out/ [--phone-engine wav2vec2|allosaurus]"""

from __future__ import annotations

import click
from rich.console import Console


@click.group()
def main() -> None:
    """PhonoTrainer: phonetic analyzer of native English speech."""


@main.command()
@click.argument("media")
@click.option("-o", "--out", "out_dir", default="out", show_default=True,
              help="Output directory.")
@click.option("--phone-engine", type=click.Choice(["wav2vec2", "allosaurus"]),
              default="wav2vec2", show_default=True,
              help="Engine that recognizes the phones actually produced.")
@click.option("--whisper-model", default="small", show_default=True,
              help="Size of the faster-whisper model (tiny/base/small/medium).")
@click.option("--language", default="en", show_default=True)
@click.option("--no-attraction", is_flag=True, default=False,
              help="Disable phonetic attraction toward the canonical form "
                   "(for comparison).")
@click.option("--download-dir", default="downloads", show_default=True,
              type=click.Path(file_okay=False),
              help="Where to put the video if MEDIA is a URL.")
@click.option("--audio-only", is_flag=True, default=False,
              help="If MEDIA is a URL, download the audio only.")
@click.option("--no-index", is_flag=True, default=False,
              help="Do not add the result to the corpus (data/phonotrainer.db).")
@click.option("--db", default=None, type=click.Path(dir_okay=False),
              help="Corpus database (data/phonotrainer.db by default).")
def analyze(media: str, out_dir: str, phone_engine: str,
            whisper_model: str, language: str, no_attraction: bool,
            download_dir: str, audio_only: bool, no_index: bool,
            db: str | None) -> None:
    """Analyze a video, an audio file or a YouTube URL.

    MEDIA can be a local path or a URL: in the latter case it is downloaded
    first with yt-dlp into --download-dir and the resulting file is analyzed.
    """
    from pathlib import Path

    from .db import DEFAULT_DB
    from .download import DownloadError, download, is_url
    from .pipeline import analyze as run

    console = Console()
    with console.status("[bold]Starting…") as status:
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
            raise click.ClickException(f"file does not exist: {media}")

        analysis = run(media, out_dir, phone_engine=phone_engine,
                       whisper_model=whisper_model, language=language,
                       attraction=not no_attraction, progress=progress)

    if not no_index:
        from .db import Corpus
        from .jobs import analysis_key

        corpus = None
        try:
            corpus = Corpus(db or DEFAULT_DB)
            # The same identity the interface uses: analyzing here and importing
            # the same out/ afterward must not count twice.
            corpus.index_analysis(analysis_key(out_dir), analysis,
                                  source=Path(media).name,
                                  result_dir=analysis_key(out_dir))
        except Exception as exc:                      # noqa: BLE001
            console.print(f"[yellow]Warning:[/] could not index into the corpus: {exc}")
        finally:
            if corpus is not None:
                corpus.close()

    counts = analysis["summary"]["phenomena_counts"]
    console.print(f"\n[bold green]Done.[/] {len(analysis['segments'])} segments, "
                  f"{sum(len(s['words']) for s in analysis['segments'])} words.")
    if counts:
        console.print("[bold]Phenomena:[/] " +
                      ", ".join(f"{k}×{v}" for k, v in
                                sorted(counts.items(), key=lambda kv: -kv[1])))
    cleanup = analysis["meta"]["phone_cleanup"]
    console.print(f"Phones normalized: {cleanup['normalized_phones']} · "
                  f"attracted to the canonical form: {cleanup['attracted_phones']}")
    console.print(f"Report: [cyan]{out_dir}/report.html[/]")


@main.command()
@click.argument("url")
@click.option("-o", "--out", "dest_dir", default="downloads", show_default=True,
              type=click.Path(file_okay=False), help="Where to put the file.")
@click.option("--audio-only", is_flag=True, default=False,
              help="Download the audio only (faster, no video).")
def download(url: str, dest_dir: str, audio_only: bool) -> None:
    """Download a YouTube video (or one from any site yt-dlp supports)."""
    from .download import DownloadError, download as run

    console = Console()
    with console.status("[bold]Downloading…") as status:
        def progress(msg: str) -> None:
            status.update(f"[bold]{msg}")

        try:
            path = run(url, dest_dir, audio_only=audio_only, progress=progress)
        except DownloadError as exc:
            raise click.ClickException(str(exc)) from exc

    # The name carries the id in brackets: it has to be escaped or rich eats it.
    from rich.markup import escape

    console.print(f"[bold green]Done:[/] [cyan]{escape(str(path))}[/]")
    console.print("Analyze it with: "
                  f"[cyan]phonotrainer analyze \"{escape(str(path))}\" -o out/[/]")


@main.command()
@click.option("--phenomenon", "-p", default=None,
              help="Only the occurrences of this phenomenon (e.g. flapping).")
@click.option("--word", "-w", "word", default=None,
              help="Only the occurrences of this word.")
@click.option("-n", "--num", default=15, show_default=True, help="How many to list.")
@click.option("--db", default=None, type=click.Path(dir_okay=False),
              help="Path to the database (data/phonotrainer.db by default).")
def corpus(phenomenon: str | None, word: str | None, num: int, db: str | None) -> None:
    """Query the corpus: everything analyzed so far, together."""
    from rich.table import Table

    from .db import DEFAULT_DB, Corpus
    from .report import PHENOMENON_LABEL

    console = Console()
    corpus_db = Corpus(db or DEFAULT_DB)
    try:
        stats = corpus_db.stats()
        if not stats["analyses"]:
            console.print("The corpus is empty. Analyze something first "
                          "([cyan]phonotrainer analyze …[/]).")
            return

        console.print(f"[bold]{stats['analyses']}[/] analyses · "
                      f"[bold]{stats['words']}[/] words · "
                      f"{stats['duration'] / 60:.1f} min of speech")

        if word:
            variants = corpus_db.word_variants(word)
            table = Table(title=f"“{word}”: how it has been pronounced", show_header=True,
                          header_style="dim")
            for column in ("realized", "times", "analyses"):
                table.add_column(column)
            for variant in variants:
                table.add_row(f"[{variant['realized_ipa'] or '∅'}]",
                              str(variant["count"]), str(variant["analyses"]))
            console.print(table)
        elif not phenomenon:
            table = Table(show_header=True, header_style="dim")
            for column in ("phenomenon", "times", "in n analyses"):
                table.add_column(column)
            for row in stats["phenomena"]:
                table.add_row(PHENOMENON_LABEL.get(row["phenomenon"], row["phenomenon"]),
                              str(row["count"]), str(row["analyses"]))
            console.print(table)

        rows = corpus_db.occurrences(phenomenon=phenomenon, word=word, limit=num)
        if rows:
            table = Table(title="Occurrences (most divergent first)",
                          show_header=True, header_style="dim")
            for column in ("word", "dict.", "realized", "t (s)", "analysis"):
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
              help="Directory where the interface stores its analyses.")
@click.option("-p", "--port", default=8000, show_default=True)
@click.option("--host", default="127.0.0.1", show_default=True)
@click.option("--no-open", is_flag=True, default=False,
              help="Do not open the browser on startup.")
@click.option("--reload", is_flag=True, default=False,
              help="Hot reload of the backend (development).")
@click.option("--import-dir", "import_dirs", multiple=True,
              type=click.Path(exists=True, file_okay=False),
              help="Register an existing out/ on startup (repeatable).")
@click.option("--allow-dir", "allow_dirs", multiple=True,
              type=click.Path(exists=True, file_okay=False),
              help="Extra directory the interface may open, on top of $HOME "
                   "and the current directory (e.g. a disk under /mnt).")
def ui(workspace: str, port: int, host: str, no_open: bool, reload: bool,
       import_dirs: tuple[str, ...], allow_dirs: tuple[str, ...]) -> None:
    """Web interface: analyze, explore the result with audio, and review."""
    from .jobs import JobStore
    from .server import serve

    console = Console()
    if import_dirs:
        store = JobStore(workspace)
        for path in import_dirs:
            job = store.import_dir(path)
            console.print(f"Imported [cyan]{path}[/] → job {job.id}")
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
              help="Words to sample.")
@click.option("--seed", default=48, show_default=True,
              help="Sampling seed (reproducible).")
def review(analysis_path: str, num: int, seed: int) -> None:
    """Sampled human validation of an analysis.json → review.json."""
    from .review import run_review

    run_review(analysis_path, n=num, seed=seed)


if __name__ == "__main__":
    main()
