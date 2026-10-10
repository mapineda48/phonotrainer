#!/usr/bin/env python3
"""Proves that an emulator system image can run the [emulator] steps of device QA (delivery review D-10).

The [emulator] steps (torture.py, capture.py, the smoke tests against host_computer.py) need an image whose
ConnectivityManager reports Wi-Fi, whose Wi-Fi and airplane mode switch from adb, that reboots, whose clock can be set
from a root shell, that draws real screenshots and has the Settings app. The API 36 `aosp_atd` image draws black
screenshots and has no Settings app. This script checks an image in about ten minutes:

 1. it creates a throwaway AVD (in its own ANDROID_AVD_HOME, deleted at the end) and boots it headless on its own
    console port with both captures of capture.py (`-tcpdump` for the cellular network, netsimd `--pcap` for Wi-Fi),
    holding the one emulator lock of this computer (gmd_lock.py);
 2. it checks, each with its time: Wi-Fi as the default network's transport; screenshots of Home and Settings that are
    not black; the commands torture.py and settings_guard.py send (device_config, am compat, standby buckets,
    query-services), each restored; the host-side fake computer reached at 10.0.2.2 from the guest; `svc wifi
    disable` and `enable`; airplane mode on and off; the clock past midnight and back from `su 0 toybox date`, auto
    time restored; a reboot (a new boot id, Wi-Fi back);
 3. it stops the emulator, checks that both captures are classic pcap files in which capture.py finds the guest's
    connection to the fake computer (made over Wi-Fi), and writes report.json and the screenshots to the evidence
    directory. The Wi-Fi capture is large (Play services update themselves): it is deleted after the check unless
    --keep-captures moves it into the evidence directory.

It only ever talks to the emulator it started (`adb -s emulator-<port>`; ANDROID_SERIAL is ignored) and refuses a port
whose serial is already in use: never a physical device, never another run's emulator.

Usage: image_check.py [--image PACKAGE] [--port 5580] [--gpu swangle_indirect] [--evidence DIR] [--budget 600]
                      [--no-lock] [--keep-avd] [--keep-captures]
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import capture  # noqa: E402
import gmd_lock  # noqa: E402

DEFAULT_IMAGE = "system-images;android-36;google_apis;x86_64"
FALLBACK_IMAGE = "system-images;android-37.2;google_apis_playstore_ps16k;x86_64"
DEFAULT_PORT = 5580                 # an even console port in adb's scan range, above the managed devices' ones
AVD_NAME = "phonotrainer-image-check"
DEVICE_PROFILE = "pixel_9"          # the managed device's profile (architecture 21.5)
EMULATOR_HOST = "10.0.2.2"
# Packages every Google APIs image has, for the per-app commands; system apps that are exempt from standby buckets
# are skipped for the bucket check.
PROBE_PACKAGES = ("com.google.android.youtube", "com.android.chrome", "com.google.android.apps.photos",
                  "com.android.settings")
BUCKETS = {"5": "exempted", "10": "active", "20": "working_set", "30": "frequent", "40": "rare", "45": "restricted",
           "50": "never"}
# The request's stdin stays open a few seconds: uvicorn drops a request whose client half-closes before the answer,
# and toybox's nc half-closes at the end of its input.
NETCAT_FORMS = ("nc -w 10", "toybox nc -w 10")
PCAP_CLASSIC = {b"\xd4\xc3\xb2\xa1", b"\xa1\xb2\xc3\xd4", b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d"}
PCAPNG = b"\x0a\x0d\x0d\x0a"


def say(line: str) -> None:
    print(line, flush=True)        # a log or a pipe shows each check as it ends


# --- what the device says ------------------------------------------------------------------------------------------

def default_transports(dumpsys: str) -> list[str] | None:
    """The transports of the default network in `dumpsys connectivity` (["WIFI"], ["CELLULAR"]…); None when there is
    no default network."""
    active = re.search(r"^Active default network: (\S+)", dumpsys, re.M)
    if not active or active[1] == "none":
        return None
    marker = f"NetworkAgentInfo{{network{{{active[1]}}}"
    for line in dumpsys.splitlines():
        if marker in line:
            found = re.search(r"Transports: ([A-Z_|]+)", line)
            return found[1].split("|") if found else []
    return []


def raw_screen_stats(data: bytes) -> tuple[int, int, float, int]:
    """(width, height, share of lit pixels, distinct colours) of `screencap` without -p: a 16-byte header (width,
    height, format, colour space; 12 bytes on old releases) and RGBA pixels. About 4,000 pixels are sampled."""
    if len(data) < 12:
        raise ValueError("not a raw screencap")
    width, height = struct.unpack_from("<II", data)
    for header in (16, 12):
        if len(data) - header == width * height * 4:
            break
    else:
        raise ValueError("not a raw RGBA screencap")
    pixels = width * height
    step = max(1, pixels // 4001) | 1
    lit = sampled = 0
    colours = set()
    for index in range(0, pixels, step):
        at = header + 4 * index
        r, g, b = data[at], data[at + 1], data[at + 2]
        sampled += 1
        colours.add((r >> 3, g >> 3, b >> 3))
        if max(r, g, b) > 24:
            lit += 1
    return width, height, lit / sampled, len(colours)


def drawn(stats: tuple[int, int, float, int]) -> bool:
    """A screen that was really drawn: not black (at least 5 % lit) and not a single flat colour."""
    return stats[2] >= 0.05 and stats[3] >= 4


def pcap_kind(head: bytes) -> str:
    if head[:4] in PCAP_CLASSIC:
        return "classic pcap"
    if head[:4] == PCAPNG:
        return "pcapng"
    return "unknown"


def next_day(yyyymmdd: str) -> str:
    return (dt.datetime.strptime(yyyymmdd, "%Y%m%d") + dt.timedelta(days=1)).strftime("%Y%m%d")


def bucket_name(raw: str) -> str:
    raw = raw.strip()
    return BUCKETS.get(raw, raw if raw in BUCKETS.values() else "active")


# --- the emulator ----------------------------------------------------------------------------------------------------

class Emulator:
    """adb against exactly one emulator, by its serial (never ANDROID_SERIAL, never the only device)."""

    def __init__(self, port: int, run=subprocess.run) -> None:
        self.port = port
        self.serial = f"emulator-{port}"
        self.run = run

    def adb(self, *args: str, timeout: float = 120, binary: bool = False) -> subprocess.CompletedProcess:
        return self.run(["adb", "-s", self.serial, *args], capture_output=True, timeout=timeout, text=not binary)

    def shell(self, command: str, timeout: float = 120) -> str:
        done = self.adb("shell", command, timeout=timeout)
        return (done.stdout or "").strip()

    def present(self) -> bool:
        listed = self.run(["adb", "devices"], capture_output=True, timeout=30, text=True).stdout or ""
        return any(line.split("\t")[0] == self.serial for line in listed.splitlines()[1:])

    def booted(self) -> bool:
        try:
            return self.shell("getprop sys.boot_completed", timeout=15) == "1"
        except subprocess.TimeoutExpired:
            return False

    def is_emulator(self) -> bool:
        return "1" in (self.shell("getprop ro.kernel.qemu"), self.shell("getprop ro.boot.qemu"))

    def transports(self) -> list[str] | None:
        return default_transports(self.shell("dumpsys connectivity"))

    def boot_id(self) -> str:
        return self.shell("cat /proc/sys/kernel/random/boot_id")


def wait(condition: Callable[[], object], timeout: float, interval: float = 2.0):
    """The first truthy value of `condition` within `timeout` seconds, else None."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            value = condition()
        except subprocess.TimeoutExpired:
            value = None
        if value:
            return value
        if time.monotonic() >= deadline:
            return None
        time.sleep(interval)


