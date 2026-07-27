import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { describe, expect, it, vi } from "vitest";

import { analysis, fakePlayer, renderWith, wordButton } from "../test/fixtures";
import { Transcript } from "./Transcript";

const noop = () => undefined;

const renderTranscript = (props: Partial<Parameters<typeof Transcript>[0]> = {}, player?: ReturnType<typeof fakePlayer>) =>
  renderWith(
    <Transcript
      analysis={analysis}
      selected={null}
      onSelect={noop}
      filter={new Set()}
      follow={false}
      {...props}
    />,
    player ? { player } : {},
  );

describe("Transcript", () => {
  it("pinta todas las palabras y marca las que tienen fenómeno", () => {
    renderTranscript();

    expect(wordButton("does")).toBeInTheDocument();
    expect(wordButton("work")).toBeInTheDocument();
    // "does" tiene reducción vocálica → recibe color de familia
    expect(wordButton("does")).toHaveClass("w--fam");
    expect(wordButton("work")).not.toHaveClass("w--fam");
  });

  it("el nombre accesible dice el fenómeno: la identidad no depende del color", () => {
    renderTranscript();
    expect(wordButton("that")).toHaveAccessibleName("that, t/d elidida");
    expect(wordButton("work")).toHaveAccessibleName("work");
  });

  it("al pulsar una palabra la selecciona y la reproduce", async () => {
    const onSelect = vi.fn();
    const { player } = renderTranscript({ onSelect });

    await userEvent.click(wordButton("that"));

    expect(onSelect).toHaveBeenCalledWith({ segment: 0, index: 1 });
    // "that" dura 110 ms: el span se estira hasta ser audible, centrado en ella
    expect(player.play).toHaveBeenCalledWith(
      expect.objectContaining({ start: expect.closeTo(0.36, 2) }),
    );
  });

  it("atenúa lo que queda fuera del filtro sin ocultarlo", () => {
    renderTranscript({ filter: new Set(["t_deletion"]) });

    expect(wordButton("that")).not.toHaveClass("w--muted");
    expect(wordButton("does")).toHaveClass("w--muted");
    expect(wordButton("does")).toBeVisible();
  });

  it("sigue la reproducción resaltando la palabra que suena", () => {
    const player = fakePlayer();
    renderTranscript({}, player);

    act(() => player.clock.set(0.45));
    expect(wordButton("that")).toHaveClass("w--playing");
    expect(wordButton("does")).not.toHaveClass("w--playing");

    act(() => player.clock.set(2.05));
    expect(wordButton("wanna")).toHaveClass("w--playing");
    expect(wordButton("that")).not.toHaveClass("w--playing");
  });

  it("el botón de tiempo reproduce el segmento entero", async () => {
    const { player } = renderTranscript();

    await userEvent.click(screen.getByRole("button", { name: /0:00.0–0:01.2/ }));
    expect(player.play).toHaveBeenCalledWith({ start: 0, end: 1.2 });
  });

  it("muestra la forma reducida de una contracción y el enlace entre palabras", () => {
    renderTranscript();
    expect(screen.getByText("want to")).toBeInTheDocument();
    expect(screen.getAllByText("‿").length).toBeGreaterThan(0);
  });

  it("el tooltip compara diccionario con lo pronunciado, no el canónico consigo mismo", () => {
    renderTranscript();
    // "work" se pronuncia igual que su canónico: repetirlo no enseñaba nada
    expect(wordButton("work")).toHaveAttribute("title", "work · /wɝk/ → [wɝk]");
    expect(wordButton("that")).toHaveAttribute(
      "title",
      "that · /ðæt/ → [ðæ] — t/d elidida",
    );
  });

  it("ofrece la transcripción fonética de la frase entera", () => {
    renderTranscript();
    const detalles = screen.getAllByText("Transcripción fonética de la frase");
    expect(detalles).toHaveLength(2); // una por segmento
    expect(screen.getByText("[dəz‿ðæ wɝk]")).toBeInTheDocument();
    expect(screen.getByText("/dʌz‿ðæt wɝk/")).toBeInTheDocument();
  });
});
