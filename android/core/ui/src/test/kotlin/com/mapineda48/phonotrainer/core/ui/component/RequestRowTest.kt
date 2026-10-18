package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The request card and the mini-row (design 9.19, 10.2 P-01) in every state of design 9.19, at font scale 2.0 in light
 * and dark, each followed by the accessibility audit; only `Request.actions` are drawn (architecture 8.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h3000dp")
class RequestRowTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val tapped = mutableListOf<String>()
  private var current by mutableStateOf(RequestRowSamples.waiting)
  private var compact by mutableStateOf(false)
  private var status by mutableStateOf<ComputerStatus?>(RequestRowSamples.connected)

  private fun show() {
    screen.setContent(ScreenVariant.REQUIRED.first()) {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        RequestRow(
          state = RequestRowState(current, compact = compact, status = status, capabilities = RequestRowSamples.capabilities, now = RequestRowSamples.now),
          onAction = { tapped += "action:${it.name}" },
          onOpen = { tapped += "open" },
          onWatch = { tapped += "watch" },
        )
      }
    }
  }

  private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  private fun eachSample(check: (String, Request) -> Unit) {
    show()
    RequestRowSamples.all.forEach { (name, request) ->
      current = request
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          check(name, request)
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("\"$name\" at $variant (compact = $compact): ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun everyCardStateDrawsOnlyItsOwnActionsAndPassesTheAudit() {
    eachSample { _, request ->
      RequestAction.entries.forEach { action ->
        assertWithMessage("$action").that(exists(RequestRowTags.action(action))).isEqualTo(action in request.actions)
      }
      compose.onNodeWithTag(RequestRowTags.STATE, useUnmergedTree = true).assertExists()
    }
  }

  @Test
  fun everyMiniRowDrawsItsPrimaryActionOnlyAndPassesTheAudit() {
    compact = true
    eachSample { name, request ->
      val drawn = RequestAction.entries.filter { exists(RequestRowTags.action(it)) }
      val expected = RequestRowCopy.words(
        RequestRowState(request, compact = true, status = status, now = RequestRowSamples.now),
        RequestRowSamples.now,
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources,
      ).primary
      assertWithMessage(name).that(drawn).isEqualTo(listOfNotNull(expected))
    }
  }

  @Test
  fun aFailedRequestInTheMiniRowShowsTryAgainInline() {
    // F-04-AC3: the Library's "In progress" shows a failed request with its reason and "Try again".
    compact = true
    current = RequestRowSamples.downloadForbidden
    show()
    compose.onNodeWithTag(RequestRowTags.STATE, useUnmergedTree = true).assertTextContains("The download failed")
    compose.onNodeWithTag(RequestRowTags.action(RequestAction.TRY_AGAIN)).assertTextContains("Try again").performClick()
    compose.onNodeWithTag(RequestRowTags.OPEN, useUnmergedTree = true).performClick()
    assertThat(tapped).containsExactly("action:TRY_AGAIN", "open").inOrder()
  }

  @Test
  fun theCardCarriesOutItsActionsAndOpens() {
    current = RequestRowSamples.downloadForbidden
    show()
    listOf(RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY, RequestAction.OPEN_LINK, RequestAction.REMOVE).forEach {
      compose.onNodeWithTag(RequestRowTags.action(it), useUnmergedTree = true).performScrollTo().performClick()
    }
    assertThat(tapped).containsExactly("action:TRY_AGAIN", "action:TRY_AUDIO_ONLY", "action:OPEN_LINK", "action:REMOVE").inOrder()
  }

  @Test
  fun aRequestOnThisPhoneOffersWatch() {
    current = RequestRowSamples.onPhone
    show()
    compose.onNodeWithTag(RequestRowTags.WATCH).assertTextContains("Watch").performClick()
    assertThat(tapped).containsExactly("watch")
  }

  @Test
  fun theReasonLinesAndTheVisualsShow() {
    current = RequestRowSamples.waitingLarge
    status = ComputerStatus.Offline
    show()
    compose.onAllNodesWithTag(RequestRowTags.REASON, useUnmergedTree = true).assertCountEquals(2)
    assertThat(exists(RequestRowTags.ROUTE)).isTrue()
    current = RequestRowSamples.analyzing
    status = RequestRowSamples.connected
    compose.waitForIdle()
    assertThat(exists(RequestRowTags.STEPPER)).isTrue()
    assertThat(exists(RequestRowTags.ROUTE)).isFalse()
    current = RequestRowSamples.sending
    compose.waitForIdle()
    assertThat(exists(RequestRowTags.PROGRESS)).isTrue()
  }
}
