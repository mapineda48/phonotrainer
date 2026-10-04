/** /learn/ipa — every sound the analyzer writes, laid out like an IPA chart in plain
 *  words (lips, gum ridge, throat…). Selecting a symbol shows the mouth making it, what to
 *  do with the tongue and lips, and words from the learner's own clips that contain it. */

import { ArrowRight, Grid3x3 } from "lucide-react";
import { useMemo } from "react";
import { useLocation, useSearch } from "wouter";

import { api } from "../../api";
import { lookupPhone } from "../../articulation/phones";
import { ipaTerm, lookupGlossary } from "../../didactic/glossary";
import { SYMBOL_PHENOMENON } from "../../didactic/glossary/ipa";
import { paths } from "../../paths";
import { phenomenonLabel, useReference } from "../../reference";
import { Card, cn, EmptyState, Ipa, Notice, PageHeader, RichText, TextLink } from "../../ui";
import { capitalize, containsSymbol, isPlayable, pickExamples, useLoad } from "./corpus";
import { ExampleList } from "./ExampleList";
import {
  ALLOPHONE_PLACES,
  ALLOPHONE_ROWS,
  CONSONANT_ROWS,
  GLIDING_GROUPS,
  PLACES,
  REDUCED_VOWELS,
  SYLLABICS,
  unplacedSymbols,
  VOWEL_COLUMNS,
  VOWEL_ROWS,
  type GridRow,
} from "./ipaLayout";
import { PhoneMouth } from "./MouthDemo";

function SymbolButton({
  symbol,
  selected,
  onSelect,
}: {
  symbol: string;
  selected: boolean;
  onSelect: (symbol: string) => void;
}) {
  const phone = lookupPhone(symbol);
  const weak = REDUCED_VOWELS.has(symbol);
  return (
    <button
      type="button"
      onClick={() => onSelect(symbol)}
      aria-pressed={selected}
      aria-label={`[${symbol}] ${phone?.name ?? ""}${weak ? ", a weak vowel" : ""}`.trim()}
      className={cn(
        "inline-flex min-h-10 min-w-10 items-center justify-center rounded-control px-1.5 text-xl",
        "transition-colors duration-(--dur-fast)",
        selected
          ? "bg-ink text-page"
          : "bg-surface text-ink shadow-[inset_0_0_0_1px_var(--line)] hover:bg-surface-2",
      )}
    >
      <Ipa className={cn(weak && "underline decoration-dotted decoration-2 underline-offset-4")}>{symbol}</Ipa>
    </button>
  );
}

