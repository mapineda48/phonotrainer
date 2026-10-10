import json
import sys
import time
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def mk_phones(spaced: str, t0: float = 0.0, dur: float = 0.06) -> list[dict]:
    """'d ə z' → a list of phone dicts with synthetic consecutive timings."""
    phones = []
    t = t0
    for p in spaced.split():
        phones.append({"phone": p, "start": round(t, 3), "end": round(t + dur, 3), "score": 1.0})
        t += dur
    return phones


def mk_word(text: str, canonical: str, real: str, t0: float = 0.0, dur: float = 0.06,
            real_dur: float | None = None, dict_arpabet: list[str] | None = None) -> dict:
    """Build the word entry that phenomena.detect() consumes."""
    can = mk_phones(canonical, t0, dur)
    rea = mk_phones(real, t0, real_dur if real_dur is not None else dur)
    end = max([p["end"] for p in can + rea], default=t0)
    word = {
        "word": text,
        "start": t0,
        "end": round(end, 3),
        "canonical": can,
        "real": rea,
    }
    if dict_arpabet is not None:
        word["dict_arpabet"] = dict_arpabet
    return word


# --- fixtures for the web interface (jobs + API) -----------------------------
# The real pipeline loads ~1.8 GB of models: the server tests use this double,
# which writes the same artifacts to disk from synthetic data.

def mk_analysis_word(word: str, t0: float, canonical: str, realized: str,
                     **extra) -> dict:
    """A word shaped exactly the way pipeline.analyze writes it into analysis.json."""
    def aligned(ipa: str, start: float) -> list[list]:
        phones, t = [], start
        for p in ipa.split():
            phones.append([p, round(t, 3), round(t + 0.05, 3)])
            t += 0.06
        return phones

    can, real = aligned(canonical, t0), aligned(realized, t0)
    end = max((p[2] for p in can + real), default=t0 + 0.05)
    entry = {
        "word": word, "start": round(t0, 3), "end": round(end, 3),
        "canonical_ipa": canonical.replace(" ", ""), "canonical_aligned": can,
        "realized_ipa": realized.replace(" ", ""), "realized_aligned": real,
        "realized_raw_ipa": "", "attracted_count": 0, "diff_cost": 0.0,
        "dict_ipa": canonical.replace(" ", ""), "oov": False, "phenomena": [],
        "low_confidence": False, "boundary_link_next": False,
        "lexical_form": None, "alignment_fallback": False,
    }
    entry.update(extra)
    return entry


def mk_analysis(source: str = "clip.wav") -> dict:
    """A minimal yet complete analysis.json (2 segments, phenomena and prosody)."""
    seg0 = {
        "start": 0.0, "end": 1.2, "text": "does that work",
        "f0_stats": {"mean": 118.0, "range": 62.0, "final_contour": "rising"},
        "f0_track": [[round(0.1 * i, 3), 110.0 + i] for i in range(12)],
        "emphasis_word_idx": 2,
        "prominence": [0.4, 0.2, 1.0],
        "word_classes": ["function", "function", "content"],
        "intonation_units": [{
            "start": 0.0, "end": 1.1, "words": [0, 2], "text": "does that work",
            "type": "incomplete", "final_contour": "rising", "final_slope_st": 5.2,
            "expected_contour": None, "matches_expected": None, "uptalk": False,
        }],
        "rhythm": None,
        "words": [
            mk_analysis_word("does", 0.0, "d ʌ z", "d ə z",
                             phenomena=["vowel_reduction"], boundary_link_next=True),
            mk_analysis_word("that", 0.4, "ð æ t", "ð æ",
                             phenomena=["t_deletion"], diff_cost=0.8),
            mk_analysis_word("work", 0.8, "w ɝ k", "w ɝ k"),
        ],
    }
    seg1 = {
        "start": 2.0, "end": 3.0, "text": "wanna go",
        "f0_stats": {"mean": 130.0, "range": 20.0, "final_contour": "falling"},
        "f0_track": [[round(2.0 + 0.1 * i, 3), 130.0 - i] for i in range(10)],
        "emphasis_word_idx": 0,
        "prominence": [1.0, 0.6],
        "word_classes": ["function", "content"],
        "intonation_units": [{
            "start": 2.0, "end": 2.9, "words": [0, 1], "text": "wanna go",
            "type": "incomplete", "final_contour": "falling", "final_slope_st": -4.1,
            "expected_contour": None, "matches_expected": None, "uptalk": False,
        }],
        "rhythm": None,
        "words": [
            mk_analysis_word("wanna", 2.0, "w ɑ n ə", "w ɑ n ə",
                             phenomena=["contraction_lex"], lexical_form="want to",
                             attracted_count=1, realized_raw_ipa="wɑnə"),
            mk_analysis_word("go", 2.6, "ɡ oʊ", "ɡ oʊ", low_confidence=True),
        ],
    }
    return {
        "meta": {
            "source": source, "duration": 3.0, "language": "en",
            "phonotrainer_version": "0.1.0",
            "models": {"asr": "faster-whisper small (int8)",
                       "phones": "facebook/wav2vec2-lv-60-espeak-cv-ft",
                       "alignment": "torchaudio forced_align"},
            "attraction": True,
            "phone_cleanup": {"normalized_phones": 1, "attracted_phones": 1},
        },
        "segments": [seg0, seg1],
        "summary": {"phenomena_counts": {"vowel_reduction": 1, "t_deletion": 1,
                                         "contraction_lex": 1}},
    }


