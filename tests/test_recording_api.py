"""Record yourself, over HTTP: the native contour of a span and a take compared
against it. Synthetic audio only; nothing the learner sends is kept."""

import json
import subprocess

import numpy as np
import pytest
import soundfile as sf
from conftest import fake_analyze, mk_analysis
from fastapi.testclient import TestClient

from phonotrainer import learner_audio
from phonotrainer.db import Corpus
from phonotrainer.jobs import JobStore
from phonotrainer.server import API_VERSION, create_app

RNG = np.random.default_rng(11)


def tone(f0_start, f0_end, secs, sr):
    t = np.linspace(0, secs, int(sr * secs), endpoint=False)
    freq = np.linspace(f0_start, f0_end, len(t))
    phase = 2 * np.pi * np.cumsum(freq) / sr
    return 0.4 * np.sin(phase) + 0.2 * np.sin(2 * phase) + 0.1 * np.sin(3 * phase)


def hush(secs, sr):
    return 0.001 * RNG.standard_normal(int(sr * secs))


def clip_wav(path, f0_start, f0_end, sr=16000):
    """3 s of analysis audio: the phrase of segment 0 (0.1–1.1 s) is a glide."""
    samples = np.concatenate([hush(0.1, sr), tone(f0_start, f0_end, 1.0, sr), hush(1.9, sr)])
    sf.write(str(path), samples.astype(np.float32), sr)


@pytest.fixture
def client(tmp_path):
    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze, corpus=corpus,
                     download_dir=tmp_path / "downloads")
    app = create_app(store=store, web_dist=tmp_path / "not-built", allowed_roots=[tmp_path])
    with TestClient(app) as c:
        yield c
    store.shutdown()
    corpus.close()


def imported(client, out, analysis=None) -> str:
    out.mkdir(exist_ok=True)
    (out / "analysis.json").write_text(json.dumps(analysis or mk_analysis()), encoding="utf-8")
    resp = client.post("/api/jobs/import", json={"path": str(out)})
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


@pytest.fixture
def job(client, tmp_path):
    """An analysis whose segment 0 (0.0–1.2 s) falls in pitch."""
    out = tmp_path / "out"
    out.mkdir()
    clip_wav(out / "audio.wav", 240, 150)
    return imported(client, out)


@pytest.fixture(scope="module")
def rising_take(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("take")
    sr = 48000
    wav = tmp / "take.wav"
    sf.write(str(wav), np.concatenate([hush(0.3, sr), tone(110, 190, 1.2, sr),
                                       hush(0.4, sr)]).astype(np.float32), sr)
    webm = tmp / "take.webm"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(wav),
                    "-c:a", "libopus", str(webm)], check=True)
    return webm.read_bytes()


def send(client, job_id, data, start=0.0, end=1.2, content_type="audio/webm;codecs=opus",
         **kwargs):
    return client.post(f"/api/jobs/{job_id}/compare",
                       files={"file": ("take.webm", data, content_type)},
                       data={"start": str(start), "end": str(end)}, **kwargs)


def test_the_reference_carries_the_recording_limits(client):
    ref = client.get("/api/reference").json()
    assert ref["api_version"] == API_VERSION >= 5
    rec = ref["recording"]
    assert rec["max_seconds"] == learner_audio.MAX_TAKE_S
    assert rec["max_bytes"] == learner_audio.MAX_UPLOAD_BYTES
    assert "audio/webm" in rec["accepted_types"] and "audio/ogg" in rec["accepted_types"]


def test_the_native_contour_of_a_span(client, job):
    resp = client.get(f"/api/jobs/{job}/contour", params={"start": 0.0, "end": 1.2})
    assert resp.status_code == 200, resp.text
    native = resp.json()
    assert native["audio"] == "mix" and native["segment"] == 0
    assert native["final_contour"] == "falling"
    assert native["peak"]["where"] == "early"
    assert native["speech"]["start"] == pytest.approx(0.1, abs=0.05)
    assert native["speech"]["end"] == pytest.approx(1.1, abs=0.05)
    assert all(len(row) == 4 for row in native["track"])


def test_a_take_compared_with_the_original(client, job, rising_take):
    resp = send(client, job, rising_take)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body) == {"native", "take", "comparison"}
    assert body["native"]["final_contour"] == "falling"
    assert body["take"]["final_contour"] == "rising"
    comparison = body["comparison"]
    assert comparison["time_scale"] == pytest.approx(1.2, abs=0.1)
    assert len(comparison["overlay"]) > 50
    observations = {o["key"]: o for o in comparison["observations"]}
    assert observations["ending"]["text"] == "The original falls at the end; yours rises."
    assert observations["peak"]["kind"] == "different"
    assert observations["length"]["kind"] in ("similar", "longer")


