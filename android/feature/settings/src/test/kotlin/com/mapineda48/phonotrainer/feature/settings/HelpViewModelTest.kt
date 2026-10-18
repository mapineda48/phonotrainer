package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeCoachMarkRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** "Restart the tour" (spec F-01-AC5): every coach mark is re-armed before the Welcome opens. */
class HelpViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  @Test
  fun restartTheTourReArmsEveryCoachMarkThenOpensTheWelcome() = runTest {
    val coachMarks = FakeCoachMarkRepository()
    val swipe = CoachMarkId("watch.swipe")
    coachMarks.dismiss(swipe)
    assertThat(coachMarks.shouldShow(swipe).first()).isFalse()
    var opened: Boolean? = null

    HelpViewModel(coachMarks).restartTour { opened = coachMarks.seen.value.isEmpty() }

    assertThat(coachMarks.shouldShow(swipe).first()).isTrue()
    // The Welcome opens only once the marks are re-armed.
    assertThat(opened).isTrue()
  }
}