def write_silent_wav(path: Path, seconds: float = 1.0, rate: int = 16000) -> Path:
    """A real mono WAV (no numpy involved) for exercising Range serving."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as fh:
        fh.setnchannels(1)
        fh.setsampwidth(2)
        fh.setframerate(rate)
        fh.writeframes(b"\x00\x00" * int(rate * seconds))
    return path


def fake_analyze(media_path, out_dir, progress=lambda m: None, **options):
    """Double for `pipeline.analyze`: same artifacts, no models.

    The progress strings must match `pipeline.analyze` word for word: jobs.py parses
    them to derive the completion percentage.
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    progress("Extracting audio (ffmpeg → 16 kHz mono WAV)…")
    write_silent_wav(out_dir / "audio.wav")
    # like separation.prepare: the dialogue track exists only when this run made it
    (out_dir / "audio_dialogue.wav").unlink(missing_ok=True)
    if options.get("separate_dialogue"):
        progress("Separating dialogue from music and effects (htdemucs)…")
        write_silent_wav(out_dir / "audio_dialogue.wav", seconds=0.5)
        dialogue = {"applied": True, "model": "htdemucs", "audio": "audio_dialogue.wav"}
    else:
        dialogue = {"applied": False}
    progress("Transcribing with faster-whisper small…")
    progress("Segment 1/2: phones + alignment…")
    progress("Segment 2/2: phones + alignment…")
    analysis = mk_analysis(source=Path(media_path).name)
    analysis["meta"]["options"] = options
    analysis["meta"]["dialogue_separation"] = dialogue
    progress("Saving outputs…")
    (out_dir / "analysis.json").write_text(json.dumps(analysis, ensure_ascii=False),
                                           encoding="utf-8")
    progress("Generating report.html…")
    (out_dir / "report.html").write_text("<html>fake</html>", encoding="utf-8")
    return analysis


