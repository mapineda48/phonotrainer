"""Tests of host_computer.py (server-contract 12): throwaway directories outside the repository, the interpreter it
serves with, the refusing downloader, the offline resolver, the synthetic analysis writer, the harness protocol and,
when the repository's virtual environment is found, a whole phone session against a running fake computer that
leaves nothing in the directory it was started from."""

from __future__ import annotations

import ipaddress
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import wave
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import host_computer as hc  # noqa: E402


def repo_snapshot() -> dict[str, tuple[int, int]]:
    """What the fake computer must never change: the server package (bytecode included) and the repository root's
    own entries (`data/`, `downloads/` and `workspace/` would appear there)."""
    found = {name: (0, 0) for name in os.listdir(hc.REPO_ROOT)}
    for root, _, files in os.walk(hc.REPO_ROOT / "phonotrainer"):
        for name in files:
            path = Path(root) / name
            st = path.stat()
            found[str(path.relative_to(hc.REPO_ROOT))] = (st.st_size, st.st_mtime_ns)
    return found


def no_network(*args, **kwargs):
    raise AssertionError("the network was used")


class Directories(unittest.TestCase):
    def test_throwaway_directories_live_outside_the_repository_and_go_away(self):
        with tempfile.TemporaryDirectory() as parent:
            dirs = hc.make_dirs(parent)
            self.assertFalse(hc.inside(dirs.base, hc.REPO_ROOT))
            self.assertTrue(dirs.base.name.startswith(hc.TMP_PREFIX))
            for path in (dirs.workspace, dirs.seed, dirs.cwd):
                self.assertTrue(path.is_dir())
                self.assertEqual(dirs.base, path.parent)
            self.assertFalse(dirs.config.exists(), "the server creates its configuration directory itself")
            (dirs.workspace / "job").mkdir()
            self.assertTrue(hc.remove(dirs))
            self.assertEqual([], os.listdir(parent))

    def test_refused_inside_the_repository(self):
        with tempfile.TemporaryDirectory() as repo:
            with self.assertRaises(RuntimeError):
                hc.make_dirs(repo, repo=Path(repo))
            self.assertEqual([], os.listdir(repo))


class Interpreter(unittest.TestCase):
    def test_an_explicit_choice_is_used_or_refused_never_replaced(self):
        self.assertEqual("/opt/py", hc.find_python("/opt/py", {}, check=lambda p: True, current_ok=lambda: True))
        self.assertIsNone(hc.find_python("/opt/py", {}, check=lambda p: False, current_ok=lambda: True))
        env = {"PHONOTRAINER_PYTHON": "/env/py"}
        self.assertEqual("/env/py", hc.find_python(None, env, check=lambda p: True))
        self.assertIsNone(hc.find_python(None, env, check=lambda p: False, current_ok=lambda: True))
        self.assertEqual("/opt/py", hc.find_python("/opt/py", env, check=lambda p: True))

    def test_this_interpreter_then_the_checkout_venv_then_the_main_checkout_venv(self):
        self.assertEqual("/current", hc.find_python(None, {}, current="/current", current_ok=lambda: True,
                                                    check=lambda p: False))
        with tempfile.TemporaryDirectory() as tmp:
            repo, main = Path(tmp) / "worktree", Path(tmp) / "main"
            venv_python = main / ".venv" / "bin" / "python"
            venv_python.parent.mkdir(parents=True)
            venv_python.write_text("")
            repo.mkdir()
            found = hc.find_python(None, {}, repo=repo, current_ok=lambda: False, check=lambda p: True,
                                   checkout=lambda r: main)
            self.assertEqual(str(venv_python), found)
            own = repo / ".venv" / "bin" / "python"
            own.parent.mkdir(parents=True)
            own.write_text("")
            self.assertEqual(str(own), hc.find_python(None, {}, repo=repo, current_ok=lambda: False,
                                                      check=lambda p: True, checkout=lambda r: main))
            self.assertIsNone(hc.find_python(None, {}, repo=repo, current_ok=lambda: False, check=lambda p: False,
                                             checkout=lambda r: main))

    def test_the_server_environment_puts_this_checkout_first_and_writes_no_bytecode(self):
        env = hc.server_environment({"PYTHONPATH": "/elsewhere", "PATH": "/bin"})
        self.assertEqual([str(hc.REPO_ROOT), "/elsewhere"], env["PYTHONPATH"].split(os.pathsep))
        self.assertEqual("1", env["PYTHONDONTWRITEBYTECODE"])
        self.assertEqual(str(hc.REPO_ROOT), hc.server_environment({})["PYTHONPATH"])


