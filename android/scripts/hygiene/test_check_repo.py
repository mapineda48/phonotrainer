"""Tests of check_repo.py: a temporary git repository with one planted violation per rule (work-plan WP-06 Proof)."""

from __future__ import annotations

import io
import socket
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_repo  # noqa: E402
import sensitive  # noqa: E402
from testsupport import TempRepo, planted  # noqa: E402

JKS_MAGIC = bytes([0xFE, 0xED, 0xFE, 0xED])


class RepoTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = TempRepo()
        self.addCleanup(self.repo.cleanup)

    def findings(self, mode: str = "worktree", rules=None, denylist=None, **kwargs):
        found, _count = check_repo.run(self.repo.path, mode, rules, denylist or [], **kwargs)
        return found

    def rules_found(self, **kwargs) -> set[str]:
        return {f.rule for f in self.findings(**kwargs)}

    def assertRule(self, rule: str, path_part: str = "", **kwargs) -> None:
        hits = [f for f in self.findings(**kwargs) if f.rule == rule and path_part in f.path]
        self.assertTrue(hits, f"expected a {rule} finding in {path_part or 'the repository'}")

    def assertClean(self, **kwargs) -> None:
        found = self.findings(**kwargs)
        self.assertEqual([], [str(f) for f in found])


class CleanTree(RepoTestCase):
    def test_a_clean_tree_has_no_findings(self):
        self.repo.write("android/docs/notes.md", "The computer answers at 192.0.2.10:8000 and 127.0.0.1.\n")
        self.repo.write("android/app/src/main/res/values/strings.xml", "<resources/>\n")
        self.repo.commit()
        self.assertClean()

    def test_values_night_and_other_non_language_qualifiers_pass(self):
        for folder in ("values-night", "drawable-night", "values-v29", "values-sw600dp", "mipmap-anydpi",
                       "values-ldrtl", "drawable-xxhdpi", "values-land", "values-car", "values-tv"):
            self.repo.write(f"android/app/src/main/res/{folder}/x.xml", "<resources/>\n")
        self.assertClean()


class PathRules(RepoTestCase):
    def test_forbidden_directory_name_is_found_even_though_git_ignores_it(self):
        self.repo.write("android/app/src/main/kotlin/x/data/Repo.kt", "package x.data\n")
        self.assertRule("forbidden-dir", "x/data")
        self.assertRule("ignored-source", "x/data")

    def test_other_forbidden_names(self):
        for name in ("out", "out_old", "downloads", "workspace"):
            self.repo.write(f"android/core/{name}/A.kt", "class A\n")
        dirs = {f.path for f in self.findings() if f.rule == "forbidden-dir"}
        self.assertEqual({"android/core/out/", "android/core/out_old/", "android/core/downloads/",
                          "android/core/workspace/"}, dirs)

    def test_build_outputs_are_expected_ignored_paths(self):
        self.repo.write("android/app/build/intermediates/out/x.txt", "build output\n")
        self.repo.write("android/local.properties", "sdk.dir=/opt/sdk\n")
        self.assertClean()

    def test_an_ignored_media_file_in_a_source_set_is_found(self):
        self.repo.write("android/core/player/src/test/resources/tone.wav", b"RIFF")
        self.assertRule("ignored-source", "tone.wav")

    def test_ignore_rules_are_checked_once_the_gradle_build_exists(self):
        repo = TempRepo(android_gitignore=False)
        self.addCleanup(repo.cleanup)
        repo.write("android/settings.gradle.kts", "rootProject.name = \"x\"\n")
        found, _ = check_repo.run(repo.path, "worktree", {"ignore-rules"}, [])
        messages = " ".join(f.message for f in found)
        self.assertIn("android/local.properties", messages)
        self.assertIn("android/app/release.jks", messages)
        self.repo.write("android/settings.gradle.kts", "rootProject.name = \"x\"\n")
        self.assertEqual([], self.findings(rules={"ignore-rules"}))

    def test_tracked_build_output_and_device_dump(self):
        self.repo.write("android/app/build/x.txt", "x\n")
        self.repo.write("android/library.db-wal", b"\0")
        self.repo.git("add", "-f", "android/app/build/x.txt", "android/library.db-wal")
        self.assertRule("never-commit", "android/app/build/x.txt")
        self.assertRule("never-commit", "library.db-wal")

    def test_keystore_by_name_and_by_content(self):
        self.repo.write("android/app/release.jks", JKS_MAGIC + b"\0\0\0\2")
        self.repo.git("add", "-f", "android/app/release.jks")
        self.repo.write("android/app/signing.bin", JKS_MAGIC + b"\0\0\0\2")
        self.assertRule("keystore", "release.jks")
        self.assertRule("keystore", "signing.bin")

    def test_language_resource_folders(self):
        for folder in ("values-es", "values-es-rMX", "values-b+es+419", "values-mcc310-en", "raw-fr"):
            self.repo.write(f"android/app/src/main/res/{folder}/strings.xml", "<resources/>\n")
        found = {f.path for f in self.findings() if f.rule == "language-folder"}
        self.assertEqual(5, len(found), found)

    def test_language_qualifier_parsing(self):
        self.assertEqual("es", check_repo.language_qualifier("values-es"))
        self.assertEqual("b+es+419", check_repo.language_qualifier("values-b+es+419"))
        self.assertIsNone(check_repo.language_qualifier("values-night"))
        self.assertIsNone(check_repo.language_qualifier("drawable-night-v31"))
        self.assertIsNone(check_repo.language_qualifier("kotlin-es"))  # not a resource folder


