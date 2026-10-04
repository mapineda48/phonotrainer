/** The glossary behind every "What's this?": general terms (terms.ts), IPA symbols
 *  (ipa.ts) and metrics (metrics.ts), merged into one lookup. */

import { IPA_GLOSSARY } from "./ipa";
import { METRIC_GLOSSARY } from "./metrics";
import { TERMS } from "./terms";
import type { Glossary, GlossaryEntry } from "./types";

export type { Glossary, GlossaryEntry } from "./types";

export const ipaTerm = (symbol: string): string => `ipa:${symbol}`;
export const metricTerm = (name: string): string => `metric:${name}`;

export const GLOSSARY: Glossary = { ...TERMS, ...IPA_GLOSSARY, ...METRIC_GLOSSARY };

export function lookupGlossary(key: string): GlossaryEntry | null {
  return GLOSSARY[key] ?? null;
}
