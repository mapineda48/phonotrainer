# AGENTS.md

Mandatory instructions for every AI agent (and human) working in `android/`. Read this file and the repository's
root `CLAUDE.md` before making any change. Where an instruction here conflicts with a default habit, this file wins.

## 1. Language (mandatory)

**Everything committed is in English**: code, identifiers, comments, KDoc, strings shown to the learner, test names
and data, documentation, file names and commit messages. There is **no translated resource folder** (`values-es` and
any other language qualifier are refused by the hygiene check); qualifiers that are not languages (`values-night`)
are fine. IPA symbols and English phonetic examples are content, not a language violation. Conversations with the
owner may happen in another language; that never changes what is committed.

## 2. The repository is public

Never commit an IP or MAC address of a real network, a device serial, a token, a key, a pairing code, a host name, a
home path or any personal data; never commit a keystore, `keystore.properties`, `local.properties` or files that device
debugging produces. Test data uses documentation ranges (`192.0.2.0/24`, `00:00:5e:00:53:xx`) and loopback. Before
every commit, from `android/`: `python3 scripts/hygiene/check_repo.py --staged` (the `pre-commit` hook runs it when
installed with `scripts/hygiene/install-hooks.sh`). Local evidence (screenshots, logs) goes to `android/artifacts/`,
which git ignores.

## 3. Where you may write

- Never touch `web/` (the web client belongs to another agent; reading it is fine) and never the Python server unless
  your work package owns the file.
- Every file has one owner (`docs/work-plan.md` section 3). A work package changes only its own files; anything else
  (a frozen contract type, a catalog entry, a Room entity, a navigation key, another module's string, a fake) goes to
  the integrator as a **change request** in the hand-off: the file, the exact additive change, and why
  (work-plan 2.2). Check a branch with `python3 scripts/hygiene/check_ownership.py --wp WP-nn --base <baseline>`.
- A bug in another work package's files is reported to the integrator with a failing test, never fixed in place.
- No package or resource directory may be named `data`, `out`, `out_*`, `downloads` or `workspace`: the root
  `.gitignore` would silently drop it (architecture 2.6). Use `store`, `local`, `transfer`, `export`.

## 4. Build and test

All Gradle commands run from `android/` (see `README.md` for the full list).

| Command | When |
| --- | --- |
| `./gradlew :<module>:testDebugUnitTest` (`:<module>:test` for JVM modules) | after every change to the module |
| `./gradlew check` and `./gradlew lintDebug` | before handing off; lint has no baseline and warnings fail it |
| `./gradlew assembleDebug`; `./gradlew assembleRelease` | release when dependencies, R8 rules, serialization or reflection changed |
| `./gradlew pixel9Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.mapineda48.phonotrainer.e2e.<area>` | when UI, navigation, persistence, DI, media or work scheduling changed |
| `./gradlew :core:content:verifyDidacticContent` | when `:core:content` or `tools/didactic` changed |
| `./gradlew :qasmoke:pixel9Api36QaAndroidTest` | the integrator, at merges: the release-like black-box smoke |

A change is done only when the definition of done of `docs/work-plan.md` 2.5 holds. Python tests of the server run from
the repository root with the repository's virtual environment (`.venv/bin/python -m pytest -q`).

**Shared computer.** Several copies of the project may build at once: keep the heap settings of `gradle.properties`,
run one managed-device (GMD) task at a time under the lock (`python3 scripts/device-qa/gmd_lock.py -- ./gradlew …`),
and stop your own Gradle daemons when you finish (`./gradlew --stop`, or a private
`-Dorg.gradle.daemon.registry.base=<dir>` so another copy's `--stop` cannot kill your build).

## 5. Tests

- **Pyramid** (architecture 21): pure logic in JVM modules; Android units and every screen state as Robolectric tests
  in the owning module; core instrumented tests only in `:core:player`, `:core:requests` and `:core:sync`; end-to-end
  tests in `:app`; the black-box `qa` smoke in `:qasmoke`.
- **End-to-end tests** use the one global set of test seams in `app/src/androidTest/.../e2e/di/` (integrator-owned):
  repositories stay real. Do not add a `@TestInstallIn` module; a test that truly needs a fake repository uses a
  per-class `@UninstallModules` plus `@BindValue` and says why. Seed through `e2e/support/TestSeeder`, move time with
  `TestTime`, release scheduled work with `WorkDriver`, and inject `FakeComputer` to turn its failure knobs.
- Each area has a robot in `e2e/<area>/`; find nodes by test tag or string resource, never by hard-coded text; every
  action returns after its effect is visible; wait with timeouts, never `Thread.sleep`.
- Every bug fix gets a regression test that fails without the fix.
- New interactive elements get a test tag (`<feature>.<screen>.<element>`, exposed as resource ids), an accessible
  label and 48 dp targets. Existing tags and content descriptions are part of the test contract: keep them.

## 6. Code conventions

- Kotlin, Jetpack Compose and Material 3, Navigation 3, ViewModels exposing immutable state, Hilt, Room, DataStore,
  WorkManager (architecture 3). Features depend only on `:core:domain` and the shared UI modules, never on another
  feature or on a core implementation.
- Versions live only in `gradle/libs.versions.toml`; modules apply the convention plugins of `build-logic/` and list
  their dependencies, nothing else. A new dependency is a change request with its licence, compatible with
  GPL-3.0-or-later, and is recorded in `THIRD-PARTY-NOTICES.md` section 7 (the licence check enforces it).
- Strings in the module's own `res/values/strings.xml` (or the owner's `strings_<name>.xml`); colours, type and sizes
  from the design system's tokens, never hard-coded in screens.
- Logging only through the `PhonoLog` facade, never `android.util.Log`; HTTP only through `:core:network`'s client.
- Two-space indentation in Kotlin and Gradle scripts (`.editorconfig`).

## 7. Devices

- The owner's phone is used only by the QA work package, with the owner's consent; every other test runs on the
  managed emulator. Never run `adb` against a physical device otherwise.
- Device QA interacts only with this app, never sends a link to the real computer (the host-side fake computer
  refuses every download), and restores every setting it changes (`scripts/device-qa/settings_guard.py`).
- Only release-signed builds are installed on the test phone; debug and `qa` builds stay on emulators.