class ContentRules(RepoTestCase):
    def check_line(self, text: str, rule: str, path: str = "android/docs/notes.md") -> list:
        self.repo.write(path, text + "\n")
        return [f for f in self.findings() if f.rule == rule and f.path == path]

    def test_private_and_public_ipv4(self):
        self.assertTrue(self.check_line(f"reach it at {planted('private-ipv4')}:8000", "address"))
        self.assertTrue(self.check_line(f"dns {planted('public-ipv4')}", "address"))

    def test_allowed_addresses(self):
        line = "192.0.2.10 198.51.100.7 203.0.113.9 127.0.0.1 0.0.0.0 255.255.255.255 10.0.2.2 2001:db8::7 ::1 " \
               "10.0.0.0/8 192.168.0.0/16 64:ff9b::/96 255.255.255.0"
        self.assertEqual([], self.check_line(line, "address"))

    def test_ipv6_literal(self):
        self.assertTrue(self.check_line(f"link-local {planted('ipv6')}", "address"))

    def test_geometry_is_not_an_address(self):
        self.assertEqual([], self.check_line('<path d="M15 14c.2-1 .7-1.7 1.5-2.5.3.5.7.7 1.5"/>', "address",
                                             "android/design/icon.svg"))

    def test_mac_address(self):
        self.assertTrue(self.check_line(f"gateway {planted('mac')}", "mac"))
        self.assertEqual([], self.check_line("doc 00:00:5e:00:53:2a and 02:00:00:00:00:00", "mac"))

    def test_home_paths(self):
        self.assertTrue(self.check_line(f"see {planted('home')}/x", "home-path"))
        self.assertTrue(self.check_line(f"see {planted('users')}", "home-path"))
        self.assertEqual([], self.check_line("feature/home/HomeScreen.kt and /home/<name>/ and ~/.config", "home-path"))

    def test_private_key(self):
        self.assertTrue(self.check_line(planted("private-key"), "private-key"))

    def test_credential_shapes(self):
        for kind in ("token", "bearer", "header", "code-json", "code-text", "server-id"):
            with self.subTest(kind=kind):
                self.assertTrue(self.check_line(f"x {planted(kind)} y", "credential"), kind)

    def test_placeholders_pass(self):
        line = '{"code": "482913"} Pairing code: 482 913  pt1_<43 base64url characters> srv_example1 ' \
               "dev_example1 00000000-0000-4000-8000-000000000001 Authorization: PT1 device=<id>, mac=<64 hex>"
        self.assertEqual([], self.check_line(line, "credential"))

    def test_long_random_token_but_not_digests(self):
        self.assertTrue(self.check_line(f'key = "{planted("random-token")}"', "credential"))
        digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        self.assertEqual([], self.check_line(f"sha256 {digest} commit 3d3c42e5aac5ba805825da76410c181273ba90b1",
                                             "credential"))

    def test_test_vector_marker_on_a_line_and_in_a_block(self):
        ip = planted("private-ipv4")
        self.assertEqual([], self.check_line(f'"{ip}",  # hygiene: test-vector', "address", "tests/test_ssrf.py"))
        block = f"# hygiene: test-vector begin\nA = '{ip}'\nB = '{planted('mac')}'\n# hygiene: test-vector end\n"
        self.repo.write("tests/test_vectors.py", block + f"C = '{ip}'\n")
        hits = [f for f in self.findings() if f.path == "tests/test_vectors.py"]
        self.assertEqual([("address", 5)], [(f.rule, f.line) for f in hits])

    def test_marker_does_not_exempt_a_private_key(self):
        self.assertTrue(self.check_line(planted("private-key") + "  hygiene: test-vector", "private-key"))

    def test_non_english_text(self):
        self.assertTrue(self.check_line(planted("spanish"), "language"))
        self.assertTrue(self.check_line(f"// {planted('spanish')}", "language", "android/app/src/main/kotlin/A.kt"))

    def test_string_resources_are_scanned_strictly(self):
        path = "android/feature/home/src/main/res/values/strings.xml"
        moon = "".join(chr(c) for c in [76, 117, 110, 97, 32, 110, 117, 101, 118, 97])
        self.assertTrue(self.check_line(f'<string name="phase">{moon}</string>', "language", path))
        english = '<string name="tip">The flap in \u201cwater\u201d: [\u027e]. Caf\u00e9 Wi-Fi is fine.</string>'
        self.assertEqual([], self.check_line(english, "language", path))

    def test_ipa_and_phonetic_examples_pass(self):
        lines = [
            "The flap is the single r of Spanish “pero”. You already have this sound.",
            "Many Spanish speakers already mix [ð] with [d]; /wɔɾɚ/ is water.",
            "In 1857 Édouard-Léon Scott de Martinville built the phonautograph; a café Wi-Fi.",
            planted("spanish") + "  hygiene: phonetic-example",
        ]
        self.assertEqual([], self.check_line("\n".join(lines), "language"))

    def test_qa_report_url(self):
        self.assertTrue(self.check_line(f"gate passed for {planted('video-url')}", "qa-report-url",
                                        "android/docs/qa-report.md"))
        self.assertEqual([], self.check_line(f"source {planted('video-url')}", "qa-report-url"))

    def test_destructive_migration(self):
        self.assertTrue(self.check_line(".fallbackToDestructiveMigration()", "destructive-migration",
                                        "android/core/database/src/main/kotlin/Db.kt"))

    def test_android_log_outside_the_facade(self):
        self.assertTrue(self.check_line("import android.util.Log", "android-log",
                                        "android/core/sync/src/main/kotlin/x/Sync.kt"))
        self.assertEqual([], self.check_line("import android.util.Log", "android-log",
                                             "android/app/src/main/kotlin/x/AndroidPhonoLog.kt"))

    def test_okhttp_outside_core_network(self):
        self.assertTrue(self.check_line("val c = OkHttpClient.Builder().build()", "okhttp",
                                        "android/core/sync/src/main/kotlin/x/A.kt"))
        self.assertEqual([], self.check_line("val c = OkHttpClient()", "okhttp",
                                             "android/core/network/src/main/kotlin/x/A.kt"))
        self.assertEqual([], self.check_line("val c = OkHttpClient()", "okhttp",
                                             "android/core/testing-jvm/src/test/kotlin/x/A.kt"))

    def test_loopback_address_policy(self):
        allowing = 'object P : AddressPolicy {\n  override fun allowsCandidate(host: String) = host == "127.0.0.1"\n}'
        self.assertTrue(self.check_line(allowing, "loopback-policy", "android/core/network/src/main/kotlin/P.kt"))
        self.assertEqual([], self.check_line(allowing, "loopback-policy",
                                             "android/app/src/androidTest/kotlin/e2e/di/P.kt"))
        refusing = "class Real : AddressPolicy {\n  fun f(a: java.net.InetAddress): Boolean {\n" \
                   "    if (a.isLoopbackAddress) return false\n    return a.isSiteLocalAddress\n  }\n}"
        self.assertEqual([], self.check_line(refusing, "loopback-policy", "android/core/network/src/main/kotlin/R.kt"))

    def test_web_is_not_scanned(self):
        self.repo.write("web/src/a.test.ts", f"const ip = '{planted('private-ipv4')}'\n")
        self.assertClean()


