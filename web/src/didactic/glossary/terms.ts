/** General terms. Keys are plain kebab-case: <Explain term="weak-form">.
 *  IPA symbols live in ipa.ts (keys "ipa:<symbol>") and metrics in metrics.ts (keys
 *  "metric:<name>"). Phenomenon explanations are NOT here: they come from
 *  /api/reference (<Explain phenomenon="flapping">). */

import { paths } from "../../paths";
import type { Glossary } from "./types";

export const TERMS: Glossary = {
  "dictionary-form": {
    title: "Dictionary form",
    body: "How a dictionary writes the word when it is said slowly and alone (the citation form). Written between slashes: /…/.",
    example: "water → /wɔtɚ/",
  },
  "what-was-said": {
    title: "What was said",
    body: "The sounds the recognizer actually heard in this recording. Written between square brackets: […].",
    example: "water → [wɔɾɚ]",
  },
  canonical: {
    title: "Expected form, placed in time",
    body: "The dictionary form lined up with the audio, sound by sound, so each expected sound can be compared with what was heard at the same moment.",
  },
  phone: {
    title: "Phone",
    body: "One speech sound. Each IPA symbol stands for one phone: t, ɾ and ʔ are three different phones.",
    learnMore: { href: paths.ipa(), label: "Open the IPA chart" },
  },
  ipa: {
    title: "IPA",
    body: "The International Phonetic Alphabet: one symbol for each sound, the same in every language. It shows what spelling hides — water has a [ɾ], not a [t].",
    learnMore: { href: paths.ipa(), label: "Open the IPA chart" },
  },
  phenomenon: {
    title: "Connected-speech phenomenon",
    body: "A regular change that happens when words are said in a stream, not one by one: a vowel weakens, a /t/ becomes a tap, two words link.",
    learnMore: { href: paths.learn(), label: "Learn all of them" },
  },
  family: {
    title: "Family of phenomena",
    body: "Phenomena are grouped in four families: reduction (sounds weaken), t/d processes, assimilation (sounds blend) and word boundary (words run together). Each family has its own icon and underline style.",
    learnMore: { href: paths.learn() },
  },
  "weak-form": {
    title: "Weak form",
    body: "Small grammar words (to, of, and, can, you…) have a strong form and a weak form. In normal speech the weak one, usually with a schwa [ə], is the default.",
    example: "to → tə",
  },
  "strong-form": {
    title: "Strong form",
    body: "The full pronunciation of a grammar word. It appears at the end of a phrase, under emphasis, or when quoted: “Yes, I can.”",
    example: "can → kæn",
  },
  schwa: {
    title: "Schwa",
    body: "The short, relaxed vowel [ə], as in the first sound of about. It is the most frequent vowel in spoken English: unstressed vowels turn into it.",
    example: "banana → bənænə",
  },
  "safe-to-produce": {
    title: "Safe to produce",
    body: "Native speakers use this change in every register, and a learner sounds natural using it too. Copy it.",
  },
  "recognize-only": {
    title: "Recognize only",
    body: "Learn to hear it, but you do not need to say it: it is casual, regional or socially marked, and it can sound forced from a learner.",
  },
  register: {
    title: "Register",
    body: "How widely a change is used: universal (every speaker, every situation), casual (relaxed speech) or marked (a region or group).",
  },
  "intonation-unit": {
    title: "Intonation unit",
    body: "A stretch of speech with one melody, usually a sentence or a phrase. Its last pitch movement tells a statement (falls) from a yes/no question (rises).",
  },
  uptalk: {
    title: "Uptalk",
    body: "A statement that ends with a rise, as if it were a question. Common in younger American speakers; you will hear it, but you do not need to produce it.",
  },
  prominence: {
    title: "Prominence",
    body: "How much a word stands out: higher pitch, louder, longer. English makes content words (nouns, verbs, adjectives) prominent and reduces grammar words.",
  },
  "content-word": {
    title: "Content word",
    body: "A word that carries meaning: nouns, main verbs, adjectives, adverbs. They usually keep their full vowels and get the stress.",
  },
  "function-word": {
    title: "Function word",
    body: "A grammar word: articles, prepositions, pronouns, auxiliaries, conjunctions. They are usually unstressed and reduced.",
  },
  f0: {
    title: "Pitch (F0)",
    body: "How high or low the voice is, moment by moment (the fundamental frequency). The line rises in questions and falls at the end of statements.",
  },
  semitone: {
    title: "Semitone",
    body: "A step of pitch, the same for every voice. Measuring in semitones lets a deep voice and a high voice be compared.",
  },
  rhythm: {
    title: "Rhythm (approximate)",
    body: "How regular the syllables are. English stretches stressed syllables and squeezes the rest; Spanish keeps them more even. Measured here from the spacing of syllables, so treat it as a rough guide.",
  },
  engine: {
    title: "Phone engine",
    body: "The model that listens to the audio and writes down the sounds it hears. The default, TIMIT-61, was trained on careful phonetic transcriptions and hears taps, glottal stops and unreleased stops.",
  },
  separation: {
    title: "Dialogue separation",
    body: "Before analyzing, the voices are separated from music and sound effects, so the recognizer hears speech only. You can play either the original mix or the dialogue alone.",
  },
  "low-confidence": {
    title: "Low confidence",
    body: "The recognizer found little or nothing here (silence, laughter, music, overlapping voices). Labels on this word may be wrong.",
  },
  linking: {
    title: "Linking",
    body: "Two words joined without a pause. The end of one word runs into the start of the next.",
    example: "pick it up → pɪ‿kɪ‿ɾʌp",
  },
  divergence: {
    title: "Divergence",
    body: "How far what was said is from the dictionary form, averaged per sound: 0 means identical.",
  },
  attraction: {
    title: "Attraction",
    body: "A clean-up step: when the recognizer confuses two sounds in a way that teaches nothing (b heard as v), the expected sound is kept. Native changes are never touched.",
  },
};
