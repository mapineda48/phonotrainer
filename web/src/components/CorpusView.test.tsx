import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { renderWith } from "../test/fixtures";
import { CorpusView } from "./CorpusView";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return {
    ...original,
    api: {
      ...original.api,
      corpusStats: vi.fn(),
      corpusOccurrences: vi.fn(),
      corpusVariants: vi.fn(),
    },
  };
});

const stats = {
  analyses: 3,
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

const ocurrencia = {
  analysis_id: "job1",
  analysis_source: "ep1.webm",
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
};

describe("CorpusView", () => {
  beforeEach(() => {
    vi.mocked(api.corpusStats).mockResolvedValue(stats);
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
        { realized_ipa: "tʊ", count: 3, analyses: 2, dict_ipa: "tu" },
      ],
    });
  });

  it("resume todo el corpus, no un análisis suelto", async () => {
    renderWith(<CorpusView onOpen={vi.fn()} />);

    expect(await screen.findByText(/3 análisis/)).toBeInTheDocument();
    expect(screen.getByText(/540 palabras/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /linking/ })).toBeInTheDocument();
    // cuántas veces y en cuántos análisis: eso es lo que aporta el corpus
    const linking = screen.getByRole("button", { name: /linking/ });
    expect(within(linking).getByText(/90/)).toBeInTheDocument();
    expect(within(linking).getByText(/3 an\./)).toBeInTheDocument();
  });

  it("filtrar por fenómeno vuelve a pedir sus apariciones", async () => {
    renderWith(<CorpusView onOpen={vi.fn()} />);
    await screen.findByText(/3 análisis/);

    await userEvent.click(screen.getByRole("button", { name: /flapping/ }));

    await waitFor(() =>
      expect(api.corpusOccurrences).toHaveBeenLastCalledWith(
        expect.objectContaining({ phenomenon: "flapping" }),
      ),
    );
  });

  it("responde cómo se ha pronunciado una palabra en todo el corpus", async () => {
    renderWith(<CorpusView onOpen={vi.fn()} />);
    await screen.findByText(/3 análisis/);

    await userEvent.type(screen.getByRole("searchbox"), "to");
    await userEvent.click(screen.getByRole("button", { name: "Buscar" }));

    expect(await screen.findByText("[tə]")).toBeInTheDocument();
    expect(screen.getByText("9 veces")).toBeInTheDocument();
    expect(screen.getByText("en 3 análisis")).toBeInTheDocument();
  });

  it("cada aparición abre su análisis en esa palabra exacta", async () => {
    const onOpen = vi.fn();
    renderWith(<CorpusView onOpen={onOpen} />);

    const fila = (await screen.findByText("better")).closest("tr")!;
    await userEvent.click(within(fila).getByText("better"));

    expect(onOpen).toHaveBeenCalledWith("job1", { segment: 4, index: 2 });
  });

  it("un corpus vacío lo dice en vez de mostrar tablas vacías", async () => {
    vi.mocked(api.corpusStats).mockResolvedValue({
      ...stats, analyses: 0, words: 0, phenomena: [], top_words: [],
    });
    renderWith(<CorpusView onOpen={vi.fn()} />);

    expect(await screen.findByText(/El corpus está vacío/)).toBeInTheDocument();
  });
});
