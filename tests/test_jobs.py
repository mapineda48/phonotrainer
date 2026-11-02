"""Job store: threaded execution, progress, cancellation and persistence."""

import json
import os
import stat
import sys
import threading
import time
from pathlib import Path

import pytest
from conftest import fake_analyze, mk_analysis, wait_until

from phonotrainer import jobs
from phonotrainer.jobs import JobError, JobStore, estimate_percent


@pytest.fixture
def media(tmp_path):
    path = tmp_path / "clip.wav"
    path.write_bytes(b"RIFF fake media")
    return path


@pytest.fixture
def store(tmp_path):
    st = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze)
    yield st
    st.shutdown()


def _done(store, job):
    return wait_until(lambda: store.get(job.id).status == jobs.DONE and job)


def test_analyzes_and_leaves_the_artifacts_behind(store, media):
    job = store.create(media, options={"whisper_model": "tiny"})
    _done(store, job)

    assert job.artifact("analysis.json") is not None
    assert job.artifact("audio.wav") is not None
    assert job.summary["words"] == 5
    assert job.meta["source"] == "clip.wav"
    assert job.meta["options"]["whisper_model"] == "tiny"   # the options reach the pipeline


def test_progress_carries_a_timestamp_and_a_percentage(store, media):
    job = store.create(media)
    _done(store, job)

    messages = [p["message"] for p in job.progress]
    assert any("faster-whisper" in m for m in messages)   # the transcription stage
    assert all(p["at"] for p in job.progress)
    assert job.percent == 100


def test_listeners_hear_the_whole_lifecycle(store, media):
    """This is what the WebSocket feeds on: upsert on creation, while running
    and when finished, and deleted on removal. A broken listener does not break
    the analysis."""
    events = []
    store.subscribe(lambda event, job_id: events.append((event, job_id)))
    store.subscribe(lambda _e, _i: (_ for _ in ()).throw(RuntimeError("broken")))

    job = store.create(media)
    _done(store, job)
    store.delete(job.id)

    assert ("upsert", job.id) in events
    assert ("deleted", job.id) in events
    # every progress message is an upsert: there are several between start and end
    assert len([e for e in events if e == ("upsert", job.id)]) > 3


def test_unsubscribing_stops_the_notifications(store, media):
    events = []
    unsubscribe = store.subscribe(lambda event, job_id: events.append(event))
    unsubscribe()

    job = store.create(media)
    _done(store, job)
    assert events == []


def test_touch_reports_changes_made_outside_the_store(store, media):
    job = store.create(media)
    _done(store, job)

    events = []
    store.subscribe(lambda event, job_id: events.append((event, job_id)))
    store.touch(job.id)
    assert events == [("upsert", job.id)]


def test_percentage_estimation():
    assert estimate_percent(None) == 0
    assert estimate_percent("Extracting audio (ffmpeg…)") == 17
    assert estimate_percent("Segment 1/10: phones…") == 39
    assert estimate_percent("Segment 10/10: phones…") == 95
    assert estimate_percent("Generating report.html…") == 98
    assert estimate_percent("a message we do not recognize") == 0


def test_the_download_gets_its_own_stretch_and_the_bar_never_goes_back():
    """A job that starts by downloading goes through two phases: the percentage
    must grow from one to the other, not restart."""
    downloading = [estimate_percent(f"Downloading from YouTube… {p}%") for p in (0, 50, 100)]
    assert downloading == [2, 8, 15]
    assert downloading[-1] <= estimate_percent("Downloaded: clip.mp4")
    assert estimate_percent("Downloaded: clip.mp4") <= estimate_percent("Extracting audio…")
    assert estimate_percent("Extracting audio…") <= estimate_percent("Segment 1/10: …")


def test_a_pipeline_error_stays_on_the_job(tmp_path, media):
    def boom(*a, **kw):
        raise RuntimeError("ffmpeg failed")

    store = JobStore(tmp_path / "ws", analyze_fn=boom)
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.ERROR)
        assert "ffmpeg failed" in job.error
        assert job.finished
    finally:
        store.shutdown()


