#!/usr/bin/env python3
"""Accessibility smoke check of the PhonoTrainer screen on display (architecture 21.7).

Lists every clickable element with its accessible label (its own text or content description, or the first labelled
child inside it) and its size in dp, flagging unlabelled elements and touch targets smaller than 48 dp. Exit status 1
when anything is flagged. See _device.py for device selection.
"""

from __future__ import annotations

import re
import sys

from _device import APP_LABEL, adb
from ui import app_elements_with_retry

MIN_TARGET_DP = 47.5  # 48 dp with rounding tolerance


def bounds(element: dict) -> list[int]:
    return list(map(int, re.findall(r"-?\d+", element.get("bounds", "[0,0][0,0]"))))[:4]


def is_inside(child: dict, parent: dict) -> bool:
    c, p = bounds(child), bounds(parent)
    return c[0] >= p[0] and c[1] >= p[1] and c[2] <= p[2] and c[3] <= p[3]


def label_of(element: dict) -> str | None:
    return (element.get("text") or "").strip() or element.get("content-desc") or None


def audit(elements: list[dict], density: float) -> list[tuple[str, str | None, float, float, list[str]]]:
    """(name, label, width dp, height dp, problems) for every clickable element."""
    results = []
    for element in elements:
        if "CLICKABLE" not in element.get("interactions", []):
            continue
        children = [label_of(c) for c in elements if c is not element and is_inside(c, element) and label_of(c)]
        label = label_of(element) or (children[0] if children else None)
        b = bounds(element)
        width, height = (b[2] - b[0]) / density, (b[3] - b[1]) / density
        problems = [] if label else ["NO LABEL"]
        if min(width, height) < MIN_TARGET_DP:
            problems.append(f"small target {width:.0f}x{height:.0f}dp")
        name = element.get("resource-id") or element.get("class", "").split(".")[-1]
        results.append((name, label, width, height, problems))
    return results


def screen_density() -> float:
    out = adb("shell", "wm", "density", text=True).stdout
    values = re.findall(r"\d+", out)
    return int(values[-1]) / 160 if values else 1.0  # the override, when one is set, is printed last


def main() -> int:
    _windows, elements = app_elements_with_retry(full=True)
    if elements is None:
        print(f"!! {APP_LABEL} window not found")
        return 1
    flagged = 0
    for name, label, width, height, problems in audit(elements, screen_density()):
        flags = "".join(f"  <-- {p}" for p in problems)
        flagged += bool(problems)
        print(f"{name:<28} label={str(label)[:45]!r} size={width:.0f}x{height:.0f}dp{flags}")
    return 1 if flagged else 0


if __name__ == "__main__":
    sys.exit(main())
