"""The configuration directory: the computer's identity and the paired phones.

`PHONOTRAINER_CONFIG_DIR` if set, otherwise `$XDG_CONFIG_HOME/phonotrainer`,
otherwise `%APPDATA%\\phonotrainer` on Windows, otherwise
`~/.config/phonotrainer`. It lives outside the repository and outside every
workspace, so switching workspaces never breaks pairing and nothing in it can
be committed by accident.

`devices.json` holds every paired phone's MAC key, so the directory is treated
as secret: it is created `0700`, every file is written `0600` through a
temporary file and `os.replace`, and every open checks that the directory (and
the secret files in it) belong to the current user and are not readable or
writable by anyone else. Read-modify-write cycles hold an exclusive `flock` on
`<dir>/.lock`, so the `phonotrainer phone` command and a running server never
lose each other's writes.

On Windows the same rules use Windows' own means (`winsec.py`): the directory
gets a protected DACL granting full control only to the current user, SYSTEM
and Administrators (its files inherit it), the owner is checked instead of the
uid, a DACL that lets anyone else in is tightened, and the lock is
`msvcrt.locking` on the first byte of `.lock`.
"""

from __future__ import annotations

import errno
import json
import logging
import os
import stat
import sys
import tempfile
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

if sys.platform == "win32":
    import msvcrt

    from . import winsec
else:
    import fcntl

ENV_VAR = "PHONOTRAINER_CONFIG_DIR"
DIR_MODE = 0o700
FILE_MODE = 0o600
LOCK_NAME = ".lock"
LOCK_RETRY_S = 0.05               # Windows: msvcrt has no blocking lock without a timeout
OPEN_FILE_TRIES = 20              # Windows: a file another process has open cannot be
                                  # replaced or deleted
RUNTIME_FILE = "runtime.json"     # written by a server running with phone access
# Files whose content is a secret: their mode is checked at every open too.
SECRET_FILES = ("devices.json", "pairing.json")

# Error and warning texts name no path: "the PhonoTrainer configuration
# directory" is all a terminal or a log line ever says about where it is.
WHERE = "the PhonoTrainer configuration directory"

log = logging.getLogger("phonotrainer.phone")


class ConfigError(RuntimeError):
    """The configuration directory cannot be used safely."""


def config_dir(environ: dict[str, str] | None = None) -> Path:
    """Where the configuration directory is (it may not exist yet)."""
    env = os.environ if environ is None else environ
    explicit = env.get(ENV_VAR)
    if explicit:
        return Path(explicit).expanduser()
    xdg = env.get("XDG_CONFIG_HOME")
    if xdg and os.path.isabs(xdg):          # the XDG spec ignores relative values
        return Path(xdg) / "phonotrainer"
    appdata = env.get("APPDATA") if sys.platform == "win32" else None
    if appdata and os.path.isabs(appdata):
        return Path(appdata) / "phonotrainer"
    return Path.home() / ".config" / "phonotrainer"


# --- POSIX owner and modes, or their Windows equivalents ------------------------------

def _owned_by_me(path: Path, st: os.stat_result) -> bool:
    if sys.platform == "win32":
        return winsec.owned_by_me(path)
    return st.st_uid == os.getuid()


def _too_open(path: Path, st: os.stat_result) -> bool:
    """Readable or writable by anyone but the owner."""
    if sys.platform == "win32":
        return bool(winsec.foreign_grantees(path))
    return bool(stat.S_IMODE(st.st_mode) & 0o077)


def _make_private(path: Path, directory: bool) -> None:
    if sys.platform == "win32":
        winsec.make_private(path, directory)
    else:
        os.chmod(path, DIR_MODE if directory else FILE_MODE)


def _lock(fd: int) -> None:
    if sys.platform != "win32":
        fcntl.flock(fd, fcntl.LOCK_EX)
        return
    while True:
        os.lseek(fd, 0, os.SEEK_SET)
        try:
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            return
        except OSError as exc:
            if exc.errno not in (errno.EACCES, errno.EDEADLK):    # not "held by another"
                raise
            time.sleep(LOCK_RETRY_S)


def _unlock(fd: int) -> None:
    if sys.platform != "win32":
        fcntl.flock(fd, fcntl.LOCK_UN)
        return
    os.lseek(fd, 0, os.SEEK_SET)
    msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)


def _while_open(action, *args) -> None:
    """`action(*args)`; on Windows replacing or deleting a file fails while
    another process (a reader, an antivirus scan) has it open, so it is
    retried for about a second."""
    tries = OPEN_FILE_TRIES if sys.platform == "win32" else 1
    for attempt in range(tries):
        try:
            action(*args)
            return
        except PermissionError:
            if attempt == tries - 1:
                raise
            time.sleep(LOCK_RETRY_S)


class _ProcessLock:
    """One per directory and process: threads take turns on the `RLock`, and
    the first holder takes the `flock` that excludes other processes. Nested
    `locked()` blocks in one thread do not deadlock on their own flock."""

    def __init__(self) -> None:
        self.rlock = threading.RLock()
        self.depth = 0
        self.fd: int | None = None


_PROCESS_LOCKS: dict[str, _ProcessLock] = {}
_PROCESS_LOCKS_GUARD = threading.Lock()


def _process_lock(path: Path) -> _ProcessLock:
    key = str(path.resolve())
    with _PROCESS_LOCKS_GUARD:
        return _PROCESS_LOCKS.setdefault(key, _ProcessLock())


