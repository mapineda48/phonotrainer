import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { FakeJobsSocket, job } from "../test/fixtures";
import { JobsChannel, type JobsState } from "./channel";

/** Canal con sockets manuales: el test decide cuándo abrir y cuándo caer. */
function manualChannel(snapshot = [job]) {
  const sockets: FakeJobsSocket[] = [];
  const channel = new JobsChannel("ws://test", () => {
    const socket = new FakeJobsSocket(snapshot, { autoOpen: false });
    sockets.push(socket);
    return socket;
  });
  channel.subscribe(() => undefined); // arranca la conexión
  return { channel, sockets };
}

let canal: JobsChannel | null = null;

beforeEach(() => vi.useRealTimers());
afterEach(() => {
  canal?.dispose();
  canal = null;
});

describe("JobsChannel", () => {
  it("arranca al primer suscriptor y el snapshot llena la lista", () => {
    const { channel, sockets } = manualChannel();
    canal = channel;

    expect(channel.getSnapshot().loaded).toBe(false);
    sockets[0].open();

    const state = channel.getSnapshot();
    expect(state.jobs).toEqual([job]);
    expect(state.loaded).toBe(true);
    expect(state.connected).toBe(true);
    expect(state.error).toBeNull();
  });

  it("un evento «job» reemplaza solo ese análisis y uno nuevo va delante", () => {
    const { channel, sockets } = manualChannel();
    canal = channel;
    sockets[0].open();

    const actualizado = { ...job, status: "running" as const, percent: 40 };
    sockets[0].push({ type: "job", job: actualizado });
    expect(channel.getSnapshot().jobs).toEqual([actualizado]);

    const nuevo = { ...job, id: "otro", source: "nuevo.wav" };
    sockets[0].push({ type: "job", job: nuevo });
    expect(channel.getSnapshot().jobs.map((j) => j.id)).toEqual(["otro", job.id]);
  });

  it("«deleted» quita el análisis de la lista", () => {
    const { channel, sockets } = manualChannel();
    canal = channel;
    sockets[0].open();

    sockets[0].push({ type: "deleted", id: job.id });
    expect(channel.getSnapshot().jobs).toEqual([]);
  });

  it("upsert aplica al momento lo que acabamos de crear por REST", () => {
    canal = new JobsChannel("ws://test", () => new FakeJobsSocket());
    expect(canal.getSnapshot().jobs).toEqual([]);

    canal.upsert(job);
    expect(canal.getSnapshot().jobs).toEqual([job]);
  });

  it("un mensaje que no es JSON del servidor no tumba el canal", () => {
    const { channel, sockets } = manualChannel();
    canal = channel;
    sockets[0].open();

    sockets[0].onmessage?.({ data: "esto no es json" });
    expect(channel.getSnapshot().jobs).toEqual([job]);
  });

  it("avisos a los suscriptores solo cuando cambia el estado", () => {
    const { channel, sockets } = manualChannel();
    canal = channel;
    const estados: JobsState[] = [];
    channel.subscribe(() => estados.push(channel.getSnapshot()));
    sockets[0].open();

    expect(estados.length).toBeGreaterThan(0);
    const trasSnapshot = estados.length;
    sockets[0].push({ type: "job", job });
    expect(estados.length).toBe(trasSnapshot + 1);
  });

  it("si cae la conexión lo dice y reconecta con backoff", async () => {
    vi.useFakeTimers();
    const { channel, sockets } = manualChannel();
    canal = channel;
    sockets[0].open();
    expect(channel.getSnapshot().connected).toBe(true);

    sockets[0].onclose?.();
    expect(channel.getSnapshot().connected).toBe(false);
    expect(channel.getSnapshot().error).toMatch(/reintentando/);

    await vi.advanceTimersByTimeAsync(1000); // primer reintento: 1 s
    expect(sockets).toHaveLength(2);

    sockets[1].onclose?.();                  // cae otra vez: backoff ×2
    await vi.advanceTimersByTimeAsync(1999);
    expect(sockets).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(sockets).toHaveLength(3);

    sockets[2].open();                       // al recuperar, error fuera
    expect(channel.getSnapshot().connected).toBe(true);
    expect(channel.getSnapshot().error).toBeNull();
  });
});
