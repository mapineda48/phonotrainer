"""`PhoneSettings`: every injectable seam of phone access, in one object.

`create_app(phone=PhoneSettings(...))` turns phone access on for that app.
This is a public, stable entry point: the tests build it with fakes, and the
host-side fake computer (`android/scripts/device-qa/host_computer.py`) builds
it with throwaway directories. New fields are only ever added at the end, with
defaults.
"""

from __future__ import annotations

import json
import shutil
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from . import DISCOVERY_PORT
from . import netinfo as netinfo_mod

FFPROBE_TIMEOUT_S = 30
RESOLVE_TIMEOUT_S = 3


def default_resolver(host: str) -> list[str]:
    """Every address `host` resolves to (the SSRF guard's view of DNS).

    `getaddrinfo` blocks in the C library and ignores socket timeouts: the
    caller bounds it to RESOLVE_TIMEOUT_S by running it on a worker thread.
    """
    infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    return sorted({info[4][0] for info in infos})


def default_runner(args: list[str], timeout: float | None = None) -> subprocess.CompletedProcess:
    """Run a tool (ffprobe, later ffmpeg) without a shell, capturing its output."""
    return subprocess.run(args, capture_output=True, timeout=timeout, check=False,
                          stdin=subprocess.DEVNULL)


def run_ffprobe(path: Path, runner: Callable[..., Any] = default_runner) -> dict | None:
    """ffprobe's JSON for `path`; None when ffprobe cannot read the file.

    Raises OSError (or subprocess.TimeoutExpired) when ffprobe itself cannot
    run: the caller then falls back to what the file suffix says.
    """
    result = runner(["ffprobe", "-v", "error", "-print_format", "json",
                     "-show_streams", "-show_format", str(path)],
                    timeout=FFPROBE_TIMEOUT_S)
    if result.returncode != 0:
        return None
    try:
        data = json.loads(result.stdout or b"{}")
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    return data if isinstance(data, dict) else None


@dataclass
class PhoneSettings:
    """The seams. Every field has a default that talks to the real system."""

    config_dir: Path | None = None                 # None: PHONOTRAINER_CONFIG_DIR, XDG, ~/.config
    clock: Callable[[], float] = time.time         # unix seconds; drives expiry and rate limits
    resolver: Callable[[str], list[str]] = default_resolver
    media_probe: Callable[[Path], dict | None] | None = None   # None: ffprobe through `runner`
    disk_usage: Callable[[Path], Any] = shutil.disk_usage
    netinfo: Callable[[], list[netinfo_mod.Interface]] = netinfo_mod.interfaces
    which: Callable[[str], str | None] = shutil.which
    runner: Callable[..., Any] = default_runner
    discovery_port: int = DISCOVERY_PORT
    # --- additions (keep at the end) ------------------------------------------
    port: int | None = None        # the listening port; set by `serve`, enables runtime.json
                                   # and the discovery responder (its replies carry the port)
    discovery_host: str = "0.0.0.0"   # the responder's bind address (tests use 127.0.0.1)

    def __post_init__(self) -> None:
        if self.config_dir is not None:
            self.config_dir = Path(self.config_dir)
        if self.media_probe is None:
            runner = self.runner
            self.media_probe = lambda path: run_ffprobe(path, runner)
