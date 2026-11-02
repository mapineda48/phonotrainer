"""Windows-only process helpers (kernel32 through ctypes). Imported only when
`sys.platform == "win32"`: Linux and macOS never load this module.

- `pid_alive`: whether a process exists, without signalling it. On Windows
  `os.kill(pid, 0)` is not a probe: signal 0 is CTRL_C_EVENT, sent to every
  process on the console.
- `relaunch_in_utf8_mode`: run this same command again under `python -X utf8`
  and wait for it (the `phonotrainer` CLI does this once when UTF-8 mode is
  off, because third-party code opens its data files without `encoding=`).
"""

from __future__ import annotations

import ctypes
import os
import signal
import subprocess
import sys
from ctypes import wintypes

_kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

SYNCHRONIZE = 0x00100000
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
ERROR_ACCESS_DENIED = 5
WAIT_TIMEOUT = 0x102
JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK = 0x1000
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9

_kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
_kernel32.OpenProcess.restype = wintypes.HANDLE
_kernel32.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
_kernel32.WaitForSingleObject.restype = wintypes.DWORD
_kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
_kernel32.CloseHandle.restype = wintypes.BOOL
_kernel32.CreateJobObjectW.argtypes = (wintypes.LPVOID, wintypes.LPCWSTR)
_kernel32.CreateJobObjectW.restype = wintypes.HANDLE
_kernel32.SetInformationJobObject.argtypes = (wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID,
                                              wintypes.DWORD)
_kernel32.SetInformationJobObject.restype = wintypes.BOOL
_kernel32.AssignProcessToJobObject.argtypes = (wintypes.HANDLE, wintypes.HANDLE)
_kernel32.AssignProcessToJobObject.restype = wintypes.BOOL


def pid_alive(pid: int) -> bool:
    """True while a process with this pid exists. A process of another user
    (access denied) counts as alive. Nothing is ever sent to it."""
    if not isinstance(pid, int) or pid <= 0 or pid > 0xFFFFFFFF:
        return False
    handle = _kernel32.OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        # ERROR_INVALID_PARAMETER: no such process. Access denied: it exists.
        return ctypes.get_last_error() == ERROR_ACCESS_DENIED
    try:
        # Not GetExitCodeProcess == STILL_ACTIVE: a process that exited with
        # code 259 would look alive.
        return _kernel32.WaitForSingleObject(handle, 0) == WAIT_TIMEOUT
    finally:
        _kernel32.CloseHandle(handle)


class _IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_ulonglong) for name in (
        "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]


class _BasicLimits(ctypes.Structure):
    _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong),
                ("PerJobUserTimeLimit", ctypes.c_longlong),
                ("LimitFlags", wintypes.DWORD),
                ("MinimumWorkingSetSize", ctypes.c_size_t),
                ("MaximumWorkingSetSize", ctypes.c_size_t),
                ("ActiveProcessLimit", wintypes.DWORD),
                ("Affinity", ctypes.c_size_t),
                ("PriorityClass", wintypes.DWORD),
                ("SchedulingClass", wintypes.DWORD)]


class _ExtendedLimits(ctypes.Structure):
    _fields_ = [("BasicLimitInformation", _BasicLimits),
                ("IoInfo", _IoCounters),
                ("ProcessMemoryLimit", ctypes.c_size_t),
                ("JobMemoryLimit", ctypes.c_size_t),
                ("PeakProcessMemoryUsed", ctypes.c_size_t),
                ("PeakJobMemoryUsed", ctypes.c_size_t)]


def _end_with_this_process(process_handle: int) -> int | None:
    """Put the child in a job that is killed when this process ends, however
    it ends (Task Manager, `Popen.kill()`), so a relaunched server never
    outlives the process its caller started. Only the child is in the job:
    the processes it starts (ffmpeg, a browser) break away silently. Returns
    the job handle, which must stay open; None when the job cannot be made."""
    job = _kernel32.CreateJobObjectW(None, None)
    if not job:
        return None
    limits = _ExtendedLimits()
    limits.BasicLimitInformation.LimitFlags = (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                                               | JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK)
    if (_kernel32.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                                          ctypes.byref(limits), ctypes.sizeof(limits))
            and _kernel32.AssignProcessToJobObject(job, process_handle)):
        return job
    _kernel32.CloseHandle(job)
    return None


def relaunch_in_utf8_mode() -> int:
    """Run this interpreter's command line again under `-X utf8` (with
    `PYTHONUTF8=1` in its environment), wait for it and return its exit code.

    The child shares this console, so Ctrl+C and Ctrl+Break reach it and it
    stops the way it always does; this process ignores both while it waits."""
    env = dict(os.environ, PYTHONUTF8="1")
    argv = [sys.executable, "-X", "utf8", *sys.orig_argv[1:]]
    child = subprocess.Popen(argv, env=env)
    job = _end_with_this_process(int(child._handle))   # noqa: F841 — open until exit
    for name in ("SIGINT", "SIGBREAK"):
        signal.signal(getattr(signal, name), signal.SIG_IGN)
    code = child.wait()
    # Windows exit codes are unsigned 32-bit (0xC000013A after Ctrl+C);
    # sys.exit takes a signed C int.
    return code - (1 << 32) if code >= (1 << 31) else code
