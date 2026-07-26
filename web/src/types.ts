/** Formas que devuelve la API (espejo de analysis.json y de jobs.Job). */

/** [fono, inicio, fin] en segundos absolutos. */
export type AlignedPhone = [string, number, number];

export interface Word {
  word: string;
  start: number;
  end: number;
  canonical_ipa: string;
  canonical_aligned: AlignedPhone[];
  realized_ipa: string;
  realized_aligned: AlignedPhone[];
  realized_raw_ipa: string;
  attracted_count: number;
  diff_cost: number;
  dict_ipa: string;
  oov: boolean;
  phenomena: string[];
  low_confidence: boolean;
  boundary_link_next: boolean;
  lexical_form: string | null;
  alignment_fallback: boolean;
}

export interface F0Stats {
  mean: number | null;
  range: number | null;
  final_contour: string;
}

export interface Segment {
  start: number;
  end: number;
  text: string;
  f0_stats: F0Stats;
  /** [t, Hz] cada 10 ms. */
  f0_track: [number, number][];
  emphasis_word_idx: number | null;
  words: Word[];
}

export interface AnalysisMeta {
  source: string;
  duration: number;
  language: string;
  phonotrainer_version: string;
  models: { asr: string; phones: string; alignment: string };
  attraction: boolean;
  phone_cleanup: { normalized_phones: number; attracted_phones: number };
}

export interface Analysis {
  meta: AnalysisMeta;
  segments: Segment[];
  summary: { phenomena_counts: Record<string, number> };
}

export type JobStatus = "queued" | "running" | "done" | "error" | "cancelled";

export interface JobOptions {
  whisper_model: "tiny" | "base" | "small" | "medium";
  phone_engine: "wav2vec2" | "allosaurus";
  language: string;
  attraction: boolean;
}

export interface Job {
  id: string;
  source: string;
  status: JobStatus;
  options: Partial<JobOptions>;
  created: string;
  started: string | null;
  finished: string | null;
  error: string | null;
  imported: boolean;
  result_dir: string;
  percent: number;
  last_message: string | null;
  has_analysis: boolean;
  has_audio: boolean;
  has_report: boolean;
  has_review: boolean;
  has_media: boolean;
  is_video: boolean;
  meta: AnalysisMeta | null;
  summary: {
    segments: number;
    words: number;
    duration: number | null;
    phenomena_counts: Record<string, number>;
  } | null;
  progress?: { at: string; message: string }[];
}

export interface Family {
  key: string;
  label: string;
  members: string[];
  member_labels: string[];
}

export interface Reference {
  families: Family[];
  family_of: Record<string, string>;
  labels: Record<string, string>;
  verdicts: string[];
}

export interface BrowseEntry {
  name: string;
  path: string;
  size?: number;
  has_analysis?: boolean;
}

export interface Browse {
  path: string;
  parent: string | null;
  home: string;
  dirs: BrowseEntry[];
  files: BrowseEntry[];
}

export type VerdictValue = "ok" | "mal" | "dudosa";

export interface SampleItem {
  segment: number;
  word_idx: number;
  word: Word;
  segment_text: string;
  segment_start: number;
  segment_end: number;
}

export interface ReviewItem {
  segment: number;
  word_idx: number;
  word: string;
  t_start: number;
  t_end: number;
  phenomena: string[];
  attracted_count: number;
  low_confidence: boolean;
  verdict: VerdictValue;
  note: string;
}

export interface Review {
  seed: number;
  sampled: number;
  ok: number;
  mal: number;
  dudosa: number;
  accuracy: number | null;
  items: ReviewItem[];
}
