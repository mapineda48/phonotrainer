#!/usr/bin/env python3
"""Snapshot of the server's `GET /api/reference` for the Android build (architecture 15.4).

The phone ships this snapshot so the change names, families, descriptions and practice
advice are there before the first pairing and offline; at runtime it replaces the snapshot
whenever the computer reports another `api_version`, so a stale one is never wrong for long.

Run it with the repository's virtual environment, from anywhere:

    .venv/bin/python android/tools/reference/export.py            write the asset
    .venv/bin/python android/tools/reference/export.py --check    exit 1 if it is stale

How it stays harmless:
- the server app is built on a throwaway directory: `JobStore(<tmp>/workspace,
  corpus=None)`, and every other path `create_app()` could touch (database, downloads,
  web build, allowed roots) points inside that directory too. Never `create_app()`'s
  defaults: `data/phonotrainer.db` is relative to the working directory and, from the
  repository root, it is the owner's corpus;
- the bytecode cache is off, so importing the server leaves no `__pycache__` behind;
- the only file written outside the temporary directory is the asset itself, and never
  under `web/`.

The snapshot is the response body as the server builds it (keys in the server's order),
indented by two spaces with a final newline. One normalisation: `ipa_tokens` comes from a
frozenset, so its order within one length changes from run to run; it is written longest
first and then in code point order, which tokenises the same and keeps `--check` stable.
"""

from __future__ import annotations

import sys

sys.dont_write_bytecode = True        # before any import of the server

import argparse                        # noqa: E402
import json                            # noqa: E402
import os                              # noqa: E402
import tempfile                        # noqa: E402
import warnings                        # noqa: E402
from pathlib import Path               # noqa: E402

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DEFAULT_OUT = REPO / "android" / "core" / "content" / "src" / "main" / "assets" / "reference" / "reference.json"

# The request comes from loopback, as the browser client's does.
LOOPBACK_CLIENT = ("127.0.0.1", 50000)
LOOPBACK_BASE = "http://127.0.0.1:8000"

ADVICE = {"produce", "understand"}
REGISTERS = {"universal", "casual", "marked"}


class ExportError(Exception):
    """A refusal or a reference that does not have the expected shape."""


def web_refusal(out: Path, repo: Path = REPO) -> str | None:
    """Why `out` may not be written, or None: never inside the repository's web/ folder
    (symbolic links followed)."""
    target = Path(os.path.realpath(out))
    web = Path(os.path.realpath(repo / "web"))
    if target == web or web in target.parents:
        return (f"refusing to write {out}: it resolves inside {web}, and nothing under web/ "
                "is written by the Android tools (the web client belongs to another project)")
    return None


def fetch_reference(scratch: Path) -> dict:
    """`GET /api/reference` from an app whose every path is inside `scratch`."""
    if sys.path[0] != str(REPO):
        sys.path.insert(0, str(REPO))         # this repository's server, not an installed copy
    with warnings.catch_warnings():
        # Import-time notices of the test client's dependencies (Starlette's httpx
        # deprecation) say nothing about the snapshot.
        warnings.simplefilter("ignore")
        from fastapi.testclient import TestClient

        import phonotrainer
        from phonotrainer.jobs import JobStore
        from phonotrainer.server import create_app

    module = Path(phonotrainer.__file__).resolve()
    if REPO not in module.parents:
        raise ExportError(f"imported phonotrainer from {module.parent}, not from {REPO}")

    workspace = scratch / "workspace"
    downloads = scratch / "downloads"
    store = JobStore(workspace, corpus=None, download_dir=downloads)
    app = create_app(workspace=workspace, store=store, web_dist=scratch / "web-dist",
                     allowed_roots=[scratch], db_path=scratch / "never-opened.db",
                     download_dir=downloads)
    with TestClient(app, base_url=LOOPBACK_BASE, client=LOOPBACK_CLIENT) as client:
        response = client.get("/api/reference")
    if response.status_code != 200:
        raise ExportError(f"GET /api/reference answered {response.status_code}: {response.text[:300]}")
    return response.json()


def _fail(where: str, message: str):
    raise ExportError(f"reference {where}: {message}")


def _advice_map(value, where: str) -> None:
    if not isinstance(value, dict) or not value:
        _fail(where, "expected a non-empty object")
    for key, advice in value.items():
        if not isinstance(advice, dict):
            _fail(f"{where}.{key}", "expected an object")
        if advice.get("practice") not in ADVICE:
            _fail(f"{where}.{key}.practice", f"{advice.get('practice')!r} is not one of {sorted(ADVICE)}")
        if advice.get("register") not in REGISTERS:
            _fail(f"{where}.{key}.register", f"{advice.get('register')!r} is not one of {sorted(REGISTERS)}")
        if not isinstance(advice.get("why"), str) or not advice["why"].strip():
            _fail(f"{where}.{key}.why", "expected a sentence")


