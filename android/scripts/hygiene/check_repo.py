#!/usr/bin/env python3
"""Repository hygiene check for the Android client (architecture 20.7; spec 11.7, X-AC2, X-AC4, X-AC5, F-02-AC9).

Usage (from `android/`, or from anywhere inside the repository):
  python3 scripts/hygiene/check_repo.py                 every tracked and untracked (not ignored) file
  python3 scripts/hygiene/check_repo.py --staged        the staged files (the pre-commit hook)
  python3 scripts/hygiene/check_repo.py --pre-push      the pushed range, read from the hook's stdin
  python3 scripts/hygiene/check_repo.py --range A..B    the files changed by every commit of a range
Options: --rules r1,r2 (a subset, see --list-rules), --manifest PATH, --require-manifest, --no-denylist.

Exit status: 0 clean, 1 findings, 2 usage or git error. Findings name the rule, the file and the line, and never print
a denylisted value or a whole secret. Python standard library only.

Two line markers exist: `hygiene: test-vector` exempts that line (or, written as `hygiene: test-vector begin` and
`hygiene: test-vector end`, the lines between) from the address, MAC, home-path and credential rules, for the
server's SSRF vectors and the shared signing vectors; `hygiene: phonetic-example` exempts a line from the language
scan. Neither marker exempts the local denylist, private keys or keystores.
"""

from __future__ import annotations

import argparse
import bisect
import fnmatch
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

sys.path.insert(0, str(Path(__file__).resolve().parent))
import language_words  # noqa: E402
import sensitive  # noqa: E402

RULES = {
    "forbidden-dir": "no directory under android/ named data, out, out_*, downloads or workspace (architecture 2.6)",
    "ignored-source": "no source, schema, resource, font, licence text or asset under android/ is ignored (X-AC5)",
    "ignore-rules": "android/.gitignore ignores build outputs, local.properties and keystores (X-AC5)",
    "never-commit": "no build output, device dump or media file is committed under android/ (2.6)",
    "keystore": "no keystore or signing material is committed (X-AC4)",
    "language-folder": "no resource folder with a language qualifier; values-night and the like pass (X-AC2)",
    "language": "heuristic scan for non-English text; IPA and phonetic examples pass (X-AC2)",
    "address": "IPv4/IPv6 literals only from the documentation ranges, loopback, the wildcard, broadcast, 10.0.2.2",
    "mac": "MAC addresses only from the documentation range 00:00:5e:00:53:xx",
    "home-path": "no home path (/home/<name>/, /Users/<name>/)",
    "private-key": "no private key",
    "credential": "no pairing token, Bearer value, signed header, pairing code (482913 is the placeholder) or id",
    "destructive-migration": "no fallbackToDestructiveMigration (architecture 4.4)",
    "android-log": "android.util.Log only inside the PhonoLog facade (20.4)",
    "okhttp": "OkHttpClient constructed only in :core:network (5.2)",
    "loopback-policy": "no AddressPolicy that allows loopback outside test sources (security review L-13)",
    "tokens": "no Color(0x…) or .sp literal in Kotlin outside :core:designsystem (design 12.5, architecture 17)",
    "manifest": "the merged release manifest's exported components and permissions equal android/SECURITY.md (20.1)",
    "denylist": "none of this computer's identifiers (host, user, home, git e-mail, serials, addresses, MACs)",
    "qa-report-url": "android/docs/qa-report.md holds no URL with a video or post id",
}

SECRET_RULES = {"address", "mac", "home-path", "credential"}  # the ones `hygiene: test-vector` exempts
TEST_VECTOR_MARK = "hygiene: test-vector"
PHONETIC_MARK = "hygiene: phonetic-example"

