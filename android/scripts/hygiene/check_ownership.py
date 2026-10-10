#!/usr/bin/env python3
"""Checks a work package's diff against the ownership table of android/docs/work-plan.md (work-plan 3.1).

Usage (from `android/`, or from anywhere inside the repository):
  python3 scripts/hygiene/check_ownership.py --wp WP-06 --base android/b1 [--phase core|complete]
  python3 scripts/hygiene/check_ownership.py --as-integrator --base android/b1
  python3 scripts/hygiene/check_ownership.py --self-check

How the table is read (work-plan 3.1):
- the first Markdown table after the heading "3.2 The table"; columns Pattern, Owner, Phase, Note; back-ticks stripped;
- a pattern is a path from the repository root; `*` matches within one segment, `**` across segments;
  `file.md#Heading text` owns the lines of that Markdown section (from the heading to the next heading of the same or a
  higher level), whitespace-only lines at a boundary belonging to the section that starts after them;
- only rows of the chosen phase and of `all` apply; the matching row with the most literal (non-wildcard) characters
  wins; a section row wins over its file's row inside the section (the innermost section with a row, when sections
  nest); two matching rows of equal precedence are a table error;
- a path under `android/` that no row matches belongs to INTEGRATOR, any other path to NONE.

The diff is the base commit against the working tree (committed, staged and unstaged changes, plus untracked files
that are not ignored); `--committed` limits it to `<base>..HEAD`. Renames count as a deletion plus an addition. A
whitespace-only changed line belongs to the nearest non-blank changed line of its hunk (a diff may place the blank
line of an inserted section on either side of it), and to its own section when the hunk has no other line.

Exit status: 0 every change is owned, 1 a change is not owned, 2 a table, usage or git error. Standard library only.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

TABLE_HEADING = "3.2 The table"
COLUMNS = ["Pattern", "Owner", "Phase", "Note"]
OWNER_RE = re.compile(r"^(?:WP-\d{2}[a-z]?|INTEGRATOR|ARCHITECT|PRODUCT|DESIGN|NONE)$")
PHASES = ("core", "complete", "all")
BOOTSTRAP_RESERVED = {"WP-04", "WP-05a", "WP-06", "WP-12"}  # day-0 rows WP-02 may not create before B1
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
FENCE_RE = re.compile(r"^\s*(```|~~~)")
HUNK_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


class TableError(Exception):
    pass


class GitError(Exception):
    pass


@dataclass(frozen=True)
class Row:
    number: int  # 1-based row of the table
    line: int  # line of the work plan
    pattern: str
    owner: str
    phase: str
    note: str = ""

    @property
    def file_part(self) -> str:
        return self.pattern.split("#", 1)[0]

    @property
    def section(self) -> str | None:
        return self.pattern.split("#", 1)[1] if "#" in self.pattern else None

    @property
    def literal(self) -> int:
        return len(self.file_part.replace("*", ""))

    def applies(self, phase: str) -> bool:
        return self.phase in (phase, "all")

    def matches(self, path: str) -> bool:
        return self.section is None and glob_regex(self.pattern).match(path) is not None

    def __str__(self) -> str:
        return f"row {self.number} `{self.pattern}` {self.owner} {self.phase}"


_REGEX_CACHE: dict[str, re.Pattern] = {}


def glob_regex(pattern: str) -> re.Pattern:
    """`*` within one path segment, `**` (a whole segment) across segments."""
    if pattern not in _REGEX_CACHE:
        segments = pattern.split("/")
        out = "^"
        for i, segment in enumerate(segments):
            last = i == len(segments) - 1
            if segment == "**":
                out += ".+" if last else "(?:[^/]+/)*"
            else:
                out += "".join("[^/]*" if c == "*" else re.escape(c) for c in segment)
                if not last:
                    out += "/"
        _REGEX_CACHE[pattern] = re.compile(out + "$")
    return _REGEX_CACHE[pattern]


# --------------------------------------------------------------------------------------------------------------------
# The table


def _cells(line: str) -> list[str]:
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|"):
        body = body[:-1]
    return [c.strip().replace("`", "").strip() for c in body.split("|")]


def parse_table(text: str) -> tuple[list[Row], list[str]]:
    """Returns the rows and the structural errors of the ownership table."""
    lines = text.splitlines()
    errors: list[str] = []
    start = None
    for i, line in enumerate(lines):
        m = HEADING_RE.match(line)
        if m and m.group(2).replace("`", "") == TABLE_HEADING:
            start = i
            break
    if start is None:
        return [], [f'no heading "{TABLE_HEADING}"']
    i = start + 1
    while i < len(lines) and not lines[i].strip().startswith("|"):
        if HEADING_RE.match(lines[i]):
            return [], [f'no table after the heading "{TABLE_HEADING}"']
        i += 1
    if i >= len(lines):
        return [], [f'no table after the heading "{TABLE_HEADING}"']
    header = _cells(lines[i])
    if header != COLUMNS:
        errors.append(f"line {i + 1}: columns are {header}, expected {COLUMNS}")
    if i + 1 >= len(lines) or not re.fullmatch(r"\|?(\s*:?-+:?\s*\|)+\s*:?-*:?\s*\|?", lines[i + 1].strip()):
        errors.append(f"line {i + 2}: missing the separator row")
    rows: list[Row] = []
    for j in range(i + 2, len(lines)):
        if not lines[j].strip().startswith("|"):
            break
        cells = _cells(lines[j])
        if len(cells) != len(COLUMNS):
            errors.append(f"line {j + 1}: {len(cells)} cells, expected {len(COLUMNS)}")
            continue
        rows.append(Row(len(rows) + 1, j + 1, cells[0], cells[1], cells[2], cells[3]))
    if not rows:
        errors.append("the table has no rows")
    return rows, errors


def row_errors(rows: list[Row]) -> list[str]:
    errors = []
    for row in rows:
        if not OWNER_RE.match(row.owner):
            errors.append(f"{row}: unknown owner '{row.owner}'")
        if row.phase not in PHASES:
            errors.append(f"{row}: unknown phase '{row.phase}'")
        path = row.file_part
        if not path or path.startswith("/") or "\\" in path or ".." in path.split("/") or path.endswith("/"):
            errors.append(f"{row}: the pattern must be a relative path")
        if any("**" in s and s != "**" for s in path.split("/")):
            errors.append(f"{row}: `**` must be a whole path segment")
        if row.section is not None:
            if "*" in path:
                errors.append(f"{row}: a section row names one file, without wildcards")
            if not row.section.strip() or "#" in row.section:
                errors.append(f"{row}: the section heading is empty or has a '#'")
    return errors


def witnesses(row: Row) -> list[str]:
    """Example paths a file row is meant to own: `**` as one segment and as none, `*` as `x`."""
    if row.section is not None:
        return []
    one = row.pattern.replace("**", "x").replace("*", "x")
    none = re.sub(r"(?:^|/)\*\*(?=/|$)", "", row.pattern).strip("/").replace("*", "x")
    return [p for p in dict.fromkeys([one, none]) if p]


def resolve(path: str, rows: list[Row], phase: str) -> tuple[str, Row | None]:
    """The owner of a whole file (file rows only) and the winning row; TableError on equal precedence."""
    matching = [r for r in rows if r.applies(phase) and r.matches(path)]
    if not matching:
        return ("INTEGRATOR" if path.startswith("android/") else "NONE"), None
    best = max(r.literal for r in matching)
    top = [r for r in matching if r.literal == best]
    if len(top) > 1:
        raise TableError(f"{path}: equal precedence between " + " and ".join(str(r) for r in top))
    return top[0].owner, top[0]


def self_check(rows: list[Row], errors: list[str], extra_paths: list[str]) -> list[str]:
    problems = list(errors) + row_errors(rows)
    for phase in ("core", "complete"):
        active = [r for r in rows if r.applies(phase)]
        seen_sections: dict[tuple[str, str], Row] = {}
        for row in active:
            if row.section is not None:
                key = (row.file_part, row.section)
                if key in seen_sections:
                    problems.append(f"phase {phase}: {seen_sections[key]} and {row} own the same section")
                seen_sections[key] = row
        seen_patterns: dict[str, Row] = {}
        for row in active:
            if row.section is None:
                if row.pattern in seen_patterns:
                    problems.append(f"phase {phase}: {seen_patterns[row.pattern]} and {row} have the same pattern")
                seen_patterns[row.pattern] = row
        probes = [w for r in active for w in witnesses(r)] + list(extra_paths)
        for path in dict.fromkeys(probes):
            try:
                resolve(path, rows, phase)
            except TableError as error:
                message = f"phase {phase}: {error}"
                if message not in problems:
                    problems.append(message)
    return problems


# --------------------------------------------------------------------------------------------------------------------
# Markdown sections


@dataclass
class Heading:
    line: int  # 0-based
    level: int
    text: str
    start: int = 0  # 0-based, includes the blank lines before the heading
    end: int = 0  # exclusive


def headings(lines: list[str]) -> list[Heading]:
    found: list[Heading] = []
    fenced = False
    for i, line in enumerate(lines):
        if FENCE_RE.match(line):
            fenced = not fenced
            continue
        m = None if fenced else HEADING_RE.match(line)
        if m:
            found.append(Heading(i, len(m.group(1)), m.group(2).replace("`", "").strip()))
    for k, h in enumerate(found):
        start = h.line
        floor = found[k - 1].line + 1 if k else 0
        while start > floor and not lines[start - 1].strip():
            start -= 1
        h.start = start
    for k, h in enumerate(found):
        h.end = len(lines)
        for nxt in found[k + 1 :]:
            if nxt.level <= h.level:
                h.end = nxt.start
                break
    return found


def line_sections(lines: list[str]) -> list[list[str]]:
    """For each line, the texts of the sections that contain it, outermost first."""
    result: list[list[str]] = [[] for _ in lines]
    for h in headings(lines):
        for i in range(h.start, h.end):
            result[i].append(h.text)
    return result


# --------------------------------------------------------------------------------------------------------------------
# git


def git(repo: Path, *args: str, check: bool = True) -> str:
    proc = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    if check and proc.returncode != 0:
        raise GitError(f"git {' '.join(args)}: {proc.stderr.decode('utf-8', 'replace').strip()}")
    return proc.stdout.decode("utf-8", "surrogateescape")


def changed_files(repo: Path, base: str, committed: bool) -> list[tuple[str, str]]:
    args = ["diff", "--name-status", "-z", "--no-renames", base] + (["HEAD"] if committed else [])
    parts = [p for p in git(repo, *args).split("\0") if p]
    changes = [(parts[k], parts[k + 1]) for k in range(0, len(parts) - 1, 2)]
    if not committed:
        untracked = git(repo, "ls-files", "-z", "--others", "--exclude-standard").split("\0")
        changes += [("A", p) for p in untracked if p]
    return sorted(set(changes), key=lambda c: c[1])


def file_lines(repo: Path, rev: str | None, path: str) -> list[str]:
    """A file's lines at a revision, or in the working tree when rev is None; [] when it does not exist."""
    if rev is None:
        full = repo / path
        return full.read_text("utf-8", "replace").splitlines() if full.is_file() else []
    proc = subprocess.run(["git", "-C", str(repo), "show", f"{rev}:{path}"], capture_output=True)
    return proc.stdout.decode("utf-8", "replace").splitlines() if proc.returncode == 0 else []


