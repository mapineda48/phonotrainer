# Implementation plan — "PhonoTrainer": phonetic analyzer for native speech

> The project's original design document; kept as a record of the decisions and
> of where they came from. Reference environment: **Fedora 44**, CPU (GPU optional).
> Goal: given a video/audio file in English, produce a time-aligned timeline with:
> (1) the transcript, (2) the phones ACTUALLY pronounced, (3) the canonical pronunciation aligned in time,
> (4) a labeled diff of connected-speech phenomena (wanna, gotcha, flapping, schwa, th-stopping…),
> (5) prosody (F0, stress, contour).
>
> **Core strategy: build on what already exists.** Do not reinvent aligners or phone
> recognizers. We reuse components from existing MDD/CAPT projects (which perform this very diff, but
> in order to "correct learners") and only write the new layer: labeling of native phenomena + report.

---

## 0. Design principle (read before coding)

- Text (ASR) and real phonetics do NOT come out of the same model. The ASR normalizes, the dictionary normalizes.
  Real reductions only show up with an acoustic phone recognizer.
- 3 sources of truth aligned by time:
  1. Words + timestamps → `faster-whisper`.
  2. Canonical phones ALIGNED IN TIME → forced alignment (torchaudio / MFA), not just a dictionary.
  3. Real phones → wav2vec2 fine-tuned to phonemes (the de facto standard in MDD repos).
- The product = diff of (3) vs (2) per time window + prosody. We read the deviation as a
  *native phenomenon to be taught*, not as an error (the inverse of what MDD tools do).

## 1. Prior work to reuse (Phase 0 — study before coding)

Clone into `references/` (read-only, to extract patterns and adaptable code under a compatible license):

| Repo | What we reuse | What we do NOT |
|---|---|---|
| `Halleck45/OpenPronounce` | wav2vec2→phonemes pipeline, phoneme-to-phoneme DTW alignment, prosody extraction, CLI+web structure | Its "learner error" scoring layer |
| `openai/whisper` ecosystem: `SYSTRAN/faster-whisper`, `m-bain/whisperX` | ASR with word timestamps; whisperX additionally brings forced alignment with wav2vec2 already solved | Its diarization (we do not need it) |
| wav2vec2 MDD repos (`vocaliodmiku/wav2vec2mdd`, `rhss10/joint-apa-mdd-mtl`) | Phoneme CTC decoding recipes, phoneme-level evaluation | Fine-tuning (we use public checkpoints) |
| `Montreal Forced Aligner` (docs) | Quality reference for canonical alignment | The conda install, if torchaudio is enough |
| `yt-dlp/yt-dlp` | Getting the material: downloading the video/audio and remuxing with ffmpeg (`phonotrainer/download.py`) | Its post-processing (subtitles, thumbnails, playlists) |
| `sqlite3` (stdlib) | A cross-analysis index: words and phenomena queryable as a corpus (`phonotrainer/db.py`) | An ORM or a database server: the file is derived and disposable |

Pretrained models (HuggingFace, downloaded once):
- **ASR**: `faster-whisper small` (int8, CPU).
- **Real phones**: `facebook/wav2vec2-lv-60-espeak-cv-ft` (CTC → eSpeak/IPA-style phonemes).
  Lightweight fallback: `allosaurus eng2102`.
- **Canonical forced alignment**: `torchaudio.pipelines.MMS_FA` or whisperX's aligner
  > **Discarded in Phase 0, and not to be reintroduced.** On technical grounds, `MMS_FA` aligns
  > *characters*, not phonemes (see `align_canonical.py`). On legal grounds, it is
  > **CC-BY-NC 4.0**: adopting it would impose a non-commercial restriction on every
  > user of the project. What is used instead is `torchaudio.functional.forced_align`,
  > which is pure algorithm (BSD-2-Clause) and downloads no weights.
  (both pip-installable; we avoid conda/MFA unless quality demands it).
- **Phonetic distance for the diff**: `panphon` (articulatory feature vectors → linguistically
  grounded substitution costs, instead of an ad-hoc matrix).

