/** Global keyboard shortcuts, ignoring anything typed into text fields. */

import { useEffect, useRef } from "react";

export type HotkeyMap = Record<string, (event: KeyboardEvent) => void>;

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
}

/** Space and Enter activate the focused control: the browser wins there. */
function activatesItself(target: EventTarget | null, key: string): boolean {
  if (key !== " " && key !== "Enter") return false;
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.tagName === "BUTTON" ||
    (target.tagName === "A" && target.hasAttribute("href")) ||
    target.getAttribute("role") === "tab" ||
    target.getAttribute("role") === "slider"
  );
}

export function useHotkeys(map: HotkeyMap, enabled = true): void {
  const ref = useRef(map);
  ref.current = map;

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (isTyping(event.target) || activatesItself(event.target, event.key)) return;
      const handler = ref.current[event.key];
      if (!handler) return;
      event.preventDefault();
      handler(event);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [enabled]);
}