def test_cancelling_aborts_the_analysis(tmp_path, media):
    def slow(media_path, out_dir, progress=lambda m: None, **kw):
        for i in range(1, 201):
            time.sleep(0.01)                   # simulates the work of one segment
            progress(f"Segment {i}/200: …")    # the callback raises JobCancelled
        return mk_analysis()

    store = JobStore(tmp_path / "ws", analyze_fn=slow)
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.RUNNING and len(job.progress) > 2)
        store.cancel(job.id)
        wait_until(lambda: job.status == jobs.CANCELLED)
        assert job.artifact("analysis.json") is None
    finally:
        store.shutdown()


def test_jobs_are_recovered_on_restart(tmp_path, media):
    root = tmp_path / "ws"
    store = JobStore(root, analyze_fn=fake_analyze)
    job = store.create(media)
    _done(store, job)
    store.shutdown()

    revived = JobStore(root, analyze_fn=fake_analyze)
    try:
        assert [j.id for j in revived.list()] == [job.id]
        assert revived.get(job.id).status == jobs.DONE
    finally:
        revived.shutdown()


def test_an_interrupted_job_is_marked_as_failed(tmp_path):
    root = tmp_path / "ws"
    job_dir = root / "20260101-000000-abcd"
    job_dir.mkdir(parents=True)
    (job_dir / "job.json").write_text(json.dumps({
        "id": job_dir.name, "source": "x.wav", "status": "running",
        "created": "2026-01-01T00:00:00+00:00", "progress": [],
    }), encoding="utf-8")

    store = JobStore(root, analyze_fn=fake_analyze)
    try:
        assert store.get(job_dir.name).status == jobs.ERROR
        assert "Interrupted" in store.get(job_dir.name).error
    finally:
        store.shutdown()


def test_importing_an_existing_directory(store, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("episode.webm")),
                                        encoding="utf-8")

    job = store.import_dir(out)
    assert job.status == jobs.DONE
    assert job.imported and job.result_dir == out.resolve()
    assert job.summary["phenomena_counts"]["t_deletion"] == 1
    assert store.import_dir(out).id == job.id      # idempotent


