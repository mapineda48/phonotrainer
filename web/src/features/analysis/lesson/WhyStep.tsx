/** Lesson step 3 — Why: one card per phenomenon on the word, each with what it is, the
 *  report's advice (safe to produce, or recognize only) and its register, how it was
 *  found, and where to see more. Low-confidence words say so first, calmly. */

import { ArrowRight, Sparkles } from "lucide-react";

import { Explain } from "../../../didactic/Explain";
import { paths } from "../../../paths";
import { LINK_TYPE_LABEL, phenomenonDescription, useReference } from "../../../reference";
import type { Segment, Word } from "../../../types";
import { Notice, PhenomenonBadge, PracticeBadge, RichText, TextLink } from "../../../ui";
import { adviceFor, hasNoCanonical, reliability } from "../lib/words";

interface Props {
  word: Word;
  segment: Segment;
  wordIndex: number;
  narrow: boolean;
}

function FormScoring({ word }: { word: Word }) {
  const form = word.form;
  if (!form) return null;
  return (
    <p className="text-sm text-ink-2" data-testid="form-scoring">
      Form scoring heard the <strong className="text-ink">{form.weak ? "weak" : "strong"} form</strong>{" "}
      <span lang="und-fonipa" className="ipa-text text-ink">
        [{form.ipa}]
      </span>
      {form.weak_margin != null && (
        <>
          {" "}
          — the weak form scored {form.weak_margin >= 0 ? "ahead by" : "behind by"}{" "}
          {Math.abs(form.weak_margin).toFixed(1)} against the citation form{" "}
          <span lang="und-fonipa" className="ipa-text">
            [{form.strong_ipa}]
          </span>
        </>
      )}
      {form.h_dropped && <> · the /h/ was dropped</>}.
    </p>
  );
}

function PhenomenonCard({ word, name, narrow }: { word: Word; name: string; narrow: boolean }) {
  const reference = useReference();
  const description = phenomenonDescription(reference, name);
  const advice = adviceFor(reference, word, name);
  const fromScoring = word.variant_labels?.includes(name) ?? false;
  const isLexical = name === "contraction_lex";
  const isLink = name === "linking" && word.boundary_link_type;

  return (
    <li className="flex flex-col gap-2 rounded-card bg-surface p-3 shadow-[inset_0_0_0_1px_var(--line)]">
      <div className="flex flex-wrap items-center gap-2">
        <PhenomenonBadge name={name} size="md" tone="tint" />
        <Explain phenomenon={name} />
      </div>
      {description && (
        <p className="text-sm text-ink">
          <RichText text={description} />
        </p>
      )}
      {isLexical && word.lexical_form && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 text-sm">
          <dt className="text-ink-2">reduced form</dt>
          <dd className="m-0 text-ink">
            “{word.lexical_form}”
            {word.lexical_expansion && <span className="text-ink-2"> ← “{word.lexical_expansion}”</span>}
          </dd>
        </dl>
      )}
      {isLink && word.boundary_link_type && (
        <p className="text-sm text-ink-2">
          Kind of link: <strong className="text-ink" data-testid="link-type">{LINK_TYPE_LABEL[word.boundary_link_type]}</strong>
        </p>
      )}
      {advice && (
        <div className="flex flex-col items-start gap-1">
          <PracticeBadge practice={advice} showRegister size="md" />
          {advice.why && <p className="text-sm text-ink-2">{advice.why}</p>}
        </div>
      )}
      {fromScoring && (
        <p className="inline-flex items-start gap-1.5 text-sm text-ink-2">
          <Sparkles size={15} aria-hidden="true" className="mt-0.5 shrink-0" />
          <span>
            <strong className="text-ink">From form scoring:</strong> not visible in the recognized sounds — the weak
            form matched this audio better than the strong one.
          </span>
        </p>
      )}
      {fromScoring && !narrow && <FormScoring word={word} />}
      <TextLink href={paths.phenomenon(name)} className="inline-flex min-h-6 w-fit items-center gap-1 text-sm">
        More examples in Learn <ArrowRight size={14} aria-hidden="true" />
      </TextLink>
    </li>
  );
}

export function WhyStep({ word, segment, wordIndex, narrow }: Props) {
  const status = reliability(word);
  const noCanonical = hasNoCanonical(word);
  const prominence = segment.prominence?.[wordIndex] ?? null;
  const wordClass = segment.word_classes?.[wordIndex] ?? null;

  const facts: React.ReactNode[] = [];
  if (word.oov) facts.push("Not in the dictionary: its dictionary form was guessed from the spelling.");
  if (word.alignment_fallback) facts.push("Approximate timing: the sounds were placed using the transcript's word times.");
  if (word.attracted_count > 0)
    facts.push(
      <>
        {word.attracted_count} sound{word.attracted_count === 1 ? " was" : "s were"} kept as expected after a
        recognizer confusion (<Explain term="attraction">attraction</Explain>).
      </>,
    );

  return (
    <div className="flex flex-col gap-3">
      {status === "no-phones" && (
        <Notice tone="caution" title="Nothing was heard here">
          The recognizer found no sounds for this word — it may be silence, laughter or music. The labels below are not
          verifiable.
        </Notice>
      )}
      {status === "low-confidence" && (
        <Notice tone="caution" title="Low confidence">
          The recognizer was unsure over this stretch. Read the labels with caution.{" "}
          <Explain term="low-confidence" />
        </Notice>
      )}

      {word.phenomena.length === 0 ? (
        <p className="text-sm text-ink">
          {noCanonical
            ? "With no dictionary form to compare against, no change can be labeled here."
            : word.realized_ipa === word.dict_ipa
              ? "Nothing changed: this word was said the way the dictionary says it."
              : "No labeled change. The sounds may still differ slightly from the dictionary form — compare them in step 2."}
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {word.phenomena.map((name) => (
            <PhenomenonCard key={name} word={word} name={name} narrow={narrow} />
          ))}
        </ul>
      )}

      {word.form && !word.variant_labels?.length && <FormScoring word={word} />}

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        {prominence != null && (
          <>
            <dt className="text-ink-2">
              <Explain term="prominence">Prominence</Explain>
            </dt>
            <dd className="m-0 text-ink" data-testid="word-prominence">
              {Math.round(prominence * 100)} % of the phrase's peak
              {wordClass && (
                <>
                  {" "}
                  · <Explain term={wordClass === "function" ? "function-word" : "content-word"}>{wordClass} word</Explain>
                </>
              )}
              {wordClass === "function" && prominence >= 0.999 && (
                <span className="text-ink-2"> — the peak fell on a grammar word: contrast or emphasis</span>
              )}
            </dd>
          </>
        )}
        <dt className="text-ink-2">
          <Explain term="divergence">Divergence</Explain>
        </dt>
        <dd className="m-0 tabular-nums text-ink">
          {noCanonical ? "no dictionary form" : `${word.diff_cost.toFixed(2)} (0 = identical)`}
        </dd>
      </dl>

      {facts.length > 0 && (
        <ul className="list-disc ps-5 text-sm text-ink-2">
          {facts.map((fact, index) => (
            <li key={index}>{fact}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
