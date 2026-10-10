"""Tests of check_ownership.py: a fixture table and diffs in a temporary repository (work-plan WP-06 Proof)."""

from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_ownership as co  # noqa: E402
from testsupport import REPO_ROOT, TempRepo  # noqa: E402

TABLE = """# Plan

## 3. Ownership

### 3.2 The table

| Pattern | Owner | Phase | Note |
|---|---|---|---|
| `web/**` | NONE | all | another agent |
| `server/**` | NONE | all | |
| `server/api.py` | WP-01 | core | |
| `server/api.py` | WP-27 | complete | |
| `README.md` | NONE | all | |
| `README.md#Phone access` | WP-01 | core | new section |
| `README.md#Phone access` | WP-27 | complete | |
| `NOTICES.md` | NONE | all | |
| `NOTICES.md#7. Android client` | INTEGRATOR | all | |
| `NOTICES.md#7.3 Fonts and icons` | WP-03 | core | nested inside 7 |
| `android/scripts/**` | WP-06 | core | |
| `android/core/ui/**` | WP-03 | core | |
| `android/core/ui/src/*/kotlin/**/ui/component/ClipCard*.kt` | WP-16 | core | |
| `android/feature/library/src/*/kotlin/**/library/transcript/**` | NONE | core | reserved |

Everything else under `android/` belongs to the integrator.

## 4. Next
"""

README = """# Project

Intro text.

## Install

Install steps.

## License

GPL.
"""

NOTICES = """# Notices

## 6. References

Refs.

## 7. Android client

Libraries.

### 7.3 Fonts and icons

Fonts.

## 8. Other

Other.
"""


