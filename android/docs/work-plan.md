# PhonoTrainer for Android: work plan

Status: v1 work plan, revision 4, 2026-10-11 (revisions 1 to 3: 2026-10-10). Owner: software architect.

**Revision 4 notes (change request CR-01, 2026-10-11; the plan's change log for it).** The owner answered Q-1 to Q-7
on 2026-10-10 while wave 1 was in flight. State on 2026-10-11 (integration log and progress files): B1 published;
WP-04, WP-05a-1, WP-06-1, WP-06-2 and WP-01 milestone A merged into `android-client`; WP-01 milestone B (the rest of
the v1-core server, API 6) finished on its branch and in review, not merged; WP-02 M3, WP-03 and WP-19's first week
running; no wave-2 WP branched yet. Spec revision 4
(D-53 to D-57) adds two v1-complete features (deleting a finished analysis from the phone, S-25; shared practice
accuracy and Saved list, S-24) and a version-2 design (architecture 26). The plan changes so that **nothing in flight
is disturbed and v1-core is unchanged**:
1. **No v1-core WP gains behaviour.** Two tiny v1-core items: the integrator (WP-19) applies change request
   **CR-01-A1** (Room `learning_meta` with `learning_id`, folded into version 1 before any install, 4.7) and publishes
   it in the next baseline; WP-16 words the existing "Remove from this phone?" confirmation (spec F-05-AC11, copy only).
2. **Server: WP-27 gains milestone A** (S-25 and S-24, API 7) before its original scope (now milestone B). It may start
   now in the Python lane, on its own branch, and merges after the v1-core gate like every v1-complete server change
   (1.4). WP-01 and its milestone B are not reopened (architecture 25.5 C-37 says why not "WP-01-C").
3. **Android v1-complete**: **WP-28 gains milestone A** (the deletion operation and the shared-progress engine in the
   core modules) before its original scope (renditions and resumable uploads, now milestone B); the new **WP-34**
   builds the screens (Clip page block, Requests rows, Library and shelf words, Practice and Saved lines, the
   confirmations); **WP-05a milestone 3** and **WP-06 milestone 3** extend the two fake computers; **CR-01-A2** (the
   v1-complete amendment of the frozen contract and Room version 2) is applied by the integrator of wave 5 (WP-26)
   before any wave-5 Android WP branches (4.7).
4. **Order of v1-complete**: CR-01 first, deletion before shared progress (spec 14.2 step 7), then the rest as before
   (1.4); the v1-complete gate gains the new ACs, QT-15 to QT-20 and D-57 (WP-33). The v1-core gate is unchanged but
   for F-05-AC11 (WP-16's copy) and CR-01-A1's test.
5. New and changed rows: ownership (3.2), dependencies (9.1), risks (9.2), traceability (10); WP-25 refers to the owner
   in neutral words.
6. **Who does what next**: the orchestrator commits these documents on `wp/CR-01`; the integrator merges them into
   `android-client` like any docs change and applies CR-01-A1 in its next round, publishing it with the next baseline;
   WP-27 milestone A may be assigned as soon as WP-01 milestone B is merged; nothing else starts before the v1-core
   gate. The browser's adoption is outside this plan (`web-progress-sync.md`) and gates nothing.
7. **Merge note for the integrator**: WP-01 milestone B's hand-off carries six additive change requests to
   `server-contract.md` (sections 3.2, 5.1, 6.1, 6.6, 6.7, 9, 10.1, 10.2, 11.2), and revision 4 of that document adds
   lines in some of the same sections (3.2, 6.1, 6.7, 10.1, 10.2). Both are additive: whichever lands second keeps
   both texts.

**Revision 4 review fixes (2026-10-11, fix round 1; architecture 25.6 lists each item and its reason).** Nothing in
flight changes: WP-01 milestone B and WP-02 M3 are untouched, and every change lands in work that has not started.
1. **CR-01-A2 grows and becomes its own item** (2.2, 4.7; WP-26 L 7 d, of which A2 is 2 d): two pairing-store keys for
   the capability flags in `:core:datastore` (WP-02's store saves capabilities field by field),
   `SyncController.exclusive` and its call in `forget()`, `ComputerDeletion.actions`, a new `DeletionWait` member
   (`COMPUTER_CANNOT` in fix round 1, `DIFFERENT_COMPUTER` since round 2), `own_stat` and `request_op.maybe_sent` in
   Room version 2.
2. **WP-27 milestone A** (7 d): `body_sha256` on the three routes, `not_before` in the authenticator, refusals per item
   instead of `store_full`, bounded Saved positions, stores that fail closed, the deletion rate limit, the visible
   audit line, the banner line, the browser routes' body rules.
3. **WP-28 milestone A** (8 d): `maybe_sent` and answers matched by code, `exclusive` with QT-18's in-flight variant,
   `own_stat`, refused items and `400` pages marked handled, `body_sha256` in `:core:network`.
4. **WP-34** (7 d): Home's attention action for a refused deletion (new phase-complete rows), the reach strip and the
   place mark of design 9.23, no "Undo" when `actions` lacks it (round 2 replaced round 1's `COMPUTER_CANNOT` words
   by spec D-62's refusal).
5. **WP-24** covers the words of Storage's "Remove from this phone?" (F-05-AC11), WP-16 consumes design 9.23 and 9.26's
   v1-core part, WP-05a-3 and WP-06-3 follow the server, and the wave-5 lanes are restated (1.4: about 24 days).

**Revision 4 review fixes, round 2 (2026-10-11; architecture 25.7).** The product track answered the architecture's
requests (spec D-58 to D-65); the plan follows. Nothing in flight changes: every item lands in CR-01-A2 (not applied
yet) or in a WP that has not started.
1. **A computer without the route refuses a deletion** (spec D-62): `DeletionWait.COMPUTER_CANNOT` becomes
   `DIFFERENT_COMPUTER` in CR-01-A2 (4.7); WP-28 A maps `404 unknown_route` (and a `health` without `delete_finished`)
   to a refusal and `409 different_computer` to a wait with its reason; WP-05a-3's knobs already cover both.
2. **WP-34 covers the answered words**: D-58's imported forms, D-59, D-61's reason order, D-62's refusal sentences,
   D-65's line, and the quoted title in every CR-01 `{title}` with the right-to-left-override screenshot test.
3. **`QuotedTitle`** (architecture 19; design 9.26) is made by the integrator in CR-01-A2 with its unit test, and the
   shell's host passes the three messages' titles through it (2.2, 3.2, 4.7, WP-26).
4. **v1-core, copy only, in WPs that have not started**: WP-22 owns `WaitingReasonCopy`, D-61's one list of waiting
   reasons, which WP-34's `DeletionCopy` reuses; WP-24 words "Forget this computer?" (D-60); WP-25 counts the practice
   answers QA gives on the test phone (D-57 (3)).
5. **The first share of the test phone** (spec D-57 (3); architecture 21.7): WP-06-3 adds `ui.py`'s
   `practice_totals()` and logs the simulated devices' answers and resets; WP-33 runs the check before the test phone
   can first share with the owner's computer, clears a history that is all QA's and logs it, or reports what the first
   share will send.

**Revision 3 notes (pre-implementation closure).** Spec revision 3 (D-37 to D-52) and the cross-track consistency
review are mirrored here; architecture 25.4 lists each item. In short: the change counts carry two units (A.3,
WP-10, WP-15, WP-17, WP-20); "Try audio only" has its transitions and `Request.actions` is the one authority
(WP-09, WP-22, WP-23); `RequestFailureCopy` takes a `FailureContext` and covers `live_stream`, `too_long` and the
phone-side copy codes (4.5, WP-22); new fields `WaitingInfo.needsAppOpen`, `RequestError.neededBytes`,
`ConnectionDiagnostics.phoneAddress`, `SearchState.Found.conflict`, `StudySetItem.Short.done`,
`FeedPlaybackState.trackFallback` (4.2, Appendix A); `ProbeOutcomeCopy` joins WP-15's shared component (3.2, 4.5);
the licence check covers fonts and icons through WP-03's `bundled.json` (WP-03, WP-19, WP-24); the fake computer gains
`impersonate(proof)` and `lanForbidden()` (WP-05a); WP-03's inputs are listed file by file against the staging export;
section 10 traces every new acceptance criterion.

**Revision 2 notes.** The delivery, Android-platform and security reviews of revision 1 changed how the plan runs;
architecture section 25 lists every finding and its disposition. In short:
1. **Integration is git-based** (2.1): a branch or worktree per WP from its baseline commit, three-way merges by the
   integrator; nothing is integrated by copying files.
2. **A dedicated integrator from B1** (WP-19); WP-02 only builds the foundation (delivery review D-05).
3. **Capacity is real** (1.2): three builder lanes plus the integrator (a fourth lane when memory allows), one GMD
   run at a time; the v1-core critical path grows from the 32 days revision 1 claimed to about 67 (about 56 with
   four lanes).
4. **Shapes before the freeze** (4, Appendix A): every frozen type has its fields; WP-12 and WP-04 start on day 0 and
   hand their shapes by day 4; WP-05 is split into WP-05a (fake computer, day 0) and WP-05b (network client).
5. **Test seams** (2.4): one global `@TestInstallIn` set with real repositories; `FakeDomainModule` is gone;
   `:core:testing-jvm` holds the fixtures and the fake computer; three core modules get their own managed device.
6. **New proofs** in WP-01, WP-06, WP-09, WP-11, WP-19, WP-23 and WP-25 for the reviews' fixes (identity proofs,
   signed requests, confined downloads, expedited intake, background upload sizing, per-tab state, task affinity,
   the host-side fake computer, the black-box R8 smoke).

Inputs: `android/docs/product-spec.md` (behaviour, features, ACs, S-nn, NFRs, tiers, build order 14.2),
`android/docs/architecture.md` (modules, decisions, the definition of done in section 23), `android/docs/server-contract.md`
(the HTTP/UDP contract and the pytest plan), and the design track's `android/docs/design.md`, its mockups
`android/docs/design/*.png` and `android/design-src/export/`. The export and the tokens are consumed by WP-03 only,
through the API fixed in architecture 17; every feature WP builds its screens and states to design.md sections 9 and
10 (and the mockups named there) on top of WP-03's primitives, and section 12.2 says which composable each WP owns.

This plan says who builds what, in which order, in which files, against which interfaces, and how each piece is proven.
It changes no behaviour (the spec does) and no design decision (the architecture does).

Conventions
- `WP-nn` is a work package: one engineer (or agent) in one copy of the project. Ids are stable; the architecture
  cites them with these meanings. WP-05 became WP-05a and WP-05b in revision 2. Revision 4 adds WP-34 and milestones
  named `WP-nn A`/`B` or `WP-nn-3` (a WP's milestone keeps its WP's ownership rows of the matching phase).
- Sizes are in engineer-days of focused work, including tests: **S** ≤ 2, **M** 3–5, **L** 6–10, **XL** 11–15. They
  are planning estimates, not commitments.
- **Baseline** `B<n>`: the integrated commit a WP starts from. `B0` is WP-02's M1 (the toolchain proof, day 2), `B1`
  the M2 contract freeze (day 6); the integrator publishes `B2`, `B3`… after merges.
- **Integrator**: WP-02 bootstraps until B1; **WP-19 from B1 to the v1-core gate**; WP-26 from the gate to the
  v1-complete gate (architecture 2.7). Only the integrator changes frozen files.
- **Lane**: one copy of the project that builds Android code, running one WP at a time (1.2).
- Spec ids (`F-nn`, `S-nn`, `NFR-nn`, `QT-n`, `SH-ACn`, `X-ACn`, `SC-nn`, `A-nn`) refer to the product spec; `AD-nn`
  and section numbers without a document name refer to the architecture.

---------------------------------------------------------------------------------------------------------------

## 1. The plan at a glance

### 1.1 Waves

| Wave | Starts when | Work packages | Ends with |
|---|---|---|---|
| 1 Foundation | day 0 | **WP-01** server v1-core (Python) · **WP-02** foundation: skeleton, contract, shell · **WP-04** didactic content (extractor and schema first) · **WP-05a** fake computer (JVM) · **WP-06** tooling, CI, device-QA kit, host-side fake computer · **WP-12** analysis core (shapes first) · **WP-03** design system (when the design track delivers) | B1 at day 6; WP-02 M3; WP-01, 04, 05a, 06, 12 merged |
| 2 Cores and features | B1 (day 6), in lanes (1.2) | cores **WP-05b** network client · **WP-07** connectivity · **WP-08** sync · **WP-09** device queue · **WP-10** corpus · **WP-11** player · **WP-14** study; features **WP-13** onboarding and connect · **WP-15** home · **WP-16** library and clip page · **WP-17** watch · **WP-18** lesson · **WP-20** learn · **WP-21** practice and saved · **WP-22** requests and add · **WP-23** share target · **WP-24** settings | every wave-2 WP merged |
| 1–3 Integration (continuous) | B1 | **WP-19** integrator and integration v1-core: merges, change requests, the global test seams, the black-box R8 smoke, then the cross-area flows and measurements | v1-core release candidate |
| 4 QA v1-core | WP-19 done; the owner's phone attached | **WP-25** QA: emulator, test phone, link gate | **v1-core gate** (spec 14.3) |
| 5 v1-complete | the v1-core gate (WP-27 may start earlier, 1.4); CR-01-A2 applied by WP-26 first (4.7) | **WP-27** server v1-complete (A: deletion from phones and shared progress, CR-01; B: renditions, resumable uploads, record yourself) · **WP-05a-3** fake computer for CR-01 · **WP-06-3** QA kit for CR-01 · **WP-28** sync, queue and study v1-complete (A: deletion operation and shared-progress engine, CR-01; B: renditions and resumable uploads) · **WP-34** deletion and shared-progress screens (CR-01) · **WP-29** articulator · **WP-30** record yourself · **WP-31** phrase details and transcript · **WP-32** insights | every wave-5 WP merged |
| 6 Integration and QA v1-complete | wave 5 merged | **WP-26** integration v1-complete · **WP-33** QA v1-complete | **v1-complete gate** |

### 1.2 Capacity, lanes and the critical path (v1-core)

**Capacity** (delivery review D-04). The development computer has 16 cores and 29 GB of RAM. One copy of the project
building and testing Android code uses about 6.5 GB (Gradle daemon `-Xmx3g`, Kotlin daemon `-Xmx1536m`, two
Robolectric forks of 1 GB; architecture 2.4), and every copy shares the one managed AVD. So:
- at most **four Android copies** build at once, **the integrator's included**: three builder lanes plus WP-19;
- a fourth builder lane is allowed only when the orchestrator has checked that a full test run in every running copy
  leaves at least 8 GB available (`free -g`);
- **one GMD run at a time**, through `scripts/device-qa/gmd_lock.py`, and a WP's GMD run is filtered to its own
  area; only the integrator runs the whole suite (architecture 21.5, 23);
- Python and Node work (WP-01, WP-06, WP-04's extractor) does not take a lane; WP-04's two-day Kotlin step may run as
  a short extra copy (JVM tests only, no GMD).

**Lanes.** Each lane runs one WP at a time, in this order; a WP starts when the previous one in its lane is handed off.
Days are working days from day 0 and assume the sizes of 1.3.

| Lane | Order (days) |
|---|---|
| A: computer, sync, home, settings | WP-05a (0–7) → WP-05b (7–14) → WP-07 (14–21) → WP-08 (21–33) → WP-15 (33–37) → WP-24 (37–43) → WP-13 (43–49) |
| B: foundation, queue, share, library, study | WP-02 (0–10) → WP-09 (10–22) → WP-23 (22–27) → WP-16 (27–33) → WP-22 (33–40) → WP-14 (40–48) → WP-21 (48–53) |
| C: analysis, design, media, feed, lesson, learn | WP-12 (0–8) → WP-03 (8–16, or later when the design track delivers; the lane runs WP-10 and WP-11 first if it has not) → WP-10 (16–20) → WP-11 (20–30) → WP-17 (30–42) → WP-18 (42–50) → WP-20 (50–56) |
| Integrator | WP-19 from B1 (day 6) to the gate |

With a fourth lane (lane D, from day 14): lane A runs WP-05a, WP-05b, WP-07, WP-08, WP-15, WP-13; lane B runs WP-02,
WP-09, WP-23, WP-22, WP-14; lane C runs WP-12, WP-10, WP-11, WP-17, WP-18; lane D runs WP-03, WP-16, WP-21, WP-20,
WP-24. Every lane then ends by about day 45.

**Critical path.** B1 (day 6) → WP-05b → WP-07 → WP-08 (day 33) keeps the sync engine early, but the end of wave 2 is
set by the lanes' totals: lane C ends at about day 56. Then WP-19's closing integration (about 5 days of flows,
measurements and the R8 and real-server smokes, prepared during wave 2) and WP-25 (6 days) give the **v1-core gate at
about day 67** with three builder lanes, **about day 56** with four. Revision 1's 32 days assumed 17 parallel copies
that this computer cannot run.

### 1.3 Size summary

| Tier | WPs | Sum (engineer-days, midpoint) |
|---|---|---|
| v1-core | 01 XL 15, 02 L 10, 03 L 8, 04 M 5, 05a L 7, 05b L 7, 06 L 6, 07 L 7, 08 XL 12, 09 XL 12, 10 M 4, 11 L 10, 12 L 8, 13 L 6, 14 L 8, 15 M 4, 16 L 6, 17 XL 12, 18 L 8, 19 integrator role + L 10, 20 L 6, 21 M 5, 22 L 7, 23 M 5, 24 L 6, 25 L 6 | about 200, plus the integrator role |
| v1-complete | 26 L 7 (CR-01-A2 2, then 5), 27 XL 16 (A 7, B 9; one day over the class, from the CR-01 review fixes), 28 XL 13 (A 8, B 5), 29 XL 15, 30 L 8, 31 L 8, 32 M 5, 33 M 5, 34 L 7, 05a-3 S 2, 06-3 S 1 | about 87 (revision 3: 58; CR-01 adds 29) |
| v1-core, CR-01 | WP-19 applies CR-01-A1 (about half a day), WP-16 words one confirmation (minutes) | no change to the v1-core critical path |

### 1.4 Rules for v1-complete

v1-complete work never blocks v1-core (spec D-30). Its priority follows spec 14.2 step 7 (the cut order of 14.4 read
backwards: what is cut last is built first): **deletion from the phone (S-25) and then shared progress (S-24), the
owner's answers of CR-01** (WP-27 milestone A, WP-05a-3, WP-28 milestone A, WP-34, WP-06-3), renditions and resumable
uploads (WP-27 and WP-28, milestones B), Insights (WP-32), the Transcript tab (WP-31, first half), record yourself
(WP-30), phrase details (WP-31, second half), the articulator (WP-29, the first item the spec cuts). WP-27 is
server-only and the highest priority, so it may start as soon as WP-01 is merged, on its own branch, beginning with
milestone A; it is **merged only after the v1-core gate**, after rebasing onto the post-gate server (which may carry
WP-19's server fixes from QA) and re-running the whole pytest suite, so v1-core QA tests v1-core alone (an early
v1-complete WP runs `check_ownership.py` with `--phase complete`). Its milestones may merge separately, A first.
Every other v1-complete WP starts after the gate. If the schedule forces a cut, WPs are dropped in the cut order and
each feature keeps its v1-core fallback, which already ships behind the extension points of section 4.4 (for CR-01:
no "On your computer" block, and progress kept on each device, spec 14.4 items 10 and 11). Lean channel events (cut
item 8) ship in v1-core (server-contract 7; spec D-44 withdrew the cut item).

**Wave-5 lanes with CR-01** (three builder lanes plus the integrator, 1.2; days from the v1-core gate). The
integrator WP-26 applies **CR-01-A2 on days 0 to 2** (its own 2-day item: about 25 types, both Room version 2
migrations with their tests, the pairing-store keys, the fakes' new members, the icon conversion with notices and
`bundled.json`, three messages); every Android WP that uses A2 branches after it.
- Lane A: WP-05a-3 (days 2–4) → WP-28 A (4–12) → WP-28 B (12–17).
- Lane B: WP-34 (2–9; on the settable fakes of CR-01-A2; its screens follow the design track's CR-01 states, design.md
  revision 3, renders 28 to 34; its seeded happy paths over WP-28 A run in WP-26's cross-area flows) → WP-29 (9–24).
- Lane C: WP-32 (0–5; it uses nothing of A2, so it branches from the gate commit and merges A2's baseline when told)
  → WP-31 (5–13) → WP-30 (13–21).
- Outside the lanes: WP-27 (Python; milestone A can run during waves 2 to 4 on its branch), WP-06-3 (Python, after
  WP-27 A is merged), and WP-26's cross-area flows once the lanes merge.

Wave 5 therefore takes **about 24 days** with three lanes (lane B is the longest), about 21 if WP-29, the first item the
spec cuts and the last on its lane, is dropped. Without CR-01 the same three lanes would need about 15 days (WP-29 alone
on one lane; WP-28 B, WP-30, WP-31 and WP-32 on the other two), so CR-01 lengthens wave 5 by about 9 days, which the cut
order of spec 14.4 says is right: the owner's items are built first and cut last. (Revision 4 ran WP-32 → WP-31 →
WP-29 on lane C, which made that lane, at 28 days, the critical path and its "about 9 days" figure wrong; WP-29 moved
to lane B and WP-30 to lane C; architecture 25.6 C-69.)

---------------------------------------------------------------------------------------------------------------

## 2. How parallel work packages run

### 2.1 Branches, baselines and merges (delivery review D-03)

1. The integrator publishes each baseline as a git commit on the integration branch (B0 at M1, B1 at M2, then B2,
   B3… after each merge round). Commits and pushes happen only when the orchestrator says so; until then a baseline
   is a commit the orchestrator authorises, or, if none is, a recorded tree plus its file hashes.
2. A WP works in its own git worktree on its own branch created from the baseline commit it starts from.
3. A WP changes only the files its rows of the ownership table (section 3) give it. It may rebase or merge a newer
   baseline at any time (it never edits files it does not own, so this never conflicts on them).
4. Before hand-off: the WP's definition of done (2.5), then, from `android/`,
   `python3 scripts/hygiene/check_ownership.py --wp WP-nn --base <its baseline commit>` clean on its branch's diff.
5. The integrator merges the WP's branch into the integration branch with a **three-way merge** (or applies its diff
   with `git apply --3way` when commits are not authorised), never by copying files over the integration tree, so a
   concurrent edit of the same file (a README or notices section, a server file fixed after QA) is either merged or
   shows as a conflict; it never silently disappears. Section rows (3.1) are merged as patches of their lines. Then the
   integrator applies the WP's change requests (2.2), runs the full definition of done (all modules, the whole GMD
   suite, pytest, the web suite) and publishes the next baseline.
6. **Server maintenance** (delivery review D-23): after WP-01 is merged, the integrator of the current wave owns the
   server rows for fixes found by WP-19 or WP-25 (with a failing test first), and owns the two drift guards that read
   `web/` (`test_phone_state.py`, `test_phone_compat.py`) when a web change breaks them. A v1-complete server branch
   (WP-27) rebases onto the post-gate server and re-runs the whole suite before its merge (1.4).

Before WP-06 delivers `check_ownership.py`, the integrator checks the file list by hand against section 3.

### 2.2 Change requests

A file a WP does not own (a frozen contract file, a version catalog entry, a Room entity, a navigation key, a string in
another module, a fake it needs extended) is changed only through a **change request** listed in the WP's hand-off
report: the file, the exact additive change, and why. The integrator applies it (additive only: new types, new
methods with default implementations, new optional fields, new keys, new dependencies; never a rename or removal
without the affected owners' agreement). A WP that cannot proceed without a change request asks the integrator early;
it keeps coding against its own temporary local type in the meantime and swaps to the frozen type on merge. Because
every frozen type has its fields in Appendix A, change requests are corrections, not the main design channel. Until
the first build is installed on the test phone, Room entity requests are folded into schema version 1 (architecture
4.4).

**Change request CR-01 (formal, from the architect; 4.7 has the exact code).** The owner's answers of 2026-10-10 need
two additive amendments of the frozen contract, published by the integrator of the moment:
- **CR-01-A1** (v1-core baseline; integrator **WP-19**, in the first merge round after the CR-01 documents are merged,
  published with the next baseline `B<n>`): `LearningDatabase` version 1 gains `learning_meta`, and `DatabaseModule`
  writes `learning_id` when the database is created. Folded into version 1 because no build is installed yet (AD-10)
  and because every backup of every release must carry the id (architecture 4.4). No DAO, repository or screen uses it
  in v1-core; WPs that branched earlier merge the baseline when told and change nothing.
- **CR-01-A2** (v1-complete; integrator **WP-26**, as the first commit after the v1-core gate commit, before any
  wave-5 Android WP that uses it branches; its own 2-day item, 1.4): the domain, SPI and `:core:network-api` additions,
  `Capabilities`' two flags **and the two pairing-store keys that keep them** (`:core:datastore`: WP-02's
  `DataStorePairingStore` saves capabilities field by field, so a flag it does not name reads back `false`), the
  `SyncController.exclusive` call around `PairingRepository.forget()`'s body (`:core:connectivity`; architecture 6.3),
  the three `UserMessageKind` members with their strings in the shell's message host, which passes each title through
  `QuotedTitle` (`:core:ui` `component/`, made in the same commit with its unit test; architecture 19, design 9.26),
  settable members for the new methods in the existing fakes, and Room **version 2** of both databases with its
  `AutoMigration` and migration tests.
  `:core:datastore` and `:core:connectivity` have no v1-complete owner, so both changes are the integrator's (3.2);
  nothing changes in WP-02 M3 or WP-07 before then.
Both are additive in this section's sense (new types, methods with default bodies, fields with defaults, new tables
and nullable columns); none renames or removes anything, and no exhaustive `when` outside the shell's message host
needs a new branch (architecture 3.4, 25.5 C-35).

### 2.3 Stubs, fakes and the freeze

At M2 every module exists, compiles and is bound to a working stub (empty flows, "not connected"), every
`:core:domain` and `:core:network-api` interface has a settable in-memory fake in `:core:testing-jvm`, every
destination is navigable to a stub screen, and the shared composables of `:core:ui` exist with placeholder visuals. A
WP replaces its own stubs and fakes with real code; it never edits another WP's. So every copy launches, and every
feature can be built and screen-tested on fakes from the first day of wave 2. v1-complete modules are stubs that bind
**no** extension point (4.4).

### 2.4 Test seams (delivery review D-01; architecture 21.5)

- Unit and screen tests use the fakes of `:core:testing-jvm` directly; state-by-state UI coverage lives in the feature
  modules' Robolectric screen tests.
- Core instrumented tests (`:core:player`, `:core:requests`, `:core:sync`) run in their own modules on the managed
  device, with the fakes they need wired by hand (no Hilt test component).
- `:app/src/androidTest` has **one global `@TestInstallIn` set**, integrator-owned and present from B1:
  `TestNetworkModule`, `FakeComputerModule`, `TestConnectivitySeamsModule`, `TestDatabaseModule`, `TestClockModule`,
  and WorkManager test initialization in the runner. **Repositories stay real** (the B1 stubs until their owners
  merge). There is no `FakeDomainModule`: a `@TestInstallIn` module would apply to every test of the source set and
  cannot be uninstalled, so it would make WP-19's full-stack tests impossible.
- A wave-2 area test proves its destinations' navigation and wiring with the repositories present in its baseline,
  and its happy path seeded through `TestSeeder` (`e2e/support/`, integrator) when the cores it reads are merged;
  otherwise its robot exposes the happy path for WP-19's flows. A test that truly needs a fake repository uses a
  per-class `@UninstallModules` plus `@BindValue` and says why.

### 2.5 Definition of done

Every Android WP: architecture 23 items 1 to 7, i.e.
1. `./gradlew :<module>:testDebugUnitTest` (or `:<module>:test` for JVM modules) for every module touched, then
   `./gradlew check` green;
2. `./gradlew lintDebug` clean with no baseline;
3. `./gradlew assembleDebug`; and `./gradlew assembleRelease` when dependencies, R8 rules, serialization or
   reflection changed;
4. under the GMD lock, when UI, navigation, persistence, DI, media or work scheduling changed: the WP's area of the
   `:app` suite (`./gradlew pixel9Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=<root>.e2e.<area>`)
   and, for `:core:player`, `:core:requests` and `:core:sync`, `./gradlew :core:<module>:pixel9Api36DebugAndroidTest`;
5. `./gradlew :core:content:verifyDidacticContent` when `:core:content` or the extractor changed;
6. the hygiene hooks installed (`scripts/hygiene/install-hooks.sh`); `python3 scripts/hygiene/check_repo.py` and
   `python3 scripts/hygiene/check_ownership.py --wp <id> --base <baseline>` clean (run from `android/`);
7. English everywhere; every new string in the module's own `strings.xml` (a sub-package or shared component owned by
   another WP uses its own `res/values/strings_<name>.xml`, 3.2); test tags and accessible labels on every new
   interactive element; previews for every new screen state.

Server WPs (WP-01, WP-27): architecture 23 item 8, as detailed in server-contract 11.4.

Each WP below adds its own proof list ("Proof") and acceptance ("Done when").

### 2.6 Rules every WP keeps

- Write only inside the project copy; never touch `web/` (reading it is fine); nothing under the main checkout.
- English only, including UI copy, test names, comments and commit messages if any.
- No address, serial, token, key, pairing code, host name, home path or personal data in any file (the hygiene hooks
  and check enforce it).
- A new dependency is a change request (version catalog) with its licence; it must be GPL-3.0-or-later compatible and
  is recorded by the integrator in `THIRD-PARTY-NOTICES.md` section 7.
- Test tags: each feature module exposes a public `object <Feature>Tags` (for example `WatchTags.SPEED`); tag values
  are `"<feature>.<screen>.<element>"`; tags and content descriptions are part of the test contract (Artemi rule).
- No device QA outside WP-25 and WP-33; no WP ever sends a link to the real computer (spec D-36); emulator work that
  needs a computer uses the host-side fake computer (server-contract 12).
- A bug found in another WP's files is reported to the integrator with a failing test, never fixed in place.

---------------------------------------------------------------------------------------------------------------

## 3. Ownership

### 3.1 How the table is read (by people and by `check_ownership.py`)

- The table of 3.2 is the first Markdown table after the heading "3.2 The table"; its columns are Pattern, Owner, Phase,
  Note; back-ticks are stripped.
- **Pattern**: a path relative to the repository root; `*` matches within one path segment, `**` across segments. A
  pattern `file.md#Heading text` owns the lines of that Markdown section (from the heading to the next heading of the
  same or a higher level), matched against the heading text exactly. Whitespace-only lines at a section boundary
  belong to the section that starts after them (delivery review D-30), so adding a new section never touches the
  previous owner's lines.
- **Owner**: `WP-nn`; `INTEGRATOR` (the integrator of the current wave, 2.1); `ARCHITECT`, `PRODUCT`, `DESIGN` (the
  planning tracks; no WP edits them); `NONE` (nobody in this project changes it).
- **Phase**: `core` (waves 1–4), `complete` (waves 5–6) or `all`. Only rows of the current phase and `all` apply.
- **Precedence**: the matching row with the most literal (non-wildcard) characters wins; a section row wins over its
  file's row for lines inside the section. Two matching rows of equal precedence are a table error.
- Defaults: a path under `android/` that no row matches belongs to `INTEGRATOR`; any other path belongs to `NONE`.
- `check_ownership.py --wp WP-nn --base <baseline commit> [--phase core|complete] [--as-integrator]` lists every
  added, changed or deleted file of the branch's diff (and, for section rows, every changed line) and fails if any is
  not owned by `WP-nn` (or by `INTEGRATOR` when `--as-integrator` is given). `--self-check` validates the table.
- **Bootstrap**: until B1, WP-02 may create any file under `android/` (the skeleton, every stub, the placeholder
  visuals and every fake), except the day-0 rows of WP-04, WP-05a, WP-06 and WP-12, whose owners create their own
  files and WP-02 only declares their modules; from B1 on, the table applies to everyone, WP-02 included.

### 3.2 The table

| Pattern | Owner | Phase | Note |
|---|---|---|---|
| `web/**` | NONE | all | the web client belongs to another agent |
| `CLAUDE.md` | NONE | all | |
| `LICENSE` | NONE | all | |
| `.gitignore` | NONE | all | `android/.gitignore` covers Android (architecture 2.6) |
| `docs/**` | NONE | all | the root design documents |
| `references/**` | NONE | all | |
| `scripts/**` | NONE | all | root scripts, not `android/scripts` |
| `phonotrainer/**` | NONE | all | the analysis pipeline is not changed by this project |
| `phonotrainer/server.py` | WP-01 | core | server-contract 10.2; the integrator after WP-01's merge (2.1) |
| `phonotrainer/jobs.py` | WP-01 | core | |
| `phonotrainer/cli.py` | WP-01 | core | |
| `phonotrainer/download.py` | WP-01 | core | S-23 and the confined phone-origin downloads |
| `phonotrainer/phone/**` | WP-01 | core | server-contract 10.1 |
| `tests/**` | WP-01 | core | server-contract 11 |
| `pyproject.toml` | WP-01 | core | the `packages` list |
| `requirements.txt` | WP-01 | core | only if server-contract 9 point 4 applies |
| `phonotrainer/server.py` | WP-27 | complete | |
| `phonotrainer/jobs.py` | WP-27 | complete | |
| `phonotrainer/phone/**` | WP-27 | complete | milestone A: deletion from phones and shared progress (CR-01); milestone B: renditions, resumable uploads, record yourself |
| `tests/**` | WP-27 | complete | |
| `README.md` | NONE | all | |
| `README.md#Phone access (Android client)` | WP-01 | core | new section |
| `README.md#Material: from YouTube to analysis` | WP-01 | core | downloader notes (S-23) |
| `README.md#Phone access (Android client)` | WP-27 | complete | |
| `README.md#Android client` | INTEGRATOR | all | new short section pointing to `android/README.md` |
| `THIRD-PARTY-NOTICES.md` | NONE | all | |
| `THIRD-PARTY-NOTICES.md#3. Python dependencies (installed with pip, not redistributed)` | WP-01 | core | only if a Python package is added |
| `THIRD-PARTY-NOTICES.md#7. Android client` | INTEGRATOR | all | new section, created by WP-19 (moved from WP-02 at M3) |
| `THIRD-PARTY-NOTICES.md#7.3 Fonts and icons` | WP-03 | core | heading pre-created by WP-19; the fonts and the Lucide icons redistributed in the APK (design 12.3) |
| `Makefile` | INTEGRATOR | all | the `android-check` target |
| `.github/**` | WP-06 | core | the Android workflow |
| `.github/**` | INTEGRATOR | complete | |
| `android/docs/product-spec.md` | PRODUCT | all | |
| `android/docs/architecture.md` | ARCHITECT | all | |
| `android/docs/server-contract.md` | ARCHITECT | all | |
| `android/docs/work-plan.md` | ARCHITECT | all | |
| `android/docs/web-progress-sync.md` | ARCHITECT | all | the browser client's hand-off for shared progress (CR-01); nothing under `web/` is changed by this project |
| `android/docs/design.md` | DESIGN | all | |
| `android/docs/design/**` | DESIGN | all | the design track's supporting pages (delivery review D-26) |
| `android/docs/brand-assets.md` | DESIGN | all | |
| `android/docs/decisions.md` | INTEGRATOR | all | |
| `android/docs/qa-report.md` | WP-25 | core | |
| `android/docs/qa-report.md` | WP-33 | complete | |
| `android/design-src/**` | DESIGN | all | the design track's sources and staging export |
| `android/design-src/convert/**` | WP-03 | core | conversion scripts and converted sources |
| `android/tools/didactic/**` | WP-04 | core | |
| `android/tools/reference/**` | WP-04 | core | |
| `android/scripts/**` | WP-06 | core | hygiene checks, hooks, device-QA kit, host-side fake computer, GMD lock |
| `android/scripts/**` | WP-06 | complete | milestone 3 (CR-01): the host-side fake computer's seeds and self-check, the D-57 guard of the destructive UI helpers, the QT-15 to QT-20 steps |
| `android/core/domain/**` | INTEGRATOR | all | frozen at B1 (section 4) |
| `android/core/network-api/**` | INTEGRATOR | all | frozen at B1 (4.3) |
| `android/core/navigation/**` | INTEGRATOR | all | frozen at B1 |
| `android/core/common/**` | WP-02 | core | |
| `android/core/common/src/*/kotlin/**/common/ipa/**` | WP-12 | core | `IpaTokenizer` (architecture 2.2) |
| `android/core/common/src/*/kotlin/**/common/shadowing/**` | WP-12 | core | `ShadowingPlan` |
| `android/core/datastore/**` | WP-02 | core | |
| `android/core/datastore/**` | INTEGRATOR | complete | CR-01-A2: the pairing store's two capability keys (4.7) |
| `android/core/notifications/**` | WP-02 | core | |
| `android/core/database/**` | INTEGRATOR | all | entities, databases, migrations, exported schemas, `DatabaseModule` |
| `android/core/database/src/*/kotlin/**/library/dao/ClipDao*.kt` | WP-08 | core | DAO and its test |
| `android/core/database/src/*/kotlin/**/library/dao/ArtifactDao*.kt` | WP-08 | core | |
| `android/core/database/src/*/kotlin/**/library/dao/SyncMetaDao*.kt` | WP-08 | core | |
| `android/core/database/src/*/kotlin/**/library/dao/ShortDao*.kt` | WP-10 | core | |
| `android/core/database/src/*/kotlin/**/library/dao/OccurrenceDao*.kt` | WP-10 | core | |
| `android/core/database/src/*/kotlin/**/library/dao/RequestDao*.kt` | WP-09 | core | |
| `android/core/database/src/*/kotlin/**/library/dao/RequestOpDao*.kt` | WP-09 | core | |
| `android/core/database/src/*/kotlin/**/learning/dao/**` | WP-14 | core | every learning DAO |
| `android/core/database/src/*/kotlin/**/dao/**` | WP-28 | complete | every DAO in v1-complete: the outbox and clip queries of the deletion, the learning DAOs of shared progress (CR-01), the artifact queries of renditions |
| `android/core/designsystem/**` | WP-03 | core | |
| `android/core/ui/**` | WP-03 | core | visual primitives (architecture 17) |
| `android/core/ui/src/*/kotlin/**/ui/extension/**` | INTEGRATOR | all | extension-point interfaces, frozen at B1 |
| `android/core/ui/src/*/kotlin/**/ui/component/ClipCard*.kt` | WP-16 | core | shared component, 4.5 |
| `android/core/ui/src/*/kotlin/**/ui/component/ClipRow*.kt` | WP-16 | core | |
| `android/core/ui/src/*/kotlin/**/ui/component/RequestRow*.kt` | WP-22 | core | |
| `android/core/ui/src/*/kotlin/**/ui/component/RequestFailureCopy*.kt` | WP-22 | core | the one failure-copy mapping (architecture 19) |
| `android/core/ui/src/*/kotlin/**/ui/component/WaitingReasonCopy*.kt` | WP-22 | core | the one list of waiting reasons of spec D-61, for requests and (in v1-complete, through `DeletionCopy`) deletions (architecture 19) |
| `android/core/ui/src/*/kotlin/**/ui/component/QuestionCard*.kt` | WP-21 | core | |
| `android/core/ui/src/*/kotlin/**/ui/component/AnswerFeedback*.kt` | WP-21 | core | |
| `android/core/ui/src/*/kotlin/**/ui/component/ShadowingPanel*.kt` | WP-18 | core | |
| `android/core/ui/src/*/kotlin/**/ui/component/OccurrenceRow*.kt` | WP-20 | core | |
| `android/core/ui/src/*/kotlin/**/ui/component/ConnectionStatusCard*.kt` | WP-15 | core | shared component, 4.5 (design 9.17) |
| `android/core/ui/src/*/kotlin/**/ui/component/ProbeOutcomeCopy*.kt` | WP-15 | core | the one probe-outcome copy (architecture 6.2), strings in `strings_connection.xml` |
| `android/core/ui/src/*/kotlin/**/ui/component/ShareBridge*.kt` | WP-23 | core | shared component, 4.5 (design 9.22) |
| `android/core/ui/src/main/res/values/strings_clip.xml` | WP-16 | core | strings of `ClipCard` and `ClipRow` (delivery review D-13) |
| `android/core/ui/src/main/res/values/strings_request.xml` | WP-22 | core | strings of `RequestRow` and `RequestFailureCopy` |
| `android/core/ui/src/main/res/values/strings_question.xml` | WP-21 | core | |
| `android/core/ui/src/main/res/values/strings_shadowing.xml` | WP-18 | core | |
| `android/core/ui/src/main/res/values/strings_occurrence.xml` | WP-20 | core | |
| `android/core/ui/src/main/res/values/strings_connection.xml` | WP-15 | core | strings of `ConnectionStatusCard` |
| `android/core/ui/src/main/res/values/strings_bridge.xml` | WP-23 | core | strings of `ShareBridge` |
| `android/core/ui/src/*/kotlin/**/ui/component/ClipCard*.kt` | WP-34 | complete | the "Waiting to be deleted from your computer" and "Deleting from your computer…" words (CR-01) |
| `android/core/ui/src/*/kotlin/**/ui/component/ClipRow*.kt` | WP-34 | complete | |
| `android/core/ui/src/main/res/values/strings_clip.xml` | WP-34 | complete | |
| `android/core/ui/src/*/kotlin/**/ui/component/ConnectionStatusCard*.kt` | WP-34 | complete | the attention row's inline action when it is a refused deletion's "Try again" (CR-01) |
| `android/core/ui/src/main/res/values/strings_connection.xml` | WP-34 | complete | |
| `android/core/ui/src/*/kotlin/**/ui/component/DeletionCopy*.kt` | WP-34 | complete | the one mapping of deletion states to words, shared by the Requests rows and the Clip page (architecture 19) |
| `android/core/ui/src/main/res/values/strings_deletion.xml` | WP-34 | complete | strings of `DeletionCopy` |
| `android/core/ui/src/*/kotlin/**/ui/component/QuotedTitle*.kt` | INTEGRATOR | complete | CR-01-A2: the quoted title of design 9.26 and its unit test (architecture 19) |
| `android/core/ui/src/*/kotlin/**/ui/place/**` | WP-34 | complete | the visual pieces of design 9.26 (`PlaceHead`, `ReachStrip`, `DeletionRoute`, `SyncLine`, the danger confirmation) |
| `android/core/ui/src/main/res/values/strings_place.xml` | WP-34 | complete | strings of `ui/place/` |
| `android/core/content/**` | WP-04 | core | |
| `android/core/network/**` | WP-05b | core | |
| `android/core/network/**` | WP-28 | complete | the three CR-01 methods of `ComputerClient` (server-contract 6.11, 6.12) |
| `android/core/analysis/**` | WP-12 | core | |
| `android/core/connectivity/**` | WP-07 | core | |
| `android/core/connectivity/**` | INTEGRATOR | complete | CR-01-A2: `forget()` inside `SyncController.exclusive` (architecture 6.3) |
| `android/core/sync/**` | WP-08 | core | including its `src/androidTest` |
| `android/core/sync/**` | WP-28 | complete | milestone A: deletion paths and the shared-progress exchange (CR-01); milestone B: renditions and phone video in the sync |
| `android/core/requests/**` | WP-09 | core | including its `src/androidTest` |
| `android/core/requests/**` | WP-28 | complete | milestone A: the `DELETE_FINISHED` operation (CR-01); milestone B: resumable uploads |
| `android/core/corpus/**` | WP-10 | core | |
| `android/core/player/**` | WP-11 | core | including its `src/androidTest` |
| `android/core/study/**` | WP-14 | core | |
| `android/core/study/**` | WP-28 | complete | the shared-progress ledger, set exclusions of waiting deletions (CR-01) |
| `android/core/articulator/**` | NONE | core | stub from WP-02 stays untouched in v1-core |
| `android/core/articulator/**` | WP-29 | complete | |
| `android/core/recorder/**` | NONE | core | stub from WP-02 |
| `android/core/recorder/**` | WP-30 | complete | |
| `android/core/testing-jvm/**` | INTEGRATOR | all | base fakes, `TestClock`, `MainDispatcherRule` |
| `android/core/testing-jvm/src/main/kotlin/**/testing/computer/**` | WP-05a | core | `FakeComputer`; `FakeComputerClient` from B1 |
| `android/core/testing-jvm/src/test/kotlin/**/testing/computer/**` | WP-05a | core | the fake computer's own JVM tests |
| `android/core/testing-jvm/src/main/kotlin/**/testing/computer/**` | WP-05a | complete | milestone 3 (CR-01): deletion and shared-progress routes, knobs, a second device |
| `android/core/testing-jvm/src/test/kotlin/**/testing/computer/**` | WP-05a | complete | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fixtures/**` | WP-12 | core | `AnalysisFixtures` (delivery review D-17) |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/content/**` | WP-04 | core | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/network/**` | WP-05b | core | fakes of `AllowedPeers`, `VerifiedPeers`, `ArtifactTransfer`, `JobChannel` |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/network/**` | WP-28 | complete | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/connectivity/**` | WP-07 | core | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/sync/**` | WP-08 | core | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/sync/**` | WP-28 | complete | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/requests/**` | WP-09 | core | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/requests/**` | WP-28 | complete | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/corpus/**` | WP-10 | core | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/player/**` | WP-11 | core | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/study/**` | WP-14 | core | |
| `android/core/testing-jvm/src/main/kotlin/**/testing/fake/study/**` | WP-28 | complete | |
| `android/core/testing/**` | INTEGRATOR | all | Android test rules, `A11yAudit` |
| `android/core/testing/src/main/kotlin/**/testing/synthetic/**` | WP-11 | core | `SyntheticMedia` (delivery review D-06) |
| `android/feature/onboarding/**` | WP-13 | core | |
| `android/feature/connect/**` | WP-13 | core | |
| `android/feature/home/**` | WP-15 | core | |
| `android/feature/home/**` | WP-34 | complete | the attention row's inline action for a refused deletion (CR-01; design 9.17) |
| `android/feature/library/**` | WP-16 | core | |
| `android/feature/library/src/*/kotlin/**/library/transcript/**` | NONE | core | reserved for WP-31 |
| `android/feature/library/src/*/kotlin/**/library/transcript/**` | WP-31 | complete | |
| `android/feature/library/src/main/res/values/strings_transcript.xml` | WP-31 | complete | |
| `android/feature/library/**` | WP-34 | complete | the Clip page's "On your computer" block (CR-01); `transcript/` stays WP-31's |
| `android/feature/watch/**` | WP-17 | core | |
| `android/feature/lesson/**` | WP-18 | core | |
| `android/feature/lesson/src/*/kotlin/**/lesson/record/**` | NONE | core | reserved for WP-30 |
| `android/feature/lesson/src/*/kotlin/**/lesson/record/**` | WP-30 | complete | |
| `android/feature/lesson/src/main/res/values/strings_record.xml` | WP-30 | complete | |
| `android/feature/lesson/src/*/kotlin/**/lesson/phrase/**` | NONE | core | reserved for WP-31 |
| `android/feature/lesson/src/*/kotlin/**/lesson/phrase/**` | WP-31 | complete | |
| `android/feature/lesson/src/main/res/values/strings_phrase.xml` | WP-31 | complete | |
| `android/feature/learn/**` | WP-20 | core | |
| `android/feature/practice/**` | WP-21 | core | |
| `android/feature/practice/**` | WP-34 | complete | the shared-progress lines and both reset confirmations (CR-01) |
| `android/feature/requests/**` | WP-22 | core | |
| `android/feature/requests/**` | WP-34 | complete | the deletion rows (CR-01) |
| `android/feature/share/**` | WP-23 | core | |
| `android/feature/settings/**` | WP-24 | core | |
| `android/feature/settings/**` | WP-34 | complete | the two CR-01 sentences of "Forget this computer…" |
| `android/feature/insights/**` | NONE | core | stub from WP-02 |
| `android/feature/insights/**` | WP-32 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/connect/**` | WP-13 | core | area robot and tests |
| `android/app/src/androidTest/kotlin/**/e2e/home/**` | WP-15 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/home/**` | WP-34 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/library/**` | WP-16 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/library/**` | WP-34 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/watch/**` | WP-17 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/lesson/**` | WP-18 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/learn/**` | WP-20 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/practice/**` | WP-21 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/practice/**` | WP-34 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/requests/**` | WP-22 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/requests/**` | WP-34 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/share/**` | WP-23 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/settings/**` | WP-24 | core | |
| `android/app/src/androidTest/kotlin/**/e2e/settings/**` | WP-34 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/mouth/**` | WP-29 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/record/**` | WP-30 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/phrase/**` | WP-31 | complete | |
| `android/app/src/androidTest/kotlin/**/e2e/insights/**` | WP-32 | complete | |

Everything else under `android/` (settings and build files, wrapper, version catalog, `build-logic/`, `.gitignore`,
`.editorconfig`, `README.md`, `AGENTS.md`, `SECURITY.md`, `qasmoke/`, `baselineprofile/`, the `:app` module including
`e2e/PhonoRobot.kt`, `e2e/support/` (`TestSeeder`), `e2e/di/` (the global test modules), `e2e/flows/`, `e2e/queue/`,
`e2e/smoke/`) belongs to the integrator by the default rule.

---------------------------------------------------------------------------------------------------------------

## 4. The contract freeze (M2): what WP-02 publishes in B1

Everything below exists in B1 with a stub implementation and a fake, and changes afterwards only additively through
the integrator. Shapes are normative; WP-02 may add fields and parameters with defaults (architecture 3.4). Every
type named here or in architecture 3.4 has its fields in **Appendix A**, so the freeze is a transcription (delivery
review D-02). Two inputs arrive from day-0 WPs by day 4: WP-12's `ClipData` family and `AnalysisParser` signature
(it may refine Appendix A.3 additively), and WP-04's `didactic.json` schema with its Kotlin model (A.8).

### 4.1 From the architecture, transcribed as is

- `:core:domain` model and API of architecture 3.4 (every type and interface listed there).
- Room `LibraryDatabase` v1 and `LearningDatabase` v1 exactly as architecture 4.2 and 4.3, schemas exported; DataStore
  keys of 4.5; file layout constants of 4.6.
- Navigation keys of architecture 16.1, `Tab`, `NavTarget` (16.4), `Navigator`, `EntryInstaller` (16.3), one stub
  installer per feature module.
- `:core:ui` API of architecture 17 (`PhonoTheme` placeholder, `FamilyMark`, `MarkedWordSpan`, `ChangeChip`,
  `PracticeBadge`, `StatusLine`, `AvailabilityChip`, `ExplainButton`, `ExplainSheet`, `IpaText`, `LoadingGate`,
  `EmptyState`, `CoachMarkHost`, `PhonoButton`, cards, sheets), and the accessibility helpers of 18
  (`Modifier.shortSemantics`, `Modifier.paneTitle`, `LocalReducedMotion`, `LocalTalkBackOn`, `Announcer`).
- The error model of architecture 19 (`AppError`).
- **Frozen names** that other parts of the build reference:
  - Hilt modules that the global test set replaces (architecture 21.5): `NetworkPolicyModule` (`:core:network`),
    `ConnectivitySeamsModule` (`:core:connectivity`), `DatabaseModule` (`:core:database`), `ClockModule` (`:app`);
  - resources that `:app`'s manifest and themes take from `:core:designsystem`, so WP-02's placeholders and WP-03's
    real resources share names (delivery review D-13): `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`,
    `@drawable/ic_stat_phono` (notification icon), `@style/Theme.Phono`, `@style/Theme.Phono.Starting` (splash),
    `@style/Theme.Phono.ShareSheet` (the translucent `ShareActivity` theme), `@color/phono_window_background`.

### 4.2 Additions fixed by this plan in `:core:domain`

Package `…core.domain.model.remote` — what the computer reports, mapped by `:core:network` from server-contract 6.3:

```kotlin
data class RemoteLibrary(val apiVersion: Int, val serverId: ServerId, val libraryId: LibraryId,
  val generatedAt: Instant, val jobs: List<RemoteJob>)
data class RemoteJob(
  val id: JobId, val revision: String, val status: RemoteStatus, val stage: Stage?, val percent: Int,
  val segment: Int?, val segments: Int?, val downloadPercent: Int?, val separation: SeparationNote?,
  val queuePosition: Int?, val title: String?, val source: RemoteSource, val audioOnly: Boolean,
  val separateDialogue: Boolean, val deviceId: DeviceId?, val own: Boolean, val clientRequestId: RequestId?,
  val rerunOf: JobId?, val createdAt: Instant, val startedAt: Instant?, val finishedAt: Instant?,
  val error: RemoteError?, val summary: RemoteSummary?, val analysis: RemoteAnalysisInfo?, val media: RemoteMedia?,
  val artifacts: List<RemoteArtifact>?)          // null when it came from the job channel (lean)
// Every enum decoded from the computer has an UNKNOWN member: a future value never fails a listing (NFR-08, D-29).
enum class RemoteStatus { QUEUED, RUNNING, DONE, ERROR, CANCELLED, UNKNOWN }
enum class Stage { DOWNLOAD, EXTRACT, SEPARATE, TRANSCRIBE, PHONES, SAVE, UNKNOWN }
enum class SeparationNote { OFF, SKIPPED, UNKNOWN }
data class RemoteSource(val kind: SourceKind, val url: String?, val site: Site?, val fileName: String?)
enum class SourceKind { LINK, UPLOAD, PATH, IMPORT, UNKNOWN }
data class RemoteError(val code: String, val message: String, val downloaderVersion: String?)
data class RemoteSummary(val durationS: Double?, val segments: Int, val words: Int, val changeCounts: Map<String, Int>)
data class RemoteAnalysisInfo(val rulesVersion: Int?, val phoneEngine: String?, val dialogueSeparation: Boolean)
data class RemoteMedia(val kind: MediaKind, val codec: String?, val width: Int?, val height: Int?, val rotation: Int,
  val startOffsetMs: Long, val durationS: Double?)        // rotation and offset are 0 when unknown (server-contract 6.3)
enum class MediaKind { VIDEO, AUDIO, NONE, UNKNOWN }
data class RemoteArtifact(val name: String, val role: ArtifactRole, val status: RemoteArtifactStatus,
  val bytes: Long?, val sha256: String?, val revision: String?, val contentType: String?, val url: String?,
  val error: String?, val video: VideoFacts?)
enum class ArtifactRole { CLIP_DATA, AUDIO_MIX, AUDIO_DIALOGUE, VIDEO, POSTER, UNKNOWN }
enum class RemoteArtifactStatus { READY, PREPARING, FAILED, UNAVAILABLE, UNKNOWN }
data class VideoFacts(val codec: String, val width: Int, val height: Int)
data class ComputerIdentity(val phoneAccess: Boolean, val serverId: ServerId?, val name: String?, val apiVersion: Int,
  val serverTime: Long, val proofValid: Boolean?, val revoked: Boolean?)   // proofValid: null when no challenge was sent
data class ComputerHealth(val serverId: ServerId, val name: String, val libraryId: LibraryId, val apiVersion: Int,
  val minAppVersion: Int, val version: String, val serverTime: Long, val deviceId: DeviceId, val analyzing: Int,
  val inLine: Int, val lowOnSpace: Boolean, val capabilities: Capabilities)
data class Capabilities(val linkDownloads: Boolean, val downloaderVersion: String?, val dialogueSeparation: Boolean,
  val renditions: Boolean, val phoneVideo: Boolean, val resumableUploads: Boolean, val recordCompare: Boolean,
  val maxUploadBytes: Long, val maxChunkBytes: Long, val maxLinkDurationS: Long, val freeBytes: Long)
data class PairGrant(val deviceId: DeviceId, val token: CharArray, val serverId: ServerId, val name: String,
  val libraryId: LibraryId, val apiVersion: Int, val minAppVersion: Int, val serverTime: Long)
sealed interface ChannelEvent {
  data class Hello(val serverId: ServerId, val libraryId: LibraryId, val apiVersion: Int) : ChannelEvent
  data class Snapshot(val jobs: List<RemoteJob>) : ChannelEvent
  data class Job(val job: RemoteJob) : ChannelEvent
  data class Deleted(val id: JobId) : ChannelEvent
}
```

Package `…core.domain.spi` — seams between core modules (so that `:core:sync` never depends on `:core:requests`,
`:core:corpus` or `:core:study` implementations):

```kotlin
interface RequestSender {                         // :core:requests (WP-09) → :core:sync (WP-08), architecture 7.1
  suspend fun sendOperations(): SendReport        // CANCEL, REMOVE_JOB, RERUN, oldest first (unpair is never queued)
  suspend fun sendLinks(): SendReport             // waiting links in seq order
  fun scheduleUploads()                           // enqueues the upload work when a file request waits
}
interface RequestReconciler {                     // :core:requests (WP-09) → :core:sync (WP-08)
  suspend fun reconcile(library: RemoteLibrary)   // request states, automatic re-run, attention notifications
  suspend fun onJob(job: RemoteJob)               // live channel event
  suspend fun onJobDeleted(id: JobId)
  suspend fun onClipOnPhone(id: JobId)            // study pack verified → ON_PHONE and "Ready to watch" (once)
}
interface ClipIndexer {                           // :core:corpus (WP-10) → :core:sync (WP-08), architecture 9.5
  suspend fun index(id: JobId): IndexOutcome      // parse → shorts → occurrences, one transaction
  suspend fun reindexStale()                      // after an app update raised a rule version
  suspend fun drop(ids: Set<JobId>)
}
interface ClipLeases {                            // :core:corpus (WP-10) → :core:sync (WP-08), architecture 7.7
  fun isOpen(id: JobId): Boolean
  val released: Flow<JobId>
}
fun interface ClipRemovalHook { suspend fun onClipsRemoved(ids: Set<JobId>) }  // @IntoSet; WP-14 and WP-10 provide one
interface StudyProtection {                       // :core:study (WP-14) → :core:sync eviction (WP-08), architecture 7.5
  suspend fun protectedClips(): Set<JobId>        // today's set and clips opened in the last 24 h
  suspend fun lastWatched(): Map<JobId, Instant>
}
fun interface StartupTask { suspend fun run(processStart: Instant) }  // @IntoSet with @StartupOrder(n); run once per process by StartupCheck (WP-08); acts only on rows and files older than processStart
interface StartupGate { suspend fun awaitDone() } // intake, transfers and the first sync pass wait for StartupCheck (architecture 4.6)
interface NetworkMonitor { val network: StateFlow<NetworkSnapshot> }      // :core:connectivity (WP-07); replaced in e2e tests
interface DiscoveryTransport {                    // :core:connectivity (WP-07); FakeDiscovery in e2e tests
  fun probe(datagram: ByteArray, broadcastTargets: List<String>, schedule: List<Duration>, listenFor: Duration): Flow<Datagram>
}
```

Players (architecture 2.2 names `FeedPlayer` without its shape; `ShadowingRunner` of 11.5 needs one too):

```kotlin
interface FeedPlayer {                            // :core:player (WP-11) → :feature:watch (WP-17)
  val state: StateFlow<FeedPlaybackState>         // index, playing, speed, track, loop, ended, holdSlow, videoShown, error
  fun setQueue(items: List<FeedItem>, startIndex: Int)
  fun settle(index: Int, autoplay: Boolean)       // prepares n-1, n, n+1 (pool of 3); plays n when autoplay
  fun play(); fun pause(); fun seekTo(positionMs: Long)
  fun setSpeed(speed: Float); fun holdSlow(held: Boolean); fun setTrack(track: Track)
  fun setLoop(mode: LoopMode)                     // OFF, PHRASE, SHORT
  fun replayPhrase(); fun playSpan(span: ClipSpan)
  fun positionMs(): Long                          // read every frame for captions (architecture 11.3)
  fun release()
}
data class FeedItem(val short: ShortInfo, val media: ClipMedia)
enum class LoopMode { OFF, PHRASE, SHORT }
interface ShadowingRunner {                       // :core:player (WP-11) → lesson (WP-18), watch (WP-17)
  val state: StateFlow<ShadowingState>            // idle, or round k of n, phase LISTEN/YOUR_TURN, speed
  suspend fun run(span: ClipSpan, slowSpeed: Float, track: Track)
  fun stop()                                      // restores the previous speed
}
```

The composable surface (`PlayerSurface(feedPlayer, index, modifier)`) lives in `:core:player`'s `android` package
(architecture 3.4 note); `:feature:watch` depends on `:core:player` for it only.

Additive fields on `Request` (architecture 3.4), with defaults, so the request card and the request detail of design
9.19 can say what the spec asks (the reason a request waits, "Last known … (10 min ago)", the milestone times, the
automatic re-run) from columns the `request` table already has (architecture 4.2):

```kotlin
// Request(…, val waiting: WaitingInfo? = null, val timeline: RequestTimeline = RequestTimeline(),
//         val rerunPending: Boolean = false, val updatedAt: Instant? = null)   (architecture 3.4 lists them too)
data class WaitingInfo(val held: Boolean, val attempts: Int, val nextAttemptAt: Instant?,  // held: "Stop sending" (D-46)
  val needsAppOpen: Boolean = false)  // a file too large for one background run: "Open PhonoTrainer on your home
                                      // Wi-Fi to send this file: …" (architecture 8.5, spec D-41); false for links
data class RequestTimeline(val sentAt: Instant? = null, val inLineAt: Instant? = null, val analyzingAt: Instant? = null,
  val readyAt: Instant? = null, val onPhoneAt: Instant? = null)
// rerunPending: the automatic re-run after "interrupted" is queued ("Starting again on your computer", architecture 8.4)
```

### 4.3 `:core:network-api` (JVM module, frozen; implemented by WP-05b)

The computer-client API lives in its own JVM module (architecture 2.2), so `:core:testing-jvm` can implement it
without depending on `:core:network` (delivery review D-18). Request and response bodies are in Appendix A.6.

```kotlin
sealed interface ApiResult<out T> {
  data class Ok<T>(val value: T, val status: Int) : ApiResult<T>
  data class Refused(                              // server-contract 8.1; the optional fields of the error body (D-14)
    val status: Int, val code: String, val detail: String,
    val job: RemoteJob? = null,                    // already_finished (cancel), done (upload declaration)
    val offset: Long? = null,                      // offset_mismatch (complete)
    val serverTime: Long? = null,                  // clock_skew
    val retryAfterS: Int? = null,                  // 429 Retry-After
  ) : ApiResult<Nothing>
  data class Failed(val error: AppError.Computer) : ApiResult<Nothing>                            // no answer, guard, malformed
}
data class Created<T>(val value: T, val created: Boolean)
interface ComputerClient {                        // one method per row of server-contract 6.10
  suspend fun hello(at: HostPort, challengeFor: DeviceId? = null): ApiResult<ComputerIdentity>   // never sends a header; with challengeFor it sends a fresh challenge and checks the proof (architecture 5.3)
  suspend fun legacyHealth(at: HostPort): ApiResult<Int>                     // GET /api/health of a pre-6 server: its api_version (D-15)
  suspend fun pair(at: HostPort, request: PairRequest): ApiResult<PairGrant>
  suspend fun health(): ApiResult<ComputerHealth>                            // the current verified peer, signed
  suspend fun unpairSelf(): ApiResult<Unit>
  suspend fun library(): ApiResult<RemoteLibrary>
  suspend fun job(id: JobId): ApiResult<RemoteJob>
  suspend fun requestLookup(key: RequestId): ApiResult<RemoteJob>
  suspend fun reference(): ApiResult<String>                                 // raw JSON for ReferenceRepository.replace
  suspend fun createLink(body: LinkCreate): ApiResult<Created<RemoteJob>>
  suspend fun openUpload(body: UploadDeclaration): ApiResult<UploadState>
  suspend fun putUpload(key: RequestId, file: File, onProgress: (Long) -> Unit): ApiResult<Created<RemoteJob>>
  suspend fun uploadStatus(key: RequestId): ApiResult<UploadState>           // complete
  suspend fun patchUpload(key: RequestId, file: File, offset: Long, length: Long): ApiResult<UploadChunk>  // complete
  suspend fun deleteUpload(key: RequestId): ApiResult<Unit>
  suspend fun cancel(id: JobId): ApiResult<RemoteJob>
  suspend fun deleteJob(id: JobId): ApiResult<Unit>
  suspend fun rerun(id: JobId, body: RerunBody): ApiResult<Created<RemoteJob>>
  suspend fun requestPhoneVideo(id: JobId): ApiResult<RemoteArtifactStatus>  // complete
  suspend fun contour(id: JobId, startS: Double, endS: Double): ApiResult<String>                              // complete
  suspend fun compare(id: JobId, take: File, contentType: String, startS: Double, endS: Double): ApiResult<String> // complete
}
interface ArtifactTransfer {                      // architecture 7.4
  suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome
}
data class DownloadRequest(val url: String, val target: File, val resumeFrom: Long, val ifRange: String?,
  val expectedBytes: Long)                         // a part already this long is verified, never requested (A-M5)
sealed interface DownloadOutcome {
  data class Complete(val etag: String?, val bytes: Long) : DownloadOutcome
  data object RestartedFromZero : DownloadOutcome // 200 where 206 was expected
  data object RangeNotSatisfiable : DownloadOutcome // 416: verify the part, restart only if it does not match
  data class Failed(val error: AppError.Computer) : DownloadOutcome
}
interface JobChannel { fun open(): Flow<ChannelEvent> }  // completes or fails when the channel is lost (architecture 5.5)
interface AllowedPeers { val current: StateFlow<HostPort?>; fun setCurrent(address: HostPort?)
  suspend fun <T> withCandidate(address: HostPort, block: suspend () -> T): T }
interface VerifiedPeers { fun markVerified(address: HostPort, serverId: ServerId); fun isVerified(address: HostPort): Boolean; fun reset() }
interface AddressPolicy { fun allowsCandidate(host: String): Boolean }           // tests allow loopback (only in test sources, hygiene rule)
interface ClockOffset { val offsetS: StateFlow<Long>; fun update(serverTime: Long) } // learned from hello and clock_skew (architecture 5.3)
interface ComputerNetworkBinder { fun bind(networkHandle: Long?) }  // fed by :core:connectivity with Network.getNetworkHandle();
                                                 // :core:network rebuilds the Network with Network.fromNetworkHandle (API 28+),
                                                 // so the API stays JVM and no core module depends on :core:network
```

### 4.4 Extension points in `:core:ui` (`ui/extension/`, bound with `@BindsOptionalOf`)

| Interface | Bound by (v1-complete) | Used by (v1-core slot and fallback) |
|---|---|---|
| `MouthRenderer { @Composable fun Mouth(frame: MouthFrame, modifier: Modifier); fun track(phones: List<TimedPhone>, mode: MouthMode): MouthTrack }` | WP-29 `:core:articulator` | lesson step 4 (WP-18), Learn mouth demo and IPA symbol sheet (WP-20); absent: the name and "what the mouth does" sentence per sound |
| `RecordSection { @Composable fun Section(span: ClipSpan, target: RecordTarget, modifier: Modifier) }` | WP-30 (`lesson/record/`) | lesson step 5 (WP-18), the short's "More" sheet (WP-17); absent: shadowing only, no menu item |
| `PhraseDetailsEntry { fun available(): Boolean }` (the destination is `PhraseDetailsKey`) | WP-31 (`lesson/phrase/`) | "More" sheet (WP-17), transcript (WP-31); absent: no menu item |
| `ClipTranscriptTab { @Composable fun Tab(jobId: JobId, onOpenWord: (WordRef) -> Unit, onOpenShort: (Int) -> Unit) }` | WP-31 (`library/transcript/`) | Clip page (WP-16); absent: Shorts and Summary tabs only |
| `InsightsAvailability { val teaser: Flow<String?> }` | WP-32 `:feature:insights` | Home teaser (WP-15), Learn entry (WP-20); absent: no card, no entry |
| `ClipReductionMeters { @Composable fun Meters(jobId: JobId, modifier: Modifier) }` (new, architecture revision 2) | WP-32 | Clip page Summary (WP-16); absent: the block is not shown |

`TimedPhone`, `MouthMode`, `RecordTarget` are domain types; `MouthFrame` and `MouthTrack` (`fun frameAt(ms: Long):
MouthFrame`) live in `ui/extension/`.

**v1-core stubs bind nothing** (delivery review D-12). `:app` depends on every module from wave 1, so a stub module
that provided a binding would make the `Optional` present in v1-core and hide the fallbacks. The stubs of
`:core:articulator`, `:core:recorder`, `:feature:insights`, `lesson/record/`, `lesson/phrase/` and
`library/transcript/` therefore declare **no** binding for any extension point; only their v1-complete owners add one.
WP-19 keeps one GMD assertion per slot that the v1-core fallback renders: lesson step 4 shows the sound's name and
sentence, no "Record" section or menu item, no "Phrase details" item, no Transcript tab, no Insights card or entry,
no reduction meters.

### 4.5 Shared domain-aware components in `:core:ui` (`ui/component/`)

Used by more than one feature, so they cannot live in a feature module. Each has one owner (section 3) and its own
strings file (`strings_<component>.xml`, 3.2); everyone else uses the frozen API and the M2 placeholder until the
owner's version is merged.

| Component (API frozen at M2) | Owner | Used by |
|---|---|---|
| `ClipCard(state, onWatch, onOpen, onMore)`, `ClipRow(state, …)` | WP-16 | Home shelf (WP-15), Library (WP-16), feed mode picker (WP-17) |
| `RequestRow(state, onAction, onOpen)` | WP-22 | Library "In progress" (WP-16), Requests (WP-22), Home attention line (WP-15) |
| `RequestFailureCopy.of(error: RequestError, context: FailureContext): FailureCopy` and `RequestFailureCopy.notificationLine(error, site): String`, with `FailureContext(kind, site, available = Request.actions, maxLinkDurationS)` and `FailureCopy(title, body, actions)`; the actions are the spec 8.6 order intersected with `available` (architecture 19) | WP-22 | request rows (WP-22, WP-16), share sheet (WP-23), `Notifier` (WP-02, through `:core:notifications` → `:core:ui`) |
| `QuestionCard(state, onAnswer, onPlay, onSlow, onContext)`, `AnswerFeedback(state, onLesson, onLearn, onWatchMore, onSave)` | WP-21 | Practice (WP-21), set questions in the feed (WP-17) |
| `ShadowingPanel(state, onStart, onStop, options)` | WP-18 | lesson step 5 (WP-18), the rail's "Shadow" (WP-17) |
| `OccurrenceRow(state, onPlay, onOpenLesson)` | WP-20 | Learn and IPA sheet (WP-20), Saved (WP-21), Practice explanations (WP-21) |
| `ConnectionStatusCard(status, sync, counts, transfers, onAction)` (design 9.17: every `ComputerStatus`, `SyncState` and the request-count rows) and `ProbeOutcomeCopy.of(outcome: ProbeOutcome): String` (the last probe in words, spec 7.5; `PhoneAccessOff` reads "Your computer answered, but phone access is off there or not approved for this network.", D-42) | WP-15 | Home (WP-15), Connection details (WP-13), Settings > Your computer (WP-24) |
| `ShareBridge(state)` + `ConnectionLine(status)` (design 9.22: the phone → computer bridge and the connection line, mapped from `ComputerStatus`) | WP-23 | share sheet (WP-23), Add a clip (WP-22) |

Visual-only pieces several features draw (`StylusTrace`, `ShortProgress`, `PosterArt` with the poster fallback,
`MarksLegend`) are not domain-aware and are WP-03's (design 12.2); `AudioOnlyVisual`, which follows playback, stays in
`:feature:watch` (architecture 11.6).

### 4.6 Test support available at B1

In `:core:testing-jvm` (JVM), under `testing/fake/<area>/`, one settable fake per interface: content
(`DidacticContent`, `ReferenceRepository`), network (`AllowedPeers`, `VerifiedPeers`, `ArtifactTransfer`,
`JobChannel`, `ClockOffset`), connectivity (`ComputerStatusRepository`, `PairingRepository`, `ChannelLiveness`,
`NetworkMonitor`, `DiscoveryTransport`), sync (`SyncController`, `ClipRepository`, `StorageRepository`), requests
(`RequestRepository`, `RequestSender`, `RequestReconciler`), corpus (`ClipContentRepository`, `ShortRepository`,
`OccurrenceRepository`, `ClipIndexer`, `ClipLeases`), player (`SpanPlayer`, `FeedPlayer`, `ShadowingRunner`,
`AudioArbiter`, `DecoderCapabilities`), study (`ProgressRepository`, `SetRepository`, `PracticeRepository`,
`SavedRepository`, `StudyProtection`), app-level (`SettingsRepository`, `CoachMarkRepository`, `UserMessages`,
`Announcer`, `SyncTrigger`, `StartupGate`), plus `TestClock`, `MainDispatcherRule`, `AnalysisFixtures` (WP-12),
`FakeComputer` (WP-05a, already merged at B1 or merged right after) and `FakeComputerClient` (an in-memory
`ComputerClient`, WP-05a from B1).

In `:core:testing` (Android): `A11yAudit` (architecture 18), Robolectric and Compose rules, a fake `Notifier` (its
`transferNotification` returns an Android `Notification`), and `SyntheticMedia` (WP-11).

In `:app/src/androidTest` (integrator): the global test set of 2.4 (`e2e/di/`), `HiltTestRunner`, `TestSeeder`
(`e2e/support/`) and the façade `PhonoRobot`. There is no `FakeDomainModule`.

### 4.7 Change request CR-01: additive amendments of the frozen contract (revision 4)

The exact changes of 2.2's CR-01, normative like 4.2 and 4.3. Why each is shaped this way: architecture 3.4, 4.3, 4.4,
7.8, 8.8 and 25.5.

**CR-01-A1** (v1-core baseline, WP-19; `:core:database`, integrator-owned):

```kotlin
// learning/entity/LearningEntities.kt — LearningDatabase version 1 gains one entity (schema 1.json regenerated)
@Entity(tableName = "learning_meta")
data class LearningMetaEntity(@PrimaryKey @ColumnInfo(name = "key") val key: String,
  @ColumnInfo(name = "value") val value: String? = null)
// learning/LearningDatabase.kt — companion: val LEARNING_ID_CALLBACK: RoomDatabase.Callback, whose onCreate inserts
// ("learning_id", UUID.randomUUID().toString()) and whose onOpen runs the same insert with INSERT OR IGNORE.
// DatabaseModule's LearningDatabase builder and the end-to-end set's in-memory builder (app/src/androidTest
// e2e/di/TestModules.kt, integrator-owned) both add it. No DAO in v1-core; module DAO tests need not add it.
```
Proof (WP-19): a Room test that a new database has exactly one `learning_id`, unchanged after reopening; the exported
schema 1 regenerated and every DAO test re-run (architecture 4.4); the backup-rules test unchanged. Why now and not
with A2: a backup taken by a v1-core build and restored after v1-complete must name its first-share baseline with the
id the original phone used, or the computer would count that history twice (spec F-17-AC11); folding into version 1
is allowed only until the first install on the test phone (WP-25), so later would mean a migration for one row. WPs
that branched before it merge the baseline when told and change nothing (no B1 type changes).

**CR-01-A2** (first commit of wave 5, WP-26; every addition has a default, so B1-era implementations and fakes compile):

```kotlin
// :core:domain, model.remote — the two flags (server-contract 3.4); the pairing store keeps them (below)
data class Capabilities(/* …B1 fields… */, val deleteFinished: Boolean = false, val sharedProgress: Boolean = false)

