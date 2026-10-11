"""Requests from phones: links behind the SSRF guard, idempotency keys, request
lookup, cancel, delete and re-run (server-contract 4.5, 6.3, 6.5, 6.7; S-06,
S-07, S-09, S-15, S-22; QT-3, QT-5, QT-12; F-20-AC3 to AC5)."""

import json
import os
import threading
from pathlib import Path

import pytest
from conftest import fake_analyze, paired_client, wait_until, write_silent_wav
from phone_fixtures import PUBLIC_V4, FakeResolver

from phonotrainer.download import DownloadError, DownloadLimits, download
from phonotrainer.jobs import PHONE_ORIGIN, JobStore
from phonotrainer.phone import ssrf
from phonotrainer.phone.ssrf import CannotCheckLink, InvalidLink, LinkRefused, check_link

KEY1 = "00000000-0000-4000-8000-000000000001"
KEY2 = "00000000-0000-4000-8000-000000000002"
KEY3 = "00000000-0000-4000-8000-000000000003"
KEY4 = "00000000-0000-4000-8000-000000000004"
KEY5 = "00000000-0000-4000-8000-000000000005"


def link(signing, url="https://youtu.be/abc123def45", key=KEY1, **extra):
    return signing.post("/api/phone/jobs/link",
                        json={"client_request_id": key, "url": url, **extra})


def status_of(store, job_id):
    return store.get(job_id).status


def finished(store, job_id):
    return wait_until(lambda: store.get(job_id).status in ("done", "error", "cancelled")
                      and store.get(job_id))


def forbidden_download(url, dest_dir, audio_only=False, progress=lambda m: None, **kwargs):
    progress("Looking up the URL…")
    raise DownloadError("ERROR: [youtube] abc123def45: unable to download video data: "
                        "HTTP Error 403: Forbidden")


def analyze_failing_on_bad(media_path, out_dir, progress=lambda m: None, **options):
    if "bad" in Path(media_path).name:
        raise RuntimeError("the analysis failed on purpose")
    return fake_analyze(media_path, out_dir, progress=progress, **options)


# --- the SSRF guard itself -----------------------------------------------------------------

HOSTILE = ["loopback.example", "private.example", "router.example", "linklocal.example",
           "cgnat.example", "multicast.example", "ula.example", "mapped.example",
           "nat64.example", "nat64local.example", "sixtofour.example", "teredo.example",
           "documentation.example", "mixed.example", "unspecified.example"]


@pytest.mark.parametrize("host", HOSTILE)
def test_names_that_resolve_to_local_addresses_are_refused(host):
    with pytest.raises(LinkRefused):
        check_link(f"https://{host}/watch?v=1", FakeResolver())


# hygiene: test-vector begin
@pytest.mark.parametrize("url", [
    "http://127.0.0.1:8000/api/browse", "http://[::1]/", "http://10.0.0.1/x",
    "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/",
    "http://224.0.0.1/", "http://[fd00::1]/", "http://[fe80::1]/", "http://[::ffff:10.0.0.1]/",
    "http://[64:ff9b::808:808]/", "http://[64:ff9b:1::a00:1]/", "http://[2002:808:808::1]/",
    "http://[2001:0:4136:e378::1]/", "http://0.0.0.0/", "http://192.0.2.10:8000/",
])
def test_literal_addresses_are_checked_without_dns(url):
    resolver = FakeResolver()
    with pytest.raises(LinkRefused):
        check_link(url, resolver)
    assert resolver.calls == []


def test_a_public_literal_and_public_names_pass():
    resolver = FakeResolver()
    assert check_link(f"http://{PUBLIC_V4}/v.mp4", resolver) == f"http://{PUBLIC_V4}/v.mp4"
    assert check_link("  https://www.youtube.com/shorts/abc  ", resolver) == \
        "https://www.youtube.com/shorts/abc"
    assert resolver.calls == ["www.youtube.com"]
    assert ssrf.public_address("2a00:1450::1")
# hygiene: test-vector end


@pytest.mark.parametrize("url", [
    "", "   ", "ftp://youtu.be/abc", "javascript:alert(1)", "file:///etc/passwd", "https://",
    "https://user:secret@youtu.be/abc", "https://user@youtu.be/abc", "https://you tu.be/abc",
    "https://youtu.be/a\nb", "https://youtu.be:99999/abc", "https://youtu.be\\@x.com/",
    "https://youtu.be/" + "a" * (2049 - len("https://youtu.be/")),
])
def test_invalid_links(url):
    with pytest.raises(InvalidLink):
        check_link(url, FakeResolver())


