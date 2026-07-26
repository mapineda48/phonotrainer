import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { describe, expect, it, vi } from "vitest";

import { analysis, fakePlayer, renderWith } from "../test/fixtures";
import { Transcript } from "./Transcript";

const noop = () => undefined;

describe("Transcript", () => {
  it("pinta todas las palabras y marca las que tienen fenómeno", () => {
    renderWith(
      <Transcript analysis={analysis} selected={null} onSelect={noop} filter={new Set()} follow={false} />,
    );

    expect(screen.getByRole("button", { name: "does" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "work" })).toBeInTheDocument();
    // "does" tiene reducción vocálica → recibe color de familia
    expect(screen.getByRole("button", { name: "does" })).toHaveClass("w--fam");
    expect(screen.getByRole("button", { name: "work" })).not.toHaveClass("w--fam");
  });

  it("al pulsar una palabra la selecciona y la reproduce", async () => {
    const onSelect = vi.fn();
    const { player } = renderWith(
      <Transcript analysis={analysis} selected={null} onSelect={onSelect} filter={new Set()} follow={false} />,
    );

    await userEvent.click(screen.getByRole("button", { name: "that" }));

    expect(onSelect).toHaveBeenCalledWith({ segment: 0, index: 1 });
    expect(player.play).toHaveBeenCalledWith(
      expect.objectContaining({ start: expect.closeTo(0.38, 2) }),
    );
  });

  it("atenúa lo que queda fuera del filtro sin ocultarlo", () => {
    renderWith(
      <Transcript
        analysis={analysis}
        selected={null}
        onSelect={noop}
        filter={new Set(["t_deletion"])}
        follow={false}
      />,
    );

    expect(screen.getByRole("button", { name: "that" })).not.toHaveClass("w--muted");
    expect(screen.getByRole("button", { name: "does" })).toHaveClass("w--muted");
    expect(screen.getByRole("button", { name: "does" })).toBeVisible();
  });

  it("sigue la reproducción resaltando la palabra que suena", () => {
    const player = fakePlayer();
    renderWith(
      <Transcript analysis={analysis} selected={null} onSelect={noop} filter={new Set()} follow={false} />,
      { player },
    );

    act(() => player.clock.set(0.45));
    expect(screen.getByRole("button", { name: "that" })).toHaveClass("w--playing");
    expect(screen.getByRole("button", { name: "does" })).not.toHaveClass("w--playing");

    act(() => player.clock.set(2.05));
    expect(screen.getByRole("button", { name: "wanna" })).toHaveClass("w--playing");
    expect(screen.getByRole("button", { name: "that" })).not.toHaveClass("w--playing");
  });

  it("el botón de tiempo reproduce el segmento entero", async () => {
    const { player } = renderWith(
      <Transcript analysis={analysis} selected={null} onSelect={noop} filter={new Set()} follow={false} />,
    );

    await userEvent.click(screen.getByRole("button", { name: /0:00.0–0:01.2/ }));
    expect(player.play).toHaveBeenCalledWith({ start: 0, end: 1.2 });
  });

  it("muestra la forma plena de una contracción y el enlace entre palabras", () => {
    renderWith(
      <Transcript analysis={analysis} selected={null} onSelect={noop} filter={new Set()} follow={false} />,
    );
    expect(screen.getByText("want to")).toBeInTheDocument();
    expect(screen.getByText("‿")).toBeInTheDocument();
  });
});
