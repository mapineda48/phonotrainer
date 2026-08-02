/** Lista de análisis y detalle de uno, en vivo por el canal WebSocket.
 *  El servidor empuja los cambios; aquí no se sondea nada. */

import { useCallback, useSyncExternalStore } from "react";

import { useJobsChannel } from "../jobs/JobsProvider";
import type { JobsState } from "../jobs/channel";
import type { Job } from "../types";

export const isActive = (job: Job): boolean =>
  job.status === "queued" || job.status === "running";

export function useJobs(): JobsState {
  const channel = useJobsChannel();
  return useSyncExternalStore(channel.subscribe, channel.getSnapshot, channel.getSnapshot);
}

/** El job con ese id, o null si no (todavía) está en la lista. Como cada
 *  evento reemplaza solo SU objeto, esto re-renderiza únicamente cuando
 *  cambia ese análisis concreto. */
export function useJob(jobId: string | null): Job | null {
  const channel = useJobsChannel();
  const select = useCallback(
    () => (jobId ? (channel.getSnapshot().jobs.find((job) => job.id === jobId) ?? null) : null),
    [channel, jobId],
  );
  return useSyncExternalStore(channel.subscribe, select, select);
}
