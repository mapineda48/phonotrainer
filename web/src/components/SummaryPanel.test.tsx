import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { analysis, renderWith } from "../test/fixtures";
import { SummaryPanel } from "./SummaryPanel";

describe("SummaryPanel", () => {
  it("lista los fenómenos con su recuento y la leyenda de familias", () => {
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />,
    );

    expect(screen.getByRole("button", { name: /reducción vocálica/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /t\/d elidida/ })).toBeInTheDocument();
    expect(screen.getByText("Procesos de t/d")).toBeInTheDocument();
  });

  it("cada barra alterna el filtro", async () => {
    const onToggle = vi.fn();
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={onToggle} onClear={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole("button", { name: /t\/d elidida/ }));
    expect(onToggle).toHaveBeenCalledWith("t_deletion");
  });

  it("marca el filtro activo y ofrece quitarlo", async () => {
    const onClear = vi.fn();
    renderWith(
      <SummaryPanel
        analysis={analysis}
        filter={new Set(["t_deletion"])}
        onToggle={vi.fn()}
        onClear={onClear}
      />,
    );

    expect(screen.getByRole("button", { name: /t\/d elidida/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await userEvent.click(screen.getByRole("button", { name: "Quitar filtro" }));
    expect(onClear).toHaveBeenCalled();
  });

  it("resume el análisis y si hubo atracción", () => {
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />,
    );
    expect(screen.getByText(/activada/)).toBeInTheDocument();
    expect(screen.getByText("faster-whisper small (int8)")).toBeInTheDocument();
  });
});
