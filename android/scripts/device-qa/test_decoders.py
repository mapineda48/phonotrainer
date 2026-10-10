"""Tests of decoders.py with codec declarations shaped like a phone's media_codecs XML files."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import decoders  # noqa: E402
from fakedevice import FakeDevice  # noqa: E402

VENDOR = """<?xml version="1.0" encoding="utf-8"?>
<MediaCodecs>
  <Include href="media_codecs_google_video.xml"/>
  <Encoders>
    <MediaCodec name="c2.exynos.h264.encoder" type="video/avc"/>
  </Encoders>
  <Decoders>
    <MediaCodec name="c2.exynos.av1.decoder" type="video/av01">
      <Limit name="size" min="96x96" max="3840x2160"/>
      <Limit name="measured-frame-rate-1920x1080" range="120-180"/>
      <Feature name="adaptive-playback"/>
    </MediaCodec>
    <MediaCodec name="c2.exynos.h264.decoder">
      <Type name="video/avc"><Limit name="size" max="4096x2304"/></Type>
      <Limit name="performance-point-3840x2160" value="60"/>
    </MediaCodec>
    <MediaCodec name="c2.exynos.aac.decoder" type="audio/mp4a-latm"/>
  </Decoders>
</MediaCodecs>
"""

SOFTWARE = """<MediaCodecs>
  <Decoders>
    <MediaCodec name="c2.android.av1.decoder" type="video/av01">
      <Limit name="size" min="2x2" max="2048x2048"/>
      <Attribute name="software-codec"/>
    </MediaCodec>
    <MediaCodec name="c2.vendor.vp9.decoder" type="video/x-vnd.on2.vp9" software="true"/>
  </Decoders>
</MediaCodecs>
"""


class Parsing(unittest.TestCase):
    def test_video_decoders_sizes_and_kinds(self):
        found = {(d.name, d.mime): d for d in decoders.parse_codecs(VENDOR, "media_codecs.xml")}
        self.assertEqual({("c2.exynos.av1.decoder", "video/av01"), ("c2.exynos.h264.decoder", "video/avc")},
                         set(found))
        av1 = found[("c2.exynos.av1.decoder", "video/av01")]
        self.assertEqual("3840x2160", av1.max_size)
        self.assertTrue(av1.hardware)
        self.assertIn("1920x1080 120-180", av1.rates)
        self.assertIn("3840x2160 @60", found[("c2.exynos.h264.decoder", "video/avc")].rates)

    def test_software_codecs(self):
        found = decoders.parse_codecs(SOFTWARE)
        self.assertEqual([False, False], [d.hardware for d in found])

    def test_stray_text_and_garbage(self):
        self.assertEqual(2, len(decoders.parse_codecs("junk\n" + VENDOR[VENDOR.index("<MediaCodecs"):])))
        self.assertEqual([], decoders.parse_codecs("not xml"))


class Collect(unittest.TestCase):
    def test_reads_the_device_and_reports_share_sources(self):
        FakeDevice(
            packages=["com.google.android.youtube"],
            files={"/vendor/etc/media_codecs.xml": VENDOR,
                   "/apex/com.android.media.swcodec/etc/media_codecs.xml": SOFTWARE,
                   "/vendor/etc/other.xml": "<x/>"},
        ).install(self)
        found, installed = decoders.collect()
        self.assertEqual(4, len(found))
        self.assertEqual({"YouTube": True, "X": False}, installed)
        text = decoders.report(found, installed)
        self.assertIn("| video/av01 | c2.exynos.av1.decoder | 3840x2160 |", text)
        self.assertIn("- X app installed: no", text)


if __name__ == "__main__":
    unittest.main()
