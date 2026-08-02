/** Contexto del canal de jobs: en producción lo monta main.tsx; los tests
 *  inyectan uno con un socket de mentira (`test/fixtures.fakeJobsChannel`). */

import { createContext, useContext, useState, type ReactNode } from "react";

import { JobsChannel } from "./channel";

const JobsContext = createContext<JobsChannel | null>(null);

export function JobsProvider({ channel, children }: { channel?: JobsChannel; children: ReactNode }) {
  // Una sola instancia por montaje: el canal sobrevive a re-renders y StrictMode.
  const [value] = useState(() => channel ?? new JobsChannel(defaultJobsUrl()));
  return <JobsContext.Provider value={value}>{children}</JobsContext.Provider>;
}

export function useJobsChannel(): JobsChannel {
  const channel = useContext(JobsContext);
  if (!channel) throw new Error("useJobsChannel fuera de un JobsProvider");
  return channel;
}

function defaultJobsUrl(): string {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  return `${protocol}://${window.location.host}/ws/jobs`;
}
