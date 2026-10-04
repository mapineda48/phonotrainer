/** Where each symbol of the inventory sits in the IPA chart. The symbols themselves come
 *  from the articulation table (web/src/articulation/phones.ts); anything the layout below
 *  does not place is still shown, in an "Other symbols" group, so the chart never drops a
 *  sound — and a test fails until the new symbol gets its proper place. */

import { PHONE_TABLE } from "../../articulation/phones";

export const PLACES = [
  { key: "bilabial", label: "Lips", detail: "bilabial" },
  { key: "labiodental", label: "Lip + teeth", detail: "labiodental" },
  { key: "dental", label: "Teeth", detail: "dental" },
  { key: "alveolar", label: "Gum ridge", detail: "alveolar" },
  { key: "postalveolar", label: "Behind the ridge", detail: "postalveolar" },
  { key: "palatal", label: "Hard palate", detail: "palatal" },
  { key: "velar", label: "Soft palate", detail: "velar" },
  { key: "glottal", label: "Throat", detail: "glottal" },
] as const;

export type Place = (typeof PLACES)[number]["key"];

export interface GridRow<C extends string> {
  label: string;
  /** Plain-English explanation of the row, for the row header's description. */
  detail: string;
  cells: Partial<Record<C, readonly string[]>>;
}

export const CONSONANT_ROWS: readonly GridRow<Place>[] = [
  {
    label: "Stop",
    detail: "the air is stopped completely, then released",
    cells: { bilabial: ["p", "b"], alveolar: ["t", "d"], velar: ["k", "ɡ"] },
  },
  { label: "Affricate", detail: "a stop that opens into a hiss", cells: { postalveolar: ["tʃ", "dʒ"] } },
  {
    label: "Fricative",
    detail: "the air hisses through a narrow gap",
    cells: {
      labiodental: ["f", "v"],
      dental: ["θ", "ð"],
      alveolar: ["s", "z"],
      postalveolar: ["ʃ", "ʒ"],
      glottal: ["h"],
    },
  },
  { label: "Nasal", detail: "the air goes out through the nose", cells: { bilabial: ["m"], alveolar: ["n"], velar: ["ŋ"] } },
  {
    label: "Approximant",
    detail: "the tongue or lips come close without touching",
    cells: { bilabial: ["w"], alveolar: ["ɹ"], palatal: ["j"] },
  },
  { label: "Lateral", detail: "the air flows around the sides of the tongue", cells: { alveolar: ["l"] } },
];

/** The sounds American English uses INSTEAD of a plain t or d (and their relatives). */
export const ALLOPHONE_PLACES: readonly Place[] = ["bilabial", "alveolar", "velar", "glottal"];

export const ALLOPHONE_ROWS: readonly GridRow<Place>[] = [
  {
    label: "Unreleased stop",
    detail: "the closure is made but never opened",
    cells: { bilabial: ["p̚", "b̚"], alveolar: ["t̚", "d̚"], velar: ["k̚", "ɡ̚"] },
  },
  { label: "Tap", detail: "one very quick touch of the tongue", cells: { alveolar: ["ɾ", "ɾ̃"] } },
  { label: "Glottal stop", detail: "the throat closes for an instant", cells: { glottal: ["ʔ"] } },
];

export const SYLLABICS: readonly string[] = ["n̩", "m̩", "ŋ̍", "l̩", "əl"];

export const VOWEL_COLUMNS = [
  { key: "front", label: "Front" },
  { key: "central", label: "Central" },
  { key: "back", label: "Back" },
] as const;

export type VowelColumn = (typeof VOWEL_COLUMNS)[number]["key"];

export const VOWEL_ROWS: readonly GridRow<VowelColumn>[] = [
  { label: "Close", detail: "tongue high, mouth almost closed", cells: { front: ["i"], back: ["u"] } },
  { label: "Near-close", detail: "", cells: { front: ["ɪ"], central: ["ᵻ"], back: ["ʊ"] } },
  { label: "Mid", detail: "the relaxed middle of the mouth", cells: { central: ["ə", "ɚ", "ɝ"] } },
  { label: "Open-mid", detail: "", cells: { front: ["ɛ"], central: ["ɜ"], back: ["ʌ", "ɔ"] } },
  { label: "Near-open", detail: "", cells: { front: ["æ"], central: ["ɐ"] } },
  { label: "Open", detail: "tongue low, mouth open", cells: { back: ["ɑ", "ɒ"] } },
];

/** The weak vowels unstressed syllables reduce to. */
export const REDUCED_VOWELS: ReadonlySet<string> = new Set(["ə", "ɐ", "ᵻ", "ɚ"]);

export const GLIDING_GROUPS: readonly { label: string; detail: string; symbols: readonly string[] }[] = [
  { label: "Diphthongs", detail: "the tongue glides from one vowel to another", symbols: ["eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "ju"] },
  { label: "Vowels with r", detail: "a vowel that ends with the tongue in the r position", symbols: ["ɪɹ", "ɛɹ", "ʊɹ", "ɑɹ", "ɔɹ", "oɹ", "aɪɚ"] },
  { label: "Non-rhotic glides", detail: "heard in accents without r, and in a few words", symbols: ["iə", "eə", "aɪə"] },
];

function cellsOf<C extends string>(rows: readonly GridRow<C>[]): string[] {
  return rows.flatMap((row) => Object.values(row.cells).flatMap((cell) => [...((cell as readonly string[]) ?? [])]));
}

/** Every symbol the layout places. */
export function placedSymbols(): string[] {
  return [
    ...cellsOf(CONSONANT_ROWS),
    ...cellsOf(ALLOPHONE_ROWS),
    ...SYLLABICS,
    ...cellsOf(VOWEL_ROWS),
    ...GLIDING_GROUPS.flatMap((group) => group.symbols),
  ];
}

/** Symbols of the articulation table the layout does not place (should be none). */
export function unplacedSymbols(): string[] {
  const placed = new Set(placedSymbols());
  return PHONE_TABLE.map((phone) => phone.symbol).filter((symbol) => !placed.has(symbol));
}