def test_importing_without_an_analysis_fails(store, tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(JobError):
        store.import_dir(empty)


def test_deleting_does_not_touch_imported_data(store, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    job = store.import_dir(out)

    store.delete(job.id)
    assert (out / "analysis.json").is_file()      # the external data is still there
    assert not (store.root / job.id).exists()
    with pytest.raises(JobError):
        store.get(job.id)


def test_deleting_a_running_job_waits_for_it_to_stop(tmp_path, media):
    """The worker keeps writing into the directory: deleting it right away left
    the analysis half done, a swallowed FileNotFoundError and —if the pipeline
    recreated the directory— the job resurrected on restart."""
    started = threading.Event()

    def slow(media_path, out_dir, progress=lambda m: None, **kw):
        started.set()
        for i in range(1, 201):
            time.sleep(0.01)
            progress(f"Segment {i}/200: …")
            Path(out_dir).mkdir(parents=True, exist_ok=True)
            (Path(out_dir) / "partial.json").write_text("{}", encoding="utf-8")
        return mk_analysis()

    store = JobStore(tmp_path / "ws", analyze_fn=slow)
    try:
        job = store.create(media)
        started.wait(5)
        store.delete(job.id)

        assert store.list() == []                       # it leaves the UI at once
        with pytest.raises(JobError):
            store.get(job.id)
        wait_until(lambda: job.status == jobs.CANCELLED)
        wait_until(lambda: not job.dir.exists())        # and the directory is cleaned up on stop
    finally:
        store.shutdown()


def test_deleting_a_running_job_does_not_resurrect_it_on_restart(tmp_path, media):
    """The worker can take minutes to reach its cancellation point (loading
    models). If the process dies before that, the deleted job must not come
    back."""
    started = threading.Event()

    def slow(media_path, out_dir, progress=lambda m: None, **kw):
        started.set()
        time.sleep(30)                      # like loading the models: no progress() calls
        return mk_analysis()

    root = tmp_path / "ws"
    store = JobStore(root, analyze_fn=slow)
    job = store.create(media)
    started.wait(5)
    store.delete(job.id)
    # the process "dies" here: we give it no time to reach _finish

    revived = JobStore(root, analyze_fn=fake_analyze)
    try:
        assert revived.list() == []
    finally:
        revived.shutdown()
        store.shutdown()


def test_importing_something_that_is_not_an_analysis_fails(store, tmp_path):
    for content in ("[1,2,3]", "null", "{}", '{"segments": 4}'):
        odd = tmp_path / f"odd{abs(hash(content))}"
        odd.mkdir()
        (odd / "analysis.json").write_text(content, encoding="utf-8")
        with pytest.raises(JobError):
            store.import_dir(odd)


def test_importing_the_same_directory_at_once_creates_a_single_job(store, tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")

    ready = threading.Barrier(8)
    created: list[str] = []
    lock = threading.Lock()

    def do_import():
        ready.wait()
        job = store.import_dir(out)
        with lock:
            created.append(job.id)

    threads = [threading.Thread(target=do_import) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert len(set(created)) == 1
    assert len(store.list()) == 1


def test_the_progress_milestones_still_exist_in_the_code():
    """`estimate_percent` recognizes prefixes by text: if someone rewrites a
    message, the bar stays at 0 without anything failing. This test ties the
    two ends together."""
    root = Path(jobs.__file__).parent
    source = "".join((root / name).read_text(encoding="utf-8")
                     for name in ("pipeline.py", "download.py", "separation.py"))
    for prefix, _ in jobs._STAGE_PERCENT:
        assert f'"{prefix}' in source or f"f\"{prefix}" in source, prefix
    assert "Segment {si + 1}/{n_seg}" in source      # the one feeding the fine-grained percentage
    assert "Downloading from YouTube… {percent}%" in source


def test_a_job_from_a_url_downloads_first_and_then_analyzes(tmp_path):
    downloads = []

    def fake_download(url, dest_dir, audio_only=False, progress=lambda m: None):
        downloads.append((url, Path(dest_dir), audio_only))
        progress("Downloading from YouTube… 100%")
        dest = Path(dest_dir)
        dest.mkdir(parents=True, exist_ok=True)
        file = dest / "A video [abc123].mp4"
        file.write_bytes(b"media")
        progress(f"Downloaded: {file.name}")
        return file

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze,
                     download_dir=tmp_path / "downloads", download_fn=fake_download)
    try:
        job = store.create_from_url("https://youtu.be/abc123", {"whisper_model": "tiny"},
                                    audio_only=True)
        assert job.source == "https://youtu.be/abc123"      # until the title is known
        wait_until(lambda: job.status == jobs.DONE)

        assert downloads == [("https://youtu.be/abc123", tmp_path / "downloads", True)]
        assert job.source == "A video [abc123].mp4"         # now with the real name
        assert job.media_path.endswith("A video [abc123].mp4")
        assert job.artifact("analysis.json") is not None
        messages = [p["message"] for p in job.progress]
        assert any("Downloading" in m for m in messages)
        assert any("faster-whisper" in m for m in messages)
        assert job.to_public()["source_url"] == "https://youtu.be/abc123"
    finally:
        store.shutdown()


def test_a_finished_analysis_enters_the_corpus(tmp_path, media):
    from phonotrainer.db import Corpus

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.DONE)

        assert corpus.stats()["words"] == 5
        assert corpus.occurrences(phenomenon="t_deletion")[0]["job_id"] == job.id
        # the identity in the corpus is the analysis directory, not the job:
        # that way analyzing from the CLI and importing later does not count twice
        assert corpus.analyses()[0]["id"] == str(job.result_dir.resolve())

        store.delete(job.id)                    # and leaving the list drops it from the corpus
        assert corpus.stats()["analyses"] == 0
    finally:
        store.shutdown()
        corpus.close()


def test_importing_also_indexes(tmp_path):
    from phonotrainer.db import Corpus

    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("ep.webm")), encoding="utf-8")

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        store.import_dir(out)
        assert corpus.stats()["analyses"] == 1
        assert corpus.analyses()[0]["source"] == "ep.webm"
    finally:
        store.shutdown()
        corpus.close()


def test_the_corpus_rebuilds_itself_from_what_is_already_in_the_workspace(tmp_path, media):
    """The corpus is derived: deleting data/ (or cloning the repo, which starts
    without a database) must not leave it out of sync with the list of
    analyses."""
    from phonotrainer.db import Corpus

    root = tmp_path / "ws"
    first = Corpus(":memory:")
    store = JobStore(root, analyze_fn=fake_analyze, corpus=first)
    job = store.create(media)
    wait_until(lambda: job.status == jobs.DONE)
    store.shutdown()
    first.close()

    # a brand-new, empty database, same workspace
    empty = Corpus(":memory:")
    revived = JobStore(root, analyze_fn=fake_analyze, corpus=empty)
    try:
        assert empty.stats()["analyses"] == 1
        assert empty.analyses()[0]["job_id"] == job.id
        assert empty.occurrences(phenomenon="t_deletion")
    finally:
        revived.shutdown()
        empty.close()


