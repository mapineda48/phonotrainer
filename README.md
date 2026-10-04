# PhonoTrainer

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](LICENSE)

Phonetic analyzer for native English speech. Given a **video or audio** file, it
separates the dialogue from the music, then produces a time-aligned view of:
- the transcript;
- the phones that were *actually* pronounced, from a wav2vec2 phone CTC trained on
  TIMIT's narrow hand transcriptions, so it hears flaps, glottal stops, unreleased
  stops and reduced vowels;
- the dictionary pronunciation, placed in time over the same acoustic pass;
- a labeled diff of *connected speech* phenomena (wanna, gotcha, flapping, glottal
  stops, schwa, t-deletion, /nt/ → [n], linking, palatalization…);
- how reduced the speech is compared with published corpus figures;
- prosody (F0, intonation units, prominence, final contour).

It runs as a CLI and as a local React web UI. See [`docs/PLAN.md`](docs/PLAN.md) for
the original plan and [`references/NOTES.md`](references/NOTES.md) for the decisions
and the evidence behind them.

The question it answers is not "are you pronouncing this correctly?" but **"what
does a native speaker actually do here, and how does that depart from the
dictionary form?"**. Mispronunciation detection (MDD) tools measure that
deviation in order to correct a learner; PhonoTrainer measures it with the sign
flipped, in order to teach the phenomenon.

## Acceptable use

PhonoTrainer is a **personal linguistic analysis** tool, meant for material you
already have the right to access.

- **No copyrighted material is distributed** with this project: no audio, no
  video, no derived transcripts. The tests use 100% synthetic fixtures (numpy
  tones and media generated with ffmpeg).
- Downloading from external platforms (`yt-dlp`) **may breach their terms of
  service**, and that responsibility belongs to whoever does it. It is just one
  more way in, not the point of the program: the normal use is to point it at
  files you already have.
- The intended workflow is **short excerpts for private study** (quotation /
  *fair use*), not archiving complete works or redistributing them.
- The Whisper model underneath explicitly asks that you not transcribe people
  **without their consent**, not use it for high-stakes decisions and not infer
  attributes about the speaker. That request is passed on as it stands.
