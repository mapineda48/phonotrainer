import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { FakeJobsSocket, job } from "../test/fixtures";
import { JobsChannel, type JobsState } from "./channel";

/** Channel with manual sockets: the test decides when to open and when to drop. */
function manualChannel(snapshot = [job]) {
  const sockets: FakeJobsSocket[] = [];
  const channel = new JobsChannel("ws://test", () => {
    const socket = new FakeJobsSocket(snapshot, { autoOpen: false });
    sockets.push(socket);
    return socket;
  });
  channel.subscribe(() => undefined); // starts the connection
  return { channel, sockets };
}

let live: JobsChannel | null = null;

beforeEach(() => vi.useRealTimers());
afterEach(() => {
  live?.dispose();
  live = null;
});

describe("JobsChannel", () => {
  it("starts on the first subscriber and the snapshot fills the list", () => {
    const { channel, sockets } = manualChannel();
    live = channel;

    expect(channel.getSnapshot().loaded).toBe(false);
    sockets[0].open();

    const state = channel.getSnapshot();
    expect(state.jobs).toEqual([job]);
    expect(state.loaded).toBe(true);
    expect(state.connected).toBe(true);
    expect(state.error).toBeNull();
  });

  it("a “job” event replaces only that analysis, and a new one goes in front", () => {
    const { channel, sockets } = manualChannel();
    live = channel;
    sockets[0].open();

    const updated = { ...job, status: "running" as const, percent: 40 };
    sockets[0].push({ type: "job", job: updated });
    expect(channel.getSnapshot().jobs).toEqual([updated]);

    const created = { ...job, id: "other", source: "new.wav" };
    sockets[0].push({ type: "job", job: created });
    expect(channel.getSnapshot().jobs.map((j) => j.id)).toEqual(["other", job.id]);
  });

  it("“deleted” removes the analysis from the list", () => {
    const { channel, sockets } = manualChannel();
    live = channel;
    sockets[0].open();

    sockets[0].push({ type: "deleted", id: job.id });
    expect(channel.getSnapshot().jobs).toEqual([]);
  });

  it("upsert immediately applies what we just created over REST", () => {
    live = new JobsChannel("ws://test", () => new FakeJobsSocket());
    expect(live.getSnapshot().jobs).toEqual([]);

    live.upsert(job);
    expect(live.getSnapshot().jobs).toEqual([job]);
  });

  it("a message that is not JSON from the server does not take the channel down", () => {
    const { channel, sockets } = manualChannel();
    live = channel;
    sockets[0].open();

    sockets[0].onmessage?.({ data: "this is not json" });
    expect(channel.getSnapshot().jobs).toEqual([job]);
  });

  it("notifies subscribers only when the state changes", () => {
    const { channel, sockets } = manualChannel();
    live = channel;
    const states: JobsState[] = [];
    channel.subscribe(() => states.push(channel.getSnapshot()));
    sockets[0].open();

    expect(states.length).toBeGreaterThan(0);
    const afterSnapshot = states.length;
    sockets[0].push({ type: "job", job });
    expect(states.length).toBe(afterSnapshot + 1);
  });

  it("reports a dropped connection and reconnects with backoff", async () => {
    vi.useFakeTimers();
    const { channel, sockets } = manualChannel();
    live = channel;
    sockets[0].open();
    expect(channel.getSnapshot().connected).toBe(true);

    sockets[0].onclose?.();
    expect(channel.getSnapshot().connected).toBe(false);
    expect(channel.getSnapshot().error).toMatch(/retrying/);

    await vi.advanceTimersByTimeAsync(1000); // first retry: 1 s
    expect(sockets).toHaveLength(2);

    sockets[1].onclose?.();                  // drops again: backoff ×2
    await vi.advanceTimersByTimeAsync(1999);
    expect(sockets).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(sockets).toHaveLength(3);

    sockets[2].open();                       // on recovery, the error clears
    expect(channel.getSnapshot().connected).toBe(true);
    expect(channel.getSnapshot().error).toBeNull();
  });
});
