/** Occurrences: every word that carries a phenomenon, most different from the dictionary
 *  first, filterable by phenomenon, word and practice advice. Each one plays on the spot
 *  and opens its word lesson; a searched word also shows every way it was said. */

import { ArrowRight, CircleAlert, Clock, Ear, Mic } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";

import { ClipPlayer } from "../../audio/ClipPlayer";
import { Explain } from "../../didactic/Explain";
import { fmtTime, plural } from "../../lib/format";
import { paths } from "../../paths";
import { phenomenonLabel, phenomenonPractice, useReference } from "../../reference";
import type { CorpusStats, Occurrence, Reference, WordVariant } from "../../types";
import {
  Button,
  Card,
  Chip,
  EmptyState,
  Ipa,
  LinkButton,
  Notice,
  PhenomenonBadge,
  PRACTICE_TEXT,
  SearchField,
  Segmented,
  Select,
  ToggleButton,
} from "../../ui";
import { OCCURRENCE_LIMIT, type OccurrenceState } from "./data";
import type { InsightsFilters, PracticeFilter } from "./query";
import { count } from "./ReductionSection";

/** Phenomena that happen BETWEEN two words: the next word is part of the example. */
const BOUNDARY = new Set(["linking", "palatalization", "h_dropping"]);

const ALL = "__all";

/** Forms of a searched word shown before "Show rarer forms". */
const FEW_FORMS = 12;

export function passesPractice(reference: Reference, item: Occurrence, practice: PracticeFilter | null): boolean {
  if (!practice) return true;
  return item.phenomena.some((name) => phenomenonPractice(reference, name)?.practice === practice);
}

/** A word with nothing to compare against ("9" before numerals were spelled out). */
const hasDictionaryForm = (item: Occurrence) => Boolean(item.canonical_ipa || item.dict_ipa);

interface Props {
  stats: CorpusStats;
  filters: InsightsFilters;
  onFilters: (patch: Partial<InsightsFilters>) => void;
  data: OccurrenceState;
}

