import { screen } from "@testing-library/react";
import { act } from "react";
import { afterEach, describe, expect, it } from "vitest";

import { fakeJobsChannel, job, renderWith } from "../test/fixtures";
import type { Job } from "../types";
import { JobProgress } from "./JobProgress";

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

let canal: ReturnType<typeof fakeJobsChannel> | null = null;

afterEach(() => {
  canal?.channel.dispose();
  canal = null;
});

describe("JobProgress", () => {
  it("muestra el registro con la línea más reciente primero", async () => {
    renderWith(<JobProgress job={runningJob} />);

    await screen.findByText("Registro");
    const log = document.querySelector(".log")!;
    const lines = [...log.querySelectorAll("div")].map((div) => div.textContent);

    expect(lines).toEqual([
      "Descargando de YouTube… 8 %",
      "Consultando la URL…",
      "En cola → arrancando…",
    ]);
  });

  it("se actualiza cuando el servidor empuja por el canal, sin sondeo", async () => {
    canal = fakeJobsChannel([{ ...runningJob, percent: 8 }]);
    renderWith(<JobProgress job={{ ...runningJob, progress: [], last_message: null }} />, {
      jobsChannel: canal.channel,
    });

    // el snapshot ya trae el job completo: el registro aparece sin pedir nada
    expect((await screen.findAllByText("Descargando de YouTube… 8 %")).length).toBeGreaterThan(0);

    act(() =>
      canal!.socket.push({
        type: "job",
        job: { ...runningJob, percent: 20, last_message: "Descargando de YouTube… 20 %" },
      }),
    );
    expect(await screen.findByText("20%")).toBeInTheDocument();
  });
});
