"""Downloading with yt-dlp. The `ydl_factory` double keeps the network out of it."""

from pathlib import Path

import pytest  # noqa: F401  (used by the error tests)

from phonotrainer.download import DownloadError, download, is_url


class FakeYDL:
    """Imitates just enough of yt_dlp.YoutubeDL: progress hooks and final file."""

    def __init__(self, options, *, writes=True, remuxes_to: str | None = None,
                 blows_up: Exception | None = None):
        self.options = options
        self.writes = writes
        self.remuxes_to = remuxes_to
        self.blows_up = blows_up

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def extract_info(self, url, download=True):
        if self.blows_up:
            raise self.blows_up
        for hook in self.options.get("progress_hooks", []):
            hook({"status": "downloading", "downloaded_bytes": 0, "total_bytes": 100})
            hook({"status": "downloading", "downloaded_bytes": 50, "total_bytes": 100})
            hook({"status": "downloading", "downloaded_bytes": 100, "total_bytes": 100})
            hook({"status": "finished"})
        template = self.options["outtmpl"]
        dest = Path(template.replace("%(title).80s", "A video")
                            .replace("%(id)s", "abc123")
                            .replace("%(ext)s", self.remuxes_to or "webm"))
        if self.writes:
            dest.write_bytes(b"fake media")
        self.info = {"id": "abc123", "title": "A video", "ext": "webm",
                     "requested_downloads": [{"filepath": str(dest)}]}
        return self.info

    def prepare_filename(self, info):
        return self.options["outtmpl"].replace("%(title).80s", info["title"]) \
            .replace("%(id)s", info["id"]).replace("%(ext)s", info["ext"])


def factory(**kwargs):
    return lambda options: FakeYDL(options, **kwargs)


def test_tells_a_url_from_a_path():
    assert is_url("https://www.youtube.com/watch?v=abc")
    assert is_url("http://youtu.be/abc")
    assert not is_url("/home/me/video.webm")
    assert not is_url("video.mp4")


def test_downloads_and_returns_the_path(tmp_path):
    messages = []
    path = download("https://youtu.be/abc123", tmp_path,
                    progress=messages.append, ydl_factory=factory())

    assert path.is_file()
    assert path.parent == tmp_path
    assert "abc123" in path.name
    assert any("50%" in m for m in messages)           # progress for the bar
    assert messages[-1].startswith("Downloaded:")


def test_progress_does_not_repeat_the_same_percentage(tmp_path):
    class Repetitive(FakeYDL):
        def extract_info(self, url, download=True):
            for hook in self.options["progress_hooks"]:
                for _ in range(5):
                    hook({"status": "downloading", "downloaded_bytes": 10, "total_bytes": 100})
            return super().extract_info(url, download)

    messages = []
    download("https://youtu.be/abc123", tmp_path, progress=messages.append,
             ydl_factory=lambda options: Repetitive(options))
    assert sum(1 for m in messages if "10%" in m) == 1


def test_audio_only_changes_the_format(tmp_path):
    seen = {}

    def spy(options):
        seen.update(options)
        return FakeYDL(options)

    download("https://youtu.be/abc123", tmp_path, audio_only=True, ydl_factory=spy)
    assert seen["format"] == "ba/b"
    assert "merge_output_format" not in seen

    download("https://youtu.be/abc123", tmp_path, ydl_factory=spy)
    assert "height<=720" in seen["format"]
    assert seen["merge_output_format"] == "mp4"
    assert seen["noplaylist"] is True                  # one URL, one video


def test_a_local_path_is_not_a_download(tmp_path):
    with pytest.raises(DownloadError, match="does not look like a URL"):
        download("/home/me/video.webm", tmp_path)


def test_a_yt_dlp_failure_is_translated(tmp_path):
    with pytest.raises(DownloadError, match="private video"):
        download("https://youtu.be/x", tmp_path,
                 ydl_factory=factory(blows_up=RuntimeError("private video")))


