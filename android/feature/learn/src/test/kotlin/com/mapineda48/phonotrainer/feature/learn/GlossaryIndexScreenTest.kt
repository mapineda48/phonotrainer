package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen tests of "Words we use" (work-plan WP-20 Proof; spec F-14, D-47) on its real ViewModel and the bundled
 * glossary: the lede, the placeholder, the live count "{n} of {total} terms", the groups "Terms" and "Measures";
 * "schwa" found (F-14-AC3); "xyzzy" giving "No term matches “xyzzy”.", "0 of {total} terms" for TalkBack and "Clear the
 * search" restoring every row (F-14-AC4); a row opening its "What's this?" sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class GlossaryIndexScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val total = BundledContent.wordsWeUse
  private val learnMore = mutableListOf<LinkTarget>()

  private val content: @Composable () -> Unit = {
    val viewModel = remember { GlossaryIndexViewModel(SavedStateHandle(), BundledContent.didactic()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlossaryIndexScreen(
      state = state,
      actions = GlossaryActions(onSearch = viewModel::search, onClear = viewModel::clear, onLearnMore = { learnMore += it }),
      entry = viewModel::entry,
    )
  }

  private fun type(text: String) {
    compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(LearnTags.GLOSSARY_SEARCH))).performTextInput(text)
    compose.waitForIdle()
  }

  private fun count() = compose.onNodeWithTag(LearnTags.GLOSSARY_COUNT)

  @Test
  fun thePageSaysWhatItIsAndListsEveryTermAndMeasure() =
    screen.checkEachInList(LearnTags.GLOSSARY_LIST, content = content) {
      compose.onNodeWithText(
        "Plain-English explanations of the terms and measures PhonoTrainer uses. Each one also opens from the “?” next to it.",
      ).assertIsDisplayed()
      compose.onNode(hasText("Search the words we use…") and hasAnyAncestor(hasTestTag(LearnTags.GLOSSARY_SEARCH)), useUnmergedTree = true)
        .assertExists()
      count().assertTextEquals("$total of $total terms")
      compose.onNode(hasTestTag(LearnTags.GLOSSARY_TERMS)).assertExists()
      compose.onNode(hasText("TERMS"), useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(LearnTags.GLOSSARY_LIST).performScrollToNode(hasTestTag(LearnTags.GLOSSARY_MEASURES))
      compose.onNode(hasText("MEASURES"), useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(LearnTags.GLOSSARY_LIST).performScrollToNode(hasTestTag(LearnTags.glossaryRow("metric:deviate")))
    }

  @Test
  fun eachRowShowsTheTermAndTheFirstSentenceOfItsExplanation() {
    screen.setContent(content = content)
    compose.onNodeWithTag(LearnTags.GLOSSARY_LIST).performScrollToNode(hasTestTag(LearnTags.glossaryRow("schwa")))
    val row = hasAnyAncestor(hasTestTag(LearnTags.glossaryRow("schwa")))
    compose.onNode(hasText("Schwa") and row, useUnmergedTree = true).assertExists()
    compose.onNode(hasText("The short, relaxed vowel [ə], as in the first sound of about.") and row, useUnmergedTree = true).assertExists()
  }

  @Test
  fun searchingSchwaFindsIt() {
    // F-14-AC3.
    screen.setContent(content = content)
    type("schwa")
    compose.onNodeWithTag(LearnTags.glossaryRow("schwa")).assertIsDisplayed()
    assertThat(count().fetchSemanticsNode().config[SemanticsProperties.Text].single().text).endsWith("of $total terms")
  }

  @Test
  fun xyzzyMatchesNothingTalkBackHearsZeroAndClearingRestoresEveryRow() {
    // F-14-AC4.
    screen.checkEachInList(LearnTags.GLOSSARY_LIST, content = content) {
      if (compose.onAllNodes(hasTestTag(LearnTags.GLOSSARY_NO_MATCH)).fetchSemanticsNodes().isEmpty()) type("xyzzy")
      compose.onNodeWithTag(LearnTags.GLOSSARY_NO_MATCH).assertIsDisplayed()
      compose.onNodeWithText("No term matches “xyzzy”.").assertIsDisplayed()
      compose.onNodeWithTag(LearnTags.GLOSSARY_CLEAR).assertIsDisplayed()
      count().assertTextEquals("0 of $total terms")
      // The count is a polite live region: TalkBack hears "0 of {total} terms" when it changes.
      assertThat(count().fetchSemanticsNode().config[SemanticsProperties.LiveRegion]).isEqualTo(LiveRegionMode.Polite)
      assertThat(compose.onAllNodes(hasTestTag(LearnTags.GLOSSARY_TERMS)).fetchSemanticsNodes()).isEmpty()
      assertThat(compose.onAllNodes(hasTestTag(UiTags.EMPTY_ACTION + ".0")).fetchSemanticsNodes()).isEmpty()
    }
    compose.onNodeWithTag(LearnTags.GLOSSARY_CLEAR).performClick()
    compose.waitForIdle()
    count().assertTextEquals("$total of $total terms")
    compose.onNodeWithTag(LearnTags.GLOSSARY_TERMS).assertIsDisplayed()
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.GLOSSARY_NO_MATCH)).fetchSemanticsNodes()).isEmpty()
    val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(LearnTags.GLOSSARY_SEARCH))).fetchSemanticsNode()
    assertThat(field.config[SemanticsProperties.EditableText].text).isEmpty()
  }

  @Test
  fun aRowOpensTheSameWhatsThisSheetAndItsLearnMore() {
    screen.setContent(content = content)
    type("differ from the dictionary")
    compose.onNodeWithTag(LearnTags.glossaryRow("metric:deviate")).performClick()
    compose.onNodeWithTag(UiTags.EXPLAIN_CLOSE).assertExists()
    compose.onNode(hasText("Words that differ from the dictionary") and hasAnyAncestor(hasTestTag(LearnTags.GLOSSARY_LIST)).not())
      .assertExists()
    compose.onNodeWithTag(UiTags.EXPLAIN_LEARN_MORE).performClick()
    assertThat(learnMore).containsExactly(LinkTarget.Learn)
  }
}

private fun androidx.compose.ui.test.SemanticsMatcher.not() =
  androidx.compose.ui.test.SemanticsMatcher("not ($description)") { !matches(it) }
