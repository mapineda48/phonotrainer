"""Tests of image_check.py without an emulator: what it reads from dumpsys and screencap, the capture's format, the
clock arithmetic, the adb target, the emulator command line, the time budget and a whole check against a scripted
device."""

from __future__ import annotations

import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Callable
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import host_computer  # noqa: E402
import image_check as ic  # noqa: E402
import test_capture as tc  # noqa: E402  (its synthetic pcap builders)

# Shaped like `dumpsys connectivity` on the API 36 google_apis image, shortened.
WIFI = """NetworkProviders for:
  6: WifiNetworkFactory
Active default network: 102
Current Networks:
  NetworkAgentInfo{network{101}  handle{1}  ni{MOBILE CONNECTED} nc{[ Transports: CELLULAR Capabilities: INTERNET]}}
  NetworkAgentInfo{network{102}  handle{2}  ni{WIFI CONNECTED} nc{[ Transports: WIFI Capabilities: INTERNET&VALIDATED]}}
"""
CELLULAR = WIFI.replace("Active default network: 102", "Active default network: 101")
AIRPLANE = "NetworkProviders for:\nActive default network: none\nCurrent Networks:\n"


def raw_screen(width: int, height: int, pixel: Callable[[int], tuple] | None = None, header: int = 16) -> bytes:
    head = struct.pack("<III", width, height, 1) + (b"\0\0\0\0" if header == 16 else b"")
    body = bytearray()
    for index in range(width * height):
        body += bytes(pixel(index) if pixel else (0, 0, 0, 255))
    return head + bytes(body)


class Readings(unittest.TestCase):
    def test_the_default_networks_transports(self):
        self.assertEqual(["WIFI"], ic.default_transports(WIFI))
        self.assertEqual(["CELLULAR"], ic.default_transports(CELLULAR))
        self.assertIsNone(ic.default_transports(AIRPLANE))
        self.assertEqual([], ic.default_transports("Active default network: 7\n"))
        self.assertTrue(ic.has_wifi(["WIFI"]) and not ic.has_wifi(None) and not ic.has_wifi(["CELLULAR"]))

    def test_a_black_screen_is_not_drawn_and_a_real_one_is(self):
        black = ic.raw_screen_stats(raw_screen(60, 40))
        self.assertEqual((60, 40), black[:2])
        self.assertFalse(ic.drawn(black))
        flat = ic.raw_screen_stats(raw_screen(60, 40, lambda i: (240, 240, 245, 255)))
        self.assertFalse(ic.drawn(flat), "one flat colour is a blank surface, not a drawn screen")
        busy = ic.raw_screen_stats(raw_screen(60, 40, lambda i: ((i * 7) % 256, (i * 13) % 256, (i * 29) % 256, 255),
                                              header=12))
        self.assertTrue(ic.drawn(busy))
        with self.assertRaises(ValueError):
            ic.raw_screen_stats(b"\x89PNG\r\n\x1a\n" + b"\0" * 40)

    def test_the_capture_format(self):
        self.assertEqual("classic pcap", ic.pcap_kind(b"\xd4\xc3\xb2\xa1"))
        self.assertEqual("classic pcap", ic.pcap_kind(b"\xa1\xb2\x3c\x4d"))
        self.assertEqual("pcapng", ic.pcap_kind(b"\x0a\x0d\x0d\x0a"))
        self.assertEqual("unknown", ic.pcap_kind(b"PK\x03\x04"))

    def test_clock_arithmetic_and_buckets(self):
        self.assertEqual("20261011", ic.next_day("20261010"))
        self.assertEqual("20270101", ic.next_day("20261231"))
        self.assertEqual("20280229", ic.next_day("20280228"))
        self.assertEqual("rare", ic.bucket_name("40"))
        self.assertEqual("working_set", ic.bucket_name("working_set\n"))
        self.assertEqual("active", ic.bucket_name("garbage"))


class Target(unittest.TestCase):
    def test_adb_always_names_its_own_emulator(self):
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 0, "List of devices attached\nemulator-5554\tdevice\n", "")

        with mock.patch.dict("os.environ", {"ANDROID_SERIAL": "emulator-5554"}):
            emu = ic.Emulator(5580, run=run)
            emu.shell("getprop ro.kernel.qemu")
            self.assertFalse(emu.present(), "another emulator's serial is not ours")
        self.assertEqual(["adb", "-s", "emulator-5580", "shell", "getprop ro.kernel.qemu"], calls[0])
        self.assertTrue(ic.Emulator(5554, run=run).present())

    def test_the_emulator_command_line(self):
        command = ic.emulator_command(5580, "swangle_indirect", Path("/evidence/capture.pcap"))
        for expected in (["-port", "5580"], ["-gpu", "swangle_indirect"], ["-tcpdump", "/evidence/capture.pcap"],
                         ["-avd", ic.AVD_NAME]):
            index = command.index(expected[0])
            self.assertEqual(expected, command[index:index + 2])
        for flag in ("-no-window", "-no-snapshot", "-wipe-data", "-no-audio"):
            self.assertIn(flag, command)
        index = command.index("-netsim-args")
        self.assertEqual(["-netsim-args", "--pcap"], command[index:index + 2], "the Wi-Fi network is captured too")

    def test_an_odd_port_is_refused(self):
        with self.assertRaises(SystemExit):
            with mock.patch("sys.stderr"):
                ic.main(["--port", "5581", "--no-lock"])


