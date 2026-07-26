import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { analysis, makeWord, renderWith } from "../test/fixtures";
import { WordDetail } from "./WordDetail";

const segment = analysis.segments[0];

const render = (word = segment.words[1], next: Parameters<typeof WordDetail>[0]["next"] = null,
                canPlay = true) =>
  renderWith(
    <WordDetail
      word={word}
      next={next}
      segment={segment}
      segmentIndex={0}
      isEmphasis={false}
      canPlay={canPlay}
    />,
  );

describe("WordDetail", () => {
  it("distingue diccionario, canónico y real, y lo explica a la vista", () => {
    render();

    expect(screen.getByText("/ðæt/")).toBeInTheDocument();
    expect(screen.getByText("[ðæt]")).toBeInTheDocument();
    expect(screen.getByText("[ðæ]")).toBeInTheDocument();
    // la diferencia entre los tres conceptos no puede vivir solo en un tooltip
    expect(screen.getByText(/espeak ya aplica procesos nativos/)).toBeInTheDocument();
  });

  it("llama a lexical_form «forma reducida» y muestra la plena si existe", () => {
    const wanna = makeWord("wanna", 2, "w ɑ n ə", "w ɑ n ə", {
      phenomena: ["contraction_lex"],
      lexical_form: "wanna",
      lexical_expansion: "want to",
    });
    render(wanna);

    expect(screen.getByText("forma reducida")).toBeInTheDocument();
    expect(screen.getByText(/“wanna”/)).toBeInTheDocument();
    expect(screen.getByText(/“want to”/)).toBeInTheDocument();
  });

  it("con un fenómeno de frontera ofrece oír el enlace con la siguiente", async () => {
    const thing = makeWord("thing", 5.9, "θ ɪ ŋ", "θ ɪ ŋ", {
      phenomena: ["linking"],
      boundary_link_next: true,
    });
    const about = makeWord("about", 6.2, "ə b aʊ t", "ə b aʊ t");
    const { player } = render(thing, about);

    const boton = screen.getByRole("button", { name: "▶ + about" });
    await userEvent.click(boton);

    const span = (player.play as ReturnType<typeof import("vitest").vi.fn>).mock.calls[0][0];
    expect(span.start).toBeCloseTo(5.88, 2);
    expect(span.end).toBeGreaterThan(about.end); // llega hasta después de la siguiente
  });

  it("avisa de que las etiquetas no son verificables si no se reconoció ningún fono", () => {
    const vacia = {
      ...makeWord("and", 3, "æ n d", "æ n d", {
        phenomena: ["t_deletion", "word_elision"],
        low_confidence: true,
      }),
      realized_aligned: [],
      realized_ipa: "",
    };
    render(vacia);

    expect(screen.getByText(/no son verificables/)).toBeInTheDocument();
  });

  it("sin audio los botones de reproducción quedan deshabilitados", () => {
    render(segment.words[1], null, false);
    expect(screen.getByRole("button", { name: "▶ Palabra" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "▶ Frase" })).toBeDisabled();
  });
});
