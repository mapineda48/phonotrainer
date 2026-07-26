import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { makeWord, renderWith } from "../test/fixtures";
import { PhoneTimeline } from "./PhoneTimeline";

describe("PhoneTimeline", () => {
  const word = makeWord("that", 0.4, "ð æ t", "ð æ", { phenomena: ["t_deletion"] });

  it("muestra las dos filas alineadas en el tiempo", () => {
    renderWith(<PhoneTimeline word={word} />);

    expect(screen.getByText("canónico alineado")).toBeInTheDocument();
    expect(screen.getByText("realmente pronunciado")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "ð" })).toHaveLength(2); // en ambas filas
  });

  it("marca el fono que no tiene pareja en la otra fila", () => {
    renderWith(<PhoneTimeline word={word} />);

    // la /t/ canónica no se pronunció → divergente; la /ð/ coincide → no
    expect(screen.getByRole("button", { name: "t" })).toHaveClass("phone--diff");
    expect(screen.getAllByRole("button", { name: "ð" })[0]).not.toHaveClass("phone--diff");
  });

  it("al pulsar un fono lo reproduce con un margen", async () => {
    const { player } = renderWith(<PhoneTimeline word={word} />);

    await userEvent.click(screen.getByRole("button", { name: "t" }));

    const span = (player.play as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(span.start).toBeCloseTo(0.5, 2);
    expect(span.end).toBeGreaterThan(span.start);
  });

  it("avisa cuando no se reconoció nada", () => {
    const mudo = { ...makeWord("uh", 1, "ʌ", "ʌ"), realized_aligned: [], realized_ipa: "" };
    renderWith(<PhoneTimeline word={mudo} />);
    expect(screen.getByText(/nada reconocido/)).toBeInTheDocument();
  });

  it("posiciona cada fono según su instante", () => {
    const { container } = renderWith(<PhoneTimeline word={word} />);
    const rows = container.querySelectorAll(".phones__row");
    const first = within(rows[0] as HTMLElement).getAllByRole("button")[0];
    expect((first as HTMLElement).style.left).toBe("0%");
  });
});
