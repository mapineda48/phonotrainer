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

From there: drag in a video/audio file (or select it by path, or import an
existing `out/`), follow the analysis live and explore the result with
synchronized audio. The new-analysis form picks the phone engine, and its
training-data note is shown under the selector. It also has an option to separate
the dialogue from the music, on by default, and a three-way phonetic attraction
setting: the engine's default, on or off.

Clicking a word plays it and compares it **phone by phone across three rows**:
- dictionary (the CMUdict citation form);
- the time-aligned canonical;
- what was actually pronounced.

With the default engine the canonical *is* the citation form placed in time, with
function words in their strong form ("to" /tu/, "of" /ʌv/), so a weak form shows up
as what it is: a deviation worth learning. With the `espeak` engine the canonical
already bakes in some native processes (the canonical of *better* is [bɛɾɚ], flap
included), and the dictionary row is what exposes them. For boundary phenomena
(linking, palatalization) both the comparison and the playback extend into the
following word, which is where they happen.

Every phenomenon comes with an example next to its label. It also carries the
advice of the connected-speech report the rules were validated against:
- **"safe to produce"**, e.g. flapping and weak forms;
- or **"recognize only"**, e.g. place assimilation, finna and tryna.

It also carries its register (universal, casual or marked), and the summary can
filter the transcript to either group. Boundary links say what links them: a
consonant, an r, or a w/j glide. At boundaries the measured gap is shown in ms,
which is what tells a link (~20 ms) apart from an ordinary boundary (~60 ms).
Every segment can expand into its full phonetic transcription (actual vs
canonical).

The summary answers **"how reduced is this speech?"**: each figure is shown next to
the published one it should be compared with, with its source. The figures are the
share of words that depart from the dictionary, that lose a segment and that lose a
syllable (Johnson 2004), the schwa share, function words, weak forms, and the
flapping and glottal-stop rates.

For intonation, each sentence of a segment gets a badge with its final contour
(rising, falling, flat) against the one its type calls for: statements and
wh-questions fall, yes/no questions rise. Statements of three words or more that
rise are marked as uptalk. A prominence strip shows which words carried the stress
and whether they were content or function words.

When the dialogue was separated, a **"Dialogue only"** toggle plays what the
analysis heard instead of the original mix, keeping your place.

### The mouth, while you listen

Above that comparison there is a **midsagittal section of the head** that moves
with the audio: the tongue, the jaw, the lips and the velum take the shape each
phone asks for, the point where the tract closes is marked, and the air is drawn
leaving through the mouth or — for /m/, /n/, /ŋ/ — through the nose. A symbol is
a name for a movement; this is the movement.

It is driven by the timings the analysis already produced, so it shows **what
was actually said**, coarticulation included: the shapes the tongue passes
through between two phones are not filler, they are the same blending the diff
measures. A switch shows the canonical form instead, which is how you see the
gesture that went missing — the /t/ of *that* that never arrives. Any phone can
be clicked to hold it still.

Where a learner most needs it, it separates what the ear does not: /b/ closes
both lips while /v/ tucks the lower lip under the teeth; /θ/ puts the tip
between the teeth and /s/ leaves a groove behind them; /k/ closes at the back
with the body of the tongue. An unreleased [t̚] holds its seal to the end of the
phone without a burst, and the nasal flap [ɾ̃] of *winter* taps with the velum
down. Without WebGL the same drawing is rendered as plain SVG — smaller, but the
mouth still moves.

There is also: filtering by phenomenon, word search, looping, 0.5× speed,
synchronized video and a human review mode that saves the same `review.json` as
the CLI. The keyboard shortcuts are listed inside the app (the `?` button).

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

In the interface, the **Corpus** tab does the same, and each occurrence opens its
analysis at that exact word.

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
  - `API_VERSION` is 4.
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
  WebSocket with reconnection that every component shares via `JobsProvider`. The
  colors are the same 4 validated categorical slots that `report.html` uses.
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
