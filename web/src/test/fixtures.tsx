/** Data and wrappers shared by the tests.
 *  The analysis is the same one `tests/conftest.py::mk_analysis` produces, so
 *  the tests on both halves talk about the same material. */

import { screen } from "@testing-library/react";
import { vi } from "vitest";

import { JobsChannel, type JobsSocket } from "../jobs/channel";
import { Clock } from "../player/clock";
import type { PlayerApi } from "../player/PlayerProvider";
import type { AlignedPhone, Analysis, Job, Metrics, Reference, Word } from "../types";

export const reference: Reference = {
  api_version: 5,
  families: [
    {
      key: "reduction",
      label: "Reduction",
      members: ["vowel_reduction", "monophthongization", "elision_syllable"],
      member_labels: ["vowel reduction", "monophthongization", "elided syllable"],
    },
    {
      key: "td",
      label: "t/d processes",
      members: ["flapping", "t_deletion", "glottalization"],
      member_labels: ["flapping", "t/d deletion", "glottalization"],
    },
    {
      key: "assimilation",
      label: "Assimilation",
      members: ["th_stopping", "palatalization"],
      member_labels: ["th-stopping", "palatalization"],
    },
    {
      key: "boundary",
      label: "Word boundary",
      members: ["linking", "h_dropping"],
      member_labels: ["linking", "h-dropping"],
    },
  ],
  family_of: {
    vowel_reduction: "reduction",
    monophthongization: "reduction",
    elision_syllable: "reduction",
    flapping: "td",
    t_deletion: "td",
    glottalization: "td",
    th_stopping: "assimilation",
    palatalization: "assimilation",
    linking: "boundary",
    h_dropping: "boundary",
  },
  labels: {
    vowel_reduction: "vowel reduction",
    t_deletion: "t/d deletion",
    contraction_lex: "lexical contraction",
    flapping: "flapping",
    linking: "linking",
  },
  descriptions: {
    vowel_reduction: "A full vowel reduces to schwa in an unstressed syllable. does → dəz",
    t_deletion: "A word-final /t/ or /d/ is never actually pronounced. that → ðæ",
    contraction_lex: "Lexicalized reduced form. want to → wanna",
    flapping: "/t/ or /d/ between vowels sounds like a soft r. water → wɔɾɚ",
    linking: "The final consonant links onto the following vowel. does it → dʌ‿zɪt",
  },
  practice: {
    vowel_reduction: { practice: "produce", register: "universal", why: "The engine of English rhythm." },
    flapping: { practice: "produce", register: "universal", why: "Safe and high-yield." },
    linking: { practice: "produce", register: "universal", why: "Needed for fluency." },
    t_deletion: { practice: "understand", register: "casual", why: "Casual: recognize it first." },
    contraction_lex: { practice: "produce", register: "universal", why: "Per reduced form." },
  },
  lexical_practice: {
    wanna: { practice: "produce", register: "universal", why: "Universal informal form." },
    tryna: { practice: "understand", register: "marked", why: "Marked: recognize it." },
  },
  link_types: ["consonant", "r", "glide_w", "glide_j"],
  metrics_reference: {
    deviate: {
      low: 60, high: null, display: "> 60 %", cite: "Johnson 2004",
      source: "Johnson 2004 (ViC/Buckeye)", note: "words that depart from their citation form",
    },
    segment_loss: {
      low: 25, high: 25, display: "≈ 25 %", cite: "Johnson 2004",
      source: "Johnson 2004 (ViC/Buckeye)", note: "words that lose a whole segment",
    },
    schwa_share: {
      low: 20, high: 25, display: "20–25 %", cite: "pedagogical estimates",
      source: "pedagogical estimates", note: "schwa is the most frequent vowel",
    },
  },
  metric_labels: {
    deviate: "words that differ from the citation form",
    segment_loss: "words that lose a whole segment",
    syllable_loss: "words that lose a syllable",
    schwa_share: "reduced vowels (schwa) among all vowels",
    function_words: "function words among all words",
    weak_forms: "function words in their weak form",
    flapping: "t/d flapped where flapping can happen",
    glottal_before_syllabic_n: "glottal t before a syllabic n (button)",
    glottal_prevocalic: "final t before a vowel as a glottal stop",
  },
  // The same ones the backend publishes from ipa_maps.ENGLISH_INVENTORY.
  ipa_tokens: ["ɑːɹ", "ɔːɹ", "aɪə", "aɪɚ", "oːɹ", "aɪ", "aʊ", "eɪ", "oʊ", "ɔɪ", "tʃ", "dʒ",
               "iː", "uː", "ɑː", "ɔː", "ɜː", "ɪɹ", "ʊɹ", "ɛɹ", "iə", "eə", "əl", "ju"],
  verdicts: ["ok", "wrong", "unsure"],
  options: {
    whisper_models: ["tiny", "base", "small", "medium"],
    phone_engines: ["timit61", "espeak"],
    phone_engine_notes: {
      timit61: "Narrow recognizer trained on TIMIT (LDC), licensed for non-commercial research.",
      espeak: "The original engine: it tends to hear the dictionary form.",
    },
    defaults: {
      whisper_model: "small",
      phone_engine: "timit61",
      language: "en",
      attraction: null,
      separate_dialogue: true,
    },
  },
  review: { default_n: 20, default_seed: 48, max_n: 500 },
};

