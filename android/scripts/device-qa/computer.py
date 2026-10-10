#!/usr/bin/env python3
"""The real PhonoTrainer computer for WP-25's real-computer pass only (architecture 21.7; server-contract 12).

Starts `phonotrainer ui --phone-access` from this checkout on a throwaway workspace, configuration directory AND
working directory (so `data/` and `downloads/`, which the server writes relative to its working directory, never land
in the repository or the owner's corpus), then reads operator commands:

  pair      `phonotrainer phone pair`: prints the pairing code to this terminal (never to a file)
  on, off   `phonotrainer phone on|off`: phone access while the server runs
  status    `phonotrainer phone status`
  list      `phonotrainer phone list`
  quit      stop the server and delete the throwaway directories (also on Ctrl-D)

Usage: computer.py [--port 8000] [--python PATH] [--keep]
Run it with the repository's virtual environment (or pass --python); it imports this checkout's `phonotrainer`.

This runs the REAL pipeline. Use local files only: no agent ever sends a link to the real computer (spec D-36). Every
emulator step uses host_computer.py, the host-side fake computer, instead.
"""

from __future__ import annotations

import argparse
import os
import shutil
import signal
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
COMMANDS = {"pair": ["pair"], "on": ["on"], "off": ["off"], "status": ["status"], "list": ["list"]}
BANNER = (
    "Real PhonoTrainer computer (WP-25 real-computer pass). It runs the real pipeline: use LOCAL FILES ONLY and never "
    "send a link to it (spec D-36)."
)


@dataclass
class Dirs:
    base: Path
    workspace: Path
    config: Path
    cwd: Path


def make_dirs(parent: str | None = None) -> Dirs:
    base = Path(tempfile.mkdtemp(prefix="phonotrainer-computer-", dir=parent))
    dirs = Dirs(base, base / "workspace", base / "config", base / "cwd")
    for path in (dirs.workspace, dirs.config, dirs.cwd):
        path.mkdir(mode=0o700)
    return dirs


def inside(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def environment(dirs: Dirs, base_env=None, repo: Path = REPO_ROOT) -> dict[str, str]:
    env = dict(os.environ if base_env is None else base_env)
    env["PHONOTRAINER_CONFIG_DIR"] = str(dirs.config)
    env["PYTHONPATH"] = os.pathsep.join([str(repo)] + ([env["PYTHONPATH"]] if env.get("PYTHONPATH") else []))
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


def server_command(python: str, dirs: Dirs, port: int) -> list[str]:
    return [python, "-m", "phonotrainer.cli", "ui", "--phone-access", "--no-open", "--workspace", str(dirs.workspace),
            "--port", str(port)]


def phone_command(python: str, name: str) -> list[str]:
    return [python, "-m", "phonotrainer.cli", "phone", *COMMANDS[name]]


class Session:
    def __init__(self, python: str, port: int, keep: bool = False, popen=subprocess.Popen, call=subprocess.call,
                 parent: str | None = None, repo: Path = REPO_ROOT):
        self.python, self.port, self.keep = python, port, keep
        self.popen, self.call, self.repo = popen, call, repo
        self.server = None
        self.dirs = make_dirs(parent)
        if inside(self.dirs.base, repo):
            self.close()
            raise RuntimeError("the throwaway directories must be outside the repository")
        self.env = environment(self.dirs, repo=repo)

    def start(self):
        self.server = self.popen(server_command(self.python, self.dirs, self.port), cwd=self.dirs.cwd, env=self.env)
        return self.server

    def phone(self, name: str) -> int:
        return self.call(phone_command(self.python, name), cwd=self.dirs.cwd, env=self.env)

    def close(self) -> None:
        if self.server is not None and self.server.poll() is None:
            self.server.send_signal(signal.SIGINT)
            try:
                self.server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.server.kill()
                self.server.wait()
        if not self.keep:
            shutil.rmtree(self.dirs.base, ignore_errors=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--python", default=sys.executable, help="the interpreter of the repository's venv")
    parser.add_argument("--keep", action="store_true", help="keep the throwaway directories (for a post-mortem)")
    args = parser.parse_args(argv)
    print(BANNER)
    session = Session(args.python, args.port, args.keep)
    try:
        session.start()
        print("Commands: pair, on, off, status, list, quit")
        for line in sys.stdin:
            command = line.strip().lower()
            if command in ("quit", "exit", "q"):
                break
            if command in COMMANDS:
                session.phone(command)
            elif command:
                print("Commands: pair, on, off, status, list, quit")
            if session.server.poll() is not None:
                print("The server stopped.")
                break
    except KeyboardInterrupt:
        pass
    finally:
        session.close()
        print("Stopped; the throwaway directories were " + ("kept." if args.keep else "deleted."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
