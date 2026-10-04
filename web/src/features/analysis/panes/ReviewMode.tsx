/** Review mode: sampled human validation of the analysis. Shows the words the pipeline
 *  touched most (attracted, low confidence, highly divergent), plays them one by one
 *  and collects a verdict for each — by keyboard too. It writes the same review.json
 *  as `phonotrainer review`. */

import { Play, Save, Shuffle } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { ToggleButton as AriaToggleButton, ToggleButtonGroup } from "react-aria-components";

import { api } from "../../../api";
import { useHotkeys } from "../../../hooks/useHotkeys";
import { wordSpan } from "../../../lib/analysis";
import { fmtTime } from "../../../lib/format";
import { usePlayer } from "../../../player/PlayerProvider";
import { phenomenonLabel, useReference } from "../../../reference";
import type { Review, SampleItem, VerdictValue } from "../../../types";
import { Button, ButtonRow, cn, IconButton, Ipa, Kbd, Notice, Switch } from "../../../ui";

const key = (item: { segment: number; word_idx: number }) => `${item.segment}:${item.word_idx}`;

const VERDICT_KEY: Record<string, string> = { ok: "1", wrong: "2", unsure: "3" };

type Verdicts = Record<string, { verdict: VerdictValue; note: string }>;

const inputClass =
  "h-9 w-20 rounded-control bg-surface px-2 text-sm tabular-nums text-ink shadow-[inset_0_0_0_1px_var(--line-strong)] outline-none focus-visible:shadow-[inset_0_0_0_2px_var(--ink)]";