def test_a_link_of_exactly_2048_characters_is_accepted():
    url = "https://youtu.be/" + "a" * (2048 - len("https://youtu.be/"))
    assert check_link(url, FakeResolver()) == url


def test_a_failed_or_slow_lookup_is_never_accepted():
    resolver = FakeResolver()
    with pytest.raises(CannotCheckLink):
        check_link("https://nowhere.example/", resolver)
    resolver.delay = 0.5
    with pytest.raises(CannotCheckLink):
        check_link("https://youtu.be/abc", resolver, timeout_s=0.05)


def test_internationalized_host_names_are_looked_up_in_ascii():
    resolver = FakeResolver()
    resolver.table["xn--bcher-kva.example"] = [PUBLIC_V4]
    assert check_link("https://bücher.example/v", resolver) == "https://bücher.example/v"
    assert resolver.calls == ["xn--bcher-kva.example"]


# --- links (6.5) ---------------------------------------------------------------------------

def test_a_link_creates_one_phone_origin_job(paired, phone_app, phone_store):
    signing, device_id, _ = paired
    resp = link(signing, "  https://youtu.be/abc123def45  ", key=KEY1.upper(),
                audio_only=True, separate_dialogue=False)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["created"] is True
    job = body["job"]
    assert job["source"] == {"kind": "link", "url": "https://youtu.be/abc123def45",
                             "site": "youtube", "file_name": None}
    assert job["own"] is True and job["device_id"] == device_id
    assert job["client_request_id"] == KEY1                    # lower case
    assert job["options"] == {"audio_only": True, "separate_dialogue": False}
    assert "artifacts" in job
    stored = json.loads((phone_store.root / job["id"] / "job.json").read_text())
    assert stored["origin"] == PHONE_ORIGIN
    assert stored["device_id"] == device_id and stored["client_request_id"] == KEY1
    assert stored["options"]["separate_dialogue"] is False
    assert stored["options"]["phone_engine"] == "timit61"     # the server's defaults


def test_the_same_key_twice_gives_one_job(paired, phone_store):
    signing, _, _ = paired
    first = link(signing)
    again = link(signing)
    assert first.status_code == 201 and again.status_code == 200
    assert again.json()["created"] is False
    assert again.json()["job"]["id"] == first.json()["job"]["id"]
    assert len(phone_store.list()) == 1


def test_concurrent_requests_with_one_key_create_one_job(paired, phone_store, fake_resolver):
    signing, _, _ = paired
    fake_resolver.delay = 0.05                    # both pass the check before either creates
    results = []

    def send():
        results.append(link(signing))

    threads = [threading.Thread(target=send) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert sorted(r.status_code for r in results) == [200, 200, 200, 201]
    assert len({r.json()["job"]["id"] for r in results}) == 1
    assert len(phone_store.list()) == 1


def test_a_key_reused_for_another_link_is_a_conflict(paired, phone_store):
    signing, _, _ = paired
    assert link(signing).status_code == 201
    resp = link(signing, "https://youtu.be/another0001")
    assert resp.status_code == 409 and resp.json()["code"] == "key_conflict"
    assert len(phone_store.list()) == 1


def test_keys_belong_to_their_device(paired, phone_app, phone_store, fake_clock):
    signing, _, _ = paired
    other, _ = paired_client(phone_app, fake_clock, "Tablet")
    assert link(signing).status_code == 201
    resp = link(other, "https://youtu.be/another0001")         # the same key, another phone
    assert resp.status_code == 201
    assert len(phone_store.list()) == 2


@pytest.mark.parametrize("url, status, code", [
    ("https://loopback.example/x", 400, "refused_address"),
    ("https://nat64.example/x", 400, "refused_address"),
    ("http://127.0.0.1:8000/api/browse", 400, "refused_address"),
    ("https://user:pw@youtu.be/abc", 400, "invalid_url"),
    ("ftp://youtu.be/abc", 400, "invalid_url"),
    ("https://youtu.be/" + "a" * 2040, 400, "invalid_url"),
    ("https://nowhere.example/x", 503, "cannot_check_link"),
])
def test_links_are_checked_before_a_job_exists(paired, phone_store, url, status, code):
    signing, _, _ = paired
    resp = link(signing, url)
    assert resp.status_code == status and resp.json()["code"] == code
    assert phone_store.list() == []


@pytest.mark.parametrize("body", [
    {"url": "https://youtu.be/abc"},
    {"client_request_id": "not-a-uuid", "url": "https://youtu.be/abc"},
    {"client_request_id": KEY1},
    {"client_request_id": KEY1, "url": "https://youtu.be/abc", "audio_only": "yes"},
])
def test_malformed_link_requests(paired, body):
    signing, _, _ = paired
    resp = signing.post("/api/phone/jobs/link", json=body)
    assert resp.status_code == 400 and resp.json()["code"] == "invalid_request"


def test_the_download_of_a_phone_link_is_confined(phone_server, tmp_path):
    seen = {}

    def spy(url, dest_dir, audio_only=False, progress=lambda m: None, **kwargs):
        seen.update(kwargs)
        raise DownloadError("stop here")

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=spy)
    app, signing, _ = phone_server(store)
    job_id = link(signing).json()["job"]["id"]
    finished(store, job_id)
    assert seen["phone_origin"] is True
    limits = seen["limits"]
    assert isinstance(limits, DownloadLimits)
    assert limits.max_duration_s == 3600 and limits.max_bytes == 2 * 1024 ** 3
    assert limits.check_link("https://youtu.be/abc") == "https://youtu.be/abc"
    with pytest.raises(LinkRefused):
        limits.check_link("https://router.example/")
    # a browser link keeps today's call
    seen.clear()
    browser = store.create_from_url("https://youtu.be/abc", {})
    finished(store, browser.id)
    assert seen == {}


