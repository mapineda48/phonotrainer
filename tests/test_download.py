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


def test_cancelar_no_es_un_fallo_de_descarga(tmp_path):
    """El callback de progreso lanza JobCancelled para abortar: si `download`
    lo convierte en DownloadError, el job aparece como error en vez de
    cancelado."""
    from phonotrainer.errors import JobCancelled

    def cancela(mensaje):
        if "%" in mensaje:
            raise JobCancelled(mensaje)

    with pytest.raises(JobCancelled):
        download("https://youtu.be/abc123", tmp_path, progress=cancela,
                 ydl_factory=factory())


def test_una_lista_de_reproduccion_se_explica(tmp_path):
    class Playlist(FakeYDL):
        def extract_info(self, url, download=True):
            return {"_type": "playlist", "entries": [], "id": "PL123"}

    with pytest.raises(DownloadError, match="lista de reproducción"):
        download("https://youtube.com/playlist?list=PL123", tmp_path,
                 ydl_factory=lambda options: Playlist(options))


def test_sin_tamano_conocido_informa_en_megas(tmp_path):
    """Directos y descargas fragmentadas no saben cuánto ocupan: la barra no
    puede quedarse muda."""

    class SinTotal(FakeYDL):
        def extract_info(self, url, download=True):
            for hook in self.options["progress_hooks"]:
                for mb in (1, 2, 5):
                    hook({"status": "downloading", "downloaded_bytes": mb << 20,
                          "total_bytes": None})
            return super().extract_info(url, download)

    mensajes = []
    download("https://youtu.be/abc123", tmp_path, progress=mensajes.append,
             ydl_factory=lambda options: SinTotal(options))

    assert [m for m in mensajes if "MB" in m] == [
        "Descargando de YouTube… 1 MB",
        "Descargando de YouTube… 2 MB",
        "Descargando de YouTube… 5 MB",
    ]


def test_recorta_los_nombres_larguisimos(tmp_path):
    """80 caracteres pueden ser 258 bytes: ext4 no los admite."""
    visto = {}

    def espia(options):
        visto.update(options)
        return FakeYDL(options)

    download("https://youtu.be/abc123", tmp_path, ydl_factory=espia)
    assert visto["trim_file_name"] == 120
    assert visto["playlist_items"] == "1"


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