def validate(reference: dict) -> dict:
    """The fields the phone reads, checked before anything is written."""
    if not isinstance(reference, dict):
        _fail("body", "expected an object")
    if not isinstance(reference.get("api_version"), int):
        _fail("api_version", "expected an integer")
    labels = reference.get("labels")
    if not isinstance(labels, dict) or not labels:
        _fail("labels", "expected a non-empty object")
    for key in ("descriptions", "family_of", "metric_labels"):
        if not isinstance(reference.get(key), dict):
            _fail(key, "expected an object")
    for label in labels:
        if not isinstance(reference["descriptions"].get(label), str):
            _fail(f"descriptions.{label}", "missing")
    families = reference.get("families")
    if not isinstance(families, list) or not families:
        _fail("families", "expected a non-empty array")
    keys = set()
    for i, family in enumerate(families):
        if not isinstance(family, dict):
            _fail(f"families[{i}]", "expected an object")
        members = family.get("members")
        if not isinstance(family.get("key"), str) or not isinstance(family.get("label"), str):
            _fail(f"families[{i}]", "expected a key and a label")
        if not isinstance(members, list) or not isinstance(family.get("member_labels"), list) \
                or len(members) != len(family["member_labels"]):
            _fail(f"families[{i}]", "members and member_labels must be arrays of the same length")
        for member in members:
            if member not in labels:
                _fail(f"families[{i}].members", f"{member!r} is not a label")
        keys.add(family["key"])
    for label, family in reference["family_of"].items():
        if label not in labels or family not in keys:
            _fail(f"family_of.{label}", f"{family!r} is not a family of a label")
    _advice_map(reference.get("practice"), "practice")
    missing = sorted(set(labels) - set(reference["practice"]))
    if missing:
        _fail("practice", f"no advice for {', '.join(missing)}")
    _advice_map(reference.get("lexical_practice"), "lexical_practice")
    tokens = reference.get("ipa_tokens")
    if not isinstance(tokens, list) or not all(isinstance(t, str) and len(t) > 1 for t in tokens):
        _fail("ipa_tokens", "expected multi-character symbols")
    if not isinstance(reference.get("link_types"), list):
        _fail("link_types", "expected an array")
    metrics = reference.get("metrics_reference")
    if not isinstance(metrics, dict):
        _fail("metrics_reference", "expected an object")
    for name, entry in metrics.items():
        if not isinstance(entry, dict) or not isinstance(entry.get("display"), str):
            _fail(f"metrics_reference.{name}", "expected an object with a display text")
        for bound in ("low", "high"):
            if entry.get(bound) is not None and not isinstance(entry[bound], (int, float)):
                _fail(f"metrics_reference.{name}.{bound}", "expected a number or null")
    return reference


def normalise(reference: dict) -> dict:
    """The one change to the body: a stable order for `ipa_tokens` (see the module doc)."""
    out = dict(reference)
    out["ipa_tokens"] = sorted(reference["ipa_tokens"], key=lambda token: (-len(token), token))
    return out


def render(reference: dict) -> str:
    return json.dumps(reference, ensure_ascii=False, indent=2) + "\n"


def snapshot() -> str:
    """The asset's text, built on a temporary directory that is removed afterwards."""
    with tempfile.TemporaryDirectory(prefix="phono-reference-") as scratch:
        reference = fetch_reference(Path(scratch))
    return render(normalise(validate(reference)))


def changed_keys(bundled, fresh, path: str = "") -> list[str]:
    """Where two JSON values differ: "~" changed, "+" only in the server's, "-" only in the
    bundled snapshot."""
    out: list[str] = []
    if isinstance(bundled, dict) and isinstance(fresh, dict):
        for key in sorted(set(bundled) | set(fresh)):
            at = f"{path}.{key}" if path else key
            if key not in fresh:
                out.append(f"- {at}")
            elif key not in bundled:
                out.append(f"+ {at}")
            else:
                out.extend(changed_keys(bundled[key], fresh[key], at))
    elif isinstance(bundled, list) and isinstance(fresh, list):
        for i in range(max(len(bundled), len(fresh))):
            at = f"{path}[{i}]"
            if i >= len(fresh):
                out.append(f"- {at}")
            elif i >= len(bundled):
                out.append(f"+ {at}")
            else:
                out.extend(changed_keys(bundled[i], fresh[i], at))
    elif bundled != fresh or type(bundled) is not type(fresh):
        out.append(f"~ {path or '(the whole file)'}")
    return out


def _shown(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(Path.cwd().resolve()))
    except ValueError:
        return str(path)


def check(out: Path, text: str) -> int:
    try:
        bundled = out.read_bytes()
    except FileNotFoundError:
        print(f"{_shown(out)} does not exist. Run android/tools/reference/export.py to create it.",
              file=sys.stderr)
        return 1
    if bundled == text.encode("utf-8"):
        print(f"{_shown(out)} is up to date with the server's /api/reference.")
        return 0
    try:
        changes = changed_keys(json.loads(bundled), json.loads(text))
    except ValueError:
        changes = ["~ (the bundled file is not valid JSON)"]
    if not changes:
        changes = ["~ (formatting only)"]
    print(f"{_shown(out)} is out of date with the server's /api/reference: {len(changes)} change(s).",
          file=sys.stderr)
    for line in changes[:60]:
        print(f"  {line}", file=sys.stderr)
    if len(changes) > 60:
        print(f"  … and {len(changes) - 60} more", file=sys.stderr)
    print("Run android/tools/reference/export.py, review the asset's diff and commit it.", file=sys.stderr)
    return 1


def write(out: Path, text: str) -> int:
    out.parent.mkdir(parents=True, exist_ok=True)
    temporary = out.with_name(f".{out.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(text, encoding="utf-8", newline="\n")
        temporary.replace(out)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Wrote {_shown(out)}.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true",
                        help="build the snapshot in memory; exit 1 if the asset differs from it")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="asset to write or check (default: "
                             "android/core/content/src/main/assets/reference/reference.json)")
    args = parser.parse_args(argv)
    out = args.out if args.out.is_absolute() else Path.cwd() / args.out
    try:
        refusal = web_refusal(out)
        if refusal:
            raise ExportError(refusal)
        text = snapshot()
    except ExportError as error:
        print(f"reference export: {error}", file=sys.stderr)
        return 2
    return check(out, text) if args.check else write(out, text)


if __name__ == "__main__":
    sys.exit(main())