class LookingYDL:
    """A yt-dlp double that must never fetch anything for these tests."""

    def __init__(self, options):
        self.options = options

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def extract_info(self, url, download=True):
        raise AssertionError("the link was looked up although it re-resolved to a local address")


def test_a_link_that_re_resolves_to_a_local_address_fails_before_the_download(
        phone_server, tmp_path, fake_resolver):
    def real_download(url, dest_dir, **kwargs):
        return download(url, dest_dir, ydl_factory=LookingYDL, **kwargs)

    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=real_download)
    app, signing, _ = phone_server(store)
    resp = link(signing, "https://rebinding.example/watch?v=1")   # public at creation
    assert resp.status_code == 201
    job = finished(store, resp.json()["job"]["id"])
    assert job.status == "error" and job.error.startswith("LinkRefused:")
    body = signing.get(f"/api/phone/jobs/{job.id}").json()
    assert body["error"]["code"] == "refused_address"
    assert fake_resolver.calls.count("rebinding.example") == 2


# --- request lookup (6.3) --------------------------------------------------------------------

def test_request_lookup_finds_only_this_phones_keys(paired, phone_app, fake_clock):
    signing, _, _ = paired
    other, _ = paired_client(phone_app, fake_clock, "Tablet")
    job_id = link(signing).json()["job"]["id"]
    resp = signing.get(f"/api/phone/requests/{KEY1.upper()}")
    assert resp.status_code == 200 and resp.json()["job"]["id"] == job_id
    for client, key in ((other, KEY1), (signing, KEY2), (signing, "nonsense")):
        missing = client.get(f"/api/phone/requests/{key}")
        assert missing.status_code == 404 and missing.json()["code"] == "unknown_request"


# --- cancel, delete (6.7) ---------------------------------------------------------------------

def test_cancel_a_queued_and_a_running_job(phone_server, tmp_path, blocking_analyze):
    store = JobStore(tmp_path / "ws", analyze_fn=blocking_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=lambda url, d, **k: (
                         write_silent_wav(Path(d) / "clip [x].wav")))
    try:
        app, signing, _ = phone_server(store)
        running = link(signing, key=KEY1).json()["job"]["id"]
        assert blocking_analyze.started.wait(5)
        queued = link(signing, "https://youtu.be/second00001", key=KEY2).json()["job"]["id"]
        resp = signing.post(f"/api/phone/jobs/{queued}/cancel")
        assert resp.status_code == 200
        assert resp.json()["job"]["status"] == "cancelled"        # at once, not later
        assert resp.json()["job"]["error"]["code"] == "cancelled"
        resp = signing.post(f"/api/phone/jobs/{running}/cancel")
        assert resp.status_code == 200 and resp.json()["job"]["status"] == "running"
    finally:
        blocking_analyze.release.set()
    assert finished(store, running).status == "cancelled"
    assert store.get(queued).status == "cancelled"
    assert store.get(queued).error == "Cancelled before starting."
    # the worker reaching the cancelled job leaves it as it is
    later = link(signing, "https://youtu.be/third000001", key=KEY3).json()["job"]["id"]
    assert finished(store, later).status == "done"
    assert store.get(queued).status == "cancelled"


