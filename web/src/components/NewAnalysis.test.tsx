import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { job, renderWith } from "../test/fixtures";
import { NewAnalysis } from "./NewAnalysis";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return { ...original, api: { ...original.api, createJob: vi.fn() } };
});

const analyze = async () => {
  await userEvent.type(screen.getByPlaceholderText(/local path/), "/home/me/ep1.webm");
  await userEvent.click(screen.getByRole("button", { name: "Analyze" }));
  await waitFor(() => expect(api.createJob).toHaveBeenCalled());
  return vi.mocked(api.createJob).mock.calls[0][1];
};

describe("NewAnalysis", () => {
  beforeEach(() => {
    vi.mocked(api.createJob).mockReset().mockResolvedValue(job);
  });

  it("explains the chosen engine, license included", async () => {
    renderWith(<NewAnalysis onCreated={vi.fn()} />);

    expect(screen.getByTestId("engine-note")).toHaveTextContent(/non-commercial research/);
    await userEvent.selectOptions(screen.getByLabelText(/Phone engine/), "espeak");
    expect(screen.getByTestId("engine-note")).toHaveTextContent(/dictionary form/);
  });

  it("sends the backend's defaults: engine default attraction, dialogue separated", async () => {
    renderWith(<NewAnalysis onCreated={vi.fn()} />);

    expect(await analyze()).toMatchObject({
      phone_engine: "timit61",
      attraction: null,
      separate_dialogue: true,
    });
  });

  it("attraction is three-way and separation can be turned off", async () => {
    renderWith(<NewAnalysis onCreated={vi.fn()} />);

    await userEvent.selectOptions(screen.getByLabelText("Phonetic attraction"), "off");
    await userEvent.click(screen.getByLabelText(/Separate the dialogue/));

    expect(await analyze()).toMatchObject({ attraction: false, separate_dialogue: false });
  });
});
