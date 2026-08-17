"""HTTP API: the full cycle (analyze → query → review) without loading models."""

import json

import pytest
from conftest import fake_analyze, mk_analysis, wait_until
from fastapi.testclient import TestClient

from phonotrainer.db import Corpus
from phonotrainer.jobs import JobStore
from phonotrainer.server import create_app


@pytest.fixture
def corpus():
    db = Corpus(":memory:")
    yield db
    db.close()


@pytest.fixture
def store(tmp_path, corpus):
    st = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze, corpus=corpus,
                  download_dir=tmp_path / "downloads",
                  download_fn=_fake_download)
    yield st
    st.shutdown()


def _fake_download(url, dest_dir, audio_only=False, progress=lambda m: None):
    """Download double: the tests never go out to the network."""
    from pathlib import Path

    progress("Downloading from YouTube… 100%")
    dest = Path(dest_dir)
    dest.mkdir(parents=True, exist_ok=True)
    file = dest / "A video [abc123].mp4"
    file.write_bytes(b"media")
    progress(f"Downloaded: {file.name}")
    return file


@pytest.fixture
def client(store, tmp_path):
    # tmp_path as the allowed root: the tests do not depend on the real $HOME.
    app = create_app(store=store, web_dist=tmp_path / "not-built",
                     allowed_roots=[tmp_path])
    with TestClient(app) as c:
        yield c


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.wav"
    path.write_bytes(b"RIFF fake media")
    return path


def analyzed(client, media, **options) -> str:
    """Create a job and wait for it to finish; returns its id."""
    resp = client.post("/api/jobs", json={"path": str(media), "options": options})
    assert resp.status_code == 201, resp.text
    job_id = resp.json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")
    return job_id


def test_health_and_reference(client):
    from phonotrainer.server import API_VERSION

    health = client.get("/api/health").json()
    assert health["ok"] is True and health["web_built"] is False
    # The interface compares this to spot an old server left open.
    assert health["api_version"] == API_VERSION
    assert client.get("/api/reference").json()["api_version"] == API_VERSION

    ref = client.get("/api/reference").json()
    assert ref["family_of"]["flapping"] == "td"
    assert ref["labels"]["vowel_reduction"] == "vowel reduction"
    assert [f["key"] for f in ref["families"]] == [
        "reduction", "td", "assimilation", "boundary"]
    assert ref["verdicts"] == ["ok", "wrong", "unsure"]
    # the UI fills its selectors from this, not from hand-copied lists
    assert ref["options"]["whisper_models"] == ["tiny", "base", "small", "medium"]
    assert ref["options"]["phone_engines"] == ["wav2vec2", "allosaurus"]
    assert ref["options"]["defaults"]["whisper_model"] == "small"
    assert ref["review"] == {"default_n": 20, "default_seed": 48, "max_n": 500}
    # without this the UI would not know what "flapping" is nor how to tokenize /aɪ/
    assert "soft r" in ref["descriptions"]["flapping"]
    assert set(ref["descriptions"]) == set(ref["labels"])
    assert "aɪ" in ref["ipa_tokens"] and "tʃ" in ref["ipa_tokens"]
    assert ref["ipa_tokens"] == sorted(ref["ipa_tokens"], key=len, reverse=True)


def test_an_imported_report_is_sandboxed(client, tmp_path):
    """An out/ may come from elsewhere: its report.html must not be able to talk
    to this API from the interface's origin."""
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    (out / "report.html").write_text("<script>fetch('/api/browse')</script>", encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(out)}).json()["id"]

    resp = client.get(f"/api/jobs/{job_id}/report")
    assert resp.status_code == 200
    assert "sandbox" in resp.headers["content-security-policy"]
    assert resp.headers["x-content-type-options"] == "nosniff"


def test_cross_origin_requests_are_rejected(client, tmp_path):
    """multipart carries no preflight: without this, any open web page could
    queue analyses on the user's machine."""
    resp = client.post(
        "/api/jobs/upload",
        files={"file": ("x.wav", b"RIFF", "audio/wav")},
        headers={"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"},
    )
    assert resp.status_code == 403

    # the same origin (and console tools, which do not send the header) get through
    assert client.get("/api/jobs", headers={"Sec-Fetch-Site": "cross-site"}).status_code == 200
    ok = client.post("/api/jobs/upload", files={"file": ("x.wav", b"RIFF", "audio/wav")},
                     headers={"Sec-Fetch-Site": "same-origin"})
    assert ok.status_code == 201


