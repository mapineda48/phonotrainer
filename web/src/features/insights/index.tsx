/** Insights (corpus metrics, occurrences, variants).
 *
 *  Contract with src/routes.tsx: keep this export name.
 *    InsightsPage  →  /insights?phenomenon=&word=&practice=
 *
 *  A single analysis answers "what happens in this video". This page answers what one
 *  analysis cannot: how reduced everything you have heard is (against published research,
 *  one phone engine at a time), how many of each change you have met, and how one word
 *  was actually said across every recording. */

import { LibraryBig } from "lucide-react";

import { Card, EmptyState, LinkButton, Notice, PageHeader } from "../../ui";
import { paths } from "../../paths";
import { CorpusOverview } from "./CorpusOverview";
import { useOccurrences, useOverview } from "./data";
import { OccurrencesSection } from "./OccurrencesSection";
import { PhenomenaChart } from "./PhenomenaChart";
import { useInsightsFilters } from "./query";
import { ReductionSection } from "./ReductionSection";

export function InsightsPage() {
  const [filters, setFilters] = useInsightsFilters();
  const overview = useOverview();
  const occurrences = useOccurrences(filters.phenomenon, filters.word);

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 px-4 py-6 sm:px-6">
      <PageHeader
        title="Insights"
        lede="Everything you have analyzed, together and measured against published research on spoken English."
      />

      {overview.error ? (
        <Notice tone="caution" title="The corpus could not be loaded">
          {overview.error}
        </Notice>
      ) : !overview.stats ? (
        <p className="text-base text-ink-2" aria-live="polite">
          Loading the corpus…
        </p>
      ) : overview.stats.analyses === 0 ? (
        <EmptyState
          icon={LibraryBig}
          title="Your corpus is empty"
          actions={
            <LinkButton href={paths.newAnalysis()} variant="primary">
              New analysis
            </LinkButton>
          }
        >
          Every analysis you finish is added here. With a few of them you can see how reduced
          the English you hear really is, and how each word changes from one speaker to another.
        </EmptyState>
      ) : (
        <>
          <CorpusOverview stats={overview.stats} analyses={overview.analyses} />
          {overview.metrics && <ReductionSection metrics={overview.metrics} />}
          <Card>
            <PhenomenaChart
              phenomena={overview.stats.phenomena}
              selected={filters.phenomenon}
              onSelect={(phenomenon) => setFilters({ phenomenon })}
            />
          </Card>
          <OccurrencesSection
            stats={overview.stats}
            filters={filters}
            onFilters={setFilters}
            data={occurrences}
          />
        </>
      )}
    </div>
  );
}