function aligned(ipa: string, start: number): AlignedPhone[] {
  return ipa.split(" ").map((symbol, index) => {
    const from = Number((start + index * 0.06).toFixed(3));
    return [symbol, from, Number((from + 0.05).toFixed(3))] as AlignedPhone;
  });
}

export function makeWord(
  word: string,
  start: number,
  canonical: string,
  realized: string,
  extra: Partial<Word> = {},
): Word {
  const canonicalAligned = aligned(canonical, start);
  const realizedAligned = aligned(realized, start);
  const end = Math.max(
    ...[...canonicalAligned, ...realizedAligned].map((phone) => phone[2]),
    start + 0.05,
  );
  return {
    word,
    start,
    end,
    canonical_ipa: canonical.replaceAll(" ", ""),
    canonical_aligned: canonicalAligned,
    realized_ipa: realized.replaceAll(" ", ""),
    realized_aligned: realizedAligned,
    realized_raw_ipa: "",
    attracted_count: 0,
    diff_cost: 0,
    dict_ipa: canonical.replaceAll(" ", ""),
    oov: false,
    phenomena: [],
    low_confidence: false,
    boundary_link_next: false,
    lexical_form: null,
    alignment_fallback: false,
    ...extra,
  };
}

export const analysis: Analysis = {
  meta: {
    source: "clip.wav",
    duration: 3,
    language: "en",
    phonotrainer_version: "0.1.0",
    models: {
      asr: "faster-whisper small (int8)",
      phones: "facebook/wav2vec2-lv-60-espeak-cv-ft",
      alignment: "torchaudio forced_align",
    },
    attraction: true,
    phone_cleanup: { normalized_phones: 1, attracted_phones: 1 },
  },
  segments: [
    {
      start: 0,
      end: 1.2,
      text: "does that work",
      f0_stats: { mean: 118, range: 62, final_contour: "rising" },
      f0_track: Array.from({ length: 12 }, (_, i) => [Number((0.1 * i).toFixed(3)), 110 + i]),
      emphasis_word_idx: 2,
      words: [
        makeWord("does", 0, "d ʌ z", "d ə z", {
          phenomena: ["vowel_reduction"],
          boundary_link_next: true,
        }),
        makeWord("that", 0.4, "ð æ t", "ð æ", { phenomena: ["t_deletion"], diff_cost: 0.8 }),
        makeWord("work", 0.8, "w ɝ k", "w ɝ k"),
      ],
    },
    {
      start: 2,
      end: 3,
      text: "wanna go",
      f0_stats: { mean: 130, range: 20, final_contour: "falling" },
      f0_track: Array.from({ length: 10 }, (_, i) => [Number((2 + 0.1 * i).toFixed(3)), 130 - i]),
      emphasis_word_idx: 0,
      words: [
        makeWord("wanna", 2, "w ɑ n ə", "w ɑ n ə", {
          phenomena: ["contraction_lex"],
          lexical_form: "want to",
          attracted_count: 1,
          realized_raw_ipa: "wɑnə",
        }),
        makeWord("go", 2.6, "ɡ oʊ", "ɡ oʊ", { low_confidence: true }),
      ],
    },
  ],
  summary: {
    // Deliberately distinct counts, so the ordering tests actually prove something.
    phenomena_counts: { linking: 7, vowel_reduction: 4, t_deletion: 2, contraction_lex: 1 },
  },
};

