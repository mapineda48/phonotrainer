/** /learn — every change native speakers make, grouped by family, each with the report's
 *  advice (copy it or just recognize it) and how often it occurs in the learner's clips. */

import { Ear, Grid3x3, Headphones, MousePointerClick } from "lucide-react";

import { paths } from "../../paths";
import { phenomenonDescription, phenomenonLabel, phenomenonPractice, useReference } from "../../reference";
import {
  FamilyIcon,
  FamilySwatch,
  LinkButton,
  markOfPhenomenon,
  PageHeader,
  PhenomenonIcon,
  PracticeBadge,
  RichText,
  TextLink,
  familyUnderlineClass,
} from "../../ui";
import { capitalize, phenomenonCounts, useCorpusStats } from "./corpus";

/** Labels that belong to no family but still deserve a page. */
const OUTSIDE_FAMILIES = ["contraction_lex", "word_elision"];

function PhenomenonCard({ name, count }: { name: string; count: number | null }) {
  const reference = useReference();
  const label = capitalize(phenomenonLabel(reference, name));
  // the mark, not family_of: an elided word must not look like a contraction
  const mark = markOfPhenomenon(name, reference.family_of);
  return (
    <li className="flex flex-col gap-2 rounded-card bg-surface p-4 shadow-1">
      <h3 className="flex items-center gap-2 text-lg font-semibold text-ink">
        <PhenomenonIcon name={name} size={18} />
        <TextLink href={paths.phenomenon(name)}>
          <span className={familyUnderlineClass(mark)}>{label}</span>
        </TextLink>
      </h3>
      <p className="text-sm text-ink-2">
        <RichText text={phenomenonDescription(reference, name)} />
      </p>
      <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
        <PracticeBadge practice={phenomenonPractice(reference, name)} showRegister />
        {count !== null && (
          <span className="text-xs text-ink-2">
            {count === 0 ? "Not in your clips yet" : `${count} in your clips`}
          </span>
        )}
      </div>
    </li>
  );
}

export function LearnHome() {
  const reference = useReference();
  const stats = useCorpusStats();
  const counts = phenomenonCounts(stats);
  const countOf = (name: string) => (stats.status === "ready" ? (counts.get(name) ?? 0) : null);
  const others = OUTSIDE_FAMILIES.filter((name) => name in reference.labels);

  return (
    <div className="mx-auto w-full max-w-6xl px-6 py-8">
      <PageHeader
        title="Learn"
        lede="How native speakers change words when they talk — and which of those changes you can copy."
        actions={
          <>
            <LinkButton href={paths.ipa()} icon={Grid3x3} variant="secondary">
              IPA chart
            </LinkButton>
            <LinkButton href={paths.practice()} icon={Ear}>
              Practice
            </LinkButton>
          </>
        }
      />

      <section aria-labelledby="learn-how" className="mb-8 rounded-card bg-surface-2 p-4">
        <h2 id="learn-how" className="mb-2 text-base font-semibold text-ink">
          How to study a change
        </h2>
        <ul className="grid gap-3 text-sm text-ink-2 sm:grid-cols-3">
          <li className="flex gap-2">
            <MousePointerClick size={18} aria-hidden="true" className="mt-0.5 shrink-0 text-ink" />
            <span>
              <strong className="text-ink">1. Read what happens</strong> and whether the report says you can copy it
              or should only learn to recognize it.
            </span>
          </li>
          <li className="flex gap-2">
            <Headphones size={18} aria-hidden="true" className="mt-0.5 shrink-0 text-ink" />
            <span>
              <strong className="text-ink">2. Listen to examples</strong> from your own clips, slowly if you need to.
            </span>
          </li>
          <li className="flex gap-2">
            <Ear size={18} aria-hidden="true" className="mt-0.5 shrink-0 text-ink" />
            <span>
              <strong className="text-ink">3. Train your ear</strong> in Practice until you hear it without thinking.
            </span>
          </li>
        </ul>
      </section>

      {reference.families.map((family) => (
        <section key={family.key} aria-labelledby={`family-${family.key}`} className="mb-8">
          <h2 id={`family-${family.key}`} className="mb-3 flex items-center gap-2 text-xl font-semibold text-ink">
            <FamilyIcon family={family.key} size={20} />
            {family.label}
            <FamilySwatch family={family.key} />
          </h2>
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {family.members.map((name) => (
              <PhenomenonCard key={name} name={name} count={countOf(name)} />
            ))}
          </ul>
        </section>
      ))}

      {others.length > 0 && (
        <section aria-labelledby="family-other" className="mb-8">
          <h2 id="family-other" className="mb-3 flex items-center gap-2 text-xl font-semibold text-ink">
            <FamilyIcon family="none" size={20} />
            Whole-word changes
          </h2>
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {others.map((name) => (
              <PhenomenonCard key={name} name={name} count={countOf(name)} />
            ))}
          </ul>
        </section>
      )}

      {stats.status === "error" && (
        <p className="text-sm text-ink-2">Your clip counts could not be loaded; the explanations still work.</p>
      )}
    </div>
  );
}
