/** /practice — ear training with words and phrases from the learner's own clips.
 *
 *  A session is 10 items; the item types rotate (which pronunciation? / which change? /
 *  how many words?). Answers by click or keys 1–4, Enter for the next item. Accuracy per
 *  phenomenon is kept in this browser only; "Focus on my weak spots" draws more often from
 *  the changes the learner gets wrong. */

import {
  ArrowRight,
  AudioLines,
  Check,
  Ear,
  ListOrdered,
  RotateCcw,
  Shapes,
  Target,
  X,
} from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { useLocation, useSearch } from "wouter";

import { api } from "../../api";
import { ClipPlayer } from "../../audio/ClipPlayer";
import { useHotkeys } from "../../hooks/useHotkeys";
import { paths } from "../../paths";
import { phenomenonDescription, phenomenonLabel, useReference } from "../../reference";
import type { Analysis, Occurrence, WordVariant } from "../../types";
import {
  Button,
  Card,
  Chip,
  cn,
  ConfirmDialog,
  DataTable,
  EmptyState,
  Ipa,
  Kbd,
  LinkButton,
  Notice,
  PageHeader,
  PhenomenonBadge,
  PhenomenonIcon,
  ProgressBar,
  RichText,
  Switch,
  TextLink,
} from "../../ui";
import { capitalize, displayWord, stripStress, useLoad } from "../learn/corpus";
import { FOCUS_PARAM, practiceHref } from "./links";
import {
  accuracy,
  buildPool,
  changeItem,
  clearStats,
  countItem,
  drawEntry,
  entryKey,
  formItem,
  kindForIndex,
  loadStats,
  quizPhenomena,
  recordAnswer,
  saveStats,
  seededRng,
  SESSION_LENGTH,
  type ItemKind,
  type PoolEntry,
  type QuizItem,
  type Stats,
} from "./quiz";

const PER_PHENOMENON = 80;

/** Load every phenomenon present in the corpus, a few dozen occurrences each. */
async function loadPool(names: readonly string[]): Promise<PoolEntry[]> {
  const stats = await api.corpusStats();
  const present = stats.phenomena.filter((row) => row.count > 0 && names.includes(row.phenomenon));
  const lists = await Promise.all(
    present.map(async (row) => {
      const { items } = await api.corpusOccurrences({ phenomenon: row.phenomenon, limit: PER_PHENOMENON });
      return [row.phenomenon, items] as const;
    }),
  );
  return buildPool(Object.fromEntries(lists) as Record<string, Occurrence[]>);
}

interface Result {
  item: QuizItem;
  chosen: string;
  correct: boolean;
}

const KIND_TEXT: Record<ItemKind, { title: string; icon: typeof Ear; text: string }> = {
  form: {
    title: "Which pronunciation?",
    icon: AudioLines,
    text: "Hear a word and pick how it was really said: the dictionary form or a reduced one.",
  },
  change: {
    title: "Which change?",
    icon: Shapes,
    text: "Hear a word and name the change: a flap, a dropped h, a reduced vowel…",
  },
  count: {
    title: "How many words?",
    icon: ListOrdered,
    text: "Hear a short phrase and count its words — small reduced words are the ones the ear misses.",
  },
};

