/** What the corpus is: how many analyses, recordings, words and minutes, which analyses
 *  make it up, and an honest warning when one recording is counted more than once. */

import { Explain } from "../../didactic/Explain";
import { fmtDuration, plural } from "../../lib/format";
import type { CorpusAnalysis, CorpusStats } from "../../types";
import { Chip, DataTable, Disclosure, Notice } from "../../ui";
import { engineName, engineOf } from "./data";
import { count } from "./ReductionSection";

export function CorpusOverview({ stats, analyses }: { stats: CorpusStats; analyses: CorpusAnalysis[] }) {
  const repeated = analyses.some((entry) => entry.duplicate_source);
  return (
    <div className="flex flex-col gap-3">
      <p className="flex flex-wrap items-center gap-2 text-base text-ink-2">
        <span>
          {count(stats.analyses, "analysis", "analyses")} of {plural(stats.materials, "recording")}
        </span>
        <span aria-hidden="true">·</span>
        <span>{stats.words.toLocaleString("en-US")} words</span>
        <span aria-hidden="true">·</span>
        <span>{fmtDuration(stats.duration)} of speech</span>
      </p>

      {repeated && (
        <Notice tone="caution" title="One recording is counted more than once">
          The same recording was analyzed more than once (for example with different settings,
          or a clip of it). Its words appear in these counts once per analysis.
        </Notice>
      )}

      <Disclosure title="What the corpus is made of" defaultExpanded={false} level={2}>
        <DataTable
          caption="Every analysis in the corpus. The corpus is an index rebuilt from your analyses."
          columns={[
            "Recording",
            "Length",
            "Words",
            <Explain key="engine" term="engine" buttonLabel="What's this: phone engine">
              Engine
            </Explain>,
            "Notes",
          ]}
          rows={analyses.map((entry) => [
            <span key="s" className="break-all text-ink">{entry.source}</span>,
            fmtDuration(entry.duration),
            entry.words.toLocaleString("en-US"),
            engineName(engineOf(entry)),
            <span key="n" className="flex flex-wrap gap-1">
              {entry.duplicate_source && <Chip tone="muted">repeated recording</Chip>}
              {!entry.attraction && engineOf(entry) === "espeak" && <Chip tone="muted">no attraction</Chip>}
              {!entry.job_id && <Chip tone="muted">indexed from the command line</Chip>}
            </span>,
          ])}
        />
      </Disclosure>
    </div>
  );
}
