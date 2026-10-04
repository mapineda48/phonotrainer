/** The workspace of a finished analysis: header, player, transcript on the left and, on
 *  the right, the word lesson (or the clip's summary, or review mode).
 *
 *  Below 1024 px there is no room for two panes: the transcript, the lesson, the summary
 *  and review become four tabs of one pane, under a player that stays in reach. Selecting
 *  a word there plays it and offers its lesson in a bar at the bottom, one tap away; the
 *  Transcript tab goes back to the same word.
 *
 *  Keyboard (also in Help → Keyboard shortcuts): Space play/pause · N / Shift+N next /
 *  previous word · P word · Shift+P word with the next one · S phrase · L loop ·
 *  ← / → 2 s · F follow · V video · ? shortcuts. */

import { BookOpen, MousePointerClick, Sparkles } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useLocation } from "wouter";

import { api, type AudioTrack } from "../../api";
import { tourAttr, TOUR } from "../../didactic/tour-ids";
import { useHotkeys } from "../../hooks/useHotkeys";
import { BREAKPOINTS, useMediaQuery } from "../../hooks/useMediaQuery";
import { usePersistentFlag } from "../../hooks/usePersistentFlag";
import { filteredWords, flattenWords, wordSpan, type FlatWord } from "../../lib/analysis";
import { paths } from "../../paths";
import { PlayerProvider, usePlayer } from "../../player/PlayerProvider";
import { phenomenonLabel, useReference } from "../../reference";
import { useSettings } from "../../settings/SettingsProvider";
import { ShortcutsDialog } from "../../shell/HelpDialogs";
import type { Analysis, Job } from "../../types";
import { Button, ButtonRow, cn, EmptyState, Kbd, Tab, TabList, TabPanel, Tabs } from "../../ui";
import { stepsFor, WordLesson, type StepId, type StepState } from "./lesson/WordLesson";
import { crossesBoundary, engineOf, isNarrowEngine } from "./lib/words";
import { ReviewMode } from "./panes/ReviewMode";
import { SummaryTab } from "./panes/SummaryTab";
import { TopBar } from "./panes/TopBar";
import { PlayerBar } from "./player/PlayerBar";
import { VideoDock } from "./player/VideoDock";
import { Waveform } from "./player/Waveform";
import { Transcript, type Selection } from "./transcript/Transcript";
import { TranscriptToolbar } from "./transcript/TranscriptToolbar";

type Pane = "lesson" | "summary" | "review";

const FROM = new Set(["insights", "learn", "practice"]);

interface Props {
  job: Job;
  analysis: Analysis;
  /** Word requested by the URL (deep link), or null. */
  selection: Selection | null;
  from: string | null;
}

export function Workspace({ job, analysis, selection, from }: Props) {
  // Listening to the isolated dialogue is a choice that sticks; the original mix stays
  // the default, since it is what was actually said on screen.
  const [dialoguePreferred, toggleDialogue] = usePersistentFlag("phonotrainer:dialogue-track");
  const track: AudioTrack = dialoguePreferred && job.has_dialogue_audio ? "dialogue" : "mix";
  return (
    <PlayerProvider src={job.has_audio ? api.audioUrl(job.id, track) : null} sourceKey={job.id}>
      <WorkspaceBody
        job={job}
        analysis={analysis}
        selection={selection}
        from={from}
        track={track}
        onTrack={(next) => {
          if ((next === "dialogue") !== dialoguePreferred) toggleDialogue();
        }}
      />
    </PlayerProvider>
  );
}

