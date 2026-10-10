# PhonoTrainer for Android: product specification

Status: v1 specification, revision 3, 2026-10-10. Owner: product owner. Revision 1 consolidated three independent
drafts (learner-first, reliability-first, feed-first) into one set of decisions. Revision 2 answers two reviews
(requirements coverage, evidence and over-reach): every blocker and high finding is either fixed in place or
answered with evidence in 15.5. Revision 3 closes, before implementation, every request the architecture and design
tracks addressed to the product track (decisions D-37 to D-52, 15.6).

Audience: the design agent (content, hierarchy, states and interactions are fixed here; visuals are not), the
architect, the engineers and QA.

Sources, cited in square brackets:
- **[Brief]** the owner's brief: the authority. Quoted when a decision rests on it.
- Research notes of the planning phase: **[WF]** web features, **[WI]** web identity, **[BE]** backend contract,
  **[AR]** Artemi reference app, **[TC]** toolchain, **[AD]** addendum (the addendum wins wherever the notes
  disagree). These notes are working files and are **not part of the repository**; every fact a builder needs is
  restated in this document, and facts taken from code carry a `path:line` reference into this repository.
- **[L]**, **[R]**, **[F]**: the learner-first, reliability-first and feed-first drafts (compared in 15.1).
- **Measured**: re-checked read-only on the **reference data set**: the 10 finished analyses on the development
  computer. Only aggregate figures are recorded here (no titles, file names, paths, addresses or dates).

Conventions
- MUST / SHOULD / MAY as in RFC 2119.
- Release tiers (14.1): **v1-core** (built first; covers every product requirement of the brief), **v1-complete**
  (built next; each item has a named fallback if the schedule forces a cut, 14.4), **Later** (backlog, 14.5).
  "v1" alone means both tiers.
- IDs: `F-nn` feature, `S-nn` server requirement, `A-nn` accessibility, `NFR-nn`, `SC-nn` success criterion,
  `D-nn` decision, `Q-n` owner question. Every acceptance criterion (AC) has an ID: `F-06-AC3` (feature ACs),
  `SH-ACn` (share target, 8.8), `QT-n` (queue torture test, 7.12), `X-ACn` (product-wide, 13.1). ACs are
  Given/When/Then and each becomes an automated test or a scripted QA step; section 16 traces brief sentences to
  AC IDs.
