/** The analysis list and the detail of one, live over the WebSocket channel.
 *  The server pushes the changes; nothing is polled here. */

import { useCallback, useSyncExternalStore } from "react";

import { useJobsChannel } from "../jobs/JobsProvider";
import type { JobsState } from "../jobs/channel";
import type { Job } from "../types";

export function useJobs(): JobsState {
  const channel = useJobsChannel();
  return useSyncExternalStore(channel.subscribe, channel.getSnapshot, channel.getSnapshot);
}

/** The job with that id, or null if it is not (yet) in the list. Since each
 *  event replaces only ITS own object, this re-renders only when that specific
 *  analysis changes. */
export function useJob(jobId: string | null): Job | null {
  const channel = useJobsChannel();
  const select = useCallback(
    () => (jobId ? (channel.getSnapshot().jobs.find((job) => job.id === jobId) ?? null) : null),
    [channel, jobId],
  );
  return useSyncExternalStore(channel.subscribe, select, select);
}
