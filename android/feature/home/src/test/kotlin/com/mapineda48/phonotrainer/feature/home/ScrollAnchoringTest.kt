package com.mapineda48.phonotrainer.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The rule that keeps the learner's place on Home when items above the ones on screen change (spec 5.2, F-03-AC8). */
class ScrollAnchoringTest {
  /** The view of the F-03-AC8 failure: Today's set ends just inside the top, then "Add a clip", "New", the shelf. */
  private val tapped = listOf(
    PlacedItem("masthead", 0),
    PlacedItem("STATUS", 300),
    PlacedItem("TODAY", 790),
    PlacedItem("ADD", 1750),
    PlacedItem("NEW", 2430),
    PlacedItem("SHELF", 2950),
    PlacedItem("CHANGES", 3870),
  )

  private fun tops(vararg items: Pair<String, Int>) = items.toMap()

  @Test
  fun theAnchorIsTheFirstItemWhoseTopIsInTheView() {
    // Today's set shows its last pixels at the top; "Add a clip" is the first item that starts in the view.
    assertThat(ScrollAnchoring.anchor(tapped, viewTop = 1700, viewHeight = 2200)).isEqualTo(3)
    // At the top of the page the masthead holds the place, so whatever grows below it pushes the rest down.
    assertThat(ScrollAnchoring.anchor(tapped, viewTop = 0, viewHeight = 2200)).isEqualTo(0)
  }

  @Test
  fun anItemTallerThanTheViewHoldsThePlaceWhenNoItemStartsInIt() {
    // The view is inside "Watch by change": the next item starts below the view, so the item across the top holds it.
    val items = tapped + PlacedItem("PRACTICE", 6400)
    assertThat(ScrollAnchoring.anchor(items, viewTop = 4000, viewHeight = 2200)).isEqualTo(6)
    // Past the last item's top, the last item holds it; with no item at all, nothing does.
    assertThat(ScrollAnchoring.anchor(items, viewTop = 7000, viewHeight = 2200)).isEqualTo(7)
    assertThat(ScrollAnchoring.anchor(emptyList(), viewTop = 0, viewHeight = 2200)).isNull()
  }

  @Test
  fun keepWatchingAddedAboveTheAnchorScrollsThePageByItsHeight() {
    // F-03-AC8: "Keep watching" (1075 px and a 53 px gap) appears between Today's set and "Add a clip" while Home is
    // away. The page scrolls by as much, so "Add a clip", "New" and the shelf stay where the learner left them.
    val back = tops(
      "masthead" to 0, "STATUS" to 300, "TODAY" to 790, "KEEP_WATCHING" to 1750,
      "ADD" to 2878, "NEW" to 3558, "SHELF" to 4078, "CHANGES" to 4998,
    )
    assertThat(ScrollAnchoring.shift(tapped, back, viewTop = 1700, viewHeight = 2200)).isEqualTo(1128)
  }

  @Test
  fun aBlockThatGrowsAboveTheViewScrollsThePageAndOneThatGrowsInsideItDoesNot() {
    // Today's set grows by 402 px while only its last pixels are on screen: the view follows "Add a clip".
    val todayGrew = tapped.associate { it.key to if (it.top > 790) it.top + 402 else it.top }
    assertThat(ScrollAnchoring.shift(tapped, todayGrew, viewTop = 1700, viewHeight = 2200)).isEqualTo(402)
    // "New" grows inside the view: the anchor above it stays, so nothing scrolls and the shelf moves down on screen.
    val newGrew = tapped.associate { it.key to if (it.top > 2430) it.top + 419 else it.top }
    assertThat(ScrollAnchoring.shift(tapped, newGrew, viewTop = 1700, viewHeight = 2200)).isEqualTo(0)
  }

  @Test
  fun whenTheAnchorGoesTheNextItemThatWasInTheViewKeepsItsPlace() {
    // "Add a clip" is the anchor and goes: "New" takes its place, 680 px higher now.
    val gone = tops("masthead" to 0, "STATUS" to 300, "TODAY" to 790, "NEW" to 1750, "SHELF" to 2270, "CHANGES" to 3190)
    assertThat(ScrollAnchoring.shift(tapped, gone, viewTop = 1700, viewHeight = 2200)).isEqualTo(-680)
    // Nothing that was in the view is left: nothing scrolls.
    val nothingLeft = tops("masthead" to 0, "STATUS" to 300, "TODAY" to 790)
    assertThat(ScrollAnchoring.shift(tapped, nothingLeft, viewTop = 1700, viewHeight = 2200)).isEqualTo(0)
  }

  @Test
  fun aFirstMeasureAndAnUnchangedPageScrollNothing() {
    assertThat(ScrollAnchoring.shift(emptyList(), tapped.associate { it.key to it.top }, viewTop = 0, viewHeight = 2200)).isEqualTo(0)
    assertThat(ScrollAnchoring.shift(tapped, tapped.associate { it.key to it.top }, viewTop = 1700, viewHeight = 2200)).isEqualTo(0)
  }
}
