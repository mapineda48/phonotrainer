/** Lista de análisis con sondeo automático mientras haya trabajo en curso. */

import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "../api";
import type { Job } from "../types";

const ACTIVE_POLL_MS = 900;
const IDLE_POLL_MS = 15000;

export const isActive = (job: Job): boolean =>
  job.status === "queued" || job.status === "running";

export function useJobs() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const latest = useRef<Job[]>([]);

  const refresh = useCallback(async () => {
    try {
      const next = await api.listJobs();
      latest.current = next;
      setJobs(next);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    const tick = async () => {
      await refresh();
      if (cancelled) return;
      const delay = latest.current.some(isActive) ? ACTIVE_POLL_MS : IDLE_POLL_MS;
      timer = window.setTimeout(tick, delay);
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [refresh]);

  return { jobs, error, loaded, refresh };
}

/** Detalle de un job (incluye el log de progreso) mientras esté activo. */
export function useJob(jobId: string | null) {
  const [job, setJob] = useState<Job | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!jobId) {
      setJob(null);
      return;
    }
    let cancelled = false;
    let timer: number | undefined;

    const tick = async () => {
      try {
        const next = await api.getJob(jobId);
        if (cancelled) return;
        setJob(next);
        setError(null);
        if (isActive(next)) timer = window.setTimeout(tick, ACTIVE_POLL_MS);
      } catch (err) {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
      }
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [jobId]);

  return { job, error };
}