Phase 0 deliverable: `references/NOTES.md` containing: the exact OpenPronounce function that does DTW,
the output format of the espeak-phoneme model, and the torchaudio-FA vs MFA decision (test both on a clip).

## 2. Environment setup (Fedora 44)

```bash
sudo dnf install -y ffmpeg python3.11 python3.11-devel gcc gcc-c++ make git espeak-ng
# RPM Fusion if ffmpeg is missing:
# sudo dnf install -y https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm

mkdir -p ~/phonotrainer && cd ~/phonotrainer
python3.11 -m venv .venv && source .venv/bin/activate
pip install --upgrade pip wheel
```

`requirements.txt`:

```
faster-whisper>=1.0
transformers>=4.40
torch --index-url https://download.pytorch.org/whl/cpu
torchaudio --index-url https://download.pytorch.org/whl/cpu
phonemizer>=3.2        # espeak-ng backend, required by the wav2vec2-espeak model
g2p-en>=2.1
panphon>=0.20
praat-parselmouth>=0.4
numpy
soundfile
click
rich
pytest
```

Notes:
- `phonemizer` needs the system `espeak-ng` (already in the dnf line above).
- A `scripts/download_models.py` script to download and cache: whisper-small, wav2vec2-espeak, cmudict/NLTK.
- If the CPU turns out to be slow with wav2vec2-lv-60: try the base checkpoint or Allosaurus as a fallback (`--phone-engine` flag).

## 3. Project structure

```
phonotrainer/
├── PLAN.md
├── requirements.txt
├── references/            # Phase 0 cloned repos + NOTES.md (gitignored)
├── scripts/download_models.py
├── phonotrainer/
│   ├── cli.py             # phonotrainer analyze <media> -o out/ [--phone-engine wav2vec2|allosaurus]
│   ├── audio.py           # ffmpeg → WAV 16kHz mono
│   ├── asr.py             # faster-whisper: words + timestamps
│   ├── phones_real.py     # wav2vec2-espeak CTC → real phones + timestamps (CTC offsets)
│   ├── align_canonical.py # forced alignment: canonical phonemes with times (torchaudio MMS_FA)
│   ├── canonical.py       # g2p_en/CMUdict as a backup and for OOV; ARPAbet→IPA
│   ├── diff.py            # real-vs-canonical alignment with panphon costs + labeling
│   ├── phenomena.py       # rules and lexical table (wanna, gonna, gotcha…)
│   ├── prosody.py         # parselmouth: F0, stress, contour (adapt the OpenPronounce approach)
│   ├── report.py          # analysis.json + static report.html
│   └── ipa_maps.py        # espeak↔IPA↔ARPAbet
├── tests/                 # our own fixtures (NO copyrighted audio in the repo)
└── out/
```

## 4. Phases

### Phase 0 — Surveying the ground
Clone the repos in the table, run OpenPronounce's notebook/CLI on a clip of our own,
document in `references/NOTES.md` which functions can be adapted. Decide torchaudio-FA vs MFA.

### Phase 1 — Skeleton + audio
`audio.py` (ffmpeg `-ac 1 -ar 16000`), `cli.py` with click. Success: media → `out/audio.wav`.

### Phase 2 — ASR
faster-whisper, `word_timestamps=True`, `vad_filter=True`. Save `out/transcript.json` WITHOUT
post-processing (if Whisper writes "gonna", it stays).

