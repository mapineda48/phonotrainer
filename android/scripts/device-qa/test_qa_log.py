"""Tests of qa_log.py: a pairing exchange, addresses, serials, paths and the local denylist are redacted."""

from __future__ import annotations

import datetime as dt
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "hygiene"))
import qa_log  # noqa: E402
import sensitive  # noqa: E402
from testsupport import planted  # noqa: E402

CODE = "65" + "4321"
TOKEN = "pt1" + "_" + ("Zz9-" * 11)[:43]
DEVICE = "dev" + "_" + "k" * 26
SERVER = "srv" + "_" + "m" * 26
HOST = "studydesk-7f3a"
SERIAL = "R5" + "CW12AB34CD"


def pairing_exchange() -> str:
    lan = ".".join(["192", "168", "7", "23"])
    return "\n".join([
        f"Pairing code: {CODE[:3]} {CODE[3:]}   (valid for 5 minutes, single use)",
        f"POST http://{lan}:8000/api/phone/pair",
        '{"co' + f'de": "{CODE}", "device_name": "Pixel 9", "app_version": 1}}',
        f'201 {{"device_id": "{DEVICE}", "token": "{TOKEN}", "server_id": "{SERVER}"}}',
        f"Authorization: PT1 device={DEVICE}, ts=1791622800, nonce={'a' * 32}, mac={'b' * 64}",
        f"Paired: Pixel 9 (from {lan}) on {HOST}, adb -s {SERIAL}, gateway {planted('mac')}",
        f"log at {planted('home')}/qa.txt, shared {planted('video-url')}",
    ])


class Redaction(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.log = qa_log.QaLog(
            Path(self.tmp.name) / "qa-log.md",
            denylist=[sensitive.DenyEntry("host name", HOST)],
            serials=[SERIAL],
            clock=lambda: dt.datetime(2026, 10, 10, 12, 0, tzinfo=dt.timezone.utc),
        )

    def test_a_pairing_exchange_leaves_nothing_sensitive(self):
        self.log.write(pairing_exchange())
        text = self.log.path.read_text("utf-8")
        for secret in (CODE, TOKEN, DEVICE, SERVER, HOST, SERIAL, planted("mac"), planted("home"),
                       ".".join(["192", "168", "7", "23"]), planted("video-url"), "b" * 64):
            with self.subTest(secret=secret[:6]):
                self.assertNotIn(secret, text)
        self.assertIn("Pairing code: <redacted>", text)
        self.assertIn("<private IPv4>", text)
        self.assertIn("PT1 <redacted>", text)
        self.assertIn("<host name>", text)
        self.assertIn("<device serial>", text)
        self.assertIn("<MAC>", text)
        self.assertIn("<link>", text)
        self.assertIn("Pixel 9", text)  # ordinary text stays
        self.assertTrue(text.startswith("# QA log\n"))
        self.assertIn("- 2026-10-10T12:00:00Z ", text)

    def test_the_redacted_log_passes_the_repository_scan(self):
        written = self.log.write(pairing_exchange())
        for line in written.splitlines():
            self.assertEqual([], list(sensitive.find_credentials(line)), line)
            self.assertEqual([], list(sensitive.find_addresses(line)), line)
            self.assertEqual([], list(sensitive.find_macs(line)), line)

    def test_allowed_addresses_and_the_package_stay(self):
        out = self.log.redact("http://10.0.2.2:8000 and 127.0.0.1 and com.qauser7.phonotrainer")
        self.assertEqual("http://10.0.2.2:8000 and 127.0.0.1 and com.qauser7.phonotrainer", out)
        log = qa_log.QaLog(self.log.path, denylist=[sensitive.DenyEntry("user name", "qauser7")], serials=[])
        self.assertEqual("com.qauser7.phonotrainer by <user name>", log.redact("com.qauser7.phonotrainer by qauser7"))

    def test_restore_table(self):
        table = qa_log.restore_table([
            {"name": "system/font_scale", "before": "1.0", "set": "2", "restored": "1.0"},
            {"name": "global/window_animation_scale", "before": "null", "set": "0", "restored": None},
        ])
        self.assertIn("| system/font_scale | 1.0 | 2 | 1.0 | yes |", table)
        self.assertIn("| global/window_animation_scale | null | 0 | not restored | NO |", table)

    def test_blocks_are_redacted_too(self):
        self.log.write_block(f"Run on {HOST}", f"serial {SERIAL}")
        text = self.log.path.read_text("utf-8")
        self.assertNotIn(HOST, text)
        self.assertNotIn(SERIAL, text)


if __name__ == "__main__":
    unittest.main()
