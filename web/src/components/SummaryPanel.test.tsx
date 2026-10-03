import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { analysis, metrics, renderWith } from "../test/fixtures";
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

describe("SummaryPanel — reduction metrics and practice", () => {
  const measured = { ...analysis, summary: { ...analysis.summary, metrics } };

  it("measures how reduced the speech is next to the report's figures", () => {
    renderWith(
      <SummaryPanel analysis={measured} filter={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />,
    );

    const deviate = screen.getByTestId("metric-deviate");
    expect(deviate).toHaveTextContent("72.0 %");
    expect(deviate).toHaveTextContent(/126 of 175 words · report > 60 % \(Johnson 2004\) · in line/);
    expect(screen.getByTestId("metric-schwa_share")).toHaveTextContent(/in line with the report/);
    expect(screen.getByTestId("reduction-metrics")).toHaveTextContent(/timit61 engine/);
    expect(screen.getByTestId("final-t")).toHaveTextContent("flapped 2 · unreleased 1");
  });

  it("older analyses without metrics show no metrics section", () => {
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />,
    );
    expect(screen.queryByTestId("reduction-metrics")).toBeNull();
  });

  it("one click keeps only the phenomena safe to produce, another only those to recognize", async () => {
    const onSetFilter = vi.fn();
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set()} onToggle={vi.fn()} onClear={vi.fn()}
                    onSetFilter={onSetFilter} />,
    );

    await userEvent.click(screen.getByRole("button", { name: "safe to produce" }));
    expect(onSetFilter).toHaveBeenLastCalledWith(["linking", "vowel_reduction", "contraction_lex"]);
    await userEvent.click(screen.getByRole("button", { name: "recognize only" }));
    expect(onSetFilter).toHaveBeenLastCalledWith(["t_deletion"]);
  });

  it("the practice shortcut toggles off when it is already the filter", async () => {
    const onSetFilter = vi.fn();
    renderWith(
      <SummaryPanel analysis={analysis} filter={new Set(["t_deletion"])} onToggle={vi.fn()}
                    onClear={vi.fn()} onSetFilter={onSetFilter} />,
    );

    const button = screen.getByRole("button", { name: "recognize only" });
    expect(button).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(button);
    expect(onSetFilter).toHaveBeenLastCalledWith([]);
  });
});
