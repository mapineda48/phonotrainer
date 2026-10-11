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


# --- S-23: the link path hardened, and phone-origin downloads confined ----------------------
# (server-contract 9; the FakeYDL seam and a fake `which`: nothing reaches a real site)

from phonotrainer.download import (  # noqa: E402
    AUDIO_FORMAT, REMOTE_COMPONENTS_ENV, VIDEO_FORMAT, DownloadLimits, LiveStreamRefused,
    NoSpaceRefused, TooLongRefused, chosen_js_runtime, js_runtimes)
from phonotrainer.errors import JobCancelled as _JobCancelled  # noqa: E402

GIB = 1024 ** 3


def which_of(*present):
    return lambda name: f"/usr/bin/{name}" if name in present else None


def spy_factory(seen, cls=FakeYDL, **kwargs):
    def make(options):
        seen.update(options)
        return cls(options, **kwargs)
    return make


def test_the_format_prefers_h264_then_vp9_then_anything_at_720p_or_less():
    assert VIDEO_FORMAT == ("bv*[height<=720][vcodec^=avc]+ba/bv*[height<=720][vcodec~='^vp0?9']"
                            "+ba/bv*[height<=720]+ba/b[height<=720]/b")
    alternatives = VIDEO_FORMAT.split("/")
    assert alternatives[0].startswith("bv*[height<=720][vcodec^=avc]")
    assert "vp0?9" in alternatives[1]
    assert alternatives[-1] == "b"
    assert AUDIO_FORMAT == "ba/b"


@pytest.mark.parametrize("present, expected, chosen", [
    (("node",), {"node": {}}, "node"),
    (("deno", "node"), {"deno": {}, "node": {}}, "deno"),
    ((), {}, None),
])
def test_javascript_runtimes_found_on_path(tmp_path, present, expected, chosen):
    assert js_runtimes(which_of(*present)) == expected
    assert chosen_js_runtime(which_of(*present)) == chosen
    seen = {}
    download("https://youtu.be/abc123", tmp_path, ydl_factory=spy_factory(seen),
             which=which_of(*present), environ={})
    if expected:
        assert seen["js_runtimes"] == expected
    else:
        assert "js_runtimes" not in seen                   # yt-dlp's own default


def test_remote_components_only_from_the_environment(tmp_path):
    seen = {}
    download("https://youtu.be/abc123", tmp_path, ydl_factory=spy_factory(seen),
             which=which_of(), environ={})
    assert "remote_components" not in seen
    seen.clear()
    download("https://youtu.be/abc123", tmp_path, ydl_factory=spy_factory(seen),
             which=which_of(), environ={REMOTE_COMPONENTS_ENV: " ejs:github , ejs:npm ,"})
    assert seen["remote_components"] == ["ejs:github", "ejs:npm"]


def test_browser_downloads_keep_todays_options(tmp_path):
    seen = {}
    download("https://youtu.be/abc123", tmp_path, ydl_factory=spy_factory(seen),
             which=which_of(), environ={})
    assert set(seen) == {"format", "outtmpl", "noplaylist", "playlist_items", "quiet",
                         "no_warnings", "noprogress", "progress_hooks", "merge_output_format"}


class TwoPhaseYDL(FakeYDL):
    """Looks first (`download=False`), then downloads what it looked up; any
    media byte fetched is recorded in `fetched`."""

    def __init__(self, options, *, info=None, grows_to=None, **kwargs):
        super().__init__(options, **kwargs)
        self.extra = info or {}
        self.grows_to = grows_to
        self.fetched = False
        self.looked = False

    def extract_info(self, url, download=True):
        assert download is False, "a phone-origin download must look first"
        self.looked = True
        return {"id": "abc123", "title": "A video", "ext": "mp4", "duration": 60,
                **self.extra}

    def process_ie_result(self, info, download=True):
        assert download is True and self.looked
        self.fetched = True
        if self.grows_to is not None:
            for hook in self.options["progress_hooks"]:
                hook({"status": "downloading", "downloaded_bytes": self.grows_to,
                      "total_bytes": None, "filename": "part"})
        return FakeYDL.extract_info(self, "", download=True)