def test_only_media_files_can_be_uploaded(client):
    resp = client.post("/api/jobs/upload", files={"file": ("payload.html", b"<script>", "text/html")})
    assert resp.status_code == 400
    assert "video or an audio" in resp.json()["detail"]


def test_an_analysis_with_another_shape_does_not_give_a_500(client, tmp_path):
    for content in ("[1,2,3]", "null", "{}", '{"segments": {"a": 1}}'):
        odd = tmp_path / f"odd{abs(hash(content))}"
        odd.mkdir()
        (odd / "analysis.json").write_text(content, encoding="utf-8")
        resp = client.post("/api/jobs/import", json={"path": str(odd)})
        assert resp.status_code == 400, content


def test_impossible_paths_give_400(client):
    for path in ("/tmp/with\x00null.wav", "~nosuchuser999/x.wav"):
        assert client.post("/api/jobs", json={"path": path}).status_code == 400
    assert client.get("/api/browse", params={"path": "~nosuchuser999"}).status_code == 400


def test_the_system_root_is_not_allowed_as_a_root(store, tmp_path):
    import pytest as _pytest

    with _pytest.raises(ValueError):
        create_app(store=store, web_dist=tmp_path, allowed_roots=["/"])


def test_analyzing_a_local_file_end_to_end(client, media):
    job_id = analyzed(client, media, whisper_model="tiny")

    job = client.get(f"/api/jobs/{job_id}").json()
    assert job["percent"] == 100 and job["has_analysis"] and job["has_audio"]
    # the last stage of the pipeline is generating the report
    assert "report.html" in [p["message"] for p in job["progress"]][-1]
    assert job["summary"]["words"] == 5

    analysis = client.get(f"/api/jobs/{job_id}/analysis").json()
    assert analysis["segments"][0]["words"][0]["word"] == "does"
    assert analysis["meta"]["options"]["whisper_model"] == "tiny"

    assert client.get("/api/jobs").json()[0]["id"] == job_id


def test_a_non_existent_path_gives_400(client, tmp_path):
    resp = client.post("/api/jobs", json={"path": str(tmp_path / "nope.wav")})
    assert resp.status_code == 400
    assert "does not exist" in resp.json()["detail"]


def test_invalid_options_give_422(client, media):
    resp = client.post("/api/jobs", json={"path": str(media),
                                          "options": {"whisper_model": "giant"}})
    assert resp.status_code == 422


def test_nothing_outside_the_roots_can_be_analyzed(client, tmp_path):
    """The interface must not turn into a reader of the system's files."""
    resp = client.post("/api/jobs", json={"path": "/etc/passwd"})
    assert resp.status_code == 403
    assert "safety" in resp.json()["detail"]

    outside = tmp_path.parent / "outside.wav"
    outside.write_bytes(b"RIFF")
    try:
        assert client.post("/api/jobs", json={"path": str(outside)}).status_code == 403
        assert client.post("/api/jobs/import",
                           json={"path": str(tmp_path.parent)}).status_code == 403
    finally:
        outside.unlink()


def test_only_media_files_are_analyzed(client, tmp_path):
    text = tmp_path / "notes.txt"
    text.write_text("I am not a video", encoding="utf-8")
    resp = client.post("/api/jobs", json={"path": str(text)})
    assert resp.status_code == 400
    assert "video or an audio" in resp.json()["detail"]


def test_the_original_media_is_never_served_as_html(client, tmp_path):
    """Guessing the Content-Type would allow serving HTML —and therefore JS—
    from the same origin as the interface."""
    trap = tmp_path / "trap.webm"
    trap.write_bytes(b"<script>alert(1)</script>")
    job_id = client.post("/api/jobs", json={"path": str(trap)}).json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")

    resp = client.get(f"/api/jobs/{job_id}/media")
    assert resp.headers["content-type"].startswith("video/webm")


