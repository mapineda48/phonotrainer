# PhonoTrainer for Android: architecture

Status: v1 architecture, revision 5, 2026-10-11 (revisions 2 to 4: 2026-10-10; revision 1: 2026-10-04). Owner:
software architect.

**Revision 5 notes (2026-10-11, change request CR-01).** The owner answered Q-1 to Q-7 while v1-core implementation was
in flight; spec revision 4 (D-53 to D-57) turned the answers into two v1-complete features and a version-2
requirement. Nothing of v1-core's behaviour changes, frozen contracts change only by additive amendment (work-plan
2.2, 4.7) and new server work is a new milestone (WP-27 A), never a rewrite of WP-01. Section 25.5 lists every item.
1. **Deleting a finished analysis from the phone (8.8, AD-24; spec D-54, S-25)**: a new outbox operation
   `DELETE_FINISHED`, bound to the server id, library id and device id it was made for, sent before new requests,
   undoable while it waits, never sent elsewhere; the server route is separate from v1-core's (server-contract 6.11).
2. **Shared progress (7.8, 10, AD-25; spec D-55, S-24)**: every practice answer gets an id and a time on the
   computer's clock, so totals add up and a reset clears exactly what was given before it; the phone's local totals
   mirror the shared ones after each exchange, so every screen keeps reading the same table; the Saved list is
   last-change-wins with tombstones and an opaque cursor; a phone's reset, like a deletion's target, travels in the
   signed request target (20.8). The browser's adoption is a hand-off (`android/docs/web-progress-sync.md`).
3. **One v1 amendment now, the rest at v1-complete (4.3, 4.4)**: `learning.db` gains `learning_meta` with a
   `learning_id` in v1 (before any install), because every backup of every release must carry it for the first-share
   baseline to count once (spec F-17-AC11); every other table and type arrives with Room version 2 and the
   v1-complete amendment.
4. **Version 2: encrypted transport (26, AD-26)**: TLS 1.3 with the computer's key pinned at pairing, pairing protected
   by SRP-6a over the pairing code, v1-paired phones moved over by a proof made with their existing key. Not part of
   the v1 work plan.

**Revision 5 review fixes (2026-10-11, fix round 1).** The security and consistency reviews of revision 5 found no
blocker; every high and medium finding and most low ones are fixed in place, and 25.6 lists each with its reason. In
short:
1. **Bodies covered, restarts closed (5.4, 20.8)**: the three CR-01 calls put `body_sha256` in the signed query, and the
   computer refuses a signature older than its own start, so a recorded request can neither be replayed after a
   restart nor carry another body.
2. **Limits refuse items, never whole requests (7.8; server-contract 4.7, 6.12)**: no `409 store_full`; a refused item
   is reported and marked handled, so no page can block an outbox; the Saved fields are bounded by the job.
3. **Deletion hardening (8.8, 6.3)**: a per-phone rate limit and a visible audit line on the computer; "Undo" is
   withdrawn once an attempt may have reached the computer (`maybe_sent`); answers are matched by code
   (`unknown_route` is never "already gone"; fix round 2 makes it a refusal, spec D-62); "Forget this computer" waits
   for a deletion in flight.
4. **Shared progress (7.8, 4.3, 4.5)**: a first share to another computer sends this phone's own answers (`own_stat`),
   never the first computer's aggregate; the pairing store keeps the two capability flags (CR-01-A2).
5. **Version 2 (26)**: the SRP-6a safety checks of RFC 5054, a separate candidate client and a pin-bound `hello`, no
   plain pairing on a version-2 computer without a legacy code, and a new device key at migration.

**Revision 5 review fixes, round 2 (2026-10-11).** The product track answered the architecture's requests (spec 15.7,
D-58 to D-65); the architecture now follows those answers, and 25.7 lists each item:
1. **A computer that no longer offers the deletion refuses it (8.8, 19; spec D-62)**: `404 unknown_route`, or a
   connected computer whose `health` no longer announces `delete_finished`, makes the deletion "Needs your attention"
   with F-05's "not offered" sentence, "Try again" and "Keep the clip"; it no longer waits.
   `DeletionWait.COMPUTER_CANNOT` is replaced by `DIFFERENT_COMPUTER`: a `409 different_computer` waits with "A
   different computer answered".
2. **`DeletionCopy` holds the product's words (19)**: the refusal sentences of D-62, the imported clip's words of D-58,
   the waiting reason in D-61's order (one `WaitingReasonCopy` for requests and deletions), and D-65's line on a
   waiting deletion that may have reached the computer.
3. **The quoted title has an owner (19)**: `QuotedTitle` in `:core:ui`, made by the integrator in CR-01-A2 with its
   unit test; every CR-01 `{title}` goes through it (design 9.26, security review F-14).
4. **The first share of the test phone (21.7; spec D-57 (3))**: the QA log counts QA's practice answers from WP-25 on,
   and before the test phone can first share with the owner's computer the kit compares its totals with that count,
   then clears them (all QA's) or reports what the first share will send.

**Revision 4 notes (2026-10-10, pre-implementation closure).** The cross-track consistency review and spec revision 3
(decisions D-37 to D-52) closed every open item before implementation. Section 25.4 lists each item, its disposition
and where it landed. The main changes:
1. **Two count units (3.4, 9.4; D-48)**: `OccurrenceRepository.changeCounts()` returns, per change, the **short
   count** (what the change mode plays, for Home chips, the mode picker and Learn cards) and the **occurrence count**
   (the phenomenon page only).
2. **"Try audio only" only where it can work (8.3, 8.4, 19; D-49)**: new transitions from `COPY_FAILED(no_space)`
   (while the source is readable) and from a file refused at sending, both back to `PREPARING`; re-runs use
   `audio_only` for links only; `Request.actions` is the one authority on what a row offers.
3. **Spec copy that replaced change requests (6.1, 6.2, 8.5, 19; D-39 to D-42)**: the discovery-conflict note, this
   phone's address in Connection details, the large-file waiting line (`WaitingInfo.needsAppOpen`), the
   `live_stream`/`too_long` copy with the computer's link limit, one probe-outcome copy for `phone_access_off` and
   `lan_forbidden`.
4. **Assets and licences (17, 20.6; D-50)**: no legacy raster mipmaps; the licence check covers libraries (notices
   7.2) and the bundled fonts and icon set (notices 7.3, from `assets/licenses/bundled.json`).
5. **3.4 mirrors the work plan's consistency pass**: the additive request, sync and clip-detail fields, and the
   shared components that moved (`ConnectionStatusCard`, `ShareBridge`, `PosterArt`, `MarksLegend`, the shell's
   message host, banner slot and appearance helper).

**Revision 3 notes (2026-10-10).** Three reviews (security, Android platform, delivery) of revision 2. Every blocker
and high finding is fixed in place; most medium and low ones too. Section 25 (Review log) lists every finding, its
disposition and where it landed, and records the few that were not adopted, with the evidence. The main changes:
1. **Trust and credentials (5.3, 6.1, 6.2)**: the phone trusts an address only after an HMAC identity proof in
   `hello`; requests are signed with a per-device key (`PT1`), so the token crosses the network once; two answers
   with one server id are a conflict, never a duplicate.
2. **Work that must survive leaving the app (7.2, 8.3, 12)**: shared-file copies and the sheet's immediate send run
   as expedited WorkManager work; background uploads start only when they fit the background window; the
   `sync-on-wifi` work backs off on foreign networks.
3. **Navigation state (16.2)**: each tab's stack is decorated separately, so a tab switch keeps its ViewModels and
   saved state; the player pool releases its decoders when Watch is not visible (11.2).
4. **Share target (12, 20.1)**: `ShareActivity` has an empty task affinity and closes its own task.
5. **Testability (2.2, 21)**: one global `@TestInstallIn` set with real repositories (no fake-repository app tests);
   JVM test fixtures and the fake computer in `:core:testing-jvm`; the network API in its own JVM module
   `:core:network-api`; GMD for the three core modules that need real codecs; a black-box R8 smoke; a host-side fake
   computer for every emulator step.
6. **Module seams (2.2, 3.4)**: the IPA tokenizer and the shadowing plan move to `:core:common`; compare rows reach the
   lesson through `ClipContentRepository`; one `RequestFailureCopy`.
7. Hardening: server-supplied identifiers validated before they become paths (4.6, 5.4), shared-file names never
   trusted (8.3), notification `PendingIntent`s unique per target (13), logging and repository hygiene (20.4, 20.7).

Where a revision 2 note below conflicts with revision 3 (for example where `ShadowingPlan` or `FakeComputer` live),
revision 3 and the sections it changed win.

**Revision 2 notes (2026-10-10).** Writing `server-contract.md` and `work-plan.md` exposed the gaps below; each is
fixed in place in the section named, and nothing else changed.
1. **Companion numbering checked**: this document's references "server-contract 5" (discovery datagram), "6" (HTTP
   routes), "6.4" (artifact names), "11" (test plan) and "work-plan.md section 3" (ownership table) match those
   documents; the WP ids used here mean what work-plan 5 to 8 say.
2. **Contract freeze completed (3.4, 2.2)**: `FeedPlayer` was named but not specified, `ShadowingRunner` had no
   interface, and the seams between core modules were implicit. The M2 freeze now also contains the remote model
   (`model.remote`), the SPIs `RequestSender`, `RequestReconciler`, `ClipIndexer`, `ClipLeases`, `ClipRemovalHook`,
   `StudyProtection`, `StartupTask` (package `spi`), `FeedPlayer`, `ShadowingRunner`, and the `:core:network` API
   (`ComputerClient`, `ArtifactTransfer`, `JobChannel`, `AllowedPeers`, `VerifiedPeers`, `AddressPolicy`,
   `ComputerNetworkBinder`), with the shapes in work-plan 4.2 and 4.3. `:core:sync` therefore reaches requests,
   corpus and study through `:core:domain` SPIs, not through their modules (module table).
3. **Lean channel from v1-core (5.5, 7.6)**: the phone channel is a new route, so its events are lean from the start
   (no artifacts, no progress log; server-contract 7). A `job` event whose `revision` differs from the stored one
   triggers a sync pass to fetch artifacts. Spec 14.4 cut item 8 (lean events) no longer applies.
4. **Pairing details (6.3)**: "Connect again" sends `previous_device_id`, so the computer re-activates the same device
   id and the phone's accepted requests stay its own; "Forget this computer" is `DELETE /api/phone/devices/self`;
   pairing can also answer `app_too_old` (server-contract 6.2).
5. **Extension point added (17)**: `ClipReductionMeters`, for the Clip page's "How reduced is this speech?" (F-05,
   v1-complete with F-18), because `:feature:library` cannot depend on `:feature:insights`. Components shared by
   several features (`ClipCard`, `ClipRow`, `RequestRow`, `QuestionCard`, `AnswerFeedback`, `ShadowingPanel`,
   `OccurrenceRow`) live in `:core:ui` `component/`, each owned by one feature WP (work-plan 4.5).
6. **Placement made explicit**: `ShadowingPlan` is a pure function in `:core:analysis` (11.5); `A11yAudit` lives in
   `:core:testing` (18); `FakeComputer` (WP-05) also implements the v1-complete routes, and an in-memory
   `FakeComputerClient` exists from M2 so that connectivity, sync and requests start before the HTTP fake (21.3).
7. **Didactic extractor re-checked (15.1)** after the worktree's fast-forward to `main` 9ef0457 (responsive web layout):
   its inputs are unchanged (that merge touched `IpaChart.tsx`, `LearnHome.tsx`, `PhenomenonPage.tsx` and
   `learn.test.tsx` only), so the input table and the CI path filters stand. The request stepper's stage labels and
   teaching lines (`web/src/jobs/stages.ts`) are deliberately not extracted: the spec (F-20) rewrites the download
   line, so they are spec copy in `:feature:requests`; the server's `stage` values equal the web's `StageKey` values,
   and a server test guards the prefix table against drift (server-contract 11.3).
8. **Discovery port (6.1)**: 45317/udp is unassigned in the development computer's services database (checked
   2026-10-10).
9. **Ownership check (23, 22)**: `check_ownership.py` takes the WP's baseline (`--base`); the CI hygiene job also runs
   the scripts' own `unittest` suite.

Inputs: `android/docs/product-spec.md` (the spec: it fixes behaviour; this document never changes behaviour, it
decides how to build it), `android/docs/server-contract.md` (the HTTP/UDP contract, written with this document),
`android/docs/work-plan.md` (who builds what, in which order), and the planning research (toolchain, Artemi reference
study, backend contract, web features, addendum; the addendum wins on conflicts). `android/docs/design.md` and the
assets in `android/design-src/export/` are produced in parallel by the design track; this document depends only on
their existence, never on their details (section 17).

Conventions
- `AD-nn` is an architecture decision. Each one states the decision, the rationale and, where it matters, the
  rejected alternative. Spec IDs (`F-nn`, `S-nn`, `NFR-nn`, `D-nn`, ACs) refer to the product spec.
- "MUST" / "SHOULD" as in RFC 2119.
- Paths are relative to the repository root unless they start with a module name (`core/sync/...` means
  `android/core/sync/...`).
- Nothing in this document is an address, a credential, a serial or a personal path. Example addresses come from the
  documentation ranges (192.0.2.0/24); the two numbers that are product constants are the HTTP port 8000 and the
  discovery port 45317 (spec 7.2).

---------------------------------------------------------------------------------------------------------------

## 1. Decisions at a glance

| Id | Decision | Section |
|---|---|---|
| AD-01 | `android/` is a self-contained Gradle build (own settings, wrapper, version catalog) inside the repository | 2.1 |
| AD-02 | Multi-module: `:app`, `:core:*` (JVM where possible), `:feature:*`; API modules (`:core:domain`, `:core:network-api`) that consumers code against; implementations bound with Hilt; test fixtures in JVM `:core:testing-jvm` | 2.2 |
| AD-03 | Convention plugins in an included build (`build-logic/`) own all shared Gradle configuration | 2.3 |
| AD-04 | Toolchain exactly as verified on this host: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20, KSP 2.3.12, Gradle 9.8.0, JDK toolchain 17 | 2.4 |
| AD-05 | minSdk 29, compileSdk 37, targetSdk 36 (D-18); no local-network permission | 2.5 |
| AD-06 | Package root `com.mapineda48.phonotrainer`; no directory anywhere under `android/` is named `data`, `out`, `out_*`, `downloads` or `workspace`; a hygiene check enforces it | 2.6 |
| AD-07 | Shared files are frozen at B1 and changed only through the integrator (a dedicated role from B1); everything else is owned by exactly one work package; integration is by git, never by copying files | 2.7 |
| AD-08 | Official architecture guide: UI (Compose + ViewModel, immutable UI state) / domain (interfaces and pure logic) / data (repositories over Room, DataStore, network, files) | 3 |
| AD-09 | Two Room databases: `library.db` (catalogue, artifacts, derived index, device queue; never backed up) and `learning.db` (progress; backed up) | 4.1 |
| AD-10 | Every schema change after the first install on the test phone bumps the version with a tested migration (before it, changes fold into version 1); destructive fallback is forbidden | 4.4 |
| AD-11 | One OkHttp client, bound per socket to the current Wi-Fi/Ethernet network, with no DNS and a cleartext guard that only lets requests reach the paired computer's current address (or the address being checked) | 5 |
| AD-12 | An address is trusted only after `hello` returns a valid HMAC proof keyed by this phone's key; requests are then signed (`PT1`), never carrying the token | 5.3 |
| AD-13 | Discovery = one UDP probe to broadcast on port 45317, unicast replies; manual address as fallback; no mDNS, no sweep | 6.1 |
| AD-14 | One sync orchestrator; foreground loop for immediacy, WorkManager for durability (periodic + constraint-triggered one-time work with backoff); transfers as WorkManager workers that become a data-sync foreground service only when started from the foreground; anything that must outlive the visible screen (shared-file copies, the sheet's send) is expedited work, never the application scope | 7 |
| AD-15 | Downloads go to a temporary file, resume with Range, are verified by size and SHA-256 against the listing, and switch atomically | 7.4 |
| AD-16 | The device queue is a Room table plus an outbox of commands; every request has a client UUID that is its idempotency key; a re-run gets its own key | 8 |
| AD-17 | Shorts, spotlights and the occurrence index are derived on the phone by pure, versioned functions in `:core:analysis` and stored in `library.db` | 9 |
| AD-18 | Playback: one ExoPlayer per visible or adjacent short (pool of 3); audio is the clock; the muted video is merged into the same player (`MergingMediaSource`), so there is no drift loop | 11 |
| AD-19 | Share target = a separate exported, translucent `ShareActivity` with an empty task affinity; notifications and internal links use a non-exported activity alias; no URI scheme | 12, 16 |
| AD-20 | Didactic content comes from a dependency-free Node extractor that imports the web TypeScript read-only and writes a versioned JSON asset; a drift check fails the build on any difference | 15 |
| AD-21 | Navigation 3 with all keys in `:core:navigation`, per-tab back stacks each decorated separately (state survives tab switches), overlays as scenes, entries contributed by features through Hilt multibinding | 16 |
| AD-22 | Screen tests run on Robolectric (sdk 35) per module; the Gradle Managed Device runs the `:app` end-to-end suite (one global `@TestInstallIn` set, real repositories) and the instrumented tests of `:core:player`, `:core:requests` and `:core:sync`; the R8 build is tested black-box | 21 |
| AD-23 | The fake computer is a stateful MockWebServer dispatcher implementing `server-contract.md` with injectable failure modes (JVM, `:core:testing-jvm`); emulator and QA runs use a host-side fake computer built from the real server code | 21.3 |
| AD-24 | (v1-complete, CR-01) Deleting a finished analysis on the computer is an outbox operation of the device queue, stored before any attempt, bound to the server id, library id and device id it was made for, sent before new requests through its own signed route (body included in the signature), idempotent ("already gone" is done), undoable only while waiting and never after an attempt that may have reached the computer | 8.8 |
| AD-25 | (v1-complete, CR-01) Shared progress: answers carry ids and times on the computer's clock and are summed on the computer after the latest reset; a device's own pre-sharing answers arrive once as a baseline named by its `learning_id`; the Saved list is last-change-wins with tombstones; the phone mirrors the shared totals into its own tables after each exchange | 7.8, 10 |
| AD-26 | (version 2, not in the v1 work plan) Encrypted transport: TLS 1.3 with the computer's self-generated key pinned on the phone; pairing protected by SRP-6a (with RFC 5054's checks) over the pairing code with the pin in the key confirmation, and plain pairing refused unless the operator asks for a legacy code; v1-paired phones move over through a pin proof made with their device key and get a new key inside TLS; PT1 signatures stay | 26 |

---------------------------------------------------------------------------------------------------------------

## 2. Repository layout and build

### 2.1 Location (AD-01)

`android/` is the root of an independent Gradle build: `android/settings.gradle.kts`, `android/gradlew`,
`android/gradle/wrapper/` (Gradle 9.8.0 with `distributionSha256Sum` pinned), `android/gradle/libs.versions.toml`,
`android/build-logic/`. Every Gradle command runs with `android/` as the working directory. The Python server, the web
client and the root `Makefile` keep working unchanged; the root `Makefile` gains an `android-check` target (WP-02) that
runs `./gradlew` from `android/`.

```
android/
  .gitignore  .editorconfig  README.md  AGENTS.md  SECURITY.md
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradlew.bat
  gradle/libs.versions.toml  gradle/wrapper/
  build-logic/convention/             convention plugins (AD-03)
  app/                                the application module
  core/<name>/                        core modules (including core/network-api and core/testing-jvm)
  feature/<name>/                     feature modules
  qasmoke/                            black-box smoke tests of the R8 `qa` APK (com.android.test, 21.6)
  baselineprofile/                    Baseline Profile generator and startup benchmarks (com.android.test, 21.6)
  tools/didactic/                     the read-only content extractor (section 15)
  tools/reference/                    the /api/reference snapshot exporter (section 15.4)
  scripts/device-qa/                  device QA kit (section 21.7)
  scripts/hygiene/                    repository hygiene checks (section 20.7)
  docs/                               product-spec, architecture, server-contract, work-plan, design, qa-report, decisions
  design-src/                         design sources and the staging export of the design track
```

### 2.2 Module structure (AD-02)

**Decision: multi-module.** The owner wants several engineers building in parallel, each in a separate copy of the
project, merging back only the files they own. A single `:app` module concentrates the hot files (one manifest, one
`strings.xml`, one DI graph, one navigation file, one source tree) and turns every merge into a conflict. A module per
feature and per infrastructure concern gives each work package a directory it owns completely, its own manifest
(merged by AGP), its own `res/values/strings.xml` (with non-transitive R classes), its own DI module and its own
tests. The cost (more build files, a convention-plugin layer, longer configuration) is paid once by the foundation
work package and is the standard of the official samples (Now in Android).

**API and implementation.** Features never depend on another feature, and never on the implementation of a core
service: they compile against `:core:domain`, a JVM module that holds the domain types and every repository/service
interface (section 3.4). Each implementation lives in its own core module and is bound with Hilt `@Binds`. The
foundation work package creates every implementation module with a working *stub* binding (empty flows, "not
connected"), so the app compiles and launches in every copy of the project before the real implementation lands.

Modules (JVM = plain Kotlin/JVM module, no Android dependency; everything else is an Android library):

| Module | Kind | Holds | Depends on |
|---|---|---|---|
| `:app` | application | `MainActivity`, `PhonoApplication`, navigation host and tab shell, the internal entry alias, WorkManager/Hilt wiring, manifest (launcher), backup rules, network security config, splash, release config | every module |
| `:core:domain` | JVM | domain types (`model/`), interfaces (`api/`) and core-to-core SPIs (`spi/`): the contract every work package codes against | coroutines-core, javax.inject |
| `:core:common` | JVM | `RetrySchedule`, SHA-256 streaming, UUID helpers, log facade, `AppError` helpers, pure utilities; `ipa/` (`IpaTokenizer`) and `shadowing/` (`ShadowingPlan`), owned by WP-12, because `:core:ui` and `:core:player` need them and cannot depend on `:core:analysis` | `:core:domain` |
| `:core:analysis` | JVM | tolerant `analysis.json` parser, shorts v1, spotlight, occurrence extraction, compare rows, link parsing and normalization | `:core:domain`, `:core:common`, kotlinx-serialization |
| `:core:network-api` | JVM | the frozen API of the computer client: `ApiResult`, `ComputerClient`, `ArtifactTransfer`, `JobChannel`, `AllowedPeers`, `VerifiedPeers`, `AddressPolicy`, `ClockOffset`, `ComputerNetworkBinder` (it takes a network handle, a `Long`, so it needs no Android type), request bodies (work-plan 4.3) | `:core:domain` |
| `:core:navigation` | Android | every `NavKey`, `NavTarget`, `Navigator`, `EntryInstaller` | navigation3-runtime, `:core:domain` |
| `:core:designsystem` | Android, Compose | theme, color roles, type, shapes, spacing, motion, fonts, icons, illustrations, launcher, splash and notification resources, base components, the bundled licence texts and their index (`assets/licenses/`, 20.6) | `:core:domain` |
| `:core:ui` | Android, Compose | domain-aware shared composables: marks, change chips, practice badges, status lines, availability chips, "What's this?" control and sheet, IPA text, loading gate, empty-state scaffold, coach-mark host, the visual pieces several features draw (`ShortProgress`, `PosterArt`, `MarksLegend`, all over `:core:designsystem`'s `StylusTrace` and tokens), extension points; and `component/`, the domain-aware components shared by several features, each owned by one feature WP (17; work-plan 4.5) | `:core:designsystem`, `:core:domain`, `:core:navigation` |
| `:core:content` | Android | bundled didactic JSON and reference snapshot; `DidacticContent`, `ReferenceRepository` | `:core:domain` |
| `:core:database` | Android, Room | `LibraryDatabase`, `LearningDatabase`, entities, DAOs, migrations, exported schemas | `:core:domain` |
| `:core:datastore` | Android | settings store, coach-mark store, pairing store, Keystore credential cipher | `:core:domain` |
| `:core:network` | Android | implementation of `:core:network-api`: OkHttp client, per-socket network binding, cleartext guard, request signing, identity proofs, Retrofit `ComputerApi`, DTOs and their validation, job channel (WebSocket) client, transfer primitives, the binder that turns a network handle back into a `Network` | `:core:network-api`, `:core:domain`, `:core:common`, `:core:datastore` |
| `:core:notifications` | Android | channels, `Notifier`, notification builders, permission state | `:core:domain`, `:core:navigation`, `:core:ui` (for `RequestFailureCopy` and its strings only, 19) |
| `:core:connectivity` | Android | network monitor, UDP discovery, probe state machine, pairing, background-restriction detection | network-api, datastore, database |
| `:core:corpus` | Android | clip indexer, parsed-clip cache, shorts/occurrence/feed queries | analysis, database, content |
| `:core:sync` | Android | sync orchestrator, catalogue reconcile, downloads, verification, storage budget and eviction, deletion semantics, workers | network-api, database, notifications; connectivity, corpus, content, study and requests only through `:core:domain` interfaces and SPIs (work-plan 4.2) |
| `:core:requests` | Android | device queue, file intake, sender, uploads, outbox, re-runs | analysis, network-api, database, notifications, datastore |
| `:core:player` | Android | Media3 player pool, short and span playback, audio arbitration, shadowing runner (over `:core:common`'s `ShadowingPlan`), decoder capability check | `:core:domain`, `:core:common`, media3 |
| `:core:study` | Android | sets, questions (quiz port), practice stats, saved items, seen state and positions | analysis, corpus, database, datastore |
| `:core:testing-jvm` | JVM | test-only: `AnalysisFixtures` (the one Kotlin port of the server's `mk_analysis`, WP-12), `FakeComputer` and `FakeComputerClient` (WP-05a), settable fakes of every `:core:domain` and `:core:network-api` interface, `TestClock`, `MainDispatcherRule` | `:core:domain`, `:core:network-api`, `:core:common`, mockwebserver3, kotlinx-serialization, coroutines-test, JUnit |
| `:core:testing` | Android | test-only: Android test helpers on top of `:core:testing-jvm` (exposed as `api`): `A11yAudit`, Robolectric and Compose rules, `SyntheticMedia` (instrumented AAC/H.264 builders, WP-11), fakes of Android-facing interfaces | `:core:testing-jvm`, `:core:domain`, `:core:designsystem`, Compose UI test, Robolectric; never an implementation module |
| `:core:articulator` | Android, Compose | v1-complete: the 2D mouth model and its Canvas renderer | `:core:content`, `:core:designsystem` |
| `:core:recorder` | Android | v1-complete: microphone takes, A/B playback, `/compare` client | network-api, player |
| `:feature:onboarding` | Android, Compose | Welcome (F-01) and the coach-mark catalogue | ui |
| `:feature:connect` | Android, Compose | Connect to your computer, Connection details (F-02) | ui |
| `:feature:home` | Android, Compose | Home dashboard (F-03) | ui |
| `:feature:library` | Android, Compose | Library (F-04), Clip page (F-05) | ui |
| `:feature:watch` | Android, Compose | the feed and the short card (F-06, F-07, the feed side of F-08) | ui, `:core:player` API |
| `:feature:lesson` | Android, Compose | word lesson (F-10), shadowing UI (F-12); v1-complete record UI (F-13) and phrase details (F-11) in owned sub-packages | ui |
| `:feature:learn` | Android, Compose | Learn, phenomenon pages, IPA chart, "Words we use" (F-14 index, F-15, F-16) | ui |
| `:feature:practice` | Android, Compose | Practice, Saved (F-09, F-17) | ui |
| `:feature:requests` | Android, Compose | Requests, request detail, Add a clip (F-19, F-20) | ui |
| `:feature:share` | Android, Compose | `ShareActivity` and the confirmation sheet (section 8 of the spec) | ui |
| `:feature:settings` | Android, Compose | Settings and its sub-pages, Storage UI, About and licences (F-21 UI, F-22, F-24) | ui |
| `:feature:insights` | Android, Compose | v1-complete: Insights (F-18) | ui |

Dependency rules (checked by a Gradle dependency-guard task in `build-logic`, WP-02):
1. `:feature:*` may depend only on `:core:domain`, `:core:common`, `:core:navigation`, `:core:designsystem`,
   `:core:ui`, `:core:content` (read-only content) and the *API* of `:core:player` (the `SpanPlayer`/`FeedPlayer`
   interfaces live in `:core:domain`; the player module is only needed for its composable surface, see 11.6). Never on
   another feature, never on `:core:database`, `:core:network`, `:core:network-api`, `:core:sync`, `:core:requests`,
   `:core:corpus`, `:core:study`, `:core:connectivity`, `:core:analysis`. What a feature needs from those reaches it
   through a `:core:domain` interface (for example `ClipContentRepository.compareRows`, 3.4).
2. `:core:*` modules form a DAG; no core module depends on a feature. Core modules other than `:core:network` use the
   computer only through `:core:network-api`.
3. `:core:testing` and `:core:testing-jvm` are only ever test dependencies (`testImplementation` /
   `androidTestImplementation`), and they never depend on an implementation module (only on `:core:domain`,
   `:core:network-api`, `:core:common`, `:core:designsystem` and test libraries). Any module's tests may therefore use
   them without a dependency cycle (delivery review D-18; WP-02 proves the shape at M1 with a two-module spike).
4. Pure logic goes in JVM modules (`:core:domain`, `:core:common`, `:core:analysis`, `:core:network-api`) so it is
   tested in milliseconds without Robolectric.
5. The dependency guard also fails the build when a JVM module gains an Android dependency.

```
                                   :app
     ┌──────────────┬──────────────┬──┴────────────┬───────────────┬──────────────┐
 :feature:*      :core:sync    :core:requests  :core:connectivity  :core:study   :core:player ...
     │             │   │    \        │     \          │      │          │     \
     │             │   │   :core:corpus ─ :core:analysis      │     :core:datastore
     │             │   │        │               :core:network-api ── :core:common
     │        :core:notifications  :core:database        │
     └──────── :core:ui ── :core:designsystem            │
                  └──── :core:navigation ── :core:domain ◄─┘ (everything depends on :core:domain)

  :core:network implements :core:network-api and is bound in :app; tests use :core:testing(-jvm), which depend on no
  implementation module.
```

### 2.3 Convention plugins (AD-03)

`build-logic/convention` is an included build (`pluginManagement { includeBuild("build-logic") }`) with these plugins;
module build files are a few lines that apply one or two of them and list module dependencies:

| Plugin id | Applies |
|---|---|
| `phonotrainer.android.application` | AGP application, compileSdk 37, minSdk 29, targetSdk 36, Java 17 toolchain, lint policy, build types `debug`, `release` (R8 + resource shrinking), `qa` (section 21.6), Gradle Managed Device `pixel9Api36`, packaging excludes |
| `phonotrainer.android.library` | AGP library with the same SDK/Java/lint settings, unit-test options (`isIncludeAndroidResources = true`, Robolectric on sdk 35, section 21.2, `maxParallelForks = 2`, test heap 1 GB), consumer R8 rules file |
| `phonotrainer.android.library.instrumented` | adds the Gradle Managed Device `pixel9Api36` and the instrumented test stack to a library; applied only by `:core:player`, `:core:requests` and `:core:sync`, whose real codecs, `MediaMuxer` and WorkManager scheduling cannot run on Robolectric (delivery review D-11) |
| `phonotrainer.android.test` | AGP `com.android.test` for the black-box modules `:qasmoke` and `:baselineprofile` (target `:app`, variant `qa`), UiAutomator, the managed device |
| `phonotrainer.android.compose` | Compose compiler plugin, Compose BOM, tooling (debug), preview |
| `phonotrainer.android.feature` | library + compose + hilt + the dependencies every feature needs (domain, navigation, designsystem, ui, content, lifecycle, hilt-viewmodel-compose) and the test stack (JUnit, Truth, Turbine, Robolectric, compose ui-test, `:core:testing`) |
| `phonotrainer.android.hilt` | Hilt plugin, KSP, hilt-android, hilt-compiler |
| `phonotrainer.android.room` | Room plugin, KSP, schema directory `$projectDir/schemas`, schemas added to test assets |
| `phonotrainer.jvm.library` | `org.jetbrains.kotlin.jvm` 2.4.20, JVM toolchain 17, JUnit, Truth |
| `phonotrainer.kotlin.serialization` | the kotlinx-serialization compiler plugin and runtime |

AGP 9 has built-in Kotlin for Android modules (no `kotlin-android` plugin); JVM modules apply the Kotlin JVM plugin
at the same 2.4.20 version, which is already on the build classpath through the Compose compiler and serialization
plugins. WP-02 proves this combination in its first milestone (M1) before any other module is written; the fallback,
if the convention layer fights the toolchain, is per-module build files generated from one template (still one owner
per file).

**Lint policy** (orchestrator decision, 2026-10-10; closes the closure verifier's blocking item). Both Android
convention plugins set `lint { warningsAsErrors = true; abortOnError = true; checkReleaseBuilds = true;
lintConfig = rootProject.file("config/lint/lint.xml") }`, and `:app` adds `checkDependencies = true`. The one
shared `android/config/lint/lint.xml` (INTEGRATOR-owned) sets these checks to `ignore`, each with a one-line reason
in an XML comment:
- `GradleDependency`, `AndroidGradlePluginVersion` and `NewerVersionAvailable`: they compare the catalog with what
  was published after it, so a build would start failing with no code change; version upgrades are deliberate
  catalog changes (2.4), made by the integrator and verified by the whole definition of done.
- `OldTargetApi`: targetSdk 36 is a decision (spec D-18) while compileSdk is 37, so this warning is permanent by
  design.
No other check may be disabled or downgraded, and no lint baseline is ever used. A WP that hits a check it believes
is wrong for this project files a change request to the integrator (work-plan 2.2) with the evidence; the integrator
adds it to `lint.xml` with its reason, or rejects it.

### 2.4 Toolchain and version catalog (AD-04)

Versions verified on this host (toolchain research, 2026-10-04). They live only in `gradle/libs.versions.toml`; no
build file contains a version number.

| Group | Version | Artifacts |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | `com.android.application`, `com.android.library` |
| Kotlin (and Compose compiler, serialization plugin) | 2.4.20 | |
| KSP | 2.3.12 | |
| Gradle wrapper | 9.8.0 | `distributionSha256Sum` pinned |
| Compose BOM | 2026.09.00 | ui, foundation (pager), material3 1.4.0, ui-test-junit4, ui-test-manifest, ui-tooling(-preview) |
| AndroidX core / splashscreen / activity-compose | 1.19.1 / 1.2.0 / 1.13.0 | |
| Lifecycle (runtime, runtime-compose, viewmodel-compose, process, viewmodel-navigation3) | 2.11.0 | `lifecycle-process` for the foreground session |
| Navigation 3 (runtime, ui) | 1.2.0 | |
| Hilt / AndroidX Hilt | 2.60.1 / 1.4.0 | hilt-android, hilt-compiler, hilt-android-testing, hilt-lifecycle-viewmodel-compose, hilt-work, androidx hilt-compiler |
| Room | 2.8.5 | runtime, ktx, compiler, testing |
| WorkManager | 2.12.0 | work-runtime-ktx, work-testing |
| DataStore | 1.2.1 | datastore-preferences |
| Coroutines | 1.11.0 | android, core, test |
| kotlinx-serialization-json | 1.11.0 | |
| OkHttp | 5.5.0 | okhttp, mockwebserver3 (test), logging-interceptor (debug only) |
| Retrofit | 3.0.0 | retrofit, converter-kotlinx-serialization |
| Media3 | 1.11.1 | exoplayer, ui-compose, test-utils (test) |
| Coil | 3.6.3 | coil-compose only (posters are local files; no network module) |
| Test | JUnit 4.13.2, Truth 1.4.5, Turbine 1.2.1, Robolectric 4.17, androidx.test core 1.7.0, runner 1.7.0, ext-junit 1.3.0, espresso 3.7.0 | `androidx.test:core` is also a unit-test dependency (Robolectric needs `ApplicationProvider`) |
| JVM DI annotations | javax.inject 1 | for qualifiers in JVM modules |
| Licence report | AboutLibraries Gradle plugin (version chosen and verified by WP-19) | build-time only, strict licence mode (20.6) |
| Startup and black-box testing | `androidx.profileinstaller`, `androidx.benchmark:benchmark-macro-junit4`, `androidx.test.uiautomator`, the `androidx.baselineprofile` Gradle plugin (versions chosen and verified by WP-02 in the catalog; Apache-2.0) | `profileinstaller` is the only runtime addition (Android review M9: a sideloaded APK gets no cloud profiles) |

Deliberately not used: `material-icons-extended` (frozen, large; icons come from the design track as vectors),
`media3-session` (background playback is Later), Paging (lists are small), `security-crypto` (deprecated; the
Keystore is used directly, 20.3), any analytics, crash-reporting, advertising or cloud SDK (X-AC1), Ktor (a second
HTTP stack for nothing).

`gradle.properties`: `org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8`, `kotlin.daemon.jvmargs=-Xmx1536m`,
`org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `org.gradle.parallel=true` (decoupled modules),
`android.useAndroidX=true`, `android.nonTransitiveRClass=true`,
`android.testoptions.manageddevices.emulator.gpu=swangle_indirect` (required on this host: the default headless GPU
modes crash the emulator; CI overrides it on the command line, 22). The memory settings bound one copy of the project
at about 6.5 GB while testing, which is what lets four copies build at once on the development computer (work-plan
1.2). `settings.gradle.kts` keeps Artemi's supply-chain rules:
Google Maven with `includeGroupByRegex` content filters, Maven Central, Gradle Plugin Portal for plugins only,
`repositoriesMode = FAIL_ON_PROJECT_REPOS`, the foojay toolchain resolver.

### 2.5 SDK levels (AD-05)

minSdk 29 (Android 10: Ogg/Opus in `MediaMuxer`, scoped storage, modern media APIs), compileSdk 37 (the verified
toolchain), targetSdk 36 (D-18): on Android 16 local-network access is implicit, and on Android 17 an app targeting 36
keeps implicit access and must not declare `ACCESS_LOCAL_NETWORK` [TC 6]. JDK toolchain 17 for compilation;
Robolectric runs at sdk 35 because sdk 36 needs Java 21 on Robolectric 4.17. The app has no native code of its own.
Two dependencies ship native libraries (`datastore-core-android` 1.2.1: `libdatastore_shared_counter.so`;
`graphics-path` 1.0.1, through Compose UI: `libandroidx.graphics.path.so`); their arm64 LOAD segments are 16 KB
aligned (checked by the Android review with `readelf -lW`). AGP does not fail the build on misalignment by default,
so 16 KB compatibility is proven by `zipalign -c -P 16 -v 4` on the release APK (WP-19) and by installing and
starting it on the host's 16 KB page-size image `google_apis_playstore_ps16k` API 37.2 (WP-25).

### 2.6 Names and the root `.gitignore` trap (AD-06)

The repository root `.gitignore` has unanchored patterns `data/`, `out/`, `out_*/`, `workspace/`, `downloads/` and
file patterns `*.wav *.mp4 *.webm *.mp3 *.db *.log .env*`. Any Kotlin package or resource directory with one of those
names would silently vanish from commits [AD 1.1].

Decisions:
1. No directory under `android/` may be named `data`, `out`, `out_*`, `downloads` or `workspace` (packages
   included). The vocabulary used instead: `store` or `local` for persistence packages, `transfer` for downloads and
   uploads, `export` for outputs.
2. No media or database file is committed under `android/`. Test media are synthesized at test time (21.4).
3. `android/.gitignore` adds what the root file lacks and re-includes nothing (re-including `workspace/` or
   `downloads/` could expose personal media; avoiding the names is safer):

```gitignore
# Build outputs and local tool state (the root .gitignore ignores none of these)
.gradle/
build/
.kotlin/
.cxx/
.externalNativeBuild/
captures/
*.apk
*.aab
*.iml
.idea/
local.properties

# Python caches of the scripts (the root file ignores them too; Android rules do not rely on that)
__pycache__/
*.pyc

# Signing material: never committed (release signing, 20.5)
*.jks
*.keystore
keystore.properties
*.p12
*.pfx
*.pem
*.pepk

# Files that device debugging produces and that can hold the pairing, the key or personal data
*.preferences_pb
*.db-wal
*.db-shm
*.db-journal
*.hprof
bugreport*.zip
logcat*.txt

# Local QA evidence, screenshots and raw tool logs (docs/qa-report.md keeps the findings)
/artifacts/
```

4. `scripts/hygiene/check_repo.py` (WP-06) fails when a forbidden directory name exists, and when
   `git status --ignored --porcelain android/` lists any ignored path that is a source, schema, resource, font,
   licence text or asset (X-AC5). It runs as a local `pre-commit` and `pre-push` hook (20.7), in CI and in every work
   package's definition of done.

Package names: application id and root namespace `com.mapineda48.phonotrainer` (D-20); module namespaces
`com.mapineda48.phonotrainer.core.<module>` and `com.mapineda48.phonotrainer.feature.<module>`. Kotlin sources live in
`src/main/kotlin`.

### 2.7 Shared files and ownership (AD-07)

A file has exactly one owner at any time. The files every module touches are created complete by the foundation
(WP-02) in wave 1 and then **frozen** at B1 (the M2 contract freeze): only the integrator changes them, through change
requests that are additive. The integrator is a dedicated role, never a builder at the same time (delivery review
D-05): WP-02 bootstraps until B1 and hands over; WP-19 integrates from B1 to the v1-core gate; WP-26 from the gate on.
Integration is done with git (a branch or worktree per WP from its baseline commit, three-way merge by the
integrator), never by copying files over the integration tree, so concurrent edits of one file cannot silently
overwrite each other (delivery review D-03; work-plan 2.1).

| Hot spot | How parallel work avoids it |
|---|---|
| `settings.gradle.kts` | every module of v1 (including v1-complete ones) is declared in wave 1, each with a stub |
| `gradle/libs.versions.toml` | every dependency of v1 is declared in wave 1; a new one is a change request |
| `app/build.gradle.kts` | `:app` depends on every module from wave 1 |
| Manifests | each module has its own `AndroidManifest.xml`; components live where they belong (`ShareActivity` in `:feature:share`, the WorkManager foreground-service type in `:core:sync`); AGP merges them |
| Strings | each module has its own `res/values/strings.xml`; a sub-package or shared component owned by another WP has its own `strings_<name>.xml` (work-plan 3.2); resource names that `:app` references from `:core:designsystem` are frozen at B1 |
| Navigation | all keys are declared in `:core:navigation` in wave 1; each feature contributes its entries through an `EntryInstaller` in its own module (16.3) |
| DI | each module has its own Hilt module; stubs are replaced by the owner's real binding in the owner's module; the modules that end-to-end tests replace have frozen names (21.5) |
| Room | entities and the two `@Database` classes are frozen at the wave-1 freeze; DAO files belong to the work package of their area and may gain queries without a schema change |
| Fakes | each fake in `:core:testing-jvm` (or `:core:testing`) lives in its own file, owned by the work package that owns the real implementation |
| End-to-end robots | `app/src/androidTest/.../e2e/<area>/` per work package; the façade robot, the global test modules and the seeding helpers belong to the integrator (21.5) |
| `THIRD-PARTY-NOTICES.md` | the integrator owns the new "Android client" section (created by the integrator, WP-19); WP-03 owns its "Fonts and icons" subsection (pre-created heading); both are merged as patches of their lines |

The machine-readable ownership table is in `work-plan.md` (section 3); `scripts/hygiene/check_ownership.py` checks a
work package's diff against it before a merge.

---------------------------------------------------------------------------------------------------------------

## 3. Layers and application structure (AD-08)

### 3.1 UI layer

- Screens are composables split into a stateful entry (`FooRoute(viewModel = hiltViewModel())`, collecting with
  `collectAsStateWithLifecycle()`) and an `internal` stateless overload taking an immutable UI state and lambdas; screen
  tests and previews call the stateless overload (Artemi pattern).
- One `@HiltViewModel` per screen; assisted injection for navigation arguments
  (`hiltViewModel<VM, VM.Factory>(creationCallback = { it.create(key) })`).
- UI state is a sealed interface per screen (`Loading`, `Ready(...)`, `Empty(...)`, `Error(...)`) built with
  `combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), Loading)`. The loading rule of the spec (placeholder
  after 300 ms, text after 1 s) is implemented once in `:core:ui` (`LoadingGate`).
- One-shot messages (snackbars, notices such as "That clip is no longer on this phone.") are **state** that the UI
  reports as consumed (`UserMessages`, 3.4), never an event channel. The app shell (`:app`, WP-02) hosts them once,
  above every destination, together with the slot of the global "Connect again" banner (design 9.23); screens never
  draw their own snackbar host.
- Anything the learner would be upset to lose lives in `SavedStateHandle` (feed mode and short, playback time, speed,
  caption mode, lesson word and step, quiz question, pasted link draft) and, where it must outlive the process or
  span sessions, also in Room or DataStore (5.2 of the spec).
- Double-tap guards (`isSubmitting` flags) on every action that writes or sends.

### 3.2 Domain layer

`:core:domain` holds the types and interfaces (3.4). Pure algorithms that need no Android API live in
`:core:analysis` (clip derivations, link parsing) and `:core:common` (retry schedule, hashing). Use cases are not
wrapped in classes unless they combine several repositories in a way two screens share; such a use case lives in the
module that owns the main repository.

### 3.3 Data layer

Repositories implement the `:core:domain` interfaces over Room DAOs, DataStore, the network module and files. They
expose `Flow`s of domain types; Room entities and network DTOs never leave their module (mappers `toDomain()` /
`toEntity()`). Writes are `suspend` and atomic (one Room transaction per state transition).

Concurrency: `@ApplicationScope CoroutineScope` (SupervisorJob + `Dispatchers.Default`) for work that outlives a
screen; dispatchers injected with qualifiers (`@IoDispatcher`, `@DefaultDispatcher`); `Clock` injected everywhere time
matters (tests use a `TestClock` driven together with the coroutine test scheduler).

### 3.4 The domain contract (`:core:domain`)

This is the interface every parallel work package codes against. WP-02 transcribes it at milestone M2 (the
contract freeze); afterwards it changes only additively, through the integrator. Signatures are normative in shape;
WP-02 may add fields and parameters with defaults.

```kotlin
package com.mapineda48.phonotrainer.core.domain.model

@JvmInline value class JobId(val value: String)          // server job id, e.g. "20261004-133519-a77c"
@JvmInline value class RequestId(val value: String)      // client UUID; also the request's first idempotency key
@JvmInline value class ServerId(val value: String)
@JvmInline value class LibraryId(val value: String)
@JvmInline value class DeviceId(val value: String)
@JvmInline value class ChangeLabel(val value: String)    // "flapping", one of the 16 reference labels
data class HostPort(val host: String, val port: Int)     // IPv4 literal only (6.2)
data class ShortId(val jobId: JobId, val firstSegment: Int)
data class WordRef(val jobId: JobId, val segment: Int, val word: Int)

enum class Family { REDUCTION, TD, ASSIMILATION, BOUNDARY, LEXICAL, OTHER }
enum class Advice { PRODUCE, UNDERSTAND }                // "Safe to produce" / "Recognize only"
enum class Site { YOUTUBE, YOUTUBE_SHORTS, X, OTHER }
enum class Track { MIX, DIALOGUE }

sealed interface ComputerStatus {                        // spec 7.5, one subtype per row
  data object NotConnected : ComputerStatus
  data object Offline : ComputerStatus
  data object NotOnWifi : ComputerStatus
  data object Looking : ComputerStatus
  data object Checking : ComputerStatus
  data class Connected(val name: String, val analyzing: Int, val inLine: Int, val lowOnSpace: Boolean) : ComputerStatus
  data class NotReachable(val name: String, val lastSeen: Instant?, val addressMayHaveChanged: Boolean,
                          val lastProbe: ProbeOutcome) : ComputerStatus
  data class DifferentComputer(val name: String) : ComputerStatus
  data object ConnectAgain : ComputerStatus
  data object ComputerTooOld : ComputerStatus
  data object AppTooOld : ComputerStatus
}
// "Syncing" is ComputerStatus.Connected combined with SyncState.Active (SyncController), never a separate claim.

sealed interface ClipAvailability {                      // spec 7.11 vocabulary
  data object OnThisPhone : ClipAvailability
  data class Bringing(val percent: Int) : ClipAvailability
  data object NotOnThisPhone : ClipAvailability          // removed by the learner, or never downloaded
  data object GettingReadyOnComputer : ClipAvailability
  data class DownloadPaused(val reason: PauseReason) : ClipAvailability
  data object NeedsDownloadingAgain : ClipAvailability
}
sealed interface VideoAvailability { /* OnThisPhone, Downloading(pct), NotOnThisPhone, GettingReadyOnComputer,
                                        NotAvailableFromComputer, CannotPlayOnThisPhone, AudioOnlyClip */ }

data class ClipSummary(val id: JobId, val title: String, val durationS: Double?, val words: Int?,
  val changeCount: Int?, val source: ClipSource, val availability: ClipAvailability, val video: VideoAvailability,
  val kept: Boolean, val origin: ClipOrigin /* ACTIVE, FORGOTTEN_COMPUTER, OTHER_LIBRARY */,
  val olderRules: Boolean, val shortsSeen: Int, val shortsTotal: Int, val addedAt: Instant)

data class Request(val id: RequestId, val kind: RequestKind, val state: RequestState, val title: String,
  val site: Site?, val link: String?, val fileName: String?, val fileBytes: Long?, val createdAt: Instant,
  val progress: RequestProgress?, val lastError: RequestError?, val jobId: JobId?,
  val actions: Set<RequestAction>,                       // the one authority on what the row offers (8.4)
  val waiting: WaitingInfo? = null,                      // why it waits: held, attempts, next attempt, needsAppOpen (8.5)
  val timeline: RequestTimeline = RequestTimeline(),     // milestone times of the request detail
  val rerunPending: Boolean = false,                     // "Starting again on your computer" (8.4)
  val updatedAt: Instant? = null)                        // "Last known: Analyzing 40 % (10 min ago)"
enum class RequestState { PREPARING, COPY_FAILED, COPY_MISSING, WAITING, SENDING, SENT, IN_LINE, ANALYZING,
  READY_ON_COMPUTER, GETTING_ON_PHONE, ON_PHONE, NEEDS_ATTENTION, CANCEL_WAITING, CANCELLED }
enum class RequestAction { CANCEL_PREPARING, SEND_NOW, STOP_SENDING, REMOVE, CANCEL, UNDO_CANCEL, TRY_AGAIN,
  TRY_AUDIO_ONLY, OPEN_LINK, SHOW_DETAILS, CONNECT_AGAIN, WHY }

sealed interface FeedMode {
  data class Mix(val setId: Long) : FeedMode
  data class InOrder(val jobId: JobId) : FeedMode
  data class Change(val label: ChangeLabel, val onlyClip: JobId? = null) : FeedMode
  data object Saved : FeedMode
}
data class Spotlight(val word: WordRef, val change: ChangeLabel, val advice: Advice, val crossesBoundary: Boolean)
data class ShortInfo(val id: ShortId, val ordinal: Int, val ofTotal: Int, val lastSegment: Int,
  val playStartMs: Long, val playEndMs: Long, val spotlight: Spotlight?, val markedWords: Int)
```

```kotlin
package com.mapineda48.phonotrainer.core.domain.api

interface Clock { fun now(): Instant; fun zone(): ZoneId }
interface SyncTrigger { fun request(reason: SyncReason) }     // requests and screens ask; :core:sync decides
interface UserMessages { val current: StateFlow<UserMessage?>; fun post(message: UserMessage); fun consumed(id: Long) }
interface Announcer { fun polite(text: String) }              // TalkBack announcements allowed by A-04 only

// :core:connectivity (WP-07)
interface ComputerStatusRepository {
  val status: StateFlow<ComputerStatus>
  val paired: StateFlow<PairedComputer?>                       // name, server id, device name, last seen, last sync
  val backgroundRestricted: StateFlow<Boolean>
  val diagnostics: StateFlow<ConnectionDiagnostics>            // address, this phone's address, short id, API version, last probe
  suspend fun checkNow(): ComputerStatus
}
interface PairingRepository {
  fun search(): Flow<SearchState>                              // ≤ 10 s; Found(list) / NothingFound / NotOnWifi
  suspend fun check(address: HostPort): CheckResult            // identity-only; never sends a credential
  suspend fun pair(target: PairingTarget, code: String, deviceName: String): PairResult
  suspend fun useNewAddress(address: HostPort): CheckResult    // accepted only for the paired server id
  suspend fun forget(): ForgetResult
}
interface ChannelLiveness { fun channelAlive(); fun channelLost() } // job channel → probe state machine

// :core:sync (WP-08)
interface SyncController { val state: StateFlow<SyncState>; fun syncNow(reason: SyncReason) }
interface ClipRepository {
  fun clips(query: ClipQuery): Flow<List<ClipSummary>>
  fun clip(id: JobId): Flow<ClipDetail?>
  fun browserJobs(): Flow<List<ComputerJob>>                   // "Started on your computer"
  fun newOnThisPhone(): Flow<List<ClipSummary>>
  val pendingMassDeletion: Flow<MassDeletionPrompt?>
  suspend fun resolveMassDeletion(remove: Boolean)
  suspend fun setKept(id: JobId, kept: Boolean)
  suspend fun downloadToPhone(id: JobId)                       // "Download to this phone" / "when connected"
  suspend fun removeFromPhone(id: JobId)
  suspend fun downloadVideo(id: JobId)
  suspend fun removeVideo(id: JobId)
  suspend fun hideBrowserJob(id: JobId)
}
interface StorageRepository {
  val usage: Flow<StorageUsage>                                // clips, videos, waiting copies, cache, budget, floor
  suspend fun setBudget(budget: StorageBudget)
  suspend fun removeAllVideos()
}

// :core:requests (WP-09)
interface RequestRepository {
  fun requests(): Flow<List<Request>>
  fun request(id: RequestId): Flow<Request?>
  fun counts(): Flow<RequestCounts>                            // own requests only (F-03)
  suspend fun findDuplicate(input: LinkInput): Duplicate?
  suspend fun addLink(input: LinkInput): AddResult             // stored before returning
  suspend fun addFiles(files: List<FileSource>, options: FileOptions): AddFilesResult
  fun holdSources(ids: Set<RequestId>): AutoCloseable          // the share sheet: its read grant lives until close() (8.3)
  fun intake(id: RequestId): Flow<IntakeProgress>              // copy progress for the share sheet
  suspend fun stopIntake(id: RequestId)
  suspend fun perform(id: RequestId, action: RequestAction)
}
interface LinkParser { fun parse(sharedText: String, subject: String?): ParsedLink? }

// :core:corpus (WP-10)
interface ClipContentRepository {
  suspend fun clipData(id: JobId): ClipData                    // parsed analysis, LRU-cached
  suspend fun media(id: JobId): ClipMedia?                     // local audio (both tracks), video, poster, offset
  fun open(id: JobId): ClipLease                               // defers deletion while a screen shows the clip
  suspend fun compareRows(word: WordRef): List<CompareRow>     // lesson step 2, computed with :core:analysis (revision 3)
}
interface ShortRepository {
  fun shorts(id: JobId): Flow<List<ShortInfo>>
  suspend fun sequence(mode: FeedMode): FeedSequence           // ordered shorts with the mode's spotlight
  suspend fun shortContaining(word: WordRef): ShortInfo?
}
interface OccurrenceRepository {
  fun changeCounts(): Flow<Map<ChangeLabel, ChangeCount>>      // two units per change (spec D-48, 9.4): shorts, occurrences
  // ChangeCount(shorts, occurrences). shorts = the playable shorts with at least one spotlight candidate of that
  // change, i.e. exactly what sequence(FeedMode.Change(label)) plays: Home chips, mode picker, Learn change cards.
  // occurrences = eligible occurrences of that change in playable clips: the phenomenon page only. Absent key = 0.
  suspend fun examples(change: ChangeLabel, limit: Int = 8, onlyClip: JobId? = null): List<Occurrence>
  suspend fun examplesWithSymbol(symbol: String, limit: Int = 6): List<Occurrence>
  suspend fun quizPool(perChange: Int = 80): Map<ChangeLabel, List<Occurrence>>
  suspend fun variants(word: String): List<WordVariant>
  fun totals(): Flow<CorpusTotals>
}

// :core:study (WP-14)
interface ProgressRepository {
  fun seen(): Flow<Set<ShortId>>
  suspend fun recordPlayed(id: ShortId, playedMs: Long, lengthMs: Long)  // seen at 80 % actually played
  fun resume(id: JobId): Flow<ShortId?>
  fun lastFeed(): Flow<FeedPosition?>
  suspend fun saveFeedPosition(position: FeedPosition)
  fun keepWatching(): Flow<KeepWatching?>
  suspend fun markOpened(id: JobId)
}
interface SetRepository {
  fun today(): Flow<StudySet?>
  suspend fun ensureToday(): StudySet?
  suspend fun anotherSet(): StudySet?
  suspend fun advance(setId: Long, position: Int)
  suspend fun answer(setId: Long, position: Int, choice: String): AnswerResult   // choice: the option id (work-plan Appendix A.5)
}
interface PracticeRepository {
  suspend fun newSession(focus: ChangeLabel?, weakSpots: Boolean): PracticeSession
  fun currentSession(): Flow<PracticeSession?>
  suspend fun answer(sessionId: Long, index: Int, choice: String): AnswerResult
  fun accuracy(): Flow<List<ChangeAccuracy>>
  fun changesMet(): Flow<Set<ChangeLabel>>
  suspend fun reset()
}
interface SavedRepository {
  fun saved(): Flow<List<SavedItem>>
  fun isSaved(id: ShortId): Flow<Boolean>
  suspend fun saveShort(id: ShortId, spotlight: WordRef?)
  suspend fun saveWord(word: WordRef)
  suspend fun unsave(itemId: Long)
}

// :core:player (WP-11)
interface SpanPlayer {                                         // lessons, practice, Learn examples, end cards
  val state: StateFlow<SpanPlaybackState>
  suspend fun play(span: ClipSpan, speed: Float = 1f, track: Track = Track.MIX, loop: Boolean = false)
  fun stop()
}
interface AudioArbiter { fun claim(owner: PlaybackOwner); fun release(owner: PlaybackOwner) } // one sound at a time
interface DecoderCapabilities {
  fun canPlay(codec: String, width: Int, height: Int, fps: Double = 30.0): Boolean
  fun summary(): List<DecoderInfo>                             // Connection details and QA (11.7): codec, max size, hardware or software
}

// :core:content (WP-04)
interface DidacticContent {
  fun glossary(key: String): GlossaryEntry?
  fun lesson(change: ChangeLabel): LessonContent?
  fun ipaLayout(): IpaLayout
  fun phone(symbol: String): PhoneArticulation?
  fun search(query: String): List<GlossaryHit>
}
interface ReferenceRepository { val reference: StateFlow<Reference>; suspend fun replace(json: String, apiVersion: Int) }

// :core:datastore (WP-02)
interface SettingsRepository { val settings: StateFlow<UserSettings>; suspend fun update(change: (UserSettings) -> UserSettings) }
interface CoachMarkRepository { fun shouldShow(id: CoachMarkId): Flow<Boolean>; suspend fun dismiss(id: CoachMarkId); suspend fun resetAll() }

// :core:notifications (WP-02)
interface Notifier {
  fun clipReady(clip: JobId, title: String, shorts: Int)
  fun needsAttention(request: RequestId, title: String, site: Site?, error: RequestError)  // text from RequestFailureCopy (19)
  fun transferNotification(kind: TransferKind, progress: TransferProgress): android.app.Notification // in an Android-facing sub-interface
  val permissionState: StateFlow<NotificationPermission>
}
```

The Android-specific interfaces (`Notifier.transferNotification`, the media surface) live in an `android`
sub-package of the owning module rather than in `:core:domain`, so that `:core:domain` stays a JVM module.

Revision 2: the freeze also covers the remote model, the core-to-core SPIs, `FeedPlayer`, `ShadowingRunner` and the
computer-client API (now the JVM module `:core:network-api`); their shapes are in work-plan 4.2 and 4.3 and are as
normative as the listing above.

Revision 3: every type the listings above name without a shape (`ClipData`, `ClipMedia`, `SyncState`, `UserSettings`,
the request, pairing and upload bodies, and the rest) has its fields in work-plan Appendix A, so the freeze is a
transcription, not a design exercise (delivery review D-02). `ClipData` and its parts are drafted by WP-12 and the
`didactic.json` model by WP-04, both handed to WP-02 by day 4, before B1.

Revision 4 (pre-implementation closure, 25.4): the listing above and work-plan 4.2 and Appendix A agree on these
additive fields, types and one method, all computed from existing columns or state except `request.needed_bytes`, the
one new column (4.2):
- requests: `Request.waiting` (`WaitingInfo(held, attempts, nextAttemptAt, needsAppOpen)`, 8.5), `timeline`,
  `rerunPending`, `updatedAt`; `RequestProgress.segment`/`segments` ("phrase 3 of 10"); `RequestCounts.analyzing`/
  `inLine` (the status card's "1 analyzing", "1 in line"); `RequestError.neededBytes` ("Free {N} MB", 8.3); the
  phone-side codes `copy_no_space`, `copy_unreadable`, `copy_interrupted`, `copy_missing` (8.3);
  `RequestRepository.holdSources(ids)` (the open share sheet keeps its sources readable, 8.3);
- sync and clips: `SyncState.Active.doneBytes`/`totalBytes` ("34 MB of 61 MB"); `ClipDetail.artifacts` with
  `ArtifactProgress` and `ArtifactLocalState` (the `artifact` table's `local_state`, 4.2);
- connectivity: `ConnectionDiagnostics.phoneAddress` (this phone's IPv4 on the bound Wi-Fi, from
  `NetworkSnapshot.ipv4`; on screen only, never in "Copy diagnostics", 20.3); `SearchState.Found.conflict` (6.1);
- corpus: `ChangeCount(shorts, occurrences)` (above, 9.4);
- study: `StudySetItem.Short.done` (the learner has moved past it in this set; Home's stations, spec F-03 item 3);
- playback: `FeedPlaybackState.trackFallback` (`TrackFallback`: `DIALOGUE_NOT_ON_PHONE`, `MIX_NOT_ON_PHONE`,
  `NO_DIALOGUE_TRACK`, or null; 11.1);
- messages: `UserMessageKind.CANCEL_UNDONE` carries the request title as its one argument.

Ownership moves of the same pass (work-plan 3.2, 4.5): the domain-aware `ConnectionStatusCard` (with
`ProbeOutcomeCopy`, WP-15) and `ShareBridge` + `ConnectionLine` (WP-23) live in `:core:ui` `component/` beside the
other shared components; the visual-only `PosterArt` (poster, poster fallback or audio art, 11.6) and `MarksLegend`
are WP-03's in `:core:ui`; `AudioOnlyVisual` stays in `:feature:watch`. The app shell (WP-02, `:app`) hosts
`UserMessages` and the "Connect again" banner (3.1) and applies the in-app appearance through a WP-03 helper (17).

Revision 5 (CR-01, v1-complete; the exact code is work-plan 4.7, change requests CR-01-A1 and CR-01-A2). Every
addition is additive in the sense of work-plan 2.2: new types, new interface methods **with default bodies** (so every
implementation and fake of B1 still compiles), new fields with defaults. Two deliberate choices keep consumers'
exhaustive `when`s compiling: deletion actions get their own enum (`DeletionAction`) instead of new `RequestAction`
members, and a waiting deletion is a new field (`ClipSummary.deletion`) instead of a new `ClipAvailability` subtype.
The one enum that grows, `UserMessageKind` (three members), has a single exhaustive consumer, the shell's message host,
which the integrator updates in the same change.
- Deletion from your computer (8.8): `ComputerDeletion`, `DeletionState`, `DeletionWait`, `DeletionAction`,
  `DeletionOffer`, `NotOfferedReason`, `DeleteResult`; `RequestRepository.deletions()`, `deletionOffer(id)`,
  `deleteFromComputer(id)`, `performDeletion(id, action)`; `ClipSummary.deletion`; `SendReport.deletedOnComputer`;
  the SPI `PendingDeletions` (`:core:requests` → `:core:sync`, `:core:study`); `UserMessageKind` gains
  `CLIP_DELETED_FROM_COMPUTER`, `CLIP_REMOVED_FROM_COMPUTER_LIST`, `DELETION_UNDONE` (each with the title).
- Shared progress (7.8, 10): `SharingState`; `PracticeRepository.sharing()`, `SavedRepository.sharing()`; the
  exchange bodies of package `…core.domain.model.share` (`PracticeTally`, `PracticeBaseline`, `PracticeReset`,
  `PracticeAnswer`, `PracticeOutbox`, `PracticeShareAnswer`, `SavedEntry`, `SavedOutbox`, `SavedShareAnswer`); the SPI
  `SharedProgressLedger` (`:core:study` → `:core:sync`).
- Both: `Capabilities.deleteFinished` and `Capabilities.sharedProgress` (server-contract 3.4), which the pairing store
  keeps under two new keys (4.5); three `ComputerClient` methods in `:core:network-api` (`deleteFinished`,
  `syncPractice`, `syncSaved`) whose default bodies answer like a server without the route
  (`Refused(404, "unknown_route")`); `SyncController.exclusive(block)`, whose default body only runs the block, so
  "Forget this computer" can wait for a pass in flight (6.3).
- Review fixes (25.6, 25.7): `ComputerDeletion.actions` (the one authority on what a deletion offers, like
  `Request.actions`) and `DeletionWait.DIFFERENT_COMPUTER` (a deletion the computer said was made for a different
  computer waits, spec D-62); `QuotedTitle` in `:core:ui` `component/`, the one function that makes the quoted title
  of design 9.26 (19), created by the integrator in CR-01-A2 because the shell's message host uses it there.

---------------------------------------------------------------------------------------------------------------

## 4. Persistence

### 4.1 Two databases (AD-09)

Backup works per file, and the spec demands that backups contain learning progress, saved items and settings only,
never the queue, file copies, the computer's address, the credential or media (11.1). One database cannot satisfy that
split, so there are two:

| Database | File | Journal | Backed up | Contents |
|---|---|---|---|---|
| `LibraryDatabase` | `library.db` | WAL | no | catalogue mirror, artifacts and local files, derived shorts and occurrences, device queue and outbox (in v1-complete also the waiting deletions of finished analyses, 8.8), sync metadata |
| `LearningDatabase` | `learning.db` | TRUNCATE (single file, so Auto Backup copies a consistent file) | yes | seen state, positions, saved items, practice stats, changes met, study sets, practice session, the history's `learning_id`; in v1-complete also the bookkeeping of shared progress (answers and Saved changes not shared yet, a waiting reset, what the computer last confirmed, 7.8), so it is backed up in the same consistent copy as the progress it describes (spec 11.1) |

Cross-database reads (eviction needs "last watched", the Mix needs both) are joined in Kotlin inside repositories.
Learning rows reference clips by job id only (+ segment + word), so a restore on a new phone re-attaches them when the
clips sync again from the same computer.

### 4.2 `LibraryDatabase` schema, version 1

Column types: TEXT ids, INTEGER epoch milliseconds (UTC) for instants, INTEGER 0/1 for booleans, enums stored by
name. Every table has the indices listed; foreign keys are declared where the lifecycle is shared.

**`clip`** (catalogue mirror; one row per job any listing of the paired server reported, plus clips kept from a
forgotten computer or a previous library)

| Column | Notes |
|---|---|
| `job_id` TEXT PK | server job id (the only unique key, [AD 2.1]) |
| `server_id`, `library_id` TEXT NOT NULL | where it came from (7.9) |
| `origin` TEXT NOT NULL | `ACTIVE`, `FORGOTTEN_COMPUTER`, `OTHER_LIBRARY` |
| `revision` TEXT NOT NULL | listing revision |
| `status` TEXT NOT NULL, `stage` TEXT, `percent` INTEGER, `queue_position` INTEGER | job state (S-13) |
| `title` TEXT NOT NULL, `source_kind` TEXT, `source_url` TEXT, `site` TEXT | |
| `submitted_by_device` TEXT | device id, or NULL for browser/CLI jobs |
| `created_at`, `started_at`, `finished_at` INTEGER | |
| `error_code`, `error_message` TEXT | |
| `duration_ms` INTEGER, `segment_count`, `word_count` INTEGER, `change_counts_json` TEXT | summary |
| `rules_version` INTEGER, `phone_engine` TEXT, `has_dialogue` INTEGER, `audio_only_job` INTEGER | |
| `media_kind` TEXT (`VIDEO`, `AUDIO`, `NONE`), `video_codec` TEXT, `video_width`, `video_height`, `video_rotation` INTEGER, `video_offset_ms` INTEGER | from the listing's `media` |
| `missing_listings` INTEGER NOT NULL DEFAULT 0 | consecutive complete listings without this job (7.9) |
| `deleted_on_computer` INTEGER NOT NULL DEFAULT 0 | removal deferred while open |
| `wanted` INTEGER, `removed_by_learner` INTEGER, `kept` INTEGER, `hidden` INTEGER | learner choices about this phone's copy (not progress, so not backed up) |
| `video_wanted` INTEGER | "Download the video" when automatic video download is off |
| `indexed_revision` TEXT, `index_rule_version` INTEGER | derived index state (section 9) |
| `first_seen_at`, `on_phone_at` INTEGER | "New on this phone" |

Indices: `(server_id, library_id)`, `(status)`, `(created_at, job_id)`, `(origin)`.

**`artifact`** (one row per served file of a clip; PK `(job_id, name)`, FK `job_id` → `clip` ON DELETE CASCADE)

| Column | Notes |
|---|---|
| `name` TEXT | `analysis`, `audio_mix`, `audio_dialogue`, `original`; v1-complete: `analysis_compact`, `audio_mix_opus`, `audio_dialogue_opus`, `poster`, `video_phone` (server-contract 6.4) |
| `role` TEXT | `CLIP_DATA`, `AUDIO_MIX`, `AUDIO_DIALOGUE`, `VIDEO`, `POSTER` |
| `remote_status` TEXT | `READY`, `PREPARING`, `FAILED`, `UNAVAILABLE` |
| `remote_bytes` INTEGER, `remote_sha256` TEXT, `remote_type` TEXT, `remote_url` TEXT | |
| `local_state` TEXT | `ABSENT`, `QUEUED`, `DOWNLOADING`, `PAUSED`, `READY`, `FAILED` |
| `local_file` TEXT | path relative to `filesDir` (4.6) |
| `local_sha256` TEXT, `local_bytes` INTEGER | the verified copy in use |
| `part_bytes` INTEGER, `part_revision` TEXT, `etag` TEXT | resume state |
| `verify_failures` INTEGER, `pause_reason` TEXT, `last_error` TEXT, `updated_at` INTEGER | |

Indices: `(local_state)`, `(role, local_state)`.

**`short`** (derived, section 9; PK `(job_id, first_segment)`, FK → `clip` CASCADE): `rule_version`, `ordinal`,
`last_segment`, `start_ms`, `end_ms` (phrase bounds), `play_start_ms`, `play_end_ms`, `default_spot_segment`,
`default_spot_word`, `default_spot_change`, `default_spot_advice`, `default_spot_crosses`, `marked_words`. Index
`(job_id, ordinal)`.

**`occurrence`** (derived; one row per word × change; PK `(job_id, segment, word, change)`, FK → `clip` CASCADE):
`short_first_segment`, `word_text`, `word_key` (lower-case letters and apostrophes, for "one per word"), `dict_ipa`,
`realized_ipa`, `start_ms`, `end_ms`, `diff_cost` REAL, `low_confidence`, `eligible` (not low confidence, ≥ 60 ms,
has a heard form), `crosses_boundary`, `next_word_text`, `advice`, `family`. Indices `(change, eligible, diff_cost)`,
`(job_id, short_first_segment)`, `(word_key)`.

**`request`** (device queue, section 8; PK `id`)

| Column | Notes |
|---|---|
| `id` TEXT PK | client UUID, the first idempotency key |
| `seq` INTEGER NOT NULL UNIQUE | creation order (max + 1 inside the insert transaction) |
| `kind` TEXT | `LINK`, `FILE` |
| `state` TEXT, `state_reason` TEXT | 8.4; for `COPY_FAILED` and `COPY_MISSING` the phone-side code (`copy_no_space`, `copy_unreadable`, `copy_interrupted`, `copy_missing`, 8.3) |
| `held` INTEGER | "Stop sending" keeps it waiting until "Send now" |
| `created_at`, `updated_at` INTEGER | |
| `link_shared` TEXT, `link_sent` TEXT, `link_key` TEXT, `site` TEXT, `title_hint` TEXT | 8.2 |
| `file_name`, `file_mime` TEXT, `file_bytes`, `file_duration_ms` INTEGER, `file_sha256` TEXT | file metadata; SHA-256 computed while copying |
| `copy_file` TEXT, `copy_bytes` INTEGER, `copy_complete` INTEGER, `source_uri` TEXT, `source_persisted` INTEGER, `audio_extracted` INTEGER, `whole_file_fallback` INTEGER | 8.3; `copy_file` is always `requests/<id>/source.<ext>` (never a name from the sharing app) |
| `audio_only`, `separate_dialogue` INTEGER | options |
| `target_server_id` TEXT | NULL until a computer is paired |
| `attempts` INTEGER, `next_attempt_at` INTEGER, `last_error_code`, `last_error_message` TEXT, `needed_bytes` INTEGER | retry state; `needed_bytes`: the space a refused copy needs ("Free {N} MB", 8.3) |
| `job_id` TEXT, `accepted_at` INTEGER, `rerun_count` INTEGER, `auto_rerun_used` INTEGER | |
| `upload_offset` INTEGER | bytes the computer confirmed (v1-complete) |
| `sent_at`, `in_line_at`, `analyzing_at`, `ready_at`, `on_phone_at` INTEGER | timeline of the request detail |
| `notified_ready`, `notified_attention` INTEGER | notify once |

Indices: `(state)`, `(job_id)` UNIQUE, `(link_key)`, `(file_sha256)`, `(seq)`.

**`request_op`** (outbox of commands that must reach the computer; PK `id`): `id` TEXT (UUID; for `RERUN` it is the
re-run's idempotency key), `kind` (`CANCEL`, `REMOVE_JOB`, `RERUN`; "Forget this computer" is never queued, 6.3),
`request_id` TEXT NULL (no foreign key: a `REMOVE_JOB` outlives its request row), `job_id` TEXT, `audio_only` INTEGER NULL, `created_at`, `attempts`,
`next_attempt_at`, `state` (`PENDING`, `DONE`, `FAILED`), `last_error_code`. Index `(state, created_at)`.
**Version 2 (v1-complete, CR-01-A2, 8.8)**: kind `DELETE_FINISHED` and state `SENDING` (stored values, the columns are
TEXT); new nullable columns `server_id`, `library_id`, `device_id` (what the deletion is bound to), `title` (for its
messages after the clip row is gone), `imported` (INTEGER; the confirmation and message variant) and `maybe_sent`
(INTEGER; set once an attempt's outcome is unknown, so "Undo" is no longer offered, 8.8). Other kinds leave them NULL.

**`sync_meta`** (key/value): last listing time, last complete listing's server id and library id, reference API
version cached, pending mass-deletion prompt, last reindex rule version.

### 4.3 `LearningDatabase` schema, version 1

| Table | Key | Columns |
|---|---|---|
| `short_seen` | `(job_id, first_segment)` | `first_seen_at`, `last_seen_at`, `seen_count` |
| `clip_progress` | `job_id` | `resume_first_segment`, `resume_position_ms`, `last_opened_at`, `last_watched_at`, `excluded_from_mix` |
| `feed_position` | `slot` (`WATCH_LAST`, `KEEP_WATCHING`) | `mode_json`, `job_id`, `first_segment`, `position_ms`, `updated_at` |
| `saved_item` | `id` autoincrement; UNIQUE `(kind, job_id, segment, word)` | `kind` (`SHORT`, `WORD`), `job_id`, `segment`, `word`, `short_first_segment`, `change`, `saved_at`, `last_watched_at` |
| `practice_stat` | `change` | `seen`, `correct` (the web's shape) |
| `change_met` | `change` | `first_met_at` |
| `study_set` | `id` autoincrement | `kind` (`TODAY`, `EXTRA`), `local_date`, `seed`, `rule_version`, `created_at`, `cursor`, `completed_at`, `answered`, `right` |
| `study_set_item` | `(set_id, position)`, FK → `study_set` CASCADE | `kind` (`SHORT`, `QUESTION`), `job_id`, `first_segment`, `spot_segment`, `spot_word`, `spot_change`, `saved_tag`, `question_json`, `choice`, `correct`, `dropped` |
| `practice_session` | `id` autoincrement | `focus`, `weak_spots`, `seed`, `questions_json`, `index`, `answers_json`, `created_at`, `finished_at` |
| `learning_meta` (v1, CR-01-A1) | `key` | `value`; one row `learning_id`: a random UUID written when the database is created (`DatabaseModule`'s `onCreate` callback, and `INSERT OR IGNORE` in `onOpen`), never changed; it names this learning history on every computer it is shared with (7.8) |

Anchors are always job + segment (+ word) (F-06 item 5): a later shorts rule maps a stored first segment to the short
whose segment range contains it.

**Version 2 (v1-complete, CR-01-A2; 7.8, 10)**, all in `learning.db`, so the bookkeeping is backed up with the progress
it describes:

| Table | Key | Columns |
|---|---|---|
| `practice_answer` | `id` (UUID text) | `change`, `correct`, `answered_at` (phone clock, ms), `computer_at` (computer clock, unix s, from the clock offset known when it was given), `sent` (0 until the computer confirmed it, or a baseline covered it); index `(sent, computer_at)`. Written in the same transaction as `practice_stat`, for every answer of the Practice tab and of a set; sent rows are kept 30 days (7.8) |
| `own_stat` | `change` | `seen`, `correct`: this phone's own answers since the latest reset it knows of, never rewritten by the mirror; the baseline of a first share with another computer (7.8, review fix C-63) |
| `share_state` | `id` = 1 (one row) | `server_id` (the computer this history is shared with), `offset_s` (last known clock offset, for answers given while not connected), `baseline_json` + `baseline_at` (the first-share baseline until confirmed, then NULL), `reset_id`, `reset_at` (the computer's current reset as last seen), `pending_reset_id`, `pending_reset_at` (a reset pressed here, not confirmed yet), `practice_synced_at`, `saved_library_id`, `saved_cursor` (the opaque Saved cursor of server-contract 6.12, stored as received, never parsed), `saved_synced_at` |
| `saved_change` | `key` (`<kind>:<job id>:<segment>:<word or ->`, server-contract 3.2) | `kind`, `job_id`, `segment`, `word`, `short_first_segment`, `change`, `saved`, `at` (computer clock, s), `sent` (0 waiting, 1 confirmed, 2 not shared: the computer's list was full, server-contract 6.12 `not_shared`): the Saved outbox, written in the same transaction as `saved_item` |

### 4.4 Migration policy (AD-10)

- Both databases set `exportSchema = true`; schemas are committed under `core/database/schemas/` and added to the test
  assets.
- Version 1 of each schema is frozen at the wave-1 contract freeze (WP-02 M2). Until the first build is installed on
  the test phone (WP-25), entity change requests are folded into version 1 by the integrator (the exported schema is
  regenerated and every DAO test re-run), so wave 2 does not produce a chain of migrations for builds nobody installed
  (delivery review D-25). From the first install on, **every** entity change bumps the version and ships an
  `@AutoMigration` (with an `AutoMigrationSpec` when renaming or deleting) or a manual `Migration`, plus a
  `MigrationTestHelper` test covering N-1 → N and 1 → latest with data, so the build on the test phone can always be
  updated in place (X-AC7).
- `fallbackToDestructiveMigration*` is forbidden (hygiene check). A failing migration is a release blocker.
- **CR-01** applies this policy in two steps. **CR-01-A1** folds `learning_meta` into version 1 now (no build is
  installed yet): the `learning_id` must exist in every backup of every release, including v1-core's, because a phone
  restored from a backup taken before its first share must name its baseline exactly as the original did, or the
  computer would count that history twice (spec F-17-AC11, 7.8). Nothing in v1-core reads it. **CR-01-A2** is the
  v1-complete schema, **version 2** of both databases (`request_op`'s new columns in `library.db`; `practice_answer`,
  `own_stat`, `share_state`, `saved_change` in `learning.db`), shipped as `@AutoMigration(from = 1, to = 2)` (new tables
  and nullable columns only, so no spec class is needed) with `MigrationTestHelper` tests of 1 → 2 with data. It waits
  for v1-complete because v1-core's build will have been installed on the test phone by then (WP-25), and because those
  tables belong to code that does not exist before v1-complete; folding them into version 1 would ship unused tables to
  v1-core QA.
- Derived tables (`short`, `occurrence`) are rebuilt by the indexer when `index_rule_version` changes; the rule
  version is not a schema version.
- Only the integrator changes entities (change request from the area owner); area owners change their DAO files.

### 4.5 DataStore (Preferences DataStore 1.2.1)

| File | Backed up | Contents |
|---|---|---|
| `datastore/settings.preferences_pb` | yes | every F-22 setting (appearance, colors, patterns, motion, default speed, play each short, end of short, captions, Listen first, track, questions after set, lesson guidance, automatic video download, storage budget, notification categories), welcome done, coach marks seen, notification permission asked |
| `datastore/pairing.preferences_pb` | no | server id, server name, last working address, device id, device name, paired at, last seen, last API version, capabilities, last library id, last probe outcome, the moving average of measured upload throughput to this computer (8.5), the **encrypted** credential (ciphertext + IV) |

Typed wrappers (`SettingsRepository`, `CoachMarkRepository`, `PairingStore`) are the only access; keys are private.

v1-complete (CR-01) adds **no DataStore file**, on purpose: the shared-progress bookkeeping lives in `learning.db`
(4.3), because Auto Backup must copy it in the same consistent file as the progress it describes (a second file could
be restored at another moment and count answers twice, spec 11.1), and waiting deletions live in `library.db` with
the other outbox operations (8.8), which are never backed up. It adds **two pairing-store keys**,
`caps_delete_finished` and `caps_shared_progress` (Boolean). The pairing store is WP-02's (M3,
`DataStorePairingStore`): it saves the cached `Capabilities` field by field under its own keys, so a new field of the
type is not stored until the store names it. CR-01-A2 (work-plan 4.7, the integrator, because `:core:datastore` has
no v1-complete owner) adds both keys to `readCapabilities()` and `writeCapabilities()`, an absent key reading `false`
(so a value cached by a v1-core build reads `false` until the next `health`), with a round-trip test. Nothing changes
in WP-02 M3 now. Version 2 adds its own pairing-store keys (26.12).

### 4.6 Files on disk

```
filesDir/
  clips/<jobId>/<name>.<sha8>.<ext>         verified artifacts in use (never in shared storage)
  clips/<jobId>/.<name>.<revision>.part     resumable partial download
  requests/<requestId>/source.<ext>         device-queue file copies (complete); the sharing app's name is kept only in Room
  requests/<requestId>/.copy.part           copy in progress
  datastore/...                             DataStore files (4.5)
cacheDir/
  takes/<uuid>.wav                          v1-complete recordings, deleted when leaving the word and at start
  posters/                                  generated poster fallbacks
```

**No path segment comes from outside the app unchecked** (security review M-3, M-4). `jobId`, artifact `name`,
`revision`, `sha256` and the extension come from the computer; they are validated in `:core:network`'s DTO mappers
before any domain object exists (5.4), and the extension is chosen from a closed map keyed by artifact name, never
derived from `content_type`. `requestId` is the app's own UUID and `<ext>` comes from the closed suffix list of 8.3.
A resolved path is additionally checked to stay under `filesDir/clips` or `filesDir/requests` (canonical path prefix)
before it is opened.

At every start, `StartupCheck` (`:core:sync`, running the `StartupTask` set, including the one from `:core:requests`)
deletes orphaned `.part` files whose row is gone, interrupted shared-file copies (8.3), stale takes and cache files,
and recomputes storage usage. It acts **only on rows and files last updated before the current process started**
(`Application.onCreate` records the instant), so a share that cold-starts the app, or a download worker started in
the same process, is never mistaken for an interrupted one; file intake, transfers and the first sync pass await
`StartupGate.awaitDone()` (work-plan 4.2) before they touch the queue or the clip files (Android review M6).

---------------------------------------------------------------------------------------------------------------

## 5. Networking (AD-11, AD-12)

### 5.1 One client, bound to the home network

- `:core:network` provides one `OkHttpClient` (`@ComputerHttp`). Retrofit, the transfer code and the WebSocket all use
  it (or `newBuilder()` derivatives that share its pool, guard and dispatcher). Media3 and Coil read local files only
  (D-12), so they need no network.
- **Network binding.** `ComputerNetworkBinder` follows the current Wi-Fi or Ethernet `Network` (from
  `ConnectivityManager.NetworkCallback`, maintained by `:core:connectivity`). An `OkHttpClient`'s socket factory is
  fixed when the client is built, so the client gets a **delegating `SocketFactory`** that binds each new socket to the
  network current at that moment (`network.bindSocket(socket)`); on every network change the binder evicts the
  connection pool (`connectionPool.evictAll()`) and cancels the job channel, so no pooled connection stays on the old
  network (Android review M11). Traffic to the computer therefore goes over the home network even when Android
  considers it "without internet" and makes mobile data the default, and the app never uses mobile data for its own
  traffic (NFR-05). With no Wi-Fi/Ethernet network, calls fail immediately with `ComputerError.NotOnWifi`. A bind that
  fails with `EPERM` (a VPN that does not allow bypass) fails the call with its own probe outcome, `VpnBlocks`, which
  Connection details shows as the "Is a VPN on?" line of the spec's checklist.
- **No DNS.** Addresses are IPv4 literals (from discovery or typed); the client's `Dns` implementation refuses every
  lookup, so a hostname can never be resolved and no DNS traffic leaves the phone.
- **Timeouts** (per call type, all derived from one client):

| Call | Connect | Read | Write | Call timeout |
|---|---|---|---|---|
| Probe (`hello`, `health`) | 3 s | 3 s | 3 s | 3 s (10 s while the computer was last known to be analyzing, spec 7.5) |
| API calls (create, cancel, re-run, listing, pairing) | 5 s | 20 s | 20 s | 30 s |
| Artifact downloads | 5 s | 30 s per read | — | none (streamed, resumable) |
| Uploads | 5 s | 60 s | 60 s per write | none |
| Job channel | 5 s | — | — | ping interval 7 s: a missing pong fails the socket within 14 s (≤ 15 s, spec 7.5) |

### 5.2 Cleartext confinement (spec 11.6, X-AC6)

- `res/xml/network_security_config.xml` permits cleartext in its `base-config` and names no address (an address known
  only at runtime cannot be named at build time and must never be committed). A comment in the file points to this
  section.
- `CleartextGuard` is the first application interceptor and also an `EventListener` check before connect. A request is
  allowed only if its scheme is `http` or `ws`, its host is an IPv4 literal, and `host:port` equals either the paired
  computer's current address (`AllowedPeers.current`) or, during discovery checks and pairing, the single candidate
  address being checked (`AllowedPeers.candidate`, which must be in a private or link-local range: 10/8,
  172.16/12, 192.168/16, 169.254/16). Every other request fails with `ComputerError.Guarded` before a byte is sent.
  The address policy is a Hilt-provided `AddressPolicy`, which tests replace to allow the loopback fake computer.
- Discovery (UDP) is not an OkHttp call: `DiscoveryTransport` sends only to broadcast addresses of the bound network
  and accepts replies only from private or link-local sources.
- No other networking API is used anywhere (no `HttpURLConnection`, no `WebView`); the hygiene check fails on
  `OkHttpClient(` or `OkHttpClient.Builder(` outside `:core:network`.
- `android/SECURITY.md` and `android/README.md` say exactly this, including that the platform setting alone would
  allow cleartext.

### 5.3 Identity proof first, then signed requests (AD-12)

The server id is public: every discovery reply and every `hello` carries it. So it is never treated as proof of who
answers (security review H-1). The phone holds a per-device key `K = sha256(token)` (32 bytes, derived once from the
token the pairing answer carried; server-contract 3.2) and uses it two ways:

- **Identity proof.** Every session with an address starts with
  `GET /api/phone/hello?device_id=<id>&challenge=<32 random hex>` (no other header). The address becomes verified in
  `VerifiedPeers` only if the answer's `proof` equals
  `HMAC-SHA256(K, "pt-hello-v1|" + server_id + "|" + device_id + "|" + challenge)` for the paired server id; a valid
  proof with `revoked: true` gives `ConnectAgain`. A missing or wrong proof, or another server id, gives
  `DifferentComputer` for that address: no request, file or recording is ever sent there and nothing is deleted
  because of it (spec 7.4, QT-11). Verification is reset on every network change and address change.
- **Signed requests.** `SigningInterceptor` (replacing a bearer interceptor) adds
  `Authorization: PT1 device=…, ts=…, nonce=…, mac=…` (server-contract 4.3) only to requests whose `host:port` is
  verified. `ts` is the phone's clock plus the offset learned from `hello`'s `server_time`; a `401 clock_skew` updates
  the offset from its `server_time` and retries once. The token itself never leaves the phone after pairing, so a
  listener on the Wi-Fi captures no reusable credential, and a captured request cannot be replayed (security review
  M-1). The key is decrypted in memory only while computing a MAC (20.3).

Before pairing there is no key, so `hello` at a candidate only reads identity, phone-access state and version; first
pairing relies on the operator's code and on the checks of 6.1 and 6.3 (residual risk in server-contract 4.8).

### 5.4 API client and DTOs

- `ComputerApi` (Retrofit 3, kotlinx-serialization converter) mirrors `server-contract.md` section 6 one method per
  endpoint; DTO classes live in `core/network/.../dto` and are `internal` to the module; the module exposes domain
  results only (the `:core:network-api` types).
- JSON: `Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }` so additive server
  changes never break the app (NFR-08). Enum-valued fields (`status`, `stage`, `role`, artifact `status`, `kind`) are
  decoded leniently: every such DTO field has a default, and an unknown value maps to an `UNKNOWN` member (or the job
  code to `processing_failed`), so a future value never fails a whole listing (delivery review D-29).
- **Validation before mapping** (security review M-3): a mapper rejects the whole object as `Malformed` when a job id
  does not match `^\d{8}-\d{6}-[0-9a-f]{4}$`, an artifact name is outside the closed table of server-contract 6.4.1,
  `revision` or `sha256` is not lower-case hex of the right length (16 and 64), or `url` is not exactly
  `/api/phone/jobs/{id}/artifacts/{name}` for that job and name. Response bodies are read with a cap: 16 MiB for a
  listing, 1 MiB for the Saved exchange (v1-complete, server-contract 6.12), 64 KiB for other JSON answers; the
  shared-progress mappers also reject ids that are not canonical UUIDs, `at` values before 2020, negative or
  inconsistent tallies or tallies above `Int.MAX_VALUE` (the computer clamps its totals to it, server-contract 6.12),
  Saved entries whose job id fails the pattern above or whose `segment`, `word` or `short_first_segment` is above
  1,000,000; artifact sizes are capped by role before
  a download starts (clip data 32 MiB, audio 1 GiB, video 4 GiB; a larger artifact is treated as unavailable), and the
  storage budget and the free-space floor apply as always (7.5), so a hostile or broken listing cannot exhaust memory or
  disk.
- **Body hash** (v1-complete, CR-01; server-contract 4.3): `deleteFinished`, `syncPractice` and `syncSaved` serialize
  their body to bytes first, add `body_sha256=<64 lower-case hex of SHA-256 of those bytes>` as the last query
  parameter, and send exactly those bytes (`toRequestBody` of the array, never a re-serialization), so the PT1
  signature, which covers the query, covers the body too. A `MockWebServer` test checks that the recorded body hashes
  to the recorded query value for each of the three calls.
- Errors: every non-2xx answer is parsed as the error envelope `{detail, code}` (with the optional `job`, `offset`,
  `server_time` and the `Retry-After` header carried in `ApiResult.Refused`, work-plan 4.3) and mapped to
  `ComputerError` (section 19); 422 validation errors whose `detail` is a list are accepted as well.
- Version negotiation: the app requires `api_version >= 6` (`MIN_SERVER_API`); capabilities in `health` switch the
  v1-complete paths (renditions, resumable uploads, record compare, phone video, and from CR-01 `delete_finished` and
  `shared_progress`; an absent flag reads as `false`). `min_app_version` greater than the
  app's `versionCode` gives `AppTooOld`. A `404` on `hello` makes the client call `legacyHealth` (`GET /api/health` at
  the same candidate, no credential): an `api_version` there means `ComputerTooOld`, anything else "not PhonoTrainer"
  (server-contract 6.2, delivery review D-15).

### 5.5 Job channel

`JobChannelClient` opens `ws://<address>/api/phone/events` with a signed upgrade request while the app is in the
foreground and the status is `Connected`. Messages (`hello`, `snapshot`, `job`, `deleted`; lean, without artifacts,
revision 2) are applied idempotently by the sync engine (7.6). OkHttp's WebSocket ping (7 s) proves liveness; a failure
calls `ChannelLiveness.channelLost()`, which moves the status to `Checking` and triggers a re-probe (spec 7.5).
Reconnection uses exponential backoff 1 s → 10 s and stops when the app leaves the foreground or the network changes
(5.1).

---------------------------------------------------------------------------------------------------------------

## 6. Connectivity, discovery and pairing (`:core:connectivity`)

### 6.1 Discovery (AD-13, spec 7.3, S-03)

- Fixed port **45317/udp** (product constant, the same in `phonotrainer/phone/discovery.py` and
  `DiscoveryProtocol.PORT`). WP-01 confirms it is free on the development computer's `/etc/services`; if the IANA
  registry or the owner's network shows a conflict, both constants change together before the first release.
- `DiscoveryClient.search()`: on the bound Wi-Fi/Ethernet network, open a `DatagramSocket` on an ephemeral port
  (`network.bindSocket(socket)`, `broadcast = true`), send the probe datagram (server-contract 5) to
  `255.255.255.255` **and** to the directed broadcast of the Wi-Fi interface (computed from `LinkProperties`
  address + prefix), at t = 0 s, 1 s and 3 s (UDP loss), and collect unicast replies until 10 s. Replies are matched by
  the probe's nonce and kept as one entry per **(server id, source address)**, and each one is confirmed with an HTTP
  `hello` at the reply's source address and announced port before it is listed. No multicast lock and no extra
  permission are needed (the phone never receives broadcast). No discovery runs in the background (NFR-04).
- **Conflicts are shown, never merged** (security review H-1, M-2). The server id and the name are public, so a second
  source answering with the same server id, or with the same name, is a conflict:
  - while paired (re-discovery), only an address whose `hello` proof verifies is accepted (5.3); the others are
    ignored, and if none verifies the status is `DifferentComputer` for that network;
  - before pairing, every answer is its own row and **none is preselected** (the spec preselects only when exactly
    one computer is found and nothing conflicts, F-02 behaviour 3), so the learner must choose.
    `SearchState.Found.conflict` is true when two answers share a name or one server id answers from two addresses;
    the Connect screen (WP-13) then shows the spec's note above the list, "Two computers answered with the same name.
    If you aren't sure which one is yours, enter the address shown on your computer instead." with "Enter the address
    instead" (spec D-39, F-02-AC10).
- Manual entry accepts `a.b.c.d:port` (IPv4, private or link-local) and checks it with `hello`.
- The computer's terminal prints "Paired: … (from <address>)" (server-contract 2.2). Connection details shows "This
  phone on this Wi-Fi: {address}" (spec D-39, F-22-AC4) from `ConnectionDiagnostics.phoneAddress`, which
  `:core:connectivity` takes from the bound network's `LinkProperties` (`NetworkSnapshot.ipv4`), so the operator can
  compare the two. The phone's address is shown on screen only: "Copy diagnostics", logs and QA logs leave it out
  (20.3, 20.4, 21.7).

### 6.2 Probe state machine (spec 7.5, SC-10)

`ComputerStatusRepository` is a single state machine driven by inputs: network changes (`NetworkMonitor`), probe
results, job-channel liveness, pairing changes and the clock. Implementation is a pure reducer
(`reduce(state, input, now) -> (state, effects)`) in `:core:connectivity` with JVM tests over a `TestClock`, plus an
effect runner. Rules:

1. No Wi-Fi/Ethernet → `Offline` (no network at all) or `NotOnWifi` (mobile data only); no probes.
2. Network change to Wi-Fi/Ethernet → `Looking`; probe the stored address first, then run discovery (re-discovery,
   spec 7.3 item 5); a discovered address is accepted only if `hello`'s identity proof verifies for the paired server
   id (5.3) and the signed `health` is accepted; the change is recorded for Connection details. (Spec 7.3 item 5 and
   7.4: the address "proves it is the paired computer", D-37.)
3. Probe = `hello` with a challenge (identity proof) → `health` (signed). A valid proof, an accepted signature and a
   compatible version → `Connected` (with `analyzing`, `inLine`, `lowOnSpace` from `health`).
4. While `Connected`, a failed probe or a lost channel → `Checking` at once, re-probe after 5 s; a second failure →
   `NotReachable(lastSeen)`, a success → `Connected`. `Checking` claims nothing.
5. 401 `credential_rejected`, or a valid proof with `revoked: true` → `ConnectAgain` (queue paused, background calls
   stop until paired again); server id mismatch, or a missing or wrong proof → `DifferentComputer`; `api_version < 6`
   or a 404 on `hello` followed by `legacyHealth` showing an older version → `ComputerTooOld`; `min_app_version` above
   the app → `AppTooOld`; `hello` with `phone_access: false`, or `403 lan_forbidden` (phone access suspended because
   the computer is on a network its operator has not approved, server-contract 2.1) → `NotReachable` with the probe
   outcome `ProbeOutcome.PhoneAccessOff`, whose words are, wherever a last probe is shown, "Your computer answered,
   but phone access is off there or not approved for this network." (spec 7.5, D-42, F-02-AC12; one mapping,
   `ProbeOutcomeCopy`, next to `ConnectionStatusCard` in `:core:ui` `component/`, WP-15); `401 clock_skew` → offset
   updated and the probe repeated once; a bind failure under a VPN → `NotReachable` with the outcome `VpnBlocks`
   (5.1). A typed address that answers this way gives `CheckResult.PhoneAccessOff` ("That address answered, but phone
   access isn't on there for this network. …", F-02 States).
6. Schedule: on app start and resume; on every network change to Wi-Fi/Ethernet; every 30 s in the foreground while
   anything is pending (waiting requests, requests on the computer, wanted downloads), every 2 min otherwise; before
   each queued send; in the background only inside sync work (section 7), never on mobile data.
7. Announcements: only transitions to `Connected`, `Offline`, `NotReachable` (never `Checking`, never percents).

### 6.3 Pairing (spec 7.4, S-04)

Flow in `PairingRepository.pair()`: `hello` at the candidate (identity, `phone_access`, API version, `server_time`)
→ `POST /api/phone/pair {code, device_name, app_version}` → on 201 store server id, name, address, device id, library
id, the clock offset and the encrypted token in one `PairingStore` write → a first `hello` with a challenge must verify
with the new key (5.3) → status `Connected` → `SyncTrigger.request(PAIRED)`. Error codes map to the F-02 copy
(`code_invalid` keeps focus and stores nothing, `code_expired`, `code_locked`, `rate_limited` with its
`Retry-After`). The code field uses `KeyboardType.NumberPassword` with autocorrect and autofill off, so the code does
not land in keyboard history (security review L-8). "Connect again" after a revoke is offered only for the same server
id.

"Connect again" sends `previous_device_id` (the stored device id) **and** `previous_token_proof`
(`HMAC(K_old, "pt-reactivate-v1|" + code)`), so the computer re-activates the same device id and the phone's accepted
requests stay its own; without a valid proof the computer creates a new device instead (server-contract 6.2, security
review L-1). If the old key cannot be decrypted (the phone was restored from a backup, 20.3), the phone pairs as a new
device and says nothing more: its earlier jobs then appear under "Started on your computer". `app_too_old` (426) maps
to `AppTooOld`.

"Forget this computer": if the status is `Connected`, the phone sends `DELETE /api/phone/devices/self` at once (5 s
limit, best effort); it is **never queued**, because the key is deleted right after and a queued unpair could never be
signed (delivery review D-32). Then it removes the pairing store, cancels every unique work and the periodic sync
(7.2), tags the clips `FORGOTTEN_COMPUTER`, removes requests that computer had accepted (after the F-22 confirmation)
and keeps waiting requests with `target_server_id = NULL`. A phone forgotten while away stays listed on the computer
until the operator revokes it there.

v1-complete (CR-01) adds one call to this flow. Waiting deletions of finished analyses (8.8) are bound to the device
id they were made with: after "Forget this computer" the pairing store is gone, and pairing again (with the same
computer or another) creates a new device id, because the old key that "Connect again" would need is deleted;
`:core:requests` drops every deletion whose device id is not the paired one (when `ComputerStatusRepository.paired`
changes, at start, and again inside the sender), so a forgotten deletion can never be sent (spec QT-18). A deletion
already **in flight** when the learner confirms is the one case the binding cannot settle (its request has left), so
`forget()` runs the unpair, the key deletion and the rest of the flow inside `SyncController.exclusive { … }`
(review fix C-62): `:core:sync` stops the running pass at its next step, waits for an outbox call already on the
wire (at most its 30 s call timeout, 5.1) and applies that answer as usual (a confirmed deletion removes its clip with
its message; an unknown outcome leaves the row to be dropped by the binding), then runs the block while holding the
orchestrator's mutex, so no new pass can start in between. The call is CR-01-A2's (the integrator's, because
`:core:connectivity` has no v1-complete owner); its default body only runs the block, which is v1-core's behaviour.
Shared progress stops by itself: it is exchanged only with the paired computer that announces the capability (7.8),
and the phone keeps showing its mirrored totals and Saved list (spec F-22).

### 6.4 Background restrictions

`BackgroundRestrictionMonitor` reads `ActivityManager.isBackgroundRestricted()` and
`UsageStatsManager.getAppStandbyBucket()` (no permission needed for the app's own bucket); `RESTRICTED` or `RARE`, or a
restricted flag, sets `backgroundRestricted` for the Home line and Connection details (F-03, F-21).

---------------------------------------------------------------------------------------------------------------

## 7. Sync engine (`:core:sync`, AD-14, AD-15)

### 7.1 The orchestrator

`SyncOrchestrator` runs *passes*, serialized by a process-wide `Mutex` (WorkManager runs in the app process). A pass:

1. **Probe** (skipped if the channel proved liveness in the last 7 s). Stop unless `Connected`.
2. **Outbox**: send `CANCEL`, `REMOVE_JOB` and `RERUN` operations (and in v1-complete `DELETE_FINISHED`, 8.8), oldest
   first (they go before any new request, F-20-AC5, QT-7, QT-15) — through `RequestSender` from `:core:requests`. The
   clips its report names in `deletedOnComputer` are removed at once by the deletion path of 7.7.
3. **Links**: send waiting link requests in creation order (`RequestSender`).
4. **Listing**: `GET /api/phone/library` → reconcile the catalogue (7.3, 7.7), then hand the listing to
   `RequestReconciler` from `:core:requests` (request states, automatic re-run after `interrupted`, notifications of own
   requests).
4a. **Shared progress** (v1-complete, `capabilities.shared_progress`): the practice and Saved exchanges of 7.8,
   after the listing so every saved item's clip row and the current library id are known.
5. **Reference**: if the listing's `api_version` differs from the cached one, fetch `/api/phone/reference` and call
   `ReferenceRepository.replace()`.
6. **Index**: clips whose verified clip data changed are (re)indexed by `ClipIndexer` (`:core:corpus`).
7. **Schedule transfers**: enqueue the download worker if any artifact is wanted; enqueue the upload worker if a file
   request is waiting (uploads belong to `:core:requests`; the orchestrator only triggers them).
8. **Budget**: run the storage policy (7.5).

`:core:sync` depends on the *API* of `:core:requests` (`RequestSender`, `RequestReconciler`); `:core:requests` never
depends on `:core:sync` and asks for passes through `SyncTrigger`.

### 7.2 Triggers and WorkManager

| Trigger | Mechanism |
|---|---|
| App start, resume, `Connected` transition, request created while connected in the app, "Sync now", pull to refresh, pairing | `ForegroundSession` (a `ProcessLifecycleOwner` observer in `:core:sync`) runs a pass immediately in the application scope; it also owns the job channel while in the foreground. A foreground pass may be cut short when the app goes to the background (Android 14+ stops background work of cached processes); nothing depends on it finishing, because every pending item is also covered by the work below (Android review H2c) |
| A request stored by the share sheet (link sent "now", spec 8.4 item 8) | unique **expedited** one-time work `send-now` (`OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`, `ExistingWorkPolicy.APPEND_OR_REPLACE`), enqueued while `ShareActivity` is visible, running one pass; never the bare application scope, which Android 14+ freezes once the sheet closes (Android review H2b) |
| Shared-file copies (temporary read grant) | unique **expedited** work `intake-share-<requestId>` (`ShareIntakeWorker`, `:core:requests`, 8.3) |
| Periodic background sync | unique periodic work `sync-periodic`, 15 min, constraints `UNMETERED` + `batteryNotLow` (spec 7.5); enqueued only while paired, cancelled by "Forget this computer" (NFR-04) |
| Work waiting for the home Wi-Fi (requests waiting, wanted downloads) with the app closed | unique one-time work `sync-on-wifi` with constraint `UNMETERED`, enqueued (`KEEP`) when something becomes pending while paired; it fires when the phone joins a Wi-Fi network even if the app is closed. When a run ends without reaching `Connected` (a café or office Wi-Fi is also unmetered), it returns `success()` and enqueues the next `sync-on-wifi` with an initial delay that starts at 15 min and doubles up to 2 h while the outcome stays "not reachable"; it is never re-enqueued with zero delay from inside a run. A network change seen in the foreground, or reaching `Connected`, resets the delay (Android review M1) |
| Downloads | unique work `downloads` (`DownloadWorker`) |
| Uploads | unique work `uploads` (`UploadWorker`, `:core:requests`); in the background only for files that fit the background window (8.5) |
| Picked-file copies (lasting permission) | unique work `intake-<requestId>` (`FileIntakeWorker`, `:core:requests`) |
| Retries of transient failures in the background | the next `sync-on-wifi` run, delayed by the larger of `RetrySchedule`'s next step and the backoff above |
| New shorts rule version after an app update | one-time `reindex` work |
| An answer, a save, an unsave or a reset while connected in the foreground (v1-complete) | `ForegroundSession` collects `SharedProgressLedger.changed` and runs only step 4a of 7.1, debounced 10 s, under the orchestrator's mutex; offline, the change simply waits for the next pass (spec 7.13) |
| A deletion confirmed on the Clip page (v1-complete) | as a request created while connected: an immediate foreground pass (step 2 sends it); otherwise `sync-on-wifi` and the next pass, as for any outbox operation |

JobScheduler's network constraint normally requires a *validated* network, so on a home Wi-Fi without internet access
the background work may not run until the app is opened; the foreground session, which binds to the Wi-Fi itself
(5.1), still works there. The UI never promises a time (spec 7.5).

Workers are `@HiltWorker` `CoroutineWorker`s with the `HiltWorkerFactory` (the default WorkManager initializer is
removed from the merged manifest). A background pass never calls UI code and never claims a time in copy (spec F-21).
Every worker logs `WorkInfo.stopReason` (as an event code, 20.4) when it is stopped, so QA can tell quota stops from
constraint stops. Expedited workers implement `getForegroundInfo()` (a `dataSync` "Transfers" notification), which
WorkManager uses on API 29–30, where expedited work runs as a foreground service (Android review L10).

**Foreground transfers.** When the learner starts a transfer from the foreground (an upload they sent, "Download the
video", the first sync after pairing), the worker is enqueued with `userVisible = true` and calls `setForeground()`
with a `ForegroundInfo` of type `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and the "Transfers" notification (low importance,
silent, "Stop"). The merged manifest declares WorkManager's `SystemForegroundService` with
`android:foregroundServiceType="dataSync"` (in `:core:sync`'s manifest) and the permissions `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC`. If the worker starts after the app left the foreground,
`ForegroundServiceStartNotAllowedException` is caught and the work continues as ordinary deferrable work (spec 7.7),
subject to 8.5's size rule for uploads. Android 15+ limits data-sync services to 6 h a day; transfers are resumable
(downloads) or restartable (v1-core uploads), so a stop is harmless. WorkManager 2.12.0 implements
`SystemForegroundService.onTimeout` and ignores scheduler stops for foreground workers (verified in the cached AAR by
the Android review), so the Android 16 job quotas do not cut a running user-visible transfer short. User-initiated data
transfer jobs, which the platform documentation recommends for this case, were rejected because WorkManager does not
schedule them and a second scheduling path would duplicate the queue logic; WP-19 records this in `decisions.md`, and
WP-25 checks the stop and resume paths with the platform's test switches (21.7).

### 7.3 Catalogue reconcile

For a listing from the paired server id:
- Upsert every job into `clip` (status, stage, percent, queue position, title, error, summary, media facts, revision);
  upsert its artifacts' remote fields. A changed revision on a clip-data or audio artifact marks a new download
  (7.4); positions and history stay (anchored to job + segment + word).
- A job in the listing with `library_id` different from the stored `sync_meta.library_id` → tag the existing `ACTIVE`
  clips of the old library `OTHER_LIBRARY`, post the library notice, store the new library id (QT-14). Clips tagged
  `OTHER_LIBRARY` or `FORGOTTEN_COMPUTER` are never touched by a listing again.
- A listing from another server id is ignored entirely (QT-11).
- Deletions: section 7.7.
- "New on this phone": `first_seen_at` for the "New on this phone" card and the `clipReady` notification.

### 7.4 Downloads, verification, resume, revisions (AD-15)

- **Order** (spec 7.8): study packs first (clips requested from this phone, then wanted clips, then newest), then the
  second audio track, then videos (kept and recently watched first, then newest). `DownloadWorker` processes one file
  at a time (two in parallel at most when one is small), re-reading the queue after each file.
- **Study pack** = clip data + the audio of the learner's chosen track. With renditions (v1-complete,
  `capabilities.renditions`) the compact clip data and Opus audio are preferred when `READY`; otherwise
  `analysis.json` and the WAV. A clip is "On this phone" only when its study pack is complete and verified (SC-11).
- **Transfer**: `GET <remote_url>` with `Range: bytes=<part_bytes>-` and `If-Range: <etag>` when a part exists;
  writes to `clips/<jobId>/.<name>.<revision>.part` with periodic `fsync` and row updates of `part_bytes` (at most every
  512 KiB); on resume the existing part is re-hashed to rebuild the SHA-256 state (≤ 65 MB, a second or two). A `200`
  where `206` was expected restarts from zero. When `part_bytes` already equals `remote_bytes` (the process died
  between the last write and the rename), no request is sent: the part is verified directly. A `416` is handled the
  same way: verify the part, and restart from zero only if it does not match (Android review M5).
- **Verify**: size and SHA-256 must equal the listing's. Mismatch → delete the part, `verify_failures + 1`, retry once;
  the second failure → `FAILED` ("Download failed: the file was damaged on the way." + "Try again"); the previous good
  file stays in use.
- **Atomic switch**: rename the part to `<name>.<sha8>.<ext>`, update the `artifact` row in one transaction, then
  delete the previous file. Readers resolve files through the row, so they always see a complete file.
- **Which video** (spec 7.8): `DecoderCapabilities.canPlay(codec, width, height, fps)` asks
  `MediaCodecList(REGULAR_CODECS)` for a decoder of the codec (`video/av01`, `video/avc`, `video/x-vnd.on2.vp9`,
  `video/hevc`, `video/x-vnd.on2.vp8`) whose `VideoCapabilities.areSizeAndRateSupported(w, h, fps)` holds for the
  stored size in both orientations (a portrait video is checked as stored and rotated), preferring a hardware decoder
  and, on API 29+, checking `getSupportedPerformancePoints()` when the decoder lists them (`isSizeSupported` alone
  says nothing about speed, and software AV1 decoders advertise 4K; Android review L2), together with the rule "1080p
  or smaller"; if it can, download `original`; otherwise (v1-complete) `POST .../renditions/video_phone`
  and download `video_phone` when `READY`; in v1-core the clip stays audio with "This video can't play on this phone.
  Listening still works." (spec 14.4). Missing original → "Video not available from your computer".
- **Paused** when not `Connected`, when the free-space floor is reached, or when the budget is reached; the reason is
  stored in `pause_reason` and shown on the clip.
- **Skipped** (v1-complete) for a clip in `PendingDeletions.jobIds` (a deletion from the phone waits or is being
  sent, 8.8): nothing more is downloaded for it, no video and no new revision, until the deletion is applied or undone
  (spec 7.8); "Undo" lets its downloads continue at the next pass. No `PauseReason` is added: the Clip page shows the
  deletion block instead.

### 7.5 Storage budget and eviction (spec 7.10, D-19)

- Budget choices 1 GB, 2 GB (default), 5 GB, no limit except free space; it covers downloaded clips (study packs +
  videos). Waiting file copies count separately.
- Free-space floor: `min(1 GB, 10 % of total)`; free space is measured with
  `StorageManager.getAllocatableBytes(getUuidForPath(filesDir))`, which counts cache the system can clear, not
  `StatFs` alone (Android review L3); below the floor downloads pause and new copies are refused (8.3).
- After each completed download and on budget change: while usage > budget, delete the **video** of the least recently
  watched clip that is not kept, not in today's set and not opened in the last 24 h (`learning.db` supplies
  `last_watched_at`/`last_opened_at`; `SetRepository` supplies today's clips). Study packs are never removed
  automatically; if they alone exceed the budget by 10 %, `StorageRepository.usage` carries the "Storage budget
  reached" state for Home and Storage.

### 7.6 Live progress

Channel events are lean (no artifacts, server-contract 7); a `job` event whose `revision` differs from the stored one
calls `SyncTrigger.request(JOB_CHANGED)`, and the pass fetches the listing with its artifacts (revision 2).

While connected in the foreground, the job channel's `job` events update `clip` rows and request states within the
event's arrival (F-03-AC5, ≤ 5 s); `deleted` events feed 7.7. With no channel, the listing of the next pass does the
same.

### 7.7 Deletion semantics (spec 7.9)

- A `deleted` event from the paired server, or a clip missing from **two consecutive complete listings** of the same
  server id and library id, removes the clip: its files, its derived rows, its saved items and positions (through
  `SavedRepository`/`ProgressRepository` cleanup hooks); per-change practice stats are kept. If the clip is open
  (`ClipLease` held), the removal is deferred until the lease closes (QT-10). One notice per batch.
- **Mass-deletion guard**: if more than half of the `ACTIVE` clips would disappear at once, nothing is removed; a
  `MassDeletionPrompt` waits for the learner.
- "Remove from this phone" deletes study pack and video but keeps the `clip` row (`removed_by_learner`); it is never
  downloaded again until "Download to this phone".
- **Deletions this phone asked for** (v1-complete, 8.8). A clip in `SendReport.deletedOnComputer` is removed by the same
  path at once (files, derived rows, saved items and positions through the removal hooks; per-change practice stats
  kept; deferred while a `ClipLease` is open, QT-10), with its own message (`CLIP_DELETED_FROM_COMPUTER`, or
  `CLIP_REMOVED_FROM_COMPUTER_LIST` for an imported clip) instead of the batch notice. A `deleted` event or a listing
  that removes a clip whose deletion waits or is being sent fulfils that deletion (`RequestReconciler.onJobDeleted`
  marks it done and posts the same message once, spec QT-16). Clips in `PendingDeletions.jobIds` are left out of the
  mass-deletion guard's count and never cause its prompt: the learner already confirmed each one (spec 7.9, QT-15).

### 7.8 Shared progress exchange (v1-complete, CR-01; spec 7.13, S-24, AD-25)

The exchange runs in `:core:sync` (the only module that calls the computer during a pass) and reads and writes the
learning state only through the SPI `SharedProgressLedger`, implemented by `:core:study`, so neither module depends on
the other's implementation (2.2):

```kotlin
interface SharedProgressLedger {                    // :core:study → :core:sync (work-plan 4.7)
  val changed: Flow<Unit>                            // an answer, a save, an unsave or a reset was stored
  suspend fun practiceOutbox(serverId: ServerId, offsetS: Long, limit: Int): PracticeOutbox   // may be empty
  suspend fun practiceApplied(serverId: ServerId, sent: PracticeOutbox, answer: PracticeShareAnswer)
  suspend fun savedOutbox(serverId: ServerId, libraryId: LibraryId, offsetS: Long, limit: Int): SavedOutbox
  suspend fun savedApplied(serverId: ServerId, libraryId: LibraryId, sent: SavedOutbox, answer: SavedShareAnswer)
  suspend fun practiceRefused(serverId: ServerId, sent: PracticeOutbox)    // a 400 or 413: the page is handled
  suspend fun savedRefused(serverId: ServerId, libraryId: LibraryId, sent: SavedOutbox)   // (Outcomes, below)
}
```

**When.** Step 4a of every pass (7.1) and the debounced foreground trigger of 7.2, only while `Connected` to the paired
computer and only when its last known `capabilities.sharedProgress` is true; otherwise nothing is sent and the phone
behaves exactly as in v1-core ("Kept only on this phone."). Each run makes at least one practice call and one Saved
call (an empty outbox still brings the other devices' changes), then repeats while a page was full (100 answers, 80
Saved changes; server-contract 4.7). An outbox that carries the baseline carries no answers (`practiceOutbox` returns
it alone; the answers go in the next call), so no body can cross the computer's 16 KiB limit.

**First share** (`practiceOutbox` for a server id that `share_state` does not name: the computer just gained the
capability, or this phone paired with another computer; spec 7.13 rule 7), in one transaction: `share_state` is set
for that server id; the **baseline** is this phone's own answers, named by `learning_meta.learning_id` and dated now
on the computer's clock: the `practice_stat` totals when `share_state` never existed (nothing was mirrored yet, so they
are all this phone's; `own_stat` is set to the same totals in the same transaction), otherwise the `own_stat` totals
(after sharing with another computer `practice_stat` mirrors that computer's whole history, other devices' answers
included, which must never travel to a second computer, spec 7.13 rule 7: "the answers given on it"; review fix
C-63); every existing `practice_answer` row is marked sent (the baseline covers it); the
Saved outbox is rebuilt from `saved_item` (one save per item of an `ACTIVE` clip, dated by its `saved_at` on the
computer's clock, so an unsave made elsewhere later still wins). Naming the baseline by `learning_id` is what makes it
count once: a phone restored from a backup taken before its first share builds a baseline with the same id, which
the computer ignores (spec F-17-AC11); pairing again with the same computer after "Forget" keeps `share_state`, so no
second baseline is sent and the answers given in between go one by one.

**Answers.** Every answer (Practice and set questions, spec F-08) writes `practice_stat`, `own_stat` and a
`practice_answer` row (UUID, change, right or not, `computer_at` = the phone's clock plus `share_state.offset_s`, the
last offset the sync stored) in one transaction. `practiceApplied` then, in one transaction: marks the sent rows
`sent`, clears the confirmed baseline and the confirmed waiting reset, stores the computer's current reset, deletes
unsent rows given at or before that reset (the computer would ignore them), and **rewrites `practice_stat` as the
computer's totals plus this phone's unsent rows** (and records the changes with answers in `change_met`). When that
reset is newer than the one stored (another device reset the history), `own_stat` is rebuilt in the same transaction
from this phone's `practice_answer` rows given after it, so answers the reset cleared never travel to another
computer later. So `PracticeRepository.accuracy()` keeps reading `practice_stat` exactly as in v1-core and always shows
"the last shared totals plus this phone's answers not shared yet" (spec F-17 item 6 and States); after "Forget this
computer" it keeps showing them as this phone's own (spec F-22). Sent rows older than 30 days are pruned (they are
about 60 bytes each); the one approximation: a reset pressed elsewhere that reaches this phone more than 30 days
later leaves `own_stat` with only the kept rows after it, so a later move to another computer adds fewer of this
phone's answers, never another device's and never one twice.

**Reset.** `PracticeRepository.reset()` while sharing: in one transaction `practice_stat` and `own_stat` are cleared,
unsent `practice_answer` rows are deleted (they were given before the reset) and a waiting reset (UUID, now on the
computer's clock) is stored; `sharing()` reads `ResetWaiting` until a practice call carries it and the computer
confirms it; answers given after it count. `ComputerClient.syncPractice` sends that reset in the signed query
(`reset_id`, `reset_at`, server-contract 6.12), never in the body; the body itself is covered by `body_sha256` (5.4).
Not sharing: the phone-only reset of v1-core, which also clears `own_stat`, deletes unsent rows and leaves
`share_state` alone.

**Saved list.** `saveShort`, `saveWord` and `unsave` write `saved_item` and the `saved_change` outbox (key of
server-contract 3.2, `at` on the computer's clock) in one transaction. Only changes of `ACTIVE` clips whose library is
the computer's current one are sent (items of clips "From another library" or "From a computer you forgot" stay on the
phone, unshared and untouched, spec 7.13 rule 6). `savedApplied` applies the computer's `items` in order (a save
upserts `saved_item`, an unsave deletes it; an entry whose local unsent change is later is left for that change to
win), removes `dropped` items, marks `not_shared` changes `sent = 2` (the item stays on this phone, unshared, and is
not sent again; one event code is logged), and on `full: true` also removes the saved items of this library's
`ACTIVE` clips that the list no longer has (items with a waiting or not-shared change are kept); then it stores the
new `saved_cursor`. When the paired computer's library id differs from `share_state.saved_library_id`, the cursor
restarts at null for the new library, so the first answer is a full list. When a saved
short was last watched is never sent (spec 7.13). A clip removed by 7.7 also loses its unsent Saved changes (the
computer drops a deleted job's entries itself, server-contract 6.12 rule 5).

**Outcomes.** Transient failures (no answer, timeout, `5xx`, including `503 store_unavailable` while the computer's
store is unreadable, server-contract 3.2) retry at the next pass; `library_changed` skips the Saved call until the
next listing updates the library id; `credential_rejected`, `phone_access_off`, a too-old computer and `clock_skew` are
handled as for every call (6.2, 5.3). The computer refuses content **per item**, never by refusing a whole request
(server-contract 4.7): answers and a baseline it does not add count in `ignored`, Saved changes come back in `dropped`
or `not_shared`, and the phone treats each as handled (sent rows marked sent, the baseline cleared, `dropped` items
removed, `not_shared` changes marked `sent = 2`), so no page can block the outbox. A `400 invalid_request` or
`413 too_large` is a defect the outbox checks make unreachable (they apply the computer's patterns and bounds before
sending); should one come, `practiceRefused` or `savedRefused` marks the page handled the same way (its answers marked
sent, its baseline cleared, its Saved changes marked `sent = 2`) and one event code is logged, so a defect costs that
page and never wedges the exchange (review fix C-55). Nothing of this ever shows in Requests, on Home or in a
notification (spec 7.13).

**One computer at a time.** `share_state` names one server id: if the phone is paired with another computer, that
computer gets a first share whose baseline is `own_stat` (this phone's own answers since the latest reset it knows of;
never the first computer's aggregate, so other phones' and the browser's answers stay where they were given, and two
phones that move count nothing twice), and answers given while sharing with the second computer are not sent to the
first one later. Several paired computers are Later (spec 14.5), so this is the documented limit, not a defect.

---------------------------------------------------------------------------------------------------------------

## 8. The device queue (`:core:requests`, AD-16)

### 8.1 Local first

Every request is written to `request` in one transaction, with a fresh UUID as `id`, **before** any network attempt
or file copy. If that insert fails (SQLite full), the caller gets `AddResult.OutOfSpace` and the share sheet says so;
nothing is pretended. Notifications permission is asked after the first successful insert (F-23); when that first
request comes from the share sheet, the sheet suspends its 2 s auto-close while the notification question is open
(the in-app question "Get a notification when your clip is ready?" and then the system dialog) and starts the 2 s
count again once it is answered, so `finish()` never dismisses either (Android review M8, spec 8.4 item 8, D-51).

### 8.2 Links

`LinkParser` (pure, in `:core:analysis` under `links/`, exposed through the `LinkParser` interface):
1. Extract the first `http://` or `https://` URL from the shared text (regex up to whitespace or `<>"'`, trailing
   `.,;:!?` and an unbalanced `)` trimmed); at most 2,048 characters; no user-info part. None → "There's no link in what
   you shared." and nothing is created.
2. Label the site: YouTube (`youtube.com/watch`, `youtu.be`, `m.youtube.com`), YouTube Shorts (`youtube.com/shorts`),
   X (`x.com`, `twitter.com`, `mobile.twitter.com`, `t.co`), otherwise Other website.
3. For YouTube and X links remove `si`, `feature`, `utm_*`, `s`, `t` and send the result; other links are sent as
   shared (trimmed).
4. `link_key`: `yt:<video id>` for every YouTube form of one video, `x:<status id>` for every X form of one post,
   the sent URL otherwise (`t.co` cannot be resolved without a network call, so it is its own key).
5. Duplicates: same `link_key` among non-removed requests or the catalogue's `source_url` keys → "You already added
   this link." with "Open it" / "Analyze again anyway" (SH-AC8).

### 8.3 Files

- **Accepted types**: the computer's suffixes `.webm .mp4 .mkv .mov .avi .m4v .mp3 .wav .m4a .flac .ogg .opus .aac`;
  the extension comes from the display name or, when missing, from the MIME type. Anything else is refused before a
  request exists (SH-AC10).
- **Provider metadata is untrusted** (security review M-4; Android knowledge base, "untrustworthy ContentProvider
  provided filename"): the copy is always stored as `requests/<id>/source.<ext>` with `<ext>` from the closed suffix
  list; the display name is kept only in Room, reduced to its base name with `Cc`/`Cf` characters removed and at most
  200 characters, and used only for display and as the upload's `file_name`. A URI whose authority belongs to this
  app (`packageManager.resolveContentProvider(authority, 0)?.packageName == packageName`) is refused, so a sharer
  cannot make the app read its own private providers. For `ACTION_SEND_MULTIPLE` the items are read from the intent's
  `ClipData` (which carries the read grants) as well as `EXTRA_STREAM`; `file://` is refused (Android review L8).
- **Size**: above the computer's declared `max_upload_bytes` (2 GiB when never connected) refused at share time; above
  500 MB "Audio only" is preselected; a copy that would cross the free-space floor is refused with the space needed.
  `OpenableColumns.SIZE` may be absent or false, so the copy also **counts bytes while streaming** and aborts as
  `COPY_FAILED(no_space)` when the count exceeds `max_upload_bytes`, would cross the free-space floor, or exceeds a
  declared size by more than 1 MiB.
- **Shared items** (`ACTION_SEND`/`SEND_MULTIPLE` grants): the request is inserted as `PREPARING`; while
  `ShareActivity` is visible (the read grant is tied to the receiving activity) it opens the source's
  `ParcelFileDescriptor`, registers it in the in-process `IntakeHandles` registry under the request id, and enqueues
  the expedited `ShareIntakeWorker` (`intake-share-<id>`, 7.2). The worker streams the descriptor through a SHA-256
  digest into `requests/<id>/.copy.part`, then renames it and marks `copy_complete` in one transaction → `WAITING`
  ("Added"). A running job keeps the process out of the cached state, so pressing Home during a large copy no longer
  freezes it (Android 14+ stops background work of cached processes; Android review H2a); the spec's "Home: the copy
  continues while the app's task lives" (7.7) holds. Back during the copy asks "Stop adding this video?". If the
  worker is stopped, it retries from the start when the descriptor is seekable; if the process died, the handle is
  gone and the request becomes `COPY_FAILED` ("the copy didn't finish") with its part deleted, at the worker's retry
  or at the next start-up check, whichever comes first (QT-13).
- **Picked items** (`ACTION_OPEN_DOCUMENT`, `takePersistableUriPermission`): copied by `FileIntakeWorker`, which
  restarts the copy after process death and releases the permission once the copy is complete (F-19-AC4). The
  permission is also kept while the request is `COPY_FAILED` with `copy_no_space` (so "Try audio only" can read the
  source again, 8.4) and released when the request is removed or a later copy completes; after `copy_unreadable` it
  is released at once.
- **Readable source** (spec D-49). A request's source can be read again only (a) while the `ShareActivity` that
  received it is alive (a grant carried by the intent belongs to the app and lasts while the receiving activity's
  task is active, so `:core:requests` can reopen the URI with the application's `ContentResolver` during that time),
  or (b) when it was picked with a lasting permission (`source_persisted = 1`). For (a), the sheet calls
  `RequestRepository.holdSources(ids)` (3.4) for the requests it created and closes the returned handle in
  `onDestroy`; while it is held, `:core:requests` keeps a re-opener per request in `IntakeHandles`, and
  `IntakeHandles.readable: StateFlow<Set<RequestId>>` is combined into `Request.actions` (8.4), so "Try audio only"
  disappears from a row the moment the sheet closes. `:feature:share` never touches `IntakeHandles` itself (2.2 rule
  1).
- **Audio only for a local video**: `MediaExtractor` + `MediaMuxer` remux the audio track without re-encoding: AAC →
  MPEG-4 (`.m4a`), Opus → Ogg (`.ogg`, API 29+), Vorbis → WebM (`.webm`); any other codec, or a failure, keeps the
  whole file with "Sent the whole file: the audio couldn't be separated on the phone."
- **Failure states** (the phone-side codes are stored in `state_reason`, exposed as `Request.lastError.code` with
  `atSending = false`, never sent to the computer, and mapped to the spec 7.7 copy by `RequestFailureCopy`, 19): no
  space mid-copy → `COPY_FAILED` `copy_no_space`, the partial copy deleted and `needed_bytes` stored ("Free {N} MB"),
  with "Try audio only" for a video only while its source is readable (above; otherwise the row reads "…then share it
  again and choose Audio only." with "Remove" only); unreadable source → `COPY_FAILED` `copy_unreadable`; a shared
  copy cut short by process death → `COPY_FAILED` `copy_interrupted`; a kept copy deleted outside the app →
  `COPY_MISSING` `copy_missing` at the start-up check (F-20-AC9).
- The copy is deleted as soon as the computer accepted the upload (the computer keeps the file; re-runs use it).

### 8.4 State machine

States are `RequestState` (3.4). Each transition is one Room transaction, observed by the UI; the queue-wide pause
("Connect again", "Update needed", "A different computer answered") is **derived** from `ComputerStatus` at read time
and never written into rows.

| From | Event | To |
|---|---|---|
| (new file) | insert | `PREPARING` |
| `PREPARING` | copy complete | `WAITING` |
| `PREPARING` | copy interrupted (shared), no space, unreadable | `COPY_FAILED` (`copy_interrupted`, `copy_no_space`, `copy_unreadable`) |
| `COPY_FAILED` `copy_no_space`, a video, source readable (8.3: its sheet still open, or `source_persisted`) | "Try audio only" | `PREPARING` with `audio_only = 1`, same request id: a new intake extracts the audio track only from the source (8.3); a new no-space failure returns to `COPY_FAILED` |
| (new link) | insert | `WAITING` |
| `WAITING` | start-up check finds the copy missing | `COPY_MISSING` |
| `WAITING` | "Send now" | `held = 0`, `next_attempt_at = now`: picked by the sender at once when connected (D-46) |
| `WAITING` (not held, connected, not paused, `next_attempt_at` reached) | sender picks it | `SENDING` |
| `SENDING` | computer answered with a job | `SENT` (job id stored, `sent_at`) |
| `SENDING` | transient failure (no answer, timeout, reset, 5xx) | `WAITING` (`attempts + 1`, `next_attempt_at` from the schedule) |
| `SENDING` | refused (`refused_address`, `too_large`, `unsupported_type`, `invalid_url`) | `NEEDS_ATTENTION` (`lastError.atSending`) |
| `NEEDS_ATTENTION` at sending, a video file refused as `too_large`, its copy still on the phone | "Try audio only" | `PREPARING` with `audio_only = 1`: the audio is extracted from the kept copy (`requests/<id>/source.<ext>`, no source read needed), which then replaces it; nothing is re-run on the computer, because no job exists. The request id stays the upload key: a declaration refused with `too_large` opened no session (server-contract 6.6), and a stale session answering `key_conflict` is deleted (`DELETE /api/phone/uploads/{key}`) before declaring again |
| `SENDING` | "Stop sending" | `WAITING` with `held = 1` |
| `SENT`/`IN_LINE`/`ANALYZING` | listing: `queued` / `running` | `IN_LINE` / `ANALYZING` |
| any accepted | listing: `done` | `READY_ON_COMPUTER` → (download starts) `GETTING_ON_PHONE` → (study pack verified) `ON_PHONE` |
| any accepted | listing: `error` with `interrupted`, auto re-run unused | enqueue `RERUN` op (in acceptance order); shown as "Starting again on your computer" |
| any accepted | listing: `error` (other, or second interruption) | `NEEDS_ATTENTION` (reason = job error code) |
| any accepted | listing: `cancelled` | `CANCELLED` |
| `SENT`/`IN_LINE`/`ANALYZING` | "Cancel" while connected | `CANCEL` op sent at once; listing confirms `CANCELLED` |
| `SENT`/`IN_LINE`/`ANALYZING` | "Cancel" while not connected | `CANCEL_WAITING` (+ `CANCEL` op); "Undo" deletes the op, restores the previous state and posts `UserMessageKind.CANCEL_UNDONE` with the title once (D-47, F-20-AC12) |
| `CANCEL_WAITING` | computer answers `already_finished` | `READY_ON_COMPUTER` + notice "Too late to cancel…" |
| `NEEDS_ATTENTION` (a job failure)/`CANCELLED`, a link | "Try again" / "Try audio only" | `RERUN` op (new UUID) with `audio_only` null / true; on acceptance `SENT` with the new job id |
| `NEEDS_ATTENTION` (a job failure)/`CANCELLED`, a file | "Try again" | `RERUN` op (new UUID) with `audio_only` null: an upload is re-run with its own options, never as audio only (server-contract 6.7); on acceptance `SENT` with the new job id |
| `PREPARING`/`WAITING`/`COPY_*` | "Remove" | row and copy deleted; a kept persisted permission released |
| `SENDING` | "Remove" | upload aborted (and, v1-complete, the upload session deleted), row and copy deleted |
| `NEEDS_ATTENTION`/`CANCELLED` | "Remove" | `REMOVE_JOB` op kept (when a job exists), row deleted |

The sender re-reads the row inside the transaction that marks it `SENDING`, so a request removed or cancelled a
moment earlier is never sent (QT-6).

**`Request.actions` is the one authority** on what a request offers (WP-09 computes it from the state, the row, the
kind, `IntakeHandles.readable` and the derived queue pause); `RequestFailureCopy` (19) only words and orders it.
"Try audio only" is offered only where it can work (spec D-49): `COPY_FAILED` `copy_no_space` for a video whose source
is readable; a video file refused at sending as `too_large`; a link's job failure whose 8.6 row lists it
(`download_forbidden`, `no_space`; never `too_long`); never for a file's job failure. The other actions of a failure
follow its spec 8.6 row (for example no "Try again" for `too_long`, `download_unavailable`, `refused_address` or
`source_gone`; "Try again" instead of "Try audio only" for a file's `no_space`), and "Send now" is offered only while
connected. A table test (WP-09) checks every row of spec 8.6 and of F-20's actions table, for both kinds, against
this rule.

### 8.5 Sending and idempotency

- **Order**: outbox operations (in v1-complete including `DELETE_FINISHED`, 8.8), then links in `seq` order (even while
  an upload runs), then files one at a time in `seq` order (spec 7.7).
- **Links**: `POST /api/phone/jobs/link` with `client_request_id = request.id`. 201 and 200 both mean accepted (200 =
  the computer already had it). A timeout after the body left keeps the key; the retry returns the existing job, so a
  lost response never creates a second analysis (QT-3, SC-05).
- **Files (v1-core)**: `POST /api/phone/uploads` (declares name, bytes, SHA-256, options; returns the existing job if
  the key is known, or refuses early on size, type or space), then `PUT /api/phone/uploads/{key}` with the whole file
  and a known `Content-Length` (`File.asRequestBody()`). An interruption restarts from zero with the same key; the
  copy stays until accepted (spec 7.7 v1-core). The server aborts a silent body after 30 s and lets a new `PUT` for the
  same key replace an idle one after 10 s (server-contract 6.6), so a phone back from a Wi-Fi drop restarts promptly;
  `409 upload_busy` is transient and retried after 10 s.
- **Background uploads only when they fit** (Android review H4). A worker without a foreground service is stopped at
  the job execution limit (about 10 minutes, less in the rarer standby buckets of Android 16), and in v1-core an
  interrupted upload restarts from zero, so a large file sent only in the background could restart forever. The
  uploader therefore starts a file in the background only when it fits the window: `file_bytes ≤ throughput × 360 s`,
  where `throughput` is the moving average of this phone's last measured upload rates to the computer (200 MB when
  nothing was measured yet; the average is kept in the pairing store, 4.5). Larger files wait (state `WAITING`,
  nothing marked failed) and are sent the next time the app is in the foreground, as a user-visible transfer (7.2),
  which Android lets run to completion. The repository exposes the rule's verdict as `WaitingInfo.needsAppOpen`,
  computed with the same function the uploader uses and shown whatever the connection state, so the row adds the spec's
  line "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent while the app is open." (spec
  7.7, D-41, F-20-AC10); links behind such a file are sent as usual. v1-complete resumable uploads
  (`capabilities.resumable_uploads`) lift the rule (`needsAppOpen` is then always false): each background run
  continues where the last one stopped.
- **Files (v1-complete, `capabilities.resumable_uploads`)**: `GET` the session offset, then `PATCH` 8 MiB chunks with
  `Upload-Offset`; after an interruption continue from the computer's offset (QT-4 v1-complete).
- **Before sending a file**: compare `file_bytes` with `max_upload_bytes` and `low_space`; refuse early with the
  spec's copy.
- **Retry schedule** (`RetrySchedule`, `:core:common`): next transition to `Connected` → 30 s → 1 min → 2 min → 5 min
  → every 15 min while connected; no maximum; after 3 failed attempts the row reads "Couldn't send yet: will try
  again" with the last reason.
- **Blocking failures**: `credential_rejected` pauses the whole queue (derived state) and nothing is marked failed
  (QT-8); `too old` likewise; `no_space` (507) pauses uploads only. `clock_skew` is never blocking: the offset is
  updated and the send retried once (5.3).

### 8.6 Re-runs

`RERUN` ops call `POST /api/phone/jobs/{id}/rerun` with the op's UUID and `audio_only` (links only; a file's re-run
always sends null, server-contract 6.7). The computer's
new job replaces the failed one (its `deleted` event and the new job arrive together); the request keeps one row with
the new job id. `410 source_gone` → `NEEDS_ATTENTION` "Your computer no longer has this file. Share it again." with
"Remove". The automatic re-run after `interrupted` uses the same op, once per request (`auto_rerun_used`), in the
original acceptance order (QT-5, QT-12).

### 8.7 Browser jobs

Jobs with `own: false` (browser and CLI jobs, and other phones' jobs, whose `device_id` the computer does not reveal,
server-contract 6.3) appear only in "Started on your computer": never counted, never in "Needs your attention", never
notified; failed ones offer only "Hide on this phone" (`clip.hidden`). Once finished, they are clips like any other,
and in v1-complete any of them may be deleted from its Clip page (8.8).

### 8.8 Deleting a finished analysis on the computer (v1-complete, CR-01; spec F-05 item 6, 7.7, 7.9, S-25, AD-24)

**The operation.** "Delete from your computer…" confirmed on the Clip page calls
`RequestRepository.deleteFromComputer(id)`, which writes one `request_op` row in one transaction **before any network
attempt** (7.7's "local first"): kind `DELETE_FINISHED`, a new UUID as `id` (also the `client_request_id` of every
attempt), `job_id`, and what it is bound to: the clip's `server_id` and `library_id` and the paired `device_id`, plus
the clip's `title` and whether it is `imported` (`source_kind = IMPORT`, server-contract 6.3) for its messages. It is
offered (`deletionOffer`) only when the phone is paired, the computer's last known `capabilities.deleteFinished` is
true, the clip is `ACTIVE` (so of the current library) and finished; one waiting deletion per clip (a second confirm
returns the existing one). `DeletionOffer.Available` carries `imported`, the number of the clip's saved items (the
confirmation's extra sentence) and whether the computer is connected (the offline sentence); otherwise
`NotOffered(FORGOTTEN_COMPUTER | OTHER_LIBRARY | COMPUTER_CANNOT)` gives the three sentences of spec F-05 item 6
(`COMPUTER_CANNOT` here is an offer never made; a stored deletion that meets such a computer is refused, below).

**Sending.** `RequestSender.sendOperations()` sends it with the other outbox operations, oldest first, before any link
or file (spec 7.7 Ordering, QT-15). Inside the transaction that marks it `SENDING` (QT-6's rule) the sender re-reads
the row and checks the binding: the paired server id equals `server_id`, the paired device id equals `device_id`
(otherwise the row is deleted: a forgotten computer's deletion is dropped, 6.3), and the computer's current library
id equals `library_id` (otherwise it stays `PENDING` with the wait `DIFFERENT_LIBRARY`). It is never sent to an address
without a valid identity proof (5.3, QT-20), because nothing is. It is not sent either when the connected computer's
capabilities (refreshed by the `health` that begins every connection, 4.5) no longer announce `deleteFinished`: the
row becomes `FAILED` with `last_error_code = unknown_route`, exactly the refusal a `404 unknown_route` gives below,
because spec D-62 treats a computer that no longer offers the deletion as a refusal whether the phone learns it from
the flag or from the answer, and one state needs one set of words. The call is
`ComputerClient.deleteFinished(id, serverId, libraryId, key)` (server-contract 6.11; the ids and the body's hash travel
in the signed query, 5.4).

Answers are matched by **status and code together**, never by status alone: v1-core's `REMOVE_JOB` reads a `404` as
"already gone", and a deletion must not share that rule, because the only `404` this route gives is `unknown_route` (a
computer without the route, for example one downgraded after the deletion was stored), which says nothing about the
clip (review fix C-61; spec D-62 makes it a refusal). One unit test per row below, including a `404 unknown_route`
that leaves the clip on the phone and refuses the deletion.

| Answer | Effect on the row | Effect on the clip |
|---|---|---|
| `200`, `already_gone` false or true | deleted (done) | its id goes into `SendReport.deletedOnComputer`; 7.7 removes the clip at once (deferred while open) and posts `CLIP_DELETED_FROM_COMPUTER` or, imported, `CLIP_REMOVED_FROM_COMPUTER_LIST`, once |
| no answer, timeout, reset, `5xx` after the request started to leave | back to `PENDING`, `attempts + 1`, `next_attempt_at` from `RetrySchedule` (8.5); the same `id` on every retry; **`maybe_sent` set** (the computer may have deleted the clip) | none |
| a connect failure before any byte left | back to `PENDING`, `attempts + 1`, `RetrySchedule`; `maybe_sent` unchanged | none |
| `429 rate_limited` (server-contract 4.7) | back to `PENDING`, `attempts + 1`, `next_attempt_at` not before `Retry-After`; `maybe_sent` unchanged (the computer refused before acting) | none |
| `409 library_changed` | `PENDING` with the wait `DIFFERENT_LIBRARY` ("Your computer is using a different library: …"), not counted as an attempt; sent once the paired computer reports that library again (QT-17) | none |
| `404 unknown_route` (and, without sending, a connected computer that no longer announces `deleteFinished`, above) | `FAILED` with `last_error_code = unknown_route`: "Needs your attention" with spec F-05 item 6's "not offered" sentence (19), "Try again" (for after an update) and "Keep the clip", no automatic retry, no notification (spec D-62, 7.7 Retries); not `maybe_sent` (no such route ran); never "already gone" | none (the clip stays on both sides; D-59 below) |
| `409 different_computer` | `PENDING`, `attempts + 1`, `RetrySchedule`, `last_error_code = different_computer`, which `ComputerDeletion` exposes as the wait `DIFFERENT_COMPUTER`: the reason "A different computer answered" (spec D-61, D-62); not `maybe_sent` (the computer refused before acting), so "Undo" stays; one event code is logged (server-contract 8.1: the phone never causes it) | none |
| `401 credential_rejected`, `403 phone_access_off`, too old | `PENDING`: the queue pauses as for every operation (QT-8, QT-19); after "Connect again" with the same computer the device id is the same (re-activation, 6.3), so it is sent then | none |
| `409 not_finished`, any other `4xx` code | `FAILED` with `last_error_code` and the reason sentence of 19 (spec D-62): "Needs your attention", no automatic retry, no notification (spec F-23) | none |

**State the learner sees** (`ComputerDeletion`, from the row and the derived queue pause, like 8.4): `WAITING`
(`PENDING`; with `DeletionWait.DIFFERENT_COMPUTER` while the row's last attempt was answered `different_computer`, since
every attempt stores its answer's code, or none, in `last_error_code`; `DIFFERENT_LIBRARY` while the computer uses
another library; otherwise `NOT_CONNECTED`, whose words come from the 7.5 status), `SENDING`, `REFUSED` (`FAILED`,
with `RequestError(code, message)`); `waiting` carries the row's attempts and next attempt as a `WaitingInfo`, so the
words of a waiting deletion are those of a waiting request, in spec D-61's order (19).
Actions (`DeletionAction`, listed in `ComputerDeletion.actions`, the one authority the screens read): `UNDO` while
`WAITING` and only while `maybe_sent` is not set (the row is deleted inside a transaction that re-reads both, so an
undo never races a send; posts `DELETION_UNDONE` once); `TRY_AGAIN` and `KEEP_CLIP` while `REFUSED` (back to `PENDING`
with `attempts = 0`, or the row deleted). **`maybe_sent`** is set when an attempt's outcome is unknown (the table above)
and when a row left `SENDING` by a process death goes back to `PENDING` at start (a `StartupTask`); it is never
cleared, because a later answer cannot tell whether that earlier attempt arrived. "Undo" would then post "Deletion
undone: “{title}” stays on your computer.", which may be false, so such a row offers no "Undo", keeps its waiting
words and adds the line "It may have reached your computer already, so it can't be undone." (19; spec D-65, review fix
C-61). Its retry carries the same key (the row's `id`), and the computer, which keeps no memory of keys,
answers `already_gone: true` if the first attempt had deleted the job: done either way (server-contract 6.11, spec
QT-16).

**While it waits.** `PendingDeletions.jobIds` (`PENDING`, `SENDING` and `FAILED` rows) feeds three places without new
coupling: `:core:sync` skips the clip's downloads (7.4) and leaves it out of the mass-deletion guard (7.7);
`:core:study` adds it to the set builder's exclusions, without touching the "Leave this clip out of the Mix" setting,
so new sets leave it out while sets made before keep it (spec F-06 States); a refused (`FAILED`) row holds its clip
the same way until "Try again" or "Keep the clip" (spec D-59); `ClipRepository` fills `ClipSummary.deletion`
(`WAITING` or `SENDING`, never for a refused one, spec D-59) for the Library rows and the Home shelf (spec F-04, F-03),
whose words follow the clip's `source.kind` (an imported clip's words, spec D-58; 19). The clip
stays studiable: nothing is removed before the computer confirms. `RequestRepository.counts()` counts waiting
deletions in `waiting` and refused ones in `needsAttention` (spec F-20-AC13, AC14); `deletions()` lists them for the
Requests screen (a row opens the Clip page; there is no request detail). Home's status card shows the attention row's
inline action from "the request in question" (design 9.17); a refused deletion is not a request, so when the items
needing attention include no failed request, the inline action is the oldest refused deletion's "Try again"
(`performDeletion(id, TRY_AGAIN)`), and with a failed request present it stays that request's action, as in v1-core
(spec D-59, F-03 item 2; WP-34 owns this in v1-complete, review fix C-68).

**Every case of spec 7.9**:

| Case | How it holds |
|---|---|
| Already deleted on the computer (browser, CLI, another phone) | `already_gone: true` is done; if a `deleted` event or the listing removed the clip first, `RequestReconciler.onJobDeleted` fulfils the row and posts its message once (7.7); never an error (QT-16) |
| Lost answer | the retry carries the same key; `already_gone` either way; the clip is removed once (QT-16); meanwhile the row is `maybe_sent`, offers no "Undo" and shows spec D-65's line |
| A different library | sent only while the computer uses the row's library; the server checks it too (`library_changed`); clips of another library are never offered (QT-17) |
| A forgotten computer | dropped by the device-id binding (6.3); clips "From a computer you forgot" are never offered (QT-18); a deletion already in flight is answered and applied before the unpair (`SyncController.exclusive`, 6.3; QT-18's variant in WP-28) |
| A computer without the route (downgraded after the deletion was stored) | refused (spec D-62): `404 unknown_route`, or the connected computer's `health` without `delete_finished` before anything is sent, makes the row `FAILED` with F-05 item 6's "not offered" sentence, "Try again" and "Keep the clip"; never read as "already gone" |
| A different computer at the address | nothing is sent there without a valid proof (5.3); the row waits (QT-20); an answer `409 different_computer` also waits, with "A different computer answered" and "Undo" (spec D-62) |
| A revoked phone | the queue pauses; the row is kept, and sent after "Connect again" with the same computer (same device id) (QT-19). One rare exception fails safe (spec D-63): if this phone's own credential key was lost (20.3: "Connect again" then pairs as a new device), the new device id drops the row as after "Forget", so the deletion is never sent and the clip stays on both sides, offered again, with no message |
| An imported analysis | allowed; its own words wherever the deletion is named (the block, the button, the confirmation, the states, the Requests title and the message, spec D-58), from `imported`; the computer only takes it off its list (server-contract 6.11) |
| The browser is showing it | the computer deletes it as the browser's own "Delete" does; nothing on the phone side changes for it |
| Refused | `FAILED`, "Try again" / "Keep the clip", no notification, the reason sentence of spec D-62; the clip stays out of new sets with its downloads paused until one is chosen, and rows and shelf cards show nothing (spec D-59); a transient failure is never a refusal |
| The clip is open | the removal waits for the `ClipLease` (QT-10) |
| Kept, or in today's set | not blocked; the set loses its shorts as for any deletion (7.7) |
| Other phones | they see an ordinary deletion on the computer, with their own mass-deletion guard |

**Never backed up**: the row lives in `library.db` (4.1), so a restored phone has no waiting deletion (spec 11.1).

---------------------------------------------------------------------------------------------------------------

## 9. Clip derivations (`:core:analysis`, `:core:corpus`, AD-17)

### 9.1 Parsing

`AnalysisParser.parse(input: InputStream, gzip: Boolean): ClipData` decodes `analysis.json` (or the compact gzip
rendition: same content) with kotlinx-serialization into lenient DTOs (every field optional with a default, unknown
keys ignored, `[phone, start, end]` arrays decoded by a custom serializer, `f0_track` into a `FloatArray` pair) and
maps them into compact domain classes. Older analyses, the espeak engine and rule versions 1 to 3 parse without errors
(NFR-08); a malformed file raises `ContentError.Corrupt`, which marks the clip "Needs downloading again". Parsing runs
on `Dispatchers.Default`; the largest real analysis (4.8 MB) must parse in well under the 1.5 s budget of NFR-02.

### 9.2 Shorts v1 (F-06 item 1)

Pure function `ShortsV1.derive(phrases: List<PhraseBounds>, clipEndS: Double): List<ShortBounds>`;
`RULE_VERSION = 1`. Definitions: a phrase is a segment `[start, end]`; a short's **length** is
`last.end - first.start`; a gap is `max(0, next.start - current.end)` (Whisper segments may overlap).

```
pass 1 (split):
  shorts = []; cur = [p0]
  for p in phrases[1:]:
    len_if_added = p.end - cur.first.start
    gap = max(0, p.start - cur.last.end)
    close = len_if_added > 30.0
         or (length(cur) >= 10.0 and gap >= 0.6)
         or gap >= 3.0
    if close: shorts += cur; cur = [p] else cur += p
  shorts += cur

pass 2 (merge), i = 0:
  while i < shorts.size:
    s = shorts[i]
    if length(s) < 5.0:
      if i > 0 and length(shorts[i-1] ∪ s) <= 30.0: shorts[i-1] = shorts[i-1] ∪ s; remove i; continue
      if i + 1 < size and length(s ∪ shorts[i+1]) <= 30.0: shorts[i+1] = s ∪ shorts[i+1]; remove i; continue
    i += 1

play bounds: start = max(0, first.start - 0.25); end = min(clipEnd, last.end + 0.4)
identity: ShortId(jobId, firstSegment) + RULE_VERSION
```

Each merge removes one short, so pass 2 terminates; the union evaluated next may merge again. Checked by hand against
F-06-AC1 (P0 0–4, P1 4.3–11, P2 11.8–14, P3 14.2–20, P4 23.5–25 → {P0,P1} play 0–11.4 s; {P2,P3,P4} play
11.55–25.4 s). F-06-AC2 becomes a property test over synthetic phrase timings (no short over 30 s unless one phrase
is; no phrase split; every phrase in exactly one short; deterministic).

### 9.3 Spotlight (F-07 item 3)

Candidates of a short: words with at least one change, not low confidence, lasting ≥ 60 ms, with a heard form (the
web's playable rule: `too_short` = duration < 0.06 s, `low_confidence` false). Deterministic order:
(1) Saved mode or a saved short in a set → the saved word; (2) change mode → the clearest candidate with that change;
(3) "Safe to produce" before "Recognize only"; (4) lowest `diff_cost`; (5) earliest word. A word whose change crosses
the boundary (`boundary_link_next`, or linking/palatalization, web `crossesBoundary`) plays with the next word. The
default spotlight (rules 3–5) is precomputed into `short.default_spot_*`; modes 1–2 are resolved at query time from
`occurrence`.

### 9.4 Occurrences, examples, variants

`OccurrenceExtractor` produces one row per word × change with eligibility, family and advice (from the reference). The
phone's equivalents of the server corpus endpoints are queries on `occurrence` (D-16), restricted to playable clips
(study pack on the phone, spec F-06 item 2). Change counts come in the spec's two units (D-48): the **short count**
of a change is the number of distinct `(job_id, short_first_segment)` among its eligible rows, which is by
construction the number of shorts `ShortRepository.sequence(FeedMode.Change(label))` plays (the spotlight candidates
of 9.3 are exactly the eligible rows), so "Flapping · 34" opens 34 shorts (F-03-AC7); the **occurrence count** is the
number of its eligible rows ("12 times in your clips" on the phenomenon page only, F-15-AC1). Both come from one
grouped query (`OccurrenceRepository.changeCounts()`, 3.4). The other queries are
examples (`pickExamples`: eligible, ascending `diff_cost`, one per `word_key`, limit 8), symbol examples (the realized
or dictionary IPA contains the symbol, tokenized with `reference.ipa_tokens`), quiz pools (80 per change) and word
variants (group by `word_key` and `realized_ipa`).

### 9.5 Where it runs and is stored

`ClipIndexer` (`:core:corpus`) runs after the clip-data artifact of a clip is verified (called by the sync pass) and
after an app update raises `RULE_VERSION`: parse → derive shorts → derive occurrences → replace the clip's `short` and
`occurrence` rows in one transaction → set `indexed_revision` and `index_rule_version`. Parsed `ClipData` is kept in
an LRU cache of three clips keyed by job id and revision for the feed, the lesson and the Clip page.

---------------------------------------------------------------------------------------------------------------

## 10. Study logic (`:core:study`)

- **Seen** (F-06 item 5): the feed reports time actually played while advancing (any speed; seeks excluded) through
  `ProgressRepository.recordPlayed`; a short is seen at 80 % of its length in one visit.
- **Sets** (F-08): `SetBuilder` (pure) takes eligible shorts (with spotlight), seen history, saved items, clip
  exclusions, today's local date and a seed (`localDate.toEpochDay()` for today's set; a counter for extra sets) and
  returns 8 shorts: up to 2 saved shorts not watched for ≥ 3 days, then unseen shorts (newest clip first, "Safe to
  produce" spotlights first), then shorts not seen for ≥ 7 days (least recently first); interleaving: no two
  consecutive spotlight changes alike when another order exists, at most 2 consecutive shorts of one clip (greedy with
  backtracking over 8 items). Questions (up to 5) are generated at set creation from the set's changes and stored, so
  the set never changes; clips deleted later only mark items `dropped`.
- **Questions** (F-08, F-17): `QuizGenerator` is a Kotlin port of `web/src/features/practice/quiz.ts` (mulberry32
  `seededRng`, `shuffle`, the three kinds, distractor rules, weak-spot weighting `1.5 − accuracy`, unseen 0.5); ported
  with its tests (the vitest cases become JUnit cases with the same inputs and expected outputs).
- **Practice stats** use the web's `{seen, correct}` per change; "changes met" records a change when a short with that
  spotlight was seen or a question on it answered.
- **Shared progress** (v1-complete, CR-01, 7.8): `:core:study` implements `SharedProgressLedger` and the two
  `sharing()` flows. `SharingState` is `NotShared` when no computer is paired, the paired computer does not announce
  `shared_progress`, or `share_state` names another computer; `ResetWaiting` (Practice only) while a reset waits;
  otherwise `Shared(lastSyncedAt)` (`practice_synced_at` for Practice, `saved_synced_at` for Saved). Accuracy, "Worth
  reviewing", weak-spot weighting and the Home practice card keep reading `practice_stat`, which the exchange keeps as
  the shared totals plus this phone's unshared answers (`own_stat` keeps this phone's own answers apart, only for a
  first share with another computer, 7.8); a change is met when `change_met` has it, and the exchange adds
  every change the shared totals have answers on (spec F-17 item 6). `PracticeRepository.reset()` picks the shared or
  the phone-only reset from `SharingState`, and the confirmation copy follows the same state (WP-34). Saved items
  arriving from other phones are ordinary `saved_item` rows with `last_watched_at` empty, so a short saved elsewhere
  and never watched here counts as not watched for 3 days or more (spec F-09 item 5). The set builder's exclusions
  also take `PendingDeletions.jobIds` (8.8).

---------------------------------------------------------------------------------------------------------------

## 11. Media playback (`:core:player`, AD-18)

### 11.1 Audio is the clock; one player per short

- Each short is played by one ExoPlayer whose source is a `MergingMediaSource` of (a) the analysed audio file of the
  chosen track (WAV or Opus), **first**, and (b) the local video file (original or phone video). The original has its
  own audio track, so the merged source exposes two audio groups that `setTrackTypeDisabled(AUDIO)` cannot tell apart;
  the player therefore sets a `TrackSelectionOverride` on the audio group of child 0 (merged group ids carry the child
  index), and the merged timeline, which comes from the first child, is the analysed audio's (Android review M3; an
  instrumented test with a synthetic video whose own audio is a different tone checks that the WAV is the one heard).
  Merging gives one timeline and one clock, so captions, the mouth and the video follow the analysed audio exactly,
  with no drift loop. The video is wrapped in a `ClippingMediaSource` that skips `video_offset_ms` when
  the offset is positive. A negative offset (the video starts after the analysed zero; none exist today) falls back to
  follower mode: a second, muted player re-synced when drift exceeds 0.2 s (the web's `MAX_DRIFT`).
- The short's play bounds are applied with a `ClippingMediaSource(playStartUs, playEndUs)` around the merged source;
  "Replay it once" seeks to 0; "Loop: Short" uses `REPEAT_MODE_ONE`; "Loop: Phrase" and "Replay" use player messages
  at the phrase end.
- Video absent, unplayable or not available → the same player with the audio source only; the card shows the poster
  (or the generated poster fallback) or the generated audio visual. A video decode error (`onPlayerError` from the
  video renderer) rebuilds the source audio-only at the same position, records `CannotPlayOnThisPhone`, and (v1-complete)
  asks for a phone video (F-06 error state). Corrupt or missing audio → "This short couldn't be played." and the clip is
  marked "Needs downloading again".
- Speed 0.5×/0.75×/1× with `PlaybackParameters(speed, pitch = 1f)` (pitch kept). "Hold to slow down" sets 0.5× while
  held. Track switch rebuilds the source with the other audio file at the same position.
- **Track fallback** (spec F-06 Partial, D-47). When a short is prepared, the player picks the chosen track's file
  from its `ClipMedia`; if that file is absent it plays the other one and sets `FeedPlaybackState.trackFallback`:
  `DIALOGUE_NOT_ON_PHONE` (dialogue chosen, its file not downloaded yet), `MIX_NOT_ON_PHONE` (mix chosen, only the
  dialogue present), `NO_DIALOGUE_TRACK` (dialogue chosen, the clip was analysed without one); null otherwise. The
  short card (WP-17) words it under the clip line. A short that is playing never switches track by itself; when a
  clip's artifacts change, WP-17 re-issues `setQueue` with fresh `ClipMedia` from `ClipContentRepository.media()`
  (same items, same index), the pool re-prepares the neighbours, and the next short of that clip plays the arrived
  track with the line gone (F-06-AC13).

### 11.2 Player pool for the vertical pager

`FeedPlayerPool` holds three ExoPlayer instances (previous, current, next). When the pager settles on page *n*, the
pool assigns players to *n-1*, *n*, *n+1*, prepares their sources (local files; preparation is tens of milliseconds
for audio and ~100–200 ms for a video decoder) paused at their start, and starts *n* (SC-08: swipe to sound ≤ 300 ms
at p90). Media3's `DefaultPreloadManager` is evaluated in WP-11 as a drop-in for the preparation step; the pool
interface does not change either way. The pool is owned by the Watch entry's ViewModel (survives rotation and, since
revision 3, tab switches, 16.2) and released in `onCleared`.

**Decoders are a scarce resource** (Android review M2). Media3 initializes a video codec even without a display
surface (it uses a placeholder surface), so three prepared players mean three video decoders, today probably three
software AV1 decoders, plus the lesson's span player over the feed. Rules:
- On `ON_STOP` of the process lifecycle (playback stops when the app leaves the foreground, D-28) and whenever Watch is
  not the visible tab, the pool `stop()`s every player (codecs released; positions kept in the ViewModel) and prepares
  again on return. A paused-but-prepared pool in the background would also lose its codecs to the media resource
  manager and come back with a codec error.
- Only the current short renders to a surface (`PlayerSurface` from `media3-ui-compose`); the card shows the poster
  (or the generated fallback) until `onRenderedFirstFrame`, so a surface switch never flashes black.
- Documented fallback if the measurements (WP-11 on the emulator, WP-25 on the test phone: `dumpsys meminfo`, the
  decoder instance count while swiping AV1 720p clips, swipe-to-sound including the keyframe distance of a jump) fail
  SC-08 or memory: neighbours are prepared with the video track disabled and enable it when the page settles, or
  `DefaultPreloadManager` with one or two players. The choice is recorded in `decisions.md`.

### 11.3 Captions timing

While playing, the card reads `player.currentPosition` every frame (`withFrameNanos`); ExoPlayer's position already
accounts for audio output latency. The current word is filled from 50 ms before its start to 0.35 s after its end
(web rule); target: highlight within 60 ms of the word's start (SC-09), verified with a synthetic clip in an
instrumented test.

### 11.4 One sound at a time, audio focus, noisy

`AudioArbiter` is the single owner of "who is playing" (feed, span player, quiz, record A/B, shadowing): a new claim
pauses the previous owner. Audio focus is requested by the arbiter itself (players are built with
`handleAudioFocus = false`), so a call or another app **pauses and never resumes by itself** (spec F-06 item 6):
`AUDIOFOCUS_LOSS` and `AUDIOFOCUS_LOSS_TRANSIENT` pause with no automatic resume; `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`
(a notification sound) **never pauses**: the arbiter lowers the volume to 0.2 and restores it on `AUDIOFOCUS_GAIN`,
and the focus request sets `setWillPauseWhenDucked(false)` so the system does not convert ducks into pauses for speech
content (Android review M4; a Robolectric test per focus-change code). `setHandleAudioBecomingNoisy(true)` pauses on
unplugging earphones. Audio attributes: `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`.

### 11.5 Span player and shadowing

`SpanPlayer` (lesson, Practice, Learn examples, saved rows) plays a `[start, end]` span of a clip's local audio with a
speed, a track and an optional loop; boundary changes add the next word (web `crossesBoundary`); very short words are
padded to at least 0.25 s audible (web rule). `ShadowingRunner` executes the plan of the pure `ShadowingPlan` (in
`:core:common` `shadowing/`, owned by WP-12, revision 3)
(Kotlin port of `web/src/features/analysis/lib/shadowing.ts`: 3 slow rounds + 1 full, silence = listen + 400 ms; 3
rounds when the slow rate is 1×) over the span player, publishes the phase text for the polite announcement and the
optional haptic tick, and restores the previous speed on stop, swipe or audio-focus loss.

### 11.6 Generated audio-only visual and poster fallback

`AudioOnlyVisual` (`:feature:watch`) draws the current phrase's pitch line from `f0_track` (semitones around the
clip's median), seeded by a stable hash of the job id (FNV-1a 32-bit) for its layout and its neutral palette choice
from the design system (never family hues), so each audio clip looks different and identical every time; with reduced
motion it does not move except with playback. The poster fallback is generated the same way from the title (design
7.4) by `PosterArt` (`:core:ui`, WP-03), the one composable that shows a clip's picture everywhere (Home, Library
rows, the Clip page hero, the short's media area: the poster, else the fallback for a video clip, else the audio art);
it is not in `:feature:watch` because Home and the Library cannot depend on a feature (2.2 rule 1). Rendered
fallbacks are cached in `cacheDir/posters/`. Both are composables; no bitmaps are bundled (the static
`ill_poster_fallback` of the export is a vector drawable).

### 11.7 Decoder capability

`DecoderCapabilities` (7.4) is also exposed in Connection details (debug and release) as a short list "AV1 up to …,
H.264 up to …", each marked hardware or software (`DecoderCapabilities.summary()`), which device QA records (NFR-14).

---------------------------------------------------------------------------------------------------------------

## 12. Share target and "Add a clip" (`:feature:share`, `:feature:requests`, AD-19)

- `ShareActivity` (`:feature:share`, `@AndroidEntryPoint`) is **exported** with three intent filters:
  `ACTION_SEND` + `text/plain`; `ACTION_SEND` + `video/*` and `audio/*`; `ACTION_SEND_MULTIPLE` + `video/*` and
  `audio/*`; each with `CATEGORY_DEFAULT`. Label "PhonoTrainer", the launcher icon. Theme translucent with no title,
  so the sharing app stays visible behind the bottom sheet; standard launch mode, `excludeFromRecents="true"` and
  **`android:taskAffinity=""`** (Android review H3, security review L-11). Without the empty affinity, a sharer or an
  OEM chooser that launches the target with `FLAG_ACTIVITY_NEW_TASK` would put the sheet into PhonoTrainer's own task
  (rooted by the `singleTask` `MainActivity`), bring the whole app forward and return the learner to PhonoTrainer
  instead of YouTube or X (SH-AC11); `excludeFromRecents` only acts on the root of a new task. In the normal case the
  sheet runs in the sharing app's task and `finish()` returns the learner there; when it is the root of its own task
  (the `NEW_TASK` case) it closes with `finishAndRemoveTask()`. The sheet handles the navigation-bar insets itself
  (edge-to-edge is enforced at target 36, 16.5).
- Input is untrusted: only `EXTRA_TEXT`/`EXTRA_SUBJECT` (length-limited to 8 KiB), `EXTRA_STREAM` and the intent's
  `ClipData` URIs are read; URIs must be `content://` and must not belong to this app's own providers; names, types
  and sizes reported by the provider are not trusted (8.3); at most 10 items; the activity never forwards intents or
  opens arbitrary URIs.
- The sheet starts a fresh probe and waits at most 2 s (spec 8.4); after the tap, the request is stored first; a file's
  copy is handed to the expedited intake work (8.3), and a link while connected is sent by the expedited `send-now`
  work (7.2), both enqueued while the sheet is visible, never by the bare application scope, which Android 14+ freezes
  once the sheet closes. The sheet closes 2 s after the request is stored (and after the copy completes for files),
  never with TalkBack on and never while the notification question it opened is open; the 2 s count starts again
  once it is answered (8.1, spec D-51).
- A copy that fails for space while the sheet is open (`COPY_FAILED` `copy_no_space`) shows the space needed and,
  for a video, "Try audio only" (spec 7.7, SH-AC15; design 9.22): the sheet calls `RequestRepository.perform(id, TRY_AUDIO_ONLY)`, which is
  offered because the sheet still holds its sources (`holdSources`, 8.3), and the same request goes back to
  `PREPARING` for the audio track; no second request is created and no partial copy is left. Once the sheet has
  closed, the row in Requests offers "Remove" only (spec D-49).
- "Open PhonoTrainer" and "See requests" start `MainActivity` through the internal alias (16.4) with
  `FLAG_ACTIVITY_NEW_TASK`, then `finish()`.
- The in-app "Add a clip" screen (`:feature:requests`) uses the same `RequestRepository`; "Paste" reads the clipboard
  only on tap; "Choose a file" uses `ActivityResultContracts.OpenMultipleDocuments` (video/*, audio/*, max 10).

---------------------------------------------------------------------------------------------------------------

## 13. Notifications (`:core:notifications`)

- Channels created at application start (idempotent): `clips_ready` ("Clips ready", default importance),
  `needs_attention` ("Needs attention", default), `transfers` ("Transfers", low, silent).
- `Notifier` posts only for this phone's own requests (the caller passes request or clip ids; browser jobs never reach
  it), respects the permission and the two Settings switches, and posts each outcome once (flags in `request`).
  "Ready to watch" notifications are grouped with a summary when several arrive. Notification text never contains an
  address or a link. The text of "Needs attention" comes from `RequestFailureCopy.notificationLine` (19), the same
  mapping the request rows use: "Couldn't download {site} link — Tap to try again or try audio only." only for
  `download_forbidden`, and for every other failure its spec 8.6 title + " — Tap to see what you can do." (spec F-23,
  D-40, F-23-AC4), so a notification never promises an action the request lacks.
- Lock screen: clip titles and links are third-party text, so every notification is `VISIBILITY_PRIVATE` with a
  public version whose text is the channel's name ("Clips ready", "Needs attention", "Transfers"), already spec copy
  (security review L-12).
- Taps use `PendingIntent.getActivity` with `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`, targeting the internal alias
  `.InternalEntry` with a serialized `NavTarget` (16.4). PendingIntent identity ignores extras, so each target gets its
  own request code (a stable hash of the serialized `NavTarget`) and a unique `data` URI on the intent
  (`phonotrainer-internal:<hash>`, matched by no intent filter), and two notifications for two clips open two clips
  (Android review M7).
- `POST_NOTIFICATIONS` is requested in context after the first stored request (F-23); `permissionState` lets Settings
  show "Notifications are off for PhonoTrainer" + "Open settings".
- v1-complete (CR-01): deletions from your computer and shared progress never notify (spec F-23): `:core:requests`
  never calls `Notifier` for a `DELETE_FINISHED` row, refused or not, and the progress exchange has no notification at
  all; their outcomes are one-shot `UserMessages` (8.8) and the Home and Requests counts.

---------------------------------------------------------------------------------------------------------------

## 14. Recording (v1-complete: `:core:recorder`, `feature/lesson/.../record/`)

- `TakeRecorder` records with `AudioRecord` at 16 kHz mono PCM16 to `cacheDir/takes/<uuid>.wav` (WAV is accepted by
  `/compare`; 30 s is under 1 MB, far below the 8 MiB limit). Source: `MediaRecorder.AudioSource.UNPROCESSED` when
  `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` is true, otherwise `VOICE_RECOGNITION` (no automatic gain
  or noise suppression on most devices; the web disables both). Auto-stop at 30 s; takes under 0.25 s are discarded;
  a level meter flow drives "We can barely hear you…" / "Too loud…".
- "Play the original first": span player, then 250 ms, then the microphone. A/B: "Original" / "Yours" /
  "Alternate" (three times, 500 ms gaps), "Yours" trimmed to the speech and loudness-matched (RMS) in memory.
- `TakeComparer` sends `POST /api/phone/jobs/{id}/compare` as multipart with a file body of known length
  (`Content-Length` set; never chunked), `start`, `end`; maps the server codes to the F-13 copy; a 20 s timeout gives
  "Measuring took too long. Try again."
- Takes are deleted when the learner leaves the word (the lesson entry's `onCleared`) and at every start; never in
  shared storage, never queued, never backed up (`cacheDir` is excluded by the backup allowlist).
- `RECORD_AUDIO` is declared in `:core:recorder`'s manifest (merged only when the module lands) and requested at the
  first "Record" with the spec's rationale.

---------------------------------------------------------------------------------------------------------------

## 15. Didactic static content (S-18, D-09, AD-20)

### 15.1 Extractor

`android/tools/didactic/extract.mjs` is a Node script with **no npm dependency**. It uses Node's built-in TypeScript
type stripping (Node ≥ 22.18 strips types by default; the script checks the version and otherwise re-executes itself
with `--experimental-strip-types`) and a small resolve hook (`ts-resolve-hook.mjs`, registered with
`module.register`) that maps extension-less relative imports to `.ts` files. It imports, read-only:

| Web file | Exports used |
|---|---|
| `web/src/didactic/glossary/index.ts` (and through it `terms.ts`, `metrics.ts`, `ipa.ts`) | `GLOSSARY` |
| `web/src/didactic/glossary/ipa.ts` | `SYMBOL_PHENOMENON` |
| `web/src/features/learn/content.ts` | `LESSONS` |
| `web/src/features/learn/ipaLayout.ts` | `PLACES`, `CONSONANT_ROWS`, `ALLOPHONE_PLACES`, `ALLOPHONE_ROWS`, `SYLLABICS`, `VOWEL_COLUMNS`, `VOWEL_ROWS`, `REDUCED_VOWELS`, `GLIDING_GROUPS`, `placedSymbols()`, `unplacedSymbols()` |
| `web/src/articulation/phones.ts` (with `pose.ts`) | `PHONE_TABLE` (name, example, cue, manner, voiced, gestures with numeric poses, carry) |

These files import only each other and `web/src/paths.ts`; nothing from `node_modules` is needed (verified). The
script:
1. refuses to run if its output path resolves inside `web/`, and never opens a file under `web/` for writing;
2. converts web `href`s (`learnMore.href`) into app link targets through a strict table (`/learn` → `learn`,
   `/learn/<p>` → `phenomenon`, `/learn/ipa?symbol=` → `ipa`, `/practice?focus=` → `practice`, `/insights` →
   `insights`); an unknown href fails the run;
3. validates the shapes it reads (every lesson demo symbol exists in the phone table, every glossary entry has a title
   and a body) and fails loudly on any unexpected shape (it depends on that shape on purpose);
4. writes `core/content/src/main/assets/didactic/didactic.json` deterministically (sorted keys, two-space indent, LF,
   trailing newline) with a header:

```json
{
  "schema": 1,
  "sources": [{"path": "web/src/features/learn/content.ts", "sha256": "…"}],
  "glossary": {"weak-form": {"title": "Weak form", "body": "…", "example": "to → tə", "learnMore": {"kind": "learn"}}},
  "symbolPhenomenon": {"ɾ": "flapping"},
  "lessons": {"flapping": {"listenFor": "…", "demo": {"word": "water", "dictionary": ["w", "ɔ", "t", "ɚ"], "said": ["w", "ɔ", "ɾ", "ɚ"], "from": "t", "to": "ɾ"}, "spanishTip": null, "note": null}},
  "ipaLayout": {"places": [], "consonantRows": [], "allophonePlaces": [], "allophoneRows": [], "syllabics": [], "vowelColumns": [], "vowelRows": [], "reducedVowels": [], "glidingGroups": [], "placed": [], "unplaced": []},
  "phones": [{"symbol": "ɾ", "name": "voiced alveolar tap", "example": "water", "cue": "…", "manner": "tap", "voiced": true, "gestures": [], "carry": null}]
}
```

### 15.2 Drift check

`./gradlew :core:content:verifyDidacticContent` runs `node tools/didactic/extract.mjs --check`, which regenerates the
JSON in memory and fails on any byte difference with the bundled asset, printing the changed keys. It is part of
`check`, of CI (with Node installed) and of the definition of done of any work package touching `:core:content`. If
`node` is missing the task fails with "Node 22.18 or newer is required to verify the didactic content (the web build
already needs it)". A JVM test in `:core:content` decodes the asset with the Kotlin model, so a schema change on either
side fails a test.

### 15.3 Kotlin side

`DidacticContentImpl` lazily decodes the asset once (a few hundred KB) on `Dispatchers.Default` and serves
`GlossaryEntry`, `LessonContent`, `IpaLayout`, `PhoneArticulation` and a search over titles and bodies ("Words we
use", F-14-AC3). Rich text rules of the web (IPA inside `/…/`, `[…]` or after "→" set in the IPA font) are implemented
in `:core:ui` (`IpaText`).

### 15.4 Reference snapshot

`/api/reference` is computed by the Python server, so it is not extracted from `web/`. `tools/reference/export.py`
(run with the repository's virtual environment) builds the server app with `store=JobStore(<tmp>, corpus=None)` on a
throwaway directory (never `create_app()`'s default store, whose `data/phonotrainer.db` is relative to the working
directory and, from the repository root, is the owner's corpus) and writes
`core/content/src/main/assets/reference/reference.json` from `GET /api/reference`. The app ships that snapshot and
replaces it at runtime whenever the computer's `api_version` differs (7.1 step 5), so a stale snapshot is never wrong
for long; a JVM test checks the snapshot has 16 labels, the 4 families and practice advice for every label.

---------------------------------------------------------------------------------------------------------------

## 16. Navigation (Navigation 3, AD-21)

### 16.1 Keys

All keys are `@Serializable` classes implementing `NavKey`, declared in `:core:navigation` and registered in one
`SerializersModule` (polymorphic `NavKey`) used by every saved back stack.

| Spec id | Key | Arguments |
|---|---|---|
| T1 | `HomeKey` | — |
| T2 | `WatchKey` | `start: FeedStart?` (`TodaySet`, `Resume`, `InOrder(jobId, firstSegment?, fromStart)`, `ChangeMode(label, onlyClip?)`, `SavedMode`, `AtWord(jobId, segment, word, openLesson)`) |
| T3 | `LearnKey` | — |
| T4 | `PracticeKey` | `focus: String?` |
| P-01 | `LibraryKey` | — |
| P-02 | `ClipKey` | `jobId`, `tab` (`SHORTS`, `TRANSCRIPT`, `SUMMARY`) |
| P-03 | `RequestsKey` | — |
| P-04 | `RequestDetailKey` | `requestId` |
| P-05 | `AddClipKey` | `start` (`CHOOSER`, `PASTE`, `FILES`) |
| P-06 | `ConnectKey` | `mode` (`FIRST_RUN`, `FROM_HOME`, `CONNECT_AGAIN`, `NEW_ADDRESS`) |
| P-07 | `ConnectionDetailsKey` | — |
| P-08 | `PhenomenonKey` | `label` |
| P-09 | `IpaChartKey` | `symbol: String?` |
| P-10 | `InsightsKey` | — (v1-complete) |
| P-11 | `SavedKey` | — |
| P-12 | `SettingsKey`, `StorageKey`, `YourComputerKey`, `NotificationSettingsKey`, `AboutKey`, `LicencesKey`, `HelpKey` | — |
| P-13 | `WelcomeKey` | `restart: Boolean` |
| F-14 | `GlossaryIndexKey` ("Words we use") | — |
| F-10 | `LessonKey` (overlay) | `jobId`, `segment`, `word`, `origin`, `presentation` (`SHEET`, `FULL`) |
| F-11 | `PhraseDetailsKey` (overlay, v1-complete) | `jobId`, `segment` |

Sheets that are not destinations (the "What's this?" sheet, the feed mode picker, the short "More" sheet,
confirmation dialogs, coach marks) are local UI state of their screen.

### 16.2 Tabs, back stacks and Back

`AppNavigator` (in `:app`, implementing `Navigator` from `:core:navigation`) keeps one `NavBackStack` per tab
(`rememberNavBackStack` with the shared serializers configuration, so stacks survive rotation and process death) and
the current tab (`rememberSaveable`). The screen shows the concatenation *Home stack + current tab stack* (Home at the
bottom), which gives the spec's Back order: close the top overlay, then pop the secondary destination, then from a tab
root go to Home, then from Home leave the app. Reselecting the current tab pops to its root, except Watch (no-op).

**Each stack is decorated on its own** (Android review H1). Navigation 3 decorators clean up an entry when it leaves
the list they decorate (in navigation3-runtime 1.2.0, `NavEntryDecorator`'s `onPop` runs for entries that disappear
from the decorated list). Decorating one list made of "Home + current tab" would therefore discard the inactive tab's
saved state and clear its `ViewModelStore` on every tab switch, losing the Watch ViewModel and its player pool, the
Library's scroll, search and filter (F-04-AC5) and the Learn and Practice stacks. Instead, `AppNavigator` calls
`rememberDecoratedNavEntries(stack, decorators, entryProvider)` once **per stack** (Home and the four tabs), with
`rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()`, and keeps all five
calls composed whatever the current tab is; it passes `homeEntries + currentTabEntries` to the `NavDisplay(entries =
…)` overload. Only a real pop (Back, pop-to-root, a removed destination) runs `onPop`. Content keys get a per-stack
prefix (`"watch/" + key`), so the same key in two stacks (for example `ClipKey(jobId, SHORTS)` reached from Home and
from Watch) never collides in the saveable-state holder. Because the Watch ViewModel now outlives tab switches, the
player pool releases its decoders while Watch is not visible (11.2). Proof (WP-02 M2): a GMD test opens the Library,
searches, switches to Learn and back, and asserts the same ViewModel instance, query and scroll position; the same
after `recreate()` and after a process-death restore.

Overlays use scene strategies: `LessonKey` with `presentation = SHEET` and `PhraseDetailsKey` render through a
`BottomSheetSceneStrategy` (an `OverlayScene` that keeps the feed composed and paused beneath; the official Nav3
bottom-sheet recipe), `FULL` renders as a normal entry. The lesson's word and step are in its ViewModel's
`SavedStateHandle`, so F-10-AC6 (process death at step 3) restores without extra code.

### 16.3 Entries contributed by features

```kotlin
// :core:navigation
fun interface EntryInstaller { fun EntryProviderScope<NavKey>.install(navigator: Navigator) }
interface Navigator {
  fun navigate(key: NavKey)
  fun back()
  fun switchTab(tab: Tab)
  fun open(target: NavTarget)
}
```

Each feature binds its installer with `@IntoSet` in its own Hilt module; `MainActivity` builds
`entryProvider { installers.forEach { with(it) { install(navigator) } } }`. WP-02 creates every feature module with an
installer whose entries render a stub screen (title + lede of the destination), so every destination is navigable from
day one and each feature work package replaces only its own installer.

### 16.4 Internal targets (spec 5.4)

`NavTarget` (sealed, serializable): `ClipFeed(jobId)`, `RequestDetail(requestId)`, `Requests`, `Home`,
`WordLesson(jobId, segment, word)`. They are delivered only through the **non-exported** activity alias
`.InternalEntry` (target `MainActivity`), used by notification `PendingIntent`s and by `ShareActivity`. `MainActivity`
(`launchMode="singleTask"`, the only exported launcher entry) honours the `NavTarget` extra only when
`intent.component` is the alias, in `onCreate` **only when `savedInstanceState == null`**, and in `onNewIntent`; after
applying it, it replaces the activity's intent with one without the target (`setIntent`), so a rotation or a restore
after process death never navigates again (Android review M7). `Navigator.open()` resolves existence through the
repositories and falls back to the parent with the spec's notice ("That clip is no longer on this phone.", "This
request was removed."). No URI scheme is registered (the notification intents' `data` URIs of section 13 only make
`PendingIntent`s distinct; no intent filter matches them).

### 16.5 Start-up

`installSplashScreen()` before `super.onCreate`, `enableEdgeToEdge()`, root `semantics { testTagsAsResourceId = true }`.
The splash stays until settings are read and the start destination is known (Welcome on first launch from the launcher;
never for a share). Window backgrounds match each theme (no white flash): the shell applies
`UserSettings.appearance` at the root and, on API 31+, calls `UiModeManager.setApplicationNightMode` for Light or Dark
through the helper WP-03 provides in `:core:designsystem`, so the next cold start's splash matches (design 12.4). `androidx.profileinstaller` installs the
Baseline Profile generated by `:baselineprofile` (21.6) on a sideloaded install.

What targetSdk 36 implies, stated so no WP rediscovers it (Android review L6): edge-to-edge is enforced (no opt-out),
so every screen and the translucent `ShareActivity` sheet consume system-bar insets themselves; predictive back is on
(`enableOnBackInvokedCallback="true"`), so Back handling uses `BackHandler`/`NavDisplay` and never overrides
`onBackPressed`; on displays of 600 dp and wider, orientation and resizability declarations are ignored. The app
declares **no orientation lock**: portrait is the designed orientation, and rotation and split screen must not lose
state (spec 5.2), which the per-stack decoration above and `SavedStateHandle` give.

---------------------------------------------------------------------------------------------------------------

## 17. Design system integration (`:core:designsystem`, `:core:ui`)

The design track delivers `android/docs/design.md` and assets in `android/design-src/export/` (staging). This
architecture fixes the **API**, not the visuals:

- `PhonoTheme(darkTheme, colors: ColorChoice, patterns, reducedMotion) { … }` provides a complete explicit Material 3
  `ColorScheme` for light and dark (dynamic color off, as Artemi: the palette is the identity) and
  `PhonoTheme.colors` for roles Material 3 has no slot for: the four family hues (standard and color-vision-friendly
  sets), mark ink, scrims over video, status surfaces, neutral decorative tones for the audio visual. Typography from
  the three bundled OFL faces (Atkinson Hyperlegible Next for the UI, Newsreader for the display voice, Charis 7.000
  for IPA; unmodified TTF, design 4.1), shapes, spacing, motion durations and the reduced-motion switch. Dark mode:
  Compose colours follow `darkTheme`; a configuration context whose `uiMode` follows the in-app appearance makes
  `drawable-night` illustrations match it; window and splash colours come from `values-night`; the shell applies the
  appearance to the window through a WP-03 helper (16.5; design 12.1, 12.4).
- `:core:ui` components (API frozen at M2, visuals by WP-03): `FamilyMark`, `MarkedWordSpan` builder (line style +
  icon + name), `ChangeChip`, `PracticeBadge`, `StatusLine` (icon + words), `AvailabilityChip`, `ExplainButton` +
  `ExplainSheet`, `IpaText`, `LoadingGate`, `EmptyState(illustration, title, body, actions)`, `CoachMarkHost`,
  `PhonoButton`/cards/sheets, the visual-only pieces several features draw (`ShortProgress`, `PosterArt` with the
  poster fallback of 11.6, `MarksLegend`; design 12.2), plus extension points for v1-complete features
  (`MouthRenderer`, `RecordSection`, `PhraseDetailsEntry`, `ClipTranscriptTab`, `InsightsAvailability`, and, since
  revision 2, `ClipReductionMeters`) bound with Dagger `@BindsOptionalOf`, so v1-core screens show the spec's
  fallbacks when a v1-complete module is absent. Their shapes are in work-plan 4.4. Domain-aware components shared by
  several features (`ClipCard`, `ClipRow`, `RequestRow` with `RequestFailureCopy`, `QuestionCard`, `AnswerFeedback`,
  `ShadowingPanel`, `OccurrenceRow`, `ConnectionStatusCard` with `ProbeOutcomeCopy`, `ShareBridge` with
  `ConnectionLine`) live in `:core:ui` `component/`, each built by one feature WP behind an API frozen at M2, with its
  own strings file (work-plan 4.5).
- Assets from staging are converted by WP-03 (`android/design-src/convert/`) into `:core:designsystem` and committed
  with their sources under `android/design-src/`; nothing in the build references the staging folder. Icons,
  illustrations, launcher layers, the monochrome and notification icons and the splash are vector drawables. The two
  adaptive-icon XMLs live in `res/mipmap-anydpi/` (a `-v26` qualifier is obsolete at minSdk 29 and lint reports it as
  `ObsoleteSdkInt`); **no legacy raster mipmaps** are made, because every supported device (API 29+) draws the
  adaptive icon (spec F-25, D-50). Every `pathData` stays under lint's `VectorPath` limit of 3,000 characters (the
  design track's export already splits the launcher background's halftone; design 12.3), because lint runs with
  `warningsAsErrors` (23). The licence texts go to `assets/licenses/` with their index `bundled.json` (20.6).
- Rules carried from Artemi: tokens only in screens (no hard-coded colors, fonts or sizes; a lint rule or hygiene
  grep enforces it), WCAG contrast on every surface a color can sit on (A-10), 48 dp targets, decorative art excluded
  from semantics, `@PreviewLightDark` previews of every state.

If `design.md` defines roles this API lacks, WP-03 adds them (additive only). Feature work packages that run before
WP-03 finishes use the placeholder theme WP-02 provides; nothing in their code changes when the real values land.

---------------------------------------------------------------------------------------------------------------

## 18. Accessibility hooks (section 10 of the spec)

- Root `testTagsAsResourceId = true`; every interactive element has a test tag and an accessible label (Artemi
  contract).
- `:core:ui` provides `Modifier.shortSemantics(summary, actions)` (one summary plus the custom actions Play/Pause, Next
  short, Previous short, Replay phrase, Slower, Open lesson, Save, F-07-AC11), `Modifier.paneTitle()`, heading helpers,
  `IpaText` with an accessible text built from symbol names (A-05), word buttons whose label is "word, changes, advice"
  (A-04).
- `Announcer` is the only way to make polite announcements, and it accepts only the allowed event kinds (stage
  changes, answer results, shadowing phases, recording hints, connection transitions).
- `LocalReducedMotion` combines the system animator scale with Settings > Motion; the pager uses an instant transition
  when it is on (F-06-AC9); content animation (the mouth following audio) is exempt.
- `LocalTalkBackOn` (from `AccessibilityManager` touch-exploration state) disables autoplay and the share sheet's
  auto-close (A-08, A-11).
- Font scale: no fixed heights on text containers; captions follow the playhead line by line at large sizes (F-07
  item 4); screen tests run at font scale 2.0.
- Automated audit: `A11yAudit.assertClean(composeRule)` (in `:core:testing`) walks the merged semantics tree and fails on clickable nodes
  without a label or with touch bounds under 48 × 48 dp; every screen test calls it (SC-12). The device-QA `a11y.py`
  repeats the check on the real phone.

---------------------------------------------------------------------------------------------------------------

## 19. Error model

```kotlin
sealed interface AppError {
  sealed interface Computer : AppError {          // from :core:network, mapped once
    data object NotOnWifi : Computer; data object Offline : Computer
    data class Unreachable(val reason: ProbeOutcome) : Computer   // timeout, refused, no route, reset, VpnBlocks
    data object DifferentComputer : Computer; data object CredentialRejected : Computer
    data class ClockSkew(val serverTime: Long) : Computer          // 401 clock_skew: re-sync and retry once
    data object PhoneAccessOff : Computer; data object ComputerTooOld : Computer; data object AppTooOld : Computer
    data class Refused(val code: String, val detail: String) : Computer   // stable server codes
    data object Guarded : Computer                 // the cleartext guard refused (a defect)
    data object Malformed : Computer
  }
  sealed interface Storage : AppError { data class NoSpace(val neededBytes: Long) : Storage; data object Io : Storage }
  sealed interface Content : AppError { data object Corrupt : Content; data object Missing : Content }
  sealed interface Media : AppError { data object VideoDecode : Media; data object AudioMissing : Media }
  sealed interface Permission : AppError { data object Microphone : Permission; data object Notifications : Permission }
  data class Unexpected(val code: String) : AppError
}
```

Rules: errors cross module boundaries as `AppError` values (or `Result`-like returns), never as raw exceptions; raw
exception text is never shown (the request detail's "Show details" shows the computer's cleaned message, which the
server already strips of escape codes and paths); unexpected exceptions are logged with a code and become
`Unexpected`.

Copy: **one mapping for request failures** (delivery review D-24). `RequestFailureCopy` (in `:core:ui`
`component/`, next to `RequestRow`, owned by WP-22, strings in `strings_request.xml`) maps a `RequestError` (a
request-level code of server-contract 8.1, a job code of 8.2, or a phone-side copy code of 8.3) to the spec 8.6 and
7.7 title, body and actions, and to the F-23 notification line:

```kotlin
object RequestFailureCopy {
  fun of(error: RequestError, context: FailureContext): FailureCopy
  fun notificationLine(error: RequestError, site: Site?): String  // F-23: the download_forbidden line, else title + " — Tap to see what you can do."
}
data class FailureContext(val kind: RequestKind, val site: Site?, val available: Set<RequestAction>,  // = Request.actions
  val maxLinkDurationS: Long?)                                     // PairedComputer.capabilities?.maxLinkDurationS
data class FailureCopy(val title: String, val body: String, val actions: List<RequestAction>)  // 8.6 order ∩ available
```

Rules: the actions shown are the spec's order for that failure intersected with `Request.actions` (8.4), so the copy
never offers what the state machine refuses; `copy_no_space` without `TRY_AUDIO_ONLY` available uses the "…then share
it again and choose Audio only." body (spec 7.7, D-49); `no_space` (job) lists "Try audio only" for a link and "Try
again" for a file (D-49); `live_stream` and `too_long` have their own spec 8.6 copy (D-40): `too_long`'s `{limit}` is
`maxLinkDurationS` written as whole hours when it is a multiple of 3,600 s ("1 hour", "2 hours"), otherwise as whole
minutes ("90 minutes"), and when it is unknown the body reads "…up to the length set on your computer…" (SH-AC18).
Request rows (WP-22, WP-16's "In progress"), the share sheet (WP-23) and `Notifier` (`:core:notifications`, through
its dependency on `:core:ui`) all use it. Only a code the app does not know maps to `processing_failed`'s copy with
the computer's cleaned message. Other features keep one `…ErrorCopy.kt` each for their own errors (connection,
storage, media); the last probe outcome has one mapping, `ProbeOutcomeCopy` (6.2).

v1-complete (CR-01): a deletion from your computer (8.8) is not a request and does not go through
`RequestFailureCopy`. One mapping, `DeletionCopy` in `:core:ui` `component/` (owned by WP-34, strings in
`strings_deletion.xml`), gives the words of every deletion state to the Requests rows (`:feature:requests`), the Clip
page block (`:feature:library`) and the deletion line of `ClipRow` and `ClipCard`, because features never depend on
each other (2.2). Every word is the product track's (spec F-05 item 6, F-20 item 1, D-58, D-61,
D-62, D-65); the architecture adds none:
- **The imported form** (spec D-58), chosen by `ComputerDeletion.imported` (rows, block) or by
  `ClipSummary.source.kind == IMPORT` (Library rows, shelf cards):

  | Where | Any finished clip | Imported clip |
  |---|---|---|
  | Requests row title | "Delete “{title}” from your computer" | "Remove “{title}” from your computer's list" |
  | Waiting (block, rows, Library row, shelf card) | "Waiting to be deleted from your computer" | "Waiting to be removed from your computer's list" |
  | Sending | "Deleting from your computer…" | "Removing from your computer's list…" |
  | Refused (block, Requests "Needs your attention") | "Couldn't delete “{title}” from your computer." | "Couldn't remove “{title}” from your computer's list." |

  The block's sentence, its button and the confirmation have the same two forms (spec F-05 item 6, D-58: "Remove from
  your computer's list…", "Remove “{title}” from your computer's list?"); they are the Clip page's own strings
  (`:feature:library`, WP-34). The reasons, "Undo" and "Deletion undone: “{title}” stays on your computer." are the
  same for both kinds.
- **Waiting reason** (spec D-61): the first that applies of
  1. the queue's pause, from `ComputerStatus`: `ConnectAgain` "Your computer no longer recognizes this phone",
     `ComputerTooOld` "Update PhonoTrainer on your computer", `AppTooOld` "Update this app", `DifferentComputer` "A
     different computer answered"; and a deletion with `DeletionWait.DIFFERENT_COMPUTER` (its last answer was
     `different_computer`, 8.8): "A different computer answered";
  2. a deletion with `DeletionWait.DIFFERENT_LIBRARY`: "Your computer is using a different library: this waits until it
     uses this clip's library again.";
  3. not connected: `Offline` and `NotOnWifi` "Not on Wi-Fi"; `Looking`, `Checking` and `NotReachable` "Your computer
     isn't reachable";
  4. `Connected` after a failed attempt (`WaitingInfo.attempts` 1 or 2): "Will try again in {n} min" ({n} from
     `nextAttemptAt`); 3 or more: "Couldn't send yet: will try again".

  Steps 1, 3 and 4 are a waiting request's reasons too (spec F-20 item 1: "requests and deletions alike", so the two
  kinds of row never give two reasons for one state), so they are one function owned by the Requests package:
  `WaitingReasonCopy` in `:core:ui` `component/` (WP-22, v1-core, strings in `strings_request.xml`), with
  `pause(status)` (step 1, from `ComputerStatus`), `connection(status, waiting)` (steps 3 and 4) and
  `of(status, waiting)` (`pause`, then `connection`), each returning the line or null (no line: `Connected` before a
  failed attempt, and `NotConnected`, for which D-61 names no words and where no deletion can exist, 6.3).
  `RequestRow` calls `of`; `DeletionCopy` calls `pause`, then its two deletion steps, then `connection`. A request
  held by "Stop sending" and a file too large for one background run keep their own lines (D-46, D-41), as before.
- **D-65's line**: a `WAITING` deletion whose `actions` lack `UNDO` (`maybe_sent`, 8.8) shows its reason and, under it,
  "It may have reached your computer already, so it can't be undone."; no "Undo".
- **Refused**: the title line above and one reason sentence chosen by the code, never the raw server text (spec D-62):
  `not_finished` → "Your computer says this analysis isn't finished, so it can't be deleted from here.";
  `unknown_route` (a `404` from a computer without the route, or the same refusal recorded without sending when the
  connected computer no longer announces `delete_finished`, 8.8) → "To delete this clip everywhere, delete it on your
  computer, or update PhonoTrainer there to do it from this phone." (F-05 item 6's "not offered" sentence); any other
  code → "Your computer answered that it can't delete this clip right now.". `different_computer` is never a refusal
  (step 1 above).
- The one-shot messages of the three new `UserMessageKind`s are the spec's (F-05 item 6) and live in the shell's host.

**The quoted title** (design 9.26, security review F-14). A clip's title is untrusted text (the uploader chooses a
link's title), so every `{title}` in the CR-01 copy goes through one function:

```kotlin
// :core:ui, component/QuotedTitle.kt — made by the integrator in CR-01-A2 (work-plan 4.7)
object QuotedTitle {
  // design 9.26, in this order: (1) Cf removed; Cc, Zl, Zp become a space; white-space runs become one space; trimmed.
  // (2) at most 60 grapheme clusters (android.icu.text.BreakIterator.getCharacterInstance()); a longer title keeps its
  // first 59, trimmed, plus "…". (3) wrapped by androidx.core.text.BidiFormatter.getInstance(false).unicodeWrap(…).
  fun of(title: String): String
}
```
Callers: `DeletionCopy` (the Requests title and the refused line), the Clip page's confirmations "Delete “{title}”
from your computer?" and "Remove “{title}” from your computer's list?" and, in v1-complete, every "Remove “{title}” from
this phone?" (Clip page, Library, Storage; WP-34), and the shell's message host for `CLIP_DELETED_FROM_COMPUTER`,
`CLIP_REMOVED_FROM_COMPUTER_LIST` and `DELETION_UNDONE` (WP-26 in CR-01-A2). The full title stays as it is where it
stands alone (the Clip page header, Library rows). Why `:core:ui` `component/`: every caller (`:core:ui`, the features
and `:app`) already depends on `:core:ui`, and the function needs Android's ICU (`android.icu`, API 24, below minSdk
29) and a bidi formatter, which the JVM module `:core:common` does not have. The formatter is the one design 9.26 names,
`androidx.core.text.BidiFormatter`, because it behaves the same on every Android version (the platform's own class
ships with the system); `androidx.core` is already a library of the app (`:app` and `:core:notifications` use
`libs.androidx.core.ktx`), so A2 adds one `implementation(libs.androidx.core.ktx)` line to `:core:ui` and no new
library (THIRD-PARTY-NOTICES and the licence report unchanged). Why the integrator in CR-01-A2: the shell's host uses
it in that very commit, and WP-34, its other user, branches after it. Tests: a Robolectric unit test of the three
steps with design 9.26's three fixtures (A2), and WP-34's screenshot test of the "Delete from your computer" dialog
with the right-to-left-override title, light and dark, at font scale 1.0 and 2.0, against render 31, specimen 5.

The deletion row of the Requests screen is `DeletionRow` in `:feature:requests` (only that screen lists deletions; the
Library's "In progress" group does not), built from the same `:core:ui` primitives as `RequestRow` and opening
`ClipKey`; the visual pieces design 9.26 adds (`PlaceHead`, `ReachStrip`, `DeletionRoute`, `SyncLine` and the danger
confirmation with stacked buttons and consequence lines) live in a new `:core:ui` sub-package `ui/place/` owned by
WP-34 (design 9.26), with plain inputs like WP-03's visual pieces.

---------------------------------------------------------------------------------------------------------------

## 20. Security

### 20.1 Exported components and manifest

- Exported: `MainActivity` (launcher) and `ShareActivity` (share target) only. `.InternalEntry` alias,
  receivers, services and providers of the app are `exported="false"`. Library-declared components (WorkManager,
  androidx.startup) keep their library defaults; `android/SECURITY.md` lists the merged result, and the hygiene check
  compares the merged release manifest's exported components and permissions with that list.
- Task hijacking: `ShareActivity` has `android:taskAffinity=""` (12). `MainActivity` keeps the default affinity, because
  an empty affinity on a `singleTask` launcher activity would change how the launcher finds its task. Android 10
  (minSdk 29, fixed by spec NFR-01) lacks the platform fix for StrandHogg-style task hijacking that API 30 brought;
  this residual risk on Android 10 devices is recorded in `android/SECURITY.md` (security review L-11).
- Permissions, complete: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `RECORD_AUDIO` (v1-complete), plus those WorkManager merges (`WAKE_LOCK`,
  `RECEIVE_BOOT_COMPLETED`). Not requested: `ACCESS_LOCAL_NETWORK`, `CHANGE_WIFI_MULTICAST_STATE`,
  `ACCESS_WIFI_STATE`, location, contacts, camera, broad storage.
- `android:allowBackup="true"` with `dataExtractionRules` (API 31+) and `fullBackupContent` (API 29–30), both
  allowlists:

```xml
<!-- res/xml/data_extraction_rules.xml (the same includes in backup_rules.xml for API 29-30) -->
<data-extraction-rules>
  <cloud-backup disableIfNoEncryptionCapabilities="true">
    <include domain="database" path="learning.db" />
    <include domain="file" path="datastore/settings.preferences_pb" />
  </cloud-backup>
  <device-transfer>
    <include domain="database" path="learning.db" />
    <include domain="file" path="datastore/settings.preferences_pb" />
  </device-transfer>
</data-extraction-rules>
```

- Cloud backup is skipped on devices that cannot encrypt it (`disableIfNoEncryptionCapabilities`, security review L-6).
  `learning.db` uses the TRUNCATE journal (4.1), so the single file Auto Backup copies is consistent; no `-wal` file
  exists to miss.
- v1-complete (CR-01) leaves the allowlist unchanged (spec 11.1): the shared-progress bookkeeping and `learning_meta`
  live in `learning.db`, so a restore brings back the progress and the record of what was already shared in one
  consistent file (7.8: nothing counted twice, nothing unsaved brought back); waiting deletions live in `library.db`
  and are never backed up (8.8). WP-02's backup-rules test keeps passing unchanged.
- `networkSecurityConfig` as in 5.2; release builds not debuggable; `android:enableOnBackInvokedCallback="true"`.

### 20.2 Shared input

See section 12. The clipboard is read only on "Paste" (F-19-AC1).

### 20.3 Credential storage

`KeystoreCredentialCipher` creates an AES-256-GCM key in `AndroidKeyStore` (alias `device_credential_v1`,
non-exportable, no user authentication) and stores only the ciphertext and IV of the pairing token in
`pairing.preferences_pb` (not backed up). The plaintext token exists in memory only while deriving the MAC key
`K = sha256(token)` for a signature or a proof (5.3); the token is never sent after pairing. If decryption fails
(restored to another phone, key invalidated), the pairing is treated as revoked ("Connect again", which then pairs as
a new device, 6.3). The token and `K` never appear in logs, diagnostics, "Copy diagnostics", crash text or
notifications. Because "Copy diagnostics" text is likely to be pasted into a public issue, it replaces the computer's
address with its class ("private 192.168/16", "link-local"), leaves out this phone's own address entirely and leaves
out the server and device ids, while Connection details itself still shows them on screen (security review L-7; spec
D-39, F-22-AC4).

### 20.4 Logging hygiene

One facade: the `PhonoLog` interface in `:core:common`, with its Android implementation in `:app`, injected
everywhere. Its methods take lambda messages (`log.d { "…" }`), and the release implementation drops debug and info
calls without invoking the lambda, so release builds never even build those strings (`-assumenosideeffects` on
`android.util.Log` would do nothing, since `Log` is used only inside the facade; Android review L4). Release builds log
warnings and errors as event codes only: no addresses, links, file names or job titles. OkHttp's logging interceptor
is a `debugImplementation` dependency, fixed at level `HEADERS` (never `BODY`, which would print the pairing code of
`/pair` and the token of its answer), with `redactHeader("Authorization")`, and absent from release; a unit test feeds
a pairing exchange through the debug logger and asserts that neither the code nor the token appears (security review
M-7). The hygiene check forbids `android.util.Log` outside the facade.

### 20.5 Release signing

Release builds are signed with one stable key kept outside the repository; `app/build.gradle.kts` reads an ignored
`keystore.properties` when present (store file, alias and passwords) and otherwise produces an unsigned release.
`android/README.md` documents this without any path or secret. Version codes increase monotonically.

The key exists before the first install on the test phone (delivery review D-20): the owner creates it outside the
repository, or WP-25 creates it with the owner's approval, as a request like spec 15.4. **Only release builds signed
with that key are installed on the test phone**; debug and `qa` builds (signed with the per-machine debug key) run only
on emulators, so a later release can always update the phone's install in place (X-AC7). WP-19 produces version N and
N+1 (`versionCode` + 1) for the update test.

### 20.6 Licences

Two kinds of third-party material ship in the APK, and a dependency report only ever sees the first (spec F-24,
D-50):
1. **Runtime libraries.** The AboutLibraries Gradle plugin (build-time) generates the dependency and licence metadata
   of the release runtime classpath into an asset, in strict mode with an allowlist (Apache-2.0, MIT, BSD-2-Clause,
   BSD-3-Clause, OFL-1.1, and the AndroidX licence identifiers) so an unknown or incompatible licence fails the
   build. WP-19 verifies the plugin under the configuration cache in its first week; the fallback is a small
   build-logic task that reads POM licences from the resolved runtime classpath.
2. **Bundled fonts and the vendored icon set** (Atkinson Hyperlegible Next, Newsreader and Charis under OFL-1.1;
   Lucide 1.51.0 under ISC with Feather-derived glyphs under MIT). They are not libraries. WP-03 ships their licence
   texts in `:core:designsystem` `assets/licenses/` together with `bundled.json`, an index generated by its conversion
   script from `FONTS.txt` and `icons.json`: one entry per component with name, version, kind (`font` or `icons`),
   files (font file names, or the icon count), SPDX licence, licence file and source, and the SHA-256 of each font
   file.

The **licence check** (`verifyLicences`, a build-logic task owned by the integrator, part of `check`; X-AC3) fails
the build unless: (a) every library of the generated report has an entry in `THIRD-PARTY-NOTICES.md` 7.2 "Runtime
libraries" and every 7.2 entry is in the report, with an allowlisted licence; (b) every entry of `bundled.json` has a
row in 7.3 "Fonts and icons" and every 7.3 row is in `bundled.json`, its licence file exists in `assets/licenses/`,
its licence is OFL-1.1, ISC or MIT, every font file under `:core:designsystem` `res/font/` is listed with a matching
SHA-256, and no font file is unlisted. The About and licences screens (F-24, WP-24) render both offline: libraries
from the generated asset, fonts and the icon set from `bundled.json` with their licence texts; the F-24-AC2 test
compares the rendered list with both sources.

### 20.7 Repository hygiene checks (`scripts/hygiene/`)

`check_repo.py` (Python standard library only) checks: forbidden directory names (2.6); ignored source files
(X-AC5); language resource folders, meaning only language qualifiers (`values-[a-z]{2,3}(-r[A-Z]{2})?` and
`values-b+…`; `values-night`, `drawable-night` and other non-language qualifiers pass, with `values-night` as a test
case, delivery review D-22) and a heuristic non-English scan of strings, comments and docs with IPA and phonetic
examples allowed (X-AC2); a secret and path scan (X-AC4): IPv4/IPv6 literals outside the documentation ranges and
loopback, MAC addresses outside the documentation range `00:00:5e:00:53:xx`, `/home/`, `/Users/`, private key
headers, keystore files, long tokens, and the credential shapes `pt1_[A-Za-z0-9_-]{43}`, `Bearer \S+`,
`PT1 device=` lines with a MAC, and `"code"\s*:\s*"\d{6}"` (the documentation placeholder `482913` of
server-contract 2.2 and 6.2 is the one allowed value); `fallbackToDestructiveMigration`; `android.util.Log`
outside the facade; OkHttp construction outside `:core:network`; an `AddressPolicy` implementation that allows
loopback anywhere but `src/test`, `src/androidTest` or `:core:testing*` (security review L-13); and the merged-manifest
allowlist. `check_ownership.py` checks a diff against the ownership table.

At run time `check_repo.py` also builds a **local denylist** it never stores: the host name, the user name, the home
directory, `git config user.email`, the serials `adb devices` lists, and the host's current IPv4 and MAC addresses;
any of them in a tracked or staged file is a finding (security review M-8). `docs/qa-report.md` gets one more rule: no
URL with a video or post id, because the link gate records outcomes, never what the owner watched (WP-25).

When it runs: `scripts/hygiene/install-hooks.sh` installs a `pre-commit` hook (staged files) and a `pre-push` hook (the
pushed range) that run `check_repo.py`; installing the hooks is part of every WP's definition of done (23), because on
a public repository a CI run after a push is already too late. CI runs it again (22). `qa_log.py` (21.7) applies the
same credential shapes and the local denylist when it writes a QA log.

### 20.8 Deletion and shared progress (v1-complete, CR-01)

The two features add the first phone actions that change what every device sees. Their rules, and the test that
proves each (phone side in WP-28 and WP-34, server side in server-contract 11.3):

| Threat | Rule | Test |
|---|---|---|
| A forged or replayed deletion, reset, answer or Saved change | all are signed phone requests (server-contract 4.3); the deletion's target (job id, server id, library id), a phone's reset (`reset_id`, `reset_at`) and, for the three CR-01 routes, the body's SHA-256 (`body_sha256`) are in the signed request target, so the signature covers the body too; the computer refuses a signature older than its own start (`not_before`), because its nonce cache lives in memory and would otherwise forget a request recorded in the 300 s before a restart (review fix C-54) | server: unsigned → 401, a replayed nonce → 401, the query changed after signing → 401, a body changed after signing → 401, a missing `body_sha256` → 401, a request signed before a restart and replayed after it → `401 clock_skew` (the phone re-signs once), a `reset` in the phone route's body → 400; phone: a `MockWebServer` recording shows the reset only in the query and the body's hash in the query (5.4) |
| A deletion carried to another computer or library | the row is bound to server id, library id and device id (8.8) and is sent only to the verified paired computer (5.3) | QT-17, QT-18, QT-20 on the JVM with `FakeComputer` (an impersonator records no request with an `Authorization` header) |
| A deletion the learner did not confirm | created only by the Clip page's confirmation (WP-34); never by a listing, an event, a link or an intent; no bulk form, no deletion route in the shared-input paths (12) | a source-scan unit test in `:core:requests` that fails if any call site other than `deleteFromComputer` inserts a `DELETE_FINISHED` row; the confirmation's screen tests (WP-34) |
| A lost or lent phone, a leaked device key or a client bug deleting the library one request at a time | every paired phone may delete any finished analysis (spec D-54), and `JobStore.delete` cannot be undone, so the computer limits `delete-finished` to 20 requests per device per 10 minutes (`429 rate_limited` with `Retry-After`, transient for the phone, 8.8), prints one visible line per deletion with the phone's name and short id, and says at start, in the banner and the README, that paired phones can delete finished analyses and how to revoke a lost one (server-contract 2.1, 4.7, 6.11, 10.3; review fix C-56) | server: the 21st request within 10 minutes → 429 with `Retry-After`, another device unaffected; the audit line printed under uvicorn's logging configuration (C-60); the banner line |
| "Undo" saying something false | "Undo" is withdrawn once an attempt may have reached the computer (`maybe_sent`, 8.8) | JVM: a timeout after the request left, then no `UNDO` in `actions`; a process death while `SENDING`, the same |
| A hostile computer answer | the shared-progress mappers reject non-UUID ids, `at` before 2020, negative, inconsistent or out-of-range tallies and positions, job ids outside the pattern, and Saved answers above 1 MiB (5.4); a rejected answer changes nothing local | mapper tests with each malformed field; an oversized body is cut at the cap |
| A wedged exchange (one bad item, a full store, a hostile client filling the computer's bounds) | the computer refuses content per item and never a whole request (`ignored`, `dropped`, `not_shared`), bounds every stored field, prunes tombstones early and clamps totals; the phone marks every refused item handled (7.8; server-contract 4.7, 6.12; review fix C-55) | server: the 49th unknown label ignored while reference labels count, the 10,001st baseline ignored while the request's answers count, a Saved position beyond the job dropped, the worst-case Saved answer under 1 MiB; phone: a page answered with `400` is marked handled and the next page is sent |
| A lost or damaged store wiping every device | the computer never recreates or overwrites an unreadable or unknown-schema `progress.sqlite3` or `saved.json`: it answers `503 store_unavailable` (transient) and prints a warning (server-contract 3.2; review fix C-64) | server: a corrupt file, a file of another schema → 503 and the file's bytes unchanged |
| A crafted clip title making a destructive confirmation read like a phone-only removal (a right-to-left override, control characters, a very long title) | every CR-01 `{title}` is `QuotedTitle.of(title)`: format characters removed, controls and separators made spaces, one line of at most 60 grapheme clusters, bidi-isolated; no place word, consequence or button label is built from the title (19; design 9.26; security review F-14, C-79) | Robolectric: design 9.26's three fixtures (CR-01-A2); WP-34's screenshot test of the dialog with the override title against render 31, specimen 5 |
| A restored backup counting answers twice or bringing back an unsaved item | `learning_id` and the share bookkeeping are in `learning.db` (20.1) | F-17-AC11 logic on the JVM (WP-28), on the emulator (WP-33) |
| Other devices' answers carried to a second computer | a first share with another computer sends `own_stat`, this phone's own answers, never the mirrored aggregate (7.8, review fix C-63) | JVM: share with computer A (another device adds answers there), forget, pair with B: B's baseline holds only this phone's answers; a reset made elsewhere before the move is respected |
| A page on the internet reading or adding to the history through the browser routes | loopback only, the `/ws/jobs` Origin rule, `same_origin_only`, `LoopbackHostGuard`, the JSON body rules (server-contract 6.13) | server: a LAN peer → 403, a foreign `Origin` → 403, `Sec-Fetch-Site: cross-site` → 403, `Host: attacker.example` → 400, `Transfer-Encoding` → 411, no `Content-Length` → 411, over 16 KiB → 413, another `Content-Type` → 415 |
| Data in logs | the computer's audit lines (a deletion, an applied reset) name the job id, the phone's name and its 12-character short id (the form `phonotrainer phone list` prints, so the operator can revoke it), or "the browser", and the first 8 characters of the request key: no title, address, token or path (server-contract 3.3, 6.11); the phone logs event codes only (20.4) | the server's audit-line test under uvicorn's logging configuration, not only `caplog`; the phone's log hygiene check unchanged |

What stays open in v1 is the accepted transport risk (spec 11.6, server-contract 4.8): a listener on the Wi-Fi can
read these exchanges (practice answers, the Saved list, which clips are deleted), and an active attacker can block or
delay them, but neither can change or replay a signed request, its body included, nor bring one back after a restart.
Version 2 encrypts them (26).

---------------------------------------------------------------------------------------------------------------

## 21. Testing strategy (AD-22, AD-23)

### 21.1 Pyramid

| Level | Where | Runs on | Covers |
|---|---|---|---|
| Pure unit | `:core:domain`, `:core:common`, `:core:analysis`, `:core:network-api` (`src/test`), fixtures from `:core:testing-jvm` | JVM, milliseconds | shorts v1 (golden F-06-AC1 + property test AC2), spotlight, occurrences and examples, link parsing and normalization (SH-AC2, duplicates), retry schedule, set builder (F-08-AC1, AC3, AC4 with a clock), quiz port, shadowing plan (F-12-AC1, AC2), probe reducer (SC-10 bounds, F-03-AC3), request state machine table (every row of 8.4), the signing and proof vectors shared with the server tests (5.3) |
| Android unit | each Android module (`src/test`) | JVM + Robolectric sdk 35 | Room DAOs and transactions, migrations (`MigrationTestHelper`), DataStore stores, repositories against fakes, workers (`TestListenableWorkerBuilder`, `WorkManagerTestInitHelper`, a Robolectric run at sdk 30 for `getForegroundInfo`), network module against the fake computer (MockWebServer), the cleartext guard (X-AC6), hostile listings (5.4), ViewModels (Turbine, `MainDispatcherRule`) |
| Screen | each feature module (`src/test`) | Robolectric + Compose UI test | every state row of every States table of the spec as a stateless-screen test, the a11y audit, font scale 2.0, light and dark. This is where state-by-state UI coverage lives |
| Core instrumented | `:core:player`, `:core:requests`, `:core:sync` (`src/androidTest`, `phonotrainer.android.library.instrumented`) | Gradle Managed Device `pixel9Api36` | real codecs and timing (merged playback, the audio override, swipe-to-sound on the pool), audio extraction with `MediaExtractor`/`MediaMuxer`, WorkManager scheduling with the test driver (delivery review D-11) |
| End-to-end | `:app` (`src/androidTest`) | Gradle Managed Device `pixel9Api36` (aosp-atd, x86_64, `swangle_indirect`) | the real app with real repositories over the global test seams of 21.5; per-area navigation and wiring tests, cross-area flows, the automated torture suite |
| Black-box release | `:qasmoke` (`com.android.test`, UiAutomator) | Gradle Managed Device, the unmodified R8 `qa` APK | the release-like smoke of 21.6 against the host-side fake computer |
| Device QA | `scripts/device-qa/` | API 36 emulator ([emulator] ACs) and the test phone | reboot, airplane mode, clock change, process kill on the emulator; on the phone: stop the server or turn phone access off, font scale, animation scale, TalkBack (all logged and restored), decoders, measurements, the link gate by the owner |

### 21.2 Robolectric

Robolectric 4.17 cannot run sdk 36 on Java 17, so every Robolectric test runs on sdk 35: the library convention
plugin adds a generated `robolectric.properties` (`sdk=35`) to each module's test resources (no per-class
annotations); a test that needs another level says so with `@Config(sdk = …)` (the sdk 30 worker test).
`androidx.test:core` is a unit-test dependency (`ApplicationProvider`).

### 21.3 The fake computer (AD-23)

`FakeComputer` (`:core:testing-jvm` `computer/`, WP-05a) wraps `mockwebserver3` with a stateful dispatcher that
implements `server-contract.md` from its JSON: hello with identity proofs, health, pairing (codes, expiry by the test
clock, five-attempt invalidation, rate limit, re-activation proofs), signature checking with nonces and a clock
window, the library listing with revisions and checksums, artifacts with Range and If-Range, link creation, upload
sessions (single PUT with the idle and "newest wins" rules and, v1-complete, PATCH offsets), request lookup, cancel,
delete, re-run, reference, the WebSocket channel (`snapshot`, `job`, `deleted`, pings), renditions and compare
(v1-complete). It is a JVM module, so the network module's tests, the core modules' Robolectric tests and the
instrumented end-to-end tests all use the same fake, and it needs only the contract to be written (it starts on day
0). `FakeComputerClient` (an in-memory `ComputerClient`, from B1) lets connectivity, sync and requests start before
the HTTP client exists. Synthetic clips come from `AnalysisFixtures` (`:core:testing-jvm` `fixtures/`, WP-12), the one
Kotlin port of the server's `mk_analysis`, with WAV bytes generated in memory; there is no second port (delivery
review D-17). Drift between the fake and the server is caught by the smoke test against the host-side fake computer
(21.6, server-contract 12), which runs the real server code.

Failure modes, each a one-line call in a test:

| Knob | Simulates |
|---|---|
| `reachability = UNREACHABLE` | no answer (the socket accepts nothing; connect times out) |
| `reachability = SLOW(latency)` | a computer busy analyzing |
| `failNext(path, status, code, times)` | 4xx with a stable code, or 5xx |
| `applyThenDrop(path, times)` | the server applies the creation and the response is lost (QT-3, SH-AC5) |
| `truncate(artifact, atBytes, times)` | partial downloads (QT-9, F-21-AC3) |
| `corrupt(artifact, times)` | same size, wrong bytes (verification) |
| `stallUpload(afterBytes)` | an upload body that stops arriving (a Wi-Fi drop mid-`PUT`, QT-4) |
| `revokeDevice()` | credential rejected (QT-8, F-02-AC8) |
| `changeServerId()`, `changeLibraryId()` | a different computer at the address (QT-11), a workspace switch (QT-14) |
| `impersonate(proof = MISSING \| WRONG)` | a second responder that answers discovery and `hello` with the paired server id and name but with no proof or a wrong one, because it lacks this phone's key (security review H-1; spec D-37, F-02-AC11) |
| `clockSkew(seconds)` | the computer's clock differs from the phone's |
| `phoneAccessOff()`, `apiVersion = 5` | phone access off, a computer too old |
| `lanForbidden()` | phone access on but suspended on this network: every phone route, `hello` included, answers `403 lan_forbidden` and discovery is silent (server-contract 2.1, 4.2; spec D-42, F-02-AC12) |
| `restart(interrupt = true)` | queued and running jobs become `interrupted` (QT-5, QT-12) |
| `deleteJob(id)`, `finishJob(id)`, `failJob(id, code)`, `progress(id, stage, percent)` | computer-side events |
| `dropChannel()` | job channel lost (Checking state) |
| `lowOnSpace()`, `maxUploadBytes = n`, `maxLinkDurationS = n` | upload refusals; the link limit `too_long`'s copy names (SH-AC18) |
| `capabilities(deleteFinished = …, sharedProgress = …)` (v1-complete, CR-01) | a computer that does or does not announce 6.11 and 6.12 (spec F-05-AC10, F-17-AC9) |
| `refuseDeletion(code, times)`, `importedJob(id)` (CR-01) | a deletion refused with `not_finished` or another code (F-05-AC12), `429 rate_limited` with `Retry-After`, `409 different_computer`, or `404 unknown_route` (a computer downgraded after the deletion was stored); a finished job listed with `source.kind: "import"` (F-05-AC9) |
| `applyThenDrop(path, times)` on the deletion route, `restart()` (CR-01 review fixes) | a deletion applied whose answer is lost (`maybe_sent`, no "Undo" afterwards); the fake also checks `body_sha256` on the three CR-01 routes and, after `restart()`, refuses a signature older than the restart with `401 clock_skew`, as the server does (server-contract 4.3) |
| `recreateSavedList()` (CR-01) | the computer's `saved.json` deleted and made again (a new `list_id`): the phone's next Saved exchange gets a full list (server-contract 6.12) |
| `otherDevice(name)` returning a handle with `answer(change, correct, at)`, `reset(at)`, `save(item, at)`, `unsave(item, at)` (CR-01) | a second phone or the browser acting on the shared history at a given computer time, so the two-device ACs run on the JVM (F-09-AC4, AC5, F-17-AC6, AC8) |
| `practiceTotals()`, `savedEntries()`, `deletedJobs()` (CR-01, observation) | what the computer holds after an exchange, to assert "counted once" and "deleted exactly once" (F-05-AC7, F-17-AC7, QT-15, QT-16) |

Observation: `jobsWithKey(key)`, `requests` (recorded requests in order, to assert "the cancel reaches the computer
before any new request", and that no `Authorization` header ever reached an impersonator), `pairedDevices`.
`FakeDiscovery` replaces the UDP transport in end-to-end tests; the real `DiscoveryClient` is tested on the JVM against
a responder bound to the loopback interface, including two responders with one name and one server id answering from
two addresses (each a conflict, not a duplicate: `SearchState.Found.conflict`, no preselection, F-02-AC10).

### 21.4 Synthesized media

No media file is committed (2.6). JVM tests write WAV bytes directly. Instrumented tests create AAC-in-MP4 audio and
H.264 video with `MediaCodec` + `MediaMuxer` on the emulator (`SyntheticMedia` in `:core:testing` `synthetic/`, owned by
WP-11, its main consumer), including a video with a deliberate start offset, a video whose own audio track is a
different tone (the audio override of 11.1) and an unsupported codec label to exercise the fallbacks.

### 21.5 Test seams: one global set, real repositories (delivery review D-01)

A `@TestInstallIn` module applies to every test of the source set it lives in, and `@UninstallModules` cannot remove
one (Hilt testing documentation). So `:app/src/androidTest` holds **one global set**, owned by the integrator and
present from B1, and no fake-repository app tests exist:
- `TestNetworkModule` replaces `NetworkPolicyModule` (`:core:network`): an `AddressPolicy` that also allows loopback,
  and a no-op `ComputerNetworkBinder`;
- `FakeComputerModule` starts a `FakeComputer` per test and exposes it;
- `TestConnectivitySeamsModule` replaces `ConnectivitySeamsModule` (`:core:connectivity`): a scripted `NetworkMonitor`
  (Wi-Fi, offline, mobile only) and `FakeDiscovery`;
- `TestDatabaseModule` replaces `DatabaseModule` (`:core:database`): in-memory databases;
- `TestClockModule` replaces `ClockModule` (`:app`): a `TestClock`;
- WorkManager is initialized by the `HiltTestRunner` with `WorkManagerTestInitHelper` (`SynchronousExecutor`,
  `TestDriver`).

The replaced modules' class names are frozen at B1 (2.7). **Repositories stay real**: in a wave-2 copy they are the
stubs of B1 until their owners merge, and afterwards the real implementations. `e2e/support/` (integrator) provides
`TestSeeder`, which pairs with the `FakeComputer` and runs a sync pass, so a test starts from a library seeded through
the real stack. Consequently:
- a wave-2 area test proves navigation and wiring of its destinations (they open, Back order holds, empty and loading
  states render with the stubs or real repositories present in its baseline) and, when the cores it reads are merged
  in its baseline, its happy path seeded through `TestSeeder`; otherwise the area's robot exposes the happy path as
  methods that WP-19's cross-area flows call;
- state-by-state UI coverage lives in the feature modules' Robolectric screen tests (21.1);
- a test that truly needs a fake repository in the app uses a per-class `@UninstallModules(<real module>)` plus
  `@BindValue`, and says why (each such class costs its own generated component).

Robots live per area in `app/src/androidTest/.../e2e/<area>/` (owned by the area's work package) behind a façade
`PhonoRobot` (integrator). Conventions from Artemi: nodes by test tag or string resource, never hard-coded text; every
action returns after its effect is visible; waits with timeouts, never `Thread.sleep`; one regression test per bug.

Mapped acceptance: the share-target query test (`queryIntentActivities` lists `ShareActivity` for `text/plain`,
SH-AC1), SH-AC2 to SH-AC16 with injected share intents against the fake computer (with and without
`FLAG_ACTIVITY_NEW_TASK` while a `MainActivity` task exists: PhonoTrainer's task is not brought forward and the
launching activity is on top after the sheet closes, H3), the automated part of the queue torture test (QT-2, QT-3,
QT-4 v1-core, QT-5 to QT-12, QT-14), F-21-AC8 and F-23-AC1 with the WorkManager test driver, SC-13's
TalkBack-equivalent flow with semantics actions, A-13's keyboard path (`performKeyInput` focus traversal and a visible
focus indicator on three screens).

GMD runs are serialized on the development computer through one lock (`android/scripts/device-qa/gmd_lock.py`, a
`flock` on a file outside the repository), because every copy of the project shares the one managed AVD, and a WP's
run is filtered to its own area (`-Pandroid.testInstrumentationRunnerArguments.package=…e2e.<area>`) except in the
integrator's full runs (delivery review D-04).

### 21.6 Release-like testing

- **R8 smoke, black-box** (Android review M10, delivery review D-19). Hilt test components generated against a
  minified app need keep rules for everything only the tests touch, so a Hilt suite on the `qa` build would fail for
  the wrong reasons. Instead `:qasmoke` (`com.android.test`, UiAutomator, target `:app` variant `qa`) drives the
  **unmodified** `qa` APK (`initWith(release)`: R8 and resource shrinking on, signed with the debug key, not
  debuggable) against the host-side fake computer (server-contract 12) reached at `10.0.2.2`: launch, pair with the
  code passed as an instrumentation argument, see the seeded clip, play a short, share a synthetic file into the
  share sheet, open the request. It proves the R8 rules for serialization, Retrofit, Hilt workers and Nav3 keys. WP-19
  proves the module with one trivial test in its first week, before anything depends on it.
- **Smoke against the real server code**: the instrumented test of server-contract 12 (pair, probe, list, download
  and verify, upload, cancel, re-run, delete, unpair) on the debug build against the host-side fake computer; it is
  the drift check between `FakeComputer` and the server.
- **Baseline Profile and startup** (Android review M9): `:baselineprofile` (`com.android.test` with the
  `androidx.baselineprofile` plugin, Macrobenchmark on the managed device) generates the profile for cold start to
  Home, Watch with 20 swipes and opening a lesson; `androidx.profileinstaller` installs it on a sideloaded APK. WP-19
  records NFR-02 timings with and without it in `decisions.md`.
- **Size and alignment**: WP-19 asserts the release APK size with `apkanalyzer` (NFR-03, ≤ 40 MB) and its 16 KB
  alignment with `zipalign -c -P 16 -v 4` (2.5).

### 21.7 Device QA kit and the torture test

`scripts/device-qa/` adapts Artemi's kit (Python 3, Pillow for screenshots; `adb` and the `android` CLI on `PATH`;
`ANDROID_SERIAL` from the environment; never a committed serial):

| Script | Purpose |
|---|---|
| `_device.py` | `PACKAGE = "com.mapineda48.phonotrainer"`, `APP_LABEL = "PhonoTrainer"` from one place, plus a smoke check that the package is installed (Artemi's stale-constant defect fixed) |
| `ui.py`, `shot.py`, `a11y.py` | as in Artemi (layout tree, tap by selector, screenshots to `android/artifacts/qa-evidence/`, unlabelled clickables and targets under 48 dp) |
| `db.py` | debug builds on an emulator only: dumps `request`, `request_op`, `clip`, `artifact` (never the pairing store) |
| `settings_guard.py` | records, changes and restores font scale, animator scales and TalkBack through `adb`, writing the restore table into the QA log (X-AC10). The TalkBack component is read from the device (`pm list packages` and the current `enabled_accessibility_services`), never hard-coded, because Samsung ships its own TalkBack build under another component name; the exact previous value is restored (Android review L7) |
| `host_computer.py` | the host-side fake computer of server-contract 12: the real server code with a synthetic analysis writer and a refusing downloader, on throwaway workspace, configuration and working directories; `--lan` serves the test phone on the host's approved Wi-Fi address; prints the pairing code to stdout for the harness (never to a file) |
| `computer.py` | the real computer for WP-25's real-computer pass only: `phonotrainer ui --phone-access` with a throwaway workspace, configuration directory **and working directory** (so `data/` and `downloads/` never land in the repository), toggling phone access, printing the pairing code to the operator |
| `gmd_lock.py` | the GMD lock of 21.5 |
| `decoders.py` | records the phone's video decoders, sizes and hardware or software (NFR-14) |
| `torture.py` | the [emulator] steps of 7.12 against `host_computer.py`: reboot (QT-1), Wi-Fi drop (QT-4), process kill mid-copy (QT-13), airplane mode (F-06-AC3), clock past midnight (F-08-AC4); plus the platform stop paths: `am compat enable FGS_INTRODUCE_TIME_LIMITS` with a short `data_sync_fgs_timeout_duration`, and `am set-standby-bucket … rare` and `restricted`, asserting that transfers stop cleanly and resume (Android review M12) |
| `capture.py` | starts the emulator with `-tcpdump` for one [emulator] run and checks that every packet goes to the host-side fake computer (X-AC1 "all traffic captured"; an OkHttp `EventListener` sees only OkHttp, Android review L9) |
| `qa_log.py` | QA log writer that redacts addresses, serials, paths, the credential shapes of 20.7 and the local denylist |

**Emulator image** (delivery review D-10): the API 36 image installed today is `aosp_atd`, which draws black
screenshots until `debug.hwui.drawing_enabled` is set and lacks the Settings app. WP-06 provisions
`system-images;android-36;google_apis;x86_64` (with the orchestrator's approval, since it writes to the SDK outside
the repository) and runs a ten-minute spike that proves, on it: `ConnectivityManager` reports `TRANSPORT_WIFI`, `svc
wifi disable/enable`, airplane mode, reboot, a clock change and screenshots. If the spike fails, the installed API 37.2
`google_apis_playstore_ps16k` image is the fallback for [emulator] steps (it is also the 16 KB page-size check of 2.5).

The torture test runs (1) automated against `FakeComputer` (WP-19), (2) the [emulator] steps against the host-side
fake computer, which refuses every download, so a shared link can never reach a site (spec D-36), (3) once with the
real computer **using local files only**. No agent ever sends a link to the real computer (D-36).

v1-complete (CR-01, spec D-57): beside its seeded clip (created by path, not imported), `host_computer.py` seeds a
second finished clip the same way and an imported one (`JobStore.import_dir` on a synthetic result folder in its
temporary directory, outside the workspace), so the [emulator] steps of QT-15 to QT-20 and F-05-AC9 delete either
kind there without removing the clip the other steps use, and it can pair a second signer to act as another device
on the shared history (server-contract 12). On the owner's computer QA never deletes one
of the owner's clips and never resets the shared practice history. The guard is in the kit, before any tap: the two
destructive UI helpers (`ui.py`'s `delete_from_computer` and `reset_everywhere`) first read the paired server id from
Connection details (shown on screen, 20.3) and stop with an error unless it is the server id of a computer the kit
started in this session (`host_computer.py`, or `computer.py`, whose throwaway configuration directory and workspace
hold only clips the session created from local test files; both record their server id in the session's QA log). So
the real-computer pass deletes only a clip it created itself, and a phone paired with the owner's own computer (the
S-20 link gate) can never be the target of either helper. `ui.py`'s generic tap applies the same check before it taps
an element labelled "Delete from your computer" or "Remove from your computer's list", with or without the trailing
"…" (so the block's buttons and the confirmations' buttons alike), or "Forget it" while the dialog titled "Forget your
practice history everywhere?" is shown, so a script cannot bypass the guard by tapping the label directly (spec D-57
(2); security review F-15). While the test phone shares with the owner's own computer, the practice answers QA gives
there are real answers in the owner's history, and the QA report says how many (spec D-57 (4)).

**The first share of the test phone** (spec D-57 (3), NFR-14, X-AC10; consistency review finding 6). The test phone is
the owner's own phone, and practice answers QA gives there (from WP-25 on, and against fake computers in WP-33) stay in
its history; at its first share with the owner's own computer they would join the owner's real history for good
(7.8, "First share"). Three rules close this:
1. **Counting, from WP-25 on.** QA gives practice answers on the test phone only in logged sessions: before and after
   each, the kit reads the Practice screen's per-change line ("7 of 10 · 70 %", spec F-17 item 5) from the layout tree,
   and the QA log records the difference per change (answers given, answers right). The kit's simulated devices
   (`host_computer.py`'s second signer) answer only outside those sessions, and the log records each answer and each
   reset they send, and each reset QA makes, with the server id it went to.
2. **The check.** Before a v1-complete build on the test phone can first share progress with the owner's own computer
   (it shares at its first connection to a computer that announces `shared_progress`), and at the latest before WP-33
   hands the phone back, `ui.py`'s `practice_totals()` reads the per-change totals, and the kit compares them with the
   log's counts since the last reset the log records: QA's own sessions, plus the simulated devices' answers on a
   computer this phone shared with. A total the kit cannot read stops the step with an error; it never guesses.
3. **The outcome, logged.** Equal: everything is QA's, so QA clears it with "Reset": the phone-only "Forget your
   practice history?" while the line reads "Kept only on this phone." (not paired, or after "Forget this computer"), or
   `reset_everywhere` while the phone shares with a computer the kit started; either reset also clears `own_stat`
   (7.8), so the first share with the owner's computer sends nothing; the log records the totals it cleared. Not
   equal: the owner practised on the phone too, so QA resets nothing, and the QA report states, per change, the totals
   that first share will send and how many of them are QA's: the screen's totals when the phone has never shared,
   otherwise this phone's own answers (`own_stat`, 7.8), which are the screen's totals minus the logged answers of the
   kit's simulated devices; QA's part is the sum of its logged sessions.
Equality is the test because clearing is itself a reset nobody can undo: any difference, including a reset the owner
made, means the history is not only QA's, and answers the owner gave are the owner's to keep or clear (spec D-57).

### 21.8 Server tests

The Python side is tested with pytest under the repository's rules (no network, synthetic media, fakes for the
downloader and resolver); the plan is in `server-contract.md` section 11.

---------------------------------------------------------------------------------------------------------------

## 22. Continuous integration

A new workflow `.github/workflows/android.yml` (the repository has no workflow today, so no web job exists to
disturb; this one never runs web tasks):

- Triggers: `push` to `main`, `pull_request`, `workflow_dispatch`, with `paths` filters `android/**`,
  `web/src/didactic/**`, `web/src/features/learn/content.ts`, `web/src/features/learn/ipaLayout.ts`,
  `web/src/articulation/phones.ts`, `web/src/articulation/pose.ts`, `web/src/paths.ts` (the drift check reads them)
  and the workflow file itself.
- `permissions: contents: read`; `concurrency` with cancel-in-progress; every action pinned to a full commit SHA with
  a version comment; `persist-credentials: false`; `defaults.run.working-directory: android`.
- Job `hygiene` (5 min): `python3 scripts/hygiene/check_repo.py`, `python3 -m unittest discover -s scripts`, and Gradle
  wrapper validation (`gradle/actions/wrapper-validation`, SHA-pinned; the wrapper also pins
  `distributionSha256Sum`, 2.1; security review L-14). It repeats what the local hooks already enforced (20.7).
- Job `build` (30 min): Temurin 21 (Gradle runs on it; compilation uses the 17 toolchain), Node 22 (drift check),
  `./gradlew check lintDebug assembleDebug assembleRelease verifyDidacticContent`, reports uploaded.
- Job `instrumented` (45 min): KVM udev rule, `./gradlew pixel9Api36DebugAndroidTest` with
  `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` (the committed `swangle_indirect` is what this
  development computer needs and is untested on GitHub runners; delivery review D-31), reports uploaded. The `qa`
  smoke and the host-side fake computer smoke run locally (WP-19), not in CI.
- No secret is needed; the release artifact is unsigned. Dependabot for Gradle and GitHub Actions is optional and
  scoped to `android/`. Dependency verification metadata (`gradle/verification-metadata.xml`) is not adopted in v1
  (section 25).

---------------------------------------------------------------------------------------------------------------

## 23. Definition of done (every Android work package)

1. `./gradlew :<module>:test` / `:<module>:testDebugUnitTest` for the modules touched, then `./gradlew check` green.
2. `./gradlew lintDebug` clean, with no baseline (`warningsAsErrors = true`, `checkDependencies = true` on `:app`;
   only the checks of the lint policy in 2.3 are ignored, through `config/lint/lint.xml`).
3. `./gradlew assembleDebug`; `./gradlew assembleRelease` when dependencies, R8 rules, serialization or reflection
   changed.
4. Instrumented tests, under the GMD lock (21.5), when UI, navigation, persistence, DI, media or work scheduling
   changed: the WP's own area of `:app` (`pixel9Api36DebugAndroidTest` filtered to `e2e.<area>`), and
   `:core:<module>:pixel9Api36DebugAndroidTest` for `:core:player`, `:core:requests` and `:core:sync`. The integrator
   runs the whole suite at every merge.
5. `./gradlew :core:content:verifyDidacticContent` when `:core:content` or the extractor changed.
6. The hygiene hooks installed (`scripts/hygiene/install-hooks.sh`, 20.7); `python3 scripts/hygiene/check_repo.py`
   and `check_ownership.py --wp <id> --base <baseline>` clean (work-plan 3.1).
7. English everywhere; every new string in the module's own `strings.xml` (or its `strings_<name>.xml`); test tags and
   labels on every new interactive element; previews for every new screen state.
8. For server work: `.venv/bin/python -m pytest -q` (the repository's virtual environment) from the repository root,
   all green (server-contract 11), and no file under `web/` changed.

---------------------------------------------------------------------------------------------------------------

## 24. Risks and how the plan contains them

| Risk | Containment |
|---|---|
| AV1 decoding on the test phone (unverified) | decoder check (size, rate, hardware) before downloading the video; audio fallback always; phone video on request in v1-complete; QA records decoders |
| Three video decoders in the pool exhaust memory or codecs on the test phone | release on `ON_STOP` and when Watch is hidden; measured in WP-11 and WP-25; documented fallback (11.2) |
| Broadcast filtered by the owner's router, VPN or guest network | manual address entry is always present; re-discovery tries the last address first; the "Why?" checklist; `VpnBlocks` outcome |
| A rogue device on the Wi-Fi answers as the computer | identity proof before anything is sent (5.3); conflicts shown, never merged (6.1); "Paired: … (from …)" on the computer |
| `MergingMediaSource` timing or decoder edge cases | explicit audio override; follower mode exists for negative offsets and is the fallback if merged playback fails an instrumented sync test (≤ 60 ms) |
| Convention plugins with AGP 9 built-in Kotlin | proved in WP-02 M1 before other modules, together with the test-module dependency shape; fallback to per-module build files |
| R8 and serialization in release | the black-box `:qasmoke` on the unmodified `qa` APK, proven in WP-19's first week |
| Background work deferred or stopped by One UI and Android 14 to 16 | expedited work for what must outlive the screen; background uploads only when they fit; backoff on foreign networks; UI never promises timing; "Background limited" line; foreground loop does the real-time work |
| Link downloads failing on the computer (403) | S-23 hardening, failure states designed, owner-only gate (spec 8.7) |
| A shared link that reaches the LAN or never ends (live stream) | the server re-checks links and confines phone-origin downloads (server-contract 4.5, 9) |
| Parallel merges colliding | git-based integration with three-way merges, ownership table + check, frozen shared files, stubs and fakes from B1 |
| The development computer cannot build many copies at once | at most four Android copies (the integrator's included), GMD runs serialized and filtered (work-plan 1.2) |
| Didactic drift between web and phone | extractor + drift check in CI on web content paths |
| A phone deletes a clip the learner did not mean to delete, or a lost phone deletes many (v1-complete) | only from the Clip page, after a destructive confirmation that names every device; undoable while waiting and never once it may have arrived; a separate route and capability; bound to one computer, library and device id (8.8); at most 20 per phone per 10 minutes, each printed on the computer, `phone revoke` named at start (20.8) |
| Shared progress counts an answer twice, loses one or stops (v1-complete) | ids on every answer and baseline, the baseline named by the backed-up `learning_id` and made of this phone's own answers, a mirror rewritten from the computer's totals, resets by time on one clock; refusals per item, never per request; an unreadable store left untouched; merge rules tested on both sides with a second device (7.8; server-contract 4.7, 11.3) |
| The browser never adopts shared progress | the phone ↔ computer path works alone and the phone's copy says "your computer", never "the browser" (spec 7.13); the hand-off is self-contained (`web-progress-sync.md`) |

---------------------------------------------------------------------------------------------------------------

## 25. Review log (revisions 3 to 5)

Three reviews of revision 2 (architecture), revision 1 (server contract) and revision 1 (work plan), dated
2026-10-10: security (`S-…`), Android platform (`A-…`) and delivery and testability (`D-…`). Every finding is listed
with its disposition: **fixed** (in the place named), **partly** (what was adopted and why the rest was not) or **not
adopted** (with the evidence). "SC" is `server-contract.md`, "WP" is `work-plan.md`. Spec text is owned by the product
track; where a fix touches it, the request is listed in 25.3 and the architecture keeps the spec's behaviour. 25.4 is
the pre-implementation closure of revision 4; 25.5 is change request CR-01 (revision 5), 25.6 and 25.7 its two
rounds of review fixes.

### 25.1 Blocker and high findings

| Id | Finding | Disposition | Where |
|---|---|---|---|
| D-01 | `FakeDomainModule` (`@TestInstallIn`) and the full-stack suite cannot share `:app/src/androidTest` | fixed: one global test set, real repositories, area tests limited to navigation, wiring and seeded happy paths; `FakeDomainModule` removed | 21.5; WP 2.4, 4.6, every wave-2 WP |
| S-H-1 | the phone hands its credential to any host that claims the public server id | fixed: HMAC identity proof in `hello` keyed by the device key; nothing is sent without it; same-id answers are conflicts | 5.3, 6.1, 6.2; SC 5.4, 6.2, 11.3; WP-01, WP-05a, WP-05b, WP-07 |
| S-H-2 | link downloads not confined (redirects, generic extractor, live streams, size, re-run, NAT64) | fixed: generic extractor off for phone-origin jobs, two-phase refusal of live, over-long and over-large downloads, free-space check, link re-checked at re-run and before download, NAT64/6to4/Teredo refused; new job codes | SC 4.5, 6.7, 8.2, 9; 19 (copy fallback); WP-01 |
| A-H1 | Nav3 decorators drop the inactive tab's state and ViewModels on every tab switch | fixed: each stack decorated separately, all composed; per-stack content keys; GMD proof at M2 | 16.2, 11.2; WP-02 |
| A-H2 | application-scope work after leaving the foreground is frozen on Android 14+ | fixed: shared-file copies and the sheet's send are expedited WorkManager work enqueued while the sheet is visible; foreground passes may be cut short by design | 7.2, 8.3, 12; WP-09, WP-23, WP-25 |
| A-H3 | `ShareActivity` keeps the default task affinity | fixed: `taskAffinity=""`, `finishAndRemoveTask()` when it roots its own task; GMD test with and without `NEW_TASK` | 12, 20.1, 21.5; WP-23 |
| A-H4 | v1-core background uploads of large files can never finish | fixed: a file starts in the background only when it fits the window by measured throughput; larger files wait for the foreground; the optional row hint is a spec request (closed by D-41, 25.4) | 8.5, 7.2; WP-09 |
| D-02 | about 35 frozen types have no shape; `ClipData` is frozen before its designer starts | fixed: WP-12 and WP-04 start on day 0 and hand shapes by day 4; WP Appendix A gives every type's fields | 3.4; WP 4, Appendix A, WP-02, WP-04, WP-12 |
| D-03 | integration by copying owned paths overwrites concurrent edits | fixed: git branches or worktrees per WP, three-way merge by the integrator, section rows merged as patches; WP-27 rebases on the post-gate server | 2.7; WP 2.1, WP-27 |
| D-04 | 17 parallel WPs do not fit the development computer; the 32-day path is not real | fixed: three builder lanes plus the integrator (a fourth lane when memory allows), memory settings per copy, one GMD lock, area-filtered GMD runs; schedule restated | 2.4, 21.5, 23; WP 1.1 to 1.3 |
| D-05 | WP-02 is both the longest builder and the integrator | fixed: dedicated integrator role from B1 (WP-19); WP-02 hands over at B1; M3's non-blocking items moved to WP-19 | 2.7; WP 1.1, WP-02, WP-19 |
| D-06 | WP-05 undersized and on the critical path | fixed: split into WP-05a (fake computer, JVM, day 0) and WP-05b (network client, from B1); `SyntheticMedia` moved to WP-11 | 21.3, 21.4; WP-05a, WP-05b, WP-11 |
| D-07 | module rules forbid what the consumes-lists require | fixed: compare rows through `ClipContentRepository.compareRows`; `ShadowingPlan` and `IpaTokenizer` in `:core:common` (WP-12 sub-packages) | 2.2, 3.4, 11.5; WP 3.2, WP-10, WP-12, WP-18 |
| D-08 | a stalled upload holds `upload_busy` forever (QT-4) | fixed: 30 s idle abort and "newest wins" after 10 s idle; pytest with a stalled body; `stallUpload` knob | SC 4.7, 6.6, 11.3; 8.5, 21.3 |
| D-09 | the real-server smoke cannot pass, runs the pipeline and can write the owner's corpus | fixed: host-side fake computer built from the real server code with fake analyze and download, on throwaway directories; sequence corrected (cancel before delete) | SC 12; 21.6, 21.7; WP-06, WP-19 |
| D-10 | [emulator] steps have no safe computer; the needed image is missing | fixed: every [emulator] step uses the host-side fake computer; WP-06 provisions a `google_apis` API 36 image and runs a spike; fallback image named | 21.7; WP-06, WP-25 |
| D-11 | wave-2 GMD proofs of core modules have no task | fixed: `phonotrainer.android.library.instrumented` gives `:core:player`, `:core:requests`, `:core:sync` a managed device; AD-22 amended | 2.3, 21.1, 23; WP-08, WP-09, WP-11 |

### 25.2 Medium and low findings

| Id | Disposition | Where |
|---|---|---|
| S-M-1 replayable bearer token | fixed with the reviewer's option (2): `PT1` signed requests, nonce cache, clock window, `clock_skew`; option (1) (last source address in `phone list`) not adopted: it would store addresses in a file, which SC 3.3 forbids, and signing removes the reusable credential it was meant to expose | 5.3, 8.5, 19; SC 3.2, 4.3, 6.2 |
| S-M-2 pairing code relay | partly: "Paired: … (from <address>)" on the computer, conflicts never preselected, residual risk recorded; the pairing screen showing "{name} at {address}" is **not adopted** because spec F-02 behaviour 3 says no address is shown in the list; it and the conflict wording are spec requests (25.3; closed by D-39, 25.4) | 6.1; SC 2.2, 4.8 |
| S-M-3 server ids become file paths unchecked | fixed: DTO validation, closed extension map, canonical-path check, response and artifact size caps | 4.6, 5.4; WP-05b |
| S-M-4 provider metadata trusted | fixed: `source.<ext>`, byte counting, own-authority refusal, `ClipData` items | 8.3, 12; WP-09, WP-23 |
| S-M-5 resource exhaustion | partly: reservations, 4 sessions and 1 body per device, idle abort, 2 channels per device, upload writes on a dedicated limiter; the per-device job cap is **not adopted**: spec 11.5 and 12 moved it to Later (S-19 withdrawn, S-L5), and idempotency keys already stop retries from creating jobs | SC 4.7, 6.6 |
| S-M-6 phone access remembered across networks | fixed differently: approved networks (interface, network, gateway MAC) checked at start and every 30 s, LAN requests only on approved addresses, discovery only inside them, "suspended" otherwise, virtual interfaces excluded; per-interface binding not used because an interface name (`wlan0`) is the same at home and in a café, and the arrival-address check gives the same protection with one socket | SC 1.4, 2.1, 4.2, 4.4, 5.4 |
| S-M-7 debug logs capture code and token | fixed: logging interceptor at `HEADERS`, redaction patterns in `qa_log.py` and `check_repo.py`, a unit test | 20.4, 20.7, 21.7 |
| S-M-8 hygiene runs too late, misses personal ids | fixed: pre-commit and pre-push hooks, local denylist, MAC and credential patterns, link gate without URLs, `.gitignore` entries | 2.6, 20.7; WP-06, WP-25 |
| S-M-9 DNS rebinding against loopback (pre-existing) | fixed: `LoopbackHostGuard` in every mode; S-21's "byte for byte" now names this one exception | SC 1.3, 4.4, 11.3 |
| S-L-1 takeover through `previous_device_id` | fixed: re-activation needs `previous_token_proof`; `device_id` only on own jobs | 6.3, 8.7; SC 6.2, 6.3 |
| S-L-2 silent lockout, spoofable names | fixed: wrong attempts printed, exit 4 when locked; `Cc`/`Cf`/`Zl`/`Zp` stripped | SC 2.2, 2.3 |
| S-L-3 host name broadcast as the default name | fixed: default "PhonoTrainer computer" and a rename hint | SC 3.2, 5.3 |
| S-L-4 served JSON artifacts outside the path-leak test | fixed: the walk covers served JSON artifacts | SC 4.6, 11.3 |
| S-L-5 uvicorn access log prints addresses | fixed: `access_log=False` with phone access on or suspended | SC 2.1, 3.3 |
| S-L-6 backup without encryption | fixed: `disableIfNoEncryptionCapabilities`; WAL concern does not apply (`learning.db` is TRUNCATE) | 20.1 |
| S-L-7 "Copy diagnostics" carries address and ids | fixed: address class only, no ids | 20.3 |
| S-L-8 code in keyboard history | fixed: `NumberPassword`, no autocorrect or autofill | 6.3 |
| S-L-9 config directory permissions checked only at creation | fixed: checked and tightened at every open | SC 3.1 |
| S-L-10 middleware robustness | fixed: pure ASGI for both scopes, decoded path with `.`/`..` refused, JSON 404 for unknown phone paths, tests | SC 4.2, 11.3 |
| S-L-11 task hijacking on minSdk 29 | partly: `ShareActivity` gets `taskAffinity=""`; `MainActivity` keeps its affinity (an empty one on a `singleTask` launcher activity changes task lookup) and raising minSdk to 30 is **not adopted** because spec NFR-01 fixes minSdk 29; the Android 10 residual risk is recorded in `SECURITY.md` | 12, 20.1 |
| S-L-12 titles on the lock screen | fixed: `VISIBILITY_PRIVATE` with the channel name as the public version | 13 |
| S-L-13 permissive `AddressPolicy` in a release classpath | fixed: hygiene rule | 20.7 |
| S-L-14 supply chain | partly: wrapper validation in CI, `distributionSha256Sum` pinned; `verification-metadata.xml` **not adopted** in v1: every catalog change by the integrator would need regenerated metadata across many parallel WPs; revisit after v1-core | 22 |
| A-M1 `sync-on-wifi` loops on foreign Wi-Fi | fixed: 15 min → 2 h backoff, never zero delay from a run, reset on foreground network change; periodic work only while paired; validated-network note | 7.2 |
| A-M2 three decoders in the pool | fixed: release on `ON_STOP` and when Watch is hidden; poster until first frame; measurement and documented fallback | 11.2; WP-11, WP-25 |
| A-M3 merged playback picks the wrong audio | fixed: audio first, `TrackSelectionOverride` on child 0, instrumented test | 11.1, 21.4 |
| A-M4 ducking pauses for good | fixed: duck lowers the volume and restores it; only losses pause | 11.4 |
| A-M5 a complete part is never finished (416) | fixed: verify the part when complete or on 416 | 7.4; SC 6.4.4 |
| A-M6 `StartupCheck` races work in the same process | fixed: acts only on rows and files older than the process; consumers await it | 4.6 |
| A-M7 notification `PendingIntent`s collide; targets re-applied | fixed: request code and `data` URI per target; applied once, then cleared | 13, 16.4 |
| A-M8 permission dialog versus the 2 s auto-close | fixed: auto-close suspended while the dialog is pending | 8.1, 12 |
| A-M9 no Baseline Profile on a sideloaded APK | fixed: `profileinstaller` and `:baselineprofile`; timings with and without | 2.4, 16.5, 21.6; WP-19 |
| A-M10 Hilt instrumented smoke on the R8 build | fixed: black-box `:qasmoke` on the unmodified `qa` APK | 21.6; WP-19 |
| A-M11 socket factory fixed per client | fixed: delegating factory binding each socket, pool eviction on change, `VpnBlocks` | 5.1, 6.2 |
| A-M12 quota and data-sync timeouts untested | fixed: platform switches in `torture.py`, stop reasons logged, the rejection of user-initiated jobs recorded in `decisions.md` | 7.2, 21.7; WP-25 |
| A-L1 wrong 16 KB rationale | fixed: the two native libraries named; `zipalign` and the 16 KB image | 2.5, 21.6 |
| A-L2 `isSizeSupported` does not measure speed | fixed: `areSizeAndRateSupported`, performance points, both orientations, hardware or software | 7.4, 11.7 |
| A-L3 free space through `StatFs` only | fixed: `getAllocatableBytes` | 7.5 |
| A-L4 R8 log stripping is a no-op | fixed: lambda messages dropped by the release facade | 20.4 |
| A-L5 periodic work while not paired | fixed (with A-M1) | 7.2, 6.3 |
| A-L6 targetSdk 36 implications unstated | fixed: insets, predictive back, no orientation lock (spec 5.2) | 16.5, 12 |
| A-L7 Samsung TalkBack component | fixed: read from the device, exact restore | 21.7 |
| A-L8 `SEND_MULTIPLE` grants on `ClipData` | fixed | 8.3, 12 |
| A-L9 X-AC1 needs all traffic | fixed: emulator `-tcpdump` run | 21.7; WP-25 |
| A-L10 expedited work on API 29–30 | fixed: `getForegroundInfo`, a Robolectric sdk 30 test | 7.2, 21.1 |
| D-12 v1-core stubs bind the extension points | fixed: stubs bind nothing; a GMD assertion per fallback | WP 4.4, WP-19 |
| D-13 no place for shared components' strings; resource names | fixed: `strings_<component>.xml` rows; `:app`-referenced resource names frozen | 2.7; WP 3.2, 4.1 |
| D-14 `ApiResult.Refused` drops `job`, `offset`, `Retry-After` | fixed | 5.4; WP 4.3 |
| D-15 no client method for the old-server probe | fixed: `legacyHealth` | 5.4, 6.2; SC 6.2; WP 4.3 |
| D-16 `phone on` on a server started without phone access | fixed: restart hint; `phone pair` refuses when off | SC 2.1, 2.2, 11.3 |
| D-17 two ports of `mk_analysis` | fixed: one `AnalysisFixtures` in `:core:testing-jvm` | 2.2, 21.3; WP-12 |
| D-18 test-to-main dependency loops unproven | fixed: testing modules never depend on implementations; network API in its own JVM module; spike at M1 | 2.2; WP-02 |
| D-19 `qa` smoke proven too late | fixed: proven in WP-19's first week, black-box (A-M10) | 21.6; WP-19 |
| D-20 signing for X-AC7/8 unassigned | fixed: key before the first phone install; only release builds on the phone; N and N+1 by WP-19 | 20.5; WP-19, WP-25 |
| D-21 ACs without a WP or method | fixed: A-13 (WP-19, WP-25), NFR-02 on the phone (WP-25), NFR-03 size (WP-19), F-02-AC9 and SC-15 (WP-06), SC-16 (orchestrator), X-AC1 capture (WP-25) | 21.5 to 21.7; WP 10 |
| D-22 language-folder rule versus `values-night`; `__pycache__` | fixed | 2.6, 20.7 |
| D-23 server defects during v1-core QA have no owner | fixed: the integrator owns server maintenance after WP-01; WP-27 rebases | WP 2.1, WP-19, WP-27 |
| D-24 error copy duplicated in five modules | fixed: one `RequestFailureCopy` | 19, 13; WP-22 |
| D-25 pre-release schema churn | fixed: fold into version 1 until the first phone install | 4.4 |
| D-26 `design.md` absent; no ownership row for `docs/design/` | fixed: WP-03 starts on the design track's delivery (the plan names the condition, not a date); row added | WP 3.2, WP-03 |
| D-27 "Cancelled." message | fixed: the rule is on status; both store texts named | SC 8.2 |
| D-28 version string format | fixed: `2026.8.19` as `importlib.metadata` returns it | SC 3.4, 6.2 |
| D-29 nulls versus non-null domain fields; strict enums | fixed: 0 for unknown rotation and offset; lenient enums | SC 6.3, 6.4.3; 5.4 |
| D-30 section boundaries in `check_ownership.py` | fixed: whitespace-only lines at a boundary belong to the new section | WP 3.1 |
| D-31 GPU property in CI | fixed: CI overrides it | 2.4, 22 |
| D-32 queued `UNPAIR` can never be sent | fixed: unpair only while connected, never queued | 4.2, 6.3, 7.1; SC 6.2 |
| D-33 drift guards that read `web/` have no owner | fixed: the integrator of the current wave | SC 11.3; WP 2.1 |
| D-34 shared library and device takeover acceptable but unstated | fixed: stated in the accepted risks (and the takeover closed, S-L-1) | SC 4.8 |

### 25.3 Requests to the product track (spec text; the architecture already behaves as described)

**All seven are closed by spec revision 3** (decisions D-37 to D-43, spec 15.6): each was accepted, the spec now says
it, and the notes that waited for the text (6.1, 6.2 rule 2, 8.5, 19; SC 8.2 rows 4 and 5) were replaced by the copy
in revision 4 (25.4).

1. Spec 7.3 item 5 and 7.4: "checks that the address answers with the paired server id" → "checks that the address
   proves it is the paired computer" (the HMAC proof of 5.3 is a strict strengthening of the same rule).
   **Closed: D-37.**
2. Spec 11.6: the credential is captured only if someone records the pairing exchange; later requests are signed and
   cannot be replayed. **Closed: D-38.**
3. F-02: wording for two computers answering with the same name or identity (listed, none preselected), and,
   optionally, this phone's own Wi-Fi address in Connection details (security review M-2). **Closed: D-39 (both
   parts; 6.1).**
4. Spec 8.6: title and body for the job codes `live_stream` and `too_long`; until then they show the
   `processing_failed` copy with the computer's message. **Closed: D-40 (19).**
5. Spec 7.7: optional row hint for a large file waiting for the app to be opened ("Open PhonoTrainer to send this
   file"); until then the row shows the waiting text. **Closed: D-41 (8.5).**
6. Spec 7.2 and S-01: phone access stays on across restarts **on the networks the operator approved**; on another
   network it is suspended until approved (operator-facing terminal behaviour, server-contract 2.1), and the computer's
   default display name is "PhonoTrainer computer". **Closed: D-42 (6.2 rule 5).**
7. Spec 8.7 and S-23: phone-origin links longer than one hour (`max_link_duration_s`, editable on the computer) or
   live streams are refused; confirm the default. **Closed: D-43** (default of 1 hour confirmed).

### 25.4 Pre-implementation closure (revision 4, 2026-10-10)

Inputs: the cross-track consistency review's open items for architecture, spec revision 3 (decisions D-37 to D-52,
spec 15.6), the design track's asset review and the staged export. Every item was re-checked against the four
documents before it was closed; items marked "found" were discovered while verifying the others. "WP" is
`work-plan.md` (revision 3), "SC" `server-contract.md` (revision 3), "design" `design.md`; what the design track
must mirror (C-02, C-04, C-13, C-14, C-19, C-21) was sent to it through the orchestrator.

| Id | Item | Disposition | Where |
|---|---|---|---|
| C-01 | `changeCounts()` counted eligible occurrences, while the spec, the Home chips, the mode picker and the Learn cards count shorts (consistency open item 8; D-48) | fixed: `changeCounts()` returns `ChangeCount(shorts, occurrences)` from one grouped query over playable clips; `shorts` is by construction the length of the change-mode sequence (F-03-AC7), `occurrences` serves the phenomenon page only (F-15-AC1) | 3.4, 9.4; WP A.3, WP-10, WP-15, WP-17, WP-20 |
| C-02 | `COPY_FAILED(no_space)` offered "Try audio only" with no transition, and a shared item's read grant ends with the sheet (consistency open item 9; D-49) | fixed: `COPY_FAILED` `copy_no_space` + "Try audio only" → `PREPARING` (audio track, same request) only while the source is readable: the open sheet holds its sources (`RequestRepository.holdSources`, new), or a picked file whose persisted permission is now kept while `copy_no_space`; otherwise the action is absent and the row reads "…then share it again and choose Audio only." with "Remove". Design asked to make 9.19's row and 9.22's sheet state conditional | 4.2, 8.3, 8.4, 12, 19; WP-09, WP-22, WP-23 |
| C-03 | found: "Try audio only" on a video refused at sending (`too_large`) mapped to a `RERUN`, but no job exists to re-run | fixed: local re-extraction from the kept copy → `PREPARING`; the request id stays the upload key (a refused declaration opened no session; a stale `key_conflict` session is deleted first) | 8.4 |
| C-04 | found: re-runs sent `audio_only` for any kind, which the server refuses for uploads (`400 invalid_option`), and the job `no_space` actions depend on the kind (D-49) | fixed: the re-run rows are split by kind (files always `audio_only` null); `Request.actions` is the one authority, and `RequestFailureCopy` shows the spec order intersected with it; a table test per code and kind | 8.4, 8.6, 19; WP 4.5, WP-09, WP-22 |
| C-05 | found: "Send now" on a held request had no transition (D-46) | fixed: `WAITING` + "Send now" clears `held` and makes it due at once | 8.4; WP-09 (F-20-AC11) |
| C-06 | found: the spec's "Free {N} MB" had no data behind it | fixed: `needed_bytes` column, `RequestError.neededBytes`, and the phone-side codes `copy_no_space`, `copy_unreadable`, `copy_interrupted`, `copy_missing` in `state_reason` | 4.2, 8.3; WP A.4 |
| C-07 | 17 still said "legacy WebP mipmaps from one geometry", which minSdk 29 never uses (consistency open item 10; D-50) | fixed: struck; adaptive XMLs in `mipmap-anydpi/` (the design track's re-export already moved them there), no raster mipmaps; the `VectorPath` limit stated (the re-export split the launcher background; the longest staged path is now 2,976 characters as lint counts them, 2,732 without blanks; design 12.3) | 17; WP-03 |
| C-08 | the licence check of 20.6 compared only the dependency report, which never lists the vendored fonts and icons (consistency open item 10; D-50; X-AC3, F-24-AC2) | fixed: two-part check `verifyLicences` (integrator): libraries against notices 7.2 through the report; fonts and the Lucide set against 7.3 through WP-03's `assets/licenses/bundled.json`, with licence files present and font SHA-256 equal; About renders both | 2.2, 17, 20.6; WP-03, WP-19, WP-24, WP 10 |
| C-09 | 3.4 did not mirror the consistency pass's additive fields and ownership moves (consistency open item 11) | fixed: `Request` gains `waiting`, `timeline`, `rerunPending`, `updatedAt` in the listing; a revision 4 paragraph lists every other additive field (A.1 to A.9) and the moves (`ConnectionStatusCard` + `ProbeOutcomeCopy` WP-15, `ShareBridge` + `ConnectionLine` WP-23, `PosterArt` and `MarksLegend` WP-03, the shell's message host, banner slot and appearance helper) | 2.2, 3.1, 3.4, 11.6, 16.5, 17 |
| C-10 | WP-03's inputs must equal the staged export | verified on 2026-10-10, after the design track's closure re-export: 173 files (`res/drawable` 142 = 122 icons, 3 launcher layers, `ic_notification`, 14 illustrations, 2 splash; `res/drawable-night` 16; `res/font` 5; `assets/licenses` 5; `res/mipmap-anydpi` 2; `res/values` 2; `res/values-night` 1); the 122 icon files equal `icons.json` (none extra or missing); the tool list follows `design-src/tools/` (`export_vectors.py`, `lint_vectors.py` included); WP-03 now lists them file by file with an inventory test, and adds `bundled.json` | WP-03 |
| C-11 | D-37 (25.3 item 1): proof of identity, not the server id | closed; no behaviour change; the 6.2 note now cites the spec; `impersonate(proof = MISSING \| WRONG)` knob; F-02-AC11 tests | 6.2, 21.3; WP-05a, WP-07, WP 10 |
| C-12 | D-38 (25.3 item 2): the credential travels once | closed; `SECURITY.md` states spec 11.6's wording (WP-19) | WP-19 |
| C-13 | D-39 (25.3 item 3): discovery conflicts and this phone's address | closed: the conflict copy and `SearchState.Found.conflict`; Connection details shows `ConnectionDiagnostics.phoneAddress`; "Copy diagnostics" leaves it out; F-02-AC10 and F-22-AC4 assigned to WP-07 and WP-13 (Connection details is WP-13's destination, not WP-24's) | 6.1, 20.3, 21.3; WP A.1, WP-07, WP-13, WP 10 |
| C-14 | D-40 (25.3 item 4): copy for `live_stream` and `too_long` | closed: `RequestFailureCopy` maps both (limit from `Capabilities.maxLinkDurationS`, as hours or minutes, or the unknown-limit body), the `processing_failed` fallback is for unknown codes only, the F-23 notification-line rule; SC 8.2 rows 4 and 5 name the titles | 13, 19; SC 8.2; WP 4.5, WP-05a, WP-22 |
| C-15 | D-41 (25.3 item 5): large files wait for the app | closed: `WaitingInfo.needsAppOpen` from the uploader's own rule, shown whatever the connection state; the throughput average kept in the pairing store | 4.5, 8.5; WP 4.2, WP-09, WP-22 |
| C-16 | D-42 (25.3 item 6): approved networks | closed: `phone_access: false` and `lan_forbidden` give `ProbeOutcome.PhoneAccessOff`, worded once by `ProbeOutcomeCopy` (WP-15, new ownership row); `lanForbidden()` knob; F-02-AC12 tests | 6.2, 21.3; WP 3.2, 4.5, WP-05a, WP-07, WP-13, WP-15 |
| C-17 | D-43 (25.3 item 7): confined links, 1 hour default | closed; nothing to change (SC 3.2, 4.5, 9, 10.3 already) | — |
| C-18 | D-44: lean channel events in v1-core | checked: no "v1-complete" wording for lean events remains in the three documents | — |
| C-19 | D-45: no "What's in today's set" control; the card shows the set | mirrored: `StudySetItem.Short.done` (with `spotlight` and `savedTag`) from WP-14; WP-15 builds the card's sentence; F-03-AC10 tests | 3.4; WP A.5, WP-14, WP-15 |
| C-20 | D-46: "Stop sending" holds the request | already the behaviour (8.4 `held`); see C-05; F-20-AC11 tests | 8.4; WP-09, WP-22 |
| C-21 | D-47: copy for states that had none | mirrored: `UserMessageKind.CANCEL_UNDONE` carries the title; `FeedPlaybackState.trackFallback` with the queue refresh that ends it; F-06-AC13, F-14-AC4, F-20-AC12 tests | 3.4, 11.1; WP A.7, A.9, WP-09, WP-11, WP-17, WP-20, WP-22 |
| C-22 | D-51: no auto-close while the notification question is open | mirrored: 8.1 and 12 name the in-app question and the system dialog, and the 2 s count restarts after the answer | 8.1, 12; WP-23 |
| C-23 | D-48 to D-50, D-52 | D-48 is C-01, D-49 is C-02 to C-04, D-50 is C-07 and C-08; D-52 (defaults apply) changes nothing here | — |
| C-24 | found: WP-17's mode picker shows short counts but did not consume `changeCounts()`, and nothing told it when a missing track arrived | fixed: WP-17 consumes `OccurrenceRepository.changeCounts` and `ClipRepository.clip` | 11.1; WP-17 |
| C-25 | new and reworded acceptance criteria of spec revision 3 | traced: WP section 10 lists each AC with its WPs and what each proves (SH-AC17 and SH-AC18 are WP-22's and WP-09's: they are opened from Requests after the sheet closed) | WP 10 |
| C-26 | 25.3 | all seven requests closed by D-37 to D-43; nothing remains open for the product track | 25.3 |
| C-27 | closure verifier, blocking: lint's version-freshness checks would fail `warningsAsErrors` on the pinned catalog with no code change | fixed (orchestrator decision): lint policy in 2.3; `GradleDependency`, `AndroidGradlePluginVersion`, `NewerVersionAvailable` and `OldTargetApi` ignored through the one INTEGRATOR-owned `config/lint/lint.xml`, nothing else, no baseline | 2.3, 23; WP-02 (creates the file at M1), WP-19 |

### 25.5 Change request CR-01 (revision 5, 2026-10-11)

Inputs: the owner's answers to Q-1 to Q-7 (spec 15.3, 15.7: D-53 to D-57), spec revision 4 and the product track's
requests A1 to A12, the design track's requests A-1 to A-10 (design.md revision 3), the state of wave 1 on 2026-10-11
(the integration log and the work packages' progress files: B1 published and merged into this branch; WP-06-2 merged
into `android-client`; WP-01 milestone B, the rest of the v1-core server at API 6, finished on its branch and in review,
not merged; WP-02 M3, WP-03 and WP-19's first week running; no wave-2 WP branched) and the code of B1's `:core:domain`,
`:core:network-api` and `:core:database`, `phonotrainer/jobs.py`, `server.py`, `phonotrainer/phone/` (WP-01's branch,
read only) and `web/src/features/practice/quiz.ts` and `PracticePage.tsx` (read only), plus WP-01 milestone B's hand-off
and WP-06-2's `host_computer.py`. Rule: implement exactly what the answers ask, minimally and robustly; disturb no work
in flight. "SC" is `server-contract.md` (revision 4), "WP" `work-plan.md` (revision 4).

| Id | Item | Disposition | Where |
|---|---|---|---|
| C-28 | Q-2, D-54, S-25: the phone may delete a finished analysis on the computer | a separate signed route (`POST /api/phone/jobs/{id}/delete-finished`, server id and library id in the signed query, any submitter, `done` only, `already_gone` is a success) instead of relaxing v1-core's `DELETE /api/phone/jobs/{id}`, whose `409 not_deletable` stays: v1-core's route, tests and the fake computer keep their meaning, and a capability flag gates the new one. On the phone an outbox operation `DELETE_FINISHED` bound to server id, library id and device id (AD-24) | 8.8, 7.1, 7.4, 7.7, 13; SC 4.2, 6.7, 6.11, 8.1 |
| C-29 | Q-3, D-55, S-24: practice accuracy and the Saved list shared through the computer | answers with ids and computer-clock times summed after the latest reset; baselines named by `learning_id`; Saved last-change-wins with tombstones; the phone mirrors the shared totals into `practice_stat`, so v1-core's readers do not change (AD-25) | 7.8, 10, 4.3; SC 3.2, 6.12 |
| C-30 | where the computer keeps it | practice history in `progress.sqlite3` in the configuration directory (per computer: survives a workspace switch, spec 7.13 rule 6; already outside the repository and every workspace, with permission checks); the Saved list in `<workspace>/saved.json` (per library, anchored to its job ids). SQLite for the answer log (unique ids, time index, transactions), JSON for the small Saved list (the same atomic-write style as `library.json`) | SC 3.2, 4.7 |
| C-31 | the browser client (`web/`, another agent's) | loopback-only additive routes `GET`/`POST /api/progress/practice` with the `/ws/jobs` Origin rule, present in every mode when started by `phonotrainer ui`, inert until called; a self-contained hand-off for the web owner; the phone ↔ computer path never depends on it | SC 1.3, 4.2, 6.13; `web-progress-sync.md` |
| C-32 | product request A3 (an `imported` flag in the listing) | not added: the listing already says it (`source.kind: "import"`, `RemoteSource.kind = IMPORT`, `clip.source_kind`) | SC 6.3; 8.8 |
| C-33 | capability flags and version | `delete_finished` and `shared_progress` in `health.capabilities`; both ship in API 7, set by WP-27's first milestone; the second milestone adds its flags within 7 (SC 1.2 rule 7), which keeps WP-05a's `API_VERSION_COMPLETE = 7` valid | SC 1.2, 3.4; 5.4 |
| C-34 | frozen B1 types | CR-01-A1 now (Room `learning_meta` with `learning_id`, created by `DatabaseModule`'s callback), because every backup of every release must carry the id; CR-01-A2 at the start of v1-complete (domain, SPI and network-api additions with defaults; Room version 2 by `AutoMigration`) | 3.4, 4.1 to 4.4; WP 2.2, 4.7 |
| C-35 | additive, not source-breaking | no new member in `RequestAction`, no new subtype of `ClipAvailability`, no new `PauseReason`: consumers' exhaustive `when`s would stop compiling; separate `DeletionAction`, the field `ClipSummary.deletion`, and the Clip page's deletion block instead. `UserMessageKind` grows (one exhaustive consumer, updated by the integrator in the same change) | 3.4, 7.4 |
| C-36 | "Forget this computer" drops waiting deletions | by binding each deletion to the paired device id (a new pairing after "Forget" always gets a new one), checked when `paired` changes, at start and in the sender; not by a hook in `:core:connectivity`, which would add v1-core scope to WP-07 | 6.3, 8.8 |
| C-37 | server work in flight (WP-01 milestone B, API 6) | untouched: the new server work is WP-27's milestone A (phase complete, the owner of the server files in v1-complete), which may start on its own branch now and merges after the v1-core gate like every v1-complete server change. A "WP-01-C" was considered and not adopted: WP-01 owns the server only in phase `core`, so its milestone would either merge before the gate (v1-core QA would test v1-complete code) or need a second owner of the same files in phase `complete` | WP 1.4, WP-27 |
| C-38 | spec F-05-AC11 (v1-core: the "Remove from this phone?" confirmation gets words) | copy only, no behaviour: added to WP-16's Covers (WP-16 has not started) | WP-16 |
| C-39 | D-57: device QA never deletes the owner's clips or resets the shared history | `host_computer.py` seeds a deletable non-imported clip and a second device; the kit's destructive helpers run only when the phone is paired with a computer the kit started in the session (its server id in the QA log), never with the owner's own; the QA log counts QA's answers | 21.7; SC 12; WP-06, WP-33 |
| C-40 | Q-1, D-56: version 2 encrypted transport | designed (26, AD-26): TLS 1.3, pinned self-generated key, SRP-6a pairing bound to the pin, migration proof with the v1 device key, no downgrade, PT1 kept; not in the v1 work plan | 26; SC 4.8, 13 |
| C-41 | Q-4, Q-5 confirmed; Q-6, Q-7 unanswered | nothing to change (D-36 and the link gate, D-42 approved networks, no spaced repetition, QA never reboots the phone) | — |
| C-42 | the design track's requests A-1 to A-10 (design.md revision 3) | A-1: the names of 3.4 (`ClipSummary.deletion`, `DeletionOffer`, `ComputerDeletion` with `imported`); A-2: `DeletionCopy`, whose sentences are the product track's (spec D-62; 19); A-3: the three `UserMessageKind`s are adopted, but deletion actions are `DeletionAction` and the row is `DeletionRow` fed by `RequestRepository.deletions()`, not a `RequestAction` or a `Request` kind (C-35); waiting reasons as F-20's through `WaitingInfo`; A-4: `SharingState`; A-5: `Capabilities.deleteFinished` and `source.kind: "import"` (C-32); A-6: `ui/place/` in `:core:ui`, WP-34; A-7: the two staged icons are converted by the integrator in CR-01-A2 (WP-03's conversion script, `PhonoIcons`, `bundled.json`, notices 7.3), so WP-03's frozen 173-file inventory never moves; A-8: in WP-34 (reusing `ShareBridge`'s motion); A-9: recorded in 26.12; A-10: renders 28 to 34 are the references of WP-34's previews and of WP-33's screenshots | 3.4, 8.8, 19, 26.12; WP 3.2, 4.7, WP-26, WP-33, WP-34 |
| C-43 | a body is not signed (SC 4.3), and a reset clears the history on every device | a phone's reset travels in the signed query (`reset_id`, `reset_at`); a `reset` in the phone route's body is `400`. The same principle as the deletion's target (C-28). Answers and Saved changes stay in the body, whose SHA-256 is now in the signed query too (C-54: the review showed that an unsigned body could do more than miscount, and that a restart reopened replays) | 7.8, 20.8; SC 4.8, 6.12 |
| C-44 | robustness of the Saved exchange | the cursor is opaque (`<list_id>:<version>`, `saved_cursor` on the phone), so a `saved.json` deleted, recreated or restored is answered with a full list instead of silently skipping changes; the 5,000-entry bound answers `200` with `not_shared` instead of a `409` that would block the request's unsaves forever | 4.3, 7.8; SC 3.2, 4.7, 6.12, 8.1 |
| C-45 | merge semantics: per-client grow-only counters (the orchestrator's example) or an answer log | an answer log with ids and times: the spec's reset (7.13 rule 3: an answer given before the reset but arriving after it is cleared, one given after it counts, whichever device) needs each answer's time, which per-client counters summed on read cannot keep without becoming a log; the log is bounded (SC 4.7) and the phone keeps a counter table (`practice_stat`) as a mirror, so its readers do not change | 7.8; SC 3.2, 6.12 |
| C-46 | design request P-2 (a refused deletion's effect on sets and downloads) and the waiting reasons (P-4) | a refused deletion stays in `PendingDeletions` (out of new sets, downloads paused) until "Try again" or "Keep the clip", because it is neither applied nor undone (spec 7.8's words); rows and shelf cards show nothing for it (only Requests and Home count it). Confirmed by spec D-59 (request R-1); the waiting reasons are F-20's in spec D-61's order (19, `DeletionCopy`, `WaitingReasonCopy`) | 8.8, 19 |
| C-47 | security of the two features | 20.8 lists each threat, rule and test; server tests in SC 11.3 | 20.8 |
| C-48 | DataStore | no file added (4.5): the bookkeeping must be backed up with the progress it describes; two pairing-store keys for the capability flags (C-66) | 4.5 |
| C-49 | the deletion's idempotency key | the phone sends its outbox operation's id on every attempt; the computer keeps **no** memory of keys (a draft had a 600 s cache): a second deletion of the same job answers `already_gone: true`, which is a success, so the cache changed no outcome and contradicted spec QT-16's "the computer's answer that the clip is already gone". The key is kept for the audit line, which shows that two requests were one deletion | 8.8; SC 4.7, 6.11 |
| C-50 | request and answer sizes of shared progress | a client sends a baseline in a call of its own (the worst case, 64 labels of 40 characters plus 100 answers, would cross the 16 KiB JSON limit); an incremental Saved answer with more than 5,000 changed entries becomes a full list, so every answer fits the phone's 1 MiB cap. Both computed from the field limits, not estimated | 7.8; SC 4.7, 6.12; `web-progress-sync.md` 6.2 |
| C-51 | the change request's suggestions (a "WP-01-C" milestone; WP-01 milestone B called "server v1-complete, API 7") | WP-01 milestone B is the rest of the v1-core server at API 6 (work-plan WP-01, its progress file); API 7 is the v1-complete server, set by WP-27's milestone A (C-33, C-37). Nothing in WP-01 changes | WP 1.4, WP-27 |
| C-52 | where the phone's new screens go | one new v1-complete package, WP-34, instead of new scope in WP-16, WP-21, WP-22 and WP-24: those are v1-core wave-2 packages, sized and laned for the v1-core gate, and v1-complete work starts after the gate (WP 1.4), so they would have to be reopened then anyway; one package keeps the shared pieces (`DeletionCopy`, `ui/place/`) with one owner and lets the cut order drop CR-01's screens as a unit (spec 14.4 rows 10 and 11). Its seeded happy paths over the real repositories are WP-26's cross-area flows, so it needs no core to finish | WP-34, WP-26, WP 3.2 |

**Requests to the product track (CR-01; spec text only), all answered in spec 15.7 (fix round 2, 25.7):**
- **R-1** (design P-2): a refused deletion keeps its clip out of new sets and its downloads paused until "Try again"
  or "Keep the clip"; Library rows, shelf cards and the Clip page header show nothing for it (Home and Requests count
  it; the Clip page block shows the refusal). **Answered: confirmed as written, spec D-59.**
- **R-2**: the refusal sentences of 19. **Answered by spec D-62**: `not_finished` and "any other code" confirmed;
  `different_computer` waits with "A different computer answered" and "Undo"; a computer without the route
  (`unknown_route`) is a refusal with F-05 item 6's "not offered" sentence, "Try again" and "Keep the clip", not the
  wait `COMPUTER_CANNOT` that fix round 1 had proposed (8.8 and 19 follow D-62; C-75).
- **R-3**: a revoked phone whose own credential key was lost re-pairs as a new device, and its waiting deletion is
  dropped, never sent (8.8). **Answered: spec D-63** (added to 7.9 and the 7.7 state machine; no message).
- **R-4**: the computer's copy is the shared history: if the operator deletes `progress.sqlite3` (or
  `<workspace>/saved.json`), every phone's shared accuracy (or Saved list of that library) follows at its next sync,
  keeping only what it had not shared yet. A file that is unreadable or of an unknown schema is never recreated or
  overwritten (the computer answers `503` and the phones keep what they have, review fix C-64). **Answered: spec D-64**
  (7.13 rule 9, with the unreadable case; the README says so, SC 10.3).

No request to the design track: every state above is drawn in design 9.26 and 9.27 (renders 28 to 34).

### 25.6 CR-01 review fixes (revision 5, fix round 1, 2026-10-11)

Inputs: the CR-01 security review (findings F-1 to F-15) and consistency review (findings 1 to 14) of revision 5, as
routed to the architect. Every high and medium item is fixed; the low ones too, because each was cheap. Nothing in
flight changes: WP-01 milestone B and WP-02 M3 are untouched, every phone-side change is in CR-01-A2 (not yet applied)
or in v1-complete WPs that have not started, and every server change is WP-27 milestone A's.

| Id | Finding | Disposition | Where |
|---|---|---|---|
| C-53 | security F-1 (high): the hand-written SRP-6a lacked RFC 5054's checks; an `A` of 0, `N` or `2N` makes `S = 0`, so a pairing with no code | the computer refuses `A mod N = 0`, the phone refuses `B mod N = 0`, both refuse `u = 0`; 256-bit fresh secret exponents; a single-use `pair_id` (any failed `finish` counts as a wrong attempt); constant-time `m1`/`m2` comparisons; tests with `A` in {0, `N`, `2N`} next to the RFC vectors | 26.4, 26.12; SC 13 |
| C-54 | security F-2: bodies of the new routes unsigned, and nonces only in memory, so a request recorded before a restart could be replayed with any body (unsave everything, a huge baseline, an undone deletion) | the three CR-01 routes carry `body_sha256` in the signed query (the technique C-43 used for the reset; the PT1 string does not change); the authenticator refuses a `ts` older than the process start with `401 clock_skew`, which the phone already answers by re-signing once, so restart replays are closed on every route; the wording of SC 4.8 and 20.8 corrected | 5.4, 7.8, 20.8; SC 4.3, 4.7, 4.8, 6.11, 6.12 |
| C-55 | security F-3: `409 store_full` refused whole requests and wedged a phone's outbox for good; unbounded Saved positions and tombstones could break the 1 MiB cap; totals could pass 32 bits | content refused per item, never per request (`store_full` removed): reference labels always accepted, at most 48 other labels, extra baselines ignored, Saved positions bounded (≤ 1,000,000 and within the job's own counts, else `dropped`), at most 10,000 entries with tombstones pruned early (advancing `pruned_through`), totals clamped to 2,147,483,647; the worst-case answer recomputed (≈ 0.99 MB); the phone marks every refused item handled and a `400` page handled too | 5.4, 7.8, 20.8; SC 4.7, 6.12, 8.1 |
| C-56 | security F-4: any paired credential can delete any finished analysis with no brake, and phones paired under API 6 gain the power silently | 20 deletions per device per 10 minutes (`429 rate_limited` with `Retry-After`; already transient on the phone); a banner line and a README paragraph naming `phonotrainer phone revoke` | 8.8, 20.8; SC 2.1, 4.7, 6.11, 10.3 |
| C-57 | security F-5: candidate mode accepted any certificate for the already paired computer at a new address, and one shared client could reuse a candidate connection for signed requests | candidate mode only while no pin exists; a separate candidate client with its own socket factory and pool, without the signing interceptor; the version-2 `hello` proof binds the pin; an instrumented pool test | 26.3, 26.8, 26.9 |
| C-58 | security F-6: stripping `tls_port` made a version-2 app pair in clear on a version-2 computer; a forged `tls_port` pushed a v1-paired phone into "Connect again" | a version-2 computer serves v1 pairing only for a `phone pair --legacy` code (any other attempt is `code_invalid`, the v1 answer when no code exists); a missing or wrong pin proof keeps the phone on v1 with "Not encrypted" | 26.4 to 26.7, 26.10, 26.11; SC 13 |
| C-59 | security F-7: a key recorded at a v1 pairing stayed valid after the move to TLS | the move ends with a rekey inside pinned TLS (`POST /api/phone/v2/rekey`, a key the phone chose, accepted only on the TLS listener; device id kept); `tls_required` after it; a key stolen and used first shows "Connect again". Deviation from the finding's wording ("returns a new token"): the phone proposes the key, so no secret is ever returned and a lost answer is settled with a `hello` instead of a second token | 26.5, 26.6, 26.10, 26.11; SC 13 |
| C-60 | security F-8: the deletion audit line was INFO on a logger uvicorn's configuration drops, and named the device by 4 useful characters | a dedicated `phonotrainer.audit` logger with its own stderr handler installed by the app's lifespan in every mode; the line names the phone and its 12-character short id (as `phone list` prints it); tested under uvicorn's `LOGGING_CONFIG` | 20.8; SC 3.3, 6.11, 10.1, 11.3 |
| C-61 | security F-9: "Undo" after an attempt with an unknown outcome could say something false; a `404` could be read as "already gone" | `maybe_sent` on the row withdraws "Undo" (and shows spec D-65's line, C-76); answers matched by status and code, `unknown_route` never "already gone" (a refusal, spec D-62, C-75); `ComputerDeletion.actions` is the one authority | 4.2, 8.8, 19; WP 4.7 |
| C-62 | security F-10: "Forget this computer" raced a deletion in flight | `forget()` runs inside `SyncController.exclusive`, which waits for an outbox call on the wire and applies its answer before the unpair and the key deletion; QT-18's in-flight variant in WP-28 | 6.3, 8.8; WP 4.7, WP-28 |
| C-63 | security F-11: a first share with a second computer sent the first computer's aggregate (other devices' answers) as this phone's baseline | `own_stat`, this phone's own answers since the latest reset it knows of, written with each answer, cleared by a reset, rebuilt from the kept answer rows when another device's reset arrives; the baseline for a computer other than the one `share_state` names (sent rows kept 30 days for that) | 4.3, 7.8, 20.8; WP 4.7 |
| C-64 | security F-12: an unreadable store's behaviour was unspecified, and recreating it would wipe every device | fail closed: `503 store_unavailable` (transient) and a warning; the file is never recreated or overwritten; R-4 then covers only a deliberate deletion | 7.8, 20.8, 25.5 R-4; SC 3.2, 8.1, 11.3 |
| C-65 | security F-13: the browser route had no body rules (a Pydantic body reads everything first), the three phone routes were not in `JSON_ROUTES`, and a reset everywhere left no trace | the browser router mirrors the phone body rules (`Transfer-Encoding` and missing length 411, over 16 KiB 413, not JSON 415, reads at most 16 KiB + 1 bytes); the three routes join `JSON_ROUTES`; one audit line per applied reset | 20.8; SC 4.7, 6.12, 6.13, 11.3 |
| C-66 | consistency 1 (high): WP-02's pairing store saves capabilities field by field, so the two new flags would read back `false` and both features never start | CR-01-A2 adds `caps_delete_finished` and `caps_shared_progress` to `readCapabilities()`/`writeCapabilities()` (absent reads `false`) with a round-trip test; WP-02 M3 unchanged | 4.5, 3.4; WP 4.7 |
| C-67 | consistency 4: Storage's "Remove from this phone" confirmation had no owner for its words | WP-24 covers the copy (WP-24 has not started); section 10's F-05-AC11 row lists WP-16 and WP-24 | WP-16, WP-24, WP 10 |
| C-68 | consistency 5: Home's attention row had no owner for a refused deletion's inline action | WP-34 gains phase-complete rows for `:feature:home`, `ConnectionStatusCard` and `e2e/home/`, with a screen test; the action is the oldest refused deletion's "Try again" when no failed request needs attention (design 9.17) | 8.8; WP 3.2, WP-34 |
| C-69 | consistency 7: the wave-5 lane arithmetic did not hold | CR-01-A2 sized as its own 2-day item of WP-26; lanes rebalanced (WP-29 last on lane B, WP-30 on lane C); wave 5 restated as about 24 days (about 21 without WP-29), against about 15 without CR-01 | WP 1.3, 1.4, WP-26 |
| C-70 | consistency 8: `different_computer` was a user-facing refusal although the spec says the deletion waits; `unknown_route` was "any other 4xx" | `different_computer` keeps waiting (an event code; fix round 2 adds its reason "A different computer answered", spec D-62); `unknown_route` was proposed to wait with `COMPUTER_CANNOT`; **superseded by C-75**: product answered (spec D-62) that it is a refusal | 8.8, 19 |
| C-71 | consistency 9: two dialog changes of design 9.23 missing from WP-34; WP-16 did not cite the v1-core "On this phone" anatomy | both added to WP-34's Covers and Proof; WP-16 consumes design 9.23 and 9.26 (v1-core part) | WP-16, WP-34 |
| C-72 | consistency 10: the browser hand-off held the Web Lock across a request, and one rejected answer blocked every later sync | the lock only around each read-modify-write; a 10 s timeout; an answer the computer refuses with `400` is logged and dropped | `web-progress-sync.md` 5, 6.2, 8 |
| C-73 | consistency 11: the exported aggregate after a move to a second computer was a limit the spec did not state | no spec sentence needed: with `own_stat` (C-63) spec 7.13 rule 7 ("the answers given on it") holds as written | 7.8 |
| C-74 | consistency 14: "Q-1 default", "Q-5 default" after the owner confirmed them | "Q-1, confirmed (D-53)" and "Q-5, confirmed (D-53)"; S-24's "outside the repository" is product's (R-8) | SC 2.1, 4.8 |

Also fixed while there (security F-15, architecture part): `ui.py`'s generic tap applies the D-57 guard to the
destructive labels (21.7).

**Requests to the product track (fix round 1; spec text only), all answered (fix round 2, 25.7):**
- **R-5** (C-61): "Undo" lasts only while no attempt may have reached the computer. **Answered: spec D-65**, which
  also adds the line "It may have reached your computer already, so it can't be undone." (19; C-76).
- **R-6** (C-56): the brake on deletions (20 per phone per 10 minutes), the terminal line per deletion and the start
  banner's sentence. **Answered by the product track's fix round 1**: server behaviour, so no spec text is needed; the
  banner and README sentence stand as written (SC 2.1, 10.3). S-06's promise stays scoped to API 6 by SC 4.2.
- **R-7** (C-54, security F-15): **Answered in spec 11.5, 11.6, S-05, S-24 and S-25**: content signed and replays
  refused after a restart from v1-complete; the browser progress routes are open to any program on this computer, like
  its other routes.
- **R-8** (C-74): **Answered in spec S-24**: "outside version control (in the operator's configuration directory and
  the git-ignored workspace, never in a committed file)".
- R-1 to R-4 of 25.5 are answered by spec D-59, D-62, D-63 and D-64.

**Requests to the design track:** none that need a new drawing. For the record: a waiting deletion without "Undo"
(C-61) is 9.26's waiting state without its button, with D-65's line under the reason (design 9.19, 9.26); a
`different_computer` answer is one more reason line ("A different computer answered", D-61 step 1); design 9.17's
"Try again" in Home's attention row is what WP-34 now owns (C-68).

### 25.7 CR-01 review fixes, round 2 (revision 5, 2026-10-11)

Inputs: the product track's answers D-58 to D-65 (spec 15.7, written after fix round 1), design revision 3.2 (9.19,
9.26), and the four items the consistency and security reviews routed back to the architect: the architecture still
contradicted D-62 on `unknown_route`; 19 and WP-34 lacked the answered words; D-57 (3)'s first-share step was only in
the spec; nothing named the quoted-title function of design 9.26. The rule stays: product owns copy and behaviour, so
the architecture follows its answers and adds no word of its own; every change lands in CR-01-A2 (not yet applied) or
in WPs that have not started (WP-22, WP-24, WP-25, WP-34, WP-28 A, WP-06-3, WP-33). WP-01 milestone B, WP-02 M3 and
everything else in flight are untouched.

| Id | Finding | Disposition | Where |
|---|---|---|---|
| C-75 | consistency (blocking): the architecture made a computer without the route a wait (`DeletionWait.COMPUTER_CANNOT`, "Undo" kept, retried), the spec a refusal (D-62, 7.7 Retries, 7.9) | aligned with D-62, product's answer to R-2: `404 unknown_route` → `FAILED` with F-05 item 6's "not offered" sentence, "Try again" and "Keep the clip", no automatic retry, never "already gone"; a connected computer whose `health` no longer announces `delete_finished` gets the same refusal without a request (one state, one set of words); `COMPUTER_CANNOT` leaves `DeletionWait`, replaced by `DIFFERENT_COMPUTER`, the wait a `409 different_computer` gives ("A different computer answered", "Undo" kept, D-62). Why not ask product to reverse D-62: the answer is product's to give, it keeps the clip on both sides (fails safe), and "Try again" serves the one real cause (an older PhonoTrainer, updated later) | 8.8, 19; SC 6.11, 8.1; WP 4.7, WP-28 A, WP-34 |
| C-76 | consistency (blocking): 19 and WP-34 predated D-58, D-61, D-62 and D-65 ("proposed" sentences, no imported words, no state-to-reason mapping, no D-65 line) | 19 now holds the product's words only: D-58's imported forms (Requests title, waiting, sending, refused; block, button and confirmation in the Clip page's strings), D-61's order (`WaitingReasonCopy`, below), D-62's three refusal sentences, D-65's line; WP-34's Covers and Proof list each | 19; WP-34 |
| C-77 | D-61: one list of reasons for requests and deletions | `WaitingReasonCopy` in `:core:ui` `component/` (WP-22, v1-core, copy only; WP-22 has not started): the queue pause, connection and retry steps; `RequestRow` and `DeletionCopy` both call it, so the two kinds of row can never give two reasons for one state, which is D-61's purpose. WP-24 gets D-60's title and confirm label of "Forget this computer?" (copy only; WP-24 has not started) | 19; WP 3.2, WP-22, WP-24 |
| C-78 | consistency (blocking): D-57 (3), NFR-14 and X-AC10 add a QA step before the test phone first shares with the owner's computer; 21.7 had none, WP-33 said QA "never resets the shared history", WP-25 did not count QA's answers | 21.7 gains the counting (from WP-25 on), the check (`practice_totals()` against the QA log) and the logged outcome (clear when equal, else report what the first share sends and QA's part); WP-25 counts its practice answers per change; WP-06-3 adds `practice_totals()`; WP-33's Covers and Proof run the step. Equality is the test because a clearing cannot be undone and the owner's answers are the owner's | 21.7; WP-06-3, WP-25, WP-33, WP 10 |
| C-79 | security F-14's architecture half: design 9.26 says "the architecture names it and decides its module"; nothing did | `QuotedTitle.of(title)` in `:core:ui` `component/`, made by the integrator in CR-01-A2 with a Robolectric test of design 9.26's three fixtures; the shell's host passes the three messages' titles through it in the same commit; WP-34 uses it in `DeletionCopy` and every CR-01 confirmation and proves the right-to-left-override dialog against render 31, specimen 5; `androidx.core.text.BidiFormatter` (already an app library, one dependency line in `:core:ui`) and the platform's `android.icu.text.BreakIterator`, so no new library | 19, 20.8; WP 3.2, 4.7, WP-26, WP-34 |

**Requests to the product track (fix round 2):** one observation, no change needed now: D-61 names no reason for a
waiting request while no computer is paired (`ComputerStatus.NotConnected`), so `WaitingReasonCopy` shows no line
then; a deletion cannot exist in that state (6.3).

**Requests to the design track (fix round 2):** none needed. For alignment only: design 9.19's deletion table may name
the wait `DeletionWait.DIFFERENT_COMPUTER` (8.8) where it says "the deletion's last error code `different_computer`"
(both are true: the field is derived from that code), and design 9.26 may cite `QuotedTitle` (19) as the function's
name (its `androidx.core.text.BidiFormatter` is the one used).

---------------------------------------------------------------------------------------------------------------

## 26. Version 2: encrypted transport (v2, not in the v1 work plan; AD-26, spec 14.6, S-L3, D-56)

**Status.** A design ready to be planned after v1-complete. No v1 work package builds any of it, and no v1 decision
blocks it: v1's per-device key (5.3), the computer's identity proof and the single shared HTTP client are exactly what
it builds on. Spec 14.6 lists eight product requirements; 26.11 maps each to this design.

### 26.1 What changes and what does not

- **Changes**: phone traffic to a version-2 computer goes over **TLS 1.3**; the phone trusts only the computer's own
  key, **pinned at pairing**; pairing runs inside TLS and is protected by **SRP-6a** with the six-digit code, and a
  version-2 computer pairs in plain HTTP only with a legacy code the operator asks for; a phone paired under v1 moves
  over by itself with a proof made with its existing device key and then gets a new key inside TLS; once a phone has
  used TLS with its computer, neither side speaks plain HTTP with the other again.
- **Does not change**: the pairing screens and the code in the computer's terminal (no certificate, fingerprint or
  setting for anyone to handle), discovery (one additive field), PT1 signatures and nonces on every phone route,
  identity proofs, approved networks, the route allowlist and Host rules, and the browser client on the computer's
  loopback HTTP (S-21).

### 26.2 The computer's identity

- When phone access is turned on (or at the first start of a version-2 server that has phone access on), the computer
  creates an EC P-256 key and a self-signed X.509 certificate (subject "PhonoTrainer computer", validity 100 years:
  dates are not part of the trust) in the configuration directory: `tls_key.pem` (`0600`) and `tls_cert.pem`, under
  the directory's lock and permission checks (server-contract 3.1). Tool: the `cryptography` package (Apache-2.0 or
  BSD-3-Clause, GPL-compatible; recorded in `THIRD-PARTY-NOTICES.md` section 3 when version 2 adds it); the system
  `openssl` command is the documented fallback if that dependency is refused.
- Its **pin** is the SHA-256 of the certificate's DER SubjectPublicKeyInfo, as lower-case hex (computed the same way by
  `cryptography`'s `public_bytes(DER, SubjectPublicKeyInfo)` and Android's `Certificate.publicKey.encoded`).
- The key never leaves the directory and is never printed. `phonotrainer phone status` shows the pin's first 8
  characters so the operator can tell identities apart; nothing else does.

### 26.3 Transport

- A second uvicorn server in the same process serves the same app on `tls_port` (default 8443, in `server.json`, in
  the range the firewall allows), on the active approved addresses only (server-contract 2.1), with the key and
  certificate above and `minimum_version = TLSv1_3` (set on the SSL context uvicorn builds, after `Config.load()` and
  before `serve()`). Port 8000 stays plain HTTP: loopback for the browser, and LAN phone routes for apps that have not
  moved yet (26.7).
- The discovery reply gains `"tls_port": 8443` (an additive field; v1 phones ignore unknown fields,
  server-contract 5.3). `hello` answers on both listeners and gains `"tls_port"`.
- On the phone, **two clients** are built from the one base client of 5.1 (the same per-socket network binding, no-DNS
  rule, dispatcher and timeouts), each with its own `SSLContext` for `TLSv1.3`, its own `SSLSocketFactory` and its own
  `ConnectionPool`, connection specs `RESTRICTED_TLS` (TLS 1.3 only) plus `CLEARTEXT` (only for computers that never
  offered TLS, 26.7):
  - the **pinned client** (`@ComputerHttp`, the client of 5.1): every request to the paired computer goes through it
    (Retrofit, transfers, the job channel) and so does **every check of the paired computer at any address**, a
    discovered, typed or changed address included. Its `PinnedPeerTrustManager.checkServerTrusted` computes the leaf's
    pin and accepts it only if it equals the pin stored for the paired computer (`tls_pin` in the pairing store, not
    backed up, next to the credential, 4.5); certificate dates and chains are ignored, because the pin is the identity.
    It has no other mode.
  - the **candidate client** (`@CandidateHttp`), used only while this phone holds **no pin** for the computer it talks
    to: the `hello` of a computer found or typed before pairing, and the SRP exchange of 26.4. Its trust manager accepts
    any leaf and records its pin for 26.4. The signing interceptor is not installed on it, so it never carries a PT1
    signature, and nothing uses it for a computer once a pin is stored.
  OkHttp keys its pool by `Address`, which includes the socket factory, and the two clients share neither factory nor
  pool, so a connection opened in candidate mode can never carry a pinned, signed request (review fix C-57). An
  instrumented test in `:core:network` proves it: a candidate connection to a `MockWebServer` serving a held
  certificate stays open, then a signed request to the same address through the pinned client (with another pin
  stored) opens a new connection and fails its handshake before any request byte.
  - The `HostnameVerifier` accepts only the current or candidate address (`AllowedPeers`), never a name: the computer's
    address changes with DHCP and its identity is the key.
  - `CleartextGuard` (5.2) gains one rule: for a computer whose pairing has `tls_required`, every `http` or `ws`
    request fails before a byte is sent; `https` and `wss` follow the same address rules as before.
  - The job channel becomes `wss://<address>:<tls_port>/api/phone/events`; artifact downloads keep Range and If-Range
    over HTTPS; media playback and image loading still read local files only (D-12), so nothing else uses the network.
  - `network_security_config.xml` keeps its cleartext base configuration while version-1 computers exist (an address
    known only at runtime cannot be named there, and spec 14.6 requirement 6 keeps a version-1 computer working); the
    guard above is what confines cleartext, as in v1, and for a computer with `tls_required` it allows none, which is
    how spec 14.6 requirement 8 ("no longer needs the cleartext allowance for the computer") is met per computer. A
    later version that drops version-1 computers sets `cleartextTrafficPermitted="false"` in the base configuration.

### 26.4 Pairing over TLS, protected by SRP-6a

A pin delivered with the pairing code's HMAC is not enough: a six-digit code has 10^6 values, so a machine that
terminates the phone's TLS during pairing receives `HMAC(code, its own pin)` and finds the code offline in well under
a second, then pairs with the real computer itself. A PAKE gives such an attacker one guess per run and a recording
nothing. SRP-6a (RFC 5054) is chosen because it needs only modular exponentiation and SHA-256: Python integers and
`hashlib` on the computer, `java.math.BigInteger` and `MessageDigest` on the phone, with the RFC's test vectors; no
PAKE library is needed on either side (SPAKE2 and CPace need elliptic-curve point arithmetic that neither standard
library exposes). Because the exchange is written by hand, the safety checks of RFC 5054 section 2.5.4 are part of the
design, not left to a library (review fix C-53).

Parameters: the RFC 5054 3072-bit group (`N`, `g = 5`), `H` = SHA-256, `k = H(N | PAD(g))`, identity
`I = "phonotrainer"`, password `P` = the six digits. Secret exponents `a` and `b` are fresh for every exchange, 256
random bits each (`SecureRandom` on the phone, `secrets.randbits(256)` on the computer), and never reused.

1. `phonotrainer phone pair` creates the code as in v1 (server-contract 2.3) and stores in `pairing.json` a 16-byte
   salt `s` and the verifier `v = g^x mod N`, `x = H(s | H(I ":" P))`, and **no** v1 `code_sha256`: a version-2
   computer pairs over plain HTTP only with a legacy code (26.6).
2. The phone opens TLS to `tls_port` through the **candidate client** (it has no pin yet) and records the server's pin
   `pin_seen`. `POST /api/phone/v2/pair/start {device_name, app_version, app_version_name, A}` with `A = g^a mod N`.
   The computer refuses an `A` with `A mod N = 0` (0, `N`, `2N`, …), which would force `S = 0` and so a key `K = H(0)`
   known without the code, with `403 code_invalid`, counted as a wrong attempt; an `A` longer than 1,024 hex digits is
   `400 invalid_request`. Otherwise it answers `{pair_id, salt, B, server_id, name}` with `B = (k·v + g^b) mod N`,
   computes `u = H(PAD(A) | PAD(B))` at once and draws a new `b` if `u = 0`. `pair_id` (16 random bytes, hex) lives 60 s
   and allows one `finish`.
3. The phone refuses a `B` with `B mod N = 0` and a `u = 0`: it sends nothing more and shows the `code_invalid` words
   (spec F-02). Both compute the shared `S` and `K = H(S)`. The phone sends
   `POST /api/phone/v2/pair/finish {pair_id, m1, previous_device_id, previous_token_proof}` with
   `m1 = HMAC-SHA256(K, "pt-pair-v2|client|" + hex(A) + "|" + hex(B) + "|" + server_id + "|" + pin_seen)`.
4. The computer deletes `pair_id` at its first `finish`, whatever the outcome, so an unknown, expired or already used
   `pair_id` and a wrong `m1` are each a wrong attempt (five lock the code, 2.3) answered `403 code_invalid`. It
   recomputes `m1` with **its own** pin and compares with `hmac.compare_digest`. A mismatch means a wrong code or a
   machine in the middle (whose certificate the phone saw). A match creates or re-activates the device exactly as v1's
   pairing does (server-contract 6.2, "Connect again" included), records `tls_since` for it (its credential never
   travels outside TLS), and answers the v1 grant plus `m2 = HMAC-SHA256(K, "pt-pair-v2|server|" + hex(A) + "|" +
   hex(B) + "|" + server_id + "|" + pin + "|" + device_id)`.
5. The phone checks `m2` with `MessageDigest.isEqual` (constant time) before storing anything; then it stores the
   grant, `tls_port`, `tls_pin = pin_seen` and `tls_required = true` in one `PairingStore` write, and continues as in
   v1 (6.3), through the pinned client: a `hello` with a challenge (26.9), then `Connected`.

The token travels once, inside TLS, only after `m1` proved that both ends know the code and saw the same certificate.
Rate limits, the five-attempt lock and the terminal's "Paired: … (from …)" line stay as in v1.

### 26.5 Moving phones paired under v1 (spec 14.6 requirement 5)

A version-2 app paired with a computer under v1 probes as in v1 (plain `hello` with its identity proof, 5.3). When the
computer's `hello` names a `tls_port`:
1. **Pin proof.** The phone sends the signed `GET /api/phone/tls?challenge=<64 hex>` over HTTP; the computer answers
   `{tls_port, pin, proof}` with `proof = HMAC-SHA256(K, "pt-tls-v1|" + server_id + "|" + device_id + "|" + pin + "|" +
   tls_port + "|" + challenge)` and the device's key `K`. The pin is therefore authenticated by the trust established
   at v1 pairing, without a new code. With a valid proof the phone stores `tls_port` and `tls_pin`. With a missing or
   wrong proof, or a `404` (a version-1 computer whose `hello` someone gave a `tls_port`), the phone stays on v1
   exactly, Connection details shows the "Not encrypted" line (26.7), and it asks again at its next session: nothing
   is deleted and nothing asks the learner to connect again, so a forged or stripped field can never push a phone into a
   new pairing (review fix C-58).
2. **First TLS session.** Through the pinned client, a `hello` with a challenge and the version-2 proof (26.9).
3. **New key** (review fix C-59). TLS here does not authenticate the client, and the phone's key `K` crossed the
   Wi-Fi in plain HTTP once (the v1 pairing answer), so it must not outlive the move. The phone makes a new token `T2`
   (the v1 format, 32 random bytes), stores it encrypted as `pending_credential` in the pairing store, and sends
   `POST /api/phone/v2/rekey {"key_sha256": "<hex SHA-256 of T2>"}`, signed with `K`, through the pinned client. The
   computer accepts this route only on its TLS listener (on port 8000 it answers `403 tls_required`), replaces the
   device's `key_sha256` (the old key stops working on both listeners at once), keeps the device id and everything
   attached to it, records `tls_since`, and answers `200 {"device_id": …}`. The new key is chosen by the phone and
   sent only inside the pinned TLS session, so no secret is ever returned and a lost answer costs nothing.
4. **Settling.** Whatever the answer (`200`, an error, or none), the phone checks a `hello` challenge through the
   pinned client with `T2`'s key: a valid proof commits the move (credential = `T2`, `tls_required = true`,
   `pending_credential` removed, in one write). Otherwise, if the old key still works, step 3 is retried at the next
   session; if neither works (someone who held the old key rekeyed first), the status is "Connect again" (v1's words),
   nothing is deleted, and pairing again with a code runs SRP and keeps the device id and everything else (6.3).

### 26.6 No downgrade

- Phone: `tls_required` (26.4, 26.5) makes `CleartextGuard` refuse plain HTTP to that computer for good. If the computer
  stops offering TLS (an older PhonoTrainer reinstalled, or the port blocked), nothing is sent, nothing is deleted, and
  the status reads "Update PhonoTrainer on your computer" (spec 7.5, the `ComputerTooOld` words).
- Computer: `devices.json` records `tls_since` for a device at its SRP pairing or its rekey; from then on that
  device's requests over plain HTTP answer `403 {"code": "tls_required"}` (except `hello`), and its old key is gone
  anyway.
- **No plain pairing by default** (review fix C-58). A version-2 computer serves v1's `POST /api/phone/pair` only for a
  code made with `phonotrainer phone pair --legacy`, which prints "This code pairs over an unencrypted connection. Use
  it only for a phone whose PhonoTrainer app is not updated yet." and stores only v1's `code_sha256` (no SRP verifier,
  so that code cannot be used over TLS either). For any other code v1's route finds no code and answers
  `403 code_invalid`, as v1 does when no code exists (server-contract 2.3). An attacker who strips `tls_port` from
  discovery or `hello` therefore gets a version-2 app a "wrong code", never a plain pairing, and the token never
  travels in clear.
- Residuals:
  - Before a phone moves, an active attacker who strips `tls_port`, or makes the pin proof fail, keeps that phone on
    v1's protection for as long as it stays in the middle; Connection details says "Not encrypted" meanwhile. Once the
    pin is stored, it cannot be undone.
  - A listener who recorded a phone's **v1** pairing holds its key until that phone moves; the rekey (26.5) replaces
    the key, so the recording is worthless afterwards. Someone who holds the key **and** is active in the middle at
    the moment of the move can either rekey first (the phone then shows "Connect again", which is visible) or give the
    phone its own pin with a valid proof (it can compute one with the key) and stay in the middle. Pairing again with a
    new code rules both out (SRP, 26.4); the original pairing's "Paired: … (from …)" line is the v1 detection that
    remains. This is spec 14.6 requirement 5: the move is exactly as trustworthy as the v1 pairing it relies on.
  - A legacy code pairs one phone exactly as v1 does, with v1's risk, and only when the operator asks for it by name.

### 26.7 Mixed versions (spec 14.6 requirement 6)

| App | Computer | Result |
|---|---|---|
| v2 | v1 (no `tls_port`) | v1 exactly (plain HTTP confined by `CleartextGuard`); Connection details: "Not encrypted: update PhonoTrainer on your computer to encrypt this connection." The same when a `tls_port` is announced but the pin proof is missing or wrong (26.5) |
| v2 | v2 | TLS (paired with SRP, or moved and given a new key by 26.5); Connection details: "Encrypted connection" (icon + words) |
| v1 | v2 | a phone already paired: v1 exactly on port 8000 until its app is updated and moves (26.5); a new pairing of a v1 app needs `phonotrainer phone pair --legacy` (26.6) |
| any | any, browser | unchanged: loopback HTTP on port 8000 (S-21) |

The owner's v1 builds are sideloaded and updated together with the computer, so a legacy code is the exception for an
app that cannot be updated at that moment; every phone paired before the update keeps working without one.

### 26.8 Identity change, rotation and revocation (spec 14.6 requirement 4)

- `phonotrainer phone new-identity` replaces the key and certificate and prints "This computer has a new identity.
  Phones must connect again with a new code: phonotrainer phone pair"; the next start repeats the line once. A lost
  configuration directory is a new server id, which phones already treat as "A different computer answered" (v1).
- A phone whose pinned handshake fails sends nothing, deletes nothing, pauses the queue and keeps every clip, request
  and saved item: a new status (`ComputerStatus.IdentityChanged(name)` and `ProbeOutcome.IdentityChanged`, version-2
  types) with the spec's words: "Your computer's identity changed. If PhonoTrainer was reinstalled or reset on your
  computer, connect again with a new code. If not, another device may be answering at its address." and "Connect
  again". Connecting again with the same server id keeps everything (re-activation with `previous_token_proof`, 6.3)
  and stores the new pin. The phone never accepts a new pin by itself, and a rotation is never "signed over" by the old
  key, because the requirement is that the learner decides.
- The paired computer is checked through the pinned client at every address, a new or typed one included (26.3), so a
  machine that relays `hello` from a new address fails at its handshake and never reaches the identity proof.
- Device revocation is v1's (`phone revoke`). A suspected key leak on the computer: `new-identity` and `revoke --all`.

### 26.9 What stays signed with PT1, and why

Every phone route keeps the v1 `Authorization: PT1 …` signature, nonce and clock window (server-contract 4.3), and
`hello` keeps its identity proof. Reasons: the server keeps one authentication path for both transports while v1 apps
exist; per-device revocation and "Connect again" keep working unchanged; and a leaked TLS key alone does not let anyone
act as a phone (the device keys are separate). Inside TLS the bodies are protected by TLS as well as by v1-complete's
`body_sha256` (server-contract 4.3).

Over TLS the identity proof binds the pin (review fix C-57): `hello` on the TLS listener answers
`proof = HMAC-SHA256(K, "pt-hello-v2|" + server_id + "|" + device_id + "|" + challenge + "|" + pin)` with the
computer's own pin, and the phone checks it with its stored `tls_pin`, so a `hello` relayed from another TLS session
fails even if a pinned handshake were ever bypassed. Over plain HTTP (a phone that has not moved) the v1 proof is
unchanged.

### 26.10 Threat model: what version 2 changes

| Threat | v1 | v2 |
|---|---|---|
| A listener on the Wi-Fi reads clips, transcripts, links, practice answers, the Saved list | yes | no (TLS 1.3, forward secrecy) |
| Recording the pairing captures the token | yes, once (spec 11.6) | no for an SRP pairing: the exchange is inside TLS and SRP reveals nothing about the code; a v1 pairing recorded earlier is usable only until that phone moves, when the rekey replaces its key (26.5); a legacy code is v1's (26.6) |
| An active attacker after pairing reads or alters traffic | yes; it cannot change a signed request or its body (CR-01's `body_sha256`), but it can read, block or delay | no: the pinned key authenticates the computer and TLS protects every byte |
| An active attacker during pairing | can relay a code typed into a rogue responder; seen only through the terminal's "Paired: … (from …)" line | one online guess per run (one in a million), five wrong attempts lock the code; stripping `tls_port` gets a "wrong code", never a plain pairing (26.6); the terminal line stays |
| A rogue responder after pairing | no proof, so `DifferentComputer` | the pinned handshake fails before any request byte, at any address (26.3, 26.8) |
| Downgrade to plain HTTP | — | only before a phone moves, and only to v1's protection, with "Not encrypted" shown; never for a pairing (26.6) |
| A v1 pairing that was recorded, then an active attacker at the moment a phone moves to TLS (26.5) | the listener already holds that phone's token | can rekey first (the phone shows "Connect again") or pin its own key once; closed by pairing again with a code (SRP), 26.6; a recording alone is worthless after the move |
| An active attacker adds an answer, a Saved change or a reset in a body (CR-01) | no: the body's hash and a phone's reset are in the signed request target (20.8) | no |
| A local user who reads the configuration directory | can act as a paired phone (`devices.json`) | also can act as the computer (`tls_key.pem`); the same `0600` / `0700` protection |
| DNS rebinding, LAN route allowlist, approved networks | unchanged | unchanged |

### 26.11 Spec 14.6 requirements → design

| Requirement | Met by |
|---|---|
| 1 Encrypted and authenticated, identity pinned, no CA, domain, service or account | TLS 1.3 to a self-generated key; SPKI pin stored at pairing (26.2 to 26.4); discovery unchanged but for `tls_port` |
| 2 No extra steps beyond pairing | the same code and screens; nothing to accept or install; no new permission (26.4) |
| 3 Pairing protected | SRP-6a with RFC 5054's checks inside TLS, the pin in the key confirmation; no plain pairing on a version-2 computer without a legacy code the operator asks for; a recording reveals nothing; one guess per run (26.4, 26.6) |
| 4 Identity change never accepted by itself | `IdentityChanged` state with the spec's words; nothing sent or deleted; "Connect again" keeps everything for the same server id (26.8) |
| 5 v1-paired phones move over by themselves; no fallback once used | the pin proof with the v1 device key, then a new key inside TLS (26.5); `tls_required` on both sides (26.6); a missing proof keeps v1 with the "Not encrypted" line; "Connect again" only when both the old and the new key fail |
| 6 Mixed versions keep working | 26.7 (paired v1 phones keep working; a new pairing of an app that is not updated takes a legacy code) |
| 7 Visible but quiet | "Encrypted connection" and the "Not encrypted" line in Connection details, the identity-change state; nothing else |
| 8 No cost to the study loop | the same base client; media and images read local files; TLS 1.3 with hardware AES on the phone; NFR-02 and SC-08 re-measured in version 2's QA; no cleartext to a computer with `tls_required` (26.3) |

### 26.12 What implementing it would take (for a future plan; not scheduled)

- **Server** (Python): identity files and `new-identity`; the TLS listener; the SRP verifier in `phone pair` and the
  `--legacy` option; the two pairing routes, `GET /api/phone/tls` and `POST /api/phone/v2/rekey`; the version-2
  `hello` proof; `tls_since` and `403 tls_required`; `tls_port` in discovery and `hello`; pytest with the RFC 5054
  appendix B vectors (its 1024-bit group and SHA-1, run through the same code with those parameters) and fixed vectors
  for this design's 3072-bit group and SHA-256 in `tests/phone_fixtures.py`, shared with the phone's tests; `A` in
  {0, `N`, `2N`} → `403 code_invalid` counted as a wrong attempt; a second `finish` with the same `pair_id` → `403`
  counted; a v1 pairing attempt without a legacy code → `code_invalid`; a refused TLS 1.2 handshake; the in-the-middle
  pairing case; the pin proof; the rekey (the old key refused on both listeners, the device id kept, the route refused
  on port 8000); the downgrade refusal (server-contract 13).
- **Phone**: `:core:network` (the pinned and the candidate client, `PinnedPeerTrustManager`, the pin recorder, the SRP
  client with its `B mod N` and `u` checks and constant-time `m2` check, the guard rule, the instrumented pool test of
  26.3), `:core:connectivity` (pairing over TLS, the move and rekey of 26.5, `IdentityChanged`), `:core:datastore`
  (pairing store keys `tls_port`, `tls_pin`, `tls_required` and the encrypted `pending_credential`; not backed up),
  Connection details copy (spec 14.6 item 7) with the icons design 9.28 names (lock and lock-open), the fake computer
  over HTTPS (`mockwebserver3` with `okhttp-tls`'s `HeldCertificate`, Apache-2.0, a test dependency), the host-side
  fake computer with its TLS listener, and the spec's version-2 ACs on the emulator.
- **No runtime dependency on the phone** (Conscrypt's TLS 1.3, `BigInteger`, `MessageDigest`); on the computer,
  `cryptography` for the certificate.
