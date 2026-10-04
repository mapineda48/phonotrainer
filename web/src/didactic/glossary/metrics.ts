/** Metric entries (Insights and the analysis summary).
 *
 *  One entry per measure of summary.metrics / /api/corpus/metrics, keyed with
 *  metricTerm(name) = "metric:<name>", e.g. "metric:deviate". Each says what is counted
 *  and what it means for listening; the published figure itself (and its source) comes
 *  from /api/reference → metrics_reference and is shown next to the meter, never copied
 *  here. */

import { paths } from "../../paths";
import type { Glossary } from "./types";

export const METRIC_GLOSSARY: Glossary = {
  "metric:deviate": {
    title: "Words that differ from the dictionary",
    body:
      "Words where at least one sound was not the one the dictionary gives: changed, " +
      "dropped or added. This is the headline of the research on spoken English: most " +
      "words in real conversation do not sound like their dictionary form.",
    example: "water → wɔɾɚ (the t became a flap)",
    learnMore: { href: paths.learn(), label: "See every change in Learn" },
  },
  "metric:segment_loss": {
    title: "Words that lose a sound",
    body:
      "Words where at least one whole sound disappeared, not just changed. When you " +
      "listen, a missing sound is easy to take for a different word.",
    example: "next day → nɛks deɪ (the t is gone)",
    learnMore: { href: paths.phenomenon("t_deletion"), label: "Learn about t/d deletion" },
  },
  "metric:syllable_loss": {
    title: "Words that lose a syllable",
    body:
      "Words that came out with fewer syllables than the dictionary gives, usually because " +
      "an unstressed vowel vanished. The word gets shorter than you expect.",
    example: "probably → pɹɑbli",
    learnMore: { href: paths.phenomenon("elision_syllable"), label: "Learn about elided syllables" },
  },
  "metric:schwa_share": {
    title: "Schwa among the vowels",
    body:
      "Of all the vowels heard, how many were schwa [ə], the short, relaxed \"uh\" of " +
      "unstressed syllables. It is the most frequent vowel of spoken English; Spanish has " +
      "no vowel like it, so it is worth learning to hear.",
    example: "about → əbaʊt",
    learnMore: { href: paths.ipa("ə"), label: "Schwa in the IPA chart" },
  },
  "metric:function_words": {
    title: "Function words",
    body:
      "Of all the words, how many were small grammar words: to, of, and, the, can, you… " +
      "They are usually unstressed and reduced, so a large part of what you hear is " +
      "reduced by default.",
    example: "a cup of tea → ə kʌp ə ti",
  },
  "metric:weak_forms": {
    title: "Function words in their weak form",
    body:
      "Of the function words, how many the recognizer heard in their weak form, with the " +
      "vowel reduced or a sound dropped. The strong form appears mostly at the end of a " +
      "phrase or when the word is stressed.",
    example: "to → tə, of → əv, and → ən",
    learnMore: { href: paths.phenomenon("vowel_reduction"), label: "Learn about vowel reduction" },
  },
  "metric:weak_forms_variant": {
    title: "Weak forms by form scoring",
    body:
      "Only for the espeak engine: each function word is scored against its strong and " +
      "weak pronunciations, and only the confident decisions are counted here.",
  },
  "metric:flapping": {
    title: "Flapped t and d",
    body:
      "Where a t or d sits after a vowel and before an unstressed vowel, how often it " +
      "became a flap [ɾ], a quick tap like the Spanish r in \"pero\". Only the places " +
      "where flapping can happen are counted.",
    example: "better → bɛɾɚ",
    learnMore: { href: paths.phenomenon("flapping"), label: "Learn about flapping" },
  },
  "metric:glottal_prevocalic": {
    title: "Final t as a glottal stop",
    body:
      "A t at the end of a word, before a word that starts with a vowel, said as a glottal " +
      "stop [ʔ]: a short catch in the throat instead of a t. It is more common in younger " +
      "speakers.",
    example: "get it → ɡɛʔ ɪt",
    learnMore: { href: paths.phenomenon("glottalization"), label: "Learn about glottalization" },
  },
  "metric:glottal_before_syllabic_n": {
    title: "Glottal t before a syllabic n",
    body:
      "A t before a syllabic n, as in \"button\" or \"certain\", said as a glottal stop. " +
      "This is the usual American pronunciation of these words.",
    example: "button → bʌʔn̩",
    learnMore: { href: paths.phenomenon("glottalization"), label: "Learn about glottalization" },
  },
  "metric:labels_per_100_words": {
    title: "Phenomena per 100 words",
    body:
      "How often each phenomenon was found, scaled to 100 words, so recordings of " +
      "different lengths can be compared.",
  },
  "metric:low_confidence": {
    title: "Low-confidence words",
    body:
      "Words where the recognizer heard almost nothing: silence, laughter or music over " +
      "the voice. They are left out of the measures above.",
  },
};