class Fakes(unittest.TestCase):
    def test_every_download_is_refused_before_any_connection(self):
        download_error = hc.repo_module("phonotrainer.download").DownloadError
        with mock.patch.object(socket, "create_connection", no_network), \
                mock.patch.object(socket, "getaddrinfo", no_network):
            with self.assertRaises(download_error) as caught:
                hc.refuse_download("https://video.example/x", Path("/nonexistent"), audio_only=False,
                                   progress=lambda m: None, phone_origin=True)
        self.assertEqual(hc.REFUSAL, str(caught.exception))

    def test_the_resolver_never_asks_dns_and_answers_a_global_address(self):
        with mock.patch.object(socket, "getaddrinfo", no_network):
            answer = hc.offline_resolver("www.youtube.com")
        self.assertEqual([hc.OFFLINE_ANSWER], answer)
        address = ipaddress.ip_address(answer[0])
        self.assertTrue(address.is_global and not address.is_multicast)

    def test_the_synthetic_analysis_writes_the_pipeline_artifacts_with_its_progress_strings(self):
        state = hc.repo_module("phonotrainer.phone.state")
        jobs = hc.repo_module("phonotrainer.jobs")
        messages, pauses = [], []
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "job"
            analysis = hc.SyntheticAnalysis(1.0, sleep=pauses.append)(
                Path(tmp) / "VID_0001.mp4", out, progress=messages.append, separate_dialogue=True)
            for name in ("analysis.json", "audio.wav", "audio_dialogue.wav", "report.html"):
                self.assertTrue((out / name).is_file(), name)
            with wave.open(str(out / "audio.wav")) as fh:
                self.assertEqual(hc.SEED_SECONDS, fh.getnframes() / fh.getframerate())
        self.assertAlmostEqual(1.0, sum(pauses), places=6)
        self.assertEqual(len(messages), len(pauses))
        self.assertTrue(all(state.stage_of_message(m) for m in messages), messages)
        percents = [jobs.estimate_percent(m) for m in messages]
        self.assertEqual(sorted(percents), percents)
        self.assertEqual("VID_0001.mp4", analysis["meta"]["source"])
        self.assertEqual({"applied": True, "model": "htdemucs", "audio": "audio_dialogue.wav"},
                         analysis["meta"]["dialogue_separation"])
        counted = {}
        for segment in analysis["segments"]:
            self.assertEqual(len(segment["words"]), len(segment["prominence"]))
            self.assertEqual(len(segment["words"]), len(segment["word_classes"]))
            for word in segment["words"]:
                for name in word["phenomena"]:
                    counted[name] = counted.get(name, 0) + 1
        self.assertEqual(counted, analysis["summary"]["phenomena_counts"])
        labels = hc.repo_module("phonotrainer.report").PHENOMENON_LABEL
        self.assertTrue(set(counted) <= set(labels), set(counted) - set(labels))

    def test_a_cancel_lands_within_one_step(self):
        cancelled = hc.repo_module("phonotrainer.errors").JobCancelled
        seen = []

        def progress(message):
            if len(seen) == 2:
                raise cancelled(message)
            seen.append(message)

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "job"
            with self.assertRaises(cancelled):
                hc.SyntheticAnalysis(0.0)(Path(tmp) / "a.wav", out, progress=progress)
            self.assertFalse((out / "analysis.json").exists())

    def test_a_deleted_workspace_is_never_recreated(self):
        with tempfile.TemporaryDirectory() as tmp:
            gone = Path(tmp) / "workspace"
            with self.assertRaises(FileNotFoundError):
                hc.SyntheticAnalysis(0.0)(Path(tmp) / "a.wav", gone / "job")
            self.assertFalse(gone.exists())

    def test_the_store_with_the_fakes_refuses_links_and_analyzes_files(self):
        jobs = hc.repo_module("phonotrainer.jobs")
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            store = jobs.JobStore(tmp / "workspace", analyze_fn=hc.SyntheticAnalysis(0.05), corpus=None,
                                  download_dir=tmp / "downloads", download_fn=hc.refuse_download)
            try:
                link = store.create_from_url(hc.SELF_CHECK_LINK)
                media = hc.write_silent_wav(tmp / "clip.wav", 1.0)
                clip = store.create(media, options={"separate_dialogue": False})
                for job_id, status in ((link.id, jobs.ERROR), (clip.id, jobs.DONE)):
                    self.assertEqual(status, hc.wait_for_job(store, job_id, {jobs.DONE, jobs.ERROR}, 30))
                self.assertIn(hc.REFUSAL, store.get(link.id).error)
                self.assertFalse((tmp / "downloads").exists())
                done = store.get(clip.id)
                self.assertEqual(str(media.resolve()), done.media_path, "the file stays the job's original")
                self.assertIsNotNone(done.artifact("analysis.json"))
                self.assertIsNone(done.dialogue_audio())
                self.assertIn("jobs: 2", hc.status_line(store))
            finally:
                store.shutdown(wait=True)

    def test_the_seeded_original_falls_back_to_a_silent_wav(self):
        class Done:
            def __init__(self, code):
                self.returncode = code

        with tempfile.TemporaryDirectory() as tmp:
            seed = Path(tmp)
            wav = hc.make_seed_media(seed, which=lambda name: None)
            self.assertEqual(".wav", wav.suffix)
            wav.unlink()
            tried = []

            def failing(command, **kwargs):
                tried.append(command[command.index("-c:v") + 1])
                return Done(1)

            self.assertEqual(".wav", hc.make_seed_media(seed, run=failing, which=lambda name: "/bin/ffmpeg").suffix)
            self.assertEqual(["libx264", "libopenh264", "mpeg4"], tried)

            used = []

            def openh264_only(command, **kwargs):
                if command[command.index("-c:v") + 1] != "libopenh264":
                    return Done(1)
                used.append(command)
                Path(command[-1]).write_bytes(b"\x00\x00\x00\x18ftypmp42")
                return Done(0)

            video = hc.make_seed_media(seed, run=openh264_only, which=lambda name: "/bin/ffmpeg")
            self.assertEqual(f"{hc.SEED_NAME}.mp4", video.name)
            self.assertIn("testsrc2=size=360x640:rate=30:duration=4.0", used[0])   # synthetic and vertical
            self.assertIn("anullsrc=channel_layout=mono:sample_rate=48000", used[0])


