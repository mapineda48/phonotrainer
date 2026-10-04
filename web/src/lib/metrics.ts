/** Reading summary.metrics: which measures to show, in what order, and how each
 *  sits against the report's figure. The figures themselves (and their
 *  sources) come from the backend, in /api/reference. */

import type { FinalTOutcome, Metrics, MetricReference, Ratio } from "../types";

export interface MetricRow {
  key: string;
  ratio: Ratio;
  /** What the count is made of ("words", "vowels"…). */
  unit: string;
}

/** The measures worth a meter, the headline (Johnson 2004) first. */
export function metricRows(metrics: Metrics): MetricRow[] {
  const rows: MetricRow[] = [
    { key: "deviate", ratio: metrics.deviate, unit: "words" },
    { key: "segment_loss", ratio: metrics.segment_loss, unit: "words" },
    { key: "syllable_loss", ratio: metrics.syllable_loss, unit: "words" },
    { key: "schwa_share", ratio: metrics.schwa_share, unit: "vowels" },
    { key: "function_words", ratio: metrics.function_words, unit: "words" },
    { key: "weak_forms", ratio: metrics.weak_forms.greedy, unit: "function words" },
  ];
  if (metrics.weak_forms.variant) {
    rows.push({ key: "weak_forms_variant", ratio: metrics.weak_forms.variant, unit: "scored" });
  }
  rows.push(
    { key: "flapping", ratio: metrics.flapping, unit: "t/d in context" },
    { key: "glottal_prevocalic", ratio: metrics.glottal_prevocalic, unit: "final t before a vowel" },
    { key: "glottal_before_syllabic_n", ratio: metrics.glottal_before_syllabic_n, unit: "t before [n̩]" },
  );
  return rows;
}

export const EXTRA_METRIC_LABELS: Record<string, string> = {
  weak_forms_variant: "function words scored weak (form scoring)",
};

/** Where a value falls against the report's range. */
export type Standing = "below" | "within" | "above" | null;

export function standing(pct: number | null, reference: MetricReference | undefined): Standing {
  if (pct == null || !reference || reference.low == null) return null;
  // Johnson 2004 figures are corpus means: ±2 points still reads as "the same"
  const tolerance = reference.high === reference.low ? 2 : 0;
  if (pct < reference.low - tolerance) return "below";
  if (reference.high != null && pct > reference.high + tolerance) return "above";
  return "within";
}

export const STANDING_TEXT: Record<Exclude<Standing, null>, string> = {
  below: "below the report",
  within: "in line with the report",
  above: "above the report",
};

export const FINAL_T_LABEL: Record<FinalTOutcome, string> = {
  flap: "flapped",
  glottal: "glottal stop",
  unreleased: "unreleased",
  released: "released",
  other: "other",
};

export const fmtPct = (pct: number | null): string => (pct == null ? "—" : `${pct.toFixed(1)} %`);