class Fixture(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = TempRepo()
        self.addCleanup(self.repo.cleanup)
        self.repo.write("android/docs/work-plan.md", TABLE)
        self.repo.write("README.md", README)
        self.repo.write("NOTICES.md", NOTICES)
        self.repo.write("server/api.py", "API = 1\n")
        self.base = self.repo.commit("Base")
        self.rows, errors = co.parse_table(TABLE)
        self.assertEqual([], errors)

    def verdicts(self, wp: str = "WP-01", phase: str = "core", integrator: bool = False, **kwargs):
        accepted = {wp} | ({"INTEGRATOR"} if integrator else set())
        return co.check(self.repo.path, self.rows, self.base, phase, accepted, **kwargs)

    def bad(self, *args, **kwargs) -> list[tuple[str, str]]:
        return [(v.path, v.owner) for v in self.verdicts(*args, **kwargs) if not v.ok]


class TableParsing(Fixture):
    def test_rows_and_backticks(self):
        self.assertEqual(14, len(self.rows))
        self.assertEqual("README.md#Phone access", self.rows[5].pattern)
        self.assertEqual("Phone access", self.rows[5].section)
        self.assertEqual("README.md", self.rows[5].file_part)

    def test_glob_semantics(self):
        self.assertTrue(co.glob_regex("android/scripts/**").match("android/scripts/a/b.py"))
        self.assertFalse(co.glob_regex("android/scripts/**").match("android/scripts"))
        rx = co.glob_regex("android/core/ui/src/*/kotlin/**/ui/component/ClipCard*.kt")
        self.assertTrue(rx.match("android/core/ui/src/main/kotlin/com/x/ui/component/ClipCardPreview.kt"))
        self.assertTrue(rx.match("android/core/ui/src/test/kotlin/ui/component/ClipCard.kt"))
        self.assertFalse(rx.match("android/core/ui/src/main/java/kotlin/x/ui/component/ClipCard.kt/x"))
        self.assertFalse(co.glob_regex("src/*/a.kt").match("src/x/y/a.kt"))

    def test_precedence_and_defaults(self):
        self.assertEqual("WP-01", co.resolve("server/api.py", self.rows, "core")[0])
        self.assertEqual("WP-27", co.resolve("server/api.py", self.rows, "complete")[0])
        self.assertEqual("NONE", co.resolve("server/other.py", self.rows, "core")[0])
        self.assertEqual("WP-16", co.resolve("android/core/ui/src/main/kotlin/a/ui/component/ClipCard.kt",
                                             self.rows, "core")[0])
        self.assertEqual("WP-03", co.resolve("android/core/ui/build.gradle.kts", self.rows, "core")[0])
        self.assertEqual("INTEGRATOR", co.resolve("android/settings.gradle.kts", self.rows, "core")[0])
        self.assertEqual("NONE", co.resolve("Makefile", self.rows, "core")[0])
        self.assertEqual("INTEGRATOR", co.resolve("android/core/ui/x.kt", self.rows, "complete")[0])

    def test_equal_precedence_is_a_table_error(self):
        rows, _ = co.parse_table(TABLE.replace("| `server/api.py` | WP-27 | complete | |",
                                               "| `server/*c.py` | WP-27 | core | |\n| `server/a*.py` | WP-02 | core | |"))
        with self.assertRaises(co.TableError):
            co.resolve("server/abc.py", rows, "core")

    def test_self_check_passes_on_the_fixture_and_on_the_work_plan(self):
        self.assertEqual([], co.self_check(self.rows, [], []))
        text = (REPO_ROOT / "android" / "docs" / "work-plan.md").read_text("utf-8")
        rows, errors = co.parse_table(text)
        self.assertGreater(len(rows), 100)
        self.assertEqual([], co.self_check(rows, errors, []))

    def test_self_check_finds_broken_tables(self):
        cases = {
            "owner": TABLE.replace("| WP-06 | core |", "| WP6 | core |"),
            "phase": TABLE.replace("| WP-06 | core |", "| WP-06 | later |"),
            "duplicate": TABLE.replace("| `web/**` | NONE | all |", "| `README.md` | WP-02 | core |"),
            "section twice": TABLE.replace("| `README.md#Phone access` | WP-27 | complete |",
                                           "| `README.md#Phone access` | WP-02 | all |"),
            "absolute": TABLE.replace("`server/**`", "`/server/**`"),
            "wild section": TABLE.replace("`NOTICES.md#7. Android client`", "`NOTI*.md#7. Android client`"),
            "ambiguous": TABLE.replace("| `server/api.py` | WP-27 | complete | |", "| `server/*/x.py` | WP-27 | core | |"
                                       "\n| `server/y/*.py` | WP-02 | core | |"),
        }
        for name, text in cases.items():
            with self.subTest(name=name):
                rows, errors = co.parse_table(text)
                self.assertTrue(co.self_check(rows, errors, []), name)

    def test_structure_errors(self):
        self.assertTrue(co.parse_table("# x\n\nno table\n")[1])
        self.assertTrue(co.parse_table(TABLE.replace("| Pattern | Owner | Phase | Note |", "| Pattern | Who |"))[1])


class Sections(unittest.TestCase):
    def test_boundary_blank_lines_belong_to_the_next_section(self):
        lines = README.splitlines()
        sections = co.line_sections(lines)
        install = lines.index("## Install")
        self.assertEqual(["Project", "Install"], sections[install - 1])  # the blank line before "## Install"
        self.assertEqual(["Project"], sections[install - 2])  # "Intro text."

    def test_nested_sections_and_fences(self):
        lines = NOTICES.splitlines() + ["```", "## not a heading", "```"]
        sections = co.line_sections(lines)
        fonts = lines.index("Fonts.")
        self.assertEqual(["Notices", "7. Android client", "7.3 Fonts and icons"], sections[fonts])
        self.assertEqual(["Notices", "8. Other"], sections[-1])


class Diffs(Fixture):
    def test_own_files_pass_and_others_fail(self):
        self.repo.write("server/api.py", "API = 2\n")
        self.repo.write("server/util.py", "X = 1\n")
        self.assertEqual([("server/util.py", "NONE")], self.bad("WP-01"))

    def test_committed_and_working_tree_changes(self):
        self.repo.write("android/scripts/a.py", "A = 1\n")
        self.repo.commit("Add")
        self.repo.write("android/scripts/b.py", "B = 1\n")
        self.assertEqual([], self.bad("WP-06"))
        self.assertEqual(["android/scripts/a.py"], [v.path for v in self.verdicts("WP-06", committed=True)])

    def test_deletion_and_rename(self):
        self.repo.git("mv", "server/api.py", "server/api2.py")
        bad = self.bad("WP-01")
        self.assertEqual([("server/api2.py", "NONE")], bad)

    def test_a_new_section_at_the_end(self):
        self.repo.write("README.md", README + "\n## Phone access\n\nTurn it on.\n")
        self.assertEqual([], self.bad("WP-01"))

    def test_a_new_section_in_the_middle_whatever_side_the_diff_puts_the_blank(self):
        text = README.replace("## License", "## Phone access\n\nTurn it on.\n\n## License")
        self.repo.write("README.md", text)
        self.assertEqual([], self.bad("WP-01"))
        text = README.replace("Install steps.\n", "Install steps.\n\n## Phone access\n\nTurn it on.\n")
        self.repo.write("README.md", text)
        self.assertEqual([], self.bad("WP-01"))

    def test_a_change_outside_the_section_fails(self):
        self.repo.write("README.md", README.replace("Intro text.", "Intro text, changed.") +
                        "\n## Phone access\n\nTurn it on.\n")
        bad = self.bad("WP-01")
        self.assertEqual([("README.md", "NONE")], bad)

    def test_phase_complete_uses_its_own_rows(self):
        self.repo.write("README.md", README + "\n## Phone access\n\nTurn it on.\n")
        self.assertEqual([("README.md", "WP-01")], self.bad("WP-27", phase="core"))
        self.assertEqual([], self.bad("WP-27", phase="complete"))

    def test_nested_section_wins_and_integrator_rows(self):
        self.repo.write("NOTICES.md", NOTICES.replace("Fonts.", "Fonts: Charis."))
        self.assertEqual([], self.bad("WP-03"))
        self.repo.write("NOTICES.md", NOTICES.replace("Libraries.", "Libraries: Room."))
        self.assertEqual([("NOTICES.md", "INTEGRATOR")], self.bad("WP-03"))
        self.assertEqual([], self.bad("WP-19", integrator=True))

    def test_reserved_and_more_specific_rows(self):
        self.repo.write("android/feature/library/src/main/kotlin/a/library/transcript/T.kt", "class T\n")
        self.repo.write("android/core/ui/src/main/kotlin/a/ui/component/ClipCard.kt", "class C\n")
        bad = dict(self.bad("WP-03"))
        self.assertEqual("NONE", bad["android/feature/library/src/main/kotlin/a/library/transcript/T.kt"])
        self.assertEqual("WP-16", bad["android/core/ui/src/main/kotlin/a/ui/component/ClipCard.kt"])

    def test_bootstrap_lets_wp02_create_android_files_except_day0_rows(self):
        self.repo.write("android/app/build.gradle.kts", "plugins {}\n")
        self.repo.write("android/core/ui/src/main/kotlin/A.kt", "class A\n")
        self.repo.write("android/scripts/x.py", "X = 1\n")
        bad = [p for p, _ in self.bad("WP-02", bootstrap=True)]
        self.assertEqual(["android/scripts/x.py"], bad)


class CommandLine(Fixture):
    def main(self, *argv: str) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            try:
                code = co.main(["--repo", str(self.repo.path), *argv])
            except SystemExit as exit_:
                code = exit_.code
        return code, out.getvalue() + err.getvalue()

    def test_exit_codes(self):
        self.repo.write("android/scripts/a.py", "A = 1\n")
        self.assertEqual(0, self.main("--wp", "WP-06", "--base", self.base)[0])
        self.repo.write("server/x.py", "X = 1\n")
        code, output = self.main("--wp", "WP-06", "--base", self.base)
        self.assertEqual(1, code)
        self.assertIn("NOT OWNED", output)

    def test_self_check_command(self):
        self.assertEqual(0, self.main("--self-check")[0])
        self.repo.write("android/docs/work-plan.md", TABLE.replace("| WP-06 | core |", "| WP-06 | someday |"))
        self.assertEqual(2, self.main("--self-check")[0])

    def test_usage_errors(self):
        self.assertEqual(2, self.main("--base", self.base)[0])
        self.assertEqual(2, self.main("--wp", "WP-06", "--base", "no-such-commit")[0])


if __name__ == "__main__":
    unittest.main()
