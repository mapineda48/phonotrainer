package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeSpanPlayer
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSavedRepository
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.QuestionTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Practice tab's screen and ViewModel together, driven by taps only (WP-14's contract through
 * [ScriptedPracticeRepository]): a whole session from "Start practicing" to the results, then "Back to the start"
 * with the accuracy that session gave, and "Leave the session" with "Continue".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class PracticeFlowTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val practice = ScriptedPracticeRepository()
  private val occurrences = FakeOccurrenceRepository()

  private val ten = List(PracticeLogic.SESSION_LENGTH) { i ->
    val base = listOf(PracticePreviewData.formQuestion, PracticePreviewData.changeQuestion, PracticePreviewData.countQuestion)[i % 3]
    base.copy(key = "${base.key}:$i")
  }

  private fun show(): PracticeViewModel {
    occurrences.counts.value = mapOf(ChangeLabel("flapping") to ChangeCount(3, 12))
    practice.questions = { _, _ -> ten }
    val vm = PracticeViewModel(
      PracticeKey(),
      SavedStateHandle(),
      practice,
      FakeSavedRepository(),
      occurrences,
      FakeReferenceRepository(),
      FakeSpanPlayer(),
      FakeSettingsRepository(),
    )
    screen.setContent(ScreenVariant()) {
      val state by vm.state.collectAsStateWithLifecycle()
      PracticeScreen(
        state,
        PracticeActions(
          onStart = vm::start,
          onResume = vm::resume,
          onAnswer = vm::answer,
          onNext = vm::next,
          onLeave = vm::backToStart,
          onAgain = vm::start,
          onAgainWeakSpots = vm::startWeakSpots,
          onBackToStart = vm::backToStart,
        ),
      )
    }
    return vm
  }

  private fun tap(tag: String) {
    compose.onNodeWithTag(tag).performScrollTo().performClick()
    compose.waitForIdle()
  }

  @Test
  fun aWholeSessionByTapsEndsWithTheResultsAndBackToTheStartShowsTheAccuracy() {
    show()
    tap(PracticeTags.START)
    repeat(PracticeLogic.SESSION_LENGTH) { i ->
      compose.onNodeWithTag(PracticeTags.POSITION).assertExists()
      val question = ten[i]
      tap(QuestionTags.option(question.options.first().id))
      tap(PracticeTags.NEXT)
    }
    compose.onNodeWithTag(PracticeTags.SCORE).assertExists()
    tap(PracticeTags.BACK_TO_START)
    compose.onNodeWithTag(PracticeTags.PROGRESS).assertExists()
    compose.onNodeWithTag(PracticeTags.RESET).assertExists()
    assertThat(practice.accuracyState.value.sumOf { it.seen }).isEqualTo(10)
  }

  @Test
  fun leavingASessionOffersToContinueItWhereItStopped() {
    show()
    tap(PracticeTags.START)
    tap(QuestionTags.option("said"))
    tap(PracticeTags.NEXT)
    tap(PracticeTags.LEAVE)
    compose.onNodeWithTag(PracticeTags.RESUME).assertExists()
    tap(PracticeTags.RESUME)
    compose.onNodeWithTag(PracticeTags.POSITION).assertExists()
    assertThat(practice.answered).hasSize(1)
  }
}