- **Two of the default models were trained on data licensed for non-commercial
  research or academic use**:
  - the phone recognizer (`timit61`) was trained on TIMIT (LDC);
  - the dialogue separator (HTDemucs) was trained on MUSDB18-HQ.

  Their weights are Apache-2.0 and MIT respectively, and nothing of either is
  redistributed here. Whether a training-data restriction carries over to a model
  trained on it is unsettled. If that matters to you, run with
  `--phone-engine espeak --no-separate-dialogue`. The details are in
  [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## Web interface (recommended)

```bash
make setup          # once: Python + npm dependencies
make ui             # builds the SPA and opens http://127.0.0.1:8000
```

The interface is built to teach, and to be read without relying on color. A rail on
the left leads to five places: **Library**, **Learn**, **Practice**, **Insights** and
**Settings**. Its **Help** menu restarts the tour, lists the keyboard shortcuts and
explains the two phone engines. Every technical term (weak form, intonation unit,
F0, each IPA symbol, each measure) has a **"What's this?"** button next to it. The
explanation opens in a popover that works with the keyboard and on touch, never only
on hover.

It works on a laptop, a tablet or a phone, down to 320 px wide, without scrolling
sideways (only the wide IPA tables scroll inside their own box). Below 768 px the rail
becomes a **Menu** button at the top that opens the same navigation in a drawer. Below
1024 px the analysis workspace shows one pane at a time (see below).

### Library and New analysis

The **Library** (`/`) lists your analyses. Each one shows its status as an icon plus a
word (Queued, Analyzing, Ready, Error, Cancelled), and a running one also shows its
current stage ("40 % · Hear the phones · phrase 3 of 10"). You can search, sort and
filter by engine, and pick up where you left off. Each analysis has a menu to open its
report, cancel, retry a failed download, or delete. Deleting always asks first and says
what is kept: your media file is never touched. **Import results…** registers an
existing `out/`.

**New analysis** (`/new`) has three steps:
1. **Choose the clip:** a path, with a folder browser; a file you upload by dragging it
   in or picking it; or a YouTube link, with an audio-only switch.
2. **Choose how to analyze it:**
   - the phone engine, with its training-data note under each choice;
   - dialogue separation, on by default;
   - phonetic attraction (the engine's default, on or off);
   - the Whisper model.
3. **Start**, then follow the pipeline as named steps: download, extract the audio,
   separate the dialogue, transcribe, hear the phones, compare and save. Each step says
   what it does, and each stage change is announced to screen readers.

### Analysis: the transcript and the 5-step word lesson

The analysis workspace (`/analysis/<id>`) puts the player and the transcript on the
left and the lesson on the right. On a screen narrower than 1024 px the transcript, the
lesson, the summary and review become four tabs under the player, which stays at the
top while you scroll. Tapping a word plays it and shows it in a bar at the bottom, with
**Open lesson** one tap away; the **Transcript** tab goes back to the same word, and a
link to a word opens straight on its lesson.

**The transcript.** Each word that changed is underlined in the style of its family of
phenomena, and the word itself always stays in ink:

| Family | Underline | Icon |
|---|---|---|
| reduction | solid | chevrons down |
| t/d processes | dashed | scissors |
| assimilation | double | merge |
| word boundary | wavy, plus ‿ | link |
| lexical contraction | dotted, plus a "wanna ← want to" chip | shrink |
| other changes (e.g. a word that disappears) | dash-dot | dashed circle |

Color is a third cue on top of the underline and the icon, and every word's accessible
name states its changes ("that, t/d deletion"). Other marks:
- a ring is the word you are studying;
- a filled word is the one playing;
- a dashed outline means low confidence;
- bold is the most prominent word of its phrase.

You can filter the transcript by change, or keep only the changes the report calls safe
to produce, or only those to recognize. "How to read the marks" shows the whole legend.
Each phrase card can open its pitch (F0), stress and IPA details: the intonation badge
(final contour against the one its sentence type calls for, uptalk flagged), a
prominence strip (content and function words), and the full transcription.

Above the transcript you have:
- the player, with a waveform you can seek, speed 0.5× / 0.75× / 1×, and loop;
- **Listen to:** the original mix or the separated dialogue (it keeps your place);
- the synchronized video;
- **Follow playback**;
- a word search.

**The word lesson.** Selecting a word (by click, or with the keyboard) opens a lesson in
five steps. The URL follows the word, so a lesson can be linked to
(`/analysis/<id>/w/<phrase>/<word>`).
1. **Listen:** the word, the word with the next one (for boundary changes, which only
   happen across the two), or the whole phrase, at any speed.
2. **Compare:** the dictionary form `/…/` against what was said `[…]`, then phone by phone.
   Each phone is marked `=` same, `≠` changed, `–` dropped or `+` added, never by color
   alone. Picking a sound plays it and explains it, with a link to the IPA chart. With the
   default engine the dictionary form is the CMUdict citation form placed in time, with
   function words in their strong form ("to" /tu/), so a weak form shows up as a
   deviation worth learning.
3. **Why it changes:** one card per phenomenon, with its description and example.
   - It carries the advice of the connected-speech report the rules were validated
     against: **Safe to produce** (e.g. flapping, weak forms) or **Recognize only**
     (e.g. place assimilation, finna, tryna).
   - It also gives the register (universal, casual or marked) and the reason.
   - Boundary links say what links them: a consonant, an r, or a w/j glide.
   - Low-confidence words get a calm notice first.
4. **See the mouth:** the articulator below.
5. **Practice:** a shadowing loop. The clip plays slowed down, then leaves a silence of the
   same length for you to repeat it; three slow rounds, then one at full speed.
   - **Record yourself** (R) records the same word or phrase from the microphone. The local
     server measures your take exactly as it measured the clip, then deletes it; nothing is
     saved. You can play the original and yours one after the other (A / B, or Alternate),
     and see the two pitch contours overlaid: semitones from each voice's own middle, your
     take stretched evenly to the original's length.
   - The feedback is a description, not a grade: how each one ends (rise, fall or level),
     where the pitch peaks, the pitch range, the length and any pauses. It needs a secure
     page, which `http://127.0.0.1` and `http://localhost` are.

Settings → Lessons → **Compact** shows only the step titles until you open one.

The right pane has two more tabs:
- **Summary** answers **"how reduced is this speech?"**. Each measure stands next to the
  published figure it should be compared with, and its source. The measures are the words
  that depart from the dictionary, that lose a segment or a syllable (Johnson 2004), the
  schwa share, function words, weak forms, and the flapping and glottal-stop rates. The
  chart of changes in the clip doubles as a filter.
- **Review** is the human validation mode. It saves the same `review.json` as the CLI.

### The mouth, while you listen

The "See the mouth" step shows a **midsagittal section of the head** that moves
with the audio: the tongue, the jaw, the lips and the velum take the shape each
phone asks for, the point where the tract closes is marked, and the air is drawn
leaving through the mouth or — for /m/, /n/, /ŋ/ — through the nose. A symbol is
a name for a movement; this is the movement.

It is driven by the timings the analysis already produced, so it shows **what
was actually said**, coarticulation included: the shapes the tongue passes
through between two phones are not filler, they are the same blending the diff
measures. A switch shows the dictionary form instead, which is how you see the
gesture that went missing — the /t/ of *that* that never arrives. Any phone can
be picked to hold it still, including one picked in the Compare step.

Where a learner most needs it, it separates what the ear does not: /b/ closes
both lips while /v/ tucks the lower lip under the teeth; /θ/ puts the tip
between the teeth and /s/ leaves a groove behind them; /k/ closes at the back
with the body of the tongue. An unreleased [t̚] holds its seal to the end of the
phone without a burst, and the nasal flap [ɾ̃] of *winter* taps with the velum
down. Without WebGL the same drawing is rendered as plain SVG — smaller, but the
mouth still moves.

### Learn

**Learn** (`/learn`) teaches each phenomenon outside any analysis. It groups the
phenomena by family. Each card gives the description, the report's advice and how many
times the change occurs in your clips. A phenomenon's page (`/learn/<name>`) covers:
- how to hear it, with a tip for Spanish speakers where the report gives one;
- whether you can copy it, and why;
- the mouth, playing the dictionary form against what is said;
- up to eight **examples from your own clips**, each playable and with a link to its
  lesson;
- **Practice this**, which starts a practice session on that phenomenon alone.

The **IPA chart** (`/learn/ipa`) lays out every symbol the analyzer can emit:
- the consonants by place and manner, the t/d allophones, syllabic consonants;
- the vowels, diphthongs and r-colored vowels.

Picking a symbol shows the mouth, its name and example, what it means for a learner,
its phenomenon, and words from your clips that contain it: beside the chart on a wide
screen, in a sheet over it below 1280 px. On a narrow screen the tables scroll sideways
in their own box, with the row names fixed.

### Practice

**Practice** (`/practice`) is ear training built from your own corpus: sessions of ten
items, answered with the mouse or with keys 1–4.
- **Which pronunciation did you hear?** What was said, the dictionary form and other
  ways the same word was said in the corpus, all in the same notation.
- **Which change did you hear?** The right phenomenon among others from different
  families.
- **How many words did you hear?** The kind of phrase where reduced words disappear.

After each answer comes the explanation, a 0.5× replay and links to Learn and to the
word's lesson. Accuracy per phenomenon is kept in the browser only, and "focus on my
weak spots" draws more items from the lowest scores. `/practice?focus=<phenomenon>`
practices a single phenomenon.

### Insights

**Insights** (`/insights`) answers what a single analysis cannot.
- **How reduced is what you hear:** the corpus measures against the published figures,
  one phone engine at a time. The two engines are never added together, and each
  measure is turned into plain listening advice.
- **The phenomena** you have met, as a chart that filters the list below.
- **Every occurrence**, filtered by phenomenon, word and practice advice. Each one has a
  play button and an **Open** link to its word lesson. **Back to Insights** returns with
  your filters. Below 1280 px the table becomes a list, one occurrence per item.
- **How one word was said across every recording**, by searching for it.

### Settings, the tour and the keyboard

**Settings** (`/settings`), also reachable from a quick popover in the rail:
- **Appearance:** system, light or dark.
- **Colors:** *Standard*, or an optional **color-vision friendly** palette. Both palettes
  are tested for contrast and for the separation of the four families under simulated
  protanopia, deuteranopia and tritanopia. The choice previews both palettes through
  those simulations, so you can pick by eye.
- **Pattern emphasis:** thicker underlines, the family icon after every marked word, and
  hatched chart bars. It is on by default with the color-vision friendly palette.
- **Text size** up to 125 %, **Motion** (follow the system or reduce), and **Lesson
  guidance** (Full or Compact).

The choices are stored in the browser and applied before the first paint, so the page
never flashes the wrong theme.

**The tour** is offered once, as a banner you can dismiss: a one-minute walk through the
rail, the library, the transcript, the lesson, the speed control, Learn and the colors.
Steps whose element is not on the current screen are skipped. Restart it any time from
Help or Settings. Esc closes it and puts the focus back where it was.

**Keyboard.** Everything works without a mouse, and **?** lists the shortcuts inside the
app. In an analysis:

| Keys | Action |
|---|---|
| Space | play / pause |
| N, Shift+N | next / previous word (or filter or search match) |
| P, Shift+P | replay the word / the word with the next one |
| S | replay the phrase |
| L | loop |
| ← → | back / forward 2 s; on a focused word, previous / next word |
| ↑ ↓, Home, End | on a focused word: previous / next phrase, first / last word |
| Enter | on a focused word: open its lesson and play it |
| F, V | follow playback, show the video |
| 1 2 3, J K | in Review: ok / wrong / unsure, next / previous |

In Practice, 1–4 answer and Enter moves on.

For safety, the interface only opens files under `$HOME` and the working
directory; for an external drive, use `phonotrainer ui --allow-dir /mnt/videos`.

> ⚠️ **The server has no authentication whatsoever.** It listens on `127.0.0.1`
> on purpose. `--host 0.0.0.0` publishes on the local network a file browser over
> your `$HOME` that anyone can read without credentials: never expose it on a
> public interface.

## Material: from YouTube to analysis

```bash
phonotrainer download "https://youtu.be/…"                 # → downloads/
phonotrainer analyze "https://youtu.be/…" -o out/          # download and analyze in one go
phonotrainer analyze "https://youtu.be/…" --audio-only     # no video, faster
```

From the interface, pasting the URL is enough. The download (yt-dlp, video ≤720p
remuxed with ffmpeg) is just one more stage of the progress bar, and the file is
reused if it is already there: re-analyzing the same URL downloads nothing again.

## Corpus: everything you have analyzed, in one place

Every finished (or imported) analysis is indexed in SQLite. That answers what a
standalone `analysis.json` cannot:

```bash
phonotrainer corpus                        # phenomena so far, across how many videos, and how reduced it all is
phonotrainer corpus -p flapping            # every occurrence of it, most divergent first
phonotrainer corpus -w to                  # how "to" has been pronounced across the whole corpus
```

The reduction figures are pooled by word count, **one column per phone engine**. The
two engines are never added together because they measure different things:
- the narrow recognizer, measured against the citation form, finds about 72 % of
  words deviating;
- espeak, measured against its own espeak canonical, finds about 42 %.

A pool that mixes analyses labeled by different rule versions is flagged.

In the interface, **Insights** does the same, and each occurrence opens its
word lesson.

## Command-line usage

```bash
source .venv/bin/activate            # venv created with: uv venv --python 3.12 .venv
phonotrainer analyze episode.webm -o out/          # video
phonotrainer analyze interview.mp3 -o out/          # or audio only
phonotrainer ui --import-dir out/                   # open that result in the interface
```

Outputs in `out/`:
- `audio.wav`: the original mix, which is what gets played;
- `audio_dialogue.wav`: the separated dialogue that was analyzed, when separation ran;
- `transcript.json`, `canonical.json`, `phones_real.json` and `analysis.json`;
- `report.html`: self-contained, with a dark mode.

Options:
- `--phone-engine timit61|espeak` (default `timit61`): which recognizer hears the
  phones. `wav2vec2` is still accepted as the old name of `espeak`.
- `--separate-dialogue/--no-separate-dialogue` (default on).
- `--attraction/--no-attraction`: phonetic attraction toward the canonical form. The
  default is the engine's: on for `espeak`, whose multilingual output needs it, and
  off for `timit61`, where it would erase real reductions.
- `--whisper-model tiny|base|small|medium` (default `small`) and `--language en`.

Sampled human validation (prioritizes attracted / low-confidence / high-diff words):

```bash
phonotrainer review out/analysis.json -n 20 --seed 48   # → out/review.json
```

## Setup from scratch

**ffmpeg** is a system program that `pip` cannot install. It is invoked as a
separate process to extract and remux audio, and without it the pipeline does not
start. **espeak-ng** is needed only for `--phone-engine espeak`: `phonemizer` loads
it to phonemize that engine's canonical form. The default engine does not use it.

| System | Command |
|---|---|
| Fedora / RHEL | `sudo dnf install -y ffmpeg espeak-ng` |
| Debian / Ubuntu | `sudo apt install -y ffmpeg espeak-ng` |
| macOS (Homebrew) | `brew install ffmpeg espeak-ng` |
| Windows | Through WSL2, with the Debian/Ubuntu commands |

Both are free software under their own license (GPL) and are **not distributed
with this project**: you install them yourself.

```bash
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python torch torchaudio --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv/bin/python -r requirements.txt -e .
.venv/bin/python scripts/download_models.py   # ~3 GB of models (one time only)
```

`download_models.py` fetches five things:
- faster-whisper `small` (~0.5 GB);
- the TIMIT-61 phone model (~1.3 GB);
- the espeak phone model (~1.3 GB, only used with `--phone-engine espeak`);
- NLTK's CMUdict and taggers;
- HTDemucs (~80 MB).

## What the tool generates (and what is not version-controlled)

`downloads/` (downloaded videos), `data/phonotrainer.db` (the corpus),
`workspace/` (the interface's analyses) and `out*/` are in `.gitignore`: **every
clone starts clean** and everything is regenerated. The corpus is a derived
index — it can be deleted and rebuilt by re-analyzing or re-importing.

## Interface architecture

- **`phonotrainer/jobs.py`**: each analysis is a *job* that runs in a thread (one
  at a time), publishes progress and survives a restart (`workspace/<id>/job.json`).
  Every change is announced to the observers (`subscribe`), which is what the
  WebSocket feeds on. Local files are referenced without being copied; external
  `out/` directories are imported.
- **`phonotrainer/server.py`**: local REST API (FastAPI) plus serving of the SPA
  and of the audio with Range support.
  - `GET /api/jobs/{id}/audio?track=dialogue` serves the separated track, but only
    when that analysis's metadata says separation ran.
  - Analysis state travels pushed over a WebSocket (`/ws/jobs`: a snapshot on
    connect and, after every change, the whole fresh job, so applying events is
    idempotent) — the interface does not poll `/api/jobs`.
  - `/api/reference` publishes the phenomenon taxonomy, the produce/recognize advice
    per phenomenon and per reduced form, the link types, the engines with their
    notes and the report's reference figures, so the UI keeps no copy of any of
    them.
  - `/api/corpus/metrics` returns the corpus figures by engine.
  - `GET /api/jobs/{id}/contour` and `POST /api/jobs/{id}/compare` measure a span of
    the clip and a learner's recording of it (`phonotrainer/learner_audio.py`).
    - Both go through the same `prosody.ProsodyExtractor`, and the two contours are
      compared in plain words, with no score.
    - The upload is checked for size, type and length, decoded by ffmpeg in a private
      temporary directory, and deleted before the answer is sent.
  - `API_VERSION` is 5.
- **`phonotrainer/download.py`**: yt-dlp with a seam (`ydl_factory`) so that the
  tests never reach the network; progress is published like the pipeline's.
- **`phonotrainer/db.py`**: the corpus. Three tables (analyses, words,
  phenomena) and cross-analysis queries; it is only fed when an analysis finishes
  or is imported. Each analysis stores its reduction metrics. Schema upgrades run in
  one locked, idempotent transaction, and older analyses get their metrics
  backfilled on the first query.
- **`web/`**: React + TypeScript (Vite). A single `<audio>` element governs the
  app; time is published through an external store (`player/clock.ts`) so that
  the transcript is not re-rendered 60 times per second. Switching between the
  original mix and the dialogue track keeps the position. The list of analyses and
  their logs arrive through another external store (`jobs/channel.ts`), a
  WebSocket with reconnection that every component shares via `JobsProvider`.
  Controls come from React Aria Components and styling from Tailwind CSS over design
  tokens (`src/theme/tokens.css`). Hue is reserved for the four phenomenon families,
  and `src/theme/palette.test.ts` re-checks both palettes' contrast and simulated
  color-vision separation on every test run.
- **`web/src/articulation/`**: the articulator.
  - `phones.ts` gives every symbol of the closed inventory a target for eleven
    articulators.
  - `tract.ts` turns a target into outlines by casting rays from inside the jaw and
    stopping them at the roof of the tract, so "fully raised" *is* contact with the
    palate and the tongue can never pass through the head.
  - `track.ts` stretches the CTC peaks into spans and interpolates between them.
  - `scene.ts` is the only file that knows about three.js and holds no phonetics at
    all.
  - The engine is loaded with a dynamic `import()`, so nobody downloads it until the
    panel is opened.
  - A Python test (`tests/test_articulation_coverage.py`) fails if a phone is added
    to the inventory without teaching the engine to draw it.

## Architecture (summary)

- **Dialogue separation** (`separation.py`, on by default): Hybrid Transformer
  Demucs (`htdemucs`) keeps the vocals stem of the original media as
  `audio_dialogue.wav`, and that track feeds ASR, phones and prosody. Measured on a
  film score mixed under real dialogue, it halves the distance of the recognized
  phones to the clean recording; on clean speech it moves 2–3 % of them.
  - It is deterministic: demucs runs with `shifts=0`, so the same clip always gives
    the same dialogue track and the same transcript.
  - It processes 60 s chunks from a memory-mapped decode, so its working memory grows
    by ≈1 GB per hour of audio on top of the model.
  - It is skipped past 3 h.
  - Any failure (demucs missing, no weights, decode, disk) falls back to the original
    mix and says so in the log and in `meta.dialogue_separation`.
- **ASR**: faster-whisper `small` int8, `word_timestamps=True`, `vad_filter=True`.
  The text is kept without post-processing.
- **Real phones** (`phones_timit.py`, the default `timit61` engine):
  `excalibur12/wav2vec2-large-lv60_phoneme-timit_english_timit-4k`, greedy CTC with
  frame spans. Its 61 TIMIT labels are mapped into the closed IPA inventory:
  - flap, nasal flap and glottal stop (dx ɾ, nx ɾ̃, q ʔ);
  - reduced vowels (ax ə, ix ᵻ, axr ɚ) and syllabic consonants;
  - a stop closure with its burst becomes one stop, and a closure with no burst
    becomes an unreleased [t̚].
- **Why not the old engine by default**: the `espeak` engine
  (`facebook/wav2vec2-lv-60-espeak-cv-ft`) was trained on espeak's own G2P labels and
  reproduces them: "of" came out [ʌv] 69 % of the time and it emitted 2 glottal stops
  in 13.7k phones. It stays available as `--phone-engine espeak`. Its multilingual
  output is sanitized to the English inventory (`ipa_maps.normalize_espeak`), and
  acoustic confusions with no teaching value are attracted to the canonical form
  (`phones_real.attract_to_canonical`) without touching native variation.
- **Time-aligned canonical**: the CMUdict citation form (g2p_en for out-of-vocabulary
  words), spelled in the recognizer's own labels, closures included, and forced with
  `torchaudio.functional.forced_align` over the emissions of the SAME model. Silence
  is folded into the blank. Function words take their strong citation form, except
  "the". With `espeak`, the canonical is that model's espeak phonemization instead.
  Either way, real and canonical share an alphabet and an acoustic pass.
- **Diff**: Needleman-Wunsch per word window; costs = panphon features + a table
  of attested native shifts (`diff.NATIVE_SHIFTS`).
- **Phenomena** (`phenomena.py`, rules version 2):
  - **Reduction**: vowel reduction, monophthongization, syllable elision and
    function-word elision (of → ə, them → əm).
  - **t/d processes**: flapping, t/d deletion in clusters, unreleased t/d, the
    glottal stop and /nt/ → [n] or [ɾ̃].
  - **Assimilation**: th-stopping, palatalization and place assimilation (ten bucks
    → tɛm bʌks).
  - **Word boundary**: linking (typed as consonant, r, or w/j glide) and h-dropping.
  - **Lexical contractions**, detected two ways: Whisper wrote the reduced form, or
    the phones carry that form's own signature (gonna needs the /t/ of "to" gone,
    hafta needs v→f; "going to" + the/a/my… is never gonna).
  - A word nobody heard (`word_elision`) gets no other label.
  - Every label carries the report's produce/recognize advice (`report.PHENOMENON_PRACTICE`).
- **Weak-form scoring** (`variants.py`, espeak engine only): the CTC likelihood of
  the strong and weak variants of each function word over the same emissions adds the
  reductions the greedy decoding hid. It helps little: the espeak bias lives in the
  emissions themselves.
- **Reduction metrics** (`metrics.py`) are computed per analysis in
  `summary.metrics`, each next to its published reference:
  - words that deviate, that lose a segment, and that lose a syllable against
    CMUdict;
  - the schwa share and the function-word share (one closed list, `lexicon.py`);
  - weak forms;
  - flapping and glottal-stop rates where the dictionary allows them.
- **Prosody** (`prosody.py`, parselmouth):
  - F0 every 10 ms, with the range adapted per speaker turn (Hirst's rule), since
    cartoon and child voices go past 400 Hz.
  - The final contour is measured in semitones per second, so the same melody reads
    the same in any voice.
  - Sentence-sized intonation units are checked against the contour their type calls
    for, with uptalk flagged.
  - Per-word prominence (F0 × intensity × duration) comes with its content/function
    class.
  - An *approximate* rhythm measure: the nPVI of syllable intervals. CTC spans are
    peaks, not durations, so %V and ΔC are not attempted.

## Tests

```bash
make test                                   # pytest + vitest
.venv/bin/python -m pytest tests/ -q        # backend only
cd web && npm test                          # interface only
```

100% synthetic fixtures (numpy tones + media generated with ffmpeg): there is no
copyrighted audio in the repository; episodes are processed locally only. The
server tests replace the pipeline with a double (`tests/conftest.py::fake_analyze`),
so they run in seconds and without loading models. The separation tests use a
stand-in separator, and the engine tests use a synthetic TIMIT vocabulary (checked
against the cached `vocab.json` when it is there).

The articulator is judged by eye as well, which no assertion can do: `npm run dev`
serves **`/articulator.html`**, a bench with every phone of the inventory one at a
time, an IPA sequence played as a word, sliders for the raw articulators and a
contact sheet of all seventy mouths side by side — where a tongue that has drifted
shows up at a glance. It is development-only; `vite build` bundles `index.html`
and nothing else.

## License

**GPL-3.0-or-later** ([`LICENSE`](LICENSE)).

This is not an aesthetic preference: the program loads copyleft code **in the same
process**, and that fixes the license of the combined work. On every run:

- **`praat-parselmouth`** — a wrapper around Praat, loaded in `prosody.py` and
  instantiated by the pipeline every time.
- **`av` (PyAV)**, which `faster-whisper` drags in on import: its *wheel* ships
  its own FFmpeg with **libx264 and libx265** (GPL-2.0-or-later) that are linked
  in-process. Different from the system `ffmpeg`, which is invoked as a separate
  process and therefore does not count.

And with `--phone-engine espeak`, a third chain: **`phonemizer`** (+ `espeak-ng`,
loaded with `dlopen`), because that model's tokenizer phonemizes every canonical word.
The default engine builds its canonical from CMUdict and never loads it.

Publishing this as MIT or Apache would be misleading: nobody could redistribute
the result under those terms. The full breakdown — what is downloaded, what is
redistributed and what is not, the training-data provenance of the default models,
and what it would take to make it permissive — is in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## Credits

No code was copied from any reference project (verified by content, not by file
name: 5 matching lines out of ~5,900, all of them stdlib `import`s). What was
taken are ideas, and they are credited all the same:

- **[whisperX](https://github.com/m-bain/whisperX)** (BSD-2-Clause, Max Bain) —
  aligning within each segment's window, `blank` = `<pad>` token, interpolating
  gaps and degrading without aborting.
- **[OpenPronounce](https://github.com/Halleck45/OpenPronounce)** (MIT,
  Jean-François Lépine) — the approach to prosody: bounded F0, interpolation over
  unvoiced stretches, co-scaled energy.
- **[faster-whisper](https://github.com/SYSTRAN/faster-whisper)** (MIT, SYSTRAN)
  and **[joint-apa-mdd-mtl](https://github.com/rhss10/joint-apa-mdd-mtl)** (MIT,
  Hyungshin Ryu) — ASR with timestamps and the phoneme CTC decoding recipe.
- **panphon** (Mortensen et al., *COLING 2016*) — the articulatory features that
  give the diff costs their meaning.
- **ZIPA** (ACL 2025) — the observation that phone recognizers trained on G2P labels
  memorize the standard pronunciation, which is why the default engine changed.
</content>
