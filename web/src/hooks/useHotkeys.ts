/** Global keyboard shortcuts, ignoring anything typed into a text field, any key the focused
 *  control uses itself (Space on a button or switch, arrows on a radio) and any key a control
 *  has already handled (arrow keys inside a radiogroup, a slider, a menu…). */

import { useEffect, useRef } from "react";

export type HotkeyMap = Record<string, (event: KeyboardEvent) => void>;

/** Input types that take typed text (number and date/time fields too): every key is theirs.
 *  Checkboxes, radios, switches (a checkbox <input> in React Aria), buttons, ranges, files
 *  and colors are not text entry, so the shortcuts keep working on them. */
const TEXT_INPUT_TYPES = new Set([
  "text",
  "search",
  "email",
  "url",
  "tel",
  "password",
  "number",
  "date",
  "datetime-local",
  "month",
  "week",
  "time",
]);

/** A target the learner types into: text-like inputs, textarea, contenteditable, and a
 *  native <select> (it types ahead: letters pick an option). */
function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target instanceof HTMLInputElement) return TEXT_INPUT_TYPES.has(target.type);
  if (target.tagName === "TEXTAREA" || target.tagName === "SELECT") return true;
  // isContentEditable is the standard check; the attribute covers environments without it
  return target.isContentEditable || target.closest('[contenteditable]:not([contenteditable="false"])') !== null;
}

const ARROWS = new Set(["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"]);
const ACTIVATING_ROLES = new Set(["tab", "slider", "switch", "checkbox", "radio", "menuitem", "menuitemcheckbox", "menuitemradio", "option"]);

/** Keys a focused control uses itself: Space and Enter press or toggle it, a radio moves
 *  with the arrows, a range input slides with arrows, Home, End and Page keys. */
function controlOwnsKey(target: EventTarget | null, key: string): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const activates = key === " " || key === "Enter";
  if (target instanceof HTMLInputElement) {
    if (target.type === "radio") return activates || ARROWS.has(key);
    if (target.type === "range") return ARROWS.has(key) || ["Home", "End", "PageUp", "PageDown"].includes(key);
    return activates; // checkbox (and switch), button-like, file, color
  }
  if (!activates) return false;
  return (
    target.tagName === "BUTTON" ||
    (target.tagName === "A" && target.hasAttribute("href")) ||
    ACTIVATING_ROLES.has(target.getAttribute("role") ?? "")
  );
}

export function useHotkeys(map: HotkeyMap, enabled = true): void {
  const ref = useRef(map);
  ref.current = map;

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      // A focused control that used the key (React Aria calls preventDefault) owns it:
      // ← on the Speed control changes the speed, it must not also seek.
      if (event.defaultPrevented) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (isTextEntry(event.target) || controlOwnsKey(event.target, event.key)) return;
      const handler = ref.current[event.key];
      if (!handler) return;
      event.preventDefault();
      handler(event);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [enabled]);
}
