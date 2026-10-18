package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.toSize
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No word and no control of Practice or Saved is cut off by its card, at font scale 1.0 and 2.0. The design system's
 * cards take the intrinsic height of their content (WP-03's `PaperCard`, `height(IntrinsicSize.Min)`) and clip to
 * it, so content whose intrinsic height is short of its real height loses its bottom: on the managed device the
 * results card hid "Back to the start" that way, because the results' lines (an icon and a text that wraps, in a row)
 * were measured as one line each. The window is tall enough that nothing is scrolled away, so a node smaller on screen
 * than its own size is cut by a clip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TALL_WINDOW)
class CardClippingTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val variants = listOf(ScreenVariant(), ScreenVariant(fontScale = 2f))

  private val wordsOrControls = SemanticsMatcher("a word or a control") { node ->
    SemanticsProperties.Text in node.config || SemanticsActions.OnClick in node.config
  }

  private fun cut(node: SemanticsNode): Boolean {
    val whole = Rect(node.positionInRoot, node.size.toSize())
    val shown = node.boundsInRoot
    return whole.height > 0f && (shown.height < whole.height - 1f || shown.width < whole.width - 1f)
  }

  private fun label(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
      ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
      ?: "node ${node.id}"

  /** Inside WP-20's example row (its contract-freeze placeholder lays its buttons out in one row that can overflow). */
  private fun inExampleRow(node: SemanticsNode): Boolean {
    var current: SemanticsNode? = node
    while (current != null) {
      val tag = current.config.getOrNull(SemanticsProperties.TestTag)
      if (tag == OccurrenceTags.PLAY || tag == OccurrenceTags.LESSON) return true
      current = current.parent
    }
    return false
  }

  private fun checkNothingCut(variants: List<ScreenVariant> = this.variants, content: @Composable () -> Unit) =
    screen.checkEach(variants, content) {
      val cut = compose.onAllNodes(wordsOrControls, useUnmergedTree = true).fetchSemanticsNodes()
        .filterNot(::inExampleRow)
        .filter(::cut)
        .map(::label)
      assertWithMessage("cut off by their card").that(cut).isEmpty()
    }

  private val longFocus = FocusUi.Known(ChangeRef(ChangeLabel("function_elision"), "function-word elision", Family.REDUCTION), playable = true)

  /**
   * At font scale 2.0 a focus as long as "function-word elision" overflows WP-03's chip, whose words are not weighted
   * in its row, so its "✕" is pushed out (reported to the integrator); "Practice everything instead" under it stays.
   */
  @Test
  fun theStartCardWithALongFocusAndTheProgress() = checkNothingCut(listOf(ScreenVariant())) {
    PracticeScreen(PracticePreviewData.startState.copy(start = StartUi(focus = longFocus, resume = ResumeUi(4, 10))), PracticeActions())
  }

  @Test
  fun theStartCardWithAFocusAtEveryScale() = checkNothingCut {
    PracticeScreen(PracticePreviewData.focusState.copy(start = PracticePreviewData.focusState.start.copy(resume = ResumeUi(4, 10))), PracticeActions())
  }

  @Test
  fun anAnsweredPronunciationQuestion() = checkNothingCut {
    PracticeScreen(PracticePreviewData.sessionState(PracticePreviewData.session(chosen = "dictionary")), PracticeActions())
  }

  @Test
  fun anAnsweredChangeQuestion() = checkNothingCut {
    val session = PracticePreviewData.session(PracticePreviewData.changeQuestion, chosen = "linking")
    PracticeScreen(PracticePreviewData.sessionState(session), PracticeActions())
  }

  /** The results the managed device showed when "Back to the start" was cut off: ten lines, six changes to review. */
  @Test
  fun theResultsOfAWholeSession() = checkNothingCut {
    val words = listOf("little", "what", "right", "just", "it", "about", "kind", "water", "got", "right")
    val kinds = listOf(QuestionKind.FORM, QuestionKind.CHANGE, QuestionKind.COUNT)
    val lines = words.mapIndexed { i, word -> ResultLine(i + 1, kinds[i % 3], word, "monophthongization", correct = i == 4 || i == 6) }
    val missed = listOf(
      "monophthongization" to Family.REDUCTION,
      "elided syllable" to Family.REDUCTION,
      "vowel reduction" to Family.REDUCTION,
      "th-stopping" to Family.ASSIMILATION,
      "unreleased t/d" to Family.TD,
      "function-word elision" to Family.REDUCTION,
    ).mapIndexed { i, (name, family) -> ChangeRef(ChangeLabel("c$i"), name, family) }
    val results = ResultsUi(right = 2, total = 10, partial = false, missed = missed, lines = lines)
    PracticeScreen(PracticePreviewData.resultsState.copy(results = results), PracticeActions())
  }

  @Test
  fun theSavedRows() = checkNothingCut { SavedScreen(PracticePreviewData.savedState, SavedActions()) }
}
