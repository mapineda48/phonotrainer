/** Datos y envoltorios compartidos por los tests.
 *  El análisis es el mismo que produce `tests/conftest.py::mk_analysis`, así los
 *  tests de las dos mitades hablan del mismo material. */

import { render, screen, type RenderOptions } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import { vi } from "vitest";

import { Clock } from "../player/clock";
import { PlayerContextProvider, type PlayerApi } from "../player/PlayerProvider";
import { ReferenceProvider } from "../reference";
import type { AlignedPhone, Analysis, Job, Reference, Word } from "../types";

export const reference: Reference = {
  families: [
    {
      key: "red",
      label: "Reducción",
      members: ["vowel_reduction", "monophthongization", "elision_syllable"],
      member_labels: ["reducción vocálica", "monoptongación", "sílaba elidida"],
    },
    {
      key: "td",
      label: "Procesos de t/d",
      members: ["flapping", "t_deletion", "glottalization"],
      member_labels: ["flapping", "t/d elidida", "glotalización"],
    },
    {
      key: "asim",
      label: "Asimilación",
      members: ["th_stopping", "palatalization"],
      member_labels: ["th-stopping", "palatalización"],
    },
    {
      key: "fron",
      label: "Frontera de palabra",
      members: ["linking", "h_dropping"],
      member_labels: ["linking", "h muda"],
    },
  ],
  family_of: {
    vowel_reduction: "red",
    monophthongization: "red",
    elision_syllable: "red",
    flapping: "td",
    t_deletion: "td",
    glottalization: "td",
    th_stopping: "asim",
    palatalization: "asim",
    linking: "fron",
    h_dropping: "fron",
  },
  labels: {
    vowel_reduction: "reducción vocálica",
    t_deletion: "t/d elidida",
    contraction_lex: "contracción léxica",
    flapping: "flapping",
    linking: "linking",
  },
  verdicts: ["ok", "mal", "dudosa"],
  options: {
    whisper_models: ["tiny", "base", "small", "medium"],
    phone_engines: ["wav2vec2", "allosaurus"],
    defaults: {
      whisper_model: "small",
      phone_engine: "wav2vec2",
      language: "en",
      attraction: true,
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
    // Recuentos distintos a propósito: así los tests de orden prueban algo.
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
  last_message: "Generando report.html…",
  has_analysis: true,
  has_audio: true,
  has_report: true,
  has_review: false,
  has_media: false,
  is_video: false,
  meta: analysis.meta,
  summary: { segments: 2, words: 5, duration: 3, phenomena_counts: analysis.summary.phenomena_counts },
};

/** Botón de una palabra de la transcripción. Su nombre accesible incluye los
 *  fenómenos ("that, t/d elidida"), así que buscamos por prefijo. */
export const wordButton = (word: string): HTMLElement =>
  screen.getByRole("button", { name: new RegExp(`^${word}(,|$)`) });

/** Reproductor de mentira: registra las llamadas sin tocar el DOM de audio. */
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

export function renderWith(
  ui: ReactElement,
  { player = fakePlayer(), ...options }: { player?: PlayerApi } & RenderOptions = {},
) {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ReferenceProvider value={reference}>
      <PlayerContextProvider value={player}>{children}</PlayerContextProvider>
    </ReferenceProvider>
  );
  return { player, ...render(ui, { wrapper: Wrapper, ...options }) };
}
