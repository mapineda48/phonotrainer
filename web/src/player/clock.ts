/** Reloj de reproducción: canal de alta frecuencia separado del estado de React.
 *
 *  El tiempo del audio cambia 60 veces por segundo; meterlo en un useState
 *  volvería a renderizar toda la transcripción en cada frame. Aquí lo publicamos
 *  como store externo y cada componente se suscribe a lo que necesita (el índice
 *  de la palabra activa, la posición del playhead…), de modo que solo re-renderiza
 *  cuando *su* valor cambia.
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

/** Tiempo actual en segundos (re-renderiza en cada frame: úsalo con moderación). */
export function useTime(clock: Clock): number {
  return useSyncExternalStore(clock.subscribe, clock.getSnapshot, clock.getSnapshot);
}

/**
 * Deriva un valor del tiempo y re-renderiza solo cuando ese valor cambia.
 * `select` debe ser puro y devolver primitivos (Object.is decide el re-render).
 */
export function useTimeSelector<T>(clock: Clock, select: (time: number) => T): T {
  const snapshot = () => select(clock.getSnapshot());
  return useSyncExternalStore(clock.subscribe, snapshot, snapshot);
}