export function ReviewMode({ jobId }: { jobId: string }) {
  const player = usePlayer();
  const reference = useReference();
  const [n, setN] = useState(reference.review.default_n);
  const [seed, setSeed] = useState(reference.review.default_seed);
  const [items, setItems] = useState<SampleItem[]>([]);
  const [verdicts, setVerdicts] = useState<Verdicts>({});
  const [current, setCurrent] = useState(0);
  const [autoplay, setAutoplay] = useState(true);
  const [saved, setSaved] = useState<Review | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const listRef = useRef<HTMLOListElement | null>(null);

  const sample = useCallback(
    async (size: number, withSeed: number) => {
      setBusy(true);
      setError(null);
      try {
        const result = await api.reviewSample(jobId, size, withSeed);
        setItems(result.items);
        setCurrent(0);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      } finally {
        setBusy(false);
      }
    },
    [jobId],
  );

  // On open: recover the previous review (if any) and sample with its seed.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      let existing: Review | null = null;
      try {
        existing = await api.review(jobId);
      } catch {
        existing = null;
      }
      if (cancelled) return;
      if (existing && existing.items.length > 0) {
        setSaved(existing);
        setSeed(existing.seed);
        setN(existing.items.length);
        setVerdicts(
          Object.fromEntries(existing.items.map((item) => [key(item), { verdict: item.verdict, note: item.note }])),
        );
        void sample(existing.items.length, existing.seed);
      } else {
        void sample(reference.review.default_n, reference.review.default_seed);
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobId, sample]);

  const item = items[current];

  useEffect(() => {
    if (!autoplay || !item) return;
    player.play(wordSpan(item.word));
    // only when the card changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, items]);

  const decide = (index: number, value: VerdictValue, advance: boolean) => {
    const target = items[index];
    if (!target) return;
    setVerdicts((all) => ({ ...all, [key(target)]: { verdict: value, note: all[key(target)]?.note ?? "" } }));
    setCurrent(advance ? Math.min(index + 1, items.length - 1) : index);
  };

  const move = (delta: number) => setCurrent((index) => Math.max(0, Math.min(index + delta, items.length - 1)));

  useHotkeys({
    "1": () => decide(current, "ok", true),
    "2": () => decide(current, "wrong", true),
    "3": () => decide(current, "unsure", true),
    j: () => move(1),
    k: () => move(-1),
    ArrowDown: () => move(1),
    ArrowUp: () => move(-1),
  });

  useEffect(() => {
    const node = listRef.current?.querySelector("[data-current]");
    if (node && typeof node.scrollIntoView === "function") node.scrollIntoView({ block: "nearest" });
  }, [current]);

  const decided = items.filter((each) => verdicts[key(each)]).length;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const payload = items
        .filter((each) => verdicts[key(each)])
        .map((each) => ({
          segment: each.segment,
          word_idx: each.word_idx,
          verdict: verdicts[key(each)].verdict,
          note: verdicts[key(each)].note,
        }));
      setSaved(await api.saveReview(jobId, seed, payload));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-ink-2">
        Check the analysis by ear. The sample favors the words the pipeline was least sure about. Keys:{" "}
        <Kbd>1</Kbd> ok · <Kbd>2</Kbd> wrong · <Kbd>3</Kbd> unsure · <Kbd>J</Kbd>/<Kbd>K</Kbd> or ↓/↑ to move.
      </p>

      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          void sample(Math.min(Math.max(1, n || 1), reference.review.max_n), seed || 0);
        }}
      >
        <label className="flex flex-col gap-1 text-sm font-semibold text-ink">
          Words
          <input
            type="number"
            min={1}
            max={reference.review.max_n}
            value={n}
            className={inputClass}
            onChange={(event) => setN(Number(event.target.value))}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm font-semibold text-ink">
          Seed
          <input type="number" value={seed} className={inputClass} onChange={(event) => setSeed(Number(event.target.value))} />
        </label>
        <Button type="submit" size="sm" icon={Shuffle} isDisabled={busy}>
          Sample
        </Button>
      </form>

      {error && (
        <Notice tone="caution" title="Something went wrong" live>
          {error}
        </Notice>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <strong className="text-sm text-ink" role="status">
          {decided}/{items.length} reviewed
        </strong>
        <Switch isSelected={autoplay} onChange={setAutoplay}>
          Play each word when it comes up
        </Switch>
        <Button variant="primary" size="sm" icon={Save} isDisabled={busy || decided === 0} onPress={() => void save()}>
          Save
        </Button>
      </div>

      {saved && (
        <p className="text-sm text-ink-2" role="status">
          Saved: {saved.ok} ok · {saved.wrong} wrong · {saved.unsure} unsure
          {saved.accuracy != null && ` · accuracy ${Math.round(saved.accuracy * 100)} %`}
        </p>
      )}

      <ol ref={listRef} className="flex flex-col gap-2" aria-label="Words to review">
        {items.map((each, index) => {
          const state = verdicts[key(each)];
          const word = each.word;
          const isCurrent = index === current;
          return (
            <li
              key={key(each)}
              data-current={isCurrent || undefined}
              aria-current={isCurrent ? "true" : undefined}
              onClick={() => setCurrent(index)}
              className={cn(
                "flex flex-col gap-2 rounded-card bg-surface p-3 shadow-[inset_0_0_0_1px_var(--line)]",
                isCurrent && "shadow-[inset_0_0_0_2px_var(--ink)]",
              )}
            >
              <div className="flex items-center gap-2">
                <strong className="text-base text-ink">{word.word}</strong>
                <span className="text-xs tabular-nums text-ink-muted">
                  {fmtTime(word.start)}–{fmtTime(word.end)}
                </span>
                <span className="flex-1" />
                <IconButton icon={Play} size="sm" label={`Play “${word.word}”`} onPress={() => player.play(wordSpan(word))} />
              </div>
              <p className="text-sm text-ink-2">
                <Ipa kind="phonemic">{word.dict_ipa}</Ipa> → <Ipa kind="phonetic" className="text-ink">{word.realized_ipa || "∅"}</Ipa>
                {word.realized_raw_ipa && (
                  <span className="text-ink-muted">
                    {" "}
                    (raw <Ipa kind="phonetic">{word.realized_raw_ipa}</Ipa>)
                  </span>
                )}
              </p>
              <p className="text-xs text-ink-2">
                {word.phenomena.map((p) => phenomenonLabel(reference, p)).join(", ") || "no change labeled"}
                {word.attracted_count > 0 && ` · ${word.attracted_count} attracted`}
                {word.low_confidence && " · low confidence"}
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <ToggleButtonGroup
                  aria-label={`Verdict for “${word.word}”`}
                  selectionMode="single"
                  selectedKeys={state ? [state.verdict] : []}
                  onSelectionChange={(keys) => {
                    const value = [...keys][0];
                    if (value !== undefined) decide(index, String(value) as VerdictValue, false);
                  }}
                  className="inline-flex rounded-control bg-surface p-0.5 shadow-[inset_0_0_0_1px_var(--line-strong)]"
                >
                  {reference.verdicts.map((value) => (
                    <AriaToggleButton
                      key={value}
                      id={value}
                      className="inline-flex h-8 items-center gap-1 rounded-[5px] px-2.5 text-sm font-medium text-ink outline-none hover:bg-surface-2 data-[selected]:bg-ink data-[selected]:text-page"
                    >
                      {value}
                      {VERDICT_KEY[value] && (
                        <span aria-hidden="true" className="text-xs opacity-70">
                          {VERDICT_KEY[value]}
                        </span>
                      )}
                    </AriaToggleButton>
                  ))}
                </ToggleButtonGroup>
                <input
                  type="text"
                  aria-label={`Note for “${word.word}”`}
                  placeholder="note…"
                  value={state?.note ?? ""}
                  className="h-8 min-w-32 flex-1 rounded-control bg-surface px-2 text-sm text-ink shadow-[inset_0_0_0_1px_var(--line-strong)] outline-none focus-visible:shadow-[inset_0_0_0_2px_var(--ink)]"
                  onClick={(event) => event.stopPropagation()}
                  onChange={(event) =>
                    setVerdicts((all) => ({
                      ...all,
                      [key(each)]: { verdict: all[key(each)]?.verdict ?? "unsure", note: event.target.value },
                    }))
                  }
                />
              </div>
            </li>
          );
        })}
      </ol>
      {items.length > 0 && (
        <ButtonRow>
          <Button size="sm" variant="quiet" onPress={() => move(-1)}>
            Previous
          </Button>
          <Button size="sm" variant="quiet" onPress={() => move(1)}>
            Next
          </Button>
        </ButtonRow>
      )}
    </div>
  );
}
