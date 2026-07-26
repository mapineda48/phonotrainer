import { renderHook, waitFor } from "@testing-library/react";
import { act } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../api";
import { job } from "../test/fixtures";
import { useJob, useJobs } from "./useJobs";

vi.mock("../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api")>();
  return { ...original, api: { ...original.api, listJobs: vi.fn(), getJob: vi.fn() } };
});

beforeEach(() => {
  vi.useRealTimers();
  vi.mocked(api.getJob).mockReset();
  vi.mocked(api.listJobs).mockReset();
});

describe("useJob", () => {
  it("sondea mientras el análisis está en curso", async () => {
    const corriendo = { ...job, status: "running" as const, percent: 30 };
    vi.mocked(api.getJob)
      .mockResolvedValueOnce(corriendo)
      .mockResolvedValueOnce({ ...corriendo, percent: 60 })
      .mockResolvedValue({ ...job, status: "done" as const });

    const { result } = renderHook(() => useJob("j1"));

    await waitFor(() => expect(result.current.job?.percent).toBe(30));
    await waitFor(() => expect(result.current.job?.status).toBe("done"), { timeout: 4000 });
    const llamadas = vi.mocked(api.getJob).mock.calls.length;

    // terminado: deja de sondear
    await new Promise((resolve) => setTimeout(resolve, 1200));
    expect(vi.mocked(api.getJob).mock.calls.length).toBe(llamadas);
  });

  it("un fallo de red no congela la vista: reintenta", async () => {
    vi.mocked(api.getJob)
      .mockRejectedValueOnce(new Error("conexión perdida"))
      .mockResolvedValue({ ...job, status: "running" as const, percent: 80 });

    const { result } = renderHook(() => useJob("j1"));

    await waitFor(() => expect(result.current.error).toBe("conexión perdida"));
    await waitFor(() => expect(result.current.job?.percent).toBe(80), { timeout: 4000 });
    expect(result.current.error).toBeNull();
  });

  it("sin id no consulta nada", async () => {
    renderHook(() => useJob(null));
    await act(async () => undefined);
    expect(api.getJob).not.toHaveBeenCalled();
  });
});

describe("useJobs", () => {
  it("carga la lista y se puede refrescar a mano", async () => {
    vi.mocked(api.listJobs).mockResolvedValue([job]);
    const { result } = renderHook(() => useJobs());

    await waitFor(() => expect(result.current.jobs).toHaveLength(1));
    expect(result.current.loaded).toBe(true);

    await act(async () => {
      await result.current.refresh();
    });
    expect(vi.mocked(api.listJobs).mock.calls.length).toBeGreaterThan(1);
  });

  it("un error de red se muestra sin romper la lista", async () => {
    vi.mocked(api.listJobs).mockRejectedValue(new Error("backend caído"));
    const { result } = renderHook(() => useJobs());

    await waitFor(() => expect(result.current.error).toBe("backend caído"));
    expect(result.current.jobs).toEqual([]);
  });
});
