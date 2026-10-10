"""Tests of .github/workflows/android.yml against architecture 22, with regular expressions (no YAML parser needed)."""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from testsupport import REPO_ROOT  # noqa: E402

WORKFLOW = REPO_ROOT / ".github" / "workflows" / "android.yml"
ARCHITECTURE = REPO_ROOT / "android" / "docs" / "architecture.md"


def architecture_paths() -> set[str]:
    """The `paths` filters architecture 22 lists in its Triggers bullet, plus the workflow file itself."""
    text = ARCHITECTURE.read_text("utf-8")
    section = text[text.index("## 22. Continuous integration") :]
    section = section[: section.index("\n## ", 5)]
    bullet = section[section.index("- Triggers:") :]
    bullet = bullet[: bullet.index("\n- ", 3)]
    return set(re.findall(r"`([^`]+)`", bullet.split("filters", 1)[1])) | {".github/workflows/android.yml"}


def block(text: str, key: str, indent: int) -> str:
    """The lines nested under `key:` at the given indentation."""
    lines = text.splitlines()
    out, inside = [], False
    for line in lines:
        if re.match(rf"^ {{{indent}}}{re.escape(key)}:\s*$", line):
            inside = True
            continue
        if inside:
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            out.append(line)
    return "\n".join(out)


class Workflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text("utf-8")

    def test_every_action_is_pinned_to_a_full_commit_sha_with_a_version_comment(self):
        uses = re.findall(r"^\s*(?:-\s+)?uses:\s*(.+)$", self.text, re.MULTILINE)
        self.assertTrue(uses)
        for value in uses:
            with self.subTest(uses=value):
                self.assertRegex(value, r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}\s+#\s*v\d+(\.\d+)*\s*$")

    def test_permissions_are_contents_read_only(self):
        top = block(self.text, "permissions", 0)
        self.assertEqual(["contents: read"], [line.strip() for line in top.splitlines() if line.strip()])
        self.assertNotRegex(self.text, r"(?m)^\s+permissions:")  # no job widens them
        self.assertNotIn("write", top)

    def test_paths_filters_equal_architecture_22(self):
        expected = architecture_paths()
        self.assertIn("android/**", expected)
        on = "\n".join(line[2:] for line in block(self.text, "on", 0).splitlines())
        for trigger in ("push", "pull_request"):
            with self.subTest(trigger=trigger):
                items = block(block(on, trigger, 0), "paths", 2)
                found = set(re.findall(r'^\s+-\s+"?([^"\n]+?)"?\s*$', items, re.MULTILINE))
                self.assertEqual(expected, found)

    def test_triggers(self):
        on = block(self.text, "on", 0)
        self.assertRegex(on, r"(?m)^  push:\n    branches: \[main\]")
        self.assertRegex(on, r"(?m)^  pull_request:")
        self.assertRegex(on, r"(?m)^  workflow_dispatch:")

    def test_checkout_never_persists_credentials(self):
        checkouts = len(re.findall(r"uses:\s*actions/checkout@", self.text))
        self.assertEqual(checkouts, len(re.findall(r"persist-credentials:\s*false", self.text)))

    def test_jobs_concurrency_and_working_directory(self):
        self.assertRegex(self.text, r"(?m)^concurrency:\n  group: .+\n  cancel-in-progress: true")
        self.assertRegex(self.text, r"(?m)^defaults:\n  run:\n    working-directory: android")
        for job, minutes in (("hygiene", 5), ("build", 30), ("instrumented", 45)):
            self.assertRegex(self.text, rf"(?ms)^  {job}:\n.*?timeout-minutes: {minutes}\n")
        self.assertIn("python3 scripts/hygiene/check_repo.py", self.text)
        self.assertIn("python3 -m unittest discover -s scripts", self.text)
        self.assertRegex(self.text, r"gradle/actions/wrapper-validation@[0-9a-f]{40}")
        self.assertIn("./gradlew check lintDebug assembleDebug assembleRelease verifyDidacticContent", self.text)
        self.assertIn("-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect", self.text)
        self.assertRegex(self.text, r"java-version: 21")
        self.assertRegex(self.text, r"node-version: 22")

    def test_no_secret_is_used(self):
        self.assertNotIn("secrets.", self.text)


if __name__ == "__main__":
    unittest.main()
