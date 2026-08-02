/** Armazón: lista de análisis a la izquierda, lo elegido a la derecha. */

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
  /** Palabra a abrir al saltar desde el corpus. */
  const [jumpTo, setJumpTo] = useState<Selection | null>(null);
  /** Filtros del corpus: sobreviven al ir y volver de un análisis. */
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
    setCameFromCorpus(selection !== null);
    setScreen("job");
    window.localStorage.setItem(LAST_JOB_KEY, id);
  };

  const onCreated = (job: Job) => {
    // La respuesta del POST se aplica ya; el evento del servidor la confirma.
    channel.upsert(job);
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
  // La interfaz se sirve desde disco y siempre está al día; el proceso que
  // responde puede ser uno viejo que quedó abierto en ese puerto. Sin este
  // aviso, la interfaz pedía rutas que ese servidor no tiene y el usuario solo
  // veía «Method Not Allowed».
  if ((reference.api_version ?? 0) < REQUIRED_API_VERSION) {
    return (
      <div className="empty">
        <p className="error">
          El servidor que responde en este puerto es más antiguo que esta interfaz.
        </p>
        <p className="tiny muted">
          Seguramente quedó abierto un <code>phonotrainer ui</code> de antes. Párralo (Ctrl-C) y
          vuelve a arrancarlo, o usa el puerto del nuevo.
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