FORBIDDEN_DIR_RE = re.compile(r"^(?:data|out|out_.*|downloads|workspace)$")
# Ignored directories that are expected under android/ (build outputs and local tool state, android/.gitignore).
EXPECTED_IGNORED_DIRS = {
    "build", ".gradle", ".kotlin", ".cxx", ".externalNativeBuild", "captures", ".idea", "__pycache__",
    ".pytest_cache", "node_modules",
}
EXPECTED_IGNORED_FILES = (
    "*.apk", "*.aab", "*.iml", "local.properties", "*.pyc", "*.jks", "*.keystore", "keystore.properties", "*.p12",
    "*.pfx", "*.pem", "*.pepk", "*.preferences_pb", "*.db", "*.db-wal", "*.db-shm", "*.db-journal", "*.hprof",
    "bugreport*.zip", "logcat*.txt", ".DS_Store", "*.log", ".env", ".env.*",
)
KEYSTORE_FILES = ("*.jks", "*.keystore", "*.p12", "*.pfx", "*.pepk", "*.pem", "keystore.properties")
KEYSTORE_MAGIC = (b"\xfe\xed\xfe\xed", b"\xce\xce\xce\xce")
NEVER_COMMIT_FILES = (
    "local.properties", "*.apk", "*.aab", "*.preferences_pb", "*.db", "*.db-wal", "*.db-shm", "*.db-journal",
    "*.hprof", "bugreport*.zip", "logcat*.txt", "*.wav", "*.mp4", "*.webm", "*.mp3", "*.m4a", "*.aac", "*.ogg",
    "*.opus", "*.mkv", "*.mov", "*.flac", "*.3gp",
)
NEVER_COMMIT_DIRS = {"build", ".gradle", ".kotlin", ".cxx", ".externalNativeBuild", "captures", ".idea"}
SOURCE_SUFFIXES = {
    ".kt", ".kts", ".java", ".xml", ".json", ".ttf", ".otf", ".txt", ".md", ".py", ".sh", ".toml", ".properties",
    ".pro", ".png", ".webp", ".svg", ".jpg", ".jpeg", ".gif", ".mjs", ".js", ".ts", ".gradle", ".sql", ".yml",
    ".yaml", ".html", ".css", ".csv",
}
# Probes for the ignore rules: the first must be ignored, the second must stay tracked (X-AC5).
MUST_IGNORE = (
    "android/build/probe", "android/app/build/probe", "android/.gradle/probe", "android/.kotlin/probe",
    "android/local.properties", "android/keystore.properties", "android/app/release.jks",
    "android/app/release.keystore", "android/captures/probe", "android/.cxx/probe", "android/app/app-release.apk",
    "android/app/app-release.aab", "android/artifacts/probe",
)
MUST_TRACK = (
    "android/app/src/main/kotlin/com/example/Probe.kt", "android/core/database/schemas/probe/1.json",
    "android/core/designsystem/src/main/res/font/probe.ttf",
    "android/core/designsystem/src/main/assets/licenses/OFL.txt", "android/app/src/main/res/values/strings.xml",
)
RES_TYPES = {
    "anim", "animator", "color", "drawable", "font", "interpolator", "layout", "menu", "mipmap", "navigation", "raw",
    "transition", "values", "xml",
}
NON_LANGUAGE_SHORT_QUALIFIERS = {"tv", "car"}
WALK_PRUNE = EXPECTED_IGNORED_DIRS | {".git"}
BINARY_SUFFIXES = {
    ".png", ".webp", ".jpg", ".jpeg", ".gif", ".ico", ".ttf", ".otf", ".woff", ".woff2", ".jar", ".zip", ".apk",
    ".aab", ".so", ".class", ".dex", ".pdf", ".mp4", ".wav", ".mp3", ".webm", ".gz", ".xz", ".bin", ".db",
}
SKIPPED_PREFIXES = ("web/",)  # the web client belongs to another agent (work-plan 3.2); its own rules apply there
# Licence texts are third-party documents; language_words.py is the scan's own vocabulary.
LANGUAGE_EXEMPT_RE = re.compile(
    r"(?:^|/)(?:licenses?/|LICENSE|COPYING|NOTICE|OFL)|^android/scripts/hygiene/language_words\.py$", re.IGNORECASE
)


@dataclass(frozen=True, order=True)
class Finding:
    path: str
    line: int
    rule: str
    message: str

    def __str__(self) -> str:
        where = f"{self.path}:{self.line}" if self.line else self.path
        return f"{where}: {self.rule}: {self.message}"


@dataclass
class Item:
    path: str
    data: bytes | None  # None when only the path is checked (symlinks)
    commit_message: bool = False


# --------------------------------------------------------------------------------------------------------------------
# git


class GitError(RuntimeError):
    pass


def git(repo: Path, *args: str, input: bytes | None = None, check: bool = True) -> bytes:
    proc = subprocess.run(["git", "-C", str(repo), *args], input=input, capture_output=True)
    if check and proc.returncode != 0:
        raise GitError(f"git {' '.join(args)}: {proc.stderr.decode('utf-8', 'replace').strip()}")
    return proc.stdout


def split_z(data: bytes) -> list[str]:
    return [p.decode("utf-8", "surrogateescape") for p in data.split(b"\0") if p]


def repo_root(start: Path) -> Path:
    return Path(git(start, "rev-parse", "--show-toplevel").decode().strip())