def test_a_missing_file_fails_clearly(tmp_path):
    with pytest.raises(DownloadError, match="file could not be found"):
        download("https://youtu.be/x", tmp_path, ydl_factory=factory(writes=False))


def test_finds_the_file_even_after_a_remux(tmp_path):
    """Once video and audio are merged the extension changes: it still has to be
    located."""

    class NoFilepath(FakeYDL):
        def extract_info(self, url, download=True):
            info = super().extract_info(url, download)
            info.pop("requested_downloads")            # older yt-dlp
            return info

    path = download("https://youtu.be/abc123", tmp_path,
                    ydl_factory=lambda options: NoFilepath(options))
    assert path.is_file() and "abc123" in path.name


def test_creates_the_destination_directory(tmp_path):
    dest = tmp_path / "downloads" / "new"
    path = download("https://youtu.be/abc123", dest, ydl_factory=factory())
    assert path.parent == dest


def test_cancelling_is_not_a_download_failure(tmp_path):
    """The progress callback raises JobCancelled to abort: if `download` turns
    it into a DownloadError, the job shows up as failed instead of cancelled."""
    from phonotrainer.errors import JobCancelled

    def cancel(message):
        if "%" in message:
            raise JobCancelled(message)

    with pytest.raises(JobCancelled):
        download("https://youtu.be/abc123", tmp_path, progress=cancel,
                 ydl_factory=factory())


def test_a_playlist_is_explained(tmp_path):
    class Playlist(FakeYDL):
        def extract_info(self, url, download=True):
            return {"_type": "playlist", "entries": [], "id": "PL123"}

    with pytest.raises(DownloadError, match="playlist"):
        download("https://youtube.com/playlist?list=PL123", tmp_path,
                 ydl_factory=lambda options: Playlist(options))


def test_with_no_known_size_it_reports_in_megabytes(tmp_path):
    """Live streams and fragmented downloads do not know their size: the bar
    cannot go silent."""

    class NoTotal(FakeYDL):
        def extract_info(self, url, download=True):
            for hook in self.options["progress_hooks"]:
                for mb in (1, 2, 5):
                    hook({"status": "downloading", "downloaded_bytes": mb << 20,
                          "total_bytes": None})
            return super().extract_info(url, download)

    messages = []
    download("https://youtu.be/abc123", tmp_path, progress=messages.append,
             ydl_factory=lambda options: NoTotal(options))

    assert [m for m in messages if "MB" in m] == [
        "Downloading from YouTube… 1 MB",
        "Downloading from YouTube… 2 MB",
        "Downloading from YouTube… 5 MB",
    ]


def test_very_long_names_are_truncated_by_bytes(tmp_path):
    """80 CJK characters are 240 bytes: ext4 does not accept names that long,
    and truncating by characters does not avoid the problem."""
    from yt_dlp import YoutubeDL

    seen = {}

    def spy(options):
        seen.update(options)
        return FakeYDL(options)

    download("https://youtu.be/abc123", tmp_path, ydl_factory=spy)
    assert seen["playlist_items"] == "1"

    # the truncation is done by the real yt-dlp, not by our assumptions
    name = Path(YoutubeDL({"outtmpl": seen["outtmpl"], "quiet": True}).prepare_filename(
        {"title": "あ" * 80, "id": "dQw4w9WgXcQ", "ext": "mp4"})).name
    assert len(name.encode("utf-8")) <= 255


def test_the_cli_does_not_eat_the_video_id(tmp_path):
    """The name carries the id in brackets and rich treats those as markup:
    unescaped, the path it prints does not exist."""
    from unittest.mock import patch

    from click.testing import CliRunner

    from phonotrainer.cli import main

    path = tmp_path / "A video [abc123].m4a"
    with patch("phonotrainer.download.download", return_value=path):
        result = CliRunner().invoke(main, ["download", "https://youtu.be/abc123"])

    assert result.exit_code == 0
    assert "[abc123]" in result.output
