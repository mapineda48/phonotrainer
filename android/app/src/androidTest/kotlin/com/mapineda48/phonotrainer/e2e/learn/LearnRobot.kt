package com.mapineda48.phonotrainer.e2e.learn

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.learn.LearnTags

/**
 * The Learn area's robot (work-plan 2.4, e2e/<area>/): it reaches Learn's pages through the real shell, scrolls their
 * lists to what it needs and reads what they say; each action returns once its effect is on screen. The happy path
 * WP-19's flows reuse: Learn → a change's page → an example → its lesson; the IPA chart → a symbol → its examples;
 * "Words we use" → search.
 */
@OptIn(ExperimentalTestApi::class)
class LearnRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  /** Learn's tab. */
  fun openLearn() {
    phono.tab(Tab.LEARN)
    phono.waitForTag(LearnTags.LIST)
  }

  /** Scrolls [list] until a node tagged [tag] is composed. */
  fun scrollTo(list: String, tag: String) {
    phono.waitForTag(list)
    compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
    compose.waitForIdle()
  }

  fun tap(tag: String) {
    phono.waitForTag(tag)
    compose.onNodeWithTag(tag).performClick()
    compose.waitForIdle()
  }

  /** The words of a change card's count line on Learn ("1 short in your clips"). */
  fun cardCount(label: ChangeLabel): String? {
    scrollTo(LearnTags.LIST, LearnTags.card(label))
    return description(LearnTags.cardCount(label))
  }

  /** Waits until the change card of [label] shows a count, and gives its words. */
  fun awaitCardCount(label: ChangeLabel): String {
    var said: String? = null
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      said = cardCount(label)
      said != null
    }
    return said!!
  }

  /** The text of the node tagged [tag], or null when it is not on screen. */
  fun text(tag: String): String? = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
    ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

  private fun description(tag: String): String? = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
    .firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

  /** Waits until the node tagged [tag] says [expected]. */
  fun awaitText(list: String, tag: String, expected: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      runCatching { scrollTo(list, tag) }
      text(tag) == expected
    }
  }

  /** The [index]-th example of the list tagged [list] shows the word [word]. */
  fun awaitExample(list: String, index: Int, word: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      runCatching { scrollTo(list, LearnTags.example(index)) }
      compose.onAllNodes(hasText(word) and hasAnyAncestor(hasTestTag(LearnTags.example(index))), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** The example's play circle. */
  fun playExample(index: Int) {
    compose.onNode(hasTestTag(OccurrenceTags.PLAY) and hasAnyAncestor(hasTestTag(LearnTags.example(index)))).performClick()
    compose.waitForIdle()
  }

  /** Waits until the example's play circle is labelled [label] ("Stop “water”" while it plays). */
  fun awaitPlayLabel(index: Int, label: String) {
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      compose.onAllNodes(
        hasTestTag(OccurrenceTags.PLAY) and hasAnyAncestor(hasTestTag(LearnTags.example(index))) and hasContentDescription(label),
      ).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** "Open in its lesson" on the example. */
  fun openExampleLesson(index: Int) {
    compose.onNode(hasTestTag(OccurrenceTags.LESSON) and hasAnyAncestor(hasTestTag(LearnTags.example(index)))).performClick()
    compose.waitForIdle()
  }

  /** Types [text] into "Words we use"'s search, replacing what was there. */
  fun search(text: String) {
    val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(LearnTags.GLOSSARY_SEARCH)))
    field.performTextClearance()
    if (text.isNotEmpty()) field.performTextInput(text)
    compose.waitForIdle()
  }

  /** True while a node tagged [tag] exists anywhere (sheets included). */
  fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  fun waitForTag(tag: String) = phono.waitForTag(tag)

  fun waitForNoTag(tag: String) = compose.waitUntil(PhonoRobot.TIMEOUT_MS) { !exists(tag) }
}
