/** Los hooks leen del canal WebSocket: aquí se prueba esa conexión, no el
 *  canal en sí (eso es channel.test.ts). */

import { renderHook, waitFor } from "@testing-library/react";
import { act } from "react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it } from "vitest";

import { JobsProvider } from "../jobs/JobsProvider";
import type { JobsChannel } from "../jobs/channel";
import { fakeJobsChannel, job } from "../test/fixtures";
import { useJob, useJobs } from "./useJobs";

const wrapper = (channel: JobsChannel) =>
  ({ children }: { children: ReactNode }) => (
    <JobsProvider channel={channel}>{children}</JobsProvider>
  );

let canal: ReturnType<typeof fakeJobsChannel> | null = null;

afterEach(() => {
  canal?.channel.dispose();
  canal = null;
});

describe("useJobs", () => {
  it("empieza sin cargar y el snapshot llena la lista", async () => {
    canal = fakeJobsChannel([job]);
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper(canal.channel) });

    expect(result.current.loaded).toBe(false);
    await waitFor(() => expect(result.current.loaded).toBe(true));
    expect(result.current.jobs).toEqual([job]);
    expect(result.current.error).toBeNull();
  });

  it("aplica los eventos que empuja el servidor", async () => {
    canal = fakeJobsChannel([]);
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper(canal.channel) });
    await waitFor(() => expect(result.current.loaded).toBe(true));

    act(() => canal!.socket.push({ type: "job", job }));
    expect(result.current.jobs).toEqual([job]);

    act(() => canal!.socket.push({ type: "deleted", id: job.id }));
    expect(result.current.jobs).toEqual([]);
  });

  it("si la conexión cae, lo cuenta", async () => {
    canal = fakeJobsChannel([job]);
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper(canal.channel) });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => canal!.socket.onclose?.());
    expect(result.current.error).toMatch(/reintentando/);
    expect(result.current.jobs).toEqual([job]); // la lista no se pierde
  });
});

describe("useJob", () => {
  it("sin id no devuelve nada", () => {
    canal = fakeJobsChannel([job]);
    const { result } = renderHook(() => useJob(null), { wrapper: wrapper(canal.channel) });
    expect(result.current).toBeNull();
  });

  it("devuelve su análisis y se actualiza solo cuando cambia ESE", async () => {
    const otro = { ...job, id: "otro" };
    canal = fakeJobsChannel([job, otro]);
    const { result } = renderHook(() => useJob(job.id), { wrapper: wrapper(canal.channel) });

    await waitFor(() => expect(result.current?.id).toBe(job.id));

    act(() => canal!.socket.push({ type: "job", job: { ...otro, percent: 50 } }));
    expect(result.current).toEqual(job); // referencia intacta

    act(() => canal!.socket.push({ type: "job", job: { ...job, percent: 80 } }));
    expect(result.current?.percent).toBe(80);
  });
});
