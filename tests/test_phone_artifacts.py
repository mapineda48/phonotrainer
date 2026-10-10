"""Artifacts, checksums and media facts (server-contract 6.4; S-11, QT-9 server side)."""

import hashlib
import json
from pathlib import Path

import pytest
from conftest import wait_until, write_silent_wav
from phone_fixtures import ffprobe_json

from phonotrainer.phone import artifacts as artifacts_mod
from phonotrainer.phone.artifacts import ArtifactIndex, facts_from_probe


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "talk.mp4"
    path.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"x" * 5000)
    return path


def make_done(store, media, **options):
    job = store.create(media, options={"separate_dialogue": True, **options})
    wait_until(lambda: store.get(job.id).status == "done")
    return store.get(job.id)


def ready(signing, job_id):
    def check():
        body = signing.get(f"/api/phone/jobs/{job_id}").json()
        return body if body["artifacts"] and all(
            a["status"] in ("ready", "unavailable") for a in body["artifacts"]) else None
    return wait_until(check)


# --- the table --------------------------------------------------------------------------------

def test_every_artifact_name_and_role(paired, phone_store, media):
    signing, _, _ = paired
    job = make_done(phone_store, media)
    body = ready(signing, job.id)
    entries = {a["name"]: a for a in body["artifacts"]}
    assert list(entries) == ["analysis", "audio_mix", "audio_dialogue", "original"]
    assert {n: (a["role"], a["content_type"]) for n, a in entries.items()} == {
        "analysis": ("clip_data", "application/json"),
        "audio_mix": ("audio_mix", "audio/wav"),
        "audio_dialogue": ("audio_dialogue", "audio/wav"),
        "original": ("video", "video/mp4"),
    }
    files = {"analysis": job.dir / "analysis.json", "audio_mix": job.dir / "audio.wav",
             "audio_dialogue": job.dir / "audio_dialogue.wav", "original": media}
    for name, entry in entries.items():
        assert entry["status"] == "ready" and entry["error"] is None
        assert entry["sha256"] == sha(files[name])
        assert entry["bytes"] == files[name].stat().st_size
        assert entry["revision"] == entry["sha256"][:16]
        assert entry["url"] == f"/api/phone/jobs/{job.id}/artifacts/{name}"


def test_no_dialogue_without_separation_and_no_original_for_audio(paired, phone_store,
                                                                  tmp_path):
    signing, _, _ = paired
    audio = write_silent_wav(tmp_path / "speech.wav")
    job = make_done(phone_store, audio, separate_dialogue=False)
    names = [a["name"] for a in ready(signing, job.id)["artifacts"]]
    assert names == ["analysis", "audio_mix"]
    for name in ("audio_dialogue", "original"):
        resp = signing.get(f"/api/phone/jobs/{job.id}/artifacts/{name}")
        assert resp.status_code == 404 and resp.json()["code"] == "unknown_artifact"


def test_unknown_names_and_jobs(paired, phone_store, media):
    signing, _, _ = paired
    job = make_done(phone_store, media)
    for name in ("report", "review", "analysis_compact", "poster", "video_phone", "x",
                 "job.json"):
        resp = signing.get(f"/api/phone/jobs/{job.id}/artifacts/{name}")
        assert resp.status_code == 404 and resp.json()["code"] == "unknown_artifact", name
    resp = signing.get("/api/phone/jobs/20261010-091503-4be1/artifacts/analysis")
    assert resp.status_code == 404 and resp.json()["code"] == "unknown_job"


def test_preparing_until_checksummed_then_ready(phone_store, media, fake_probe):
    """Without the warmer, nothing is ever hashed inline."""
    index = ArtifactIndex(phone_store, fake_probe)
    job = make_done(phone_store, media)
    entries = index.entries(job)
    assert {e["status"] for e in entries} == {"preparing"}
    assert all(e["sha256"] is None and e["url"] is None and e["bytes"] is None
               for e in entries)
    assert index.warm(job.id) is True
    entries = index.entries(job)
    assert {e["status"] for e in entries} == {"ready"}
    assert index.warm(job.id) is False               # nothing left to do
    cache = json.loads((job.dir / "phone" / "checksums.json").read_text())
    assert set(cache["files"]) == {"analysis", "audio_mix", "audio_dialogue", "original"}
    assert set(cache["files"]["original"]) == {"bytes", "mtime_ns", "sha256"}


