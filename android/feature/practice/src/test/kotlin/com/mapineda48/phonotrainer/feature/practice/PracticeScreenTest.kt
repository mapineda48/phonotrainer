package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.QuestionTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Practice tab, state by state (work-plan WP-21 Proof; spec F-17, design 10.1 T4 and 9.14): the start screen with
 * the focus chip, a session, the answers with icon + words + outline, the results, the progress without streaks
 * (F-17-AC5), the reset confirmation, the empty and exhausted pools. Every state is checked at font scale 2.0 in light
 * and dark, each followed by the shared accessibility audit, in a window tall enough that nothing is scrolled away
 * ([TALL_WINDOW]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class PracticeScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose

  /** What the screen asked for, in order. */
  private val calls = mutableListOf<String>()

  private val actions = PracticeActions(
    onClearFocus = { calls += "clearFocus" },
    onWeakSpots = { calls += "weakSpots:$it" },
    onStart = { calls += "start" },
    onResume = { calls += "resume" },
    onAnswer = { calls += "answer:$it" },
    onPlay = { calls += "play:$it" },
    onNext = { calls += "next" },
    onLeave = { calls += "leave" },
    onLesson = { calls += "lesson:${it.segment}/${it.word}" },
    onLearn = { calls += "learn:${it.value}" },
    onWatchMore = { calls += "watchMore:${it.value}" },
    onSave = { calls += "save" },
    onAgain = { calls += "again" },
    onAgainWeakSpots = { calls += "againWeakSpots" },
    onBackToStart = { calls += "backToStart" },
    onReset = { calls += "reset" },
    onSaved = { calls += "saved" },
    onAddClip = { calls += "addClip" },
  )

  private fun show(state: PracticeUiState, check: () -> Unit = {}) =
    screen.checkEach(ScreenVariant.REQUIRED, { PracticeScreen(state, actions) }) { check() }

  private fun tap(tag: String) {
    compose.onNodeWithTag(tag).performScrollTo().performClick()
  }

  /** Every text inside the node tagged [tag], in the unmerged tree. */
  private fun textsUnder(tag: String): List<String> =
    compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) or hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { node -> texts(node) }

  private fun texts(node: SemanticsNode): List<String> =
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
      node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

  private fun SemanticsNodeInteraction.text(): String = texts(fetchSemanticsNode()).joinToString(" ")

  @Test
  fun theStartScreenExplainsTheKindsAndStarts() {
    show(PracticePreviewData.startState) {
      compose.onNodeWithText("Practice").assertExists()
      compose.onNodeWithText("Train your ear with words and phrases from your own clips. A few minutes a day is enough.").assertExists()
      val kinds = textsUnder(PracticeTags.KINDS).joinToString(" ")
      assertThat(kinds).contains("Which pronunciation?")
      assertThat(kinds).contains("Which change?")
      assertThat(kinds).contains("How many words?")
      compose.onNodeWithTag(PracticeTags.FOCUS_CHIP).assertDoesNotExist()
      compose.onNodeWithTag(PracticeTags.WEAK_SPOTS).assertIsOff()
      compose.onNodeWithTag(PracticeTags.START).assertTextContains("Start practicing")
    }
    tap(PracticeTags.WEAK_SPOTS)
    tap(PracticeTags.START)
    assertThat(calls).containsExactly("weakSpots:true", "start").inOrder()
  }

  @Test
  fun theFocusChipSaysOnlyThatChangeAndCanBeSetAside() {
    show(PracticePreviewData.focusState) {
      assertThat(textsUnder(PracticeTags.FOCUS_CHIP)).contains("Only: flapping")
      compose.onNodeWithTag(PracticeTags.FOCUS_EVERYTHING).assertTextContains("Practice everything instead")
      compose.onNodeWithTag(PracticeTags.WEAK_SPOTS).assertIsOn()
    }
    tap(PracticeTags.FOCUS_EVERYTHING)
    compose.onNode(hasContentDescription("Practice everything instead") and hasAnyAncestor(hasTestTag(PracticeTags.FOCUS_CHIP)))
      .performScrollTo().performClick()
    assertThat(calls).containsExactly("clearFocus", "clearFocus")
  }

  @Test
  fun aFocusWithoutExamplesSaysSoAndOffersNoStart() {
    val state = PracticePreviewData.startState.copy(start = StartUi(focus = FocusUi.Known(PracticePreviewData.hDropping, playable = false)))
    show(state) {
      assertThat(textsUnder(PracticeTags.FOCUS_NONE).joinToString(" ")).contains("No playable examples of h-dropping yet")
      compose.onNodeWithTag(PracticeTags.START).assertDoesNotExist()
      compose.onNodeWithTag(PracticeTags.FOCUS_EVERYTHING).assertExists()
    }
  }

  @Test
  fun anUnknownFocusPractisesEverything() {
    show(PracticePreviewData.startState.copy(start = StartUi(focus = FocusUi.Unknown("flap")))) {
      compose.onNodeWithTag(PracticeTags.FOCUS_UNKNOWN).assertTextEquals("Unknown focus “flap”: practicing everything.")
      compose.onNodeWithTag(PracticeTags.START).assertExists()
    }
  }

  @Test
  fun aSessionLeftUnfinishedIsOfferedAsContinue() {
    show(PracticePreviewData.startState.copy(start = PracticePreviewData.start.copy(resume = ResumeUi(4, 10)))) {
      compose.onNodeWithText("Your last session stopped at question 4 of 10.").assertExists()
    }
    tap(PracticeTags.RESUME)
    assertThat(calls).containsExactly("resume")
  }

  @Test
  fun progressShowsTheAccuracyPerChangeWeakestFirstAndNothingAboutStreaksOrTime() {
    show(PracticePreviewData.startState) {
      compose.onNodeWithTag(PracticeTags.PROGRESS_KEPT).assertTextEquals("Kept only on this phone.")
      compose.onNodeWithTag(PracticeTags.accuracyRow(ChangeLabel("t_deletion"))).assertTextContains("4 of 9 · 44 %")
      compose.onNodeWithTag(PracticeTags.accuracyRow(ChangeLabel("flapping"))).assertTextContains("7 of 10 · 70 %")
      assertThat(compose.onNodeWithTag(PracticeTags.accuracyRow(ChangeLabel("t_deletion"))).text())
        .contains("T/d deletion: 4 of 9 right, 44 percent")
      val rows = compose.onAllNodes(hasTestTag(PracticeTags.accuracyRow(ChangeLabel("t_deletion"))) or
        hasTestTag(PracticeTags.accuracyRow(ChangeLabel("flapping")))).fetchSemanticsNodes()
      assertThat(rows.map { it.positionInRoot.y }).isInStrictOrder()
      assertThat(textsUnder(PracticeTags.LINK_SAVED)).containsAtLeast("Saved", "12")
      assertThat(textsUnder(PracticeTags.MET)).containsAtLeast("Changes you have met", "11 of 16")
      // F-17-AC5: nothing mentions streaks, missed days or time spent, however long since the last practice.
      val words = textsUnder(PracticeTags.PROGRESS).joinToString(" ").lowercase()
      assertThat(words).containsMatch("changes you have met")
      assertThat(words).doesNotContainMatch(STREAKS_OR_TIME.toPattern())
    }
    tap(PracticeTags.LINK_SAVED)
    compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(PracticeTags.notMet(ChangeLabel("linking")))))
      .performScrollTo().performClick()
    assertThat(calls).containsExactly("saved", "learn:linking").inOrder()
  }

  @Test
  fun withoutAnswersProgressSaysHowItFillsAndOffersNoReset() {
    show(PracticePreviewData.startState.copy(progress = PracticePreviewData.progress.copy(rows = emptyList()))) {
      compose.onNodeWithTag(PracticeTags.PROGRESS_NONE)
        .assertTextEquals("Answer a few questions and your results per change will appear here.")
      compose.onNodeWithTag(PracticeTags.RESET).assertDoesNotExist()
    }
  }

  @Test
  fun resetAsksFirstAndForgetsOnlyWhenConfirmed() {
    screen.setContent(ScreenVariant(fontScale = 2f)) { PracticeScreen(PracticePreviewData.startState, actions) }
    tap(PracticeTags.RESET)
    compose.onNodeWithText("Forget your practice history?").assertExists()
    compose.onNodeWithText("Your accuracy per change is kept only on this phone. This clears it; your clips are not touched.").assertExists()
    screen.audit()
    compose.onNodeWithText("Cancel").performClick()
    compose.onNodeWithText("Forget your practice history?").assertDoesNotExist()
    assertThat(calls).isEmpty()

    tap(PracticeTags.RESET)
    compose.onNodeWithText("Forget it").performClick()
    assertThat(calls).containsExactly("reset")
    compose.onNodeWithText("Forget your practice history?").assertDoesNotExist()
  }

  @Test
  fun nothingToPracticeOffersToAddAClip() {
    show(PracticePreviewData.firstRunState) {
      compose.onNodeWithText("Nothing to practice yet").assertExists()
      compose.onNodeWithText(
        "Practice builds its questions from the changes found in your clips. Add a clip first — a minute of conversation " +
          "already gives dozens of questions.",
      ).assertExists()
      // A first run has no progress to show yet.
      compose.onNodeWithTag(PracticeTags.PROGRESS).assertDoesNotExist()
      compose.onNodeWithTag(PracticeTags.START_CARD).assertDoesNotExist()
    }
    compose.onNodeWithText("Add a clip").performScrollTo().performClick()
    assertThat(calls).containsExactly("addClip")
  }

  @Test
  fun loadingAndBuildingShowTheLoadingGate() {
    show(PracticeUiState(page = PracticePage.BUILDING)) {
      compose.onNodeWithTag(PracticeTags.LOADING).assertExists()
      compose.onNodeWithTag(PracticeTags.START_CARD).assertDoesNotExist()
    }
  }

  @Test
  fun aQuestionShowsItsPositionTheBeadsAndTheCard() {
    show(PracticePreviewData.sessionState()) {
      compose.onNodeWithTag(PracticeTags.POSITION).assertTextEquals("Question 3 of 10")
      compose.onNodeWithTag(PracticeTags.TALLY).assertTextEquals("1 right · 1 wrong")
      assertThat(compose.onAllNodesWithTag(PracticeTags.bead(9), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
      compose.onNodeWithTag(QuestionTags.PROMPT).assertTextEquals("Listen to “water”. Which pronunciation did you hear?")
      compose.onNodeWithTag(PracticeTags.NEXT).assertDoesNotExist()
      compose.onNodeWithTag(QuestionTags.SAVE).assertDoesNotExist()
      // The tab root's title gives the room to the question.
      compose.onNodeWithText("Train your ear with words and phrases from your own clips. A few minutes a day is enough.").assertDoesNotExist()
    }
    compose.onNodeWithTag(QuestionTags.option("said")).performScrollTo().performClick()
    tap(QuestionTags.SLOW)
    tap(QuestionTags.CONTEXT)
    tap(PracticeTags.LEAVE)
    assertThat(calls).containsExactly("answer:said", "play:SLOW", "play:CONTEXT", "leave").inOrder()
  }

  @Test
  fun theOptionsAreAtLeast56DpTall() {
    show(PracticePreviewData.sessionState()) {
      PracticePreviewData.formQuestion.options.forEach { option ->
        val node = compose.onNodeWithTag(QuestionTags.option(option.id)).fetchSemanticsNode()
        assertThat(node.boundsInRoot.height / compose.density.density).isAtLeast(56f)
      }
    }
  }

  @Test
  fun aWrongAnswerShowsTheMarksTheExplanationAndNext() {
    show(PracticePreviewData.sessionState(PracticePreviewData.session(chosen = "dictionary"))) {
      compose.onNodeWithTag(QuestionTags.option("said")).assertTextContains("Correct answer")
      compose.onNodeWithTag(QuestionTags.option("dictionary")).assertTextContains("Your answer")
      compose.onNodeWithTag(QuestionTags.LIVE).assertTextContains("Not quite — the answer was [ˈwɔɾɚ].", substring = true)
      compose.onNodeWithTag(QuestionTags.FORMS).assertTextEquals("“water” /ˈwɔtɚ/ → [ˈwɔɾɚ]")
      compose.onNodeWithTag(QuestionTags.SAVE).assertTextContains("Save")
      compose.onNodeWithTag(PracticeTags.NEXT).assertTextContains("Next question")
    }
    // Nothing is saved unless "Save" is tapped (spec F-17 item 3).
    assertThat(calls).isEmpty()
    listOf(QuestionTags.AGAIN_SLOW, QuestionTags.LESSON, QuestionTags.SAVE, QuestionTags.LEARN, QuestionTags.WATCH_MORE, PracticeTags.NEXT)
      .forEach(::tap)
    assertThat(calls).containsExactly(
      "play:SLOW", "lesson:2/4", "save", "learn:flapping", "watchMore:flapping", "next",
    ).inOrder()
  }

  @Test
  fun aRightAnswerOnTheLastQuestionLeadsToTheResults() {
    val last = PracticePreviewData.session(PracticePreviewData.changeQuestion, chosen = "flapping", index = 9)
    show(PracticePreviewData.sessionState(last)) {
      compose.onNodeWithTag(PracticeTags.POSITION).assertTextEquals("Question 10 of 10")
      compose.onNodeWithTag(QuestionTags.LIVE).assertTextContains("Correct!")
      compose.onNodeWithTag(PracticeTags.NEXT).assertTextContains("See your results")
    }
  }

  @Test
  fun aCountAnswerShowsThePhrase() {
    val count = PracticePreviewData.session(PracticePreviewData.countQuestion, chosen = "4")
    show(PracticePreviewData.sessionState(count)) {
      compose.onNodeWithTag(QuestionTags.PHRASE).assertExists()
      compose.onNode(hasContentDescription("4. the (changed)"), useUnmergedTree = true).assertExists()
    }
  }

  @Test
  fun theResultsSayTheScoreWhatToReviewAndWhatNext() {
    show(PracticePreviewData.resultsState) {
      compose.onNodeWithTag(PracticeTags.SCORE).assertTextEquals("7 of 10 right")
      compose.onNodeWithTag(PracticeTags.PARTIAL).assertDoesNotExist()
      compose.onNodeWithText("Worth reviewing").assertExists()
      compose.onNode(hasContentDescription("Wrong: 2. Which change? “just” — t/d deletion")).assertExists()
      compose.onNode(hasContentDescription("Right: 1. Which pronunciation? “water” — flapping")).assertExists()
    }
    compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(PracticeTags.review(ChangeLabel("t_deletion")))))
      .performScrollTo().performClick()
    listOf(PracticeTags.AGAIN, PracticeTags.AGAIN_WEAK_SPOTS, PracticeTags.BACK_TO_START).forEach(::tap)
    assertThat(calls).containsExactly("learn:t_deletion", "again", "againWeakSpots", "backToStart").inOrder()
  }

  @Test
  fun aPoolThatRanOutEarlySaysSo() {
    show(PracticePreviewData.partialResultsState) {
      compose.onNodeWithTag(PracticeTags.SCORE).assertTextEquals("3 of 4 right")
      compose.onNodeWithTag(PracticeTags.PARTIAL).assertTextEquals("There were not enough clips to build questions this time.")
    }
  }

  @Test
  fun lessonOpensOnTheQuestionsWord() {
    screen.setContent(ScreenVariant()) { PracticeScreen(PracticePreviewData.sessionState(PracticePreviewData.session(chosen = "said")), actions) }
    tap(QuestionTags.LESSON)
    val word: WordRef = PracticePreviewData.water
    assertThat(calls).containsExactly("lesson:${word.segment}/${word.word}")
  }

  private companion object {
    /** Streaks, missed days, days or weeks practised, time spent (F-17-AC5). */
    val STREAKS_OR_TIME = Regex("streak|in a row|missed|\\bdays?\\b|\\bweeks?\\b|minutes?|hours?|time spent|yesterday|today")
  }
}