class Budget(unittest.TestCase):
    def test_checks_after_the_budget_are_recorded_as_not_run(self):
        report = ic.Report("image", "7", "swangle_indirect", "now")
        lines = []
        runner = ic.Runner(report, 60, say=lines.append)
        self.assertTrue(runner.check("first", lambda: (True, "fine")))
        self.assertFalse(runner.check("broken", lambda: 1 / 0))
        runner.deadline = 0
        self.assertFalse(runner.check("late", lambda: (True, "never called")))
        self.assertEqual([True, False, None], [r.ok for r in report.results])
        self.assertIn("ZeroDivisionError", report.results[1].detail)
        self.assertFalse(report.passed)
        self.assertTrue(any(line.startswith("  skip late") for line in lines))


class ScriptedEmulator(ic.Emulator):
    """Answers the shell commands of the checks like the API 36 google_apis image does."""

    def __init__(self):
        super().__init__(5580, run=None)
        self.settings = {"auto_time": "1", "airplane_mode_on": "0"}
        self.device_config = None
        self.wifi = True
        self.commands = []
        self.clock = "202610101200"

    def shell(self, command: str, timeout: float = 120) -> str:
        self.commands.append(command)
        if command == "dumpsys connectivity":
            if self.settings["airplane_mode_on"] == "1":
                return AIRPLANE
            return WIFI if self.wifi else CELLULAR
        if command.startswith("svc wifi "):
            self.wifi = command.endswith("enable")
            return ""
        if command.startswith("cmd connectivity airplane-mode "):
            self.settings["airplane_mode_on"] = "1" if command.endswith("enable") else "0"
            return ""
        if command.startswith("settings get global "):
            return self.settings.get(command.split()[-1], "null")
        if command.startswith("settings put global "):
            _, _, _, key, value = command.split()
            self.settings[key] = value
            return ""
        if command == "su 0 id":
            return "uid=0(root) gid=0(root)"
        if command == "date +%Y%m%d":
            return self.clock[:8]
        if command.startswith("su 0 toybox date $("):
            self.clock = self.clock[:8] + "2359"
            return ""
        if command == "date +%H%M":
            return self.clock[8:]
        if command == "date +%Y%m%d%H%M":
            return ic.next_day(self.clock[:8]) + "0000"
        if command.startswith("su 0 toybox date -u "):
            return ""
        if command == "date +%s":
            return "1791633600"
        return ""


HELLO = ('HTTP/1.1 200 OK\r\ncontent-type: application/json\r\n\r\n'
         '{"service":"phonotrainer","phone_access":true,"api_version":6}')
SETTINGS_TOP = ("  topResumedActivity=ActivityRecord{30781075 u0 "
                "com.android.settings/.homepage.SettingsHomepageActivity t8}")
SETTINGS_LAYOUT = '<hierarchy><node text="Network &amp; internet" /></hierarchy>'


class Screens(ScriptedEmulator):
    def __init__(self, top: str = SETTINGS_TOP, layout: str = SETTINGS_LAYOUT):
        super().__init__()
        self.top, self.layout = top, layout

    def shell(self, command: str, timeout: float = 120) -> str:
        self.commands.append(command)
        if command == "dumpsys activity activities":
            return self.top
        if command.startswith("uiautomator dump /dev/tty"):
            return ""                         # what a dump to the terminal gives without a pty
        if command.startswith(f"uiautomator dump {ic.UI_DUMP}"):
            return self.layout
        return ""