def test_the_cache_survives_a_restart_and_follows_changes(phone_store, media, fake_probe,
                                                          monkeypatch):
    job = make_done(phone_store, media)
    ArtifactIndex(phone_store, fake_probe).warm(job.id)

    def no_hashing(path):
        raise AssertionError("hashed again")

    monkeypatch.setattr(artifacts_mod, "sha256_file", no_hashing)
    restarted = ArtifactIndex(phone_store, fake_probe)
    assert {e["status"] for e in restarted.entries(job)} == {"ready"}
    monkeypatch.undo()
    write_silent_wav(job.dir / "audio.wav", seconds=2.0)          # the file changed
    statuses = {e["name"]: e["status"] for e in restarted.entries(job)}
    assert statuses["audio_mix"] == "preparing" and statuses["analysis"] == "ready"
    restarted.warm(job.id)
    entry = next(e for e in restarted.entries(job) if e["name"] == "audio_mix")
    assert entry["sha256"] == sha(job.dir / "audio.wav")


def test_artifact_not_ready_while_preparing_or_running(paired, phone_app, phone_store, media):
    signing, _, _ = paired
    phone_app.state.phone.artifacts.stop()                        # no warmer
    job = make_done(phone_store, media)
    resp = signing.get(f"/api/phone/jobs/{job.id}/artifacts/analysis")
    assert resp.status_code == 409 and resp.json()["code"] == "artifact_not_ready"
    phone_store.get(job.id).status = "running"
    resp = signing.get(f"/api/phone/jobs/{job.id}/artifacts/analysis")
    assert resp.status_code == 409 and resp.json()["code"] == "artifact_not_ready"


def test_the_original_is_unavailable_when_gone(paired, phone_store, media):
    signing, _, _ = paired
    job = make_done(phone_store, media)
    ready(signing, job.id)
    media.unlink()
    entry = next(a for a in signing.get(f"/api/phone/jobs/{job.id}").json()["artifacts"]
                 if a["name"] == "original")
    assert entry == {"name": "original", "role": "video", "status": "unavailable",
                     "bytes": None, "sha256": None, "revision": None,
                     "content_type": "video/mp4", "url": None, "error": None}
    resp = signing.get(f"/api/phone/jobs/{job.id}/artifacts/original")
    assert resp.status_code == 409 and resp.json()["code"] == "artifact_not_ready"


# --- serving ------------------------------------------------------------------------------------

def test_whole_file_with_etag_and_no_content_encoding(paired, phone_store, media):
    signing, _, _ = paired
    job = make_done(phone_store, media)
    ready(signing, job.id)
    resp = signing.get(f"/api/phone/jobs/{job.id}/artifacts/original",
                       headers={"Accept-Encoding": "gzip"})
    assert resp.status_code == 200
    assert resp.content == media.read_bytes()
    assert resp.headers["etag"] == f'"{sha(media)}"'
    assert resp.headers["accept-ranges"] == "bytes"
    assert resp.headers["content-length"] == str(media.stat().st_size)
    assert resp.headers["content-type"] == "video/mp4"
    assert "content-encoding" not in resp.headers
    analysis = signing.get(f"/api/phone/jobs/{job.id}/artifacts/analysis")
    assert analysis.content == (job.dir / "analysis.json").read_bytes()
    assert analysis.headers["content-type"].startswith("application/json")


def test_range_if_range_and_416(paired, phone_store, media):
    signing, _, _ = paired
    job = make_done(phone_store, media)
    ready(signing, job.id)
    url = f"/api/phone/jobs/{job.id}/artifacts/original"
    data, size, etag = media.read_bytes(), media.stat().st_size, f'"{sha(media)}"'
    resp = signing.get(url, headers={"Range": "bytes=100-"})
    assert resp.status_code == 206
    assert resp.content == data[100:]
    assert resp.headers["content-range"] == f"bytes 100-{size - 1}/{size}"
    resp = signing.get(url, headers={"Range": "bytes=100-", "If-Range": etag})
    assert resp.status_code == 206 and resp.content == data[100:]
    resp = signing.get(url, headers={"Range": "bytes=100-", "If-Range": '"' + "0" * 64 + '"'})
    assert resp.status_code == 200 and resp.content == data       # restart from zero
    resp = signing.get(url, headers={"Range": f"bytes={size}-"})
    assert resp.status_code == 416