export function PracticePage() {
  const reference = useReference();
  const search = useSearch();
  const [, navigate] = useLocation();
  const focus = new URLSearchParams(search).get(FOCUS_PARAM);
  const focusKnown = focus && focus in reference.labels ? focus : null;

  const names = useMemo(() => quizPhenomena(reference), [reference]);
  const pool = useLoad(`practice-pool:${names.join(",")}`, () => loadPool(names));

  const [stats, setStats] = useState<Stats>(() => loadStats());
  const [weakSpots, setWeakSpots] = useState(false);
  const [session, setSession] = useState<{ seed: number; index: number; results: Result[] } | null>(null);
  const [item, setItem] = useState<QuizItem | null>(null);
  const [building, setBuilding] = useState(false);
  const [chosen, setChosen] = useState<string | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const variantsCache = useRef(new Map<string, WordVariant[]>());
  const analysisCache = useRef(new Map<string, Analysis>());
  const usedRef = useRef(new Set<string>());

  const entries = pool.status === "ready" ? pool.data : [];
  const focusEntries = focusKnown ? entries.filter((entry) => entry.phenomenon === focusKnown) : entries;

  const buildItem = useCallback(
    async (index: number, seed: number): Promise<QuizItem | null> => {
      const rng = seededRng(seed * 7919 + index * 104729);
      const preferred = kindForIndex(index + (seed % 3));
      // With a focus every "which change?" answer would be the same: skip that type.
      const kinds: ItemKind[] = focusKnown
        ? preferred === "change"
          ? ["form", "count"]
          : [preferred, preferred === "form" ? "count" : "form"]
        : [preferred, "change"];
      for (const kind of kinds) {
        for (let attempt = 0; attempt < 5; attempt += 1) {
          const entry = drawEntry(entries, rng, {
            stats,
            weakSpots,
            focus: focusKnown,
            avoid: usedRef.current,
          });
          if (!entry) return null;
          try {
            let built: QuizItem | null = null;
            if (kind === "change") built = changeItem(entry, reference, rng);
            else if (kind === "form") {
              const word = entry.occurrence.word;
              let variants = variantsCache.current.get(word);
              if (!variants) {
                variants = (await api.corpusVariants(word)).variants;
                variantsCache.current.set(word, variants);
              }
              built = formItem(entry, variants, reference, rng);
            } else {
              const jobId = entry.occurrence.job_id;
              let analysis = analysisCache.current.get(jobId);
              if (!analysis) {
                analysis = await api.analysis(jobId);
                analysisCache.current.set(jobId, analysis);
              }
              const segment = analysis.segments[entry.occurrence.segment];
              built = segment ? countItem(entry, segment, rng) : null;
            }
            if (built) {
              usedRef.current.add(entryKey(entry));
              return built;
            }
          } catch {
            /* this clip's data is unavailable: draw another */
          }
        }
      }
      return null;
    },
    [entries, stats, weakSpots, focusKnown, reference],
  );

  // Build the item for the current position whenever the session moves.
  useEffect(() => {
    if (!session || session.index >= SESSION_LENGTH) return;
    let active = true;
    setBuilding(true);
    setItem(null);
    setChosen(null);
    buildItem(session.index, session.seed).then((built) => {
      if (!active) return;
      setBuilding(false);
      if (built) setItem(built);
      else setSession((current) => (current ? { ...current, index: SESSION_LENGTH } : current));
    });
    return () => {
      active = false;
    };
    // buildItem changes with the stats after every answer; only a new position rebuilds
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.seed, session?.index]);

  const start = () => {
    usedRef.current = new Set();
    setSession({ seed: Math.floor(Math.random() * 1e9), index: 0, results: [] });
  };

  const answer = (optionId: string) => {
    if (!item || chosen !== null || !session) return;
    const correct = optionId === item.answer;
    setChosen(optionId);
    const updated = recordAnswer(stats, item.phenomenon, correct);
    setStats(updated);
    saveStats(updated);
    setSession({ ...session, results: [...session.results, { item, chosen: optionId, correct }] });
  };

  const next = () => {
    if (!session || chosen === null) return;
    setSession({ ...session, index: session.index + 1 });
  };

  useHotkeys(
    {
      "1": () => item && answer(item.options[0]?.id ?? ""),
      "2": () => item && item.options[1] && answer(item.options[1].id),
      "3": () => item && item.options[2] && answer(item.options[2].id),
      "4": () => item && item.options[3] && answer(item.options[3].id),
      Enter: () => next(),
    },
    Boolean(session && item),
  );

  const header = (
    <PageHeader
      title="Practice"
      lede="Train your ear with words and phrases from your own clips. A few minutes a day is enough."
    />
  );

  if (pool.status === "loading") {
    return (
      <Shell>
        {header}
        <p className="text-base text-ink-2" role="status">
          Gathering words from your clips…
        </p>
      </Shell>
    );
  }
  if (pool.status === "error") {
    return (
      <Shell>
        {header}
        <Notice tone="caution" title="Your clips could not be loaded">
          {pool.message}
        </Notice>
      </Shell>
    );
  }
  if (entries.length === 0) {
    return (
      <Shell>
        {header}
        <EmptyState
          icon={Ear}
          title="Nothing to practice yet"
          actions={<LinkButton href={paths.newAnalysis()}>Analyze a clip</LinkButton>}
        >
          Practice builds its questions from the changes found in your clips. Analyze a video or audio file
          first — a minute of conversation already gives dozens of questions.
        </EmptyState>
      </Shell>
    );
  }

  const results = session?.results ?? [];
  const right = results.filter((result) => result.correct).length;
  const wrong = results.length - right;
  const finished = session !== null && session.index >= SESSION_LENGTH;

  return (
    <Shell>
      {header}

      {!session && (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <Card title="Start a session" level={2} description={`${SESSION_LENGTH} questions, in three kinds:`}>
            <ul className="mb-5 flex flex-col gap-3">
              {(Object.keys(KIND_TEXT) as ItemKind[]).map((kind) => {
                const { title, icon: Icon, text } = KIND_TEXT[kind];
                return (
                  <li key={kind} className="flex gap-3">
                    <Icon size={20} aria-hidden="true" className="mt-0.5 shrink-0 text-ink" />
                    <span>
                      <strong className="text-ink">{title}</strong>{" "}
                      <span className="text-ink-2">{text}</span>
                    </span>
                  </li>
                );
              })}
            </ul>
            {focus && (
              <div className="mb-4 flex flex-wrap items-center gap-2">
                {focusKnown ? (
                  <>
                    <span className="text-sm text-ink-2">Only:</span>
                    <PhenomenonBadge name={focusKnown} />
                  </>
                ) : (
                  <span className="text-sm text-ink-2">Unknown focus “{focus}”: practicing everything.</span>
                )}
                <Button size="sm" variant="quiet" onPress={() => navigate(practiceHref(null), { replace: true })}>
                  Practice everything instead
                </Button>
              </div>
            )}
            {focusKnown && focusEntries.length === 0 ? (
              <Notice tone="info" title={`No playable examples of ${phenomenonLabel(reference, focusKnown)} yet`}>
                Analyze more clips, or practice everything for now.
              </Notice>
            ) : (
              <div className="flex flex-col gap-4">
                {!focusKnown && (
                  <Switch
                    isSelected={weakSpots}
                    onChange={setWeakSpots}
                    description="Changes you get wrong come up more often."
                  >
                    Focus on my weak spots
                  </Switch>
                )}
                <div>
                  <Button variant="primary" size="lg" icon={Target} onPress={start}>
                    Start practicing
                  </Button>
                </div>
                <p className="text-sm text-ink-2">
                  Keys: <Kbd>1</Kbd>–<Kbd>4</Kbd> to answer, <Kbd>Enter</Kbd> for the next question.
                </p>
              </div>
            )}
          </Card>
          <StatsCard stats={stats} onReset={() => setConfirmReset(true)} />
        </div>
      )}

      {session && !finished && (
        <Card>
          <div className="mb-4 flex flex-wrap items-center gap-4">
            <ProgressBar
              className="min-w-48 flex-1"
              label="Session progress"
              value={(results.length / SESSION_LENGTH) * 100}
              valueText={`Question ${Math.min(session.index + 1, SESSION_LENGTH)} of ${SESSION_LENGTH}`}
            />
            <span className="flex items-center gap-1 text-sm text-ink">
              <Check size={16} aria-hidden="true" /> {right} right
            </span>
            <span className="flex items-center gap-1 text-sm text-ink">
              <X size={16} aria-hidden="true" /> {wrong} wrong
            </span>
          </div>
          {building && !item && (
            <p className="text-base text-ink-2" role="status">
              Preparing the next question…
            </p>
          )}
          {item && (
            <QuestionView
              key={item.key}
              item={item}
              chosen={chosen}
              onAnswer={answer}
              onNext={next}
              last={session.index + 1 >= SESSION_LENGTH}
            />
          )}
        </Card>
      )}

      {finished && session && (
        <SessionSummary
          results={session.results}
          onAgain={start}
          onWeakSpots={() => {
            setWeakSpots(true);
            start();
          }}
          onClose={() => setSession(null)}
        />
      )}

      <ConfirmDialog
        title="Forget your practice history?"
        isOpen={confirmReset}
        onOpenChange={setConfirmReset}
        confirmLabel="Forget it"
        destructive
        onConfirm={() => {
          clearStats();
          setStats({});
        }}
      >
        Your accuracy per change is stored only in this browser. This clears it; your clips are not touched.
      </ConfirmDialog>
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return <div className="mx-auto w-full max-w-5xl px-4 py-6 sm:px-6 sm:py-8">{children}</div>;
}

function QuestionView({
  item,
  chosen,
  onAnswer,
  onNext,
  last,
}: {
  item: QuizItem;
  chosen: string | null;
  onAnswer: (id: string) => void;
  onNext: () => void;
  last: boolean;
}) {
  const promptId = useId();
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const answered = chosen !== null;

  // A new question replaces the controls that had focus: put the learner on the question.
  useEffect(() => {
    headingRef.current?.focus();
  }, []);
  const correct = chosen === item.answer;
  const answerOption = item.options.find((option) => option.id === item.answer);

  return (
    <div className="flex flex-col gap-4">
      <div>
        <p className="text-sm font-semibold uppercase tracking-wide text-ink-2">{KIND_TEXT[item.kind].title}</p>
        <h2 id={promptId} ref={headingRef} tabIndex={-1} className="text-xl font-semibold text-ink outline-none">
          {item.prompt}
        </h2>
      </div>

      <div className="flex flex-wrap gap-2">
        <ClipPlayer {...item.clip} label={item.kind === "count" ? "Play the phrase" : "Play the word"}>
          Play
        </ClipPlayer>
        <ClipPlayer {...item.clip} rate={0.5} label="Play slowly">
          Slowly
        </ClipPlayer>
        {item.context && (
          <ClipPlayer {...item.context} label="Play with the words around it">
            With context
          </ClipPlayer>
        )}
      </div>

      <div role="group" aria-labelledby={promptId} className="grid gap-2 sm:grid-cols-2">
        {item.options.map((option, index) => {
          const isAnswer = option.id === item.answer;
          const isChosen = option.id === chosen;
          return (
            <button
              key={option.id}
              type="button"
              disabled={answered}
              onClick={() => onAnswer(option.id)}
              aria-describedby={answered && (isAnswer || isChosen) ? `${promptId}-${option.id}` : undefined}
              className={cn(
                "flex min-h-14 items-center gap-3 rounded-card px-4 py-2 text-left text-base text-ink",
                "bg-surface shadow-[inset_0_0_0_1px_var(--line-strong)]",
                !answered && "hover:bg-surface-2",
                answered && isAnswer && "shadow-[inset_0_0_0_3px_var(--ink)] font-semibold",
                answered && !isAnswer && !isChosen && "opacity-70",
              )}
            >
              <Kbd>{index + 1}</Kbd>
              <span className="flex min-w-0 flex-1 items-center gap-2">
                {option.phenomenon && <PhenomenonIcon name={option.phenomenon} size={18} />}
                {option.ipa ? (
                  <Ipa kind="phonetic" className="text-lg">
                    {option.ipa}
                  </Ipa>
                ) : (
                  <span>{option.phenomenon ? capitalize(option.label) : option.label}</span>
                )}
              </span>
              {answered && isAnswer && (
                <span id={`${promptId}-${option.id}`} className="flex items-center gap-1 text-sm text-ink">
                  <Check size={16} aria-hidden="true" className="text-good" /> Correct answer
                </span>
              )}
              {answered && isChosen && !isAnswer && (
                <span id={`${promptId}-${option.id}`} className="flex items-center gap-1 text-sm text-ink">
                  <X size={16} aria-hidden="true" className="text-critical" /> Your answer
                </span>
              )}
            </button>
          );
        })}
      </div>

      <div role="status" aria-live="polite" className="min-h-6">
        {answered && (
          <p className="flex items-center gap-2 text-lg font-semibold text-ink">
            {correct ? (
              <>
                <Check size={20} aria-hidden="true" className="text-good" /> Correct!
              </>
            ) : (
              <>
                <X size={20} aria-hidden="true" className="text-critical" /> Not quite — the answer was{" "}
                {answerOption?.ipa ? <Ipa kind="phonetic">{answerOption.ipa}</Ipa> : answerOption?.label}.
              </>
            )}
          </p>
        )}
      </div>

      {answered && <Explanation item={item} />}

      {answered && (
        <div className="flex flex-wrap items-center gap-3">
          <Button autoFocus variant="primary" icon={ArrowRight} onPress={onNext}>
            {last ? "See your results" : "Next question"}
          </Button>
          <span className="text-sm text-ink-2">
            or press <Kbd>Enter</Kbd>
          </span>
        </div>
      )}
    </div>
  );
}

function Explanation({ item }: { item: QuizItem }) {
  const reference = useReference();
  const occurrence = item.occurrence;
  const label = phenomenonLabel(reference, item.phenomenon);
  return (
    <section aria-label="Explanation" className="flex flex-col gap-3 rounded-card bg-surface-2 p-4">
      {item.kind === "count" && item.phrase && (
        <div>
          <p className="mb-2 text-base text-ink">
            The phrase has <strong>{item.phrase.length} words</strong>. Words with a dotted underline changed in fast
            speech — the ones a learner usually misses:
          </p>
          <ol className="flex flex-wrap gap-1.5">
            {item.phrase.map((word, index) => (
              <li
                key={index}
                className="inline-flex items-baseline gap-1 rounded-control bg-surface px-2 py-1 text-base text-ink"
              >
                <span className="text-xs text-ink-2">{index + 1}</span>
                <span className={cn(word.changed && "underline decoration-dotted decoration-2 underline-offset-4")}>
                  {word.text}
                </span>
                {word.changed && <span className="sr-only"> (changed)</span>}
              </li>
            ))}
          </ol>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <PhenomenonBadge name={item.phenomenon} showPractice />
        <span className="text-base text-ink">
          “{displayWord(occurrence.word)}”{" "}
          {occurrence.dict_ipa && <Ipa kind="phonemic">{stripStress(occurrence.dict_ipa)}</Ipa>} →{" "}
          <Ipa kind="phonetic">{occurrence.realized_ipa ?? "∅"}</Ipa>
        </span>
      </div>
      <p className="text-sm text-ink-2">
        <RichText text={phenomenonDescription(reference, item.phenomenon)} />
      </p>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
        <ClipPlayer {...item.clip} rate={0.5} label="Listen again slowly" size="sm">
          Again at 0.5×
        </ClipPlayer>
        <TextLink href={paths.phenomenon(item.phenomenon)}>Learn about {label}</TextLink>
        <TextLink href={paths.word(occurrence.job_id, occurrence.segment, occurrence.word_idx, "practice")}>
          Open this word's lesson
        </TextLink>
      </div>
    </section>
  );
}

function SessionSummary({
  results,
  onAgain,
  onWeakSpots,
  onClose,
}: {
  results: readonly Result[];
  onAgain: () => void;
  onWeakSpots: () => void;
  onClose: () => void;
}) {
  const reference = useReference();
  const right = results.filter((result) => result.correct).length;
  const missed = [...new Set(results.filter((result) => !result.correct).map((result) => result.item.phenomenon))];
  return (
    <Card title="Session finished" level={2}>
      <p className="text-2xl font-bold text-ink" role="status">
        {right} of {results.length} right
      </p>
      {results.length === 0 && (
        <p className="mt-2 text-base text-ink-2">There were not enough clips to build questions this time.</p>
      )}
      {missed.length > 0 && (
        <div className="mt-4">
          <h3 className="text-base font-semibold text-ink">Worth reviewing</h3>
          <ul className="mt-2 flex flex-wrap gap-2">
            {missed.map((name) => (
              <li key={name}>
                <TextLink href={paths.phenomenon(name)}>{capitalize(phenomenonLabel(reference, name))}</TextLink>
              </li>
            ))}
          </ul>
        </div>
      )}
      <ol className="mt-4 flex flex-col gap-1">
        {results.map((result, index) => (
          <li key={result.item.key} className="flex items-center gap-2 text-sm text-ink">
            {result.correct ? (
              <Check size={16} aria-hidden="true" className="text-good" />
            ) : (
              <X size={16} aria-hidden="true" className="text-critical" />
            )}
            <span className="sr-only">{result.correct ? "Right:" : "Wrong:"}</span>
            {index + 1}. {KIND_TEXT[result.item.kind].title} “{displayWord(result.item.occurrence.word)}” —{" "}
            {phenomenonLabel(reference, result.item.phenomenon)}
          </li>
        ))}
      </ol>
      <div className="mt-5 flex flex-wrap gap-2">
        <Button variant="primary" icon={RotateCcw} onPress={onAgain}>
          Practice again
        </Button>
        <Button variant="secondary" icon={Target} onPress={onWeakSpots}>
          Practice my weak spots
        </Button>
        <Button variant="quiet" onPress={onClose}>
          Back to the start
        </Button>
      </div>
    </Card>
  );
}

function StatsCard({ stats, onReset }: { stats: Stats; onReset: () => void }) {
  const reference = useReference();
  const rows = Object.entries(stats)
    .filter(([, entry]) => entry.seen > 0)
    .sort((a, b) => (accuracy(a[1]) ?? 0) - (accuracy(b[1]) ?? 0));
  return (
    <Card
      title="Your accuracy so far"
      level={2}
      description="Kept only in this browser."
      actions={
        rows.length > 0 ? (
          <Button size="sm" variant="quiet" onPress={onReset}>
            Reset
          </Button>
        ) : undefined
      }
    >
      {rows.length === 0 ? (
        <p className="text-sm text-ink-2">Answer a few questions and your results per change will appear here.</p>
      ) : (
        <DataTable
          caption="Accuracy per change, weakest first"
          columns={["Change", "Right", "Accuracy"]}
          rows={rows.map(([name, entry]) => [
            capitalize(phenomenonLabel(reference, name)),
            `${entry.correct} of ${entry.seen}`,
            `${Math.round((accuracy(entry) ?? 0) * 100)} %`,
          ])}
        />
      )}
      <Chip tone="muted" className="mt-3">
        Weak spots are the changes at the top.
      </Chip>
    </Card>
  );
}