def cat_blobs(repo: Path, specs: list[str]) -> dict[str, tuple[str, bytes]]:
    """Reads `<rev>:<path>` specs in one `git cat-file --batch`; returns spec -> (object id, bytes)."""
    if not specs:
        return {}
    proc = subprocess.run(
        ["git", "-C", str(repo), "cat-file", "--batch"],
        input="".join(s + "\n" for s in specs).encode("utf-8", "surrogateescape"),
        capture_output=True,
    )
    if proc.returncode != 0:
        raise GitError(f"git cat-file --batch: {proc.stderr.decode('utf-8', 'replace').strip()}")
    out, pos, result = proc.stdout, 0, {}
    for spec in specs:
        end = out.index(b"\n", pos)
        header = out[pos:end].decode("utf-8", "replace").split()
        pos = end + 1
        if len(header) < 3:  # "<spec> missing" or an ambiguous name
            continue
        size = int(header[2])
        result[spec] = (header[0], out[pos : pos + size])
        pos += size + 1
    return result


# --------------------------------------------------------------------------------------------------------------------
# Collecting what to scan


def worktree_items(repo: Path) -> list[Item]:
    paths = set(split_z(git(repo, "ls-files", "-z", "--cached")))
    paths |= set(split_z(git(repo, "ls-files", "-z", "--others", "--exclude-standard")))
    items = []
    for path in sorted(paths):
        full = repo / path
        if full.is_symlink():
            items.append(Item(path, None))
        elif full.is_file():
            items.append(Item(path, full.read_bytes()))
    return items


def staged_items(repo: Path) -> list[Item]:
    paths = split_z(git(repo, "diff", "--cached", "--name-only", "-z", "--no-renames", "--diff-filter=ACMR"))
    blobs = cat_blobs(repo, [":" + p for p in paths])
    return [Item(p, blobs[":" + p][1]) for p in paths if ":" + p in blobs]


def range_commits(repo: Path, spec: str) -> list[str]:
    return git(repo, "rev-list", "--reverse", *spec.split()).decode().split()


def pre_push_commits(repo: Path, stdin_text: str) -> list[str]:
    commits: list[str] = []
    for line in stdin_text.splitlines():
        parts = line.split()
        if len(parts) != 4:
            continue
        _local_ref, local_sha, _remote_ref, remote_sha = parts
        if set(local_sha) == {"0"}:
            continue  # a deleted ref pushes nothing
        if set(remote_sha) == {"0"}:
            spec = [local_sha, "--not", "--remotes"]
        else:
            spec = [f"{remote_sha}..{local_sha}"]
        try:
            found = git(repo, "rev-list", "--reverse", *spec).decode().split()
        except GitError:
            found = git(repo, "rev-list", "--reverse", local_sha, "--not", "--remotes").decode().split()
        commits += [c for c in found if c not in commits]
    return commits


def commit_items(repo: Path, commits: list[str]) -> list[Item]:
    items: list[Item] = []
    seen_objects: set[str] = set()
    for commit in commits:
        paths = split_z(git(
            repo, "diff-tree", "-r", "-z", "--no-commit-id", "--name-only", "--no-renames", "--diff-filter=ACMR",
            "--root", "-m", "--first-parent", commit,
        ))
        blobs = cat_blobs(repo, [f"{commit}:{p}" for p in paths])
        for path in paths:
            blob = blobs.get(f"{commit}:{path}")
            if blob and blob[0] not in seen_objects:
                seen_objects.add(blob[0])
                items.append(Item(path, blob[1]))
        message = git(repo, "log", "-n1", "--format=%B", commit)
        items.append(Item(f"<commit {commit[:12]} message>", message, commit_message=True))
    return items


def skipped(path: str) -> bool:
    return path.startswith(SKIPPED_PREFIXES)


# --------------------------------------------------------------------------------------------------------------------
# Path rules


def language_qualifier(dirname: str) -> str | None:
    """The language qualifier of a resource directory name (`values-es`, `values-b+es+419`), or None."""
    parts = dirname.split("-")
    if parts[0] not in RES_TYPES or len(parts) < 2:
        return None
    qualifiers = parts[1:]
    i = 0
    while i < len(qualifiers) and re.fullmatch(r"(?:mcc|mnc)\d+", qualifiers[i]):
        i += 1
    if i == len(qualifiers):
        return None
    q = qualifiers[i]
    if q.startswith("b+") or (re.fullmatch(r"[a-z]{2,3}", q) and q not in NON_LANGUAGE_SHORT_QUALIFIERS):
        return q
    return None


def matches_any(name: str, patterns) -> bool:
    return any(fnmatch.fnmatchcase(name, p) for p in patterns)


