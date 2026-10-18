package com.mapineda48.phonotrainer.feature.home

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId

/** Test tags of :feature:home ("home.<screen>.<element>", work-plan 2.6); part of the test contract. */
object HomeTags {
  /** The scrolling page. */
  const val PAGE = "home.home.page"
  const val SETTINGS = "home.home.settings"
  const val NOTICE = "home.home.notice"
  const val REFRESH = "home.home.refresh"

  /**
   * Each block of F-03 on its root, by its name in [BLOCK_NAMES] ("status", "today", …); their order on screen is F-25
   * list item 3.
   */
  fun block(name: String): String = "home.home.block.$name"

  /** The names of the blocks, in reading order (spec F-03 items 2 to 10, with the first-run, empty and error blocks). */
  val BLOCK_NAMES: List<String> =
    listOf("status", "teaching", "empty", "today", "keep_watching", "add", "new", "error", "shelf", "changes", "practice", "insights")

  const val TEACH_CONNECT = "home.home.teach.connect"
  const val TEACH_LEARN = "home.home.teach.learn"
  const val ERROR_RETRY = "home.home.error.retry"

  /** The Today's set card's summary (its one sentence) and its only control. */
  const val TODAY_CARD = "home.home.today.card"
  const val TODAY_START = "home.home.today.start"
  const val TODAY_ANOTHER = "home.home.today.another"

  const val KEEP_WATCHING = "home.home.keep.action"
  const val ADD_PASTE = "home.home.add.paste"
  const val ADD_FILE = "home.home.add.file"

  /** "Watch" on the new clip [id]. */
  fun newWatch(id: JobId): String = "home.home.new.watch.${id.value}"

  /** The shelf card of [id] (its `ClipCard` is inside). */
  fun shelfCard(id: JobId): String = "home.home.shelf.${id.value}"
  const val SHELF_ROW = "home.home.shelf.row"
  const val SHELF_MENU_WATCH = "home.home.shelf.menu.watch"
  const val SHELF_MENU_OPEN = "home.home.shelf.menu.open"

  /** "All clips" (opens the Library). */
  const val LINK_LIBRARY = "home.home.library"

  /** The Watch by change chip of [label]. */
  fun chip(label: ChangeLabel): String = "home.home.chip.${label.value}"

  const val PRACTICE_START = "home.home.practice.start"
  const val PRACTICE_SAVED = "home.home.practice.saved"
  const val INSIGHTS = "home.home.insights"

  // The status card's controls are tagged by the card itself (`ConnectionTags`, :core:ui).

  /** The tag of [block]'s root. */
  internal fun block(block: HomeBlock): String = block(block.name.lowercase())
}
