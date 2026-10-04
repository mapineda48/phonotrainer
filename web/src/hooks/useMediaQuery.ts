/** Media queries from React: true while the query matches, re-rendering when that
 *  changes (a window resized, a tablet turned). Read synchronously on the first render, so
 *  a narrow screen never flashes the wide layout first.
 *
 *  Where layout is decided by CSS alone, use Tailwind's breakpoints. This hook is for the
 *  few places where the narrow layout is a different component tree (the navigation, the
 *  analysis workspace, the IPA chart's symbol sheet, the Insights occurrences list); its
 *  queries are written exactly like Tailwind's `max-md:`, `max-lg:` and `max-xl:`, so the
 *  CSS and the components switch at the same width. */

import { useCallback, useSyncExternalStore } from "react";

export const BREAKPOINTS = {
  /** Below Tailwind's `md` (48rem = 768 px): the navigation becomes a menu button. */
  compactNav: "(width < 48rem)",
  /** Below Tailwind's `lg` (64rem = 1024 px): the analysis workspace shows one pane at a time. */
  singlePane: "(width < 64rem)",
  /** Below Tailwind's `xl` (80rem = 1280 px): too narrow, beside the rail, for the IPA chart
   *  next to its symbol panel, or for the six columns of the Insights occurrences table. */
  belowXl: "(width < 80rem)",
} as const;

function mediaQueryList(query: string): MediaQueryList | null {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return null;
  try {
    return window.matchMedia(query);
  } catch {
    return null;
  }
}

export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = mediaQueryList(query);
      if (!list) return () => undefined;
      list.addEventListener("change", onChange);
      return () => list.removeEventListener("change", onChange);
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => mediaQueryList(query)?.matches ?? false,
    () => false,
  );
}