def wait_until(predicate, timeout: float = 10.0, interval: float = 0.02):
    """Short busy-wait for jobs that run on another thread."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError(f"condition not met within {timeout}s")


# --- phone access (phonotrainer/phone, server-contract 11.2) ------------------
# Every test runs with PHONOTRAINER_CONFIG_DIR in a temporary directory, so no
# test can ever read or write a real configuration directory.

import pytest  # noqa: E402

from phone_fixtures import (FakeClock, FakeDisk, FakeNetinfo, FakeProbe,  # noqa: E402
                            FakeResolver, PT1Auth, fake_which, no_runner)


@pytest.fixture(autouse=True)
def isolated_config(tmp_path_factory, monkeypatch):
    path = tmp_path_factory.mktemp("phone-config") / "config"
    monkeypatch.setenv("PHONOTRAINER_CONFIG_DIR", str(path))
    return path


# What serve() writes into the environment (for `uvicorn --reload`'s factory).
SERVE_ENV = ("PHONOTRAINER_WORKSPACE", "PHONOTRAINER_ROOTS", "PHONOTRAINER_PHONE_ACCESS",
             "PHONOTRAINER_PORT", "PHONOTRAINER_CONFIG_DIR")


@pytest.fixture
def uvicorn_calls(monkeypatch):
    """`uvicorn.run` recorded instead of run: a list of (app, keyword arguments).
    The environment serve() writes is restored after the test."""
    import os

    import uvicorn

    for name in SERVE_ENV:
        monkeypatch.setenv(name, os.environ.get(name, ""))
    calls = []
    monkeypatch.setattr(uvicorn, "run", lambda app, **kw: calls.append((app, kw)))
    return calls


@pytest.fixture
def fake_clock():
    return FakeClock()


@pytest.fixture
def fake_netinfo():
    return FakeNetinfo()


@pytest.fixture
def fake_probe():
    return FakeProbe()


@pytest.fixture
def fake_disk():
    return FakeDisk()


@pytest.fixture
def fake_resolver():
    return FakeResolver()


@pytest.fixture
def phone_settings(isolated_config, fake_clock, fake_netinfo, fake_probe, fake_disk,
                   fake_resolver):
    from phonotrainer.phone.settings import PhoneSettings

    return PhoneSettings(config_dir=isolated_config, clock=fake_clock, resolver=fake_resolver,
                         media_probe=fake_probe, disk_usage=fake_disk, netinfo=fake_netinfo,
                         which=fake_which, runner=no_runner, discovery_port=0,
                         discovery_host="127.0.0.1")


def phone_download(url, dest_dir, audio_only=False, progress=lambda m: None, **kwargs):
    """Download double: the phone tests never reach a website."""
    progress("Looking up the URL…")
    progress("Downloading from YouTube… 100%")
    dest = Path(dest_dir)
    dest.mkdir(parents=True, exist_ok=True)
    file = dest / "A video [abc123].mp4"
    file.write_bytes(b"media")
    progress(f"Downloaded: {file.name}")
    return file


@pytest.fixture
def phone_store(tmp_path):
    from phonotrainer.jobs import JobStore

    store = JobStore(tmp_path / "workspace", analyze_fn=fake_analyze, corpus=None,
                     download_dir=tmp_path / "downloads", download_fn=phone_download)
    yield store
    store.shutdown()


def enable_phone_access(settings) -> None:
    """What `phonotrainer phone on` does: phone access on, current networks approved."""
    from phonotrainer.phone.identity import enable_phone_access as enable

    enable(settings)


@pytest.fixture
def phone_app(phone_store, phone_settings, tmp_path):
    """An app with phone access on, its lifespan running."""
    from fastapi.testclient import TestClient

    from phonotrainer.server import create_app

    enable_phone_access(phone_settings)
    app = create_app(store=phone_store, phone=phone_settings,
                     web_dist=tmp_path / "not-built", allowed_roots=[tmp_path])
    with TestClient(app):
        yield app


def lan(app, client=("192.0.2.20", 50000), base_url="http://192.0.2.10:8000"):
    from fastapi.testclient import TestClient

    return TestClient(app, client=client, base_url=base_url)


def loopback(app, base_url="http://127.0.0.1:8000"):
    from fastapi.testclient import TestClient

    return TestClient(app, client=("127.0.0.1", 50000), base_url=base_url)


@pytest.fixture
def lan_client(phone_app):
    return lan(phone_app)


@pytest.fixture
def loopback_client(phone_app):
    return loopback(phone_app)


def pair_phone(app, client, name="Pixel 9", **extra):
    """Pair through the API with a fresh code; returns (response, code)."""
    code = app.state.phone.registry.create_code().code
    body = {"code": code, "device_name": name, "app_version": 1, "app_version_name": "1.0.0"}
    body.update(extra)
    return client.post("/api/phone/pair", json=body), code


@pytest.fixture
def paired(phone_app, lan_client, fake_clock):
    """(signing_client, device_id, key): a paired phone on the LAN whose every
    request carries a fresh signature."""
    from phonotrainer.phone.registry import token_key

    resp, _ = pair_phone(phone_app, lan_client)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    key = token_key(body["token"])
    signing = lan(phone_app)
    signing.auth = PT1Auth(body["device_id"], key, fake_clock)
    return signing, body["device_id"], key


@pytest.fixture
def blocking_analyze():
    """A fake analysis that holds the worker until `.release.set()`."""
    import threading

    release = threading.Event()
    started = threading.Event()

    def analyze(media_path, out_dir, progress=lambda m: None, **options):
        started.set()
        release.wait(timeout=30)
        return fake_analyze(media_path, out_dir, progress=progress, **options)

    analyze.release = release
    analyze.started = started
    return analyze


def paired_client(app, clock, name="Pixel 9"):
    """Pair one more phone through the API; returns (signing client, device id)."""
    from phonotrainer.phone.registry import token_key

    client = lan(app)
    resp, _ = pair_phone(app, client, name=name)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    client.auth = PT1Auth(body["device_id"], token_key(body["token"]), clock)
    return client, body["device_id"]


@pytest.fixture
def phone_server(phone_settings, tmp_path):
    """A factory for an app with phone access on around a store the test
    builds: `make(store)` → (app, signing client, device id). Every app's
    lifespan runs until the test ends; every store is shut down."""
    from fastapi.testclient import TestClient

    from phonotrainer.server import create_app

    opened = []

    def make(store, name="Pixel 9"):
        enable_phone_access(phone_settings)
        app = create_app(store=store, phone=phone_settings, web_dist=tmp_path / "not-built",
                         allowed_roots=[tmp_path])
        lifespan = TestClient(app)
        lifespan.__enter__()
        opened.append((lifespan, store))
        signing, device_id = paired_client(app, phone_settings.clock, name)
        return app, signing, device_id

    yield make
    for lifespan, store in opened:
        lifespan.__exit__(None, None, None)
        store.shutdown()
