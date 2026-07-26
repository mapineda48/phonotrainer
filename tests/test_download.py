"""Descarga con yt-dlp. El doble de `ydl_factory` evita tocar la red."""

from pathlib import Path

import pytest

from phonotrainer.download import DownloadError, download, is_url


class FakeYDL:
    """Imita lo justo de yt_dlp.YoutubeDL: hooks de progreso y archivo final."""

    def __init__(self, options, *, escribe=True, remuxa_a: str | None = None,
                 revienta: Exception | None = None):
        self.options = options
        self.escribe = escribe
        self.remuxa_a = remuxa_a
        self.revienta = revienta

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def extract_info(self, url, download=True):
        if self.revienta:
            raise self.revienta
        for hook in self.options.get("progress_hooks", []):
            hook({"status": "downloading", "downloaded_bytes": 0, "total_bytes": 100})
            hook({"status": "downloading", "downloaded_bytes": 50, "total_bytes": 100})
            hook({"status": "downloading", "downloaded_bytes": 100, "total_bytes": 100})
            hook({"status": "finished"})
        plantilla = self.options["outtmpl"]
        destino = Path(plantilla.replace("%(title).80s", "Un vídeo")
                                .replace("%(id)s", "abc123")
                                .replace("%(ext)s", self.remuxa_a or "webm"))
        if self.escribe:
            destino.write_bytes(b"fake media")
        self.info = {"id": "abc123", "title": "Un vídeo", "ext": "webm",
                     "requested_downloads": [{"filepath": str(destino)}]}
        return self.info

    def prepare_filename(self, info):
        return self.options["outtmpl"].replace("%(title).80s", info["title"]) \
            .replace("%(id)s", info["id"]).replace("%(ext)s", info["ext"])


def factory(**kwargs):
    return lambda options: FakeYDL(options, **kwargs)


def test_distingue_url_de_ruta():
    assert is_url("https://www.youtube.com/watch?v=abc")
    assert is_url("http://youtu.be/abc")
    assert not is_url("/home/yo/video.webm")
    assert not is_url("video.mp4")


def test_descarga_y_devuelve_la_ruta(tmp_path):
    mensajes = []
    path = download("https://youtu.be/abc123", tmp_path,
                    progress=mensajes.append, ydl_factory=factory())

    assert path.is_file()
    assert path.parent == tmp_path
    assert "abc123" in path.name
    assert any("50 %" in m for m in mensajes)          # progreso para la barra
    assert mensajes[-1].startswith("Descargado:")


def test_el_progreso_no_repite_el_mismo_porcentaje(tmp_path):
    class Repetitivo(FakeYDL):
        def extract_info(self, url, download=True):
            for hook in self.options["progress_hooks"]:
                for _ in range(5):
                    hook({"status": "downloading", "downloaded_bytes": 10, "total_bytes": 100})
            return super().extract_info(url, download)

    mensajes = []
    download("https://youtu.be/abc123", tmp_path, progress=mensajes.append,
             ydl_factory=lambda options: Repetitivo(options))
    assert sum(1 for m in mensajes if "10 %" in m) == 1


def test_audio_only_cambia_el_formato(tmp_path):
    visto = {}

    def espia(options):
        visto.update(options)
        return FakeYDL(options)

    download("https://youtu.be/abc123", tmp_path, audio_only=True, ydl_factory=espia)
    assert visto["format"] == "ba/b"
    assert "merge_output_format" not in visto

    download("https://youtu.be/abc123", tmp_path, ydl_factory=espia)
    assert "height<=720" in visto["format"]
    assert visto["merge_output_format"] == "mp4"
    assert visto["noplaylist"] is True                 # una URL, un vídeo


def test_una_ruta_local_no_es_una_descarga(tmp_path):
    with pytest.raises(DownloadError, match="no parece una URL"):
        download("/home/yo/video.webm", tmp_path)


def test_el_fallo_de_yt_dlp_se_traduce(tmp_path):
    with pytest.raises(DownloadError, match="vídeo privado"):
        download("https://youtu.be/x", tmp_path,
                 ydl_factory=factory(revienta=RuntimeError("vídeo privado")))


def test_si_no_aparece_el_archivo_falla_claro(tmp_path):
    with pytest.raises(DownloadError, match="no encuentro el archivo"):
        download("https://youtu.be/x", tmp_path, ydl_factory=factory(escribe=False))


def test_encuentra_el_archivo_aunque_se_remuxe(tmp_path):
    """Tras juntar vídeo y audio la extensión cambia: hay que localizarlo igual."""

    class SinFilepath(FakeYDL):
        def extract_info(self, url, download=True):
            info = super().extract_info(url, download)
            info.pop("requested_downloads")            # yt-dlp antiguo
            return info

    path = download("https://youtu.be/abc123", tmp_path,
                    ydl_factory=lambda options: SinFilepath(options))
    assert path.is_file() and "abc123" in path.name


def test_crea_el_directorio_de_destino(tmp_path):
    destino = tmp_path / "downloads" / "nuevo"
    path = download("https://youtu.be/abc123", destino, ydl_factory=factory())
    assert path.parent == destino


def test_la_cli_no_se_come_el_id_del_video(tmp_path):
    """El nombre lleva el id entre corchetes y rich los trata como marcado:
    sin escapar, la ruta que se imprime no existe."""
    from unittest.mock import patch

    from click.testing import CliRunner

    from phonotrainer.cli import main

    ruta = tmp_path / "Un vídeo [abc123].m4a"
    with patch("phonotrainer.download.download", return_value=ruta):
        result = CliRunner().invoke(main, ["download", "https://youtu.be/abc123"])

    assert result.exit_code == 0
    assert "[abc123]" in result.output