def test_reimporting_indexes_again(tmp_path):
    from phonotrainer.db import Corpus

    out = tmp_path / "out"
    out.mkdir()
    (out / "analysis.json").write_text(json.dumps(mk_analysis("ep.webm")), encoding="utf-8")

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        job = store.import_dir(out)
        corpus.forget_job(job.id)                  # as if the index had been lost
        assert corpus.stats()["analyses"] == 0

        assert store.import_dir(out).id == job.id  # still idempotent…
        assert corpus.stats()["analyses"] == 1     # …but the index is restored
    finally:
        store.shutdown()
        corpus.close()


def test_deleting_while_running_leaves_no_ghosts_in_the_corpus(tmp_path, media):
    """If the worker finishes after the deletion, its analysis must not enter
    the corpus: it used to leave a row pointing at a non-existent job that
    survived every restart."""
    from phonotrainer.db import Corpus

    started = threading.Event()

    def slow(media_path, out_dir, progress=lambda m: None, **kw):
        started.set()
        time.sleep(0.3)                 # no further progress() calls: it does not self-cancel
        return fake_analyze(media_path, out_dir, progress=lambda m: None, **kw)

    corpus = Corpus(":memory:")
    store = JobStore(tmp_path / "ws", analyze_fn=slow, corpus=corpus)
    try:
        job = store.create(media)
        started.wait(5)
        store.delete(job.id)
        wait_until(lambda: job.status in (jobs.DONE, jobs.CANCELLED, jobs.ERROR))

        assert corpus.stats()["analyses"] == 0
        assert corpus.occurrences() == []
    finally:
        store.shutdown()
        corpus.close()


def test_on_startup_rows_of_jobs_that_no_longer_exist_are_purged(tmp_path, media):
    from phonotrainer.db import Corpus

    corpus = Corpus(":memory:")
    root = tmp_path / "ws"
    store = JobStore(root, analyze_fn=fake_analyze, corpus=corpus)
    job = store.create(media)
    wait_until(lambda: job.status == jobs.DONE)
    store.shutdown()

    _rmtree_dir(job.dir)                # as if it had been deleted by hand
    assert corpus.stats()["analyses"] == 1

    revived = JobStore(root, analyze_fn=fake_analyze, corpus=corpus)
    try:
        assert corpus.stats()["analyses"] == 0
    finally:
        revived.shutdown()
        corpus.close()


def test_analyzing_from_the_cli_and_importing_later_does_not_count_twice(tmp_path):
    """The README flow: `analyze -o out/` and then `ui --import-dir out/`."""
    from phonotrainer.db import Corpus
    from phonotrainer.jobs import analysis_key

    out = tmp_path / "out"
    out.mkdir()
    analysis = mk_analysis("ep.webm")
    (out / "analysis.json").write_text(json.dumps(analysis), encoding="utf-8")

    corpus = Corpus(":memory:")
    corpus.index_analysis(analysis_key(out), analysis, source="ep.webm",
                          result_dir=str(out))       # what the CLI does

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=corpus)
    try:
        store.import_dir(out)                        # and now the interface
        assert corpus.stats()["analyses"] == 1       # the same material, one entry
        assert corpus.stats()["words"] == 5
        assert corpus.analyses()[0]["job_id"] is not None
    finally:
        store.shutdown()
        corpus.close()


def _rmtree_dir(path):
    import shutil

    shutil.rmtree(path, ignore_errors=True)


def test_if_the_corpus_fails_the_analysis_is_still_valid(tmp_path, media):
    class BrokenCorpus:
        def index_analysis(self, *a, **kw):
            raise RuntimeError("disk full")

        def forget(self, *a, **kw):
            pass

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=BrokenCorpus())
    try:
        job = store.create(media)
        wait_until(lambda: job.status == jobs.DONE)
        assert job.artifact("analysis.json") is not None
        assert any("could not index" in p["message"] for p in job.progress)
    finally:
        store.shutdown()


def test_creating_with_a_non_existent_path_fails(store, tmp_path):
    with pytest.raises(JobError):
        store.create(tmp_path / "does-not-exist.wav")


