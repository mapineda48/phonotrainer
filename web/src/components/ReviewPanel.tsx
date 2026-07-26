/** Validación humana muestreada (la MEJORA 5 de la CLI, aquí con audio).
 *
 *  Muestra las palabras que el pipeline tocó más (atraídas, baja confianza,
 *  divergencia alta), las reproduce una a una y recoge el veredicto con el
 *  teclado. Guarda el mismo review.json que escribe `phonotrainer review`.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "../api";
import { useHotkeys } from "../hooks/useHotkeys";
import { wordSpan } from "../lib/analysis";
import { fmtTime } from "../lib/format";
import { usePlayer } from "../player/PlayerProvider";
import { phenomenonLabel, useReference } from "../reference";
import type { Review, SampleItem, VerdictValue } from "../types";

const key = (item: { segment: number; word_idx: number }) => `${item.segment}:${item.word_idx}`;

interface Props {
  jobId: string;
  onSaved: () => void;
}

export function ReviewPanel({ jobId, onSaved }: Props) {
  const player = usePlayer();
  // Veredictos y valores por defecto vienen del backend (review.py).
  const reference = useReference();
  const [n, setN] = useState(reference.review.default_n);
  const [seed, setSeed] = useState(reference.review.default_seed);
  const [items, setItems] = useState<SampleItem[]>([]);
  const [verdicts, setVerdicts] = useState<Record<string, { verdict: VerdictValue; note: string }>>({});
  const [current, setCurrent] = useState(0);
  const [autoplay, setAutoplay] = useState(true);
  const [saved, setSaved] = useState<Review | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const listRef = useRef<HTMLDivElement | null>(null);

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

  // Al abrir: recuperamos la revisión previa (si la hay) y muestreamos con su seed.
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
          Object.fromEntries(
            existing.items.map((item) => [key(item), { verdict: item.verdict, note: item.note }]),
          ),
        );
        void sample(existing.items.length, existing.seed);
      } else {
        void sample(reference.review.default_n, reference.review.default_seed);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [jobId, sample]);

  const item = items[current];

  useEffect(() => {
    if (!autoplay || !item) return;
    player.play(wordSpan(item.word));
    // Solo al cambiar de ficha: reproducir cada vez que cambia el estado sería ruido.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, items]);

  const setVerdict = (value: VerdictValue) => {
    if (!item) return;
    setVerdicts((current_) => ({
      ...current_,
      [key(item)]: { verdict: value, note: current_[key(item)]?.note ?? "" },
    }));
    setCurrent((index) => Math.min(index + 1, items.length - 1));
  };

  const move = (delta: number) =>
    setCurrent((index) => Math.max(0, Math.min(index + delta, items.length - 1)));

  useHotkeys({
    "1": () => setVerdict("ok"),
    "2": () => setVerdict("mal"),
    "3": () => setVerdict("dudosa"),
    j: () => move(1),
    k: () => move(-1),
    ArrowDown: () => move(1),
    ArrowUp: () => move(-1),
  });

  useEffect(() => {
    const node = listRef.current?.querySelector(".review__item--current");
    if (node && typeof node.scrollIntoView === "function") {
      node.scrollIntoView({ block: "nearest" });
    }
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
      onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel__body" ref={listRef}>
      <div className="row" style={{ marginBottom: 8 }}>
        <label className="field" style={{ flexDirection: "row", alignItems: "center", gap: 6 }}>
          n
          <input
            type="number"
            min={1}
            max={reference.review.max_n}
            value={n}
            style={{ width: 62 }}
            onChange={(event) => setN(Number(event.target.value))}
          />
        </label>
        <label className="field" style={{ flexDirection: "row", alignItems: "center", gap: 6 }}>
          seed
          <input
            type="number"
            value={seed}
            style={{ width: 70 }}
            onChange={(event) => setSeed(Number(event.target.value))}
          />
        </label>
        <button
          type="button"
          className="btn btn--sm"
          disabled={busy}
          onClick={() =>
            void sample(Math.min(Math.max(1, n || 1), reference.review.max_n), seed || 0)
          }
        >
          Muestrear
        </button>
      </div>

      <p className="tiny muted" style={{ margin: "0 0 10px" }}>
        Prioriza palabras atraídas, de baja confianza o muy divergentes. Teclas:{" "}
        <span className="kbd">1</span> ok · <span className="kbd">2</span> mal ·{" "}
        <span className="kbd">3</span> dudosa · <span className="kbd">j</span>/
        <span className="kbd">k</span> moverse.
      </p>

      {error && <p className="error">{error}</p>}

      <div className="row tiny" style={{ marginBottom: 10 }}>
        <strong>
          {decided}/{items.length} revisadas
        </strong>
        <span className="spacer" />
        <label className="row tiny" style={{ gap: 4 }}>
          <input
            type="checkbox"
            checked={autoplay}
            onChange={(event) => setAutoplay(event.target.checked)}
          />
          reproducir al avanzar
        </label>
        <button
          type="button"
          className="btn btn--primary btn--sm"
          disabled={busy || decided === 0}
          onClick={() => void save()}
        >
          Guardar
        </button>
      </div>

      {saved && (
        <p className="tiny dim">
          Guardado: {saved.ok} ok · {saved.mal} mal · {saved.dudosa} dudosa
          {saved.accuracy != null && ` · acierto ${Math.round(saved.accuracy * 100)}%`}
        </p>
      )}

      {items.map((each, index) => {
        const state = verdicts[key(each)];
        const word = each.word;
        return (
          <div
            key={key(each)}
            className={`review__item ${index === current ? "review__item--current" : ""}`}
            onClick={() => setCurrent(index)}
          >
            <div className="row">
              <strong>{word.word}</strong>
              <span className="muted tiny num">
                {fmtTime(word.start)}–{fmtTime(word.end)}
              </span>
              <span className="spacer" />
              <button
                type="button"
                className="btn btn--sm"
                onClick={(event) => {
                  event.stopPropagation();
                  player.play(wordSpan(word));
                }}
              >
                ▶
              </button>
            </div>

            <div className="tiny dim" style={{ margin: "4px 0" }}>
              <span className="ipa">/{word.dict_ipa}/</span> →{" "}
              <span className="ipa">[{word.canonical_ipa}]</span> vs{" "}
              <span className="ipa">[{word.realized_ipa || "∅"}]</span>
              {word.realized_raw_ipa && (
                <span className="ipa muted"> (crudo [{word.realized_raw_ipa}])</span>
              )}
            </div>

            <div className="tiny muted" style={{ marginBottom: 6 }}>
              {word.phenomena.map((p) => phenomenonLabel(reference, p)).join(", ") || "sin fenómenos"}
              {word.attracted_count > 0 && ` · ${word.attracted_count} atraído(s)`}
              {word.low_confidence && " · baja confianza"}
            </div>

            <div className="verdicts">
              {reference.verdicts.map((value) => (
                <button
                  key={value}
                  type="button"
                  className="btn btn--sm verdict"
                  data-verdict={value}
                  aria-pressed={state?.verdict === value}
                  onClick={(event) => {
                    event.stopPropagation();
                    setCurrent(index);
                    setVerdicts((current_) => ({
                      ...current_,
                      [key(each)]: { verdict: value, note: current_[key(each)]?.note ?? "" },
                    }));
                  }}
                >
                  {value}
                </button>
              ))}
              <input
                type="text"
                className="input"
                placeholder="nota…"
                value={state?.note ?? ""}
                onChange={(event) =>
                  setVerdicts((current_) => ({
                    ...current_,
                    [key(each)]: {
                      verdict: current_[key(each)]?.verdict ?? "dudosa",
                      note: event.target.value,
                    },
                  }))
                }
                onClick={(event) => event.stopPropagation()}
              />
            </div>
          </div>
        );
      })}
    </div>
  );
}
