"""The configuration directory: the computer's identity and the paired phones.

`PHONOTRAINER_CONFIG_DIR` if set, otherwise `$XDG_CONFIG_HOME/phonotrainer`,
otherwise `~/.config/phonotrainer`. It lives outside the repository and outside
every workspace, so switching workspaces never breaks pairing and nothing in it
can be committed by accident.

`devices.json` holds every paired phone's MAC key, so the directory is treated
as secret: it is created `0700`, every file is written `0600` through a
temporary file and `os.replace`, and every open checks that the directory (and
the secret files in it) belong to the current user and are not readable or
writable by anyone else. Read-modify-write cycles hold an exclusive `flock` on
`<dir>/.lock`, so the `phonotrainer phone` command and a running server never
lose each other's writes.
"""

from __future__ import annotations

import fcntl
import json
import logging
import os
import stat
import tempfile
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

ENV_VAR = "PHONOTRAINER_CONFIG_DIR"
DIR_MODE = 0o700
FILE_MODE = 0o600
LOCK_NAME = ".lock"
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
    return Path.home() / ".config" / "phonotrainer"


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
        """Create the directory if needed (0700) and check it."""
        try:
            self.path.mkdir(mode=DIR_MODE, parents=True)
            os.chmod(self.path, DIR_MODE)      # mkdir's mode is filtered by the umask
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
        if st.st_uid != os.getuid():
            raise ConfigError(f"{WHERE} belongs to another user; refusing to use it.")
        tightened = []
        if stat.S_IMODE(st.st_mode) & 0o077:
            os.chmod(self.path, DIR_MODE)
            tightened.append("the directory")
        for name in SECRET_FILES:
            path = self.path / name
            try:
                fst = os.lstat(path)
            except FileNotFoundError:
                continue
            if not stat.S_ISREG(fst.st_mode):
                raise ConfigError(f"{name} in {WHERE} is not a regular file.")
            if fst.st_uid != os.getuid():
                raise ConfigError(f"{name} in {WHERE} belongs to another user; "
                                  "refusing to use it.")
            if stat.S_IMODE(fst.st_mode) & 0o077:
                os.chmod(path, FILE_MODE)
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
        """Atomic write with mode 0600."""
        self.ensure()
        fd, tmp = tempfile.mkstemp(dir=self.path, prefix=f".{name}.", suffix=".tmp")
        try:
            os.fchmod(fd, FILE_MODE)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False, indent=2)
                fh.write("\n")
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp, self.path / name)
        except BaseException:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise

    def delete(self, name: str) -> None:
        try:
            os.unlink(self.path / name)
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
                    fcntl.flock(fd, fcntl.LOCK_EX)
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
                    fcntl.flock(plock.fd, fcntl.LOCK_UN)
                    os.close(plock.fd)
                    plock.fd = None


def runtime_info(config: ConfigDir) -> dict | None:
    """runtime.json when a server with phone access is really running (its
    pid is alive); None otherwise. Read by `phonotrainer phone …`."""
    try:
        data = config.read_json(RUNTIME_FILE)
    except Exception:                                 # noqa: BLE001
        return None
    if not data or not isinstance(data.get("pid"), int):
        return None
    try:
        os.kill(data["pid"], 0)
    except ProcessLookupError:
        return None
    except PermissionError:
        pass                                          # alive, another user's
    return data
