package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.WaitingInfo
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples
import com.mapineda48.phonotrainer.core.ui.component.RequestRowTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every state of the request detail (spec F-20 item 2; design 10.2 P-04) at font scale 2.0 in light and dark with the
 * audit; the failures of SH-AC9, SH-AC16, SH-AC17 and SH-AC18 opened with their copy, their actions and "Show details"
 * without escape codes; the waiting lines of F-20-AC10 and AC11; "Undo" of F-20-AC12.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h3000dp")
class RequestDetailScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val asked = mutableListOf<String>()
  private var state by mutableStateOf(RequestsSamples.detailAnalyzing)
  private val actions = DetailActions(
    onBack = { asked += "back" },
    onSeeRequests = { asked += "seeRequests" },
    onAction = { asked += "action:${it.name}" },
    onWatch = { asked += "watch" },
    onLinkExpanded = { asked += "link:$it" },
    onDetailsExpanded = {
      asked += "details:$it"
      state = state.copy(detailsExpanded = it)
    },
  )

  private fun show(initial: RequestDetailUiState) {
    state = initial
    screen.setContent(ScreenVariant.REQUIRED.first()) { RequestDetailScreen(state, actions) }
  }

  private fun detailOf(request: Request, details: Boolean = false) = RequestsSamples.detailAnalyzing.copy(request = request, detailsExpanded = details)

  private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

  private fun action(action: RequestAction) = hasTestTag(RequestRowTags.action(action)) and hasAnyAncestor(hasTestTag(RequestsTags.DETAIL_CARD))

  /** Whether a node under [tag] (or [tag]'s own) shows [words]. */
  private fun assertShows(tag: String, words: String) {
    val inside = hasText(words, substring = true) and hasAnyAncestor(hasTestTag(tag))
    val own = hasText(words, substring = true) and hasTestTag(tag)
    assertWithMessage("\"$words\" under $tag").that(exists(inside) || exists(own)).isTrue()
  }

  private fun shownActions() = RequestAction.entries.filter { exists(action(it)) }

  private fun text(tag: String): String =
    compose.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

  @Test
  fun everyStateRendersAndPassesTheAudit() {
    show(RequestsSamples.detailStates.first().second)
    RequestsSamples.detailStates.forEach { (name, next) ->
      state = next
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          when (name) {
            "loading" -> assertThat(exists(hasTestTag(RequestsTags.DETAIL_LOADING))).isTrue()
            "removed" -> assertThat(exists(hasTestTag(RequestsTags.DETAIL_REMOVED))).isTrue()
            else -> {
              assertThat(exists(hasTestTag(RequestsTags.DETAIL_SOURCE))).isTrue()
              assertThat(exists(hasTestTag(RequestsTags.DETAIL_TIMELINE))).isTrue()
            }
          }
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("In state \"$name\" at $variant: ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun anAnalysisShowsTheStagesWithTheirTeachingLinesTheEstimateAndTheFirstRunNote() {
    show(RequestsSamples.detailAnalyzing)
    listOf(Stage.DOWNLOAD, Stage.EXTRACT, Stage.SEPARATE, Stage.TRANSCRIBE, Stage.PHONES, Stage.SAVE).forEach {
      assertWithMessage("$it").that(exists(hasTestTag(RequestsTags.stage(it)))).isTrue()
    }
    compose.onNodeWithTag(RequestsTags.stage(Stage.PHONES)).assertTextContains("Hear the phones — in progress · phrase 3 of 10")
    compose.onNodeWithTag(RequestsTags.stage(Stage.PHONES))
      .assertTextContains("The phone recognizer listens to each word and lines it up with its dictionary form.")
    compose.onNodeWithTag(RequestsTags.stage(Stage.DOWNLOAD)).assertTextContains("Download — done")
    compose.onNodeWithTag(RequestsTags.DETAIL_ESTIMATE).assertTextContains("Usually takes about as long as the clip, once it's downloaded.")
    assertThat(exists(hasTestTag(RequestsTags.DETAIL_FIRST_RUN))).isTrue()
    compose.onNodeWithTag(RequestsTags.milestone(Milestone.ANALYZING.name)).assertTextContains("Analyzing · 61 %")
  }

  @Test
  fun aDownloadFailureOffersItsFourActionsAndDetailsWithoutEscapeCodes() {
    // SH-AC9.
    show(RequestsSamples.detailForbidden.copy(detailsExpanded = false))
    assertThat(shownActions()).containsExactly(RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY, RequestAction.OPEN_LINK, RequestAction.REMOVE)
    compose.onNodeWithTag(RequestsTags.DETAIL_DETAILS_TOGGLE).performScrollTo().performClick()
    assertThat(text(RequestsTags.DETAIL_DETAILS)).contains("HTTP Error 403: Forbidden")
    assertThat(text(RequestsTags.DETAIL_DETAILS)).contains("Downloader version on your computer: 2026.08.19")
    assertThat(text(RequestsTags.DETAIL_DETAILS)).doesNotContain("\u001b")
    assertThat(asked).containsExactly("details:true")
  }

  @Test
  fun showDetailsHasNoTerminalEscapeCodesEvenIfTheMessageHadThem() {
    // F-20-AC7: the computer and :core:requests clean the message; the screen never shows one either way.
    val raw = RequestRowSamples.downloadForbidden.copy(
      lastError = RequestError("download_forbidden", "\u001B[0;31mERROR:\u001B[0m HTTP Error 403: Forbidden", null, atSending = false),
    )
    show(detailOf(raw, details = true))
    assertThat(text(RequestsTags.DETAIL_DETAILS)).isEqualTo("ERROR: HTTP Error 403: Forbidden")
  }

  @Test
  fun aPlaylistOffersOnlyOpenTheLinkAndRemove() {
    // SH-AC16.
    val playlist = RequestRowSamples.downloadForbidden.copy(
      lastError = RequestError("playlist", "that is a playlist", null, atSending = false),
      actions = setOf(RequestAction.OPEN_LINK, RequestAction.REMOVE),
    )
    show(detailOf(playlist))
    compose.onNode(hasTestTag(RequestRowTags.STATE) and hasAnyAncestor(hasTestTag(RequestsTags.DETAIL_CARD))).assertTextContains("That's a playlist")
    assertThat(shownActions()).containsExactly(RequestAction.OPEN_LINK, RequestAction.REMOVE)
  }

  @Test
  fun aLiveStreamSaysWhyAndOffersTryAgainOpenTheLinkAndRemove() {
    // SH-AC17.
    val live = RequestRowSamples.downloadForbidden.copy(
      lastError = RequestError("live_stream", "LiveStreamRefused: this video is live.", null, atSending = false),
      actions = setOf(RequestAction.TRY_AGAIN, RequestAction.OPEN_LINK, RequestAction.REMOVE),
    )
    show(detailOf(live, details = true))
    assertShows(RequestsTags.DETAIL_CARD, "Live streams can't be analyzed")
    assertShows(RequestsTags.DETAIL_CARD, "This video is live or hasn't started yet. Once it has ended and is saved as a video, try again.")
    assertThat(shownActions()).containsExactly(RequestAction.TRY_AGAIN, RequestAction.OPEN_LINK, RequestAction.REMOVE)
    assertThat(text(RequestsTags.DETAIL_DETAILS)).doesNotContain("\u001b")
  }

  @Test
  fun aTooLongVideoNamesTheComputersLimitAndShowDetailsOpensTheDetails() {
    // SH-AC18: capabilities give a link limit of 3600 s.
    show(RequestsSamples.detailTooLong.copy(detailsExpanded = false))
    assertShows(RequestsTags.DETAIL_CARD, "This video is too long for your computer")
    assertShows(RequestsTags.DETAIL_CARD, "Your computer analyzes link videos up to 1 hour long.")
    assertThat(shownActions()).containsExactly(RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS, RequestAction.REMOVE)
    assertThat(exists(hasTestTag(RequestsTags.DETAIL_DETAILS_TOGGLE))).isFalse()

    compose.onNode(action(RequestAction.SHOW_DETAILS)).performScrollTo().performClick()
    assertThat(asked).containsExactly("details:true")
    assertThat(text(RequestsTags.DETAIL_DETAILS)).contains("TooLongRefused: the video is 2:14:00 long.")
  }

  @Test
  fun aLargeFileAndAHeldRequestKeepTheirOwnLines() {
    // F-20-AC10, F-20-AC11.
    show(detailOf(RequestRowSamples.waitingLarge))
    assertShows(RequestsTags.DETAIL_CARD, "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent while the app is open.")
    state = detailOf(RequestRowSamples.waitingHeld)
    compose.waitForIdle()
    assertShows(RequestsTags.DETAIL_CARD, "Stopped by you: it waits here until you tap Send now.")
    assertThat(shownActions()).containsExactly(RequestAction.SEND_NOW, RequestAction.REMOVE)
  }

  @Test
  fun undoGoesAtOnceAndRemoveAsksFirst() {
    // F-20-AC12: "Undo" restores the request at once; the "Cancel undone" message is the shell's (UserMessages).
    show(detailOf(RequestRowSamples.cancelWaiting))
    compose.onNode(action(RequestAction.UNDO_CANCEL)).performScrollTo().performClick()
    assertThat(asked).containsExactly("action:UNDO_CANCEL")

    state = detailOf(RequestRowSamples.downloadForbidden)
    compose.waitForIdle()
    compose.onNode(action(RequestAction.REMOVE)).performScrollTo().performClick()
    assertThat(asked).containsExactly("action:UNDO_CANCEL")
    compose.onNode(hasText("Its failed analysis is removed from your computer too.")).assertExists()
    compose.onNode(hasText("Remove") and hasAnyAncestor(hasTestTag(RequestsTags.REMOVE_DIALOG))).performClick()
    assertThat(asked).containsExactly("action:UNDO_CANCEL", "action:REMOVE").inOrder()
  }

  @Test
  fun theLinkCanBeShownWholeAndARemovedRequestLeadsBackToRequests() {
    show(RequestsSamples.detailForbidden)
    compose.onNodeWithTag(RequestsTags.DETAIL_LINK_TOGGLE).performClick()
    state = state.copy(linkExpanded = true)
    compose.waitForIdle()
    assertShows(RequestsTags.DETAIL_SOURCE, "https://www.youtube.com/watch?v=Ab3xQ9kLmQx7")

    state = RequestsSamples.detailRemoved
    compose.waitForIdle()
    compose.onNode(hasText("See requests")).performClick()
    assertThat(asked).containsExactly("link:true", "seeRequests").inOrder()
  }

  @Test
  fun aRequestOnThisPhoneOffersWatch() {
    show(RequestsSamples.detailOnPhone)
    compose.onNode(hasTestTag(RequestRowTags.WATCH)).performScrollTo().performClick()
    assertThat(asked).containsExactly("watch")
    assertThat(RequestsSamples.detailOnPhone.request?.state).isEqualTo(RequestState.ON_PHONE)
    assertThat(RequestRowSamples.waitingHeld.waiting).isEqualTo(WaitingInfo(held = true, attempts = 0, nextAttemptAt = null))
  }
}
