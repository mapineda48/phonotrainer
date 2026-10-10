#!/usr/bin/env python3
"""Records the target device's video decoders and the share sources it has (architecture 21.7; spec NFR-14).

Reads the codec declarations (`media_codecs*.xml` under /vendor, /odm, /product, /system and the software-codec APEX)
and prints, for every video decoder: codec name, MIME type, maximum size, frame-rate hints and whether it is a
hardware or software codec. It also records whether the YouTube and X apps are installed (the share target's sources).
`--log` appends the result to the QA log. It only reads files and package lists; it changes nothing on the device.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

import _device

CODEC_DIRS = ("/vendor/etc", "/odm/etc", "/product/etc", "/system/etc", "/apex/com.android.media.swcodec/etc")
SHARE_SOURCES = {"YouTube": "com.google.android.youtube", "X": "com.twitter.android"}
SOFTWARE_PREFIXES = ("c2.android.", "omx.google.", "c2.google.")


@dataclass
class Decoder:
    name: str
    mime: str
    max_size: str = "?"
    rates: list[str] = field(default_factory=list)
    hardware: bool = True
    source: str = ""

    def row(self) -> str:
        kind = "hardware" if self.hardware else "software"
        rates = ", ".join(self.rates) or "-"
        return f"| {self.mime} | {self.name} | {self.max_size} | {rates} | {kind} | {self.source} |"


def is_software(name: str, attributes: set[str]) -> bool:
    return "software-codec" in attributes or name.lower().startswith(SOFTWARE_PREFIXES)


def parse_codecs(xml_text: str, source: str = "") -> list[Decoder]:
    """Video decoders declared in one media_codecs XML file."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        # vendor files sometimes carry stray text before the root; retry from the first element
        start = xml_text.find("<MediaCodecs")
        if start < 0:
            return []
        try:
            root = ET.fromstring(xml_text[start:])
        except ET.ParseError:
            return []
    decoders = []
    for section in root.iter("Decoders"):
        for codec in section.iter("MediaCodec"):
            name = codec.get("name", "")
            mimes = [codec.get("type")] if codec.get("type") else [t.get("name") for t in codec.iter("Type")]
            attributes = {a.get("name", "") for a in codec.iter("Attribute")}
            if codec.get("software") == "true":
                attributes.add("software-codec")
            size, rates = "?", []
            for limit in codec.iter("Limit"):
                kind = limit.get("name") or ""
                if kind == "size":
                    size = limit.get("max") or limit.get("value") or "?"
                elif kind.startswith("measured-frame-rate-"):
                    rates.append(f"{kind.removeprefix('measured-frame-rate-')} {limit.get('range', '')}".strip())
                elif kind.startswith("performance-point-"):
                    rates.append(f"{kind.removeprefix('performance-point-')} @{limit.get('value', '?')}")
                elif kind == "blocks-per-second":
                    rates.append(f"blocks/s {limit.get('max') or limit.get('range', '')}".strip())
            for mime in mimes:
                if mime and mime.startswith("video/"):
                    decoders.append(Decoder(name, mime, size, rates, not is_software(name, attributes), source))
    return decoders


def codec_files() -> list[str]:
    files = []
    for directory in CODEC_DIRS:
        out = _device.shell(f"ls {directory} 2>/dev/null")
        files += [f"{directory}/{n}" for n in out.split() if re.fullmatch(r"media_codecs[\w.-]*\.xml", n)]
    return files


def collect() -> tuple[list[Decoder], dict[str, bool]]:
    decoders: list[Decoder] = []
    for path in codec_files():
        decoders += parse_codecs(_device.shell(f"cat {path}"), path.rsplit("/", 1)[-1])
    seen, unique = set(), []
    for d in decoders:
        if (d.name, d.mime) not in seen:
            seen.add((d.name, d.mime))
            unique.append(d)
    packages = _device.shell("pm list packages")
    installed = {label: f"package:{package}" in packages.split() for label, package in SHARE_SOURCES.items()}
    return sorted(unique, key=lambda d: (d.mime, not d.hardware, d.name)), installed


def report(decoders: list[Decoder], installed: dict[str, bool]) -> str:
    lines = ["| MIME | Decoder | Max size | Rates | Kind | Declared in |", "|---|---|---|---|---|---|"]
    lines += [d.row() for d in decoders]
    lines.append("")
    lines += [f"- {label} app installed: {'yes' if ok else 'no'}" for label, ok in installed.items()]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    decoders, installed = collect()
    text = report(decoders, installed)
    print(text)
    if "--log" in args:
        from qa_log import QaLog

        QaLog().write_block("Video decoders and share sources", text)
    return 0 if decoders else 1


if __name__ == "__main__":
    sys.exit(main())
