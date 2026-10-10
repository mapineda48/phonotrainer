"""Tests of the /api/reference snapshot exporter (architecture 15.4).

Run with the repository's virtual environment, from the repository root:

    .venv/bin/python -B -m pytest -q -p no:cacheprovider android/tools/reference
    .venv/bin/python -B -m unittest discover -s android/tools/reference

The bundled asset is only read; every run that writes uses a temporary directory.
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "export.py"

_spec = importlib.util.spec_from_file_location("reference_export", SCRIPT)
export = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(export)      # module level only: no server import happens here

REPO = export.REPO
ASSET = export.DEFAULT_OUT


def run(*args: str, cwd: Path | None = None, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(SCRIPT), *args], cwd=cwd, env=env,
                          capture_output=True, text=True, timeout=120)


def repository_state() -> str:
    """Every changed, untracked and ignored path of the repository (git's own view)."""
    result = subprocess.run(["git", "status", "--porcelain", "--ignored", "--untracked-files=all"],
                            cwd=REPO, capture_output=True, text=True, check=True)
    return result.stdout


def entries(folder: Path) -> list[str]:
    return sorted(str(p.relative_to(folder)) for p in folder.rglob("*"))


class TheSnapshot(unittest.TestCase):
    """What the phone relies on (the JVM test of :core:content repeats the first three)."""

    @classmethod
    def setUpClass(cls):
        cls.reference = json.loads(ASSET.read_text(encoding="utf-8"))

    def test_has_the_16_labels(self):
        self.assertEqual(len(self.reference["labels"]), 16)
        self.assertEqual(set(self.reference["descriptions"]), set(self.reference["labels"]))

    def test_has_the_4_families(self):
        families = self.reference["families"]
        self.assertEqual([f["key"] for f in families], ["reduction", "td", "assimilation", "boundary"])
        for family in families:
            self.assertEqual(len(family["members"]), len(family["member_labels"]))
            for member in family["members"]:
                self.assertEqual(self.reference["family_of"][member], family["key"])

    def test_has_practice_advice_for_every_label(self):
        practice = self.reference["practice"]
        self.assertEqual(set(practice), set(self.reference["labels"]))
        for label, advice in practice.items():
            self.assertIn(advice["practice"], {"produce", "understand"}, label)
            self.assertIn(advice["register"], {"universal", "casual", "marked"}, label)
            self.assertTrue(advice["why"].strip(), label)
        self.assertGreater(len(self.reference["lexical_practice"]), 0)

    def test_is_the_whole_body_with_a_stable_token_order(self):
        self.assertIsInstance(self.reference["api_version"], int)
        for key in ("verdicts", "options", "review", "recording", "metrics_reference", "metric_labels"):
            self.assertIn(key, self.reference)
        tokens = self.reference["ipa_tokens"]
        self.assertEqual(tokens, sorted(tokens, key=lambda t: (-len(t), t)))

    def test_is_canonical_text_with_no_machine_path(self):
        text = ASSET.read_text(encoding="utf-8")
        self.assertEqual(export.render(json.loads(text)), text)
        self.assertNotIn("/home/", text)
        self.assertNotIn("/Users/", text)


class TheExporter(unittest.TestCase):

    def test_check_passes_on_the_bundled_asset_whatever_the_hash_seed(self):
        for seed in ("1", "2"):
            result = run("--check", env={**os.environ, "PYTHONHASHSEED": seed})
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("is up to date", result.stdout)

    def test_check_fails_on_a_changed_copy_and_names_the_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / "reference.json"
            reference = json.loads(ASSET.read_text(encoding="utf-8"))
            reference["descriptions"]["flapping"] += " Edited."
            copy.write_text(export.render(reference), encoding="utf-8")
            result = run("--check", "--out", str(copy))
        self.assertEqual(result.returncode, 1)
        self.assertIn("~ descriptions.flapping", result.stderr)

    def test_leaves_no_file_outside_its_temporary_directory(self):
        before = repository_state()
        with tempfile.TemporaryDirectory() as tmp:
            sandbox = Path(tmp)
            cwd, home, temp, out = (sandbox / name for name in ("cwd", "home", "tmp", "out"))
            for folder in (cwd, home, temp, out):
                folder.mkdir()
            env = {**os.environ, "HOME": str(home), "TMPDIR": str(temp)}
            env.pop("PYTHONDONTWRITEBYTECODE", None)        # the script must turn it off itself
            result = run("--out", str(out / "reference.json"), cwd=cwd, env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(entries(out), ["reference.json"])
            self.assertEqual(entries(cwd), [], "nothing in the working directory (no data/phonotrainer.db)")
            self.assertEqual(entries(temp), [], "its temporary directory is removed")
            self.assertEqual(entries(home), [], "nothing in the home directory")
            self.assertEqual((out / "reference.json").read_bytes(), ASSET.read_bytes())
        self.assertEqual(repository_state(), before, "nothing changed or appeared in the repository")
        self.assertFalse((REPO / "data").exists())

    def test_never_writes_under_web(self):
        self.assertIn("refusing", export.web_refusal(REPO / "web" / "reference.json"))
        self.assertIn("refusing", export.web_refusal(REPO / "web"))
        self.assertIsNone(export.web_refusal(ASSET))
        self.assertIsNone(export.web_refusal(REPO / "webapp" / "reference.json"))
        with tempfile.TemporaryDirectory() as tmp:
            link = Path(tmp) / "link"
            link.symlink_to(REPO / "web")
            self.assertIn("refusing", export.web_refusal(link / "nested" / "reference.json"))


class TheChecks(unittest.TestCase):

    def setUp(self):
        self.reference = json.loads(ASSET.read_text(encoding="utf-8"))

    def test_a_label_without_advice_is_refused(self):
        del self.reference["practice"]["flapping"]
        with self.assertRaisesRegex(export.ExportError, "no advice for flapping"):
            export.validate(self.reference)

    def test_an_unknown_advice_is_refused(self):
        self.reference["lexical_practice"]["gonna"]["practice"] = "imitate"
        with self.assertRaisesRegex(export.ExportError, "lexical_practice.gonna.practice"):
            export.validate(self.reference)

    def test_a_family_member_must_be_a_label(self):
        self.reference["families"][0]["members"][0] = "nope"
        with self.assertRaisesRegex(export.ExportError, "'nope' is not a label"):
            export.validate(self.reference)

    def test_changed_keys(self):
        self.assertEqual(
            export.changed_keys({"a": {"b": 1, "c": [1, 2]}, "gone": 1}, {"a": {"b": 2, "c": [1]}, "new": 1}),
            ["~ a.b", "- a.c[1]", "- gone", "+ new"],
        )


if __name__ == "__main__":
    unittest.main()
