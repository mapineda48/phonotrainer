package com.mapineda48.phonotrainer.e2e.watch

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.feature.watch.WatchTags

/**
 * The Watch area's robot (work-plan 2.4, e2e/<area>/): it opens the feed through the real shell and moves it the
 * three ways a learner can (swipe, the header's buttons, TalkBack's custom actions), each returning once the header
 * shows the new position. The happy path WP-19's flows reuse.
 */
private const val WORD_PREFIX = "watch.card.word."
private const val COACH_MARKS = 4

@OptIn(ExperimentalTestApi::class)
class WatchRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  /** Opens one clip in order from its first short (the Clip page's "Watch from the start"). */
  fun openInOrder(jobId: JobId): WatchRobot {
    phono.open(WatchKey(FeedStart.InOrder(jobId, firstSegment = null, fromStart = true)))
    waitForPosition(1)
    return this
  }

  /** The header's position ("4 / 15"), or null while there is none. */
  fun position(): String? = compose.onAllNodes(hasTestTag(WatchTags.POSITION), useUnmergedTree = true).fetchSemanticsNodes()
    .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

  /** Waits until the header reads short [number]. */
  fun waitForPosition(number: Int) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { position()?.startsWith("$number /") == true }
  }

  fun swipeToNext(): WatchRobot = move(+1) { compose.onNodeWithTag(WatchTags.PAGER).performTouchInput { swipeUp() } }

  fun tapNext(): WatchRobot = move(+1) { compose.onNodeWithTag(WatchTags.NEXT).performClick() }

  fun tapPrevious(): WatchRobot = move(-1) { compose.onNodeWithTag(WatchTags.PREVIOUS).performClick() }

  /** TalkBack's custom action [label] on the card ("Next short", "Previous short"). */
  fun cardAction(label: String, step: Int): WatchRobot = move(step) {
    val node = compose.onNode(hasTestTag(WatchTags.MEDIA) and onCurrentPage()).fetchSemanticsNode()
    val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
    compose.runOnIdle { action.action() }
  }

  private fun move(step: Int, action: () -> Unit): WatchRobot {
    val now = current()
    action()
    waitForPosition(now + step)
    return this
  }

  /** The number of the short on screen. */
  fun current(): Int = position()?.substringBefore(" /")?.trim()?.toIntOrNull() ?: error("no short on screen")

  /** The number of shorts of the feed. */
  fun total(): Int = position()?.substringAfter("/ ")?.trim()?.toIntOrNull() ?: error("no short on screen")

  /** Waits until the play/pause disc says the short is playing (true) or paused (false). */
  fun waitForPlaying(playing: Boolean) {
    val label = if (playing) "Pause" else "Play"
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      compose.onAllNodes(hasTestTag(WatchTags.PLAY_PAUSE) and contentDescription(label), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** Waits for [tag] to be on screen (unmerged tree: lines inside the card are part of its picture node). */
  fun waitForTag(tag: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  fun waitForNoTag(tag: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }
  }

  fun waitForText(text: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * Taps a word of the phrase being said (a caption button of the current phrase) and returns it as
   * `(segment, word)`.
   */
  fun tapCurrentWord(): Pair<Int, Int> {
    val matcher = SemanticsMatcher("a word of the current phrase") { node ->
      node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(WORD_PREFIX) == true
    } and hasAnyAncestor(hasTestTag(WatchTags.CAPTION_CURRENT)) and onCurrentPage()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    val tag = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().fetchSemanticsNode().config[SemanticsProperties.TestTag]
    // No performScrollTo: it scrolls every scrollable ancestor until the word is inside each viewport, and a word that
    // is a 48 dp target in a tight caption area never is, so it swings the pager between shorts forever. The first
    // word of a phrase is always in view (the captions follow the word being said, design 9.4).
    compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    val (segment, word) = tag.removePrefix(WORD_PREFIX).split('.').map { it.toInt() }
    return segment to word
  }

  /** Taps the spotlight slip of the short on screen (it opens the lesson on the studied word, design 9.3). */
  fun tapSlip() {
    compose.onNode(hasTestTag(WatchTags.SLIP) and onCurrentPage()).performClick()
  }

  /** Dismisses the coach marks one by one ("Got it"), as a learner does on the first visit (spec F-01 item 4). */
  fun dismissCoachMarks() {
    repeat(COACH_MARKS) {
      if (compose.onAllNodes(hasTestTag(UiTags.COACH_GOT_IT), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) return
      compose.onNodeWithTag(UiTags.COACH_GOT_IT, useUnmergedTree = true).performClick()
      compose.waitForIdle()
    }
  }

  /** Inside the pager page of the short on screen (In order: page n − 1 for short n), not a neighbour still composed. */
  private fun onCurrentPage(): SemanticsMatcher = hasAnyAncestor(hasTestTag(WatchTags.page(current() - 1)))

  private fun contentDescription(value: String) = SemanticsMatcher("contentDescription = $value") { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
  }
}