def test_upload_options_that_are_not_a_json_object_give_400(client):
    for payload in ("[]", '"hello"', "5", "{not json"):
        resp = client.post(
            "/api/jobs/upload",
            files={"file": ("x.wav", b"RIFF", "audio/wav")},
            data={"options": payload},
        )
        assert resp.status_code == 400, payload
        assert "invalid options" in resp.json()["detail"]


def test_a_corrupt_analysis_gives_400_and_not_500(client, tmp_path):
    broken = tmp_path / "broken"
    broken.mkdir()
    (broken / "analysis.json").write_text("{ this is not json", encoding="utf-8")

    resp = client.post("/api/jobs/import", json={"path": str(broken)})
    assert resp.status_code == 400
    assert "unreadable" in resp.json()["detail"]

    # and if it gets corrupted after being imported, the routes that read it do not blow up either
    (broken / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(broken)}).json()["id"]
    (broken / "analysis.json").write_text("{ broken again", encoding="utf-8")
    assert client.get(f"/api/jobs/{job_id}/review/sample").status_code == 400
    assert client.put(f"/api/jobs/{job_id}/review", json={"verdicts": []}).status_code == 400


def test_file_upload(client):
    resp = client.post(
        "/api/jobs/upload",
        files={"file": ("uploaded.wav", b"RIFF fake", "audio/wav")},
        data={"options": json.dumps({"attraction": False})},
    )
    assert resp.status_code == 201
    job_id = resp.json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")

    job = client.get(f"/api/jobs/{job_id}").json()
    assert job["source"] == "uploaded.wav" and job["has_media"] is True
    assert job["options"]["attraction"] is False


def test_audio_supports_range_so_it_can_seek(client, media):
    job_id = analyzed(client, media)

    whole = client.get(f"/api/jobs/{job_id}/audio")
    assert whole.status_code == 200
    assert whole.headers["accept-ranges"] == "bytes"

    partial = client.get(f"/api/jobs/{job_id}/audio", headers={"Range": "bytes=0-99"})
    assert partial.status_code == 206
    assert len(partial.content) == 100
    assert partial.headers["content-range"].startswith("bytes 0-99/")


def test_the_original_media_is_served_for_the_video(client, media):
    job_id = analyzed(client, media)
    resp = client.get(f"/api/jobs/{job_id}/media")
    assert resp.status_code == 200 and resp.content == media.read_bytes()


def test_an_artifact_that_does_not_exist_yet_gives_404(client, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    job_id = client.post("/api/jobs/import", json={"path": str(out)}).json()["id"]

    assert client.get(f"/api/jobs/{job_id}/audio").status_code == 404
    assert client.get(f"/api/jobs/{job_id}/media").status_code == 404
    assert client.get(f"/api/jobs/{job_id}/analysis").status_code == 200


def test_importing_an_existing_directory(client, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("episode.webm")),
                                        encoding="utf-8")

    job = client.post("/api/jobs/import", json={"path": str(out)}).json()
    assert job["status"] == "done" and job["imported"] is True
    assert job["source"] == "episode.webm"
    assert job["summary"]["segments"] == 2


def test_analyzing_a_youtube_url(client):
    resp = client.post("/api/jobs/youtube", json={
        "url": "https://youtu.be/abc123",
        "options": {"whisper_model": "tiny"},
        "audio_only": True,
    })
    assert resp.status_code == 201
    job_id = resp.json()["id"]
    wait_until(lambda: client.get(f"/api/jobs/{job_id}").json()["status"] == "done")

    job = client.get(f"/api/jobs/{job_id}").json()
    assert job["source_url"] == "https://youtu.be/abc123"
    assert job["source"] == "A video [abc123].mp4"
    assert job["has_media"] is True and job["is_video"] is True
    assert any("Downloading" in p["message"] for p in job["progress"])


def test_something_that_is_not_a_url_gives_400(client):
    resp = client.post("/api/jobs/youtube", json={"url": "/home/me/video.webm"})
    assert resp.status_code == 400
    assert "does not look like a URL" in resp.json()["detail"]