class Protocol(unittest.TestCase):
    def test_the_harness_reads_address_seed_and_code_from_standard_output(self):
        info = hc.read_announcement([
            "PhonoTrainer host-side fake computer: synthetic analyses, every download refused (server-contract 12).",
            "throwaway directory: /tmp/phonotrainer-fake-computer-abc",
            "listening: 127.0.0.1:41234",
            "emulator: 10.0.2.2:41234",
            "seeded clip: 20261010-091503-4be1",
            "pairing code: 482913 (valid for 5 minutes, single use)",
            "ready",
        ])
        self.assertEqual(("127.0.0.1", 41234, "20261010-091503-4be1", "482913"),
                         (info.host, info.port, info.seed, info.code))
        self.assertEqual(Path("/tmp/phonotrainer-fake-computer-abc"), info.base)
        lan = hc.read_announcement(["listening: 192.0.2.10:8765   (wlan0)"])
        self.assertEqual(("192.0.2.10", 8765), (lan.host, lan.port))

    def test_the_signer_reproduces_the_reference_vector_of_server_contract_4_3(self):
        # hygiene: test-vector begin (the synthetic reference token of tests/phone_fixtures.py and its MAC)
        token = "pt1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        expected = ("PT1 device=dev_example1, ts=1791622800, nonce=00112233445566778899aabbccddeeff, "
                    "mac=a8b96a295b5eca4413be81636b8b80d004d488b19a3c668b6098f10ea8204440")
        # hygiene: test-vector end
        key = __import__("hashlib").sha256(token.encode("ascii")).digest()
        header = hc.pt1_header("dev_example1", key, "get",
                               "/api/phone/jobs/20261010-091503-4be1/artifacts/audio_mix", 1791622800,
                               "00112233445566778899aabbccddeeff")
        self.assertEqual(expected, header)

    def test_only_documented_addresses_and_a_marked_vector_are_named(self):
        self.assertEqual("10.0.2.2", hc.EMULATOR_HOST)
        self.assertTrue(ipaddress.ip_address(hc.LOOPBACK).is_loopback)
        self.assertIn(ipaddress.ip_address(hc.LOOPBACK_INTERFACE[1]), ipaddress.ip_network("192.0.2.0/24"))