@dataclass
class Hunk:
    removed: list[int] = field(default_factory=list)  # 1-based lines of the base version
    added: list[int] = field(default_factory=list)  # 1-based lines of the new version


def hunks(repo: Path, base: str, path: str, committed: bool, untracked: bool) -> list[Hunk]:
    if untracked:
        return [Hunk(added=list(range(1, len(file_lines(repo, None, path)) + 1)))]
    args = ["diff", "-U0", "--no-color", "--no-ext-diff", "--no-renames", base] + (["HEAD"] if committed else [])
    out = git(repo, *args, "--", path)
    result: list[Hunk] = []
    old = new = 0
    for line in out.splitlines():
        m = HUNK_RE.match(line)
        if m:
            result.append(Hunk())
            old, new = int(m.group(1)), int(m.group(3))
            continue
        if not result or line.startswith(("\\", "+++", "---")):
            continue
        if line.startswith("-"):
            result[-1].removed.append(old)
            old += 1
        elif line.startswith("+"):
            result[-1].added.append(new)
            new += 1
    return result


# --------------------------------------------------------------------------------------------------------------------
# The check


@dataclass
class Verdict:
    status: str
    path: str
    owner: str
    rule: str
    ok: bool
    lines: str = ""  # "base 3-5" / "new 7" for section files


