import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { makeWord, renderWith } from "../test/fixtures";
import { PhoneTimeline, splitIpa } from "./PhoneTimeline";

describe("PhoneTimeline", () => {
  const word = makeWord("that", 0.4, "ð æ t", "ð æ", { phenomena: ["t_deletion"] });

  it("muestra las tres filas: diccionario, canónico y real", () => {
    renderWith(<PhoneTimeline word={word} />);

    expect(screen.getByText(/diccionario/)).toBeInTheDocument();
    expect(screen.getByText("canónico alineado")).toBeInTheDocument();
    expect(screen.getByText("realmente pronunciado")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "ð" })).toHaveLength(2); // filas con tiempo
  });

  it("la fila de diccionario destapa el fenómeno cuando el canónico ya lo aplica", () => {
    // espeak canoniza "better" CON flap: canónico y real son idénticos y la /t/
    // solo existe en el diccionario. Sin esa fila el flapping sería invisible.
    const better = makeWord("better", 8.9, "b ɛ ɾ ɚ", "b ɛ ɾ ɚ", {
      phenomena: ["flapping"],
      dict_ipa: "bɛtɚ",
    });
    const { container } = renderWith(<PhoneTimeline word={better} />);

    const dict = container.querySelector(".phones__dict") as HTMLElement;
    expect(within(dict).getByText("t")).toHaveClass("phone--diff");
    expect(within(dict).getByText("b")).not.toHaveClass("phone--diff");
  });

  it("marca el fono que no tiene pareja en la otra fila", () => {
    renderWith(<PhoneTimeline word={word} />);
    expect(screen.getByRole("button", { name: "t" })).toHaveClass("phone--diff");
    expect(screen.getAllByRole("button", { name: "ð" })[0]).not.toHaveClass("phone--diff");
  });

  it("no marca como divergentes fonos que solo se rozan en el tiempo", () => {
    // los tiempos son picos de un frame: el mismo fono puede aparecer 20 ms
    // desplazado en cada fila y no por eso es una divergencia
    const desfasado = {
      ...makeWord("don't", 0.6, "d oʊ n", "d oʊ n"),
      canonical_aligned: [["n", 0.684, 0.704]] as [string, number, number][],
      realized_aligned: [["n", 0.704, 0.744]] as [string, number, number][],
    };
    renderWith(<PhoneTimeline word={desfasado} />);
    for (const boton of screen.getAllByRole("button", { name: "n" })) {
      expect(boton).not.toHaveClass("phone--diff");
    }
  });

  it("al pulsar un fono lo reproduce con un margen", async () => {
    const { player } = renderWith(<PhoneTimeline word={word} />);

    await userEvent.click(screen.getByRole("button", { name: "t" }));

    const span = (player.play as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(span.start).toBeCloseTo(0.5, 2);
    expect(span.end).toBeGreaterThan(span.start);
  });

  it("con un fenómeno de frontera incluye la palabra siguiente y la marca", () => {
    const thing = makeWord("thing", 5.9, "θ ɪ ŋ", "θ ɪ ŋ", {
      phenomena: ["linking"],
      boundary_link_next: true,
    });
    const about = makeWord("about", 6.2, "ə b aʊ t", "ə b aʊ t");
    renderWith(<PhoneTimeline word={thing} next={about} />);

    expect(screen.getByText("frontera")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "aʊ" }).length).toBeGreaterThan(0);
  });

  it("avisa cuando no se reconoció nada", () => {
    const mudo = { ...makeWord("uh", 1, "ʌ", "ʌ"), realized_aligned: [], realized_ipa: "" };
    renderWith(<PhoneTimeline word={mudo} />);
    expect(screen.getByText(/nada reconocido/)).toBeInTheDocument();
  });

  it("no vende duración donde solo hay un instante detectado", () => {
    renderWith(<PhoneTimeline word={word} />);
    expect(screen.getByText(/instante detectado/)).toBeInTheDocument();
  });

  it("posiciona cada fono según su instante", () => {
    const { container } = renderWith(<PhoneTimeline word={word} />);
    const rows = container.querySelectorAll(".phones__row");
    const first = within(rows[0] as HTMLElement).getAllByRole("button")[0];
    expect((first as HTMLElement).style.left).toBe("0%");
  });
});

describe("splitIpa", () => {
  it("mantiene los diacríticos con su símbolo base", () => {
    expect(splitIpa("bɛtɚ")).toEqual(["b", "ɛ", "t", "ɚ"]);
    expect(splitIpa("ɑːɹ")).toEqual(["ɑː", "ɹ"]);
    expect(splitIpa("")).toEqual([]);
  });
});
