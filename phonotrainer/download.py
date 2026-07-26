"""Descarga de material desde YouTube (y de todo lo que soporte yt-dlp).

El habla nativa que interesa a este proyecto vive en vídeos: bajarlos a mano y
arrastrarlos sobraba un paso. Los archivos caen en `downloads/` (ignorada por
git: cada clon empieza vacío) y se reutilizan si ya están, así que volver a
analizar la misma URL no vuelve a descargar.

Solo se descarga lo que el usuario pide explícitamente y se procesa en local;
el reparto de los archivos no es cosa de esta herramienta.
"""

from __future__ import annotations

import re
from pathlib import Path

DEFAULT_DIR = Path("downloads")
# Vídeo hasta 720p: más que suficiente para ver la cara y mucho más rápido.
VIDEO_FORMAT = "bv*[height<=720]+ba/b[height<=720]/b"
AUDIO_FORMAT = "ba/b"

_URL_RE = re.compile(r"^https?://", re.IGNORECASE)


class DownloadError(RuntimeError):
    """La descarga falló (URL inválida, sin red, vídeo privado…)."""


def is_url(text: str) -> bool:
    """¿Esto es una URL o una ruta local?"""
    return bool(_URL_RE.match(str(text).strip()))


def _noop(message: str) -> None:
    pass


def _make_ydl(options: dict):
    try:
        from yt_dlp import YoutubeDL
    except ImportError as exc:   # pragma: no cover - depende del entorno
        raise DownloadError(
            "falta yt-dlp: instálalo con «uv pip install yt-dlp»"
        ) from exc
    return YoutubeDL(options)


def download(url: str, dest_dir: str | Path = DEFAULT_DIR, audio_only: bool = False,
             progress=_noop, ydl_factory=_make_ydl) -> Path:
    """Descarga `url` en `dest_dir` y devuelve la ruta del archivo.

    `progress` recibe mensajes con el porcentaje, para que la interfaz los
    muestre igual que los del pipeline. `ydl_factory` es la costura de los tests.
    """
    if not is_url(url):
        raise DownloadError(f"no parece una URL: {url}")
    dest_dir = Path(dest_dir).expanduser()
    dest_dir.mkdir(parents=True, exist_ok=True)

    last_percent = -1

    def hook(status: dict) -> None:
        nonlocal last_percent
        if status.get("status") == "downloading":
            total = status.get("total_bytes") or status.get("total_bytes_estimate") or 0
            done = status.get("downloaded_bytes") or 0
            percent = int(done * 100 / total) if total else 0
            if percent != last_percent:      # un mensaje por punto porcentual
                last_percent = percent
                progress(f"Descargando de YouTube… {percent} %")
        elif status.get("status") == "finished":
            progress("Descarga terminada, preparando el archivo…")

    options = {
        "format": AUDIO_FORMAT if audio_only else VIDEO_FORMAT,
        "outtmpl": str(dest_dir / "%(title).80s [%(id)s].%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "progress_hooks": [hook],
    }
    if not audio_only:
        options["merge_output_format"] = "mp4"

    progress("Consultando la URL…")
    try:
        with ydl_factory(options) as ydl:
            info = ydl.extract_info(url, download=True)
            path = _resolve_path(ydl, info)
    except DownloadError:
        raise
    except Exception as exc:            # noqa: BLE001 — yt-dlp lanza de todo
        raise DownloadError(f"{type(exc).__name__}: {exc}") from exc

    if path is None or not path.is_file():
        raise DownloadError(f"yt-dlp terminó pero no encuentro el archivo en {dest_dir}")
    progress(f"Descargado: {path.name}")
    return path


def _resolve_path(ydl, info) -> Path | None:
    """La ruta final: tras remuxar, la extensión no es la del template."""
    if not isinstance(info, dict):
        return None
    requested = info.get("requested_downloads") or []
    if requested and requested[0].get("filepath"):
        return Path(requested[0]["filepath"])
    if info.get("filepath"):
        return Path(info["filepath"])
    try:
        candidate = Path(ydl.prepare_filename(info))
    except Exception:                   # noqa: BLE001
        return None
    if candidate.is_file():
        return candidate
    # Se remuxó a otra extensión: buscamos por el id del vídeo, que va en el nombre.
    matches = sorted(candidate.parent.glob(f"*[[]{info.get('id', '')}[]]*"))
    return matches[0] if matches else None
