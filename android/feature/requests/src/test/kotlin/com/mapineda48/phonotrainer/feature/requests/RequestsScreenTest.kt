package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples
import com.mapineda48.phonotrainer.core.ui.component.RequestRowTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every state of Requests (spec F-20 States; design 10.2 P-03) at font scale 2.0 in light and dark, each followed by the
 * accessibility audit, and what each control asks for: "Cancel" and "Remove" ask first, "Undo" does not (F-20-AC12),
 * and "Started on your computer" is collapsed and counted only in its own head (F-20-AC8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h8000dp")
class RequestsScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val asked = mutableListOf<String>()
  private val actions = RequestsActions(
    onBack = { asked += "back" },
    onAdd = { asked += "add" },
    onRetry = { asked += "retry" },
    onOpen = { asked += "open:${it.id.value}" },
    onWatch = { asked += "watch:${it.id.value}" },
    onAction = { request, action -> asked += "action:${request.id.value}:${action.name}" },
    onBrowserExpanded = { asked += "browser:$it" },
    onHide = { asked += "hide:${it.value}" },
    onConnectAgain = { asked += "connectAgain" },
    onWhy = { asked += "why" },
  )
  private var state by mutableStateOf(RequestsSamples.full)

  private fun show(initial: RequestsUiState) {
    state = initial
    screen.setContent(ScreenVariant.REQUIRED.first()) { RequestsScreen(state, actions) }
  }

  private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

  /** Whether a node under [tag] (or [tag]'s own) shows [words]. */
  private fun assertShows(tag: String, words: String) {
    val inside = hasText(words, substring = true) and hasAnyAncestor(hasTestTag(tag))
    val own = hasText(words, substring = true) and hasTestTag(tag)
    assertWithMessage("\"$words\" under $tag").that(exists(inside) || exists(own)).isTrue()
  }

  private fun inCard(request: Request, tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag(RequestsTags.request(request.id)))

  /** Taps [action] on [request]'s card, scrolled to it first. */
  private fun tap(request: Request, action: RequestAction) {
    compose.onNodeWithTag(RequestsTags.LIST).performScrollToNode(hasTestTag(RequestsTags.request(request.id)))
    compose.onNode(inCard(request, RequestRowTags.action(action))).performClick()
    compose.waitForIdle()
  }

  @Test
  fun everyStateRendersAndPassesTheAudit() {
    show(RequestsSamples.requestsStates.first().second)
    RequestsSamples.requestsStates.forEach { (name, next) ->
      state = next
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          when (name) {
            "loading" -> assertThat(exists(hasTestTag(RequestsTags.LOADING))).isTrue()
            "error" -> assertThat(exists(hasTestTag(RequestsTags.ERROR))).isTrue()
            "empty" -> assertThat(exists(hasTestTag(RequestsTags.EMPTY))).isTrue()
            "paused" -> assertThat(exists(hasTestTag(RequestsTags.PAUSED))).isTrue()
            else -> assertThat(exists(hasTestTag(RequestsTags.group(RequestGroupKind.ATTENTION.name)))).isTrue()
          }
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("In state \"$name\" at $variant: ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun everyGroupHasItsHeadWithItsCount() {
    show(RequestsSamples.full)
    val counts = RequestsSamples.full.groups.associate { it.kind to it.requests.size }
    RequestGroupKind.entries.forEach { kind ->
      compose.onNodeWithTag(RequestsTags.LIST).performScrollToNode(hasTestTag(RequestsTags.group(kind.name)))
      compose.onNodeWithTag(RequestsTags.group(kind.name)).assertTextContains(counts.getValue(kind).toString())
    }
    compose.onNodeWithTag(RequestsTags.LIST).performScrollToNode(hasTestTag(RequestsTags.group(RequestGroupKind.ATTENTION.name)))
    compose.onNodeWithTag(RequestsTags.group(RequestGroupKind.ATTENTION.name)).assertTextContains("NEEDS YOUR ATTENTION")
  }

  @Test
  fun cancelAsksFirstAndOnlyTheConfirmationCancels() {
    show(RequestsSamples.full)
    tap(RequestRowSamples.analyzing, RequestAction.CANCEL)
    assertThat(asked).isEmpty()
    assertShows(RequestsTags.CANCEL_DIALOG, "Stop analyzing “How to order coffee like a local” on your computer?")
    compose.onNode(hasText("Keep analyzing")).performClick()
    assertThat(asked).isEmpty()

    tap(RequestRowSamples.analyzing, RequestAction.CANCEL)
    compose.onNode(hasText("Stop analyzing")).performClick()
    assertThat(asked).containsExactly("action:analyzing:CANCEL")
  }

  @Test
  fun removeAsksFirstWithWhatGoesWithIt() {
    show(RequestsSamples.full)
    tap(RequestRowSamples.waiting, RequestAction.REMOVE)
    assertShows(RequestsTags.REMOVE_DIALOG, "It won't be sent to your computer. The copy kept on this phone is deleted too.")
    compose.onNode(hasText("Remove") and hasAnyAncestor(hasTestTag(RequestsTags.REMOVE_DIALOG))).performClick()

    assertThat(asked).containsExactly("action:waiting:REMOVE")
  }

  @Test
  fun undoAndTheOtherActionsGoAtOnce() {
    show(RequestsSamples.full)
    tap(RequestRowSamples.cancelWaiting, RequestAction.UNDO_CANCEL)
    tap(RequestRowSamples.waiting, RequestAction.SEND_NOW)
    tap(RequestRowSamples.downloadForbidden, RequestAction.TRY_AUDIO_ONLY)
    tap(RequestRowSamples.tooLong, RequestAction.SHOW_DETAILS)

    assertThat(asked).containsExactly(
      "action:cancel-waiting:UNDO_CANCEL",
      "action:waiting:SEND_NOW",
      "action:forbidden:TRY_AUDIO_ONLY",
      "action:too-long:SHOW_DETAILS",
    ).inOrder()
  }

  @Test
  fun aCardOpensAndARequestOnThisPhoneOffersWatch() {
    show(RequestsSamples.full)
    compose.onNodeWithTag(RequestsTags.LIST).performScrollToNode(hasTestTag(RequestsTags.request(RequestRowSamples.onPhone.id)))
    compose.onNode(inCard(RequestRowSamples.onPhone, RequestRowTags.WATCH)).performClick()
    compose.onNode(inCard(RequestRowSamples.onPhone, RequestRowTags.OPEN)).performClick()

    assertThat(asked).containsExactly("watch:on-phone", "open:on-phone").inOrder()
  }

  @Test
  fun theBrowsersJobsAreCollapsedCountedOnlyInTheirHeadAndAFailedOneOnlyHides() {
    // F-20-AC8: a failed browser job sits only in "Started on your computer", collapsed, with "Hide on this phone".
    val only = RequestsSamples.empty.copy(browser = listOf(RequestsSamples.browserFailed))
    show(only)
    assertThat(exists(hasTestTag(RequestsTags.group(RequestGroupKind.ATTENTION.name)))).isFalse()
    assertThat(exists(hasTestTag(RequestsTags.EMPTY))).isFalse()
    assertShows(RequestsTags.BROWSER, "1")
    assertThat(exists(hasTestTag(RequestsTags.browserJob(RequestsSamples.browserFailed.id)))).isFalse()
    compose.onNodeWithTag(RequestsTags.BROWSER_TOGGLE).performClick()
    assertThat(asked).containsExactly("browser:true")

    state = only.copy(browser = listOf(RequestsSamples.browserRunning, RequestsSamples.browserFailed), browserExpanded = true)
    compose.waitForIdle()
    assertThat(exists(hasTestTag(RequestsTags.hide(RequestsSamples.browserRunning.id)))).isFalse()
    compose.onNodeWithTag(RequestsTags.LIST).performScrollToNode(hasTestTag(RequestsTags.hide(RequestsSamples.browserFailed.id)))
    compose.onNodeWithTag(RequestsTags.hide(RequestsSamples.browserFailed.id)).assertTextContains("Hide on this phone").performClick()
    assertThat(asked).containsExactly("browser:true", "hide:${RequestsSamples.browserFailed.id.value}").inOrder()
  }

  @Test
  fun theEmptyTheErrorAndThePausedStatesOfferTheirWayOut() {
    show(RequestsSamples.empty)
    compose.onNode(hasTestTag("${UiTags.EMPTY_ACTION}.0")).assertTextContains("Add a clip").performClick()

    state = RequestsSamples.failed
    compose.waitForIdle()
    assertShows(RequestsTags.ERROR, "Your requests could not be loaded.")
    compose.onNodeWithTag(RequestsTags.RETRY).performClick()

    state = RequestsSamples.paused
    compose.waitForIdle()
    assertShows(RequestsTags.PAUSED, "Your computer no longer recognizes this phone.")
    compose.onNodeWithTag(RequestsTags.PAUSED_ACTION).assertTextContains("Connect again").performClick()
    compose.onNodeWithTag(RequestsTags.LINK_ADD).performClick()

    assertThat(asked).containsExactly("add", "retry", "connectAgain", "add").inOrder()
  }

  @Test
  fun theConnectionLineSaysWhereTheComputerIs() {
    show(RequestsSamples.full)
    compose.onNodeWithTag(RequestsTags.LINE).assertContentDescriptionEquals("Connected to Desk PC")
    state = RequestsSamples.offline
    compose.waitForIdle()
    compose.onNodeWithTag(RequestsTags.LINE).assertContentDescriptionEquals("Your computer isn't reachable")
  }
}