def limits(**overrides):
    values = {"max_duration_s": 3600, "max_bytes": 2 * GIB, "min_free_bytes": 2 * GIB,
              "disk_usage": lambda path: type("U", (), {"free": 100 * GIB})(),
              "check_link": lambda url: url}
    values.update(overrides)
    return DownloadLimits(**values)


def confined(tmp_path, seen=None, lim=None, **ydl):
    made = []

    def make(options):
        if seen is not None:
            seen.update(options)
        made.append(TwoPhaseYDL(options, **ydl))
        return made[-1]

    try:
        return download("https://youtu.be/abc123", tmp_path, ydl_factory=make,
                        phone_origin=True, limits=lim or limits(), which=which_of("node"),
                        environ={})
    finally:
        confined.made = made


def test_phone_origin_downloads_use_site_extractors_and_a_size_bound(tmp_path):
    seen = {}
    path = confined(tmp_path, seen)
    assert path.is_file()
    # never the generic extractor, nor the Mediasite ones (their links may name any host)
    assert seen["allowed_extractors"] == ["default", "-generic", "-mediasite.*"]
    assert seen["max_filesize"] == 2 * GIB
    assert seen["format"] == VIDEO_FORMAT and seen["js_runtimes"] == {"node": {}}
    # yt-dlp's own downloaders (the connect guard sees their sockets), one
    # fragment at a time, and no proxy (it would hide the address reached)
    assert seen["external_downloader"] == {"default": "native"}
    assert seen["concurrent_fragment_downloads"] == 1 and seen["proxy"] == ""
    assert confined.made[0].fetched


def test_a_confined_download_runs_inside_the_connect_guard(tmp_path):
    from phonotrainer.phone.ssrf import current_confinement

    seen = []

    class Watching(TwoPhaseYDL):
        def extract_info(self, url, download=True):
            seen.append(current_confinement())
            return super().extract_info(url, download)

        def process_ie_result(self, info, download=True):
            seen.append(current_confinement())
            return super().process_ie_result(info, download)

    download("https://youtu.be/abc123", tmp_path, ydl_factory=lambda o: Watching(o),
             phone_origin=True, limits=limits(), which=which_of(), environ={})
    assert len(seen) == 2 and all(c is not None for c in seen) and seen[0] is seen[1]
    assert current_confinement() is None                    # and only while it runs
    seen.clear()
    download("https://youtu.be/abc123", tmp_path / "b", ydl_factory=lambda o: Recording(o, seen),
             which=which_of(), environ={})
    assert seen == [None]                                   # browser downloads are not confined


class Recording(FakeYDL):
    def __init__(self, options, seen):
        super().__init__(options)
        self.seen = seen

    def extract_info(self, url, download=True):
        from phonotrainer.phone.ssrf import current_confinement

        self.seen.append(current_confinement())
        return super().extract_info(url, download)


def test_a_refused_connect_fails_the_job_as_a_refused_address(tmp_path):
    """Whatever yt-dlp makes of the refusal (here a generic error after it
    caught the transport error), the job fails with `LinkRefused`."""
    import socket as socket_module

    from phonotrainer.phone.ssrf import LinkRefused
    from phonotrainer.phone.state import classify_error

    class Reaching(TwoPhaseYDL):
        def process_ie_result(self, info, download=True):
            try:
                with socket_module.create_connection(("127.0.0.1", 9), timeout=1):
                    pass
            except OSError as exc:
                raise RuntimeError(f"Unable to download video data: {exc}") from None
            raise AssertionError("the connect must be refused")

    with pytest.raises(LinkRefused) as refused:
        download("https://youtu.be/abc123", tmp_path, ydl_factory=lambda o: Reaching(o),
                 phone_origin=True, limits=limits(), which=which_of(), environ={})
    assert type(refused.value) is LinkRefused
    assert classify_error("error", f"LinkRefused: {refused.value}") == "refused_address"