function WorkspaceBody({
  job,
  analysis,
  selection,
  from,
  track,
  onTrack,
}: Props & { track: AudioTrack; onTrack: (track: AudioTrack) => void }) {
  const player = usePlayer();
  // the player without its shifting identity, for effects that must not re-run whenever
  // the active span changes
  const playerRef = useRef(player);
  playerRef.current = player;
  const reference = useReference();
  const { settings } = useSettings();
  const [, navigate] = useLocation();

  const [selected, setSelected] = useState<Selection | null>(null);
  const [pane, setPane] = useState<Pane>("lesson");
  // One pane at a time (narrow screens): is the transcript the one showing? A deep link
  // to a word arrives on its lesson.
  const singlePane = useMediaQuery(BREAKPOINTS.singlePane);
  const [showTranscript, setShowTranscript] = useState(() => selection === null);
  const lessonHidden = useRef(false);
  lessonHidden.current = singlePane && showTranscript;
  const [filter, setFilter] = useState<ReadonlySet<string>>(new Set());
  const [query, setQuery] = useState("");
  const [follow, setFollow] = useState(true);
  const [showVideo, toggleVideo] = usePersistentFlag("phonotrainer:show-video");
  const [shortcuts, setShortcuts] = useState(false);
  const [steps, setSteps] = useState<StepState>(() => stepsFor(settings.guidance));
  const [announcement, setAnnouncement] = useState("");

  // the guidance setting changes what opens by default
  const guidance = useRef(settings.guidance);
  useEffect(() => {
    if (guidance.current === settings.guidance) return;
    guidance.current = settings.guidance;
    setSteps(stepsFor(settings.guidance));
  }, [settings.guidance]);

  const hasVideo = job.is_video && job.has_media;
  const canPlay = job.has_audio;
  const engine = engineOf(analysis);
  const flat = useMemo(() => flattenWords(analysis), [analysis]);
  const needle = query.trim().toLowerCase();

  /** The words N / Shift+N walk through: the filtered ones (or all), narrowed by search. */
  const walk = useMemo(() => {
    const byFilter = filter.size > 0 ? filteredWords(analysis, filter) : flat;
    return needle ? byFilter.filter((fw) => fw.word.word.toLowerCase().includes(needle)) : byFilter;
  }, [analysis, flat, filter, needle]);

  const selectedWord = selected ? (analysis.segments[selected.segment]?.words[selected.index] ?? null) : null;
  const selectedSegment = selected ? analysis.segments[selected.segment] : null;
  const flatIndex = selected
    ? flat.findIndex((fw) => fw.segment === selected.segment && fw.index === selected.index)
    : -1;
  // the next one on the timeline, not within the phrase: linking also happens on the
  // last word of a phrase
  const following = flatIndex >= 0 ? (flat[flatIndex + 1]?.word ?? null) : null;
  const boundaryNext = selectedWord && crossesBoundary(selectedWord, following) ? following : null;

  // ---- selection ---------------------------------------------------------------------
  const lastUrl = useRef<string | null>(null);
  const select = useCallback(
    (next: Selection, options: { play?: boolean; open?: boolean } = {}) => {
      setSelected(next);
      setPane("lesson");
      // with one pane, only an explicit request leaves the transcript for the lesson
      if (options.open) setShowTranscript(false);
      const lessonShows = options.open || !lessonHidden.current;
      const word = analysis.segments[next.segment]?.words[next.index];
      if (word && options.play !== false) {
        if (canPlay) playerRef.current.play(wordSpan(word));
        else playerRef.current.seek(word.start);
      }
      if (word) {
        const names = word.phenomena.map((p) => phenomenonLabel(reference, p));
        setAnnouncement(
          `“${word.word}”${names.length ? `: ${names.join(", ")}` : ""}. ${lessonShows ? "Lesson open." : "Its lesson is one tap away, below."}`,
        );
      }
      // the URL names the word, so a reload or a shared link reopens it
      const url = paths.word(
        job.id,
        next.segment,
        next.index,
        from && FROM.has(from) ? (from as "insights" | "learn" | "practice") : undefined,
      );
      lastUrl.current = `${next.segment}:${next.index}`;
      navigate(url, { replace: true });
    },
    [analysis, canPlay, reference, job.id, from, navigate],
  );

  // Arriving on a word (deep link, Insights, Learn): open it and play it — once per
  // requested word. Our own URL updates come back here as the same word and are skipped.
  const applied = useRef<string | null>(null);
  useEffect(() => {
    if (!selection) return;
    const wanted = `${selection.segment}:${selection.index}`;
    if (applied.current === wanted || lastUrl.current === wanted) {
      applied.current = wanted;
      return;
    }
    if (!analysis.segments[selection.segment]?.words[selection.index]) return;
    applied.current = wanted;
    select(selection, { open: true });
  }, [selection, analysis, select]);

  /** Jump to the next word in `list` (or the previous one) and play it. */
  const jumpIn = (list: FlatWord[], delta: number) => {
    if (list.length === 0) return;
    let index: number;
    const at = selected ? list.findIndex((fw) => fw.segment === selected.segment && fw.index === selected.index) : -1;
    if (at >= 0) {
      index = (at + delta + list.length) % list.length;
    } else {
      const now = player.clock.getSnapshot();
      if (delta < 0) {
        const previous = [...list].reverse().find((match) => match.word.end < now - 0.01);
        index = previous ? list.indexOf(previous) : list.length - 1;
      } else {
        const found = list.findIndex((match) => match.word.start > now + 0.01);
        index = found === -1 ? 0 : found;
      }
    }
    const target = list[index];
    select({ segment: target.segment, index: target.index });
    focusWordIfInTranscript(target);
  };

  const jump = (delta: number) => jumpIn(walk, delta);

  const setFilterAndGo = (phenomena: string[]) => {
    const next = new Set(phenomena);
    setFilter(next);
    // filtering without moving leaves the learner staring at faded text
    if (next.size > 0) jumpIn(filteredWords(analysis, next), 1);
  };

  const toggleFilter = (phenomenon: string) => {
    const next = new Set(filter);
    if (next.has(phenomenon)) next.delete(phenomenon);
    else next.add(phenomenon);
    setFilter(next);
    if (!filter.has(phenomenon)) jumpIn(filteredWords(analysis, new Set([phenomenon])), 1);
  };

  const onQuery = (value: string) => {
    setQuery(value);
    // go to the first match as you type, without playing (that would fire on every
    // keystroke); Enter and N move on to the next one
    const text = value.trim().toLowerCase();
    if (!text) return;
    const base = filter.size > 0 ? filteredWords(analysis, filter) : flat;
    const hit = base.find((fw) => fw.word.word.toLowerCase().includes(text));
    if (hit) {
      select({ segment: hit.segment, index: hit.index }, { play: false });
      player.seek(hit.word.start);
    }
  };

  const playWord = () => selectedWord && player.play(wordSpan(selectedWord));
  const playWithNext = () => {
    if (!selectedWord) return;
    const span = wordSpan(selectedWord);
    player.play(boundaryNext ? { start: span.start, end: wordSpan(boundaryNext).end } : span);
  };

  useHotkeys(
    {
      " ": () => player.toggle(),
      ArrowRight: () => player.seek(player.clock.getSnapshot() + 2),
      ArrowLeft: () => player.seek(player.clock.getSnapshot() - 2),
      l: () => player.setLoop(!player.loop),
      L: () => player.setLoop(!player.loop),
      f: () => setFollow((value) => !value),
      F: () => setFollow((value) => !value),
      v: () => hasVideo && toggleVideo(),
      V: () => hasVideo && toggleVideo(),
      n: () => jump(1),
      N: () => jump(-1),
      p: playWord,
      P: playWithNext,
      s: () => selectedSegment && player.play({ start: selectedSegment.start, end: selectedSegment.end }),
      S: () => selectedSegment && player.play({ start: selectedSegment.start, end: selectedSegment.end }),
      "?": () => setShortcuts(true),
    },
    !shortcuts,
  );

  const firstChange = flat.find((fw) => fw.word.phenomena.length > 0);
  const spanLabel = player.span
    ? selectedWord && Math.abs(player.span.start - wordSpan(selectedWord).start) < 0.01
      ? `“${selectedWord.word}”`
      : selectedSegment &&
          Math.abs(player.span.start - selectedSegment.start) < 0.01 &&
          Math.abs(player.span.end - selectedSegment.end) < 0.01
        ? "this phrase"
        : "a part"
    : null;

  // ---- one pane at a time (narrow screens) ---------------------------------------------
  const stickyRef = useRef<HTMLDivElement | null>(null);
  const lessonRef = useRef<HTMLElement | null>(null);
  /** Height of the player + tabs while they stick to the top (0 when they don't: on a
   *  short screen nothing sticks, or nothing would be left to read). */
  const [stickyHeight, setStickyHeight] = useState(0);

  useLayoutEffect(() => {
    const element = stickyRef.current;
    if (!singlePane || !element) return;
    const measure = () => setStickyHeight(getComputedStyle(element).position === "sticky" ? element.offsetHeight : 0);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    window.addEventListener("resize", measure);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measure);
    };
  }, [singlePane]);

  // Switching panes keeps the place: back on the transcript, the word being studied is in
  // view; on the lesson (or summary, or review), its top is, just below the player.
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const shownBefore = useRef(showTranscript);
  useLayoutEffect(() => {
    if (shownBefore.current === showTranscript) return;
    shownBefore.current = showTranscript;
    if (!singlePane) return;
    if (showTranscript) {
      const at = selectedRef.current;
      const node = at ? document.querySelector<HTMLElement>(`[data-word="${at.segment}:${at.index}"]`) : null;
      if (node && typeof node.scrollIntoView === "function") node.scrollIntoView({ block: "center" });
      return;
    }
    const lesson = lessonRef.current;
    const main = document.getElementById("main");
    if (!lesson || !main || typeof lesson.scrollIntoView !== "function") return;
    if (lesson.getBoundingClientRect().top < main.getBoundingClientRect().top + stickyHeight) {
      lesson.scrollIntoView({ block: "start" });
    }
  }, [showTranscript, singlePane, stickyHeight]);

  /** Show the lesson pane (one pane at a time) and put the keyboard there. */
  const openLesson = () => {
    setPane("lesson");
    setShowTranscript(false);
    requestAnimationFrame(() => lessonRef.current?.focus({ preventScroll: true }));
  };

  const startWithFirstChange = () => {
    if (!firstChange) return;
    select({ segment: firstChange.segment, index: firstChange.index }, { open: true });
    if (singlePane) requestAnimationFrame(() => lessonRef.current?.focus({ preventScroll: true }));
    else focusWordIfInTranscript(firstChange, true);
  };

  const selectedNames = selectedWord ? selectedWord.phenomena.map((p) => phenomenonLabel(reference, p)) : [];

  // ---- the pieces, laid out below for one pane or two -----------------------------------
  const header = (
    <TopBar
      job={job}
      analysis={analysis}
      from={from}
      query={query}
      onQuery={onQuery}
      onSearchNext={() => jump(1)}
      matches={needle ? walk.length : null}
      track={track}
      onTrack={onTrack}
      hasVideo={hasVideo}
      showVideo={showVideo}
      onToggleVideo={toggleVideo}
      follow={follow}
      onFollow={setFollow}
      onShortcuts={() => setShortcuts(true)}
    />
  );

  const playerBar = (
    <PlayerBar duration={analysis.meta.duration} enabled={canPlay} spanLabel={spanLabel}>
      {canPlay && (
        <Waveform src={api.audioUrl(job.id, track)} duration={analysis.meta.duration} className="max-sm:min-w-24" />
      )}
    </PlayerBar>
  );

  const noAudio = !canPlay && (
    <p className="border-b border-line bg-surface-2 px-4 py-2 text-sm text-ink-2">
      This analysis was imported without its audio: you can read it, but not listen to it.
    </p>
  );

  const transcript = (
    <section
      aria-label="Transcript"
      className={cn("relative flex min-h-0 min-w-0 flex-1 flex-col gap-3 p-4", !singlePane && "overflow-auto")}
      {...tourAttr(TOUR.transcript)}
    >
      <a
        href="#lesson-pane"
        onClick={(event) => {
          // one pane at a time: the lesson is not on the page until its tab is chosen
          if (!singlePane) return;
          event.preventDefault();
          openLesson();
        }}
        className="sr-only rounded-control bg-ink px-3 py-1.5 text-page focus:not-sr-only focus:w-fit"
      >
        Skip to the word lesson
      </a>
      <TranscriptToolbar analysis={analysis} filter={filter} onSetFilter={setFilterAndGo} />
      <Transcript
        analysis={analysis}
        selected={selected}
        onSelect={select}
        filter={filter}
        query={query}
        follow={follow}
      />
    </section>
  );

  const filterBar = (filter.size > 0 || needle) && (
    <div
      role="region"
      aria-label="Filter"
      className="flex flex-wrap items-center gap-2 border-t border-line bg-surface px-4 py-2 text-sm"
    >
      <span className="text-ink">
        {filter.size > 0 && (
          <>
            Showing: <strong>{[...filter].map((p) => phenomenonLabel(reference, p)).join(", ")}</strong>{" "}
          </>
        )}
        {needle && <>Search: “{query.trim()}” </>}· <strong>{walk.length}</strong> words
      </span>
      <span className="flex-1" />
      <Button size="sm" onPress={() => jump(-1)}>
        Previous
      </Button>
      <Button size="sm" onPress={() => jump(1)}>
        Next <Kbd>N</Kbd>
      </Button>
      <Button
        size="sm"
        variant="quiet"
        onPress={() => {
          setFilter(new Set());
          setQuery("");
        }}
      >
        Clear
      </Button>
    </div>
  );

  const video = (className?: string) =>
    showVideo && hasVideo && <VideoDock src={api.mediaUrl(job.id)} onClose={toggleVideo} className={className} />;

  const panels: ReactNode = (
    <>
      <TabPanel id="lesson" className="p-4">
        {selectedWord && selectedSegment && selected ? (
          <WordLesson
            key={`${selected.segment}:${selected.index}`}
            word={selectedWord}
            next={boundaryNext}
            segment={selectedSegment}
            segmentIndex={selected.segment}
            wordIndex={selected.index}
            position={{ n: flatIndex + 1, total: flat.length }}
            onStep={(delta) => jumpIn(flat, delta)}
            canPlay={canPlay}
            narrow={isNarrowEngine(engine)}
            steps={steps}
            onStepsChange={(id: StepId, open: boolean) => setSteps((all) => ({ ...all, [id]: open }))}
            jobId={job.id}
          />
        ) : (
          <EmptyState
            icon={MousePointerClick}
            title="Pick a word to study"
            actions={
              <ButtonRow>
                {firstChange && (
                  <Button variant="primary" icon={Sparkles} onPress={startWithFirstChange}>
                    Start with the first change
                  </Button>
                )}
              </ButtonRow>
            }
          >
            <p>
              {singlePane ? "Tap" : "Click"} any word in the transcript to hear it and see, step by step, how it was
              really said and why. Underlined words carry a change. Press <Kbd>N</Kbd> to jump from one to the next.
            </p>
          </EmptyState>
        )}
      </TabPanel>
      <TabPanel id="summary" className="p-4">
        <SummaryTab analysis={analysis} filter={filter} onToggle={toggleFilter} onSetFilter={setFilterAndGo} />
      </TabPanel>
      <TabPanel id="review" className="p-4">
        {job.has_analysis ? (
          <ReviewMode jobId={job.id} />
        ) : (
          <p className="text-sm text-ink-2">There is no analysis to review.</p>
        )}
      </TabPanel>
    </>
  );

  const paneTabs = (
    <>
      <Tab
        id="lesson"
        className={cn(singlePane && "flex-1 justify-center px-2 pointer-coarse:h-11")}
        // one pane at a time: the tour points at the tab that opens the lesson
        {...(singlePane ? tourAttr(TOUR.wordLesson) : {})}
      >
        Lesson
      </Tab>
      <Tab id="summary" className={cn(singlePane && "flex-1 justify-center px-2 pointer-coarse:h-11")}>
        Summary
      </Tab>
      <Tab id="review" className={cn(singlePane && "flex-1 justify-center px-2 pointer-coarse:h-11")}>
        Review
      </Tab>
    </>
  );

  const extras = (
    <>
      <div aria-live="polite" className="sr-only">
        {announcement}
      </div>
      <ShortcutsDialog isOpen={shortcuts} onOpenChange={setShortcuts} />
    </>
  );

  if (singlePane) {
    return (
      <Tabs
        selectedKey={showTranscript ? "transcript" : pane}
        onSelectionChange={(key) => {
          if (key === "transcript") {
            setShowTranscript(true);
          } else {
            setPane(key as Pane);
            setShowTranscript(false);
          }
        }}
        style={{ "--ws-sticky": `${stickyHeight}px` } as React.CSSProperties}
        className={cn(
          "relative min-h-full",
          // words and phrases scrolled into view land below the sticky player, and above
          // the word bar at the bottom
          "[&_[data-word]]:scroll-mt-[calc(var(--ws-sticky)_+_0.75rem)] [&_[data-word]]:scroll-mb-32",
          "[&_section]:scroll-mt-[calc(var(--ws-sticky)_+_0.5rem)]",
        )}
      >
        {header}
        {/* sticks only on a screen tall enough to leave most of it for reading */}
        <div
          ref={stickyRef}
          className="z-20 bg-surface [@media(min-height:40rem)]:sticky [@media(min-height:40rem)]:top-0"
        >
          {playerBar}
          <TabList aria-label="Panel" className="border-b border-line bg-surface px-1">
            <Tab id="transcript" className="flex-1 justify-center px-2 pointer-coarse:h-11">
              Transcript
            </Tab>
            {paneTabs}
          </TabList>
        </div>
        {noAudio}
        <TabPanel id="transcript" shouldForceMount hidden={!showTranscript} className="flex flex-col">
          {transcript}
          {/* the word being studied and its lesson, one tap away */}
          <div className="relative z-10 border-t border-line bg-surface [@media(min-height:40rem)]:sticky [@media(min-height:40rem)]:bottom-0">
            {video("bottom-full right-3 mb-3 w-[min(14rem,55%)]")}
            {filterBar}
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-2.5">
              {selectedWord ? (
                <>
                  <p className="min-w-0 flex-1 text-sm text-ink-2">
                    <span className="block truncate text-base font-semibold text-ink">“{selectedWord.word}”</span>
                    <span className="block truncate">
                      {selectedNames.length > 0 ? selectedNames.join(", ") : "No change in this word"}
                    </span>
                  </p>
                  <Button
                    variant="primary"
                    icon={BookOpen}
                    aria-label={`Open lesson: “${selectedWord.word}”`}
                    onPress={openLesson}
                    className="pointer-coarse:h-11"
                  >
                    Open lesson
                  </Button>
                </>
              ) : (
                <>
                  <p className="min-w-48 flex-1 text-sm text-ink-2">Tap any word to hear it and open its lesson.</p>
                  {firstChange && (
                    <Button
                      icon={Sparkles}
                      onPress={startWithFirstChange}
                      // may wrap with very large text rather than overflow the bar
                      className="h-auto min-h-10 max-w-full whitespace-normal py-2 text-left pointer-coarse:min-h-11"
                    >
                      Start with the first change
                    </Button>
                  )}
                </>
              )}
            </div>
          </div>
        </TabPanel>
        <aside
          ref={lessonRef}
          id="lesson-pane"
          tabIndex={-1}
          hidden={showTranscript}
          aria-label="Word lesson, summary and review"
          style={{ scrollMarginTop: stickyHeight }}
          className="min-w-0 bg-surface outline-none"
        >
          {panels}
        </aside>
        {extras}
      </Tabs>
    );
  }

  return (
    <div className="relative flex h-full min-h-0 flex-col">
      {header}
      {playerBar}
      {noAudio}

      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(22rem,26rem)] xl:grid-cols-[minmax(0,1fr)_minmax(24rem,32rem)]">
        <div className="relative flex min-h-0 min-w-0 flex-col">
          {transcript}
          {filterBar}
          {video()}
        </div>

        <aside
          ref={lessonRef}
          id="lesson-pane"
          tabIndex={-1}
          aria-label="Word lesson, summary and review"
          className="relative min-h-0 overflow-auto border-l border-line bg-surface outline-none"
          {...tourAttr(TOUR.wordLesson)}
        >
          <Tabs selectedKey={pane} onSelectionChange={(key) => setPane(key as Pane)}>
            <TabList aria-label="Panel" className="sticky top-0 z-10 bg-surface px-3 pt-2">
              {paneTabs}
            </TabList>
            {panels}
          </Tabs>
        </aside>
      </div>

      {extras}
    </div>
  );
}

/** Keep keyboard focus with the word the learner moved to, when focus is already in
 *  the transcript (N pressed while a word has focus). */
function focusWordIfInTranscript(target: { segment: number; index: number }, force = false) {
  requestAnimationFrame(() => {
    const active = document.activeElement as HTMLElement | null;
    if (!force && !active?.dataset.word) return;
    const node = document.querySelector<HTMLElement>(`[data-word="${target.segment}:${target.index}"]`);
    node?.focus();
    if (node && typeof node.scrollIntoView === "function") node.scrollIntoView({ block: "nearest" });
  });
}
