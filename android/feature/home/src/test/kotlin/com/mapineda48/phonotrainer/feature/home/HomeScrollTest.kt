package com.mapineda48.phonotrainer.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home keeps the learner's place (spec 5.2, F-03-AC8, F-03's story "continue exactly where I stopped") when blocks above
 * the ones on screen change: while another destination is on top, as the shell's navigation holds Home (its saveable
 * state kept, its composition gone), and while Home shows. At the top of the page nothing is held, so what grows there
 * pushes the rest down as before.
 */
@RunWith(RobolectricTestRunner::class)
// A phone's window: the page is taller than the screen, so the shelf is below the fold.
@Config(qualifiers = "w412dp-h800dp")
class HomeScrollTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose

  private var shown by mutableStateOf(HomeSamples.populated)

  /** False while another destination (the feed) is on top of Home. */
  private var onScreen by mutableStateOf(true)

  private val shelfCard = HomeTags.shelfCard(HomeSamples.coffee.id)

  private fun start(state: HomeUiState) {
    shown = state
    screen.setContent {
      val holder = rememberSaveableStateHolder()
      if (onScreen) holder.SaveableStateProvider(HOME) { HomeScreen(shown, HomeActions()) }
    }
  }

  /** The top of the node tagged [tag] on screen, in pixels. */
  private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

  private fun scrollValue(): Float =
    compose.onNodeWithTag(HomeTags.PAGE).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

  private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

  /** Scrolls the page to the shelf, then the shelf's row to the card (its nearest scrolling parent is the row). */
  private fun showTheShelfCard() {
    compose.onNodeWithTag(HomeTags.block("shelf")).performScrollTo()
    compose.onNodeWithTag(shelfCard).performScrollTo()
    compose.waitForIdle()
  }

  /** The learner scrolls to the shelf card and leaves for the feed; [away] happens while Home is not on screen. */
  private fun leaveFromTheShelf(away: () -> Unit): Float {
    showTheShelfCard()
    val at = top(shelfCard)
    onScreen = false
    compose.waitForIdle()
    away()
    compose.waitForIdle()
    return at
  }

  private fun comeBack() {
    onScreen = true
    compose.waitForIdle()
  }

  @Test
  fun backFindsTheShelfWhereItWasWhenKeepWatchingAppearedWhileHomeWasAway() {
    start(HomeSamples.populated.copy(keepWatching = null))
    // F-03-AC8: the clip's feed becomes the last feed position, so "Keep watching" is above the shelf on the way back.
    val at = leaveFromTheShelf { shown = HomeSamples.populated }
    comeBack()
    assertThat(exists(HomeTags.block("keep_watching"))).isTrue()
    compose.onNodeWithTag(shelfCard).assertIsDisplayed()
    assertThat(top(shelfCard)).isEqualTo(at)
  }

  @Test
  fun backFindsTheShelfWhereItWasWhenKeepWatchingArrivesAfterHomeCameBack() {
    // After a long watch Home's state flows start again on the way back: "Keep watching" arrives a few frames later.
    start(HomeSamples.populated.copy(keepWatching = null))
    val at = leaveFromTheShelf {}
    comeBack()
    assertThat(top(shelfCard)).isEqualTo(at)
    shown = HomeSamples.populated
    compose.waitForIdle()
    assertThat(exists(HomeTags.block("keep_watching"))).isTrue()
    assertThat(top(shelfCard)).isEqualTo(at)
  }

  @Test
  fun aBlockThatGrowsAboveTheViewWhileHomeShowsLeavesTheShelfInPlace() {
    start(HomeSamples.populated.copy(today = TodayCard.NothingNew))
    showTheShelfCard()
    val at = top(shelfCard)
    // Today's set is above the view, and a sync makes it the full card with its stations.
    assertThat(top(HomeTags.block("today"))).isLessThan(0f)
    val before = scrollValue()
    shown = HomeSamples.populated
    compose.waitForIdle()
    assertThat(scrollValue()).isGreaterThan(before)
    assertThat(top(shelfCard)).isEqualTo(at)
  }

  @Test
  fun atTheEndOfThePageABlockThatGrowsAboveLeavesTheLastBlockInPlace() {
    // The page is at its end: the scroll can only follow the block that grew once the page's new end is measured.
    start(HomeSamples.populated.copy(today = TodayCard.NothingNew))
    compose.onNodeWithTag(HomeTags.PAGE).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, TO_THE_END) }
    compose.waitForIdle()
    val range = compose.onNodeWithTag(HomeTags.PAGE).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    assertThat(range.value()).isEqualTo(range.maxValue())
    val at = top(HomeTags.block("practice"))
    shown = HomeSamples.populated
    compose.waitForIdle()
    assertThat(top(HomeTags.block("practice"))).isEqualTo(at)
  }

  @Test
  fun atTheTopOfThePageWhatGrowsPushesTheRestDown() {
    start(HomeSamples.populated.copy(today = TodayCard.NothingNew))
    val add = top(HomeTags.block("add"))
    shown = HomeSamples.populated
    compose.waitForIdle()
    // The masthead holds the place: the page does not scroll, and the blocks below the grown card move down.
    assertThat(scrollValue()).isEqualTo(0f)
    assertThat(top(HomeTags.block("add"))).isGreaterThan(add)
  }

  private companion object {
    const val HOME = "home"

    /** More than the page's height: a scroll by it stops at the end. */
    const val TO_THE_END = 100_000f
  }
}