def path_findings(path: str, rules: set[str]) -> list[Finding]:
    found: list[Finding] = []
    parts = PurePosixPath(path).parts
    name = parts[-1]
    in_android = parts[0] == "android"
    if "keystore" in rules and matches_any(name, KEYSTORE_FILES):
        found.append(Finding(path, 0, "keystore", "signing material must stay outside the repository (20.5)"))
    if in_android:
        dirs = parts[1:-1]
        for depth, d in enumerate(dirs, start=2):
            where = "/".join(parts[:depth]) + "/"  # the directory itself, as the tree walk reports it
            if "forbidden-dir" in rules and FORBIDDEN_DIR_RE.match(d):
                found.append(Finding(where, 0, "forbidden-dir", f"directory named '{d}' (root .gitignore trap)"))
            q = language_qualifier(d) if "language-folder" in rules else None
            if q:
                found.append(Finding(where, 0, "language-folder", f"'{d}' has the language qualifier '{q}'"))
        if "never-commit" in rules:
            if matches_any(name, NEVER_COMMIT_FILES) or any(d in NEVER_COMMIT_DIRS for d in dirs) or (
                len(parts) > 2 and parts[1] == "artifacts"
            ):
                found.append(Finding(path, 0, "never-commit", "build output, device dump or media file"))
    return found


def walk_android_dirs(repo: Path):
    """Every directory under android/ on disk, except build outputs and tool state."""
    root = repo / "android"
    if not root.is_dir():
        return
    for current, dirnames, _files in os.walk(root):
        rel = Path(current).relative_to(repo).as_posix()
        dirnames[:] = sorted(
            d for d in dirnames if d not in WALK_PRUNE and not (rel == "android" and d == "artifacts")
        )
        for d in dirnames:
            yield f"{rel}/{d}"


def tree_findings(repo: Path, rules: set[str]) -> list[Finding]:
    found: list[Finding] = []
    if rules & {"forbidden-dir", "language-folder"}:
        for d in walk_android_dirs(repo):
            name = d.rsplit("/", 1)[-1]
            if "forbidden-dir" in rules and FORBIDDEN_DIR_RE.match(name):
                found.append(Finding(d + "/", 0, "forbidden-dir", f"directory named '{name}' (root .gitignore trap)"))
            q = language_qualifier(name)
            if "language-folder" in rules and q:
                found.append(Finding(d + "/", 0, "language-folder", f"'{name}' has the language qualifier '{q}'"))
    if "ignored-source" in rules and (repo / "android").is_dir():
        status = split_z(git(
            repo, "status", "--porcelain=v1", "-z", "--ignored=matching", "--untracked-files=all", "--", "android/"
        ))
        for entry in status:
            if not entry.startswith("!! "):
                continue
            path = entry[3:]
            reason = unexpected_ignored(path)
            if reason:
                found.append(Finding(path, 0, "ignored-source", reason))
    if "ignore-rules" in rules and (
        (repo / "android" / "settings.gradle.kts").exists() or (repo / "android" / "gradlew").exists()
    ):
        probes = "\n".join(MUST_IGNORE + MUST_TRACK) + "\n"
        ignored = set(git(repo, "check-ignore", "--no-index", "--stdin", input=probes.encode(), check=False)
                      .decode().split())
        for probe in MUST_IGNORE:
            if probe not in ignored:
                found.append(Finding("android/.gitignore", 0, "ignore-rules", f"does not ignore {probe}"))
        for probe in MUST_TRACK:
            if probe in ignored:
                found.append(Finding("android/.gitignore", 0, "ignore-rules", f"ignores the source path {probe}"))
    return found


def unexpected_ignored(path: str) -> str | None:
    """Why an ignored path under android/ is a problem, or None when it is an expected build output or local file."""
    is_dir = path.endswith("/")
    parts = PurePosixPath(path.rstrip("/")).parts
    if any(p in EXPECTED_IGNORED_DIRS for p in parts):
        return None
    if len(parts) > 1 and parts[1] == "artifacts":
        return None
    name = parts[-1]
    if is_dir:
        return f"ignored directory '{name}': its files would silently vanish from commits"
    if matches_any(name, EXPECTED_IGNORED_FILES):
        return None
    if "src" in parts or "assets" in parts or "res" in parts:
        return "ignored file inside a source set (media are synthesized at test time, 21.4)"
    if PurePosixPath(name).suffix.lower() in SOURCE_SUFFIXES:
        return "ignored source file would silently vanish from commits"
    return None


# --------------------------------------------------------------------------------------------------------------------
# Language scan (heuristic)

STRONG = {
    "Spanish": language_words.SPANISH_STRONG,
    "Portuguese": language_words.PORTUGUESE_STRONG,
    "French": language_words.FRENCH_STRONG,
    "German": language_words.GERMAN_STRONG,
}
WORD_RE = re.compile(r"[^\W\d_]+")
SHORT_QUOTE_RE = re.compile(r"[“\"'‘«]([^“”\"'‘’«»\n]{1,40})[”\"'’»]")
IPA_SPAN_RE = re.compile(r"/[^/\s][^/\n]{0,40}/|\[[^\[\]\n]{1,40}\]")


