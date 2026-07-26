/** Vista de un análisis terminado: reproductor + transcripción + panel lateral. */

import { useEffect, useMemo, useState } from "react";

import { api } from "../api";
import { useHotkeys } from "../hooks/useHotkeys";
import { filteredWords, flattenWords, wordSpan, type FlatWord } from "../lib/analysis";
import { PlayerProvider, usePlayer } from "../player/PlayerProvider";
import type { Analysis, Job } from "../types";
import { PlayerBar } from "./PlayerBar";
import { ReviewPanel } from "./ReviewPanel";
import { SummaryPanel } from "./SummaryPanel";
import { Transcript, type Selection } from "./Transcript";
import { VideoPane } from "./VideoPane";
import { Waveform } from "./Waveform";
import { WordDetail } from "./WordDetail";

type Tab = "word" | "summary" | "review";

interface Props {
  job: Job;
  onChanged: () => void;
}

export function AnalysisView({ job, onChanged }: Props) {
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
      <div className="empty">
        <p className="error">{error}</p>
      </div>
    );
  }
  if (!analysis) {
    return (
      <div className="empty">
        <p>Cargando análisis…</p>
      </div>
    );
  }

  return (
    <PlayerProvider src={job.has_audio ? api.audioUrl(job.id) : null}>
      <AnalysisBody job={job} analysis={analysis} onChanged={onChanged} />
    </PlayerProvider>
  );
}

