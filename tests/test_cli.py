"""Command line: the corpus overview, without models."""

import os
import subprocess
import sys
from pathlib import Path

import pytest
from click.testing import CliRunner
from conftest import mk_analysis, wait_until

from phonotrainer.cli import main
from phonotrainer.db import Corpus


def test_the_corpus_overview_puts_its_reduction_next_to_the_references(tmp_path):
    path = tmp_path / "corpus.db"
    db = Corpus(path)
    db.index_analysis("out", mk_analysis())
    db.close()

    # wide enough that rich does not wrap a cell over two lines
    result = CliRunner().invoke(main, ["corpus", "--db", str(path)], env={"COLUMNS": "200"})
    assert result.exit_code == 0, result.output
    assert "Reduction across 1 recording(s)" in result.output
    assert "words that lose a whole segment" in result.output
    assert "25.0 % (1/4)" in result.output
    assert "Johnson 2004" in result.output


def test_an_empty_corpus_says_so(tmp_path):
    result = CliRunner().invoke(main, ["corpus", "--db", str(tmp_path / "c.db")])
    assert result.exit_code == 0
    assert "The corpus is empty" in result.output


def test_only_engines_that_exist_are_accepted(tmp_path):
    result = CliRunner().invoke(main, ["analyze", str(tmp_path / "x.wav"),
                                       "--phone-engine", "allosaurus"])
    assert result.exit_code != 0
    assert "allosaurus" in result.output and "timit61" in result.output


# --- Windows: the command runs in Python's UTF-8 mode ----------------------------------

REPO = Path(__file__).resolve().parents[1]
WINDOWS_ONLY = pytest.mark.skipif(sys.platform != "win32", reason="the relaunch is Windows'")


def run_cli(*args, env_changes=None, interpreter_options=(), tmp_path=None, **kwargs):
    env = {k: v for k, v in os.environ.items() if k != "PYTHONUTF8"}
    if tmp_path is not None:
        env["PHONOTRAINER_CONFIG_DIR"] = str(tmp_path / "config")
    env.update(env_changes or {})
    return subprocess.run([sys.executable, *interpreter_options, "-m", "phonotrainer.cli", *args],
                          cwd=REPO, env=env, capture_output=True, timeout=120, **kwargs)


@WINDOWS_ONLY
def test_without_utf8_mode_the_command_runs_again_in_it(tmp_path):
    """A redirected stdout is cp1252 on Windows outside UTF-8 mode, which
    cannot print the arrows and the IPA; the exit code comes back unchanged."""
    shown = run_cli("review", "--help", tmp_path=tmp_path)
    assert shown.returncode == 0, shown.stderr
    assert "analysis.json → review.json" in shown.stdout.decode("utf-8")
    refused = run_cli("phone", "revoke", tmp_path=tmp_path)
    assert refused.returncode == 2                         # click's usage error, passed through


@WINDOWS_ONLY
def test_utf8_mode_turned_off_on_purpose_is_not_retried_forever(tmp_path):
    shown = run_cli("phone", "--help", interpreter_options=("-X", "utf8=0"), tmp_path=tmp_path)
    assert shown.returncode == 0, shown.stderr
    assert b"Phone access for the Android client" in shown.stdout


HOLDER = """
import subprocess, sys, time
from phonotrainer._win import _end_with_this_process
child = subprocess.Popen([sys.executable, "-c",
                          "import os, sys, time; open(sys.argv[1], 'w').write(str(os.getpid()));"
                          " time.sleep(120)", sys.argv[1]])
job = _end_with_this_process(int(child._handle))
assert job is not None
time.sleep(120)
"""


@WINDOWS_ONLY
def test_a_relaunched_command_ends_with_the_process_that_started_it(tmp_path):
    """Killing the first process (Task Manager, `Popen.kill()`) must not leave
    the second one running, holding the server's port."""
    from phonotrainer._win import pid_alive

    pid_file = tmp_path / "child.pid"
    holder = subprocess.Popen([sys.executable, "-c", HOLDER, str(pid_file)], cwd=REPO)
    try:
        wait_until(lambda: pid_file.is_file() and pid_file.read_text(), timeout=60)
        child = int(pid_file.read_text())
        assert pid_alive(child)
    finally:
        holder.kill()
        holder.wait(timeout=30)
    wait_until(lambda: not pid_alive(child), timeout=30)
