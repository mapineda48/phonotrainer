import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { analysis, renderWith } from "../test/fixtures";
import { ReviewPanel } from "./ReviewPanel";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return {
    ...original,
    api: {
      ...original.api,
      review: vi.fn(),
      reviewSample: vi.fn(),
      saveReview: vi.fn(),
    },
  };
});

const sample = {
  seed: 48,
  n: 20,
  items: [
    {
      segment: 0,
      word_idx: 1,
      word: analysis.segments[0].words[1],
      segment_text: "does that work",
      segment_start: 0,
      segment_end: 1.2,
    },
    {
      segment: 1,
      word_idx: 0,
      word: analysis.segments[1].words[0],
      segment_text: "wanna go",
      segment_start: 2,
      segment_end: 3,
    },
  ],
};

const emptyReview = {
  seed: 48,
  sampled: 0,
  ok: 0,
  wrong: 0,
  unsure: 0,
  accuracy: null,
  items: [],
};

describe("ReviewPanel", () => {
  beforeEach(() => {
    vi.mocked(api.review).mockResolvedValue(emptyReview);
    vi.mocked(api.reviewSample).mockResolvedValue(sample);
    vi.mocked(api.saveReview).mockResolvedValue({
      ...emptyReview,
      sampled: 1,
      ok: 1,
      accuracy: 1,
    });
  });

  it("shows the prioritized sample with its phonetic context", async () => {
    renderWith(<ReviewPanel jobId="j1" />);

    expect(await screen.findByText("that")).toBeInTheDocument();
    expect(screen.getByText("wanna")).toBeInTheDocument();
    expect(screen.getByText("[ðæ]")).toBeInTheDocument();
    expect(screen.getByText(/0\/2 reviewed/)).toBeInTheDocument();
  });

  it("the keyboard records the verdict and advances", async () => {
    renderWith(<ReviewPanel jobId="j1" />);
    await screen.findByText("that");

    await userEvent.keyboard("1"); // ok for the first one
    expect(screen.getByText(/1\/2 reviewed/)).toBeInTheDocument();

    await userEvent.keyboard("2"); // wrong for the second one
    expect(screen.getByText(/2\/2 reviewed/)).toBeInTheDocument();

    const wrongButtons = screen.getAllByRole("button", { name: "wrong" });
    expect(wrongButtons[1]).toHaveAttribute("aria-pressed", "true");
  });

  it("saves only what was decided and shows the accuracy", async () => {
    renderWith(<ReviewPanel jobId="j1" />);
    await screen.findByText("that");

    await userEvent.click(screen.getAllByRole("button", { name: "ok" })[0]);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(api.saveReview).toHaveBeenCalled());
    expect(vi.mocked(api.saveReview).mock.calls[0][2]).toEqual([
      { segment: 0, word_idx: 1, verdict: "ok", note: "" },
    ]);
    expect(await screen.findByText(/accuracy 100%/)).toBeInTheDocument();
  });

  it("recovers a previous review with its seed", async () => {
    vi.mocked(api.review).mockResolvedValue({
      seed: 7,
      sampled: 1,
      ok: 1,
      wrong: 0,
      unsure: 0,
      accuracy: 1,
      items: [
        {
          segment: 0,
          word_idx: 1,
          word: "that",
          t_start: 0.4,
          t_end: 0.5,
          phenomena: ["t_deletion"],
          attracted_count: 0,
          low_confidence: false,
          verdict: "ok",
          note: "good",
        },
      ],
    });

    renderWith(<ReviewPanel jobId="j1" />);

    await waitFor(() => expect(api.reviewSample).toHaveBeenCalledWith("j1", 1, 7));
    expect(await screen.findByDisplayValue("good")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "ok" })[0]).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("re-samples with a different seed", async () => {
    renderWith(<ReviewPanel jobId="j1" />);
    await screen.findByText("that");

    const seed = screen.getByRole("spinbutton", { name: /seed/i });
    await userEvent.clear(seed);
    await userEvent.type(seed, "99");
    await userEvent.click(screen.getByRole("button", { name: "Sample" }));

    await waitFor(() => expect(api.reviewSample).toHaveBeenLastCalledWith("j1", 20, 99));
  });
});