// :core:domain, model — deletion from your computer (architecture 8.8)
enum class DeletionState { WAITING, SENDING, REFUSED }
// NOT_CONNECTED takes its words from ComputerStatus (7.5); DIFFERENT_COMPUTER: the last answer was 409
// different_computer, "A different computer answered" (spec D-62). A computer without the route refuses (REFUSED).
enum class DeletionWait { NOT_CONNECTED, DIFFERENT_LIBRARY, DIFFERENT_COMPUTER }
enum class DeletionAction { UNDO, TRY_AGAIN, KEEP_CLIP }
data class ComputerDeletion(val jobId: JobId, val title: String, val imported: Boolean, val state: DeletionState,
  val wait: DeletionWait?, val error: RequestError?, val createdAt: Instant,
  val waiting: WaitingInfo? = null,   // attempts and next attempt: the waiting words of a request (architecture 19)
  val actions: Set<DeletionAction> = emptySet())   // the one authority (architecture 8.8): no UNDO once maybe_sent
enum class NotOfferedReason { FORGOTTEN_COMPUTER, OTHER_LIBRARY, COMPUTER_CANNOT }
sealed interface DeletionOffer {
  data class Available(val imported: Boolean, val savedItems: Int, val connected: Boolean) : DeletionOffer
  data class InProgress(val deletion: ComputerDeletion) : DeletionOffer
  data class NotOffered(val reason: NotOfferedReason) : DeletionOffer
}
sealed interface DeleteResult { data object Stored : DeleteResult; data object NotOffered : DeleteResult
  data object OutOfSpace : DeleteResult }