def test_cancel_of_a_done_job_is_too_late_and_of_a_failed_one_a_no_op(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=forbidden_download)
    app, signing, _ = phone_server(store)
    failed = link(signing).json()["job"]["id"]
    assert finished(store, failed).status == "error"
    resp = signing.post(f"/api/phone/jobs/{failed}/cancel")
    assert resp.status_code == 200
    assert resp.json()["job"]["status"] == "error"
    assert resp.json()["job"]["error"]["code"] == "download_forbidden"
    assert resp.json()["job"]["error"]["downloader_version"]

    media = write_silent_wav(tmp_path / "in" / "clip.wav")
    done = store.adopt_upload("clip.wav", lambda dest: dest.write_bytes(media.read_bytes()),
                              {}, device_id=app.state.phone.registry.devices()[0].device_id,
                              client_request_id=KEY5, origin=PHONE_ORIGIN)
    assert finished(store, done.id).status == "done"
    resp = signing.post(f"/api/phone/jobs/{done.id}/cancel")
    assert resp.status_code == 409 and resp.json()["code"] == "already_finished"
    assert resp.json()["job"]["id"] == done.id and resp.json()["job"]["status"] == "done"


def test_only_own_jobs_can_be_cancelled_deleted_or_re_run(paired, phone_app, phone_store,
                                                         fake_clock, tmp_path):
    signing, _, _ = paired
    other, _ = paired_client(phone_app, fake_clock, "Tablet")
    theirs = link(other).json()["job"]["id"]
    browser = phone_store.create(write_silent_wav(tmp_path / "b.wav")).id
    for job_id in (theirs, browser):
        finished(phone_store, job_id)
        for resp in (signing.post(f"/api/phone/jobs/{job_id}/cancel"),
                     signing.delete(f"/api/phone/jobs/{job_id}"),
                     signing.post(f"/api/phone/jobs/{job_id}/rerun",
                                  json={"client_request_id": KEY4})):
            assert resp.status_code == 403 and resp.json()["code"] == "not_own_job"
    for resp in (signing.post("/api/phone/jobs/20261010-091503-4be1/cancel"),
                 signing.delete("/api/phone/jobs/20261010-091503-4be1"),
                 signing.post("/api/phone/jobs/20261010-091503-4be1/rerun",
                              json={"client_request_id": KEY4}),
                 signing.post("/api/phone/jobs/not-a-job/cancel")):
        assert resp.status_code == 404 and resp.json()["code"] == "unknown_job"


def test_delete_rules(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=forbidden_download)
    app, signing, device_id = phone_server(store)
    failed = link(signing).json()["job"]["id"]
    assert finished(store, failed).status == "error"
    media = write_silent_wav(tmp_path / "in" / "clip.wav")
    done = store.adopt_upload("clip.wav", lambda dest: dest.write_bytes(media.read_bytes()),
                              {}, device_id=device_id, client_request_id=KEY2,
                              origin=PHONE_ORIGIN)
    assert finished(store, done.id).status == "done"
    resp = signing.delete(f"/api/phone/jobs/{done.id}")
    assert resp.status_code == 409 and resp.json()["code"] == "not_deletable"
    assert resp.json()["detail"] == "Delete it on your computer."
    assert signing.delete(f"/api/phone/jobs/{failed}").status_code == 204
    assert not (store.root / failed).exists()
    again = signing.delete(f"/api/phone/jobs/{failed}")
    assert again.status_code == 404 and again.json()["code"] == "unknown_job"