def test_the_corpus_accumulates_what_has_been_analyzed(client, media):
    empty = client.get("/api/corpus/stats").json()
    assert empty["analyses"] == 0 and empty["phenomena"] == []

    analyzed(client, media)
    analyzed(client, media)

    stats = client.get("/api/corpus/stats").json()
    assert stats["analyses"] == 2
    assert stats["words"] == 10
    assert {p["phenomenon"] for p in stats["phenomena"]} >= {"t_deletion", "vowel_reduction"}
    assert stats["top_words"][0]["word"] in {"does", "that", "wanna"}


def test_the_corpus_lists_occurrences_with_their_position(client, media):
    job_id = analyzed(client, media)

    body = client.get("/api/corpus/occurrences", params={"phenomenon": "t_deletion"}).json()
    assert body["phenomenon"] == "t_deletion"
    (item,) = body["items"]
    # with this the interface can open the analysis right at that word
    assert item["job_id"] == job_id
    assert (item["word"], item["segment"], item["word_idx"]) == ("that", 0, 1)
    assert item["analysis_source"] == "clip.wav"


def test_the_corpus_answers_how_a_word_has_been_said(client, media):
    analyzed(client, media)
    analyzed(client, media)

    body = client.get("/api/corpus/variants", params={"word": "That,"}).json()
    assert body["variants"][0]["realized_ipa"] == "ðæ"
    assert body["variants"][0]["count"] == 2          # once per analysis


def test_deleting_an_analysis_drops_it_from_the_corpus(client, media):
    job_id = analyzed(client, media)
    assert client.get("/api/corpus/stats").json()["analyses"] == 1

    client.delete(f"/api/jobs/{job_id}")
    assert client.get("/api/corpus/stats").json()["analyses"] == 0


def test_an_unknown_job_gives_404(client):
    assert client.get("/api/jobs/does-not-exist").status_code == 404
    assert client.get("/api/jobs/does-not-exist/analysis").status_code == 404
    assert client.get("/api/made-up").status_code == 404


def test_deleting_a_job(client, media):
    job_id = analyzed(client, media)
    assert client.delete(f"/api/jobs/{job_id}").status_code == 204
    assert client.get(f"/api/jobs/{job_id}").status_code == 404
    assert client.get("/api/jobs").json() == []


def test_the_review_sample_is_reproducible(client, media):
    job_id = analyzed(client, media)

    a = client.get(f"/api/jobs/{job_id}/review/sample", params={"n": 3, "seed": 48}).json()
    b = client.get(f"/api/jobs/{job_id}/review/sample", params={"n": 3, "seed": 48}).json()
    assert a == b
    assert len(a["items"]) == 3
    first = a["items"][0]
    assert first["word"]["word"]                        # the complete word
    assert first["segment_text"]                        # and its context