def sdk_root() -> Path:
    return Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Android/Sdk")


def tool(name: str, *relative: str) -> str:
    for candidate in relative:
        path = sdk_root() / candidate
        if path.exists():
            return str(path)
    return shutil.which(name) or name


def emulator_command(port: int, gpu: str, pcap: Path, avd: str = AVD_NAME) -> list[str]:
    return [tool("emulator", "emulator/emulator"), "-avd", avd, "-port", str(port), "-no-window", "-no-audio",
            "-no-boot-anim", "-no-snapshot", "-wipe-data", "-gpu", gpu, "-tcpdump", str(pcap),
            *capture.NETSIM_PCAP_ARGS]


def image_revision(image: str) -> str | None:
    props = sdk_root().joinpath(*image.split(";")) / "source.properties"
    try:
        found = re.search(r"^Pkg\.Revision=(.+)$", props.read_text(encoding="utf-8"), re.M)
    except OSError:
        return None
    return found[1].strip() if found else None


# --- the checks ------------------------------------------------------------------------------------------------------

@dataclass
class Result:
    name: str
    ok: bool | None          # None: not run
    seconds: float
    detail: str


@dataclass
class Report:
    image: str
    revision: str | None
    gpu: str
    started: str
    results: list[Result] = field(default_factory=list)
    boot_seconds: float | None = None
    total_seconds: float | None = None

    @property
    def passed(self) -> bool:
        return bool(self.results) and all(r.ok for r in self.results)