- **Where an AC is verified** (brief: device QA "must only interact with this app and must restore any changed
  device setting"; NFR-14):
  - By default an AC is verified by automated tests: JVM unit tests, or instrumented tests on the API 36 emulator
    against the **fake computer** (a test double that can be unreachable, slow, answer 4xx or 5xx, drop responses,
    and serve partial downloads).
  - ACs marked **[emulator]** change device state (reboot, airplane mode, Wi-Fi off, clock change) and are verified
    on the API 36 emulator only.
  - On the **test phone**, QA simulates "computer unreachable" only by stopping the server or turning phone access
    off. It never reboots the phone, toggles its radios (airplane mode, Wi-Fi, mobile data) or changes its clock.
    System settings that the brief allows (font scale, animation scale, TalkBack) are changed only through adb,
    logged, and restored. A reboot happens only if the owner does it himself (Q-7).
  - No link is ever sent to the real computer except by the owner's own share from the test phone (8.7). Tests
    that inject share intents run against the fake computer.
- **Test phone**: the owner's phone named in the brief, a mid-range Samsung phone with One UI on Android 16 / API
  36 (not re-verified: the phone was not attached during research [AD 1.3]).
- Text in double quotes is proposed UI copy, in English (repository rule: everything in English, no
  translations). "(web)" marks copy reused verbatim from the web client, to keep one voice across clients. The
  design agent may shorten copy but MUST keep its meaning.
- **UI vocabulary** (D-21): the server is "your computer"; an analysed file is a "clip"; a feed card is a
  "short"; a sentence-sized unit is a "phrase"; a phenomenon is a "change"; something sent for analysis is a
  "request". The words "server", "job", "segment", "token", "endpoint" never appear on screen outside
  Settings > Your computer > Connection details. "Analyze" / "analysis" are allowed (web precedent).
- Out of scope: colors, fonts, pixel layouts, motion curves (design agent); code architecture (architect); any
  change under `web/` (owned by another agent; D-35).

---------------------------------------------------------------------------------------------------------------

## 1. Vision and product principles

### 1.1 Vision

PhonoTrainer for Android turns the clips the learner chooses into a vertical feed of short, captioned moments
that teach how English is really spoken: which sounds weaken, merge, vanish or run together, and which of those
changes are safe to copy. The computer at home does the heavy analysis; the phone is where the learner swipes,
listens, slows down, repeats and practises, at home or away, with everything already downloaded working offline.

### 1.2 Why a feed

- The owner asks for it: the app "works, in general, like TikTok or YouTube Shorts — vertical videos you swipe
  through — BUT it must also have a dashboard / main panel to navigate between the videos" [Brief].
- The data already has the right grain. A phrase (transcript segment) lasts a median 1.3 to 3.8 s per clip
  (measured), too short to swipe one at a time; runs of consecutive phrases make moments of 10 to 25 s, the
  length of a short, and today's 10 clips already yield about 140 shorts (measured with the rule in F-06). A
  real feed exists from day one.
- Connected speech is learned by many short exposures to the same change in different voices. A swipeable feed
  gives volume and variety; the word lesson gives depth; they are one tap apart.
- Replay, slow down and repeat-after-me are the core study gestures, and a phone in the hand is the best device
  for them [WF 9.1].

### 1.3 Product principles (every screen and feature is checked against them)

1. **Every short teaches one named thing.** Each short has one "Listen for" spotlight: one change, on one word,
   with the dictionary form, what was said and whether it is "Safe to produce" or "Recognize only" (web).
2. **Every mark is a door.** Any marked word opens its lesson. Nothing on screen is unexplained jargon: terms
   carry "What's this?".
3. **Notice, compare, imitate, meet again.** The five-step word lesson (Listen, Compare, Why it changes, See the
   mouth, Practice; web) is the spine; the feed is the doorway; saved shorts come back in later sets.
4. **Slow and repeat are one gesture away**, and every gesture also exists as a labelled button.
5. **Describe, don't grade.** "This is a description, not a grade." (web). No pronunciation score anywhere.
   Practice measures the learner's ear, never their mouth.
6. **Copy what is safe, recognize the rest.** The report's advice (`reference.practice`) decides whether the app
   invites imitation or only recognition.
7. **Finite by design.** Sessions come in sets with an end; nothing moves to the next short or clip by itself
   unless the learner turns that on; no streaks, no guilt, no infinite recommendations.
8. **Never color alone.** The web identity was built for learners with color-vision deficiency [WI]. Every
   family, status and answer is shown by icon and word (and line style); color is a third cue.
9. **Your clips, your computer, nobody else.** No account, no cloud, no analytics; the phone talks only to the
   paired computer on the home network.
10. **Offline-first, honest online.** Everything on the phone works with no network. The app never claims the
    computer is reachable unless it answered as the paired computer within the current probe interval, and says
    "Checking your computer…" (a claim of nothing) while it is unsure (7.5).
11. **Never lose a request, never do it twice.** A link or file handed to the app is on the computer, or
    visibly waiting on the phone with a reason, or visibly failed with a way out. Silent loss and duplicate
    analyses are defects.
12. **Failure is a state, not an error dialog.** The link download path has 0 recorded successes on the
    development computer [AD 2.1]; "download failed" is a designed, recoverable state.
13. **Rich and robust.** "It is very important that it is NOT minimalist, and that it is robust." [Brief] Every
    screen explains itself in a sentence; every state in this document is designed; richness comes from content
    and imagery, not from adding hue to controls [WI 12].
14. **Continuity with the web, not a copy.** "Clearly it won't be 1:1 with the web client, but it won't be
    something improvised either." [Brief] Didactic devices, vocabulary and copy come from the web; the phone
    changes their form. Every device with no web precedent is listed and justified (9.6).

### 1.4 What the app is NOT

- Not a social or discovery feed: no content from other people, no likes, comments, followers, recommendations
  from the internet.
- Not a video player or downloader for watching: a shared link is sent to the computer for analysis only; the
  phone never fetches YouTube or X media itself.
- Not an analyzer: transcription and phone recognition stay on the computer [BE 1].
- Not a pronunciation grader, and not a points or streak game.
- Not a remote control for the computer's disk: no file browser, no import of folders, no Review mode, no
  report.html [WF 9.3].
- Not reachable from the internet, not commercial ("an entirely local, non-commercial project" [Brief]), not on
  any store, and English-only.

---------------------------------------------------------------------------------------------------------------

## 2. Context of use and personas

### 2.1 Persona A: the learner (primary)

- Target learner: an adult whose first language is Spanish, intermediate or better English: follows slow, clear
  English but loses fast native speech (weak forms, flaps, dropped sounds, linked words). Watches series, YouTube
  and X in English; the reference data set is mostly TV dialogue [WF 9.4]. The web content already carries tips
  for Spanish speakers (`web/src/features/learn/content.ts`, 3 changes) and IPA notes framed for them [WF 4.2].
- The design serves learners with color-vision deficiency: the web identity was built for that case [WI], and the
  phone keeps every rule of it (section 10).
- Studies in short slots (commute, queue, before sleep), often with earphones and often away from home Wi-Fi.
- Collects material opportunistically: while scrolling YouTube or X on the phone, sees a scene worth studying and
  wants to hand it over in two taps [Brief share feature].
- Needs from a 5-minute session: open the app, press one button, hear a handful of shorts with one change each,
  open a lesson or two, repeat a few phrases aloud, answer a few listening questions, and close the app knowing
  what was practised. Over weeks: meet every change many times in different voices, and see saved shorts again.
- Device: the test phone (conventions): a mid-range Samsung phone with One UI on Android 16 / API 36.

### 2.2 Persona B: the operator (the same person, at the computer)

- Runs `phonotrainer ui` on a Linux desktop at home; comfortable with a terminal, wants few manual steps.
- Expects the browser client to keep working exactly as today and does not want the computer exposed to other
  devices on the Wi-Fi unless they choose so.
- Publishes the repository: nothing personal may be committed [Brief].

### 2.3 The computer as a participant

- Not a 24/7 server: it may be asleep, off, or running PhonoTrainer without phone access; its LAN address may
  change (DHCP) [AD 4.7].
- Processes one analysis at a time, in arrival order; an analysis takes 0.78x to 1.28x the clip's duration
  (measured on 9 jobs, CPU, separation on) and the first one after a start loads about 1.8 GB of models
  [BE 1, WF 3.3]. While it analyzes, its CPU is saturated for minutes (separation alone took 6.5 min on the
  longest clip), so it may answer slowly.
- A desktop firewall may block mDNS (UDP 5353); the development computer's does [AD 1.2]. The product therefore
  uses one fixed unprivileged UDP port and one TCP port and never requires a firewall change.
- Today it binds to 127.0.0.1 (`phonotrainer/cli.py:247`), has no authentication, and `GET /api/health` returns
  the absolute workspace path (`phonotrainer/server.py:245`) [BE 15, AD 4.3].

### 2.4 Contexts of use

| Id | Context | What works | What the app says |
|---|---|---|---|
| C1 | Home Wi-Fi, computer on, phone access on, paired | everything | "Connected to {computer}" + sync status |
| C2 | Away (mobile data or another Wi-Fi) | all study on the phone; sharing creates waiting requests | "Not on Wi-Fi" or "Your computer isn't reachable", and that requests wait on this phone |
| C3 | Home Wi-Fi, computer asleep, off, or phone access off (or not approved for this network, 7.2) | same as C2 | "Your computer isn't reachable · last seen {time}" + "Why?" checklist |
| C4 | Inside YouTube or X, any network | share → analyze now (C1) or wait on the phone (C2, C3) | in the share confirmation, before the learner confirms |
| C5 | Computer busy with a long analysis | study; new requests wait in line on the computer | queue position and an honest estimate |
| C6 | No network at all | all study on the phone | "Offline · requests wait on this phone" |

### 2.5 Material facts the product must design for (reference data set)

| Fact | Value | Source |
|---|---|---|
| Finished clips | 10 (plus 3 failed link jobs, all created from the browser client) | [AD 3] |
| Durations | one 42.7 s clip, eight of 229 to 282 s, one of 614 s | [AD 2.3] |
| Media kinds | 6 landscape video (5 at 1280x720, 1 at 1920x1080), 1 portrait 2160x3840, 3 audio-only | [AD 2.2] |
| Video codec | every video original is AV1 (720p originals 9.7 to 15.1 MB; the 4K portrait one 65.6 MB); AV1 decoding on the test phone is unverified | [AD 2.2] |
| Video timeline | every original starts at time 0, so its timeline equals the analysed audio's; nothing in the code guarantees this | [AD 2.2] |
| Original files | the downloader's originals live in the project's ignored `downloads/` folder; a path job's original lives wherever it was (one is outside the project) and may disappear | [BE 5, AD 2.2] |
| Words / phrases per clip | 182 to 1,489 words; 27 to 232 phrases; phrase median 1.3 to 3.8 s, longest 12.4 s | [AD 2.3], measured |
| Engine | every analysis is TIMIT-61, rules version 3, dialogue separation on; no espeak analysis exists | [AD 2.3] |
| Server artifacts per 4-min clip | about 18 MB (2 MB JSON + two 8 MB WAVs) plus the original | [BE 5] |
| Link downloads | 0 of 3 worker downloads succeeded (HTTP 403); X never attempted; no JS runtime is configured for the downloader; its format choice picks AV1 even when H.264 is offered (`phonotrainer/download.py:21`) | [AD 2.1, BE 9] |

---------------------------------------------------------------------------------------------------------------

## 3. Goals, non-goals, success criteria

### 3.1 Goals (v1)

- G1. A vertical feed of shorts built from every clip on the phone, each with a spotlight, marked captions,
  slow-down, replay, loop, shadowing and the lesson one tap away (F-06 to F-13).
- G2. A dashboard (Home, Library, Clip page) to navigate between clips and enter the feed anywhere [Brief].
- G3. A daily study loop of about 5 minutes: a finite set of shorts, retrieval questions, and saved shorts that
  come back in later sets (F-08, F-09, F-17).
- G4. Process clips from the phone: links shared from YouTube and X (and other apps) and local video or audio
  files, sent to the computer when it is reachable and kept in a durable device queue otherwise [Brief] (F-19,
  F-20, section 8). Local files and the queue work regardless of the downloader; whether link analysis succeeds
  depends on the computer's downloader, which v1 hardens (S-23) and proves on the owner's own shares (S-20, 8.7).
- G5. Automatic sync: what the computer has and the phone lacks is downloaded, within a storage budget the
  learner controls [Brief] (F-21, section 7).
- G6. Every web didactic device that makes sense on a phone (section 9), in v1-core or v1-complete (14.1).
- G7. Safe connection: phone access is an opt-in on the computer, phones are paired and authenticated, nothing
  sensitive is committed [Brief] (sections 7, 11, 12).
- G8. Accessible to learners with color-vision deficiency and to TalkBack, switch and large-text users
  (section 10).
- G9. Built to the Artemi standard of craft (tests, lint, release hygiene, device QA) [Brief, AR 10.1].

### 3.2 Non-goals (v1)

- Analysis on the phone; access to the computer from outside the home network; any cloud or relay.
- Syncing learner progress (practice stats, saved items, seen shorts, settings) with the web client (Q-3).
- Deleting finished analyses on the computer from the phone (Q-2); editing analyses; Review mode; report.html;
  the computer's file browser; folder import; engine and speech-model choices.
- Any change under `web/`, including a browser page for pairing (D-35).
- Several paired computers at once; switching computers other than by "Forget this computer"; several learners
  per phone.
- Streaming media from the computer into the feed (the feed plays only files on the phone, D-12).
- Targeting API 37 and its local-network permission flow (D-18).
- Any non-English UI (no `values-*` translations); publishing on a store.

### 3.3 Success criteria (measured in QA on the test phone and the API 36 emulator, release build)

| Id | Criterion | Target |
|---|---|---|
| SC-01 | Cold start with clips on the phone and the computer unreachable (test phone: server stopped; emulator: airplane mode), to the first sound of Today's set | ≤ 2 taps and ≤ 3 s |
| SC-02 | Withdrawn in revision 2: it measured the speed of a QA script, not the app (15.5) | — |
| SC-03 | Taps from first launch to the first short playing, including pairing (computer already in phone-access mode), along the designed path | ≤ 8 taps (code digits not counted) |
| SC-04 | Requests lost (handed to the app but neither on the computer nor visible on the phone) | 0 in the queue torture test (7.12) |
| SC-05 | Duplicate analyses created by retries or replays | 0 |
| SC-06 | Share sheet: taps after choosing PhonoTrainer to a stored request; time from "Analyze" to the request reading "Sent to your computer" for a link when connected (fake computer in automated runs; the owner's own shares on the real one) | ≤ 2 taps; median < 3 s |
| SC-07 | A clip finished on the computer (a file request or an existing clip; never a link sent by QA) is "On this phone" (study pack, 4-min clip) after the phone notices it, home Wi-Fi | < 60 s with the app open. With the app closed: best effort; Android and One UI may defer background work, so no UI copy ever promises a time (F-21) |
| SC-08 | Swipe to sound for a short on the phone | ≤ 300 ms at p90 over 50 swipes |
| SC-09 | Caption word highlight vs heard audio | within 60 ms |
| SC-10 | Wrong connectivity claims in QA scripts. "Connected" is shown at most one probe interval + one probe timeout after the computer stopped answering; then "Checking your computer…" (no claim) until the re-probe 5 s later decides. "Isn't reachable" is never shown while the computer answers, except for the 5 s + one timeout re-probe (7.5) | 0 violations of these bounds |
| SC-11 | A partial or corrupt download shown as "On this phone" | 0 |
| SC-12 | Unlabelled interactive elements and targets under 48 x 48 dp | 0 on every screen (automated a11y script) |
| SC-13 | TalkBack can complete: share → wait → sync → watch → lesson → quiz | yes, scripted (fake computer on the emulator; on the test phone with TalkBack turned on through adb and restored) |
| SC-14 | Failure states of this phone's own requests that leave the learner without an action | 0 (every one offers at least one of Try again / Try audio only / Remove / Connect again). Jobs created from the browser client are read-only on the phone and excluded (F-20) |
| SC-15 | Sensitive values in committed files (addresses, tokens, codes, serials, home paths, keystores, personal data) | 0 (checks in 11.7, X-AC4) |
| SC-16 | Product requirements of the brief without at least one passing AC id | 0 (section 16). Process directives of the brief ("analyze", "make a work plan", "delegate", "design agent") have no product AC; the orchestrator verifies them and section 16 says so |

Learning indicators are shown to the learner on the phone only (no telemetry): per-change listening accuracy (web),
changes met, and saved shorts (F-17).

---------------------------------------------------------------------------------------------------------------

## 4. Glossary of product terms

| Term | UI word | Meaning | Backend source |
|---|---|---|---|
| Clip | "clip" | One analysed video or audio file: transcript, phones, changes, audio and optionally video. | a job with `status: done` and its `analysis.json` |
| Analysis / processing | "Analyzing" | The work the computer does to turn a link or file into a clip. | job `queued` / `running` |
| Phrase | "phrase" | One transcript segment, a few seconds of speech with word timings. Never split by the app. | `segments[]` |
| Short | "short" | One feed card: consecutive phrases of one clip, normally 5 to 30 s (rule in F-06). | derived on the phone |
| Word | "word" | One spoken word with its dictionary form and what was said. | `segments[].words[]` |
| Change (phenomenon) | "change", or its name | One of 16 labelled connected-speech processes, e.g. flapping, linking. | `reference.labels` |
| Family | family name | Reduction, t/d processes, Assimilation, Word boundary; plus whole-word changes outside families. | `reference.families`, `family_of` |
| Mark | (visual) | The encoding of a change on a word: line style + icon + name (+ family color). | `phenomena[]` per word |
| Practice advice | "Safe to produce" / "Recognize only" | Whether the report advises imitating a change, with its register (universal, casual, marked) and why. | `reference.practice`, `lexical_practice` |
| Spotlight | "Listen for" | The one change, on one word, that a short asks the learner to notice. | rule in F-07 |
| Mode | "Mix", "In order", change name, "Saved" | Which shorts the feed shows and in what order. | on the phone |
| Set | "Today's set", "set" | A finite block of the Mix: shorts, then questions, then a summary card. | on the phone |
| Saved item | "Saved" | A short or a word the learner chose to save; saved shorts come back in later sets (F-09). | on the phone |
| Lesson | "lesson" | The five-step word lesson. | web `WordLesson` |
| Your computer | "your computer" | The PhonoTrainer server (`phonotrainer ui`) on the owner's computer. | |
| Phone access | "phone access" | The opt-in server mode that accepts paired phones from the home network. | NEW S-01 |
| Pairing | "Connect to your computer" | One-time exchange (discovery or a typed address, then a 6-digit code shown in the computer's terminal) that makes phone and computer trust each other. | NEW S-03, S-04 |
| Device credential | (not shown) | Secret created at pairing and kept on both sides. After pairing it never travels again: the phone signs every request with it, and the computer proves its identity with it (7.4). Revocable on the computer. | NEW S-04, S-05 |
| Online | "Connected" | The paired computer answered within the current probe interval, proved it is the paired computer, accepted this phone's signed requests and has a compatible version (7.5). | NEW S-02 |
| Request | "request" | Something the learner asked the computer to process: a link or a file. | on the phone, then a job |
| Device queue | "Waiting on this phone" | Requests stored on the phone and not yet accepted by the computer. | on the phone |
| Line | "Waiting in line" | Requests accepted by the computer and not started (one analysis at a time). | job `queued` |
| Sync | "Syncing" | Bringing the phone's catalogue and downloads in line with the computer and sending waiting requests. | section 7 |
| Catalogue | (Library) | Metadata of every clip the computer has, mirrored on the phone. | NEW S-11 listing |
| Study pack | "On this phone" | What must be on the phone to study a clip offline: clip data + the audio of the chosen track (compressed when the computer provides it, otherwise the WAV). | existing artifacts; NEW S-12 renditions when available |
| Video layer | "Video on this phone" | The optional video of a clip, downloaded after the study pack: the original when this phone can play it, otherwise a phone video the computer makes on request. | `/media` (exists); NEW S-12 |
| Rendition | (not shown) | A phone-friendly copy produced by the computer (compact clip data, compressed audio, poster, and a phone video only when the original can't be played). Each has a fallback when absent. | NEW S-12 |
| Kept clip | "Keep on this phone" | A clip the learner protected from automatic cleanup. | on the phone |
| Removed from this phone | "Not on this phone" | Local copy deleted by the learner; listed, not downloaded again unless asked. | on the phone |
| Library id | (not shown) | Identifier of the computer's workspace, so that a switch of workspace is never mistaken for mass deletion (7.9). | NEW S-02 |
| Idempotency key | (not shown) | Id the phone attaches to a request so a replay never creates a second analysis. | NEW S-09 |

---------------------------------------------------------------------------------------------------------------

## 5. Information architecture

### 5.1 Destinations

Top level: a persistent bottom navigation bar with four labelled items (icon + word) [L, R, F agree]:

| Id | Tab | Purpose |
|---|---|---|
| T1 | "Home" | The dashboard / main panel: computer status, today's set, continue, add a clip, clips, watch by change, practice, an Insights teaser (F-03). Start destination. |
| T2 | "Watch" | The vertical feed (F-06 to F-08). Opens the last mode and position; with none, the Mix at today's set. |
| T3 | "Learn" | Changes by family, phenomenon pages, IPA chart, "Words we use" glossary index, Insights (F-14 to F-16, F-18). Works before any clip exists. |
| T4 | "Practice" | Ear-training quiz, saved shorts, accuracy per change (F-09, F-17). |

Secondary destinations (pushed on top of a tab; system Back returns):

| Id | Destination | Reached from |
|---|---|---|
| P-01 | Library (all clips) | Home "All clips", Home status card counts |
| P-02 | Clip page (tabs Shorts, Transcript, Summary; Transcript is v1-complete) | Home, Library, the clip line of a short, notifications |
| P-03 | Requests | Home status card, Library "In progress", notifications, share confirmation |
| P-04 | Request detail | Requests |
| P-05 | Add a clip | Home "Paste a link" / "Choose a file", empty states |
| P-06 | Connect to your computer | first run, Home status card, Settings |
| P-07 | Connection details | Home status card "Why?", Settings > Your computer |
| P-08 | Phenomenon page | Learn, any change chip, lesson step 3, quiz explanation |
| P-09 | IPA chart (+ symbol sheet) | Learn, any IPA sound in a lesson |
| P-10 | Insights (v1-complete) | Learn, Home teaser |
| P-11 | Saved | Practice, Home practice card, feed mode picker |
| P-12 | Settings (and its sub-pages Storage, Your computer, Notifications, About and licences) | Home top bar |
| P-13 | Welcome | first launch only; "Restart the tour" in Settings |

Where each web route goes (web routes from `web/src/routes.tsx`, `web/src/paths.ts`; [WF 1.4]):

| Web route | Web page | On the phone |
|---|---|---|
| `/` | Library | P-01 Library (all clips, with availability on this phone) and the Home shelf; "Continue where you left off" becomes Home "Keep watching" |
| `/new` | New analysis | P-05 Add a clip and the share target (section 8): links and phone files only; server paths, the disk browser and engine choices are dropped (9.7) |
| `/analysis/:id` (not done) | progress view and stepper | P-04 Request detail with the same stepper and teaching lines (F-20) |
| `/analysis/:id` (done) | two-pane workspace | P-02 Clip page plus the feed "In order" on that clip (F-05, F-06) |
| `/analysis/:id/w/:segment/:index` | word lesson | the lesson sheet over the feed, same addressing job + segment + word (F-10) |
| `/learn` | Learn | T3 Learn (F-15) |
| `/learn/:phenomenon` | Phenomenon page | P-08 (F-15), with "Watch examples" |
| `/learn/ipa?symbol=` | IPA chart | P-09 (F-16) |
| `/practice?focus=` | Practice | T4 Practice with the same focus chip (F-17) |
| `/insights` | Insights | P-10 (F-18); the occurrences table becomes change modes (9.7) |
| `/settings` | Settings | P-12 (F-22), plus Your computer, Storage, Notifications |
| `*` | Not found | a missing target opens the nearest parent with a notice (5.4) |

Sheets and overlays (not destinations; closing returns exactly where the learner was, paused): word lesson
(F-10, expandable to full height), phrase details (F-11), "What's this?" (F-14), feed mode picker (F-06), short
"More" sheet (F-07), share confirmation (section 8, shown over the sharing app), coach marks (F-01),
confirmation dialogs.

### 5.2 Navigation model: dashboard and feed

The owner asks for both a swipe feed and "a dashboard / main panel to navigate between the videos" [Brief].
Both are one tap apart at all times.

- **Home → Watch**, each path landing in a defined mode and position:
  1. Today's set card "Start" / "Continue" → Mix at the current position of today's set.
  2. "Keep watching" card → the mode and short the learner left.
  3. A clip's play action (Home shelf, Library row, Clip page "Watch") → mode "In order" for that clip at the
     short where the learner left it (short 1 if never opened); "Watch from the start" is explicit.
  4. A "Watch by change" chip → that change's mode (topic feed).
  5. The Watch tab → the last mode and position; with none, the Mix.
- **Inside Watch**: vertical swipe (and "Next short" / "Previous short" buttons and accessibility actions) moves
  one short within the current mode. The mode picker at the top of the feed lists the modes, the 16 changes by
  family with short counts, Saved, and every clip on the phone (a dashboard inside the feed). Tapping the clip
  line of a short opens its Clip page. No horizontal swipe gestures (D-14).
- **Watch → dashboard**: the Home tab, or Back from a feed entered from Home, the Library or a Clip page, which
  returns there at the same scroll position (F-03-AC8, F-04-AC5).
- **Feed ↔ lesson**: tapping a word or the spotlight pauses the short and opens the lesson sheet; closing it
  (swipe down, Back, close) returns to the same short, paused at the same time. "Next change" inside the lesson
  may move to a later phrase; on close the feed shows the short that contains the last studied word.
- **Clip page → Watch**: tapping a short in the Shorts tab, or a phrase time in the Transcript tab, opens the feed
  "In order" at that short; Back returns to the Clip page.
- **Back**: closes the top sheet first; then pops the secondary destination; from a tab root goes to Home; from
  Home leaves the app. Reselecting the current tab returns to its root, except Watch (reselecting does nothing,
  no surprise jumps).
- **State that survives rotation, tab switches and process death**: feed mode, short, playback time, speed,
  caption mode; open lesson and its step; quiz question; today's set progress; drafts (a pasted link) [AR 3.4].
- **Orientation**: portrait is the designed orientation; rotation and split screen MUST NOT lose state or
  content (screens reflow). A dedicated full-screen landscape video mode is Later.

### 5.3 Feed modes

| Mode | Content | Order | Ends with | Entered from |
|---|---|---|---|---|
| "Mix" (default) | Shorts with a spotlight, from every clip on the phone | Always delivered in sets (F-08): saved shorts not watched for 3+ days, unseen shorts, then shorts not seen for 7+ days; at most 2 consecutive shorts from the same clip; no two consecutive shorts with the same spotlight change when avoidable; a set never changes once generated | the set's questions and summary card | Watch tab, Home today's set |
| "In order" | Every short of one clip, including shorts without a spotlight | Timeline order | "End of {clip}" card | clip play action, Clip page, "Ready to watch" notification |
| change (topic), e.g. "Flapping" | Shorts containing a reliable instance of that change, across clips; spotlight forced to that change | Clearest instance first (lowest `diff_cost`), one short per distinct word first, then the rest | "That's every “flapping” in your clips." card | Home "Watch by change", phenomenon page "Watch examples", lesson step 3, quiz "Watch more like this" |
| "Saved" | Saved shorts | Most recently saved first | "That's all your saved shorts." card | Saved screen, mode picker |

### 5.4 Navigation targets for notifications and the share flow (internal only)

Notifications and the share confirmation open precise places. These are internal navigation targets: **no URI
scheme is registered and nothing is exported** for them (attack surface, 11.4). They carry only server ids,
never addresses or credentials. A target that no longer exists opens its nearest parent with a notice.

| Opened from | Target | If the target is missing |
|---|---|---|
| "Ready to watch" notification, "New on this phone" card | the feed "In order" on that clip at its resume short | Home + "That clip is no longer on this phone." |
| "Needs attention" notification | that request's detail | Requests + "This request was removed." |
| Ongoing transfer notification; share confirmation "See requests" | Requests | n/a |
| Share confirmation "Open PhonoTrainer" | Home | n/a |
| A word inside the app (lesson cross-links, Learn and Practice examples) | the feed "In order" at the short containing that word + the lesson sheet on it (same addressing as the web's `/analysis/:id/w/:segment/:index`) | the Clip page, or Library + "That clip is no longer on your computer or this phone." |

---------------------------------------------------------------------------------------------------------------

## 6. Feature specifications

Every feature lists purpose, user stories, behaviour, states, acceptance criteria, data and priority. States use
one vocabulary:
- **First run**: never paired, nothing on the phone.
- **Empty**: nothing to show in this view (paired or not).
- **Loading**: local data is being read; a placeholder appears only after 300 ms and a text label after 1 s
  (web `PageLoading` pattern); never an unlabelled spinner.
- **Offline**: no network, or no Wi-Fi/Ethernet (the computer is reachable only on the home network).
- **Unreachable**: on Wi-Fi, but the paired computer does not answer, answers as another computer, rejects the
  credential, or is incompatible (7.5 lists each).
- **Error**: an operation failed; the copy says what happened and what to do, calmly ("Errors say what happened
  and what to do next" [WI 10]).
- **Partial**: some parts are present and others not (clip data without the video; analysis without optional
  fields; a video, poster or compact rendition not yet available from the computer, which never holds the clip
  back, 7.8).

Data columns cite existing endpoints and fields from [BE] or mark **NEW S-nn** (section 12).

---

### F-01 First run, welcome and coach marks (v1-core)

**Purpose.** In under a minute, say what the app does, connect the computer (or skip), and reach a playing
short. Replaces the web's 10-step driver.js tour [WF 4.1] with a phone-native welcome plus contextual coach
marks.

**User stories**
- As a new learner, I want to understand what a "change" is and what I will do every day before I am asked for
  anything.
- As a learner away from home on first launch, I want to skip connecting and still explore Learn and share links
  into the queue.
- As a returning learner, I want to replay the tips.

**Behaviour**
1. First launch from the launcher shows the **Welcome**: 3 pages, each with an illustration, a title and one
   sentence; "Skip" on every page, "Next" and, on the last page, "Get started":
   - "Hear English as it's really spoken" / "PhonoTrainer shows how native speakers change words when they
     talk, and which of those changes you can copy."
   - "Your clips become shorts" / "Share a video from YouTube or X, or pick one on this phone. Your computer
     analyzes it; your phone turns it into shorts you can swipe, slow down and repeat."
   - "A few minutes a day" / "Each day you get a short set: listen, notice one change, say it back, then answer
     a few questions. A few minutes a day is enough." (last sentence: web)
2. Then **Connect to your computer** (F-02) with "Not now" ("You can connect later from Home.") → Home.
3. After pairing, the first sync shows "Bringing your clips to this phone: 3 of 10"; "Start watching" becomes
   available as soon as one clip is On this phone (the learner never waits for all clips).
4. **Coach marks**: one at a time, the first time a screen is shown, each dismissible with "Got it", never
   pausing or blocking playback, never on a timer:
   - Watch: "Swipe up for the next short." · "Tap any underlined word to see how it was really said." · "Press
     and hold the picture to slow down." · "The line style and the small icon tell you the kind of change, so
     you never need to tell colors apart." (web tour step 4)
   - Lesson: "Five steps: listen, compare, why it changes, see the mouth, practice." (web tour step 5)
   - Requests, first time a request exists: "Links and files you add wait here until your computer is
     reachable."
   - Practice: "Questions are built from your own clips."
   A step whose anchor is not on screen is skipped (web rule). Settings > Help > "Restart the tour" replays the
   Welcome and re-arms every coach mark; it never re-pairs.
5. No runtime permission is requested during the Welcome. Notifications are asked at the first request
   (F-23) and the microphone at the first "Record" (F-13). No other runtime permission exists (11.4).
6. A cold start caused by a share skips the Welcome: the share confirmation (section 8) opens directly and
   explains pairing inline.

**States**
| State | Behaviour |
|---|---|
| First run | Welcome → Connect → Home (first-run empty state, F-03) |
| Empty / Loading | n/a (static content) |
| Offline / Unreachable | Connect shows its own states (F-02); "Not now" is always available |
| Error | n/a |
| Returning learner | Welcome never shows again; only coach marks of screens not yet seen |
| TalkBack on | coach marks are announced as dialogs with a "Got it" button |

**Acceptance criteria**
- **F-01-AC1** Given a fresh install, When the app opens from the launcher, Then Welcome page 1 is shown, "Skip" is
  reachable, and no runtime permission dialog appears before the Connect step.
- **F-01-AC2** Given the Welcome is finished or skipped, When the learner taps "Not now" on Connect, Then Home shows
  the first-run state, and on the next launch the Welcome is not shown.
- **F-01-AC3** Given the first sync is running and one clip is On this phone, When the learner looks at Home, Then
  "Start watching" is enabled and opens Watch in the Mix.
- **F-01-AC4** Given the learner opens Watch for the first time, When a short is current, Then exactly one coach mark
  is visible and dismissing it neither pauses nor skips playback.
- **F-01-AC5** Given the learner dismissed the Watch coach marks, When they tap Settings > "Restart the tour" and
  open Watch, Then the Watch coach marks show again.
- **F-01-AC6** Given a fresh install, When the first launch is a shared link (a `text/plain` share intent, fake
  computer unreachable), Then the share confirmation appears without the Welcome, and after confirming the request is
  "Waiting on this phone".

**Data.** None from the server. Local flags: welcome done, coach marks seen. **Priority:** v1-core.

---

### F-02 Connect to your computer: discovery, pairing, trust (v1-core)

**Purpose.** Let the phone find the computer on the home Wi-Fi and trust it once, without typing an address in
the common case and without exposing anything unless the operator opted in. Mechanism: section 7.

**User stories**
- As the operator, I want my computer reachable by my phone only when I choose, and only by phones I paired.
- As the learner, I want the phone to find my computer by itself, and to keep working when its address changes.
- As the operator, when discovery fails, I want to type the address myself.

**Behaviour**
1. Screen "Connect to your computer", lede: "Your computer analyzes the clips; this phone keeps them and
   teaches with them. Connect once, on your home Wi-Fi."
2. Instruction card for the computer side, three steps: "On your computer, start PhonoTrainer with phone access
   on." (the exact command text ships with the server change S-01 and is shown here; no address or host name is
   hard-coded) · "In a terminal on your computer, ask PhonoTrainer for a pairing code. It shows a 6-digit code and
   its address." · "Pick your computer below and enter the code."
3. **Search** starts automatically (7.3: the phone sends one discovery probe on the Wi-Fi; computers with phone
   access on answer it directly): "Looking for your computer…" for up to 10 s; computers appear as found, each
   with its name, "PhonoTrainer {version}" and a status word; if exactly one is found it is preselected. No
   address is shown in the list. **Same name or same identity** (D-39): names and server ids are public, so when
   two answers share a name, or the same server id answers from two addresses, every answer is listed as its own
   row, **none is preselected**, and a note above the list reads "Two computers answered with the same name. If
   you aren't sure which one is yours, enter the address shown on your computer instead." + "Enter the address
   instead". (A computer with two network connections on the same Wi-Fi, or two computers that kept the default
   name "PhonoTrainer computer", look the same way; the note covers every case without accusing anyone.)
4. "Enter the address instead": one field "Computer address and port" (placeholder "As shown on your computer";
   never an example address) and "Check", which asks that address for its identity.
5. **Code**: "Enter the code shown on your computer", 6 digits, numeric keypad, paste allowed; optional field
   "Name of this phone on your computer" (default: the phone's model name, editable); "Connect".
6. **Success**: "Connected to {name}" + "Clips on your computer start coming to this phone now. Requests waiting
   here are being sent." → Home, first sync starts (F-21).
7. Later management lives in Settings > Your computer (F-22): status, name, last seen, last sync, "Check now",
   "Connection details", "Enter the new address", "Connect again", "Forget this computer…". To pair a different
   computer the learner first forgets this one (D-29).

**States**
| State | What the learner sees |
|---|---|
| First run | the screen as above |
| Offline (not on Wi-Fi) | "Connect to your home Wi-Fi first. Your computer is reachable only there." + "Open Wi-Fi settings" (system screen) |
| Loading (searching) | "Looking for your computer…" (≤ 10 s) |
| Empty (nothing found) | "No computer answered." + checklist "Is PhonoTrainer running with phone access on? If your computer joined a new network, run phonotrainer phone on there.", "Are this phone and your computer on the same Wi-Fi?", "Some routers keep devices apart (guest networks).", "Is a VPN on? Turn it off at home: it can hide your computer." + "Search again" + "Enter the address instead" |
| Unreachable: a typed address answers without phone access (possible only when PhonoTrainer was started by hand on a network address without phone access, or when phone access is on but this network is not approved for phones, 7.2; discovery never lists such a computer) | "That address answered, but phone access isn't on there for this network. On your computer, run phonotrainer phone on, then try again." |
| Two answers with the same name or identity | every answer listed, none preselected, the note of behaviour 3 + "Enter the address instead" |
| Unreachable: too old | row (or "Check" result) reads "Needs a newer PhonoTrainer on your computer"; not selectable |
| Error: wrong code | "That code didn't match. Check the code on your computer." (field keeps focus; nothing stored) |
| Error: code expired | "That code has expired. Your computer can show a new one." |
| Error: too many attempts | "Too many tries. Ask your computer for a new code." (the code is invalidated by the computer) |
| Already paired, "Connect again" after a revoke | only the same computer (same server id) is offered; another computer found by the search reads "This phone is connected to {name}. Forget it first in Settings to connect to another computer." |
| Later: credential revoked | global banner "Your computer no longer recognizes this phone. Connect again to keep syncing." + "Connect again"; study keeps working; requests are kept |

**Acceptance criteria**
- **F-02-AC1** Given phone access is on and both devices are on the same Wi-Fi, When Connect opens, Then the computer
  is listed within 10 s without typing an address.
- **F-02-AC2** Given the computer is listed, When the learner enters the code shown on the computer, Then the phone
  shows "Connected to {name}" and a sync starts.
- **F-02-AC3** Given a wrong code, When it is submitted, Then the error copy shows, the field keeps focus and no
  credential is stored; and Given 5 wrong codes, When a 6th is tried, Then the computer refuses it and the phone
  shows the too-many-tries copy.
- **F-02-AC4** Given discovery finds nothing within 10 s (the fake computer does not answer probes), When the learner
  enters the address and port shown on the computer and the code, Then pairing succeeds.
- **F-02-AC5** Given a paired phone, When the computer's LAN address changes and discovery finds it, Then the next
  time the phone is on the home Wi-Fi it reconnects without asking anything (the new address proves it is the paired
  computer).
- **F-02-AC6** Given a paired phone whose computer changed address and does not answer discovery, When the stored
  address fails, Then the status offers "Enter the new address", and a typed address that proves it is the paired
  computer is accepted without pairing again.
- **F-02-AC7** Given an address that answers without phone access, When "Check" is tapped, Then the phone reports
  that phone access is off there and stores nothing.
- **F-02-AC8** Given a paired phone, When the operator revokes it on the computer, Then the next call fails with the
  credential-rejected code, the banner shows, the device queue pauses, and every clip stays studiable.
- **F-02-AC9** Given the repository, When it is searched (11.7), Then no address, code or credential appears in
  committed files.
- **F-02-AC10** Given an unpaired phone and two fake computers answering discovery with the same name (and, in a
  second run, one server id answering from two addresses), When the list shows, Then every answer is a row, none is
  preselected, and the note "Two computers answered with the same name. If you aren't sure which one is yours, enter
  the address shown on your computer instead." with "Enter the address instead" is above the list.
- **F-02-AC11** Given a paired phone and an address that answers with the paired server id but without a valid proof
  of identity (a fake computer that knows the server id but not this phone's credential), When the phone probes it,
  Then the status reads "A different computer answered", no request, file, recording or signed call is sent there,
  and nothing is deleted on the phone.
- **F-02-AC12** Given a paired phone and a fake computer whose phone access is on but not approved for the current
  network (it answers `lan_forbidden`), When the phone probes it, Then the status reads "Your computer isn't
  reachable", and Connection details shows the last probe result "Your computer answered, but phone access is off
  there or not approved for this network."

**Data.** `GET /api/health` (exists; MUST stop leaking the workspace path and add identity: S-02, S-06);
discovery NEW S-03; pairing, device list and revoke from the terminal and CLI NEW S-04; identity proof and signed
requests NEW S-02, S-05.
**Priority:** v1-core.

---

### F-03 Home: the dashboard (v1-core)

**Purpose.** The main panel the brief asks for: what is true about the computer and the requests, what to study
today, and the way into every clip and every feed.

**User stories**
- As a learner with 5 minutes, I want one obvious button that starts today's study.
- As a learner, I want to continue exactly where I stopped.
- As a learner, I want to see at a glance whether my computer is reachable and what is waiting, analyzing or
  failed.
- As a learner, I want to jump into my clips, or into a feed of one change ("show me flaps").

**Behaviour: content in reading order** (hierarchy, not layout)
1. **Top bar**: wordmark "PhonoTrainer", "Settings".
2. **Your computer status card**, always present, read by TalkBack as one sentence:
   - a status line with icon + words + time from the 7.5 table, e.g. "Connected to {name} · everything is on
     this phone", "Checking your computer…", "Your computer isn't reachable · last seen 3 h ago · your clips work
     offline", "Not connected to a computer" + "Connect";
   - when non-zero, counts of this phone's own requests with icon + word: "2 waiting on this phone", "1 analyzing
     · 40 % · Hear the phones", "1 in line", "1 needs your attention" (failed items show their primary action
     inline, e.g. "Try again"); jobs started from the browser client are never counted here; tap → Requests;
   - while downloading: "Bringing 2 clips to this phone · 34 MB of 61 MB";
   - "Why?" when not reachable (→ Connection details); "Sync now" when connected.
3. **Today's set card** (F-08): "Today · about 5 minutes" + "8 shorts, 5 questions · 2 saved shorts back" +
   "Start"; mid-set "Today · 3 of 8 done" + "Continue"; finished "Done for today" + "What you met: flapping ×3, linking
   ×2…" + quiet "Another set". The card itself shows what the set holds, one mark per short (its spotlight change's
   family; saved shorts marked as saved; shorts already watched marked as done) and one per question, and TalkBack
   reads it as part of one sentence: "Today's set: 8 shorts with flapping, linking, h-dropping and 2 more changes;
   2 saved shorts back; then 5 questions." (each distinct spotlight change named once, in set order, at most three
   names, then "and {N} more changes"). The card has no other control: there is no list or detail view of a set
   (D-45); the set is met short by short in Watch.
4. **Keep watching card** (when the last feed position is not today's set): poster, clip title, "Short 4 of
   15", the spotlight ("Listen for: flapping in “water”"), "Keep watching".
5. **Add a clip**: "Paste a link" and "Choose a file" (→ Add a clip, F-19). The clipboard is never read
   silently.
6. **New on this phone** (clips that arrived since the last visit): "Ready to watch: {title}" + "Watch".
7. **Your clips** shelf: the 10 most recently studied or added clips (poster or audio art, title, length,
   "15 shorts", family icons with counts, availability icon + word) + "All clips" (→ Library).
8. **Watch by change**: the 16 changes grouped by family, each chip with icon, name and short count ("Flapping
   · 34"); a change with 0 shorts is shown as "Not in your clips yet" and opens its Learn page instead. A change's
   **short count** is the number of shorts its change mode plays: playable shorts that contain at least one
   spotlight candidate with that change (F-07), so "Flapping · 34" opens a feed of exactly 34 shorts (D-48). The
   same count is used by the mode picker (F-06) and the Learn change cards (F-15); occurrence counts are a different
   unit and appear only on phenomenon pages.
9. **Practice card**: "10 questions · about 3 minutes", the weakest change ("Worth reviewing: t/d deletion · 4
   of 9"), "12 saved shorts" (→ Saved), "Start".
10. **Insights teaser** (v1-complete; absent if Insights is cut): one takeaway from Insights (F-18), e.g. "About 7
    in 10 words you hear don't sound the way the dictionary writes them." → Insights.
- Pull to refresh = "Sync now" when connected; otherwise it shows "Your computer isn't reachable right now." and
  changes nothing else.

**States**
| State | Behaviour |
|---|---|
| First run (not paired, no clips) | Status card "Not connected to a computer" + "Connect"; a teaching block (web empty library adapted): "1. Share a clip from YouTube or X, or pick one on this phone. 2. Your computer analyzes it. 3. Swipe through it short by short." + "Connect to your computer" + "Explore Learn"; Today's set card reads "Today's set starts with your first clip."; "Add a clip" works (requests wait) |
| Empty (paired, computer has no clips) | "Your computer has no clips yet." + "Start with a short clip (30–60 seconds) of natural dialogue." (web) + "Add a clip" |
| Loading | first sync: shelf fills as clips arrive, each "Bringing to this phone 40 %"; status card shows "Bringing your clips to this phone: 3 of 10" |
| Offline / Unreachable | everything on the phone works; the status card says which state (7.5) and that requests wait; clips not on the phone show "Not on this phone" and open their Clip page with the reason; nothing is silently disabled |
| Error | catalogue unreadable: "Your clips could not be loaded." + "Try again"; the status card still works and the queue is never cleared by this error |
| Partial | a clip without its video shows "Video not on this phone"; a clip whose clip data is still being prepared on the computer shows "Getting ready on your computer" (a missing video or poster never holds a clip back, 7.8); a clip with an incomplete study pack never shows "On this phone" |
| Storage nearly full | status card line "Storage almost full: videos of older clips will be removed first." → Storage |
| Background limited | when Android restricts the app in the background (battery optimization, One UI sleeping apps), a quiet line "Android is limiting PhonoTrainer in the background: clips arrive when you open the app." + "Battery settings" (system screen); shown once until the restriction changes |

**Acceptance criteria**
- **F-03-AC1** Given at least one clip is on the phone, When Home opens, Then the Today's set card is the first study
  item and "Start" opens Watch in the Mix at today's set.
- **F-03-AC2** Given a paired computer that answered 10 s ago, When Home shows, Then the status line reads "Connected
  to {name}" with an icon and the time, and no state is conveyed by color alone.
- **F-03-AC3** Given the computer stops answering while the status reads "Connected", When the next probe fails, Then
  the status line becomes "Checking your computer…" at once, and When the re-probe 5 s later also fails, Then it
  becomes "Your computer isn't reachable · last seen {time}" and no action claims to run "now" (SC-10).
- **F-03-AC4** Given 2 requests waiting and the phone offline, When Home shows, Then "2 waiting on this phone" is
  visible and opens Requests.
- **F-03-AC5** Given a request analyzing on the computer and the app open, When its stage changes, Then the status
  card updates within 5 s.
- **F-03-AC6** Given the learner stopped in clip X at short 4, When Home opens, Then "Keep watching" shows X, "Short
  4 of N" and opens the feed there.
- **F-03-AC7** Given a change chip "Flapping · 34", When it is tapped, Then the feed opens in the flapping mode with
  34 shorts and every spotlight is a flapping instance.
- **F-03-AC8** Given clip X on the "Your clips" shelf, When its play action is tapped, Then the feed opens "In order"
  on X at its resume short (short 1 if never opened); When Back is pressed, Then Home shows at the same scroll
  position.
- **F-03-AC9** Given a failed job created from the browser client, When Home shows, Then no request count includes
  it.
- **F-03-AC10** Given today's set of 8 shorts (2 of them saved shorts) whose spotlights are, in order, flapping,
  linking, h-dropping, flapping, t/d deletion, vowel reduction, linking and flapping, and 5 questions, When TalkBack
  focuses the Today's set card, Then it reads "Today's set: 8 shorts with flapping, linking, h-dropping and 2 more
  changes; 2 saved shorts back; then 5 questions." as part of the card's sentence, and the card's only control is
  "Start".

**Data.** Local catalogue (from the NEW S-11 listing; fields of `GET /api/jobs` [BE 2]: `id, source, source_url,
status, percent, last_message, has_media, is_video, has_dialogue_audio, summary{segments, words, duration,
phenomena_counts}, created, finished, error`), local queue, local progress, Insights pooled metrics (F-18).
**Priority:** v1-core.

---

### F-04 Library: all clips (v1-core)

**Purpose.** Find any clip, see its availability, and manage what is on the phone.

**User stories**
- As a learner, I want to search all my clips and open any of them.
- As a learner who just shared a link, I want to see it where I look for clips, even before it is analyzed.

**Behaviour**
- An **"In progress"** group at the top with every non-final request of this phone (preparing, waiting on this
  phone, sending, in line, analyzing, needs your attention), each with its state and inline primary action; tap
  → request detail. Jobs started from the browser client are summarized in one line, "2 more started on your
  computer" (→ Requests, F-20), and never as rows here.
- Below, every clip in the catalogue. Sort "Newest first" (default; ties broken by job id because several jobs
  share the same second [AD 2.1]), "Oldest first", "Name A–Z" (web `SORTS`). Search "Search your clips…" with a
  live count "2 of 10 clips". Filter chips "On this phone", "Not on this phone".
- Each row: poster or audio art; title (the computer's `source` cleaned of the bracketed id and extension); meta
  "4 min 21 s · 691 words · 14 changes"; source icon + word ("YouTube", "X", "Phone file", "Computer");
  availability icon + word ("On this phone", "Video on this phone", "Bringing to this phone 45 %", "Not on
  this phone", "Getting ready on your computer"); "Kept" marker; chip "Older rules: re-analyze on your computer to
  update" when `meta.rules_version` < current (web precedent); progress hint "You've seen 12 of 15 shorts" (it
  serves the brief's "navigate between the videos": where to continue); clips from a computer or library this
  phone no longer syncs with carry "From a computer you forgot" or "From another library" (7.9) and are never
  deleted by a listing.
- Row actions, through an explicit "More" button (long-press is only a shortcut): "Watch", "Open clip page",
  "Keep on this phone" / "Stop keeping", "Download to this phone" / "Remove from this phone…", "Remove the video
  only".

**States**
| State | Behaviour |
|---|---|
| First run / Empty | same copy and actions as Home |
| Loading | "Loading your clips…" only after 300 ms |
| Offline / Unreachable | list works from the local catalogue; banner "Showing what this phone knows. Last synced {time}." |
| Error | "Your clips could not be loaded." + "Try again" |
| Partial | a row whose download is paused shows the reason ("Download paused: storage budget reached") |
| Search, no result | "No clip matches “{text}”." + "Clear the search" (web) |

**Acceptance criteria**
- **F-04-AC1** Given 12 clips of which 3 are on the phone, When "On this phone" is selected, Then exactly 3 rows show
  and TalkBack hears "3 of 12 clips".
- **F-04-AC2** Given a clip removed from the phone, When the Library shows, Then its row reads "Not on this phone"
  and no download starts until "Download to this phone" is tapped.
- **F-04-AC3** Given a request of this phone that failed on the computer, When the Library shows, Then it appears in
  "In progress" with its reason and "Try again".
- **F-04-AC4** Given two jobs with the same `created` second, When the list refreshes repeatedly, Then their order is
  stable.
- **F-04-AC5** Given N clips on the phone, When "Watch" on row k is tapped, Then the feed opens "In order" on clip k
  at its resume short; When Back is pressed, Then the Library shows at the same scroll position, search and filter.
- **F-04-AC6** Given a failed job created from the browser client, When the Library shows, Then it is not a row of
  "In progress" and only the "started on your computer" line mentions it.

**Data.** Catalogue (NEW S-11), local availability, device queue. **Priority:** v1-core.

---

### F-05 Clip page (v1-core; Transcript tab v1-complete)

**Purpose.** Depth for one clip and the bridge between the dashboard and the feed. Replaces the web's two-pane
workspace with one thing at a time [WF 9.2].

**User stories**
- As a learner, I want to read the whole dialogue with every change marked and jump into any part.
- As a learner, I want to see only what I may copy ("Safe to produce") or only what I should recognize.
- As a learner, I want to know which changes this clip is rich in and what is stored on my phone.

**Behaviour**
1. **Header**: title; "4 min 21 s · 87 phrases · 691 words"; kind ("Video" / "Audio only"); added date;
   availability; primary "Watch" (resumes) and "Watch from the start".
2. **Tab "Shorts"**: numbered list, each with time range, spotlight, count of marked words, "Seen" (check icon +
   word) when seen; tap → feed "In order" at that short.
3. **Tab "Transcript"** (v1-complete; if cut, the Clip page keeps Shorts and Summary, and words are studied from
   the feed): phrases with a play button and time, intonation badge, words with marks (same
   vocabulary as captions, F-07). Tap a word → lesson sheet; tap a phrase time → feed at that short. Filters
   "Safe to produce" (mic icon), "Recognize only" (ear icon), "Filter by change" (multi-select with counts):
   filtering fades other words and never hides text, and the lesson's Next/Previous walk only filtered words
   (web). "Search a word…" with a match count. A mini player plays the clip's audio with speed 0.5× / 0.75× /
   1×, loop and "Follow playback". "Listen to: Original mix | Dialogue only" when the clip has a dialogue track
   (web labels; position kept when switching). Each phrase has "Pitch, stress and IPA of this phrase" (F-11).
4. **Tab "Summary"**: "Changes in this clip" (icon, name, practice badge, count; tap → feed in that change,
   limited to this clip); "How reduced is this speech?" (v1-complete, built with Insights F-18: web Summary
   measures against published figures, each with "What's this?", with a table alternative); "About this clip"
   (engine, speech model, dialogue separation, rules version, source link shown as text that opens the browser
   only on an explicit tap).
5. **On this phone** block: what is stored and its size ("Clip data and audio · 2.1 MB", "Video · 17 MB"),
   "Keep on this phone", "Download the video" / "Remove the video", "Remove from this phone…". There is no
   "delete from your computer" in v1 (D-07, Q-2).

**States**
| State | Behaviour |
|---|---|
| First run / Empty | n/a (a Clip page exists only for a known clip) |
| Loading | header and Summary from the catalogue appear at once; tabs that need clip data show "Opening the transcript…" after 300 ms |
| Offline / Unreachable, not on this phone | header notice "This clip isn't on this phone yet. It downloads the next time your computer is reachable." + "Download when connected" (marks it wanted); Shorts and Transcript say "The transcript comes with the clip."; Summary shows catalogue counts |
| Error | corrupt local clip data: "This clip needs downloading again." and it is re-queued for download; the old good copy stays in use if any |
| Partial | audio still downloading: transcript readable, play disabled with "Getting the audio… 40 %"; no video: "Video not on this phone"; the computer cannot provide a video (original missing, or a phone video failed): "Video not available from your computer. Listening and study work."; imported without audio: "This clip came without its audio: you can read it, but not listen to it." (web) and it is excluded from the feed; older rules: "Analyzed with older rules. Re-analyze it on your computer to update."; espeak analysis: Compare shows "Expected by the aligner" (web), never crashes |
| Clip still processing | the page shows the request detail (F-20) and turns into the Clip page by itself when ready (web behaviour) |
| Deleted on the computer while open | banner "This clip was deleted on your computer. It stays until you leave this page." then it is removed |

**Acceptance criteria**
- **F-05-AC1** Given a clip with flapping ×12 on the phone, When the learner taps that row in Summary, Then the feed
  opens limited to this clip's shorts that contain a reliable flapping instance, and its header says so.
- **F-05-AC2** Given a clip with a dialogue track, When "Dialogue only" is chosen during playback, Then playback
  continues from the same time on the dialogue audio.
- **F-05-AC3** Given the "Safe to produce" filter, When the transcript renders, Then words with only "Recognize only"
  changes are faded but keep their text.
- **F-05-AC4** Given a clip not on the phone and the computer unreachable, When the learner taps "Download when
  connected", Then the clip is marked wanted and downloads at the next sync even if automatic video download is off.
- **F-05-AC5** Given a short tapped in the Shorts tab, When the feed opens, Then it is "In order" at that short and
  Back returns to the Clip page.

**Data.** `analysis.json` (`segments`, `words`, `intonation_units`, `prominence`, `word_classes`, `f0_track`,
`f0_stats`, `summary.metrics`, `summary.phenomena_counts`, `meta`), `reference.metrics_reference`,
`metric_labels`, audio renditions (NEW S-12). **Priority:** v1-core; Transcript tab v1-complete.

---

### F-06 Watch: the vertical feed (v1-core)

**Purpose.** The TikTok/Shorts-like heart of the app [Brief], built as a teaching device: one spotlight per short,
the lesson one tap away, and an end to every session.

**User stories**
- As a learner, I want to swipe through short moments of my clips with captions that show what was really said.
- As a learner, I want a feed of only one change ("only flaps") to train my ear on it.
- As a learner offline, I want the feed to work with whatever is on the phone.
- As a learner, I want the feed to stop when my set is done instead of tempting me to keep scrolling.

**Behaviour**
1. **What one short is** (rule "shorts v1", computed on the phone from `analysis.json`; versioned and
   deterministic; no server change):
   0. A short's **length** is measured from its first phrase's start to its last phrase's end (phrase bounds, not
      the play bounds of item 4); every threshold below uses it.
   1. Walk the phrases of a clip in order; a phrase is never split.
   2. Close the current short before the next phrase when any holds: (a) adding it would make the short longer
      than 30 s; (b) the short is already ≥ 10 s and the pause before the next phrase is ≥ 0.6 s; (c) the pause
      is ≥ 3 s.
   3. After that pass, from first to last, a short under 5 s merges into the previous short of the same clip if
      the result is ≤ 30 s, otherwise into the next one if that result is ≤ 30 s; otherwise it stays. (So a clip
      of 30 s or less may still give several shorts: the pause rules decide, as F-06-AC1 shows.)
   4. Play bounds: 0.25 s before the first phrase (not before 0) to 0.4 s after the last phrase (not past the
      end), so breaths and releases are not clipped.
   5. Identity: `{jobId}:{firstSegmentIndex}` plus the rule version. Everything the learner keeps (saved items,
      lesson history, seen state) is anchored to job + segment (+ word), never to the short id, so a later rule
      change loses nothing.
   Why: phrases last a median 1.3 to 3.8 s (measured), too short to swipe one by one; 30 s matches the
   computer's span limit for pitch contours and takes (`MAX_SPAN_S = 30`, [WF 2]).
2. **Eligibility.** A short is *playable* when its clip's study pack is on the phone (the feed never streams,
   D-12). A short is *eligible for Mix and change modes* when it has a spotlight candidate (F-07); shorts
   without one appear only "In order", labelled "No labelled change here: listen to the rhythm."
3. **Modes** as in 5.3. The **mode picker** ("Mix ▾", always visible at the top) lists Mix, Saved, the 16
   changes by family with counts of playable shorts (the short count of F-03 item 8: "Flapping · 34 shorts"; 0
   shown muted and routed to Learn),
   and every clip on the phone. Picking one changes the mode. "Leave this clip out of the Mix" (short "More"
   sheet) excludes a clip from the Mix; reversible in Settings.
4. **Movement**: vertical swipe, and "Next short" / "Previous short" buttons and accessibility actions, move one
   short. When new clips arrive: a set (Mix) never changes once generated, except that shorts of a deleted clip
   drop out (F-08); "In order" shows one clip, which does not change; in change modes and Saved, new matching
   shorts are added after the current position, never above it. The feed never advances by itself unless the
   learner chose "Go to the next one" at the end of a short (F-07).
5. **Seen**: a short counts as seen when, during one visit, the time actually played (the position advancing
   while playing, at any speed) reaches 80 % of its length; dragging the progress bar does not count. Seen state
   drives the Mix, "Keep watching" and the Clip page marks.
6. **Playback model**: the clip's audio is the clock (web: the analysed WAV is the master [WF 3.4]); the video,
   when present, is muted and follows, re-synced when it drifts more than 0.2 s (web `MAX_DRIFT`); the video's
   start offset against the analysed timeline comes from the computer's listing (S-11), because nothing
   guarantees it is 0 (2.5). Speed 0.5×,
   0.75×, 1× keeps pitch; the chosen speed and track persist across shorts and sessions. Only one sound plays at
   a time across the app (web `ClipPlayer` rule). Audio focus is respected: a call or another app pauses; it
   does not resume by itself; unplugging earphones pauses. Playback stops when the app leaves the foreground.
7. **Media framing** (designed as normal cases, not edge cases: 6 of 10 clips are landscape, 1 portrait, 3
   audio-only [AD 2.2]): landscape video is shown whole, never cropped (mouths at the edges matter), and the
   space it leaves holds the spotlight and captions, which therefore never cover the picture; portrait video
   fills the card and captions sit over it on a scrim that guarantees legibility; audio-only clips get a
   generated visual that draws the current phrase's pitch line as it plays (`f0_track`), seeded per clip so each
   audio clip looks different and the same every time, labelled "Audio only"; a video clip whose video is not on
   the phone shows its poster (or the poster fallback, F-25) with "Video not on this phone · playing the
   audio"; one the computer cannot provide shows "Video not available from your computer".
8. **Preparation**: the next and previous shorts are prepared from local files so a swipe starts sound within
   300 ms (SC-08).
9. **End cards**: Mix → set questions and summary (F-08); In order → "End of {clip}" + "You saw 15 shorts with 14
   kinds of change." + "Watch it again", "Next clip: {title}" (a button, never an automatic swipe), "Practice",
   "Back to Home"; change → "That's every “flapping” in your clips." + "Learn about flapping",
   "Practice flapping"; Saved → "That's all your saved shorts." + "Practice". Nothing loads past an end card.
   Mix with nothing left, when no new set can be built (every short seen within the last 7 days and no saved
   short is back): "You've
   seen every short. New ones appear when your computer finishes a clip." + "Replay the mix" (builds a set from
   seen shorts, least recently seen first), "Practice", "Add a clip".

**States**
| State | Behaviour |
|---|---|
| First run (no clips) | illustration + "Your feed fills up with your clips" + "Connect to your computer" / "Add a clip" + link "Meanwhile, explore the changes in Learn" |
| Empty (paired, clips on the computer but none on the phone yet) | "Your clips are still coming to this phone." + progress; shorts appear as soon as a clip is On this phone; a card at the end "3 more clips on the way" |
| Empty mode (a change with no shorts) | "No shorts with {change} on this phone yet." + "Watch the Mix" |
| Loading a short | the clip line, spotlight and captions show at once (from clip data); the media area shows the poster or audio art with a loading indicator; sound starts when ready |
| Offline / Unreachable | works for every short on the phone; the feed header shows "On this phone"; counts in the mode picker are of playable shorts only |
| Error: decode failure | the short plays as audio with captions and says "This video can't play on this phone. Listening still works." (no crash); the phone then asks the computer for a phone video (S-12) |
| Error: corrupt or missing local file | "This short couldn't be played." The clip is marked "Needs downloading again" and re-queued; a one-time notice; swiping moves on |
| Partial | video not on the phone → audio with poster and the chip above; clip data present but chosen track missing → the other track is used and a quiet line under the clip line says which (D-47): "Playing the original mix: the dialogue track isn't on this phone yet." / "Playing the dialogue only: the original mix isn't on this phone yet." / (a clip analysed without a dialogue track while "Dialogue only" is chosen) "Playing the original mix: this clip has no dialogue-only track." |
| Clip deleted on the computer while watching | the current short finishes; at the next swipe the clip's shorts are gone with "A clip was deleted on your computer and removed from this phone." |
| TalkBack on | nothing plays until "Play" is activated (F-07) |

**Acceptance criteria**
- **F-06-AC1** Given a 26 s clip with phrases P0 0–4 s, P1 4.3–11, P2 11.8–14, P3 14.2–20, P4 23.5–25, When shorts v1
  runs, Then there are exactly two shorts: {P0, P1} with play bounds 0–11.4 s and {P2, P3, P4} with play bounds
  11.55–25.4 s.
- **F-06-AC2** Given synthetic clips whose phrase timings follow the measured distribution (median 1.3–3.8 s, longest
  12.4 s), When shorts v1 runs, Then no short exceeds 30 s unless a single phrase does, and no phrase is split.
- **F-06-AC3** Given a downloaded clip and airplane mode [emulator] (on the test phone: the computer's server
  stopped), When the learner swipes 20 times, Then each short plays and no network error appears.
- **F-06-AC4** Given the Mix with more than one clip playable, When 20 shorts are listed, Then no more than 2
  consecutive shorts come from the same clip.
- **F-06-AC5** Given the change mode "flapping", When any short is current, Then its spotlight is a flapping instance
  that is not low confidence.
- **F-06-AC6** Given an audio-only clip, When its short is current, Then audio and captions play, "Audio only" is
  visible and no empty video frame is shown.
- **F-06-AC7** Given a clip whose video is not on the phone, When its short is current offline, Then audio and
  captions play and "Video not on this phone · playing the audio" is visible.
- **F-06-AC8** Given a short playing at 0.5×, When the learner swipes to the next short, Then it plays at 0.5×.
- **F-06-AC9** Given Reduce motion, When the learner moves between shorts, Then the change is instant with no sliding
  animation.
- **F-06-AC10** Given the clip "In order" was left at short 7, When the learner reopens that clip with "Watch", Then
  the feed resumes at short 7.
- **F-06-AC11** Given a 20 s short, When the learner plays 10 s and drags the progress bar to its end, Then it is not
  seen; When 16 s have played in that visit, Then it is seen.
- **F-06-AC12** Given the learner left the feed in the flapping mode at short 5 and went to Learn, When the Watch tab
  is opened, Then the feed shows the flapping mode at short 5 (the Watch tab's last mode and position, 5.2).
- **F-06-AC13** Given "Listen to: Dialogue only" and a clip whose dialogue track is still downloading while its
  original mix is on the phone, When its short plays, Then the original mix plays and the card reads "Playing the
  original mix: the dialogue track isn't on this phone yet."; When the dialogue track arrives, Then the next short of
  that clip plays the dialogue and the line is gone.

**Data.** `analysis.json` per clip: `segments[].start/end/text/words[]/intonation_units/emphasis_word_idx/f0_track`,
words' `start, end, phenomena, low_confidence, diff_cost, dict_ipa, realized_ipa, canonical_aligned,
realized_aligned, boundary_link_next, boundary_link_type, lexical_form, lexical_expansion`; `reference.labels,
families, family_of, practice, lexical_practice`; audio and video renditions and posters (NEW S-12); local seen
state, positions, practice stats. **Priority:** v1-core.

---

### F-07 The short card: spotlight, captions, actions, gestures (v1-core)

**Purpose.** Make every short a small noticing exercise: cue, listen, reveal, replay slowly, open the lesson.

**User stories**
- As a learner, I want to know the one thing to listen for, and hear it again slowly with one gesture.
- As a learner who wants a harder exercise, I want to hear the phrase before I read it (Listen first, opt-in).
- As a learner, I want to tap any word and learn why it sounded that way, then come back to the same moment.

**Behaviour: content hierarchy, in reading order**
1. **Feed header**: mode picker; position ("4 / 15" in clip mode, "3 of 8" in a set); "On this phone" badge when
   the computer is not reachable.
2. **Media area** (F-06 framing).
3. **Listen for (spotlight)**: family icon + change name + practice badge ("Safe to produce" with a microphone
   icon / "Recognize only" with an ear icon, web); the word in quotes; dictionary form and what was said in IPA,
   e.g. "“water” /wɔtɚ/ → [wɔɾɚ]"; a one-line cue from the Learn content ("How to hear it", `listenFor`),
   shortened for the card; buttons "Play the word" and "Slowly" (0.5×). For "Recognize only" the cue adds "Just
   learn to hear it: no need to copy it." Tapping the spotlight opens the lesson on that word.
   - **Spotlight choice** among candidates (words of the short with ≥ 1 change, not low confidence, lasting
     ≥ 60 ms, with a "what was said" form: the web's playable rule). The rule is deterministic per short, so a
     short always shows the same spotlight in the same mode: (1) in Saved mode, and for a saved short in a set,
     the saved word; (2) in a change mode, the clearest candidate with that change; (3) otherwise a "Safe to
     produce" change before a "Recognize only" one; (4) then the clearest alignment (lowest `diff_cost`, web
     `pickExamples`); (5) ties: the earliest word. When the change crosses into the next word (linking,
     palatalization, `boundary_link_next`), "Play the word" plays both words (web `crossesBoundary`).
   - When playback reaches the spotlight word, the spotlight and the word's mark on the progress bar show a
     "now" state (shape or weight; a brief pulse only when motion is allowed).
4. **Captions**: previous phrase (dimmed), current phrase (the word being said is filled from 50 ms before its
   start to 0.35 s after its end, web rule), next phrase (dimmed); only phrases of the current short are shown
   (the first phrase has no previous one). Every word is a button with a ≥ 48 dp target. **When the current
   phrase does not fit** (a phrase may last 12.4 s, 35+ words; large fonts; IPA mode): the dimmed phrases are
   dropped first; then the caption area shows the lines around the word being said and follows the playhead
   line by line (with Reduce motion it jumps without animation); when paused, the learner can scroll the
   caption area; nothing is ever cut mid-word or hidden without a way to reach it (A-06).
   Marks use the web vocabulary exactly (9.8 table): line style + icon + name, ‿ between linked words, a chip
   "gonna ← going to" for contractions, a dashed outline for low confidence, bold for the most prominent word.
   The intonation badge closes the current phrase when the unit is judged (web `isJudged`): sentence type +
   "rises" / "falls" / "stays flat" with an arrow icon, "usually falls" when it differs from the expected
   contour, an "uptalk" chip with "What's this?". With "Patterns" on, the family icon follows each marked word.
5. **Caption modes** ("More" sheet; persisted): "Marks" (default), "Plain" (text only), "IPA" (what was said
   under each word; marked words also show the dictionary form), "Off". **Listen first** (setting, **off** by
   default; no web precedent, so it is opt-in, D-25): when on, the current phrase shows a single line "Listen
   first: the words appear after it plays" (never one placeholder per word, which would give away "How many
   words?") until it has played once, then the words appear.
6. **Action rail** (thumb side; each ≥ 48 dp with icon + short word and an accessible name): "Replay" (current
   phrase), "Speed" (cycles 1× → 0.75× → 0.5×, always showing the current value), "Loop" ("Off" / "Phrase" /
   "Short"), "Shadow" (F-12 on the current phrase), "Save" (toggles "Saved", F-09), "More".
7. **Clip line**: clip title (tap → Clip page) and "Short 4 of 15 · 0:42–0:58".
8. **Progress bar of the short**: scrubbable; phrase boundaries as ticks; the spotlight word as a distinct mark
   (shape, not color); releasing near a phrase start lands on it.
9. **"More" sheet**: "Listen to: Original mix | Dialogue only" (when available), captions mode, "Listen first",
   "Pitch, stress and IPA of this phrase" (F-11), "Record yourself" (F-13 on the current phrase), "Open the clip
   page", "Download the video" (when absent), "Leave this clip out of the Mix", "What do the marks mean?" (legend:
   web "How to read the marks" with the sample words “does”, “water”, “did you”, “pick it up”).
10. **Playback rules**: a short plays once automatically when it settles (setting "Play each short when it
    appears", on; forced off while TalkBack is on). At its end the default is **"Replay it once"**, then it
    stops on the short; in Listen first the replay is the first time the words are visible. Setting "At the end
    of a short: Replay it once (default) / Stop / Go to the next one".

**Gestures** (every gesture has a visible button or accessibility action; none is the only path)
| Gesture | Effect | Equivalent |
|---|---|---|
| Swipe up / down | next / previous short | "Next short" / "Previous short" actions |
| Tap the media | play / pause | play/pause button on the progress row |
| Double-tap the media | replay the current phrase | "Replay" |
| Press and hold the media | plays at 0.5× while held, back to the chosen speed on release ("Hold to slow down") | "Speed" |
| Tap a word | pause and open its lesson | word buttons |
| Long-press a caption phrase | loop that phrase until tapped again | "Loop: Phrase" |
| Drag the progress bar | scrub; captions follow | slider with "Back 2 s" / "Forward 2 s" actions |

**States**
| State | Behaviour |
|---|---|
| First run / Empty | n/a (the feed's empty states apply, F-06) |
| Loading | card text first, media when ready (F-06) |
| Listen first on, before the first play | spotlight + the single "Listen first" line + "Show the words" |
| Playing | current word filled; "Play" becomes "Pause" |
| No spotlight ("In order" only) | spotlight replaced by "No labelled change here: listen to the rhythm." |
| Contraction spotlight | "gonna ← going to" with its own advice (`lexical_practice`) |
| Offline / Unreachable | everything works ("Record yourself" measures pitch only when connected, F-13) |
| Error: media volume at 0 | hint "Turn the volume up: this is all about sound." |
| Partial: no audio (imported clip) | never in the feed (excluded) |

**Acceptance criteria**
- **F-07-AC1** Given Listen first is on, When a short becomes current, Then words are hidden behind one line that
  reveals no word count, the spotlight is visible, and the words appear when the first play ends; Given the default
  (off), Then the words show from the start.
- **F-07-AC2** Given the spotlight change crosses a word boundary, When "Play the word" is tapped, Then the spotlight
  word and the next word play together.
- **F-07-AC3** Given "Speed" is tapped twice from 1×, When playback starts, Then it plays at 0.5× and the control
  reads "0.5×".
- **F-07-AC4** Given the learner presses and holds the media, Then playback is 0.5× while held and returns to the
  chosen speed on release.
- **F-07-AC5** Given playback reaches a word, Then that word is highlighted within 60 ms of its start time.
- **F-07-AC6** Given the learner taps a caption word, Then playback pauses and the lesson opens on that word; When
  the lesson closes, Then the same short is shown paused at the same time.
- **F-07-AC7** Given the default end-of-short setting, When a short finishes, Then it replays once and then stops on
  the same short.
- **F-07-AC8** Given the same short shown twice in the Mix on different days, When it is current, Then its spotlight
  is the same word both times.
- **F-07-AC9** Given a 12 s phrase at the largest system font in IPA mode, When it plays, Then the word being said is
  always visible and the caption area follows it line by line.
- **F-07-AC10** Given any marked word, When the card is reviewed in a grayscale screenshot, Then its family is
  identifiable by line style and icon.
- **F-07-AC11** Given TalkBack, When a short becomes current, Then it does not play until "Play" is activated, its
  summary reads "Short 4 of 15, {clip}. Listen for flapping in “water”. 14 seconds.", and the custom actions Play,
  Next short, Previous short, Replay phrase, Slower, Open lesson, Save are available.

**Data.** As F-06, plus `reference.descriptions`, `lexical_practice`, `link_types`; Learn `listenFor` content
(static, single source: S-18). **Priority:** v1-core.

---

### F-08 Sets: today's set, questions and the end of a session (v1-core)

**Purpose.** Turn the Mix into a finite, daily, interleaved study session with retrieval right after exposure: the
answer to "how a feed teaches instead of inviting endless scrolling" [L, F].

**User stories**
- As a learner, I want one button that gives me about 5 minutes of the right material.
- As a learner, I want a clear "done" so I can stop with a sense of completion.
- As a learner, I want to be asked about what I just heard, while it is fresh.

**Behaviour**
1. The Mix is always delivered in **sets** of **8 shorts and up to 5 questions** (one size; no setting).
2. **Composition**, in this order until 8 shorts are chosen: (1) up to 2 saved shorts not watched for 3 or more
   days (F-09); (2) unseen shorts, most recently added clip first, "Safe to produce" spotlights first; (3) shorts
   not seen for 7 or more days, least recently seen first. Interleaving: no two consecutive shorts with the same
   spotlight change when another order exists; at most 2 consecutive shorts from the same clip.
3. **Today's set** is the first set of the local day. It is generated once and **never changes afterwards**, except
   that shorts of a clip deleted from the phone drop out; it keeps its progress ("3 of 8") if the learner leaves
   and returns. A new today's set is offered after local midnight. Clips that arrive during the day are used by
   the next set.
4. Saved shorts in a set carry a "Saved" tag.
5. **Questions**: after the shorts, question cards in the same feed, built only from changes met in this set
   (retrieval practice), with the Practice kinds and rules (F-17): "Which pronunciation?", "Which change?", "How
   many words?". Feedback "Correct!" / "Not quite — the answer was [ɾ]" (web; icon + words) with a one-line
   explanation, "Open the lesson" and "Save". Swiping past a question is allowed and not counted. Answers count in
   Practice accuracy. Nothing is saved unless the learner taps "Save". Setting "Questions after each set" (on).
6. **Summary card**: "Done for today" (first set) or "Set done" + "8 shorts · 5 questions · 4 of 5 right" +
   "What you met" (change chips with counts) + "Back to Home", "Another set", "Practice". No swipe past it;
   nothing continues by itself.
7. **After the set**: the Watch tab in the Mix shows the summary card until the learner picks an action;
   "Another set" builds a new set (not today's) with the same rules; when no set can be built, the "Mix with
   nothing left" end card shows (F-06).
8. No streaks, no "you missed a day" messages, no time-spent badges.

**States**
| State | Behaviour |
|---|---|
| First run / Empty | Home card "Today's set starts with your first clip." |
| Loading | set generation happens off the UI thread in ≤ 1 s; the card shows "Preparing today's set…" only after 300 ms |
| Offline / Unreachable | fully works on the phone |
| Partial: fewer eligible shorts than 8 | the set is shorter and says so: "Only 4 shorts today: add a clip for more." |
| Partial: not enough material for questions | questions skipped with "Not enough clips to build questions yet." |
| Interrupted mid-set | Home shows "Today · 3 of 8 done · Continue" |
| A clip used in the set is deleted | its shorts drop from the remaining set and the count updates |
| Error | n/a (local) |

**Acceptance criteria**
- **F-08-AC1** Given 3 saved shorts last watched 4 days ago and 20 unseen shorts, When today's set is generated, Then
  it holds 2 saved shorts and 6 unseen shorts, and no two consecutive shorts share a spotlight change when another
  order exists.
- **F-08-AC2** Given the learner completed 3 shorts and left, When they reopen the set the same day, Then the same
  set resumes at short 4.
- **F-08-AC3** Given today's set was generated, When a new clip arrives on the phone, Then today's set is unchanged
  and the next set may use the new clip.
- **F-08-AC4** Given the device clock passes local midnight [emulator], When Home shows, Then a new today's set is
  offered.
- **F-08-AC5** Given the set's questions are answered, When the summary shows, Then "N of 5 right" matches the
  answers and a further swipe loads nothing.
- **F-08-AC6** Given a wrong answer, When the explanation shows, Then "Save" is offered and nothing is added to Saved
  unless it is tapped.

**Data.** On the phone only: clip data, seen state, practice stats, saved shorts. Question logic ported from
`web/src/features/practice/quiz.ts` (seeded, no reused occurrence). **Priority:** v1-core.

---

### F-09 Saved shorts (v1-core)

**Purpose.** Keep hard moments and meet them again. (Spaced repetition on a schedule was considered and moved to
Later: it has no web precedent and needs its own data model and prompts; Q-6.)

**User stories**
- As a learner, I want to save a short or a word that was hard.
- As a learner, I want my saved shorts to come back without managing them.

**Behaviour**
1. "Save" on a short saves its anchor (job + first phrase) and its spotlight word; "Save this word" in a lesson
   and "Save" in a question's explanation save a word (anchored to job + segment + word, and to the short that
   contains it).
2. Saved shorts come back by one rule: a set includes up to 2 saved shorts not watched for 3 or more days (F-08).
   No intervals, no "Learned" state, no self-check prompt.
3. **Saved screen** (Practice > "Saved"): one list, most recently saved first; each row: word, change, clip,
   "Play", "Last watched {time}"; "Watch saved" opens the Saved mode; unsave from the row, the short or the lesson.
4. Saved items of a clip deleted on the computer disappear with it, counted in the deletion notice.

**States**
| State | Behaviour |
|---|---|
| First run / Empty | "Nothing saved yet. Tap “Save” on a short or a word you want to hear again." |
| Loading / Offline / Unreachable | local; always works |
| Partial | an item whose clip is "Not on this phone" stays listed with "Download the clip to watch it." |
| Error | n/a |

**Acceptance criteria**
- **F-09-AC1** Given the learner taps "Save" on a short, When they open Saved, Then the item is listed first with its
  spotlight word.
- **F-09-AC2** Given a saved short watched today, When tomorrow's set is generated, Then it is not in the set; Given
  it was last watched 3 days ago, Then it can be.
- **F-09-AC3** Given a saved short and a later "shorts v2" rule, When the item opens, Then it opens the short
  containing its anchor phrase.

**Data.** Local only. **Priority:** v1-core.

---

### F-10 Word lesson: five steps (v1-core; step 4 v1-complete)

**Purpose.** The didactic core, carried over from the web (`WordLesson.tsx`, [WF 3.6]) and reshaped for one hand.

**User stories**
- As a learner, I want to hear the word alone and in its phrase, slowly; see the dictionary form next to what was
  said, sound by sound; know why it changed and whether I may copy it; see the mouth; and practise it.
- As a learner, I want to move to the next word with a change without going back to the feed.

**Behaviour**
1. Opens as a sheet over the paused feed (half height, expandable to full height), or full height from the Clip
   page, Learn examples, Practice explanations and Saved. Closing returns exactly where it was.
2. **Header**: the word ("“water”"), "Word 12 of 431 · phrase 4 · 0:12.3–0:12.7", change chips (icon + name),
   reliability badge "Low confidence" / "Nothing heard here" (web), "Save this word", "Previous word" / "Next
   word" (all words, or only filtered ones when a transcript filter is on, as the web's N / Shift+N), "Next
   change" (the next word with any change, possibly in a later phrase; new on the phone, listed in 9.6).
3. **Steps** as numbered sections. Settings > Learning > "Lesson guidance": "Full" (steps 1 to 4 open, 5 closed)
   or "Compact" (titles only) (web rule). State resets per word (web).
   - **1 Listen**: "Play the word", "Play with “{next}”" (only when the change crosses the boundary, with "This
     change happens between “did” and “you”: listen to them together.", web), "Play the phrase"; speed 0.5× /
     0.75× / 1×; "Loop: repeat what you play" (web); track choice when a dialogue track exists.
   - **2 Compare**: "Dictionary /…/" and "What was said […]" in IPA, each with "What's this?"; sound by sound,
     every expected and heard sound with its mark **= same, ≠ changed, – dropped, + added**, each also as a word
     for TalkBack. On a phone the comparison MUST show every sound without truncation (one row per sound, or a
     scroller inside the step with the changed sound brought into view first: the designer chooses). When the
     next word is included, a divider and a header per word. Boundary gap: "Gap between “did” and “you”: 12 ms —
     they run together: that is the linking (an unlinked boundary sits nearer 60 ms)." (web). Tapping a sound
     plays it and opens its card: symbol, name ("voiced alveolar tap"), what the mouth does, "As in “water”",
     "Play this sound", "See it in the mouth", "Open in the IPA chart". Numbers and symbols without a dictionary
     form show the web notice.
   - **3 Why it changes**: one card per change: name + "What's this?", the description with an IPA example
     (`reference.descriptions`), the kind of link for linking, contraction "wanna ← want to", practice badge +
     register + the report's why, "Watch more like this" (change mode) and "Learn about {change}". Low-confidence
     notices first (web copy). No change: "Nothing changed: this word was said the way the dictionary says it."
     (web). Footer facts: prominence, divergence, not-in-dictionary, attraction (web).
   - **4 See the mouth** (v1-complete; fallback if cut: each sound's button shows its name and the articulation
     table's "what the mouth does" sentence, with no drawing): the 2D side view of the mouth (the web's
     midsagittal articulator, a port, [AD 2.5])
     following the playhead over the whole phrase; "Show the mouth for: What was said | Dictionary form"; one
     button per sound to hold the mouth there and play it; live caption "[ɾ] voiced alveolar tap · as in
     “water”" + cue; "Follow the playback again"; footer "The shapes in between the sounds are not decoration:
     they are the coarticulation this analysis measures." (web). Symbols without a drawing: button disabled with
     "No drawing for this sound yet."
   - **5 Practice**: shadowing (F-12) and record yourself (F-13) on "the word and the next" or "the whole
     phrase".

**States**
| State | Behaviour |
|---|---|
| First run / Empty | n/a |
| Loading | opens in ≤ 500 ms from local data |
| Offline / Unreachable | steps 1 to 5 work, except the pitch comparison in record yourself (F-13) |
| Error | clip deleted while open: the lesson stays readable until closed, then the deletion notice shows |
| Partial: low confidence / nothing heard | badge in the header; step 3 opens with the web notice; the word is still studiable |
| Partial: no dictionary form | step 2 notice; steps 1, 4 (what was said only) and 5 work |
| Partial: no audio | steps 1 and 5 disabled with the reason; 2 to 4 readable |
| Partial: espeak analysis (older data) | "Expected by the aligner" row and form-scoring notes as on the web; never crashes |

**Acceptance criteria**
- **F-10-AC1** Given the word “water” with flapping, When step 2 shows, Then the expected [t] and heard [ɾ] are
  paired with "≠" and TalkBack reads "changed".
- **F-10-AC2** Given a dropped sound, When step 2 shows, Then it carries "–" and TalkBack reads "{symbol}, not
  heard".
- **F-10-AC3** Given Lesson guidance "Compact", When a lesson opens, Then only the five step titles are visible and
  each opens on tap.
- **F-10-AC4** Given airplane mode [emulator] and step 4, When the learner taps [ɾ], Then the drawing holds the [ɾ]
  pose, the sound plays and the caption names it.
- **F-10-AC5** Given "Next change" on word i, When tapped, Then the lesson moves to the next word with at least one
  change.
- **F-10-AC6** Given the app process is killed with the lesson open at step 3, When the learner returns, Then the
  lesson reopens at step 3 on the same word.

**Data.** Words: `canonical_aligned`, `realized_aligned`, `dict_ipa`, `realized_ipa`, `phenomena`,
`boundary_link_next`, `boundary_link_type`, `lexical_form`, `lexical_expansion`, `low_confidence`, `diff_cost`,
`oov`, `no_canonical`, `attracted_count`, `form` (espeak only); segment `prominence`, `word_classes`;
`reference.*`; articulation table, geometry and IPA glossary (static, single source: S-18). **Priority:** v1-core;
step 4 v1-complete.

---

### F-11 Phrase details: pitch, stress and IPA (v1-complete)

**Purpose.** The web's phrase details [WF 3.5] for intonation and stress, from a short's "More" sheet or a
transcript phrase.

**Behaviour.** A sheet titled "Pitch, stress and IPA of this phrase": "What was said […]" and "Dictionary /…/"
for the whole phrase with ‿ (web `joinIpa`); voice line ("average pitch 187 Hz, range 5.2 semitones");
intonation units with type, actual and expected contour, "uptalk" chip; prominence per word (content vs function
word told apart by shape, with a text list "Stressed: GOT, KNOW"); pitch chart with a playhead, a text summary
("Pitch between 90 and 210 Hz; a range of 5.2 semitones; the phrase falls at the end.", web) and "Show as a
table"; touch scrubbing with the value announced; rhythm line labelled "approximate" (web). Each term has "What's
this?".

**States.** Loading: local, instant. Offline: works. Partial: no measurable pitch → "No measurable pitch in this
phrase (whispered or voiceless speech, music or silence)." (web); no intonation units → "ends: falls" fallback
(web). Empty/First run/Error: n/a.

**Acceptance criteria**
- **F-11-AC1** Given a phrase with an uptalk unit, When details open, Then the uptalk chip and its "What's this?" are
  present and the text summary says the statement ends rising.
- **F-11-AC2** Given the chart, When "Show as a table" is tapped, Then the same values appear as rows.

**Data.** `f0_track`, `f0_stats`, `intonation_units`, `prominence`, `word_classes`, `rhythm`. **Priority:**
v1-complete. Fallback if cut: a text-only sheet (both IPA lines, the voice line, intonation units, the list of
stressed words), with no charts.

---

### F-12 Shadowing (v1-core)

**Purpose.** Pace repeat-after-me: listen slowly, say it in the silence, end at full speed. The phone is the
better device for it [WF 9.1].

**Behaviour** (web `lib/shadowing.ts`). From the rail ("Shadow", current phrase) or lesson step 5. Choose what
("The word and the next" / "The whole phrase"; default the phrase when it is ≤ 6 s) and slow speed (0.5× or
0.75×). "Start shadowing" runs 3 rounds of (listen at the slow speed, then a silence as long as what was played +
400 ms) and 1 round at full speed: **4 rounds, 8 phases** [AD 3] (3 rounds only when the slow speed is 1×). Live
text "Round 2 of 4: listen at 0.75×" / "Round 2 of 4: your turn — say it now" (web), a progress indicator, an
optional short haptic tick at "your turn" (follows system haptics; never the only cue). "Stop" restores the
previous speed. Intro line: "Shadowing: listen, then say it in the silence that follows — copying the rhythm and
the reductions, not just the sounds. Start slow; the last round is at full speed." (web). Nothing is recorded.
For "Recognize only" changes the card adds "You can shadow it to train your ear; you don't need to copy it."

**States.** Offline/Unreachable: works. Error: audio focus lost (a call) → stops with "Shadowing stopped."
Swiping to another short or closing the lesson stops it and restores the speed. Empty/Loading/Partial: n/a; no
audio → action disabled with the reason.

**Acceptance criteria**
- **F-12-AC1** Given a 2 s phrase at 0.75×, When shadowing runs, Then 8 phases occur in order: listens of about 2.67
  s (rounds 1 to 3) and 2 s (round 4), each followed by a silence 400 ms longer than its listen.
- **F-12-AC2** Given slow speed 1×, When the shadowing plan is computed, Then it has 3 rounds (a unit test of the
  plan function only: the UI offers 0.5× and 0.75×, so this branch, kept faithful to `web/src/lib/shadowing.ts`, is
  not reachable from the screen).
- **F-12-AC3** Given shadowing is running, When the learner swipes to another short, Then it stops and the speed is
  restored.
- **F-12-AC4** Given TalkBack, When each phase starts, Then its text is announced politely.

**Data.** Audio rendition and spans. **Priority:** v1-core.

---

### F-13 Record yourself (v1-complete; pitch comparison needs the computer)

**Purpose.** Hear yourself next to the original, and, when the computer is reachable, see your pitch next to the
original's with plain-language observations [WF 3.6]. "This is a description, not a grade." (web)

**User stories**
- As a learner on the bus, I want to record myself and compare by ear, without my computer.
- As a learner at home, I want to see whether my melody rises or falls like the original.

**Behaviour**
1. Section "Record yourself — Say it, then see your pitch next to the original's." (web), in lesson step 5 and in
   the short's "More" sheet (current phrase). Privacy line: connected "Sent only to your computer, measured there
   and deleted right away. Nothing is saved."; otherwise "Stays on this phone and is deleted when you leave this
   word." Hint: "Use earphones so the original doesn't leak into your recording." Single word: "Pitch shapes need
   a few syllables. For intonation, record the whole phrase." (web)
2. One main button "Record" → "Stop" → "Record again"; switch "Play the original first" (plays the original at
   normal speed, waits 250 ms, then opens the microphone; persisted, web); "Discard". Auto-stop at 30 s; minimum
   0.25 s; live hints "We can barely hear you: move closer or speak up." / "Too loud: move back a little." (web).
   Recording avoids device noise suppression and automatic gain where the device allows (web disables them,
   [AD 4.6]).
3. After a take, always (offline too): "Original" / "Yours" / "Alternate" (original then take, three times, 500 ms
   gaps, announcing "Original" / "Yours"); "Yours" trimmed to the speech and loudness-matched (web behaviour).
4. When connected, the take is measured on the computer: "Measuring your pitch…", then the overlay (original solid,
   yours dashed, told apart by dash, weight, end marker and labels, never color) in semitones from each voice's
   median, a text summary, "Show as a table", and observations grouped "How they differ" / "How they're alike" /
   "Couldn't measure" (web), with the footer "This is a description, not a grade."
5. Not connected: "Pitch comparison needs your computer. You can still compare by ear." Takes are never queued
   (privacy; takes are deliberately never stored [BE 10]).
6. The take lives in memory or a temporary file until the learner leaves the word; never in shared storage,
   never backed up.

**States**
| State | Copy |
|---|---|
| First use | one-line rationale "PhonoTrainer needs the microphone to record you saying this phrase." then the system dialog |
| Permission denied | "Microphone blocked" / "Allow the microphone for PhonoTrainer in Android settings, then try again." + "Open settings" |
| Microphone busy | "The microphone is in use by another app." |
| Loading (measuring) | "Measuring your pitch…"; no answer within 20 s: "Measuring took too long. Try again." (measuring runs beside the analysis worker, two at a time, `phonotrainer/server.py:80`, so it is slower, not queued, while the computer analyzes) |
| Offline / Unreachable | step 5 above; no network request is attempted |
| Error by server code | `no_voice` "We couldn't hear a voice" / "Check that the microphone isn't covered or muted. Play your take to hear what was recorded."; `too_short` "That was very short" / "Record the whole word or phrase."; `too_long` "Too long: takes stop at 30 seconds."; `unsupported_type` / `undecodable` "Couldn't read the recording"; `empty` "The recording arrived empty" / "Record it again."; `too_large` "That recording is too large to send" / "Record a shorter part."; `ffmpeg_missing` "Your computer can't measure recordings yet (ffmpeg is missing)."; any other or unknown code (`bad_span`, `missing_file`, `length_required` are app defects and are logged without content): "Couldn't measure this take. Try again." A/B playback keeps working |
| Partial | n/a |

**Acceptance criteria**
- **F-13-AC1** Given the phone is offline, When the learner records a take, Then "Original", "Yours" and "Alternate"
  work and the comparison area shows the offline copy without any network attempt.
- **F-13-AC2** Given the computer is connected and not analyzing, When a 3 s take is stopped, Then it is sent with a
  known Content-Length (the server refuses chunked bodies with 411, `phonotrainer/server.py:622-625`) in an accepted
  type (AAC in MP4, Ogg/Opus or WAV; never 3GPP/AMR [BE 10]), and the overlay and observations appear within 10 s.
- **F-13-AC3** Given the fake computer answers with an unknown error code, When the result shows, Then the generic
  copy appears and the take still plays.
- **F-13-AC4** Given the learner leaves the word, When they return, Then the take is gone.
- **F-13-AC5** Given the server answers `no_voice`, When the result shows, Then the "We couldn't hear a voice" copy
  appears and the take still plays.

**Data.** `GET /api/jobs/{id}/contour`, `POST /api/jobs/{id}/compare` (multipart `file`, `start`, `end`;
Content-Length required; ≤ 8 MiB; 30 s), `reference.recording`; available to paired phones (S-16). **Priority:**
v1-complete. Fallback if cut: lesson step 5 offers shadowing only.

---

### F-14 "What's this?" and "Words we use" (v1-core)

**Purpose.** On-demand plain-English explanations wherever a term appears (web `Explain`, [WF 4.2]).

**Behaviour.** A "?" control after each term (≥ 48 dp target even if drawn small) opens a bottom sheet: title, 1 to
3 sentences, example (IPA in the IPA font), "Learn more" link. Sources: 27 general terms, 12 metrics, about 78 IPA
keys (web glossary), and the 16 changes (server description + practice badge + register + why + "Watch more like
this"). Learn holds a searchable index "Words we use" (terms and metrics). TalkBack name "What's this: {term}"
(web). Closing returns focus to the "?" control.

**"Words we use" page** (opened from Learn; copy fixed by D-47): title "Words we use"; lede "Plain-English
explanations of the terms and measures PhonoTrainer uses. Each one also opens from the “?” next to it."; search field
"Search the words we use…" with a live count "{n} of {total} terms" (as the Library's); two groups, "Terms" and
"Measures", each row showing the term and the first sentence of its explanation; a row opens the same "What's this?"
sheet. No match: "No term matches “{text}”." + "Clear the search" (no illustration: it is not an empty state).

**States.** Always available offline and before pairing (bundled). A term missing from the content shows the
server description if one exists, otherwise no "?" is shown (never an empty sheet). Loading/Error/Partial: n/a.

**Acceptance criteria**
- **F-14-AC1** Given the Compare step, When the learner taps "?" next to "What was said", Then the sheet shows the
  web entry with its example, and closing it returns focus to the "?".
- **F-14-AC2** Given a fresh install with no pairing and no network, When any "?" is opened, Then its content shows.
- **F-14-AC3** Given the "Words we use" index, When the learner searches "schwa", Then the entry is found.
- **F-14-AC4** Given the "Words we use" index, When the learner searches "xyzzy", Then "No term matches “xyzzy”." and
  "Clear the search" show, TalkBack hears "0 of {total} terms", and "Clear the search" restores every row.

**Data.** Glossary content identical to `web/src/didactic/glossary/*` from a single source (S-18);
`reference.descriptions`, `practice`. **Priority:** v1-core.

---

### F-15 Learn: changes and phenomenon pages (v1-core; mouth demo v1-complete)

**Purpose.** Reference pages per change (web `/learn`, [WF 3.9, 3.10]) that also feed the feed: "Watch examples"
turns a page into a feed of one change.

**Behaviour**
1. **Learn home**: lede "How native speakers change words when they talk — and which of those changes you can
   copy." (web); "How to study a change" (web's three steps, adapted: "Read what happens and whether you can copy
   it." · "Watch it in your clips, slowly if you need to." · "Train your ear in Practice."); sections per family +
   "Whole-word changes"; each change card: icon, name in its family line style, description with IPA, practice
   badge + register, "34 shorts in your clips" (the short count of F-03 item 8) / "Not in your clips yet". Entries
   to "IPA chart", "Insights",
   "Words we use".
2. **Phenomenon page**: description; "How to hear it" (`listenFor`); "For Spanish speakers: …" where the content
   has a tip (3 changes today); caveat note; "Can you copy it?" (badge, why, register meaning, web); "The mouth"
   demo (v1-complete, with the articulator: word, "What is said | Dictionary form", "Slow | Normal", "Play the
   movement", sound buttons; web `MouthDemo`; omitted for `word_elision`, which has none); primary **"Watch
   examples"** (change mode) and "Practice this" (Practice with focus); "Examples from your clips" (up to 8,
   clearest first, one per word, each playable with "Open in its lesson", web `pickExamples`).
3. Counts and examples come from the clips on the phone (computed locally, D-16), in two named units (D-48): the
   Learn change card shows **shorts** ("34 shorts in your clips", the same number as the Home chip and the mode
   picker, so it matches the feed "Watch examples" opens); the phenomenon page shows **occurrences**, eligible
   occurrences only (playable, not low confidence): "12 times in your clips" above "Examples from your clips". When
   some clips of the catalogue are not on this phone, the page says "Counted in the clips on this phone." (The
   computer's raw `phenomena_counts` use a different unit and are never compared with them.)

**States**
| State | Behaviour |
|---|---|
| First run | all 16 pages and the IPA chart open with full text, before pairing |
| Empty (no examples) | "No examples in your clips yet — When an analysis finds this change, its clearest cases will appear here." + "Add a clip" (web) |
| Loading | counts appear after the local index is ready; text shows at once |
| Offline / Unreachable | fully works |
| Error | local index unreadable: "Your clip counts could not be loaded; the explanations still work." (web) |
| Partial | counts from the phone only, labelled as above |

**Acceptance criteria**
- **F-15-AC1** Given 12 eligible flapping occurrences in 9 shorts on the phone and no network, When the Flapping page
  opens, Then it shows "12 times in your clips" and up to 8 playable examples, and Learn's Flapping card reads
  "9 shorts in your clips".
- **F-15-AC2** Given "Watch examples" on Flapping, When Watch opens, Then the mode is "Flapping" and it ends with the
  change end card.
- **F-15-AC3** Given the vowel_reduction page, When it renders, Then the "For Spanish speakers" tip from the web
  content shows.

**Data.** `reference.families`, `labels`, `descriptions`, `practice`; Learn content (S-18); local occurrence
index built from downloaded clips (the phone's equivalent of `/api/corpus/occurrences`, [BE 11]). **Priority:**
v1-core; mouth demo v1-complete.

---

### F-16 IPA chart (v1-core; mouths v1-complete)

**Purpose.** Every symbol the app shows can be looked up, heard in the learner's clips and seen in the mouth (web
`/learn/ipa`, [WF 3.11]). The chart is v1-core; the mouths are v1-complete with the articulator (fallback: the
symbol sheet without a drawing).

**Behaviour.** Sections: Consonants (by manner, places in plain words with the technical term, reflowed for a
phone so the page never scrolls sideways), "Sounds that replace t and d", Syllabic consonants, Vowels (weak vowels
marked by a dotted underline and the word "weak"), Diphthongs, Vowels with r. Symbol buttons ≥ 48 dp. Selecting
opens the symbol sheet: symbol, name, the mouth from rest to the sound with "Play the movement", glossary body and
learner note (web), "As in …", the change it signals ("It is the sound of flapping"), "In your clips" (up to 6
playable examples). Nothing selected: "Choose a symbol — Each symbol is one sound. Start with [ɾ], [t̚] or [ə]"
(web).

**States.** First run: chart and mouths work; "In your clips" empty with "No examples in your clips yet."
Offline: works. Loading/Error/Partial: as F-15.

**Acceptance criteria**
- **F-16-AC1** Given the largest system font size on a 360 dp wide screen, When the chart renders, Then every symbol
  is reachable without sideways page scrolling and no symbol is clipped.
- **F-16-AC2** Given TalkBack, When focus lands on a symbol, Then it reads the symbol's name ("voiced alveolar tap"),
  not only the glyph.
- **F-16-AC3** Given [ɾ] selected and examples on the phone, When "In your clips" shows, Then up to 6 examples play.

**Data.** IPA layout, articulation table, IPA glossary (S-18); `reference.ipa_tokens`; local index.
**Priority:** v1-core; mouths v1-complete.

---

### F-17 Practice: ear-training quiz and progress (v1-core)

**Purpose.** Retrieval practice from the learner's own clips (web Practice, [WF 3.12]), offline, plus a view of
progress over weeks without grades or guilt.

**Behaviour**
1. **Start screen**: "Train your ear with words and phrases from your own clips. A few minutes a day is enough."
   (web); the three kinds explained (web copy); focus chip "Only: flapping" + "Practice everything instead";
   switch "Focus on my weak spots" (weight 1.5 − accuracy, unseen 0.5, web); "Start practicing".
2. **Session**: 10 questions, web `quiz.ts` rules (pool of playable occurrences, kinds rotate, seeded, no reused
   occurrence; with a focus the "Which change?" kind is skipped); "Which pronunciation?" (2 to 4 IPA options
   including other attested pronunciations of the same word, computed on the phone), "Which change?" (correct +
   3 from other families when possible), "How many words?" (a 3 to 9 word phrase); "Play", "Slowly", "With
   context"; options ≥ 56 dp tall.
3. **After each answer**: "Correct!" / "Not quite — the answer was [ɾ]." (icon + words + outline), explanation
   (word /dict/ → [said], change chip, advice, description), "Again at 0.5×", "Learn about {change}", "Open this
   word's lesson", "Watch more like this" (change mode), "Save" (nothing is saved unless tapped).
4. **Results**: "7 of 10 right", "Worth reviewing" links, "Practice again", "Practice my weak spots".
5. **Progress** (on the start screen, below): "Your accuracy so far — Kept only on this phone." (web: per change
   "7 of 10 · 70 %", weakest first; "Reset" with "Forget your practice history?"); "Saved: 12" (→ Saved);
   "Changes you have met: 11 of 16" (a change is met when a short with that spotlight was seen or a question on it
   was answered) with "Not met yet" ones linking to Learn. Trends over weeks and days practised are Later (9.7).

**States**
| State | Behaviour |
|---|---|
| First run / Empty | "Nothing to practice yet — Practice builds its questions from the changes found in your clips. Add a clip first — a minute of conversation already gives dozens of questions." (web, adapted) |
| Loading | session generation ≤ 1 s; "Gathering words from your clips…" (web) after 300 ms |
| Offline / Unreachable | fully works |
| Error / Partial | pool exhausted early: "There were not enough clips to build questions this time." (web) |
| Process death mid-session | resumes at the same question |

**Acceptance criteria**
- **F-17-AC1** Given clips on the phone and no network, When Practice starts, Then 10 questions are generated and
  each plays.
- **F-17-AC2** Given a focus on one change (the focus chip), When the session runs, Then no "Which change?"
  question appears.
- **F-17-AC3** Given a "Which change?" question on a flapping word, When options are built, Then the 3 distractors
  are changes the word does not have, from other families when possible.
- **F-17-AC4** Given a wrong answer, When the explanation shows, Then the per-change accuracy updates and "Save" is
  offered.
- **F-17-AC5** Given no practice for a week, When Progress shows, Then nothing mentions streaks, missed days or time
  spent.

**Data.** Local occurrence index and variants (equivalents of `/api/corpus/occurrences` and `/variants`), clip
audio, local stats. **Priority:** v1-core.

---

### F-18 Insights: how reduced is what you hear (v1-complete)

**Purpose.** Measure the learner's own material against published research, in plain words (web `/insights`,
[WF 3.13]).

**Behaviour.** Overview "10 clips on this phone · 4,321 words · 42 min of speech"; "How reduced is what you hear"
metric meters with value, published band and source ("Published: > 60 % · Johnson 2004"), each with "What's
this?" and "Show as a table"; engines never pooled (a segmented choice only when more than one engine exists);
"What this means for your listening" takeaways (web); "Changes across your clips" (icon + name + count; tap →
change mode). Computed on the phone by pooling each clip's `summary.metrics` counts (ratios are `{count, of}`, so
pooling is exact). The web occurrences table (a 44-rem table) is replaced by change modes and phenomenon pages.

**States.** Empty: "Your clips are counted here — Every clip you add shows how reduced the English you hear really
is." Offline: full. Partial: "2 clips are older and are not measured."; mixed rule versions warning (web).
Loading/Error: as F-15.

**Acceptance criteria**
- **F-18-AC1** Given two clips with flapping `{count: 30, of: 40}` and `{count: 10, of: 20}`, When Insights shows
  flapping, Then it reads 40 of 60 (67 %).
- **F-18-AC2** Given every chart, When "Show as a table" is tapped, Then the same values appear as rows.

**Data.** `summary.metrics` per clip, `reference.metrics_reference`, `metric_labels`. **Priority:** v1-complete.
Fallback if cut: no Insights destination and no Home teaser; Learn counts and change modes remain.

---

### F-19 Add a clip: in-app add and share target (v1-core)

**Purpose.** "Process from the phone" [Brief]: links shared from YouTube and X through the system share sheet,
links pasted in the app, and local video or audio files. Full model in section 8; this is the in-app entry.

**User stories**
- As a learner in the YouTube or X app, I want to tap Share → PhonoTrainer and be back in my video in seconds.
- As a learner with a video recorded or saved on my phone, I want my computer to analyze it.
- As a learner away from home, I want the request to wait safely and go by itself when I am back.

**Behaviour.** "Add a clip" screen, lede "Pick a clip of native English speech. Short, clear dialogue (30–60
seconds) teaches the most." (web):
- "Paste a link": field + "Paste" (the clipboard is read only on this tap); the first http(s) URL is extracted;
  site recognized as "YouTube", "YouTube Shorts" or "X"; any other http(s) link is accepted with "Other website:
  downloads may not work."; no URL → "That doesn't look like a link."
- "Choose a file": the system picker for video and audio (up to 10 files; each becomes one request); shows name,
  duration and size. A picked file keeps a lasting read permission until its copy is complete, so a copy
  interrupted by the app being closed restarts by itself (shared files cannot, 7.7).
- Options: links "Audio only" switch ("Smaller and faster. There is no video to watch with the shorts.", adapted
  web copy; off); local video "Audio only" (on by default above 500 MB); switch "Separate the dialogue from music
  and effects" (on; "Slower (about 0.7× the clip's length), but much better on films and series with music.",
  web). Engine, speech model and language use the computer's defaults (D-08).
- Legal line for links: "Downloading may breach the website's terms of service; that responsibility is yours."
  (web, generalised from "platform").
- Primary "Analyze" when connected, "Add to queue" otherwise ("It starts when this phone can reach your
  computer."). The same confirmation logic and states as the share sheet (8.4).

**States and data**: section 8 (8.4.1 lists the states).

**Acceptance criteria** (the share-sheet ACs of 8.8 apply as well)
- **F-19-AC1** Given text on the clipboard, When the Add a clip screen opens, Then the clipboard is not read; When
  "Paste" is tapped, Then the first http(s) URL of the clipboard fills the field and its site is labelled.
- **F-19-AC2** Given the clipboard holds no URL, When "Paste" is tapped, Then "That doesn't look like a link." shows
  and "Analyze" / "Add to queue" stays disabled.
- **F-19-AC3** Given 3 files chosen in the picker, When the learner confirms, Then 3 requests exist, each with its
  own name, size and progress, and each reads "Added" only after its own copy completes.
- **F-19-AC4** Given a picked file whose copy was interrupted by the app process being killed [emulator], When the
  app starts again, Then the copy restarts by itself and the request is not lost.

**Priority:** v1-core.

---

### F-20 Requests: device queue, processing and failures (v1-core)

**Purpose.** One honest place that answers "what happened to what I asked for?", with failure as a recoverable
state, and the web's pipeline stepper with teaching lines so waiting also teaches how the analysis works [WF 3.3].

**User stories**
- As a learner, I want to see each request's state, the reason, and what I can do.
- As a learner, I want to cancel, retry, or try audio only.
- As a curious learner, I want to understand what my computer is doing during the minutes it takes.

**Behaviour**
1. **Requests screen**, groups in this order (empty groups hidden), each with a count:
   - "Needs your attention": this phone's requests that failed, were refused, or lost their file copy; reason in
     one sentence + primary action.
   - "Preparing": files being copied into the app ("Preparing the file… 40 %").
   - "Waiting on this phone": reason it waits ("Your computer isn't reachable", "Not on Wi-Fi", "Will try again
     in 2 min", "Couldn't send yet: will try again", and after "Stop sending" "Stopped by you: it waits here until you
     tap Send now."), age ("Added yesterday", "Added 15 days ago"), size for files. A file too large to send in one
     background run (7.7) adds the line "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent
     while the app is open." (D-41).
   - "Sending": "Sending 40 % · 125 of 312 MB".
   - "On your computer": "Waiting in line · 2nd" ("Analyses run one at a time.", web) or "Analyzing 61 % · Hear
     the phones · phrase 3 of 10" (web `stageSummary`).
   - "Getting it on this phone": "70 %".
   - "Done recently" (last 7 days): opens the clip; "Watch".
   - "Started on your computer" (collapsed by default; jobs created from the browser client): read-only. Active
     ones are shown so the learner knows why their own request waits in line; failed ones carry only "Hide on
     this phone". This group is never counted on Home, never in "Needs your attention", and never notified.
2. **Request detail**: source (site + link, or file name + size + duration); a timeline with icon + word per
   milestone: "Added on this phone" → "Sent to your computer" → "Waiting in line" → "Analyzing" → "Ready on your
   computer" → "On this phone", each with its time; during analysis the **stepper** with the web stages and
   teaching lines: Download ("Fetching the video from the website."; links only; the web says "from YouTube",
   generalised because X and other sites are accepted), Extract the audio, Separate the dialogue, Transcribe the
   words, Hear the phones, Compare and save; each with its status word (done / in progress / next / skipped /
   failed / cancelled) and detail ("phrase 3 of 10"). Estimate, only once the clip's duration is known (a file,
   or a link after its download): "Usually takes about as long as the clip (about 4 min)."; before that, for a
   link: "Usually takes about as long as the clip, once it's downloaded." First run after a computer start: "The
   first analysis after your computer starts loads about 1.8 GB of models, so it starts slowly." (web). "Show
   the log" (newest first; terminal color codes removed). "Show details" for errors (the computer's message,
   cleaned).
3. **Actions by state** (visible buttons; swipe actions are only shortcuts):
   | State (icon + words) | Actions |
   |---|---|
   | Preparing the file (copy, or audio extraction for "Audio only") | "Cancel" |
   | Couldn't keep the file (copy interrupted by a restart, file unreadable, or no space; 7.7) | "Remove" (+ "Try audio only" for a video refused for space, only while the file can still be read: a file picked in the app; a shared file must be shared again, D-49) |
   | The copy kept on this phone is missing (deleted outside the app) | "Remove" ("Share the file again to analyze it.") |
   | Waiting on this phone | "Send now" (only when connected), "Remove" (files: "The copy kept on this phone is deleted too.") |
   | Sending | "Stop sending" (back to waiting, held until "Send now", D-46), "Remove" |
   | Sent / Waiting in line / Analyzing | "Cancel" (confirm "Stop analyzing “{title}” on your computer?"); offline: becomes "Cancel waiting for your computer" with "Undo"; "Undo" confirms with "Cancel undone: “{title}” won't be cancelled." |
   | Getting it on this phone | none (progress only) |
   | Couldn't download (link) | "Try again", "Try audio only", "Open the link", "Remove" (the exact title, body and actions per failure code are in 8.6, including a live stream or a video longer than the computer's limit) |
   | Analysis failed | "Try again", "Show details", "Remove" |
   | Refused by your computer at sending (file type, file too large, a link to a private address) | "Try audio only" (videos too large), "Remove" |
   | Interrupted (after the one automatic re-run, 7.7) | "Try again", "Remove" |
   | Cancelled | "Try again", "Remove" |
   | Queue paused: "Connect again" / "Update needed" | "Connect again" / "Why?" |
4. **"Try again" and "Try audio only"** ask the computer to **re-run** the request from what it already stored (the
   link, or the uploaded file) with the same options (or audio only, for links) (S-22). Nothing is uploaded
   again, so it works after the phone deleted its copy. The re-run carries its own idempotency key, linked to the
   original; the computer's new job replaces the failed one, so there are never two rows for one intent (web Retry
   precedent). If the computer no longer has the uploaded file: "Your computer no longer has this file. Share it
   again." with "Remove". "Remove" on a failed or cancelled request also removes its failed job from the computer
   (own jobs only; queued if offline).
5. Stage changes are announced politely to TalkBack ("Now: Transcribe the words."), never every percent (web).

**States**
| State | Behaviour |
|---|---|
| First run / Empty | "Nothing in progress. Share a video from YouTube or X, or add a file, to analyze a new clip." + "Add a clip" |
| Loading | local; instant |
| Offline / Unreachable | computer-side states show "Last known: Analyzing 40 % (10 min ago)"; actions that need the computer read "…when connected" |
| Error | queue store unreadable: "Your requests could not be loaded." + "Try again"; the queue is never cleared by this error |
| Partial | after "Forget this computer": requests that computer had accepted are no longer tracked; the learner confirmed their removal when forgetting (7.9) |
| Computer restarted mid-analysis | every own job of that computer that was waiting in line or analyzing is re-run once automatically, in its original order, and reads "Starting again on your computer" until the computer accepts the re-run; if a re-run fails again → "Needs your attention" with "Your computer stopped during the analysis." |

**Acceptance criteria**
- **F-20-AC1** Given a link request waiting on this phone, When the (fake) computer becomes reachable, Then it moves
  to "Sending" and then "Waiting in line" or "Analyzing" without any action.
- **F-20-AC2** Given a request analyzing, When the stage changes, Then the stepper updates and TalkBack announces
  only the stage change.
- **F-20-AC3** Given a download failure (fake computer), When the learner taps "Try audio only", Then the computer is
  asked to re-run it with audio only, the failed row disappears once the re-run is accepted, and Requests shows one
  row for that link.
- **F-20-AC4** Given a file request whose analysis failed and whose phone copy was deleted after upload, When "Try
  again" is tapped, Then the computer re-runs it from its stored file, no upload happens, and one row remains.
- **F-20-AC5** Given the learner cancels an accepted request while offline, When the computer becomes reachable, Then
  the cancel is sent before any new request and the request shows "Cancelled"; and if the job had already finished,
  Then the clip is kept and the learner sees "Too late to cancel: “{title}” is ready. Remove it from this phone if
  you don't want it."
- **F-20-AC6** Given a 300 MB file waiting, When "Remove" is confirmed, Then the local copy is deleted and Storage
  decreases by that amount.
- **F-20-AC7** Given any failed state of an own request, When it is shown, Then at least one action is offered
  (SC-14), and "Show details" never contains terminal escape codes.
- **F-20-AC8** Given a failed job created from the browser client, When Requests shows, Then it sits only in the
  collapsed "Started on your computer" group with "Hide on this phone", and no count or notification includes it.
- **F-20-AC9** Given a waiting file request whose copy was deleted outside the app, When the queue is checked at
  start, Then the request shows the copy-missing state with "Remove", and nothing is sent.
- **F-20-AC10** Given a 600 MB file request waiting, no upload speed measured yet on this phone, and the app in the
  background, When the (fake) computer becomes reachable and a background sync runs, Then the file is not sent, the
  links waiting behind it are sent, and its row reads "Open PhonoTrainer on your home Wi-Fi to send this file: large
  files are sent while the app is open."; When the app is opened, Then the upload starts as a visible transfer.
- **F-20-AC11** Given a file request "Sending" to the (fake) computer, When the learner taps "Stop sending", Then the
  upload stops, the request is "Waiting on this phone" with "Stopped by you: it waits here until you tap Send now.",
  and it is not sent again while connected until "Send now" is tapped.
- **F-20-AC12** Given an accepted request that shows "Cancel waiting for your computer" while offline, When the learner
  taps "Undo", Then the request returns to its previous state, "Cancel undone: “{title}” won't be cancelled." shows
  once, and no cancel reaches the computer at the next connection.

**Data.** Local requests; `POST /api/jobs/youtube` `{url, options, audio_only}`, `POST /api/jobs/upload`
(made idempotent by S-09; resumable by S-10 in v1-complete), `POST /api/jobs/{id}/cancel`, `DELETE /api/jobs/{id}`
(own failed or cancelled jobs, S-06), `GET /api/jobs/{id}` (`progress`), `/ws/jobs` (authenticated, S-05); NEW
S-09 idempotency, S-13 stage, queue position, error codes and submitting device, S-22 re-run. **Priority:**
v1-core.

---

### F-21 Sync, downloads and storage (user-facing) (v1-core)

**Purpose.** "If the server has content the app does not have, the app downloads it." [Brief] Automatic, visible,
bounded by a budget. Full model in section 7; this is what the learner sees and controls.

**User stories**
- As a learner, I want new clips from my computer on my phone without doing anything.
- As a learner with limited space, I want to control whether videos are kept and how much space the app uses.

**Behaviour.** Sync runs on app start and resume, when the phone joins Wi-Fi, after a request finishes, from the
background on Wi-Fi when Android allows it (7.5), and on "Sync now" / pull to refresh. It sends waiting requests,
refreshes the catalogue, downloads every clip's study pack, then videos within the budget, and mirrors deletions
with the safeguards of 7.9. The UI never promises when a background sync will happen; it says "Last synced
{time}". Settings > Storage: total used of the budget; breakdown "Clips (text and audio)", "Videos", "Waiting to
send", "Cache"; budget choice (1 GB, **2 GB default**, 5 GB, "No limit except free space"); "Download videos
automatically" (on); per-clip list sorted by size with "Remove the video", "Remove from this phone", "Keep on this
phone"; "Remove all videos" (confirm).

**States**
| State | Behaviour |
|---|---|
| First run | nothing to sync; Storage shows zeros |
| Empty | computer has no clips: "Your computer has no clips yet." |
| Loading | per clip: "Clip data ✓ · Audio 60 % · Video waiting"; Home: "Bringing your clips to this phone: 3 of 10" |
| Offline / Unreachable | downloads paused: "Downloads continue the next time this phone reaches your computer." |
| Error | a verification failing twice: "Download failed: the file was damaged on the way." + "Try again"; the previous good version stays in use |
| Partial | study pack ready, video pending: the clip is studiable and says "Video downloading 40 %"; phone video being made: "Video getting ready on your computer"; no video possible: "Video not available from your computer" (study works) |
| Storage | free space under the floor (1 GB or 10 % of the phone's storage, whichever is smaller; 7.10): "Your phone is almost full. Downloads are paused."; budget reached: "Storage budget reached. Remove clips or raise the budget." |
| Background limited | Android restricts the app in the background: Storage and Connection details say "Android is limiting PhonoTrainer in the background: clips arrive when you open the app." + "Battery settings" |

**Acceptance criteria**
- **F-21-AC1** Given a file request finishes on the (fake or real) computer while the app is open on home Wi-Fi, When
  the phone notices it, Then its study pack is downloaded, verified and the clip is "On this phone" within 60 s
  (4-min clip).
- **F-21-AC2** Given "Download videos automatically" is off, When a new clip arrives, Then no video is downloaded and
  the clip reads "Video not on this phone".
- **F-21-AC3** Given a download interrupted at 60 %, When the connection returns, Then it resumes from at least 60 %
  (HTTP Range) and the clip is "On this phone" only after verification.
- **F-21-AC4** Given a 1 GB budget exceeded by a new video, When cleanup runs, Then the video of the least recently
  watched clip that is not kept and not in today's set is removed, and that clip stays "On this phone".
- **F-21-AC5** Given the computer reports a different identity than the paired one, When sync runs, Then nothing is
  deleted on the phone and the status says "A different computer answered".
- **F-21-AC6** Given a video clip whose original this phone cannot decode (or is above 1080p), When videos download,
  Then the phone asks the computer for a phone video, shows "Video getting ready on your computer", and downloads it
  once ready; the study pack never waits for it.
- **F-21-AC7** Given a video clip whose original is missing on the computer, When videos download, Then the clip
  reads "Video not available from your computer" and its shorts play as audio with captions.
- **F-21-AC8** Given background sync (forced in tests with `WorkManagerTestInitHelper`; on a device with `adb shell
  cmd jobscheduler run -f` for this app only), When a clip finished while the app was closed, Then the study pack is
  downloaded and "Last synced" updates.

**Data.** NEW S-11 listing, NEW S-12 renditions (each with a fallback), Range on artifact routes (exists [BE 2]).
**Priority:** v1-core (renditions: v1-complete, 14.1).

---

### F-22 Settings (v1-core)

All settings apply at once and are remembered on this phone. Groups and items (default first):
- **Display**: Appearance "System" / "Light" / "Dark"; Colors "Standard" / "Color-vision friendly" (web copy:
  "Blue, orange, vermilion and sky — kept apart even with red-green or blue-yellow color blindness. Turns on
  pattern emphasis.") with a preview of the four families; "Show patterns as well as colors" (follows Colors /
  on / off); Motion "Follow system" / "Reduce". Text size follows the system font size (no in-app duplicate,
  D-22).
- **Playback and captions**: default speed; "Play each short when it appears" (on); "At the end of a short:
  Replay it once / Stop / Go to the next one"; captions "Marks / Plain / IPA / Off"; "Listen first" (off);
  "Listen to: Original mix / Dialogue only"; clips left out of the Mix.
- **Learning**: "Questions after each set" (on); "Lesson guidance: Full / Compact".
- **Your computer**: name, status, last seen, last sync, "Check now", "Connection details" (the computer's address,
  "This phone on this Wi-Fi: {address}" (D-39: the operator compares it with the "Paired: … (from {address})" line
  the computer's terminal prints after pairing), short server id, API version, last probe result in words, pending
  counts, background restriction, "Copy diagnostics" without the credential, without any address (only whether it is
  a private or link-local one) and without ids), "Enter the new address", "Connect again", "Forget this computer…"
  ("This phone stops talking to {name}. Your clips stay on this phone; requests waiting here stay too. Requests
  {name} already accepted will no longer be followed here." default button "Cancel").
- **Storage**: F-21.
- **Notifications**: "When a clip is ready", "When a request needs attention" (both on), link to the system
  channel settings.
- **Help**: "Restart the tour", "What do the marks mean?", "How PhonoTrainer works" (the engines in plain words,
  web "About the engines").
- **About and licences**: F-24. Privacy statement: "PhonoTrainer has no account and sends nothing to the internet.
  Your clips and recordings go only to your own computer."

**States.** Offline/Unreachable: Your computer shows the 7.5 state; everything else local. Error: n/a.

**Acceptance criteria**
- **F-22-AC1** Given Colors "Color-vision friendly", When any mark renders, Then the family icon follows each marked
  word unless patterns were explicitly turned off.
- **F-22-AC2** Given Motion "Reduce", When swiping the feed, Then transitions are instant and no decorative animation
  plays, while the mouth drawing still follows the audio (content, web rule).
- **F-22-AC3** Given "Forget this computer" is confirmed, When Home shows, Then the credential is gone, the status
  reads "Not connected to a computer", every clip and waiting request remains, and the clips read "From a computer
  you forgot".
- **F-22-AC4** Given a paired phone on Wi-Fi, When Connection details opens, Then it shows "This phone on this Wi-Fi:"
  with the phone's current address on that network; and When "Copy diagnostics" is tapped, Then the copied text
  contains no address of the phone or the computer, no server or device id and no credential.

**Priority:** v1-core.

---

### F-23 Notifications (v1-core)

- **Channels**: "Clips ready" (default importance): "Ready to watch: {title} — 15 shorts, tap to start." (tap →
  feed "In order" on that clip; grouped when several); "Needs attention" (default): "Couldn't download {site}
  link — Tap to try again or try audio only." for `download_forbidden`, and for every other failure its 8.6 title
  + " — Tap to see what you can do." (D-40), so a notification never offers an action the request lacks (tap →
  request detail); "Transfers" (low, silent): ongoing
  progress only while a transfer started from the foreground runs (an upload the learner sent, or a download they
  asked for), with "Stop". Transfers started in the background run without a notification as ordinary resumable
  work (7.7).
- Only this phone's own requests notify; jobs started from the browser client never do.
- Permission (Android 13+) is asked in context the first time a request is created: "Get a notification when your
  clip is ready?" with two buttons, "Notify me" (then the system prompt) and "Not now" (no system prompt; the
  question is not asked again, and Settings > Notifications keeps the way to turn them on); never at first launch. Denial is respected; Home and Requests carry the
  same information.
- No notification for routine sync, percent ticks or missed days; no daily reminder in v1 (Later). Notifications
  never contain the computer's address or links with tracking parameters.

**States**
| State | Behaviour |
|---|---|
| First run / Empty | no channel posts anything until a request exists |
| Permission not yet asked | asked after the first request is safely stored (8.4) |
| Permission denied | nothing is posted; Home and Requests show the same facts; Settings > Notifications says "Notifications are off for PhonoTrainer" + "Open settings" |
| Offline / Unreachable | nothing new to notify; nothing is posted about connectivity |
| Error / Partial | n/a |

**Acceptance criteria**
- **F-23-AC1** Given notifications allowed, and a file request that finished on the computer while the app was closed
  and the phone on home Wi-Fi, When the next background sync runs (forced with the job-scheduler test hook, F-21),
  Then one "Ready to watch" notification appears and opens that clip's feed.
- **F-23-AC2** Given notification permission denied, When a request fails, Then no notification appears and Home
  shows "1 needs your attention".
- **F-23-AC3** Given a job created from the browser client fails, When sync runs, Then no notification appears.
- **F-23-AC4** Given notifications allowed and this phone's link job failing with `too_long` on the fake computer,
  When the failure is noticed, Then one "Needs attention" notification reads "This video is too long for your
  computer — Tap to see what you can do." and opens that request's detail.

**Priority:** v1-core.

---

### F-24 About and licences (v1-core)

Version; the computer's API version last seen; "PhonoTrainer is free software under the GNU GPL-3.0-or-later" with
the licence text; source location (the public repository); every third-party component, font and vendored icon set
with its licence (the same entries recorded in `THIRD-PARTY-NOTICES.md`), readable offline. Fonts and icons are not
libraries, so they are listed from their bundled licence texts, not from the dependency report (D-50).

**Acceptance criteria**
- **F-24-AC1** Given no network, When "Open-source licences" opens, Then every bundled licence text is readable.
- **F-24-AC2** Given the release dependency report and the bundled fonts and icon set, When About lists components,
  Then every runtime dependency, font and vendored icon set of the release build appears with its licence (the same
  list X-AC3 checks).

**Priority:** v1-core.

---

### F-25 Brand and content assets (v1-core)

"Create the necessary assets so the app is pleasant to use." [Brief] The web has no logo, icon, splash or
illustration to inherit; only the wordmark, a microphone glyph and the family system [WI 1]. Required assets
(content and purpose only; the design agent decides their look, and every asset is vector or generated, committed
with its source):

| Asset | Purpose / constraint |
|---|---|
| Adaptive launcher icon (background, foreground, monochrome) from one geometry; no legacy (pre-adaptive) icons, which no supported Android version uses (minSdk 29, D-50) | recognizable at share-sheet size next to WhatsApp and others |
| Splash mark (one-shot animation) | no loop; no blank flash in light or dark |
| Notification icon (monochrome) | legible in the status bar |
| Welcome illustrations (3) | one per Welcome page |
| Empty-state illustrations | feed empty, Home first run, Home no clips, Requests empty, no examples, Practice nothing yet, no computer found, Saved empty |
| Generated audio-only visual | deterministic per clip, draws the current phrase's pitch line; leaves room for captions; never uses family hues for decoration |
| Poster fallback | for video clips whose poster has not arrived or does not exist |
| Family icon set (6 glyphs) and practice icons (microphone, ear) | matched to the web's shapes (chevrons down, scissors, merge, link, shrink, dashed circle) [WI 3] |
| Status icons (waiting, sending, analyzing, ready, needs attention, cancelled, connected, checking, not reachable) | always paired with a word |
| Site icons "YouTube", "X", "Other website", "Phone file" | generic glyphs that do not reproduce third-party logos |
| Fonts | an IPA font that places combining marks correctly (Charis 7.000, unmodified TTF, the web's choice [WI 5.2]) and the UI font, both OFL with licence text, recorded in `THIRD-PARTY-NOTICES.md` [AD 4.1] |
| Mouth (articulator) drawing | the web's 2D model ported (v1-complete); anatomy never uses family hues [WI 2.4] |

**Design acceptance list ("It is very important that it is NOT minimalist, and that it is robust." [Brief]).** The
design agent's review signs off against this list, and QA checks it on screenshots:
1. Every top-level screen (Home, Watch, Learn, Practice) and every secondary destination opens with a title and a
   one-sentence lede (A-16).
2. Every empty state named in this document has its illustration from the table above, a title, a one-sentence
   explanation and at least one action.
3. Home in its populated state shows every block of F-03 in that order (nine in v1-core, ten with Insights).
4. Every short shows, at once, the clip line, the spotlight with its practice badge, marked captions and the
   action rail (F-07).
5. Every state named in a States table of this document has a designed screen or component (no default platform
   error or blank screen).

**States.** Assets are static: no loading, offline or error states; the poster fallback and the generated
audio-only visual cover a missing poster or video.

**Acceptance criteria**
- **F-25-AC1** Given the release build, When the launcher, share sheet, notification shade and every empty state are
  captured on the API 36 emulator and the test phone in light and dark, Then each asset of the table is present, the
  launcher icon has background, foreground and monochrome layers, the notification icon is a single-color silhouette,
  and every illustration and icon meets the contrast of A-10 against its surface.
- **F-25-AC2** Given the screenshot set of every screen and state, When the design acceptance list is checked, Then
  all five items hold.

**Priority:** v1-core (the mouth drawing: v1-complete).

---------------------------------------------------------------------------------------------------------------

## 7. Connectivity, pairing and sync model

### 7.1 Facts the model is built on

- The server binds to 127.0.0.1 by default (`phonotrainer/cli.py:247`), has no authentication, no identity, no
  discovery and no LAN mode [BE 15, 16; AD 4.4]. `GET /api/health` returns the absolute workspace path
  (`phonotrainer/server.py:245`) [AD 4.3].
- A desktop firewall may block mDNS (UDP 5353); the development computer's does, while unprivileged ports are open
  on its Wi-Fi [AD 1.2]. Turning mDNS on is a manual host change outside the repository: the product never
  requires it.
- The computer's LAN address can change: it is discovered at runtime, stored only on the phone, never committed
  [AD 4.7, Brief].
- Reading the Wi-Fi network name needs location permission; the app does not do it. "On the computer's Wi-Fi" is
  defined by reachability of the paired computer, which is the brief's own condition: the phone "recognizes that
  the server is up" [Brief].
- The app targets API 36 (D-18). On Android 16 local-network access is implicit; on Android 17 an app that
  targets API 36 or lower keeps implicit access and must not declare `ACCESS_LOCAL_NETWORK` [TC 6]. So no
  local-network permission flow exists in v1.
- Job ids are the only unique key; `created` / `finished` have second resolution and collide [AD 2.1].
- Artifacts support HTTP Range (resume works); conditional GET and compression do not [BE 2].
- Creating a job twice creates two jobs; there is no idempotency [BE 4, 16.7].
- On a computer restart every job that was waiting in line or analyzing becomes an error ("Interrupted: the
  server stopped during the analysis.", `phonotrainer/jobs.py:250-252`); nothing resumes it.
- The job channel (`/ws/jobs`) has no application-level ping; WebSocket protocol pings are answered by the
  server's WebSocket stack [BE 7].

### 7.2 Exposure on the home network (operator opt-in)

- Default unchanged: `phonotrainer ui` listens on loopback only and the browser client works exactly as today.
- **Phone access** is an explicit operator choice (S-01). Once turned on it stays on across restarts until turned
  off (default of Q-5), **on the networks the operator approved** (D-42): turning it on approves the networks the
  computer is on at that moment (the home Wi-Fi), and on any other network (a café, an office, a new router) phone
  access is **suspended** until the operator approves that network with `phonotrainer phone on`; while suspended the
  computer refuses every phone request and does not answer discovery. Every start with phone access on prints that
  it is on (or suspended, and how to approve the network) and how to pair. These are terminal messages for the
  operator; the phone sees a suspended computer as one whose phone access is off (7.5).
- The computer's display name, which phones see in discovery and on Connect, defaults to "PhonoTrainer computer",
  never the computer's host name (which often carries the owner's name); the operator can rename it from the
  terminal.
- With phone access on, every non-loopback request (API, artifacts, job channel) needs a valid device credential,
  and the dangerous routes are unavailable to LAN clients (S-05, S-06, 11.5).
- Nothing in the app or the repository encodes an address, a host name or a credential. The fixed discovery port
  and the default HTTP port are product constants, the same for every installation, and may be committed.

### 7.3 Discovery (works with a firewall that blocks mDNS)

1. **Primary**: PhonoTrainer's own discovery on one fixed UDP port in the unprivileged range (S-03), active only
   while phone access is on. **The phone broadcasts one small probe on the Wi-Fi; each computer with phone access
   on answers by unicast to the sender.** The phone never listens for broadcast or multicast traffic, so it needs
   no multicast lock and no permission beyond network access. The answer carries only server id, display name,
   HTTP port and API version: never a credential, path or user name.
2. **Fallback**: manual "address and port" entry (Connect, Settings > Your computer "Enter the new address"),
   with the values the computer prints.
3. Not used: mDNS (blocked by some desktop firewalls), a sweep of the subnet (looks like a port scan, slow) [F].
4. **Discovery can fail** where routers isolate devices (guest networks, client isolation), where the network
   filters broadcast, or while a VPN on the phone routes traffic away: the "Why?" checklist names each case, and
   manual entry always remains.
5. **Re-discovery**: the phone stores the last working address for the paired server id. On a network change or
   after failed probes it tries the last address first, then discovery; it accepts a new address only when the
   address **proves it is the paired computer** (7.4: an answer to a fresh challenge that only the paired computer
   can compute) and accepts this phone's signed requests (D-37). The change appears only in Connection details ("Your
   computer's address changed"). If discovery cannot find it, the status offers "Enter the new address"; a typed
   address that proves it is the paired computer is accepted without pairing again.
6. No discovery runs in the background (battery, NFR-04).

### 7.4 Pairing and trust

- The operator asks for a **6-digit pairing code** in a terminal on the computer (S-04). The code is single use,
  valid 5 minutes, and invalidated after 5 wrong attempts; pairing calls are rate-limited. There is no browser
  page for pairing (D-35).
- The phone sends the code and a device name it shows to the learner (the phone's model, editable) and receives a
  **per-device credential**, stored only on the phone in protected, non-backed-up storage (11.1). The credential
  travels once, in the pairing answer, and never again (D-38): every later call, artifact download and job-channel
  connection is **signed** with it, so a signed request proves the phone without revealing the secret, and a
  recorded request cannot be replayed.
- **Identity, not address**: the phone trusts the computer's persistent random server id, which belongs to the
  machine, not to a workspace (S-02, D-34). The server id is public (every discovery answer carries it), so it is
  never proof on its own (D-37). Before sending anything to an address, the phone checks that the address **proves
  it is the paired computer**: it answers a fresh random challenge from the phone with a proof that only a holder of
  this phone's credential can compute. A missing or wrong proof, or a different server id, yields "A different
  computer answered at this address.": the phone sends it no request, file, recording or signed call, and never
  deletes anything because of its listing.
- From the terminal, the operator lists paired phones (name, paired date, last seen) and revokes one or all
  (S-04). A revoked or unknown credential gets a stable error code: the phone shows "Connect again", **pauses**
  the device queue (requests are kept, never failed or discarded) and stops background calls until paired again.
- Re-pairing with the same computer (same server id) keeps everything. **One computer at a time** (D-29): to pair
  another computer, the learner first uses "Forget this computer" (7.9). Clips keep a tag of the computer and
  library they came from, so nothing is lost and nothing is mixed up.
- Transport is plain HTTP on the home Wi-Fi in v1 (the server has no TLS [BE 15.3]): an accepted, documented risk
  (11.6, Q-1).

### 7.5 What "online" means, and every connectivity state

A **probe** is a call to the computer's health endpoint: first the identity proof of 7.4, then authenticated (signed),
returning server id, library id, API version, minimum app version, capabilities and queue length (S-02). Its timeout is
3 s, or 10 s while the computer was last known to be analyzing (its CPU is saturated then, 2.3); the health
endpoint answers without waiting on the analysis worker (S-02).

The computer is **online** ("Connected") when all of these hold:
1. the phone has a Wi-Fi or Ethernet network (traffic to the computer goes over that network even if it has no
   internet access);
2. the latest probe to the stored address succeeded within the current probe interval;
3. the address proved it is the paired computer (the paired server id with a valid proof, 7.4);
4. the computer accepted this phone's signed request;
5. the computer's API version is one this app supports, and this app is at least the computer's minimum.

An open job channel counts as continuous proof only while its WebSocket pings are answered; a missing answer is
detected within 15 s, ends the proof, and triggers a probe. **No flicker, no false claim**: when a probe fails
while the status is "Connected", the status changes at once to "Checking your computer…" (it claims nothing) and
a re-probe runs 5 s later; a second failure gives "Your computer isn't reachable", a success restores
"Connected". This bounds wrong claims as SC-10 states.

**Probe schedule** (battery, NFR-04): on app start and resume; on every network change to Wi-Fi or Ethernet; every
30 s while the app is in the foreground and there is pending work (waiting requests, requests on the computer,
wanted downloads), every 2 min otherwise; before each queued send; and in the background through the system
scheduler, at most every 15 minutes, only on an unmetered network with battery not low, and only as often as
Android and One UI allow (background restrictions can defer it for hours; no copy promises a time). No probes on
mobile data or with no network: those states are reported without trying.

| State | Condition | Status (icon + words) | Device queue | Downloads |
|---|---|---|---|---|
| Not connected | never paired, or forgotten | "Not connected to a computer" + "Connect" | requests wait | none |
| Offline | no network | "Offline · requests wait on this phone" | wait | paused |
| Not on Wi-Fi | mobile data only | "Not on Wi-Fi · your computer is reachable only at home" | wait | paused |
| Looking | probing after a network change (≤ 10 s) | "Looking for your computer…" | wait | paused |
| Checking | one probe failed after "Connected"; re-probe in 5 s | "Checking your computer…" | wait | paused (resume on success) |
| Connected | definition above | "Connected to {name}" (+ "· analyzing 1 · 2 in line" when busy) | sends | run |
| Syncing | connected and transferring | "Syncing · bringing 3 clips · 42 %" | sends | run |
| Not reachable | Wi-Fi, two probes fail | "Your computer isn't reachable · last seen {time}" + "Why?" (+ "Enter the new address" after a network change) | wait; retry on schedule | paused; resume later |
| Different computer | server id mismatch, or no valid proof of identity (7.4) | "A different computer answered" + "Details" | paused | paused |
| Connect again | credential rejected | "Your computer no longer recognizes this phone" + "Connect again" | paused (kept) | paused |
| Computer too old | API older than the app needs | "Update PhonoTrainer on your computer" | paused | paused |
| App too old | computer needs a newer app | "Update this app" | paused | paused |
| Computer low on space | computer reports low disk or upload limit | "Your computer is low on space" | uploads paused; links still sent | run |

"Why?" opens Connection details with a plain checklist ("Is your computer awake?", "Is PhonoTrainer running with
phone access on? If your computer joined a new network, run phonotrainer phone on there.", "Is this phone on the same
Wi-Fi?", "Is a VPN on? Turn it off at home: it can hide your computer.", "Some routers keep devices apart (guest
networks).") and the last probe result in words ("No answer after 3 s"; for a computer whose phone access is off or
suspended on this network, "Your computer answered, but phone access is off there or not approved for this
network."). TalkBack announces only transitions ("Connected", "Offline", "Clip ready"), never percent ticks or
the "Checking" state.

### 7.6 What syncs in each direction

| Direction | What | When | v1 |
|---|---|---|---|
| Computer → phone | Catalogue: the computer's complete lean listing: every job with status, title, duration, counts, stage, error, revision, submitting device, library id, artifact sizes, checksums and rendition status (S-11) | every successful probe cycle (a few KB) | v1-core |
| Computer → phone | Study pack per finished clip: clip data + audio of the mix and of the dialogue track when present (compact and compressed when the computer provides them, S-12; otherwise `analysis.json` and the WAVs) | automatically, for every clip not removed by the learner, within the budget | v1-core |
| Computer → phone | Poster per video clip | with the study pack, when the computer provides one (otherwise the poster fallback) | v1-complete |
| Computer → phone | Video layer: the original when this phone can play it; otherwise a phone video the computer makes on request (S-12) | automatically if "Download videos automatically" is on and the budget allows; otherwise on request | v1-core (originals); v1-complete (phone video) |
| Computer → phone | Reference vocabulary (`/api/reference`) | when the API version changes; a snapshot ships in the app | v1-core |
| Computer → phone | Live progress of jobs | while the app is in the foreground and connected (job channel); the listing otherwise | v1-core |
| Phone → computer | Requests (links, files) from the device queue | when connected, in order (7.7) | v1-core |
| Phone → computer | Cancels of this phone's requests; removal of this phone's failed or cancelled jobs; re-runs (S-22) | when connected (they can wait on the phone) | v1-core |
| Phone → computer | Record-yourself takes | live only; never queued; deleted by the computer after measuring | v1-complete |
| Phone → computer | Pairing | at pairing | v1-core |
| Phone ↔ computer | Practice stats, saved items, seen shorts, settings | never in v1 (phone only) | Later (Q-3) |
| Phone → computer | Deleting finished analyses | not allowed in v1 | Later (Q-2) |
| Not synced | `report.html`, `review.json`, intermediate files, logs, the corpus database | never | — |

The brief's rule holds literally for study packs: every finished analysis is downloaded unless the learner removed
it from the phone or the budget is full, and in both cases the Library says so. The video layer is skipped when
"Download videos automatically" is off or the budget is full, and the original file is never downloaded when the
phone cannot play it (a phone video replaces it); each case is visible on the clip.

### 7.7 The device queue

**What can be queued**: (1) a link to analyze (options: audio only); (2) a local video or audio file (options:
audio only, separate the dialogue); (3) a cancel of this phone's accepted request; (4) the removal of this phone's
failed or cancelled job; (5) a re-run of this phone's failed, cancelled or interrupted job (S-22). Not queueable:
deleting finished analyses, record-yourself takes, pairing.

**Creation, always local first.** Every request is written durably on the phone with a unique client-generated id
(also its idempotency key) before any network attempt. If that write itself fails (the phone is out of space),
the sheet says "PhonoTrainer couldn't save this request: your phone is out of space." and nothing is pretended.

**Files: the copy.** A shared item's read grant is temporary [AD 2.4], so a file is copied into app-private storage
before it can wait. The request is recorded first in the state "Preparing the file"; then:
- the share sheet stays open showing "Preparing the file… 40 %" and says "Added" only when the copy is complete
  (with "Audio only", the phone extracts the audio track without re-encoding and keeps only that; if extraction
  is impossible it keeps the whole file and the request says "Sent the whole file: the audio couldn't be
  separated on the phone.");
- **Home** during the copy: the copy continues while the app's task lives;
- **Back** during the copy: "Stop adding this video?" with "Keep adding" (default) and "Stop" (the record and the
  partial copy are deleted);
- **the process dies or the phone restarts mid-copy**: at the next start, a "Preparing" request with an
  incomplete copy is checked. A file picked in the app (lasting read permission, F-19) restarts its copy by
  itself. A shared file cannot be read again: the partial copy is deleted and the request shows "Couldn't keep
  the file: the copy didn't finish. Share it again." with "Remove";
- **no space mid-copy**: the partial copy is deleted and the request shows "Not enough space on this phone to keep
  this video until your computer is reachable. Free {N} MB or choose Audio only." with "Try audio only" (videos)
  and "Remove". "Try audio only" starts a new copy of the audio track only, so it is offered only while the file can
  still be read (D-49): in the share sheet while it is open, and later in Requests for a file picked in the app
  (lasting read permission, F-19). A shared file whose sheet has closed can't be read again: its row shows "Not
  enough space on this phone to keep this video. Free {N} MB, then share it again and choose Audio only." with
  "Remove";
- **the file can't be read** (permission revoked, the source app removed it, a cloud file not on the phone while
  offline): "PhonoTrainer couldn't read this file. Open it once in the app that has it, then share it again."
  with "Remove";
- **the kept copy disappears later** (deleted outside the app): the queue check at start shows "The copy kept on
  this phone is missing. Share the file again to analyze it." with "Remove"; nothing is sent.

**Request record** (product fields): id, kind, source (link as sent, normalized link for duplicates, or file name,
size, duration), options, target server id, created time, state, attempts, last error (code + sentence), job id
once accepted, local file copy and its completeness, link to the original request for re-runs.

**Duplicates.** Before creating, a YouTube or X link is normalized and compared with the queue and the catalogue
(`source_url`): `youtu.be`, `youtube.com/watch`, `youtube.com/shorts`, `m.youtube.com` forms of one video are the
same video; `x.com`, `twitter.com`, `mobile.twitter.com` forms of one post are the same post; the parameters
`si`, `feature`, `utm_*` (tracking), `s` (an X share token) and `t` (a YouTube start time: the analysis always
covers the whole video) are removed. Match: "You already added this link." + "Open it" / "Analyze again anyway".
Files are compared by size and a content fingerprint against waiting files.

**Ordering.** Cancels, removals and re-runs are sent first. Links (a few hundred bytes) are sent as soon as the
computer is connected, even while a large upload is in progress. Files upload one at a time in creation order.
The computer analyzes in arrival order, so a link added after a large file may start first; the request detail
says so when it happens.

**Sending and idempotency.** Each attempt carries the idempotency key (S-09). A request is "Sent" only when the
computer answered with a job id, which the phone stores with the request. A send whose outcome is unknown
(timeout after the body left) is retried with the same key, so it can never create a second analysis. After app
kill, reboot or network loss the request is retried with the same key.

**Uploads.** Uploads run only when connected (therefore on the home Wi-Fi) and pause (never fail) when the
computer disappears. Before sending, the phone checks the computer's declared limits (maximum size, free space)
and refuses early ("Too large for your computer's limit (2 GB). Choose Audio only."). Progress is shown in bytes
("120 MB of 260 MB").
- **v1-core**: one upload per attempt with a known Content-Length and the idempotency key; an interrupted upload
  restarts from zero. The local copy is kept until the computer accepts the file, so nothing is lost.
- **v1-complete**: resumable uploads (S-10): the phone declares size and checksum, sends in parts, and after an
  interruption asks the computer how much it has and continues from there.
- An upload the learner starts while the app is in the foreground may continue as a user-visible transfer with a
  notification (F-23; the architect chooses a data-sync foreground service or a user-initiated data transfer
  job). An upload started from the background (for example after the phone joins the home Wi-Fi with the app
  closed) runs as ordinary deferrable work without a notification; Android forbids starting a foreground service
  from the background, and the resumable or restartable design makes that safe.
- **Large files wait for the app** (v1-core, D-41): Android stops background work after a few minutes, and a
  v1-core upload that is cut short restarts from zero, so a file starts in the background only when this phone's
  measured upload speed says it can finish within one background run. A larger file stays "Waiting on this phone"
  (never failed) with the line "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent while
  the app is open.", and is sent the next time the app is open and connected, as a user-visible transfer. Links are
  not held up by it (Ordering, above). With resumable uploads (v1-complete) each background run continues where
  the last one stopped, and the rule and its line no longer apply.

**Retries**
| Failure | Class | Behaviour |
|---|---|---|
| No answer, timeout, connection reset, 5xx, computer busy | transient | retry immediately on the next transition to connected, then after 30 s, 1, 2, 5 min, then every 15 min while connected; no maximum while the request exists; after 3 failed attempts the row reads "Couldn't send yet: will try again" with the last reason |
| Credential rejected (401/403 with the stable code) | blocking | pause the whole queue; state "Connect again" |
| Computer or app too old | blocking | pause the queue; state as 7.5 |
| Refused at sending (4xx with a reason: unsupported file type, file too large, a link to a private or local address, S-07) | permanent for this request | "Needs your attention" with the reason; no automatic retry |
| Computer low on space | blocking for uploads | pause uploads; links continue |
| Job failed on the computer (download refused, private video, playlist, extraction error) | job failure | "Needs your attention" with reason and actions (8.6); no automatic retry (repeated 403s would only occupy the single worker [BE 9]) |
| Job interrupted by a computer restart (`interrupted`, S-15), whether it was analyzing or waiting in line | transient job failure | one automatic re-run (S-22) for each own interrupted job, in the original acceptance order; the re-run replaces the failed job; a second interruption → "Needs your attention" |

**Cancellation.** Preparing → the copy stops and the record is deleted. Waiting → removed at once and its file
copy deleted. Sending → upload stopped; "Stop sending" returns it to waiting, **held**: it is not sent again,
even while connected, until the learner taps "Send now" (D-46; reason line "Stopped by you: it waits here until you
tap Send now."); "Remove" discards it (the computer
drops the partial upload, S-10). Accepted → "Cancel" when connected, or "Cancel waiting for your computer"
(visible, undoable) when not; sent before anything else at the next connection. If the job finished before the
cancel arrives, the clip is kept and the learner is told (F-20).

**Expiry.** Requests never expire silently [Brief: "queued ON THE DEVICE until the connection happens"]. A waiting
request shows its age ("Added 15 days ago"). Copies of waiting files count in Storage.

**Limits.** The only phone-side limit is space: a copy is refused, with the space needed, when it would take the
phone below the free-space floor (7.10).

**State machine (device side)**, every transition stored before it is shown:
`Preparing the file (files only) → Waiting → Sending → Sent → In line → Analyzing → Ready on your computer →
Getting it on this phone → On this phone`, with exits `→ Couldn't keep the file` (from Preparing), `→ Copy
missing` (from Waiting), `→ Needs your attention` (refused, failed, interrupted after the automatic re-run), `→
Cancel waiting → Cancelled`, `→ Paused` (queue-wide: connect again, update needed), and `→ Removed` from any
non-final state. A re-run returns a request from "Needs your attention" or "Cancelled" to "Sent".

**Survival.** Requests, their states and file copies survive app close, process death, reboot, low-memory kills and
app updates (SC-04, NFR-07).

### 7.8 Downloads: verification, order and revisions

- Every artifact downloads to a temporary file, resumes with Range after interruptions, and is verified against the
  size and checksum in the listing (S-11) before it replaces anything. Two failed verifications → "Download
  failed: the file was damaged on the way." + "Try again"; the previous good version stays in use.
- A clip is **"On this phone"** only when its study pack is complete and verified: clip data + the audio of the
  learner's chosen track. The other track and the video follow independently ("Video downloading 40 %" while the
  clip is already studiable).
- **Renditions never hold a clip back.** The listing reports each artifact's own status (ready, being prepared,
  failed, unavailable). The study pack uses the compact clip data and compressed audio when ready and the
  existing `analysis.json` and WAVs otherwise; a poster that is missing uses the poster fallback; a video that is
  being prepared, failed or unavailable never delays the study pack.
- **Which video**: the phone downloads the original when it can decode its codec at its size (checked against
  the phone's own decoders) and it is 1080p or smaller; otherwise it asks the computer for a phone video (S-12)
  and downloads that when ready. If the original is missing on the computer, the clip reads "Video not available
  from your computer" and stays an audio clip on the phone.
- Order: study packs first (clips requested from this phone first, then wanted clips, then newest first), then
  videos (kept and recently watched clips first, then newest).
- Downloads run only when connected (home Wi-Fi).
- **Revisions**: when an artifact of a clip changes (for example a rendition becomes ready), its revision changes;
  the phone downloads the changed artifacts in the background and switches atomically; position, saved items and
  history stay (anchored to job + segment + word; if phrases changed, the position falls back to the nearest
  time). A re-analysis on the computer creates a new id today [BE 16.14]: it appears as a new clip.
- A clip is "Getting ready on your computer" only while its clip data is not available yet.

### 7.9 Deletion semantics

| Action | Where | Effect on the phone | Effect on the computer |
|---|---|---|---|
| "Remove from this phone" | phone | deletes study pack and video; keeps the catalogue row, history and stats; marks "Not on this phone"; never downloaded again automatically until "Download to this phone" | none |
| "Remove the video" | phone | deletes the video layer; the clip stays studiable | none |
| Clip deleted on the computer (web or CLI) | a `deleted` event on the job channel from the paired server id, or the clip missing from two consecutive complete listings of the same server id and library id | removes the clip, its files, its saved items and its positions (after the learner leaves it if open); per-change practice stats are kept; one notice "1 clip deleted on your computer was removed from this phone." If more than half of the clips would disappear at once, the learner is asked first: "Your computer no longer lists 9 of 10 clips. Remove them from this phone?" | n/a |
| The computer switches to another library (workspace) | the library id in the listing changed | nothing is deleted; clips of the previous library stay, tagged "From another library", and are never touched by the new library's listings; one notice "Your computer is using a different library. Clips from the other one stay on this phone until you remove them." | n/a |
| A listing from a different server id, or from an address without a valid proof of identity | identity check | nothing is deleted and nothing is sent ("A different computer answered") | n/a |
| "Remove" a failed or cancelled request | phone | removes the request and its file copy | deletes this phone's failed or cancelled job when connected (S-06) |
| "Hide on this phone" (a failed job started from the browser client) | phone | hides it from "Started on your computer" | none |
| Delete a finished analysis | — | not possible from the phone in v1: "Delete it on your computer." (Q-2) | — |
| "Forget this computer" | phone | removes credential and computer record; keeps clips (tagged "From a computer you forgot") and waiting requests (they go to the next computer paired); requests that computer had accepted stop being followed and are removed from the phone, after the confirmation in F-22 | revokes the credential if connected; otherwise the phone stays in the computer's list until revoked there |
| The computer revokes the phone | computer | "Connect again"; nothing deleted; queue paused | n/a |
| Uninstall | phone | everything local is deleted (no backup of media, queue or credential) | none |

### 7.10 What gets downloaded, renditions and the storage budget

| Part | Served today | Phone rendition (S-12, v1-complete) | Size per 4-min clip | Policy |
|---|---|---|---|---|
| Clip data | `analysis.json`, 2 MB pretty-printed, uncompressed | a pre-compressed compact file (`application/gzip`, no `Content-Encoding`, so Range, size and checksum all refer to the same bytes) | 2 MB today; 0.2–0.4 MB as a rendition | always, automatic |
| Audio, original mix | `audio.wav` 16 kHz PCM, about 8 MB | compressed speech audio on exactly the analysed timeline, with sample-accurate seeking (the codec is the architect's; Opus 24 kbit/s measured 10.4x smaller [BE 5]) | 8 MB today; about 0.8 MB as a rendition | always, automatic |
| Audio, dialogue | `audio_dialogue.wav`, about 8 MB | same | same | always when the clip has it |
| Poster | none | one frame image | < 0.1 MB | for video clips, when available; otherwise the poster fallback |
| Video | `/media`, the original: AV1 720p 10–15 MB today, one 4K portrait 65.6 MB | the original when this phone plays it (1080p or less, decodable); otherwise a phone video made on request: H.264 ≤ 720p (`libopenh264` is the H.264 encoder available on the development computer [BE 14]), orientation kept, starting at the analysed timeline's zero | original size, or about 17–25 MB as a phone video | automatic within the budget when "Download videos automatically" is on |
| Report | `/report` | not used | — | — |

- **Budget**: default **2 GB** for downloaded clips (study packs + videos). With renditions a 4-minute clip with
  its 720p AV1 original takes about 14 MB (about 140 such clips fit); with the WAV fallback about 30 MB (about 65
  fit). Choices 1 GB, 2 GB, 5 GB, "No limit except free space". Waiting files count separately (7.7).
- **Free-space floor**: the app always leaves at least 1 GB or 10 % of the phone's storage free, whichever is
  smaller; below that, downloads pause with "Your phone is almost full. Downloads are paused." and new file copies
  are refused (7.7).
- **Cleanup when the budget is exceeded**: remove videos of the least recently watched clips first, never a kept
  clip, never a clip in today's set or opened in the last 24 hours; study packs are never removed automatically.
  If study packs alone exceed the budget by 10 %, the learner is asked: "Storage budget reached. Remove clips or
  raise the budget."
- App-private storage only; nothing in the shared gallery; media excluded from backups.

### 7.11 User-visible sync status (summary)

- Home status card (always present, 7.5 states, request counts, transfer progress, "Sync now").
- Library "Last synced {time}"; every clip row's availability: "On this phone", "Video on this phone", "Bringing
  to this phone 40 %", "Video not on this phone", "Video getting ready on your computer", "Video not available
  from your computer", "Not on this phone", "Getting ready on your computer", "Download paused: {reason}".
- Requests screen for phone → computer work; Connection details for diagnostics (including a background
  restriction); Storage for sizes.
- One ongoing notification only for long transfers started from the foreground (F-23).
- Wording always names a place: "on this phone", "on your computer", "waiting on this phone"; never "cloud";
  never "server" outside diagnostics.

### 7.12 Queue torture test (acceptance for 7.5 to 7.10)

QA runs these on the API 36 emulator with the fake computer (unreachable, slow, 4xx, 5xx, lost responses, partial
downloads), then once with the real computer **using local files only** (no link is ever sent to the real
computer by QA, 8.7). Steps that reboot or cut the network are **[emulator]** only.
- **QT-1** Given the computer unreachable, When the learner shares 3 links and 1 video, Then 4 requests are "Waiting
  on this phone" in creation order, and after force-stopping the app and rebooting the device [emulator] they are
  still there.
- **QT-2** Given those 4 requests (fake computer), When the computer becomes reachable, Then the 3 links are sent
  first in order, the video uploads, and the computer has exactly 4 new jobs.
- **QT-3** Given a link whose creation succeeds on the computer but whose response is lost, When the phone retries,
  Then the computer still has exactly 1 job for it and the phone shows that job.
- **QT-4** Given an upload at 60 %, When Wi-Fi drops for 2 minutes and returns [emulator], Then in v1-complete the
  upload resumes from at least 60 % and completes with a verified checksum; in v1-core it restarts and completes, and
  the computer has exactly 1 job for it.
- **QT-5** Given a file request analyzing, When the computer process restarts, Then the request is re-run once
  automatically and, if it is interrupted again, shows "Needs your attention".
- **QT-6** Given a waiting request, When the learner removes it while offline, Then it is never sent later.
- **QT-7** Given a sent request and the computer unreachable, When the learner cancels it, Then "Cancel waiting for
  your computer" shows, and at the next connection the cancel reaches the computer before any new request.
- **QT-8** Given a revoked credential, When the computer answers with the credential-rejected code, Then the queue
  pauses with "Connect again" and no request is lost or marked failed.
- **QT-9** Given a study-pack download interrupted at 70 %, When it resumes, Then it continues from at least 70 % and
  the clip becomes "On this phone" only after verification.
- **QT-10** Given a clip deleted on the computer while it is open in the feed, When sync runs, Then the open short
  keeps playing and the clip disappears after the learner leaves it, with one notice.
- **QT-11** Given a listing from a different server id at the stored address, When sync runs, Then nothing is deleted
  and nothing is sent.
- **QT-12** Given two own file requests waiting in line behind an analyzing one, When the computer process restarts,
  Then all three are re-run once automatically, in their original order, and no duplicate job exists.
- **QT-13** Given a shared 600 MB video being copied, When the app process is killed at 50 % [emulator], Then at the
  next start the request shows "Couldn't keep the file: the copy didn't finish. Share it again.", the partial copy is
  gone, and Storage does not count it.
- **QT-14** Given the listing's library id changes, When sync runs, Then no clip is deleted and the library notice
  shows.

---------------------------------------------------------------------------------------------------------------

## 8. Share target and "process from the phone"

The owner: "from YouTube or Twitter/X, when tapping 'Share' on a link, in the share sheet where apps like WhatsApp
appear, THIS app must appear too. If online (server reachable) it processes; if not, it queues." and "it processes
the videos the user wants to process from the phone" [Brief]. Decision D-05: "process from the phone" means
**shared links and local files**. For links, "it processes" means the request reaches the computer and the
analysis starts; whether the computer's downloader then succeeds is outside the phone's control, so v1 hardens it
(S-23), proves it on the owner's own shares (8.7, S-20), and makes every failure recoverable (8.6).

### 8.1 Entry points

| Entry | What arrives | v1 |
|---|---|---|
| System share sheet from YouTube, X, browsers, any app sharing text | `ACTION_SEND`, `text/plain` (`EXTRA_TEXT`, sometimes a subject) | v1-core |
| System share sheet from Gallery, Files, recorders | `ACTION_SEND` with one `video/*` or `audio/*` item (temporary read grant) | v1-core |
| Gallery multi-select | `ACTION_SEND_MULTIPLE`, up to 10 items, one request each | v1-core |
| In-app "Add a clip" → "Paste a link" / "Choose a file" (F-19) | text / picked items (lasting read permission) | v1-core |
| Direct Share targets, launcher shortcut "Add a link", home-screen widget | — | Later |

The app appears in the share sheet as **"PhonoTrainer"** with its icon, for shared text and shared video or audio
only. Some apps show the system share sheet only behind their own "More" or "Share via" entry; reaching
PhonoTrainer through that entry satisfies the requirement.

### 8.2 Link handling

1. Take the **first `http://` or `https://` URL** in the shared text (YouTube and X share "title + link"; the
   computer rejects text that does not start with http, `phonotrainer/download.py:24`). No URL → "There's no link
   in what you shared." + "Close"; nothing is created.
2. Label the site: "YouTube" (`youtube.com/watch`, `youtu.be`, `m.youtube.com`), "YouTube Shorts"
   (`youtube.com/shorts`), "X" (`x.com`, `twitter.com`, `mobile.twitter.com`, `t.co`). Any other http(s) link is
   accepted as "Other website" with "PhonoTrainer is made for YouTube and X links. Other websites may not
   download." (the computer's downloader accepts many sites [BE 9]).
3. For YouTube and X links, the parameters listed in 7.7 are removed before sending (privacy and
   de-duplication); other links are sent as shared, trimmed, because their parameters may matter.
4. Non-http(s) schemes are refused on the phone; links to private or local addresses are refused by the computer
   at sending (S-07). Playlists are not refused at sending: a video link that also names a playlist downloads
   the video (the downloader ignores the playlist, `phonotrainer/download.py:90-91`), and a pure playlist link
   fails on the computer with the `playlist` code (8.6).
5. Provisional title: the shared subject or title text if present, else "{site} link"; the real title appears once
   the computer has downloaded the media (no metadata before processing [BE 16.8]; a probe is Later).
6. Duplicates as in 7.7.

### 8.3 Local files

- **Copied into the app** before the request can wait, with the progress, interruption and failure rules of 7.7
  ("Preparing the file… 40 %"; "Added" only when the copy is complete). The copy is deleted as soon as the
  computer has accepted the upload: the computer keeps the file, and "Try again" re-runs from it (S-22).
- **Accepted types**: the computer's media suffixes `.webm .mp4 .mkv .mov .avi .m4v .mp3 .wav .m4a .flac .ogg
  .opus .aac` (`phonotrainer/jobs.py:43`); the phone derives the extension from the type when the name lacks one.
  Anything else (e.g. AMR or 3GP voice notes): "This file type can't be analyzed. Supported: MP4, MOV, MKV, WebM,
  AVI, M4V, MP3, M4A, AAC, WAV, FLAC, OGG, Opus." and no request.
- **Size**: above the computer's declared maximum (or 2 GB when the phone has never been connected) → refused at
  share time ("This file is larger than your computer accepts ({limit})."). Above 500 MB: "Audio only" is
  preselected and the sheet says "Large file: sending takes a few minutes on Wi-Fi." Not enough free space for the
  copy (free-space floor, 7.10): refused with the space needed.
- **Audio only** for a local video: the phone extracts the audio track without re-encoding (fast, no quality
  loss); if that fails it keeps the whole file and says so (7.7).
- **Multi-select** with some items not accepted: the accepted ones are added and the sheet says which were not:
  "2 of 3 added. 1 file type isn't supported: {name}."
- Uploads happen only when connected, so never over mobile data.

### 8.4 The share confirmation sheet

Shown over the sharing app (the learner never loses YouTube or X); a cold start opens it directly, with no Welcome
and no Home. Content in order:
1. Title "Analyze with PhonoTrainer".
2. What was shared: site icon + word and the shortened link (full on demand); or file name, kind ("Video" /
   "Audio"), size and duration; or "3 videos · 412 MB".
3. **Connection line**: the sheet starts a fresh probe and waits for it at most 2 s ("Checking your computer…"
   meanwhile); after 2 s it shows the last known state and updates the line if the answer arrives later:
   - Connected: "Your computer is connected: the analysis starts now." (+ "It goes in line after 2 other clips."
     when busy).
   - Not reachable / offline / not on Wi-Fi: "Your computer isn't reachable right now. This waits on your phone and
     is sent when you're back on your home Wi-Fi."
   - Not paired: "This phone isn't connected to your computer yet. It waits on your phone until you connect."
   - Needs attention (connect again, update needed): "Your computer needs attention ({reason}). This waits on your
     phone."
4. Options: links "Audio only" ("Smaller and faster. There is no video to watch with the shorts."); local videos
   "Audio only" (preselected above 500 MB). Everything else uses defaults (D-08).
5. Guidance: "Short, clear dialogue (30–60 seconds) teaches the most; analysis takes about as long as the clip."
   (web lede plus the web's timing note).
6. Links: "Downloading may breach the website's terms of service; that responsibility is yours." (web,
   generalised); and, for a site that failed the release gate (8.7), its site note.
7. Buttons: primary **"Analyze"** (connected) or **"Add to queue"** (otherwise); secondary "Cancel".
8. After the tap: the request is stored first (7.7); for files the copy runs with its progress. Then the sheet
   shows "Added to your queue." (or, for a link when connected, "Sending to your computer…", replaced by "Sent to
   your computer." if the computer confirms while the sheet is open), with "Open PhonoTrainer" and "See
   requests". It closes by itself 2 s after the request is stored (and, for files, after the copy completes),
   showing whichever message is current; Back closes it at once (for a file still copying, Back asks first, 7.7).
   With TalkBack on it does not close by itself, nor while the notification question of item 9 is open (D-51); the
   2 s count starts again once it is answered. The final outcome is always visible in Requests, on Home and, if
   allowed, in a notification.
9. The first time a request is created, this is when notifications are asked for (F-23), after the request is
   safely stored.

#### 8.4.1 States of the share flow

| State | Behaviour |
|---|---|
| First run (never opened, not paired) | the sheet opens without the Welcome; "Not paired" connection line; the request waits |
| Loading | "Checking your computer…" for at most 2 s; files: "Preparing the file… 40 %" |
| Offline / Unreachable | connection line as above; "Add to queue" |
| Empty | shared text without a link: "There's no link in what you shared." + "Close" |
| Error: unsupported file type | the type message of 8.3; nothing created |
| Error: too large / no space / unreadable / out of space for the record | the messages of 8.3 and 7.7; nothing pretended |
| Partial: multi-select with some items refused | "2 of 3 added. 1 file type isn't supported: {name}." |
| Duplicate | "You already added this link." + "Open it" / "Analyze again anyway" |
| Interrupted copy (Back, Home, process death) | the rules of 7.7 |

### 8.5 Online or queued: one model

Both paths create the same request (7.7). "Analyze" only means the phone tries to send it now, in the background;
the sheet never waits for an upload. If the immediate send fails, the request stays "Waiting on this phone" and
retries: no data loss, no error dialog over the sharing app. The outcome shows on Home, in Requests and, if allowed,
in a notification.

### 8.6 Failure is a first-class, recoverable state

The computer's link download path has 0 recorded successes on the development computer (3 attempts, all HTTP
403; X never tried) [AD 2.1]. Failures are therefore designed states. Messages come from stable error codes
(S-13); the cleaned computer text is always available under "Show details". "Try again" and "Try audio only" ask
the computer to re-run from what it stored (S-22).

| Failure (code, S-13) | When | Title / body | Actions |
|---|---|---|---|
| `refused_address` | at sending (S-07) | "This link can't be analyzed" / "It points to a private or local address." | Remove |
| `unsupported_type` / `too_large` (files) | at sending | "Your computer can't take this file" / "{type not supported, or larger than {limit}}" | Try audio only (videos too large) · Remove |
| `download_forbidden` (HTTP 403) | job | "The download failed" / "The website refused it. This sometimes passes after a while, or with audio only." | Try again · Try audio only · Open the link · Remove |
| `download_unavailable` (private, removed, age-restricted, login required) | job | "This video can't be downloaded" / "It is private, removed, or needs a login, which PhonoTrainer can't do." | Open the link · Remove |
| `unsupported_site` | job | "This website isn't supported" / "Your computer doesn't know how to download from this site. Try a YouTube or X link." | Open the link · Remove |
| `playlist` | job | "That's a playlist" / "Share a single video instead." | Open the link · Remove |
| `live_stream` (links from the phone, 8.7) | job, before any download | "Live streams can't be analyzed" / "This video is live or hasn't started yet. Once it has ended and is saved as a video, try again." | Try again · Open the link · Remove |
| `too_long` (links from the phone, 8.7) | job, before or during the download | "This video is too long for your computer" / "Your computer analyzes link videos up to {limit} long. Share a shorter video; the limit can be changed on your computer (see its README)." ({limit} from the computer's capabilities, e.g. "1 hour"; when unknown: "up to the length set on your computer"; a video refused for its size, above the computer's upload limit, shows the same lines and "Show details" names the reason) | Open the link · Show details · Remove |
| `network` | job | "Your computer couldn't reach the website." | Try again · Remove |
| `no_space` | job | "Your computer is low on space" / "{free space}" | Try audio only (links) · Try again (files, once space is freed) · Remove |
| `not_media` / `extract_failed` | job | "Your computer couldn't read this file." + cleaned detail | Remove |
| `tool_missing` | job | "Your computer can't process this yet" / "It is missing a tool (ffmpeg). See the README on your computer." | Try again · Remove |
| `interrupted` | job | after one automatic re-run: "Your computer stopped during the analysis" / "Start PhonoTrainer again, then try again." | Try again · Remove |
| `processing_failed` (any other) | job | "The analysis failed" / first line of the computer's message | Try again · Show details · Remove |
| `cancelled` | job | "Cancelled." | Try again · Remove |
| Upload interrupted (transport) | sending | none: stays "Waiting on this phone"; after 3 attempts "Couldn't send yet: will try again." | Send now · Remove |

Rules: a failed request never disappears by itself; it can be retried days later because the computer keeps the
link or the uploaded file (S-22; if the computer no longer has it, F-20 says so); "Try audio only" re-runs a link
with `audio_only` (the downloader's audio path is a different format selection and may succeed where video fails
[BE 9]); a failure notifies once, never repeatedly; "Open the link" hands the link to the system (the YouTube or X
app, or the browser) only on tap. Failures of jobs started from the browser client are never shown here (F-20).

### 8.7 Release gate for the link path

- Before the gate, S-23 is in place: the downloader prefers H.264 at 720p or less, its JavaScript runtime is
  configured and documented, its version is recorded, and its errors map to the codes above.
- **Links from the phone are confined** (S-23, D-43): the computer downloads only through the downloader's
  site-specific support (no generic page scraping, so "Other website" links work only for sites the downloader
  knows); it refuses live and not-yet-started streams (`live_stream`) and videos longer than its link limit,
  **1 hour by default** and editable on the computer (`too_long`), before any media is downloaded, and checks its free
  space first (`no_space`). Links sent from the browser client are unchanged (S-21). The reference data set's
  longest clip is about 10 minutes and the guidance asks for 30–60 s of dialogue, so the default refuses none of
  the material the app is designed for, while a multi-hour video or a never-ending stream cannot occupy the single
  analysis worker for hours.
- During device QA, **the owner himself** shares one YouTube video link, one YouTube Shorts link and one X post
  with video from the test phone (Q-4). Each is followed end to end on the development computer (share →
  analysis → on the phone) and the outcome, including any 403 diagnosis, is recorded in the QA report as it is
  (S-20).
- **Only the owner's own manual share counts.** Agents never send a link to the real computer: no injected share
  intent (for example `adb shell am start -a android.intent.action.SEND` with a URL), no direct call to the link
  endpoint, no downloader run against a real site. Every automated link test uses the fake computer.
- If a site fails, v1 still ships the share feature (local files and the queue work regardless), the failure states
  above are verified on that real failure, and the confirmation sheet for that site carries a note decided from the
  result, e.g. "Downloads from X often fail right now. If it fails, try audio only."; the owner is told plainly.

### 8.8 Acceptance criteria

- **SH-AC1** Given an app that shares text through the system share sheet (YouTube, directly or through its "More"
  entry), When the learner looks at the list of apps, Then "PhonoTrainer" is present (verified on the test phone if
  the YouTube app is installed; always verified in an automated test that a generic `ACTION_SEND` `text/plain`
  chooser lists PhonoTrainer).
- **SH-AC2** Given a share intent with the text "Great scene https://youtu.be/abc?si=xyz", When PhonoTrainer is
  chosen, Then the sheet shows "YouTube" and the link, and the request stores `https://youtu.be/abc`.
- **SH-AC3** Given the fake computer connected, When "Analyze" is tapped, Then within 3 s the request reads "Sent to
  your computer." and the computer has 1 job carrying that idempotency key.
- **SH-AC4** Given the phone offline, When the learner taps "Add to queue", Then the sheet confirms, closes, the
  sharing app is visible again, and Home shows "1 waiting on this phone".
- **SH-AC5** Given that request waiting, When the phone joins the home Wi-Fi with the fake computer in phone access
  mode, Then exactly one job is created, even if the first response is lost, within one probe interval with the app
  open (and at the next background run the system allows, otherwise).
- **SH-AC6** Given a 600 MB MP4 shared while offline [emulator], When confirmed, Then "Audio only" was preselected,
  the (extracted or whole) file is in app storage before "Added" shows, the request survives a reboot, is uploaded
  when connected, and the copy is deleted after acceptance.
- **SH-AC7** Given shared text without a link, When PhonoTrainer is chosen, Then "There's no link in what you
  shared." shows and no request is created.
- **SH-AC8** Given the same link shared twice while the first is waiting or analyzing, When the second sheet opens,
  Then it offers "You already added this link." with "Open it" / "Analyze again anyway" instead of queueing silently.
- **SH-AC9** Given a job that ends with `download_forbidden`, When the learner opens it, Then "Try again", "Try audio
  only", "Open the link" and "Remove" are offered, and "Show details" contains no terminal escape codes.
- **SH-AC10** Given an AMR file shared, When the sheet opens, Then it explains that the type is unsupported, lists
  supported types and creates nothing.
- **SH-AC11** Given the app was not running, When a share arrives, Then the sheet opens without the Welcome or Home,
  and the learner is returned to the sharing app afterwards.
- **SH-AC12** Given the app is not paired, When a link is shared, Then the request is stored and Home shows "Not
  connected to a computer" with "1 waiting on this phone".
- **SH-AC13** Given a shared video being copied, When the learner presses Back, Then "Stop adding this video?"
  appears and "Keep adding" (the default) continues the copy.
- **SH-AC14** Given a multi-select of 2 MP4 files and 1 AMR file, When it is shared, Then 2 requests are created and
  the sheet says "2 of 3 added. 1 file type isn't supported".
- **SH-AC15** Given the phone without space for the copy, When a video is shared, Then the sheet states the space
  needed and offers "Try audio only" (7.7), and no request is left half-made.
- **SH-AC16** Given a pure playlist link (fake computer answering the `playlist` failure), When the job fails, Then
  "That's a playlist" shows with "Open the link" and "Remove".
- **SH-AC17** Given a link whose job fails with `live_stream` (fake computer), When the learner opens it, Then "Live
  streams can't be analyzed" shows with its body and "Try again", "Open the link" and "Remove", and no "Show details"
  text contains terminal escape codes.
- **SH-AC18** Given a fake computer whose capabilities give a link limit of 3600 s and a link whose job fails with
  `too_long`, When the learner opens it, Then "This video is too long for your computer" shows with "Your computer
  analyzes link videos up to 1 hour long." in its body, and "Open the link", "Show details" and "Remove" are offered
  (no "Try again" and no "Try audio only").

---------------------------------------------------------------------------------------------------------------

## 9. Didactic model

### 9.1 The learning design in one paragraph

Connected speech is learned by **noticing** a change in real input, **comparing** it with the form the learner
expects, **imitating** what is safe to imitate, and **meeting it again**, over days, in different voices. The
web already supplies the comparing (the five-step lesson), the explaining (glossary, Learn, practice advice), the
imitating (shadowing, record yourself) and the retrieval (the quiz). The phone adds what a phone is good at: short
daily sessions, swipe-cheap exposure and repetition, retrieval right after exposure, the return of saved shorts,
and practice in the learner's ears and mouth wherever they are.

### 9.2 How the feed stays didactic

1. **One spotlight per short** (F-07): one change named, located, written both ways, labelled "Safe to produce" or
   "Recognize only". Without it a 15 s short with 10 to 40 marked words is noise [F, measured].
2. **Marks on every caption** in the web's family vocabulary, each mark a door to its lesson.
3. **Hear before reading** (Listen first) for learners who turn it on; reading along on the automatic replay.
4. **Slow, replay and loop under the thumb**, with "hold to slow down" as the signature gesture.
5. **Sets with an end**, retrieval questions on what was just met, and a summary card (F-08).
6. **Meeting again**: saved shorts come back in later sets (F-09).
7. **Change modes**: massed exposure to one change across voices and clips, reached from Home, Learn, lessons and
   quiz results.
8. **Copy what is safe**: shadowing is invited for "Safe to produce" changes; "Recognize only" says "Just learn to
   hear it: no need to copy it."

### 9.3 A 5-minute session (today's set), as the learner lives it

| Time | What the learner does | Devices used |
|---|---|---|
| 0:00 | Home → "Start" (Today · about 5 minutes) | today's set |
| 0:05 | Short 1 (Saved, last watched 4 days ago): "Listen for: the h disappears" → plays → replays once → "Play the word" slowly | spotlight, saved shorts |
| 0:45 | Short 2 (flapping) → taps “water” → lesson step 2 (t ≠ ɾ) → step 4 (the tap in the mouth) → closes | lesson, compare, mouth |
| 1:50 | Shorts 3 to 5 (new, from last week's clip) → holds to slow one down → "Shadow" on short 4 (Safe to produce) | slow, shadowing |
| 3:20 | Shorts 6 to 8 → "Save" one that was hard | saved items |
| 4:00 | 5 questions on the changes just met (which pronunciation / which change / how many words) | retrieval |
| 5:00 | Summary: "Done for today · 4 of 5 right · What you met: flapping ×3, h dropping ×2…" → closes | end card |

### 9.4 Over weeks

- Week 1: one or two clips; most shorts are new.
- Weeks 2 to 4: the share sheet adds clips during the week; up to 2 saved shorts return in each set; Practice's
  "Focus on my weak spots" follows the lowest question accuracy; Learn counts grow ("34 shorts in your clips").
- Over months: Practice shows accuracy per change and how many of the 16 changes were met; Insights reminds the
  learner why weak forms are the default.
- The app never punishes absence: no streaks, no "you missed a day" messages.

### 9.5 Web didactic devices: what comes over and how

| # | Web device [WF 5] | Phone adaptation | Release |
|---|---|---|---|
| 1 | "What's this?" popovers (27 terms, 12 metrics, about 78 IPA keys, 16 changes) | bottom sheet; same texts; "Words we use" index (F-14) | v1 |
| 2 | Guided tour (10 steps, driver.js) | 3-page Welcome + contextual coach marks, restartable (F-01) | v1 |
| 3 | Five-step word lesson | sheet over the feed, same steps, Full / Compact guidance (F-10) | v1 |
| 4 | Dictionary vs said, `= ≠ – +`, boundary gap in ms | lesson step 2, reflowed so every sound is visible | v1 |
| 5 | Practice advice + register + why | spotlight, lesson step 3, Learn, quiz explanation; drives whether shadowing is invited | v1 |
| 6 | Articulator (2D mouth), mouth demos, IPA mouths | port of the 2D model: lesson step 4, Learn, IPA chart (F-10, F-15, F-16) | v1-complete |
| 7 | Shadowing loop (3 slow rounds + 1 full) | short action + lesson step 5 (F-12) | v1 |
| 8 | Record yourself + pitch comparison, no score | A/B by ear always; pitch comparison when connected (F-13) | v1-complete |
| 9 | Transcript marks | captions on every short (v1-core) + Clip page transcript (F-07, F-05; v1-complete) | v1 |
| 10 | Intonation badge | on the current caption phrase and in the transcript | v1 |
| 11 | Prominence strip, content vs function words | phrase details (F-11) | v1-complete |
| 12 | Pitch (F0) chart with table | phrase details, with text summary and table | v1-complete |
| 13 | Phrase IPA both ways | "IPA" caption mode (v1-core); phrase details (v1-complete) | v1 |
| 14 | Learn phenomenon pages | Learn + "Watch examples" (F-15) | v1 |
| 15 | IPA chart | reflowed by group for a phone (F-16) | v1 |
| 16 | Practice quiz | Practice tab + questions at the end of each set (F-17, F-08) | v1 |
| 17 | Insights vs published figures | Insights pooled on the phone + Clip Summary + Home teaser (F-18) | v1-complete |
| 18 | Pipeline stepper with teaching lines | request detail (F-20) | v1 |
| 19 | Review mode | dropped: validates the pipeline, not the learner [WF 3.8] | — |
| 20 | "Safe to produce" / "Recognize only" filters | transcript filters, spotlight preference | v1 |
| 21 | Accessible redundancy, CVD palette, pattern emphasis, chart tables | section 10, F-22 | v1 (CVD simulation previews Later) |
| 22 | "Back to Insights / Learn / Practice" | sheets and system Back return to the origin | v1 |

### 9.6 New on the phone (no web precedent), and why each is justified

The owner: "Add everything you consider necessary for the app to stay didactic." [Brief], within "don't improvise
too much". This is the complete list of devices and conveniences with no web precedent; each is built from web data
and web logic. Revision 2 removed the ones the review judged over-built (moved to Later in 9.7).

| Device | Kind | Why it is needed | Built from |
|---|---|---|---|
| Shorts (5–30 s runs of phrases) | brief | the feed unit the brief asks for; phrases alone are 1.3–3.8 s | `segments[]` timings |
| Spotlight "Listen for" (a fixed, deterministic order) | didactic | one thing to notice per short; otherwise the feed is noise | web `pickExamples`, `reference.practice` |
| Sets, today's set, summary card | didactic | a finite daily session instead of endless scrolling | Mix ordering + web quiz |
| Questions at the end of a set | didactic | retrieval right after exposure | web `quiz.ts` |
| Saved shorts that return in sets after 3 days | didactic | "meet it again" without a scheduling system | local saved list |
| Change modes ("Watch examples") | didactic | many examples of one change in different voices | web "Examples from your clips" as a feed |
| Listen first (opt-in, off by default) | didactic | hearing before reading, for learners who want it | caption text |
| "Hold to slow down" gesture, "Replay it once" at the end of a short | feed convenience | slow and repeat are the core study gestures on a phone | web speeds and loop |
| "Next change" in the lesson | convenience | the web's N walks every word; on a phone, jumping to the next marked word saves many taps | `phenomena[]` |
| Generated audio-only visual | brief (feed) | 3 of 10 clips have no video; the feed must still look alive | `f0_track` |
| "Leave this clip out of the Mix" | convenience | a clip the learner does not want to study should not fill sets | local setting |
| Home dashboard, Library availability, Requests, notifications, Storage | brief | the dashboard, sync and queue the brief asks for | catalogue, queue |

### 9.7 Dropped or deferred, and why

- **Review mode**: dropped; it validates the pipeline, not the learner [WF 3.8].
- **report.html**: dropped; a desktop HTML duplicate of the Summary [BE 2].
- **Server file browser, path jobs, folder import**: dropped; meaningless and unsafe from a phone [WF 9.3, BE 15.1].
- **Keyboard shortcuts**: replaced by labelled buttons, gestures and accessibility actions.
- **Engine, speech model and language choices**: the phone sends the computer's defaults (TIMIT-61, small, English);
  only "Audio only" and dialogue separation are exposed [WF 9.3, AD 2.3].
- **Insights occurrences table** (44 rem wide): replaced by change modes and phenomenon pages.
- **Word-variants explorer** ("How “to” was said across your clips"): Later; Practice already uses variants and
  change modes cover browsing examples.
- **Color-vision simulation previews**: Later; the palettes and patterns themselves are v1.
- **Offline "record now, compare later"**: Later; it would store the learner's voice on the phone [BE 16.13].
- **Daily reminder notification**: Later; the core loop must prove itself first, and no guilt-driven prompts in v1.
- **Removed in revision 2 as over-built (Later)**: spaced repetition with intervals 1/3/7/14/30 days, the
  "Learned" state and the "Did you hear the change?" self-check (Q-6); three set sizes ("Session length");
  the set's "focus change"; weekly trends, days practised and minutes in Progress; "Focus mode"; "Practice this
  clip" (clip-scoped practice); automatic saving of wrong answers; Library filters "Audio only" / "With video";
  "Download only while charging"; the 14-day "waited 2 weeks" notice; the 50-request and 4 GB queue limits; an
  internal `phonotrainer://` URI scheme; switching between several computers; the API 37 local-network
  permission flow.

### 9.8 Content rules

- **Single source** (S-18, D-09): the glossary (`web/src/didactic/glossary/*`), the Learn content
  (`web/src/features/learn/content.ts`: how to hear it, Spanish-speaker tips, caveats, mouth demos), the IPA layout
  (`web/src/features/learn/ipaLayout.ts`) and the articulation table (`web/src/articulation/phones.ts`) exist only
  in the web's TypeScript today [AD 2.6]. The phone shows the same text through a **build-time extractor** that
  reads those files **read-only** and emits versioned JSON into the Android build, bundled so Learn works before
  pairing and offline. **Nothing under `web/` is created, moved or edited** (D-35). A drift test regenerates the
  JSON from the current TypeScript and fails the build when it differs from what the app bundles; the extractor
  fails loudly when the TypeScript shape changes (it depends on that shape, so it is deliberately strict). It may
  use the Node toolchain the web build already requires; any new tool is recorded in `THIRD-PARTY-NOTICES.md`.
  `/api/reference` ships as a snapshot and refreshes from the computer when the API version changes. The app
  never invents didactic content.
- **Voice** [WI 10]: plain, second person, calm; jargon explained in one sentence; describe, never grade; sentence
  case; curly quotes around studied words; "×" for speeds, "→" for changes, "‿" for linking.
- **IPA**: `/dictionary form/` vs `[what was said]`; always rendered in the IPA font, never with per-glyph fallback
  (combining marks would misplace, "which is exactly what a learner is trying to read" [WI 5.2]).
- **Low-confidence words** are never spotlighted, never asked about, and are labelled when shown.
- **Mark vocabulary** (identical to the web; colors are the design agent's, the semantics are fixed) [WI 3]:

| Family / kind | Line style | Icon meaning | Name |
|---|---|---|---|
| Reduction | solid underline | chevrons down: "the sound weakens" | "Reduction" |
| t/d processes | dashed underline | scissors: "the stop is clipped" | "t/d processes" |
| Assimilation | double underline | merge: "two sounds blend" | "Assimilation" |
| Word boundary | wavy underline + ‿ between linked words | link: "words run together" | "Word boundary" |
| Lexical contraction | dotted underline + chip "gonna ← going to" | shrink | "Lexical contraction" |
| Other change (e.g. elided word) | dash-dot underline | dashed circle | "Other change" |
| Low confidence / nothing heard | dashed outline around the word | — | "Low confidence" |
| Most prominent word of the phrase | bold | — | "Stressed word" |

---------------------------------------------------------------------------------------------------------------

## 10. Accessibility and inclusive design requirements

The web identity was built for learners with color-vision deficiency [WI]; the phone keeps that rule and adds touch
and screen-reader requirements. Each item is verified by an automated check, a scripted TalkBack pass, or a
grayscale / largest-font screenshot review.

| Id | Requirement |
|---|---|
| A-01 | **Never color alone.** Family = line style + icon + name (+ color); status, connection and sync = icon + word; correct/wrong = icon + words + outline; selection = fill, ring or check; chart series = line style + marker + direct label. Every screen passes a grayscale review. |
| A-02 | **Hue is reserved** for the four families [WI 11]; brand color may appear only where it cannot be confused with a family; anatomy in the mouth drawing never uses family hues. |
| A-03 | **Touch targets ≥ 48 x 48 dp** for every control, including each caption word (hit areas may extend beyond the glyphs without overlapping neighbours), "What's this?" controls, IPA symbols and sound buttons in the lesson; quiz answers ≥ 56 dp tall. |
| A-04 | **TalkBack**: every control labelled; caption words read "water, flapping, safe to produce" (word + changes + advice; the spotlight word adds "listen for"); each short exposes one summary and custom actions (Play/Pause, Next short, Previous short, Replay phrase, Slower, Open lesson, Save); sheets have pane titles; headings marked; decorative art hidden; polite announcements limited to stage changes, answer results, shadowing phases, recording hints and connection transitions. |
| A-05 | **IPA is speakable**: every IPA string has an accessible text built from symbol names ("[ɾ]: voiced alveolar tap") and a play action. |
| A-06 | **Font scaling** up to the system maximum (200 %): no clipped or truncated essential text; captions drop the dimmed previous/next phrases first, then follow the word being said line by line (F-07 item 4), so the current word is always visible; tables reflow; IPA scales with text. |
| A-07 | **Reduced motion** (system animator scale off, or Motion "Reduce"): instant feed transitions, no pulses, parallax or decorative animation, no animated auto-scroll; the mouth drawing still follows the audio (content, web rule) but never moves without playback. No infinite decorative loops at any setting. |
| A-08 | **No autoplay with TalkBack on**: nothing starts sound without an explicit action. |
| A-09 | **Captions for all speech**: every played sound has its text available; information is never audio-only ("your turn" in shadowing is text + optional haptic). |
| A-10 | **Contrast**: body text ≥ 7:1, secondary ≥ 4.5:1, marks and focus ≥ 3:1 on every surface they sit on, including scrims over video (web gates [WI 4, 11]). |
| A-11 | **No time limits** the learner cannot control: questions untimed; coach marks wait; the share sheet's 2 s auto-close is off with TalkBack; the only limit (30 s per take) is announced in advance. |
| A-12 | **Gesture alternatives**: every gesture in F-07 has a button or accessibility action; long-press always has a "More" equivalent. |
| A-13 | **Keyboard, D-pad and switch access**: everything reachable in reading order with a visible focus indicator. |
| A-14 | **Charts** have a text summary and a "Show as a table" view (web rule). |
| A-15 | **Color-vision friendly palette and pattern emphasis** settings (F-22); patterns default on with that palette. |
| A-16 | **Plain language**: every screen has a one-sentence lede; jargon has "What's this?"; errors say what happened and what to do. |

---------------------------------------------------------------------------------------------------------------

## 11. Privacy, security and public-repository requirements

### 11.1 Data on the phone

- App-private storage only: clip copies, the device queue and its file copies, learning progress, saved items,
  settings, the paired computer's identity, last address and device credential.
- The credential is kept in platform-protected storage; never shown, logged, exported or included in "Copy
  diagnostics".
- **Backup** (Auto Backup and device transfer) uses an allowlist [AR 8.3]: learning progress, saved items and
  settings only. Never the credential, the computer's address, the queue, file copies or media (re-downloadable).

### 11.2 What leaves the phone

- Only to the paired computer: requests (links, files), cancels and removals of this phone's failed jobs,
  record-yourself takes (live; measured and deleted by the computer [BE 10]), and the pairing exchange.
- Nothing goes anywhere else: no analytics, no crash-reporting service, no advertising id, no cloud. The phone never
  contacts YouTube or X; "Open the link" hands a link to the system only when tapped.
- Release logs contain no addresses, credentials, links or file names; notifications never contain the address or
  links with tracking parameters.
- The clipboard is read only on an explicit "Paste".

### 11.3 Recordings

Takes live in memory or a temporary file only until the learner leaves the word; never written to shared storage,
never queued, never backed up.

### 11.4 Exported components and permissions

- Only the launcher entry and the share target are exported; navigation targets are internal (5.4). Shared input
  is untrusted: only the first http(s) URL is used, length-limited; files are read through the system resolver,
  checked for type and size before copying; the app never forwards intents or opens arbitrary URIs.
- Permissions, complete list:
  - `INTERNET` and `ACCESS_NETWORK_STATE` (install-time);
  - `RECORD_AUDIO` (runtime, asked at the first "Record", F-13);
  - `POST_NOTIFICATIONS` (runtime, Android 13+, asked at the first request, F-23);
  - for transfers started from the foreground, the architect's choice of either `FOREGROUND_SERVICE` +
    `FOREGROUND_SERVICE_DATA_SYNC` (a data-sync foreground service; Android 15+ limits its running time, which
    resumable or restartable transfers tolerate) or `RUN_USER_INITIATED_JOBS` (a user-initiated data transfer
    job);
  - the permissions the background-work library adds to the merged manifest by itself (for example `WAKE_LOCK`,
    `RECEIVE_BOOT_COMPLETED`), listed in `android/SECURITY.md`.
  - Not requested: `ACCESS_LOCAL_NETWORK` (the app targets API 36, 7.1), `CHANGE_WIFI_MULTICAST_STATE` (discovery
    never receives broadcast or multicast, 7.3), location, contacts, camera, storage-wide access (system picker
    only).

### 11.5 What the computer must restrict for LAN clients (functional; S-05 to S-08)

- Phone access is off by default and enabled only by an explicit operator action (S-01).
- Every non-loopback request (API, artifacts, job channel) must be signed with a valid device credential (a
  signature, never the credential itself; replays refused); pairing endpoints are rate-limited; loopback use by the
  browser client keeps working without a credential.
- Before authentication, health reveals only display name, server id, API version, whether phone access is on and
  the computer's clock, plus, to a paired phone's challenge, the proof of identity of 7.4.
- Phone access answers only on the networks the operator approved (7.2); elsewhere it is suspended.
- Unavailable to LAN clients: the disk browser (`/api/browse`), analysis from a server path (`POST /api/jobs` with
  `path`), folder import, review writes, deleting finished analyses; cancel, delete and re-run only for the
  device's own requests (own failed or cancelled jobs for delete).
- No absolute path or user name in any response to LAN clients (`/api/health` `workspace`, job `result_dir`, corpus
  `result_dir`, path-revealing errors) [BE 15.1, AD 4.3].
- Links from LAN clients: only http(s), refusing targets that resolve to loopback, private, link-local or multicast
  addresses (SSRF) [BE 15.1].
- Requests whose `Host` does not match the computer's own names or addresses are refused (DNS rebinding) [BE 15.2].
- Resource limits: upload size cap and free-space check, bounded job-channel connections [BE 15.4]. A per-device
  cap on pending jobs is Later (S-L5): only paired phones can create jobs.

### 11.6 Transport risk (accepted for v1, documented) and how cleartext is confined

Plain HTTP on the home network (the server has no TLS [BE 15.3]): someone on the same Wi-Fi could read clips,
links and requests in transit. A phone's credential could be captured only by someone recording the pairing exchange
itself, the one moment it travels (the code that starts it is shown only in the computer's terminal, single use,
valid 5 minutes). After pairing the credential never travels again: every request is signed with it, so a recorded
request reveals no reusable secret and cannot be replayed (D-38). Mitigations: opt-in phone access on approved
networks only, per-device revocable credentials, signed requests, the computer's proof of identity before the phone
sends anything (7.4), restricted routes for LAN clients.

**How cleartext is allowed** (an Android network security configuration can only name hosts known at build time,
and the computer's address is known only at runtime and must never be committed):
- the network security configuration permits cleartext in its base configuration and names no address;
- the app enforces the restriction in code: its single shared HTTP client (also used by media playback and image
  loading) refuses any cleartext request whose host is not the paired computer's current address, or, before
  pairing, an address in a private or link-local range being checked or paired; every other cleartext request
  fails before any byte is sent;
- `android/SECURITY.md` and the README say exactly this (including that the platform setting alone would allow
  cleartext), together with how to turn phone access off and revoke phones. TLS with a certificate pinned at
  pairing is Later (Q-1).

### 11.7 Public-repository rules (definition-of-done checks)

- Never committed: IP addresses, host names of the owner's machines, pairing codes, credentials, server ids,
  device serials or adb ids, home paths, user names, keystores, signing configs, `local.properties`, personal
  media, screenshots or QA logs showing any of these [Brief]. The fixed discovery port and the default HTTP port
  are product constants, not personal data, and may be committed (7.2). QA evidence goes to an ignored folder;
  screenshots in docs come from the fake computer with synthetic data. Test fixtures are synthetic (like the
  server's `mk_analysis`), never real clips or recordings.
- **Ignore-rule trap** [AD 1.1]: the root `.gitignore` swallows any folder named `data/`, `out/`, `downloads/`,
  `workspace/` and media suffixes anywhere, and ignores no Android build output or keystore. `android/.gitignore`
  MUST re-include what the code needs (or the code avoids those names) and MUST ignore `.gradle/`, `build/`,
  `.kotlin/`, `local.properties`, `*.jks`, `*.keystore`, `keystore.properties`, `captures/`, `.cxx/`, `*.apk`,
  `*.aab`, `/artifacts/`. `git status --ignored android/` is checked in the definition of done (X-AC5).
- **Release signing**: release builds are signed with one stable key kept outside the repository and referenced
  through an ignored `keystore.properties` (documented in `android/README.md`, with no path or secret), so that
  each new version installs over the previous one and the queue, clips and progress survive updates (NFR-07).
- A scan for addresses, paths and secret-shaped strings, and a scan for non-English text, run before every commit in
  `android/` (X-AC2, X-AC4).
- Licences: every new dependency and font is GPL-3.0-or-later compatible and recorded in a new Android section of
  `THIRD-PARTY-NOTICES.md`; fonts vendored as unmodified TTF with their OFL text [AD 4.1] (X-AC3).

---------------------------------------------------------------------------------------------------------------

## 12. Server-side requirements (functional; no implementation design)

Every change keeps the browser client working (it requires `api_version >= 5`, `web/src/api.ts:26`), bumps
`API_VERSION` (`phonotrainer/server.py:75`), and ships with pytest coverage (repository rule). No server change
touches `web/` (D-35). Discovery, renditions and pairing introduce no dependency that is not recorded in
`THIRD-PARTY-NOTICES.md`.

| Id | Requirement | Why (evidence) | Tier |
|---|---|---|---|
| S-01 | **Phone access, opt-in.** Default stays loopback-only and unchanged. An explicit operator action turns phone access on; once on it stays on across restarts until turned off (Q-5), remembered in the operator's PhonoTrainer configuration directory outside the repository and the workspace, **on the networks approved when it was turned on** (approving another network is one terminal command; on an unapproved network phone access is suspended: no phone request is accepted and discovery is silent, D-42); each start with it on prints that it is on (or suspended, with the command that approves the network), the address and port to use, and how to show a pairing code, without writing secrets to logs. | 127.0.0.1 default, no LAN mode [AD 4.4] | v1-core |
| S-02 | **Identity and capabilities.** A persistent random server id per machine, kept with the paired devices in the operator's configuration directory (never in the repository or a workspace), so a workspace switch never breaks pairing (D-34); a library id per workspace; an editable display name, by default "PhonoTrainer computer" and never the host name (D-42); API version; minimum supported app version; capability flags (link downloads available, dialogue separation available, renditions available, maximum upload size, free space). Identity-only answer before authentication, with a **proof of identity** for a paired phone's random challenge, computed with that phone's credential, so the server id alone never proves who answers (D-37); no paths ever. Health answers without waiting on the analysis worker. | no instance id, health leaks the workspace [BE 16.4, AD 4.3]; CPU saturated during analysis (2.3) | v1-core |
| S-03 | **Discovery**: while phone access is on, answer a phone's broadcast probe on one fixed UDP port in the unprivileged range with a unicast reply carrying server id, display name, HTTP port and API version only. No mDNS dependency. | some desktop firewalls block 5353 [AD 1.2] | v1-core (fallback if cut: manual address only) |
| S-04 | **Pairing and device management, terminal only.** A CLI command shows an on-demand 6-digit code, single use, valid 5 minutes, invalidated after 5 wrong attempts; pairing calls rate-limited; exchange for a per-device credential; the CLI lists paired phones (name, paired date, last seen) and revokes one or all. No browser page (D-35). | no auth anywhere [BE 15.2, 16.6] | v1-core |
| S-05 | **Authentication** of every non-loopback API call, artifact download and job-channel connection by **signed requests**: the credential is exchanged once at pairing and never sent again; each request carries a signature with a timestamp and a single-use nonce, so a recorded request cannot be replayed (D-38); a stable error code for a rejected credential. | [BE 15.2] | v1-core |
| S-06 | **LAN restrictions**: 11.5 in full (no browse, path-create, import, review writes, deletion of finished analyses; cancel, delete and re-run only of the device's own requests; no absolute paths). | [BE 15.1, AD 4.3] | v1-core |
| S-07 | **SSRF guard** for links from LAN clients, refused at creation with the `refused_address` code. | [BE 15.1] | v1-core |
| S-08 | **Host validation** while phone access is on. | [BE 15.2] | v1-core |
| S-09 | **Idempotent creation**: link creation, upload creation and re-run accept a client request id; a repeat returns the existing job; the key is kept with the job; the submitting device is recorded. | duplicates verified [BE 4, 16.7] | v1-core |
| S-10 | **Resumable, bounded uploads**: declared size and checksum, a query of received bytes, continuation after interruption, discard of abandoned partial uploads after 48 h, a size cap and free-space check announced in capabilities, no double-disk copy beyond what is unavoidable. In v1-core the existing upload route is used with the size cap and free-space check, and an interrupted upload restarts. | unbounded, restart from zero, 2x disk [BE 8, AD 2.4] | v1-complete (fallback: single upload, restart from zero, idempotent) |
| S-11 | **Sync listing**: a lean, complete listing (no progress logs) of every job: id, status, revision (changes whenever a served artifact or status changes), title, summary, duration, has-video, submitting device, library id, and per artifact its name, bytes, checksum and status (ready, being prepared, failed, unavailable); for a video original also its codec, width, height and start offset against the analysed timeline. Stable ordering by id. The live job channel keeps its `deleted` events. (No cursor and no tombstones: at this scale the complete listing is a few KB, and deletions are detected as in 7.9.) | no revision, sizes or checksums [BE 16.1, 16.15]; ids are the only unique key [AD 2.1] | v1-core |
| S-12 | **Phone renditions, each optional with a fallback**: (a) a pre-compressed compact clip data file served as `application/gzip` without `Content-Encoding`; (b) compressed speech audio for the mix and the dialogue track on exactly the analysed timeline; (c) a poster for every video clip; (d) a **phone video on request**: when a paired phone asks for it (the original is above 1080p or the phone cannot decode it), an H.264 ≤ 720p video, orientation kept, starting at the analysed timeline's zero. (a)–(c) are made after each analysis and back-filled for existing clips. All renditions run outside the analysis worker at low priority and never delay a waiting analysis; each reports its own status in the listing; a missing original makes (d) "unavailable", never a stuck "being prepared". Range support on all of them. | WAV and AV1 only; AV1 on the test phone unverified; `libx264` absent, `libopenh264` present [BE 5, 14, 16.2; AD 2.2] | v1-complete (fallbacks: `analysis.json`, the WAVs, the poster fallback, the original or audio only) |
| S-13 | **Machine-readable job state**: stage identifier (download, extract, separate, transcribe, phones, save), queue position, error code (taxonomy of 8.6, including `playlist` from the downloader's playlist error and `refused_address`), a clean human message without terminal escape codes, a title once known, the submitting device, the link kept; download progress names the actual site, not always "YouTube". | free text with ANSI codes [BE 3, 16.9, 16.16] | v1-core |
| S-14 | **Job channel for phones**: the job channel available to authenticated phones, with **lean events** (no artifacts and no progress log per event; the phone fetches artifacts through the listing when a job's revision changes). | heavy full-job messages [BE 7] | v1-core (lean events included, D-44) |
| S-15 | **Interrupted jobs** (running or waiting in line at a restart) carry the `interrupted` code (today only text). | `phonotrainer/jobs.py:250-252` | v1-core |
| S-16 | **Record yourself** (`/contour`, `/compare`, existing) available to paired phones with the same limits. | [BE 10] | v1-complete |
| S-17 | **Reference** (`/api/reference`) available to paired phones, cacheable by API version. | [BE 12] | v1-core |
| S-18 | **Didactic content extraction (build tooling, not a server change)**: a deterministic extractor reads the glossary, Learn content, IPA layout and articulation table from `web/src` read-only and emits versioned JSON for the Android build; a drift test fails on any difference (9.8). | TypeScript-only today [AD 2.6] | v1-core |
| S-19 | Withdrawn in revision 2 (per-device pending limit): moved to Later as S-L5. | only paired phones create jobs | — |
| S-20 | **Link path proven on the owner's own shares**: during device QA the owner shares one YouTube video, one YouTube Shorts and one X post from the test phone; each is followed end to end on the development computer; the outcome is recorded as it is (8.7). | 0 successes [AD 2.1] | v1 release gate |
| S-21 | **Compatibility**: with phone access off, behaviour and API for the browser client are exactly as before; the SPA's version check keeps passing. | [AD 4.4] | v1-core |
| S-22 | **Re-run**: re-run an own failed, cancelled or interrupted job from what the computer stored (its link, or its uploaded file), with the same options or audio only for links; idempotent (S-09); the new job replaces the old one; "unavailable" with a clear code when the stored file is gone. Used by "Try again", "Try audio only" and the automatic re-run after an interruption. | the phone deletes its copy after upload (8.3); no retry route exists and path creation is forbidden to phones (S-06) | v1-core (fallback if cut: the phone keeps each file copy until the clip is "Ready on your computer" and re-uploads; links are re-created) |
| S-23 | **Link path hardening**: the video format selection prefers H.264 at 720p or less, then VP9, then other codecs (today AV1 is chosen even when H.264 is offered, `phonotrainer/download.py:21`); the downloader's JavaScript runtime is configured (Node, which the web build already requires; any new runtime is recorded in `THIRD-PARTY-NOTICES.md`) and documented in the README; the installed downloader version is reported in the QA report, and "update the downloader" is the README's first remedy for HTTP 403 (it stays unpinned on purpose: sites change and only new releases follow them); downloader errors map to the codes of 8.6. **Links from phones are confined** (8.7, D-43): site-specific extractors only (the generic one is off), the link re-checked against private addresses at creation, re-run and download; live and upcoming streams refused (`live_stream`); videos longer than `max_link_duration_s` (default 3600 s, editable on the computer) or larger than the upload limit refused (`too_long`); free space checked before the download (`no_space`); browser and CLI downloads unchanged (S-21). Tested with the existing fake downloader, never against a real site. | 0 of 3 downloads succeeded with 403; no JS runtime configured; AV1 preferred [AD 2.1, BE 9] | v1-core |
| S-L1 | Link metadata probe (title, duration, thumbnail) before download. | [BE 16.8] | Later |
| S-L2 | Learner progress storage and sync between web and phone. | [AD 2.7] | Later (Q-3) |
| S-L3 | TLS for phone access with a certificate pinned at pairing. | [BE 15.3] | Later (Q-1) |
| S-L4 | Server-side link normalization and a "same link already analyzed" answer across devices. | `?si=` kept [BE 9] | Later |
| S-L5 | Per-device limit on pending jobs, with a clear error code. | unbounded queue [BE 15.4] | Later |

**New server capabilities in v1, summarized**: phone-access mode (S-01), machine identity, library id and
capabilities (S-02), UDP discovery (S-03), terminal pairing and device management (S-04), authentication (S-05),
LAN restrictions, SSRF guard and Host validation (S-06 to S-08), idempotent creation (S-09), resumable uploads
(S-10, v1-complete), the sync listing (S-11), phone renditions including phone video on request (S-12,
v1-complete), machine-readable job state and error codes (S-13, S-15), the job channel and reference for phones
(S-14, S-17), record-yourself for phones (S-16, v1-complete), re-run (S-22) and link-path hardening (S-23).
S-18 is build tooling and S-20 a QA gate.

---------------------------------------------------------------------------------------------------------------

## 13. Non-functional requirements

| Id | Area | Requirement |
|---|---|---|
| NFR-01 | Supported Android | minSdk 29 (Android 10: Opus recording, modern media APIs, scoped storage [AD 4.5]); compileSdk 37 (the Artemi toolchain [AR 2]); **targetSdk 36** (D-18: no local-network permission flow; on Android 17 an app targeting 36 keeps implicit local-network access [TC 6]); designed and tested on Android 16 / API 36 (the test phone and the API 36 managed emulator); 16 KB page-size compatible. |
| NFR-02 | Performance (release build, test phone) | cold start to interactive Home ≤ 1.5 s with 20 clips; swipe to sound ≤ 300 ms p90 (SC-08); caption highlight within 60 ms (SC-09); ≥ 95 % of frames on time during swipes; lesson opens ≤ 500 ms; a set or a Practice session is generated ≤ 1 s with 20 clips; preparing the shorts of the largest real analysis (614 s, 4.8 MB) ≤ 1.5 s off the UI thread. |
| NFR-03 | Storage | install size ≤ 40 MB; a 4-minute clip without video ≤ 3 MB on the phone with renditions (≤ 20 MB with the `analysis.json` and WAV fallback); the budget is enforced (7.10); the app never takes the phone below the free-space floor (1 GB or 10 % of storage, whichever is smaller) with its own downloads or copies; temporary and partial files are cleaned at start. |
| NFR-04 | Battery | no background work when nothing is pending except the periodic sync window (≥ 15 min, unmetered network, battery not low, as Android allows); no discovery in the background; no wake locks beyond user-visible transfers with a notification; only the current and adjacent shorts are prepared; playback stops when the app leaves the foreground. |
| NFR-05 | Data | the app's only network peer is the paired computer on the home network (or, before pairing, the address being checked); it never uses mobile data for its own traffic. |
| NFR-06 | Offline-first | with no network, everything on the phone works: Home, Library, feed (all modes), lessons (all steps except the pitch comparison), shadowing, recording with A/B, Learn, IPA chart, glossary, Practice, Insights, Saved, Settings, creating requests. Needs the computer: sending, processing, downloading, pitch comparison, cancels and re-runs (queued). No screen shows an indefinite spinner because the computer is missing. |
| NFR-07 | Durability | requests, request states, file copies, download states, positions, lesson step, quiz question and set progress survive rotation, process death, reboot, low-memory kills and app updates; writes are atomic; the data model is versioned with migrations from the first release (schemas exported, migration tests [AR 3.2]); release builds are signed with one stable key so updates install in place (11.7). |
| NFR-08 | Robustness of data | unknown or missing optional fields never crash the app (older analyses, espeak engine, rules versions 1 to 3 [AD 2.3]); partial downloads are never used; corrupt files are re-downloaded. |
| NFR-09 | Media | the clip audio is the clock; muted video follows within 0.2 s, offset by the start offset from the listing; every clip kind plays (landscape, portrait, audio-only, video not on the phone, video not available); an undecodable video falls back to sound, never a crash; speed changes keep pitch; switching tracks keeps the position; audio focus and earphone unplugging are respected. |
| NFR-10 | Security | cleartext allowed by the platform setting but confined in code to the paired computer (11.6); section 11; release builds minified and not debuggable; only the launcher and share target exported. |
| NFR-11 | Licensing | GPL-3.0-or-later app; every dependency compatible and listed in `THIRD-PARTY-NOTICES.md`; fonts OFL, unmodified TTF with licence text. |
| NFR-12 | Language | every string, test name, comment, log and document in English; no translations (repository rule). |
| NFR-13 | Quality bar | the Artemi standard [AR 10.1]: unit tests, lint clean with no baseline, managed-device UI and end-to-end tests with a robot and test tags, release build verified on the phone; the fake computer (unreachable, slow, 4xx, 5xx, lost responses, partial downloads), fakes for connectivity, clock and player; tests for the queue state machine, retries, idempotency, partial downloads, connectivity states, share parsing and shorts v1; a regression test per bug; server changes with pytest. |
| NFR-14 | Device QA | interacts only with this app; on the test phone simulates "computer unreachable" only by stopping the server or turning phone access off; never reboots the phone, toggles its radios or changes its clock (those ACs are **[emulator]**); changes only the settings the brief allows (font scale, animation scale, TalkBack) through adb, logs them and restores them; reboots only if the owner does it (Q-7); records the phone's video decoders (codec, maximum size) and whether the YouTube and X apps are installed; never sends a link to the real computer (8.7). |

### 13.1 Product-wide acceptance criteria

- **X-AC1** (local, non-commercial; brief: "an entirely local, non-commercial project"): Given a QA run on the
  emulator with all traffic captured, When every scripted flow runs, Then every connection the app opens goes to the
  paired computer's address (or, during pairing, to the address being checked or the discovery broadcast); and Given
  the merged release dependency list, Then it contains no analytics, crash-reporting, advertising or cloud SDK.
- **X-AC2** (English only): Given the `android/` tree, When the language scan runs, Then it reports 0 findings
  outside IPA symbols and phonetic examples, and no `res/values-<language>` directory exists.
- **X-AC3** (licences): Given the release build's dependency report and its bundled fonts and vendored icon set, When
  the licence check runs, Then every library has an entry in the Android section of `THIRD-PARTY-NOTICES.md` with a
  GPL-3.0-compatible licence, every font and the icon set have their entry and bundled licence text, and the check
  fails the build otherwise.
- **X-AC4** (no sensitive data): Given the files to commit under `android/` (and any server change), When the secret
  and path scan runs, Then it finds no IP address, host name, home path, user name, serial, adb id, credential,
  pairing code or keystore.
- **X-AC5** (ignore rules): Given the Android project, When `git status --ignored android/` runs, Then every source
  file, schema, font and licence text is tracked, and every build output, `local.properties`, keystore and
  `keystore.properties` is ignored.
- **X-AC6** (cleartext confinement): Given the app paired with a computer, When any code path attempts a cleartext
  request to another host, Then the shared HTTP client refuses it before sending a byte.
- **X-AC7** (update in place): Given version N installed with waiting requests and downloaded clips, When version N+1
  signed with the same key is installed over it, Then every request, clip and setting is still there.
- **X-AC8** (the Android client in `android/`): Given a clean checkout, When the Android build runs from `android/`
  (unit tests, lint and a release build), Then it succeeds, and the release build installs and starts on the API
  36 emulator and on the test phone.
- **X-AC9** (tests and the web boundary): Given the server changes of a tier, When the Python suite runs from the
  repository root, Then it passes and every S-nn of that tier has at least one test; and Given the same branch,
  When the web suite runs, Then it passes and no file under `web/` differs from the base branch.
- **X-AC10** (device QA hygiene): Given a device QA session on the test phone, When it ends, Then its log lists every
  setting changed through adb with its restored value, and records no reboot, radio toggle, clock change or link
  sent to the real computer by an agent.

---------------------------------------------------------------------------------------------------------------

## 14. Release plan

### 14.1 v1-core and v1-complete

**v1-core** satisfies every product requirement of the brief: the feed, the dashboard, didactic shorts and lessons,
the share target and device queue, sync, and local operation. One requirement stays conditional on a component
outside the app: **link analysis** succeeds only as far as the computer's downloader does; v1 hardens it (S-23)
and reports the owner's own shares as they are (S-20). **v1-complete** adds the remaining web devices and the
efficiency work; it is built right after v1-core, and each item has a named fallback (14.4).

| Area | v1-core | v1-complete |
|---|---|---|
| Android project | `android/` in this repository; Artemi toolchain and standards; English only; GPL-compatible dependencies recorded; ignore-rule trap handled; stable release signing (11.7) | — |
| Server | S-01 to S-09, S-11, S-13, S-14 (channel with lean events, D-44), S-15, S-17, S-18 (build tooling), S-21, S-22, S-23 | S-10 resumable uploads, S-12 renditions (incl. phone video on request), S-16 record yourself |
| Onboarding and connection | F-01, F-02, 7.2 to 7.5 | — |
| Dashboard | F-03 Home, F-04 Library, F-05 Clip page (header, Shorts, Summary counts, On this phone) | F-05 Transcript tab and "How reduced is this speech?" |
| Feed | F-06, F-07, F-08, F-09: shorts v1, Mix in sets, In order, change and Saved modes, spotlight, captions with marks and modes, Listen first (opt-in), gestures, end cards, questions, saved shorts, audio-only visual, offline behaviour | — |
| Depth | F-10 lesson steps 1, 2, 3 and 5 (shadowing, F-12) | F-10 step 4 and the mouths in Learn and the IPA chart (articulator port), F-11 phrase details, F-13 record yourself |
| Learn and practice | F-14 glossary, F-15 Learn (text, counts, examples), F-16 IPA chart (without mouths), F-17 Practice | F-18 Insights and the Home teaser |
| Processing | F-19 add a clip and the share target (section 8), F-20 Requests with re-run, F-23 notifications | — |
| Sync | F-21: listing, study-pack and original-video downloads with verification and resume, budget, deletion semantics | renditions in the sync (compact data, compressed audio, posters, phone video) |
| Shell | F-22 Settings, F-24 About, F-25 assets (except the mouth drawing), section 10 | — |

### 14.2 Suggested build order (input for the work plan; each step demonstrable)

1. Server foundations: S-01, S-02, S-05, S-06, S-07, S-08, S-09, S-11, S-13, S-15, S-21, then S-03, S-04, S-14,
   S-17, S-22, S-23; S-18 extractor.
2. App shell, Connect, connectivity states, catalogue sync and study-pack download, Home, Library, Clip page,
   Settings and Storage.
3. Feed: shorts v1, short card (spotlight, captions, gestures), modes; lesson steps 1 to 3 and 5; glossary, Learn,
   IPA chart.
4. Share target, device queue, Requests with re-run, notifications.
5. Sets, questions, Saved, Practice.
6. Assets, accessibility pass, device QA on the test phone, the link gate (8.7). Device-specific checks wait until
   the phone is attached [AD 1.3]. **v1-core is done here.**
7. v1-complete in the order of 14.4 read backwards (the item cut last is built first): renditions (S-12),
   resumable uploads (S-10), Insights, the Transcript tab, record yourself, phrase details, and the articulator port
   (the largest port item [AD 2.5]). (Lean channel events, formerly first here, ship in v1-core with the phone
   channel, D-44.)

### 14.3 Release gates (definition of done)

For v1-core and again for v1-complete: all acceptance criteria of the tier green; the queue torture test (7.12);
the TalkBack script (SC-13) and the a11y script (SC-12); the product-wide ACs (13.1); the link gate (8.7)
attempted on the owner's own shares and its result reported; release build installed over the previous one and
exercised on the test phone; server pytest suite green, and the web vitest suite green without any change under
`web/`.

### 14.4 If the schedule forces a cut: what goes first, and its fallback

None of these is a product requirement of the brief; each fallback keeps the brief satisfied.

| Order | Cut | Fallback |
|---|---|---|
| 1 | Articulator port (lesson step 4, Learn mouth demos, IPA mouths) | each sound shows its name and the articulation table's "what the mouth does" sentence, without a drawing |
| 2 | F-11 phrase details charts (pitch chart, prominence strip, rhythm line, touch scrubbing) | a text-only sheet: both IPA lines, voice line, intonation units, stressed words |
| 3 | F-13 record yourself (recording, A/B, pitch overlay) | lesson step 5 offers shadowing only |
| 4 | F-05 Transcript tab | the Clip page keeps Shorts and Summary; words are studied from the feed |
| 5 | F-18 Insights and the Home teaser, and the Clip Summary meters | Learn counts and change modes remain |
| 6 | S-10 resumable uploads | a single upload with Content-Length and the idempotency key that restarts from zero; the local copy is kept until acceptance |
| 7 | S-12 renditions | `analysis.json` and the WAVs for the study pack; the poster fallback; the original video when the phone plays it, otherwise audio only with "This video can't play on this phone. Listening still works." |
| 8 | Withdrawn on 2026-10-10: S-14 lean channel events ship in v1-core, because the phone channel is new and lean from its first version (server-contract 7, D-44) | — |
| 9 | "Leave this clip out of the Mix" | none needed |
| — | S-03 discovery, only if it fails on the owner's network | manual address entry (always present) |
| — | S-22 re-run, only if it slips | the phone keeps each file copy until the clip is "Ready on your computer" and re-uploads on "Try again"; links are re-created and the failed job removed |

### 14.5 Later backlog

| Item | Why later |
|---|---|
| TLS with a certificate pinned at pairing | needs certificate management; the v1 risk is accepted and documented (Q-1) |
| Deleting finished analyses from the phone | destructive, not requested by the brief (Q-2) |
| Practice progress sync with the web | needs a server home for progress (S-L2, Q-3) |
| Spaced repetition (1, 3, 7, 14, 30 days), "Learned", the self-check after a review | no web precedent; new data model and prompts (Q-6) |
| Progress over weeks (trends, days practised, minutes) | needs a time-series model; the web keeps only totals |
| Session length choices; a "focus change" per set; "Focus mode"; clip-scoped practice | over-built for v1 (15.5) |
| A browser page for pairing and phone management | `web/` belongs to another agent (D-35) |
| Targeting API 37 with the local-network permission flow | the test phone runs API 36; a target-36 app keeps implicit access on Android 17 |
| Streaming video from the computer into the feed | the feed plays local files only (D-12) |
| Full-screen landscape video mode | portrait study with whole landscape framing is enough for v1 |
| QR-code pairing | camera permission and a scanner dependency; the code flow works with TalkBack |
| Link metadata probe before analysis | new server surface with SSRF and rate concerns (S-L1) |
| Word-variants explorer; corpus occurrences browsing | change modes and Learn examples cover browsing in v1 |
| Offline "record now, compare later" | stores the learner's voice; not requested |
| Daily reminder | prove the core loop first; no guilt-driven prompts |
| Direct Share targets, launcher shortcuts, home-screen widget | conveniences after the core loop is proven |
| Background listening (audio with the screen off) | media session and battery work; the feed is visual first |
| Color-vision simulation previews | web-specific affordance; palettes are v1 |
| Several paired computers; tablet layouts | single user, single computer; large screens work unoptimized |
| "Download only while charging" | a refinement of the budget, not needed for v1 |
| Per-device pending-job limit (S-L5) | only paired phones create jobs |
| mDNS discovery | needs a host firewall change the project must not automate [AD 1.2] |

---------------------------------------------------------------------------------------------------------------

## 15. Decisions made on the owner's behalf, conflicts resolved, and questions

### 15.1 Decisions

"Drafts" says what each draft proposed ([L] learner-first, [R] reliability-first, [F] feed-first) and which
position won. The rule applied: learning value from [L], connectivity and sync rigor from [R], the feed experience
from [F], nothing beyond the brief and the evidence.

| Id | Decision | Drafts → winner | Rationale and evidence |
|---|---|---|---|
| D-01 | **The feed unit is a short: consecutive phrases of one clip, 5 to 30 s, by the versioned rule "shorts v1"** (F-06). | [L] one phrase, merged under 2 s, split over 12 s; [R] one phrase; [F] 5–30 s runs → **[F]** | Phrases last a median 1.3–3.8 s (measured), too short to swipe; today's clips give about 140 shorts (measured); 30 s = the computer's span limit for contours and takes [WF 2]. [L]'s "one change at a time" survives through the spotlight (D-25). |
| D-02 | **Bottom navigation Home, Watch, Learn, Practice**; Library, Requests, Insights, Settings are secondary destinations. | all three agree on the tabs; [L], [F] list all clips on Home, [R] adds a Library → **[R]** for the Library | The brief wants a feed and "a dashboard / main panel to navigate between the videos" [Brief], one tap apart; a full list on Home would bury Home's other entries; a Library scales. |
| D-03 | **The Mix is always delivered in finite sets of 8 shorts and up to 5 questions** (today's set, questions at the end, a summary card); a set never changes once generated; nothing advances to the next short or clip by itself by default; no streaks. | [L] finite today's set as the Watch default; [R] an "All clips" feed with no end; [F] an endless Mix with a question every 6 shorts and a pause card after 20 → **[F]'s modes in [L]'s sets**; revision 2: one size, no focus change | The brief asks for TikTok-like swiping and for the app "to stay didactic"; a set gives a "done", retrieval right after exposure and the web's "A few minutes a day is enough."; one mechanism replaces [F]'s two. Three sizes and a focus change were over-built (15.5). |
| D-04 | **Discovery: the phone broadcasts one probe on a fixed unprivileged UDP port and the computer answers by unicast; plus manual address; no mDNS, no subnet sweep.** | [L] UDP + manual (+ optional sweep); [R] UDP probe + manual; [F] UDP beacon + manual, no sweep → **[R]'s probe with [F]'s "no sweep"** (revision 2 fixes the direction) | mDNS is blocked by the development computer's firewall while high ports are open [AD 1.2]; a sweep looks like a port scan; probe-and-reply means the phone never receives broadcast or multicast, so it needs no multicast lock or extra permission. |
| D-05 | **"Process from the phone" = shared links and local files**; files are copied on confirm; local videos may be sent as audio only (extracted on the phone). | [L] links + files, audio extraction Later; [R], [F] links + files with extraction in v1 → **[R]/[F]** | The brief: "processes the videos the user wants to process from the phone" plus the share feature; temporary read grants [AD 2.4]; phone videos can be hundreds of MB and the upload had no resume [AD 2.4]. |
| D-06 | **Phone access is opt-in; pairing by a 6-digit code shown in the computer's terminal (5 min, single use, invalidated after 5 wrong tries); per-device revocable credential; device list and revoke in the CLI; plain HTTP in v1.** | [L] 6 digits, 1-min lock; [R] 8 characters, 10 min, invalidation; [F] 6 digits, 5 min, invalidation → **[F]** (with [R]'s invalidation) | Numeric codes are easy with TalkBack and a numeric keypad; invalidation is stronger than a lock; no auth exists today [BE 15]; the public repo forbids embedded secrets [Brief]. A per-device credential costs the same exchange as one shared secret and allows revoking one phone. |
| D-07 | **The phone cannot delete finished analyses on the computer in v1**; it can remove only its own failed or cancelled jobs. | [L] yes, online and confirmed; [R], [F] no → **[R]/[F]** | Destructive, not requested by the brief, and a LAN surface today [BE 15.4]; the computer is the archive. Asked as Q-2. |
| D-08 | **The phone sends the computer's default analysis options**; only "Audio only" and dialogue separation are exposed (separation only in the in-app Add screen). | [L] audio only; [R] audio only + separation + speech model; [F] audio only + separation → **[F]** | All analyses are TIMIT-61 [AD 2.3]; engine and model choices are power-user settings [WF 9.3]; separation is a meaningful choice for music-heavy clips (web copy). |
| D-09 | **Didactic static content comes from the web's TypeScript through a read-only build-time extractor, bundled, with a drift test.** | all three agree on one source; revision 2 fixes the mechanism | TypeScript-only today; copying creates drift [AD 2.6]; Learn must work before pairing and offline; `web/` must not be edited (D-35). |
| D-10 | **Learner progress stays on the phone in v1** (backed up through the allowlist). | all three agree | The brief's sync covers content and queued requests; no server home exists [AD 2.7]. Asked as Q-3. |
| D-11 | **Video on the phone: the original when the phone can play it (1080p or less, decodable); a phone video (H.264 ≤ 720p) made by the computer only on request otherwise. Compact clip data, compressed audio and posters are renditions with fallbacks (v1-complete).** | [L] audio and data v1, video SHOULD with original as fallback; [R] all v1; [F] original when decodable, rendition otherwise → revision 1 **[R]**; revision 2 **[F]** | The 720p AV1 originals are 10–15 MB while an H.264 rendition would be 17–25 MB plus server CPU; Android ships a software AV1 decoder that is likely fine at 720p and doubtful at 4K [AD 2.2]; transcoding only what the phone cannot play keeps the one 4K clip working without converting the rest. S-23 makes new downloads H.264 anyway. |
| D-12 | **The feed plays only files on the phone; no streaming from the computer in v1.** | [L] streams when online with fallback; [R] local only; [F] streaming allowed (1.5 s target) → **[R]** | Offline-first, no stalls when the computer sleeps, one playback path; renditions make local copies small (7.10). |
| D-13 | **The mouth articulator ships in v1-complete** (lesson step 4, Learn demos, IPA chart), after v1-core, with a text fallback if cut. | all three agree it ships; revision 2 tiers it | It is a step of the web lesson and the core of Learn and the IPA chart; it is the largest port item (about 2,450 lines plus tests [AD 2.5]), so it must not block the brief's core. |
| D-14 | **No horizontal swipe gestures in the feed** (the clip page opens from the clip line). | [F] swipe left → clip page; [R] no horizontal gestures → **[R]** | Conflicts with the system back gesture at the edges and with TalkBack and switch users; the clip line gives the same path. |
| D-15 | **Trust the server id, not the address; never read the Wi-Fi name.** (Revision 3: the server id counts only with the computer's proof of identity at that address, D-37.) | [R] explicit, [L], [F] agree | DHCP changes the address [AD 4.7]; the Wi-Fi name needs location permission; reachability is the brief's own condition ("recognizes that the server is up" [Brief]). |
| D-16 | **Corpus views (Learn counts and examples, Practice pool and variants, Insights, change modes) are computed on the phone** from downloaded clips. | all three agree | Offline-first; the corpus endpoints are server-only [WF 9.2]; pooling `{count, of}` is exact. |
| D-17 | **Every request is stored on the phone first and sent with an idempotency key; requests never expire; failed downloads are not retried automatically; interrupted jobs (running or waiting in line) are re-run once by the computer, in their original order.** | [L], [F] manual retry after interruption; [R] one automatic resubmission → **[R]**, made implementable by S-22 | The brief's queue promise; duplicates are created today [BE 16.7]; 403s repeat and would occupy the single worker [BE 9]; a restart fails every queued and running job (`phonotrainer/jobs.py:250-252`), which is the computer's fault, not the link's. |
| D-18 | **minSdk 29; compileSdk 37; targetSdk 36; no local-network permission flow.** | [L] minSdk 29, target open; [R] target 36; [F] target 37 with the flow → revision 1 **[F]**; revision 2 **[R]** | The test phone runs API 36; an app targeting 36 keeps implicit local-network access even on Android 17 [TC 6], so targeting 37 only adds a permission flow, its denial states and emulator tests for a device nobody has. compileSdk 37 keeps the Artemi toolchain. |
| D-19 | **Storage: study packs always; videos automatic within a 2 GB default budget** for downloaded clips; cleanup removes least recently watched videos first, never kept clips, today's set or clips opened in the last 24 h; a free-space floor of 1 GB or 10 % of storage, whichever is smaller. | [L] 2 GB video budget; [R] 3 GB total; [F] 2 GB total → **[F]** with [R]'s free-space rules and [L]'s protections | About 65 to 140 four-minute clips with video fit in 2 GB (7.10); the reference data set has 10; adjustable in Settings. |
| D-20 | **App name "PhonoTrainer", application id `com.mapineda48.phonotrainer`.** | [L], [AR 10.2] | Project name and the convention suggested by the Artemi study. |
| D-21 | **UI vocabulary**: "your computer", "clip", "short", "phrase", "change", "request". | [L], [F] "your computer"; [R] "your PC"; [L], [R] "Requests", [F] "Processing" → **[L]/[F]** for "computer", **[L]/[R]** for "Requests" | Web copy says "The analysis runs on your computer" (tour step 3); the brief itself speaks of a "request" being queued [Brief]. |
| D-22 | **Text size follows the system font size** (no in-app size control); IPA scales with it. | [L] adds "Larger IPA"; [R], [F] system only → **[R]/[F]** | One control, already system-wide; A-06 makes IPA scale. |
| D-23 | **Record yourself offline = comparison by ear; pitch comparison only when connected; takes never queued or stored.** | all three agree | `/compare` is server-side; takes are deliberately never kept [BE 10]. |
| D-24 | **Default listening track "Original mix"; "Dialogue only" one tap away.** | [L] mix (web default); [F] mix; [R] lists dialogue first → **[L]/[F]** | Web default [WF 3.4]; the mix is real-world listening. |
| D-25 | **One spotlight per short, chosen by a fixed deterministic order (saved word, mode's change, safe before recognize-only, clearest, earliest); Listen first is an opt-in setting (off); a short replays once at its end, then stops.** | [L] focus word, listen first, stop at the end; [F] spotlight, captions shown, replay once; [R] neither → **[F]** spotlight, captions and replay; **[L]**'s listen first as an option | Noticing needs one target; a 7-level weighted priority and listen-first by default had no web precedent and changed the captioned shorts the owner described (15.5). |
| D-26 | **Phone access, once turned on, stays on across restarts until turned off** (revision 3: on the networks the operator approved, D-42). | [F] explicit at each start; [L], [R] a flag → **owner default for Q-5** | The brief expects the phone to work when it "recognizes that the server is up" [Brief]; it remains opt-in, visible at every start and revocable. |
| D-27 | **Saved shorts come back in later sets (up to 2 per set, when not watched for 3 days); spaced-repetition intervals are Later (Q-6).** | [L] intervals 1/3/7/14/30 with "Learned" → revision 1 **[L]**; revision 2 simplified | "Meet it again" survives with one rule and no new prompts; a scheduling system with a self-check has no web precedent [WF 3.12]. |
| D-28 | **Playback stops when the app leaves the foreground in v1.** | [F] → **[F]** | The feed is visual; battery; background listening is Later. |
| D-29 | **One paired computer at a time; to change, "Forget this computer" first; clips keep a tag of their computer and library and are never deleted by another computer's or library's listing.** | [L], [R] keep old clips; [F] removes them → **[L]/[R]**; revision 2 removed the multi-computer flows | Non-destructive; one owner with one computer, so switching flows were over-built (15.5). |
| D-30 | **Two tiers: v1-core (every product requirement of the brief) and v1-complete (the remaining web devices and efficiency work), with a cut order and a fallback for each v1-complete item.** | revision 2 (both reviews) | "Don't improvise too much" and "Take your time" [Brief]: everything is still built, but the large or unproven items cannot block the brief's core or device QA. |
| D-31 | **"Try again" re-runs on the computer from what it stored (S-22)**, for links and files. | revision 2 | After upload the phone has no file (8.3), no retry route exists, and path creation is forbidden to phones (S-06); keeping every copy until analysis ends is the fallback. |
| D-32 | **Where ACs are verified**: automated tests and the fake computer by default; reboot, airplane mode, Wi-Fi loss and clock changes on the emulator only; on the test phone only server stop or phone access off. | revision 2 | The brief: device QA "must only interact with this app and must restore any changed device setting". |
| D-33 | **Cleartext is allowed by the platform setting and confined in code to the paired computer.** | revision 2 (both reviews) | A network security configuration cannot name a runtime address, and the address must never be committed [Brief]. |
| D-34 | **The server id belongs to the machine, kept outside the repository and the workspace; each workspace has a library id.** | revision 2 | A workspace switch must not look like "a different computer" or a mass deletion. |
| D-35 | **Nothing under `web/` is created, moved or edited**: no browser pairing page, content extracted read-only. | revision 2 (both reviews) | The orchestrator's worktree rule: the web client belongs to another agent. |
| D-36 | **Only the owner's own shares reach the real computer with a link; agents never inject one.** | revision 2 | Q-4's approval covers the owner's manual shares only, and the brief limits device QA to interacting with this app. |

### 15.2 Smaller conflicts resolved

| Topic | Positions | Resolution and why |
|---|---|---|
| Connection status on Home | [L] a chip + requests card; [R] an always-present status card; [F] a strip only when something happens | **[R]**: the card always answers "is my computer reachable?" (principle 10). |
| Online definition and probe schedule | [L] 30 s checks; [F] 30 s backing off to 60 s; [R] full definition, two-failure rule, 30 s / 2 min / 15 min schedule | **[R]**, plus a "Checking your computer…" state after the first failure and a longer timeout while the computer analyzes (revision 2), so SC-10 holds by design. |
| Sync manifest | [L] sizes + complete list in v1, tombstones SHOULD; [R] revision, checksums, tombstones, cursor; [F] revision, sizes, hashes | **[F]/[L]** in revision 2: a lean complete listing with revisions, sizes and checksums; no cursor or tombstones at this scale; [L]'s mass-deletion safeguard and different-server-id rule (7.9). |
| Study pack contents | [L], [F] both tracks always; [R] chosen track required, other later | Both tracks download automatically; "On this phone" needs only the chosen track **[R]**. |
| Upload resume | [L] Later; [R] v1; [F] SHOULD | **v1-complete** (revision 2): v1-core restarts an interrupted upload with the same idempotency key and keeps the local copy until acceptance; resumable uploads follow. |
| Files picked in the app | [F] persistable permission, no copy; [R] copy | **[R]** copy, plus [F]'s lasting permission during the copy, so an interrupted copy restarts by itself. |
| Tracking parameters in links | [L] send as shared; [R] normalize only for duplicates; [F] strip before sending | **[F]** for YouTube and X links (privacy, duplicates); other sites sent as shared, because their parameters may matter. |
| Cancel scope | [L] any job; [R], [F] own requests | **[R]/[F]**: a phone only cancels what it asked for. |
| Share sheet options | [L] audio only; [R] audio only + advanced; [F] audio only | **[F]**: two taps; separation lives in the in-app Add screen. |
| Legal line on links | [L] every time; [R] once; [F] in the Add screen | **[L]**: the web shows it on every use of the link field. |
| Questions in the feed | [L] at the end of the set; [F] every 6 shorts (6 / 12 / off) | **[L]** placement (retrieval after exposure) with **[F]**'s "off" switch. |
| Shadowing and swiping | [F] swiping disabled during shadowing; [L] swiping stops it | **[L]**: no trapped modes. |
| Phrase details (F0 chart, prominence strip) | [L] Later; [R], [F] v1 | **v1-complete** (revision 2) with a text-only fallback: a web device with data already on the phone, but not needed by the brief's core. |
| Insights | [L] folded into Progress; [R], [F] separate destination | **[R]/[F]** for web parity, in **v1-complete**; [L]'s progress indicators that need no time series live in Practice (F-17). |
| Word-variants explorer | [F] v1; [L] Later; [R] dropped | **[L]**: Later; Practice uses variants already. |
| Full-screen landscape video | [F] v1; [L] Later; [R] "does not break" | **[L]**: Later; whole-frame landscape in portrait is enough for study. |
| Daily reminder | [L] optional, off; [R], [F] none | **[R]/[F]**: Later. |
| Onboarding | [R] tour-offer banner + coach marks; [L], [F] Welcome + coach marks | **[L]/[F]**: one path, restartable. |
| Exported deep links | [L] internal or strictly validated; [R], [F] not exported | **[R]/[F]**: smaller attack surface; revision 2 also drops the internal URI scheme (5.4). |
| Link release gate | [L] YouTube, Shorts, X; [R], [F] YouTube, X | **[L]**: Shorts are the vertical content the feed suits best; revision 2: only the owner's own shares (D-36). |
| Copy for the queue button | [L] "Add to queue"; [R] "Save for later"; [F] "Add to the queue" | "Add to queue" ([L]/[F]); the queue is the brief's concept. |

### 15.3 Questions for the owner (non-blocking; the default applies if unanswered)

Status on 2026-10-10: none of Q-1 to Q-7 was answered, so every default below applies (D-52).

| Id | Question | Default if unanswered |
|---|---|---|
| Q-1 | Is plain HTTP between the phone and your computer acceptable on your home Wi-Fi for v1 (phone access is opt-in, phones are paired and revocable, cleartext is confined to your computer in code)? | Yes; encrypted transport with a certificate pinned at pairing goes to the backlog. |
| Q-2 | Should the phone be able to delete a finished analysis on your computer? | No in v1; the phone removes only its own copy and its own failed requests. |
| Q-3 | Should practice progress and saved shorts also sync with the browser client? | No in v1; they stay on the phone and in its backup. |
| Q-4 | During device QA, will you share one YouTube video, one YouTube Shorts link and one X post from your phone yourself, so the link path is tested end to end on your computer? | Yes, and only your own shares count: no agent sends a link to your computer in any other way (D-36); the result is reported as it is. |
| Q-5 | Once you turn phone access on, should it stay on across restarts of `phonotrainer ui` until you turn it off? | Yes, on the networks it was turned on in (your home Wi-Fi; another network needs `phonotrainer phone on` there, D-42); every start prints that it is on, and you can turn it off or revoke phones at any time. |
| Q-6 | Do you want saved shorts to come back on a spaced schedule (1, 3, 7, 14, 30 days, with a "Did you hear the change?" check)? | Not in v1: saved shorts return in sets after 3 days; the schedule is in the backlog. |
| Q-7 | May QA restart your phone once, to confirm that waiting requests survive a restart on the real device? | No: restarts are verified on the emulator; you may restart the phone yourself during QA if you want that check. |

### 15.4 Request (not a question)

Connect the phone and authorise USB debugging before device QA: the phone was not attached during research
[AD 1.3], so its Android version, video decoders, screen metrics and the presence of the YouTube and X apps are
still unverified.

### 15.5 Review log (revision 2)

Two reviews read revision 1: requirements coverage (C) and evidence and over-reach (E). Every blocker and high
finding is listed with what changed; medium and low findings were all applied unless the line says otherwise.

| Finding | Disposition |
|---|---|
| C-B1: the spec was written into the main checkout, against the worktree rule | Process, not content: this document is edited where the orchestrator placed it; moving it to the `android-client` worktree is the orchestrator's step. Nothing in the text depends on its location. |
| C-H1 / E-B1: pairing page and shared content would edit `web/` | Fixed: S-04 is terminal and CLI only; the browser page is Later; S-18 is a read-only build-time extractor with a drift test (9.8, D-09, D-35). |
| C-H2: traceability overclaimed; no AC ids | Fixed: every AC has an id; section 16 maps brief sentences to AC ids; process directives are marked as such; new ACs for local operation, English, licences, secrets and design richness (13.1, F-25); SC-16 restated. |
| C-H3: link path had no attempt to fix the 403; ACs relied on successful link jobs | Fixed: S-23 (format preference, JavaScript runtime, error mapping, version reporting); ACs that need a finished job use files or the fake computer; only the owner's own shares reach the real computer (8.7, D-36). Pinning the downloader was rejected: site changes need fresh releases, so "update the downloader" is the documented remedy. |
| C-H4: SC-10 contradicted the two-failure rule | Fixed: "Checking your computer…" after the first failure, re-probe after 5 s, SC-10 restated with explicit bounds, a ping rule for the job channel (7.5). |
| C-H5: device-QA ACs rebooted the phone and toggled its radios | Fixed: conventions and NFR-14 define where each AC is verified; those ACs are [emulator]; Q-7 asks about a reboot on the real phone. |
| E-B2: "a clip of ≤ 30 s is one short" contradicted its AC | Fixed: the sentence is gone, "length" is defined (phrase bounds), and the AC was re-derived by hand. |
| E-B3: "Try again" could not work for uploaded files | Fixed: S-22 re-runs from what the computer stored; fallback named in 14.4 (D-31). |
| E-H1: mandatory H.264 renditions rested on an unverified premise and cost more | Fixed: the original plays when the phone can decode it; a phone video only on request; the test phone's decoders are recorded in QA (D-11, S-12). |
| E-H2 / C-M8: v1 was everything and the cut list cut only cosmetics | Fixed: v1-core and v1-complete (14.1) and a real cut order with a fallback per item (14.4). |
| E-H3: invented didactic machinery | Mostly accepted: one set size, no focus change, no spaced schedule (Q-6), Listen first opt-in, a deterministic spotlight order without weights, no weekly trends, no focus mode, no clip-scoped practice, no auto-save of wrong answers, fewer Library filters, no charging-only downloads, no queue count limits, no URI scheme (9.7). Kept with reasons: the 4 budget choices (one setting; the brief's "downloads it" needs a bound on a phone), the "seen 12 of 15" hint (it serves navigating between videos), and "Leave this clip out of the Mix" (first in line to cut). |
| E-H4: browser-created failed jobs would sit in "Needs your attention" forever | Fixed: a collapsed read-only "Started on your computer" group with "Hide on this phone"; never counted or notified (F-20, F-03, F-04, F-23). |
| E-H5 / C-M4: process death or failures during a share-time copy | Fixed: record first, copy with progress, rules for Home, Back, process death, no space, unreadable files and a vanished copy; picked files restart their copy (7.7, 8.4.1, QT-13). |
| E-H6 / C-M1: cleartext rule not expressible | Fixed: platform setting permits cleartext, code confines it; SECURITY.md says so; X-AC6 (11.6, D-33). |
| C-M7: owner-specific facts in a public document | Mostly fixed: firewall posture generalized, phone described generically, persona no longer states the owner's health, research notes declared non-committed. Kept: aggregate measurements of the reference data set (durations, codecs, counts), because they drive design decisions and contain no titles, paths, addresses or dates. |
| E-M11: target SDK 37 added a flow for a device nobody has | Fixed: targetSdk 36 (D-18). |
| E-M10: pairing and multi-computer flows beyond the brief | Partly accepted: multi-computer flows removed ("Forget this computer" only); the per-device credential stays because it costs the same exchange as a shared secret and allows revoking one phone. |
| E-L6: SC-02 and SC-03 measured QA speed | SC-02 withdrawn; SC-03 keeps only the tap count. |

### 15.6 2026-10-10 pre-implementation decisions

Design and architecture were reviewed before implementation and addressed requests to the product track: the seven
requests of architecture 25.3, the release-plan change of work-plan 1.4 and server-contract 7, and the open items of
the cross-track consistency review that product owns or shares. Each is decided here, on the brief's authority
("don't improvise too much"). The rule applied to the architecture's requests: the architecture already behaves as
each one describes, so a request is accepted unless it changes behaviour the owner asked for. **None of the seven was
declined**: six strengthen or clarify behaviour without changing anything the owner asked for, and one (D-41) is a
platform-forced compromise on timing that keeps the brief's queue promise and is removed in v1-complete.

| Id | Decision | Source → disposition | Spec text changed | Why |
|---|---|---|---|---|
| D-37 | **An address is trusted only when it proves it is the paired computer**: it answers the phone's fresh random challenge with a proof computed from this phone's credential. The server id, which is public, never counts on its own. Wording "answers with the paired server id" replaced by "proves it is the paired computer". | architecture 25.3 request 1 → **accepted** | 4 (Online), 7.3 item 5, 7.4, 7.5 (probe, conditions 3 and 4), 11.5, S-02, D-15; F-02-AC5, F-02-AC6 reworded; F-02-AC11 new | A strict strengthening of the same rule: anything that copies the public id could otherwise receive requests and files. The owner's condition, that the phone "recognizes that the server is up" [Brief], is unchanged. Architecture 5.3 and 6.2 already do it. |
| D-38 | **The credential travels once, in the pairing answer; every later request is signed with it and cannot be replayed.** The transport risk of 11.6 is restated: the credential can be captured only by recording the pairing exchange itself. | architecture 25.3 request 2 → **accepted** | 4 (Device credential), 7.4, 11.5, 11.6, S-05 | It removes the main consequence of plain HTTP (a reusable credential captured on the Wi-Fi) at no cost to the learner. Plain HTTP itself stays (Q-1 default). Architecture 5.3 and server-contract 4.3 already do it. |
| D-39 | **Discovery conflicts are shown, never resolved by guessing**: when two answers share a name, or one server id answers from two addresses, every answer is a row, none is preselected, and a note sends the learner to the address the computer prints ("Two computers answered with the same name. If you aren't sure which one is yours, enter the address shown on your computer instead." + "Enter the address instead"). **Connection details shows "This phone on this Wi-Fi: {address}"**, which the operator compares with the terminal's "Paired: … (from {address})" line; "Copy diagnostics" carries no address and no id. | architecture 25.3 request 3, both parts (the second was optional) → **accepted** | F-02 behaviour 3 and States (new row), F-22 (Your computer); F-02-AC10, F-22-AC4 new | Names and ids are public, so a preselected row could be an impostor relaying the code. "No address is shown in the list" is kept: the manual path that already exists resolves every case, including a computer with two connections or two computers that kept the default name. The phone's own address is on screen only, never in diagnostics, logs or the repository (11.7). |
| D-40 | **Copy for the job codes `live_stream` and `too_long`** (8.6). A live stream offers "Try again" (the same link becomes a video when the stream ends); a video that is too long offers no "Try again" or "Try audio only", because the length limit does not depend on the format. | architecture 25.3 request 4 → **accepted** | 8.6 (two rows), F-20 actions table (pointer to 8.6), F-23 (the "Needs attention" line follows the failure's own title and actions); SH-AC17, SH-AC18, F-23-AC4 new | Every failure says what happened and offers a way out (principle 12, SC-14); the generic "The analysis failed" would hide a limit the operator can change. |
| D-41 | **In v1-core, a file too large to send in one background run waits for the app to be opened**, as "Waiting on this phone" (never failed) with the line "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent while the app is open."; links are not held up by it; resumable uploads (v1-complete) remove the wait. | architecture 25.3 request 5 (the hint was optional) → **accepted** | 7.7 (Uploads), F-20 (waiting reasons); F-20-AC10 new | Android stops background work after minutes and a v1-core upload restarts from zero, so such a file sent only from the background would restart forever (architecture review A-H4). The brief's promise holds: the request is kept on the phone and carried out at the next connection with the app open, and the line names the one action that sends it. |
| D-42 | **Phone access stays on across restarts on the networks the operator approved**; on any other network it is suspended (no phone request accepted, discovery silent) until approved with `phonotrainer phone on`. The phone shows a suspended computer as "isn't reachable", with the last probe result "Your computer answered, but phone access is off there or not approved for this network." and a checklist line naming the command. **The computer's default display name is "PhonoTrainer computer"**, never the host name. | architecture 25.3 request 6 → **accepted** | 2.4 (C3), 7.2, 7.5 ("Why?" and the last probe result), F-02 (Empty and Unreachable rows), 11.5, S-01, S-02, D-26, Q-5 default; F-02-AC12 new | The owner's case, the home Wi-Fi where phone access was turned on, is unchanged. A laptop that later joins a café or office Wi-Fi must not expose phone access there. The host name often carries the owner's name and would be broadcast in every discovery answer. |
| D-43 | **Links from the phone are confined, and the default limit of 1 hour is confirmed**: site-specific downloads only, live and upcoming streams refused, videos longer than the computer's link limit (default 3600 s, editable on the computer) or larger than its upload limit refused before or during the download, free space checked first. Browser-client links are unchanged. | architecture 25.3 request 7 → **accepted; default confirmed** | 8.7 (new bullet), S-23, 8.6 | The reference data set's longest clip is about 10 minutes and the guidance asks for 30–60 s of dialogue, so 1 hour refuses nothing the app is designed for; an analysis takes about as long as the clip on a single worker, so a multi-hour video or an endless stream would block every other request. The operator can raise the limit, and the failure copy says so. |
| D-44 | **Lean job-channel events ship in v1-core** with the phone channel. Cut-list item 8 is withdrawn (not renumbered, so references stay valid). | work-plan 1.4, server-contract 7 → **accepted** | S-14, 14.1 (Server row), 14.2 step 7, 14.4 item 8 | The phone channel is new, so it is lean from its first version at no extra cost; there is nothing left to cut. |
| D-45 | **Today's set card: no "What's in today's set" control.** The card itself shows what the set holds (one mark per short by its spotlight change's family, saved and done shorts marked, one per question), and TalkBack reads it in the card's sentence: "Today's set: 8 shorts with flapping, linking, h-dropping and 2 more changes; 2 saved shorts back; then 5 questions." There is no list or detail view of a set. | consistency review, open item (design) 2: a designed control with no planned target → **removed, not specified** | F-03 item 3; F-03-AC10 new | No destination or sheet in 5.1 or architecture 16.1 lists a set's contents; a list of the set's shorts has no web precedent and is not among the new devices of 9.6, while the card already shows the set. The nearest existing feature, the summary card's "What you met" (F-08), belongs after the set. |
| D-46 | **"Stop sending" holds the request until "Send now"**, with the reason "Stopped by you: it waits here until you tap Send now." | design 9.19 (held row without a reason); architecture 8.4 (`held`) → **clarified** | 7.7 (Cancellation), F-20 (waiting reasons, actions table); F-20-AC11 new | "Back to waiting" without a hold would let the sender pick the request up again within seconds, and "Stop sending" would do nothing. The architecture already holds it. |
| D-47 | **Copy the design needed and the spec did not give**: the snackbar after "Undo" on a pending cancel, "Cancel undone: “{title}” won't be cancelled."; the track lines "Playing the original mix: the dialogue track isn't on this phone yet." / "Playing the dialogue only: the original mix isn't on this phone yet." / "Playing the original mix: this clip has no dialogue-only track."; the "Words we use" page (lede, search, count, groups "Terms" and "Measures", no-match line); "Starting again on your computer" for the automatic re-run (the design's wording, adopted); "Sent" added to the states that offer "Cancel". | consistency review, open item 7 (design + product) → **specified** | F-06 (Partial), F-14 (new paragraph), F-20 (actions table, States); F-06-AC13, F-14-AC4, F-20-AC12 new | Each state existed in the spec without words. The copy follows 9.8's voice: plain, second person, and it always names a place. |
| D-48 | **Two named count units**: a change's **short count** (the shorts its change mode plays) on Home chips, in the mode picker and on Learn change cards; **occurrences** ("12 times in your clips") only on the phenomenon page. | consistency review, open item (architecture) 8; the spec's own F-03-AC7 and F-15-AC1 used different units for similar labels → **clarified** | F-03 item 8, F-06 item 3, F-15 items 1 and 3; F-15-AC1 reworded | A chip must promise the number of shorts its feed plays (F-03-AC7); the phenomenon page keeps the web's per-occurrence examples. |
| D-49 | **"Try audio only" is offered only where it can work**: after a failed copy for space, only while the file can still be read (in the open share sheet, or for a file picked in the app); a shared file whose sheet has closed reads "…then share it again and choose Audio only." with "Remove". For the job code `no_space`, "Try audio only" is for links and "Try again" for files. | consistency review, open item (architecture) 9; S-22 re-runs uploads with their own options only → **clarified** | 7.7 (no space mid-copy), F-20 actions table, 8.6 (`no_space`) | An offered action that cannot succeed breaks principle 12. A shared file's read permission ends with the sheet, and the computer cannot re-run an uploaded file as audio only. |
| D-50 | **Assets and licences**: no legacy (pre-adaptive) launcher icons, because minSdk 29 always uses adaptive icons; About lists, and X-AC3 checks, the vendored icon set as well as fonts and libraries, from their bundled licence texts. | consistency review, open items 10 and 3 (architecture; the spec shared the wording) → **accepted** | F-24, F-24-AC2, F-25 (launcher row), X-AC3 | Nothing is lost by dropping assets no supported device shows. The design vendors a third-party icon set (ISC, with MIT-licensed parts), which must be credited like the fonts, and a dependency report never lists fonts or icons. |
| D-51 | **The share sheet does not close by itself while the notification question is open**; the 2 s count starts again once it is answered. | architecture review A-M8, design 9.22; 8.4 items 8 and 9 contradicted each other → **aligned** | 8.4 item 8 | No time limit the learner cannot control (A-11). |
| D-52 | **Q-1 to Q-7 were never answered; every default applies.** | orchestrator's resumption note → **recorded** | 15.3 | Defaults were written to be safe when unanswered. |

---------------------------------------------------------------------------------------------------------------

## 16. Traceability matrix (owner's brief → this specification)

"Process" rows are directives about how the work is done; they have no product AC and the orchestrator verifies
them (SC-16). "Fact" rows are environment facts the design relies on.

| Brief sentence (translated) | Kind | Satisfied by | AC ids |
|---|---|---|---|
| "Create a folder called `android/` in this repository." | product | 14.1 (Android project row), 11.7 (`android/.gitignore` rules) | X-AC8, X-AC5 |
| "Build an Android client for PhonoTrainer." | product | sections 5 and 6 (F-01 to F-25), 14.1, NFR-01 | X-AC8, every F-nn AC |
| "Analyze the reference repository (Artemi …) and analyze the current web client (`web/`)." | process | 5.1 (web routes → phone destinations), 9.5 (every web device mapped), NFR-13 (Artemi quality bar), 11.1 (Artemi backup pattern), D-18, D-20 | — (process) |
| "Clearly it won't be 1:1 with the web client, but it won't be something improvised either." | process (spec review) | principle 14, 9.5 to 9.7 (kept, adapted, new with justification, dropped with reasons), 1.4, 15.1, 15.5 | — (process) |
| "A product agent must formalize these generic directions into a more adequate specification." | process | this document; every feature with stories, states, ACs and data (section 6) | — (process) |
| "Don't improvise too much." | process (spec review) | 9.6 (the complete list of devices without web precedent), 9.7 (what revision 2 removed), 9.8 (no invented didactic content), 14.1 tiers, 15.1 (every decision cites evidence), 15.5, 15.6 (a designed control with no planned target removed rather than specified, D-45) | — (process) |
| "Create the necessary assets so the app is pleasant to use." | product | F-25 (asset inventory and design acceptance list; no legacy launcher icons, D-50), principle 13 | F-25-AC1, F-25-AC2 |
| "The app works, in general, like TikTok or YouTube Shorts — vertical videos you swipe through —" | product | F-06, F-07, 5.3, D-01, D-03, D-25, 1.2 | F-06-AC1 to F-06-AC13, F-07-AC1 to F-07-AC11 |
| "BUT it must also have a dashboard / main panel to navigate between the videos." | product | F-03, F-04, F-05, 5.2 (all paths between dashboard and feed), mode picker in F-06, D-02, D-45 (the Today's set card shows the set), D-48 (a change chip promises its feed's length) | F-03-AC1, F-03-AC6, F-03-AC7, F-03-AC8, F-03-AC10, F-04-AC1, F-04-AC5, F-05-AC5, F-06-AC10, F-06-AC12 |
| "When the phone is on the Wi-Fi network of this computer and recognizes that the server is up, it processes the videos the user wants to process from the phone (the phone sends the work to the PhonoTrainer server that runs on this computer)." | product | 7.2 to 7.5 (approved networks, discovery, pairing, identity proof, the definition of online), 7.7 (sending), F-02, F-19, F-20, section 8, S-01 to S-09, S-22, D-05, D-15, D-37, D-39, D-42 | F-02-AC1, F-02-AC2, F-02-AC4, F-02-AC5, F-02-AC6, F-02-AC10, F-02-AC11, F-02-AC12, F-03-AC2, F-03-AC3, F-20-AC1, F-19-AC3, SH-AC3, SH-AC5, QT-2 |
| "Add everything you consider necessary for the app to stay didactic." | product | section 9, F-07 to F-18, principles 1 to 7, D-03, D-13, D-25, D-27 | F-07-AC2, F-07-AC10, F-08-AC1, F-08-AC5, F-09-AC2, F-10-AC1 to F-10-AC5, F-12-AC1, F-14-AC1, F-14-AC3, F-14-AC4, F-15-AC1 to F-15-AC3, F-16-AC2, F-17-AC1 to F-17-AC3 (v1-complete: F-11, F-13, F-18 ACs) |
| "Sync between app and server: if the server has content the app does not have, the app downloads it." | product | 7.6, 7.8, 7.10, F-21, S-11, S-12, D-11, D-19. Disclosed limits: the study pack of every clip is downloaded unless the learner removed the clip or the budget is full; the video layer is skipped when "Download videos automatically" is off or the budget is full; an original the phone cannot play is replaced by a phone video (or audio only if the computer cannot make one); the Library shows each case | F-21-AC1 to F-21-AC8, QT-9, QT-10, QT-14 |
| "If the app is not on the server's Wi-Fi or cannot connect, a request to process audio or video is queued ON THE DEVICE until the connection happens, then carried out." | product | 7.7 (device queue, including large files that wait for the app in v1-core and "Stop sending"), 7.12 (torture test), 8.5, F-20, S-09, S-10, D-17, D-41, D-46 | QT-1 to QT-8, QT-12, QT-13, SH-AC4, SH-AC5, SH-AC6, F-20-AC1, F-20-AC5, F-20-AC10, F-20-AC11, F-20-AC12 |
| "It is an entirely local, non-commercial project." | product | 1.4, 3.2, 11.2 (no third-party services), NFR-05, 7.2 (phone access only on approved networks, D-42) | X-AC1 |
| "Do not leave sensitive data in this layer: the repository is PUBLIC (no IPs, tokens, serial numbers, home paths, keystores, personal data in committed files)." | product | 11.7, 11.1, 11.6 (signed requests, D-38), 7.2 (default name "PhonoTrainer computer", D-42), 5.4 (ids only), F-22 ("Copy diagnostics" without addresses or ids, D-39), SC-15, 2.1 and 2.5 (no personal data in this document) | X-AC4, X-AC5, X-AC6, F-02-AC9, F-22-AC4 |
| "From YouTube or Twitter/X, when tapping 'Share' on a link, in the share sheet where apps like WhatsApp appear, THIS app must appear too." | product | 8.1, 8.2, F-19, F-25 (share-sheet icon) | SH-AC1, SH-AC2, SH-AC11, F-25-AC1 |
| "If online (server reachable) it processes; if not, it queues." | product | 8.4 (connection line and buttons), 8.5, 8.6, 8.7. Satisfied in full for local files and for queueing and sending links; whether a link's analysis then succeeds is conditional on the computer's downloader, hardened by S-23 and reported from the owner's own shares (S-20). Disclosed limit: links from the phone to live streams or to videos longer than the computer's link limit (1 hour by default, editable on the computer) are refused with their own copy (D-40, D-43) | SH-AC3, SH-AC4, SH-AC5, SH-AC9, SH-AC12, SH-AC16, SH-AC17, SH-AC18, F-20-AC3, F-20-AC4, F-23-AC4 |
| "Design must be delegated to a dedicated design agent." | process | conventions (no visual design here), F-25 (asset inventory as content), 9.8 (mark semantics fixed, visuals open) | — (process; F-25-AC2 is the design agent's sign-off) |
| "It is very important that it is NOT minimalist, and that it is robust." | product | principle 13, F-25 design acceptance list, every States table (revision 3 gives words to the states that had none, D-47), 7.7 to 7.12, NFR-07, NFR-08 | F-25-AC2, F-20-AC7, F-06-AC13, QT-1 to QT-14, X-AC7 |
| "Take your time; make a work plan; delegate everything possible." | process | 14.1 (tiers), 14.2 (build order), 14.3 (gates), 14.4 (cut order; lean channel events moved to v1-core, D-44), section 12 (server work separable), 15.3 (non-blocking questions; unanswered, defaults applied, D-52), 15.6 (pre-implementation closure of the other tracks' requests) | — (process) |
| "When ready, the owner connects the phone to install and test (the test phone, Android 16 / API 36, attached over adb)." | product | NFR-01, NFR-14, 8.7 (gate on the device), 14.2 step 6, 14.3, 15.4 | X-AC8, X-AC10 |
| Constraint: all repository content in English, including UI strings; no translations | product | conventions, NFR-12, 11.7 (language scan) | X-AC2 |
| Constraint: GPL-3.0-or-later; dependencies and fonts recorded; OFL fonts vendored with licence text | product | NFR-11, F-24 (libraries, fonts and the vendored icon set, D-50), F-25, 11.7 | X-AC3, F-24-AC1, F-24-AC2 |
| Constraint: tests; server changes need pytest coverage | product | section 12 preamble, NFR-13, 14.3 | X-AC9 |
| Constraint: the repository already ignores media, `workspace/`, `data/`, `downloads/`, `*.db`, `.env*` | product | 11.7 (ignore-rule trap and checks) | X-AC5 |
| Orchestrator rule: never touch `web/`; work only in the `android-client` worktree | process | D-35, S-04, S-18, 9.8 | X-AC9 (web unchanged) |
| Environment: Android SDK 36 and 37 platforms, Gradle 9.8 and JDK 17 are available; the Artemi toolchain builds here | fact | NFR-01, NFR-13, D-18 | X-AC8 |
| Environment: the server binds 127.0.0.1:8000 by default | fact | 7.1, 7.2, S-01, S-21 | X-AC9 |
| Environment: the host firewall allows unprivileged TCP/UDP ports on Wi-Fi (and blocks mDNS) | fact | 2.3, 7.1, 7.3, S-03, D-04 | F-02-AC1, F-02-AC4 |
| Environment: jobs are about 20 MB; originals can be 50+ MB | fact | 2.5 (720p originals are 10–15 MB; one 4K original 65.6 MB), 7.10, S-12, D-11, D-19 | F-21-AC4, F-21-AC6 |
| Environment: device QA interacts only with this app and restores any changed setting | product | conventions (where ACs are verified), NFR-14, D-32 | X-AC10 |