class Denylist(RepoTestCase):
    def synthetic_denylist(self):
        with mock.patch.object(socket, "gethostname", return_value="studydesk-7f3a"), \
                mock.patch.object(sensitive.getpass, "getuser", return_value="qauser7"), \
                mock.patch.object(sensitive, "_adb_serials", return_value=["R5" + "CW12AB34CD"]):
            return sensitive.local_denylist(self.repo.path, {"USER": "qauser7", "ANDROID_SERIAL": ""})

    def test_a_host_name_taken_from_the_denylist_is_found(self):
        denylist = self.synthetic_denylist()
        host = next(e.value for e in denylist if e.kind == "host name")
        self.repo.write("android/docs/notes.md", f"Built on {host} today.\n")
        hits = [f for f in self.findings(denylist=denylist) if f.rule == "denylist"]
        self.assertTrue(hits)
        self.assertNotIn(host, " ".join(str(f) for f in hits))  # the value is never printed

    def test_serial_user_and_email(self):
        denylist = self.synthetic_denylist()
        kinds = {e.kind for e in denylist}
        self.assertTrue({"host name", "user name", "git e-mail", "device serial"} <= kinds, kinds)
        serial = next(e.value for e in denylist if e.kind == "device serial")
        self.repo.write("android/a.md", f"adb -s {serial} shell\nby qauser7\nmail qa@example.invalid\n")
        hits = sorted(f.line for f in self.findings(denylist=denylist) if f.rule == "denylist")
        self.assertEqual([1, 2, 3], hits)

    def test_the_package_and_repository_forms_of_the_handle_pass(self):
        denylist = [sensitive.DenyEntry("user name", "qauser7")]
        self.repo.write("android/a.kt", "package com.qauser7.phonotrainer\n// src/main/kotlin/com/qauser7/x\n")
        self.repo.write("pyproject.toml", 'Homepage = "https://github.com/qauser7/phonotrainer"\n')
        self.assertEqual([], [f for f in self.findings(denylist=denylist) if f.rule == "denylist"])
        self.repo.write("android/b.md", "Ask qauser7 or mail.qauser7.example\n")
        self.assertEqual(1, len([f for f in self.findings(denylist=denylist) if f.rule == "denylist"]))

    def test_the_real_denylist_entries_are_found_when_planted(self):
        real = sensitive.local_denylist(self.repo.path)
        self.assertTrue(any(e.kind == "git e-mail" for e in real))
        lines = [f"value: {e.value} ." for e in real]
        self.repo.write("android/planted.md", "\n".join(lines) + "\n")
        hit_lines = {f.line for f in self.findings(denylist=real) if f.rule == "denylist"}
        self.assertEqual(set(range(1, len(lines) + 1)), hit_lines)

    def test_generic_host_names_are_not_denylisted(self):
        with mock.patch.object(socket, "gethostname", return_value="fedora"), \
                mock.patch.object(sensitive.getpass, "getuser", return_value="runner"):
            denylist = sensitive.local_denylist(self.repo.path, {"USER": "runner"})
        self.assertEqual([], [e.kind for e in denylist if e.kind in ("host name", "user name")])