export const job: Job = {
  id: "20260726-120000-abcd",
  source: "clip.wav",
  status: "done",
  options: { whisper_model: "small", phone_engine: "wav2vec2", attraction: true },
  created: "2026-07-26T12:00:00+00:00",
  started: "2026-07-26T12:00:01+00:00",
  finished: "2026-07-26T12:00:30+00:00",
  error: null,
  imported: false,
  result_dir: "/tmp/workspace/20260726-120000-abcd",
  percent: 100,
  last_message: "Generating report.html…",
  has_analysis: true,
  has_audio: true,
  has_report: true,
  has_review: false,
  has_media: false,
  is_video: false,
  meta: analysis.meta,
  summary: { segments: 2, words: 5, duration: 3, phenomena_counts: analysis.summary.phenomena_counts },
};

/** A word button from the transcript. Its accessible name includes the
 *  phenomena ("that, t/d deletion"), so we match on the prefix. */
export const wordButton = (word: string): HTMLElement =>
  screen.getByRole("button", { name: new RegExp(`^${word}(,|$)`) });

/** Fake player: records the calls without touching the audio DOM. */
export function fakePlayer(overrides: Partial<PlayerApi> = {}): PlayerApi {
  return {
    clock: new Clock(),
    playing: false,
    rate: 1,
    loop: false,
    span: null,
    duration: 3,
    play: vi.fn(),
    pause: vi.fn(),
    toggle: vi.fn(),
    seek: vi.fn(),
    setRate: vi.fn(),
    setLoop: vi.fn(),
    clearSpan: vi.fn(),
    ...overrides,
  };
}

/** Fake WebSocket for the jobs channel: on "open" it delivers the snapshot,
 *  and then the test pushes events with push(). Opening happens in a microtask,
 *  like the real open: by then the channel has already wired up its handlers. */
export class FakeJobsSocket implements JobsSocket {
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(
    private readonly snapshot: Job[] = [],
    { autoOpen = true }: { autoOpen?: boolean } = {},
  ) {
    if (autoOpen) queueMicrotask(() => this.open());
  }

  open(): void {
    this.onopen?.();
    this.push({ type: "snapshot", jobs: this.snapshot });
  }

  push(message: unknown): void {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  close(): void {
    /* the channel closes on dispose: there is nothing to close here */
  }
}

/** Channel with the fake socket inside. The socket is born on subscribe (like
 *  the real one), so it is requested through the getter, not before. */
export function fakeJobsChannel(jobs: Job[] = []) {
  let socket: FakeJobsSocket | null = null;
  const channel = new JobsChannel("ws://test", () => {
    socket = new FakeJobsSocket(jobs);
    return socket;
  });
  return {
    channel,
    get socket(): FakeJobsSocket {
      if (!socket) throw new Error("nobody has subscribed yet: there is no socket");
      return socket;
    },
  };
}

const ratio = (count: number, of: number) => ({
  count,
  of,
  pct: of ? Number(((100 * count) / of).toFixed(1)) : null,
});

/** summary.metrics as metrics.py produces it (timit61 engine). */
export const metrics: Metrics = {
  version: 2,
  engine: "timit61",
  words: { total: 181, analyzed: 175, low_confidence: 6, low_confidence_pct: 3.3 },
  deviate: ratio(126, 175),
  segment_loss: ratio(45, 175),
  syllable_loss: ratio(7, 173),
  schwa_share: ratio(56, 238),
  function_words: ratio(85, 181),
  weak_forms: { greedy: ratio(14, 35), variant: null },
  flapping: ratio(3, 4),
  glottal_before_syllabic_n: ratio(0, 0),
  glottal_prevocalic: ratio(0, 3),
  final_t_prevocalic: { released: 0, flap: 2, glottal: 0, unreleased: 1, other: 0, of: 3 },
  labels: { linking: 24, vowel_reduction: 18 },
  labels_per_100_words: { linking: 13.26, vowel_reduction: 9.94 },
  rhythm: {
    npvi: 36.2, varco: 39.8, n_intervals: 204, n_pairs: 174, mean_ms: 157.9, sd_ms: 62.9,
    approximate: true, method: "inter-nucleus intervals (CTC peaks)",
  },
};
