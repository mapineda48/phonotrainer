/** Ear-training quiz: pure logic (no React, no network). Items are built from corpus
 *  occurrences, word variants and analysis segments that the page loads.
 *
 *  Three item types (the third one follows the report's dictation advice):
 *    form   — hear a word; which pronunciation was it? (what was said vs the dictionary
 *             form vs other ways the same word was said in the corpus)
 *    change — hear a word; which change happened?
 *    count  — hear a short phrase; how many words were in it? */

import type { Occurrence, Reference, Segment, Word, WordVariant } from "../../types";
import { displayWord, isPlayable, stripStress } from "../learn/corpus";

export type ItemKind = "form" | "change" | "count";

export type Rng = () => number;

/** Small deterministic PRNG (mulberry32): tests and seeded sessions repeat exactly. */
export function seededRng(seed: number): Rng {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function shuffle<T>(items: readonly T[], rng: Rng): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(rng() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

/** A playable span of one job's audio. */
export interface ClipSpan {
  jobId: string;
  start: number;
  end: number;
  padBefore: number;
  padAfter: number;
}

export interface QuizOption {
  id: string;
  /** Accessible and visible text ("[ɡɑɾə]", "flapping", "5 words"). */
  label: string;
  /** IPA to show in the IPA font (form items). */
  ipa?: string;
  /** Phenomenon key (change items): rendered with its family icon. */
  phenomenon?: string;
}

export interface QuizItem {
  kind: ItemKind;
  key: string;
  prompt: string;
  /** What to listen to. */
  clip: ClipSpan;
  /** A wider span with the words around (form and change items). */
  context?: ClipSpan;
  options: QuizOption[];
  answer: string;
  /** The phenomenon this item trains: stats and the explanation link. */
  phenomenon: string;
  occurrence: Occurrence & { job_id: string };
  /** count items: the words of the phrase, marking the ones that changed. */
  phrase?: { text: string; changed: boolean }[];
}

/** Occurrences a quiz can use, each tagged with the phenomenon it was fetched for. */
export interface PoolEntry {
  phenomenon: string;
  occurrence: Occurrence & { job_id: string };
}

/** Phenomena that make sense in a quiz: every labelled change except "elided word"
 *  (there is nothing to hear). */
export function quizPhenomena(reference: Reference): string[] {
  return Object.keys(reference.labels).filter((name) => name !== "word_elision");
}

export function buildPool(byPhenomenon: Record<string, readonly Occurrence[]>): PoolEntry[] {
  const pool: PoolEntry[] = [];
  for (const [phenomenon, occurrences] of Object.entries(byPhenomenon)) {
    for (const occurrence of occurrences) {
      if (isPlayable(occurrence) && occurrence.realized_ipa) pool.push({ phenomenon, occurrence });
    }
  }
  return pool;
}

/* ---- Stats ------------------------------------------------------------------------- */

export interface PhenomenonStats {
  seen: number;
  correct: number;
}

export type Stats = Record<string, PhenomenonStats>;

export const STATS_KEY = "phonotrainer:practice-stats";

export function loadStats(): Stats {
  try {
    const raw = window.localStorage.getItem(STATS_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return {};
    const out: Stats = {};
    for (const [name, value] of Object.entries(parsed as Record<string, unknown>)) {
      const v = value as Partial<PhenomenonStats> | null;
      if (v && Number.isFinite(v.seen) && Number.isFinite(v.correct)) {
        out[name] = { seen: Math.max(0, v.seen as number), correct: Math.max(0, v.correct as number) };
      }
    }
    return out;
  } catch {
    return {};
  }
}

export function saveStats(stats: Stats): void {
  try {
    window.localStorage.setItem(STATS_KEY, JSON.stringify(stats));
  } catch {
    /* private mode or storage full: stats just aren't kept */
  }
}

export function clearStats(): void {
  try {
    window.localStorage.removeItem(STATS_KEY);
  } catch {
    /* nothing to clear */
  }
}

export function recordAnswer(stats: Stats, phenomenon: string, correct: boolean): Stats {
  const before = stats[phenomenon] ?? { seen: 0, correct: 0 };
  return { ...stats, [phenomenon]: { seen: before.seen + 1, correct: before.correct + (correct ? 1 : 0) } };
}

/** Accuracy 0–1, or null before the first answer. */
export const accuracy = (entry: PhenomenonStats | undefined): number | null =>
  entry && entry.seen > 0 ? entry.correct / entry.seen : null;

/* ---- Drawing an occurrence ----------------------------------------------------------- */

/** Pick from the pool. With `weakSpots`, phenomena with lower accuracy (and unseen ones)
 *  are drawn more often: weight = 1.5 − accuracy (unseen counts as 0.5). */
export function drawEntry(
  pool: readonly PoolEntry[],
  rng: Rng,
  options: { stats?: Stats; weakSpots?: boolean; focus?: string | null; avoid?: ReadonlySet<string> } = {},
): PoolEntry | null {
  let candidates = options.focus ? pool.filter((entry) => entry.phenomenon === options.focus) : [...pool];
  if (options.avoid && candidates.some((entry) => !options.avoid!.has(entryKey(entry)))) {
    candidates = candidates.filter((entry) => !options.avoid!.has(entryKey(entry)));
  }
  if (candidates.length === 0) return null;

  // Draw a phenomenon first (so a phenomenon with 200 examples does not drown one with 3),
  // then an occurrence inside it.
  const names = [...new Set(candidates.map((entry) => entry.phenomenon))];
  const weights = names.map((name) => {
    if (!options.weakSpots) return 1;
    const acc = accuracy(options.stats?.[name]);
    return 1.5 - (acc ?? 0.5);
  });
  const total = weights.reduce((sum, w) => sum + w, 0);
  let roll = rng() * total;
  let chosen = names[names.length - 1];
  for (let i = 0; i < names.length; i += 1) {
    roll -= weights[i];
    if (roll < 0) {
      chosen = names[i];
      break;
    }
  }
  const inside = candidates.filter((entry) => entry.phenomenon === chosen);
  return inside[Math.floor(rng() * inside.length)] ?? null;
}

export const entryKey = (entry: PoolEntry): string =>
  `${entry.occurrence.analysis_id}:${entry.occurrence.segment}:${entry.occurrence.word_idx}`;

/* ---- Clips ---------------------------------------------------------------------------- */

export function wordClip(occurrence: Occurrence & { job_id: string }, family: string | null): ClipSpan {
  return {
    jobId: occurrence.job_id,
    start: occurrence.start,
    end: occurrence.end,
    padBefore: 0.1,
    padAfter: family === "boundary" ? 0.45 : 0.12,
  };
}

export function contextClip(occurrence: Occurrence & { job_id: string }): ClipSpan {
  return { jobId: occurrence.job_id, start: occurrence.start, end: occurrence.end, padBefore: 1.2, padAfter: 1.2 };
}

/* ---- Item: which change? ---------------------------------------------------------------- */

/** The right phenomenon plus up to three others the word does NOT have, preferring other
 *  families so the choice is about hearing, not about fine labels. */
export function changeOptions(
  entry: PoolEntry,
  reference: Reference,
  rng: Rng,
  candidates: readonly string[] = quizPhenomena(reference),
): QuizOption[] {
  const present = new Set(entry.occurrence.phenomena);
  present.add(entry.phenomenon);
  const family = (name: string) => reference.family_of[name] ?? "lexical";
  const others = shuffle(
    candidates.filter((name) => !present.has(name)),
    rng,
  );
  const picked: string[] = [];
  const usedFamilies = new Set([family(entry.phenomenon)]);
  for (const name of others) {
    if (picked.length >= 3) break;
    if (usedFamilies.has(family(name))) continue;
    picked.push(name);
    usedFamilies.add(family(name));
  }
  for (const name of others) {
    if (picked.length >= 3) break;
    if (!picked.includes(name)) picked.push(name);
  }
  return shuffle([entry.phenomenon, ...picked], rng).map((name) => ({
    id: name,
    label: reference.labels[name] ?? name,
    phenomenon: name,
  }));
}

export function changeItem(entry: PoolEntry, reference: Reference, rng: Rng): QuizItem {
  const family = reference.family_of[entry.phenomenon] ?? null;
  return {
    kind: "change",
    key: `change:${entryKey(entry)}`,
    prompt: `Listen to “${displayWord(entry.occurrence.word)}”. Which change did you hear?`,
    clip: wordClip(entry.occurrence, family),
    context: contextClip(entry.occurrence),
    options: changeOptions(entry, reference, rng),
    answer: entry.phenomenon,
    phenomenon: entry.phenomenon,
    occurrence: entry.occurrence,
  };
}

/* ---- Item: which pronunciation? --------------------------------------------------------- */

const normalize = (ipa: string): string => stripStress(ipa).replace(/[ː‿\s]/g, "");

/** What was said, the dictionary form, and up to two other attested pronunciations of the
 *  same word. Null when there is nothing to choose between. */
export function formOptions(
  occurrence: Occurrence,
  variants: readonly WordVariant[],
  rng: Rng,
): QuizOption[] | null {
  const said = occurrence.realized_ipa;
  if (!said) return null;
  const seen = new Set<string>([normalize(said)]);
  const options: QuizOption[] = [{ id: "said", label: `[${said}]`, ipa: said }];
  const dictionary = occurrence.dict_ipa ? stripStress(occurrence.dict_ipa) : null;
  if (dictionary && !seen.has(normalize(dictionary))) {
    seen.add(normalize(dictionary));
    options.push({ id: "dictionary", label: `[${dictionary}]`, ipa: dictionary });
  }
  const others = [...variants]
    .filter((variant) => variant.realized_ipa && !seen.has(normalize(variant.realized_ipa)))
    .sort((a, b) => b.count - a.count);
  for (const variant of others) {
    if (options.length >= 4) break;
    const ipa = variant.realized_ipa as string;
    if (seen.has(normalize(ipa))) continue;
    seen.add(normalize(ipa));
    options.push({ id: `variant:${options.length}`, label: `[${ipa}]`, ipa });
  }
  if (options.length < 2) return null;
  return shuffle(options, rng);
}

export function formItem(
  entry: PoolEntry,
  variants: readonly WordVariant[],
  reference: Reference,
  rng: Rng,
): QuizItem | null {
  const options = formOptions(entry.occurrence, variants, rng);
  if (!options) return null;
  const family = reference.family_of[entry.phenomenon] ?? null;
  return {
    kind: "form",
    key: `form:${entryKey(entry)}`,
    prompt: `Listen to “${displayWord(entry.occurrence.word)}”. Which pronunciation did you hear?`,
    clip: wordClip(entry.occurrence, family),
    context: contextClip(entry.occurrence),
    options,
    answer: "said",
    phenomenon: entry.phenomenon,
    occurrence: entry.occurrence,
  };
}

/* ---- Item: how many words? ------------------------------------------------------------ */

export const MIN_PHRASE = 3;
export const MAX_PHRASE = 9;

/** A short phrase around the word: its intonation unit if it is short enough, else a
 *  window of the segment's words around it. */
export function phraseAround(segment: Segment, wordIdx: number): Word[] | null {
  const words = segment.words;
  if (wordIdx < 0 || wordIdx >= words.length) return null;
  const unit = segment.intonation_units?.find(
    (candidate) => candidate.words[0] <= wordIdx && wordIdx <= candidate.words[1],
  );
  if (unit) {
    const inside = words.slice(unit.words[0], unit.words[1] + 1);
    if (inside.length >= MIN_PHRASE && inside.length <= MAX_PHRASE) return inside;
  }
  if (words.length >= MIN_PHRASE && words.length <= MAX_PHRASE) return [...words];
  if (words.length < MIN_PHRASE) return null;
  const size = 6;
  const from = Math.max(0, Math.min(wordIdx - 2, words.length - size));
  return words.slice(from, from + size);
}

export function countOptions(count: number, rng: Rng): QuizOption[] {
  const values = new Set<number>([count]);
  for (const delta of [-1, 1, 2, -2, 3]) {
    if (values.size >= 4) break;
    if (count + delta >= 1) values.add(count + delta);
  }
  return shuffle([...values], rng).map((value) => ({
    id: String(value),
    label: `${value} ${value === 1 ? "word" : "words"}`,
  }));
}

export function countItem(entry: PoolEntry, segment: Segment, rng: Rng): QuizItem | null {
  const phrase = phraseAround(segment, entry.occurrence.word_idx);
  if (!phrase || phrase.some((word) => word.low_confidence)) return null;
  const count = phrase.length;
  return {
    kind: "count",
    key: `count:${entryKey(entry)}`,
    prompt: "Listen to the phrase. How many words did you hear?",
    clip: {
      jobId: entry.occurrence.job_id,
      start: phrase[0].start,
      end: phrase[phrase.length - 1].end,
      padBefore: 0.08,
      padAfter: 0.15,
    },
    options: countOptions(count, rng),
    answer: String(count),
    phenomenon: entry.phenomenon,
    occurrence: entry.occurrence,
    phrase: phrase.map((word) => ({ text: word.word, changed: word.phenomena.length > 0 })),
  };
}

/** Rotate the item types through a session: form, change, count, form, … */
export function kindForIndex(index: number): ItemKind {
  return (["form", "change", "count"] as const)[index % 3];
}

export const SESSION_LENGTH = 10;
