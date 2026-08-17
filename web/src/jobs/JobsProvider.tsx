/** Context for the jobs channel: in production main.tsx mounts it; tests
 *  inject one with a fake socket (`test/fixtures.fakeJobsChannel`). */

import { createContext, useContext, useState, type ReactNode } from "react";

import { JobsChannel } from "./channel";

const JobsContext = createContext<JobsChannel | null>(null);

export function JobsProvider({ channel, children }: { channel?: JobsChannel; children: ReactNode }) {
  // One instance per mount: the channel survives re-renders and StrictMode.
  const [value] = useState(() => channel ?? new JobsChannel(defaultJobsUrl()));
  return <JobsContext.Provider value={value}>{children}</JobsContext.Provider>;
}

export function useJobsChannel(): JobsChannel {
  const channel = useContext(JobsContext);
  if (!channel) throw new Error("useJobsChannel used outside a JobsProvider");
  return channel;
}

function defaultJobsUrl(): string {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  return `${protocol}://${window.location.host}/ws/jobs`;
}