// ClipSummary(…B1 fields…, val deletion: DeletionState? = null)        WAITING or SENDING: Library rows, Home shelf
// SendReport(…B1 fields…, val deletedOnComputer: Set<JobId> = emptySet())
// UserMessageKind gains CLIP_DELETED_FROM_COMPUTER, CLIP_REMOVED_FROM_COMPUTER_LIST, DELETION_UNDONE (args: the title);
// the integrator adds their strings to the shell's message host in the same commit (spec F-05 item 6), each title
// passed through QuotedTitle.of (below)

// :core:domain, model — shared progress (architecture 7.8, 10)
sealed interface SharingState {
  data object NotShared : SharingState                          // "Kept only on this phone."
  data class Shared(val lastSyncedAt: Instant?) : SharingState  // "Shared through your computer · last synced {time}"
  data object ResetWaiting : SharingState                       // "Reset on this phone. It reaches your computer …"
}

// :core:domain, model.share — the exchange bodies (server-contract 6.12, field by field; `at` = computer clock, unix s;
// ids = canonical UUID strings)
data class PracticeTally(val seen: Int, val correct: Int)
data class PracticeBaseline(val id: String, val at: Long, val stats: Map<ChangeLabel, PracticeTally>)
data class PracticeReset(val id: String, val at: Long)
data class PracticeAnswer(val id: String, val change: ChangeLabel, val correct: Boolean, val at: Long)
data class PracticeOutbox(val baseline: PracticeBaseline?, val reset: PracticeReset?, val answers: List<PracticeAnswer>)
// syncPractice sends `reset` in the signed query (reset_id, reset_at) and the rest as the body (server-contract 6.12)
data class PracticeShareAnswer(val serverTime: Long, val reset: PracticeReset?, val totals: Map<ChangeLabel, PracticeTally>)
data class SavedEntry(val kind: SavedKind, val jobId: JobId, val segment: Int, val word: Int?, val shortFirstSegment: Int,
  val change: ChangeLabel?, val saved: Boolean, val at: Long)
data class SavedOutbox(val cursor: String?, val changes: List<SavedEntry>)   // cursor: opaque, as last received
data class SavedShareAnswer(val libraryId: LibraryId, val cursor: String, val full: Boolean, val items: List<SavedEntry>,
  val dropped: List<SavedEntry>, val notShared: List<SavedEntry> = emptyList())

// :core:domain, api — methods with default bodies
interface RequestRepository {                         // + B1 members
  fun deletions(): Flow<List<ComputerDeletion>> = flowOf(emptyList())
  fun deletionOffer(id: JobId): Flow<DeletionOffer> = flowOf(DeletionOffer.NotOffered(NotOfferedReason.COMPUTER_CANNOT))
  suspend fun deleteFromComputer(id: JobId): DeleteResult = DeleteResult.NotOffered
  suspend fun performDeletion(id: JobId, action: DeletionAction) {}
}
interface PracticeRepository { fun sharing(): Flow<SharingState> = flowOf(SharingState.NotShared) }   // + B1 members
interface SavedRepository { fun sharing(): Flow<SharingState> = flowOf(SharingState.NotShared) }      // + B1 members
interface SyncController {                            // + B1 members; WP-28 A implements it (architecture 6.3)
  // Stops the running pass at its next step, waits for an outbox call on the wire and applies its answer, then runs
  // block while holding the orchestrator's mutex. PairingRepository.forget() runs its whole body inside it.
  suspend fun <T> exclusive(block: suspend () -> T): T = block()
}

// :core:domain, spi
interface PendingDeletions { val jobIds: StateFlow<Set<JobId>> }   // :core:requests → :core:sync, :core:study (8.8)
interface SharedProgressLedger {                                   // :core:study → :core:sync (architecture 7.8)
  val changed: Flow<Unit>
  suspend fun practiceOutbox(serverId: ServerId, offsetS: Long, limit: Int): PracticeOutbox
  suspend fun practiceApplied(serverId: ServerId, sent: PracticeOutbox, answer: PracticeShareAnswer)
  suspend fun savedOutbox(serverId: ServerId, libraryId: LibraryId, offsetS: Long, limit: Int): SavedOutbox
  suspend fun savedApplied(serverId: ServerId, libraryId: LibraryId, sent: SavedOutbox, answer: SavedShareAnswer)
  suspend fun practiceRefused(serverId: ServerId, sent: PracticeOutbox)    // a 400 or 413 marks the page handled
  suspend fun savedRefused(serverId: ServerId, libraryId: LibraryId, sent: SavedOutbox)   // (architecture 7.8)
}