def test_queued_and_running_jobs_are_not_deletable(phone_server, tmp_path, blocking_analyze):
    store = JobStore(tmp_path / "ws", analyze_fn=blocking_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=lambda url, d, **k: (
                         write_silent_wav(Path(d) / "clip [x].wav")))
    try:
        app, signing, _ = phone_server(store)
        running = link(signing, key=KEY1).json()["job"]["id"]
        assert blocking_analyze.started.wait(5)
        queued = link(signing, "https://youtu.be/second00001", key=KEY2).json()["job"]["id"]
        for job_id in (running, queued):
            resp = signing.delete(f"/api/phone/jobs/{job_id}")
            assert resp.status_code == 409 and resp.json()["code"] == "not_deletable"
            resp = signing.post(f"/api/phone/jobs/{job_id}/rerun",
                                json={"client_request_id": KEY3})
            assert resp.status_code == 409 and resp.json()["code"] == "not_rerunnable"
    finally:
        blocking_analyze.release.set()


# --- re-run (6.7) --------------------------------------------------------------------------

def test_try_audio_only_re_runs_a_failed_link(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=forbidden_download)
    app, signing, device_id = phone_server(store)
    old = link(signing).json()["job"]["id"]
    assert finished(store, old).status == "error"
    events = []
    store.subscribe(lambda event, job_id: events.append((event, job_id)))
    resp = signing.post(f"/api/phone/jobs/{old}/rerun",
                        json={"client_request_id": KEY2, "audio_only": True})
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["replaced"] == old
    new = body["job"]
    assert new["rerun_of"] == old and new["client_request_id"] == KEY2
    assert new["options"]["audio_only"] is True and new["own"] is True
    assert new["source"]["url"] == "https://youtu.be/abc123def45"
    assert ("deleted", old) in events                           # one row on the phone
    stored = store.get(new["id"])
    assert stored.origin == PHONE_ORIGIN and stored.device_id == device_id
    # the repeated key returns the job it created, although the old one is gone
    again = signing.post(f"/api/phone/jobs/{old}/rerun",
                         json={"client_request_id": KEY2, "audio_only": True})
    assert again.status_code == 200
    assert again.json() == {"job": again.json()["job"], "replaced": old}
    assert again.json()["job"]["id"] == new["id"]
    assert len(store.list()) == 1
    # the same key against another job is a conflict
    resp = signing.post(f"/api/phone/jobs/{new['id']}/rerun", json={"client_request_id": KEY2})
    assert resp.status_code == 409 and resp.json()["code"] == "key_conflict"


def test_a_re_run_checks_the_link_again(phone_server, tmp_path, fake_resolver):
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=forbidden_download)
    app, signing, _ = phone_server(store)
    old = link(signing, "https://public.example/v").json()["job"]["id"]
    assert finished(store, old).status == "error"
    fake_resolver.table["public.example"] = ["10.1.2.3"]  # hygiene: test-vector
    resp = signing.post(f"/api/phone/jobs/{old}/rerun", json={"client_request_id": KEY2})
    assert resp.status_code == 400 and resp.json()["code"] == "refused_address"
    del fake_resolver.table["public.example"]
    resp = signing.post(f"/api/phone/jobs/{old}/rerun", json={"client_request_id": KEY2})
    assert resp.status_code == 503 and resp.json()["code"] == "cannot_check_link"
    assert [j.id for j in store.list()] == [old]                # the old job is kept


def _failed_upload(store, device_id, tmp_path, key, name="bad.wav"):
    media = write_silent_wav(tmp_path / "in" / name)
    job = store.adopt_upload(name, lambda dest: dest.write_bytes(media.read_bytes()), {},
                             device_id=device_id, client_request_id=key, origin=PHONE_ORIGIN)
    assert finished(store, job.id).status == "error"
    return job


def test_try_again_moves_the_stored_upload_into_the_new_job(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=analyze_failing_on_bad, corpus=None)
    app, signing, device_id = phone_server(store)
    old = _failed_upload(store, device_id, tmp_path, KEY1)
    inode = os.stat(old.media_path).st_ino
    resp = signing.post(f"/api/phone/jobs/{old.id}/audio_only", json={})
    assert resp.status_code == 404                               # not a route
    resp = signing.post(f"/api/phone/jobs/{old.id}/rerun",
                        json={"client_request_id": KEY2, "audio_only": True})
    assert resp.status_code == 400 and resp.json()["code"] == "invalid_option"
    resp = signing.post(f"/api/phone/jobs/{old.id}/rerun", json={"client_request_id": KEY2})
    assert resp.status_code == 201, resp.text
    new = store.get(resp.json()["job"]["id"])
    assert new.rerun_of == old.id
    assert Path(new.media_path).parent == (store.root / new.id / "media").resolve()
    assert os.stat(new.media_path).st_ino == inode               # moved, not copied
    assert not (store.root / old.id).exists()
    assert resp.json()["job"]["source"]["kind"] == "upload"
    assert resp.json()["job"]["source"]["file_name"] == "bad.wav"