class Runner:
    """Runs named checks against a time budget; a check returns (ok, detail) or raises."""

    def __init__(self, report: Report, budget: float, say=say) -> None:
        self.report = report
        self.deadline = time.monotonic() + budget
        self.say = say

    def remaining(self) -> float:
        return max(0.0, self.deadline - time.monotonic())

    def check(self, name: str, body: Callable[[], tuple[bool, str]]) -> bool:
        if self.remaining() <= 0:
            result = Result(name, None, 0.0, "not run: the time budget is spent")
        else:
            started = time.monotonic()
            try:
                ok, detail = body()
            except Exception as error:                  # noqa: BLE001 — a failed check is a result
                ok, detail = False, f"{type(error).__name__}: {error}"
            result = Result(name, ok, round(time.monotonic() - started, 1), detail)
        self.report.results.append(result)
        mark = {True: "ok  ", False: "FAIL", None: "skip"}[result.ok]
        self.say(f"  {mark} {name} ({result.seconds:.0f} s): {result.detail}")
        return bool(result.ok)


def wait_transport(emu: Emulator, want: Callable[[list[str] | None], bool], timeout: float) -> tuple[bool, object]:
    seen: list = []

    def probe():
        current = emu.transports()
        seen.append(current)
        return want(current)

    ok = bool(wait(probe, timeout))
    return ok, (seen[-1] if seen else "nothing read")


def has_wifi(transports) -> bool:
    return bool(transports) and "WIFI" in transports


def check_wifi(emu: Emulator, timeout: float) -> tuple[bool, str]:
    ok, last = wait_transport(emu, has_wifi, timeout)
    status = emu.shell("cmd wifi status")
    connected = "Wifi is connected" in status
    return ok and connected, f"default network transports {last}; " + (
        "Wi-Fi connected" if connected else "Wi-Fi not connected")


def check_wifi_toggle(emu: Emulator) -> tuple[bool, str]:
    emu.shell("svc wifi disable")
    off, during = wait_transport(emu, lambda t: not has_wifi(t), 45)
    emu.shell("svc wifi enable")
    on, after = wait_transport(emu, has_wifi, 90)
    return off and on, f"disabled: default network {during}; enabled again: {after}"


def check_airplane(emu: Emulator) -> tuple[bool, str]:
    emu.shell("cmd connectivity airplane-mode enable")
    on = emu.shell("settings get global airplane_mode_on") == "1"
    none, during = wait_transport(emu, lambda t: t is None, 45)
    emu.shell("cmd connectivity airplane-mode disable")
    off = emu.shell("settings get global airplane_mode_on") == "0"
    back, after = wait_transport(emu, has_wifi, 90)
    return on and none and off and back, (f"airplane_mode_on=1: {on}; default network {during}; "
                                          f"off again: {off}; then {after}")


