package com.mapineda48.phonotrainer.e2e.requests

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.AddClipStart
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.ui.component.RequestRowTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.requests.R
import com.mapineda48.phonotrainer.feature.requests.RequestsTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The Requests area's robot (work-plan WP-22; architecture 21.5): Requests (P-03), the request detail (P-04) and "Add a
 * clip" (P-05) driven through their test tags and string resources, on top of the façade [PhonoRobot], with the device
 * queue read through the real `RequestRepository`. Every action returns after its effect is visible; waits have
 * timeouts and never sleep. WP-19's cross-area flows use it with the computer stack (a link added here and then sent,
 * F-20-AC1; "Try audio only", F-20-AC3).
 */
@OptIn(ExperimentalTestApi::class)
class RequestsRobot(private val compose: ComposeTestRule, val phono: PhonoRobot, private val requests: RequestRepository) {
  private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext

  /** The words of one of :feature:requests' string resources. */
  fun words(id: Int, vararg args: Any): String = app.getString(id, *args)

  fun openRequests() {
    phono.open(RequestsKey)
    phono.assertShown(RequestsKey)
  }

  fun openAddClip(start: AddClipStart = AddClipStart.CHOOSER) {
    phono.open(AddClipKey(start))
    phono.assertShown(AddClipKey(start))
  }

  /** Puts [text] on the system clipboard, as another app would. */
  fun clipboard(text: String) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("e2e", text))
    }
  }

  /** "Paste" on "Add a clip". */
  fun paste() = phono.tap(RequestsTags.ADD_PASTE)

  /** Types [text] into the link field. */
  fun type(text: String) {
    val field = hasSetTextAction() and hasAnyAncestor(hasTestTag(RequestsTags.ADD_LINK))
    compose.waitUntilAtLeastOneExists(field, PhonoRobot.TIMEOUT_MS)
    compose.onNode(field).performTextReplacement(text)
    compose.waitForIdle()
  }

  /** The link field's text. */
  fun linkText(): String {
    val field = hasSetTextAction() and hasAnyAncestor(hasTestTag(RequestsTags.ADD_LINK))
    return compose.onNode(field).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
  }

  /** "Analyze" / "Add to queue". */
  fun submit() {
    phono.waitForTag(RequestsTags.ADD_SUBMIT)
    compose.onNodeWithTag(RequestsTags.ADD_SUBMIT).performScrollTo().performClick()
    compose.waitForIdle()
  }

  /** Answers the notification question "Not now" when it is asked (the first request of a fresh install). */
  fun notNowIfAsked() {
    val question = hasText(words(R.string.add_notify_title))
    val asked = try {
      compose.waitUntilAtLeastOneExists(question, QUESTION_WAIT_MS)
      true
    } catch (_: Throwable) {
      false
    }
    if (asked) tapText(words(R.string.add_not_now))
  }

  fun tapText(text: String) {
    val node = hasText(text) and hasClickAction()
    compose.waitUntilAtLeastOneExists(node, PhonoRobot.TIMEOUT_MS)
    compose.onNode(node).performClick()
    compose.waitForIdle()
  }

  /** Taps the node tagged [tag], scrolled into view first ("Add a clip" and the detail scroll). */
  fun tapScrolled(tag: String) {
    phono.waitForTag(tag)
    compose.onNodeWithTag(tag).performScrollTo().performClick()
    compose.waitForIdle()
  }

  /** Waits until [matcher] is on screen. */
  fun await(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, PhonoRobot.TIMEOUT_MS)

  /** Waits until [text] shows in the node tagged [container] (merged into it, or in a node under it). */
  fun awaitTextIn(container: String, text: String) {
    val words = hasText(text, substring = true)
    await((words and hasTestTag(container)) or (words and hasAnyAncestor(hasTestTag(container))))
  }

  /** Taps [action]'s button on [id]'s card in Requests, scrolled to it first. */
  fun tapOnCard(id: RequestId, action: RequestAction) {
    scrollToCard(id)
    compose.onNode(hasTestTag(RequestRowTags.action(action)) and hasAnyAncestor(hasTestTag(RequestsTags.request(id)))).performClick()
    compose.waitForIdle()
  }

  /** Opens [id]'s request detail from its card in Requests. */
  fun openCard(id: RequestId) {
    scrollToCard(id)
    compose.onNode(hasTestTag(RequestRowTags.OPEN) and hasAnyAncestor(hasTestTag(RequestsTags.request(id)))).performClick()
    compose.waitForIdle()
    phono.assertShown(RequestDetailKey(id))
  }

  fun scrollToCard(id: RequestId) {
    phono.waitForTag(RequestsTags.LIST)
    compose.onNodeWithTag(RequestsTags.LIST).performScrollToNode(hasTestTag(RequestsTags.request(id)))
  }

  /** Taps [action] in the open request detail. */
  fun tapInDetail(action: RequestAction) {
    val button = hasTestTag(RequestRowTags.action(action)) and hasAnyAncestor(hasTestTag(RequestsTags.DETAIL_CARD))
    await(button)
    compose.onNode(button).performScrollTo().performClick()
    compose.waitForIdle()
  }

  /** The device queue as `RequestRepository` reports it now. */
  fun queue(): List<Request> = runBlocking { requests.requests().first() }

  /** Waits until the queue satisfies [check]; returns it. */
  fun awaitQueue(check: (List<Request>) -> Boolean): List<Request> {
    var last = emptyList<Request>()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      last = queue()
      check(last)
    }
    return last
  }

  private companion object {
    const val QUESTION_WAIT_MS = 3_000L
  }
}
