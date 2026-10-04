/** Analysis workspace.
 *
 *  Contract with src/routes.tsx: keep this export name and props.
 *    AnalysisPage  →  /analysis/:id   and   /analysis/:id/w/:segment/:index[?from=…]
 *
 *  A finished analysis opens the workspace (transcript + word lesson); one in flight
 *  shows its progress and opens by itself when it is done. */

import { CircleX, FileQuestion, LoaderCircle } from "lucide-react";
import { useEffect, useState } from "react";

import { api } from "../../api";
import { useJob, useJobs } from "../../hooks/useJobs";
import { paths } from "../../paths";
import { useDocumentTitle } from "../../shell/useDocumentTitle";
import type { Analysis, Job } from "../../types";
import { EmptyState, LinkButton } from "../../ui";
import { JobProgressView } from "./panes/JobProgressView";
import { Workspace } from "./Workspace";

export interface AnalysisPageProps {
  jobId: string;
  /** The word to open on entry (deep link), or null. */
  selection: { segment: number; index: number } | null;
  /** Where the learner came from ("insights", "learn", "practice"), for a back link. */
  from: string | null;
}

export function AnalysisPage({ jobId, selection, from }: AnalysisPageProps) {
  const { loaded } = useJobs();
  const job = useJob(jobId);
  useDocumentTitle(job ? job.source : "Analysis");

  if (!job) {
    if (!loaded) return <Loading text="Loading your analyses…" />;
    return (
      <EmptyState
        icon={FileQuestion}
        title="This analysis no longer exists"
        className="mt-16"
        actions={<LinkButton href={paths.library()}>Back to your analyses</LinkButton>}
      >
        It may have been deleted, or the link may be old.
      </EmptyState>
    );
  }
  if (job.status !== "done") return <JobProgressView job={job} />;
  return <AnalysisLoader job={job} selection={selection} from={from} />;
}

function Loading({ text }: { text: string }) {
  return (
    <p role="status" className="flex items-center justify-center gap-2 p-12 text-ink-2">
      <LoaderCircle size={20} aria-hidden="true" className="motion-ok:animate-spin" />
      {text}
    </p>
  );
}

function AnalysisLoader({ job, selection, from }: { job: Job } & Omit<AnalysisPageProps, "jobId">) {
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setAnalysis(null);
    setError(null);
    api
      .analysis(job.id)
      .then((result) => {
        if (!cancelled) setAnalysis(result);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      });
    return () => {
      cancelled = true;
    };
  }, [job.id]);

  if (error) {
    return (
      <EmptyState
        icon={CircleX}
        title="The analysis could not be opened"
        className="mt-16"
        actions={<LinkButton href={paths.library()}>Back to your analyses</LinkButton>}
      >
        {error}
      </EmptyState>
    );
  }
  if (!analysis) return <Loading text="Loading the analysis…" />;
  // an analysis.json of another shape would otherwise blank out the whole app
  if (!analysis.meta || !Array.isArray(analysis.segments)) {
    return (
      <EmptyState icon={CircleX} title="This analysis has an unexpected shape" className="mt-16">
        Regenerate it with “phonotrainer analyze”.
      </EmptyState>
    );
  }
  return <Workspace job={job} analysis={analysis} selection={selection} from={from} />;
}
