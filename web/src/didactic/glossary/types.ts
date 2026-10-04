/** One "What's this?" entry. Plain English for an adult learner whose first language is
 *  not English: short sentences, one idea each, an example where it helps. */
export interface GlossaryEntry {
  /** The term as the learner sees it ("Weak form"). */
  title: string;
  /** One to three sentences. IPA inside /…/ or […], or after "→", is set in the IPA font. */
  body: string;
  /** An example line ("to → tə"); IPA after "→" is set in the IPA font. */
  example?: string;
  /** Where to read more inside the app. */
  learnMore?: { href: string; label?: string };
}

export type Glossary = Record<string, GlossaryEntry>;
