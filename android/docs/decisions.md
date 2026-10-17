# PhonoTrainer for Android: integration decisions

Decisions the integrator (WP-19, then WP-26) takes where the planning documents leave a choice open, and the
measurements the work plan asks to record (NFR-02 with and without the Baseline Profile, NFR-03, the 16 KB alignment).
Newest first within each section. The planning documents stay authoritative; an entry here never changes behaviour
they fix.

## App shell

### I-12 The shell draws WP-03's frame and leaves the top inset to each destination (2026-10-11, WP-19)

The wave-1 review (architecture finding 1, completeness WP-03 item 4; WP-03's CR-3) found the shell on Material's
`Scaffold`, `NavigationBar` and `SnackbarHost`, and `Scaffold`'s content padding put every destination below the status
bar, against architecture 16.5 ("every screen … consume system-bar insets themselves") and design 10 (status bar edge
to edge, the feed one card per screen height minus the bottom bar). `PhonoShell` now composes a stateless
`ShellFrame`: the `page` colour with `paperGrain()`, the "Connect again" banner as a caution `Notice` with its action,
the destinations, one `PhonoSnackbar` for `UserMessages.current` (10 s unless TalkBack is on, then it waits; "Dismiss"
reports it done), the announcer's live region and `PhonoBottomBar` on the bottom edge.

The inset contract, which every wave-2 destination follows:
- the shell never pads a destination with the status-bar inset: a destination applies `statusBarsPadding()` (or draws
  its top bar or title behind the status bar with that padding), and the feed is full bleed;
- the bottom bar's sheet continues behind the navigation bar (the gesture area) and its items keep clear of the system
  bars and cutouts (`safeDrawing`, horizontal and bottom); the destinations see the bar's measured height, navigation
  bar included, as consumed at the bottom (`consumeWindowInsets`), so their `navigationBarsPadding()` is zero and
  their `imePadding()` counts from the top of the bar, as `Scaffold` content does;
- while the banner shows, it pads the status bar itself and its height is consumed at the top the same way;
- horizontal insets are each destination's (landscape navigation bar, cutouts), except the bar's and the snackbar's.

Two of CR-3's names have no use in the shell: `PhonoTopBar` belongs to the secondary destinations (design 9.24) and
`PhonoDialog` to the feature that asks (design 9.23); the shell draws neither a top bar nor a dialog in v1-core (the
CR-01-A2 messages are snackbars). `PhonoSnackbar` has no modifier for its action, so the "Dismiss" button has no test
tag of its own; tests find it by its string. The bar is not hidden under a full-height sheet: the lesson and phrase
sheets are `ModalBottomSheet` windows, which cover it.

Proof: `e2e/smoke/ShellFrameTest` on the managed device. Its image (aosp-atd) draws no status bar, so the test hands
the window content the insets of a phone with a 32 dp status bar and a 24 dp navigation bar; on the old shell Home's
top was 32 dp (red), on the new one every destination starts at 0 and the bar sits on the bottom edge with its items
above the navigation bar. `ShellFrameScreenTest` (Robolectric, on the harness of I-13) checks the frame at font scale
2.0 light and dark and the colour-vision palette with the accessibility audit, and the snackbar's 10 s and TalkBack
behaviour. `NavigationSmokeTest`, `PerTabStateTest` and `NotificationTapTest` are unchanged and green. Until the
features replace their stubs, `DestinationStub` (WP-03's) starts under the status bar; WP-03 or the features add
`statusBarsPadding()`.

## Data and the frozen contract

### I-17 Schema 1 states its NOT NULL columns and defaults (2026-10-11, WP-19)

Wave-1 review, architecture finding 3: WP-02 made a column NOT NULL only where architecture 4.2 says so, so
`saved_item`'s `UNIQUE(kind, job_id, segment, word)` let a short be saved twice (SQLite treats NULLs in a UNIQUE key as
distinct) and every counter started NULL, so `x = x + 1` stayed NULL. Folded into version 1 (architecture 4.4: no
build is installed before WP-25), by one rule for both databases:
- kinds, states and the times a row is written with are NOT NULL: `request.kind`, `state`, `created_at`, `updated_at`;
  `request_op.kind`, `state`, `created_at`; `artifact.role`, `remote_status`, `local_state`, `updated_at`;
  `clip.first_seen_at`; `saved_item.kind`, `job_id`, `segment`, `saved_at`; `short_seen.first_seen_at`,
  `last_seen_at`; `feed_position.mode_json`, `updated_at`; `change_met.first_met_at`; `study_set.kind`, `local_date`,
  `seed`, `rule_version`, `created_at`; `study_set_item.kind`; `practice_session.seed`, `questions_json`,
  `created_at`;
- counters and 0/1 flags are NOT NULL DEFAULT 0 (`practice_stat.seen`, `correct`, `short_seen.seen_count`,
  `request.attempts`, `rerun_count`, `artifact.verify_failures`, `request_op.attempts`, `study_set.cursor`,
  `answered`, `right`, `practice_session.index`, and every flag of `clip`, `request`, `clip_progress`,
  `study_set_item` and `practice_session`); `artifact.local_state` defaults to `ABSENT` and `request_op.state` to
  `PENDING`;
- `saved_item.word` is NOT NULL DEFAULT -1, `SavedItemEntity.NO_WORD`, for a SHORT row (whose `segment` is the
  short's first segment), the "-" of server-contract 3.2's key;
- nullable stay: times of events that may not have happened (`sent_at`, `finished_at`, `completed_at`,
  `last_watched_at`…), what the computer reports (an older listing may leave it out), tri-states such as
  `study_set_item.correct` (not answered yet) and `request_op.audio_only` (RERUN only), and optional data.

The Kotlin entities match (non-null types; defaults equal to the SQL defaults). Schema 1 is regenerated (library
`5ee6c996…`, learning `a3a736eb…`). `SchemaConstraintsTest` (`:core:database`, plain SQL on the production builders)
failed 4 of 4 on the old schema (two SHORT rows, NULL counters, nothing refused) and passes; `SchemaV1Test`,
`LearningIdTest` and the device tests are green. No DAO existed yet beyond `count()`, so no query changed.

### I-16 One message-id allocator, declared SPI sets, and no double navigation (2026-10-11, WP-19)

Wave-1 review, architecture findings 10, 12 and 15 (first item):
- **Message ids.** `UserMessage.nextId()` (a process-wide counter in `:core:domain`) is the one allocator, and
  `UserMessages.post(kind, args): Long` (a default method, additive) posts under a fresh id and returns it; the
  shell's resolver uses it. `ShellUserMessages.post(message)` gives a message whose id is already current or waiting a
  fresh one, so consuming one message never consumes or hides another; the snackbar's timer is keyed on the id.
- **SPI sets.** `SpiSetsModule` (`:app`, next to the other app bindings) declares `Set<StartupTask>` and
  `Set<ClipRemovalHook>` with `@Multibinds`, so `StartupCheck` and the clip removal (WP-08) can inject them whether or
  not a core contributes yet; contributors only add `@Binds @IntoSet`. Without it the androidTest graph failed with
  `[Dagger/MissingBinding]` for both sets.
- **Navigation.** `AppNavigator.navigate(key)` does nothing when `key` is already on top of the current stack, and a
  started feed or a focused Practice that is already its tab's only entry is shown, not replaced: an equal key shares
  the entry's content key, so a second push stacked a duplicate over one `ViewModelStore`, and a replace cleared the
  shown entry's state with the entry it removed.
Proof: `ShellUserMessagesTest` and `AppNavigatorTest` (red 3 before the change), `GlobalTestSetTest.theSpiSetsAre…`.

### I-14 A word target whose clip is gone has its own message (2026-10-11, WP-19)

Spec 5.4 gives two notices: a clip target ("Ready to watch", "New on this phone") falls back to Home with "That clip
is no longer on this phone.", a word target inside the app to the Library with "That clip is no longer on your computer
or this phone." Both posted `CLIP_GONE`. `UserMessageKind` gains `WORD_CLIP_GONE` (appended; the shell's message host
is its one exhaustive consumer, architecture C-35), with the second sentence in `:app`'s strings, and
`TargetResolver` posts it for `NavTarget.WordLesson`. Design 9.23's list of one-shot messages and Appendix A.9 name only
the first; forwarded to DESIGN and ARCHITECT. WP-17 and WP-20 test their word-target fallbacks against it.

### I-11 Deviations of the contract freeze that the planning documents do not record (2026-10-11, WP-19)

Wave-1 review, architecture finding 17 and completeness WP-02 item 5 (WP-02 M2 hand-off deviations 3 to 5). These are
part of the frozen contract as merged; forwarded to ARCHITECT to fold into architecture 2.3, 4.1 and 21.5:
- `DataStoreModule` (`:core:datastore`) and `DaoModule` (`:core:database`) are frozen names like the four of work-plan
  4.1: `:app`'s end-to-end set replaces `DataStoreModule` with `TestDataStoreModule` (per-test preference files,
  because DataStore refuses two live instances on one file in a process), a **sixth** global test module beside the
  five of architecture 21.5, and `DaoModule` provides the DAOs from whichever databases `DatabaseModule` or
  `TestDatabaseModule` binds;