export function OccurrencesSection({ stats, filters, onFilters, data }: Props) {
  const reference = useReference();
  const [draft, setDraft] = useState(filters.word);
  const [variant, setVariant] = useState<string | null>(null);

  // the URL is the truth: keep the box in step when it changes from elsewhere
  useEffect(() => setDraft(filters.word), [filters.word]);
  // a new search starts with every form
  useEffect(() => setVariant(null), [filters.word, filters.phenomenon]);

  const visible = useMemo(
    () =>
      data.items.filter(
        (item) =>
          passesPractice(reference, item, filters.practice) &&
          (variant === null || (item.realized_ipa || "") === variant),
      ),
    [data.items, reference, filters.practice, variant],
  );
  const narrowed = filters.practice !== null || variant !== null;
  const anyFilter = Boolean(filters.phenomenon || filters.word || filters.practice);

  const phenomenonItems = [
    { id: ALL, label: "All phenomena" },
    ...stats.phenomena.map((row) => ({
      id: row.phenomenon,
      label: `${phenomenonLabel(reference, row.phenomenon)} (${row.count})`,
    })),
  ];
  // a phenomenon from the URL that the corpus does not hold still has to be selectable
  if (filters.phenomenon && !phenomenonItems.some((item) => item.id === filters.phenomenon)) {
    phenomenonItems.push({ id: filters.phenomenon, label: phenomenonLabel(reference, filters.phenomenon) });
  }

  const submitWord = (value: string) => onFilters({ word: value.trim() });

  return (
    <Card
      title="Occurrences"
      description="Every word where a change was found, most different from the dictionary first. Play one, or open it to study it step by step."
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-3">
          <Select
            label="Phenomenon"
            items={phenomenonItems}
            value={filters.phenomenon ?? ALL}
            onChange={(id) => onFilters({ phenomenon: id === ALL ? null : id })}
            className="min-w-56"
          />
          <form
            className="flex min-w-64 flex-1 flex-col gap-1.5"
            role="search"
            onSubmit={(event) => {
              event.preventDefault();
              submitWord(draft);
            }}
          >
            <span className="text-sm font-medium text-ink" aria-hidden="true">
              Word
            </span>
            <div className="flex gap-2">
              <SearchField
                label="Word to look up across the corpus"
                value={draft}
                onChange={setDraft}
                onSubmit={submitWord}
                placeholder="One word: to, that, want…"
                className="flex-1"
              />
              <Button type="submit" variant="secondary" isDisabled={data.busy && draft === filters.word}>
                Search
              </Button>
            </div>
          </form>
          <Segmented<"all" | PracticeFilter>
            label="Practice"
            options={[
              { id: "all", label: "All" },
              { id: "produce", label: PRACTICE_TEXT.produce, icon: Mic },
              { id: "understand", label: PRACTICE_TEXT.understand, icon: Ear },
            ]}
            value={filters.practice ?? "all"}
            onChange={(id) => onFilters({ practice: id === "all" ? null : id })}
          />
          <Explain term="safe-to-produce" buttonLabel="What's this: safe to produce" />
          {anyFilter && (
            <Button
              variant="quiet"
              onPress={() => {
                setDraft("");
                onFilters({ phenomenon: null, word: "", practice: null });
              }}
            >
              Clear filters
            </Button>
          )}
        </div>

        {filters.word ? (
          <Variants word={filters.word} variants={data.variants} selected={variant} onSelect={setVariant} />
        ) : (
          stats.top_words.length > 0 && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm text-ink-2">Words with the most changes:</span>
              {stats.top_words.slice(0, 10).map((entry) => (
                <Button
                  key={entry.word}
                  size="sm"
                  variant="secondary"
                  aria-label={`Look up “${entry.word}”: ${entry.count} changes`}
                  onPress={() => onFilters({ word: entry.word })}
                >
                  {entry.word}
                  <span className="font-normal text-ink-2">· {entry.count}</span>
                </Button>
              ))}
            </div>
          )
        )}

        {data.error ? (
          <Notice tone="caution" title="The occurrences could not be loaded">
            {data.error}
          </Notice>
        ) : (
          <>
            <ResultLine
              total={data.total}
              loaded={data.items.length}
              shown={visible.length}
              narrowed={narrowed}
              filters={filters}
              variant={variant}
              reference={reference}
              busy={data.busy}
            />
            {visible.length > 0 ? (
              <OccurrenceTable items={visible} />
            ) : (
              !data.busy && (
                <EmptyState title="Nothing to show for these filters">
                  {filters.practice
                    ? `None of the loaded occurrences is marked “${PRACTICE_TEXT[filters.practice]}”. Try another phenomenon, or set Practice to All.`
                    : "Search works on one word at a time. Clear the filters to see everything."}
                </EmptyState>
              )
            )}
          </>
        )}
      </div>
    </Card>
  );
}

function ResultLine({
  total,
  loaded,
  shown,
  narrowed,
  filters,
  variant,
  reference,
  busy,
}: {
  total: number;
  loaded: number;
  shown: number;
  narrowed: boolean;
  filters: InsightsFilters;
  variant: string | null;
  reference: Reference;
  busy: boolean;
}) {
  const parts: ReactNode[] = [];
  if (filters.phenomenon) parts.push(<> of “{phenomenonLabel(reference, filters.phenomenon)}”</>);
  if (filters.word) parts.push(<> of “{filters.word}”</>);
  if (variant !== null) {
    parts.push(
      <>
        {" "}
        pronounced {variant ? <Ipa kind="phonetic">{variant}</Ipa> : "with no sounds"}
      </>,
    );
  }
  if (filters.practice) parts.push(<> marked “{PRACTICE_TEXT[filters.practice]}”</>);
  return (
    <div className="flex flex-col gap-0.5" aria-live="polite" aria-busy={busy}>
      <p className="text-base font-semibold text-ink">
        {busy ? "Loading occurrences…" : narrowed ? plural(shown, "occurrence") : plural(total, "occurrence")}
        {!busy && parts.map((part, i) => <span key={i}>{part}</span>)}
      </p>
      {!busy && (
        <p className="text-sm text-ink-2">
          Most different from the dictionary first; words too short to hear go last.
          {narrowed && total > loaded && ` Filtered from the first ${loaded} of ${total}.`}
          {!narrowed && total > loaded && ` Showing the first ${loaded}.`}
        </p>
      )}
      {!busy && total >= OCCURRENCE_LIMIT && !filters.phenomenon && !filters.word && (
        <p className="sr-only">Choose a phenomenon or a word to see the rest.</p>
      )}
    </div>
  );
}