// :core:network-api — ComputerClient gains three methods whose defaults answer like a server without the route;
// the implementation (WP-28 A) adds body_sha256 to each query before signing (architecture 5.4)
data class FinishedDeletion(val jobId: JobId, val alreadyGone: Boolean, val imported: Boolean?)
interface ComputerClient {                            // + B1 members
  suspend fun deleteFinished(id: JobId, serverId: ServerId, libraryId: LibraryId, key: RequestId): ApiResult<FinishedDeletion> =
    ApiResult.Refused(404, "unknown_route", "")
  suspend fun syncPractice(body: PracticeOutbox): ApiResult<PracticeShareAnswer> = ApiResult.Refused(404, "unknown_route", "")
  suspend fun syncSaved(libraryId: LibraryId, body: SavedOutbox): ApiResult<SavedShareAnswer> =
    ApiResult.Refused(404, "unknown_route", "")
}
```

`:core:ui` (integrator, in the same commit; architecture 19, design 9.26): the one function that makes the quoted
title, so the shell's host can use it here and WP-34 finds it when it branches:

```kotlin
// ui/component/QuotedTitle.kt
object QuotedTitle {
  // (1) Cf removed; Cc, Zl, Zp become a space; runs of white space become one; trimmed. (2) At most 60 grapheme
  // clusters (android.icu.text.BreakIterator.getCharacterInstance()); longer: the first 59, trimmed, + "…".
  // (3) androidx.core.text.BidiFormatter.getInstance(false).unicodeWrap(…), last. :core:ui's build file gains
  // implementation(libs.androidx.core.ktx): already an app library (:app, :core:notifications), so no new library.
  fun of(title: String): String
}
```

Room **version 2** (`:core:database`, integrator): `LibraryDatabase` and `LearningDatabase` declare
`autoMigrations = [AutoMigration(from = 1, to = 2)]` and export schema 2:
- `RequestOpEntity` gains nullable `server_id`, `library_id`, `device_id`, `title` (TEXT), `imported` and
  `maybe_sent` (INTEGER); the documented values gain kind `DELETE_FINISHED` and state `SENDING` (architecture 4.2);
- new entities `PracticeAnswerEntity` (`practice_answer`), `OwnStatEntity` (`own_stat`: `change` primary key, `seen`,
  `correct`), `ShareStateEntity` (`share_state`), `SavedChangeEntity` (`saved_change`) with the columns and index of
  architecture 4.3.

The pairing store (`:core:datastore`, `DataStorePairingStore`, WP-02 M3's file; the integrator's in phase complete):

```kotlin
val CAPS_DELETE_FINISHED = booleanPreferencesKey("caps_delete_finished")
val CAPS_SHARED_PROGRESS = booleanPreferencesKey("caps_shared_progress")
// readCapabilities(): deleteFinished = this[CAPS_DELETE_FINISHED] ?: false,
//   sharedProgress = this[CAPS_SHARED_PROGRESS] ?: false
// writeCapabilities(): this[CAPS_DELETE_FINISHED] = capabilities.deleteFinished; the same for sharedProgress
```
A value written by a v1-core build has neither key and reads both as `false` until the next `health` (architecture
4.5).

Also in A2: no Hilt binding is needed for the new SPIs until WP-28 binds them (nothing injects them before); the
settable fakes in `testing/fake/requests/`, `fake/study/`, `fake/sync/` and `fake/network/` gain members for the new
methods (a deletion list, an offer per clip, a sharing state, recorded calls), so WP-34 starts on its first day; the
`MigrationTestHelper` tests 1 → 2 with data for both databases. Proof: `./gradlew check` and the whole GMD suite at the
merge (2.1 point 5); the migration tests; the shell's message host shows the three new messages with quoted titles (a
screen test); `QuotedTitle` against design 9.26's three fixtures (a Robolectric unit test: the unclosed U+202E title
of more than 60 characters gives "Coffee shop small talk ylno enohp siht morf with the barist…"; the U+2028 and
line-feed title gives "Coffee shop small talk with the barista"; the right-to-left letters and digits keep their text
inside the wrapper `unicodeWrap` adds); a
pairing-store round trip with both flags true and both false, and a store written without the keys reading `false`;
`forget()` calls `SyncController.exclusive` exactly once around its body (a fake controller records it).

---------------------------------------------------------------------------------------------------------------

## 5. Wave 1: foundation

### WP-01 Server v1-core (Python)

- **Tier** core · **Wave** 1 · **Size** XL (15 d) · **Starts** day 0 · **Depends on** nothing · **Lane** none (Python).
- **Goal**: every v1-core server capability of server-contract, with pytest coverage, and the browser client unchanged.
- **Covers**: S-01, S-02, S-03, S-04, S-05, S-06, S-07, S-08, S-09, S-11, S-13, S-14 (channel, lean), S-15, S-17,
  S-21, S-22, S-23; the server side of F-02-AC3, F-02-AC7, F-02-AC8, QT-3, QT-4, QT-5, QT-8, QT-12, F-20-AC3 to AC5;
  X-AC9 (server part); the security review's server findings (H-1, H-2, M-1, M-5, M-6, M-9, L-1 to L-5, L-9, L-10).
- **Owns**: `phonotrainer/server.py`, `jobs.py`, `cli.py`, `download.py`, `phonotrainer/phone/**`, `tests/**`,
  `pyproject.toml`, `requirements.txt` (only if needed), the README sections "Phone access (Android client)" and
  "Material: from YouTube to analysis", notices section 3 (only if a package is added).
- **Must not touch**: `web/**`, `android/**`, every other `phonotrainer/` module, the rest of the README and notices.
- **Consumes**: nothing (it defines the contract).
- **Provides**: server-contract sections 2 to 9 as running code (API_VERSION 6); `create_app(phone=…)`,
  `PhoneSettings` and the `JobStore` seams as a stable entry point for the host-side fake computer (server-contract
  12); the reference vectors of 4.3 and 6.2 (signature and proof) in `tests/phone_fixtures.py`, which WP-05a and
  WP-05b copy into their JVM tests.
- **Milestones** (each merged on its own, each leaving the suite green):
  - **A (day 7)**: configuration directory with permission checks, identity, approved networks and suspension,
    registry with keys, re-activation proofs and identity proofs, `LoopbackHostGuard` and `PhoneAccessMiddleware`
    (pure ASGI), signed requests with nonces and the clock window, `hello`, `pair`, `health`, `devices/self`, the
    listing with checksums and media facts, artifacts, reference. This unblocks the host-side fake computer (WP-06)
    and the smoke tests.
  - **B (day 15)**: links with the SSRF guard (NAT64, 6to4, Teredo), uploads (single PUT, reservations, per-device
    limits, 30 s idle abort, "newest wins"), cancel, delete, re-run with the link re-check, request lookup, the phone
    channel with per-device limits, discovery inside approved networks, the `phone` CLI and the `ui` banner,
    S-23 with the confined phone-origin download wrapper and its new job codes, the README.
- **Proof**: the test modules of server-contract 11.3 marked core, including `test_host_fake_computer.py`, plus the
  whole existing suite unchanged.
- **Done when**: server-contract 11.4 items 1 to 5 at each milestone, and `check_ownership.py --wp WP-01` clean.

### WP-02 Foundation: skeleton, contract and shell (Android)

- **Tier** core · **Wave** 1 · **Size** L (10 d) · **Starts** day 0 · **Lane** B · **Hands the integrator role to
  WP-19 at B1** (delivery review D-05).
- **Goal**: an `android/` build that every other WP branches from and compiles against; the frozen contract; the shell.
- **Covers**: AD-01 to AD-10, AD-21; NFR-01; X-AC5 (`android/.gitignore`), X-AC8 (clean build), F-22 and F-01 storage
  (settings, coach marks), F-23 channels and permission state, spec 5.1 (every destination reachable, stubbed), 5.4
  (internal targets, alias), 11.1 backup rules, 11.4 manifest and permissions, 16.5 splash and edge-to-edge, 20.4
  logging facade.
- **Milestones**:
  - **M1 toolchain proof (day 2) = B0**: `settings.gradle.kts` declaring every module of architecture 2.2 (bodies may
    still be empty), wrapper 9.8.0 with checksum, `libs.versions.toml` with every v1 dependency (architecture 2.4,
    including the profile installer, Macrobenchmark and UiAutomator), `build-logic` with all ten convention plugins and the lint policy file `config/lint/lint.xml` (architecture 2.3),
    `:core:domain`, `:core:common`, `:core:network-api`, `:core:testing-jvm` (JVM) and `:app` with an empty
    `MainActivity`; the **dependency-shape spike**: `:core:network` tests using `:core:testing-jvm`, which implements a
    `:core:network-api` interface, resolve with no variant or task cycle (delivery review D-18); `./gradlew
    assembleDebug testDebugUnitTest lintDebug` green. Fallbacks: per-module build files from one template if the
    convention layer fights AGP 9 (architecture 2.3); if the spike fails, fakes depend on `:core:domain` only.
    B0 lets WP-05a and WP-12 compile their day-0 sources.
  - **M2 contract freeze (day 6) = B1**: every module of architecture 2.2 compiling with stub bindings (no binding
    for extension points, 4.4); section 4 of this plan and Appendix A transcribed, with WP-12's and WP-04's shapes;
    Room v1 schemas exported; DataStore stores; the frozen Hilt module and resource names (4.1); the navigation shell
    (bottom bar, per-tab back stacks decorated per stack as architecture 16.2, overlay scene strategy, internal alias)
    with every destination stubbed (title and lede); the app-wide host of `UserMessages` snackbars and the slot of
    the global "Connect again" banner (design 9.23), both in the shell; placeholder `PhonoTheme` applied at the root
    from `UserSettings.appearance` (on API 31+ the shell also calls `UiModeManager.setApplicationNightMode` for Light
    or Dark, design 12.4, through the helper WP-03 later fills); the base fakes of `:core:testing-jvm`;
    a GMD smoke test that opens every destination. The stubs of v1-complete destinations sit where their v1-complete
    owner replaces them (`PhraseDetailsKey`'s installer under `lesson/phrase/`, `InsightsKey`'s in
    `:feature:insights`, the `:core:articulator` and `:core:recorder` modules), so no v1-core WP touches them. At B1
    WP-19 becomes the integrator.
  - **M3 foundation complete (day 10)**: `:core:common` (RetrySchedule, streaming SHA-256, UUIDs, `PhonoLog` with
    lambda messages), `:core:datastore` (settings, coach marks, pairing store with the clock offset and the
    upload-throughput average, `KeystoreCredentialCipher`), `:core:notifications` (channels, `Notifier` using the
    placeholder of `RequestFailureCopy.notificationLine`, private lock-screen versions, unique `PendingIntent`s per
    target, permission state, `NotificationPermissionAsker`), the dependency guard (architecture 2.2 rules 1 to 5). The licence report, the `qa`
    build type, release signing, `Makefile`, `android/README.md`, `AGENTS.md`, `SECURITY.md` and notices section 7
    moved to WP-19.
- **Owns**: bootstrap until B1 (3.1); then `android/core/common/**` (except WP-12's sub-packages),
  `android/core/datastore/**`, `android/core/notifications/**`.
- **Must not touch** (after B1): files owned by other WPs, including the frozen contract (the integrator's); `web/**`;
  server files.
- **Consumes**: architecture; server-contract 6 (for the `:core:network-api` shapes); WP-12's `ClipData` family and
  WP-04's content model by day 4.
- **Provides**: B0, B1 (section 4, Appendix A), the M3 modules.
- **Proof**: M1 build and spike; JVM tests for RetrySchedule (every step of architecture 8.5), SHA-256 streaming, the
  credential cipher (Robolectric with a fake keystore), settings and coach-mark stores; Room `MigrationTestHelper`
  harness with the v1 schemas; the GMD navigation smoke (every key opens its stub, Back order of architecture 16.2,
  **per-tab state kept across tab switches**: open the Library stub's list, scroll and type a query, switch to Learn
  and back, same ViewModel instance and state; the same after `recreate()` and after a process-death restore; Android
  review H1); `PendingIntent` test (two targets give two distinct intents; a recreate after opening from a
  notification does not navigate again); manifest test (exported components and permissions equal `SECURITY.md`'s
  list, `ShareActivity` placeholder with `taskAffinity=""`); backup rules test (only `learning.db` and the settings
  file, `disableIfNoEncryptionCapabilities` set).
- **Done when**: 2.5 items 1 to 7 at M2 and M3; B0 and B1 published; `assembleRelease` green.

### WP-03 Design system and assets

- **Tier** core · **Wave** 1 → 2 · **Size** L (8 d) · **Lane** C (lane D when a fourth lane runs) · **Starts** when
  the design track has delivered `android/docs/design.md` and the staging export (the plan names the condition, not a
  date; delivery review D-26). The condition is met: `design.md` v1, the mockups in `android/docs/design/` and the
  staging export in `android/design-src/export/` were delivered on 2026-10-10. Conversion in
  `android/design-src/convert/`, module work after B1. Until then every WP uses WP-02's placeholder theme, and nothing
  in their code changes when the real values land (architecture 17).
- **Goal**: the real `PhonoTheme`, tokens, fonts, icons, illustrations and the visual primitives of `:core:ui`, built
  from the design track's output, behind the API frozen at B1 (architecture 17). It depends on the existence of
  `design.md` and the export, never on particular values: whatever roles and assets they define are mapped onto the
  API, and missing roles are added additively.
- **Covers**: F-25 (every asset of the table, AC1's automated part; no legacy launcher icons, D-50), A-01, A-02, A-10,
  A-15, F-22-AC1 (patterns follow the colors choice), spec 10's visual rules, the "not minimalist" acceptance list
  items the components carry; the bundled half of X-AC3 and F-24-AC2 (the fonts' and icons' licence texts and
  `bundled.json`, architecture 20.6).
- **Owns**: `android/core/designsystem/**`, `android/core/ui/**` except `extension/`, the shared components of 4.5 and
  their strings files, `android/design-src/convert/**`, notices section 7.3 ("Fonts and icons").
- **Must not touch**: `design.md`, `android/docs/design/**` and `design-src/export/` (the design track's), feature
  modules, `extension/`.
- **Consumes**: `design.md` (section 12 is the implementation map), `design-src/palette.json` (every colour; the
  source of `Palette.kt`), `design-src/icons.json` (the icon inventory behind `PhonoIcons`: 122 entries, Lucide
  1.51.0), `design-src/svg/` (the masters: 122 icons, 14 illustrations, 6 brand files), the design tools of design 13
  in `design-src/tools/` (`svg2vd.py`, `vd2svg.py`, `roundtrip.py`, `export_vectors.py`, `lint_vectors.py`,
  `check_palette.py`, `brand_export.py`, `lucide_icons.py`), which its conversion scripts call, and
  `design-src/export/`; the frozen `:core:ui` API and the frozen resource names (4.1). The export holds exactly these
  **173 files** (checked against the staging folder on 2026-10-10, after the design track's closure re-export; design
  12.3), and WP-03's conversion test fails if the export and the converted resources ever differ from this
  inventory:
  - `res/font/` (5): `atkinson_hyperlegible_next.ttf` (UI), `newsreader.ttf` and `newsreader_italic.ttf` (display),
    `charis_regular.ttf` and `charis_bold.ttf` (IPA), all OFL-1.1, unmodified upstream bytes;
  - `assets/licenses/` (5): `atkinson-hyperlegible-next-OFL.txt`, `newsreader-OFL.txt`, `charis-OFL.txt`,
    `lucide-LICENSE.txt` (ISC, with the MIT text of the Feather-derived glyphs) and the provenance manifest
    `FONTS.txt` (version, source, SHA-256 per font);
  - `res/drawable/` (142): the 122 `ic_*` icons of `icons.json` (one file per entry, none extra); the launcher layers
    `ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`; `ic_notification.xml`;
    the 14 illustrations `ill_feed_empty`, `ill_home_no_clips`, `ill_no_computer`, `ill_no_examples`,
    `ill_practice_empty`, `ill_requests_empty`, `ill_saved_empty` (empty states), `ill_welcome_listen`,
    `ill_welcome_clips`, `ill_welcome_daily` (Welcome; `ill_welcome_clips` doubles as Home first run),
    `ill_set_done`, `ill_spot_practice`, `ill_spot_insights`, `ill_poster_fallback` (the static form of design 7.4);
    `splash_mark.xml` and `splash_mark_animated.xml`;
  - `res/drawable-night/` (16): the dark twins of the 14 illustrations and of both splash drawables;
  - `res/mipmap-anydpi/` (2): `ic_launcher.xml`, `ic_launcher_round.xml` (adaptive icons with background, foreground
    and monochrome layers; no `-v26` qualifier, which minSdk 29 makes obsolete);
  - `res/values/` (2): `colors.xml` (`phono_page`, `phono_ink`, `phono_launcher_tile`) and `brand_aliases.xml` (the
    frozen names `@drawable/ic_stat_phono` → `ic_notification` and `@color/phono_window_background` → `phono_page`);
    `res/values-night/` (1): `colors.xml` (`phono_page`, `phono_ink`; the launcher tile is the same in both themes).
- **Provides**: `PhonoTheme(darkTheme, colors, patterns, reducedMotion)`, `PhonoTheme.colors` roles (family hues in
  both palettes, mark ink, scrims, status surfaces, neutral decorative tones), typography with the three bundled OFL
  faces (Atkinson Hyperlegible Next for the UI, Newsreader for the display voice, Charis 7.000 for IPA; unmodified TTF
  with licence texts in `assets/licenses/`), shapes, spacing, motion, the icon set (all of `icons.json`), launcher
  icon layers, splash, notification icon, every illustration of the export, the generated-visual palette inputs,
  `IpaText` (using `:core:common`'s `IpaTokenizer`); the themes `Theme.Phono`, `Theme.Phono.Starting` and
  `Theme.Phono.ShareSheet` (frozen names, 4.1; design 12.4); the dark-mode strategy of design 12.1 and 12.4 (Compose
  colours from `darkTheme`; a configuration context whose `uiMode` follows the in-app appearance so `drawable-night`
  illustrations match it; `values-night` window and splash colours) with a helper the shell calls to apply the in-app
  appearance to the window (WP-02, `:app`); and the additive `:core:ui` visuals design 12.2 defines (`StylusTrace`,
  `ShortProgress`, `PosterArt` with the poster fallback of design 7.4, `MarksLegend`). Every resource lives in
  `:core:designsystem` under the frozen names; WP-03 never edits `:app`. The launcher XMLs stay in `mipmap-anydpi/`
  (a `-v26` qualifier would fail lint's `ObsoleteSdkInt` under `warningsAsErrors`); no legacy raster mipmaps are made
  (every supported device draws the adaptive icon; spec F-25, D-50); every `pathData` is under lint's 3,000-character
  `VectorPath` limit (the re-export split the launcher background's halftone into four paths, seven paths in that
  file; the longest path in the export, in `ill_no_computer.xml`, counts 2,976 characters as lint counts them, every
  run of whitespace as one, and 2,732 without blanks), and every intrinsic size is within lint's 200 dp
  `VectorRaster` limit; WP-03's lint run and `tools/lint_vectors.py` keep both so (design 12.3). The licence texts go to `assets/licenses/` with **`bundled.json`**, generated by the conversion script from
  `FONTS.txt` and `icons.json` (architecture 20.6: one entry per font family and one for the Lucide set, with version,
  kind, files or count, SPDX licence, licence file, source and each font's SHA-256), which the integrator's licence
  check and the Licences screen (WP-24) read; and the rows of notices 7.3 "Fonts and icons" for the same entries.
- **Proof**: a JVM contrast test over every text/surface and mark/surface pair of both themes and both palettes (A-10:
  7:1 body, 4.5:1 secondary, 3:1 marks and focus; the gates G1 to G11 of design 3, ported from `check_palette.py`);
  Robolectric tests per primitive (labels, 48 dp, semantics, font scale 2.0, a visible focus indicator for keyboard
  focus, A-13); a resource test that the launcher icon has background, foreground and monochrome layers and the
  notification icon is single-colour; `lintDebug` clean on `:core:designsystem` (the `VectorPath` limit of 3,000
  characters per path: design 12.3); the inventory test above (173 staged files, every `icons.json` entry converted,
  nothing extra); `bundled.json` agrees with `FONTS.txt` (same SHA-256 as each `res/font/` file) and with
  `icons.json`'s version and count, and every licence file it names exists; previews of every component in light and
  dark.
- **Done when**: 2.5; the design track signs off the component previews (F-25-AC2 is checked later on screenshots by
  WP-25).

### WP-04 Didactic content and reference snapshot

- **Tier** core · **Wave** 1 · **Size** M (5 d) · **Starts** day 0 (extractor, Node); Kotlin after B1 (a two-day
  extra copy, 1.2).
- **Goal**: S-18 and architecture 15: the read-only extractor, the drift check, the bundled JSON, the reference
  snapshot and their Kotlin readers.
- **Covers**: S-18, D-09, F-14 content (AC2 offline, AC3 search), F-15 and F-16 text content (F-15-AC3 tip); the
  stepper teaching lines are **not** extracted (architecture revision 2).
- **Owns**: `android/tools/didactic/**`, `android/tools/reference/**`, `android/core/content/**`,
  `testing-jvm/.../fake/content/**`.
- **Must not touch**: anything under `web/` (the extractor refuses to write there and opens nothing there for writing).
- **Consumes**: `web/src/didactic/glossary/*`, `web/src/features/learn/content.ts`, `ipaLayout.ts`,
  `web/src/articulation/phones.ts`, `pose.ts`, `web/src/paths.ts` (read-only); the server's `GET /api/reference`
  through `tools/reference/export.py` (repository venv, `JobStore(<tmp>, corpus=None)`, never the default corpus
  database, architecture 15.4).
- **Provides**: **by day 4**, the `didactic.json` schema 1 and its Kotlin model (Appendix A.8) to WP-02 for B1;
  then `DidacticContent`, `ReferenceRepository`, `didactic.json`, `reference.json`,
  `./gradlew :core:content:verifyDidacticContent`.
- **Proof**: `extract.mjs --check` passes and fails on an injected difference (a test copies the inputs to a temporary
  directory and edits one string); the JVM decode test of the asset; glossary search finds "schwa"; the reference
  snapshot has 16 labels, 4 families and advice for every label; unknown `href` fails the extractor; `export.py`
  leaves no file outside its temporary directory.
- **Done when**: 2.5 including item 5.

### WP-05a Fake computer (JVM)

- **Tier** core (implements the complete-tier routes too) · **Wave** 1 · **Size** L (7 d) · **Starts** day 0
  (sources), compiles from B0 · **Lane** A (delivery review D-06).
- **Goal**: the stateful fake computer of architecture 21.3, written from server-contract alone, so the network client,
  connectivity, sync and requests are built and tested against the contract.
- **Covers**: AD-23; the fake side of every flow in QT-1 to QT-14, SH-AC5, F-21-AC3.
- **Owns**: `android/core/testing-jvm/src/main/kotlin/**/testing/computer/**` (and its tests).
- **Consumes**: server-contract 4.3 and 5 to 8 (its JSON is the contract); WP-01's reference vectors (signature,
  proof); `AnalysisFixtures` (WP-12) for clip data; from B1, the frozen `:core:network-api` types for
  `FakeComputerClient`.
- **Provides**: `FakeComputer` (MockWebServer dispatcher; every knob of architecture 21.3, including
  `impersonate(proof = MISSING | WRONG)`, `lanForbidden()`, `stallUpload` and `clockSkew`; signature and proof
  checking; uploads with the idle rules and PATCH; renditions and compare; a `maxLinkDurationS` capability and the
  `live_stream` and `too_long` job failures for SH-AC17, SH-AC18 and F-23-AC4); `FakeComputerClient` from B1.
- **Proof**: JVM tests per route and per error code of server-contract 6.10 and 8.1 with a raw OkHttp client and the
  reference signer; the reference vectors reproduced; `impersonate` answers discovery and `hello` with the paired
  server id and no proof or a wrong one (F-02-AC11); `lanForbidden` answers `403 lan_forbidden` on every phone route,
  `hello` included, and stays silent to discovery (F-02-AC12); `stallUpload` plus a second PUT after 10 s succeeds.
- **Done when**: 2.5 items 1, 2, 6, 7; WP-05b, WP-07, WP-08 and WP-09 confirm the fake covers their flows (their
  hand-off lists any gap).
- **Milestone 3 (v1-complete, CR-01; Size S, 2 d; lane A, first in wave 5; needs CR-01-A2)**: server-contract 6.11 and
  6.12 in `FakeComputer` (the same codes, `already_gone` (no memory of keys), the signed query, a phone's reset read
  only from the query, `body_sha256` checked on the three routes, the merge rules, tombstones, the opaque cursor and
  `full`, `dropped`, `not_shared`, the bounds of server-contract 4.7 refused per item, the deletion rate limit, and a
  knob that recreates the Saved list), `not_before` after `restart()` (server-contract 4.3) and the two flags at API 7;
  `FakeComputerClient` implements the three new `ComputerClient` methods; the knobs of architecture 21.3
  (`capabilities(…)`, `refuseDeletion` including `429`, `different_computer` and `unknown_route`, `applyThenDrop` on the
  deletion route, `importedJob`, `otherDevice(…)`, `practiceTotals()`, `savedEntries()`, `deletedJobs()`);
  `deleteJob(id)` and `changeLibraryId()` also drop the job's Saved entries and keep the practice history. **Proof**:
  JVM tests per route and code as for milestone 1, and the server-side merge cases of server-contract 11.3
  `test_progress.py` repeated on the fake (two devices add up, a resend counts once, a reset clears only earlier
  answers, the tie keeps the unsave, a deleted job's entries are never accepted again). **Done when**: 2.5 items 1, 2,
  6, 7 on `--phase complete`; WP-28 confirms the fake covers its flows.

### WP-06 Repository tooling: hygiene, hooks, CI, device-QA kit, host-side fake computer

- **Tier** core · **Wave** 1 · **Size** L (6 d) · **Starts** day 0 · **Lane** none (Python).
- **Goal**: the checks every definition of done runs, the hooks that run them before a commit, the CI workflow, the
  device-QA kit WP-25 uses, and the host-side fake computer every emulator step and smoke test needs.
- **Covers**: X-AC2, X-AC4, X-AC5 (checks), F-02-AC9 and SC-15 (the repository scans; delivery review D-21),
  architecture 20.7, 21.7, 22; NFR-14 and X-AC10 tooling.
- **Owns**: `android/scripts/**`, `.github/**`.
- **Must not touch**: Gradle files (the task names are fixed in architecture 22 and 2.5).
- **Provides**:
  - `scripts/hygiene/check_repo.py` (standard library only) with every rule of architecture 20.7: forbidden directory
    names; ignored sources; **language** qualifiers only (`values-night` passes); the non-English scan; addresses
    outside the documentation ranges, loopback, the wildcard, broadcast and 10.0.2.2; MAC addresses outside the
    documentation range; home paths (`/home/<name>/`, `/Users/<name>/`, not preceded by a path character); private
    keys, keystores, long tokens with the placeholder forms of server-contract 6.1 allowed; the credential shapes and
    the `482913` placeholder exemption; the `hygiene: test-vector` marker for the server's SSRF vectors;
    `fallbackToDestructiveMigration`; `android.util.Log`; OkHttp construction; a loopback `AddressPolicy` outside test
    sources; the tokens-only rule (`Color(0x` and `.sp` outside `:core:designsystem`, design 12.5); the merged
    release manifest against `SECURITY.md`; the **local denylist** computed at run time (host
    name, user name, home directory, git e-mail, `adb devices` serials, the host's IPv4 and MAC addresses), never
    stored; no video or post URL in `docs/qa-report.md`.
  - `scripts/hygiene/install-hooks.sh`: `pre-commit` (staged files) and `pre-push` (pushed range) hooks running
    `check_repo.py`.
  - `scripts/hygiene/check_ownership.py` exactly as 3.1 describes (git diff against a baseline commit, section rows,
    the boundary rule), with `--self-check`.
  - `scripts/device-qa/` as architecture 21.7: `_device.py`, `ui.py`, `shot.py`, `a11y.py`, `db.py`,
    `settings_guard.py` (TalkBack component read from the device), `host_computer.py` (after WP-01 milestone A),
    `computer.py` (throwaway working directory too), `gmd_lock.py`, `decoders.py`, `torture.py` (with the platform
    stop switches), `capture.py`, `qa_log.py` (credential shapes, local denylist).
  - **Emulator image spike** (delivery review D-10): with the orchestrator's approval, install
    `system-images;android-36;google_apis;x86_64` and prove, in a ten-minute script, Wi-Fi transport reported,
    `svc wifi disable/enable`, airplane mode, reboot, clock change and screenshots; record the result (and the
    fallback image if it fails) in the hand-off.
  - `.github/workflows/android.yml` as architecture 22 (wrapper validation, the CI GPU override).
- **Proof**: `unittest` suites (standard library) for every script: a temporary git repository with planted violations
  for each rule of `check_repo.py` (including `values-night` passing, a planted `pt1_…` token, a planted MAC, a planted
  host name taken from the denylist); a fixture table and diffs for `check_ownership.py`, including section
  ownership, the boundary rule, phases and precedence; `qa_log.py` redaction of a pairing exchange; the hooks
  installed into a temporary repository block a planted violation; `host_computer.py` started on a temporary
  directory leaves nothing in the directory it was started from and refuses a link download; a test that checks, with
  regular expressions, that every action of the workflow is pinned to a full commit SHA, that `permissions` is
  `contents: read` and that the paths filters equal architecture 22.
- **Done when**: the scripts pass their tests; `check_repo.py` is clean on B1; `check_ownership.py --self-check`
  passes on this document.
- **Milestone 3 (v1-complete, CR-01; Size S, 1 d; Python, no lane; after WP-27 milestone A is merged)**:
  `host_computer.py` seeds, beside its clip created by path, a second finished clip created the same way and an imported
  one (`JobStore.import_dir` on a synthetic result folder in its temporary directory, outside the workspace;
  server-contract 12), can pair a second signer to act as another device, and its `--self-check` adds a deletion of the
  second clip and of the imported one (6.11; the imported folder stays), a practice exchange from two signers and a
  Saved change (6.12); `host_computer.py` and `computer.py` record the server id they created in the session's QA log;
  `ui.py` gains `delete_from_computer` and `reset_everywhere`, which read the paired server id from Connection details
  and stop before any tap unless it is one of those (spec D-57, architecture 21.7), and its generic tap applies the same
  check to the destructive labels of 21.7 (with or without the trailing "…", and "Forget it" only on the everywhere
  dialog); `torture.py` gains the [emulator] steps of QT-15 (reboot with two waiting deletions and a link) and QT-17
  (the host fake computer restarted on another workspace); `qa_log.py` records, per change, the practice answers QA
  gave on the test phone (from WP-25's logged sessions on) and each answer and reset the simulated device sends, with
  its server id; `ui.py` gains `practice_totals()`, which reads the Practice screen's per-change "x of y" lines and
  fails instead of guessing when one cannot be read, for the first-share check of architecture 21.7 (spec D-57 (3)).
  **Proof**: the scripts' `unittest` suites with the new cases (each helper refuses with a foreign server id and acts
  with the session's own, on `fakedevice.py`; the generic tap refuses "Delete from your computer", "Delete from your
  computer…", "Remove from your computer's list…" and "Forget it" on the everywhere dialog with a foreign one, and
  taps "Forget it" on the phone-only dialog; `practice_totals()` on a recorded layout tree, and an error on a tree with
  an unreadable line); `test_host_fake_computer.py` green on the merged tree. **Done when**:
  as above, on `--phase complete`.

### WP-12 Analysis core (pure JVM)

- **Tier** core · **Wave** 1 · **Size** L (8 d) · **Starts** day 0 (shapes first), compiles from B0 · **Lane** C
  (delivery review D-02).
- **Goal**: every pure clip derivation of architecture 9, the link rules of 8.2, the IPA tokenizer and the shadowing
  plan, as JVM functions with golden and property tests, and the one Kotlin port of the server's `mk_analysis`.
- **Covers**: F-06 item 1 and F-06-AC1, F-06-AC2 (shorts v1), F-07 item 3, F-07-AC2 and F-07-AC8 (spotlight rules),
  NFR-08 and NFR-02's parse budget (tolerant parser), D-16 (occurrences, `pickExamples`), spec 8.2 and SH-AC2
  (links), lesson step 2 compare rows (F-10-AC1, AC2 data), F-12-AC1 and AC2 (`ShadowingPlan`, a port of
  `web/src/features/analysis/lib/shadowing.ts` with its cases).
- **Owns**: `android/core/analysis/**`, `android/core/common/.../common/ipa/**`, `android/core/common/.../common/shadowing/**`,
  `android/core/testing-jvm/.../testing/fixtures/**`.
- **Consumes**: `:core:domain` types; the reference snapshot shape (from WP-04, via domain types).
- **Provides**: **by day 4**, the `ClipData` family (Appendix A.3, refined additively from `mk_analysis` and the
  web's readers) and the `AnalysisParser` signature, to WP-02 for B1; then `AnalysisParser`, `ShortsV1`
  (`RULE_VERSION = 1`), `Spotlight`, `OccurrenceExtractor`, `ExamplePicker`, `CompareRows`, `LinksV1` (implements
  `LinkParser`; bound by WP-09), `IpaTokenizer` and `ShadowingPlan` (in `:core:common`), `AnalysisFixtures` (in
  `:core:testing-jvm`; the only port of `mk_analysis`, used by WP-05a, WP-10, WP-14 and everyone else; delivery review
  D-17).
- **Proof**: the F-06-AC1 golden case; a property test over synthetic phrase timings (no short over 30 s unless one
  phrase is, no phrase split, every phrase once, deterministic); spotlight order cases including saved and change
  modes and boundary changes; parser tests on `AnalysisFixtures` analyses in every variant (old keys missing, espeak
  engine, rules 1–3, unknown enum values, malformed → `ContentError.Corrupt`); a parse-time test on a synthetic
  1,500-word, 230-phrase analysis; link cases of 8.2 (every YouTube and X form, tracking parameters, `t.co`, 2,048
  limit, user-info, no URL); `IpaTokenizer` against `reference.ipa_tokens`; the shadowing cases ported from the web.
- **Done when**: 2.5 items 1, 2, 6, 7.

---------------------------------------------------------------------------------------------------------------

## 6. Wave 2: cores and features (from B1, in lanes)

Each WP below starts from the newest baseline when its lane reaches it (1.2), uses the fakes of 4.6 for everything it
does not own, and is merged when done.

**Must not touch (every wave-2 WP)**: anything outside its rows of section 3, in particular the frozen contract
(`core/domain`, `core/network-api`, `core/navigation`, `core/ui/.../extension`, Room entities and the two
`@Database` classes), another WP's fakes, DAOs or shared components, `:app` (except its own `e2e/<area>/`), the version
catalog and every build file, and `web/`. A feature WP never depends on another feature or on a core implementation
module (the dependency guard fails the build); a core WP uses another WP's work only through the frozen interfaces
(the `:core:domain` API and SPIs, `:core:network-api`), never through its implementation classes.

**GMD area tests (every feature WP)**: on the global test set of 2.4 with real repositories: the WP's destinations
open, Back order holds, and the states its baseline's repositories produce render; plus its happy path seeded through
`TestSeeder` when the cores it reads are merged in its baseline, otherwise robot methods for WP-19's flows. Run under
the GMD lock, filtered to the area. State-by-state coverage is in the Robolectric screen tests.

### WP-05b Network client

- **Tier** core · **Size** L (7 d) · **Lane** A · **Needs** B1 and WP-05a's `FakeComputer` (merged at about day 7).
- **Goal**: the one HTTP stack of architecture 5: binding, guard, signing, proofs, client, transfers, channel.
- **Covers**: AD-11, AD-12, X-AC6 (cleartext confinement), NFR-05 (bound to Wi-Fi, no DNS), architecture 5.1 to 5.5;
  security review H-1 (client side), M-1, M-3, M-7.
- **Owns**: `android/core/network/**`, `testing-jvm/.../fake/network/**`.
- **Must not touch**: `core/network-api/` (frozen), other cores.
- **Consumes**: `:core:network-api`; `PairingStore` and the credential cipher (WP-02); `FakeComputer` (WP-05a).
- **Provides**: implementations of `ComputerClient` (including `legacyHealth` and `hello` with challenges),
  `ArtifactTransfer`, `JobChannel`, `AllowedPeers`, `VerifiedPeers`, `AddressPolicy`, `ClockOffset`,
  `ComputerNetworkBinder` (delegating per-socket factory, pool eviction, `VpnBlocks`); `CleartextGuard`,
  `SigningInterceptor` (signature only for a verified peer); DTOs with validation, size caps and lenient enums;
  the debug logging interceptor at `HEADERS`; `NetworkPolicyModule` (frozen name).
- **Proof**: JVM/Robolectric tests: the guard refuses every request except the current or candidate private address
  before a byte is sent (X-AC6); no DNS lookup ever; no `Authorization` header on `hello` or on any request to an
  unverified peer; an `impersonate()` responder never receives a signed request; a wrong or missing proof leaves the
  peer unverified; the reference signature and proof vectors (from WP-01's fixtures) reproduced; `clock_skew` updates
  the offset and retries once; DTO decoding ignores unknown fields and maps unknown enum values to `UNKNOWN`; hostile
  listings (`../` job ids, unknown artifact names, bad hex, a foreign `url`, an oversized body) are `Malformed` (M-3);
  error envelopes (string `detail`, 422 lists, `job`, `offset`, `server_time`, `Retry-After`) map to
  `ApiResult.Refused`; Range resume, If-Range mismatch → `RestartedFromZero`, a complete part is verified without a
  request, 416 → `RangeNotSatisfiable`; a socket opened after a network change is bound to the new network and the
  pool is evicted (fake binder); the WebSocket channel fails within 14 s of missing pongs (short intervals in the
  test); the debug logger never prints a pairing code or token (M-7); a conformance test per route and per code of
  server-contract 6.10 and 8.1 runs `ComputerClient` against `FakeComputer`.
- **Done when**: 2.5.

### WP-07 Connectivity, discovery and pairing

- **Tier** core · **Size** L (7 d) · **Lane** A · **Needs** WP-05b (merged at about day 14; `FakeComputerClient`
  covers anything it needs before).
- **Goal**: architecture 6: the probe state machine, discovery, pairing, re-discovery, background-restriction state.
- **Covers**: spec 7.3, 7.4, 7.5; SC-10; F-02-AC1, AC2, AC4 to AC8, AC10 to AC12 (logic); F-22-AC4 (data: this phone's
  address); F-03-AC2, AC3 (status logic); QT-8 and QT-11 (status side); F-21-AC5
  (status side); NFR-04 (no background discovery); the background-limited state; security review H-1 (conflicts),
  M-2 (no preselection on conflict), L-1 (re-activation proof); spec D-37, D-39, D-42.
- **Owns**: `android/core/connectivity/**`, `testing-jvm/.../fake/connectivity/**`.
- **Consumes**: `ComputerClient`, `AllowedPeers`, `VerifiedPeers`, `ClockOffset`, `ComputerNetworkBinder`
  (`:core:network-api`, WP-05b); `PairingStore` (WP-02); `SyncTrigger`, `Clock`.
- **Provides**: `ComputerStatusRepository` (with `ConnectionDiagnostics.phoneAddress` from the bound network's
  `LinkProperties`), `PairingRepository` (with `SearchState.Found.conflict`), `ChannelLiveness`; the `NetworkMonitor`
  and `DiscoveryTransport` implementations and `ConnectivitySeamsModule` (frozen name), replaced in end-to-end tests.
- **Proof**: the pure reducer on the JVM with a test clock (every row of spec 7.5, the 5 s re-probe, SC-10 bounds, the
  10 s timeout while analyzing, announcements only on allowed transitions, `DifferentComputer` on a missing or wrong
  proof with no signed call, request or file sent there and nothing deleted (F-02-AC11, against
  `impersonate(MISSING)` and `impersonate(WRONG)`), `ConnectAgain` on a revoked proof, `phone_access: false` and
  `lanForbidden()` → `NotReachable` with `ProbeOutcome.PhoneAccessOff` (F-02-AC12), `VpnBlocks`);
  `DiscoveryClient` against responders on the loopback interface (nonce matching, one entry per server id and source,
  two sources with one name, and one server id from two addresses → `conflict = true` and no preselection
  (F-02-AC10), the 0/1/3 s schedule, 10 s end); `phoneAddress` follows the bound network and is absent off Wi-Fi;
  pairing flows against `FakeComputer` (wrong, expired, locked codes, rate limit with `Retry-After`,
  `previous_device_id` with its proof on "Connect again", an undecryptable old key pairs as new, different server id,
  phone access off, too old through `legacyHealth`); `useNewAddress` accepted only with a valid proof for the paired
  id; "Forget" sends the unpair only when connected and cancels the unique works (fake WorkManager);
  background-restriction mapping.
- **Done when**: 2.5 (no GMD needed unless a worker or DI binding changes).

### WP-08 Sync engine, downloads and storage

- **Tier** core · **Size** XL (12 d) · **Lane** A · **Needs** WP-05b and WP-07 merged; the SPIs of WP-09, WP-10,
  WP-11 and WP-14 through their fakes until those merge (their real wiring is proven by WP-19's flows).
- **Goal**: architecture 7: the orchestrator, triggers and workers, catalogue reconcile, verified resumable downloads,
  budget and eviction, deletion semantics, the start-up check, the foreground session and the job channel.
- **Covers**: F-21-AC1 to AC8; spec 7.6, 7.8, 7.9, 7.10, 7.11 (data); QT-9, QT-10, QT-11, QT-14; SC-07, SC-11;
  F-04-AC2, F-05-AC4 (wanted); F-03 loading and storage lines (data); NFR-03, NFR-04; Android review M1, M5, M6, L3,
  L5.
- **Owns**: `android/core/sync/**` (including its `src/androidTest`), the DAOs `ClipDao`, `ArtifactDao`,
  `SyncMetaDao`, `testing-jvm/.../fake/sync/**`, and the WorkManager foreground-service declaration in `:core:sync`'s
  manifest.
- **Consumes**: `ComputerClient`, `ArtifactTransfer`, `JobChannel` (`:core:network-api`); `ComputerStatusRepository`,
  `ChannelLiveness` (WP-07); `RequestSender`, `RequestReconciler`, `StartupTask` set (WP-09); `ClipIndexer`,
  `ClipLeases`, `ClipRemovalHook` set (WP-10, WP-14); `StudyProtection` (WP-14); `ReferenceRepository` (WP-04);
  `Notifier` (WP-02); `DecoderCapabilities` (WP-11).
- **Provides**: `SyncController`, `SyncTrigger` (real), `ClipRepository`, `StorageRepository`, `StartupCheck` and
  `StartupGate`, `ForegroundSession`, `DownloadWorker`, `SyncWorker` (periodic, on-Wi-Fi with backoff, `send-now`).
- **Proof**: Robolectric with `FakeComputer`: pass order (operations, links, listing, reference, index, transfers,
  budget); reconcile of revisions, library-id change (QT-14), other server id ignored (QT-11), `own: false` jobs only
  in "Started on your computer"; two consecutive missing listings delete, one does not; mass-deletion guard; a deletion
  deferred while a lease is open (QT-10); downloads with `truncate` (resume from ≥ 70 %, QT-9), a complete part and a
  416 verified without restarting, `corrupt` (two failures → failed, old file kept); a clip is "On this phone" only
  after verification (SC-11); download order; video choice with a fake `DecoderCapabilities`; eviction order and
  protections (F-21-AC4); free-space floor with `getAllocatableBytes`; `StartupCheck` leaves rows and files newer than
  the process alone (a share during start-up keeps its copy); WorkManager `TestDriver` tests: an unreachable foreign
  Wi-Fi gives one `sync-on-wifi` run per backoff step and never a zero-delay loop; periodic work exists only while
  paired; workers with `TestListenableWorkerBuilder` (F-21-AC8), stop reasons logged; the foreground transfer falling
  back when not allowed. GMD in `:core:sync` (architecture 21.1): the download worker as a user-visible transfer
  resumes after a stop.
- **Done when**: 2.5 including `:core:sync`'s GMD tests.

### WP-09 Device queue, file intake and uploads

- **Tier** core · **Size** XL (12 d) · **Lane** B · **Needs** WP-05b by its day 4 (until then `FakeComputerClient`);
  WP-12's `LinksV1` (merged at about day 8).
- **Goal**: architecture 8: local-first requests, links and duplicates, file copies with audio extraction (shared
  items through expedited work), the state machine, sending with idempotency keys and the background size rule, the
  outbox, re-runs, reconciliation and notifications of own requests.
- **Covers**: spec 7.7, 8.2 to 8.6; QT-1 to QT-8, QT-12, QT-13 (logic); SC-04, SC-05, SC-14; F-19-AC1 to AC4 (data),
  F-20-AC1, AC3 to AC12 (data); SH-AC2 to SH-AC8, SH-AC10, SH-AC12 to SH-AC15, SH-AC17, SH-AC18 (data); F-23-AC2,
  AC3, AC4 (posted once); F-03-AC4, AC9 (counts); Android review H2a, H4, L8, L10; security review M-4; spec D-41,
  D-46, D-49 (`Request.actions`, the "Try audio only" transitions, `needsAppOpen`, the held state).
- **Owns**: `android/core/requests/**` (including its `src/androidTest`), the DAOs `RequestDao`, `RequestOpDao`,
  `testing-jvm/.../fake/requests/**`.
- **Consumes**: `ComputerClient` (`:core:network-api`); `ComputerStatusRepository` (WP-07); `SyncTrigger`,
  `StartupGate`; `LinkParser` (WP-12); `Notifier`, `NotificationPermissionAsker`, `RetrySchedule`, `PairingStore`
  (the upload-throughput average, architecture 4.5) (WP-02);
  `SyntheticMedia` (WP-11, for its GMD tests; until merged, a local builder in its own test sources).
- **Provides**: `RequestRepository`, `RequestSender`, `RequestReconciler`, its `StartupTask` (copy checks), the
  `LinkParser` binding, `FileIntake`, `IntakeHandles` (with the re-openers held through `RequestRepository.holdSources`
  and `readable`, architecture 8.3),
  `ShareIntakeWorker` (expedited), `FileIntakeWorker`, `UploadWorker` (with the background size rule and the
  throughput average); `Request.actions` (the one authority, architecture 8.4), `WaitingInfo.needsAppOpen` (computed
  by the uploader's own rule), the phone-side copy codes and `RequestError.neededBytes`.
- **Proof**: the state-machine table of architecture 8.4 row by row (JVM over a pure transition function plus Room
  tests); idempotency with `applyThenDrop` (one job, QT-3, SH-AC5); outbox before links (QT-7, F-20-AC5 "too late");
  removal while offline never sent (QT-6); `credential_rejected` pauses without failing anything (QT-8); automatic
  re-run once after `interrupted`, in acceptance order (QT-5, QT-12); duplicate detection; file copy with SHA-256 to
  `source.<ext>` whatever the display name (a hostile `../../databases/library.db` name), byte counting against a
  provider that lies about its size, own-authority URIs refused, `ClipData` items of `SEND_MULTIPLE` read, no-space,
  unreadable, missing copy at start (F-20-AC9), interrupted shared copy → `COPY_FAILED` (QT-13 logic); picked-file
  copy restarts (F-19-AC4 logic); upload restart from zero with the same key, `stallUpload` then `upload_busy` retried
  until "newest wins" (QT-4 core); the background size rule (a file above `throughput × 360 s` is not started in the
  background, is started by the foreground transfer, and nothing is marked failed); `getForegroundInfo` at sdk 30;
  notifications only for own requests and once. GMD in `:core:requests`: audio extraction with `SyntheticMedia`
  (AAC → m4a, whole-file fallback); the expedited shared-file copy continues while the app is backgrounded (the
  activity finishes, Home is pressed, the copy completes), and a stopped worker on a non-seekable source ends in
  `COPY_FAILED`. Revision 3 additions: `COPY_FAILED` `copy_no_space` offers `TRY_AUDIO_ONLY` for a video while its
  sources are held (`holdSources`) or `source_persisted`, which returns the same request to `PREPARING` for the audio
  track, and loses it the moment the handle is closed (the row then offers `REMOVE` only, D-49); a picked file's
  persisted permission is kept while `copy_no_space` and released on Remove; a video refused at sending as
  `too_large` goes back to `PREPARING` on "Try audio only" with no re-run sent; a file's re-run never sends
  `audio_only`, and a file's job `no_space` offers `TRY_AGAIN`, a link's `TRY_AUDIO_ONLY`; a table test of
  `Request.actions` against every row of spec 8.6 and F-20's actions table for both kinds; a 600 MB file with no
  measured throughput is not sent by a background pass while the links behind it are, reads `needsAppOpen = true`,
  and is sent as a visible transfer when the app opens (F-20-AC10); "Stop sending" holds the request and it is not
  sent again while connected until "Send now" (F-20-AC11); "Undo" of a pending cancel restores the previous state,
  posts `CANCEL_UNDONE` with the title once, and no cancel reaches the fake computer (F-20-AC12); a `too_long` failure
  posts one "Needs attention" notification (F-23-AC4, with WP-22's line).
- **Done when**: 2.5 including `:core:requests`'s GMD tests.

### WP-10 Corpus: indexer and clip content

- **Tier** core · **Size** M (4 d) · **Lane** C · **Needs** WP-12 merged (its tests run the real derivations).
- **Goal**: architecture 9.5: the indexer, the parsed-clip cache, media resolution, leases, compare rows for the
  lesson and the queries behind shorts, change modes, examples, quiz pools and variants.
- **Covers**: F-06 item 2 (eligibility), F-06-AC5, F-03-AC7 and F-15-AC1 (the two count units of spec D-48, and
  examples), F-16-AC3, F-10-AC1 and AC2 (compare rows reach the lesson), D-16, NFR-02 (prepare the largest analysis
  ≤ 1.5 s off the UI thread).
- **Owns**: `android/core/corpus/**`, the DAOs `ShortDao`, `OccurrenceDao`, `testing-jvm/.../fake/corpus/**`.
- **Consumes**: `:core:analysis` (WP-12), `ReferenceRepository` (WP-04), the clip and artifact rows (read-only through
  its own queries on WP-08's tables via `LibraryDatabase`).
- **Provides**: `ClipContentRepository` (including `compareRows(word)`, delivery review D-07), `ShortRepository`
  (In order, change and Saved sequences; Mix sequences come from `SetRepository`), `OccurrenceRepository`,
  `ClipIndexer`, `ClipLeases`, a `ClipRemovalHook`.
- **Proof**: Room tests of index replacement in one transaction, rule-version re-index, `changeCounts()` in both
  units over playable clips only (12 eligible flapping occurrences in 9 shorts give `ChangeCount(shorts = 9,
  occurrences = 12)`, F-15-AC1; for every label `shorts` equals the length of `sequence(FeedMode.Change(label))`,
  F-03-AC7; a clip whose study pack is missing counts in neither), `examples` (one per word, clearest first, limit
  8), symbol examples, quiz pools, variants, compare rows equal to WP-12's function; LRU of three; a lease defers
  removal.
- **Done when**: 2.5.

### WP-11 Player

- **Tier** core · **Size** L (10 d) · **Lane** C · **Needs** WP-12 (`ShadowingPlan` in `:core:common`).
- **Goal**: architecture 11: merged audio-clock playback with the explicit audio override, the pool of three with
  decoder release, span playback, audio arbitration and focus (ducks never pause), shadowing runner, decoder
  capabilities; `SyntheticMedia`.
- **Covers**: F-06 items 6 and 8; SC-08, SC-09; NFR-09; F-06-AC6, AC7, AC8, AC13 (player side); F-07-AC3, AC4, AC5,
  AC7 (player side); F-12-AC3, AC4; D-28; NFR-14 data (decoder list); Android review M2, M3, M4, L2.
- **Owns**: `android/core/player/**` (including its `src/androidTest`), `testing-jvm/.../fake/player/**`,
  `android/core/testing/.../testing/synthetic/**`.
- **Consumes**: `ClipMedia` and `ClipSpan` types; `ShadowingPlan` (WP-12, `:core:common`); `Announcer`.
- **Provides**: `FeedPlayer`, `SpanPlayer`, `ShadowingRunner`, `AudioArbiter`, `DecoderCapabilities` (with
  `summary()`), `PlayerSurface`; `SyntheticMedia` (instrumented AAC/H.264 builders, including a video whose own audio
  is a different tone and a video with a start offset).
- **Proof**: GMD instrumented tests in `:core:player` with `SyntheticMedia`: merged playback starts at the clip
  offset, the WAV (not the video's own audio) is the selected audio track, video and audio stay within 60 ms (else the
  follower fallback is enabled, architecture 24); swipe-to-sound timing on the pool, including a jump to a short whose
  start is far from a keyframe; the pool releases its codecs on `ON_STOP` and when told Watch is hidden, and plays
  again on return; the poster shows until the first frame; speed keeps pitch; track switch keeps the position; loop
  modes; becoming noisy pauses; an unsupported video falls back to audio; the track fallback of architecture 11.1
  (dialogue chosen and absent → the mix plays with `trackFallback = DIALOGUE_NOT_ON_PHONE`; the reverse
  `MIX_NOT_ON_PHONE`; no dialogue track `NO_DIALOGUE_TRACK`; a `setQueue` with fresh media after the dialogue arrived
  plays it from the next short with `trackFallback = null`, F-06-AC13 player side). Robolectric: the arbiter per focus-change
  code (`LOSS` and `LOSS_TRANSIENT` pause with no resume, `CAN_DUCK` lowers and restores the volume, never pauses);
  the shadowing runner (8 phases, speed restored on stop); `DecoderCapabilities` with fake codec lists (size and rate,
  rotated sizes, hardware preferred). `DefaultPreloadManager` evaluated; `dumpsys meminfo` and the decoder count while
  swiping on the emulator recorded; the decision recorded in `decisions.md` through a change request.
- **Done when**: 2.5 including `:core:player`'s GMD tests.

### WP-14 Study logic

- **Tier** core · **Size** L (8 d) · **Lane** B · **Needs** WP-10 merged.
- **Goal**: architecture 10: seen state, positions, sets, questions (the `quiz.ts` port), practice stats, saved items.
- **Covers**: F-06 item 5, F-06-AC4, AC10, AC11, AC12; F-08-AC1 to AC6 (logic); F-09-AC1 to AC3; F-17-AC1 to AC5
  (logic); F-03-AC6 (keep watching); F-03-AC10 (data: today's set in order with each short's spotlight, saved tag and
  `done` mark, spec D-45); changes met.
- **Owns**: `android/core/study/**`, `learning/dao/**`, `testing-jvm/.../fake/study/**`.
- **Consumes**: `ShortRepository`, `OccurrenceRepository` (WP-10); `Clock`; `SettingsRepository`; `AnalysisFixtures`.
- **Provides**: `ProgressRepository`, `SetRepository` (`today()` gives the set's items in set order, each short with
  its `spotlight`, `savedTag` and `done` = the learner has moved past it in this set, Appendix A.5), `PracticeRepository`,
  `SavedRepository`, `StudyProtection`, a `ClipRemovalHook`, `SetBuilder` and `QuizGenerator` (pure).
- **Proof**: the vitest cases of `web/src/features/practice/quiz.ts` ported to JUnit with the same inputs and outputs;
  `SetBuilder` cases (F-08-AC1 composition and interleaving, at most 2 per clip, F-08-AC3 unchanged set, F-08-AC4 with
  the test clock crossing midnight); seen at 80 % of played time with seeks excluded (F-06-AC11); weak-spot
  weighting; saved anchors survive a shorts-rule change (F-09-AC3); `done` marks follow the cursor (3 of 8 done →
  the first three shorts marked) and survive a process restart.
- **Done when**: 2.5.

### WP-13 Onboarding and Connect

- **Tier** core · **Size** L (6 d) · **Lane** A.
- **Covers**: F-01-AC1 to AC5 (AC6 with WP-23), F-02 every state and AC1 to AC8, AC10, AC12 (UI), Connection details
  (P-07) with the "Why?" checklist (including the `VpnBlocks` outcome and the D-42 item "Is PhonoTrainer running with
  phone access on? If your computer joined a new network, run phonotrainer phone on there."), the last probe in words
  through `ProbeOutcomeCopy`, "This phone on this Wi-Fi: {address}" (F-22-AC4, D-39), decoder list with hardware or
  software, and "Copy diagnostics" without the credential, with the computer's address reduced to its class, without
  this phone's address and without ids (security review L-7, F-22-AC4); the coach-mark catalogue; the pairing code
  field as `NumberPassword` with no autocorrect or autofill (L-8); two computers with one name or identity listed with
  none preselected under the D-39 note with "Enter the address instead" (M-2, F-02-AC10); the typed-address copy for
  an address answering without phone access (D-42).
- **Owns**: `android/feature/onboarding/**`, `android/feature/connect/**`, `e2e/connect/**`.
- **Consumes**: `PairingRepository`, `ComputerStatusRepository`, `CoachMarkRepository`, `SettingsRepository`,
  `DecoderCapabilities`; `:core:ui` primitives, `ConnectionStatusCard` (Connection details).
- **Provides**: entry installers for `WelcomeKey`, `ConnectKey`, `ConnectionDetailsKey`; `OnboardingTags`,
  `ConnectTags`.
- **Proof**: Robolectric screen tests of every state row (welcome pages, searching, nothing found, typed address,
  phone access off with the D-42 copy, too old, wrong/expired/locked code with focus kept, success, "Connect again"
  for the same computer only, a conflict with no preselection and the note above the list (F-02-AC10), `Retry-After`
  shown when rate-limited; Connection details with the phone's address and the `PhoneAccessOff` probe words
  (F-02-AC12 UI), and "Copy diagnostics" text containing no address, id or credential (F-22-AC4)); a11y audit and
  font scale 2.0 on each; GMD area test: launcher → Welcome → Not now → Home (F-01-AC1, AC2), no permission dialog; pairing against the
  `FakeComputer` of the global set when WP-07 is merged in its baseline.
- **Done when**: 2.5 including GMD.

### WP-15 Home

- **Tier** core · **Size** M (4 d) · **Lane** A.
- **Covers**: F-03-AC1 to AC10 (UI), every F-03 state (first run, empty, loading, offline, error, partial, storage,
  background limited), pull to refresh, the Insights teaser slot; the Today's set card without a contents control,
  its stations and its TalkBack sentence (D-45); change chips in the short unit (D-48); the shared
  `ConnectionStatusCard` and `ProbeOutcomeCopy` (D-42 words).
- **Owns**: `android/feature/home/**`, `ConnectionStatusCard`, `ProbeOutcomeCopy`, `strings_connection.xml`,
  `e2e/home/**`.
- **Consumes**: `ComputerStatusRepository`, `SyncController`, `RequestRepository.counts`, `SetRepository`,
  `ProgressRepository.keepWatching`, `ClipRepository`, `OccurrenceRepository.changeCounts` (`shorts` only),
  `ReferenceRepository` (change names), `PracticeRepository`,
  `SavedRepository`, `StorageRepository`, `InsightsAvailability` (optional); `ClipCard`, `RequestRow`, `PosterArt`.
- **Provides**: the `HomeKey` installer; `ConnectionStatusCard`, `ProbeOutcomeCopy`; `HomeTags`.
- **Proof**: screen tests per state; the status card's one-sentence TalkBack summary; block order of F-03 (F-25 list
  item 3); the Today's set sentence of F-03-AC10 from a pure function (each distinct spotlight change once, in set
  order, at most three names, then "and {N} more changes"; saved shorts; questions) and "Start" as the card's only
  control; a chip reads `ChangeCount.shorts` ("Flapping · 34") and a zero reads "Not in your clips yet" and opens
  Learn; `ProbeOutcomeCopy` maps every `ProbeOutcome` (a table test); a11y audit; GMD area test: "Start" opens Watch
  at today's set, Back restores the scroll position (F-03-AC8), and a chip opens the change mode with as many shorts
  as it says (F-03-AC7, with WP-10 and WP-17 merged).
- **Done when**: 2.5 including GMD.

### WP-16 Library and Clip page

- **Tier** core · **Size** L (6 d) · **Lane** B (lane D when a fourth lane runs).
- **Covers**: F-04-AC1 to AC6; F-05 v1-core (header, Shorts tab, Summary counts, On this phone block; F-05-AC1, AC4,
  AC5, and AC11: the "Remove “{title}” from this phone?" confirmation with the words of spec F-05 item 5, on the Clip
  page and in the Library's menu, both in `:feature:library`; Storage's copy of the same words is WP-24's, because
  features never depend on each other; spec revision 4, copy only) and every F-05 state; the Summary meters slot
  (`ClipReductionMeters`) and the Transcript tab slot. The "On your computer" block is v1-complete (WP-34): v1-core has
  no such block, and nothing here prepares one.
- **Owns**: `android/feature/library/**` (except `transcript/`), `ClipCard`, `ClipRow`, `strings_clip.xml`,
  `e2e/library/**`.
- **Consumes**: `ClipRepository`, `RequestRepository`, `ShortRepository`, `ProgressRepository`, `StorageRepository`;
  `PosterArt` (WP-03: poster, poster fallback or audio art in cards, rows and the Clip page hero); `ClipTranscriptTab`,
  `ClipReductionMeters` (optional); design 9.23 and 9.26, v1-core part (the "On this phone" block: place head, size
  rows, the "Keep on this phone" switch row, the secondary buttons; the confirmation's layout).
- **Provides**: `LibraryKey`, `ClipKey` installers; `ClipCard`, `ClipRow`; `LibraryTags`.
- **Proof**: screen tests (search with live count, filters, stable order for equal seconds, "In progress" group,
  "started on your computer" line, every availability word, older-rules chip, forgotten and other-library tags, the
  "Remove from this phone?" confirmation's title, body and buttons, F-05-AC11);
  GMD area test: Watch on row k opens In order at the resume short and Back restores scroll, search and filter
  (F-04-AC5), and the same after switching to another tab and back (the per-stack decoration of architecture 16.2).
- **Done when**: 2.5 including GMD.

### WP-17 Watch: the feed and the short card

- **Tier** core · **Size** XL (12 d) · **Lane** C.
- **Covers**: F-06-AC1 to AC13 (UI), F-07-AC1 to AC11, F-08 feed side (question cards, summary card, end cards,
  "Another set", F-08-AC2, AC5, AC6), F-09-AC1 (save from a short), the mode picker with short counts (D-48), the
  track-fallback line (D-47), gestures and their accessibility
  actions, the generated audio-only visual (architecture 11.6; the poster fallback is WP-03's `PosterArt`, shared with
  Home and the Library), the "More" sheet with its slots.
- **Owns**: `android/feature/watch/**`, `e2e/watch/**`.
- **Consumes**: `FeedPlayer`, `PlayerSurface`, `ShadowingRunner` (WP-11); `ShortRepository`, `ClipContentRepository`,
  `OccurrenceRepository.changeCounts` (`shorts`, for the mode picker) (WP-10); `ClipRepository.clip` (a clip's
  artifacts changed: re-issue `setQueue` with fresh media, architecture 11.1) (WP-08); `SetRepository`,
  `ProgressRepository`, `SavedRepository`, `PracticeRepository` (WP-14);
  `SettingsRepository`, `CoachMarkRepository`; `QuestionCard`, `AnswerFeedback`, `ShadowingPanel`; WP-03's
  `ShortProgress`, `PosterArt`, `MarksLegend`; `RecordSection`, `PhraseDetailsEntry` (optional).
- **Provides**: the `WatchKey` installer; `WatchTags`.
- **Proof**: screen tests of every card state (Listen first, no spotlight, contraction spotlight, offline badge, decode
  fallback, corrupt file, volume 0, each `trackFallback` value with its D-47 line, TalkBack on: no autoplay and the
  summary text of F-07-AC11), the mode picker's counts equal `ChangeCount.shorts`, captions overflow at
  font scale 2.0 in IPA mode (F-07-AC9), grayscale identification by line style and icon (F-07-AC10, a test on the
  semantics and draw commands of `MarkedWordSpan`); the Watch ViewModel tells the pool when Watch stops being the
  visible tab (architecture 11.2); GMD area tests on the real player with clips seeded by `TestSeeder` (WP-10 and
  WP-11 are merged in its baseline): swipe, buttons and accessibility actions move one short, reduced motion makes it
  instant (F-06-AC9), tapping a word pauses and opens the lesson and closing returns paused (F-07-AC6), end cards stop
  the feed, a tab switch and return keeps the feed position, and a dialogue track arriving during the feed is played
  from the next short of that clip with the line gone (F-06-AC13).
- **Done when**: 2.5 including GMD.

### WP-18 Word lesson and shadowing UI

- **Tier** core · **Size** L (8 d) · **Lane** C.
- **Covers**: F-10-AC1, AC2, AC3, AC5, AC6 (and AC4's fallback slot), F-10 every state, F-12 UI (AC3, AC4), F-14-AC1
  in the Compare step, "Save this word", "Next change", the step-4 fallback and `MouthRenderer` slot, the step-5
  `RecordSection` slot.
- **Owns**: `android/feature/lesson/**` except `record/` and `phrase/`; `ShadowingPanel`; `strings_shadowing.xml`;
  `e2e/lesson/**`.
- **Consumes**: `ClipContentRepository` (including `compareRows`, WP-10; a feature never depends on `:core:analysis`,
  delivery review D-07), `SpanPlayer`, `ShadowingRunner`, `SavedRepository`, `DidacticContent`, `ReferenceRepository`;
  `MouthRenderer`, `RecordSection` (optional).
- **Provides**: `LessonKey` installer (sheet and full presentations); `ShadowingPanel`; `LessonTags`.
- **Proof**: screen tests of every step and state (low confidence, nothing heard, no dictionary form, no audio,
  espeak data), the compare rows' TalkBack words ("changed", "not heard"), Compact guidance, the boundary-gap line;
  process death at step 3 restores (F-10-AC6, a `SavedStateHandle` test plus a GMD recreate test).
- **Done when**: 2.5 including GMD.

### WP-20 Learn, IPA chart and "Words we use"

- **Tier** core · **Size** L (6 d) · **Lane** C (lane D when a fourth lane runs).
- **Covers**: F-14 index (AC2, AC3, AC4: the "Words we use" page copy of D-47), F-15-AC1 to AC3 and its states (the
  two count units of D-48: "N shorts in your clips" on change cards, "N times in your clips" on the phenomenon
  page), F-16-AC1 to AC3 and its states, the Insights entry (optional), the mouth slots.
- **Owns**: `android/feature/learn/**`, `OccurrenceRow`, `strings_occurrence.xml`, `e2e/learn/**`.
- **Consumes**: `DidacticContent`, `ReferenceRepository`, `OccurrenceRepository`, `SpanPlayer`; `MouthRenderer`,
  `InsightsAvailability` (optional).
- **Provides**: `LearnKey`, `PhenomenonKey`, `IpaChartKey`, `GlossaryIndexKey` installers; `OccurrenceRow`;
  `LearnTags`.
- **Proof**: screen tests (every family section and whole-word changes, counts and "Counted in the clips on this
  phone.", the Flapping card reading "9 shorts in your clips" and its page "12 times in your clips" from
  `ChangeCount(9, 12)` (F-15-AC1), empty examples, the Spanish-speaker tip on vowel_reduction; "Words we use" with its
  lede, placeholder, live count "{n} of {total} terms", the groups "Terms" and "Measures", and "xyzzy" giving the
  no-match line, "0 of {total} terms" for TalkBack and "Clear the search" restoring every row, F-14-AC4); the IPA chart at font scale 2.0 on a 360 dp
  width with no sideways scroll and no clipped symbol (F-16-AC1); symbol names read by TalkBack (F-16-AC2).
- **Done when**: 2.5 including GMD.

### WP-21 Practice and Saved

- **Tier** core · **Size** M (5 d) · **Lane** B (lane D when a fourth lane runs).
- **Covers**: F-17-AC1 to AC5 (UI) and its states; F-09 Saved screen (AC1, partial state); `QuestionCard`,
  `AnswerFeedback`.
- **Owns**: `android/feature/practice/**`, `QuestionCard`, `AnswerFeedback`, `strings_question.xml`,
  `e2e/practice/**`.
- **Consumes**: `PracticeRepository`, `SavedRepository`, `SpanPlayer`, `ReferenceRepository`; `OccurrenceRow`.
- **Provides**: `PracticeKey`, `SavedKey` installers; `QuestionCard`, `AnswerFeedback`; `PracticeTags`.
- **Proof**: screen tests (start screen with focus chip, session, answers with icon + words + outline, results,
  progress without streaks (F-17-AC5), reset confirmation, empty and exhausted pools); options ≥ 56 dp; process death
  mid-session resumes the question.
- **Done when**: 2.5 including GMD.

### WP-22 Requests and "Add a clip"

- **Tier** core · **Size** L (7 d) · **Lane** B.
- **Covers**: F-19-AC1 to AC4 (UI), F-20-AC1 to AC12 (UI), SH-AC9, SH-AC16, SH-AC17, SH-AC18 (the request opened
  after the failure), the request detail with the stepper and teaching lines (spec copy, architecture revision 2),
  the actions table of F-20, polite stage announcements (F-20-AC2), the notification ask after the first request
  (F-23); the one failure-copy mapping `RequestFailureCopy` (architecture 19, delivery review D-24) with its
  `FailureContext`: the `live_stream` and `too_long` copy with the computer's link limit (D-40), the phone-side copy
  codes of 7.7, `no_space` by kind (D-49), the F-23 notification line (F-23-AC4), and the `processing_failed`
  fallback for unknown codes only; the D-41 waiting line, the D-46 held reason, the D-47 "Cancel undone" snackbar
  and "Starting again on your computer"; which reason a waiting row shows, by state (spec F-20 item 1, D-61; copy
  only, revision 4 round 2): `WaitingReasonCopy` (architecture 19), the one list `RequestRow` uses and WP-34's
  `DeletionCopy` reuses in v1-complete.
- **Owns**: `android/feature/requests/**`, `RequestRow`, `RequestFailureCopy`, `WaitingReasonCopy`,
  `strings_request.xml`, `e2e/requests/**`.
- **Consumes**: `RequestRepository` (`Request.actions`, `waiting`, `rerunPending`), `ComputerStatusRepository`
  (`paired.capabilities.maxLinkDurationS`), `LinkParser`, `NotificationPermissionAsker`, `UserMessages`
  (`CANCEL_UNDONE`); `ShareBridge` (Add a clip).
- **Provides**: `RequestsKey`, `RequestDetailKey`, `AddClipKey` installers; `RequestRow`; `RequestsTags`.
- **Proof**: screen tests of every group, state and action; "Paste" reads the clipboard only on tap (F-19-AC1, AC2);
  the picker contract with 3 files (F-19-AC3); escape-code-free "Show details"; the browser-jobs group collapsed and
  never counted (F-20-AC8); `RequestFailureCopy` maps every code of server-contract 8.1 and 8.2 and every phone-side
  copy code, for both kinds, to the spec 8.6 and 7.7 copy (a table test), never shows an action outside
  `FailureContext.available`, maps an unknown code to `processing_failed`'s copy, writes the link limit as "1 hour",
  "2 hours" or "90 minutes" and the unknown-limit body (SH-AC18), and gives the notification line of F-23 for
  `download_forbidden` and for `too_long` (F-23-AC4); rows with `needsAppOpen` (F-20-AC10), held (F-20-AC11) and the
  "Cancel undone" snackbar (F-20-AC12); `copy_no_space` with and without `TRY_AUDIO_ONLY` available (D-49);
  `WaitingReasonCopy` over every `ComputerStatus` and `WaitingInfo` case (a table test, spec D-61: the four pauses,
  `Offline` and `NotOnWifi` "Not on Wi-Fi", `Looking`, `Checking` and `NotReachable` "Your computer isn't
  reachable", `Connected` with 0, 1 and 3 attempts, `NotConnected` with no line), and a held row and a large file
  keeping their own lines.
- **Done when**: 2.5 including GMD.

### WP-23 Share target

- **Tier** core · **Size** M (5 d) · **Lane** B.
- **Covers**: spec 8 (8.1 entries, 8.4 sheet, 8.4.1 states), SH-AC1 to SH-AC16 (UI and flows), F-01-AC6,
  architecture 12 and 20.2 (untrusted input); "Try audio only" in the open sheet after a copy fails for space,
  because the sheet holds its sources (`RequestRepository.holdSources`, closed in `onDestroy`; D-49, SH-AC15); the
  auto-close held while the notification question is open (D-51); Android review H2b, H3, M8, L6 (insets); security
  review L-11.
- **Owns**: `android/feature/share/**` (including `ShareActivity` and its manifest entries, with
  `taskAffinity=""`), `ShareBridge`, `strings_bridge.xml`, `e2e/share/**`.
- **Consumes**: `RequestRepository`, `LinkParser`, `ComputerStatusRepository`, `NotificationPermissionAsker`,
  `TalkBack` state.
- **Provides**: `ShareActivity`; `ShareBridge`; `ShareTags`.
- **Proof**: GMD tests launching `ACTION_SEND` text, single and multiple `video/*` and `audio/*` intents (synthetic
  files through a test `ContentProvider`, including one that lies about its name and size): parsing and site labels
  (SH-AC2), no link (SH-AC7), duplicates (SH-AC8), AMR refused (SH-AC10), multi-select partial (SH-AC14), no space
  before and during the copy, with "Try audio only" re-copying the same request's audio track and no request left
  half-made, and "Try audio only" gone from the Requests row once the sheet has closed (SH-AC15, D-49), Back during
  a copy asks first (SH-AC13), Home during a large copy and the copy completes (the expedited
  intake), cold start without Welcome and return to the sharing task (SH-AC11) **launched both with and without
  `FLAG_ACTIVITY_NEW_TASK` while a `MainActivity` task exists** (PhonoTrainer's task is not brought forward; the
  launching activity is on top after the sheet closes; Android review H3), the link sent by `send-now` after the sheet
  closed, auto-close off with TalkBack and suspended while the in-app notification question or the system dialog is
  open, restarting its 2 s once answered (M8, D-51); the
  `queryIntentActivities` check for `text/plain` (SH-AC1, automated part).
- **Done when**: 2.5 including GMD.

### WP-24 Settings, Storage, About

- **Tier** core · **Size** L (6 d) · **Lane** A (lane D when a fourth lane runs).
- **Covers**: F-22-AC1 to AC3 and every item of F-22; F-21's Storage screen (budget, "Download videos automatically",
  per-clip list, "Remove all videos"), and F-05-AC11 for Storage's per-clip "Remove from this phone": the "Remove
  “{title}” from this phone?" confirmation with the words of spec F-05 item 5 and design 9.23 (copy only, the same words
  as WP-16's; revision 4 review fixes); the "Forget this computer…" confirmation's title "Forget this computer?" and
  buttons "Cancel" (the default, focused first) and "Forget this computer" (spec D-60, F-22's body unchanged; copy
  only, revision 4 round 2); Your computer page; Notifications page (F-23 permission state); Help ("Restart
  the tour", F-01-AC5; "What do the marks mean?"; "How PhonoTrainer works"); About and licences (F-24-AC1, AC2:
  libraries, fonts and the vendored icon set, D-50).
- **Owns**: `android/feature/settings/**`, `e2e/settings/**`.
- **Consumes**: `SettingsRepository`, `CoachMarkRepository`, `StorageRepository`, `ClipRepository`,
  `ComputerStatusRepository`, `PairingRepository` (forget), `Notifier.permissionState`, the licence-report asset
  (WP-19) and `assets/licenses/bundled.json` with the licence texts (WP-03); `ConnectionStatusCard` (Your computer),
  `MarksLegend` (Help).
- **Provides**: `SettingsKey`, `StorageKey`, `YourComputerKey`, `NotificationSettingsKey`, `AboutKey`, `LicencesKey`,
  `HelpKey` installers; `SettingsTags`.
- **Proof**: screen tests of every group; "Forget this computer…" confirmation copy (spec D-60's title and confirm
  label) and default button with the initial focus on "Cancel"; Storage's
  "Remove “{title}” from this phone?" title, body, buttons and initial focus (F-05-AC11); licences
  readable offline; F-24-AC2 compares the rendered list with the generated report and with `bundled.json` (every
  library, the three font families and the Lucide set, each with its licence text).
- **Done when**: 2.5 including GMD.

---------------------------------------------------------------------------------------------------------------

## 7. Waves 3 and 4: integration and QA (v1-core)

### WP-19 Integrator and integration v1-core

- **Tier** core · **Waves** 1 to 3 (the role from B1 to the v1-core gate; the closing integration in wave 3) · **Size**
  the integrator role plus L (10 d) of its own deliverables · **Lane** its own copy (1.2) · **Role** integrator from B1
  (delivery review D-05); it hands over to WP-26 at the gate commit.
- **Goal**: merge every WP safely, own the frozen files and the global test seams, and prove one app where every real
  implementation works together, end to end and release-like.
- **Covers**: SC-04, SC-05, SC-11, SC-12 (automated sweep), SC-13 (automated equivalent with semantics actions);
  A-13 (keyboard focus traversal and a visible focus indicator on three screens); QT-2, QT-3, QT-4 core, QT-5 to QT-12,
  QT-14 (automated against `FakeComputer`); F-21-AC8, F-23-AC1 (WorkManager test driver); X-AC1 (only the fake
  computer's address is ever contacted, checked with an OkHttp `EventListener`, and the release dependency list has no
  analytics, crash or cloud SDK), X-AC3 (the licence check of architecture 20.6: libraries against notices 7.2, the
  bundled fonts and icon set against 7.3 through WP-03's `bundled.json`; D-50), X-AC6 end to end; NFR-02 and SC-08/SC-09 measured on the
  emulator with and without the Baseline Profile (recorded in `decisions.md`); NFR-03 (release APK size with
  `apkanalyzer`); the 16 KB alignment check; the R8 smoke and the smoke against the real server code.
- **Owns**: every `INTEGRATOR` row from B1 to the gate: the frozen contract files (applying change requests),
  `:app` (wiring, workers, `e2e/di/`, `e2e/support/`, `e2e/PhonoRobot.kt`, `e2e/flows/`, `e2e/queue/`, `e2e/smoke/`),
  `qasmoke/`, `baselineprofile/`, `decisions.md`, the notices section 7, `SECURITY.md`, `android/README.md`,
  `AGENTS.md`, `Makefile`, `README.md#Android client`; after WP-01's merge, server maintenance and the drift guards
  (2.1).
- **Consumes**: everything; `scripts/device-qa/host_computer.py` and `gmd_lock.py` (WP-06).
- **Provides**:
  - **First week after B1** (things everything else leans on): the global test set (`e2e/di/`: `TestNetworkModule`,
    `FakeComputerModule`, `TestConnectivitySeamsModule`, `TestDatabaseModule`, `TestClockModule`, the runner with
    WorkManager test initialization) and `TestSeeder`; the `qa` build type and `:qasmoke` proven with one trivial
    black-box test on the unmodified `qa` APK (delivery review D-19, Android review M10); the licence report in
    strict mode under the configuration cache (or its fallback task) and the `verifyLicences` task in `check`
    (architecture 20.6: both directions for 7.2 and for 7.3, licence files present, font SHA-256 equal); release
    signing from an ignored `keystore.properties`; `Makefile` `android-check`; `android/README.md`, `AGENTS.md`,
    `SECURITY.md` (first version, including the Android 10 task-hijacking note and the transport risk in spec 11.6's
    words: the credential crosses the network once, at pairing, and later requests are signed and cannot be
    replayed, D-38), notices section 7 with subsections 7.1 Overview, 7.2 Runtime libraries, 7.3 Fonts and icons
    (empty, for WP-03), 7.4 Build tools.
  - **Continuously**: merges (2.1), change requests within a day of a request, B2, B3… baselines, the whole GMD suite at
    each merge, schema folding into version 1 until the first phone install (architecture 4.4), server fixes from
    QA with a failing test first.
  - **CR-01-A1** (2.2, 4.7), in the first merge round after the CR-01 documents are merged: `learning_meta` and the
    `learning_id` callback, its Room test, the regenerated schema 1, published with the next baseline; the hand-off
    of each wave-2 WP that merges it needs no change.
  - **Closing integration** (about 5 days after the last wave-2 merge, prepared during wave 2): the cross-area flows,
    the automated torture suite in `e2e/queue/`, the a11y sweep over every destination, the extension-point fallback
    assertions (4.4), the R8 smoke and the smoke against the host-side fake computer, the Baseline Profile, the
    measurements, version N and N+1 builds for the update test (X-AC7), the v1-core release candidate (debug, `qa`,
    release APKs) for WP-25.
- **Proof**: the cross-area flows (share → requests → sync → watch → lesson → quiz with TalkBack-equivalent actions;
  pair → first sync → "Start watching"; forget → re-pair keeps clips; deletion on the computer while watching; an
  impersonating responder on the network never receives a signed request or a file); the automated torture suite in
  `e2e/queue/`; the a11y sweep and the keyboard traversal (A-13); one assertion per extension-point fallback; the
  `:qasmoke` suite on the R8 `qa` APK against the host-side fake computer; the smoke of server-contract 12 on the API
  36 emulator against the host-side fake computer; `zipalign -c -P 16 -v 4` and the `apkanalyzer` size on the
  release APK; the Baseline Profile generated and installed (`profileinstaller`), with NFR-02 numbers with and
  without it; `verifyLicences` green on the tree and failing on each planted fault (a library missing from 7.2, a 7.2
  row with no library, a font file missing from `bundled.json`, a wrong font SHA-256, a 7.3 row missing, a licence
  text missing) (X-AC3).
- **Done when**: 2.5 for the whole tree; `./gradlew check lintDebug assembleDebug assembleRelease
  pixel9Api36DebugAndroidTest verifyDidacticContent` and the `:qasmoke` run green; the server suite and the web suite
  green; no file under `web/` changed.

### WP-25 QA v1-core: emulator, test phone, link gate

- **Tier** core · **Wave** 4 · **Size** L (6 d, plus the owner's time) · **Starts** WP-19 done; the owner has attached
  the phone and authorised USB debugging (spec 15.4).
- **Goal**: the v1-core release gate of spec 14.3, with evidence.
- **Before the first install on the phone** (delivery review D-20): the release key exists outside the repository
  (created by the owner, or by WP-25 with the owner's approval) and the ignored `keystore.properties` points to it;
  **only release-signed builds are installed on the phone**; debug and `qa` builds stay on emulators.
- **Covers**: the [emulator] ACs (QT-1 reboot, QT-4 Wi-Fi drop, QT-13 process kill, F-06-AC3 airplane mode, F-08-AC4
  midnight, F-19-AC4, SH-AC6), all run **against the host-side fake computer** (server-contract 12; delivery review
  D-10), on the `google_apis` API 36 image WP-06 provisioned (or its recorded fallback); the platform stop paths
  (data-sync timeout and standby buckets, Android review M12); one emulator run under `capture.py` (`-tcpdump`) for
  X-AC1 (Android review L9); the release APK installed and started on the 16 KB page-size image (2.5); on the test
  phone: SC-01, SC-03, SC-06, SC-07, SC-08 (p90 over 50 swipes), SC-09, SC-10 (server stopped and phone access off
  only), SC-12 (`a11y.py`), SC-13 (TalkBack turned on through adb and restored), A-13 (an `adb shell input keyevent`
  walk over three screens), NFR-02 on the phone (`am start -W` for cold start, `dumpsys gfxinfo` for frames on time, the
  instrumented timers of the lesson and set), the player pool's memory and decoder count while swiping (`dumpsys
  meminfo`, Android review M2), NFR-14 (decoders with hardware or software, presence of the YouTube and X apps), SH-AC1
  on the device, F-25-AC1 screenshots in light and dark (from the fake computer with synthetic data), F-25-AC2 with the
  design track's sign-off, X-AC7 (version N then N+1 over it, on the emulator and the phone), X-AC8, X-AC10; the torture
  test once with the real computer using local files only (`computer.py`, throwaway workspace, configuration and working
  directories); **S-20 the link gate**: the owner shares one YouTube video, one YouTube Shorts link and one X post from
  the phone at the end of QA (spec Q-4, confirmed); each is followed end to end and its outcome recorded as it is (spec
  8.7), **as "YouTube video / Shorts / X post: outcome, error code, downloader version"**, never the URL, the title or
  an account name (security review M-8). **Practice answers on the test phone are counted** (spec D-57 (3), NFR-14;
  architecture 21.7; revision 4 round 2): QA answers practice questions on the phone only in logged sessions, and the
  QA log records, per change, the Practice screen's "x of y" line before and after each session (read from the layout
  tree with `ui.py`) and so the answers QA gave and got right; WP-33 needs these counts before the phone can first
  share progress with the owner's computer.
- **Owns**: `android/docs/qa-report.md`; local evidence only under the ignored `android/artifacts/`.
- **Must not touch**: source code (defects go to the owning WP or the integrator with a failing test); the phone's
  radios, clock or reboot state; any app other than PhonoTrainer; never sends a link to the real computer.
- **Consumes**: the release candidate; the device-QA kit and the host-side fake computer (WP-06);
  `phonotrainer ui --phone-access` on throwaway directories for the real-computer pass, the owner's workspace only
  for the link gate.
- **Provides**: the QA report and the gate decision; the site notes for the share sheet decided from the link gate
  (spec 8.7), delivered as a change request to WP-23's strings.
- **Done when**: every v1-core AC is green or has a recorded, accepted deviation; the settings-restore table of the QA
  log is complete, and so are its per-change counts of QA's practice answers on the test phone; the report contains
  no address, serial, URL of a watched video or other personal data (the hygiene check passes on it).

---------------------------------------------------------------------------------------------------------------

## 8. Waves 5 and 6: v1-complete

Build priority when capacity is short: as 1.4 (CR-01 first: WP-27 A, WP-05a-3, WP-28 A, WP-34, WP-06-3, deletion
before shared progress; then WP-27 B and WP-28 B, WP-32, WP-31's transcript, WP-30, WP-31's phrase details, WP-29).

### WP-27 Server v1-complete

- **Tier** complete · **Size** XL (16 d; revision 3: L 9 d): milestone A 7 d, milestone B 9 d · **Lane** none
  (Python) · **Starts** after WP-01 is merged, on its own branch; milestone A may run at once, during waves 2 to 4,
  ideally by the engineer who built WP-01 (revision 4) · **Merged** after the v1-core gate, once rebased onto the
  post-gate server (with WP-19's QA fixes) and the whole suite re-run (1.4, 2.1); milestone A may merge before B.
- **Milestone A (CR-01; first)**: S-25 (server-contract 6.11: the signed `delete-finished` route, its binding, the
  `already_gone` answer, the audit line), S-24 (6.12 and 6.13: `phone/progress.py` with `progress.sqlite3` and
  `saved.json`, the merge rules, the job-deletion listener and start-up sweep, the phone routes (a phone's reset only
  from the signed query; the opaque Saved cursor; `not_shared`), the loopback browser routes with the Origin rule,
  `SharedProgressSettings` in `create_app`, `serve` and `app_from_env`), the flags `delete_finished` and
  `shared_progress`, `API_VERSION = 7` (server-contract 1.2 rule 7), the README notes of server-contract 10.3. Review
  fixes (architecture 25.6): `body_sha256` on the three phone routes and the three in `access.JSON_ROUTES`, `not_before`
  in the authenticator (4.3); refusals per item instead of `store_full`, Saved positions bounded by the job, the
  10,000-entry bound with early pruning, totals clamped (4.7, 6.12); stores that fail closed with `503
  store_unavailable` (3.2); the deletion rate limit (4.7, 6.11); the `phonotrainer.audit` logger and its lines for
  deletions and applied resets (`phone/audit.py`, 6.11, 6.12); the banner's API 7 line (2.1); the browser routes' body
  rules (6.13). WP-01's v1-core routes, including `DELETE /api/phone/jobs/{id}` and its `409 not_deletable`, are not
  changed.
- **Milestone B**: S-10 (resumable uploads), S-12 (renditions and phone video on request, back-fill, low priority),
  S-16 (record yourself for phones); their capability flags, within API 7.
- **Owns**: server rows of phase complete (section 3).
- **Proof**: A: `test_phone_delete_finished.py`, `test_progress.py`, the `test_phone_compat.py` additions (the
  `STATS_KEY` drift guard), the `test_phone_access.py` addition (`not_before`), the `test_phone_cli.py` banner line, the
  complete half of `test_host_fake_computer.py` once WP-06 milestone 3 is merged (until then its in-process session
  covers 6.11 and 6.12); B: `test_phone_uploads.py` complete cases, `test_phone_renditions.py`, `test_phone_record.py`
  (server-contract 11.3).
- **Done when**: server-contract 11.4 for each milestone (on the rebased branch; the web suite green and `web/`
  untouched), and `check_ownership.py --wp WP-27 --phase complete` clean.

### WP-28 Sync, device queue and study, v1-complete (Android core)

- **Tier** complete · **Size** XL (13 d): milestone A 8 d, milestone B 5 d · **Lane** A (after WP-05a-3) · **Needs**
  CR-01-A2 (4.7) and WP-05a milestone 3 for A; WP-27's contract for B (the fake computer already implements it).
- **Owns** (phase complete): `android/core/sync/**`, `android/core/requests/**`, `android/core/study/**`,
  `android/core/network/**`, every DAO (`core/database/.../dao/**`), the fakes
  `testing/fake/{requests,sync,study,network}/**`.
- **Milestone A (CR-01; first)**:
  - **Deletion from your computer** (architecture 8.8; spec F-05 item 6, 7.7, 7.9, S-25): `RequestRepository`'s four
    new methods, the `DELETE_FINISHED` operation with its binding to server id, library id and device id, its place in
    `sendOperations()` (oldest first, before links and files), the answer table of 8.8, `SendReport.deletedOnComputer`,
    the dropping of other device ids (on `paired` changes, at start, in the sender), the `StartupTask` that returns
    `SENDING` rows to `PENDING`, `PendingDeletions` (bound in `:core:requests`), `counts()` with deletions,
    `RequestReconciler.onJobDeleted` fulfilling a waiting deletion; in `:core:sync` the removal of
    `deletedOnComputer` clips with their own message instead of the batch notice, downloads skipped for pending
    deletions (7.4), the mass-deletion guard ignoring them (7.7), `ClipSummary.deletion`; in `:core:study` the set
    builder's exclusion of pending deletions; `ComputerClient.deleteFinished` in `:core:network` (DTOs, validation).
    Review fixes (architecture 25.6, 25.7): `maybe_sent` and `ComputerDeletion.actions` (no "Undo" once an attempt
    may have arrived), answers matched by status and code (`unknown_route` refused as `FAILED` with that code, and the
    same refusal recorded without a request when the connected computer's `health` no longer announces
    `delete_finished`, spec D-62; `different_computer` waits as `DeletionWait.DIFFERENT_COMPUTER` with an event code,
    "Undo" kept; `429` waits for `Retry-After`), a refused deletion holding its clip like a waiting one (spec D-59),
    and `SyncController.exclusive` in `:core:sync` (6.3).
  - **Shared progress** (architecture 7.8, 10; spec 7.13, S-24): `SharedProgressLedger` and both `sharing()` flows in
    `:core:study` (the answer log written with `practice_stat`, first share with the `learning_id` baseline, the mirror,
    the shared and the phone-only reset, the Saved outbox and its application, the library cursor, pruning); step 4a
    and the debounced foreground trigger in `:core:sync`; `syncPractice` and `syncSaved` in `:core:network` (the 1 MiB
    cap of the Saved answer, the mappers' checks of architecture 5.4). Review fixes: `own_stat` (written with each
    answer, cleared by a reset, rebuilt when another device's reset arrives) as the baseline of a first share with
    another computer; refused items and `400` pages marked handled; `body_sha256` on the three calls (5.4).
  - **Covers**: S-24 and S-25 (phone side); F-05-AC7, AC8, AC12 (data), F-09-AC4, AC5, AC6, F-17-AC6, AC7, AC8 (data),
    AC9, AC11 (logic; the [emulator] run is WP-33's), F-20-AC13, AC14 (counts), F-23 (never notifies for these), QT-15
    to QT-20 (logic), spec 7.6, 7.7, 7.8 (pending deletions download nothing), 7.9, 7.13, 11.1 (backup bookkeeping).
  - **Proof**: JVM and Robolectric tests against `FakeComputer` (milestone 3 knobs) and in-memory Room: the 8.8 answer
    table row by row; QT-15 (two deletions and a link stored offline, sent deletions first, exactly two jobs deleted, no
    mass-deletion prompt); QT-16 (a lost answer, then `already_gone`; a browser deletion first, one message, never
    failed); QT-17 (`library_changed` waits, sent after the library comes back); QT-18 (forget, then pair again with the
    same and with another computer: never sent) and its in-flight variant (a deletion on the wire when "Forget" is
    confirmed: its answer is applied before the unpair, and the unpair waits for it); the answer table by code (a `404
    unknown_route` never removes the clip and leaves the row `REFUSED` with `TRY_AGAIN` and `KEEP_CLIP`, no automatic
    retry; a connected computer whose `capabilities(…)` drop `delete_finished` gets the same refusal and no request;
    `different_computer` never shows a refusal: `WAITING` with `DIFFERENT_COMPUTER` and `UNDO`, retried; `429` not
    retried before `Retry-After`); a refused deletion's clip stays out of new sets with its downloads paused until
    `TRY_AGAIN` or `KEEP_CLIP`, and `ClipSummary.deletion` stays null for it (spec D-59); `maybe_sent` (a timeout after
    the request left, or a process death while `SENDING`: no `UNDO` in `actions` afterwards, and an attempted undo
    changes nothing); QT-19 (revoked: kept, sent after "Connect again");
    QT-20 (an impersonator never receives it); "Undo" never races a send; a pending deletion's clip leaves new sets but
    not the current one, and downloads nothing; F-17-AC6 (3 of 4 elsewhere + 1 of 2 here offline → 4 of 6 on both
    sides), AC7 (a lost answer, each answer counted once), AC8 (an offline shared reset, then one earlier and one later
    answer from the other device), AC9 (no capability → phone-only reset; the capability appears → the whole history
    added once), AC11 logic (a copy of `learning.db` taken before the first share, restored, paired again: nothing
    counted twice; the backup taken after an unsave: the item does not come back); F-09-AC4 to AC6 with `otherDevice`;
    `accuracy()` equals totals plus unsent answers at every step; `own_stat` (share with A while `otherDevice` adds
    answers there, forget, pair with B: B's baseline is this phone's answers only; a reset from another device rebuilds
    it from the kept rows; a phone-only reset clears it); a page answered `400`, and a baseline or answers counted in
    `ignored`, never block the next page; every request body hashes to its `body_sha256`; the `SharingState` table; the
    1 → 2 migration keeps v1-core data (with the integrator's tests); the Saved cursor (a `list_id` change from the fake
    → a full list, local waiting and `not_shared` items kept); the security rows of architecture 20.8 (a reset recorded
    only in the signed query, never in the body; each malformed field rejected by the mappers and nothing changed
    locally; the source-scan test of `DELETE_FINISHED` inserts). GMD in `:core:requests` and `:core:sync` for the worker
    paths that changed.
- **Milestone B** (revision 3's scope, renditions and resumable uploads on the phone):
  - **Covers**: spec 7.10 renditions in the sync (compact clip data and Opus preferred when ready, posters, the
    phone video requested when the original cannot play, "Video getting ready on your computer"), F-21-AC6, NFR-03
    (≤ 3 MB per 4-minute clip without video), QT-4 complete (resume from ≥ 60 % with a verified checksum).
  - **Proof**: `FakeComputer` with `capabilities.renditions` and `resumable_uploads`: rendition choice and fallback
    when `failed` or `preparing`; revision switch keeps positions; PATCH resume after `truncate`; `offset_mismatch`
    recovery (from `ApiResult.Refused.offset`); background runs continue large uploads where the last one stopped (the
    size rule of architecture 8.5 lifted).
- **Done when**: 2.5 including GMD, for each milestone (`--phase complete`); WP-34 confirms the repositories give its
  screens every state they need (its hand-off lists any gap).

### WP-29 Articulator

- **Tier** complete · **Size** XL (15 d) · **Starts** after the v1-core gate; the lowest v1-complete priority (1.4).
- **Covers**: F-10 step 4 and F-10-AC4 [emulator], the Learn mouth demo (F-15), IPA mouths (F-16), A-02 (anatomy never
  uses family hues), A-07 (follows audio only while playing).
- **Owns**: `android/core/articulator/**`, `e2e/mouth/**`.
- **Consumes**: `PhoneArticulation` and poses from `DidacticContent` (WP-04); the `MouthRenderer` interface.
- **Provides**: the `MouthRenderer` binding (Compose Canvas port of the web's 2D model).
- **Proof**: the web's articulation tests ported (`tract`, `track`, `palette`, `phones` cases), pose interpolation
  goldens as numeric checks, a draw smoke test per symbol with a drawing; the GMD test of F-10-AC4.
- **Done when**: 2.5 including GMD.

### WP-30 Record yourself

- **Tier** complete · **Size** L (8 d).
- **Covers**: F-13-AC1 to AC5 and every F-13 state; architecture 14; `RECORD_AUDIO` with its rationale.
- **Owns**: `android/core/recorder/**`, `lesson/record/**`, `strings_record.xml`, `e2e/record/**`.
- **Consumes**: `SpanPlayer`, `AudioArbiter`, `ComputerClient.compare`, `ComputerStatusRepository`; the `RecordSection`
  interface.
- **Provides**: the `RecordSection` binding.
- **Proof**: Robolectric tests of the take lifecycle (auto-stop at 30 s, minimum 0.25 s, takes deleted on leaving the
  word and at start), every server code mapped to its copy against `FakeComputer` (F-13-AC3, AC5), the offline path
  with no network attempt (F-13-AC1); GMD: a WAV take sent with a known length (F-13-AC2 against the fake).
- **Done when**: 2.5 including GMD.

### WP-31 Phrase details and the Transcript tab

- **Tier** complete · **Size** L (8 d).
- **Covers**: F-11-AC1, AC2 and its states; F-05 Transcript tab, filters and mini player (F-05-AC2, AC3).
- **Owns**: `lesson/phrase/**`, `strings_phrase.xml`, `library/transcript/**`, `strings_transcript.xml`,
  `e2e/phrase/**`.
- **Consumes**: `ClipContentRepository`, `SpanPlayer`, `DidacticContent`; the `PhraseDetailsEntry` and
  `ClipTranscriptTab` interfaces; `PhraseDetailsKey`.
- **Provides**: the `PhraseDetailsEntry` and `ClipTranscriptTab` bindings and the `PhraseDetailsKey` installer.
- **Proof**: screen tests (uptalk chip and its text summary, chart and its table, no measurable pitch, filters fade
  without hiding, track switch keeps the time).
- **Done when**: 2.5 including GMD.

### WP-32 Insights

- **Tier** complete · **Size** M (5 d).
- **Covers**: F-18-AC1, AC2 and its states, the Home teaser, the Clip Summary meters ("How reduced is this speech?").
- **Owns**: `android/feature/insights/**`, `e2e/insights/**`.
- **Consumes**: `ClipContentRepository` (per-clip `summary.metrics`), `ReferenceRepository`.
- **Provides**: `InsightsKey` installer, `InsightsAvailability` and `ClipReductionMeters` bindings.
- **Proof**: pooling exactness (F-18-AC1: 40 of 60, 67 %), every chart's table view, older-clip and mixed-rule notes.
- **Done when**: 2.5 including GMD.

### WP-34 Deletion and shared-progress screens (CR-01)

- **Tier** complete · **Wave** 5 · **Size** L (7 d; revision 4: 6 d) · **Lane** B (first in wave 5) · **Starts** when
  CR-01-A2 is published (day 2 of wave 5, 1.4), on its settable fakes (4.7); **Consumes** the design track's CR-01
  additions (design.md revision 3 with its fix rounds: 9.18, 9.19, 9.23, 9.26, 9.27; renders 28 to 34, light and dark; the two icons
  converted by WP-26 in CR-01-A2); **Needs** nothing else: it runs on the fakes, and the seeded happy paths over WP-28
  A's real repositories are WP-26's cross-area flows, so the lane's copy moves on to WP-29 when WP-34 is done (1.4).
- **Goal**: every screen of spec revision 4's v1-complete items, on the repositories' new methods only (no feature
  depends on a core implementation, 2.2 rule 1).
- **Covers**: F-05 item 6 and its States row, F-05-AC6, AC8 (UI), AC9, AC10, AC12 (UI); F-04's waiting-deletion words
  and F-04-AC7 (no "Delete from your computer…" in the Library's "More" menu); F-03's shelf words (through
  `ClipCard`); F-20's deletion rows, actions and F-20-AC13, AC14 (UI); F-17 item 6, its States row, F-17-AC8 (UI),
  AC9 (UI), AC10; F-09 item 5 and its line; F-22's two "Forget this computer…" sentences (shown only when waiting
  deletions exist, and when progress is shared); the three new messages through the shell's host. Review fixes
  (architecture 25.6): Home's attention row whose inline action is a refused deletion's "Try again" when no failed
  request needs attention (design 9.17, spec F-03 item 2; C-68); in v1-complete the two-node reach strip on
  "Remove “{title}” from this phone?" (Clip page, Library and Storage) and the smartphone place mark on the phone-only
  "Forget your practice history?" (design 9.23, 9.26; C-71); a waiting deletion without "Undo" when
  `ComputerDeletion.actions` lacks it. The product track's answers (spec 15.7; architecture 19, 25.7): an imported
  clip's words wherever the deletion is named (D-58: the block's sentence, "Remove from your computer's list…", the
  confirmation, "Removing from your computer's list…", "Waiting to be removed from your computer's list", "Couldn't
  remove “{title}” from your computer's list.", the Requests title "Remove “{title}” from your computer's list"; on
  Library rows and shelf cards from `ClipSummary.source.kind`); a refused deletion shows nothing on rows, shelf cards
  or the header (D-59); the waiting reason in D-61's order through WP-22's `WaitingReasonCopy` plus the two deletion
  steps ("A different computer answered" for `DIFFERENT_COMPUTER`, the different-library sentence); D-62's refusal
  sentences (`not_finished`, `unknown_route` with F-05 item 6's "not offered" sentence, any other code); D-65's line
  "It may have reached your computer already, so it can't be undone." on a waiting deletion without "Undo"; every
  CR-01 `{title}` as `QuotedTitle.of(title)` (design 9.26, security review F-14): `DeletionCopy`, "Delete “{title}”
  from your computer?", "Remove “{title}” from your computer's list?" and, in v1-complete, "Remove “{title}” from this
  phone?" (Clip page, Library, Storage).
- **Owns** (phase complete): `android/feature/library/**` (not `transcript/`), `android/feature/requests/**`,
  `android/feature/practice/**`, `android/feature/settings/**`, `ClipCard`, `ClipRow`, `strings_clip.xml`,
  `DeletionCopy` and `strings_deletion.xml` (architecture 19), the `:core:ui` sub-package `ui/place/` and
  `strings_place.xml` (design 9.26), `DeletionRow` in `:feature:requests`, `android/feature/home/**`,
  `ConnectionStatusCard` and `strings_connection.xml` (the attention row's deletion action), `e2e/library/**`,
  `e2e/requests/**`, `e2e/practice/**`, `e2e/settings/**`, `e2e/home/**`.
- **Consumes**: `QuotedTitle` (CR-01-A2) and `WaitingReasonCopy` (WP-22, v1-core; architecture 19);
  `RequestRepository.deletionOffer`, `deleteFromComputer`, `deletions`, `performDeletion`;
  `PracticeRepository.sharing`, `SavedRepository.sharing`, `reset`; `ClipRepository.clip` and `ClipSummary.deletion`;
  `ComputerStatusRepository.status` (the 7.5 words of a waiting deletion); `RequestRepository.counts` (Home);
  `UserMessages`.
- **Proof**: Robolectric screen tests of every state of the "On your computer" block (available with the sentence and
  the action, sending, waiting with each reason and "Undo", waiting on another library, refused with "Try again" and
  "Keep the clip" and each of the three refusal sentences of spec D-62, each "not offered" sentence), the block
  placed after "On this phone" and never in the same block; the same states for an imported clip with D-58's words
  (sentence, button, confirmation, sending, waiting, refused) and an imported waiting row in the Library and on a
  shelf card (F-05-AC9); a waiting row per reason of D-61 in order (each queue pause, `DIFFERENT_COMPUTER`,
  `DIFFERENT_LIBRARY`, `Offline`, `Looking`, `Connected` after 1 and after 3 attempts); a refused deletion with no
  line on its Library row, shelf card or header (D-59); the confirmation of F-05 item 6 (title, body, the
  saved-items and offline sentences when they apply, focus on
  "Cancel" first, not dismissed by an outside tap) and its imported variant; the page closing with the message after
  the deletion; the Library's "More" menu without the deletion (F-04-AC7) and a waiting row's words; Requests'
  deletion rows in "Waiting on this phone", "Sending" and "Needs your attention", each opening the Clip page; the
  three Practice and Saved line variants (with "last synced 5 min ago", F-17-AC10), both reset confirmations chosen by
  `SharingState`, a waiting reset showing no accuracy; the "Forget this computer…" sentences; Home's attention row
  with only a refused deletion ("1 needs your attention" + "Try again", which calls `performDeletion(id, TRY_AGAIN)`)
  and with a failed request and a refused deletion (the request's action, as in v1-core); the reach strip on
  "Remove “{title}” from this phone?" in the Library and Storage and the place mark on the phone-only reset dialog,
  each collapsing above font scale 1.3; a `maybe_sent` waiting deletion with no "Undo" and D-65's line, on the Clip
  page and in Requests; the screenshot test of the "Delete from your computer" dialog with design 9.26's
  right-to-left-override title, light and dark, at font scale 1.0 and 2.0, against render 31, specimen 5 (no U+202E
  left, "from your computer?" after the closing quote in reading order); the a11y audit and font scale 2.0 on each;
  previews of every new state in light and dark, checked against renders 28 to 34 (design request
  A-10; WP-33 compares screenshots with them). GMD area tests (library, requests, practice, settings, home) on the
  global test set: the destinations open with the new blocks and their states as the fakes give them.
- **Done when**: 2.5 including GMD, on `--phase complete`; the design track signs off the CR-01 states.

### WP-26 Integration v1-complete

- **Tier** complete · **Wave** 6 (the role from the gate) · **Size** L (7 d: CR-01-A2 2 d on days 0 to 2 of wave 5,
  then 5 d) · **Role** integrator of waves 5 and 6, taking over from WP-19 at the v1-core gate commit.
- **Goal**: as WP-19 for the complete tier: apply **CR-01-A2** as the first commit after the gate (2.2, 4.7: the
  contract additions, the pairing store's two capability keys with their round-trip test, `forget()` inside
  `SyncController.exclusive`, the three messages in the shell's host with their titles passed through `QuotedTitle`
  (`:core:ui`, made here with its unit test; architecture 19), the fakes' new members, Room version 2 with its
  `AutoMigration` and migration tests, and the two icons the design track staged in `design-src/cr01/` converted with
  WP-03's script into `:core:designsystem` with their `PhonoIcons` entries, `bundled.json` and notices 7.3 updated so
  `verifyLicences` stays green; WP-03's frozen 173-file inventory is not touched, design 9.26), then merge wave
  5, extend the cross-area flows (record in a lesson, phrase details from the feed, Insights from Home, renditions in
  the first sync; CR-01: a deletion confirmed offline, kept through a process death and applied at the next connection
  before a shared link; a deletion of a clip open in the feed; two phones sharing accuracy and the Saved list through
  `FakeComputer`'s second device; WP-34's screens over WP-28 A's real repositories, seeded through `TestSeeder`:
  confirm offline → "Undo", confirm connected → the page closes and the message shows once, a shared reset
  confirmation; the update from the v1-core build to the v1-complete build keeping progress, with `learning_id`
  unchanged), re-run the real-server smoke against an API 7 server (with WP-06 milestone 3's self-check),
  release candidate 2.
- **Done when**: as WP-19.

### WP-33 QA v1-complete

- **Tier** complete · **Wave** 6 · **Size** M (5 d; revision 4 adds 1 d for CR-01).
- **Covers**: the complete-tier ACs (F-10-AC4 [emulator], F-11, F-13 on the phone with the real computer and a
  synthetic clip, F-18, QT-4 complete [emulator]), NFR-03 sizes on the phone, the phone video of a clip the phone
  cannot decode, the release gate of spec 14.3 again (including X-AC7 update from the v1-core build). CR-01: F-04-AC7,
  F-05-AC6 to AC10 and AC12, F-09-AC4 to AC6, F-17-AC6 to AC10, F-17-AC11 [emulator] (a backup restored on the
  emulator against the host-side fake computer), F-20-AC13, AC14, QT-15 to QT-20 (the [emulator] steps against the
  host-side fake computer), all deletions and resets against computers the kit started; **D-57** (spec 15.7, NFR-14,
  X-AC10; architecture 21.7): on the real computer QA deletes only a clip it created itself in the same session from a
  local test file and never resets the owner's shared history; **the first share of the test phone** (D-57 (3)):
  before a v1-complete build on the test phone can first share progress with the owner's own computer, and at the
  latest before the phone is handed back, QA compares the phone's per-change totals (`practice_totals()`) with the QA
  log's counts since the last logged reset (QA's own answers from WP-25 on, plus the simulated devices' on a computer
  the phone shared with); equal: QA clears them with "Reset" (phone-only while the line reads "Kept only on this
  phone.", or `reset_everywhere` while it shares with a computer the kit started) and logs the totals cleared; not
  equal: QA resets nothing and the QA report states, per change, the totals that first share will send and how many
  are QA's; while the phone shares with the owner's own computer the report says how many answers QA gave there
  (D-57 (4)).
- **Proof**: the QA log's entry for the first-share check (the totals read, the counts compared, the outcome and, when
  cleared, the Practice screen showing no accuracy afterwards); the report's first-share paragraph; no deletion or
  reset in the log against a server id the kit did not start.
- **Owns**: `android/docs/qa-report.md` (phase complete).
- **Done when**: as WP-25 for the complete tier, and the first-share check is logged before the phone is handed back.

---------------------------------------------------------------------------------------------------------------

## 9. Dependencies and risks of the plan

### 9.1 Dependency matrix (who needs whom before done; "fake" means the WP can start on the B1 fakes)

| WP | Needs merged before its own done | Can start on fakes |
|---|---|---|
| 01 | nothing | — |
| 02 | WP-12's and WP-04's shapes by day 4 (for B1) | — |
| 03 | design track output; B1 | yes (placeholder theme) |
| 04 | nothing for the extractor; B1 for the Kotlin step | — |
| 05a | B0 to compile; WP-01's reference vectors (milestone A) for its signature and proof tests | — |
| 06 | WP-01 milestone A for `host_computer.py` | — |
| 12 | B0 to compile | — |
| 05b | B1, 05a | — |
| 07 | 05b | yes (`FakeComputerClient`) |
| 08 | 05b, 07; 09, 10, 11, 14 through their SPIs (real wiring proven by WP-19) | yes, all through SPIs |
| 09 | 05b by its day 4, 12 (links) | yes |
| 10 | 12 | partly (frozen signatures) |
| 11 | 12 (shadowing plan) | yes |
| 14 | 10 | yes |
| 13, 15, 16, 17, 18, 20, 21, 22, 23, 24 | none for screen tests (fakes); their GMD area tests run on the global test set with whatever repositories their baseline has, and their seeded happy paths need the cores they read (2.4) | yes |
| 19 | B1 to start (role); every wave-2 WP to finish | — |
| 25 | 19; the phone attached; the release key (20.5 of the architecture) | — |
| 27 | 01 to start (milestone A may start at once, on its branch); the v1-core gate to merge (rebased) | — |
| 28 | A: CR-01-A2 and 05a-3; B: 27's contract (the fake implements it since WP-05a) | yes |
| 05a-3 | CR-01-A2 | — |
| 06-3 | 27 milestone A merged | — |
| 34 | CR-01-A2 (with `QuotedTitle`); WP-22's `WaitingReasonCopy` (v1-core, merged by the gate); the design track's CR-01 states for sign-off (its seeded happy paths over 28 A are WP-26's flows) | yes (the settable fakes of CR-01-A2) |
| 29 | 04, 12 (both long merged by then) | — |
| 30, 31, 32 | the v1-core gate | yes |

### 9.2 Risks and how the plan contains them

| Risk | Containment |
|---|---|
| The convention plugins fight AGP 9 built-in Kotlin, or test modules form dependency cycles | WP-02 M1 proves both before anything else; template fallback (architecture 2.3); fakes on `:core:domain` only if the spike fails |
| B1 slips, holding wave 2 | WP-01, WP-05a, WP-06, WP-12 and WP-04's extractor do not need B1; the shapes of Appendix A are already written; M2 is scoped to declarations and stubs, not implementations |
| A frozen interface proves wrong in wave 2 | additive change requests; the integrator, a dedicated role, applies them within a day; a WP never waits idle (2.2) |
| `FakeComputer` drifts from the server | written from server-contract with a conformance test per route and the shared reference vectors; the smoke against the host-side fake computer, which runs the real server code (WP-19) |
| Shared components arrive late (4.5) | consumers use the frozen API with the B1 placeholder; no code change on merge |
| Many parallel branches collide | git three-way merges by the integrator; ownership table and check; frozen hot files; strings per module, per sub-package and per shared component; e2e areas per WP |
| The development computer runs out of memory or the managed AVD is contended | four Android copies at most, memory settings per copy, one GMD lock, area-filtered runs (1.2) |
| AV1 on the test phone, broadcast filtered by the router | known in architecture 24; WP-25 records decoders and falls back to manual address |
| The link path fails on the real computer (403) | S-23 in WP-01; designed failure states (WP-09, WP-22); the gate reports the outcome as it is |
| An emulator image cannot toggle Wi-Fi or take screenshots | WP-06's day-0 spike on a `google_apis` image, with a recorded fallback image (architecture 21.7) |
| The owner's phone is not attached when WP-25 starts | every [emulator] AC and the automated suite run first; device steps wait (spec 15.4) |
| CR-01 lands while wave 1 and 2 are in flight | v1-core gains only CR-01-A1 (one table, one callback) and one confirmation's words; everything else is v1-complete, additive (2.2, 4.7) and in new milestones (WP-27 A, WP-28 A, WP-05a-3, WP-06-3) or a new WP (WP-34); WP-01 is not reopened |
| A deletion or a reset touches the owner's real data during QA | D-57: the kit's destructive helpers refuse to act unless the phone is paired with a computer the kit started in the session (`host_computer.py`, or `computer.py`'s throwaway directories, whose clips the session created), never the owner's own (WP-06-3, WP-33); before the test phone first shares with the owner's computer, its practice totals are compared with QA's logged answers and cleared only when all are QA's, otherwise reported (D-57 (3); WP-25, WP-33) |
| The browser client does not adopt shared progress | nothing waits for it: the phone ↔ computer path is complete without it; the hand-off (`web-progress-sync.md`) is self-contained, and the server's routes are inert until called |

---------------------------------------------------------------------------------------------------------------

## 10. Traceability: spec → work packages

| Spec item | WPs |
|---|---|
| S-01 to S-09, S-11, S-13 to S-15, S-17, S-21 to S-23 | WP-01 |
| S-10, S-12, S-16 | WP-27 (server), WP-28 (phone, S-10/S-12), WP-30 (phone, S-16) |
| S-24, S-25 (CR-01) | WP-27 milestone A (server), WP-28 milestone A (phone core), WP-34 (screens), WP-05a-3 and WP-06-3 (fake computers), WP-26 (flows), WP-33 (QA) |
| S-18 | WP-04 |
| S-20 | WP-25 |
| F-01 | WP-13 (UI), WP-02 (stores), WP-23 (AC6) |
| F-02 | WP-07 (logic), WP-13 (UI), WP-01 (server), WP-06 (F-02-AC9 scan) |
| F-03 | WP-15 (UI), WP-14 (today's set data), WP-10 (short counts); v1-complete: WP-34 (the attention row's action for a refused deletion, item 2) |
| F-04, F-05 (core) | WP-16 (F-05-AC11 included; Storage's copy of its words: WP-24) |
| F-04, F-05 "On your computer" (complete) | WP-34 (UI), WP-28 (data) |
| F-05 Transcript | WP-31 |
| F-06, F-07 | WP-12 (rules), WP-10 (queries), WP-11 (playback), WP-14 (seen, positions), WP-17 (UI) |
| F-08 | WP-14 (logic), WP-17 (feed), WP-21 (question component) |
| F-09 | WP-14 (logic), WP-21 (Saved screen), WP-17 (save); the shared list (complete): WP-28 (logic), WP-34 (line) |
| F-10 | WP-12 (compare rows), WP-10 (rows through `ClipContentRepository`), WP-18 (UI), WP-29 (step 4 drawing) |
| F-11 | WP-31 |
| F-12 | WP-12 (plan), WP-11 (runner), WP-18 (panel) |
| F-13 | WP-30, WP-27 (S-16) |
| F-14 | WP-04 (content), WP-03 (explain sheet), WP-18, WP-20 (index page, AC4) |
| F-15, F-16 | WP-20, WP-04, WP-10 (the two count units), WP-29 (mouths) |
| F-17 | WP-14 (logic), WP-21 (UI); shared accuracy (complete): WP-28 (logic), WP-34 (line, confirmations) |
| F-18 | WP-32 |
| F-19 | WP-09 (data), WP-22 (UI) |
| F-20 | WP-09 (data), WP-22 (UI); deletion rows (complete): WP-28 (data), WP-34 (UI) |
| F-21 | WP-08 (engine), WP-24 (Storage UI), WP-28 (renditions) |
| F-22 | WP-02 (store), WP-24 (UI), WP-13 (Connection details, AC4), WP-07 (this phone's address); the CR-01 sentences of "Forget this computer…": WP-34 |
| F-23 | WP-02 (channels), WP-09 (own-request notices), WP-22 (the notification line of `RequestFailureCopy`), WP-22 and WP-23 (asking) |
| F-24 | WP-19 (report and licence check), WP-03 (bundled licence texts and `bundled.json`), WP-24 (screen) |
| F-25 | WP-03 (assets), WP-25 (screenshots, sign-off) |
| Spec 7.12 torture test | WP-08, WP-09 (logic), WP-19 (automated), WP-25 ([emulator] and real computer); QT-15 to QT-20 (complete): WP-28 (logic), WP-26 (automated), WP-06-3 and WP-33 ([emulator], D-57) |
| Spec 7.13 shared progress (complete) | WP-27 (server), WP-28 (engine), WP-34 (lines), the web owner (browser, outside the plan: `web-progress-sync.md`) |
| Spec 14.6 version 2 (encrypted transport) | not in the v1 plan; designed in architecture 26 and server-contract 13 |
| Spec 8 share target | WP-23, WP-09, WP-22 (the failures opened from Requests: SH-AC9, SH-AC16 to SH-AC18) |
| Spec 10 accessibility | WP-03 (primitives, focus indicator), every feature WP (screens), WP-19 (sweep, A-13 keyboard traversal), WP-25 (device, A-13 key-event walk) |
| Spec 11 privacy and security | WP-01 (server), WP-02 (manifest, backup, logging), WP-05b (cleartext, signing, proofs), WP-06 (scans, hooks), WP-09 and WP-23 (untrusted shared input) |
| X-AC1 to X-AC10 | WP-19 (1, 3, 6, 7 builds N and N+1), WP-03 (3: the bundled fonts and icon set), WP-06 (2, 4, 5), WP-02 (5, 8), WP-25 (1 packet capture, 7, 8, 10), WP-01 (9) |
| NFR-01 to NFR-14 | WP-02 (01, 07, 10, 11), WP-12 and WP-10 (02, 08), WP-08 (03, 04), WP-05b (05), every feature WP (06 per screen), WP-11 (09), WP-06 (12, the language scan), WP-19 (02 measured on the emulator with and without the Baseline Profile, 03 APK size, 06 end to end, 13), WP-25 (02 on the phone, 13, 14) |
| SC-01 to SC-16 | as listed in each WP's Covers; SC-15 (repository scans) WP-06; SC-16 (every brief requirement has a passing AC, spec section 16) is verified by the orchestrator at each gate, outside the WPs (delivery review D-21) |

**Acceptance criteria added or reworded by spec revision 3** (spec 15.6, D-37 to D-52; architecture 25.4). Each has a
named test in the WPs' Proof lists.

| AC | Decision | WPs (what each proves) |
|---|---|---|
| F-02-AC5, F-02-AC6 (reworded: "proves it is the paired computer") | D-37 | WP-07 (re-discovery and `useNewAddress` accept only a verified proof), WP-05b (no signature before verification) |
| F-02-AC10 (new) | D-39 | WP-07 (`SearchState.Found.conflict`, no preselection, two loopback responders), WP-13 (the note above the list, "Enter the address instead") |
| F-02-AC11 (new) | D-37 | WP-05a (`impersonate(MISSING \| WRONG)`), WP-07 (`DifferentComputer`, nothing sent, nothing deleted), WP-05b (no signed call reaches it) |
| F-02-AC12 (new) | D-42 | WP-05a (`lanForbidden()`), WP-07 (`NotReachable` with `PhoneAccessOff`), WP-15 (`ProbeOutcomeCopy` words), WP-13 (Connection details shows them) |
| F-03-AC10 (new) | D-45 | WP-14 (the set's items in order with `spotlight`, `savedTag`, `done`), WP-15 (the card's sentence and its single control) |
| F-06-AC13 (new) | D-47 | WP-11 (`trackFallback`, fresh media from the next short), WP-17 (the line, the queue refresh, the GMD flow) |
| F-14-AC4 (new) | D-47 | WP-20 |
| F-15-AC1 (reworded: shorts on the card, occurrences on the page) | D-48 | WP-10 (`ChangeCount`), WP-20 (both labels) |
| F-20-AC10 (new) | D-41 | WP-09 (`needsAppOpen`, links not held up, visible transfer on open), WP-22 (the line) |
| F-20-AC11 (new) | D-46 | WP-09 (held until "Send now"), WP-22 (the reason line) |
| F-20-AC12 (new) | D-47 | WP-09 (undo restores, no cancel sent, `CANCEL_UNDONE` posted once), WP-22 (the snackbar) |
| F-22-AC4 (new) | D-39 | WP-07 (`phoneAddress`), WP-13 (Connection details row; "Copy diagnostics" without addresses, ids or credential). Connection details is WP-13's destination (`ConnectionDetailsKey`), so the AC is WP-13's, not WP-24's |
| F-23-AC4 (new) | D-40 | WP-22 (`notificationLine`), WP-09 (posted once for an own request), WP-05a (the `too_long` failure) |
| F-24-AC2 (reworded: libraries, fonts and the icon set) | D-50 | WP-24 (the rendered list against both sources), WP-03 (`bundled.json`), WP-19 (the report) |
| SH-AC17 (new) | D-40 | WP-22 (title, body, actions), WP-09 (`Request.actions`), WP-05a (the `live_stream` failure) |
| SH-AC18 (new) | D-40 | WP-22 (the limit as "1 hour", no "Try again" or "Try audio only"), WP-09 (`Request.actions`), WP-05a (`maxLinkDurationS = 3600`) |
| X-AC3 (reworded: fonts and the icon set too) | D-50 | WP-19 (`verifyLicences`), WP-03 (`bundled.json`, notices 7.3) |

SH-AC17 and SH-AC18 are opened from Requests after the share sheet has closed, so they are WP-22's and WP-09's, not
WP-23's.

**Acceptance criteria added by spec revision 4** (CR-01, spec 15.7, D-53 to D-57; architecture 25.5). Each has a named
test in the WPs' Proof lists.

| AC | Decision | WPs (what each proves) |
|---|---|---|
| F-04-AC7 | D-54 | WP-34 (no deletion in the "More" menu; the waiting row's words) |
| F-05-AC6 | D-54 | WP-34 (the block apart from "On this phone", the confirmation's title, focus and dismissal) |
| F-05-AC7 | D-54 | WP-28 (exactly one job deleted, files, saved items and positions gone, practice stats kept, one message), WP-34 (the page closes) |
| F-05-AC8 | D-54 | WP-28 (nothing removed offline, out of new sets, "Undo" sends nothing, applied later), WP-34 (the states) |
| F-05-AC9 | D-54, D-58 | WP-34 (the imported confirmation and D-58's words in every state, row and shelf card), WP-28 (`imported` from `source.kind`), WP-27 (list-only deletion) |
| F-05-AC10 | D-54 | WP-34 (the three sentences), WP-28 (`DeletionOffer.NotOffered`) |
| F-05-AC11 (v1-core) | D-54 | WP-16 (the words of the "Remove from this phone?" confirmation on the Clip page and in the Library; nothing sent), WP-24 (the same words in Storage) |
| F-05-AC12 | D-54, D-59, D-62 | WP-28 (refused: kept on both sides; "Keep the clip" clears it; `unknown_route` and a computer without the flag refused, never "already gone"), WP-34 (the rows and D-62's sentences), WP-05a-3 (`refuseDeletion`) |
| F-09-AC4, AC5, AC6 | D-55 | WP-28 (two devices through `otherDevice`; the tie and the later save; a deleted job's items never come back), WP-27 (the same rules on the server), WP-05a-3 |
| F-17-AC6, AC7 | D-55 | WP-28 (4 of 6 on both sides; each answer counted once), WP-27 |
| F-17-AC8 | D-55 | WP-34 (the confirmation and the waiting line), WP-28 (the offline reset; earlier answer cleared, later kept), WP-27 |
| F-17-AC9 | D-55 | WP-34 (the line and the phone-only confirmation), WP-28 (the whole history added once at the first share) |
| F-17-AC10 | D-55 | WP-34 ("last synced 5 min ago") |
| F-17-AC11 [emulator] | D-55 | WP-28 (the restore logic on the JVM), WP-33 (the [emulator] restore), WP-19 (CR-01-A1: `learning_id` in every backup) |
| F-20-AC13, AC14 | D-54 | WP-28 (counts; no notification), WP-34 (rows and actions) |
| QT-15 to QT-20 | D-54 | WP-28 (logic), WP-26 (automated flows), WP-06-3 and WP-33 ([emulator] against the host-side fake computer) |
| NFR-14, X-AC10 (D-57) | D-57 | WP-25 (QA's practice answers on the test phone counted per change), WP-06-3 (the destructive helpers' server-id guard; `qa_log.py` records the kit's server ids, QA's answers and the simulated devices' answers and resets; `practice_totals()`), WP-33 (the first-share check of D-57 (3), architecture 21.7) |

The product track's answers of fix round 2 (spec 15.7, D-58 to D-65) add no acceptance criterion; they fix copy and
two mappings, and each has an owner: D-58 WP-34; D-59 WP-28 A (the hold) and WP-34 (no row line); D-60 WP-24 (copy
only); D-61 WP-22 (`WaitingReasonCopy`, copy only) and WP-34; D-62 WP-28 A (the code mapping) and WP-34 (the
sentences); D-63 WP-28 A (the device-id binding drops the row); D-64 WP-27 A (stores fail closed, the README); D-65
WP-28 A (`maybe_sent`) and WP-34 (the line).

---------------------------------------------------------------------------------------------------------------

## Appendix A. Shapes of the frozen types (delivery review D-02)

Fields only, in Kotlin. Every type that architecture 3.4 or section 4 names without a shape is here, so WP-02
transcribes B1 instead of designing it. The CR-01 additions (revision 4) are in 4.7, not repeated here. WP-02 may add
fields with defaults; owners correct a shape through a change request (2.2). Packages: `…core.domain.model` unless
noted; `Instant`, `LocalDate`, `Duration` are `java.time`. A `…Id` value class, `HostPort`, `ShortId`, `WordRef`, the
enums `Family`, `Advice`, `Site`, `Track` and the sealed types listed with members in architecture 3.4 are not repeated.

### A.1 Computer, connection and pairing (WP-07, WP-13)

```kotlin
sealed interface ProbeOutcome {                       // "the last probe in words" (spec 7.5)
  data object Ok : ProbeOutcome
  data class NoAnswer(val afterS: Int) : ProbeOutcome // "No answer after 3 s"
  data object Refused : ProbeOutcome; data object NoRoute : ProbeOutcome; data object Reset : ProbeOutcome
  data object VpnBlocks : ProbeOutcome                // bind failed with EPERM (architecture 5.1)
  data object PhoneAccessOff : ProbeOutcome           // phone_access false, or lan_forbidden
  data object NotPhonoTrainer : ProbeOutcome
  data object ProofFailed : ProbeOutcome              // DifferentComputer (architecture 5.3)
}
data class PairedComputer(val serverId: ServerId, val name: String, val deviceId: DeviceId, val deviceName: String,
  val address: HostPort, val libraryId: LibraryId, val pairedAt: Instant, val lastSeen: Instant?,
  val lastSync: Instant?, val apiVersion: Int, val capabilities: Capabilities?)
data class ConnectionDiagnostics(val address: HostPort?, val shortServerId: String?, val apiVersion: Int?,
  val lastProbe: ProbeOutcome?, val lastProbeAt: Instant?, val addressChangedAt: Instant?, val waitingRequests: Int,
  val requestsOnComputer: Int, val wantedDownloads: Int, val backgroundRestricted: Boolean,
  val decoders: List<DecoderInfo>,
  val phoneAddress: String? = null)  // "This phone on this Wi-Fi: {address}" (spec D-39): NetworkSnapshot.ipv4 of the
                                     // bound Wi-Fi/Ethernet; on screen only, never in "Copy diagnostics" (arch. 20.3)
data class FoundComputer(val serverId: ServerId, val name: String, val address: HostPort, val apiVersion: Int,
  val phoneAccess: Boolean, val tooOld: Boolean, val conflict: Boolean)   // conflict: another source used the same id or name
sealed interface SearchState {
  data object Searching : SearchState
  data class Found(val computers: List<FoundComputer>, val preselected: FoundComputer?,   // null when 0 or 2+ or a conflict
    val conflict: Boolean = false) : SearchState  // two answers share a name, or one server id answers from two
                                                  // addresses: the D-39 note above the list (architecture 6.1)
  data object NothingFound : SearchState; data object NotOnWifi : SearchState
}
sealed interface CheckResult {                        // a typed address, or useNewAddress
  data class Ok(val computer: FoundComputer) : CheckResult
  data object PhoneAccessOff : CheckResult; data object TooOld : CheckResult; data object NotPhonoTrainer : CheckResult
  data object NoAnswer : CheckResult; data object NotPrivate : CheckResult
  data object DifferentComputer : CheckResult         // useNewAddress: another server id, or no valid proof
}
data class PairingTarget(val address: HostPort, val serverId: ServerId?, val name: String?)
sealed interface PairResult {
  data class Paired(val computer: PairedComputer) : PairResult
  data object CodeInvalid : PairResult; data object CodeExpired : PairResult; data object CodeLocked : PairResult
  data class RateLimited(val retryAfterS: Int?) : PairResult
  data object AppTooOld : PairResult; data object PhoneAccessOff : PairResult
  data class Failed(val error: AppError.Computer) : PairResult
}
sealed interface ForgetResult { data object Forgotten : ForgetResult; data class Failed(val error: AppError) : ForgetResult }
enum class NetworkKind { NONE, MOBILE_ONLY, WIFI, ETHERNET }
data class NetworkSnapshot(val kind: NetworkKind, val networkHandle: Long?, val ipv4: String?, val prefixLength: Int?,
  val vpnActive: Boolean, val validated: Boolean)
class Datagram(val bytes: ByteArray, val sourceHost: String, val sourcePort: Int)
```

### A.2 Library, sync and storage (WP-08, WP-15, WP-16, WP-24)

```kotlin
data class ClipSource(val kind: SourceKind, val site: Site?, val link: String?, val fileName: String?)
enum class ClipOrigin { ACTIVE, FORGOTTEN_COMPUTER, OTHER_LIBRARY }
enum class PauseReason { NOT_CONNECTED, FREE_SPACE_FLOOR, BUDGET_REACHED, NOT_WANTED }
data class ClipQuery(val search: String = "", val filter: ClipFilter = ClipFilter.ALL, val sort: ClipSort = ClipSort.NEWEST)
enum class ClipFilter { ALL, ON_THIS_PHONE, NOT_ON_THIS_PHONE, IN_PROGRESS, KEPT, VIDEO, AUDIO_ONLY }
enum class ClipSort { NEWEST, OLDEST, TITLE, LONGEST }
data class ClipDetail(val summary: ClipSummary, val durationS: Double?, val segments: Int?, val changeCounts: Map<ChangeLabel, Int>,
  val rulesVersion: Int?, val phoneEngine: String?, val dialogueSeparation: Boolean?, val hasDialogueTrack: Boolean,
  val studyPackBytes: Long?, val videoBytes: Long?, val lastWatched: Instant?, val resume: ShortId?,
  val computerError: RequestError?,
  val artifacts: List<ArtifactProgress> = emptyList())   // per-file download state (F-21 "Clip data ✓ · Audio 60 % · Video waiting")
data class ArtifactProgress(val role: ArtifactRole, val state: ArtifactLocalState, val percent: Int?,
  val pauseReason: PauseReason?, val verifyFailures: Int)   // verifyFailures ≥ 2: "Download failed: the file was damaged on the way."
enum class ArtifactLocalState { ABSENT, QUEUED, DOWNLOADING, PAUSED, READY, FAILED }   // the artifact table's local_state (architecture 4.2)
data class ComputerJob(val id: JobId, val title: String?, val status: RemoteStatus, val stage: Stage?, val percent: Int,
  val queuePosition: Int?, val source: ClipSource, val error: RequestError?, val createdAt: Instant, val hidden: Boolean)
data class MassDeletionPrompt(val count: Int, val ofTotal: Int, val serverName: String)
sealed interface SyncState {
  data object Idle : SyncState
  data class Active(val bringing: Int, val percent: Int?, val sending: Int,             // "Syncing · bringing 3 clips · 42 %"
    val doneBytes: Long? = null, val totalBytes: Long? = null) : SyncState   // "Bringing 2 clips to this phone · 34 MB of 61 MB" (F-03)
  data class Paused(val reason: PauseReason) : SyncState
}
enum class SyncReason { APP_START, RESUME, CONNECTED, REQUEST_CREATED, SYNC_NOW, PULL_TO_REFRESH, PAIRED, JOB_CHANGED,
  PERIODIC, ON_WIFI, SEND_NOW, NETWORK_CHANGED }
data class StorageUsage(val clipsBytes: Long, val videosBytes: Long, val waitingCopiesBytes: Long, val cacheBytes: Long,
  val budget: StorageBudget, val freeBytes: Long, val floorBytes: Long, val budgetReached: Boolean, val belowFloor: Boolean,
  val perClip: List<ClipStorage>)
data class ClipStorage(val id: JobId, val title: String, val studyPackBytes: Long, val videoBytes: Long, val kept: Boolean)
sealed interface StorageBudget { data class Limit(val bytes: Long) : StorageBudget; data object FreeSpaceOnly : StorageBudget }
sealed interface IndexOutcome {                       // :core:corpus → :core:sync
  data class Indexed(val shorts: Int, val occurrences: Int) : IndexOutcome
  data object Unchanged : IndexOutcome; data object Corrupt : IndexOutcome; data object Missing : IndexOutcome
}
interface ClipLease : AutoCloseable { val id: JobId }  // close() releases; removal deferred while open
```

### A.3 Clip data (WP-12 drafts and may refine by day 4; WP-10, WP-11, WP-17, WP-18, WP-20, WP-31, WP-32 consume)

```kotlin
class ClipData(val jobId: JobId, val revision: String, val meta: ClipMeta, val phrases: List<Phrase>, val summary: ClipDataSummary)
data class ClipMeta(val durationS: Double, val language: String?, val rulesVersion: Int?, val phoneEngine: String?,
  val dialogueSeparation: Boolean?, val phonotrainerVersion: String?)
class Phrase(val index: Int, val startS: Double, val endS: Double, val text: String, val words: List<Word>,
  val f0: F0Track?, val finalContour: String?, val emphasisWord: Int?, val prominence: FloatArray?,
  val wordClasses: List<String>?, val intonationUnits: List<IntonationUnit>)
data class Word(val index: Int, val text: String, val startS: Double, val endS: Double, val dictIpa: String?,
  val canonicalIpa: String?, val realizedIpa: String?, val canonicalPhones: List<TimedPhone>,
  val realizedPhones: List<TimedPhone>, val changes: List<ChangeLabel>, val diffCost: Double, val lowConfidence: Boolean,
  val boundaryLinkNext: Boolean, val lexicalForm: String?, val oov: Boolean) {
  val tooShort: Boolean get() = endS - startS < 0.06                     // the web's playable rule
}
data class TimedPhone(val symbol: String, val startS: Double, val endS: Double)
class F0Track(val timesS: FloatArray, val hz: FloatArray)
data class IntonationUnit(val startS: Double, val endS: Double, val firstWord: Int, val lastWord: Int, val type: String?,
  val finalContour: String?, val finalSlopeSt: Double?, val uptalk: Boolean?)
data class ClipDataSummary(val changeCounts: Map<ChangeLabel, Int>, val metrics: Map<String, Double>)
data class ClipMedia(val jobId: JobId, val audioMix: File, val audioDialogue: File?, val videoFile: File?,
  val videoCodec: String?, val videoWidth: Int?, val videoHeight: Int?, val videoRotation: Int, val videoOffsetMs: Long,
  val poster: File?, val videoAvailability: VideoAvailability)
data class ClipSpan(val jobId: JobId, val startMs: Long, val endMs: Long, val word: WordRef?)   // padded to 0.25 s audible by the player
data class CompareRow(val dictionary: String?, val said: String?, val status: CompareStatus, val change: ChangeLabel?)
enum class CompareStatus { SAME, CHANGED, NOT_HEARD, ADDED }
data class Occurrence(val word: WordRef, val shortFirstSegment: Int, val wordText: String, val dictIpa: String?,
  val realizedIpa: String?, val startMs: Long, val endMs: Long, val change: ChangeLabel, val diffCost: Double,
  val advice: Advice, val family: Family, val crossesBoundary: Boolean, val nextWordText: String?, val clipTitle: String)
data class WordVariant(val realizedIpa: String, val count: Int, val examples: List<Occurrence>)
data class CorpusTotals(val clips: Int, val shorts: Int, val words: Int, val occurrences: Int)
data class ChangeCount(val shorts: Int, val occurrences: Int)  // OccurrenceRepository.changeCounts(), spec D-48:
  // shorts = playable shorts with a spotlight candidate of the change = what its change mode plays (Home chips, mode
  // picker, Learn cards); occurrences = its eligible occurrences in playable clips (the phenomenon page only)
data class FeedSequence(val mode: FeedMode, val items: List<ShortInfo>, val startIndex: Int)
```

### A.4 Requests (WP-09, WP-22, WP-23)

```kotlin
enum class RequestKind { LINK, FILE }
data class RequestProgress(val stage: Stage?, val percent: Int?, val queuePosition: Int?, val sentBytes: Long?,
  val totalBytes: Long?, val downloadPercent: Int?,
  val segment: Int? = null, val segments: Int? = null)   // "Analyzing 61 % · Hear the phones · phrase 3 of 10" (F-20)
data class RequestError(val code: String, val message: String?, val downloaderVersion: String?, val atSending: Boolean,
  val neededBytes: Long? = null)  // the space a refused copy needs: "Free {N} MB" (architecture 8.3)
// code: a request-level code (server-contract 8.1), a job code (8.2), or a phone-side copy code (architecture 8.3):
// "copy_no_space", "copy_unreadable", "copy_interrupted" (state COPY_FAILED), "copy_missing" (state COPY_MISSING)
data class RequestCounts(val waiting: Int, val onComputer: Int, val needsAttention: Int, val readyNew: Int,
  val analyzing: Int = 0, val inLine: Int = 0)   // onComputer split for the status card rows "1 analyzing", "1 in line" (F-03)
data class LinkInput(val sharedText: String, val subject: String?, val audioOnly: Boolean, val force: Boolean = false)
data class ParsedLink(val shared: String, val sent: String, val key: String, val site: Site)
data class Duplicate(val requestId: RequestId?, val jobId: JobId?, val title: String?)
sealed interface AddResult {
  data class Added(val id: RequestId) : AddResult
  data class DuplicateFound(val duplicate: Duplicate) : AddResult
  data object NoLink : AddResult
  data object OutOfSpace : AddResult                  // the insert itself failed (architecture 8.1)
}
data class FileSource(val uri: String, val displayName: String?, val mimeType: String?, val declaredBytes: Long?,
  val persisted: Boolean)                             // persisted: picked with a lasting permission
data class FileOptions(val audioOnly: Boolean, val separateDialogue: Boolean = true)
data class AddFilesResult(val added: List<RequestId>, val refused: List<FileRefusal>)
data class FileRefusal(val displayName: String?, val reason: FileRefusalReason, val neededBytes: Long?)
enum class FileRefusalReason { UNSUPPORTED_TYPE, TOO_LARGE, NO_SPACE, UNREADABLE, OWN_PROVIDER, TOO_MANY }
data class IntakeProgress(val id: RequestId, val copiedBytes: Long, val totalBytes: Long?, val done: Boolean,
  val failed: RequestError?)
data class SendReport(val sent: Int, val refused: Int, val transient: Int, val blocked: Boolean)
```

### A.5 Study (WP-14, WP-17, WP-21)

```kotlin
data class FeedPosition(val mode: FeedMode, val short: ShortId, val positionMs: Long, val updatedAt: Instant)
data class KeepWatching(val clip: ClipSummary, val short: ShortId, val positionMs: Long)
data class StudySet(val id: Long, val kind: StudySetKind, val date: LocalDate, val items: List<StudySetItem>, val cursor: Int,
  val answered: Int, val right: Int, val completedAt: Instant?)
enum class StudySetKind { TODAY, EXTRA }
sealed interface StudySetItem {
  data class Short(val short: ShortInfo, val spotlight: Spotlight?, val savedTag: Boolean, val dropped: Boolean,
    val done: Boolean = false) : StudySetItem   // done: the learner has moved past it in this set (Home's stations, D-45)
  data class QuestionItem(val question: Question, val choice: String?, val correct: Boolean?) : StudySetItem
}
data class Question(val kind: QuestionKind, val key: String, val prompt: String, val clip: ClipSpan,
  val context: ClipSpan?, val options: List<QuestionOption>, val answer: String, val change: ChangeLabel,
  val occurrence: Occurrence, val phrase: List<PhraseWord>?)  // QuizItem of web/src/features/practice/quiz.ts
enum class QuestionKind { CHANGE, FORM, COUNT }        // the web's ItemKind values "change", "form", "count"
data class QuestionOption(val id: String, val label: String, val ipa: String?, val change: ChangeLabel?)
data class PhraseWord(val text: String, val changed: Boolean)
data class AnswerResult(val correct: Boolean, val answer: String, val change: ChangeLabel, val explanation: Occurrence)
data class PracticeSession(val id: Long, val focus: ChangeLabel?, val weakSpots: Boolean, val questions: List<Question>,
  val index: Int, val answers: List<String?>, val createdAt: Instant, val finishedAt: Instant?)
data class ChangeAccuracy(val change: ChangeLabel, val seen: Int, val correct: Int)
data class SavedItem(val id: Long, val kind: SavedKind, val word: WordRef?, val short: ShortId, val change: ChangeLabel?,
  val savedAt: Instant, val lastWatchedAt: Instant?, val clipTitle: String, val available: Boolean)
enum class SavedKind { SHORT, WORD }
```

### A.6 Request and response bodies of `:core:network-api` (mirror server-contract 6 field by field)

```kotlin
data class PairRequest(val code: String, val deviceName: String, val appVersion: Int, val appVersionName: String,
  val previousDeviceId: DeviceId?, val previousTokenProof: String?)
data class LinkCreate(val clientRequestId: RequestId, val url: String, val audioOnly: Boolean, val separateDialogue: Boolean?)
data class UploadDeclaration(val clientRequestId: RequestId, val fileName: String, val bytes: Long, val sha256: String,
  val separateDialogue: Boolean?)
sealed interface UploadState {
  data class Open(val clientRequestId: RequestId, val offset: Long, val bytes: Long, val expiresAt: Instant,
    val maxChunkBytes: Long, val resumable: Boolean) : UploadState
  data class Done(val job: RemoteJob) : UploadState
}
sealed interface UploadChunk {                        // complete: PATCH
  data class Accepted(val offset: Long) : UploadChunk // 204 Upload-Offset
  data class Finished(val job: Created<RemoteJob>) : UploadChunk
}
data class RerunBody(val clientRequestId: RequestId, val audioOnly: Boolean?)
```

### A.7 Playback (WP-11, WP-17, WP-18, WP-30)

```kotlin
data class SpanPlaybackState(val span: ClipSpan?, val playing: Boolean, val positionMs: Long, val speed: Float,
  val track: Track, val loop: Boolean, val error: AppError.Media?)
data class FeedPlaybackState(val index: Int, val playing: Boolean, val speed: Float, val track: Track, val loop: LoopMode,
  val ended: Boolean, val holdSlow: Boolean, val videoShown: Boolean, val firstFrameRendered: Boolean,
  val error: AppError.Media?,
  val trackFallback: TrackFallback? = null)  // the other track plays, and why (architecture 11.1, spec D-47)
enum class TrackFallback { DIALOGUE_NOT_ON_PHONE, MIX_NOT_ON_PHONE, NO_DIALOGUE_TRACK }
sealed interface ShadowingState {
  data object Idle : ShadowingState
  data class Running(val round: Int, val of: Int, val phase: ShadowingPhase, val speed: Float) : ShadowingState
}
enum class ShadowingPhase { LISTEN, YOUR_TURN }
enum class PlaybackOwner { FEED, SPAN, QUIZ, RECORD_AB, SHADOWING }
data class DecoderInfo(val mime: String, val maxWidth: Int, val maxHeight: Int, val maxFps: Double?, val hardware: Boolean)
enum class MouthMode { FOLLOW_AUDIO, STEP, STILL }
data class RecordTarget(val word: WordRef?, val span: ClipSpan, val phrase: Boolean)
```

### A.8 Didactic content and reference (WP-04 delivers `didactic.json` schema 1 and this model by day 4)

```kotlin
data class GlossaryEntry(val key: String, val title: String, val body: String, val example: String?, val learnMore: LinkTarget?,
  val learnMoreLabel: String?)
sealed interface LinkTarget {                          // the extractor's href table (architecture 15.1)
  data object Learn : LinkTarget; data class Phenomenon(val label: ChangeLabel) : LinkTarget
  data class Ipa(val symbol: String?) : LinkTarget; data class Practice(val focus: ChangeLabel?) : LinkTarget
  data object Insights : LinkTarget
}
data class GlossaryHit(val key: String, val title: String, val snippet: String)
data class LessonContent(val change: ChangeLabel, val listenFor: String, val demo: MouthDemo?, val spanishTip: String?,
  val note: String?)
data class MouthDemo(val word: String, val dictionary: List<String>, val said: List<String>, val from: String, val to: String?)
data class IpaLayout(val places: List<IpaColumn>, val consonantRows: List<IpaGridRow>, val allophonePlaces: List<IpaColumn>,
  val allophoneRows: List<IpaGridRow>, val syllabics: List<String>, val vowelColumns: List<IpaColumn>,
  val vowelRows: List<IpaGridRow>, val reducedVowels: List<String>, val glidingGroups: List<IpaGroup>,
  val placed: List<String>, val unplaced: List<String>)
data class IpaColumn(val key: String, val label: String, val detail: String)
data class IpaGridRow(val label: String, val detail: String, val cells: Map<String, List<String>>)
data class IpaGroup(val label: String, val symbols: List<String>)
data class PhoneArticulation(val symbol: String, val name: String, val example: String, val cue: String, val manner: String,
  val voiced: Boolean, val gestures: List<Gesture>, val carry: Map<String, Double>?)
data class Gesture(val at: Double, val pose: Map<String, Double>)   // jaw, lipOpen, lipRound, lipTuck, body, height, tip, tipFront, root, velum, voice
data class Reference(val apiVersion: Int, val families: List<FamilyInfo>, val familyOf: Map<ChangeLabel, Family>,
  val labels: Map<ChangeLabel, String>, val descriptions: Map<ChangeLabel, String>, val practice: Map<ChangeLabel, Advice>,
  val lexicalPractice: Map<String, Advice>, val linkTypes: List<String>, val ipaTokens: List<String>,
  val metricsReference: Map<String, Double>, val metricLabels: Map<String, String>)
data class FamilyInfo(val family: Family, val label: String, val members: List<ChangeLabel>, val memberLabels: List<String>)
```

### A.9 Settings, messages, notifications and navigation (WP-02, WP-24; `:core:navigation` for the last block)

```kotlin
data class UserSettings(val appearance: Appearance = Appearance.SYSTEM, val colors: ColorChoice = ColorChoice.STANDARD,
  val patterns: PatternChoice = PatternChoice.FOLLOW_COLORS, val motion: MotionChoice = MotionChoice.FOLLOW_SYSTEM,
  val defaultSpeed: Float = 1f, val playEachShort: Boolean = true, val endOfShort: EndOfShort = EndOfShort.REPLAY_ONCE,
  val captions: CaptionMode = CaptionMode.MARKS, val listenFirst: Boolean = false, val track: Track = Track.MIX,
  val excludedFromMix: Set<JobId> = emptySet(), val questionsAfterSet: Boolean = true,
  val lessonGuidance: LessonGuidance = LessonGuidance.FULL, val downloadVideos: Boolean = true,
  val storageBudget: StorageBudget = StorageBudget.Limit(2_000_000_000), val notifyReady: Boolean = true,
  val notifyAttention: Boolean = true, val welcomeDone: Boolean = false, val notificationsAsked: Boolean = false)
enum class Appearance { SYSTEM, LIGHT, DARK }
enum class ColorChoice { STANDARD, COLOR_VISION_FRIENDLY }
enum class PatternChoice { FOLLOW_COLORS, ON, OFF }
enum class MotionChoice { FOLLOW_SYSTEM, REDUCE }
enum class EndOfShort { REPLAY_ONCE, STOP, NEXT }
enum class CaptionMode { MARKS, PLAIN, IPA, OFF }
enum class LessonGuidance { FULL, COMPACT }
@JvmInline value class CoachMarkId(val value: String)  // the catalogue is WP-13's
data class UserMessage(val id: Long, val kind: UserMessageKind, val args: List<String> = emptyList())
  // args: TOO_LATE_TO_CANCEL and CANCEL_UNDONE carry the request title ("Cancel undone: “{title}” won't be cancelled.",
  // D-47); CLIPS_REMOVED the count; the others none
enum class UserMessageKind { CLIP_GONE, REQUEST_REMOVED, LIBRARY_CHANGED, CLIPS_REMOVED, TOO_LATE_TO_CANCEL, CANCEL_UNDONE }
enum class TransferKind { UPLOAD, DOWNLOAD, SHARE_COPY }
data class TransferProgress(val title: String, val doneBytes: Long, val totalBytes: Long?, val items: Int)
enum class NotificationPermission { NOT_ASKED, GRANTED, DENIED, NOT_NEEDED }   // NOT_NEEDED below Android 13

// :core:navigation
enum class Tab { HOME, WATCH, LEARN, PRACTICE }
sealed interface FeedStart {                           // WatchKey.start (architecture 16.1)
  data object TodaySet : FeedStart; data object Resume : FeedStart
  data class InOrder(val jobId: JobId, val firstSegment: Int?, val fromStart: Boolean) : FeedStart
  data class ChangeMode(val label: ChangeLabel, val onlyClip: JobId?) : FeedStart
  data object SavedMode : FeedStart
  data class AtWord(val jobId: JobId, val segment: Int, val word: Int, val openLesson: Boolean) : FeedStart
}
sealed interface NavTarget {                           // architecture 16.4; serializable
  data class ClipFeed(val jobId: JobId) : NavTarget; data class RequestDetail(val requestId: RequestId) : NavTarget
  data object Requests : NavTarget; data object Home : NavTarget
  data class WordLesson(val jobId: JobId, val segment: Int, val word: Int) : NavTarget
}
```