def _strip_examples(line: str) -> str:
    """Removes IPA spans and quoted examples of at most two words (“pero”), which are content, not language."""
    line = IPA_SPAN_RE.sub(" ", line)
    return SHORT_QUOTE_RE.sub(lambda m: " " if len(m.group(1).split()) <= 2 else m.group(0), line)


def non_english(line: str, strict: bool = False) -> str | None:
    """Why a line looks non-English, or None. `strict` is for UI string resources, which are short: one marker
    word, one accented word (capitalized or not) or an inverted mark is enough there."""
    if PHONETIC_MARK in line:
        return None
    words = WORD_RE.findall(_strip_examples(line))
    if not words:
        return None
    lower = [w.lower() for w in words]
    accented = {
        w.lower()
        for w in words
        if (strict or w[0].islower()) and language_words.ACCENTED & set(w)
        and w.lower() not in language_words.LOANWORDS
    }
    punctuation = 1 if ("¿" in line or "¡" in line) else 0
    weak = {w for w in lower if w in language_words.SPANISH_WEAK}
    flagged = False
    counts = {}
    for language, strong_words in STRONG.items():
        strong = {w for w in lower if w in strong_words}
        counts[language] = len(strong)
        score = len(strong | accented) + (punctuation if language == "Spanish" else 0)
        flagged |= score >= (1 if strict else 2) or (language == "Spanish" and score >= 1 and len(weak) >= 2)
    if not flagged:
        return None
    language = max(counts, key=lambda k: counts[k]) if any(counts.values()) else "Spanish"
    return f"looks like {language}, not English"


# --------------------------------------------------------------------------------------------------------------------
# Content rules


def is_binary(path: str, data: bytes) -> bool:
    return PurePosixPath(path).suffix.lower() in BINARY_SUFFIXES or b"\0" in data[:8192]


def mask(text: str) -> str:
    return text[:4] + "…" if len(text) > 4 else "…"


def kotlin_like(path: str) -> bool:
    return path.endswith((".kt", ".kts", ".java"))


def in_test_code(path: str) -> bool:
    return bool(re.search(r"/src/(?:test|androidTest|testFixtures|sharedTest)\w*/", path)) or path.startswith(
        ("android/core/testing-jvm/", "android/core/testing/")
    )


STRING_RESOURCE_RE = re.compile(r"/res/values[^/]*/[^/]+\.xml$")
STRING_ELEMENT_RE = re.compile(r"<(?:string|item)\b")
LOG_RE = re.compile(r"\bandroid\.util\.(?:Log\b|\*)")
OKHTTP_RE = re.compile(r"\bOkHttpClient\s*(?:\.\s*Builder\s*)?\(")
ADDRESS_POLICY_IMPL_RE = re.compile(r"(?::\s*AddressPolicy\b|object\s*:\s*AddressPolicy\b|AddressPolicy\s*\{)")
LOOPBACK_HINT_RE = re.compile(r"127\.\d+\.\d+\.\d+|\"localhost\"|\"::1\"|\bisLoopbackAddress\b|\bLOOPBACK\b")


