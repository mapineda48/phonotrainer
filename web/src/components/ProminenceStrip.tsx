/** Which words of a phrase stood out (F0 peak × intensity × duration, scaled to
 *  the segment's peak) and whether each one is a content or a function word.
 *  English stresses content words and reduces function words (report §4): a tall
 *  bar over "the" is contrast, not the norm. */

import type { Segment } from "../types";

const MAX_BAR = 22; // px, the segment's most prominent word

export function ProminenceStrip({ segment }: { segment: Segment }) {
  const prominence = segment.prominence;
  if (!prominence || prominence.length !== segment.words.length) return null;
  const classes = segment.word_classes;

  return (
    <div className="prom" data-testid="prominence-strip">
      <div className="prom__row" role="list" aria-label="Prominence of each word">
        {segment.words.map((word, index) => {
          const value = prominence[index] ?? 0;
          const kind = classes?.[index];
          const pct = Math.round(value * 100);
          return (
            <span
              key={`${index}-${word.start}`}
              className="prom__cell"
              role="listitem"
              title={`${word.word} — ${pct} % of the phrase's peak${kind ? ` · ${kind} word` : ""}`}
            >
              <span
                className={`prom__bar ${kind === "function" ? "prom__bar--function" : ""}`}
                style={{ height: Math.max(2, value * MAX_BAR) }}
                aria-label={`${word.word}: ${pct} %${kind ? `, ${kind} word` : ""}`}
              />
              <span className="prom__word">{word.word}</span>
            </span>
          );
        })}
      </div>
      {classes && (
        <div className="prom__legend tiny muted">
          <span className="prom__key" aria-hidden="true" /> content word
          <span className="prom__key prom__key--function" aria-hidden="true" /> function word
        </div>
      )}
    </div>
  );
}