function AnalysisBody({
  job,
  analysis,
  onChanged,
}: {
  job: Job;
  analysis: Analysis;
  onChanged: () => void;
}) {
  const player = usePlayer();
  const [selected, setSelected] = useState<Selection | null>(null);
  const [tab, setTab] = useState<Tab>("summary");
  const [filter, setFilter] = useState<ReadonlySet<string>>(new Set());
  const [follow, setFollow] = useState(true);
  const [showVideo, setShowVideo] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  const [query, setQuery] = useState("");

  const duration = analysis.meta.duration;
  const canPlay = job.has_audio;

  /** Palabras por las que navegan N / Mayús+N: las del filtro, o todas. */
  const walk = useMemo(() => {
    const byFilter = filteredWords(analysis, filter);
    const base = byFilter.length > 0 ? byFilter : flattenWords(analysis);
    const needle = query.trim().toLowerCase();
    return needle ? base.filter((fw) => fw.word.word.toLowerCase().includes(needle)) : base;
  }, [analysis, filter, query]);

  const selectedWord = selected ? analysis.segments[selected.segment]?.words[selected.index] : null;
  const selectedSegment = selected ? analysis.segments[selected.segment] : null;
  const nextWord = selected
    ? (analysis.segments[selected.segment]?.words[selected.index + 1] ?? null)
    : null;

  const select = (next: Selection) => {
    setSelected(next);
    setTab("word");
  };

  /** Salta a la siguiente palabra de `list` (o a la anterior) y la reproduce. */
  const jumpIn = (list: FlatWord[], delta: number) => {
    if (list.length === 0) return;
    const now = player.clock.getSnapshot();
    let index: number;
    if (delta < 0) {
      const previous = [...list].reverse().find((match) => match.word.end < now - 0.01);
      index = previous ? list.indexOf(previous) : list.length - 1;
    } else {
      const found = list.findIndex((match) => match.word.start > now + 0.01);
      index = found === -1 ? 0 : found;
    }
    const target = list[index];
    select({ segment: target.segment, index: target.index });
    if (canPlay) player.play(wordSpan(target.word));
    else player.seek(target.word.start);
  };

  const jump = (delta: number) => jumpIn(walk, delta);

  const toggleFilter = (phenomenon: string) =>
    setFilter((current) => {
      const next = new Set(current);
      if (next.has(phenomenon)) next.delete(phenomenon);
      else next.add(phenomenon);
      return next;
    });

  useHotkeys({
    " ": () => player.toggle(),
    ArrowRight: () => player.seek(player.clock.getSnapshot() + 2),
    ArrowLeft: () => player.seek(player.clock.getSnapshot() - 2),
    l: () => player.setLoop(!player.loop),
    f: () => setFollow((value) => !value),
    n: () => jump(1),
    N: () => jump(-1),
    p: () => selectedWord && player.play(wordSpan(selectedWord)),
    s: () =>
      selectedSegment &&
      player.play({ start: selectedSegment.start, end: selectedSegment.end }),
    "?": () => setShowHelp((value) => !value),
  });

  const spanLabel = player.span
    ? selectedWord && Math.abs(player.span.start - wordSpan(selectedWord).start) < 0.01
      ? `palabra «${selectedWord.word}»`
      : "fragmento"
    : null;

  return (
    <>
      <header className="topbar">
        <h2 className="topbar__title" title={job.source}>
          {job.source}
        </h2>
        <span className="tiny muted">
          {analysis.meta.duration.toFixed(1)} s · {analysis.segments.length} segmentos ·{" "}
          {analysis.meta.language}
          {analysis.meta.attraction ? "" : " · sin atracción"}
        </span>
        <span className="spacer" />
        <label className="row" style={{ gap: 4 }}>
          <span className="sr-only">Buscar una palabra en la transcripción</span>
          <input
            type="search"
            className="input"
            style={{ width: 150 }}
            placeholder="Buscar palabra…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                jump(1);
              }
            }}
          />
        </label>
        {query.trim() && (
          <span className="tiny muted num">{walk.length} coincidencias</span>
        )}
        {job.is_video && job.has_media && (
          <button
            type="button"
            className="btn btn--sm"
            aria-pressed={showVideo}
            onClick={() => setShowVideo((value) => !value)}
          >
            Video
          </button>
        )}
        <button
          type="button"
          className="btn btn--sm"
          aria-pressed={follow}
          title="Seguir la reproducción y desplazar la transcripción (F)"
          onClick={() => setFollow((value) => !value)}
        >
          Seguir
        </button>
        {job.has_report && (
          <a
            className="btn btn--sm"
            href={api.reportUrl(job.id)}
            target="_blank"
            rel="noreferrer"
          >
            report.html
          </a>
        )}
        <button
          type="button"
          className="btn btn--sm"
          aria-pressed={showHelp}
          title="Atajos de teclado (?)"
          onClick={() => setShowHelp((value) => !value)}
        >
          ?
        </button>
      </header>

      {showHelp && <Shortcuts onClose={() => setShowHelp(false)} />}

      <PlayerBar duration={duration} spanLabel={spanLabel} enabled={canPlay}>
        {canPlay && <Waveform src={api.audioUrl(job.id)} duration={duration} />}
      </PlayerBar>

      {!job.has_audio && (
        <p className="tiny muted" style={{ padding: "6px 16px" }}>
          Este análisis se importó sin <code>audio.wav</code>: se puede leer, pero no escuchar.
        </p>
      )}

      <a className="skip" href="#panel">
        Saltar al panel de detalle
      </a>

      <div className="workspace">
        <div style={{ display: "flex", flexDirection: "column", minHeight: 0 }}>
          {showVideo && job.has_media && (
            <div style={{ padding: "10px 16px 0" }}>
              <VideoPane src={api.mediaUrl(job.id)} />
            </div>
          )}
          <Transcript
            analysis={analysis}
            selected={selected}
            onSelect={select}
            filter={filter}
            follow={follow}
          />
        </div>

        <aside className="aside" id="panel" tabIndex={-1}>
          <div className="tabs" role="tablist">
            <button
              type="button"
              role="tab"
              className="tab"
              aria-selected={tab === "word"}
              onClick={() => setTab("word")}
            >
              Palabra
            </button>
            <button
              type="button"
              role="tab"
              className="tab"
              aria-selected={tab === "summary"}
              onClick={() => setTab("summary")}
            >
              Resumen
            </button>
            <button
              type="button"
              role="tab"
              className="tab"
              aria-selected={tab === "review"}
              onClick={() => setTab("review")}
            >
              Revisión
            </button>
          </div>

          {tab === "word" &&
            (selectedWord && selectedSegment && selected ? (
              <WordDetail
                word={selectedWord}
                next={nextWord}
                segment={selectedSegment}
                segmentIndex={selected.segment}
                isEmphasis={selectedSegment.emphasis_word_idx === selected.index}
                canPlay={canPlay}
              />
            ) : (
              <div className="panel__body">
                <p className="muted tiny">
                  Pulsa cualquier palabra de la transcripción para oírla y ver su comparación fono a
                  fono. Con <span className="kbd">N</span> vas saltando de una a la siguiente.
                </p>
              </div>
            ))}

          {tab === "summary" && (
            <SummaryPanel
              analysis={analysis}
              filter={filter}
              onToggle={(phenomenon) => {
                const activando = !filter.has(phenomenon);
                toggleFilter(phenomenon);
                // Al activar un fenómeno vamos a su primera aparición: filtrar
                // sin moverse deja al usuario mirando un texto atenuado.
                if (activando) {
                  const next = new Set(filter);
                  next.add(phenomenon);
                  jumpIn(filteredWords(analysis, next), 1);
                }
              }}
              onClear={() => setFilter(new Set())}
            />
          )}

          {tab === "review" &&
            (job.has_analysis ? (
              <ReviewPanel jobId={job.id} onSaved={onChanged} />
            ) : (
              <div className="panel__body">
                <p className="muted tiny">No hay análisis que revisar.</p>
              </div>
            ))}
        </aside>
      </div>

      {filter.size > 0 && (
        <div className="row tiny" style={{ padding: "6px 16px", borderTop: "1px solid var(--border)" }}>
          <span>
            Filtro activo: <strong>{walk.length}</strong> palabras.
          </span>
          <button type="button" className="btn btn--sm" onClick={() => jump(1)}>
            Siguiente (N)
          </button>
          <button type="button" className="btn btn--sm" onClick={() => jump(-1)}>
            Anterior
          </button>
          <span className="spacer" />
          <button type="button" className="btn btn--sm" onClick={() => setFilter(new Set())}>
            Quitar filtro
          </button>
        </div>
      )}
    </>
  );
}

const SHORTCUTS: [string, string][] = [
  ["espacio", "reproducir / pausa"],
  ["N", "siguiente palabra (o siguiente coincidencia del filtro o la búsqueda)"],
  ["Mayús + N", "palabra anterior"],
  ["P", "repetir la palabra seleccionada"],
  ["S", "repetir la frase entera"],
  ["L", "bucle: repetir el fragmento acotado"],
  ["← / →", "retroceder / avanzar 2 s"],
  ["F", "seguir la reproducción (desplaza la transcripción)"],
  ["1 / 2 / 3", "en Revisión: ok / mal / dudosa"],
  ["?", "mostrar u ocultar esta ayuda"],
];

function Shortcuts({ onClose }: { onClose: () => void }) {
  return (
    <div className="card" style={{ margin: "12px 16px" }}>
      <div className="row">
        <strong className="tiny">Atajos de teclado</strong>
        <span className="spacer" />
        <button type="button" className="btn btn--ghost btn--sm" onClick={onClose}>
          ✕
        </button>
      </div>
      <dl className="deflist" style={{ marginTop: 8 }}>
        {SHORTCUTS.map(([keys, what]) => (
          <div key={keys} style={{ display: "contents" }}>
            <dt>
              <span className="kbd">{keys}</span>
            </dt>
            <dd>{what}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