def test_an_unknown_size_is_checked_as_the_largest_it_may_reach(tmp_path):
    """A site that gives no size (common for HLS and DASH) may still deliver
    up to `max_bytes`, twice that while two formats wait to be merged: the
    free-space check counts that, not zero."""
    from phonotrainer.download import needed_bytes

    lim = limits(max_bytes=GIB, disk_usage=lambda path: type("U", (), {"free": 2 * GIB + GIB})())
    assert needed_bytes({}, lim) == GIB
    assert needed_bytes({"requested_formats": [{}, {}]}, lim) == 2 * GIB
    assert needed_bytes({"filesize": 100}, lim) == 100
    assert confined(tmp_path / "one", lim=lim, info={}).is_file()            # 3 GiB - 1 GiB ≥ 2 GiB
    with pytest.raises(NoSpaceRefused):
        confined(tmp_path / "two", lim=lim, info={"requested_formats": [{}, {}]})
    assert not confined.made[0].fetched


@pytest.mark.parametrize("probed, refused", [
    ({"format": {"duration": "3601.5"}}, True),
    ({"format": {}, "streams": [{"duration": "12"}, {"duration": "3700"}]}, True),
    ({"format": {"duration": "59.9"}}, False),
    (None, False),                                  # ffprobe could not tell: kept
])
def test_a_video_whose_site_gave_no_duration_is_measured(tmp_path, probed, refused):
    probes = []

    def probe(path):
        probes.append(path)
        return probed

    lim = limits(media_probe=probe)
    if refused:
        with pytest.raises(TooLongRefused, match="up to 60 minutes"):
            confined(tmp_path, lim=lim, info={"duration": None})
        assert not [p for p in tmp_path.iterdir() if p.is_file()]
    else:
        assert confined(tmp_path, lim=lim, info={"duration": None}).is_file()
    assert len(probes) == 1
    # a site that gave the duration is not probed again
    confined(tmp_path / "known", lim=lim)
    assert len(probes) == 1


def test_a_probe_that_cannot_run_keeps_the_file(tmp_path):
    def broken(path):
        raise OSError("ffprobe is missing")

    assert confined(tmp_path, lim=limits(media_probe=broken), info={"duration": None}).is_file()


@pytest.mark.parametrize("info, refusal", [
    ({"is_live": True}, LiveStreamRefused),
    ({"live_status": "is_live"}, LiveStreamRefused),
    ({"live_status": "is_upcoming"}, LiveStreamRefused),
    ({"live_status": "post_live"}, LiveStreamRefused),
    ({"duration": 3601}, TooLongRefused),
    ({"filesize": 2 * GIB + 1}, TooLongRefused),
    ({"filesize_approx": 3 * GIB}, TooLongRefused),
    ({"requested_formats": [{"filesize": GIB}, {"filesize_approx": GIB + 1}]}, TooLongRefused),
])
def test_refusals_before_any_media_byte(tmp_path, info, refusal):
    with pytest.raises(refusal):
        confined(tmp_path, info=info)
    assert not confined.made[0].fetched
    assert not list(tmp_path.iterdir())


def test_a_lack_of_free_space_is_refused_before_the_download(tmp_path):
    tight = limits(disk_usage=lambda path: type("U", (), {"free": 2 * GIB + 10})())
    with pytest.raises(NoSpaceRefused):
        confined(tmp_path, lim=tight, info={"filesize": 100})
    assert not confined.made[0].fetched
    roomy = limits(disk_usage=lambda path: type("U", (), {"free": 2 * GIB + 1000})())
    assert confined(tmp_path, lim=roomy, info={"filesize": 100}).is_file()


def test_a_shorter_limit_from_server_json_is_honoured(tmp_path):
    with pytest.raises(TooLongRefused, match="up to 10 minutes"):
        confined(tmp_path, lim=limits(max_duration_s=600), info={"duration": 601})


def test_an_unknown_size_that_grows_past_the_limit_fails_as_too_long(tmp_path):
    with pytest.raises(TooLongRefused):
        confined(tmp_path, lim=limits(max_bytes=1000), grows_to=1001)
    # and a finished file that is still larger is deleted
    with pytest.raises(TooLongRefused):
        confined(tmp_path, lim=limits(max_bytes=5))               # "fake media" is 10 bytes
    assert not [p for p in tmp_path.iterdir() if p.is_file()]


