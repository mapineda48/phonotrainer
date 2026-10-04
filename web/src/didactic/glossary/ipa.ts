/** IPA symbol entries (Learn, the IPA chart and every "What's this?" on a sound).
 *
 *  One entry per symbol of the closed inventory, keyed with ipaTerm(symbol) =
 *  "ipa:<symbol>", e.g. "ipa:t̚". The entries are BUILT from the articulation table
 *  (web/src/articulation/phones.ts: name, example word, what the mouth does), so a symbol
 *  added there is explained here with no second list to keep in sync. What the table
 *  cannot say — why a sound matters to a learner of American English — is added below
 *  for the few symbols where it does. */

import { bareSymbol, PHONE_TABLE, type PhoneArticulation } from "../../articulation/phones";
import { paths } from "../../paths";
import type { Glossary, GlossaryEntry } from "./types";

/** The phenomenon a symbol is the audible trace of: its entry links to that page. */
export const SYMBOL_PHENOMENON: Readonly<Record<string, string>> = {
  "ɾ": "flapping",
  "ɾ̃": "nt_reduction",
  "ʔ": "glottalization",
  "p̚": "t_unreleased",
  "b̚": "t_unreleased",
  "t̚": "t_unreleased",
  "d̚": "t_unreleased",
  "k̚": "t_unreleased",
  "ɡ̚": "t_unreleased",
  "ə": "vowel_reduction",
  "ɐ": "vowel_reduction",
  "ᵻ": "vowel_reduction",
  "ɚ": "vowel_reduction",
};

/** Why the symbol matters, in plain English. Only where there is something to say. */
const NOTES: Readonly<Record<string, string>> = {
  "ɾ": "You hear it in “water”, “better” and “get it”. It is the single r of Spanish “pero”: you can already say it.",
  "ɾ̃": "Heard in “winter”, “twenty” and “internet” said fast.",
  "ʔ": "Heard as the t of “button” or “important”, like the catch in “uh-oh”.",
  "t̚": "Listen for a tiny silence where the t should be: “that one” sounds like “tha' one”.",
  "ə":
    "The most frequent vowel of spoken English: “about”, “banana”, and the weak forms of “to”, “of” and “a”.",
  "ɐ": "It counts as a weak vowel, like [ə].",
  "ɚ": "The -er of “letter”, and the weak form of “for” and “your”.",
  "n̩": "Heard in “button” [bʌʔn̩] and “didn't”.",
  "θ": "Many Spanish speakers say [s] or [t] here, but English keeps “think”, “sink” and “tink” apart.",
  "ð": "Not the same as [d]: “they” and “day” are different words.",
  "v": "Spanish b and v are one sound; English keeps “berry” and “very” apart.",
  "z": "Spanish has no /z/: “rise” and “rice”, “plays” and “place” differ only by it.",
  "æ": "Between Spanish a and e: “cat”. Saying [ɛ] or [a] instead is a common accent marker.",
  "ʌ": "The short “uh” of “cup”: not [æ] (cat) and not [ɑ] (father).",
  "ɹ": "Never a tap or a trill, unlike Spanish r.",
};

/** Length-marked spellings the backend emits for the same mouth ("iː" = "i"). */
const ALIASES = ["iː", "uː", "ɑː", "ɔː", "ɜː", "ɑːɹ", "ɔːɹ", "oːɹ"] as const;

function entryFor(phone: PhoneArticulation): GlossaryEntry {
  const note = NOTES[phone.symbol];
  const phenomenon = SYMBOL_PHENOMENON[phone.symbol];
  const body = note ? `${capitalize(phone.cue)} ${note}` : capitalize(phone.cue);
  return {
    title: `[${phone.symbol}] ${phone.name}`,
    body,
    example: `As in: ${phone.example}`,
    learnMore: phenomenon
      ? { href: paths.phenomenon(phenomenon), label: "See the change it belongs to" }
      : { href: paths.ipa(phone.symbol), label: "See it in the IPA chart" },
  };
}

const capitalize = (text: string): string => (text ? text[0].toUpperCase() + text.slice(1) : text);

function build(): Glossary {
  const glossary: Glossary = {};
  for (const phone of PHONE_TABLE) glossary[`ipa:${phone.symbol}`] = entryFor(phone);
  for (const alias of ALIASES) {
    const entry = glossary[`ipa:${bareSymbol(alias)}`];
    if (entry) glossary[`ipa:${alias}`] = entry;
  }
  return glossary;
}

export const IPA_GLOSSARY: Glossary = build();