def check_clock(emu: Emulator, sleep=time.sleep, now=time.time) -> tuple[bool, str]:
    """torture.py's midnight step: auto time off, 23:59:50 from a root shell, past midnight; then the host's UTC time
    back and auto time as it was."""
    auto_time = emu.shell("settings get global auto_time")
    root = emu.shell("su 0 id")
    day = emu.shell("date +%Y%m%d")
    try:
        emu.shell("settings put global auto_time 0")
        emu.shell("su 0 toybox date $(date +%m%d)2359$(date +%Y).50")
        set_to = emu.shell("date +%H%M")
        sleep(15)
        after = emu.shell("date +%Y%m%d%H%M")
    finally:
        utc = dt.datetime.fromtimestamp(now(), dt.timezone.utc).strftime("%m%d%H%M%Y.%S")
        emu.shell(f"su 0 toybox date -u {utc}")
        emu.shell(f"settings put global auto_time {auto_time if auto_time in ('0', '1') else '1'}")
    drift = abs(int(emu.shell("date +%s") or 0) - now())
    restored = emu.shell("settings get global auto_time") == (auto_time if auto_time in ("0", "1") else "1")
    passed = (root.startswith("uid=0") and set_to == "2359" and after[:8] == next_day(day)
              and after[8:] == "0000" and drift < 10 and restored)
    return passed, (f"root shell: {root.startswith('uid=0')}; set to {set_to}; 15 s later {after[8:]} on the next day: "
                    f"{after[:8] == next_day(day)}; clock restored within {drift:.0f} s; "
                    f"auto_time restored: {restored}")


def check_platform_commands(emu: Emulator) -> tuple[bool, str]:
    """The commands torture.py (fgs-timeout, standby-*) and settings_guard.py send, each restored."""
    notes, ok = [], True
    previous = emu.shell("device_config get activity_manager data_sync_fgs_timeout_duration")
    emu.shell("device_config put activity_manager data_sync_fgs_timeout_duration 60000")
    changed = emu.shell("device_config get activity_manager data_sync_fgs_timeout_duration") == "60000"
    if previous in ("", "null"):
        emu.shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
    else:
        emu.shell(f"device_config put activity_manager data_sync_fgs_timeout_duration {previous}")
    restored = emu.shell("device_config get activity_manager data_sync_fgs_timeout_duration") == (previous or "null")
    ok &= changed and restored
    notes.append(f"device_config: set {changed}, restored {restored}")

    installed = emu.shell("pm list packages")
    packages = [p for p in PROBE_PACKAGES if f"package:{p}" in installed.split()]
    if not packages:
        return False, "no probe package installed"
    enabled = emu.shell(f"am compat enable FGS_INTRODUCE_TIME_LIMITS {packages[0]}")
    reset = emu.shell(f"am compat reset FGS_INTRODUCE_TIME_LIMITS {packages[0]}")
    compat = enabled.startswith("Enabled change") and reset.startswith("Reset change")
    ok &= compat
    # On a `user` build am compat only overrides debuggable apps: there the probe (a system app) fails, while the
    # app's debug build, which torture.py targets, would pass. The device's answer says which case it is.
    said = "" if compat else f" ({(enabled.splitlines() or ['no answer'])[0][:160]})"
    notes.append(f"am compat FGS_INTRODUCE_TIME_LIMITS on {packages[0]}: {compat}{said}")

    probe = next((p for p in packages if emu.shell(f"am get-standby-bucket {p}") not in ("5", "")), None)
    if probe is None:
        notes.append("standby buckets: every probe app is exempted, not checked")
        ok = False
    else:
        before = emu.shell(f"am get-standby-bucket {probe}")
        seen = []
        for bucket, number in (("rare", "40"), ("restricted", "45")):
            emu.shell(f"am set-standby-bucket {probe} {bucket}")
            seen.append(emu.shell(f"am get-standby-bucket {probe}") == number)
        emu.shell(f"am set-standby-bucket {probe} {bucket_name(before)}")
        back = emu.shell(f"am get-standby-bucket {probe}") == before.strip()
        ok &= all(seen) and back
        notes.append(f"standby buckets rare/restricted: {seen}, restored {back}")

    services = emu.shell("cmd package query-services --components -a android.accessibilityservice.AccessibilityService")
    talkback = next((line for line in services.splitlines() if "TalkBackService" in line), None)
    notes.append(f"query-services lists TalkBack: {talkback or 'no'}")
    ok &= bool(services.strip())
    return ok, "; ".join(notes)