def test_the_link_is_checked_again_right_before_the_download(tmp_path):
    from phonotrainer.phone.ssrf import CannotCheckLink, LinkRefused

    def refuse(url):
        raise LinkRefused()

    with pytest.raises(LinkRefused):
        confined(tmp_path, lim=limits(check_link=refuse))
    assert confined.made == []                                  # yt-dlp never even started

    def unreachable(url):
        raise CannotCheckLink("no answer")

    with pytest.raises(DownloadError, match="Failed to resolve"):
        confined(tmp_path, lim=limits(check_link=unreachable))


def test_refusals_keep_their_class_for_the_error_codes(tmp_path):
    from phonotrainer.phone.state import classify_error

    for info, code in (({"is_live": True}, "live_stream"), ({"duration": 9999}, "too_long")):
        try:
            confined(tmp_path, info=info)
        except DownloadError as exc:
            text = f"{type(exc).__name__}: {exc}"
        assert classify_error("error", text) == code


def test_a_phone_origin_playlist_is_still_explained(tmp_path):
    with pytest.raises(DownloadError, match="playlist"):
        confined(tmp_path, info={"_type": "playlist"})


def test_cancelling_a_confined_download_is_not_a_failure(tmp_path):
    def cancel(message):
        if message.startswith("Downloading"):
            raise _JobCancelled(message)

    with pytest.raises(_JobCancelled):
        download("https://youtu.be/abc123", tmp_path, progress=cancel,
                 ydl_factory=lambda o: TwoPhaseYDL(o, grows_to=10), phone_origin=True,
                 limits=limits(), which=which_of(), environ={})


class SkippingYDL(TwoPhaseYDL):
    """yt-dlp skipping a file whose announced size is above `max_filesize`:
    one screen line through the logger, no file, no exception."""

    def process_ie_result(self, info, download=True):
        self.fetched = True
        self.options["logger"].debug(
            "\r[download] File is larger than max-filesize (3000 bytes > 1000 bytes). Aborting.")
        return {**info, "requested_downloads": [{"filepath": None}]}


def test_a_file_yt_dlp_skips_for_its_size_fails_as_too_long(tmp_path):
    with pytest.raises(TooLongRefused):
        download("https://youtu.be/abc123", tmp_path, ydl_factory=lambda o: SkippingYDL(o),
                 phone_origin=True, limits=limits(max_bytes=1000), which=which_of(), environ={})
    # a browser download has no such logger and keeps today's message
    assert "logger" not in spy_options(tmp_path)


def spy_options(tmp_path):
    seen = {}
    download("https://youtu.be/abc123", tmp_path / "browser", ydl_factory=spy_factory(seen),
             which=which_of(), environ={})
    return seen


def test_a_refusal_mid_download_deletes_its_partial_files(tmp_path):
    dest = tmp_path / "downloads"
    dest.mkdir()
    partial = dest / "A video [abc123].f137.mp4.part"
    outside = tmp_path / "elsewhere.part"
    for path in (partial, outside):
        path.write_bytes(b"x" * 10)

    class Growing(TwoPhaseYDL):
        def process_ie_result(self, info, download=True):
            self.fetched = True
            for hook in self.options["progress_hooks"]:
                hook({"status": "downloading", "downloaded_bytes": 2000, "total_bytes": None,
                      "filename": str(outside), "tmpfilename": str(partial)})
            raise AssertionError("the hook must stop the download")

    with pytest.raises(TooLongRefused):
        download("https://youtu.be/abc123", dest, ydl_factory=lambda o: Growing(o),
                 phone_origin=True, limits=limits(max_bytes=1000), which=which_of(), environ={})
    assert not partial.exists()
    assert outside.exists()                       # only files inside the download directory


def test_yt_dlp_errors_still_reach_the_terminal_for_phone_links(tmp_path, capsys):
    from phonotrainer.download import _OversizeLogger

    logger = _OversizeLogger()
    logger.warning("not shown: warnings are off")
    logger.error("ERROR: [youtube] abc123: Video unavailable")
    assert capsys.readouterr().err == "ERROR: [youtube] abc123: Video unavailable\n"
    assert logger.seen is False