class ConfigDir:
    """The configuration directory and its JSON files."""

    def __init__(self, path: str | Path | None = None) -> None:
        self.path = Path(path).expanduser() if path is not None else config_dir()

    # --- safety --------------------------------------------------------------
    def ensure(self) -> Path:
        """Create the directory if needed (0700, or private on Windows) and check it."""
        try:
            self.path.mkdir(mode=DIR_MODE, parents=True)
            _make_private(self.path, directory=True)   # mkdir's mode is filtered by the umask
        except FileExistsError:
            pass
        self.check()
        return self.path

    def exists(self) -> bool:
        return self.path.is_dir()

    def check(self) -> None:
        """Ownership and modes of the directory and its secret files.

        A directory owned by another user is refused; a mode wider than
        0700/0600 is tightened, with one warning line naming no path.
        """
        try:
            st = os.stat(self.path)
        except FileNotFoundError:
            return
        if not stat.S_ISDIR(st.st_mode):
            raise ConfigError(f"{WHERE} is not a directory.")
        if not _owned_by_me(self.path, st):
            raise ConfigError(f"{WHERE} belongs to another user; refusing to use it.")
        tightened = []
        if _too_open(self.path, st):
            _make_private(self.path, directory=True)
            tightened.append("the directory")
        for name in SECRET_FILES:
            path = self.path / name
            try:
                fst = os.lstat(path)
            except FileNotFoundError:
                continue
            if not stat.S_ISREG(fst.st_mode):
                raise ConfigError(f"{name} in {WHERE} is not a regular file.")
            if not _owned_by_me(path, fst):
                raise ConfigError(f"{name} in {WHERE} belongs to another user; "
                                  "refusing to use it.")
            if _too_open(path, fst):
                _make_private(path, directory=False)
                tightened.append(name)
        if tightened:
            log.warning("Tightened the permissions of %s in %s (it holds phone keys).",
                        " and ".join(tightened), WHERE)

    # --- files ---------------------------------------------------------------
    def stat_key(self, name: str) -> tuple[int, int, int] | None:
        """What changes whenever the file is replaced (os.replace gives a new
        inode, so two writes within one mtime tick still differ)."""
        try:
            st = os.stat(self.path / name)
        except FileNotFoundError:
            return None
        return (st.st_mtime_ns, st.st_size, st.st_ino)

    def read_json(self, name: str) -> dict | None:
        """The file's object, or None when it does not exist. A damaged file is
        an error: guessing would, for example, change the computer's id."""
        if not self.exists():
            return None
        self.check()
        try:
            text = (self.path / name).read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ConfigError(f"{name} in {WHERE} is damaged ({exc.msg}).") from exc
        if not isinstance(data, dict):
            raise ConfigError(f"{name} in {WHERE} is damaged (not a JSON object).")
        return data

    def write_json(self, name: str, data: dict) -> None:
        """Atomic write with mode 0600 (on Windows the file inherits the
        directory's private DACL)."""
        self.ensure()
        fd, tmp = tempfile.mkstemp(dir=self.path, prefix=f".{name}.", suffix=".tmp")
        try:
            if sys.platform != "win32":
                os.fchmod(fd, FILE_MODE)
            fh = os.fdopen(fd, "w", encoding="utf-8")
        except BaseException:
            os.close(fd)                  # Windows cannot delete a file that is still open
            os.unlink(tmp)
            raise
        try:
            with fh:
                json.dump(data, fh, ensure_ascii=False, indent=2)
                fh.write("\n")
                fh.flush()
                os.fsync(fh.fileno())
            _while_open(os.replace, tmp, self.path / name)
        except BaseException:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise

    def delete(self, name: str) -> None:
        try:
            _while_open(os.unlink, self.path / name)
        except FileNotFoundError:
            pass

    @contextmanager
    def locked(self) -> Iterator[None]:
        """Exclusive lock for a read-modify-write cycle, across threads and
        processes."""
        self.ensure()
        plock = _process_lock(self.path)
        with plock.rlock:
            if plock.depth == 0:
                fd = os.open(self.path / LOCK_NAME, os.O_RDWR | os.O_CREAT, FILE_MODE)
                try:
                    _lock(fd)
                except BaseException:
                    os.close(fd)
                    raise
                plock.fd = fd
            plock.depth += 1
            try:
                yield
            finally:
                plock.depth -= 1
                if plock.depth == 0 and plock.fd is not None:
                    _unlock(plock.fd)
                    os.close(plock.fd)
                    plock.fd = None


def pid_alive(pid: int) -> bool:
    """Whether a process with this pid exists. Nothing is sent to it: on
    Windows `os.kill(pid, 0)` is no probe (signal 0 is CTRL_C_EVENT, sent to
    every process on the console), so the process is looked up instead."""
    if sys.platform == "win32":
        from .._win import pid_alive as windows_pid_alive

        return windows_pid_alive(pid)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        pass                                          # alive, another user's
    return True


def runtime_info(config: ConfigDir) -> dict | None:
    """runtime.json when a server with phone access is really running (its
    pid is alive); None otherwise. Read by `phonotrainer phone …`."""
    try:
        data = config.read_json(RUNTIME_FILE)
    except Exception:                                 # noqa: BLE001
        return None
    if not data or not isinstance(data.get("pid"), int):
        return None
    if not pid_alive(data["pid"]):
        return None
    return data