def screenshot(emu: Emulator, evidence: Path, name: str) -> tuple[bool, str]:
    raw = emu.adb("exec-out", "screencap", binary=True, timeout=60).stdout
    stats = raw_screen_stats(raw)
    png = emu.adb("exec-out", "screencap -p", binary=True, timeout=60).stdout
    (evidence / f"{name}.png").write_bytes(png)
    return drawn(stats), f"{stats[0]}x{stats[1]}, {stats[2]:.0%} lit, {stats[3]} colours sampled; {name}.png"


def resumed_activity(dumpsys: str) -> str | None:
    """The component of the top resumed activity in `dumpsys activity activities`."""
    found = re.search(r"topResumedActivity=ActivityRecord\{\S+ u\d+ (\S+)", dumpsys)
    return found[1] if found else None


UI_DUMP = "/data/local/tmp/phonotrainer-image-check-ui.xml"


def settings_shown(emu: Emulator) -> tuple[bool, str]:
    """The Settings app on top with its "Network & internet" entry laid out. `uiautomator dump` goes to a file: with
    no terminal (adb shell without a pty) a dump to /dev/tty prints nothing."""
    top = resumed_activity(emu.shell("dumpsys activity activities")) or "nothing"
    layout = emu.shell(f"uiautomator dump {UI_DUMP} >/dev/null && cat {UI_DUMP}; rm -f {UI_DUMP}", timeout=60)
    entry = "Network &amp; internet" in layout
    return top.startswith("com.android.settings/") and entry, f"top activity {top}, network entry laid out: {entry}"


def check_screens(emu: Emulator, evidence: Path) -> tuple[bool, str]:
    emu.shell("input keyevent KEYCODE_WAKEUP")
    emu.shell("wm dismiss-keyguard")
    emu.shell("input keyevent KEYCODE_HOME")
    time.sleep(3)
    home_ok, home = screenshot(emu, evidence, "home")
    emu.shell("am start -W -a android.settings.SETTINGS")
    seen: list = []
    listed = wait(lambda: seen.append(settings_shown(emu)) or seen[-1][0], 30)
    settings_ok, settings = screenshot(emu, evidence, "settings")
    emu.shell("input keyevent KEYCODE_HOME")
    shown = seen[-1][1] if seen else "unread"
    return home_ok and settings_ok and bool(listed), f"Home: {home}. Settings app: {shown}; {settings}"


def check_fake_computer(emu: Emulator, port_holder: list) -> tuple[bool, str]:
    """The guest reaches the host-side fake computer at 10.0.2.2 (server-contract 12)."""
    import host_computer

    python = host_computer.find_python()
    if python is None:
        return False, "no interpreter with the server's packages: host_computer.py cannot start"
    running = host_computer.Running(python, ["--port", "0"])
    answer, used, network = "", None, emu.transports()
    try:
        info = running.wait_ready(120)
        port_holder.append(info.port)
        request = (f"(printf 'GET /api/phone/hello HTTP/1.0\\r\\nHost: {EMULATOR_HOST}:{info.port}\\r\\n\\r\\n'; "
                   f"sleep 3)")
        for netcat in NETCAT_FORMS:
            answer = emu.shell(f"{request} | {netcat} {EMULATOR_HOST} {info.port}", timeout=60)
            if '"service":"phonotrainer"' in answer:
                used = netcat
                break
    finally:
        code = running.stop()
    ok = '"service":"phonotrainer"' in answer and '"phone_access":true' in answer
    first = answer.splitlines()[0] if answer else "no answer"
    return ok and code == 0, (f"GET /api/phone/hello from the guest over {network} with `{used}`: {first}; "
                              f"fake computer stopped with {code}")


