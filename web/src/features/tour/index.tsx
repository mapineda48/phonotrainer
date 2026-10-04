/** The guided tour.
 *
 *  Contract with the shell: keep these exports.
 *    startTour()   called by Help → "Restart the tour" and Settings → "Restart the tour"
 *    <TourOffer/>  rendered once by AppShell above the page: a dismissible "Take the tour"
 *                  banner on first run (or nothing)
 *  Anchors: src/didactic/tour-ids.ts; the script: ./steps.ts. */

import "driver.js/dist/driver.css";
import "./tour.css";

import { driver, type DriveStep } from "driver.js";
import { Route } from "lucide-react";
import { useEffect, useState, useSyncExternalStore } from "react";

import { Button } from "../../ui";
import { resolveSteps } from "./steps";

/** "offered" (never answered) | "dismissed" | "taken". */
export const TOUR_KEY = "phonotrainer:tour";

function readTourState(): string | null {
  try {
    return window.localStorage.getItem(TOUR_KEY);
  } catch {
    return null;
  }
}

function writeTourState(value: string): void {
  try {
    window.localStorage.setItem(TOUR_KEY, value);
  } catch {
    /* storage blocked: the offer may come back next time, which is harmless */
  }
  offerStore.emit();
}

/** Lets the banner disappear the moment the tour starts from anywhere (Help, Settings). */
const offerStore = {
  listeners: new Set<() => void>(),
  version: 0,
  subscribe(listener: () => void) {
    offerStore.listeners.add(listener);
    return () => offerStore.listeners.delete(listener);
  },
  emit() {
    offerStore.version += 1;
    for (const listener of offerStore.listeners) listener();
  },
};

export function prefersReducedMotion(): boolean {
  if (typeof document === "undefined") return true;
  if (document.documentElement.getAttribute("data-motion") === "reduce") return true;
  try {
    return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
  } catch {
    return false;
  }
}

let active: ReturnType<typeof driver> | null = null;

/** driver.js marks whatever it highlights with aria-haspopup="dialog",
 *  aria-expanded="true" and aria-controls. Our anchors are landmarks and plain
 *  containers (and its own invisible stand-in for a centered card), where
 *  aria-expanded is not allowed: axe reports it as critical, and a screen reader would
 *  announce a popup that is not there. The card itself is a labelled dialog that takes
 *  the focus, so nothing is lost. driver.js puts back whatever was there (nothing) when
 *  it moves on. */
const POPUP_ARIA = ["aria-haspopup", "aria-expanded", "aria-controls"] as const;
export function stripPopupAria(root: ParentNode = document): void {
  for (const element of root.querySelectorAll(".driver-active-element")) {
    for (const name of POPUP_ARIA) element.removeAttribute(name);
  }
}

/** The driver.js steps for the current screen. */
export function buildDriveSteps(root: ParentNode = document): DriveStep[] {
  return resolveSteps(root).map(({ step, element }) => ({
    ...(element ? { element } : {}),
    popover: { title: step.title, description: step.body, side: step.side ?? "bottom", align: "start" },
  }));
}

/** Start (or restart) the tour on whatever screen is showing. Esc closes it and focus
 *  returns to where it was — or to `returnFocus`, for a caller whose control is about to
 *  disappear (a menu item: the Help menu passes its own button). */
export function startTour(options: { returnFocus?: HTMLElement | null } = {}): void {
  if (typeof document === "undefined") return;
  active?.destroy();
  const returnFocus =
    options.returnFocus ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
  const reduced = prefersReducedMotion();
  writeTourState("taken");

  const tour = driver({
    steps: buildDriveSteps(),
    animate: !reduced,
    smoothScroll: !reduced,
    allowClose: true,
    allowKeyboardControl: true,
    showProgress: true,
    progressText: "Step {{current}} of {{total}}",
    nextBtnText: "Next",
    prevBtnText: "Back",
    doneBtnText: "Done",
    popoverClass: "pt-tour",
    overlayOpacity: 0.55,
    stagePadding: 6,
    onPopoverRender: (popover) => {
      // driver.js renders plain divs: make the card a labelled dialog and put the
      // keyboard on its main button.
      const titleId = "pt-tour-title";
      const bodyId = "pt-tour-body";
      popover.title.id = titleId;
      popover.description.id = bodyId;
      popover.wrapper.setAttribute("role", "dialog");
      popover.wrapper.setAttribute("aria-labelledby", titleId);
      popover.wrapper.setAttribute("aria-describedby", bodyId);
      popover.closeButton.setAttribute("aria-label", "Close the tour");
      popover.progress.setAttribute("aria-live", "polite");
      // driver.js focuses its first button (the close ×) after this hook: go after it.
      window.setTimeout(() => popover.nextButton.focus(), 0);
    },
    onHighlighted: () => stripPopupAria(),
    // Every way out (Esc, ×, Done) passes through here; driver.js skips onDestroyed for
    // a centered step and refocuses <body>, so the focus is restored after its cleanup.
    onDestroyStarted: () => {
      tour.destroy();
      active = null;
      window.setTimeout(() => {
        const usable = returnFocus && returnFocus !== document.body && returnFocus.isConnected;
        const target = usable ? returnFocus : document.getElementById("main");
        target?.focus();
      }, 0);
    },
  });
  active = tour;
  tour.drive();
}

/** First-run banner: an invitation, never forced. */
export function TourOffer() {
  useSyncExternalStore(offerStore.subscribe, () => offerStore.version, () => 0);
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!mounted || readTourState() !== null) return null;

  return (
    <section
      aria-label="Guided tour"
      className="flex flex-wrap items-center gap-3 border-b border-line bg-surface-2 px-6 py-2.5"
    >
      <Route size={18} aria-hidden="true" className="shrink-0 text-ink" />
      <p className="min-w-0 flex-1 text-sm text-ink">
        New here? A one-minute tour shows where everything is and how to read the changes.
      </p>
      <Button size="sm" variant="primary" onPress={() => startTour()}>
        Take the tour
      </Button>
      <Button size="sm" variant="quiet" onPress={() => writeTourState("dismissed")}>
        Not now
      </Button>
    </section>
  );
}
