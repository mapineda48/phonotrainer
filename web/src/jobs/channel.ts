/** Live channel to the server: the analysis list arrives over a WebSocket
 *  (initial snapshot + events) instead of polling /api/jobs every second.
 *
 *  It is an external store like `player/clock.ts`: the UI subscribes with
 *  useSyncExternalStore and only re-renders what changes. Every "job" event
 *  carries the COMPLETE, fresh state of the analysis (the server re-reads it
 *  when sending), so applying one is idempotent.
 *
 *  One connection per app, opened on the first subscriber; if it drops, it
 *  reconnects with exponential backoff. The socket is injectable for tests.
 */

import type { Job } from "../types";

export interface JobsState {
  jobs: Job[];
  /** true after the first snapshot: we now know what the server holds. */
  loaded: boolean;
  connected: boolean;
  error: string | null;
}

const INITIAL: JobsState = { jobs: [], loaded: false, connected: false, error: null };

/** Messages the server emits (see `server.jobs_ws`). */
export type JobsMessage =
  | { type: "snapshot"; jobs: Job[] }
  | { type: "job"; job: Job }
  | { type: "deleted"; id: string };

/** The minimum the channel needs from a WebSocket (injectable in tests). */
export interface JobsSocket {
  onopen: (() => void) | null;
  onmessage: ((event: { data: string }) => void) | null;
  onclose: (() => void) | null;
  close(): void;
}

const RETRY_BASE_MS = 1000;
const RETRY_MAX_MS = 10_000;

export class JobsChannel {
  private listeners = new Set<() => void>();
  private state: JobsState = INITIAL;
  private started = false;
  private attempts = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private socket: JobsSocket | null = null;

  constructor(
    private readonly url: string,
    private readonly openSocket: (url: string) => JobsSocket = (u) =>
      new WebSocket(u) as unknown as JobsSocket,
  ) {}

  getSnapshot = (): JobsState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    if (!this.started) {
      this.started = true;
      this.connect();
    }
    return () => {
      this.listeners.delete(listener);
    };
  };

  /** Immediately apply a job we just created or touched over REST: without
   *  this the view would flicker until the server event arrived. */
  upsert(job: Job): void {
    this.apply({ type: "job", job });
  }

  private connect(): void {
    const socket = this.openSocket(this.url);
    this.socket = socket;
    socket.onopen = () => {
      this.attempts = 0;
      this.patch({ connected: true, error: null });
    };
    socket.onmessage = (event) => {
      try {
        this.apply(JSON.parse(event.data) as JobsMessage);
      } catch {
        /* a message that is not JSON from the server does not take us down */
      }
    };
    socket.onclose = () => {
      if (this.socket !== socket) return; // connection already replaced, or closed on purpose
      this.patch({ connected: false, error: "No connection to the server; retrying…" });
      const delay = Math.min(RETRY_BASE_MS * 2 ** this.attempts, RETRY_MAX_MS);
      this.attempts += 1;
      this.timer = setTimeout(() => this.connect(), delay);
    };
  }

  private apply(message: JobsMessage): void {
    switch (message.type) {
      case "snapshot":
        this.patch({ jobs: message.jobs, loaded: true });
        break;
      case "job": {
        const exists = this.state.jobs.some((job) => job.id === message.job.id);
        // New analyses go first (the list arrives sorted by date).
        const jobs = exists
          ? this.state.jobs.map((job) => (job.id === message.job.id ? message.job : job))
          : [message.job, ...this.state.jobs];
        this.patch({ jobs });
        break;
      }
      case "deleted":
        this.patch({ jobs: this.state.jobs.filter((job) => job.id !== message.id) });
        break;
    }
  }

  private patch(partial: Partial<JobsState>): void {
    this.state = { ...this.state, ...partial };
    for (const listener of this.listeners) listener();
  }

  /** Close the channel without reconnecting (tests and shutdown). */
  dispose(): void {
    clearTimeout(this.timer);
    const socket = this.socket;
    this.socket = null; // so its onclose does not schedule another reconnect
    socket?.close();
  }
}