def check_reboot(emu: Emulator) -> tuple[bool, str]:
    before = emu.boot_id()
    started = time.monotonic()
    emu.adb("reboot", timeout=60)
    back = wait(lambda: emu.booted() and emu.boot_id() not in ("", before), 240, interval=3)
    seconds = time.monotonic() - started
    wifi, transports = wait_transport(emu, has_wifi, 90) if back else (False, None)
    return bool(back) and wifi, f"new boot id: {bool(back)} after {seconds:.0f} s; Wi-Fi back: {transports}"


def check_capture(cellular: Path, wifi: Path | None, computer_port: int | None) -> tuple[bool, str]:
    """Both captures readable by capture.py, the Wi-Fi one present, and the guest's connection to the fake computer
    (made while Wi-Fi was the default network) in them."""
    if wifi is None:
        return False, ("netsimd wrote no Wi-Fi capture (was it already running without --pcap?): the default "
                       "network's traffic would be missing from every X-AC1 run")
    captures, sizes = [], []
    for path in (cellular, wifi):
        data = path.read_bytes()
        kind = pcap_kind(data[:4])
        if kind != "classic pcap":
            return False, f"{path.name}: {kind} ({len(data)} bytes): capture.py reads classic pcap only"
        captures.append(data)
        sizes.append(f"{path.name} {len(data) // 1024} KiB")
    report = capture.analyse(*captures)
    wifi_only = capture.analyse(captures[1])
    reached = computer_port is not None and wifi_only.flows[("tcp", EMULATOR_HOST, computer_port)] > 0
    rows = capture.verdicts(report, (EMULATOR_HOST, computer_port or 0), [], capture.DEFAULT_PLATFORM_NAMES)
    others = sorted({name or "unnamed" for verdict, _, _, name in rows if verdict == "UNEXPECTED"})
    return reached and report.has_wifi, (
        f"classic pcap: {', '.join(sizes)}; 802.11 frames read: {report.has_wifi}; guest addresses "
        f"{', '.join(report.guests)}; the connection to the fake computer in the Wi-Fi capture: {reached}; "
        f"platform traffic outside capture.py's default names: {', '.join(others) or 'none'}")


# --- the run ---------------------------------------------------------------------------------------------------------

def create_avd(image: str, avd_home: Path) -> None:
    env = dict(os.environ, ANDROID_AVD_HOME=str(avd_home))
    done = subprocess.run([tool("avdmanager", "cmdline-tools/latest/bin/avdmanager"), "create", "avd", "-n", AVD_NAME,
                           "-k", image, "-d", DEVICE_PROFILE, "--force"], input="no\n", capture_output=True,
                          text=True, env=env, timeout=120)
    if done.returncode != 0:
        raise RuntimeError(f"avdmanager failed: {(done.stderr or done.stdout).strip()[-300:]}")