# --- media facts -------------------------------------------------------------------------------

@pytest.mark.parametrize("probe, facts", [
    (ffprobe_json(video={"codec": "h264", "width": 1280, "height": 720},
                  audio={}, duration=260.6),
     {"kind": "video", "codec": "h264", "width": 1280, "height": 720, "rotation": 0,
      "start_offset_ms": 0, "duration_s": 260.6}),
    # the video has content before the analysed zero: positive offset
    (ffprobe_json(video={"start_time": "0.000000"}, audio={"start_time": "0.480000"}),
     {"start_offset_ms": 480}),
    # the video starts later than the audio: negative offset
    (ffprobe_json(video={"start_time": "0.300000"}, audio={"start_time": "0.000000"}),
     {"start_offset_ms": -300}),
    # a start time missing on either side: 0
    (ffprobe_json(video={"start_time": None}, audio={"start_time": "1.0"}),
     {"start_offset_ms": 0}),
    (ffprobe_json(video={"side_data_list": [{"side_data_type": "Display Matrix",
                                             "rotation": -90}]}, audio={}),
     {"rotation": 90}),
    (ffprobe_json(video={"side_data_list": [{"rotation": 90}]}, audio={}), {"rotation": 270}),
    (ffprobe_json(video={"side_data_list": [{"rotation": 180}]}, audio={}), {"rotation": 180}),
    (ffprobe_json(video={"tags": {"rotate": "90"}}, audio={}), {"rotation": 90}),
    (ffprobe_json(video={"tags": {"rotate": "270"}}, audio={}), {"rotation": 270}),
    # cover art is not a video
    (ffprobe_json(video={"codec": "mjpeg"}, audio={}, attached_pic=True),
     {"kind": "audio", "codec": None, "width": None, "height": None}),
    (ffprobe_json(audio={}, duration=None), {"kind": "audio", "duration_s": None}),
    (ffprobe_json(), {"kind": "none"}),
    (None, {"kind": "none", "codec": None, "rotation": 0, "start_offset_ms": 0}),
])
def test_media_facts(probe, facts):
    result = facts_from_probe(probe, "clip.mp4")
    assert set(result) == {"kind", "codec", "width", "height", "rotation", "start_offset_ms",
                           "duration_s"}
    for key, value in facts.items():
        assert result[key] == value, key


def test_ffprobe_missing_keeps_the_kind_from_the_suffix(phone_store, media, fake_probe):
    fake_probe.by_name[media.name] = FileNotFoundError("ffprobe")
    index = ArtifactIndex(phone_store, fake_probe)
    job = make_done(phone_store, media)
    assert index.media_facts(job) is None                         # null until probed
    index.warm(job.id)
    assert index.media_facts(job) == {"kind": "video", "codec": None, "width": None,
                                      "height": None, "rotation": 0, "start_offset_ms": 0,
                                      "duration_s": None}


def test_media_facts_are_cached(phone_store, media, fake_probe):
    job = make_done(phone_store, media)
    ArtifactIndex(phone_store, fake_probe).warm(job.id)
    assert fake_probe.calls == 1
    stored = json.loads((job.dir / "phone" / "media.json").read_text())
    assert stored["facts"]["kind"] == "video"
    restarted = ArtifactIndex(phone_store, fake_probe)
    restarted.warm(job.id)
    assert restarted.media_facts(job)["codec"] == "h264"
    assert fake_probe.calls == 1


def test_the_default_probe_runs_ffprobe_through_the_runner(tmp_path):
    from phonotrainer.phone.settings import PhoneSettings

    calls = []

    class Done:
        returncode = 0
        stdout = json.dumps(ffprobe_json(video={}, audio={})).encode()

    def runner(args, timeout=None):
        calls.append((args, timeout))
        return Done()

    settings = PhoneSettings(runner=runner)
    assert settings.media_probe(tmp_path / "x.mp4")["streams"][0]["codec_type"] == "video"
    assert calls == [(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams",
                       "-show_format", str(tmp_path / "x.mp4")], 30)]


def test_warming_a_deleted_job_never_recreates_its_directory(phone_store, media, fake_probe):
    index = ArtifactIndex(phone_store, fake_probe)
    job = make_done(phone_store, media)
    phone_store.delete(job.id)
    assert index.warm(job.id) is False
    assert not job.dir.exists()