- `config/lint/lint.xml` ignores `InsecureBaseConfiguration` for `src/main/res/xml/network_security_config.xml` only:
  the computer's address is known only at run time, so the base configuration permits cleartext and
  `:core:network`'s guard confines it (architecture 5.2; `SECURITY.md`);
- `RequestFailureCopy.of`, `RequestFailureCopy.notificationLine` and `ProbeOutcomeCopy.of` take an Android `Resources`
  as their last parameter (work-plan 4.5 writes them without it): the copy lives in `strings_request.xml` and
  `strings_connection.xml` (work-plan 3.2), which a plain `object` cannot read without `Resources`;
- `SettingsRepository.awaitLoaded()` (a default method): the splash stays until the stored settings are read,
  because `settings` starts at the defaults (architecture 16.5).

### I-10 The `learning_id` callback overrides Room's connection callbacks (2026-10-11, WP-19)

Work-plan 4.7 (CR-01-A1) fixes what `LearningDatabase.LEARNING_ID_CALLBACK` does: a plain insert of
(`learning_id`, a random UUID) in `onCreate`, the same insert with `INSERT OR IGNORE` in `onOpen`. Room 2.8 declares
each callback twice, once with a `SupportSQLiteDatabase` and once with an `androidx.sqlite.SQLiteConnection`, and its
connection manager calls only the second; without a driver it passes a connection that wraps the support database,
and the default body forwards to the first. The callback overrides the connection forms, with a prepared statement
and bound values, so it runs the same way if the databases ever move to a driver. `onOpen` alone would already name a
new database; `onCreate` is kept as the plan writes it, and the open after it then changes nothing.
`LearningIdTest` (`:core:database`, Robolectric) proves one id that survives reopening, a version 1 file without the
row gaining one on open, and a new id for every new database; replacing `INSERT OR IGNORE` by `INSERT OR REPLACE`, or
dropping `onOpen`, makes it fail. `LearningIdOnDeviceTest` repeats the first case on the managed device, and
`GlobalTestSetTest.theInMemoryLearningDatabaseHasItsLearningId` covers the end-to-end set's in-memory builder.

