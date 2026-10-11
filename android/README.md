# PhonoTrainer for Android

The Android client of PhonoTrainer: your analysed clips on the phone, studied as a feed of short vertical clips with a
home dashboard, lessons, practice and Learn pages, and new work (a shared YouTube or X link, a video or audio file)
sent to the PhonoTrainer server on your own computer when the phone is on your home Wi-Fi, or kept on the phone until
it is. Everything stays between the phone and your computer: no account, no cloud, no analytics.

This directory is an independent Gradle build inside the PhonoTrainer repository (GPL-3.0-or-later, like the rest of
it). The server side it talks to is the `phonotrainer` package at the repository root (its phone access is described
in the root `README.md`, "Phone access (Android client)").

| Document | What it holds |
|---|---|
| [`docs/product-spec.md`](docs/product-spec.md) | what the app does: features, acceptance criteria, non-functional requirements |
| [`docs/design.md`](docs/design.md) | the visual design system, screens and assets |
| [`docs/architecture.md`](docs/architecture.md) | modules, layers, persistence, networking, sync, testing, security |
| [`docs/server-contract.md`](docs/server-contract.md) | the HTTP and discovery contract with the computer |
| [`docs/work-plan.md`](docs/work-plan.md) | work packages, ownership of every file, the definition of done |
| [`SECURITY.md`](SECURITY.md) | the security posture, accepted risks and the release manifest's exported components |
| [`AGENTS.md`](AGENTS.md) | the rules every contributor and agent follows here |

## Requirements

- JDK 17 or newer to run Gradle (the build compiles with a JDK 17 toolchain and provisions one if it is missing).
- The Android SDK with `ANDROID_HOME` set (or `sdk.dir` in an ignored `local.properties`): platform 37, build tools,
  and, for the managed-device tests, the emulator and the `aosp_atd` API 36 x86_64 system image (Gradle downloads it
  on the first run).
- Python 3 for `scripts/` (standard library; Pillow, if installed, for device screenshots), the repository's
  virtual environment for `tools/reference/`, and Node.js 22 for the didactic-content drift check.

Android Studio opens this directory (`android/`), not the repository root. Phone and Android versions supported:
Android 10 (API 29) and later; the app targets API 36.

## Build and test

Every Gradle command runs from `android/`:

| Command | What it does |
|---|---|
| `./gradlew check` | unit and Robolectric tests of every module, lint, the licence check (`verifyLicences`) and its own tests |
| `./gradlew lintDebug` | lint with warnings as errors and no baseline (`config/lint/lint.xml` lists the few ignored checks and why) |
| `./gradlew assembleDebug assembleRelease` | the debug APK and the R8-minified release APK |
| `./gradlew :core:content:verifyDidacticContent` | the bundled didactic content still matches the web client's sources |
| `./gradlew pixel9Api36DebugAndroidTest` | the end-to-end suite of `:app` on a headless managed emulator (no phone needed) |
| `./gradlew :qasmoke:pixel9Api36QaAndroidTest` | the black-box smoke of the release-like `qa` APK on the managed emulator |

From the repository root, `make android-check` runs the checks a change must pass before it is merged (`check`,
`lintDebug`, both APKs, the content drift check once `:core:content` exists, and the repository hygiene check).

Filter the end-to-end suite to one area with
`-Pandroid.testInstrumentationRunnerArguments.package=com.mapineda48.phonotrainer.e2e.<area>`. Without `--rerun`,
Gradle skips test tasks whose inputs did not change. On a computer where several copies of the project build at once,
run managed-device tasks one at a time under the lock: `python3 scripts/device-qa/gmd_lock.py -- ./gradlew …`.

## Build types

| Build type | Minified | Debuggable | Signed with | Installed on |
|---|---|---|---|---|
| `debug` | no | yes | the per-machine debug key | emulators |
| `qa` | yes (R8 and resource shrinking, as release) | no | the per-machine debug key | emulators (the `:qasmoke` and `:baselineprofile` targets) |
| `release` | yes | no | the release key, when `keystore.properties` exists; otherwise unsigned | the test phone |

## Release signing

Release builds are signed with one stable key that never enters the repository. Create the key outside the repository
(for example with `keytool -genkeypair -keyalg RSA -keysize 4096 -validity 10000`), then create `android/keystore.properties`,
which `android/.gitignore` ignores, with four entries:

```properties
storeFile=<path of the keystore file, absolute or relative to android/>
storePassword=<the keystore's password>
keyAlias=<the key's alias>
keyPassword=<the key's password>
```

`./gradlew assembleRelease` then signs the release APK; without the file the release APK is built unsigned. To use a
properties file kept elsewhere, pass `-Pphonotrainer.keystoreProperties=<path>`. Never commit the keystore, the
properties file or a password; the hygiene check and `android/.gitignore` refuse them. Only release-signed builds are
installed on the test phone, so a later release can always update it in place; `versionCode` only ever increases.

## Tests at a glance

- **Unit tests** (`src/test`) run on the JVM; Android modules use Robolectric (sdk 35). Fakes of every domain and
  network interface live in `:core:testing-jvm`, Android test helpers (the accessibility audit, Compose rules) in
  `:core:testing`.
- **End-to-end tests** (`app/src/androidTest/.../e2e/`) run the real app on the managed emulator with real
  repositories over one global set of test seams (`e2e/di/`): a fake PhonoTrainer computer per test (`FakeComputer`,
  reached over the loopback network and by a fake discovery), a scripted network, in-memory databases, a clock tests
  move (`e2e/support/TestTime`), and a test WorkManager whose work runs when `e2e/support/WorkDriver` releases it.
  `e2e/support/TestSeeder` seeds the fake computer and brings the clips to the phone through the real stack. Each area
  has its own robot under `e2e/<area>/`, behind the façade `e2e/PhonoRobot`.
- **`:qasmoke`** drives the unmodified `qa` APK from outside with UiAutomator: no test code runs inside the minified
  app, so it proves the R8 rules the way a release would exercise them.
- **Device QA** (`scripts/device-qa/`) drives an emulator or the test phone with `adb` for what automated tests cannot
  do; it touches only this app and restores every setting it changes.

## Licences

The app redistributes the libraries compiled into it and its bundled fonts and icons; `THIRD-PARTY-NOTICES.md`
section 7 lists every one, and `./gradlew check` fails when the list and the build disagree (`verifyLicences`). When a
dependency changes, the failure names each library to add or remove and gives the row to paste into section 7.2. A
library with a licence outside Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause and OFL-1.1 fails the build. Every new
dependency is a deliberate change of `gradle/libs.versions.toml`.

## Repository hygiene

The repository is public. `python3 scripts/hygiene/check_repo.py` (from `android/`) refuses addresses, serials, tokens,
keys, home paths and personal data, ignored source files, and non-English text, and compares the release manifest
with `SECURITY.md`; `python3 scripts/hygiene/check_ownership.py` checks a change against the file ownership of the
work plan. See `scripts/README.md`.
