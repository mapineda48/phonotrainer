package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quiz card (spec F-17 items 2 and 3, F-08 item 5; design 9.14; WP-21): what a question shows, the three play
 * buttons, options of at least 56 dp in a real radio group, and the answered marks with icon + words + outline and
 * the live line. Every state passes the accessibility audit at font scale 2.0 in light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class QuestionCardTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val form = QuestionCardSamples.form

  private class Calls {
    val answers = mutableListOf<String>()
    var plays = 0
    var slows = 0
    var contexts = 0
  }

  /** The card in a vertical scroll, as every screen shows it (font scale 2.0 makes it taller than the window). */
  private fun card(state: QuestionCardState, calls: Calls = Calls()): @Composable () -> Unit = {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      QuestionCard(
        state = state,
        onAnswer = { calls.answers += it },
        onPlay = { calls.plays++ },
        onSlow = { calls.slows++ },
        onContext = { calls.contexts++ },
      )
    }
  }

  private fun option(id: String) = compose.onNodeWithTag(QuestionTags.option(id), useUnmergedTree = true)

  @Test
  fun aQuestionShowsItsKindItsQuestionItsLedeAndThreeWaysToListen() {
    val calls = Calls()
    screen.checkEach(ScreenVariant.REQUIRED, card(QuestionCardState(form, number = 3, of = 10), calls)) {
      compose.onNodeWithTag(QuestionTags.KIND).assertTextContains("WHICH PRONUNCIATION?")
        .assert(hasContentDescription("Question 3 of 10: Which pronunciation?"))
      compose.onNodeWithTag(QuestionTags.PROMPT).assertTextEquals("Listen to “water”. Which pronunciation did you hear?")
      compose.onNodeWithTag(QuestionTags.LEDE).assertTextEquals("From “A walk in the park”.")
      compose.onNodeWithTag(QuestionTags.PLAY).assertTextContains("Play")
      compose.onNodeWithTag(QuestionTags.SLOW).assertTextContains("Slowly")
      compose.onNodeWithTag(QuestionTags.CONTEXT).assertTextContains("With context")
      compose.onNodeWithTag(QuestionTags.LIVE).assertDoesNotExist()
    }
    compose.onNodeWithTag(QuestionTags.PLAY).performScrollTo().performClick()
    compose.onNodeWithTag(QuestionTags.SLOW).performScrollTo().performClick()
    compose.onNodeWithTag(QuestionTags.CONTEXT).performScrollTo().performClick()
    assertThat(listOf(calls.plays, calls.slows, calls.contexts)).containsExactly(1, 1, 1).inOrder()
    assertThat(calls.answers).isEmpty()
  }

  @Test
  fun aQuestionWithoutContextOffersNoWithContextAndALedeCanBeGiven() {
    screen.setContent(ScreenVariant(fontScale = 2f), card(QuestionCardState(QuestionCardSamples.change, lede = "From a short you met a minute ago.")))
    compose.onNodeWithTag(QuestionTags.CONTEXT).assertDoesNotExist()
    compose.onNodeWithTag(QuestionTags.LEDE).assertTextEquals("From a short you met a minute ago.")
    compose.onNodeWithTag(QuestionTags.KIND).assertTextContains("WHICH CHANGE?")
    screen.audit()
  }

  @Test
  fun theOptionsAreARadioGroupAndEachIsTallEnough() {
    // Spec F-17 item 2: options ≥ 56 dp tall, at every font scale of design 11.
    val variants = ScreenVariant.FONT_SCALES.map { ScreenVariant(fontScale = it) }
    screen.checkEach(variants, card(QuestionCardState(form))) {
      compose.onNodeWithTag(QuestionTags.OPTIONS)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
      form.options.forEach { option ->
        val node = option(option.id).fetchSemanticsNode()
        val heightDp = node.boundsInRoot.height / compose.density.density
        assertThat(heightDp).isAtLeast(MIN_OPTION_DP)
        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.RadioButton)
        option(option.id).assertIsEnabled().assertIsNotSelected()
      }
    }
  }

  @Test
  fun tappingAnOptionAnswersWithItsId() {
    val calls = Calls()
    screen.setContent(ScreenVariant(), card(QuestionCardState(form), calls))
    option("variant:2").performScrollTo().performClick()
    assertThat(calls.answers).containsExactly("variant:2")
  }

  @Test
  fun aWrongAnswerMarksTheAnswerAndThePickWithIconWordsAndOutline() {
    val calls = Calls()
    screen.checkEach(ScreenVariant.REQUIRED, card(QuestionCardState(form, chosen = "dictionary"), calls)) {
      // The marks are part of each option's one TalkBack node: the symbols, then "Correct answer" or "Your answer".
      compose.onNodeWithTag(QuestionTags.option("said")).assertTextContains("Correct answer")
      compose.onNodeWithTag(QuestionTags.option("dictionary")).assertTextContains("Your answer")
      compose.onNodeWithTag(QuestionTags.optionMark("said"), useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(QuestionTags.optionMark("dictionary"), useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(QuestionTags.optionMark("variant:2"), useUnmergedTree = true).assertDoesNotExist()
      compose.onNodeWithTag(QuestionTags.LIVE).assertTextContains("Not quite — the answer was [ˈwɔɾɚ].", substring = true)
      option("dictionary").assertIsSelected()
      form.options.forEach { option(it.id).assertIsNotEnabled() }
    }
    // An answered question takes no second answer.
    option("said").performScrollTo().performClick()
    assertThat(calls.answers).isEmpty()
  }

  @Test
  fun aRightAnswerSaysCorrect() {
    screen.checkEach(ScreenVariant.REQUIRED, card(QuestionCardState(form, chosen = "said"))) {
      compose.onNodeWithTag(QuestionTags.LIVE).assertTextContains("Correct!")
      compose.onNodeWithTag(QuestionTags.option("said")).assertTextContains("Correct answer")
      compose.onAllNodes(hasText("Your answer"), useUnmergedTree = true).fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }
  }

  @Test
  fun aWhichChangeOptionShowsItsNameAndAWrongOneTellsTheChange() {
    val state = QuestionCardState(QuestionCardSamples.change, chosen = "linking", families = QuestionCardSamples.families)
    screen.checkEach(ScreenVariant.REQUIRED, card(state)) {
      compose.onNode(hasText("Flapping") and hasAnyAncestor(hasTestTag(QuestionTags.option("flapping"))), useUnmergedTree = true)
        .assertExists()
      compose.onNodeWithTag(QuestionTags.LIVE).assertTextContains("Not quite — the answer was flapping.", substring = true)
    }
  }

  @Test
  fun aCountQuestionOffersTheCounts() {
    screen.checkEach(ScreenVariant.REQUIRED, card(QuestionCardState(QuestionCardSamples.count))) {
      compose.onNodeWithTag(QuestionTags.KIND).assertTextContains("HOW MANY WORDS?")
      compose.onNodeWithText("5 words", useUnmergedTree = true).assertExists()
    }
  }

  private companion object {
    const val MIN_OPTION_DP = 56f
  }
}