def test_the_dialogue_stem_is_what_gets_measured(client, tmp_path):
    """When separation ran, the analysis read the dialogue stem: so does the comparison."""
    out = tmp_path / "separated"
    out.mkdir()
    clip_wav(out / "audio.wav", 240, 150)                 # the mix falls...
    clip_wav(out / "audio_dialogue.wav", 150, 240)        # ...the speech rises
    analysis = mk_analysis()
    analysis["meta"]["dialogue_separation"] = {"applied": True, "model": "htdemucs",
                                               "audio": "audio_dialogue.wav"}
    job_id = imported(client, out, analysis)
    native = client.get(f"/api/jobs/{job_id}/contour",
                        params={"start": 0.0, "end": 1.2}).json()
    assert native["audio"] == "dialogue" and native["final_contour"] == "rising"


@pytest.mark.parametrize("content_type", ["text/plain", "application/octet-stream", "audio/ogg"])
def test_the_wrong_type_is_refused(client, job, rising_take, content_type):
    resp = send(client, job, rising_take, content_type=content_type)
    assert resp.status_code == 415
    assert resp.json()["code"] == "unsupported_type"
    assert isinstance(resp.json()["detail"], str)


def test_an_oversized_upload_is_refused_before_it_is_read(client, job, monkeypatch):
    calls = []
    monkeypatch.setattr(learner_audio, "measure_take", lambda *a: calls.append(a))
    big = b"\x1a\x45\xdf\xa3" + b"\x00" * (learner_audio.MAX_UPLOAD_BYTES + 70 * 1024)
    resp = send(client, job, big)
    assert resp.status_code == 413 and resp.json()["code"] == "too_large"
    assert calls == []


def test_an_upload_without_a_length_is_refused(client, job, rising_take):
    def chunks():
        yield b"--x\r\n"

    resp = client.post(f"/api/jobs/{job}/compare", content=chunks(),
                       headers={"content-type": "multipart/form-data; boundary=x"})
    assert resp.status_code == 411 and resp.json()["code"] == "length_required"


@pytest.mark.parametrize("start,end", [(1.2, 0.0), (0.0, 40.0), (50.0, 51.0)])
def test_a_bad_span_is_refused(client, job, rising_take, start, end):
    resp = send(client, job, rising_take, start=start, end=end)
    assert resp.status_code == 400 and resp.json()["code"] == "bad_span"
    resp = client.get(f"/api/jobs/{job}/contour", params={"start": start, "end": end})
    assert resp.status_code == 400 and resp.json()["code"] == "bad_span"


def test_missing_fields(client, job, rising_take):
    resp = client.post(f"/api/jobs/{job}/compare", data={"start": "0", "end": "1"})
    assert resp.status_code == 400 and resp.json()["code"] == "missing_file"
    resp = client.post(f"/api/jobs/{job}/compare",
                       files={"file": ("take.webm", rising_take, "audio/webm")},
                       data={"start": "soon", "end": "1"})
    assert resp.status_code == 400 and resp.json()["code"] == "bad_span"


def test_a_job_without_audio_or_without_existence(client, tmp_path, rising_take):
    assert send(client, "nope", rising_take).status_code == 404
    job_id = imported(client, tmp_path / "no-audio")
    assert send(client, job_id, rising_take).status_code == 404
    assert client.get(f"/api/jobs/{job_id}/contour",
                      params={"start": 0, "end": 1}).status_code == 404


def test_another_site_cannot_post_a_take(client, job, rising_take):
    resp = send(client, job, rising_take, headers={"sec-fetch-site": "cross-site"})
    assert resp.status_code == 403


def test_a_voiceless_take_says_so(client, job, tmp_path):
    sr = 48000
    wav = tmp_path / "noise.wav"
    sf.write(str(wav), (0.05 * RNG.standard_normal(sr)).astype(np.float32), sr)
    resp = send(client, job, wav.read_bytes(), content_type="audio/wav")
    assert resp.status_code == 422 and resp.json()["code"] == "no_voice"


def test_takes_are_not_kept(client, job, rising_take, tmp_path, monkeypatch):
    root = tmp_path / "private-tmp"
    root.mkdir()
    monkeypatch.setattr(learner_audio, "TEMP_ROOT", str(root))
    workspace = tmp_path / "workspace"
    before = sorted(p for p in workspace.rglob("*"))
    assert send(client, job, rising_take).status_code == 200
    assert send(client, job, b"\x1a\x45\xdf\xa3junk").status_code == 422
    assert list(root.iterdir()) == []
    assert sorted(p for p in workspace.rglob("*")) == before
