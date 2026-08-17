# PhonoTrainer

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](LICENSE)

Phonetic analyzer for native English speech. Given a **video or audio** file, it
produces a time-aligned view of: the transcript, the phones that were *actually*
pronounced (wav2vec2 phone CTC), the canonical pronunciation aligned in time
(forced alignment over the same acoustic pass), a labeled diff of *connected
speech* phenomena (wanna, gotcha, flapping, schwa, t-deletion, vowel reduction,
linking, palatalization…) and prosody (F0, stress, final contour). CLI plus a
local React web UI. See [`docs/PLAN.md`](docs/PLAN.md) for the full plan and
[`references/NOTES.md`](references/NOTES.md) for the Phase 0 decisions.

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

## Web interface (recommended)

```bash
make setup          # once: Python + npm dependencies
make ui             # builds the SPA and opens http://127.0.0.1:8000
```

From there: drag in a video/audio file (or select it by path, or import an
existing `out/`), follow the analysis live and explore the result with
synchronized audio. Clicking a word plays it and compares it **phone by phone
across three rows**: dictionary (citation form), time-aligned canonical, and what
was actually pronounced. That dictionary row is what exposes the processes
espeak-ng already bakes in — the canonical form of *better* is [bɛɾɚ], flap
included — and for boundary phenomena (linking, palatalization, h-dropping) both
the comparison and the playback extend into the following word, which is where
they happen.

Every phenomenon comes with an example next to its label, every segment can
expand into its full phonetic transcription (actual vs canonical), and at
boundaries the measured gap is shown in ms, which is what tells a link (~20 ms)
apart from an ordinary boundary (~60 ms).

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
phonotrainer corpus                        # how many phenomena you have so far, and across how many videos
phonotrainer corpus -p flapping            # every occurrence of it, most divergent first
phonotrainer corpus -w to                  # how "to" has been pronounced across the whole corpus
```

In the interface, the **Corpus** tab does the same, and each occurrence opens its
analysis at that exact word.

## Command-line usage

```bash
source .venv/bin/activate            # venv created with: uv venv --python 3.12 .venv
phonotrainer analyze episode.webm -o out/          # video
phonotrainer analyze interview.mp3 -o out/          # or audio only
phonotrainer ui --import-dir out/                   # open that result in the interface
```

Outputs in `out/`: `audio.wav`, `transcript.json`, `canonical.json`,
`phones_real.json`, `analysis.json` and `report.html` (self-contained, with a dark mode).

Options: `--whisper-model tiny|base|small|medium` (default `small`),
`--language en`, `--no-attraction` (disables phonetic attraction, to compare
outputs). `--phone-engine` accepts `wav2vec2` (the only one implemented, and the
default) and `allosaurus`, which is reserved as an extension point and today
fails with `NotImplementedError`.

Sampled human validation (prioritizes attracted / low-confidence / high-diff words):

```bash
phonotrainer review out/analysis.json -n 20 --seed 48   # → out/review.json
```

## Setup from scratch

Two system programs that `pip` cannot install. **ffmpeg** is invoked as a
separate process (extracting and remuxing audio); **espeak-ng** is loaded by
`phonemizer` to phonemize the canonical form. Without them the pipeline does not
start.

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
.venv/bin/python scripts/download_models.py   # ~1.8 GB of models (one time only)
```

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
  and of the audio with Range support. Analysis state travels pushed over a
  WebSocket (`/ws/jobs`: a snapshot on connect and, after every change, the whole
  fresh job, so applying events is idempotent) — the interface does not poll
  `/api/jobs`. `/api/reference` publishes the phenomenon taxonomy, so the UI does
  not keep a copy of the labels.
- **`phonotrainer/download.py`**: yt-dlp with a seam (`ydl_factory`) so that the
  tests never reach the network; progress is published like the pipeline's.
- **`phonotrainer/db.py`**: the corpus. Three tables (analyses, words,
  phenomena) and cross-analysis queries; it is only fed when an analysis finishes
  or is imported.
- **`web/`**: React + TypeScript (Vite). A single `<audio>` element governs the
  app; time is published through an external store (`player/clock.ts`) so that
  the transcript is not re-rendered 60 times per second. The list of analyses and
  their logs arrive through another external store (`jobs/channel.ts`), a
  WebSocket with reconnection that every component shares via `JobsProvider`. The
  colors are the same 4 validated categorical slots that `report.html` uses.

## Architecture (summary)

- **ASR**: faster-whisper `small` int8, `word_timestamps=True`, `vad_filter=True`.
  The text is kept without post-processing.
- **Real phones**: `facebook/wav2vec2-lv-60-espeak-cv-ft` (greedy CTC with frame
  spans → per-phone timestamps). The raw (multilingual) output is sanitized down to
  the English inventory (`ipa_maps.normalize_espeak`, raw value kept in `raw_phone`)
  and acoustic confusions with no didactic value are attracted to the canonical form
  (`phones_real.attract_to_canonical`), never once touching native variation
  (`diff.NATIVE_SHIFTS`, reductions, monophthongizations).
- **Time-aligned canonical**: `torchaudio.functional.forced_align` over the emissions
  of the SAME model, with the canonical sequence phonemized by that model's own
  tokenizer (espeak-ng). Real and canonical share an alphabet and an acoustic pass.
- **Diff**: Needleman-Wunsch per word window; costs = panphon features + a table
  of attested native shifts (`diff.NATIVE_SHIFTS`).
- **Phenomena** (`phenomena.py`): vowel reduction, t/d elision, glottalization,
  th-stopping, flapping, palatalization, syllable elision, linking, h-dropping and
  lexical contractions detected two ways (Whisper's text or phonetic evidence).
- **Prosody**: parselmouth — F0 every 10 ms, per-word stress (peak of
  F0×intensity), rising/falling final contour.

## Tests

```bash
make test                                   # pytest + vitest
.venv/bin/python -m pytest tests/ -q        # backend only
cd web && npm test                          # interface only
```

100% synthetic fixtures (numpy tones + media generated with ffmpeg): there is no
copyrighted audio in the repository; episodes are processed locally only. The
server tests replace the pipeline with a double (`tests/conftest.py::fake_analyze`),
so they run in seconds and without loading models.

## License

**GPL-3.0-or-later** ([`LICENSE`](LICENSE)).

This is not an aesthetic preference: the program's main path loads **in the same
process** three chains of copyleft code, and that fixes the license of the
combined work.

- **`phonemizer`** (+ `espeak-ng`, loaded with `dlopen`) — the model's tokenizer
  phonemizes **every canonical word** in `align_canonical.py`. It is invisible in
  the project's `import` statements, but it is always there.
- **`praat-parselmouth`** — a wrapper around Praat, loaded in `prosody.py` and
  instantiated by the pipeline every time.
- **`av` (PyAV)**, which `faster-whisper` drags in on import: its *wheel* ships
  its own FFmpeg with **libx264 and libx265** (GPL-2.0-or-later) that are linked
  in-process. Different from the system `ffmpeg`, which is invoked as a separate
  process and therefore does not count.

Publishing this as MIT or Apache would be misleading: nobody could redistribute
the result under those terms. The full breakdown — what is downloaded, what is
redistributed and what is not, and what it would take to make it permissive — is
in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

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
</content>
