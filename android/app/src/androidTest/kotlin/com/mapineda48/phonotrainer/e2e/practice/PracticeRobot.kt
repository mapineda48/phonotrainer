package com.mapineda48.phonotrainer.e2e.practice

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.ui.component.QuestionTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.practice.PracticeTags

/**
 * The Practice area's robot (work-plan WP-21, 2.4; architecture 21.5): the Practice tab and Saved driven through their
 * test tags on top of the façade [PhonoRobot]. Every action returns once its effect is on screen. The happy path
 * WP-19's flows reuse: open the tab, start, answer, next, read the results.
 */
@OptIn(ExperimentalTestApi::class)
class PracticeRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  /** The Practice tab, from the bottom bar. */
  fun openTab(): PracticeRobot {
    phono.tab(Tab.PRACTICE)
    phono.assertShown(PracticeKey())
    return this
  }

  fun waitForTag(tag: String, timeoutMs: Long = PhonoRobot.TIMEOUT_MS) {
    compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)
  }

  fun waitForNoTag(tag: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
  }

  fun has(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

  /** Scrolls to the node tagged [tag] and taps it. */
  fun tap(tag: String) {
    waitForTag(tag)
    compose.onNodeWithTag(tag).performScrollTo().performClick()
    compose.waitForIdle()
  }

  /** The words of the node tagged [tag] and of everything inside it, text and descriptions. */
  fun text(tag: String): String =
    compose.onAllNodes(hasTestTag(tag) or SemanticsMatcher("inside $tag") { node -> node.hasAncestorTagged(tag) }, useUnmergedTree = true)
      .fetchSemanticsNodes()
      .flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
          node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
      }
      .distinct()
      .joinToString(" ")

  private fun SemanticsNode.hasAncestorTagged(tag: String): Boolean {
    var node = parent
    while (node != null) {
      if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return true
      node = node.parent
    }
    return false
  }

  /** Waits for the start screen (the corpus may still be indexing the clip that sync just brought). */
  fun awaitStart() {
    waitForTag(PracticeTags.START, INDEX_TIMEOUT_MS)
  }

  /** "Start practicing", then the first question. */
  fun start() {
    awaitStart()
    tap(PracticeTags.START)
    waitForTag(QuestionTags.PROMPT, INDEX_TIMEOUT_MS)
  }

  /** "Question 3 of 10". */
  fun position(): String = text(PracticeTags.POSITION)

  /** The option ids of the question on screen, in order. */
  fun optionIds(): List<String> = compose.onAllNodes(OPTION).fetchSemanticsNodes()
    .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix(OPTION_PREFIX) }

  /** Answers the question on screen with option [id] and waits for the live line. */
  fun answer(id: String = optionIds().first()) {
    tap(QuestionTags.option(id))
    waitForTag(QuestionTags.LIVE)
  }

  /** "Next question" (or "See your results"), and waits for what follows. */
  fun next() {
    tap(PracticeTags.NEXT)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { !has(QuestionTags.LIVE) || has(PracticeTags.RESULTS) }
  }

  /** Answers every question left with its first option and goes on to the results. */
  fun finishSession() {
    while (!has(PracticeTags.RESULTS)) {
      waitForTag(QuestionTags.PROMPT)
      answer()
      next()
    }
    waitForTag(PracticeTags.SCORE)
  }

  private companion object {
    const val OPTION_PREFIX = "ui.question.option."

    /** A sync pass hands the clip over before the indexer has cut it into occurrences (WP-08, WP-10). */
    const val INDEX_TIMEOUT_MS = 30_000L

    val OPTION = SemanticsMatcher("an option of the quiz card") { node ->
      val tag = node.config.getOrNull(SemanticsProperties.TestTag)
      tag != null && tag.startsWith(OPTION_PREFIX) && !tag.endsWith(".mark")
    }
  }
}