function Grid<C extends string>({
  caption,
  columns,
  rows,
  selected,
  onSelect,
}: {
  caption: string;
  columns: readonly { key: C; label: string; detail?: string }[];
  rows: readonly GridRow<C>[];
  selected: string | null;
  onSelect: (symbol: string) => void;
}) {
  return (
    <div className="relative overflow-x-auto">
      <table className="w-full border-collapse text-left">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            <td />
            {columns.map((column) => (
              <th key={column.key} scope="col" className="px-1 pb-2 align-bottom text-xs font-semibold text-ink-2">
                {column.label}
                {column.detail && <span className="block font-normal text-ink-muted">{column.detail}</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.label} className="border-t border-line">
              <th scope="row" className="py-1.5 pr-2 text-sm font-semibold text-ink">
                {row.label}
                {row.detail && <span className="block text-xs font-normal text-ink-2">{row.detail}</span>}
              </th>
              {columns.map((column) => (
                <td key={column.key} className="px-1 py-1.5 align-middle">
                  <div className="flex flex-wrap gap-1">
                    {(row.cells[column.key] ?? []).map((symbol) => (
                      <SymbolButton
                        key={symbol}
                        symbol={symbol}
                        selected={selected === symbol}
                        onSelect={onSelect}
                      />
                    ))}
                  </div>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SymbolGroup({
  title,
  detail,
  symbols,
  selected,
  onSelect,
}: {
  title: string;
  detail: string;
  symbols: readonly string[];
  selected: string | null;
  onSelect: (symbol: string) => void;
}) {
  const id = `ipa-group-${title.replace(/\W+/g, "-").toLowerCase()}`;
  return (
    <div>
      <h3 id={id} className="text-sm font-semibold text-ink">
        {title}
        <span className="ml-2 font-normal text-ink-2">{detail}</span>
      </h3>
      <ul aria-labelledby={id} className="mt-1.5 flex flex-wrap gap-1">
        {symbols.map((symbol) => (
          <li key={symbol}>
            <SymbolButton symbol={symbol} selected={selected === symbol} onSelect={onSelect} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function SymbolDetail({ symbol }: { symbol: string }) {
  const reference = useReference();
  const phone = lookupPhone(symbol);
  const entry = lookupGlossary(ipaTerm(symbol));
  const phenomenon = SYMBOL_PHENOMENON[symbol];
  const tagged = useLoad("ipa-tagged-words", () => api.corpusOccurrences({ limit: 1000 }));

  const examples = useMemo(() => {
    if (tagged.status !== "ready") return [];
    const withSymbol = tagged.data.items.filter((item) =>
      containsSymbol(item.realized_ipa, symbol, reference.ipa_tokens),
    );
    return pickExamples(withSymbol, 6).filter(isPlayable);
  }, [tagged, symbol, reference.ipa_tokens]);

  if (!phone) return null;
  return (
    <div className="flex flex-col gap-4">
      <div aria-live="polite">
        <p className="text-5xl leading-none text-ink">
          <Ipa kind="phonetic">{symbol}</Ipa>
        </p>
        <h2 className="mt-2 text-xl font-semibold text-ink">{capitalize(phone.name)}</h2>
      </div>
      <PhoneMouth symbol={symbol} />
      {entry && (
        <p className="text-base text-ink">
          <RichText text={entry.body} />
        </p>
      )}
      <p className="text-sm text-ink-2">
        <strong className="text-ink">As in: </strong>
        {phone.example}
      </p>
      {phenomenon && (
        <p className="text-sm">
          <TextLink href={paths.phenomenon(phenomenon)}>
            It is the sound of {phenomenonLabel(reference, phenomenon)}
            <ArrowRight size={14} aria-hidden="true" className="ml-1 inline" />
          </TextLink>
        </p>
      )}
      <section aria-labelledby="ipa-in-your-clips">
        <h3 id="ipa-in-your-clips" className="text-base font-semibold text-ink">
          In your clips
        </h3>
        {tagged.status === "loading" && <p className="text-sm text-ink-2">Looking through your clips…</p>}
        {tagged.status === "error" && (
          <Notice tone="caution" title="Your clips could not be searched">
            {tagged.message}
          </Notice>
        )}
        {tagged.status === "ready" &&
          (examples.length > 0 ? (
            <ExampleList examples={examples} label={`Words from your clips with [${symbol}]`} />
          ) : (
            <p className="mt-1 text-sm text-ink-2">
              No changed word in your clips contains <Ipa kind="phonetic">{symbol}</Ipa> yet.
            </p>
          ))}
      </section>
    </div>
  );
}

export function IpaChart() {
  const search = useSearch();
  const [, navigate] = useLocation();
  const requested = new URLSearchParams(search).get("symbol");
  const selected = requested && lookupPhone(requested) ? requested : null;
  const select = (symbol: string) => navigate(paths.ipa(symbol), { replace: true });
  const other = unplacedSymbols();

  return (
    <div className="mx-auto w-full max-w-7xl px-6 py-8">
      <PageHeader
        eyebrow={
          <TextLink href={paths.learn()}>
            Learn
          </TextLink>
        }
        title="IPA chart"
        lede="Every sound the analyzer can write. Select a symbol to see the mouth make it and to hear it in your own clips."
      />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(320px,400px)]">
        <div className="flex min-w-0 flex-col gap-6">
          <Card title="Consonants" level={2} description="Columns: where the mouth closes. Rows: how the air gets out. In a pair, the first has no voice and the second has voice.">
            <Grid
              caption="English consonants by place and manner"
              columns={PLACES}
              rows={CONSONANT_ROWS}
              selected={selected}
              onSelect={select}
            />
          </Card>
          <Card
            title="Sounds that replace t and d"
            level={2}
            description="American English often says these instead of a full t or d: they are what the analyzer looks for."
          >
            <Grid
              caption="Allophones of t and d"
              columns={PLACES.filter((place) => ALLOPHONE_PLACES.includes(place.key))}
              rows={ALLOPHONE_ROWS}
              selected={selected}
              onSelect={select}
            />
            <div className="mt-4">
              <SymbolGroup
                title="Syllabic consonants"
                detail="a consonant that makes a whole syllable, with no vowel"
                symbols={SYLLABICS}
                selected={selected}
                onSelect={select}
              />
            </div>
          </Card>
          <Card
            title="Vowels"
            level={2}
            description={
              <>
                Columns: how far forward the tongue is. Rows: how high. Dotted underline = a weak vowel, the one
                unstressed syllables reduce to (<Ipa kind="phonetic">ə</Ipa> is the most frequent).
              </>
            }
          >
            <Grid
              caption="English vowels by tongue height and position"
              columns={VOWEL_COLUMNS}
              rows={VOWEL_ROWS}
              selected={selected}
              onSelect={select}
            />
            <div className="mt-4 flex flex-col gap-3">
              {GLIDING_GROUPS.map((group) => (
                <SymbolGroup
                  key={group.label}
                  title={group.label}
                  detail={group.detail}
                  symbols={group.symbols}
                  selected={selected}
                  onSelect={select}
                />
              ))}
            </div>
          </Card>
          {other.length > 0 && (
            <Card title="Other symbols" level={2}>
              <SymbolGroup title="Not yet placed" detail="" symbols={other} selected={selected} onSelect={select} />
            </Card>
          )}
        </div>

        <aside aria-label="Selected symbol" className="lg:sticky lg:top-6 lg:self-start">
          <Card>
            {selected ? (
              <SymbolDetail symbol={selected} />
            ) : (
              <EmptyState icon={Grid3x3} title="Choose a symbol">
                Each symbol is one sound. Start with <Ipa kind="phonetic">ɾ</Ipa>, <Ipa kind="phonetic">t̚</Ipa> or{" "}
                <Ipa kind="phonetic">ə</Ipa>: they are the sounds of the most common American changes.
              </EmptyState>
            )}
          </Card>
        </aside>
      </div>
    </div>
  );
}
