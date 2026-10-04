/** Screen-size emulation for tests. jsdom has no layout, so the media queries the app
 *  asks through matchMedia (hooks/useMediaQuery) answer what the test says: by default
 *  nothing matches, which is the wide (desktop) layout.
 *
 *    phoneScreen()       a phone: compact navigation and one pane at a time
 *    setScreen(queries)  any other combination; returns a function that changes it later
 *
 *  The spy is restored after each test (setup.ts: vi.restoreAllMocks). */

import { act } from "@testing-library/react";
import { vi } from "vitest";

import { BREAKPOINTS } from "../hooks/useMediaQuery";

export function setScreen(matching: readonly string[]): (next: readonly string[]) => void {
  let current = new Set(matching);
  const listeners = new Map<string, Set<() => void>>();
  vi.spyOn(window, "matchMedia").mockImplementation(
    (query: string) =>
      ({
        get matches() {
          return current.has(query);
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: () => void) => {
          if (!listeners.has(query)) listeners.set(query, new Set());
          listeners.get(query)!.add(listener);
        },
        removeEventListener: (_type: string, listener: () => void) => listeners.get(query)?.delete(listener),
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  );
  return (next) => {
    current = new Set(next);
    act(() => {
      for (const set of listeners.values()) for (const listener of set) listener();
    });
  };
}

/** A phone: below every breakpoint. */
export const phoneScreen = () => setScreen(Object.values(BREAKPOINTS));
