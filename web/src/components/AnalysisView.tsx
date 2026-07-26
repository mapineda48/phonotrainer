/** Vista de un análisis terminado: reproductor + transcripción + panel lateral. */

import { useEffect, useMemo, useState } from "react";

import { api } from "../api";
import { useHotkeys } from "../hooks/useHotkeys";
import { filteredWords, wordSpan } from "../lib/analysis";
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

  const duration = analysis.meta.duration;
  const matches = useMemo(() => filteredWords(analysis, filter), [analysis, filter]);

  const selectedWord = selected ? analysis.segments[selected.segment]?.words[selected.index] : null;
  const selectedSegment = selected ? analysis.segments[selected.segment] : null;

  const select = (next: Selection) => {
    setSelected(next);
    setTab("word");
  };

  /** Salta a la siguiente palabra que cumple el filtro (o a la anterior). */
  const jump = (delta: number) => {
    if (matches.length === 0) return;
    const now = player.clock.getSnapshot();
    let index = matches.findIndex((match) => match.word.start > now + 0.01);
    if (delta < 0) {
      const previous = [...matches].reverse().find((match) => match.word.end < now - 0.01);
      index = previous ? matches.indexOf(previous) : matches.length - 1;
    } else if (index === -1) {
      index = 0;
    }
    const target = matches[index];
    select({ segment: target.segment, index: target.index });
    player.play(wordSpan(target.word));
  };

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
      </header>

      <PlayerBar duration={duration} spanLabel={spanLabel}>
        {job.has_audio && <Waveform src={api.audioUrl(job.id)} duration={duration} />}
      </PlayerBar>

      {!job.has_audio && (
        <p className="tiny muted" style={{ padding: "6px 16px" }}>
          Este análisis se importó sin <code>audio.wav</code>: se puede leer, pero no escuchar.
        </p>
      )}

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

        <aside className="aside">
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
                segment={selectedSegment}
                segmentIndex={selected.segment}
                isEmphasis={selectedSegment.emphasis_word_idx === selected.index}
              />
            ) : (
              <div className="panel__body">
                <p className="muted tiny">
                  Pulsa cualquier palabra de la transcripción para oírla y ver su comparación fono a
                  fono.
                </p>
              </div>
            ))}

          {tab === "summary" && (
            <SummaryPanel
              analysis={analysis}
              filter={filter}
              onToggle={(phenomenon) =>
                setFilter((current) => {
                  const next = new Set(current);
                  if (next.has(phenomenon)) next.delete(phenomenon);
                  else next.add(phenomenon);
                  return next;
                })
              }
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
            Filtro activo: <strong>{matches.length}</strong> palabras.
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
