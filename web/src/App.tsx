/** App shell: the analysis list on the left, whatever is selected on the right. */

import { useEffect, useState } from "react";

import { api, REQUIRED_API_VERSION } from "./api";
import { AnalysisView } from "./components/AnalysisView";
import { CorpusView, type CorpusFilters } from "./components/CorpusView";
import { JobProgress } from "./components/JobProgress";
import { NewAnalysis } from "./components/NewAnalysis";
import { Sidebar } from "./components/Sidebar";
import type { Selection } from "./components/Transcript";
import { useJobs } from "./hooks/useJobs";
import { useJobsChannel } from "./jobs/JobsProvider";
import { ReferenceProvider } from "./reference";
import type { Job, Reference } from "./types";

const LAST_JOB_KEY = "phonotrainer:last-job";

type Screen = "job" | "new" | "corpus";

export default function App() {
  const { jobs, loaded, error } = useJobs();
  const channel = useJobsChannel();
  const [reference, setReference] = useState<Reference | null>(null);
  const [referenceError, setReferenceError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [screen, setScreen] = useState<Screen>("job");
  /** Word to open when jumping in from the corpus. */
  const [jumpTo, setJumpTo] = useState<Selection | null>(null);
  /** Corpus filters: they survive a round trip into an analysis and back. */
  const [corpusFilters, setCorpusFilters] = useState<CorpusFilters>({
    phenomenon: null,
    word: "",
  });
  const [cameFromCorpus, setCameFromCorpus] = useState(false);

  useEffect(() => {
    api
      .reference()
      .then(setReference)
      .catch((err: unknown) =>
        setReferenceError(err instanceof Error ? err.message : String(err)),
      );
  }, []);

  // On open: restore the last analysis viewed, if it still exists.
  useEffect(() => {
    if (!loaded || selectedId !== null || jobs.length === 0) return;
    const remembered = window.localStorage.getItem(LAST_JOB_KEY);
    const target = jobs.find((job) => job.id === remembered) ?? jobs[0];
    setSelectedId(target.id);
  }, [loaded, jobs, selectedId]);

  const select = (id: string, selection: Selection | null = null) => {
    setSelectedId(id);
    setJumpTo(selection);
    setCameFromCorpus(selection !== null);
    setScreen("job");
    window.localStorage.setItem(LAST_JOB_KEY, id);
  };

  const onCreated = (job: Job) => {
    // The POST response is applied right away; the server event confirms it.
    channel.upsert(job);
    select(job.id);
  };

  const selected = jobs.find((job) => job.id === selectedId) ?? null;
  const showNew = screen === "new" || (screen === "job" && (!selected || (loaded && jobs.length === 0)));

  if (referenceError) {
    return (
      <div className="empty">
        <p className="error">Could not reach the backend: {referenceError}</p>
        <p className="tiny muted">Start the server with “phonotrainer ui”.</p>
      </div>
    );
  }
  if (!reference) return <div className="empty">Loading…</div>;
  // The UI is served from disk and is always up to date; the process answering
  // may be an old one left running on that port. Without this warning, the UI
  // would request routes that server does not have and the user would only see
  // "Method Not Allowed".
  if ((reference.api_version ?? 0) < REQUIRED_API_VERSION) {
    return (
      <div className="empty">
        <p className="error">
          The server answering on this port is older than this interface.
        </p>
        <p className="tiny muted">
          An earlier <code>phonotrainer ui</code> was probably left running. Stop it (Ctrl-C) and
          start it again, or use the new one's port.
        </p>
      </div>
    );
  }

  return (
    <ReferenceProvider value={reference}>
      <div className="app">
        <Sidebar
          jobs={jobs}
          selectedId={screen === "job" && !showNew ? selectedId : null}
          onSelect={(id) => select(id)}
          onNew={() => setScreen("new")}
          onCorpus={() => setScreen("corpus")}
          corpusOpen={screen === "corpus"}
          onChanged={() => setSelectedId(null)}
        />
        <main className="main">
          {error && (
            <p className="error" style={{ margin: 12 }}>
              {error}
            </p>
          )}
          {screen === "corpus" ? (
            <CorpusView
              onOpen={(id, selection) => select(id, selection)}
              filters={corpusFilters}
              onFilters={setCorpusFilters}
            />
          ) : showNew ? (
            <NewAnalysis onCreated={onCreated} />
          ) : selected!.status === "done" ? (
            <AnalysisView
              key={selected!.id}
              job={selected!}
              initialSelection={jumpTo}
              onBackToCorpus={cameFromCorpus ? () => setScreen("corpus") : undefined}
            />
          ) : (
            <JobProgress job={selected!} />
          )}
        </main>
      </div>
    </ReferenceProvider>
  );
}
