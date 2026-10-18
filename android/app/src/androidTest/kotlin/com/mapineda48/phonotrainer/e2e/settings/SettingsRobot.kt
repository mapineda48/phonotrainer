package com.mapineda48.phonotrainer.e2e.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.home.HomeTags
import com.mapineda48.phonotrainer.feature.settings.SettingsTags

/**
 * The Settings area's robot (work-plan WP-24; architecture 21.5): Settings and its sub-pages driven through their test
 * tags and string resources, on top of the façade [PhonoRobot]. Every action returns once its effect is on screen.
 * WP-19's flows reuse it: open Settings from Home, open a sub-page, forget the computer, remove a clip from Storage.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext

  /** The words of one of the app's string resources. */
  fun words(id: Int, vararg args: Any): String = app.getString(id, *args)

  /** Settings from Home's masthead, as a learner opens it. */
  fun openFromHome(): SettingsRobot {
    phono.tap(HomeTags.SETTINGS)
    phono.assertShown(SettingsKey)
    return this
  }

  fun waitForTag(tag: String, timeoutMs: Long = PhonoRobot.TIMEOUT_MS) {
    compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)
  }

  fun has(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

  /** Scrolls the page list tagged [list] to the node tagged [tag]. */
  fun scrollTo(list: String, tag: String) {
    waitForTag(list)
    compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
  }

  /** Scrolls [list] to the node tagged [tag] and taps it. */
  fun tapIn(list: String, tag: String) {
    scrollTo(list, tag)
    compose.onNodeWithTag(tag).performClick()
    compose.waitForIdle()
  }

  /** Taps the clickable node with [text] inside the node tagged [group] (one option of a segmented control). */
  fun choose(list: String, group: String, text: String) {
    scrollTo(list, group)
    compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(group))).performClick()
    compose.waitForIdle()
  }

  /** Whether the option [text] of [group] is the selected one. */
  fun isSelected(group: String, text: String): Boolean =
    compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(group))).fetchSemanticsNode()
      .config.getOrNull(SemanticsProperties.Selected) == true

  /** Every text and content description of the node tagged [tag] and of the nodes inside it. */
  fun text(tag: String): String =
    compose.onAllNodes(hasTestTag(tag) or SemanticsMatcher("inside $tag") { it.hasAncestorTagged(tag) }, useUnmergedTree = true)
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

  // --- Confirmations ---

  /** A confirmation is open with [title] and [body]; "Cancel" has the input focus (design 9.23). */
  fun assertDialog(titleTag: String, title: String, bodyTag: String, body: String, cancelTag: String) {
    compose.waitUntilAtLeastOneExists(isDialog(), PhonoRobot.TIMEOUT_MS)
    compose.onNode(hasTestTag(titleTag) and hasText(title)).assertIsDisplayed()
    compose.onNode(hasTestTag(bodyTag) and hasText(body)).assertIsDisplayed()
    compose.onNodeWithTag(cancelTag).assertIsDisplayed()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      compose.onNodeWithTag(cancelTag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    }
  }

  /** Taps [tag] in the open dialog and waits until it is gone. */
  fun answerDialog(tag: String) {
    phono.tap(tag)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
  }

  // --- Your computer ---

  /** "Forget this computer…", confirmed. */
  fun forgetComputer() {
    tapIn(SettingsTags.COMPUTER_LIST, SettingsTags.COMPUTER_FORGET)
    answerDialog(SettingsTags.FORGET_CONFIRM)
    waitForTag(SettingsTags.COMPUTER_NONE)
  }
}
