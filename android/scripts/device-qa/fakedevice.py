"""A scripted stand-in for `adb` used by the device-QA unit tests; no device or adb is involved.

`FakeDevice.install()` replaces `_device.run`, so every adb call of the scripts lands here: settings live in a dict,
properties and package lists are fixed, files are a dict, and every shell command is recorded.
"""

from __future__ import annotations

import shlex
import subprocess
from unittest import mock

import _device


class FakeDevice:
    def __init__(self, emulator: bool = True, packages=(), files=None, services: str = ""):
        self.emulator = emulator
        self.settings: dict[tuple[str, str], str] = {}
        self.packages = list(packages)
        self.files = dict(files or {})
        self.services = services
        self.commands: list[str] = []
        self.exec_out: dict[str, bytes] = {}

    def install(self, test_case) -> "FakeDevice":
        patcher = mock.patch.object(_device, "run", self.run)
        patcher.start()
        test_case.addCleanup(patcher.stop)
        return self

    def run(self, argv, **kwargs) -> subprocess.CompletedProcess:
        args = list(argv)
        if args[:1] == ["adb"]:
            args = args[1:]
            if args[:1] == ["-s"]:
                args = args[2:]
        text = kwargs.get("text", False)
        if args[:1] == ["exec-out"]:
            command = " ".join(args[1:])
            self.commands.append("exec-out " + command)
            data = self.exec_out.get(command, b"")
            return subprocess.CompletedProcess(argv, 0, data.decode() if text else data, "" if text else b"")
        if args[:1] == ["shell"]:
            command = " ".join(args[1:])
            self.commands.append(command)
            out = self.shell(command)
            return subprocess.CompletedProcess(argv, 0, out if text else out.encode(), "" if text else b"")
        self.commands.append(" ".join(args))
        return subprocess.CompletedProcess(argv, 0, "" if text else b"", "" if text else b"")

    def shell(self, command: str) -> str:
        words = shlex.split(command)
        if words[:2] == ["settings", "get"]:
            return self.settings.get((words[2], words[3]), "null") + "\n"
        if words[:2] == ["settings", "put"]:
            self.settings[(words[2], words[3])] = words[4]
            return ""
        if words[:2] == ["settings", "delete"]:
            self.settings.pop((words[2], words[3]), None)
            return ""
        if words[:1] == ["getprop"]:
            props = {"ro.kernel.qemu": "1" if self.emulator else "", "ro.boot.qemu": "1" if self.emulator else "",
                     "sys.boot_completed": "1"}
            return props.get(words[1], "") + "\n"
        if words[:3] == ["pm", "list", "packages"]:
            return "".join(f"package:{p}\n" for p in self.packages)
        if words[:2] == ["pm", "path"]:
            return f"package:/data/app/{words[2]}/base.apk\n" if words[2] in self.packages else ""
        if words[:3] == ["cmd", "package", "query-services"]:
            return self.services
        if words[:1] == ["ls"]:
            directory = words[1]
            names = sorted(p.rsplit("/", 1)[1] for p in self.files if p.rsplit("/", 1)[0] == directory)
            return "\n".join(names) + ("\n" if names else "")
        if words[:1] == ["cat"]:
            return self.files.get(words[1], "")
        return ""