class Checks(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.object(ic.time, "sleep", lambda s: None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_wifi_toggle_and_airplane_mode_on_a_scripted_emulator(self):
        emu = ScriptedEmulator()
        ok, detail = ic.check_wifi_toggle(emu)
        self.assertTrue(ok, detail)
        self.assertIn("svc wifi disable", emu.commands)
        self.assertEqual("svc wifi enable", [c for c in emu.commands if c.startswith("svc")][-1])
        ok, detail = ic.check_airplane(emu)
        self.assertTrue(ok, detail)
        self.assertEqual("0", emu.settings["airplane_mode_on"])

    def test_a_radio_that_never_comes_back_fails(self):
        emu = ScriptedEmulator()
        with mock.patch.object(ic, "wait", lambda condition, timeout, interval=2.0: condition() or None):
            emu.shell = (lambda original: lambda command, timeout=120: (
                CELLULAR if command == "dumpsys connectivity" else original(command)))(emu.shell)
            ok, _ = ic.check_wifi_toggle(emu)
        self.assertFalse(ok)

    def test_the_clock_goes_past_midnight_and_everything_is_restored(self):
        emu = ScriptedEmulator()
        ok, detail = ic.check_clock(emu, sleep=lambda s: None, now=lambda: 1791633600.0)
        self.assertTrue(ok, detail)
        self.assertEqual("1", emu.settings["auto_time"])
        self.assertIn("su 0 toybox date -u 101012002026.00", emu.commands)   # the host's UTC time back

    def test_the_settings_app_is_read_from_a_dump_file(self):
        emu = Screens()
        ok, detail = ic.settings_shown(emu)
        self.assertTrue(ok, detail)
        dump = next(c for c in emu.commands if c.startswith("uiautomator"))
        self.assertIn(ic.UI_DUMP, dump)
        self.assertIn(f"rm -f {ic.UI_DUMP}", dump, "the dump file is removed")
        self.assertNotIn("/dev/tty", dump)
        self.assertEqual("com.android.settings/.homepage.SettingsHomepageActivity", ic.resumed_activity(SETTINGS_TOP))
        self.assertIsNone(ic.resumed_activity("nothing here"))
        self.assertFalse(ic.settings_shown(Screens(top=SETTINGS_TOP.replace("com.android.settings", "com.x")))[0])
        self.assertFalse(ic.settings_shown(Screens(layout="<hierarchy/>"))[0])

    def test_the_fake_computer_answers_only_while_the_request_stays_open(self):
        class Guest(ScriptedEmulator):
            def shell(self, command: str, timeout: float = 120) -> str:
                self.commands.append(command)
                if command == "dumpsys connectivity":
                    return WIFI
                # uvicorn drops a request whose client half-closed before the answer
                return HELLO if " nc " in f" {command}" and "sleep 3)" in command else ""

        class Running:
            def __init__(self, python, args):
                self.args = args

            def wait_ready(self, timeout):
                return host_computer.Announcement(port=18765)

            def stop(self):
                return 0

        emu, port = Guest(), []
        with mock.patch.object(host_computer, "find_python", lambda: sys.executable), \
                mock.patch.object(host_computer, "Running", Running):
            ok, detail = ic.check_fake_computer(emu, port)
        self.assertTrue(ok, detail)
        self.assertEqual([18765], port)
        self.assertIn("over ['WIFI']", detail)
        request = next(c for c in emu.commands if " nc " in f" {c}")
        self.assertIn("Host: 10.0.2.2:18765", request)
        self.assertTrue(request.endswith("10.0.2.2 18765"))


class Capture(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.tmp, ignore_errors=True))
        self.cellular = self.tmp / "capture.pcap"
        self.cellular.write_bytes(tc.session())
        self.wifi = self.tmp / "capture-wifi.pcap"

    def write_wifi(self, port: int) -> None:
        self.wifi.write_bytes(tc.pcap([tc.BEACON, tc.wifi(tc.ipv4(ic.capture.WIFI_GUEST, ic.EMULATOR_HOST, 6,
                                                                   tc.tcp(42000, port, 0x02)))], linktype=127))

    def test_the_connection_to_the_fake_computer_is_found_in_the_wifi_capture(self):
        self.write_wifi(18765)
        ok, detail = ic.check_capture(self.cellular, self.wifi, 18765)
        self.assertTrue(ok, detail)
        self.assertIn("802.11 frames read: True", detail)

    def test_without_the_wifi_capture_or_the_connection_it_fails(self):
        self.assertFalse(ic.check_capture(self.cellular, None, 18765)[0])
        self.write_wifi(18766)
        ok, detail = ic.check_capture(self.cellular, self.wifi, 18765)
        self.assertFalse(ok, "a connection on the cellular network alone does not prove the Wi-Fi capture")
        self.wifi.write_bytes(b"\x0a\x0d\x0d\x0a" + b"\0" * 40)
        ok, detail = ic.check_capture(self.cellular, self.wifi, 18765)
        self.assertFalse(ok)
        self.assertIn("pcapng", detail)


if __name__ == "__main__":
    unittest.main()
