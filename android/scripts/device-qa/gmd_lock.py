#!/usr/bin/env python3
"""Runs a command while holding the one Gradle Managed Device lock of this computer (architecture 21.5; work-plan 1.2).

Every copy of the project shares the one managed AVD, so only one GMD run may happen at a time. The lock is an
exclusive flock(2) on a file outside the repository, the same kind of lock util-linux `flock FILE command` takes, so
this script and `flock` users of the same file exclude each other. On Windows it is a lock on the file's first byte
(msvcrt.locking), which excludes the other users of this script (image_check.py included), not `flock`.

Usage:
  gmd_lock.py [--timeout SECONDS] [--lock-file PATH] -- ./gradlew pixel9Api36DebugAndroidTest ...
  gmd_lock.py [--timeout SECONDS] [--lock-file PATH] -- gradlew.bat pixel9Api36DebugAndroidTest ...   (Windows)
  gmd_lock.py --status        say whether the lock is free (exit 0) or held (exit 1)
  gmd_lock.py --where         print the lock file in use

The lock file is PHONOTRAINER_GMD_LOCK when set, otherwise ~/.cache/phonotrainer-orch/android/scratchpad/locks/
gmd.lock (the file the orchestration's `flock` users share). The command's exit status is returned; 75 means the lock
was not acquired before the timeout. The child does not inherit the lock descriptor, so a daemon it leaves behind
(the Gradle daemon) does not keep the lock. On Windows, `./gradlew` runs the `gradlew.bat` beside it.
"""

from __future__ import annotations

import argparse
import errno
import os
import signal
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

ENV_VAR = "PHONOTRAINER_GMD_LOCK"
EX_TEMPFAIL = 75
WINDOWS = os.name == "nt"

if WINDOWS:
    import msvcrt

    def _try_lock(fd: int) -> bool:
        """True when the lock was taken. LockFile refuses at once when another handle holds the byte, works on a
        read-only handle and past the end of the file."""
        os.lseek(fd, 0, os.SEEK_SET)
        try:
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            return True
        except OSError as error:
            if error.errno in (errno.EACCES, errno.EDEADLOCK):
                return False
            raise

    def _unlock(fd: int) -> None:
        # Windows does not say when it releases the lock of a closed handle, so it is released first.
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
else:
    import fcntl

    def _try_lock(fd: int) -> bool:
        """True when the lock was taken."""
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except OSError as error:
            if error.errno in (errno.EWOULDBLOCK, errno.EAGAIN):
                return False
            raise

    def _unlock(fd: int) -> None:
        pass  # closing the descriptor releases the lock


def default_lock_file(environ=None) -> Path:
    env = os.environ if environ is None else environ
    if env.get(ENV_VAR):
        return Path(env[ENV_VAR]).expanduser()
    home = Path(env.get("HOME") or Path.home())
    return home / ".cache" / "phonotrainer-orch" / "android" / "scratchpad" / "locks" / "gmd.lock"


def _open(path: Path) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Windows has no O_CLOEXEC; its descriptors are not inherited anyway (PEP 446), and O_NOINHERIT says so.
    flags = os.O_RDONLY | os.O_CREAT | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOINHERIT", 0)
    return os.open(path, flags, 0o666)


def _release(fd: int, locked: bool) -> None:
    try:
        if locked:
            _unlock(fd)
    except OSError:
        pass
    finally:
        os.close(fd)  # closing the descriptor releases the lock


@contextmanager
def gmd_lock(path: Path | None = None, timeout: float | None = None, poll: float = 0.5, announce=print):
    """Holds the lock for the body of the `with`; TimeoutError when it is not acquired in time."""
    path = Path(path or default_lock_file())
    fd = _open(path)
    locked = False
    try:
        deadline = None if timeout is None else time.monotonic() + timeout
        announced = False
        while not (locked := _try_lock(fd)):
            if deadline is not None and time.monotonic() >= deadline:
                raise TimeoutError(f"the GMD lock is still held after {timeout:g} s")
            if not announced and announce:
                announce("gmd_lock: another managed-device run holds the lock; waiting…")
                announced = True
            time.sleep(poll)
        yield path
    finally:
        _release(fd, locked)


def is_free(path: Path) -> bool:
    fd = _open(path)
    locked = False
    try:
        locked = _try_lock(fd)
        return locked
    except OSError:
        return False
    finally:
        _release(fd, locked)


def windows_command(command: list[str]) -> list[str]:
    """On Windows, `./gradlew` (a POSIX script, which CreateProcess cannot start) becomes the `gradlew.bat` beside
    it; any other command is unchanged."""
    if not WINDOWS or not command:
        return command
    program = Path(command[0])
    batch = program.with_name(program.name + ".bat")
    if program.suffix == "" and not program.is_dir() and batch.is_file():
        return [str(batch.absolute()), *command[1:]]
    return command


def run_locked(command: list[str], path: Path, timeout: float | None) -> int:
    try:
        with gmd_lock(path, timeout):
            child = subprocess.Popen(windows_command(command), close_fds=True)

            def forward(signum, _frame):
                if WINDOWS and signum != signal.SIGTERM:
                    # Ctrl-C and Ctrl-Break reach the child through the console it shares with this process (and
                    # Popen.send_signal cannot forward SIGINT there): keep the lock until the child has stopped.
                    return
                child.send_signal(signum)

            stops = (signal.SIGINT, signal.SIGTERM) + ((signal.SIGBREAK,) if hasattr(signal, "SIGBREAK") else ())
            previous = {s: signal.signal(s, forward) for s in stops}
            try:
                return child.wait()
            finally:
                for s, handler in previous.items():
                    signal.signal(s, handler)
    except TimeoutError as error:
        print(f"gmd_lock: {error}", file=sys.stderr)
        return EX_TEMPFAIL


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    command: list[str] = []
    if "--" in args:
        split = args.index("--")
        args, command = args[:split], args[split + 1 :]
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--timeout", type=float, help="give up after this many seconds (default: wait)")
    parser.add_argument("--lock-file", type=Path, help=f"the lock file (default: ${ENV_VAR} or the shared one)")
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--where", action="store_true")
    options = parser.parse_args(args)
    path = options.lock_file or default_lock_file()
    if options.where:
        print(path)
        return 0
    if options.status:
        free = is_free(path)
        print("free" if free else "held")
        return 0 if free else 1
    if not command:
        parser.error("give the command after `--`")
    return run_locked(command, path, options.timeout)


if __name__ == "__main__":
    sys.exit(main())
