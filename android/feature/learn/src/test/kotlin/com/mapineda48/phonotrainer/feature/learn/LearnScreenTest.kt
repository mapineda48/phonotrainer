package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen tests of Learn (work-plan WP-20 Proof; spec F-15 item 1, its states, D-48): every family section and the
 * whole-word changes with all sixteen cards, the short counts ("9 shorts in your clips" from `ChangeCount(9, 12)`,
 * F-15-AC1) and "Not in your clips yet", "Counted in the clips on this phone.", the unreadable index, the entry tiles,
 * each with the accessibility audit at font scale 2.0 in light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class LearnScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val sections = LearnLogic.sections(SampleReference.reference)
  private val flapping = ChangeLabel("flapping")

  private fun state(
    counts: CountsUi = CountsUi.Ready(mapOf(flapping to ChangeCount(shorts = 9, occurrences = 12), ChangeLabel("linking") to ChangeCount(1, 1))),
    partial: Boolean = false,
    insights: InsightsEntryUi? = null,
  ) = LearnUiState(sections, counts, partial, insights)

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(LearnTags.LIST).performScrollToNode(hasTestTag(tag))
  }

  @Test
  fun everyFamilySectionAndTheWholeWordChangesHoldAllSixteenChanges() =
    screen.checkEachInList(LearnTags.LIST, content = { LearnScreen(state(), LearnActions()) }) {
      compose.onNodeWithText("How native speakers change words when they talk — and which of those changes you can copy.").assertIsDisplayed()
      for (family in listOf(Family.REDUCTION, Family.TD, Family.ASSIMILATION, Family.BOUNDARY)) {
        scrollTo(LearnTags.family(family.name))
        compose.onNodeWithTag(LearnTags.family(family.name)).assertIsDisplayed()
      }
      scrollTo(LearnTags.family("WHOLE"))
      compose.onNodeWithTag(LearnTags.family("WHOLE")).assertIsDisplayed()
      compose.onNodeWithText("Whole-word changes", useUnmergedTree = true).assertIsDisplayed()
      for (label in SampleReference.reference.labels.keys) {
        scrollTo(LearnTags.card(label))
        compose.onNodeWithTag(LearnTags.card(label)).assertIsDisplayed()
      }
    }

  @Test
  fun aFamilyHeadSaysItsNameAndWhatItsLineMeans() {
    screen.setContent { LearnScreen(state(), LearnActions()) }
    scrollTo(LearnTags.family(Family.TD.name))
    val head = compose.onNodeWithTag(LearnTags.family(Family.TD.name)).fetchSemanticsNode()
    val description = head.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
    assertThat(description).isEqualTo("t/d processes. Dashed line · the stop is clipped")
  }

  @Test
  fun theFlappingCardReadsNineShortsAndAChangeNotFoundReadsNotInYourClipsYet() {
    // F-15-AC1: ChangeCount(9, 12) — the card's unit is shorts (D-48), the page's occurrences.
    screen.setContent { LearnScreen(state(), LearnActions()) }
    scrollTo(LearnTags.card(flapping))
    compose.onNode(hasTestTag(LearnTags.cardCount(flapping)), useUnmergedTree = true).assertTextEqualsDescription("9 shorts in your clips")
    scrollTo(LearnTags.card(ChangeLabel("linking")))
    compose.onNode(hasTestTag(LearnTags.cardCount(ChangeLabel("linking"))), useUnmergedTree = true).assertTextEqualsDescription("1 short in your clips")
    scrollTo(LearnTags.card(ChangeLabel("t_deletion")))
    compose.onNode(hasTestTag(LearnTags.cardCount(ChangeLabel("t_deletion"))), useUnmergedTree = true).assertTextEqualsDescription("Not in your clips yet")
  }

  @Test
  fun whileTheIndexIsReadTheCardsHaveTheirTextButNoCount() {
    screen.setContent { LearnScreen(state(counts = CountsUi.Loading), LearnActions()) }
    scrollTo(LearnTags.card(flapping))
    compose.onNodeWithTag(LearnTags.card(flapping)).assertIsDisplayed()
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.cardCount(flapping)), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun whenSomeClipsAreNotHereTheCountsSayTheyAreThisPhones() =
    screen.checkEachInList(LearnTags.LIST, content = { LearnScreen(state(partial = true), LearnActions()) }) {
      scrollTo(LearnTags.COUNTED_HERE)
      compose.onNodeWithTag(LearnTags.COUNTED_HERE).assertTextEquals("Counted in the clips on this phone.")
    }

  @Test
  fun anUnreadableIndexSaysSoAndTheExplanationsStay() =
    screen.checkEachInList(LearnTags.LIST, content = { LearnScreen(state(counts = CountsUi.Failed, partial = true), LearnActions()) }) {
      scrollTo(LearnTags.COUNTS_FAILED)
      compose.onNodeWithTag(LearnTags.COUNTS_FAILED).assertTextEquals("Your clip counts could not be loaded; the explanations still work.")
      assertThat(compose.onAllNodes(hasTestTag(LearnTags.COUNTED_HERE)).fetchSemanticsNodes()).isEmpty()
      scrollTo(LearnTags.card(flapping))
      compose.onNodeWithTag(LearnTags.card(flapping)).assertIsDisplayed()
    }

  @Test
  fun withoutInsightsThereAreTwoEntriesAndWithItThree() {
    var insights by mutableStateOf<InsightsEntryUi?>(null)
    screen.setContent { LearnScreen(state(insights = insights), LearnActions()) }
    compose.onNodeWithTag(LearnTags.LINK_IPA).assertIsDisplayed()
    compose.onNodeWithTag(LearnTags.LINK_GLOSSARY).assertIsDisplayed()
    assertThat(compose.onAllNodes(hasTestTag(LearnTags.LINK_INSIGHTS)).fetchSemanticsNodes()).isEmpty()
    insights = InsightsEntryUi("58 % of your words differ")
    compose.waitForIdle()
    compose.onNodeWithTag(LearnTags.LINK_INSIGHTS).assertIsDisplayed()
    compose.onNodeWithText("58 % of your words differ", useUnmergedTree = true).assertExists()
  }

  @Test
  fun theEntriesAndTheCardsLeadWhereTheySay() {
    val calls = mutableListOf<String>()
    val actions = LearnActions(
      onOpenChange = { calls += "change:${it.value}" },
      onIpaChart = { calls += "ipa" },
      onGlossary = { calls += "glossary" },
      onInsights = { calls += "insights" },
    )
    screen.setContent { LearnScreen(state(insights = InsightsEntryUi(null)), actions) }
    compose.onNodeWithTag(LearnTags.LINK_IPA).performClick()
    compose.onNodeWithTag(LearnTags.LINK_GLOSSARY).performClick()
    compose.onNodeWithTag(LearnTags.LINK_INSIGHTS).performClick()
    scrollTo(LearnTags.LINK_FLAPPING)
    compose.onNodeWithTag(LearnTags.LINK_FLAPPING).performClick()
    assertThat(calls).containsExactly("ipa", "glossary", "insights", "change:flapping").inOrder()
  }

  @Test
  fun atTheLargestTextTheEntriesStackSoNoWordIsCut() =
    screen.checkEachInList(LearnTags.LIST, content = { LearnScreen(state(insights = InsightsEntryUi(null)), LearnActions()) }) {
      val ipa = compose.onNodeWithTag(LearnTags.LINK_IPA).fetchSemanticsNode().boundsInRoot
      val glossary = compose.onNodeWithTag(LearnTags.LINK_GLOSSARY).fetchSemanticsNode().boundsInRoot
      assertThat(glossary.top).isAtLeast(ipa.bottom)
    }
}

/** The merged node of a count line reads exactly [text] (its content description is its words). */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEqualsDescription(text: String) {
  val node = fetchSemanticsNode()
  val said = node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
  assertThat(said).isEqualTo(text)
}
