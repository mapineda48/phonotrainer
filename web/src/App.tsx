/** Armazón: lista de análisis a la izquierda, lo elegido a la derecha. */

import { useEffect, useState } from "react";

import { api } from "./api";
import { AnalysisView } from "./components/AnalysisView";
import { CorpusView } from "./components/CorpusView";
import { JobProgress } from "./components/JobProgress";
import { NewAnalysis } from "./components/NewAnalysis";
import { Sidebar } from "./components/Sidebar";
import type { Selection } from "./components/Transcript";
import { useJobs } from "./hooks/useJobs";
import { ReferenceProvider } from "./reference";
import type { Job, Reference } from "./types";

const LAST_JOB_KEY = "phonotrainer:last-job";

type Screen = "job" | "new" | "corpus";

export default function App() {
  const { jobs, loaded, error, refresh } = useJobs();
  const [reference, setReference] = useState<Reference | null>(null);
  const [referenceError, setReferenceError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [screen, setScreen] = useState<Screen>("job");
  /** Palabra a abrir al saltar desde el corpus. */
  const [jumpTo, setJumpTo] = useState<Selection | null>(null);

  useEffect(() => {
    api
      .reference()
      .then(setReference)
      .catch((err: unknown) =>
        setReferenceError(err instanceof Error ? err.message : String(err)),
      );
  }, []);

  // Al abrir: recuperamos el último análisis visto, si sigue existiendo.
  useEffect(() => {
    if (!loaded || selectedId !== null || jobs.length === 0) return;
    const remembered = window.localStorage.getItem(LAST_JOB_KEY);
    const target = jobs.find((job) => job.id === remembered) ?? jobs[0];
    setSelectedId(target.id);
  }, [loaded, jobs, selectedId]);

  const select = (id: string, selection: Selection | null = null) => {
    setSelectedId(id);
    setJumpTo(selection);
    setScreen("job");
    window.localStorage.setItem(LAST_JOB_KEY, id);
  };

  const onCreated = (job: Job) => {
    void refresh();
    select(job.id);
  };

  const selected = jobs.find((job) => job.id === selectedId) ?? null;
  const showNew = screen === "new" || (screen === "job" && (!selected || (loaded && jobs.length === 0)));

  if (referenceError) {
    return (
      <div className="empty">
        <p className="error">No se pudo hablar con el backend: {referenceError}</p>
        <p className="tiny muted">Arranca el servidor con «phonotrainer ui».</p>
      </div>
    );
  }
  if (!reference) return <div className="empty">Cargando…</div>;

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
          onChanged={() => {
            setSelectedId(null);
            void refresh();
          }}
        />
        <main className="main">
          {error && (
            <p className="error" style={{ margin: 12 }}>
              {error}
            </p>
          )}
          {screen === "corpus" ? (
            <CorpusView onOpen={(id, selection) => select(id, selection)} />
          ) : showNew ? (
            <NewAnalysis onCreated={onCreated} />
          ) : selected!.status === "done" ? (
            <AnalysisView
              key={selected!.id}
              job={selected!}
              initialSelection={jumpTo}
              onChanged={() => void refresh()}
            />
          ) : (
            <JobProgress job={selected!} onChanged={() => void refresh()} />
          )}
        </main>
      </div>
    </ReferenceProvider>
  );
}
