/** El video sobre la transcripción: preferencia persistente, por defecto
 *  apagado y solo disponible cuando el análisis tiene video. */

import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { analysis, job, renderWith } from "../test/fixtures";
import type { Job } from "../types";
import { AnalysisView } from "./AnalysisView";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return { ...original, api: { ...original.api, analysis: vi.fn() } };
});

const videoJob: Job = { ...job, is_video: true, has_media: true };
const FLAG = "phonotrainer:show-video";
const hayVideo = () => document.querySelector("video") !== null;

beforeEach(() => {
  window.localStorage.clear();
  vi.mocked(api.analysis).mockResolvedValue(analysis);
});

describe("AnalysisView — video configurable", () => {
  it("por defecto el video está oculto y el botón lo ofrece", async () => {
    renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });

    expect(hayVideo()).toBe(false);
    expect(screen.getByRole("button", { name: "Video" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("el botón lo muestra y la preferencia sobrevive al remontaje", async () => {
    const tools = renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });

    await userEvent.click(screen.getByRole("button", { name: "Video" }));
    expect(hayVideo()).toBe(true);
    expect(window.localStorage.getItem(FLAG)).toBe("1");

    // Al abrir otro análisis con video, la preferencia se recuerda.
    tools.unmount();
    renderWith(<AnalysisView job={{ ...videoJob, id: "otro" }} />);
    await screen.findByRole("button", { name: /^does/ });
    expect(hayVideo()).toBe(true);
  });

  it("la tecla V alterna igual que el botón", async () => {
    renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });

    await userEvent.keyboard("v");
    expect(hayVideo()).toBe(true);
    await userEvent.keyboard("v");
    expect(hayVideo()).toBe(false);
  });

  it("el botón ✕ del dock lo oculta y apaga la preferencia", async () => {
    window.localStorage.setItem(FLAG, "1");
    renderWith(<AnalysisView job={videoJob} />);
    await screen.findByRole("button", { name: /^does/ });
    expect(hayVideo()).toBe(true);

    await userEvent.click(screen.getByRole("button", { name: "Ocultar video" }));
    expect(hayVideo()).toBe(false);
    expect(window.localStorage.getItem(FLAG)).toBe("0");
  });

  it("sin video disponible no hay botón, aunque la preferencia esté activa", async () => {
    window.localStorage.setItem(FLAG, "1");
    renderWith(<AnalysisView job={{ ...videoJob, is_video: false }} />);
    await screen.findByRole("button", { name: /^does/ });

    expect(screen.queryByRole("button", { name: "Video" })).toBeNull();
    expect(hayVideo()).toBe(false);
  });
});