## Media

### I-18 The feed keeps its pool of three players; DefaultPreloadManager is the measured fallback (2026-10-17, WP-11)

Architecture 11.2 asked WP-11 to evaluate Media3's `DefaultPreloadManager` as a drop-in for the pool's preparation and
to record decoders and memory while swiping. On the managed device (Pixel 9 API 36 ATD, software decoders), 50 swipes
over two-second shorts of a 320×240 H.264 clip, swipe to sound measured from `settle` to the platform's playout start:
pool of three p50 86 / p90 91 / max 96 ms with 3 video decoders, PSS 110 → 118 MB; one player with
`DefaultPreloadManager` (neighbours loaded 1 s ahead) p50 124 / p90 139 ms with 1 decoder; 1280×720 on the pool p90
93 ms, 3 decoders, PSS 112 → 118 MB. Both meet SC-08; the pool keeps the neighbours' decoders warm and the first frame
ready, so it stays. A short loaded while already current (a jump) starts with its video off and its neighbours wait
for it (p90 163 ms 4.5 s from a key frame), and players buffer local files 0.1 s before starting. If WP-25's phone
measurements show codec or memory pressure (AV1 720p on the test phone), the fallback is the preload manager with one
player, behind the same `FeedPlayer` (architecture 11.2). Proof: `:core:player` `SwipeTimingTest`,
`PreloadManagerEvaluationTest` (device log tag `PhonoPlayerQA`).