def content_findings(item: Item, rules: set[str], denylist: list[sensitive.DenyEntry]) -> list[Finding]:
    path, data = item.path, item.data
    found: list[Finding] = []
    if data is None:
        return found
    if is_binary(path, data):
        if "keystore" in rules and data[:4] in KEYSTORE_MAGIC:
            found.append(Finding(path, 0, "keystore", "Java keystore content"))
        return found
    text = data.decode("utf-8", "replace")
    in_android = path.startswith("android/")
    code = in_android and kotlin_like(path)
    check_language = "language" in rules and not LANGUAGE_EXEMPT_RE.search(path)
    qa_report = path == "android/docs/qa-report.md"
    string_resource = bool(STRING_RESOURCE_RE.search(path))
    facade = bool(re.match(r"android/app/src/[^/]+/kotlin/.*PhonoLog[^/]*\.kt$", path))
    policy_file = code and not in_test_code(path) and ADDRESS_POLICY_IMPL_RE.search(text)
    in_vector_block = False

    for number, line in enumerate(text.splitlines(), start=1):
        vector = in_vector_block or TEST_VECTOR_MARK in line
        if TEST_VECTOR_MARK + " begin" in line:
            in_vector_block = True
        elif TEST_VECTOR_MARK + " end" in line:
            in_vector_block = False

        def add(rule: str, message: str) -> None:
            if rule in rules and not (vector and rule in SECRET_RULES):
                found.append(Finding(path, number, rule, message))

        if "private-key" in rules and sensitive.PRIVATE_KEY_RE.search(line):
            found.append(Finding(path, number, "private-key", "private key header"))
        for _s, _e, kind in sensitive.find_denied(line, denylist):
            if not (item.commit_message and kind == "git e-mail"):
                found.append(Finding(path, number, "denylist", f"this computer's {kind}"))
        if rules & SECRET_RULES and not vector:
            for _s, _e, literal, kind in sensitive.find_addresses(line):
                add("address", f"{kind} literal outside the allowed ranges ({mask(literal)})")
            for _s, _e, literal in sensitive.find_macs(line):
                add("mac", f"MAC address outside 00:00:5e:00:53:xx ({mask(literal)})")
            for _s, _e, literal in sensitive.find_home_paths(line):
                add("home-path", f"home path ({mask(literal)})")
            for _s, _e, kind in sensitive.find_credentials(line):
                add("credential", kind)
        if check_language:
            reason = non_english(line, strict=string_resource and STRING_ELEMENT_RE.search(line) is not None)
            if reason:
                add("language", reason)
        if qa_report:
            for _s, _e, _url in sensitive.find_post_links(line):
                add("qa-report-url", "URL with a video or post id (the link gate records outcomes only)")
        if code:
            if "fallbackToDestructiveMigration" in line:
                add("destructive-migration", "fallbackToDestructiveMigration (migrations are tested instead, 4.4)")
            if LOG_RE.search(line) and not facade:
                add("android-log", "android.util.Log outside the PhonoLog facade")
            if OKHTTP_RE.search(line) and not path.startswith("android/core/network/") and not in_test_code(path):
                add("okhttp", "OkHttpClient constructed outside :core:network")
            if policy_file and LOOPBACK_HINT_RE.search(line) and not _refuses(line):
                add("loopback-policy", "an AddressPolicy that allows loopback belongs in test sources only")
    if "tokens" in rules and code and not path.startswith(DESIGN_SYSTEM_PREFIX):
        found.extend(token_findings(path, text))
    return found


# Tokens only in screens (design 12.5, architecture 17): the colour and text-size literals belong to :core:designsystem,
# in every one of its source sets; every other Kotlin file under android/ (tests too) uses its tokens.
DESIGN_SYSTEM_PREFIX = "android/core/designsystem/"
COLOUR_LITERAL_RE = re.compile(r"\bColor\s*\(\s*(?:color\s*=\s*)?0[xX]")
TEXT_SIZE_RE = re.compile(r"\.sp\b(?!\s*\()|\bTextUnitType\s*\.\s*Sp\b")
DECLARATION_RE = re.compile(r"[ \t]*(?:import|package)\s")
TOKEN_CHECKS = (
    (COLOUR_LITERAL_RE, "Color(0x…) colour literal outside :core:designsystem; use a theme colour (design 12.5)"),
    (TEXT_SIZE_RE, ".sp text size outside :core:designsystem; use the type scale (design 12.5)"),
)
LINE_BREAKS = frozenset("\n\r\x0b\x0c\x1c\x1d\x1e\x85\u2028\u2029")  # the ones str.splitlines() splits on
CHAR_LITERAL_RE = re.compile(r"'(?:\\(?:u[0-9A-Fa-f]{4}|.)|[^'\\\n\r])'")


def kotlin_code(text: str) -> str:
    """`text` with Kotlin's (or Java's) comments and string and character literals replaced by spaces. Every line
    break stays where it was, so offsets and line numbers still match the text; a string template's `${…}` stays code.
    A regular string ends at its line even when its closing quote is missing."""
    out = list(text)
    n = len(text)

    def blank(start: int, end: int) -> None:
        for k in range(start, min(end, n)):
            if out[k] not in LINE_BREAKS:
                out[k] = " "

    # Open contexts, innermost last: ["code", open braces] for the file and each template, ["str", delimiter].
    stack: list[list] = [["code", 0]]
    i = 0
    while i < n:
        top, c = stack[-1], text[i]
        if top[0] == "code":
            if text.startswith("//", i):
                end = i
                while end < n and text[end] not in "\n\r":
                    end += 1
                blank(i, end)
                i = end
            elif text.startswith("/*", i):  # Kotlin block comments nest
                depth, end = 1, i + 2
                while end < n and depth:
                    if text.startswith("/*", end):
                        depth, end = depth + 1, end + 2
                    elif text.startswith("*/", end):
                        depth, end = depth - 1, end + 2
                    else:
                        end += 1
                blank(i, end)
                i = end
            elif text.startswith('"""', i):
                stack.append(["str", '"""'])
                blank(i, i + 3)
                i += 3
            elif c == '"':
                stack.append(["str", '"'])
                blank(i, i + 1)
                i += 1
            elif c == "'" and (char := CHAR_LITERAL_RE.match(text, i)):
                blank(i, char.end())
                i = char.end()
            elif c == "}" and top[1] == 0 and len(stack) > 1:  # the end of a template: back into its string
                stack.pop()
                blank(i, i + 1)
                i += 1
            else:
                if c == "{":
                    top[1] += 1
                elif c == "}":
                    top[1] = max(0, top[1] - 1)
                i += 1
        elif text.startswith("${", i):
            stack.append(["code", 0])
            blank(i, i + 2)
            i += 2
        elif top[1] == '"' and c == "\\":
            blank(i, i + 2)
            i += 2
        elif top[1] == '"' and (c == '"' or c in LINE_BREAKS):
            stack.pop()
            blank(i, i + 1)
            i += 1
        elif top[1] == '"""' and text.startswith('"""', i):
            end = i
            while end < n and text[end] == '"':  # the closing delimiter is the last three quotes of a run
                end += 1
            stack.pop()
            blank(i, end)
            i = end
        else:
            blank(i, i + 1)
            i += 1
    return "".join(out)


