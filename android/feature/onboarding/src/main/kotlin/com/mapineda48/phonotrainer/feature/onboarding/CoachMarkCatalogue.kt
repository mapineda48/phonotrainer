package com.mapineda48.phonotrainer.feature.onboarding

import androidx.annotation.StringRes
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId

/** The screen a coach mark belongs to (spec F-01 behaviour 4). */
enum class CoachScreen { WATCH, LESSON, REQUESTS, PRACTICE }

/** One coach mark: its stored id, its screen, its bold tip and its one sentence (design 9.23). */
data class CoachMarkEntry(
  val id: CoachMarkId,
  val screen: CoachScreen,
  @StringRes val tip: Int,
  @StringRes val body: Int,
)

/**
 * The coach-mark catalogue (spec F-01 behaviour 4; architecture 2.2 places it in :feature:onboarding; the type
 * `CoachMarkId` says "the catalogue is WP-13's"): every coach mark of the app, the id it is stored under in
 * `CoachMarkRepository`, the screen that shows it and its words. A feature never depends on another feature, so the
 * screens that draw coach marks keep their own copy of their ids and words; this list is the reference they follow,
 * and `CoachMarkCatalogueTest` pins the ids the shipped screens already use (Watch, WP-17). Rules for every screen:
 * one at a time, the first time the screen is shown, dismissed with "Got it", never pausing or blocking playback, never
 * on a timer; a mark whose anchor is not on screen is skipped; Settings > Help > "Restart the tour" re-arms all of
 * them (`CoachMarkRepository.resetAll`).
 */
object CoachMarkCatalogue {
  val entries: List<CoachMarkEntry> = listOf(
    CoachMarkEntry(CoachMarkId("swipe"), CoachScreen.WATCH, R.string.coach_watch_swipe_tip, R.string.coach_watch_swipe_body),
    CoachMarkEntry(CoachMarkId("tap_word"), CoachScreen.WATCH, R.string.coach_watch_tap_word_tip, R.string.coach_watch_tap_word_body),
    CoachMarkEntry(CoachMarkId("hold_slow"), CoachScreen.WATCH, R.string.coach_watch_hold_tip, R.string.coach_watch_hold_body),
    CoachMarkEntry(CoachMarkId("marks"), CoachScreen.WATCH, R.string.coach_watch_marks_tip, R.string.coach_watch_marks_body),
    CoachMarkEntry(CoachMarkId("lesson_steps"), CoachScreen.LESSON, R.string.coach_lesson_steps_tip, R.string.coach_lesson_steps_body),
    CoachMarkEntry(
      CoachMarkId("requests_waiting"),
      CoachScreen.REQUESTS,
      R.string.coach_requests_waiting_tip,
      R.string.coach_requests_waiting_body,
    ),
    CoachMarkEntry(
      CoachMarkId("practice_own_clips"),
      CoachScreen.PRACTICE,
      R.string.coach_practice_own_clips_tip,
      R.string.coach_practice_own_clips_body,
    ),
  )

  /** The marks of [screen], in the order they show (one at a time). */
  fun of(screen: CoachScreen): List<CoachMarkEntry> = entries.filter { it.screen == screen }

  /** The entry stored under [id], or null for an id this catalogue does not know. */
  fun find(id: CoachMarkId): CoachMarkEntry? = entries.firstOrNull { it.id == id }
}