def test_a_re_run_whose_file_is_gone_keeps_the_old_job(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=analyze_failing_on_bad, corpus=None)
    app, signing, device_id = phone_server(store)
    old = _failed_upload(store, device_id, tmp_path, KEY1)
    Path(old.media_path).unlink()
    resp = signing.post(f"/api/phone/jobs/{old.id}/rerun", json={"client_request_id": KEY2})
    assert resp.status_code == 410 and resp.json()["code"] == "source_gone"
    assert store.get(old.id).status == "error"
    assert signing.get(f"/api/phone/requests/{KEY2}").status_code == 404


def test_a_done_job_is_not_re_run(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=fake_analyze, corpus=None)
    app, signing, device_id = phone_server(store)
    media = write_silent_wav(tmp_path / "in" / "fine.wav")
    job = store.adopt_upload("fine.wav", lambda dest: dest.write_bytes(media.read_bytes()), {},
                             device_id=device_id, client_request_id=KEY1, origin=PHONE_ORIGIN)
    assert finished(store, job.id).status == "done"
    resp = signing.post(f"/api/phone/jobs/{job.id}/rerun", json={"client_request_id": KEY2})
    assert resp.status_code == 409 and resp.json()["code"] == "not_rerunnable"


def test_a_key_used_by_a_link_cannot_re_run(phone_server, tmp_path):
    store = JobStore(tmp_path / "ws", analyze_fn=analyze_failing_on_bad, corpus=None,
                     download_dir=tmp_path / "dl", download_fn=forbidden_download)
    app, signing, device_id = phone_server(store)
    old = _failed_upload(store, device_id, tmp_path, KEY1)
    assert link(signing, key=KEY2).status_code == 201
    resp = signing.post(f"/api/phone/jobs/{old.id}/rerun", json={"client_request_id": KEY2})
    assert resp.status_code == 409 and resp.json()["code"] == "key_conflict"


# --- a restart (S-15, QT-5, QT-12) ------------------------------------------------------------

def test_a_restart_interrupts_and_re_runs_keep_the_arrival_order(phone_settings, tmp_path,
                                                                 phone_server, blocking_analyze):
    workspace = tmp_path / "ws"
    first = JobStore(workspace, analyze_fn=blocking_analyze, corpus=None)
    try:
        app, signing, device_id = phone_server(first)
        jobs = []
        for index, key in enumerate((KEY1, KEY2, KEY3)):
            media = write_silent_wav(tmp_path / "in" / f"clip{index}.wav")
            jobs.append(first.adopt_upload(
                media.name, lambda dest, m=media: dest.write_bytes(m.read_bytes()), {},
                device_id=device_id, client_request_id=key, origin=PHONE_ORIGIN))
        assert blocking_analyze.started.wait(5)
        statuses = [first.get(j.id).status for j in jobs]
        assert statuses == ["running", "queued", "queued"]
        # the process dies here: what survives is the workspace as it is now
        import shutil

        shutil.copytree(workspace, tmp_path / "after-restart")
    finally:
        blocking_analyze.release.set()
        first.shutdown()
    second = JobStore(tmp_path / "after-restart", analyze_fn=blocking_analyze, corpus=None)
    for job in jobs:
        reloaded = second.get(job.id)
        assert reloaded.status == "error"
        assert reloaded.error.startswith("Interrupted: the server stopped")
    from phonotrainer.phone.state import classify_error

    assert {classify_error(second.get(j.id).status, second.get(j.id).error) for j in jobs} \
        == {"interrupted"}
    blocking_analyze.release.clear()
    blocking_analyze.started.clear()
    try:
        new_ids = [second.rerun(j.id, device_id, key).id
                   for j, key in zip(jobs, (KEY4, KEY5, "00000000-0000-4000-8000-000000000006"))]
        assert blocking_analyze.started.wait(5)
        positions = second.queue_positions()
        assert second.get(new_ids[0]).status == "running"
        assert [positions.get(i) for i in new_ids[1:]] == [1, 2]   # the original order
        assert [second.get(i).rerun_of for i in new_ids] == [j.id for j in jobs]
        assert len(second.list()) == 3                              # no duplicate
    finally:
        blocking_analyze.release.set()
        second.shutdown()
