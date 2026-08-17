/** The hooks read from the WebSocket channel: what is tested here is that
 *  wiring, not the channel itself (that is channel.test.ts). */

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

let tools: ReturnType<typeof fakeJobsChannel> | null = null;

afterEach(() => {
  tools?.channel.dispose();
  tools = null;
});

describe("useJobs", () => {
  it("starts unloaded and the snapshot fills the list", async () => {
    tools = fakeJobsChannel([job]);
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper(tools.channel) });

    expect(result.current.loaded).toBe(false);
    await waitFor(() => expect(result.current.loaded).toBe(true));
    expect(result.current.jobs).toEqual([job]);
    expect(result.current.error).toBeNull();
  });

  it("applies the events the server pushes", async () => {
    tools = fakeJobsChannel([]);
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper(tools.channel) });
    await waitFor(() => expect(result.current.loaded).toBe(true));

    act(() => tools!.socket.push({ type: "job", job }));
    expect(result.current.jobs).toEqual([job]);

    act(() => tools!.socket.push({ type: "deleted", id: job.id }));
    expect(result.current.jobs).toEqual([]);
  });

  it("reports it when the connection drops", async () => {
    tools = fakeJobsChannel([job]);
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper(tools.channel) });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => tools!.socket.onclose?.());
    expect(result.current.error).toMatch(/retrying/);
    expect(result.current.jobs).toEqual([job]); // the list is not lost
  });
});

describe("useJob", () => {
  it("returns nothing without an id", () => {
    tools = fakeJobsChannel([job]);
    const { result } = renderHook(() => useJob(null), { wrapper: wrapper(tools.channel) });
    expect(result.current).toBeNull();
  });

  it("returns its analysis and updates only when THAT one changes", async () => {
    const other = { ...job, id: "other" };
    tools = fakeJobsChannel([job, other]);
    const { result } = renderHook(() => useJob(job.id), { wrapper: wrapper(tools.channel) });

    await waitFor(() => expect(result.current?.id).toBe(job.id));

    act(() => tools!.socket.push({ type: "job", job: { ...other, percent: 50 } }));
    expect(result.current).toEqual(job); // reference untouched

    act(() => tools!.socket.push({ type: "job", job: { ...job, percent: 80 } }));
    expect(result.current?.percent).toBe(80);
  });
});
