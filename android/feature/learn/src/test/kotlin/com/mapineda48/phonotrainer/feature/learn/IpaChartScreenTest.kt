package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen tests of the IPA chart (work-plan WP-20 Proof; spec F-16): on the bundled layout, at the largest text on a
 * 360 dp wide screen, every symbol is reachable without sideways scrolling and none is clipped (F-16-AC1); TalkBack
 * reads each symbol's name (F-16-AC2); the weak vowels; "Choose a symbol"; the symbol sheet with its text, the change
 * it signals and "In your clips" (F-16-AC3), empty or full; and both mouth slots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class IpaChartScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val content = BundledContent.didactic()
  private val layout = content.layout
  private val names = LearnLogic.names(layout, content)

  private fun chart(selected: String? = null, detail: SymbolDetailUi? = null) =
    IpaChartUiState(layout = layout, weak = layout.reducedVowels.toSet(), names = names, selected = selected, detail = detail)

  private fun tap(examples: ExamplesUi = LearnPreviewData.examples()) =
    LearnLogic.symbolDetail("ɾ", content, SampleReference.reference, examples)

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(LearnTags.IPA_LIST).performScrollToNode(hasTestTag(tag))
  }

  @Test
  fun theChartPassesTheAuditAtTheLargestText() =
    screen.checkEachInList(LearnTags.IPA_LIST, content = { IpaChartScreen(chart(), IpaChartActions()) }) {
      scrollTo(LearnTags.ipaSection("vowels"))
      compose.onNodeWithTag(LearnTags.ipaSection("vowels")).assertExists()
    }

  @Test
  fun eachSymbolIsReadByItsNameAndAWeakVowelSaysSo() {
    // F-16-AC2: "voiced alveolar tap" in the spec; the bundled table names [ɾ] "alveolar tap".
    screen.setContent { IpaChartScreen(chart(), IpaChartActions()) }
    scrollTo(LearnTags.symbol("ɾ"))
    val tap = compose.onNodeWithTag(LearnTags.symbol("ɾ")).fetchSemanticsNode()
    assertThat(tap.config[SemanticsProperties.ContentDescription].joinToString()).isEqualTo("[ɾ] alveolar tap")
    for (symbol in layout.placed) {
      scrollTo(LearnTags.symbol(symbol))
      val said = compose.onNodeWithTag(LearnTags.symbol(symbol)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
      assertThat(said).isEqualTo(
        if (symbol in layout.reducedVowels) "[$symbol] ${names.getValue(symbol)}, a weak vowel" else "[$symbol] ${names.getValue(symbol)}",
      )
    }
    scrollTo(LearnTags.symbol("ə"))
    compose.onNode(hasText("weak") and hasAnyAncestor(hasTestTag(LearnTags.symbol("ə"))), useUnmergedTree = true).assertExists()
  }

  @Test
  fun atLargeTextTheNameIsWrittenUnderTheSymbol() {
    screen.setContent(ScreenVariant(fontScale = 2f)) { IpaChartScreen(chart(), IpaChartActions()) }
    scrollTo(LearnTags.symbol("ɾ"))
    compose.onNode(hasText("alveolar tap") and hasAnyAncestor(hasTestTag(LearnTags.symbol("ɾ"))), useUnmergedTree = true).assertExists()
  }

  @Test
  fun atNormalTextOnlyTheSymbolIsWritten() {
    screen.setContent { IpaChartScreen(chart(), IpaChartActions()) }
    scrollTo(LearnTags.symbol("ɾ"))
    val written = compose.onAllNodes(hasAnyAncestor(hasTestTag(LearnTags.symbol("ɾ"))) and hasText("alveolar tap"), useUnmergedTree = true)
    assertThat(written.fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun nothingSelectedSaysChooseASymbolAndATapSelectsIt() {
    val selected = mutableListOf<String?>()
    screen.setContent { IpaChartScreen(chart(), IpaChartActions(onSelect = { selected += it })) }
    compose.onNodeWithTag(LearnTags.IPA_CHOOSE).assertIsDisplayed()
    compose.onNodeWithText("Choose a symbol").assertIsDisplayed()
    compose.onNode(hasText("Each symbol is one sound. Start with [ɾ], [t̚] or [ə].") and hasAnyAncestor(hasTestTag(LearnTags.IPA_CHOOSE)), useUnmergedTree = true)
      .assertExists()
    scrollTo(LearnTags.symbol("ɾ"))
    compose.onNodeWithTag(LearnTags.symbol("ɾ")).performClick()
    assertThat(selected).containsExactly("ɾ")
  }

  @Test
  fun theSymbolSheetSaysWhatTheSoundIsAndPlaysItInYourClips() {
    // F-16-AC3: [ɾ] selected with examples on the phone.
    val calls = mutableListOf<String>()
    val actions = IpaChartActions(
      onSelect = { calls += "select:$it" },
      onPlay = { calls += "play:${it.wordText}" },
      onOpenLesson = { calls += "lesson:${it.wordText}" },
      onOpenChange = { calls += "change:${it.value}" },
    )
    screen.setContent { IpaChartScreen(chart("ɾ", tap()), actions) }
    compose.onNodeWithTag(LearnTags.IPA_SHEET_SYMBOL).assertTextEquals("ɾ")
    compose.onNodeWithTag(LearnTags.IPA_SHEET_NAME).assertTextEquals("Alveolar tap")
    compose.onNode(hasTestTag(LearnTags.IPA_SHEET_BODY)).assertExists()
    compose.onNodeWithTag(LearnTags.IPA_SHEET_AS_IN).assertTextEquals("As in: the -tt- of “butter”")
    compose.onNodeWithTag(LearnTags.IPA_SHEET_CHANGE).assertTextEquals("It is the sound of flapping")
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.IPA_CHOOSE)).fetchSemanticsNodes()).isEmpty()
    compose.onNodeWithText("In your clips").assertExists()
    compose.onNode(hasTestTag(OccurrenceTags.PLAY) and hasAnyAncestor(hasTestTag(LearnTags.example(1)))).performClick()
    compose.onNode(hasTestTag(OccurrenceTags.LESSON) and hasAnyAncestor(hasTestTag(LearnTags.example(1)))).performClick()
    compose.onNodeWithTag(LearnTags.IPA_SHEET_CHANGE).performClick()
    compose.onNodeWithTag(LearnTags.IPA_SHEET_CLOSE).performClick()
    assertThat(calls).containsExactly("play:get", "lesson:get", "change:flapping", "select:null").inOrder()
  }

  @Test
  fun theSelectedSymbolIsMarkedSelected() {
    screen.setContent { IpaChartScreen(chart("ɾ", tap()), IpaChartActions()) }
    scrollTo(LearnTags.symbol("ɾ"))
    compose.onNodeWithTag(LearnTags.symbol("ɾ"), useUnmergedTree = true).assertIsSelected()
  }

  @Test
  fun aSymbolWithNoExampleSaysNoExamplesYet() {
    screen.setContent { IpaChartScreen(chart("ɾ", tap(ExamplesUi.Ready(emptyList()))), IpaChartActions()) }
    compose.onNodeWithTag(LearnTags.IPA_SHEET_NO_EXAMPLES).assertTextEquals("No examples in your clips yet.")
  }

  @Test
  fun withoutTheArticulatorTheSheetSaysWhatTheMouthDoesAndWithItDrawsIt() {
    val bare = BundledContent.didactic().apply { entries.remove("ipa:p") }
    val detail = LearnLogic.symbolDetail("p", bare, SampleReference.reference, ExamplesUi.Ready(emptyList()))
    var mouth by mutableStateOf<RecordingMouth?>(null)
    screen.setContent { IpaChartScreen(chart("p", detail), IpaChartActions(), mouth = mouth) }
    compose.onNodeWithTag(LearnTags.IPA_SHEET_CUE).assertExists()
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.IPA_SHEET_MOUTH), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    mouth = RecordingMouth()
    compose.waitForIdle()
    compose.onNodeWithTag(LearnTags.IPA_SHEET_MOUTH, useUnmergedTree = true).assertExists()
    assertThat(mouth!!.tracked.last().map { it.symbol }).containsExactly("p")
  }

  @Test
  fun theChangeLinkNamesTheReferencesName() {
    val detail = tap().copy(change = ChangeLinkUi(ChangeLabel("flapping"), "flapping"))
    screen.setContent { IpaChartScreen(chart("ɾ", detail), IpaChartActions()) }
    compose.onNodeWithTag(LearnTags.IPA_SHEET_CHANGE).assertTextEquals("It is the sound of flapping")
  }
}