function Variants({
  word,
  variants,
  selected,
  onSelect,
}: {
  word: string;
  variants: WordVariant[] | null;
  selected: string | null;
  onSelect: (value: string | null) => void;
}) {
  const [all, setAll] = useState(false);
  useEffect(() => setAll(false), [word]);
  if (!variants || variants.length === 0) return null;
  const dictionary = variants.find((v) => v.dict_ipa)?.dict_ipa;
  // the long tail of one-off forms is mostly recognizer noise: it waits behind a button
  const shown = all || variants.length <= FEW_FORMS ? variants : variants.slice(0, FEW_FORMS);
  return (
    <section aria-labelledby="insights-variants" className="flex flex-col gap-2 rounded-card bg-surface-2 p-3">
      <h3 id="insights-variants" className="text-base font-semibold text-ink">
        How “{word}” was said across your corpus
      </h3>
      <p className="text-sm text-ink-2">
        {dictionary && (
          <>
            Dictionary: <Ipa kind="phonemic">{dictionary}</Ipa>.{" "}
          </>
        )}
        Choose a form to keep only those occurrences.
      </p>
      <div className="flex flex-wrap gap-2">
        {shown.map((v) => {
          const key = v.realized_ipa || "";
          const on = selected === key;
          return (
            <ToggleButton
              key={key || "∅"}
              size="sm"
              variant="secondary"
              isSelected={on}
              onChange={() => onSelect(on ? null : key)}
              aria-label={`Said ${v.realized_ipa ? `[${v.realized_ipa}]` : "with no sounds"}: ${count(
                v.count,
                "time",
                "times",
              )}, in ${count(v.analyses, "analysis", "analyses")}`}
            >
              {v.realized_ipa ? <Ipa kind="phonetic">{v.realized_ipa}</Ipa> : <span>no sounds</span>}
              <span className="font-normal">{count(v.count, "time", "times")}</span>
            </ToggleButton>
          );
        })}
        {shown.length < variants.length && (
          <Button size="sm" variant="quiet" onPress={() => setAll(true)}>
            Show {variants.length - shown.length} rarer forms
          </Button>
        )}
      </div>
    </section>
  );
}

/** Rows rendered at first, and added per "Show more": a long table is hard to scan. */
export const PAGE = 50;

function OccurrenceTable({ items }: { items: Occurrence[] }) {
  const [limit, setLimit] = useState(PAGE);
  // a new result set starts again from the top
  useEffect(() => setLimit(PAGE), [items]);
  const rows = items.slice(0, limit);
  return (
    <div className="flex flex-col gap-3">
      {/* relative: the cells' sr-only text must not escape the scroller and widen the page */}
      <div className="relative overflow-x-auto">
        <table className="w-full min-w-[44rem] border-collapse text-sm">
          <caption className="sr-only">Occurrences, most different from the dictionary first</caption>
          <thead>
            <tr className="border-b border-line-strong text-left text-ink">
              <th scope="col" className="px-2 py-2 font-semibold">Word</th>
              <th scope="col" className="px-2 py-2 font-semibold">
                <Explain term="dictionary-form">Dictionary</Explain>
              </th>
              <th scope="col" className="px-2 py-2 font-semibold">
                <Explain term="what-was-said">Heard</Explain>
              </th>
              <th scope="col" className="px-2 py-2 font-semibold">Changes</th>
              <th scope="col" className="px-2 py-2 font-semibold">Where</th>
              <th scope="col" className="px-2 py-2 font-semibold">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((item) => (
              <OccurrenceRow key={`${item.analysis_id}:${item.segment}:${item.word_idx}`} item={item} />
            ))}
          </tbody>
        </table>
      </div>
      {items.length > limit && (
        <Button variant="secondary" className="self-start" onPress={() => setLimit((n) => n + PAGE)}>
          Show {Math.min(PAGE, items.length - limit)} more ({items.length - limit} left)
        </Button>
      )}
    </div>
  );
}

