package com.mapineda48.phonotrainer.e2e.home

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.ui.component.ClipCardTags
import com.mapineda48.phonotrainer.core.ui.component.ConnectionTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.home.HomeTags

/**
 * The Home area's robot (work-plan 2.4, `e2e/<area>/`): it waits for Home's blocks, scrolls to a control before it
 * taps it (Home is one scrolling page), reads the status card's sentence, a chip's short count, the page's scroll
 * position and where a shelf card is on screen, and plays a shelf clip the way TalkBack and the menu do. WP-19's flows
 * reuse it.
 */
@OptIn(ExperimentalTestApi::class)
class HomeRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext

  fun waitForHome(): HomeRobot {
    phono.waitForTag(HomeTags.PAGE)
    return this
  }

  /** Scrolls Home until [tag] is on screen, then taps it. */
  fun tap(tag: String) {
    phono.waitForTag(tag)
    compose.onNodeWithTag(tag).performScrollTo()
    compose.onNodeWithTag(tag).performClick()
    compose.waitForIdle()
  }

  fun scrollTo(tag: String) {
    phono.waitForTag(tag)
    compose.onNodeWithTag(tag).performScrollTo()
    compose.waitForIdle()
  }

  /** The status card's one sentence (design 9.17). */
  fun statusSentence(): String = compose.onNodeWithTag(ConnectionTags.STATUS).fetchSemanticsNode()
    .config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

  /** Waits until the status card's sentence starts with [words]. */
  fun waitForStatus(words: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { runCatching { statusSentence().startsWith(words) }.getOrDefault(false) }
  }

  /** Waits until [text] is shown somewhere on Home. */
  fun waitForText(text: String, substring: Boolean = false) {
    compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), PhonoRobot.TIMEOUT_MS)
  }

  /** The page's scroll position, in pixels. */
  fun scrollPosition(): Float = compose.onNodeWithTag(HomeTags.PAGE).fetchSemanticsNode()
    .config[SemanticsProperties.VerticalScrollAxisRange].value()

  /** The short count a chip shows ("Flapping · 34"), or 0 for "Not in your clips yet". */
  fun chipCount(label: ChangeLabel): Int {
    val node = compose.onNodeWithTag(HomeTags.chip(label)).fetchSemanticsNode()
    val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    return words.substringAfterLast(" · ", "").trim().toIntOrNull() ?: 0
  }

  /** Waits until some chip shows a short count and returns its label and count. */
  fun firstCountedChip(labels: List<ChangeLabel>): Pair<ChangeLabel, Int> {
    var found: Pair<ChangeLabel, Int>? = null
    compose.waitUntil(SYNC_TIMEOUT_MS) {
      found = labels.firstNotNullOfOrNull { label -> runCatching { chipCount(label) }.getOrNull()?.takeIf { it > 0 }?.let { label to it } }
      found != null
    }
    return checkNotNull(found)
  }

  private fun shelfCard(id: JobId) = hasTestTag(ClipCardTags.OPEN) and hasAnyAncestor(hasTestTag(HomeTags.shelfCard(id)))

  /**
   * Scrolls Home until the shelf card of [id] is on screen (it waits for the clip to reach the shelf): the page first,
   * to the shelf block (the card's nearest scrolling parent is the shelf's own row), then the row to the card.
   */
  fun scrollToShelf(id: JobId) {
    compose.waitUntilAtLeastOneExists(shelfCard(id), SYNC_TIMEOUT_MS)
    compose.onNodeWithTag(HomeTags.block("shelf")).performScrollTo()
    compose.onNode(shelfCard(id)).performScrollTo()
    compose.waitForIdle()
  }

  /** The top of the shelf card of [id] on screen, in pixels (where the learner sees it; off screen it is outside). */
  fun shelfCardTop(id: JobId): Float = compose.onNode(shelfCard(id)).fetchSemanticsNode().positionInRoot.y

  /**
   * "Watch" on the shelf card of [id] (F-03-AC8's play action: the card's custom action, as TalkBack offers it), after
   * scrolling the card on screen. Returns where the page and the card were at the tap, read on the main thread in the
   * same turn as the action, so no frame falls between them: Back must show the card there. A reading taken before this
   * call may not be: Home's blocks above the shelf keep filling in after a sync (today's set, "New on this phone", the
   * status card), and the scroll to the card follows them.
   */
  fun watchFromShelf(id: JobId): ShelfTap {
    scrollToShelf(id)
    val page = compose.onNodeWithTag(HomeTags.PAGE).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    val card = compose.onNode(shelfCard(id)).fetchSemanticsNode()
    val label = app.getString(com.mapineda48.phonotrainer.core.ui.R.string.clip_watch)
    val watch = card.config[SemanticsActions.CustomActions].single { it.label == label }
    var tap: ShelfTap? = null
    compose.runOnIdle {
      tap = ShelfTap(scroll = page.value(), cardTop = card.positionInRoot.y)
      watch.action()
    }
    compose.waitForIdle()
    return checkNotNull(tap)
  }

  /** Waits until Home shows "Keep watching" (the last feed position is not today's set, F-03 item 4). */
  fun waitForKeepWatching() {
    compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.block("keep_watching")), PhonoRobot.TIMEOUT_MS)
  }

  /** Waits until the shelf card of [id] is on Home again (after Back from its feed). */
  fun waitForShelfCard(id: JobId) {
    compose.waitUntilAtLeastOneExists(shelfCard(id), PhonoRobot.TIMEOUT_MS)
  }

  /** Waits until "New on this phone" shows the card of [id] (it waits for the clip to reach the phone). */
  fun waitForNew(id: JobId) {
    compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.newWatch(id)), SYNC_TIMEOUT_MS)
  }

  /** The key on top of [tab]'s stack. */
  fun top(tab: Tab): NavKey {
    var key: NavKey? = null
    phono.onNavigator { key = it.stack(tab).last() }
    return checkNotNull(key)
  }

  /** Where the page ([scroll], its scroll value) and the tapped shelf card ([cardTop], its top on screen) were at a tap. */
  data class ShelfTap(val scroll: Float, val cardTop: Float)

  companion object {
    /** Waits that include a sync pass and the indexer. */
    const val SYNC_TIMEOUT_MS = 30_000L
  }
}
