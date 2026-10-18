package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.MouthMode
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceTags
import com.mapineda48.phonotrainer.core.ui.extension.MouthFrame
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import com.mapineda48.phonotrainer.core.ui.extension.MouthTrack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen tests of a phenomenon page (work-plan WP-20 Proof; spec F-15 item 2, 3 and its states, D-48): "12 times in
 * your clips" from `ChangeCount(9, 12)` above the examples (F-15-AC1), the partial line, the empty examples with "Add a
 * clip", the Spanish-speaker tip of vowel_reduction from the bundled web content (F-15-AC3), the unknown change, the
 * unreadable index, the actions, the change's "What's this?", and both mouth slots, with the accessibility audit at
 * font scale 2.0 in light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class PhenomenonScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose

  private fun page(
    state: PhenomenonUiState,
    actions: PhenomenonActions = PhenomenonActions(),
    mouth: MouthRenderer? = null,
  ): @Composable () -> Unit = {
    PhenomenonScreen(state, actions, glossary = BundledContent.glossary::get, mouth = mouth)
  }

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(LearnTags.PHENOMENON_LIST).performScrollToNode(hasTestTag(tag))
  }

  @Test
  fun flappingSaysTwelveTimesInYourClipsAboveItsExamples() =
    screen.checkEachInList(LearnTags.PHENOMENON_LIST, content = page(LearnPreviewData.phenomenon())) {
      // F-15-AC1: the page's unit is occurrences (D-48), from the same ChangeCount(9, 12) as the card's 9 shorts.
      scrollTo(LearnTags.PHENOMENON_TIMES)
      compose.onNodeWithTag(LearnTags.PHENOMENON_TIMES).assertTextEquals("12 times in your clips")
      assertThat(compose.onAllNodes(hasTestTag(LearnTags.PHENOMENON_COUNTED_HERE)).fetchSemanticsNodes()).isEmpty()
      scrollTo(LearnTags.example(2))
      compose.onNodeWithText("Examples from your clips").assertExists()
      val times = compose.onNodeWithTag(LearnTags.PHENOMENON_TIMES).fetchSemanticsNode().boundsInRoot
      val first = compose.onNodeWithTag(LearnTags.example(0)).fetchSemanticsNode().boundsInRoot
      assertThat(times.bottom).isAtMost(first.top)
      compose.onNodeWithTag(LearnTags.example(2)).assertIsDisplayed()
    }

  @Test
  fun oneOccurrenceReadsOneTime() {
    screen.setContent(content = page(LearnPreviewData.phenomenon(counts = CountsUi.Ready(mapOf(ChangeLabel("flapping") to ChangeCount(1, 1))))))
    scrollTo(LearnTags.PHENOMENON_TIMES)
    compose.onNodeWithTag(LearnTags.PHENOMENON_TIMES).assertTextEquals("1 time in your clips")
  }

  @Test
  fun whenSomeClipsAreNotHereTheCountSaysItIsThisPhones() {
    screen.setContent(content = page(LearnPreviewData.phenomenon(partial = true)))
    scrollTo(LearnTags.PHENOMENON_COUNTED_HERE)
    compose.onNodeWithTag(LearnTags.PHENOMENON_COUNTED_HERE).assertTextEquals("Counted in the clips on this phone.")
  }

  @Test
  fun noExampleShowsTheEmptyStateWithAddAClip() {
    var added = 0
    val state = LearnPreviewData.phenomenon(counts = CountsUi.Ready(emptyMap()), examples = ExamplesUi.Ready(emptyList()))
    screen.checkEachInList(LearnTags.PHENOMENON_LIST, content = page(state, PhenomenonActions(onAddClip = { added++ }))) {
      scrollTo(LearnTags.PHENOMENON_NO_EXAMPLES)
      compose.onNodeWithText("No examples in your clips yet").assertIsDisplayed()
      compose.onNodeWithText("When an analysis finds this change, its clearest cases will appear here.").assertExists()
      compose.onNodeWithTag(LearnTags.PHENOMENON_TIMES).assertTextEquals("0 times in your clips")
    }
    scrollTo(LearnTags.PHENOMENON_NO_EXAMPLES)
    compose.onNode(hasText("Add a clip") and hasAnyAncestor(hasTestTag(LearnTags.PHENOMENON_NO_EXAMPLES))).performClick()
    assertThat(added).isEqualTo(1)
  }

  @Test
  fun vowelReductionShowsTheSpanishSpeakerTipOfTheWebContent() {
    // F-15-AC3.
    val label = ChangeLabel("vowel_reduction")
    val lesson = BundledContent.lessons.getValue(label)
    val state = LearnPreviewData.phenomenon(lesson = lesson, demo = LearnLogic.demo(lesson.demo, BundledContent.didactic()))
      .copy(card = LearnLogic.card(SampleReference.reference, label), familyName = "Reduction")
    screen.checkEachInList(LearnTags.PHENOMENON_LIST, content = page(state)) {
      scrollTo(LearnTags.PHENOMENON_TIP)
      compose.onNodeWithTag(LearnTags.PHENOMENON_TIP).assertTextContains("For Spanish speakers:", substring = true)
      compose.onNodeWithTag(LearnTags.PHENOMENON_TIP).assertTextContains(lesson.spanishTip!!, substring = true)
      scrollTo(LearnTags.PHENOMENON_HOW)
      compose.onNodeWithText("How to hear it").assertIsDisplayed()
    }
  }

  @Test
  fun aChangeWithoutATipShowsNone() {
    val lesson = BundledContent.lessons.getValue(ChangeLabel("t_deletion"))
    screen.setContent(content = page(LearnPreviewData.phenomenon(lesson = lesson)))
    scrollTo(LearnTags.PHENOMENON_COPY)
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.PHENOMENON_TIP)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun theChipsSayTheFamilyAndTheAdviceAndCanYouCopyItSaysTheRegister() {
    screen.setContent(content = page(LearnPreviewData.phenomenon()))
    compose.onNode(hasAnyAncestor(hasTestTag(LearnTags.PHENOMENON_CHIPS)) and hasContentDescription("t/d processes, dashed line"), useUnmergedTree = true)
      .assertExists()
    compose.onNode(hasText("Safe to produce · universal") and hasAnyAncestor(hasTestTag(LearnTags.PHENOMENON_CHIPS)), useUnmergedTree = true).assertExists()
    scrollTo(LearnTags.PHENOMENON_COPY)
    compose.onNode(hasText("universal — Every speaker does this, in almost every style."), useUnmergedTree = true).assertExists()
  }

  @Test
  fun theRegistersWhatsThisOpensItsExplanation() {
    screen.setContent(content = page(LearnPreviewData.phenomenon()))
    scrollTo(LearnTags.PHENOMENON_COPY)
    compose.onNode(hasContentDescription("What's this: Register")).performClick()
    compose.onNodeWithTag(UiTags.EXPLAIN_CLOSE).assertExists()
    compose.onNode(hasText(BundledContent.glossary.getValue("register").body)).assertExists()
  }

  @Test
  fun theTopBarsWhatsThisExplainsTheChangeAndWatchesMoreLikeIt() {
    val watched = mutableListOf<ChangeLabel>()
    screen.setContent(content = page(LearnPreviewData.phenomenon(), PhenomenonActions(onWatch = { watched += it })))
    compose.onNode(hasContentDescription("What's this: Flapping")).performClick()
    compose.onNodeWithTag(LearnTags.CHANGE_SHEET).assertExists()
    compose.onNodeWithTag(LearnTags.CHANGE_SHEET_WATCH).performClick()
    assertThat(watched).containsExactly(ChangeLabel("flapping"))
  }

  @Test
  fun theActionsReachTheirPlaces() {
    val calls = mutableListOf<String>()
    val actions = PhenomenonActions(
      onBack = { calls += "back" },
      onWatch = { calls += "watch:${it.value}" },
      onPractice = { calls += "practice:${it.value}" },
      onPlay = { calls += "play:${it.wordText}" },
      onOpenLesson = { calls += "lesson:${it.wordText}" },
      onIpaChart = { calls += "ipa:$it" },
    )
    screen.setContent(content = page(LearnPreviewData.phenomenon(), actions))
    scrollTo(LearnTags.PHENOMENON_MOUTH_CHART)
    compose.onNodeWithTag(LearnTags.PHENOMENON_MOUTH_CHART).performClick()
    scrollTo(LearnTags.PHENOMENON_EXAMPLES)
    compose.onNodeWithTag(LearnTags.PHENOMENON_EXAMPLES).performClick()
    compose.onNodeWithTag(LearnTags.PHENOMENON_PRACTICE).performClick()
    scrollTo(LearnTags.example(0))
    compose.onNode(hasTestTag(OccurrenceTags.PLAY) and hasAnyAncestor(hasTestTag(LearnTags.example(0)))).performClick()
    compose.onNode(hasTestTag(OccurrenceTags.LESSON) and hasAnyAncestor(hasTestTag(LearnTags.example(0)))).performClick()
    compose.onNodeWithTag(LearnTags.BACK).performClick()
    assertThat(calls).containsExactly("ipa:ɾ", "watch:flapping", "practice:flapping", "play:water?", "lesson:water?", "back").inOrder()
  }

  @Test
  fun whileTheExamplesAreReadTheLoadingRuleApplies() {
    screen.setContent(content = page(LearnPreviewData.phenomenon(counts = CountsUi.Loading, examples = ExamplesUi.Loading)))
    scrollTo(LearnTags.PHENOMENON_EXAMPLE_LIST)
    compose.onNode(hasTestTag(UiTags.LOADING) and hasAnyAncestor(hasTestTag(LearnTags.PHENOMENON_EXAMPLE_LIST)), useUnmergedTree = true).assertExists()
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.PHENOMENON_TIMES)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun anUnreadableIndexSaysSoAndTheExplanationsStay() {
    screen.setContent(content = page(LearnPreviewData.phenomenon(counts = CountsUi.Failed, examples = ExamplesUi.Failed)))
    scrollTo(LearnTags.PHENOMENON_EXAMPLE_LIST)
    val failed = compose.onAllNodes(hasText("Your clip counts could not be loaded; the explanations still work.")).fetchSemanticsNodes()
    assertThat(failed).isNotEmpty()
    scrollTo(LearnTags.PHENOMENON_HOW)
    compose.onNodeWithTag(LearnTags.PHENOMENON_HOW).assertIsDisplayed()
  }

  @Test
  fun anUnknownChangeSaysThereIsNoLessonAndLeadsToLearn() {
    var learn = 0
    screen.checkEachInList(null, content = page(PhenomenonUiState.Unknown, PhenomenonActions(onLearn = { learn++ }))) {
      compose.onNodeWithText("There is no lesson with that name").assertIsDisplayed()
    }
    compose.onNodeWithText("See every change").performClick()
    assertThat(learn).isEqualTo(1)
  }

  @Test
  fun withoutTheArticulatorTheMouthSaysEachChangingSoundInWords() =
    screen.checkEachInList(LearnTags.PHENOMENON_LIST, content = page(LearnPreviewData.phenomenon())) {
      scrollTo(LearnTags.PHENOMENON_MOUTH)
      val mouth = hasAnyAncestor(hasTestTag(LearnTags.PHENOMENON_MOUTH))
      compose.onNode(hasText("[t] Voiceless alveolar stop") and mouth, useUnmergedTree = true).assertExists()
      compose.onNode(hasText("[ɾ] Alveolar tap") and mouth, useUnmergedTree = true).assertExists()
      compose.onNode(hasText("The tip bounces off the ridge once", substring = true) and mouth, useUnmergedTree = true).assertExists()
      assertThat(compose.onAllNodes(hasTestTag(LearnTags.PHENOMENON_MOUTH_DRAWING), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
      assertThat(compose.onAllNodes(hasText("Play the movement"), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    }

  @Test
  fun aDroppedSoundIsSaidToBeDropped() {
    val dropped = LearnPreviewData.demo.copy(to = null)
    screen.setContent(content = page(LearnPreviewData.phenomenon(demo = dropped)))
    scrollTo(LearnTags.PHENOMENON_MOUTH)
    compose.onNode(hasText("The sound is dropped: nothing is said in its place."), useUnmergedTree = true).assertExists()
  }

  @Test
  fun withTheArticulatorTheMouthIsDrawnAndPlaysItsMovement() {
    val renderer = RecordingMouth()
    screen.checkEachInList(LearnTags.PHENOMENON_LIST, content = page(LearnPreviewData.phenomenon(), mouth = renderer)) {
      scrollTo(LearnTags.PHENOMENON_MOUTH_PLAY)
      compose.onNodeWithTag(LearnTags.PHENOMENON_MOUTH_DRAWING, useUnmergedTree = true).assertExists()
      compose.onNodeWithText("What is said").assertExists()
      compose.onNodeWithText("Slow").assertExists()
    }
    scrollTo(LearnTags.PHENOMENON_MOUTH_PLAY)
    assertThat(renderer.tracked.last().map { it.symbol }).containsExactly("w", "ɔ", "ɾ", "ɚ").inOrder()
    compose.onNodeWithText("Dictionary form").performSemanticsAction(SemanticsActions.OnClick)
    compose.waitForIdle()
    assertThat(renderer.tracked.last().map { it.symbol }).containsExactly("w", "ɔ", "t", "ɚ").inOrder()
    val before = renderer.frames.toList()
    compose.mainClock.autoAdvance = false
    compose.onNodeWithTag(LearnTags.PHENOMENON_MOUTH_PLAY).performSemanticsAction(SemanticsActions.OnClick)
    repeat(200) { compose.mainClock.advanceTimeByFrame() }
    compose.mainClock.autoAdvance = true
    compose.waitForIdle()
    assertWithMessage("frames before $before, after ${renderer.frames}").that(renderer.frames.size).isGreaterThan(before.size + 2)
  }

  @Test
  fun aPageWithoutLessonTextStillShowsTheReferencesText() {
    screen.setContent(content = page(LearnPreviewData.phenomenon(lesson = null, demo = null)))
    compose.onNodeWithTag(LearnTags.PHENOMENON_DESCRIPTION).assertIsDisplayed()
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.PHENOMENON_HOW)).fetchSemanticsNodes()).isEmpty()
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.PHENOMENON_MOUTH)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun theTitleWearsTheChangesMarkAndIsAHeading() {
    screen.setContent(content = page(LearnPreviewData.phenomenon()))
    val headings = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
      .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } }
    assertThat(headings).contains("Flapping")
  }
}

/** A stand-in for WP-29's renderer: it records the phones it was given and every frame it drew. */
internal class RecordingMouth : MouthRenderer {
  val tracked = mutableListOf<List<TimedPhone>>()
  val frames = mutableListOf<Long>()

  @Composable
  override fun Mouth(frame: MouthFrame, modifier: Modifier) {
    frames += (frame.pose["at"] ?: 0.0).toLong()
    Box(modifier.size(10.dp).testTag("test.mouth"))
  }

  override fun track(phones: List<TimedPhone>, mode: MouthMode): MouthTrack {
    tracked += phones
    return MouthTrack { ms -> MouthFrame(mapOf("at" to ms.toDouble())) }
  }
}
