package com.mapineda48.phonotrainer.feature.practice

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel

/** Test tags of :feature:practice ("practice.<screen>.<element>", work-plan 2.6); part of the test contract. */
object PracticeTags {
  // Practice: the tab root.
  const val SCREEN = "practice.practice.screen"
  const val LOADING = "practice.practice.loading"
  const val EMPTY = "practice.practice.empty"

  // The start card.
  const val START_CARD = "practice.practice.startCard"
  const val KINDS = "practice.practice.kinds"
  const val FOCUS_CHIP = "practice.practice.focusChip"
  const val FOCUS_EVERYTHING = "practice.practice.focusEverything"
  const val FOCUS_UNKNOWN = "practice.practice.focusUnknown"
  const val FOCUS_NONE = "practice.practice.focusNone"
  const val WEAK_SPOTS = "practice.practice.weakSpots"
  const val START = "practice.practice.start"
  const val RESUME = "practice.practice.resume"

  // A session.
  const val SESSION = "practice.session.screen"
  const val POSITION = "practice.session.position"
  const val BEADS = "practice.session.beads"
  const val TALLY = "practice.session.tally"
  const val LEAVE = "practice.session.leave"
  const val NEXT = "practice.session.next"

  // The results.
  const val RESULTS = "practice.results.screen"
  const val SCORE = "practice.results.score"
  const val PARTIAL = "practice.results.partial"
  const val REVIEW = "practice.results.review"
  const val AGAIN = "practice.results.again"
  const val AGAIN_WEAK_SPOTS = "practice.results.weakSpots"
  const val BACK_TO_START = "practice.results.backToStart"

  // Progress.
  const val PROGRESS = "practice.progress.block"
  const val PROGRESS_KEPT = "practice.progress.kept"
  const val PROGRESS_NONE = "practice.progress.none"
  const val RESET = "practice.progress.reset"
  const val RESET_DIALOG = "practice.progress.resetDialog"
  const val LINK_SAVED = "practice.practice.saved"
  const val MET = "practice.progress.met"
  const val NOT_MET = "practice.progress.notMet"

  // Saved.
  const val SAVED = "practice.saved.screen"
  const val SAVED_LOADING = "practice.saved.loading"
  const val SAVED_EMPTY = "practice.saved.empty"
  const val SAVED_LIST = "practice.saved.list"
  const val SAVED_WATCH = "practice.saved.watch"

  /** The accuracy row of [change]. */
  fun accuracyRow(change: ChangeLabel): String = "practice.progress.row.${change.value}"

  /** The "Worth reviewing" chip of [change]. */
  fun review(change: ChangeLabel): String = "practice.results.review.${change.value}"

  /** The "Not met yet" chip of [change]. */
  fun notMet(change: ChangeLabel): String = "practice.progress.notMet.${change.value}"

  /** Bead [index] of the session's row. */
  fun bead(index: Int): String = "practice.session.bead.$index"

  /** The Saved row of item [id] and its parts. */
  fun savedRow(id: Long): String = "practice.saved.row.$id"

  fun savedUnsave(id: Long): String = "practice.saved.row.$id.unsave"

  fun savedWatchShort(id: Long): String = "practice.saved.row.$id.watch"

  fun savedLast(id: Long): String = "practice.saved.row.$id.last"

  fun savedNotOnPhone(id: Long): String = "practice.saved.row.$id.notOnPhone"

  fun savedOpenClip(id: Long): String = "practice.saved.row.$id.openClip"
}
