/** The guided tour's anchors. Each page puts the matching data-tour attribute on its
 *  element — `{...tourAttr(TOUR.transcript)}` or a `tourId` prop on a shared component.
 *  The tour's steps (features/tour/steps.ts) point at these ids and skip any that are
 *  absent on the current screen; App.test checks every one is found on the real pages. */

export const TOUR = {
  /** NavRail. */
  nav: "nav",
  /** "New analysis" button on the Library. */
  libraryNew: "library-new",
  /** The transcript panel of the analysis workspace. */
  transcript: "transcript",
  /** The word lesson pane. */
  wordLesson: "word-lesson",
  /** The playback-speed control. */
  playerSpeed: "player-speed",
  /** The "Learn" item of the NavRail. */
  learnNav: "learn-nav",
  /** The Colors choice on the Settings page. */
  settingsColors: "settings-colors",
} as const;

export type TourId = (typeof TOUR)[keyof typeof TOUR];

export const tourAttr = (id: TourId) => ({ "data-tour": id });
