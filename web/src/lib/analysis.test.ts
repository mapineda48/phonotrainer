import { describe, expect, it } from "vitest";

import { analysis, makeWord } from "../test/fixtures";
import {
  filteredWords,
  findActiveIndex,
  flattenWords,
  matchesFilter,
  phenomenaByFrequency,
  wordFamilies,
  wordSpan,
} from "./analysis";

const spans = [
  { start: 0, end: 0.5 },
  { start: 1, end: 1.4 },
  { start: 2, end: 2.9 },
];

describe("findActiveIndex", () => {
  it("encuentra el span que contiene el instante", () => {
    expect(findActiveIndex(spans, 0.2)).toBe(0);
    expect(findActiveIndex(spans, 1.3)).toBe(1);
    expect(findActiveIndex(spans, 2.5)).toBe(2);
  });

  it("aguanta el resaltado en el hueco entre palabras", () => {
    expect(findActiveIndex(spans, 0.7)).toBe(0); // dentro de la tolerancia
    expect(findActiveIndex(spans, 0.9)).toBe(-1); // silencio largo
  });

  it("se adelanta a la palabra que está a punto de sonar", () => {
    // al pulsar una palabra se reproduce 20 ms antes: el resaltado ya es suyo
    expect(findActiveIndex(spans, 0.98)).toBe(1);
  });

  it("devuelve -1 antes de que empiece nada", () => {
    expect(findActiveIndex(spans, -1)).toBe(-1);
    expect(findActiveIndex([], 5)).toBe(-1);
  });
});

describe("filtros", () => {
  const word = makeWord("that", 0, "ð æ t", "ð æ", { phenomena: ["t_deletion"] });

  it("un filtro vacío deja pasar todo", () => {
    expect(matchesFilter(word, new Set())).toBe(true);
  });

  it("filtra por fenómeno", () => {
    expect(matchesFilter(word, new Set(["t_deletion"]))).toBe(true);
    expect(matchesFilter(word, new Set(["flapping"]))).toBe(false);
  });

  it("lista las palabras del filtro en orden temporal", () => {
    const matches = filteredWords(analysis, new Set(["vowel_reduction", "contraction_lex"]));
    expect(matches.map((match) => match.word.word)).toEqual(["does", "wanna"]);
    expect(matches[1]).toMatchObject({ segment: 1, index: 0 });
  });

  it("sin filtro no hay saltos posibles", () => {
    expect(filteredWords(analysis, new Set())).toEqual([]);
  });
});

describe("ayudas varias", () => {
  it("aplana las palabras conservando su posición", () => {
    const flat = flattenWords(analysis);
    expect(flat).toHaveLength(5);
    expect(flat[3]).toMatchObject({ segment: 1, index: 0 });
  });

  it("ordena los fenómenos de más a menos frecuente", () => {
    expect(phenomenaByFrequency(analysis)).toEqual([
      ["linking", 7],
      ["vowel_reduction", 4],
      ["t_deletion", 2],
      ["contraction_lex", 1],
    ]);
  });

  it("resuelve la familia de color de una palabra", () => {
    const word = makeWord("x", 0, "a", "a", { phenomena: ["t_deletion", "linking"] });
    expect(wordFamilies(word, { t_deletion: "td", linking: "fron" })).toEqual(["td", "fron"]);
    expect(wordFamilies(makeWord("y", 0, "a", "a"), {})).toEqual([]);
  });

  it("añade margen al span de una palabra sin irse a negativo", () => {
    expect(wordSpan(makeWord("x", 0, "a", "a"))).toEqual({ start: 0, end: 0.11 });
  });
});
