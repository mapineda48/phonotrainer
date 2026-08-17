/** View of a finished analysis: player + transcript + side panel. */

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
  /** Word to open on entry (comes from the corpus). */
  initialSelection?: Selection | null;
  /** Present when arriving from the corpus: allows going back without losing the filter. */
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
        <p>Loading analysis…</p>
      </div>
    );
  }
  // An analysis.json with a different shape would blank out the whole app.
  if (!analysis.meta || !Array.isArray(analysis.segments)) {
    return (
      <div className="empty">
        <p className="error">This analysis.json is not shaped the way the interface expects.</p>
        <p className="tiny muted">Regenerate it with “phonotrainer analyze”.</p>
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
  /** The player without its shifting identity, for effects that must not re-run
   *  every time the active span changes. */
  const playerRef = useRef(player);
  playerRef.current = player;
  const reference = useReference();
  const [selected, setSelected] = useState<Selection | null>(null);
  const [tab, setTab] = useState<Tab>("summary");
  const [filter, setFilter] = useState<ReadonlySet<string>>(new Set());
  const [follow, setFollow] = useState(true);
  // Persistent preference: off by default (as it has always been); only honored
  // when the open analysis actually has video.
  const [showVideo, toggleVideo] = usePersistentFlag("phonotrainer:show-video");
  const [showHelp, setShowHelp] = useState(false);
  const [query, setQuery] = useState("");

  const hasVideo = job.is_video && job.has_media;

  const duration = analysis.meta.duration;
  const canPlay = job.has_audio;
  const flat = useMemo(() => flattenWords(analysis), [analysis]);

  /** The words N / Shift+N walk through: the filtered ones, or all of them. */
  const narrow = (base: FlatWord[], needle: string) =>
    needle ? base.filter((fw) => fw.word.word.toLowerCase().includes(needle.toLowerCase())) : base;

  const walk = useMemo(() => {
    const byFilter = filteredWords(analysis, filter);
    return narrow(byFilter.length > 0 ? byFilter : flat, query.trim());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [analysis, flat, filter, query]);

  const selectedWord = selected ? analysis.segments[selected.segment]?.words[selected.index] : null;
  const selectedSegment = selected ? analysis.segments[selected.segment] : null;
  // The next one on the timeline, not within the segment: linking also happens
  // on the last word of a segment.
  const flatIndex = selected
    ? flat.findIndex((fw) => fw.segment === selected.segment && fw.index === selected.index)
    : -1;
  const nextWord = flatIndex >= 0 ? (flat[flatIndex + 1]?.word ?? null) : null;

  const select = (next: Selection) => {
    setSelected(next);
    setTab("word");
  };

  /** Jump to the next word in `list` (or the previous one) and play it. */
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

  // Arriving from the corpus: open straight on that word and play it. Once per
  // selection only: `player` changes identity when the span is set, so without
  // the guard the effect kept re-triggering itself and the word played forever.
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
      ? `word “${selectedWord.word}”`
      : "span"
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
          {analysis.meta.duration.toFixed(1)} s · {analysis.segments.length} segments ·{" "}
          {analysis.meta.language}
          {analysis.meta.attraction ? "" : " · no attraction"}
        </span>
        <span className="spacer" />
        <input
          type="search"
          className="input"
          style={{ width: 150 }}
          aria-label="Search for a word in the transcript"
          placeholder="Search word…"
          value={query}
          onChange={(event) => {
            const value = event.target.value;
            setQuery(value);
            // Go to the first match as you type (without playing, which would
            // fire on every keystroke); Enter and N move to the next one.
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
            {walk.length} {walk.length === 1 ? "match" : "matches"}
          </span>
        )}
        {hasVideo && (
          <button
            type="button"
            className="btn btn--sm"
            aria-pressed={showVideo}
            title="Show the original video over the transcript (V)"
            onClick={toggleVideo}
          >
            Video
          </button>
        )}
        <button
          type="button"
          className="btn btn--sm"
          aria-pressed={follow}
          title="Follow playback: the sounding segment stays pinned at the top (F)"
          onClick={() => setFollow((value) => !value)}
        >
          Follow
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
          title="Keyboard shortcuts (?)"
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
          This analysis was imported without <code>audio.wav</code>: you can read it, but not
          listen to it.
        </p>
      )}

      <a className="skip" href="#panel">
        Skip to the detail panel
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
            /* The dock floats over the transcript: it rides along with the
             * scroll that follows the phrase instead of pushing it down. */
            <div className="video-dock">
              <VideoPane src={api.mediaUrl(job.id)} />
              <button
                type="button"
                className="btn btn--ghost btn--sm video-dock__close"
                aria-label="Hide video"
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
              Word
            </button>
            <button
              type="button"
              role="tab"
              className="tab"
              aria-selected={tab === "summary"}
              onClick={() => setTab("summary")}
            >
              Summary
            </button>
            <button
              type="button"
              role="tab"
              className="tab"
              aria-selected={tab === "review"}
              onClick={() => setTab("review")}
            >
              Review
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
                  Click any word in the transcript to hear it and see its phone-by-phone
                  comparison. <span className="kbd">N</span> jumps from one to the next.
                </p>
              </div>
            ))}

          {tab === "summary" && (
            <SummaryPanel
              analysis={analysis}
              filter={filter}
              onToggle={(phenomenon) => {
                const turningOn = !filter.has(phenomenon);
                toggleFilter(phenomenon);
                // Turning a phenomenon on takes you to its first occurrence:
                // filtering without moving leaves the user staring at dimmed text.
                if (turningOn) {
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
                <p className="muted tiny">There is no analysis to review.</p>
              </div>
            ))}
        </aside>
      </div>

      {(filter.size > 0 || query.trim()) && (
        <div className="row tiny" style={{ padding: "6px 16px", borderTop: "1px solid var(--border)" }}>
          <span>
            {filter.size > 0 && (
              <>
                Filter: <strong>{[...filter].map((p) => reference.labels[p] ?? p).join(", ")}</strong>{" "}
              </>
            )}
            {query.trim() && <>Search: “{query.trim()}” </>}·{" "}
            <strong>{walk.length}</strong> words.
          </span>
          <button type="button" className="btn btn--sm" onClick={() => jump(1)}>
            Next (N)
          </button>
          <button type="button" className="btn btn--sm" onClick={() => jump(-1)}>
            Previous
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
            Clear
          </button>
        </div>
      )}
    </>
  );
}

const SHORTCUTS: [string, string][] = [
  ["space", "play / pause"],
  ["N", "next word (or next filter or search match)"],
  ["Shift + N", "previous word"],
  ["P", "replay the selected word"],
  ["S", "replay the whole phrase"],
  ["L", "loop: repeat the bounded span"],
  ["← / →", "back / forward 2 s"],
  ["F", "follow playback (the sounding segment stays at the top)"],
  ["V", "show or hide the original video"],
  // The verdict values come from the backend and are stored verbatim in
  // review.json, so the help names the same keys the buttons carry.
  ["1 / 2 / 3", "in Review: ok / wrong / unsure"],
  ["?", "show or hide this help"],
];

function Shortcuts({ onClose }: { onClose: () => void }) {
  return (
    <div className="card" style={{ margin: "12px 16px" }}>
      <div className="row">
        <strong className="tiny">Keyboard shortcuts</strong>
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
