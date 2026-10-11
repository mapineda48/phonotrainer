# PhonoTrainer for Android: integration decisions

Decisions the integrator (WP-19, then WP-26) takes where the planning documents leave a choice open, and the
measurements the work plan asks to record (NFR-02 with and without the Baseline Profile, NFR-03, the 16 KB alignment).
Newest first within each section. The planning documents stay authoritative; an entry here never changes behaviour
they fix.

## Build and test infrastructure

### I-09 Two lint analyses at a time inside the Gradle daemon (2026-10-11, WP-19)

Lint runs in the Gradle daemon (AGP's default), sharing the 3 GB heap of architecture 2.4 with R8, D8, KSP and javac.
AGP admits `max(1, floor(0.75 × heap / reservedMemoryPerTask))` lint analyses at once, which is four with the default
512 MB. From-scratch runs of `check lintDebug assembleDebug assembleRelease --rerun-tasks` began to fail on lint's own
Kotlin (K2) resolution: "Unexpected failure during lint analysis … Error while resolving FirRegularClassImpl from
RAW_FIR to COMPILER_REQUIRED_ANNOTATIONS" (or `SUPER_TYPES`), each time on a different, unchanged test file
(`AndroidBundledAssetsTest.kt`, `DependencyShapeTest.kt`, `HiltTestRunner.kt`). The task passes when it runs alone.
The failures grew with the tree: 2 of 3 runs at w1-wp19a, 0 of 1 at w1-wp02m3, and 3 of 3 once WP-03 was merged. A GC
log of a failing run shows the daemon's heap full, with G1 Full (compaction) pauses from 3,071 MB. A full collection
clears soft references, and the analysis keeps its resolved declarations behind soft references. That fits a
declaration found back at `RAW_FIR` in the middle of a lazy resolution, although lint hides the inner cause.
`android.experimental.lint.reservedMemoryPerTask=1g` in `gradle.properties` admits two analyses at a time. The heap
settings and the bound of about 6.5 GB per copy do not change, because the daemon's peak goes down, not up. With it,
2 of 2 from-scratch runs passed, each about half a minute longer. Running lint out of process was rejected: each
worker would add its own heap, outside the per-copy budget that lets four copies build at once. If the crash returns
as the tree grows, the next step is `1536m` (one analysis at a time), measured the same way.

### I-08 The end-to-end address policy is the release one plus `127.0.0.1` (2026-10-11, WP-19)

Architecture 21.5 asks `TestNetworkModule` for "an `AddressPolicy` that also allows loopback". A `@TestInstallIn`
module cannot be uninstalled per test, so whatever it accepts is what every end-to-end test of every area runs under,
including the cleartext-confinement proof (X-AC6). The test policy therefore does not restate the rules of
architecture 5.2: it calls `NetworkPolicyModule.addressPolicy()` (the release `:core:network` binding) and admits one
more host, the literal `127.0.0.1` the in-process fake computer listens on. Private and link-local ranges behave
exactly as in a release (10/8 with the emulator's 10.0.2.2, 172.16/12, 192.168/16, 169.254/16), and a host name never
passes, however it begins. `GlobalTestSetTest.theAddressPolicyIsTheReleaseOnePlusLoopback` pins both the vectors and
the agreement with the release policy. This relies on `NetworkPolicyModule.addressPolicy()` staying a public,
parameterless function; if `:core:network` changes that shape, the `:app` test sources stop compiling at the merge,
and the integrator moves the delegation to the new form rather than copying the rules.

### I-07 The app holds okio at the version OkHttp needs (2026-10-11, WP-19)

The end-to-end fake computer is OkHttp's MockWebServer running inside the app's process, and the app's runtime
classpath decides the version of every library the test APK shares with it. At B1 the app's only okio comes from
DataStore (3.9.1), while OkHttp 5.5.0 needs 3.18.1 (`okio.Socket`): the first test that served a request crashed the
whole instrumentation run with `NoClassDefFoundError`. `:app` now carries a dependency constraint on
`com.squareup.okio:okio` 3.18.1 (catalog `okio`, kept equal to what the `okhttp` version asks for). It adds no library
and is what the release classpath becomes anyway once `:core:network` brings OkHttp (WP-05b); notices 7.2 lists okio
3.18.1. When a later OkHttp needs a newer okio, the same crash names the class, and the catalog's `okio` moves with it.

### I-06 Release signing reads `keystore.properties`, or the file a Gradle property names (2026-10-10, WP-19)

Architecture 20.5 fixes the ignored `android/keystore.properties`. The build-logic function `configureReleaseSigning`
reads it through a Gradle provider, so the configuration cache tracks its content and its absence, and signs only the
`release` build type; `qa` keeps the debug key whatever the file says. `-Pphonotrainer.keystoreProperties=<path>`
points at a file elsewhere, which lets a check sign a release with a throwaway key kept in a temporary directory,
writing nothing into the working tree. A file that lacks one of its four keys, or names a keystore that does not
exist, fails the configuration with a message that names neither path nor secret.

### I-05 The end-to-end computer's clock is frozen and moves with the phone's (2026-10-10, WP-19)

`FakeComputer` defaults to a running clock. In `:app`'s end-to-end tests the phone's clock is `TestClock`, which only
moves when a test moves it, so a running computer clock would drift away from the phone's and a long test would fall
out of the signature window for reasons unrelated to the code under test. `FakeComputerModule` therefore starts each
test's computer with a clock frozen at the `TestClock` instant, and `e2e/support/TestTime` moves both. Code expiry,
rate-limit windows and upload idleness pass exactly when a test says so (QT-4 advances time to make the computer
abandon a stalled upload).

### I-04 Every end-to-end test gets a fresh WorkManager and a Hilt-aware worker factory (2026-10-10, WP-19)

Architecture 21.5 initialises the test WorkManager in the runner, once per process; its work database would then carry
requests from one test to the next, and its workers would be created without the running test's Hilt bindings. The
runner adds a JUnit listener (`e2e/di/PerTestIsolation`) that installs a new test WorkManager before each test and
closes every fake computer after it, and the WorkManager configuration uses `e2e/di/TestWorkerFactory`, which looks up
the running test's `HiltWorkerFactory` through an entry point each time it creates a worker. `e2e/support/WorkDriver`
releases enqueued work (constraints, initial delay, period) on demand.

### I-03 `:qasmoke` instruments itself and only its `qa` variant exists (2026-10-10, WP-19)

The `qa` build type of `:app` is `release` (R8, resource shrinking, not debuggable) signed with the debug key, with
`matchingFallbacks = release` for the libraries. `phonotrainer.android.test` gives `com.android.test` modules a `qa`
build type that targets it, disables every other variant, and sets `android.experimental.self-instrumenting`, so the
test APK runs in its own process and reaches the minified app only through UiAutomator: no test class is ever loaded
into the R8 output (delivery review D-19, Android review M10). The test APK declares the app's package in `<queries>`.
Run: `./gradlew :qasmoke:pixel9Api36QaAndroidTest`.

### I-02 Notices 7.2 lists every artifact with its version, checked both ways (2026-10-10, WP-19)

`verifyLicences` keys 7.2 on Maven coordinates and compares the version and the licence too, so the notices always say
exactly what ships (154 artifacts at B1, all Apache-2.0 except DataStore's repackaged protocol buffers, BSD-3-Clause).
A version bump of a runtime library therefore changes 7.2 in the same merge; the check's failure gives the row to
paste. The 7.3 rows are matched by component name against `bundled.json`. `bundled.json` schema 1, as the check reads
it (WP-03 generates it):

```json
{
  "schema": 1,
  "components": [
    {"name": "Charis", "version": "7.000", "kind": "font", "licence": "OFL-1.1",
     "licenceFile": "charis-OFL.txt", "source": "https://…",
     "files": [{"name": "charis_regular.ttf", "sha256": "…"}]},
    {"name": "Lucide", "version": "1.51.0", "kind": "icons", "licence": "ISC AND MIT",
     "licenceFile": "lucide-LICENSE.txt", "source": "https://…", "count": 122}
  ]
}
```

The 7.3 table needs the columns Component, Version and Licence (others may follow, for example Kind, Files, Licence
file, Source). Until `bundled.json` exists, 7.3 must have no rows and `res/font/` no font file.

### I-01 The licence report is AboutLibraries 15.2.0, offline and strict (2026-10-10, WP-19)

Architecture 2.4 leaves the version to WP-19. 15.2.0 is the latest release on the Gradle Plugin Portal (2026-08-28;
Apache-2.0); it needs Gradle 8.8 or newer and works with AGP 9.4.1 and Gradle 9.8. Its Android plugin
(`com.mikepenz.aboutlibraries.plugin.android`) registers `prepareLibraryDefinitions<Variant>` for every variant and
adds the result to the variant's resources as `res/raw/aboutlibraries.json`. Configuration: `offlineMode`, no remote
licence or funding lookup, no platform (BOM) entries, `requireLicense`, `StrictMode.FAIL` with the allowlist of
architecture 20.6 (Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, OFL-1.1); no "AndroidX licence identifier" was needed,
because the plugin maps every AndroidX POM licence to `Apache-2.0`. Verified: the configuration cache stores and
reuses its entry with the plugin applied (no fallback task was needed), and the build fails when a licence of the
classpath is removed from the allowlist. Known limit: in offline mode the report carries licence names and URLs, not
their texts (the plugin warns "does not contain the license text"); the Licences screen (WP-24) needs the texts, which
the plugin reads from a `configPath` directory of licence definitions, to be added with WP-24.
