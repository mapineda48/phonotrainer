package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The feedback after an answer (spec F-17 item 3, F-17-AC4, F-08-AC6; design 9.14; WP-21): the explanation well
 * (change chip and practice badge, the word's forms, the description, the counted phrase) and its actions, "Save"
 * offered and saving only when tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class AnswerFeedbackTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val form = QuestionCardSamples.form

  private class Calls {
    val taps = mutableListOf<String>()
  }

  /** The feedback in a vertical scroll, as every screen shows it. */
  private fun feedback(state: AnswerFeedbackState, calls: Calls = Calls(), againSlow: Boolean = true): @Composable () -> Unit = {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      AnswerFeedback(
        state = state,
        onLesson = { calls.taps += "lesson" },
        onLearn = { calls.taps += "learn" },
        onWatchMore = { calls.taps += "watchMore" },
        onSave = { calls.taps += "save" },
        onAgainSlow = if (againSlow) ({ calls.taps += "againSlow" }) else null,
      )
    }
  }

  private fun wrong(saved: Boolean = false) =
    AnswerFeedbackState(QuestionCardSamples.result(form, "dictionary"), saved = saved, change = QuestionCardSamples.flapping)

  @Test
  fun theExplanationShowsTheChangeItsAdviceTheFormsAndTheDescription() {
    screen.checkEach(ScreenVariant.REQUIRED, feedback(wrong())) {
      compose.onNode(hasContentDescription("Flapping", substring = true) and hasText("Flapping")).assertExists()
      compose.onNode(hasText("Safe to produce · universal"), useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(QuestionTags.FORMS).assertTextEquals("“water” /ˈwɔtɚ/ → [ˈwɔɾɚ]")
      compose.onNodeWithTag(QuestionTags.DESCRIPTION).assertTextContains("between vowels sounds like a soft r", substring = true)
      compose.onNodeWithTag(QuestionTags.PHRASE).assertDoesNotExist()
      compose.onNodeWithTag(QuestionTags.LEARN).assertTextContains("Learn about flapping")
    }
  }

  @Test
  fun saveIsOfferedAfterAWrongAnswerAndSavesOnlyWhenTapped() {
    val calls = Calls()
    screen.checkEach(ScreenVariant.REQUIRED, feedback(wrong(), calls)) {
      compose.onNodeWithTag(QuestionTags.SAVE).assertTextContains("Save").assertIsEnabled()
    }
    // F-17-AC4 and F-08-AC6: nothing is saved by showing the explanation.
    assertThat(calls.taps).isEmpty()
    compose.onNodeWithTag(QuestionTags.SAVE).performScrollTo().performClick()
    assertThat(calls.taps).containsExactly("save")
  }

  @Test
  fun aSavedWordReadsSavedAndCannotBeSavedTwice() {
    screen.checkEach(ScreenVariant.REQUIRED, feedback(wrong(saved = true))) {
      compose.onNodeWithTag(QuestionTags.SAVE).assertTextContains("Saved").assertIsNotEnabled()
    }
  }

  @Test
  fun eachActionCallsItsCallback() {
    val calls = Calls()
    screen.setContent(ScreenVariant(), feedback(wrong(), calls))
    listOf(QuestionTags.AGAIN_SLOW, QuestionTags.LESSON, QuestionTags.LEARN, QuestionTags.WATCH_MORE).forEach {
      compose.onNodeWithTag(it).performScrollTo().performClick()
    }
    compose.onNodeWithTag(UiTags.CHANGE_CHIP).performScrollTo().performClick()
    assertThat(calls.taps).containsExactly("againSlow", "lesson", "learn", "watchMore", "learn").inOrder()
    compose.onNodeWithTag(QuestionTags.AGAIN_SLOW).assertTextContains("Again at 0.5×")
    compose.onNodeWithTag(QuestionTags.LESSON).assertTextContains("Open the lesson")
    compose.onNodeWithTag(QuestionTags.WATCH_MORE).assertTextContains("Watch more like this")
  }

  @Test
  fun againAtHalfSpeedShowsOnlyWhenTheCallerPlaysIt() {
    screen.setContent(ScreenVariant(fontScale = 2f), feedback(wrong(), againSlow = false))
    compose.onNodeWithTag(QuestionTags.AGAIN_SLOW).assertDoesNotExist()
    screen.audit()
  }

  @Test
  fun aCountShowsThePhraseWithItsChangedWords() {
    val count = QuestionCardSamples.count
    val state = AnswerFeedbackState(QuestionCardSamples.result(count, "5"), change = QuestionCardSamples.flapping, phrase = count.phrase)
    screen.checkEach(ScreenVariant.REQUIRED, feedback(state)) {
      compose.onNodeWithTag(QuestionTags.PHRASE).assertExists()
      compose.onNode(hasText("The phrase has 5 words. Underlined words changed in fast speech", substring = true), useUnmergedTree = true)
        .assertExists()
      compose.onNode(hasContentDescription("2. you (changed)"), useUnmergedTree = true).assertExists()
      compose.onNode(hasContentDescription("3. get"), useUnmergedTree = true).assertExists()
    }
  }

  @Test
  fun withoutAReferenceTheChangeKeyIsReadAsWords() {
    val state = AnswerFeedbackState(QuestionCardSamples.result(form, "said").copy(change = ChangeLabel("t_deletion")))
    screen.setContent(ScreenVariant(), feedback(state))
    compose.onNodeWithTag(QuestionTags.LEARN).assertTextContains("Learn about t deletion")
    compose.onNodeWithTag(QuestionTags.DESCRIPTION).assertDoesNotExist()
  }

  @Test
  fun theStateTakesTheChangesWordsFromTheReferenceAndThePhraseOfACount() {
    val reference = SampleReference.reference
    val count = QuestionCardSamples.count
    val state = answerFeedbackState(QuestionCardSamples.result(count, "4"), count, reference, saved = true)
    assertThat(state.saved).isTrue()
    assertThat(state.result.correct).isFalse()
    assertThat(state.phrase).isEqualTo(count.phrase)
    val change = checkNotNull(state.change)
    assertThat(change.name).isEqualTo("flapping")
    assertThat(change.description).isEqualTo(reference.descriptions[ChangeLabel("flapping")])
    assertThat(change.advice).isEqualTo(Advice.PRODUCE)
    assertThat(change.register).isEqualTo(Register.UNIVERSAL)
    // A question of another kind carries no phrase; no reference keeps the change's own words.
    assertThat(answerFeedbackState(QuestionCardSamples.result(form, "said"), form, reference, saved = false).phrase).isNull()
    assertThat(answerFeedbackState(QuestionCardSamples.result(form, "said"), form, null, saved = false).change).isNull()
  }
}
