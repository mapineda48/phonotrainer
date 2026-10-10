#!/usr/bin/env python3
"""The host-side fake computer: the real PhonoTrainer server with fake analyses and downloads (server-contract 12).

Every [emulator] step of device QA, the emulator smoke test and the R8 smoke run against this, never against the real
computer (architecture 21.6 and 21.7, delivery review D-09). It builds the server from this checkout's own code
(`create_app`, `JobStore`, `PhoneSettings`) with:

- a synthetic analysis writer instead of the pipeline: an `analysis.json` shaped like the pipeline's (and like
  `tests/conftest.py`'s `mk_analysis`), a silent `audio.wav` (and a silent dialogue track when one is asked for), and
  the pipeline's progress strings spread over about a second (`--analysis-seconds`), so a cancel lands promptly; an
  uploaded file stays the job's original;
- a downloader that refuses every link ("downloads are disabled on the fake computer") and a name resolver that never
  asks DNS: no link can ever reach a site, whatever a test does (spec D-36);
- throwaway workspace, configuration and working directories inside one fresh temporary directory, which is also the
  process's working directory and is deleted at exit: nothing is written where it was started, nor in the repository
  (no bytecode either);
- phone access on, and one finished synthetic clip seeded through the store (not through HTTP), whose original is a
  short synthetic vertical video when ffmpeg is installed (a silent WAV otherwise).

By default it listens on 127.0.0.1, which the emulator reaches as 10.0.2.2:<port>. `--lan` serves the test phone on
this computer's approved Wi-Fi address instead (WP-25), with discovery on its usual port.

Standard output, one line each, for a harness (the pairing code goes nowhere else, never to a file):

    throwaway directory: <its path>
    listening: 127.0.0.1:41234
    emulator: 10.0.2.2:41234
    seeded clip: <job id>
    pairing code: 482913 (valid for 5 minutes, single use)
    ready

Standard input: `pair` prints a new pairing code, `status` the jobs, `quit` stops (so do Ctrl-C, SIGTERM and, on a
terminal, Ctrl-D).

Usage: host_computer.py [--port 0] [--analysis-seconds 1] [--lan [--interface NAME]] [--parent DIR] [--python PATH]
       host_computer.py --self-check   starts one, runs a phone session against it over HTTP, stops it, checks cleanup

It needs the repository's virtual environment. Started with another interpreter it finds one (`--python`, then
`PHONOTRAINER_PYTHON`, then `.venv` in this checkout or in the main checkout of its git worktrees) and runs again with
it, with `PYTHONPATH` set to this checkout, so it always serves this checkout's server code.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import http.client
import importlib
import importlib.util
import json
import os
import queue
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

SCRIPT = Path(__file__).resolve()
HERE = SCRIPT.parent
REPO_ROOT = SCRIPT.parents[3]

REFUSAL = "downloads are disabled on the fake computer"
LOOPBACK = "127.0.0.1"
EMULATOR_HOST = "10.0.2.2"          # the emulator's alias of this computer's loopback
# Loopback mode approves this documentation-range network, which exists nowhere: phone access is on (hello answers in
# full), yet the server listens on loopback only, so no LAN client can reach it.
LOOPBACK_INTERFACE = ("fake0", "192.0.2.10", 24)
# The offline resolver's answer for every name: a global address nothing ever connects to, because the downloader
# refuses before any connection. The SSRF guard accepts a link only when every answer is global.
OFFLINE_ANSWER = "192.88.99.1"  # hygiene: test-vector
# A link that names no real site; only ever sent to the fake computer.
SELF_CHECK_LINK = "https://video.example/never-fetched"
SEED_NAME = "Seeded synthetic clip"
SEED_SECONDS = 4.0
DEFAULT_ANALYSIS_SECONDS = 1.0
SERVER_MODULES = ("fastapi", "uvicorn")
REEXEC_MARK = "PHONOTRAINER_FAKE_COMPUTER_REEXEC"
TMP_PREFIX = "phonotrainer-fake-computer-"


# --- throwaway directories -------------------------------------------------------------------------------------------

@dataclass
class Dirs:
    base: Path
    workspace: Path
    config: Path
    seed: Path
    cwd: Path


def inside(path: Path, root: Path) -> bool:
    try:
        Path(path).resolve().relative_to(Path(root).resolve())
        return True
    except ValueError:
        return False


def make_dirs(parent: str | Path | None = None, repo: Path = REPO_ROOT) -> Dirs:
    """A fresh temporary directory with the workspace, seed and working directories (the configuration directory is
    created by the server itself, with its own permission rules). Never inside the repository."""
    base = Path(tempfile.mkdtemp(prefix=TMP_PREFIX, dir=parent)).resolve()
    if inside(base, repo):
        shutil.rmtree(base, ignore_errors=True)
        raise RuntimeError("the throwaway directory must be outside the repository")
    dirs = Dirs(base, base / "workspace", base / "config", base / "seed", base / "cwd")
    for path in (dirs.workspace, dirs.seed, dirs.cwd):
        path.mkdir(mode=0o700)
    return dirs


def remove(dirs: Dirs) -> bool:
    """Delete the throwaway directory; True when it is gone."""
    for _ in range(3):
        shutil.rmtree(dirs.base, ignore_errors=True)
        if not dirs.base.exists():
            return True
        time.sleep(0.5)               # a worker thread was still finishing a write
    return False


# --- the interpreter -------------------------------------------------------------------------------------------------

def has_server_modules() -> bool:
    return all(importlib.util.find_spec(name) is not None for name in SERVER_MODULES)


def can_serve(python: str) -> bool:
    """True when `python` can import the server's web stack."""
    try:
        return subprocess.run([python, "-I", "-c", "import " + ", ".join(SERVER_MODULES)],
                              capture_output=True, timeout=60).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def main_checkout(repo: Path = REPO_ROOT) -> Path | None:
    """The main checkout of this repository's worktrees (where `.venv` usually lives), from git's common directory."""
    try:
        result = subprocess.run(["git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return None
    common = Path(result.stdout.strip()) if result.returncode == 0 else None
    return common.parent if common is not None and common.name == ".git" else None


def find_python(explicit: str | None = None, environ: dict | None = None, repo: Path = REPO_ROOT,
                check: Callable[[str], bool] = can_serve, current: str | None = None,
                current_ok: Callable[[], bool] = has_server_modules,
                checkout: Callable[[Path], Path | None] = main_checkout) -> str | None:
    """The interpreter to serve with: an explicit choice (`--python`, then `PHONOTRAINER_PYTHON`) is used or refused,
    never replaced; otherwise this interpreter when it has the web stack, then `.venv` in this checkout, then in the
    main checkout."""
    environ = os.environ if environ is None else environ
    chosen = explicit or environ.get("PHONOTRAINER_PYTHON")
    if chosen:
        return chosen if check(chosen) else None
    if current_ok():
        return current or sys.executable
    for root in (repo, checkout(repo)):
        if root is None:
            continue
        candidate = root / ".venv" / "bin" / "python"
        if candidate.exists() and check(str(candidate)):
            return str(candidate)
    return None


def server_environment(base: dict | None = None, repo: Path = REPO_ROOT) -> dict[str, str]:
    """The environment the server runs with: this checkout first on the import path, no bytecode written."""
    env = dict(os.environ if base is None else base)
    env["PYTHONPATH"] = os.pathsep.join([str(repo)] + ([env["PYTHONPATH"]] if env.get("PYTHONPATH") else []))
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


def repo_module(name: str, repo: Path = REPO_ROOT):
    """Import a module of this checkout's server without writing bytecode into the repository."""
    if str(repo) not in sys.path:
        sys.path.insert(0, str(repo))
    previous = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        return importlib.import_module(name)
    finally:
        sys.dont_write_bytecode = previous


# --- the fakes: downloads, names, analyses ---------------------------------------------------------------------------

def refuse_download(url, dest_dir=None, *args, **kwargs):
    """The store's `download_fn`: every link is refused before any connection (spec D-36)."""
    raise repo_module("phonotrainer.download").DownloadError(REFUSAL)


def offline_resolver(host: str) -> list[str]:
    """`PhoneSettings.resolver` without DNS: one global address for every name (a literal IP is never resolved; the
    SSRF guard checks it as it is)."""
    return [OFFLINE_ANSWER]


def write_silent_wav(path: Path, seconds: float, rate: int = 16000) -> Path:
    with wave.open(str(path), "wb") as fh:
        fh.setnchannels(1)
        fh.setsampwidth(2)
        fh.setframerate(rate)
        fh.writeframes(b"\x00\x00" * int(rate * seconds))
    return path


def _aligned(ipa: str, start: float) -> list[list]:
    phones, t = [], start
    for phone in ipa.split():
        phones.append([phone, round(t, 3), round(t + 0.05, 3)])
        t += 0.06
    return phones


def _word(word: str, t0: float, canonical: str, realized: str, **extra) -> dict:
    """A word shaped like the pipeline's (and `tests/conftest.py`'s `mk_analysis_word`)."""
    can, real = _aligned(canonical, t0), _aligned(realized, t0)
    entry = {
        "word": word, "start": round(t0, 3), "end": round(max(p[2] for p in can + real), 3),
        "canonical_ipa": canonical.replace(" ", ""), "canonical_aligned": can,
        "realized_ipa": realized.replace(" ", ""), "realized_aligned": real,
        "realized_raw_ipa": "", "attracted_count": 0, "diff_cost": 0.0,
        "dict_ipa": canonical.replace(" ", ""), "oov": False, "canonical_text": None, "no_canonical": False,
        "phenomena": [], "low_confidence": False, "boundary_link_next": False, "boundary_link_type": None,
        "lexical_form": None, "lexical_expansion": None, "alignment_fallback": False,
    }
    entry.update(extra)
    return entry


def _segment(start: float, end: float, text: str, words: list[dict], contour: str, classes: list[str],
             prominence: list[float]) -> dict:
    steps = int(round((end - start) / 0.1))
    rising = contour == "rising"
    return {
        "start": start, "end": end, "text": text,
        "f0_stats": {"mean": 120.0, "range": 40.0, "final_contour": contour},
        "f0_track": [[round(start + 0.1 * i, 3), 110.0 + (i if rising else -i)] for i in range(steps)],
        "emphasis_word_idx": prominence.index(max(prominence)),
        "prominence": prominence,
        "word_classes": classes,
        "intonation_units": [{
            "start": start, "end": round(end - 0.1, 3), "words": [0, len(words) - 1], "text": text,
            "type": "incomplete", "final_contour": contour, "final_slope_st": 4.0 if rising else -4.0,
            "expected_contour": None, "matches_expected": None, "uptalk": False,
        }],
        "rhythm": None,
        "words": words,
    }


def synthetic_analysis(source: str, duration: float, dialogue: dict, options: dict) -> dict:
    """A complete `analysis.json` of three short segments with a few connected-speech phenomena (synthetic data)."""
    segments = [
        _segment(0.2, 1.4, "does that work", [
            _word("does", 0.2, "d ʌ z", "d ə z", phenomena=["vowel_reduction"], boundary_link_next=True),
            _word("that", 0.6, "ð æ t", "ð æ", phenomena=["t_deletion"], diff_cost=0.8),
            _word("work", 1.0, "w ɝ k", "w ɝ k"),
        ], "rising", ["function", "function", "content"], [0.4, 0.2, 1.0]),
        _segment(1.8, 2.8, "wanna go", [
            _word("wanna", 1.8, "w ɑ n ə", "w ɑ n ə", phenomena=["contraction_lex"], lexical_form="want to"),
            _word("go", 2.4, "ɡ oʊ", "ɡ oʊ", low_confidence=True),
        ], "falling", ["function", "content"], [1.0, 0.6]),
        _segment(3.0, 3.8, "get it", [
            _word("get", 3.0, "ɡ ɛ t", "ɡ ɛ ɾ", phenomena=["flapping"], boundary_link_next=True,
                  boundary_link_type="consonant_vowel"),
            _word("it", 3.4, "ɪ t", "ɪ ʔ", phenomena=["glottalization"]),
        ], "falling", ["content", "function"], [1.0, 0.3]),
    ]
    counts: dict[str, int] = {}
    for segment in segments:
        for word in segment["words"]:
            for name in word["phenomena"]:
                counts[name] = counts.get(name, 0) + 1
    try:
        version = repo_module("phonotrainer").__version__
        rules = repo_module("phonotrainer.phenomena").RULES_VERSION
    except Exception:                                   # noqa: BLE001 — the shape matters, not the numbers
        version, rules = None, None
    analysis = {
        "meta": {
            "source": source, "duration": duration, "language": "en",
            "phonotrainer_version": version, "rules_version": rules, "phone_engine": "timit61",
            "models": {"asr": "faster-whisper small (int8)", "phones": "synthetic (host-side fake computer)",
                       "alignment": "synthetic"},
            "attraction": False, "dialogue_separation": dialogue, "form_scoring": None,
            "phone_cleanup": {"normalized_phones": 0, "attracted_phones": 0},
            "options": dict(options),
        },
        "segments": segments,
        "summary": {"phenomena_counts": counts},
    }
    try:
        analysis["summary"]["metrics"] = repo_module("phonotrainer.metrics").safe_compute(analysis)
    except Exception:                                   # noqa: BLE001
        pass
    return analysis


class SyntheticAnalysis:
    """The store's `analyze_fn`: the pipeline's artifacts and progress strings, no models.

    The steps are spread over `seconds`; each one calls `progress`, which raises once a cancel is requested, so a
    cancel lands within one step. The job directory is created without parents, so a deleted workspace is never
    re-created by a late step."""

    def __init__(self, seconds: float = DEFAULT_ANALYSIS_SECONDS, duration: float = SEED_SECONDS,
                 sleep: Callable[[float], None] = time.sleep) -> None:
        self.seconds = max(0.0, float(seconds))
        self.duration = duration
        self.sleep = sleep

    def __call__(self, media_path, out_dir, progress=lambda message: None, **options) -> dict:
        out_dir = Path(out_dir)
        out_dir.mkdir(exist_ok=True)
        separate = bool(options.get("separate_dialogue"))
        segments = 3
        steps = (["Extracting audio (ffmpeg → 16 kHz mono WAV)…"]
                 + (["Separating dialogue from music and effects (htdemucs)…"] if separate else [])
                 + [f"Transcribing with faster-whisper {options.get('whisper_model') or 'small'}…",
                    f"Loading phone engine ({options.get('phone_engine') or 'timit61'})…",
                    "Loading prosody (parselmouth)…"]
                 + [f"Segment {i}/{segments}: phones + alignment…" for i in range(1, segments + 1)]
                 + ["Saving outputs…", "Generating report.html…"])
        pause = self.seconds / len(steps)
        (out_dir / "audio_dialogue.wav").unlink(missing_ok=True)   # only this run's dialogue track counts
        dialogue = {"applied": False}
        analysis = None
        for message in steps:
            progress(message)
            if pause:
                self.sleep(pause)
            if message.startswith("Extracting audio"):
                write_silent_wav(out_dir / "audio.wav", self.duration)
            elif message.startswith("Separating dialogue"):
                write_silent_wav(out_dir / "audio_dialogue.wav", self.duration)
                dialogue = {"applied": True, "model": "htdemucs", "audio": "audio_dialogue.wav"}
            elif message.startswith("Saving outputs"):
                analysis = synthetic_analysis(Path(str(media_path)).name, self.duration, dialogue, options)
                (out_dir / "analysis.json").write_text(json.dumps(analysis, ensure_ascii=False), encoding="utf-8")
            elif message.startswith("Generating report"):
                (out_dir / "report.html").write_text(
                    "<!doctype html><title>Synthetic analysis</title><p>Made by the host-side fake computer.</p>",
                    encoding="utf-8")
        return analysis


def make_seed_media(seed_dir: Path, seconds: float = SEED_SECONDS, run=subprocess.run,
                    which: Callable[[str], str | None] = shutil.which) -> Path:
    """The seeded clip's original: a synthetic vertical H.264 video with a silent track (ffmpeg's `testsrc2` and
    `anullsrc`), or a silent WAV when ffmpeg is missing or can encode none of the codecs tried."""
    ffmpeg = which("ffmpeg")
    if ffmpeg:
        video = seed_dir / f"{SEED_NAME}.mp4"
        for encoder in ("libx264", "libopenh264", "mpeg4"):
            command = [ffmpeg, "-nostdin", "-v", "error", "-y",
                       "-f", "lavfi", "-i", f"testsrc2=size=360x640:rate=30:duration={seconds}",
                       "-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=48000",
                       "-t", str(seconds), "-c:v", encoder, "-b:v", "600k", "-pix_fmt", "yuv420p",
                       "-c:a", "aac", "-b:a", "64k", "-shortest", "-movflags", "+faststart", str(video)]
            try:
                done = run(command, capture_output=True, timeout=120, stdin=subprocess.DEVNULL)
            except (OSError, subprocess.TimeoutExpired):
                continue
            if done.returncode == 0 and video.is_file() and video.stat().st_size > 0:
                return video
            video.unlink(missing_ok=True)
    return write_silent_wav(seed_dir / f"{SEED_NAME}.wav", seconds)


# --- the server ------------------------------------------------------------------------------------------------------

class Console:
    """One line at a time on standard output, flushed (a harness reads it through a pipe)."""

    def __init__(self, stream=None) -> None:
        self.stream = stream
        self.lock = threading.Lock()

    def say(self, line: str) -> None:
        with self.lock:
            print(line, file=self.stream or sys.stdout, flush=True)


def wait_for_job(store, job_id: str, statuses: set[str], timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = store.get(job_id).status
        if status in statuses:
            return status
        time.sleep(0.05)
    raise RuntimeError(f"job {job_id} did not reach {sorted(statuses)} within {timeout:.0f} s")


def status_line(store) -> str:
    jobs = store.list()
    counts: dict[str, int] = {}
    for job in jobs:
        counts[job.status] = counts.get(job.status, 0) + 1
    detail = ", ".join(f"{name} {counts[name]}" for name in sorted(counts))
    return f"jobs: {len(jobs)}" + (f" ({detail})" if detail else "")


def bind_socket(host: str, port: int) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))
    sock.set_inheritable(True)
    return sock


def _stop_on_sigterm(signum, frame):
    raise KeyboardInterrupt


def serve(args, console: Console) -> int:
    """Run the fake computer until `quit`, Ctrl-C or SIGTERM; always delete the throwaway directory."""
    try:
        start_dir = Path.cwd()
    except FileNotFoundError:
        start_dir = Path("/")
    dirs = make_dirs(args.parent)
    console.say(f"throwaway directory: {dirs.base}")
    os.environ["PHONOTRAINER_CONFIG_DIR"] = str(dirs.config)
    os.chdir(dirs.cwd)
    store = None
    previous_sigterm = signal.signal(signal.SIGTERM, _stop_on_sigterm)
    try:
        store, server, sock = build(args, dirs, console)
        try:
            server.run(sockets=[sock])
        finally:
            sock.close()
    except KeyboardInterrupt:
        pass
    finally:
        signal.signal(signal.SIGTERM, previous_sigterm)
        if store is not None:
            store.shutdown(wait=True)     # the worker stops at its next step, before the directory goes
        try:
            os.chdir(start_dir)
        except OSError:
            os.chdir("/")
        gone = remove(dirs)
        console.say("stopped: the throwaway directory was deleted" if gone
                    else "stopped: the throwaway directory could not be deleted")
    return 0 if gone else 1


def import_server(repo: Path = REPO_ROOT):
    """This checkout's server, first on the import path; the device-QA scripts beside this file are not."""
    here = str(HERE)
    sys.path[:] = [entry for entry in sys.path if os.path.abspath(entry or ".") != here]
    sys.path.insert(0, str(repo))
    sys.dont_write_bytecode = True
    package = importlib.import_module("phonotrainer")
    if not inside(Path(package.__file__), repo):
        raise RuntimeError("phonotrainer was imported from another checkout; run with PYTHONPATH set to this one")


def build(args, dirs: Dirs, console: Console):
    import_server()
    import uvicorn

    from phonotrainer.jobs import CANCELLED, DONE, ERROR, JobStore
    from phonotrainer.phone import DISCOVERY_PORT
    from phonotrainer.phone.config import ConfigDir
    from phonotrainer.phone.identity import ServerIdentity, active_addresses, enable_phone_access
    from phonotrainer.phone.netinfo import Interface, interfaces as host_interfaces
    from phonotrainer.phone.registry import DeviceRegistry
    from phonotrainer.phone.settings import PhoneSettings
    from phonotrainer.server import create_app

    if args.lan:
        netinfo = host_interfaces
        settings = PhoneSettings(config_dir=dirs.config, resolver=offline_resolver, netinfo=netinfo)
        enable_phone_access(settings, (args.interface,) if args.interface else ())
        identity = ServerIdentity(ConfigDir(dirs.config), time.time)
        identity.load()
        active = [iface for iface in active_addresses(identity.approved_networks, netinfo())
                  if args.interface in (None, iface.name)]
        if not active:
            raise SystemExit("No approved Wi-Fi network is up" + (f" on {args.interface}" if args.interface else "")
                             + ": --lan needs this computer on the phone's Wi-Fi.")
        host, label, discovery = active[0].address, active[0].name, DISCOVERY_PORT
    else:
        fake = Interface(*LOOPBACK_INTERFACE)
        netinfo = lambda: [fake]                        # noqa: E731
        settings = PhoneSettings(config_dir=dirs.config, resolver=offline_resolver, netinfo=netinfo)
        enable_phone_access(settings)
        host, label, discovery = LOOPBACK, None, 0

    sock = bind_socket(host, args.port)
    port = sock.getsockname()[1]
    settings = PhoneSettings(config_dir=dirs.config, resolver=offline_resolver, netinfo=netinfo,
                             discovery_port=discovery, port=port)
    analyze = SyntheticAnalysis(0.0)                   # the seed needs no pacing
    store = JobStore(dirs.workspace, analyze_fn=analyze, corpus=None, download_dir=dirs.base / "downloads",
                     download_fn=refuse_download)
    try:
        seed = store.create(make_seed_media(dirs.seed), options={"separate_dialogue": True})
        if wait_for_job(store, seed.id, {DONE, ERROR, CANCELLED}, 120) != DONE:
            raise RuntimeError(f"the seeded clip did not finish: {store.get(seed.id).error}")
        analyze.seconds = max(0.0, float(args.analysis_seconds))
        app = create_app(store=store, phone=settings, web_dist=None, allowed_roots=[dirs.base])
        app.state.phone.artifacts.warm(seed.id)        # checksums and media facts ready before "ready"
        config = uvicorn.Config(app, host=host, port=port, proxy_headers=False, access_log=False,
                                log_level="warning", lifespan="on")
        server = uvicorn.Server(config)
    except BaseException:
        sock.close()
        store.shutdown(wait=True)
        raise
    console.say(f"listening: {host}:{port}" + (f"   ({label})" if label else ""))
    if not args.lan:
        console.say(f"emulator: {EMULATOR_HOST}:{port}")
    console.say(f"seeded clip: {seed.id}")
    registry = DeviceRegistry(ConfigDir(dirs.config), time.time)
    threading.Thread(target=operator, args=(server, store, registry, console), name="operator",
                     daemon=True).start()
    return store, server, sock


def new_code_line(registry) -> str:
    return f"pairing code: {registry.create_code().code} (valid for 5 minutes, single use)"


def operator(server, store, registry, console: Console, stdin=None) -> None:
    """Announce readiness once the server listens, then follow the commands on standard input."""
    while not server.started and not server.should_exit:
        time.sleep(0.05)
    if server.should_exit:
        return
    console.say(new_code_line(registry))
    console.say("ready")
    stdin = stdin or sys.stdin
    interactive = stdin.isatty()
    while True:
        try:
            line = stdin.readline()
        except (OSError, ValueError):
            return
        if not line:
            if interactive:
                server.should_exit = True              # Ctrl-D on a terminal
            return                                     # a closed pipe: keep serving until a signal
        command = line.strip().lower()
        if command in ("quit", "exit", "q"):
            server.should_exit = True
            return
        if command == "pair":
            console.say(new_code_line(registry))
        elif command == "status":
            console.say(status_line(store))
        elif command:
            console.say("commands: pair, status, quit")


# --- a minimal phone, for the self-check and the tests ---------------------------------------------------------------

def pt1_header(device_id: str, key: bytes, method: str, target: str, ts: int | str, nonce: str) -> str:
    """`Authorization: PT1 …` (server-contract 4.3)."""
    message = f"pt-req-v1|{method.upper()}|{target}|{ts}|{nonce}"
    mac = hmac.new(key, message.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"PT1 device={device_id}, ts={ts}, nonce={nonce}, mac={mac}"


@dataclass
class Answer:
    status: int
    headers: dict[str, str]
    body: bytes

    def json(self):
        return json.loads(self.body.decode("utf-8"))


class Phone:
    """Pairs and signs every request as the app does (standard library only)."""

    def __init__(self, host: str, port: int, timeout: float = 30.0) -> None:
        self.host, self.port, self.timeout = host, port, timeout
        self.device_id: str | None = None
        self.key: bytes | None = None

    def request(self, method: str, target: str, body: bytes | dict | None = None, *, sign: bool = True,
                headers: dict[str, str] | None = None) -> Answer:
        sent = dict(headers or {})
        if isinstance(body, dict):
            body = json.dumps(body).encode("utf-8")
            sent.setdefault("Content-Type", "application/json")
        if sign and self.key is not None:
            sent["Authorization"] = pt1_header(self.device_id, self.key, method, target, int(time.time()),
                                               secrets.token_hex(16))
        connection = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        try:
            skip_host = any(name.lower() == "host" for name in sent)
            connection.putrequest(method, target, skip_host=skip_host, skip_accept_encoding=True)
            if body is not None:
                sent["Content-Length"] = str(len(body))
            for name, value in sent.items():
                connection.putheader(name, value)
            connection.endheaders(body)
            response = connection.getresponse()
            return Answer(response.status, {k.lower(): v for k, v in response.getheaders()}, response.read())
        finally:
            connection.close()

    def pair(self, code: str, name: str = "Fake computer self-check") -> dict:
        answer = self.request("POST", "/api/phone/pair", {"code": code, "device_name": name, "app_version": 1},
                              sign=False)
        if answer.status != 201:
            raise CheckFailed(f"pair answered {answer.status}: {answer.body[:200]!r}")
        paired = answer.json()
        self.device_id = paired["device_id"]
        self.key = hashlib.sha256(paired["token"].encode("ascii")).digest()
        return paired


class CheckFailed(AssertionError):
    pass


def expect(condition, message: str) -> None:
    if not condition:
        raise CheckFailed(message)


# --- running one as a child process ------------------------------------------------------------------------------------

@dataclass
class Announcement:
    base: Path | None = None
    host: str | None = None
    port: int | None = None
    seed: str | None = None
    code: str | None = None


LINE_PATTERNS = {
    "base": re.compile(r"^throwaway directory: (?P<value>.+)$"),
    "listening": re.compile(r"^listening: (?P<host>[0-9.]+):(?P<port>[0-9]+)\b"),
    "seed": re.compile(r"^seeded clip: (?P<value>\S+)$"),
    "code": re.compile(r"^pairing code: (?P<value>[0-9]{6})\b"),
}


def read_announcement(lines, into: Announcement | None = None) -> Announcement:
    """Fill an Announcement from the fake computer's standard output lines."""
    found = into or Announcement()
    for line in lines:
        for key, pattern in LINE_PATTERNS.items():
            match = pattern.match(line)
            if not match:
                continue
            if key == "listening":
                found.host, found.port = match["host"], int(match["port"])
            elif key == "base":
                found.base = Path(match["value"])
            else:
                setattr(found, key, match["value"])
    return found


@dataclass
class Running:
    """A fake computer started as a child process, with its output followed line by line."""

    python: str
    args: list[str] = field(default_factory=list)
    cwd: str | None = None
    environ: dict | None = None

    def __post_init__(self) -> None:
        self.lines: queue.Queue[str | None] = queue.Queue()
        self.output: list[str] = []
        self.errors: list[str] = []
        self.process = subprocess.Popen(
            [self.python, "-B", str(SCRIPT), *self.args], cwd=self.cwd, env=server_environment(self.environ),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8",
            bufsize=1)
        threading.Thread(target=self._follow, args=(self.process.stdout, True), daemon=True).start()
        threading.Thread(target=self._follow, args=(self.process.stderr, False), daemon=True).start()

    def _follow(self, stream, is_stdout: bool) -> None:
        for line in stream:
            line = line.rstrip("\n")
            (self.output if is_stdout else self.errors).append(line)
            if is_stdout:
                self.lines.put(line)
        if is_stdout:
            self.lines.put(None)

    def expect_line(self, prefix: str, timeout: float = 120.0) -> str:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise CheckFailed(f"no {prefix!r} line within {timeout:.0f} s{self.tail()}")
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty:
                continue
            if line is None:
                raise CheckFailed(f"the fake computer exited before printing {prefix!r}{self.tail()}")
            if line.startswith(prefix):
                return line

    def wait_ready(self, timeout: float = 180.0) -> Announcement:
        self.expect_line("ready", timeout)
        return read_announcement(self.output)

    def send(self, command: str) -> None:
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def stop(self, timeout: float = 30.0, signum: int | None = None) -> int:
        if self.process.poll() is None:
            try:
                if signum is None:
                    self.send("quit")
                else:
                    self.process.send_signal(signum)
            except (BrokenPipeError, OSError):
                pass
            try:
                self.process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            try:
                stream.close()
            except OSError:
                pass
        return self.process.returncode

    def tail(self) -> str:
        lines = (self.output + self.errors)[-20:]
        return ("\n" + "\n".join(lines)) if lines else ""


# --- the self-check ----------------------------------------------------------------------------------------------------

def poll(fetch: Callable[[], object], done: Callable[[object], bool], timeout: float, what: str):
    deadline = time.monotonic() + timeout
    value = fetch()
    while not done(value):
        if time.monotonic() >= deadline:
            raise CheckFailed(f"{what}: still {value!r} after {timeout:.0f} s")
        time.sleep(0.2)
        value = fetch()
    return value


def _phone_job(phone: Phone, job_id: str) -> dict:
    answer = phone.request("GET", f"/api/phone/jobs/{job_id}")
    expect(answer.status == 200, f"GET jobs/{job_id} answered {answer.status}")
    return answer.json()


def _seed_ready(phone: Phone, seed_id: str) -> dict | None:
    answer = phone.request("GET", "/api/phone/library")
    expect(answer.status == 200, f"library answered {answer.status}")
    job = next((j for j in answer.json()["jobs"] if j["id"] == seed_id), None)
    if job and job["status"] == "done" and job["artifacts"] and all(
            a["status"] == "ready" for a in job["artifacts"]):
        return job
    return None


def session(phone: Phone, info: Announcement, running: Running | None, report: Callable[[str], None]) -> None:
    """A phone's session against a fake computer: every step raises CheckFailed with what went wrong."""
    hello = phone.request("GET", "/api/phone/hello", sign=False)
    expect(hello.status == 200 and hello.json().get("phone_access") is True,
           f"hello answered {hello.status} {hello.body[:200]!r}")
    server = hello.json()
    report(f"hello: phone access on, API {server['api_version']}")
    emulator = phone.request("GET", "/api/phone/hello", sign=False,
                             headers={"Host": f"{EMULATOR_HOST}:{info.port}"})
    expect(emulator.status == 200, f"the emulator's Host was refused ({emulator.status})")
    rebinding = phone.request("GET", "/api/phone/hello", sign=False, headers={"Host": "attacker.example"})
    expect(rebinding.status == 400 and rebinding.json().get("code") == "bad_host",
           f"a foreign Host name answered {rebinding.status}")
    report("host rule: the emulator's address passes, a foreign name gets bad_host")
    unsigned = phone.request("GET", "/api/phone/health", sign=False)
    expect(unsigned.status == 401 and unsigned.json().get("code") == "credential_rejected",
           f"an unsigned health answered {unsigned.status}")

    paired = phone.pair(info.code)
    expect(paired["token"].startswith("pt1_") and paired["server_id"] == server["server_id"],
           "the pairing answer does not match hello")
    challenge = secrets.token_hex(16)
    proof = phone.request("GET", f"/api/phone/hello?device_id={phone.device_id}&challenge={challenge}",
                          sign=False).json().get("proof")
    expected = hmac.new(phone.key, f"pt-hello-v1|{server['server_id']}|{phone.device_id}|{challenge}".encode(),
                        hashlib.sha256).hexdigest()
    expect(proof == expected, "the identity proof of hello does not verify")
    report("paired with the printed code; the identity proof verifies")

    health = phone.request("GET", "/api/phone/health")
    expect(health.status == 200 and health.json()["device"]["device_id"] == phone.device_id,
           f"health answered {health.status}")
    report("signed health answered")

    seed = poll(lambda: _seed_ready(phone, info.seed), lambda job: job is not None, 60, "the seeded clip")
    names = sorted(a["name"] for a in seed["artifacts"])
    expect({"analysis", "audio_mix"} <= set(names), f"the seeded clip lists {names}")
    for artifact in seed["artifacts"]:
        answer = phone.request("GET", artifact["url"])
        digest = hashlib.sha256(answer.body).hexdigest()
        expect(answer.status == 200 and len(answer.body) == artifact["bytes"] and digest == artifact["sha256"]
               and answer.headers.get("etag") == f'"{digest}"', f"artifact {artifact['name']} does not verify")
        if artifact["name"] == "analysis":
            expect(json.loads(answer.body).get("segments"), "the seeded analysis has no segments")
    report(f"seeded clip listed and downloaded, checksums verified: {', '.join(names)}")

    reference = phone.request("GET", "/api/phone/reference")
    expect(reference.status == 200 and reference.json().get("api_version") == server["api_version"],
           f"reference answered {reference.status}")
    report("reference answered")

    created = phone.request("POST", "/api/jobs/youtube", {"url": SELF_CHECK_LINK}, sign=False)
    expect(created.status == 201, f"the browser's link route answered {created.status}")
    link_job = created.json()["id"]
    browser = poll(lambda: json.loads(phone.request("GET", f"/api/jobs/{link_job}", sign=False).body),
                   lambda job: job["status"] in ("done", "error", "cancelled"), 30, "the link job")
    expect(browser["status"] == "error" and REFUSAL in (browser.get("error") or ""),
           f"the link job ended {browser['status']}: {browser.get('error')}")
    listed = _phone_job(phone, link_job)
    expect(listed["status"] == "error" and listed["error"]["code"] == "processing_failed",
           f"phones see the refused link as {listed['status']} {listed.get('error')}")
    expect(phone.request("DELETE", f"/api/jobs/{link_job}", sign=False).status == 204, "the link job stayed")
    report("link refused by the downloader before any connection, as phones see it too")

    probe = phone.request("POST", "/api/phone/uploads", {})
    if probe.status == 404 and probe.json().get("code") == "unknown_route":
        report("uploads, cancel, re-run, delete and phone links: not on this server yet (WP-01 milestone B)")
    else:
        transfers(phone, report)

    expect(phone.request("DELETE", "/api/phone/devices/self").status == 204, "unpairing failed")
    after = phone.request("GET", "/api/phone/health")
    expect(after.status == 401, f"health after unpairing answered {after.status}")
    report("unpaired; the old credential is rejected")

    if running is not None:
        running.send("pair")
        line = running.expect_line("pairing code:", 30)
        expect(re.match(r"^pairing code: [0-9]{6} ", line), f"unexpected line {line!r}")
        running.send("status")
        running.expect_line("jobs:", 30)
        report("operator commands: a new pairing code, the status line")


def transfers(phone: Phone, report: Callable[[str], None]) -> None:
    """Milestone B: upload a synthetic two-second WAV, cancel, re-run, cancel, delete; a phone link is refused."""
    with tempfile.TemporaryDirectory() as scratch:
        data = write_silent_wav(Path(scratch) / "self-check.wav", 2.0).read_bytes()
    key = str(uuid.uuid4())
    declared = phone.request("POST", "/api/phone/uploads", {
        "client_request_id": key, "file_name": "self-check.wav", "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(), "separate_dialogue": False})
    expect(declared.status == 201 and declared.json().get("state") == "open",
           f"declaring the upload answered {declared.status} {declared.body[:200]!r}")
    put = phone.request("PUT", f"/api/phone/uploads/{key}", data,
                        headers={"Content-Type": "application/octet-stream"})
    expect(put.status == 201, f"the upload answered {put.status} {put.body[:200]!r}")
    job_id = put.json()["job"]["id"]
    for round_ in ("upload", "re-run"):
        cancel = phone.request("POST", f"/api/phone/jobs/{job_id}/cancel")
        expect(cancel.status == 200, f"cancelling the {round_} answered {cancel.status} {cancel.body[:200]!r}")
        poll(lambda: _phone_job(phone, job_id)["status"], lambda status: status == "cancelled", 30,
             f"the cancelled {round_}")
        if round_ == "upload":
            rerun = phone.request("POST", f"/api/phone/jobs/{job_id}/rerun", {"client_request_id": str(uuid.uuid4())})
            expect(rerun.status == 201 and rerun.json().get("replaced") == job_id,
                   f"the re-run answered {rerun.status} {rerun.body[:200]!r}")
            job_id = rerun.json()["job"]["id"]
    expect(phone.request("DELETE", f"/api/phone/jobs/{job_id}").status == 204, "the cancelled job stayed")
    report("uploaded, cancelled, re-ran, cancelled and deleted a synthetic WAV")
    link = phone.request("POST", "/api/phone/jobs/link", {"client_request_id": str(uuid.uuid4()),
                                                         "url": SELF_CHECK_LINK})
    expect(link.status == 201, f"the phone's link route answered {link.status} {link.body[:200]!r}")
    link_id = link.json()["job"]["id"]
    final = poll(lambda: _phone_job(phone, link_id), lambda job: job["status"] in ("done", "error", "cancelled"), 30,
                 "the phone's link job")
    expect(final["status"] == "error" and REFUSAL in (final["error"] or {}).get("message", ""),
           f"the phone's link job ended {final['status']} {final.get('error')}")
    expect(phone.request("DELETE", f"/api/phone/jobs/{link_id}").status == 204, "the failed link job stayed")
    report("a phone link was refused by the downloader and deleted")


def listing(path: Path) -> list[str]:
    try:
        return sorted(os.listdir(path))
    except OSError:
        return []


def self_check(python: str, parent: str | None = None, analysis_seconds: float = 4.0,
               report: Callable[[str], None] = print) -> bool:
    """Start a fake computer, run a phone session against it, stop it and check that nothing was left behind."""
    start = Path.cwd()
    before = listing(start)
    args = ["--port", "0", "--analysis-seconds", str(analysis_seconds)] + (["--parent", parent] if parent else [])
    running = Running(python, args)
    ok = True
    info = Announcement()
    try:
        info = running.wait_ready()
        report(f"started on {info.host}:{info.port}")
        session(Phone(info.host, info.port), info, running, lambda line: report("  ok   " + line))
    except CheckFailed as exc:
        ok = False
        report(f"  FAIL {exc}")
    finally:
        code = running.stop()
    if code != 0:
        ok = False
        report(f"  FAIL the fake computer exited with {code}{running.tail()}")
    else:
        report("  ok   stopped cleanly")
    if info.base is not None and info.base.exists():
        ok = False
        report("  FAIL the throwaway directory is still there")
    elif info.base is not None:
        report("  ok   the throwaway directory is gone")
    if listing(start) != before:
        ok = False
        report("  FAIL the directory it was started from changed")
    else:
        report("  ok   nothing was written where it was started")
    report("self-check: " + ("PASS" if ok else "FAIL"))
    return ok


# --- entry point ---------------------------------------------------------------------------------------------------

def parse_args(argv: list[str] | None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--port", type=int, default=0, help="listening port (default: a free one, printed)")
    parser.add_argument("--analysis-seconds", type=float, default=DEFAULT_ANALYSIS_SECONDS,
                        help="how long each synthetic analysis takes (default: 1)")
    parser.add_argument("--lan", action="store_true",
                        help="serve the test phone on this computer's approved Wi-Fi address instead of loopback")
    parser.add_argument("--interface", help="with --lan: the interface to approve and serve on")
    parser.add_argument("--parent", help="where the throwaway directory goes (default: the system temporary directory)")
    parser.add_argument("--python", help="the interpreter of the repository's virtual environment")
    parser.add_argument("--self-check", action="store_true",
                        help="start one, run a phone session against it, stop it, check cleanup")
    args = parser.parse_args(argv)
    if args.interface and not args.lan:
        parser.error("--interface goes with --lan")
    return args


def main(argv: list[str] | None = None) -> int:
    sys.dont_write_bytecode = True
    argv = list(sys.argv[1:] if argv is None else argv)
    args = parse_args(argv)
    python = find_python(args.python)
    if python is None:
        print("No interpreter with the server's packages was found: pass --python with the repository's virtual "
              "environment (or set PHONOTRAINER_PYTHON).", file=sys.stderr)
        return 2
    if args.self_check:
        return 0 if self_check(python, args.parent) else 1
    here = os.path.abspath(python) == os.path.abspath(sys.executable) or (
        not args.python and not os.environ.get("PHONOTRAINER_PYTHON") and has_server_modules())
    if not here:
        if os.environ.get(REEXEC_MARK):
            print(f"{python} cannot run the server.", file=sys.stderr)
            return 2
        env = server_environment()
        env[REEXEC_MARK] = "1"
        os.execve(python, [python, "-B", str(SCRIPT), *argv], env)
    console = Console()
    console.say("PhonoTrainer host-side fake computer: synthetic analyses, every download refused "
                "(server-contract 12).")
    return serve(args, console)


if __name__ == "__main__":
    sys.exit(main())