def token_findings(path: str, text: str) -> list[Finding]:
    """Colour and text-size literals in the code of a Kotlin file (comments, strings and imports do not count)."""
    code = kotlin_code(text)
    starts, offset = [], 0
    for line in text.splitlines(keepends=True):
        starts.append(offset)
        offset += len(line)
    found = []
    for pattern, message in TOKEN_CHECKS:
        for match in pattern.finditer(code):
            number = bisect.bisect_right(starts, match.start())
            if not DECLARATION_RE.match(code, starts[number - 1]):
                found.append(Finding(path, number, "tokens", message))
    return found


def _refuses(line: str) -> bool:
    """A loopback mention that refuses it (`!a.isLoopbackAddress`, `if (a.isLoopbackAddress) return false`)."""
    stripped = line.strip()
    return stripped.startswith(("//", "*", "/*")) or bool(re.search(r"!\s*\w+(?:\.\w+)*\.isLoopbackAddress|\bfalse\b",
                                                                   line))


# --------------------------------------------------------------------------------------------------------------------
# Manifest allowlist (android/SECURITY.md)

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
COMPONENT_KINDS = ("activity", "activity-alias", "service", "receiver", "provider")
MANIFEST_GLOBS = (
    "android/app/build/intermediates/merged_manifest/release/**/AndroidManifest.xml",
    "android/app/build/intermediates/merged_manifests/release/**/AndroidManifest.xml",
    "android/app/build/intermediates/packaged_manifests/release/**/AndroidManifest.xml",
)


def find_release_manifest(repo: Path) -> Path | None:
    for pattern in MANIFEST_GLOBS:
        hits = sorted(repo.glob(pattern))
        if hits:
            return hits[0]
    return None


def manifest_entries(manifest: Path) -> set[tuple[str, str]]:
    root = ET.parse(manifest).getroot()
    package = root.get("package", "")
    entries: set[tuple[str, str]] = set()
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for node in root.iter(tag):
            entries.add(("permission", node.get(ANDROID_NS + "name", "")))
    application = root.find("application")
    if application is not None:
        for kind in COMPONENT_KINDS:
            for node in application.iter(kind):
                if node.get(ANDROID_NS + "exported") == "true":
                    name = node.get(ANDROID_NS + "name", "")
                    entries.add((kind, package + name if name.startswith(".") else name))
    return entries


def security_allowlist(text: str) -> set[tuple[str, str]] | None:
    """The `Kind | Name` table after the heading that contains "merged release manifest", or None."""
    lines = text.splitlines()
    start = next(
        (i for i, l in enumerate(lines) if l.startswith("#") and "merged release manifest" in l.lower()), None
    )
    if start is None:
        return None
    entries: set[tuple[str, str]] = set()
    header_seen = False
    for line in lines[start + 1 :]:
        if line.startswith("#"):
            break
        if not line.strip().startswith("|"):
            if header_seen:
                break
            continue
        cells = [c.strip().strip("`").strip() for c in line.strip().strip("|").split("|")]
        if not header_seen:
            header_seen = True
            continue
        if len(cells) < 2 or set(cells[0]) <= set("-: "):
            continue
        entries.add((cells[0].lower(), cells[1]))
    return entries if header_seen else None