def test_saving_a_review_writes_review_json(client, media, store):
    job_id = analyzed(client, media)
    resp = client.put(f"/api/jobs/{job_id}/review", json={
        "seed": 7,
        "verdicts": [
            {"segment": 0, "word_idx": 0, "verdict": "ok"},
            {"segment": 0, "word_idx": 1, "verdict": "wrong", "note": "it is a pause"},
            {"segment": 1, "word_idx": 0, "verdict": "unsure"},
        ],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert (body["ok"], body["wrong"], body["unsure"]) == (1, 1, 1)
    assert body["accuracy"] == 0.5

    # persisted in the same shape the CLI writes
    saved = json.loads((store.get(job_id).result_dir / "review.json")
                       .read_text(encoding="utf-8"))
    assert saved["seed"] == 7
    assert saved["items"][1]["word"] == "that"
    assert saved["items"][1]["note"] == "it is a pause"
    assert saved["items"][1]["phenomena"] == ["t_deletion"]

    assert client.get(f"/api/jobs/{job_id}/review").json()["accuracy"] == 0.5
    assert client.get(f"/api/jobs/{job_id}").json()["has_review"] is True


def test_an_empty_review_before_reviewing(client, media):
    job_id = analyzed(client, media)
    assert client.get(f"/api/jobs/{job_id}/review").json() == {
        "items": [], "sampled": 0, "ok": 0, "wrong": 0, "unsure": 0,
        "accuracy": None, "seed": 48,
    }


def test_stale_verdicts_are_dropped_when_a_saved_review_is_read(client, media, store):
    """A verdict this API no longer has must not travel from an old file to the UI."""
    job_id = analyzed(client, media)
    put = client.put(f"/api/jobs/{job_id}/review", json={
        "seed": 7,
        "verdicts": [
            {"segment": 0, "word_idx": 0, "verdict": "ok"},
            {"segment": 0, "word_idx": 1, "verdict": "wrong"},
        ],
    }).json()

    # a review.json written before the rename: two entries speak a vocabulary
    # this API no longer has, and the summary that came with them counted both
    path = store.get(job_id).result_dir / "review.json"
    saved = json.loads(path.read_text(encoding="utf-8"))
    saved["items"].insert(1, dict(saved["items"][0], verdict="bogus"))
    saved["items"].append(dict(saved["items"][0], verdict="stale"))
    saved.update({"sampled": 4, "ok": 2, "wrong": 0, "unsure": 2, "accuracy": 1.0})
    path.write_text(json.dumps(saved), encoding="utf-8")

    body = client.get(f"/api/jobs/{job_id}/review").json()
    # the UI never sees them, so its next save cannot send them back and be rejected
    assert [item["verdict"] for item in body["items"]] == ["ok", "wrong"]
    # none of the numbers above survive: they are recounted from what is left
    assert (body["ok"], body["wrong"], body["unsure"]) == (1, 1, 0)
    assert body["sampled"] == 2
    assert body["accuracy"] == 0.5
    assert body["seed"] == 7
    # and a save answers with exactly the same shape
    assert body.keys() == put.keys() == {
        "items", "sampled", "ok", "wrong", "unsure", "accuracy", "seed"}


def test_a_verdict_out_of_range_gives_400(client, media):
    job_id = analyzed(client, media)
    resp = client.put(f"/api/jobs/{job_id}/review", json={
        "verdicts": [{"segment": 9, "word_idx": 99, "verdict": "ok"}]})
    assert resp.status_code == 400
    assert "out of range" in resp.json()["detail"]


@pytest.fixture
def home(tmp_path):
    """Simulated allowed root: the browser must never leave it."""
    home = tmp_path / "home"
    home.mkdir()
    return home


@pytest.fixture
def browser(store, home, tmp_path):
    app = create_app(store=store, web_dist=tmp_path / "nothing", allowed_roots=[home])
    with TestClient(app) as c:
        yield c


def test_the_browser_lists_media_and_directories(browser, home):
    (home / "videos").mkdir()
    (home / "videos" / "ep1.webm").write_bytes(b"x")
    (home / "output").mkdir()
    (home / "output" / "analysis.json").write_text("{}", encoding="utf-8")
    (home / "notes.txt").write_text("not media", encoding="utf-8")

    root = browser.get("/api/browse", params={"path": str(home)}).json()
    assert {d["name"] for d in root["dirs"]} == {"videos", "output"}
    assert root["files"] == []                                   # notes.txt is not media
    assert [d["has_analysis"] for d in root["dirs"] if d["name"] == "output"] == [True]
    assert root["parent"] is None                                # it never goes above the root

    inside = browser.get("/api/browse", params={"path": str(home / "videos")}).json()
    assert [f["name"] for f in inside["files"]] == ["ep1.webm"]
    assert inside["parent"] == str(home)


def test_the_browser_does_not_leave_the_root(browser, home):
    outside = browser.get("/api/browse", params={"path": "/etc"}).json()
    assert outside["path"] == str(home)
    assert browser.get("/api/browse", params={"path": str(home.parent)}).json()["path"] == str(home)


def test_when_not_built_it_explains_how_to_build(client):
    resp = client.get("/")
    assert resp.status_code == 200
    assert "npm install" in resp.text


def test_serves_the_built_spa(store, tmp_path):
    dist = tmp_path / "dist"
    (dist / "assets").mkdir(parents=True)
    (dist / "index.html").write_text("<html>app</html>", encoding="utf-8")
    (dist / "assets" / "app.js").write_text("console.log(1)", encoding="utf-8")

    with TestClient(create_app(store=store, web_dist=dist)) as client:
        assert client.get("/").text == "<html>app</html>"
        assert client.get("/assets/app.js").text == "console.log(1)"
        assert client.get("/job/abc").text == "<html>app</html>"   # router routes
        assert client.get("/api/health").json()["web_built"] is True
