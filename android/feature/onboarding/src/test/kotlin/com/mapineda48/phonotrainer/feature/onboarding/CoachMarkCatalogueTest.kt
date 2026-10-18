package com.mapineda48.phonotrainer.feature.onboarding

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The coach-mark catalogue (spec F-01 behaviour 4): every mark the spec names, one id each, the ids the shipped Watch
 * feed already stores (WP-17's `WatchCoachMark`, also pinned by the Settings area test), and the spec's words.
 */
@RunWith(RobolectricTestRunner::class)
class CoachMarkCatalogueTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun everyIdIsStoredOnce() {
    val ids = CoachMarkCatalogue.entries.map { it.id }
    assertThat(ids).containsNoDuplicates()
    ids.forEach { assertThat(it.value).matches("[a-z]+(_[a-z]+)*") }
  }

  @Test
  fun watchKeepsTheIdsTheFeedAlreadyStores() {
    assertThat(CoachMarkCatalogue.of(CoachScreen.WATCH).map { it.id })
      .containsExactly(CoachMarkId("swipe"), CoachMarkId("tap_word"), CoachMarkId("hold_slow"), CoachMarkId("marks"))
      .inOrder()
  }

  @Test
  fun everyScreenOfTheSpecHasItsMarks() {
    assertThat(CoachMarkCatalogue.entries.map { it.screen }.toSet()).containsExactlyElementsIn(CoachScreen.entries)
    val tips = CoachMarkCatalogue.entries.associate { it.id.value to context.getString(it.tip) }
    assertThat(tips["swipe"]).isEqualTo("Swipe up for the next short.")
    assertThat(tips["tap_word"]).isEqualTo("Tap any underlined word to see how it was really said.")
    assertThat(tips["hold_slow"]).isEqualTo("Press and hold the picture to slow down.")
    assertThat(tips["lesson_steps"]).isEqualTo("Five steps: listen, compare, why it changes, see the mouth, practice.")
    assertThat(tips["requests_waiting"]).isEqualTo("Links and files you add wait here until your computer is reachable.")
    assertThat(tips["practice_own_clips"]).isEqualTo("Questions are built from your own clips.")
    CoachMarkCatalogue.entries.forEach { assertThat(context.getString(it.body)).isNotEmpty() }
  }

  @Test
  fun anUnknownIdIsNotInTheCatalogue() {
    assertThat(CoachMarkCatalogue.find(CoachMarkId("swipe"))?.screen).isEqualTo(CoachScreen.WATCH)
    assertThat(CoachMarkCatalogue.find(CoachMarkId("nothing"))).isNull()
  }
}
