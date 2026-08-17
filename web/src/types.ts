/** Shapes returned by the API (mirrors analysis.json and jobs.Job). */

/** [phone, start, end] in absolute seconds. */
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
  /** The reduced form actually spoken ("wanna", "dunno"). */
  lexical_form: string | null;
  /** The corresponding full form ("want to"). Absent in older analyses. */
  lexical_expansion?: string | null;
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
  /** [t, Hz] every 10 ms. */
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

export interface CorpusStats {
  analyses: number;
  /** Distinct file names. */
  sources: number;
  /** Distinct recordings: if lower than `analyses`, something is counted twice. */
  materials: number;
  words: number;
  segments: number;
  duration: number;
  phenomena: { phenomenon: string; count: number; analyses: number }[];
  top_words: { word: string; count: number }[];
}

export interface CorpusAnalysis {
  id: string;
  job_id: string | null;
  source: string;
  duration: number | null;
  words: number;
  segments: number;
  attraction: boolean;
  duplicate_source: boolean;
  indexed_at: string;
}

export interface Occurrence {
  /** Stable identity of the analysis (its directory). */
  analysis_id: string;
  /** The UI job, if there is one: this is what makes jumping possible. */
  job_id: string | null;
  analysis_source: string;
  analysis_attraction: boolean;
  /** The following word: with linking the phenomenon happens between the two. */
  next_word: string | null;
  segment: number;
  word_idx: number;
  word: string;
  start: number;
  end: number;
  dict_ipa: string | null;
  canonical_ipa: string | null;
  realized_ipa: string | null;
  realized_raw_ipa: string | null;
  diff_cost: number;
  attracted_count: number;
  low_confidence: boolean;
  oov: boolean;
  lexical_form: string | null;
  phenomena: string[];
  /** Shorter than 60 ms: almost always an alignment failure, not a phenomenon. */
  too_short: boolean;
}

export interface WordVariant {
  realized_ipa: string | null;
  count: number;
  analyses: number;
  dict_ipa: string | null;
}

export interface Job {
  id: string;
  source: string;
  /** Present if the analysis started by downloading a video. */
  source_url?: string | null;
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
  /** API contract. Absent = server older than the field itself. */
  api_version?: number;
  families: Family[];
  family_of: Record<string, string>;
  labels: Record<string, string>;
  /** What each phenomenon is, in one sentence and with an example. */
  descriptions: Record<string, string>;
  /** Multi-character IPA symbols, longest first. */
  ipa_tokens: string[];
  verdicts: VerdictValue[];
  options: {
    whisper_models: JobOptions["whisper_model"][];
    phone_engines: JobOptions["phone_engine"][];
    defaults: JobOptions;
  };
  review: { default_n: number; default_seed: number; max_n: number };
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

export type VerdictValue = "ok" | "wrong" | "unsure";

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
  wrong: number;
  unsure: number;
  accuracy: number | null;
  items: ReviewItem[];
}
