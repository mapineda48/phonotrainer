# Third-party notices — PhonoTrainer

PhonoTrainer is distributed under **GPL-3.0-or-later** (see [`LICENSE`](LICENSE)).
This file credits the **direct dependencies and the relevant transitive ones**
— those that impose some obligation or that constrain the license — together with
the reference projects and the models that get downloaded. It is not an
exhaustive inventory of the environment: importing the full pipeline loads on the
order of 60 distributions, almost all of them permissive and with no obligation
beyond existing.

**This repository redistributes no third-party code, weights or corpora.**
Everything listed below is installed with `pip`/`npm` or downloaded on the first
analysis into the user's cache (`~/.cache/huggingface`, `~/nltk_data`). That is
why the obligation here is to *inform*, not to include the license texts — except
for those of the web interface bundle, which does ship compiled (see §2).

---

## 1. Why GPL-3.0-or-later

It is not a preference: it is the only license consistent with what the program
links on its main path.

| Dependency | License | Where it comes in |
|---|---|---|
| `phonemizer` 3.3.0 | **GPL-3.0-or-later** | The model's tokenizer (`Wav2Vec2PhonemeCTCTokenizer`, `phonemizer_backend="espeak"`) phonemizes **every canonical word** in `align_canonical.py`. It appears in none of our `import` statements, but it is always loaded. |
| `praat-parselmouth` 0.4.7 | **GPL-3.0-or-later** | A direct `import parselmouth` in `prosody.py`: F0, intensity and contour. |
| `espeak-ng` (system) | GPL-3.0-only AND GPL-3.0-or-later AND Apache-2.0 AND BSD-2-Clause AND Unicode-DFS-2016 AND CC-BY-SA-3.0 | `phonemizer` loads it with `dlopen`. A system binary; it is not redistributed. |
| `av` (PyAV) 18.0.0 → **libx264**, **libx265** | GPL-2.0-or-later (the codecs); LGPL-3.0-or-later (the embedded FFmpeg libs) | `faster_whisper/audio.py` does `import av` when the module loads, so a plain `from faster_whisper import WhisperModel` is enough to pull into the process the FFmpeg packaged inside PyAV's *wheel*, `libx264-*.so` and `libx265-*.so` included. |

All three chains are loaded **in the same process**, so the combined work that is
distributed is GPL-3.0. A permissive license (MIT/Apache) would be misleading:
nobody could redistribute the result under those terms.
GPL-2.0-or-later and LGPL-3.0-or-later are compatible with GPL-3.0-or-later
(thanks to the "or later" clause), so there is no conflict, only an obligation.

> That FFmpeg is **not** the `ffmpeg` of §5. That one is a separate process — mere
> aggregation; this one travels inside a PyPI *wheel* and is linked in-process.
> The distinction is precisely what decides whether there is a combined work.

Making PhonoTrainer permissive would require neutralizing **three** routes, not two:
prosody (replacing parselmouth), audio decoding (avoiding the `av` that
faster-whisper drags in) and canonical phonemization. This last one **is not a
deletion but a substitution**: the model's tokenizer works with
`do_phonemize=False` if it is handed ready-made IPA phonemes, so a non-copyleft
G2P would be needed (`g2p_en` + CMUdict is already in the repo). The price is not
cosmetic: espeak's canonical form and CMUdict's differ on close to half of the
common words (vowel length `uː`/`u`, rhotic units — espeak gives *for* = `f ɔːɹ`,
a single token; CMUdict gives `f ɔ ɹ`, two of them), and the acoustic model was
trained on espeak labels: splitting in two what it emits as one produces spurious
insertions and deletions, and with them invented phenomena. It is a redesign with
recalibration and ~60 tests behind it, not a metadata change.

