/** Every keyboard shortcut, for Help → Keyboard shortcuts. The keys themselves are bound
 *  where they act (the analysis workspace binds its own); this is the single list shown
 *  to the learner, so whoever adds or changes a key updates it here too. */

export interface Shortcut {
  keys: string[];
  action: string;
  /** Pressed together ("Shift + N") rather than alternatives ("1 / 2 / 3"). */
  combo?: boolean;
}

export interface ShortcutGroup {
  title: string;
  shortcuts: Shortcut[];
}

export const SHORTCUT_GROUPS: ShortcutGroup[] = [
  {
    title: "Everywhere",
    shortcuts: [
      { keys: ["Tab"], action: "move to the next control" },
      { keys: ["Esc"], action: "close a popover or dialog" },
    ],
  },
  {
    title: "Analysis",
    shortcuts: [
      { keys: ["Space"], action: "play / pause" },
      { keys: ["N"], action: "next word (or next filter or search match)" },
      { keys: ["Shift", "N"], action: "previous word", combo: true },
      { keys: ["P"], action: "replay the selected word" },
      { keys: ["Shift", "P"], action: "replay the word with the next one", combo: true },
      { keys: ["S"], action: "replay the whole phrase" },
      { keys: ["L"], action: "loop the selected span" },
      { keys: ["←", "→"], action: "back / forward 2 s (on a focused word: previous / next word)" },
      { keys: ["↑", "↓"], action: "on a focused word: previous / next phrase" },
      { keys: ["Home", "End"], action: "on a focused word: first / last word" },
      { keys: ["Enter"], action: "on a focused word: open its lesson and play it" },
      { keys: ["F"], action: "follow playback" },
      { keys: ["V"], action: "show or hide the original video" },
      { keys: ["?"], action: "show the keyboard shortcuts" },
      { keys: ["1", "2", "3"], action: "in Review: ok / wrong / unsure" },
      { keys: ["J", "K"], action: "in Review: next / previous word (also ↓ / ↑)" },
    ],
  },
  {
    title: "Practice",
    shortcuts: [
      { keys: ["1", "2", "3", "4"], action: "choose an answer" },
      { keys: ["Enter"], action: "next item" },
    ],
  },
];