def test_an_upload_copies_the_file_into_the_job(store):
    job = store.adopt_upload("uploaded.wav", lambda dest: dest.write_bytes(b"RIFFdata"))
    _done(store, job)
    assert Path(job.media_path) == (job.dir / "media" / "uploaded.wav").resolve()
    assert job.to_public()["has_media"] is True


def test_imported_analyses_know_their_phone_engine():
    from phonotrainer.jobs import _phone_engine_of

    assert _phone_engine_of({"phone_engine": "timit61"}) == "timit61"
    # older analyses carry only the model id: all of them were made with espeak
    assert _phone_engine_of(
        {"models": {"phones": "facebook/wav2vec2-lv-60-espeak-cv-ft"}}) == "espeak"
    assert _phone_engine_of({}) == "espeak"


WINDOWS_ONLY = pytest.mark.skipif(sys.platform != "win32",
                                  reason="Windows cannot delete or replace an open file")


@WINDOWS_ONLY
def test_a_deleted_job_whose_files_are_open_is_removed_later_on_windows(store, media,
                                                                         monkeypatch):
    """A file being served (or hashed) when the job is deleted stays behind on
    Windows, with a tombstone next to the directory: the removal is retried
    once the file is closed, and the tombstone goes with the directory."""
    monkeypatch.setattr(jobs, "RMTREE_RETRIES_S", (0.2, 0.2, 0.2))
    job = _done(store, store.create(media))
    tombstone = store.root / f".{job.id}.deleted"
    (job.dir / "readonly.txt").write_text("x")
    os.chmod(job.dir / "readonly.txt", stat.S_IREAD)            # git objects are read-only
    held = open(job.dir / "audio.wav", "rb")                    # a download in progress
    try:
        store.delete(job.id)
        assert job.dir.is_dir() and not (job.dir / "job.json").exists()
        assert not (job.dir / "readonly.txt").exists()
        assert tombstone.is_file()
    finally:
        held.close()
    wait_until(lambda: not job.dir.exists() and not tombstone.exists(), timeout=5)


@WINDOWS_ONLY
def test_only_tombstoned_job_directories_are_removed_at_the_start_on_windows(store):
    """A process that died before its retries leaves the tombstone: the next
    start finishes the removal. A job directory without job.json and without
    a tombstone (a job another store on this workspace is creating, a job.json
    deleted by hand) is never touched."""
    buried = store.root / "20261010-091503-4be1"
    (buried / "media").mkdir(parents=True)
    (buried / "media" / "clip.wav").write_bytes(b"RIFF")
    (store.root / ".20261010-091503-4be1.deleted").touch()
    being_created = store.root / "20261010-091504-5cf2"
    (being_created / "media").mkdir(parents=True)
    (being_created / "media" / "clip.wav").write_bytes(b"RIFF")
    unrelated = store.root / "notes"
    unrelated.mkdir()
    JobStore(store.root, analyze_fn=fake_analyze, corpus=None).shutdown()
    assert not buried.exists() and not (store.root / ".20261010-091503-4be1.deleted").exists()
    assert (being_created / "media" / "clip.wav").is_file() and unrelated.is_dir()


@WINDOWS_ONLY
def test_a_running_job_deleted_on_windows_leaves_a_tombstone_until_it_is_gone(
        tmp_path, media, blocking_analyze):
    store = JobStore(tmp_path / "workspace", analyze_fn=blocking_analyze, corpus=None)
    try:
        job = store.create(media)
        assert blocking_analyze.started.wait(10)
        store.delete(job.id)
        tombstone = store.root / f".{job.id}.deleted"
        assert tombstone.is_file() and not (job.dir / "job.json").exists()
        blocking_analyze.release.set()
        wait_until(lambda: not job.dir.exists() and not tombstone.exists())
    finally:
        blocking_analyze.release.set()
        store.shutdown()


@WINDOWS_ONLY
def test_job_json_is_saved_while_a_reader_holds_it_on_windows(store, media):
    import threading

    job = _done(store, store.create(media))
    reader = open(job.dir / "job.json", encoding="utf-8")
    threading.Timer(0.3, reader.close).start()
    job.error = "marker"
    store._save(job)
    assert reader.closed
    assert json.loads((job.dir / "job.json").read_text(encoding="utf-8"))["error"] == "marker"
