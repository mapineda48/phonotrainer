package com.mapineda48.phonotrainer.e2e.library

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.ClipTab
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.ui.component.ClipCardTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.library.LibraryTags
import com.mapineda48.phonotrainer.feature.library.R
import com.mapineda48.phonotrainer.feature.library.RowMenuItem
import java.util.Collections
import kotlin.math.abs

/**
 * The Library area's robot (work-plan WP-16; architecture 21.5): the Library (P-01) and the Clip page (P-02) driven
 * through their test tags and string resources, on top of the façade [PhonoRobot]. Every action returns after its
 * effect is visible. WP-19's cross-area flows call it once the library is seeded through `TestSeeder`.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext

  /** The words of one of :feature:library's string resources. */
  fun words(id: Int, vararg args: Any): String = app.getString(id, *args)

  /** The words of one of :feature:library's plurals. */
  fun plural(id: Int, count: Int, vararg args: Any): String = app.resources.getQuantityString(id, count, *args)

  /** Taps the button with the words of [label] inside the node tagged [container] (an empty state's action). */
  fun tapAction(container: String, label: Int) {
    val button = hasText(words(label)) and hasClickAction() and hasAnyAncestor(hasTestTag(container))
    compose.waitUntilAtLeastOneExists(button, PhonoRobot.TIMEOUT_MS)
    compose.onNode(button).performClick()
    compose.waitForIdle()
  }

  // --- The Library ---

  /** Opens the Library on the current tab's stack, as Home's "All clips" does, and waits for it. */
  fun open(): LibraryRobot {
    phono.open(LibraryKey)
    phono.assertShown(LibraryKey)
    return this
  }

  /** Waits until the catalogue has arrived (the list and its live count show). */
  fun awaitRows() {
    compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.COUNT), PhonoRobot.TIMEOUT_MS)
  }

  private val searchField: SemanticsMatcher = hasSetTextAction() and hasAnyAncestor(hasTestTag(LibraryTags.SEARCH))

  fun search(text: String) {
    compose.onNodeWithTag(LibraryTags.LIST).performScrollToNode(hasTestTag(LibraryTags.SEARCH))
    compose.onNode(searchField).performTextInput(text)
    compose.waitUntilAtLeastOneExists(searchField and hasText(text), PhonoRobot.TIMEOUT_MS)
  }

  /** Taps the filter chip of [filter] and waits until it is selected. */
  fun filter(filter: ClipFilter) {
    compose.onNodeWithTag(LibraryTags.LIST).performScrollToNode(hasTestTag(LibraryTags.filter(filter)))
    phono.tap(LibraryTags.filter(filter))
    compose.onNodeWithTag(LibraryTags.filter(filter)).assertIsSelected()
  }

  /** Waits until the live count reads [shown] of [total] clips ("40 of 50 clips"). */
  fun awaitCount(shown: Int, total: Int) {
    val words = plural(R.plurals.library_count_of, total, shown, total)
    compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.COUNT) and hasText(words), PhonoRobot.TIMEOUT_MS)
  }

  /** Scrolls the list until the row of [id] shows. */
  fun scrollTo(id: JobId) {
    compose.onNodeWithTag(LibraryTags.LIST).performScrollToNode(hasTestTag(LibraryTags.row(id)))
    compose.onNodeWithTag(LibraryTags.row(id)).assertIsDisplayed()
  }

  /** Where the row of [id] is on screen: the same value means the same scroll position. */
  fun rowTop(id: JobId): Float = compose.onNodeWithTag(LibraryTags.row(id)).fetchSemanticsNode().boundsInRoot.top

  /** Opens the row's "More actions" menu and picks [item]. */
  fun menu(id: JobId, item: RowMenuItem) {
    compose.onNode(hasTestTag(ClipCardTags.MORE) and hasAnyAncestor(hasTestTag(LibraryTags.row(id)))).performClick()
    phono.tap(LibraryTags.menu(item))
  }

  /**
   * Opens the row's menu and picks [item] the way a keyboard or switch user does: through the nodes' click actions,
   * with no touch event, so the phone stays out of touch mode.
   */
  fun menuWithoutTouch(id: JobId, item: RowMenuItem) {
    compose.onNode(hasTestTag(ClipCardTags.MORE) and hasAnyAncestor(hasTestTag(LibraryTags.row(id))))
      .performSemanticsAction(SemanticsActions.OnClick)
    compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.menu(item)), PhonoRobot.TIMEOUT_MS)
    compose.onNodeWithTag(LibraryTags.menu(item)).performSemanticsAction(SemanticsActions.OnClick)
    compose.waitForIdle()
  }

  /** "Watch" on the row of [id] (its menu; TalkBack offers the same as an action of the row). */
  fun watch(id: JobId) = menu(id, RowMenuItem.WATCH)

  /** Taps the row of [id], which opens its Clip page. */
  fun openClip(id: JobId) {
    compose.onNode(hasTestTag(ClipCardTags.OPEN) and hasAnyAncestor(hasTestTag(LibraryTags.row(id)))).performClick()
    compose.waitForIdle()
  }

  /** Matches every clip row (tagged by clip id, [LibraryTags.row]). */
  private val anyRow = SemanticsMatcher("is a clip row") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(ROW_PREFIX) == true
  }

  /** The clips of the rows on screen, top to bottom. */
  fun shownRows(): List<JobId> = compose.onAllNodes(anyRow).fetchSemanticsNodes()
    .sortedBy { it.boundsInRoot.top }
    .map { JobId(it.config[SemanticsProperties.TestTag].removePrefix(ROW_PREFIX)) }

  /**
   * The Library shows what the learner left, without scrolling: the row of [id] at [top] (within a pixel: the same
   * scroll position) among rows that are a run of [order], the list that search, filter and sort give.
   */
  fun assertKept(order: List<JobId>, id: JobId, top: Float) {
    phono.assertShown(LibraryKey)
    compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.row(id)), PhonoRobot.TIMEOUT_MS)
    compose.onNodeWithTag(LibraryTags.row(id)).assertIsDisplayed()
    assertThat(abs(rowTop(id) - top)).isAtMost(1f)
    val rows = shownRows()
    assertThat(rows).contains(id)
    assertThat(Collections.indexOfSubList(order, rows)).isAtLeast(0)
  }

  /** Scrolls up to the controls: [search] in the field, [filter] selected, and the count "[shown] of [total] clips". */
  fun assertControls(search: String, filter: ClipFilter, shown: Int, total: Int) {
    compose.onNodeWithTag(LibraryTags.LIST).performScrollToNode(hasTestTag(LibraryTags.SEARCH))
    compose.waitUntilAtLeastOneExists(searchField and hasText(search), PhonoRobot.TIMEOUT_MS)
    compose.onNodeWithTag(LibraryTags.LIST).performScrollToNode(hasTestTag(LibraryTags.COUNT))
    compose.onNodeWithTag(LibraryTags.filter(filter)).assertIsSelected()
    awaitCount(shown, total)
  }

  /** The tab stacks of Home and of [tab] (the per-tab proofs' premise). */
  fun stacks(tab: Tab): Pair<List<NavKey>, List<NavKey>> {
    var home = emptyList<NavKey>()
    var owner = emptyList<NavKey>()
    phono.onNavigator {
      home = it.stack(Tab.HOME).toList()
      owner = it.stack(tab).toList()
    }
    return home to owner
  }

  // --- The removal confirmation ---

  /** The confirmation shows with its title for [title]; "Cancel" has the focus. */
  fun assertRemoveDialog(title: String) {
    compose.waitUntilAtLeastOneExists(isDialog(), PhonoRobot.TIMEOUT_MS)
    compose.onNode(hasTestTag(LibraryTags.REMOVE_TITLE) and hasText(words(R.string.library_remove_title, title)))
      .assertIsDisplayed()
    compose.onNode(hasTestTag(LibraryTags.REMOVE_BODY) and hasText(words(R.string.library_remove_body))).assertIsDisplayed()
    compose.onNode(hasTestTag(LibraryTags.REMOVE_CANCEL) and hasText(words(R.string.library_remove_cancel))).assertIsDisplayed()
    compose.onNode(hasTestTag(LibraryTags.REMOVE_CONFIRM) and hasText(words(R.string.library_remove_confirm)))
      .assertIsDisplayed()
  }

  /** "Cancel" has the input focus (a keyboard user lands on it, design 9.23). */
  fun assertCancelFocused() {
    compose.onNodeWithTag(LibraryTags.REMOVE_CANCEL).assertIsFocused()
  }

  fun confirmRemove() {
    phono.tap(LibraryTags.REMOVE_CONFIRM)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
  }

  fun cancelRemove() {
    phono.tap(LibraryTags.REMOVE_CANCEL)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
  }

  // --- The Clip page ---

  /** Waits until the Clip page shows the clip titled [title]. */
  fun awaitClip(title: String) {
    compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.CLIP_TITLE) and hasText(title), PhonoRobot.TIMEOUT_MS)
  }

  fun clipTab(tab: ClipTab) {
    compose.onNodeWithTag(LibraryTags.CLIP_LIST).performScrollToNode(hasTestTag(LibraryTags.tab(tab)))
    phono.tap(LibraryTags.tab(tab))
    compose.onNodeWithTag(LibraryTags.tab(tab)).assertIsSelected()
  }

  /** "Watch" on the Clip page (it resumes). */
  fun clipWatch() {
    compose.onNodeWithTag(LibraryTags.CLIP_LIST).performScrollToNode(hasTestTag(LibraryTags.CLIP_WATCH))
    phono.tap(LibraryTags.CLIP_WATCH)
  }

  /** "Remove from this phone…" in the Summary's "On this phone" block. */
  fun clipRemoveFromPhone() {
    clipTab(ClipTab.SUMMARY)
    compose.onNodeWithTag(LibraryTags.CLIP_LIST).performScrollToNode(hasTestTag(LibraryTags.REMOVE_FROM_PHONE))
    phono.tap(LibraryTags.REMOVE_FROM_PHONE)
  }

  /** The page of a clip this phone does not know: "That clip is no longer on this phone." */
  fun assertClipGone() {
    phono.waitForTag(LibraryTags.CLIP_GONE)
    compose.onNode(hasText(words(R.string.clip_page_gone))).assertIsDisplayed()
  }

  private companion object {
    /** The prefix of every clip row's tag. */
    val ROW_PREFIX: String = LibraryTags.row(JobId(""))
  }
}