def manifest_findings(repo: Path, manifest: Path | None, required: bool) -> list[Finding]:
    manifest = manifest or find_release_manifest(repo)
    if manifest is None or not manifest.exists():
        if required:
            return [Finding("android/app", 0, "manifest", "no merged release manifest; run ./gradlew assembleRelease")]
        return []
    security = repo / "android" / "SECURITY.md"
    allowed = security_allowlist(security.read_text("utf-8")) if security.exists() else None
    if allowed is None:
        return [Finding("android/SECURITY.md", 0, "manifest", "no 'Merged release manifest' table to compare with")]
    actual = manifest_entries(manifest)
    found = []
    for kind, name in sorted(actual - allowed):
        found.append(Finding("android/SECURITY.md", 0, "manifest", f"{kind} {name} is in the manifest, not listed"))
    for kind, name in sorted(allowed - actual):
        found.append(Finding("android/SECURITY.md", 0, "manifest", f"{kind} {name} is listed, not in the manifest"))
    return found


# --------------------------------------------------------------------------------------------------------------------
# Driver


def run(
    repo: Path,
    mode: str = "worktree",
    rules: set[str] | None = None,
    denylist: list[sensitive.DenyEntry] | None = None,
    range_spec: str | None = None,
    pre_push_stdin: str = "",
    manifest: Path | None = None,
    require_manifest: bool = False,
) -> tuple[list[Finding], int]:
    """Returns (findings, number of files scanned)."""
    rules = set(RULES) if rules is None else rules
    if denylist is None:
        denylist = []
    if mode == "worktree":
        items = worktree_items(repo)
    elif mode == "staged":
        items = staged_items(repo)
    elif mode == "range":
        items = commit_items(repo, range_commits(repo, range_spec or ""))
    elif mode == "pre-push":
        items = commit_items(repo, pre_push_commits(repo, pre_push_stdin))
    else:
        raise ValueError(mode)
    items = [i for i in items if not skipped(i.path)]
    findings: set[Finding] = set()
    for item in items:
        if not item.commit_message:
            findings.update(path_findings(item.path, rules))
            for _s, _e, kind in sensitive.find_denied(item.path, denylist):
                findings.add(Finding(item.path, 0, "denylist", f"this computer's {kind} in the path"))
        findings.update(content_findings(item, rules, denylist))
    if mode in ("worktree", "staged"):
        findings.update(tree_findings(repo, rules))
    if "manifest" in rules and (mode == "worktree" or manifest is not None or require_manifest):
        findings.update(manifest_findings(repo, manifest, require_manifest))
    return sorted(findings), len(items)


def in_ci(environ) -> bool:
    return environ.get("GITHUB_ACTIONS") == "true" or environ.get("CI") == "true"


def main(argv: list[str] | None = None, stdin=None, environ=None) -> int:
    environ = os.environ if environ is None else environ
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--staged", action="store_true", help="check the staged files (pre-commit)")
    group.add_argument("--pre-push", nargs="*", metavar="ARG", help="check the pushed range (hook stdin)")
    group.add_argument("--range", metavar="A..B", help="check every commit of a range")
    parser.add_argument("--rules", help="comma-separated subset of rules")
    parser.add_argument("--list-rules", action="store_true")
    parser.add_argument("--manifest", type=Path, help="merged release manifest (default: found under app/build)")
    parser.add_argument("--require-manifest", action="store_true", help="fail when no merged manifest exists")
    parser.add_argument("--no-denylist", action="store_true", help="skip the local denylist (it is skipped in CI)")
    parser.add_argument("--repo", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.list_rules:
        for name, description in RULES.items():
            print(f"{name:22} {description}")
        return 0
    rules = set(RULES)
    if args.rules:
        rules = {r.strip() for r in args.rules.split(",") if r.strip()}
        unknown = rules - set(RULES)
        if unknown:
            parser.error(f"unknown rules: {', '.join(sorted(unknown))}")
    try:
        repo = repo_root(args.repo or Path(__file__).resolve().parent)
        denylist = []
        if "denylist" in rules and not args.no_denylist and not in_ci(environ):
            denylist = sensitive.local_denylist(repo, environ)
        if args.staged:
            mode = "staged"
        elif args.pre_push is not None:
            mode = "pre-push"
        elif args.range:
            mode = "range"
        else:
            mode = "worktree"
        stdin_text = (stdin or sys.stdin).read() if mode == "pre-push" else ""
        findings, scanned = run(
            repo, mode, rules, denylist, args.range, stdin_text, args.manifest, args.require_manifest
        )
    except GitError as error:
        print(f"check_repo: {error}", file=sys.stderr)
        return 2
    for finding in findings:
        print(finding)
    if findings:
        print(f"check_repo: {len(findings)} finding(s) in {scanned} file(s) checked ({mode}).", file=sys.stderr)
        return 1
    print(f"check_repo: clean ({scanned} file(s) checked, {mode}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
