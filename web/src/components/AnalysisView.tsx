/** Vista de un análisis terminado: reproductor + transcripción + panel lateral. */

import { useEffect, useMemo, useRef, useState } from "react";

import { api } from "../api";
import { useHotkeys } from "../hooks/useHotkeys";
import { usePersistentFlag } from "../hooks/usePersistentFlag";
import { filteredWords, flattenWords, wordSpan, type FlatWord } from "../lib/analysis";
import { PlayerProvider, usePlayer } from "../player/PlayerProvider";
import { useReference } from "../reference";
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
  /** Palabra que hay que abrir al entrar (viene del corpus). */
  initialSelection?: Selection | null;
  /** Presente si se llegó desde el corpus: permite volver sin perder el filtro. */
  onBackToCorpus?: () => void;
}

export function AnalysisView({ job, initialSelection = null,
                               onBackToCorpus }: Props) {
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
  // Un analysis.json con otra forma tumbaría toda la app en blanco.
  if (!analysis.meta || !Array.isArray(analysis.segments)) {
    return (
      <div className="empty">
        <p className="error">Este analysis.json no tiene la forma que espera la interfaz.</p>
        <p className="tiny muted">Vuelve a generarlo con «phonotrainer analyze».</p>
      </div>
    );
  }

  return (
    <PlayerProvider src={job.has_audio ? api.audioUrl(job.id) : null}>
      <AnalysisBody job={job} analysis={analysis}
                    initialSelection={initialSelection} onBackToCorpus={onBackToCorpus} />
    </PlayerProvider>
  );
}