def stop_emulator(emu: Emulator, process: subprocess.Popen) -> None:
    if process.poll() is None:
        try:
            emu.adb("emu", "kill", timeout=30)
        except subprocess.TimeoutExpired:
            pass
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def run_checks(args, say=say) -> Report:
    evidence = Path(args.evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    report = Report(args.image, image_revision(args.image), args.gpu, dt.datetime.now(dt.timezone.utc).isoformat())
    runner = Runner(report, args.budget, say)
    emu = Emulator(args.port)
    if emu.present():
        raise SystemExit(f"{emu.serial} is already in use: pick another --port (never another run's emulator).")
    avd_home = Path(tempfile.mkdtemp(prefix="phonotrainer-avd-"))
    pcap = evidence / "capture.pcap"
    started = time.monotonic()
    since = time.time() - 1
    process = None
    computer_port: list[int] = []
    netsim_free = capture.wait_netsimd_gone(30)     # netsimd only records when this run starts it
    try:
        create_avd(args.image, avd_home)
        with (evidence / "emulator.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(emulator_command(args.port, args.gpu, pcap), stdout=log,
                                       stderr=subprocess.STDOUT, env=dict(os.environ, ANDROID_AVD_HOME=str(avd_home)),
                                       start_new_session=True)
        say(f"booting {args.image} as {emu.serial} …")
        booted = wait(lambda: process.poll() is not None or (emu.present() and emu.booted()), 300, interval=3)
        report.boot_seconds = round(time.monotonic() - started, 1)
        if not booted or process.poll() is not None:
            runner.check("boot", lambda: (False, f"not booted after {report.boot_seconds:.0f} s (see emulator.log)"))
            return report
        if not emu.is_emulator():
            raise SystemExit("the device on the chosen port is not an emulator; stopping")
        runner.check("boot", lambda: (True, f"booted in {report.boot_seconds:.0f} s, "
                                            f"build {emu.shell('getprop ro.build.type')}"))
        runner.check("wifi-transport", lambda: check_wifi(emu, min(120, runner.remaining())))
        runner.check("screenshots", lambda: check_screens(emu, evidence))
        runner.check("platform-commands", lambda: check_platform_commands(emu))
        runner.check("fake-computer-at-10.0.2.2", lambda: check_fake_computer(emu, computer_port))
        runner.check("svc-wifi", lambda: check_wifi_toggle(emu))
        runner.check("airplane-mode", lambda: check_airplane(emu))
        runner.check("clock-past-midnight", lambda: check_clock(emu))
        runner.check("reboot", lambda: check_reboot(emu))
    finally:
        if process is not None:
            stop_emulator(emu, process)
        if args.keep_avd:
            say(f"kept the AVD {AVD_NAME} in {avd_home} (ANDROID_AVD_HOME)")
        else:
            shutil.rmtree(avd_home, ignore_errors=True)
    capture.wait_netsimd_gone(30)                  # it closes its captures when it stops
    wifi = capture.wifi_capture(AVD_NAME, since) if netsim_free else None
    capture.discard_other_captures(AVD_NAME, since)
    if pcap.exists():
        runner.check("capture", lambda: check_capture(pcap, wifi, computer_port[0] if computer_port else None))
    if wifi is not None:
        if args.keep_captures:
            shutil.move(str(wifi), capture.wifi_path(pcap))
        else:
            wifi.unlink(missing_ok=True)
    report.total_seconds = round(time.monotonic() - started, 1)
    return report


def write_report(report: Report, evidence: Path) -> Path:
    data = asdict(report)
    data["passed"] = report.passed
    path = evidence / "report.json"
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--image", default=DEFAULT_IMAGE, help=f"the system image (default {DEFAULT_IMAGE}; "
                                                                 f"fallback {FALLBACK_IMAGE})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="the emulator's console port (even)")
    parser.add_argument("--gpu", default="swangle_indirect", help="emulator -gpu mode (swiftshader_indirect in CI)")
    parser.add_argument("--evidence", help="where report.json, screenshots and the capture go "
                                           "(default android/artifacts/qa-evidence/image-check-<time>)")
    parser.add_argument("--budget", type=float, default=600, help="seconds for the whole run (default 600)")
    parser.add_argument("--no-lock", action="store_true", help="the caller already holds the GMD lock")
    parser.add_argument("--keep-avd", action="store_true", help="keep the throwaway AVD (its path is printed)")
    parser.add_argument("--keep-captures", action="store_true",
                        help="move the Wi-Fi capture into the evidence directory instead of deleting it")
    args = parser.parse_args(argv)
    if args.port % 2:
        parser.error("the console port must be even (adb uses the next one)")
    if not args.evidence:
        stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        args.evidence = str(Path(__file__).resolve().parents[2] / "artifacts" / "qa-evidence" / f"image-check-{stamp}")

    def go() -> int:
        report = run_checks(args)
        path = write_report(report, Path(args.evidence))
        print(f"image_check: {'PASS' if report.passed else 'FAIL'} for {args.image} in {report.total_seconds} s; "
              f"report: {path}")
        return 0 if report.passed else 1

    if args.no_lock:
        return go()
    with gmd_lock.gmd_lock():
        return go()


if __name__ == "__main__":
    sys.exit(main())
