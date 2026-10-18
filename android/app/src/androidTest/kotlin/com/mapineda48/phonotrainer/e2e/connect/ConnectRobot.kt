package com.mapineda48.phonotrainer.e2e.connect

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.connect.ConnectTags
import com.mapineda48.phonotrainer.feature.onboarding.OnboardingTags
import java.util.regex.Pattern

/**
 * The robot of the Welcome, Connect and Connection details (work-plan WP-13; architecture 21.5), on top of the façade
 * [PhonoRobot]. Every action returns once its effect is on screen. WP-19's flows reuse it: walk the Welcome, pair with
 * the fake computer as a learner does (the computer found on the Wi-Fi, or its address typed), read Connection details.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectRobot(private val compose: ComposeTestRule, val phono: PhonoRobot) {
  private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext

  // --- Welcome ---

  fun waitForWelcome(): ConnectRobot {
    phono.waitForTag(OnboardingTags.WELCOME)
    return this
  }

  /** The Welcome's kicker and title as read together ("1 OF 3 Hear English as it's really spoken"). */
  fun welcomeTitle(): String = text(OnboardingTags.PAGE_TITLE)

  fun next() = phono.tap(OnboardingTags.NEXT)

  fun skip() = phono.tap(OnboardingTags.SKIP)

  fun getStarted() = phono.tap(OnboardingTags.GET_STARTED)

  /** Whether a runtime permission dialog of the system is on screen (none may appear before Connect, F-01-AC1). */
  fun permissionDialogShown(): Boolean = phono.device.hasObject(By.pkg(PERMISSION_CONTROLLER))

  // --- Connect ---

  fun waitForConnect(): ConnectRobot {
    phono.waitForTag(ConnectTags.PAGE)
    return this
  }

  /** Scrolls Connect (or Connection details, [list]) to the node tagged [tag]. */
  fun scrollTo(tag: String, list: String = ConnectTags.PAGE) {
    phono.waitForTag(list)
    compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
    compose.waitForIdle()
  }

  /** Scrolls to [tag] and taps it. */
  fun tapIn(tag: String, list: String = ConnectTags.PAGE) {
    scrollTo(tag, list)
    compose.onNodeWithTag(tag).performClick()
    compose.waitForIdle()
  }

  fun notNow() = tapIn(ConnectTags.NOT_NOW)

  /** The row of the computer with [serverId] answering at [address] (no address is shown; the tag carries it). */
  fun rowTag(serverId: ServerId, address: HostPort): String = ConnectTags.row("${serverId.value}@${address.host}:${address.port}")

  /** Waits for the computer's row (discovery answers within the 10 s window, F-02-AC1). */
  fun waitForRow(serverId: ServerId, address: HostPort) {
    compose.waitUntilAtLeastOneExists(hasTestTag(rowTag(serverId, address)), SEARCH_TIMEOUT_MS)
  }

  fun isSelected(tag: String): Boolean =
    compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

  /** "Enter the address instead", the address typed as the computer prints it, and "Check". */
  fun checkAddress(address: HostPort) {
    tapIn(ConnectTags.ENTER_ADDRESS)
    scrollTo(ConnectTags.ADDRESS)
    // The tag sits on the field's frame; the text goes to the editable node inside it.
    compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ConnectTags.ADDRESS)))
      .performTextReplacement("${address.host}:${address.port}")
    tapIn(ConnectTags.CHECK)
  }

  /**
   * Types [code] into the cells (as a paste does) and taps "Connect". The code section is far down the page, so the
   * list is scrolled to it (a lazy list composes only what is on screen).
   */
  fun enterCodeAndConnect(code: String) {
    scrollTo(ConnectTags.CODE)
    compose.onNodeWithTag(ConnectTags.CODE).performTextReplacement(code)
    compose.waitForIdle()
    tapIn(ConnectTags.CONNECT)
  }

  /** Waits until the node tagged [tag] says [words], scrolling the page to it each time (it may be below the fold). */
  fun waitForWords(tag: String, words: String, list: String = ConnectTags.PAGE, timeoutMs: Long = PAIR_TIMEOUT_MS) {
    compose.waitUntil(timeoutMs) {
      runCatching {
        scrollTo(tag, list)
        text(tag).contains(words)
      }.getOrDefault(false)
    }
  }

  fun waitForSuccess() {
    compose.waitUntilAtLeastOneExists(hasTestTag(ConnectTags.SUCCESS), PAIR_TIMEOUT_MS)
  }

  /** Whether the code's cells have the input focus (F-02-AC3: they keep it after a wrong code). */
  fun codeFocused(): Boolean =
    compose.onNodeWithTag(ConnectTags.CODE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

  // --- Reading ---

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

  /** What TalkBack reads of the node tagged [tag] (its merged words). */
  fun spoken(tag: String): String {
    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
    return (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
      node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()).joinToString(" ")
  }

  /** The text on the clipboard, read in the app's own process while it is in front. */
  fun clipboardText(): String {
    var text = ""
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
      text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
    }
    return text
  }

  fun words(id: Int, vararg args: Any): String = app.getString(id, *args)

  private fun SemanticsNode.hasAncestorTagged(tag: String): Boolean {
    var node = parent
    while (node != null) {
      if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return true
      node = node.parent
    }
    return false
  }

  companion object {
    /** The discovery window is 10 s (spec 7.3); the fake computer answers at once, so this is a generous bound. */
    const val SEARCH_TIMEOUT_MS = 15_000L

    /** Pairing is a hello, the pair call, a proof and a signed health against the in-process computer. */
    const val PAIR_TIMEOUT_MS = 20_000L

    /** The system's permission dialogs (AOSP and Google builds). */
    val PERMISSION_CONTROLLER: Pattern = Pattern.compile(".*permissioncontroller.*")
  }
}