def section_owner(sections: list[str], section_rows: dict[str, Row]) -> Row | None:
    for text in reversed(sections):
        if text in section_rows:
            return section_rows[text]
    return None


def _spans(numbers: list[int]) -> str:
    spans, start, prev = [], None, None
    for n in sorted(numbers):
        if start is None:
            start = prev = n
        elif n == prev + 1:
            prev = n
        else:
            spans.append(f"{start}-{prev}" if prev != start else f"{start}")
            start = prev = n
    if start is not None:
        spans.append(f"{start}-{prev}" if prev != start else f"{start}")
    return ",".join(spans)


def check(
    repo: Path,
    rows: list[Row],
    base: str,
    phase: str,
    accepted: set[str],
    committed: bool = False,
    bootstrap: bool = False,
) -> list[Verdict]:
    verdicts: list[Verdict] = []
    untracked = set()
    if not committed:
        untracked = {p for p in git(repo, "ls-files", "-z", "--others", "--exclude-standard").split("\0") if p}

    def accepts(owner: str, path: str) -> bool:
        if owner in accepted:
            return True
        return bootstrap and path.startswith("android/") and owner not in BOOTSTRAP_RESERVED

    for status, path in changed_files(repo, base, committed):
        owner, row = resolve(path, rows, phase)
        rule = f"`{row.pattern}`" if row else "default rule"
        section_rows = {r.section: r for r in rows if r.applies(phase) and r.section and r.file_part == path}
        if not section_rows:
            verdicts.append(Verdict(status, path, owner, rule, accepts(owner, path)))
            continue
        base_lines = file_lines(repo, base, path)
        new_lines = file_lines(repo, "HEAD" if committed else None, path)
        sides = {"base": (base_lines, line_sections(base_lines)), "new": (new_lines, line_sections(new_lines))}
        touched: dict[tuple[str, str, bool], dict[str, list[int]]] = {}
        for hunk in hunks(repo, base, path, committed, path in untracked):
            for side, numbers in (("base", hunk.removed), ("new", hunk.added)):
                text, secs = sides[side]
                owners: dict[int, tuple[str, str]] = {}
                for n in numbers:
                    srow = section_owner(secs[n - 1], section_rows) if 0 < n <= len(secs) else None
                    owners[n] = (srow.owner, f"`{srow.pattern}`") if srow else (owner, rule)
                content = [n for n in numbers if 0 < n <= len(text) and text[n - 1].strip()]
                for n in numbers:
                    if content and not (0 < n <= len(text) and text[n - 1].strip()):
                        after = [c for c in content if c > n]
                        nearest = after[0] if after else max(c for c in content if c < n)
                        owners[n] = owners[nearest]
                for n, (who, why) in owners.items():
                    key = (who, why, accepts(who, path))
                    touched.setdefault(key, {}).setdefault(side, []).append(n)
        if not touched:
            verdicts.append(Verdict(status, path, owner, rule, accepts(owner, path)))
        for (who, why, ok), by_side in sorted(touched.items()):
            where = "; ".join(f"{side} lines {_spans(ns)}" for side, ns in sorted(by_side.items()))
            verdicts.append(Verdict(status, path, who, why, ok, where))
    return verdicts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--wp", help="the work package, for example WP-06")
    parser.add_argument("--base", help="the baseline commit the work package started from")
    parser.add_argument("--phase", choices=("core", "complete"), default="core")
    parser.add_argument("--as-integrator", action="store_true", help="also accept INTEGRATOR rows")
    parser.add_argument("--committed", action="store_true", help="only <base>..HEAD, not the working tree")
    parser.add_argument("--bootstrap", action="store_true", help="WP-02 before B1 (work-plan 3.1)")
    parser.add_argument("--self-check", action="store_true", help="validate the table and exit")
    parser.add_argument("--table", type=Path, help="the work plan (default: android/docs/work-plan.md)")
    parser.add_argument("--repo", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--quiet", action="store_true", help="print only what is not owned")
    args = parser.parse_args(argv)

    try:
        repo = Path(git(args.repo or Path(__file__).resolve().parent, "rev-parse", "--show-toplevel").strip())
    except GitError as error:
        print(f"check_ownership: {error}", file=sys.stderr)
        return 2
    table = args.table or repo / "android" / "docs" / "work-plan.md"
    try:
        rows, errors = parse_table(table.read_text("utf-8"))
    except OSError as error:
        print(f"check_ownership: cannot read the table: {error}", file=sys.stderr)
        return 2

    if args.self_check:
        tracked = [p for p in git(repo, "ls-files", "-z", check=False).split("\0") if p]
        problems = self_check(rows, errors, tracked)
        for problem in problems:
            print(f"table error: {problem}")
        if problems:
            return 2
        print(f"check_ownership: the table is valid ({len(rows)} rows, {len(tracked)} tracked paths probed).")
        return 0

    if errors or row_errors(rows):
        for problem in errors + row_errors(rows):
            print(f"table error: {problem}")
        return 2
    if not args.base or not (args.wp or args.as_integrator):
        parser.error("--base and --wp (or --as-integrator) are required")
    if args.wp and not OWNER_RE.match(args.wp):
        parser.error(f"--wp {args.wp}: expected WP-nn")
    if args.bootstrap and args.wp != "WP-02":
        parser.error("--bootstrap applies to WP-02 only")
    accepted = ({args.wp} if args.wp else set()) | ({"INTEGRATOR"} if args.as_integrator else set())
    try:
        verdicts = check(repo, rows, args.base, args.phase, accepted, args.committed, args.bootstrap)
    except TableError as error:
        print(f"table error: {error}")
        return 2
    except GitError as error:
        print(f"check_ownership: {error}", file=sys.stderr)
        return 2
    bad = [v for v in verdicts if not v.ok]
    who = " + ".join(sorted(accepted))
    for v in verdicts:
        if v.ok and args.quiet:
            continue
        mark = "ok       " if v.ok else "NOT OWNED"
        lines = f" ({v.lines})" if v.lines else ""
        print(f"{mark} {v.status} {v.path}{lines}: {v.owner} by {v.rule}")
    if bad:
        print(f"check_ownership: {len(bad)} change(s) not owned by {who} (phase {args.phase}).", file=sys.stderr)
        return 1
    print(f"check_ownership: every change is owned by {who} ({len(verdicts)} checked, phase {args.phase}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
