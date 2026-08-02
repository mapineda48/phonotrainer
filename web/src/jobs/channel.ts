/** Canal en vivo con el servidor: la lista de análisis llega por WebSocket
 *  (snapshot inicial + eventos) en vez de sondear /api/jobs cada segundo.
 *
 *  Es un store externo como `player/clock.ts`: la UI se suscribe con
 *  useSyncExternalStore y solo re-renderiza lo que cambia. Cada evento "job"
 *  lleva el estado COMPLETO y fresco del análisis (el servidor lo relee al
 *  enviar), así que aplicarlo es idempotente.
 *
 *  Una sola conexión por app, abierta al primer suscriptor; si cae, se
 *  reconecta con backoff exponencial. El socket es inyectable para los tests.
 */

import type { Job } from "../types";

export interface JobsState {
  jobs: Job[];
  /** true tras el primer snapshot: ya sabemos qué hay en el servidor. */
  loaded: boolean;
  connected: boolean;
  error: string | null;
}

const INITIAL: JobsState = { jobs: [], loaded: false, connected: false, error: null };

/** Mensajes que emite el servidor (ver `server.jobs_ws`). */
export type JobsMessage =
  | { type: "snapshot"; jobs: Job[] }
  | { type: "job"; job: Job }
  | { type: "deleted"; id: string };

/** Lo mínimo que necesita el canal de un WebSocket (inyectable en tests). */
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

  /** Aplica ya un job que acabamos de crear o tocar por REST: sin esto la
   *  vista parpadearía hasta que llegara el evento del servidor. */
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
        /* un mensaje que no es JSON del servidor no nos tumba */
      }
    };
    socket.onclose = () => {
      if (this.socket !== socket) return; // conexión ya reemplazada o cerrada a propósito
      this.patch({ connected: false, error: "Sin conexión con el servidor; reintentando…" });
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
        // Los análisis nuevos van primero (la lista viene ordenada por fecha).
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

  /** Cierra el canal sin reconectar (tests y apagado). */
  dispose(): void {
    clearTimeout(this.timer);
    const socket = this.socket;
    this.socket = null; // así su onclose no programa otra reconexión
    socket?.close();
  }
}
