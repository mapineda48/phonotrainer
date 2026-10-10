"""Tests of settings_guard.py against a scripted device: exact restore, TalkBack read from the device, the log."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qa_log  # noqa: E402
import settings_guard as sg  # noqa: E402
from fakedevice import FakeDevice  # noqa: E402

SAMSUNG_TALKBACK = "com.samsung.android.accessibility.talkback"
GOOGLE_TALKBACK = "com.google.android.marvin.talkback"


class Guard(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.log = qa_log.QaLog(Path(self.tmp.name) / "log.md", denylist=[], serials=[])
        self.session = Path(self.tmp.name) / "session.json"

    def guard(self) -> sg.SettingsGuard:
        return sg.SettingsGuard(self.log, self.session)

    def test_font_and_animation_scales_are_restored_exactly(self):
        device = FakeDevice().install(self)
        device.settings[("system", "font_scale")] = "1.15"
        guard = self.guard()
        guard.font_scale(2.0)
        guard.animations(0)
        self.assertEqual("2", device.settings[("system", "font_scale")])
        self.assertEqual("0", device.settings[("global", "animator_duration_scale")])
        rows = guard.restore()
        self.assertEqual("1.15", device.settings[("system", "font_scale")])
        self.assertNotIn(("global", "animator_duration_scale"), device.settings)  # it was unset: deleted again
        self.assertTrue(all(r["restored"] == r["before"] for r in rows))
        self.assertFalse(self.session.exists())
        text = self.log.path.read_text("utf-8")
        self.assertIn("| system/font_scale | 1.15 | 2 | 1.15 | yes |", text)
        self.assertIn("setting global/window_animation_scale: null -> 0 (through adb)", text)

    def test_the_session_survives_between_invocations(self):
        device = FakeDevice().install(self)
        device.settings[("system", "font_scale")] = "1.0"
        self.guard().font_scale(1.3)
        second = self.guard()
        second.font_scale(2.0)  # the value before the session is kept, not the intermediate one
        second.restore()
        self.assertEqual("1.0", device.settings[("system", "font_scale")])

    def test_samsung_talkback_component_is_read_from_the_device(self):
        services = f"{SAMSUNG_TALKBACK}/com.samsung.android.marvin.talkback.TalkBackService\n" \
                   "com.example.other/.Service\n"
        device = FakeDevice(packages=[SAMSUNG_TALKBACK, "com.example.other"], services=services).install(self)
        device.settings[("secure", "enabled_accessibility_services")] = "com.example.other/.Service"
        guard = self.guard()
        component = guard.talkback(True)
        self.assertTrue(component.startswith(SAMSUNG_TALKBACK + "/"))
        self.assertEqual(f"com.example.other/.Service:{component}",
                         device.settings[("secure", "enabled_accessibility_services")])
        self.assertEqual("1", device.settings[("secure", "accessibility_enabled")])
        guard.restore()
        self.assertEqual("com.example.other/.Service", device.settings[("secure", "enabled_accessibility_services")])
        self.assertNotIn(("secure", "accessibility_enabled"), device.settings)

    def test_verbose_service_listing_and_talkback_already_on(self):
        verbose = f"1 services found:\n  Service #0:\n    ServiceInfo:\n      name=.TalkBackService\n" \
                  f"      packageName={GOOGLE_TALKBACK}\n"
        self.assertEqual([f"{GOOGLE_TALKBACK}/.TalkBackService"], sg.parse_services(verbose))
        device = FakeDevice(packages=[GOOGLE_TALKBACK]).install(self)
        enabled = f"{GOOGLE_TALKBACK}/com.google.android.marvin.talkback.TalkBackService"
        device.settings[("secure", "enabled_accessibility_services")] = enabled
        guard = self.guard()
        guard.talkback(False)
        self.assertNotIn(("secure", "enabled_accessibility_services"), device.settings)
        guard.restore()
        self.assertEqual(enabled, device.settings[("secure", "enabled_accessibility_services")])

    def test_no_talkback_installed(self):
        FakeDevice(packages=["com.example.other"]).install(self)
        with self.assertRaises(LookupError):
            self.guard().talkback(True)

    def test_only_the_allowed_settings_change(self):
        FakeDevice().install(self)
        with self.assertRaises(PermissionError):
            self.guard().set("global", "airplane_mode_on", "1")
        with self.assertRaises(ValueError):
            self.guard().font_scale(5)

    def test_context_manager_restores(self):
        device = FakeDevice().install(self)
        with self.guard() as guard:
            guard.font_scale(1.5)
        self.assertNotIn(("system", "font_scale"), device.settings)


if __name__ == "__main__":
    unittest.main()
