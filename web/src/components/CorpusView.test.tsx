import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { renderWith } from "../test/fixtures";
import { CorpusView, type CorpusFilters } from "./CorpusView";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return {
    ...original,
    api: {
      ...original.api,
      corpusStats: vi.fn(),
      corpusAnalyses: vi.fn(),
      corpusOccurrences: vi.fn(),
      corpusVariants: vi.fn(),
    },
  };
});

const stats = {
  analyses: 3,
  sources: 2,
  materials: 1,
  words: 540,
  segments: 81,
  duration: 128,
  phenomena: [
    { phenomenon: "linking", count: 90, analyses: 3 },
    { phenomenon: "t_deletion", count: 51, analyses: 3 },
    { phenomenon: "flapping", count: 18, analyses: 2 },
  ],
  top_words: [{ word: "to", count: 12 }],
};

const analyses = [
  { id: "/tmp/out", job_id: "job1", source: "ep1.webm", duration: 42.7, words: 182,
    segments: 27, attraction: true, duplicate_source: true, indexed_at: "2026-07-26T18:00:00+00:00" },
  { id: "/tmp/out_noattr", job_id: "job2", source: "ep1.webm", duration: 42.7, words: 182,
    segments: 27, attraction: false, duplicate_source: true, indexed_at: "2026-07-26T18:01:00+00:00" },
];

const ocurrencia = {
  analysis_id: "/tmp/out",
  job_id: "job1",
  analysis_source: "ep1.webm",
  analysis_attraction: true,
  next_word: "you",
  segment: 4,
  word_idx: 2,
  word: "better",
  start: 8.94,
  end: 9.12,
  dict_ipa: "bɛtɚ",
  canonical_ipa: "bɛɾɚ",
  realized_ipa: "bɛɾɚ",
  realized_raw_ipa: "",
  diff_cost: 0,
  attracted_count: 0,
  low_confidence: false,
  oov: false,
  lexical_form: null,
  phenomena: ["flapping"],
  too_short: false,
};

/** La vista recibe los filtros de arriba: aquí los mantenemos como App. */
function Anfitrion({ onOpen = vi.fn() }: { onOpen?: (id: string, s: unknown) => void }) {
  const [filters, setFilters] = useState<CorpusFilters>({ phenomenon: null, word: "" });
  return <CorpusView onOpen={onOpen} filters={filters} onFilters={setFilters} />;
}