## Build and test infrastructure

### I-15 `StartupOrder` is kept by an R8 rule, proved with AGP's own R8 (2026-10-11, WP-19)

Wave-1 review, architecture finding 8: `@StartupOrder` is read by reflection from each `StartupTask`'s class, the tasks
are reached only through Hilt's generated code, and in R8's full mode (AGP 9's default) an attribute such as
`RuntimeVisibleAnnotations` stays only on classes a keep rule matches. The annotation stays (no contract change);
`app/proguard-rules.pro` keeps it on every annotated class that survives shrinking
(`-keep,allowobfuscation,allowshrinking @…StartupOrder class *`, so the class may still be renamed or removed when
unused) and keeps the annotation type with its value.

`StartupOrderR8Test` (`:app` unit test) runs the R8 that builds the release (9.4.24, inside AGP's
`com.android.tools.build:builder` artifact, added to the catalog as `android-builder` and used non-transitively in
`testImplementation` only) in full mode, with the release build type's own configuration files (AGP's
`proguard-android-optimize.txt` and `proguard-rules.pro`, passed in by `:app`'s build file after
`extractProguardFiles`), over two fixture tasks reached the way Hilt reaches the real ones and the real `StartupOrder`
and `StartupTask` classes, then loads the shrunk classes and reads the orders. Without the app's rules both orders are
lost (`null,null`), which is the control; with them they are `20,10`. Removing the class rule makes it fail; the
`@interface` rule alone is not needed today and is kept as a guard. `:qasmoke` cannot see this until WP-08's
`StartupCheck` exists, because no class of the app carries the annotation before then.

### I-13 One app root and one screen-test harness (2026-10-11, WP-19)

Wave-1 review, architecture findings 5 and 6: the theme and the accessibility locals were set up only in
`MainActivity`, so the share sheet's `ShareActivity` got the system's dark mode, the Standard palette, no TalkBack and
no reduced motion, and `:core:testing` had none of the Robolectric and Compose rules work-plan 4.6 promised.
- `PhonoAppRoot(settings, ipaReading, reducedMotion, talkBackOn, content)` turns `UserSettings` into `PhonoTheme`
  (in-app appearance, palette, patterns) and provides `LocalReducedMotion`, `LocalTalkBackOn` and `LocalIpaReading`,
  with one reduced-motion value for both `LocalReducedMotion` and the theme's motion. `MainActivity` uses it; WP-23's
  `ShareActivity` wraps its sheet in it with the injected `SettingsRepository`'s settings (window effects stay with
  each activity). It lives in `:core:ui`'s `ui/extension/` package, the integrator's row in the one module that
  `:app`, the features and the test harness can all reach; forwarded to ARCHITECT, who may give it a row of its own.
- `:core:testing` gains `PhonoScreenRule` and `ScreenVariant`: the Compose `v2` rule (effects on a
  `StandardTestDispatcher`), content set inside `PhonoAppRoot`, variants switched on the content already set (font
  scale through `LocalDensity`, light or dark, Standard or colour-vision-friendly, TalkBack, reduced motion),
  `ScreenVariant.MATRIX` (design 11: 3 font scales × light and dark × both palettes) and `REQUIRED` (architecture
  21.1: font scale 2.0, light and dark), and `checkEach`, which runs the screen's checks and `A11yAudit` at every
  variant and names the variant in a failure. `PhonoScreenRuleTest` proves the locals per variant and the audit;
  with the harness drawing a plain `PhonoTheme` and no audit, 2 of its 3 tests fail. `A11yAuditTest` moved to the
  `v2` rule.
- To reach the root, `:core:testing` depends on `:core:ui`. Architecture 2.2 rule 3 lists the modules a testing module
  may use; `:core:ui` is not an implementation module and adds no cycle (a module's tests may see its own main code
  through `:core:testing`), so the dependency guard's list gains it. Forwarded to ARCHITECT for rule 3's text.
WP-03's own tests (`ThemeTest`, `ComponentsTest`, `PrimitivesTest`, `PlaceholderA11yTest`) keep their rule until WP-03
moves them; feature WPs write screen tests on `PhonoScreenRule` (AGENTS.md section 5).

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
