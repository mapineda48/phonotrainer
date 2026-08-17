/** A boolean persisted in localStorage: interface preferences that must
 *  survive switching analyses and reloading (e.g. showing the video). */

import { useCallback, useState } from "react";

export function usePersistentFlag(key: string, initial = false): [boolean, () => void] {
  const [value, setValue] = useState(() => {
    try {
      const saved = window.localStorage.getItem(key);
      return saved === null ? initial : saved === "1";
    } catch {
      return initial; // storage blocked (strict private browsing…)
    }
  });

  const toggle = useCallback(() => {
    setValue((current) => {
      const next = !current;
      try {
        window.localStorage.setItem(key, next ? "1" : "0");
      } catch {
        /* with no storage the preference lasts only as long as the tab */
      }
      return next;
    });
  }, [key]);

  return [value, toggle];
}
