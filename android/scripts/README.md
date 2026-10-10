# Android scripts

Python 3 standard library only (`shot.py` makes its small copy with Pillow when it is installed). Run them from
`android/`; `python3 -m unittest discover -s scripts` runs their tests (CI does too).

## Repository hygiene (`hygiene/`, architecture 20.7)

| Script | Purpose |
|---|---|
| `check_repo.py` | The public-repository check: forbidden directory names and ignored sources (the root `.gitignore` trap), the ignore rules, language folders and a non-English scan, addresses, MACs, home paths, keys, keystores, credentials, `fallbackToDestructiveMigration`, `android.util.Log`, OkHttp construction, loopback address policies, the merged release manifest against `SECURITY.md`, this computer's identifiers (the local denylist) and post links in `docs/qa-report.md`. `--list-rules` lists them; `--staged`, `--pre-push` and `--range A..B` check what a commit or a push adds. |
| `install-hooks.sh` | Installs the `pre-commit` (staged files) and `pre-push` (every pushed commit) hooks that run `check_repo.py`. |
| `check_ownership.py` | Checks a work package's diff against the ownership table of `docs/work-plan.md` 3.2: `--wp WP-nn --base <baseline>`; `--self-check` validates the table. |
| `sensitive.py`, `language_words.py` | The shapes, the denylist, the redaction and the vocabulary the two checks and `qa_log.py` share. |

The local denylist (host name, user name, home directory, git e-mail, the serials of a running adb server, the host's
addresses and MACs) is computed at each run and never written anywhere; findings name the kind of value, never the
value. CI skips it (the runner's identity is not personal). Two line markers exist: `hygiene: test-vector` (also as a
`begin`/`end` pair) exempts test vectors from the address, MAC, home-path and credential rules, and
`hygiene: phonetic-example` exempts a line from the language scan. Neither exempts the denylist, private keys or
keystores.

`SECURITY.md` lists the merged release manifest in a table under a heading that contains "Merged release manifest",
with the columns `Kind` (`permission`, `activity`, `activity-alias`, `service`, `receiver`, `provider`) and `Name`
(fully qualified): every requested permission and every exported component, nothing else.

## Device QA (`device-qa/`, architecture 21.7)

`adb` and the `android` CLI on `PATH`; `ANDROID_SERIAL` selects the device (never commit a serial). Evidence and logs
go to `android/artifacts/qa-evidence/`, which git ignores.

| Script | Purpose |
|---|---|
| `_device.py` | The package, the launcher label and the adb wrappers in one place; `python3 _device.py` checks the app is installed. |
| `ui.py`, `shot.py`, `a11y.py` | Element list and taps by test tag or text; screenshots; unlabelled clickables and targets under 48 dp. |
| `db.py` | Debug build on an emulator only: the `request`, `request_op`, `clip` and `artifact` tables (never the pairing store). |
| `settings_guard.py` | Font scale, animator scales and TalkBack only: records, changes and restores exactly, and logs the restore table. |
| `qa_log.py` | The QA log; every line is redacted (credentials, ids, addresses, MACs, serials, paths, the denylist, post links). |
| `decoders.py` | The device's video decoders (size, rates, hardware or software) and whether the YouTube and X apps are installed. |
| `torture.py` | The [emulator] steps of the queue torture test and the platform stop paths; it refuses any device but an emulator. It compares the queue before and after each step: a step passes only with no unexpected error state and with the state changes it declares (process-kill: a shared copy `PREPARING` to `COPY_FAILED` `copy_interrupted`, QT-13). |
| `capture.py` | Starts the emulator with `-tcpdump` and checks every connection went to the computer. |
| `gmd_lock.py` | Runs a Gradle Managed Device task under the one lock of this computer: `gmd_lock.py -- ./gradlew …`. |
| `host_computer.py` | The host-side fake computer (server-contract 12) every emulator step uses: this checkout's server with synthetic analyses, a downloader that refuses every link and a resolver that never asks DNS, on a throwaway directory deleted at exit, with one seeded synthetic clip. It prints its address, the seeded clip and a pairing code to stdout for the harness (`pair`, `status` and `quit` on stdin); `--lan` serves the test phone on the approved Wi-Fi address; `--self-check` runs a phone session against a fresh one. |
| `computer.py` | The real computer for the real-computer QA pass only, on throwaway directories; local files only, never a link. |

`host_computer.py` and `computer.py` need the repository's virtual environment; started with another interpreter,
`host_computer.py` finds it (`--python`, `PHONOTRAINER_PYTHON`, `.venv` here or in the main checkout) and runs again
with it. Its tests that start a server are skipped where no such interpreter exists (CI).