class ManifestRule(RepoTestCase):
    MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
  <uses-permission android:name="android.permission.INTERNET"/>
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
  <application>
    <activity android:name=".MainActivity" android:exported="true"/>
    <activity android:name="com.example.share.ShareActivity" android:exported="true"/>
    <service android:name="androidx.work.impl.foreground.SystemForegroundService" android:exported="false"/>
  </application>
</manifest>
"""
    SECURITY = """# Security

## Merged release manifest

| Kind | Name |
|---|---|
| permission | `android.permission.INTERNET` |
| permission | `android.permission.POST_NOTIFICATIONS` |
| activity | `com.example.app.MainActivity` |
| activity | `com.example.share.ShareActivity` |
"""

    def manifest(self) -> Path:
        return self.repo.write("android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/"
                               "AndroidManifest.xml", self.MANIFEST)

    def test_equal_lists_pass(self):
        self.manifest()
        self.repo.write("android/SECURITY.md", self.SECURITY)
        self.assertEqual([], self.findings(rules={"manifest"}))

    def test_an_unlisted_permission_and_a_missing_component_are_found(self):
        self.repo.write("android/app/build/intermediates/merged_manifest/release/x/AndroidManifest.xml",
                        self.MANIFEST.replace("POST_NOTIFICATIONS", "READ_CONTACTS"))
        self.repo.write("android/SECURITY.md", self.SECURITY)
        messages = [f.message for f in self.findings(rules={"manifest"})]
        self.assertEqual(2, len(messages), messages)

    def test_a_missing_table_and_a_missing_manifest(self):
        self.manifest()
        self.repo.write("android/SECURITY.md", "# Security\n")
        self.assertRule("manifest", rules={"manifest"})
        self.repo = TempRepo()
        self.addCleanup(self.repo.cleanup)
        self.assertEqual([], self.findings(rules={"manifest"}))
        self.assertRule("manifest", rules={"manifest"}, require_manifest=True)


class Modes(RepoTestCase):
    def test_staged_mode_checks_the_index_not_the_working_tree(self):
        target = "android/docs/notes.md"
        self.repo.write(target, f"at {planted('private-ipv4')}\n")
        self.repo.git("add", target)
        self.repo.write(target, "clean now\n")
        self.assertRule("address", target, mode="staged")
        self.repo.git("add", target)
        self.repo.write(target, f"at {planted('private-ipv4')}\n")
        self.assertEqual([], [f for f in self.findings(mode="staged") if f.rule == "address"])

    def test_range_mode_sees_a_value_removed_by_a_later_commit(self):
        base = self.repo.commit("Base")
        self.repo.write("android/a.md", f"mac {planted('mac')}\n")
        self.repo.commit("Add")
        self.repo.write("android/a.md", "fixed\n")
        self.repo.commit("Remove")
        self.assertRule("mac", "android/a.md", mode="range", range_spec=f"{base}..HEAD")

    def test_commit_messages_are_checked_in_a_range(self):
        base = self.repo.commit("Base")
        self.repo.git("commit", "-q", "--allow-empty", "-m", f"Use {planted('token')}")
        self.assertRule("credential", "message", mode="range", range_spec=f"{base}..HEAD")

    def test_pre_push_of_a_new_branch_checks_commits_no_remote_has(self):
        self.repo.commit("Base")
        self.repo.write("android/b.md", f"mac {planted('mac')}\n")
        head = self.repo.commit("Add")
        stdin = f"refs/heads/main {head} refs/heads/main {'0' * 40}\n"
        self.assertRule("mac", "android/b.md", mode="pre-push", pre_push_stdin=stdin)
        deleted = f"(delete) {'0' * 40} refs/heads/old {head}\n"
        self.assertEqual([], self.findings(mode="pre-push", pre_push_stdin=deleted))


class CommandLine(RepoTestCase):
    def main(self, *argv: str, environ=None) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = check_repo.main(["--repo", str(self.repo.path), *argv], environ=environ or {})
        return code, out.getvalue() + err.getvalue()

    def test_exit_codes_and_no_secret_in_the_output(self):
        self.assertEqual(0, self.main("--no-denylist")[0])
        token = planted("token")
        self.repo.write("android/a.md", f"token {token}\n")
        code, output = self.main("--no-denylist")
        self.assertEqual(1, code)
        self.assertIn("credential", output)
        self.assertNotIn(token, output)

    def test_unknown_rule_is_a_usage_error(self):
        with self.assertRaises(SystemExit):
            self.main("--rules", "nonsense")

    def test_the_denylist_is_skipped_in_ci(self):
        with mock.patch.object(sensitive, "local_denylist") as builder:
            self.main(environ={"GITHUB_ACTIONS": "true"})
            builder.assert_not_called()

    def test_list_rules(self):
        code, output = self.main("--list-rules")
        self.assertEqual(0, code)
        for rule in check_repo.RULES:
            self.assertIn(rule, output)


if __name__ == "__main__":
    unittest.main()
