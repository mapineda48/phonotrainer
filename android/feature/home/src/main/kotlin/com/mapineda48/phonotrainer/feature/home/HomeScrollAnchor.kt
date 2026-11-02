package com.mapineda48.phonotrainer.feature.home

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.math.roundToInt

/** One of Home's items where the page last measured it: its key and its top in the page's column, in pixels. */
internal data class PlacedItem(val key: String, val top: Int)

/**
 * Scroll anchoring on Home, the pure rule (spec 5.2 and F-03-AC8: Back from a feed "returns there at the same scroll
 * position"; F-03's story "continue exactly where I stopped"). Home's blocks fill in from the store at their own pace: a
 * sync grows Today's set, the status card or "New on this phone", and watching a clip adds "Keep watching" above the
 * shelf while Home is away. The same scroll value would then show other content, so after a measure the page scrolls by
 * how far the item that holds the learner's place moved, and that item stays where it was on screen.
 *
 * The view's top is the scroll value read in the column's coordinates: the column starts below the status bar's inset,
 * so this is the first row of the page that the status bar does not cover.
 */
internal object ScrollAnchoring {
  /**
   * The index in [items] (the page's items top to bottom) of the item that holds the learner's place in the view from
   * [viewTop] over [viewHeight] pixels: the first item whose top is in the view or, when none starts there (an item
   * taller than the view), the item across the view's top edge. Null when no item reaches the view.
   */
  fun anchor(items: List<PlacedItem>, viewTop: Int, viewHeight: Int): Int? {
    val first = items.indexOfFirst { it.top >= viewTop }
    if (first >= 0 && items[first].top < viewTop + viewHeight) return first
    return items.indexOfLast { it.top < viewTop }.takeIf { it >= 0 }
  }

  /**
   * How far the page must scroll after a measure so that the learner keeps their place: [before] are the items as the
   * view from [viewTop] over [viewHeight] showed them, [after] the tops of the items measured now, by key. The anchor
   * keeps its place on screen; when it went, the next item that was in the view does. Zero when nothing that was in the
   * view is still there.
   */
  fun shift(before: List<PlacedItem>, after: Map<String, Int>, viewTop: Int, viewHeight: Int): Int {
    val anchor = anchor(before, viewTop, viewHeight) ?: return 0
    val kept = before.asSequence().drop(anchor)
      .takeWhile { it.top < viewTop + viewHeight }
      .firstOrNull { it.key in after } ?: return 0
    return after.getValue(kept.key) - kept.top
  }
}

/**
 * Home's scroll anchoring state: the items as the page last measured them and the height of the view, saved with the
 * page, so the first measure after Home comes back from another destination (or after the process was recreated)
 * applies [ScrollAnchoring] as a measure does while Home shows.
 */
@Stable
internal class HomeScrollAnchor(items: List<PlacedItem> = emptyList(), viewHeight: Int = 0) {
  private var items: List<PlacedItem> = items
  private var viewHeight: Int = viewHeight

  /** The part of the last shift that the page's old end held back; [finish] scrolls it once the end is measured. */
  private var held = 0

  /**
   * Called from the column's measure with its items [now]: scrolls [scroll] so that the learner keeps their place, then
   * remembers [now]. It reads the scroll state without observing it, so scrolling never measures the column again.
   */
  fun settle(scroll: ScrollState, now: List<PlacedItem>) {
    val (value, view) = Snapshot.withoutReadObservation { scroll.value to scroll.viewportSize }
    // A state restored after Home was away has not measured its view yet: the saved height is the view the learner saw.
    if (view > 0) viewHeight = view
    val shift = ScrollAnchoring.shift(items, now.associate { it.key to it.top }, value, viewHeight)
    items = now
    if (shift == 0) return
    // The scroll stops at the end of the page as last measured; the page grows with the blocks, so a shift down that
    // the old end stops is finished in the placement, after the scroll has measured the new end.
    val done = scroll.dispatchRawDelta(shift.toFloat()).roundToInt()
    held = if (shift > 0) shift - done else 0
  }

  /** Called from the column's placement: scrolls what [settle] could not before the page's new end was measured. */
  fun finish(scroll: ScrollState) {
    if (held == 0) return
    val rest = held
    held = 0
    scroll.dispatchRawDelta(rest.toFloat())
  }

  companion object {
    /** Saves the view's height and the items' keys and tops, in their order. */
    val Saver: Saver<HomeScrollAnchor, Any> = listSaver(
      save = { anchor -> listOf(anchor.viewHeight, ArrayList(anchor.items.map { it.key }), ArrayList(anchor.items.map { it.top })) },
      restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val keys = saved[1] as List<String>
        @Suppress("UNCHECKED_CAST")
        val tops = saved[2] as List<Int>
        HomeScrollAnchor(keys.zip(tops, ::PlacedItem), saved[0] as Int)
      },
    )
  }
}