### Phase 3 — Time-aligned canonical
`align_canonical.py` with `torchaudio.pipelines.MMS_FA` (or whisperX's wav2vec2 aligner): for each
ASR word, canonical phonemes with real [start, end]. `canonical.py` (g2p_en) only for OOV
and to display the dictionary form. Success: a word → canonical phonemes with times table.

### Phase 4 — Real phones
`phones_real.py`: wav2vec2-espeak via transformers with `output_char_offsets=True` for per-phone
timestamps; map the espeak→IPA alphabet in `ipa_maps.py`. A flag for Allosaurus as an alternative engine.
Success: for a relaxed "does that", see something like `d ə d ə` / `d ə z ð ə`.

### Phase 5 — Diff + labeling
- Needleman-Wunsch alignment between the real and canonical sequences **inside each word window**
  (we already have both with times, so the problem becomes local and robust).
- Substitution costs = `panphon` feature distance (vowel↔ə cheap, ð↔d cheap, p↔s expensive).
- `phenomena.py` labels on top of the operations:

| Label | Rule | Example |
|---|---|---|
| vowel_reduction | full vowel→ə/ɪ when unstressed | does→dəz |
| t_deletion / glottalization | final t elided / t→ʔ | that→ðæ, button→bʌʔn̩ |
| th_stopping | ð→d, θ→t | that→dat |
| flapping | intervocalic t,d→ɾ | water→wɔɾɚ |
| palatalization | t+j→tʃ, d+j→dʒ, s+j→ʃ, z+j→ʒ across a boundary | got you→gotcha |
| elision_syllable | omitted syllable | probably→prɒbli |
| linking | resyllabified consonant | does‿it |
| h_dropping | unstressed h omitted | tell 'im |
| contraction_lex | direct lexical table | wanna, gonna, gotta, hafta, whaddya, didja, lemme, gimme, kinda, sorta, outta, shoulda… |

- Two routes for `contraction_lex`: (a) Whisper's text already contains the reduced form, or
  (b) the text says "want to" but the real phones have neither /t/ nor /u/ → it is wanna all the same.
- TDD: synthetic tests per rule BEFORE implementing.

### Phase 6 — Prosody
`prosody.py` with parselmouth (adapting OpenPronounce's prosody approach): F0 every 10 ms,
mean/range per segment, stressed word (peak of F0+intensity), rising/falling final contour.

### Phase 7 — Report
`out/analysis.json` (schema below) + a self-contained `out/report.html`: transcript colored by
phenomenon, canonical vs real tooltip, F0 mini-SVG, legend.

```json
{
  "segments": [{
    "start": 12.3, "end": 15.1, "text": "does that work for you",
    "f0_stats": {"mean": 118, "range": 62, "final_contour": "rising"},
    "emphasis_word_idx": 2,
    "words": [{
      "word": "does", "start": 12.30, "end": 12.44,
      "canonical_ipa": "dʌz", "canonical_aligned": [["d",12.30,12.34],["ʌ",12.34,12.40],["z",12.40,12.44]],
      "realized_ipa": "dəz",
      "phenomena": ["vowel_reduction"], "boundary_link_next": true
    }]
  }],
  "summary": {"phenomena_counts": {"vowel_reduction": 41, "flapping": 12}}
}
```

## 5. Testing and validation
- pytest; fixture = a short clip recorded by the user or TTS (do NOT commit copyrighted audio;
  episodes are processed locally only).
- Tests: complete espeak↔IPA↔ARPAbet mappings, every phenomena rule with synthetic sequences,
  alignment with phones at word boundaries.
- Manual validation: 2–3 min of an episode, checking 20 random words against the ear.

## 6. Risks and mitigations
- **Background music/effects** degrade the phone recognizer → Whisper's VAD first; optional
  phase 8 with `demucs` to separate the voice (heavy on CPU, only if needed).
- **wav2vec2-lv-60 slow on CPU** → base checkpoint or Allosaurus (`--phone-engine`), or process
  in overnight batches.
- **The espeak-phoneme model emits the espeak alphabet, not pure IPA** → a mapping table in ipa_maps.py
  with a test covering the model's full vocabulary.
- **Whisper normalizes reductions** → the two lexical routes of Phase 5.
- **OOV (proper nouns)** → g2p_en predicts them; mark `oov: true`.

## 7. Order of work for Claude Code
1. Phase 0 in full (clone, run OpenPronounce on a clip, NOTES.md with the decisions).
2. Phases 1+2 in a single session; verify with a WAV of our own.
3. Phases 3 and 4 conceptually in parallel; checkpoint: a word | canonical(t) | real(t) table.
4. Phase 5 with strict TDD.
5. Phases 6+7. Optional: demucs and a folder batch mode.
</content>
