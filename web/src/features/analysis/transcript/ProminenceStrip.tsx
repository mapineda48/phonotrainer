/** Which words of a phrase stood out (pitch × loudness × length, scaled to the phrase's
 *  peak), and whether each is a content or a function word. English stresses content
 *  words and reduces grammar words (report §4): a tall bar over "the" is contrast or
 *  emphasis, not the norm. Content and function words differ by fill (solid vs
 *  outlined) and by name, never by hue. */

import { Explain } from "../../../didactic/Explain";
import type { Segment } from "../../../types";
import { cn } from "../../../ui";

const MAX_BAR = 28; // px for the phrase's most prominent word

export function ProminenceStrip({ segment }: { segment: Segment }) {
  const prominence = segment.prominence;
  if (!prominence || prominence.length !== segment.words.length) return null;
  const classes = segment.word_classes;

  return (
    <div data-testid="prominence-strip" className="flex flex-col gap-2">
      <p className="text-sm font-semibold text-ink">
        <Explain term="prominence">Prominence</Explain>
      </p>
      <ul aria-label="Prominence of each word" className="flex flex-wrap items-end gap-x-2 gap-y-3">
        {segment.words.map((word, index) => {
          const value = Math.max(0, Math.min(1, prominence[index] ?? 0));
          const kind = classes?.[index];
          const pct = Math.round(value * 100);
          return (
            <li
              key={`${index}-${word.start}`}
              aria-label={`${word.word}: ${pct} %${kind ? `, ${kind} word` : ""}`}
              className="flex flex-col items-center gap-1"
            >
              <span className="flex h-7 items-end" aria-hidden="true">
                <span
                  className={cn(
                    "block w-3 rounded-t-[3px]",
                    kind === "function" ? "bg-surface shadow-[inset_0_0_0_1.5px_var(--ink-2)]" : "bg-ink-2",
                  )}
                  style={{ height: Math.max(3, value * MAX_BAR) }}
                />
              </span>
              <span aria-hidden="true" className="text-xs text-ink-2">
                {word.word}
              </span>
            </li>
          );
        })}
      </ul>
      {classes && (
        <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-2">
          <span className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className="block h-3 w-3 rounded-[2px] bg-ink-2" />
            <Explain term="content-word">content word</Explain>
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className="block h-3 w-3 rounded-[2px] bg-surface shadow-[inset_0_0_0_1.5px_var(--ink-2)]" />
            <Explain term="function-word">function word</Explain>
          </span>
        </p>
      )}
    </div>
  );
}