function AnalysisBody({
  job,
  analysis,
  initialSelection,
  onBackToCorpus,
}: {
  job: Job;
  analysis: Analysis;
  initialSelection: Selection | null;
  onBackToCorpus?: () => void;
}) {
  const player = usePlayer();
  /** El reproductor sin su identidad cambiante, para efectos que no deben
   *  reejecutarse cada vez que cambia el fragmento activo. */
  const playerRef = useRef(player);
  playerRef.current = player;
  const reference = useReference();
  const [selected, setSelected] = useState<Selection | null>(null);
  const [tab, setTab] = useState<Tab>("summary");
  const [filter, setFilter] = useState<ReadonlySet<string>>(new Set());
  const [follow, setFollow] = useState(true);
  // Preferencia persistente: por defecto apagado (como siempre); solo se
  // respeta si el análisis abierto tiene video.
  const [showVideo, toggleVideo] = usePersistentFlag("phonotrainer:show-video");
  const [showHelp, setShowHelp] = useState(false);
  const [query, setQuery] = useState("");

  const hasVideo = job.is_video && job.has_media;

  const duration = analysis.meta.duration;
  const canPlay = job.has_audio;
  const flat = useMemo(() => flattenWords(analysis), [analysis]);

  /** Palabras por las que navegan N / Mayús+N: las del filtro, o todas. */
  const narrow = (base: FlatWord[], needle: string) =>
    needle ? base.filter((fw) => fw.word.word.toLowerCase().includes(needle.toLowerCase())) : base;

  const walk = useMemo(() => {
    const byFilter = filteredWords(analysis, filter);
    return narrow(byFilter.length > 0 ? byFilter : flat, query.trim());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [analysis, flat, filter, query]);

  const selectedWord = selected ? analysis.segments[selected.segment]?.words[selected.index] : null;
  const selectedSegment = selected ? analysis.segments[selected.segment] : null;
  // La siguiente en la línea de tiempo, no en el segmento: el enlace también
  // ocurre en la última palabra de un segmento.
  const flatIndex = selected
    ? flat.findIndex((fw) => fw.segment === selected.segment && fw.index === selected.index)
    : -1;
  const nextWord = flatIndex >= 0 ? (flat[flatIndex + 1]?.word ?? null) : null;

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

  // Llegando desde el corpus: abrir directamente en esa palabra y oírla.
  // Una sola vez por selección: `player` cambia de identidad al fijar el
  // fragmento, así que sin el testigo el efecto se volvía a disparar solo y la
  // palabra se reproducía sin fin.
  const applied = useRef<Selection | null>(null);
  useEffect(() => {
    if (!initialSelection || applied.current === initialSelection) return;
    const word = analysis.segments[initialSelection.segment]?.words[initialSelection.index];
    if (!word) return;
    applied.current = initialSelection;
    setSelected(initialSelection);
    setTab("word");
    if (canPlay) playerRef.current.play(wordSpan(word));
    else playerRef.current.seek(word.start);
  }, [initialSelection, analysis, canPlay]);

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
    v: () => hasVideo && toggleVideo(),
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
        {onBackToCorpus && (
          <button type="button" className="btn btn--sm" onClick={onBackToCorpus}>
            ← Corpus
          </button>
        )}
        <h2 className="topbar__title" title={job.source}>
          {job.source}
        </h2>
        <span className="tiny muted">
          {analysis.meta.duration.toFixed(1)} s · {analysis.segments.length} segmentos ·{" "}
          {analysis.meta.language}
          {analysis.meta.attraction ? "" : " · sin atracción"}
        </span>
        <span className="spacer" />
        <input
          type="search"
          className="input"
          style={{ width: 150 }}
          aria-label="Buscar una palabra en la transcripción"
          placeholder="Buscar palabra…"
          value={query}
          onChange={(event) => {
            const value = event.target.value;
            setQuery(value);
            // Ir a la primera coincidencia al teclear (sin reproducir, que
            // sonaría en cada tecla); Intro y N pasan a la siguiente.
            const hits = narrow(
              filter.size > 0 ? filteredWords(analysis, filter) : flat,
              value.trim(),
            );
            if (value.trim() && hits[0]) {
              setSelected({ segment: hits[0].segment, index: hits[0].index });
              player.seek(hits[0].word.start);
            }
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              jump(1);
            }
          }}
        />
        {query.trim() && (
          <span className="tiny muted num">
            {walk.length} {walk.length === 1 ? "coincidencia" : "coincidencias"}
          </span>
        )}
        {hasVideo && (
          <button
            type="button"
            className="btn btn--sm"
            aria-pressed={showVideo}
            title="Mostrar el video original sobre la transcripción (V)"
            onClick={toggleVideo}
          >
            Video
          </button>
        )}
        <button
          type="button"
          className="btn btn--sm"
          aria-pressed={follow}
          title="Seguir la reproducción: el segmento que suena se queda arriba (F)"
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
        <div style={{ display: "flex", flexDirection: "column", minHeight: 0, position: "relative" }}>
          <Transcript
            analysis={analysis}
            selected={selected}
            onSelect={select}
            filter={filter}
            follow={follow}
          />
          {showVideo && hasVideo && (
            /* El dock flota sobre la transcripción: acompaña al scroll que
             * sigue la frase en vez de empujarla hacia abajo. */
            <div className="video-dock">
              <VideoPane src={api.mediaUrl(job.id)} />
              <button
                type="button"
                className="btn btn--ghost btn--sm video-dock__close"
                aria-label="Ocultar video"
                onClick={toggleVideo}
              >
                ✕
              </button>
            </div>
          )}
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
              <ReviewPanel jobId={job.id} />
            ) : (
              <div className="panel__body">
                <p className="muted tiny">No hay análisis que revisar.</p>
              </div>
            ))}
        </aside>
      </div>

      {(filter.size > 0 || query.trim()) && (
        <div className="row tiny" style={{ padding: "6px 16px", borderTop: "1px solid var(--border)" }}>
          <span>
            {filter.size > 0 && (
              <>
                Filtro: <strong>{[...filter].map((p) => reference.labels[p] ?? p).join(", ")}</strong>{" "}
              </>
            )}
            {query.trim() && <>Búsqueda: “{query.trim()}” </>}·{" "}
            <strong>{walk.length}</strong> palabras.
          </span>
          <button type="button" className="btn btn--sm" onClick={() => jump(1)}>
            Siguiente (N)
          </button>
          <button type="button" className="btn btn--sm" onClick={() => jump(-1)}>
            Anterior
          </button>
          <span className="spacer" />
          <button
            type="button"
            className="btn btn--sm"
            onClick={() => {
              setFilter(new Set());
              setQuery("");
            }}
          >
            Quitar
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
  ["F", "seguir la reproducción (el segmento que suena, siempre arriba)"],
  ["V", "mostrar u ocultar el video original"],
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
