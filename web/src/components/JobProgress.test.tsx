import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { job, renderWith } from "../test/fixtures";
import type { Job } from "../types";
import { JobProgress } from "./JobProgress";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return {
    ...original,
    api: { ...original.api, getJob: vi.fn() },
  };
});

const runningJob: Job = {
  ...job,
  status: "running",
  percent: 8,
  last_message: "Descargando de YouTube… 8 %",
  progress: [
    { at: "2026-07-26T12:00:01+00:00", message: "En cola → arrancando…" },
    { at: "2026-07-26T12:00:02+00:00", message: "Consultando la URL…" },
    { at: "2026-07-26T12:00:03+00:00", message: "Descargando de YouTube… 8 %" },
  ],
};

describe("JobProgress", () => {
  it("muestra el registro con la línea más reciente primero", async () => {
    vi.mocked(api.getJob).mockResolvedValue(runningJob);
    renderWith(<JobProgress job={runningJob} onChanged={() => undefined} />);

    await screen.findByText("Registro");
    const log = document.querySelector(".log")!;
    const lines = [...log.querySelectorAll("div")].map((div) => div.textContent);

    expect(lines).toEqual([
      "Descargando de YouTube… 8 %",
      "Consultando la URL…",
      "En cola → arrancando…",
    ]);
  });
});