**Compatibility verified.** `distance` 0.1.3 (a dependency declared by
`g2p-en`) **is read conservatively as GPL-2.0-only** — the author attaches the
GPL-2 text without choosing a version; its §9 would allow one to argue that the
licensee may pick any version — and GPL-2.0-only would indeed be incompatible with
GPL-3.0. It is an idle debate: **nobody imports it**, neither `g2p_en` nor any other
package in the environment (`'distance' in sys.modules` is `False` after using `G2p()`).
Since it is never combined with the program, it is mere aggregation. If something ever
starts importing it, this decision has to be redone.

We also reviewed what would have been fatal and is not: the `libsndfile` embedded
in the `soundfile` *wheel* is **LGPL-2.1-or-later** (its headers grant "or, at
your option, any later version"); had it been LGPL-2.1-*only* it would be
incompatible with GPL-3.0. Nothing LGPL-2.1-only or GPL-2.0-only is linked in
anywhere.

---

## 2. Web interface — this one is redistributed compiled

`web/dist/` is not version-controlled, but the backend serves it, so the bundle is
distributed to whoever deploys the application. These three packages end up inside it:

| Package | Version | License | Copyright |
|---|---|---|---|
| `react` | 19.2.8 | MIT | Copyright (c) Meta Platforms, Inc. and affiliates |
| `react-dom` | 19.2.8 | MIT | Copyright (c) Meta Platforms, Inc. and affiliates |
| `scheduler` | 0.27.0 | MIT | Copyright (c) Meta Platforms, Inc. and affiliates |

The minifier strips the `@license` banners, so `web/vite.config.ts` restores the
MIT notice in the bundle header (the `phonotrainer:license-banner` plugin).
Full text: <https://github.com/facebook/react/blob/main/LICENSE>.

The rest of `node_modules` (vite, vitest, typescript, testing-library, jsdom…)
is development-only: it is neither compiled in nor distributed. `lightningcss` (MPL-2.0)
comes in as a transitive dependency of vite but is never activated
(`css.transformer` is left undefined) and contributes zero bytes to the bundle.

The IPA typefaces (`Charis SIL`, `Doulos SIL`, `Gentium Plus`, `DejaVu Sans`)
are named by family in CSS; they are **not embedded**, so they create no obligation.

---

## 3. Python dependencies (installed with pip, not redistributed)

| Package | Version | License | Rights holder |
|---|---|---|---|
| faster-whisper | 1.2.1 | MIT | Copyright (c) 2023 SYSTRAN |
| CTranslate2 | 4.8.1 | MIT | OpenNMT |
| `av` (PyAV) | 18.0.0 | BSD-3-Clause (the wrapper) — its *wheel* embeds FFmpeg (LGPL-3.0-or-later), **libx264** and **libx265** (GPL-2.0-or-later), libmp3lame, libopus, libdav1d, libSvtAv1Enc, libopencore-amr… | Mike Boers and the PyAV contributors; the codecs, their respective projects (VideoLAN, MulticoreWare…) |
| transformers | 5.14.1 | Apache-2.0 | Copyright 2018– The Hugging Face team |
| torch | 2.13.0 | Apache-2.0 AND Apache-2.0 WITH LLVM-exception AND BSD-2-Clause AND BSD-3-Clause AND BSL-1.0 AND MIT | Meta Platforms and many others (Idiap / Ronan Collobert, DeepMind / Koray Kavukcuoglu…; see its `LICENSE`) |
| torchaudio | 2.11.0 | BSD-2-Clause | Copyright (c) 2017 Facebook Inc. (Soumith Chintala) |
| **phonemizer** | 3.3.0 | **GPL-3.0-or-later** | Mathieu Bernard, Hadrien Titeux (CNRS / bootphon) |
| **praat-parselmouth** | 0.4.7 | **GPL-3.0-or-later** | Yannick Jadoul — a wrapper around Praat (Boersma & Weenink) |
| g2p-en | 2.1.0 | Apache-2.0 | Kyubyong Park & Jongseok Kim |
| panphon | 0.22.2 | MIT | Copyright (c) 2015 Carnegie Mellon University (David R. Mortensen) |
| nltk | 3.10.0 | Apache-2.0 | NLTK Project |
| numpy | 2.5.1 | BSD-3-Clause | NumPy Developers |
| soundfile | 0.14.0 | BSD-3-Clause (its *wheel* embeds libsndfile 1.2.2, **LGPL-2.1-or-later**) | Copyright (c) 2013 Bastian Bechtold |
| click | 8.4.2 | BSD-3-Clause | Pallets |
| rich | 15.0.0 | MIT | Will McGugan |
| fastapi | 0.140.0 | MIT | Sebastián Ramírez |
| uvicorn | 0.51.0 | BSD-3-Clause | Encode |
| python-multipart | 0.0.32 | Apache-2.0 | Andrew Dunham |
| yt-dlp | 2026.7.4 | Unlicense | yt-dlp contributors |

Transitive dependencies that also call for a notice: `huggingface_hub`,
`safetensors` and `tokenizers` (Apache-2.0, Hugging Face), `certifi` (MPL-2.0) and
`tqdm` (MPL-2.0 AND MIT). MPL-2.0 is per-file copyleft and its §3.3 expressly
permits combination with GPL-3.0; since we modify none of its files, all that is
required is to credit them.

`praat-parselmouth` is a wrapper around **Praat**, by Paul Boersma and David
Weenink (University of Amsterdam); the wrapper itself is by Yannick Jadoul.

`panphon` asks for an academic citation: Mortensen, Littell, Bharadwaj, Goyal, Dyer,
Levin — *PanPhon: A Resource for Mapping IPA Segments to Articulatory Feature
Vectors*, COLING 2016. Its tables (`ipa_all.csv`) are the substrate of every one of
the diff's costs, so the citation is fair as well as cheap.

---

## 4. Models and linguistic data (downloaded on first use)

None of this travels in the repository.

| Resource | License | Rights holder | Use |
|---|---|---|---|
| `Systran/faster-whisper-small` | MIT | SYSTRAN (CTranslate2 conversion) | ASR with per-word timestamps |
| `openai/whisper-small` (original weights) | Apache-2.0 | OpenAI | The basis of the conversion above |
| `facebook/wav2vec2-lv-60-espeak-cv-ft` | Apache-2.0 | Meta AI — Xu, Baevski, Auli ([arXiv:2109.11680](https://arxiv.org/abs/2109.11680)) | Real phones (CTC) **and** forced alignment of the canonical, in the same pass |
| CMU Pronouncing Dictionary `cmudict.0.7a` (via NLTK) | BSD-2-Clause *with a clause of its own*, see below | Copyright (C) 1993-2008 Carnegie Mellon University. All rights reserved. | Citation form and OOV detection |
| NLTK `averaged_perceptron_tagger(_eng)` | MIT | Copyright 2013 Matthew Honnibal (NLTK redistributes it; it is not the rights holder) | POS tagging for homographs in `g2p_en` |

**Exception: one model that is redistributed — though not by us.** The
`faster-whisper` *wheel* carries `assets/silero_vad_v6.onnx` inside it (Silero VAD, MIT,
Silero Team). It is not downloaded: it travels in the package, and it is **active** — `asr.py`
passes `vad_filter=True` — so silence never reaches the ASR. It arrives via `pip`,
which means this repository still redistributes nothing; it is documented because it is the
only model in the pipeline that is not fetched from the network.

About CMUdict: its notice adds to clause 1 the sentence **"The contents of this
file are deemed to be source code"**. That sentence is the operative one: it turns
the dictionary's *data* into code for licensing purposes, so that anyone
redistributing the file must retain the notice, the conditions and the disclaimer.
Here it is not redistributed — it is downloaded with `nltk.download("cmudict")` — but if
it is ever embedded in the repo, its README must be copied in full alongside it.
(The NLTK index describes this package as "0.6 / Copyright 1998 / completely
unrestricted": that is out of date. The README that gets installed is what governs.)

**Whisper model caveats**, which its model card asks to pass on: it is not intended
for transcribing people without their consent, nor for high-stakes decisions, nor for
inferring attributes about the speaker. Whoever uses PhonoTrainer is
responsible for having the right to process the material they feed it.

**Deliberately not used:** `torchaudio.pipelines.MMS_FA` is **CC-BY-NC 4.0**
(non-commercial). It aligns characters, not phonemes, so it was discarded for technical
reasons — but adopting it later would impose a non-commercial restriction on
every user. Do not replace the aligner with it.

---

## 5. System requirements (external processes, not linked)

| Program | License | Use |
|---|---|---|
| `ffmpeg` | GPL-3.0-or-later (depending on the packaging) | Extraction and remuxing of audio/video (`audio.py`, `download.py`) |
| `espeak-ng` | GPL-3.0 and others (see §1) | Phonemization backend for `phonemizer` |

The user installs them and they are not redistributed here, but the difference
between the two matters and should not be "optimized away" later:

- **ffmpeg is invoked as a separate process** (`subprocess.run` with an argument
  list, `audio.py`). Crossing the process boundary is mere aggregation: it does not
  create a combined work. The ffmpeg on this machine is compiled with
  `--enable-gpl` and `--enable-libfdk-aac`, whose license is **not** compatible with
  the GPL — all the more reason never to link it in-process or to package a binary
  of it in a release.
- **espeak-ng is loaded with `dlopen`** inside the same process, via `ctypes`
  from `phonemizer`. That is linking, and it is part of why the result is
  GPL-3.0.

---

## 6. Reference projects — studied, **not** copied

`references/` contains clones of five repositories that were used to decide the
architecture (the analysis is in [`references/NOTES.md`](references/NOTES.md)).
**They are in `.gitignore` and are not distributed.**

Verification was done by content, not by file name: of the ~5,900 long lines of
version-controlled code, only 5 coincide with any of the ~6,400 lines of the
clones, and all five are stdlib `import`s or documented calls to declared
dependencies. No function, table or vocabulary from those repos is in
`phonotrainer/`. What was taken are **design ideas**, reimplemented with
different libraries and different parameters — not protected expression. That is why
PhonoTrainer inherits no notice obligation from them; the credit below is voluntary
and deserved.

| Project | Commit consulted | License | What it contributed |
|---|---|---|---|
| [whisperX](https://github.com/m-bain/whisperX) | `2cfd7b7` | BSD-2-Clause · Copyright (c) 2024, Max Bain | Aligning within each segment's window; `blank` = `<pad>` token; interpolating gaps and degrading without aborting |
| [OpenPronounce](https://github.com/Halleck45/OpenPronounce) | `759ab4c` | MIT · Copyright (c) 2025 Jean-François Lépine | The approach to prosody: bounded F0, interpolation over unvoiced stretches, co-scaled energy |
| [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | `ed9a06c` | MIT · Copyright (c) 2023 SYSTRAN | Besides being a dependency: the `word_timestamps` / `vad_filter` defaults |
| [joint-apa-mdd-mtl](https://github.com/rhss10/joint-apa-mdd-mtl) | `5fbc315` | MIT · Copyright 2023 Hyungshin Ryu | The phoneme CTC decoding recipe and phoneme-level evaluation |
| [wav2vec2mdd](https://github.com/vocaliodmiku/wav2vec2mdd) | `760ccca` | **NO LICENSE** (all rights reserved) | Reading only: the three-way MDD evaluation protocol |

> ⚠️ **wav2vec2mdd has no license** — neither now nor at any point in its
> history. It was checked that nothing was copied from it (not `phone39.table`, not
> `dict.phn.txt`, not a single one of its identifiers). Its data also derives from
> L2-ARCTIC and TIMIT, with their own corpus restrictions. **It is reading
> material: never copy code, tables or data from there into `phonotrainer/`.**

`references/NOTES.md` quotes short fragments from these repos (about 5 lines in
total, the longest ~120 characters) for the purposes of critical commentary and
identification. Each repository is governed by its own license.
</content>
