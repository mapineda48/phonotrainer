import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { analysis, renderWith } from "../test/fixtures";
import { SummaryPanel } from "./SummaryPanel";

describe("SummaryPanel", () => {
  it("lists the phenomena with their counts and the family legend", () => {
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />,
    );

    expect(screen.getByRole("button", { name: /vowel reduction/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /t\/d deletion/ })).toBeInTheDocument();
    expect(screen.getByText("t/d processes")).toBeInTheDocument();
  });

  it("each bar toggles the filter", async () => {
    const onToggle = vi.fn();
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={onToggle} onClear={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole("button", { name: /t\/d deletion/ }));
    expect(onToggle).toHaveBeenCalledWith("t_deletion");
  });

  it("marks the active filter and offers to clear it", async () => {
    const onClear = vi.fn();
    renderWith(
      <SummaryPanel
        analysis={analysis}
        filter={new Set(["t_deletion"])}
        onToggle={vi.fn()}
        onClear={onClear}
      />,
    );

    expect(screen.getByRole("button", { name: /t\/d deletion/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await userEvent.click(screen.getByRole("button", { name: "Clear filter" }));
    expect(onClear).toHaveBeenCalled();
  });

  it("summarizes the analysis and whether attraction was applied", () => {
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />,
    );
    expect(screen.getByText(/enabled/)).toBeInTheDocument();
    expect(screen.getByText("faster-whisper small (int8)")).toBeInTheDocument();
  });
});
