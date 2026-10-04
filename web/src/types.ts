/** Shapes returned by the API (mirrors analysis.json and jobs.Job). */

/** [phone, start, end] in absolute seconds. */
export type AlignedPhone = [string, number, number];

/** How a word links onto the next one (phenomena.LINK_TYPES). */
export type LinkType = "consonant" | "r" | "glide_w" | "glide_j";

/** Strong vs weak pronunciation scored against the recognizer's emissions
 *  (variants.py; espeak engine only). Scores are relative to the best one. */
export interface WordForm {
  ipa: string;
  strong_ipa: string;
  weak: boolean;
  weak_margin: number | null;
  scores: Record<string, number>;
  h_dropped?: boolean;
  h_drop_margin?: number | null;
}

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
  /** How a numeral was said ("9.30" → "nine thirty"); absent before rules v3. */
  canonical_text?: string | null;
  /** No dictionary form to compare against: no labels, excluded from the metrics. */
  no_canonical?: boolean;
  /** Absent in older analyses; null when the word does not link. */
  boundary_link_type?: LinkType | null;
  /** Absent in older analyses and with the timit61 engine. */
  form?: WordForm | null;
  /** Labels added by the form scoring, not read off the greedy phones. */
  variant_labels?: string[];
}

export type Contour = "rising" | "falling" | "flat";

export interface F0Stats {
  mean: number | null;
  range: number | null;
  final_contour: string;
  /** Speaker-independent versions (semitones); absent in older analyses. */
  range_st?: number | null;
  final_slope_st?: number | null;
  /** The pitch range adapted to this segment's voice. */
  f0_floor?: number | null;
  f0_ceiling?: number | null;
}

/** "incomplete": the segment ends mid-sentence (no final punctuation), so no
 *  contour is expected of it. */
export type UnitType =
  | "statement"
  | "yes_no_question"
  | "wh_question"
  | "exclamation"
  | "incomplete";

/** A sentence-sized stretch of a segment, split at Whisper's punctuation. */
export interface IntonationUnit {
  start: number;
  end: number;
  /** [first, last] word index within the segment, inclusive. */
  words: [number, number];
  text: string;
  type: UnitType;
  final_contour: Contour;
  final_slope_st: number | null;
  /** What the report expects for this type; null when it expects nothing. */
  expected_contour: Contour | null;
  matches_expected: boolean | null;
  /** A statement that ends rising. */
  uptalk: boolean;
}

/** Rhythm from the spacing of syllable nuclei. Approximate by construction: the
 *  recognizer gives phone peaks, not durations. */
export interface Rhythm {
  npvi: number;
  varco: number | null;
  n_intervals: number;
  n_pairs: number;
  mean_ms: number;
  sd_ms: number;
  approximate: boolean;
  method: string;
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
  /** Parallel to `words`, 0–1; absent in older analyses. */
  prominence?: number[];
  word_classes?: ("content" | "function")[];
  intonation_units?: IntonationUnit[];
  rhythm?: Rhythm | null;
}

export interface AnalysisMeta {
  source: string;
  duration: number;
  language: string;
  phonotrainer_version: string;
  /** Absent in older analyses, which were all made with the espeak engine. */
  phone_engine?: string;
  models: { asr: string; phones: string; alignment: string };
  attraction: boolean;
  phone_cleanup: { normalized_phones: number; attracted_phones: number };
  /** Dialogue isolated from music and effects before analyzing. */
  dialogue_separation?: {
    applied: boolean;
    model?: string | null;
    audio?: string | null;
    seconds?: number | null;
    error?: string | null;
  } | null;
  form_scoring?: { weak_margin: number; h_drop_margin: number } | null;
  /** Version of the labeling rules (phenomena.RULES_VERSION); absent in older analyses. */
  rules_version?: number;
}

/** count out of `of`; pct is null when there was nothing to count. */
export interface Ratio {
  count: number;
  of: number;
  pct: number | null;
}

export type FinalTOutcome = "released" | "flap" | "glottal" | "unreleased" | "other";

/** summary.metrics (metrics.py): how reduced this speech is. */
export interface Metrics {
  version: number;
  engine: string | null;
  /** Only in corpus aggregates. */
  analyses?: number;
  /** Corpus aggregates: the rules_version of each pooled analysis, and whether they differ. */
  rules?: (number | null)[];
  mixed_rules?: boolean;
  words: {
    total: number;
    analyzed: number;
    low_confidence: number;
    low_confidence_pct: number | null;
    /** Words with no dictionary form to compare against (excluded from the measures). */
    no_canonical?: number;
  };
  deviate: Ratio;
  segment_loss: Ratio;
  syllable_loss: Ratio;
  schwa_share: Ratio;
  function_words: Ratio;
  weak_forms: {
    greedy: Ratio;
    variant: (Ratio & { weak: number; strong: number; uncertain: number }) | null;
  };
  flapping: Ratio;
  glottal_before_syllabic_n: Ratio;
  glottal_prevocalic: Ratio;
  final_t_prevocalic: Record<FinalTOutcome, number> & { of: number };
  labels: Record<string, number>;
  labels_per_100_words: Record<string, number>;
  rhythm: Rhythm | null;
}

/** A figure from the report the metrics are read against. */
export interface MetricReference {
  low: number | null;
  high: number | null;
  display: string;
  cite: string;
  source: string;
  note: string;
}

export interface CorpusMetrics {
  analyses: number;
  materials: number;
  measured: number;
  /** Analyses whose analysis.json is gone: not measured, not guessed. */
  missing: number;
  /** One pool per phone engine, the default first: they are never added up. */
  by_engine: Record<string, Metrics | null>;
  reference: Record<string, MetricReference>;
}

export interface Analysis {
  meta: AnalysisMeta;
  segments: Segment[];
  summary: { phenomena_counts: Record<string, number>; metrics?: Metrics | null };
}

export type JobStatus = "queued" | "running" | "done" | "error" | "cancelled";

export interface JobOptions {
  whisper_model: "tiny" | "base" | "small" | "medium";
  /** "wav2vec2" is the espeak engine's old name, still found on old jobs. */
  phone_engine: "timit61" | "espeak" | "wav2vec2";
  language: string;
  /** null = the engine's default (on for espeak, off for timit61). */
  attraction: boolean | null;
  separate_dialogue?: boolean;
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
  /** Absent on older servers. */
  phone_model?: string | null;
  metrics?: Metrics | null;
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
  /** The isolated dialogue track the analysis read (absent on older servers). */
  has_dialogue_audio?: boolean;
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

/** The report's advice: say it yourself, or learn to recognize it. */
export interface Practice {
  practice: "produce" | "understand";
  register: "universal" | "casual" | "marked";
  why: string;
}

export interface Reference {
  /** API contract. Absent = server older than the field itself. */
  api_version?: number;
  families: Family[];
  family_of: Record<string, string>;
  labels: Record<string, string>;
  /** What each phenomenon is, in one sentence and with an example. */
  descriptions: Record<string, string>;
  /** Per phenomenon label and per lexical reduced form ("gonna"). */
  practice?: Record<string, Practice>;
  lexical_practice?: Record<string, Practice>;
  link_types?: LinkType[];
  /** Multi-character IPA symbols, longest first. */
  ipa_tokens: string[];
  verdicts: VerdictValue[];
  /** What summary.metrics is compared against, and what each measure is. */
  metrics_reference?: Record<string, MetricReference>;
  metric_labels?: Record<string, string>;
  options: {
    whisper_models: JobOptions["whisper_model"][];
    phone_engines: JobOptions["phone_engine"][];
    phone_engine_notes?: Record<string, string>;
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