describe("CorpusView", () => {
  beforeEach(() => {
    vi.mocked(api.corpusStats).mockResolvedValue(stats);
    vi.mocked(api.corpusAnalyses).mockResolvedValue({ items: analyses });
    vi.mocked(api.corpusOccurrences).mockResolvedValue({
      phenomenon: null,
      word: null,
      total: 1,
      items: [ocurrencia],
    });
    vi.mocked(api.corpusVariants).mockResolvedValue({
      word: "to",
      variants: [
        { realized_ipa: "tə", count: 9, analyses: 3, dict_ipa: "tu" },
        { realized_ipa: "tʊ", count: 1, analyses: 1, dict_ipa: "tu" },
      ],
    });
  });

  it("resume todo el corpus, no un análisis suelto", async () => {
    renderWith(<Anfitrion />);

    expect(await screen.findByText(/3 análisis de 1 grabación/)).toBeInTheDocument();
    expect(screen.getByText(/540 palabras/)).toBeInTheDocument();
    const linking = screen.getByRole("button", { name: /linking/ });
    expect(within(linking).getByText(/90/)).toBeInTheDocument();
    expect(within(linking).getByText(/3 an\./)).toBeInTheDocument();
  });

  it("avisa de que hay material contado dos veces y deja verlo", async () => {
    renderWith(<Anfitrion />);
    await screen.findByText(/3 análisis de 1 grabación/);

    expect(screen.getByText(/la cuentan más de una vez/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /ver de qué se compone/ }));
    const tabla = screen.getByTestId("corpus-analyses");
    expect(within(tabla).getAllByText("ep1.webm")).toHaveLength(2);
    expect(within(tabla).getByText(/sin atracción/)).toBeInTheDocument();
    expect(within(tabla).getAllByText(/material repetido/)).toHaveLength(2);
  });

  it("filtrar por fenómeno mantiene la palabra buscada y la cabecera no miente", async () => {
    renderWith(<Anfitrion />);
    await screen.findByText(/3 análisis de 1 grabación/);

    await userEvent.type(screen.getByRole("searchbox"), "to");
    await userEvent.click(screen.getByRole("button", { name: "Buscar" }));
    await waitFor(() =>
      expect(api.corpusOccurrences).toHaveBeenLastCalledWith(
        expect.objectContaining({ word: "to" }),
      ),
    );

    await userEvent.click(screen.getByRole("button", { name: /flapping/ }));
    await waitFor(() =>
      expect(api.corpusOccurrences).toHaveBeenLastCalledWith(
        expect.objectContaining({ phenomenon: "flapping", word: "to" }),
      ),
    );
  });

  it("responde cómo se ha pronunciado una palabra, y cada forma filtra la lista", async () => {
    renderWith(<Anfitrion />);
    await screen.findByText(/3 análisis de 1 grabación/);

    await userEvent.type(screen.getByRole("searchbox"), "to");
    await userEvent.click(screen.getByRole("button", { name: "Buscar" }));

    expect(await screen.findByText("[tə]")).toBeInTheDocument();
    expect(screen.getByText("9 veces")).toBeInTheDocument();
    expect(screen.getByText("1 vez")).toBeInTheDocument();      // singular, no "1 veces"

    await userEvent.click(screen.getByText("[tə]"));
    expect(screen.getByText(/pronunciadas \[tə\]/)).toBeInTheDocument();
  });

  it("cada aparición abre su análisis en esa palabra, también con el teclado", async () => {
    const onOpen = vi.fn();
    renderWith(<Anfitrion onOpen={onOpen} />);

    const fila = await screen.findByRole("button", { name: /abrir «better»/ });
    await userEvent.click(fila);
    expect(onOpen).toHaveBeenCalledWith("job1", { segment: 4, index: 2 });

    onOpen.mockClear();
    fila.focus();
    await userEvent.keyboard("{Enter}");
    expect(onOpen).toHaveBeenCalledWith("job1", { segment: 4, index: 2 });
  });

  it("un corpus vacío lo dice en vez de mostrar tablas vacías", async () => {
    vi.mocked(api.corpusStats).mockResolvedValue({
      ...stats, analyses: 0, sources: 0, materials: 0, words: 0, phenomena: [], top_words: [],
    });
    renderWith(<Anfitrion />);

    expect(await screen.findByText(/El corpus está vacío/)).toBeInTheDocument();
  });

  it("una respuesta que llega tarde no pisa a la actual", async () => {
    const pendiente: { resolver?: (value: never) => void } = {};
    vi.mocked(api.corpusOccurrences)
      .mockImplementationOnce(
        () => new Promise((resolve) => {
          pendiente.resolver = resolve as (value: never) => void;
        }),
      )
      .mockResolvedValue({
        phenomenon: "flapping", word: null, total: 1,
        items: [{ ...ocurrencia, word: "water" }],
      });

    renderWith(<Anfitrion />);
    await screen.findByText(/3 análisis de 1 grabación/);
    await userEvent.click(screen.getByRole("button", { name: /flapping/ }));
    await screen.findByText("water");

    pendiente.resolver?.({ phenomenon: null, word: null, total: 99,
                           items: [ocurrencia] } as never);

    await waitFor(() => expect(screen.getByText("water")).toBeInTheDocument());
    expect(screen.queryByText(/99 apariciones/)).not.toBeInTheDocument();
  });
});