PYTHON = hc.find_python()


@unittest.skipUnless(PYTHON, "no interpreter with the server's packages (the repository's virtual environment)")
class RunningFakeComputer(unittest.TestCase):
    """A real fake computer, started from an empty directory, on a private parent for its throwaway directory."""

    def setUp(self) -> None:
        self.start = tempfile.TemporaryDirectory()
        self.parent = tempfile.TemporaryDirectory()
        self.addCleanup(self.start.cleanup)
        self.addCleanup(self.parent.cleanup)
        self.before = repo_snapshot()

    def assert_nothing_left(self) -> None:
        self.assertEqual([], os.listdir(self.start.name), "something was written where it was started")
        self.assertEqual([], os.listdir(self.parent.name), "the throwaway directory is still there")
        self.assertEqual(self.before, repo_snapshot(), "the repository changed")

    def test_the_self_check_runs_a_phone_session_and_leaves_nothing_behind(self):
        # Started with this interpreter: when it lacks the server's packages, the script finds the venv itself.
        done = subprocess.run([sys.executable, "-B", str(hc.SCRIPT), "--self-check", "--parent", self.parent.name],
                              cwd=self.start.name, capture_output=True, text=True, timeout=300,
                              env={k: v for k, v in os.environ.items() if k != hc.REEXEC_MARK})
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        for line in ("ok   paired with the printed code", "ok   seeded clip listed and downloaded",
                     "ok   link refused by the downloader before any connection", "ok   unpaired",
                     "ok   the throwaway directory is gone", "self-check: PASS"):
            self.assertIn(line, done.stdout)
        self.assert_nothing_left()

    def test_sigterm_stops_it_cleanly_and_a_closed_pipe_does_not(self):
        running = hc.Running(PYTHON, ["--parent", self.parent.name], cwd=self.start.name)
        self.addCleanup(running.stop)
        info = running.wait_ready()
        self.assertTrue(info.base.is_dir() and hc.inside(info.base, Path(self.parent.name)))
        self.assertTrue((info.base / "workspace" / info.seed / "analysis.json").is_file())
        self.assertEqual(hc.LOOPBACK, info.host)
        self.assertIn(f"emulator: {hc.EMULATOR_HOST}:{info.port}", running.output)
        self.assertRegex(info.code, r"^[0-9]{6}$")
        running.process.stdin.close()                  # a harness that never writes commands
        time.sleep(0.5)
        self.assertIsNone(running.process.poll())
        hello = hc.Phone(info.host, info.port).request("GET", "/api/phone/hello", sign=False)
        self.assertEqual(200, hello.status)
        self.assertEqual(0, running.stop(signum=signal.SIGTERM))
        self.assertIn("stopped: the throwaway directory was deleted", running.output)
        self.assertEqual([], running.errors)
        self.assert_nothing_left()


if __name__ == "__main__":
    unittest.main()
