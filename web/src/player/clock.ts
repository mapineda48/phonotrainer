/** Playback clock: a high-frequency channel kept out of React state.
 *
 *  The audio time changes 60 times per second; putting it in a useState would
 *  re-render the entire transcript on every frame. Here we publish it as an
 *  external store and each component subscribes to just what it needs (the
 *  index of the active word, the playhead position…), so it only re-renders
 *  when *its* value changes.
 */

import { useSyncExternalStore } from "react";

export class Clock {
  private listeners = new Set<() => void>();
  private value = 0;

  getSnapshot = (): number => this.value;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  set(time: number): void {
    if (time === this.value) return;
    this.value = time;
    for (const listener of this.listeners) listener();
  }
}

/** Current time in seconds (re-renders on every frame: use sparingly). */
export function useTime(clock: Clock): number {
  return useSyncExternalStore(clock.subscribe, clock.getSnapshot, clock.getSnapshot);
}

/**
 * Derive a value from the time and re-render only when that value changes.
 * `select` must be pure and return primitives (Object.is decides the re-render).
 */
export function useTimeSelector<T>(clock: Clock, select: (time: number) => T): T {
  const snapshot = () => select(clock.getSnapshot());
  return useSyncExternalStore(clock.subscribe, snapshot, snapshot);
}
