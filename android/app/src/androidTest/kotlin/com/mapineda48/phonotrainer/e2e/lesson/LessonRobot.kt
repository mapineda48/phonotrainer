package com.mapineda48.phonotrainer.e2e.lesson

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.lesson.LessonStep
import com.mapineda48.phonotrainer.feature.lesson.LessonTags
import com.mapineda48.phonotrainer.feature.watch.WatchTags

/**
 * The lesson area's robot (work-plan 2.4, e2e/<area>/): it opens a word's lesson the two ways the app does (the sheet
 * over the paused feed, the full page from the Clip page, Learn, Practice and Saved), reads its header and moves
 * through its steps; each action returns once its effect is on screen. The happy path WP-19's flows reuse.
 */
@OptIn(ExperimentalTestApi::class)
class LessonRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  /** A string of the app, with its arguments, as the lesson shows it. */
  fun string(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

  /** The feed in order on [jobId], then the lesson sheet of a word over it, as the feed opens it. */
  fun openOverFeed(jobId: JobId, segment: Int, word: Int): LessonKey {
    phono.open(WatchKey(FeedStart.InOrder(jobId, firstSegment = null, fromStart = true)))
    waitForTag(WatchTags.POSITION)
    return openSheet(jobId, segment, word)
  }

  /**
   * The feed in order on [jobId], playing, its coach marks dismissed as a learner does, then the spotlight slip of the
   * first short tapped: the lesson sheet opens the way the feed opens it, over a playing short (design 9.3).
   */
  fun openFromThePlayingFeed(jobId: JobId) {
    phono.open(WatchKey(FeedStart.InOrder(jobId, firstSegment = null, fromStart = true)))
    waitForFeedPlaying(true)
    repeat(COACH_MARKS) {
      if (compose.onAllNodes(hasTestTag(UiTags.COACH_GOT_IT), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) return@repeat
      compose.onNodeWithTag(UiTags.COACH_GOT_IT, useUnmergedTree = true).performClick()
      compose.waitForIdle()
    }
    tapSlip()
  }

  /** Taps the spotlight slip of the first short (no scrolling: the pager would swing between shorts). */
  fun tapSlip() {
    compose.onNode(hasTestTag(WatchTags.SLIP) and hasAnyAncestor(hasTestTag(WatchTags.page(0)))).performClick()
    compose.waitForIdle()
  }

  /** Waits until the feed's play button says the short is playing ([playing]) or paused. */
  fun waitForFeedPlaying(playing: Boolean) {
    val label = if (playing) "Pause" else "Play"
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      compose.onAllNodes(hasTestTag(WatchTags.PLAY_PAUSE) and hasContentDescription(label), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** The lesson sheet of a word over what is on screen (the feed, as the feed opens it). */
  fun openSheet(jobId: JobId, segment: Int, word: Int): LessonKey {
    val key = LessonKey(jobId, segment, word, LessonOrigin.FEED, LessonPresentation.SHEET)
    phono.open(key)
    phono.assertShown(key)
    return key
  }

  /** The lesson as a page of its own (the Clip page's way). */
  fun openFull(jobId: JobId, segment: Int, word: Int): LessonKey {
    val key = LessonKey(jobId, segment, word, LessonOrigin.CLIP_PAGE, LessonPresentation.FULL)
    phono.open(key)
    phono.assertShown(key)
    return key
  }

  /**
   * Waits until the lesson sheet has settled (its top has not moved for half a second) and returns where its top is,
   * as a fraction of the screen's height: about 0.5 at half height (design 9.9), near 0 fully expanded.
   */
  fun settledSheetTop(): Float {
    val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()
    var last = Float.NaN
    var since = 0L
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      val top = compose.onAllNodes(hasTestTag(LessonTags.ROOT)).fetchSemanticsNodes().firstOrNull()?.positionOnScreen?.y
      val now = SystemClock.uptimeMillis()
      if (top == null || top != last) {
        last = top ?: Float.NaN
        since = now
        false
      } else {
        now - since >= SETTLE_MS
      }
    }
    return last / screenHeight
  }

  /** Waits until the header shows [word] (“water”). */
  fun waitForWord(word: String) {
    val shown = string(com.mapineda48.phonotrainer.feature.lesson.R.string.lesson_word, word)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { text(LessonTags.WORD)?.startsWith(shown) == true }
  }

  /** The words of the node tagged [tag] (merged), or null when it is not on screen. */
  fun text(tag: String): String? = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()
    ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }

  /** Whether [step]'s content is on screen (open). */
  fun isOpen(step: LessonStep): Boolean =
    compose.onAllNodes(hasTestTag(LessonTags.stepBody(step))).fetchSemanticsNodes().isNotEmpty()

  /** Taps [step]'s title and waits until it has opened or closed. */
  fun tapStep(step: LessonStep) {
    val wasOpen = isOpen(step)
    tap(LessonTags.step(step))
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { isOpen(step) != wasOpen }
  }

  /** "Next change", returning once the header shows another word. */
  fun tapNextChange() {
    val before = text(LessonTags.META)
    tap(LessonTags.NEXT_CHANGE)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { text(LessonTags.META).let { it != null && it != before } }
  }

  /** Opens step 5 if needed and starts shadowing; returns once the running card shows its live line. */
  fun startShadowing() {
    if (!isOpen(LessonStep.PRACTICE)) tapStep(LessonStep.PRACTICE)
    tap(ShadowingTags.START)
    waitForTag(ShadowingTags.LINE)
  }

  /** "Stop", returning once "Start shadowing" is back. */
  fun stopShadowing() {
    tap(ShadowingTags.STOP)
    waitForTag(ShadowingTags.START)
  }

  /** Scrolls the node tagged [tag] into view and taps it. */
  fun tap(tag: String) {
    waitForTag(tag)
    compose.onNodeWithTag(tag).performScrollTo().performClick()
    compose.waitForIdle()
  }

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

  private companion object {
    /** The Watch coach marks of a first visit (spec F-01 item 4). */
    const val COACH_MARKS = 4

    /** How long the sheet's top must stay put to count as settled. */
    const val SETTLE_MS = 500L
  }
}
