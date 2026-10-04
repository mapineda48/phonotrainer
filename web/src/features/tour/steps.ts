/** The guided tour's script. Each step points at a data-tour anchor
 *  (didactic/tour-ids.ts). On any screen, steps whose anchor is present highlight it; an
 *  absent anchor either skips the step or — for the steps the learner must not miss —
 *  shows it centered, so the tour always covers the whole app. */

import { TOUR, type TourId } from "../../didactic/tour-ids";

export interface TourStep {
  /** Where the step points; null = a centered card. */
  anchor: TourId | null;
  /** Show centered when the anchor is not on this screen (instead of skipping). */
  keepWhenAbsent?: boolean;
  /** Where the card opens next to the anchor (default: below). */
  side?: "top" | "right" | "bottom" | "left";
  title: string;
  body: string;
}

export const TOUR_STEPS: readonly TourStep[] = [
  {
    anchor: null,
    title: "Welcome to PhonoTrainer",
    body:
      "It shows how native speakers really pronounce English, and which of those changes you can copy. " +
      "This tour takes about a minute. Press Esc to leave it at any time.",
  },
  {
    anchor: TOUR.nav,
    side: "right",
    title: "Find your way",
    body:
      "Library holds your clips. Learn explains every change. Practice trains your ear. " +
      "Insights measures everything you have analyzed. Settings changes colors and text size.",
  },
  {
    anchor: TOUR.libraryNew,
    title: "Analyze a clip",
    body:
      "Start here: add a video or audio file, or paste a YouTube link. " +
      "The analysis runs on your computer and takes roughly as long as the clip.",
  },
  {
    anchor: TOUR.transcript,
    title: "The transcript",
    body:
      "Every word that changed is underlined. The line style and a small icon tell you the kind of change, " +
      "so you never need to tell colors apart. Select a word to open its lesson.",
  },
  {
    anchor: TOUR.wordLesson,
    keepWhenAbsent: true,
    title: "A lesson for every word",
    body:
      "Five steps: 1. Listen — at full or half speed. 2. Compare the dictionary form with what was said. " +
      "3. Explain the change, and whether you can copy it. 4. See the mouth. 5. Practice by repeating it.",
  },
  {
    anchor: TOUR.playerSpeed,
    title: "Slow it down",
    body: "Fast reductions are easier to catch at 0.5× or 0.75×. The mouth follows the audio at any speed.",
  },
  {
    anchor: TOUR.learnNav,
    side: "right",
    keepWhenAbsent: true,
    title: "Learn",
    body:
      "One page per change, with examples from your own clips, and an IPA chart where every symbol shows " +
      "the mouth making it.",
  },
  {
    anchor: null,
    title: "Practice",
    body:
      "Short ear-training sessions built from your clips: which pronunciation did you hear, which change " +
      "was it, and how many words were in the phrase. Use the keys 1–4 to answer.",
  },
  {
    anchor: TOUR.settingsColors,
    keepWhenAbsent: true,
    title: "Colors that suit your eyes",
    body:
      "Settings → Colors has a palette designed for color-vision differences. Changes are also marked with " +
      "icons and line styles, so meaning never depends on color alone.",
  },
  {
    anchor: null,
    title: "You're ready",
    body: "Open a clip and select any word. You can restart this tour from Help or Settings.",
  },
];

export const anchorSelector = (id: TourId): string => `[data-tour="${id}"]`;

/** Is the anchor on the page and visible? */
export function anchorPresent(id: TourId, root: ParentNode = document): Element | null {
  const element = root.querySelector(anchorSelector(id));
  if (!element) return null;
  if (element instanceof HTMLElement && element.closest("[hidden], [aria-hidden='true']")) return null;
  return element;
}

export interface ResolvedStep {
  step: TourStep;
  /** The element to highlight, or null for a centered card. */
  element: Element | null;
}

/** The steps for the current screen. */
export function resolveSteps(root: ParentNode = document, steps: readonly TourStep[] = TOUR_STEPS): ResolvedStep[] {
  const out: ResolvedStep[] = [];
  for (const step of steps) {
    if (!step.anchor) {
      out.push({ step, element: null });
      continue;
    }
    const element = anchorPresent(step.anchor, root);
    if (element) out.push({ step, element });
    else if (step.keepWhenAbsent) out.push({ step, element: null });
  }
  return out;
}
