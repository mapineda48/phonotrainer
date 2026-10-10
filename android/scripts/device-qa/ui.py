#!/usr/bin/env python3
"""Compact view of the PhonoTrainer window and tap helpers (architecture 21.7).

Usage:
  ui.py                      print the app window's elements, one line each
  ui.py all                  also print every window's title
  ui.py get <selector>       print the element that matches
  ui.py tap <selector>       tap the element that matches (test tag, text or content description; exact first, then
                             substring)

Each line: index, #test-tag, t='text', d='content description', st=state, i=interactions (C clickable, L long
clickable, F focusable, K checkable, S scrollable, E editable), @[x,y] centre, class. See _device.py for the device.
"""

from __future__ import annotations

import json
import sys
import time

from _device import APP_LABEL, adb, android_cli

ABBREVIATIONS = {
    "CLICKABLE": "C", "LONG_CLICKABLE": "L", "FOCUSABLE": "F", "CHECKABLE": "K", "SCROLLABLE": "S", "EDITABLE": "E",
}


def parse_layout(output: str) -> list[dict]:
    """The JSON window list of `android layout --flat` (the CLI may print a preamble before it)."""
    start = output.find("[")
    return json.loads(output[start:]) if start >= 0 else []


def dump(full: bool = False) -> list[dict]:
    args = ["layout", "--flat"] + (["--full"] if full else [])
    return parse_layout(android_cli(*args, text=True).stdout)


def app_elements(windows: list[dict]) -> list[dict] | None:
    for window in windows:
        if window.get("window-title") == APP_LABEL:
            return window.get("content", [])
    return None


def app_elements_with_retry(full: bool = False, attempts: int = 4) -> tuple[list[dict], list[dict] | None]:
    """The layout sometimes comes back without the app window; retry briefly."""
    windows: list[dict] = []
    for attempt in range(attempts):
        windows = dump(full)
        elements = app_elements(windows)
        if elements is not None:
            return windows, elements
        titles = [w.get("window-title") for w in windows]
        sys.stderr.write(f"[ui.py] app window missing (attempt {attempt + 1}), windows={titles}\n")
        time.sleep(0.4)
    return windows, None


def fmt(index: int, element: dict) -> str:
    parts = [f"{index:02d}"]
    if element.get("resource-id"):
        parts.append(f"#{element['resource-id']}")
    if "text" in element:
        parts.append(f"t={element['text']!r}")
    if element.get("content-desc"):
        parts.append(f"d={element['content-desc']!r}")
    if element.get("state"):
        parts.append("st=" + ",".join(element["state"]))
    if element.get("interactions"):
        parts.append("i=" + "".join(ABBREVIATIONS.get(x, x[0]) for x in element["interactions"]))
    parts.append(f"@{element.get('center')}")
    parts.append(element.get("class", "").split(".")[-1])
    return " ".join(parts)


def find(elements: list[dict], selector: str) -> dict | None:
    for key in ("resource-id", "text", "content-desc"):
        for element in elements:
            if element.get(key) == selector:
                return element
    for key in ("text", "content-desc", "resource-id"):
        for element in elements:
            if selector in (element.get(key) or ""):
                return element
    return None


def center(element: dict) -> tuple[int, int]:
    value = element["center"]
    x, y = json.loads(value) if isinstance(value, str) else value
    return int(x), int(y)


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    windows, elements = app_elements_with_retry()
    if not args or args[0] == "all":
        if args:
            print("windows:", [(w.get("window-title"), w.get("type")) for w in windows])
        if elements is None:
            print(f"!! {APP_LABEL} window not found. Windows:", [w.get("window-title") for w in windows])
            return 1
        for i, element in enumerate(elements):
            print(fmt(i, element))
        return 0
    if len(args) < 2 or args[0] not in ("get", "tap"):
        print(__doc__)
        return 2
    command, selector = args[0], args[1]
    if elements is None:
        print(f"!! {APP_LABEL} window not found")
        return 1
    element = find(elements, selector)
    if element is None:
        print(f"!! not found: {selector}")
        return 1
    if command == "get":
        print(fmt(0, element))
    else:
        x, y = center(element)
        adb("shell", "input", "tap", str(x), str(y))
        print(f"tapped {selector} @{x},{y}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