function OccurrenceRow({ item }: { item: Occurrence }) {
  const boundary = item.next_word && item.phenomena.some((name) => BOUNDARY.has(name));
  const spoken = boundary ? `${item.word} ${item.next_word}` : item.word;
  const where = `${item.analysis_source} at ${fmtTime(item.start)}`;
  return (
    <tr className="border-b border-line align-top last:border-b-0">
      <th scope="row" className="px-2 py-2 text-left font-semibold text-ink">
        {item.word}
        {boundary && <span className="font-normal text-ink-2">‿{item.next_word}</span>}
        {item.lexical_form && (
          <div className="mt-1">
            <Chip tone="muted">“{item.lexical_form}”</Chip>
          </div>
        )}
      </th>
      <td className="px-2 py-2 text-ink">
        {hasDictionaryForm(item) ? (
          <Ipa kind="phonemic">{item.dict_ipa || item.canonical_ipa || ""}</Ipa>
        ) : (
          <span className="text-ink-2">no dictionary form</span>
        )}
      </td>
      <td className="px-2 py-2 text-ink">
        {item.realized_ipa ? <Ipa kind="phonetic">{item.realized_ipa}</Ipa> : <span className="text-ink-2">no sounds heard</span>}
        <Flags item={item} />
      </td>
      <td className="px-2 py-2">
        {item.phenomena.length > 0 ? (
          <span className="flex flex-wrap gap-1">
            {item.phenomena.map((name) => (
              <PhenomenonBadge key={name} name={name} />
            ))}
          </span>
        ) : (
          <span className="text-ink-2">none found</span>
        )}
      </td>
      <td className="max-w-[14rem] px-2 py-2 text-ink-2">
        <span className="line-clamp-2 break-words" title={item.analysis_source}>
          {item.analysis_source}
        </span>
        <span className="block tabular-nums">{fmtTime(item.start)}</span>
      </td>
      <td className="px-2 py-2">
        {item.job_id ? (
          <span className="flex items-center gap-1.5">
            <ClipPlayer
              jobId={item.job_id}
              start={item.start}
              end={item.end}
              size="sm"
              label={`Play “${spoken}” from ${where}`}
            />
            <LinkButton
              size="sm"
              variant="secondary"
              icon={ArrowRight}
              href={paths.word(item.job_id, item.segment, item.word_idx, "insights")}
              aria-label={`Open “${item.word}” in ${where}`}
            >
              Open
            </LinkButton>
          </span>
        ) : (
          <span className="text-xs text-ink-2">Indexed from the command line: open it there.</span>
        )}
      </td>
    </tr>
  );
}

function Flags({ item }: { item: Occurrence }) {
  const flags: { icon: typeof CircleAlert; text: string }[] = [];
  if (item.low_confidence) flags.push({ icon: CircleAlert, text: "low confidence: maybe silence or noise" });
  if (item.oov) flags.push({ icon: CircleAlert, text: "not in the dictionary: its form is a guess" });
  if (item.too_short) flags.push({ icon: Clock, text: "very short: probably an alignment error" });
  if (flags.length === 0) return null;
  return (
    <ul className="mt-1 flex flex-col gap-0.5 text-xs text-ink-2">
      {flags.map(({ icon: Icon, text }) => (
        <li key={text} className="inline-flex items-center gap-1">
          <Icon size={13} aria-hidden="true" />
          {text}
        </li>
      ))}
    </ul>
  );
}
