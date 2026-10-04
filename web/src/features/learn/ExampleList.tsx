/** Examples from the learner's own clips: play the word, compare the dictionary form with
 *  what was said, and jump to that word's lesson. */

import { ArrowRight } from "lucide-react";

import { ClipPlayer } from "../../audio/ClipPlayer";
import { paths } from "../../paths";
import { useReference } from "../../reference";
import type { Occurrence } from "../../types";
import { Ipa, TextLink } from "../../ui";
import { clipPadding, displayWord, stripStress } from "./corpus";

interface Props {
  examples: readonly (Occurrence & { job_id: string })[];
  /** The phenomenon being taught: decides whether the clip runs into the next word. */
  phenomenon?: string;
  /** Accessible name of the list. */
  label: string;
}

export function ExampleList({ examples, phenomenon, label }: Props) {
  const reference = useReference();
  return (
    <ul aria-label={label} className="flex flex-col divide-y divide-line">
      {examples.map((example) => {
        const family = phenomenon ? reference.family_of[phenomenon] : null;
        const words =
          family === "boundary" && example.next_word
            ? `${displayWord(example.word)} ${displayWord(example.next_word)}`
            : displayWord(example.word);
        return (
          <li
            key={`${example.analysis_id}:${example.segment}:${example.word_idx}`}
            className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3"
          >
            <ClipPlayer
              jobId={example.job_id}
              start={example.start}
              end={example.end}
              {...clipPadding(family)}
              label={`Play “${words}”`}
              size="sm"
            />
            <span className="min-w-28 font-semibold text-ink">{words}</span>
            <span className="flex flex-wrap items-center gap-2 text-base">
              {example.dict_ipa && (
                <>
                  <span className="sr-only">Dictionary:</span>
                  <Ipa kind="phonemic">{stripStress(example.dict_ipa)}</Ipa>
                  <ArrowRight size={14} aria-hidden="true" className="text-ink-muted" />
                </>
              )}
              <span className="sr-only">said:</span>
              <Ipa kind="phonetic">{example.realized_ipa || "∅"}</Ipa>
            </span>
            <span className="ml-auto text-sm">
              <TextLink href={paths.word(example.job_id, example.segment, example.word_idx, "learn")}>
                Open in its lesson
                <span className="sr-only"> ({words}, {example.analysis_source})</span>
              </TextLink>
            </span>
          </li>
        );
      })}
    </ul>
  );
}
