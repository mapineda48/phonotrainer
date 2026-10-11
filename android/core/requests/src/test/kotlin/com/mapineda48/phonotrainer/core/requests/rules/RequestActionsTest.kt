package com.mapineda48.phonotrainer.core.requests.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.CANCEL
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.CANCEL_PREPARING
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.CONNECT_AGAIN
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.OPEN_LINK
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.REMOVE
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.SEND_NOW
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.SHOW_DETAILS
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.STOP_SENDING
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.TRY_AGAIN
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.TRY_AUDIO_ONLY
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.UNDO_CANCEL
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.WHY
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestKind.FILE
import com.mapineda48.phonotrainer.core.domain.model.RequestKind.LINK
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import org.junit.Test

/**
 * `Request.actions` (architecture 8.4) against every row of spec 8.6 and of F-20's actions table, for both kinds: the
 * one authority `RequestFailureCopy` only words. Every failure keeps an action (SC-14).
 */
class RequestActionsTest {
  /** Spec 8.6, job failures: (code, link's actions, file's actions). */
  private val jobFailures = listOf(
    Triple(Codes.DOWNLOAD_FORBIDDEN, setOf(TRY_AGAIN, TRY_AUDIO_ONLY, OPEN_LINK, REMOVE), setOf(TRY_AGAIN, REMOVE)),
    Triple(Codes.DOWNLOAD_UNAVAILABLE, setOf(OPEN_LINK, REMOVE), setOf(REMOVE)),
    Triple(Codes.UNSUPPORTED_SITE, setOf(OPEN_LINK, REMOVE), setOf(REMOVE)),
    Triple(Codes.PLAYLIST, setOf(OPEN_LINK, REMOVE), setOf(REMOVE)),
    Triple(Codes.LIVE_STREAM, setOf(TRY_AGAIN, OPEN_LINK, REMOVE), setOf(TRY_AGAIN, REMOVE)),
    // D-40: no "Try again" and no "Try audio only" for a video longer than the computer's limit.
    Triple(Codes.TOO_LONG, setOf(OPEN_LINK, SHOW_DETAILS, REMOVE), setOf(SHOW_DETAILS, REMOVE)),
    Triple(Codes.NETWORK, setOf(TRY_AGAIN, REMOVE), setOf(TRY_AGAIN, REMOVE)),
    // D-49: "Try audio only" for a link, "Try again" for a file.
    Triple(Codes.NO_SPACE, setOf(TRY_AUDIO_ONLY, REMOVE), setOf(TRY_AGAIN, REMOVE)),
    Triple(Codes.NOT_MEDIA, setOf(REMOVE), setOf(REMOVE)),
    Triple(Codes.EXTRACT_FAILED, setOf(REMOVE), setOf(REMOVE)),
    Triple(Codes.TOOL_MISSING, setOf(TRY_AGAIN, REMOVE), setOf(TRY_AGAIN, REMOVE)),
    Triple(Codes.INTERRUPTED, setOf(TRY_AGAIN, REMOVE), setOf(TRY_AGAIN, REMOVE)),
    Triple(Codes.PROCESSING_FAILED, setOf(TRY_AGAIN, SHOW_DETAILS, REMOVE), setOf(TRY_AGAIN, SHOW_DETAILS, REMOVE)),
    Triple(Codes.CANCELLED, setOf(TRY_AGAIN, REMOVE), setOf(TRY_AGAIN, REMOVE)),
    // A job refused by the confined download, and a re-run whose stored source is gone (F-20 item 4).
    Triple(Codes.REFUSED_ADDRESS, setOf(REMOVE), setOf(REMOVE)),
    Triple(Codes.SOURCE_GONE, setOf(REMOVE), setOf(REMOVE)),
    // A code this app does not know reads as processing_failed (architecture 19).
    Triple("a_future_code", setOf(TRY_AGAIN, SHOW_DETAILS, REMOVE), setOf(TRY_AGAIN, SHOW_DETAILS, REMOVE)),
  )

  @Test
  fun everyJobFailureOffersTheSpecActionsForBothKinds() {
    jobFailures.forEach { (code, link, file) ->
      assertWithMessage("link $code").that(actions(LINK, RequestState.NEEDS_ATTENTION, code, hasJob = true)).isEqualTo(link)
      assertWithMessage("file $code").that(actions(FILE, RequestState.NEEDS_ATTENTION, code, hasJob = true, video = true))
        .isEqualTo(file)
    }
  }

  @Test
  fun refusalsAtSendingOfferRemoveAndAudioOnlyOnlyForAVideoTooLargeWithItsCopy() {
    assertThat(actions(LINK, RequestState.NEEDS_ATTENTION, Codes.REFUSED_ADDRESS)).containsExactly(REMOVE)
    assertThat(actions(LINK, RequestState.NEEDS_ATTENTION, Codes.INVALID_URL)).containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.NEEDS_ATTENTION, Codes.UNSUPPORTED_TYPE, video = true, copyKept = true))
      .containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.NEEDS_ATTENTION, Codes.TOO_LARGE, video = true, copyKept = true))
      .containsExactly(TRY_AUDIO_ONLY, REMOVE)
    assertThat(actions(FILE, RequestState.NEEDS_ATTENTION, Codes.TOO_LARGE, video = false, copyKept = true))
      .containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.NEEDS_ATTENTION, Codes.TOO_LARGE, video = true, copyKept = false))
      .containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.NEEDS_ATTENTION, Codes.TOO_LARGE, video = true, copyKept = true, audioOnly = true))
      .containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.NEEDS_ATTENTION, Codes.CHECKSUM_MISMATCH, copyKept = true))
      .containsExactly(TRY_AGAIN, REMOVE)
  }

  @Test
  fun copyStatesOfferRemoveAndAudioOnlyOnlyWhileTheVideoCanBeReadAgain() {
    assertThat(actions(FILE, RequestState.COPY_FAILED, CopyCodes.NO_SPACE, video = true, readable = true))
      .containsExactly(TRY_AUDIO_ONLY, REMOVE)
    // D-49: once the share sheet closed, a shared file cannot be read again.
    assertThat(actions(FILE, RequestState.COPY_FAILED, CopyCodes.NO_SPACE, video = true, readable = false)).containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.COPY_FAILED, CopyCodes.NO_SPACE, video = false, readable = true)).containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.COPY_FAILED, CopyCodes.UNREADABLE, video = true, readable = true)).containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.COPY_FAILED, CopyCodes.INTERRUPTED, video = true, readable = true)).containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.COPY_MISSING, CopyCodes.MISSING, video = true)).containsExactly(REMOVE)
  }

  @Test
  fun theActionsTableOfF20ByState() {
    assertThat(actions(FILE, RequestState.PREPARING)).containsExactly(CANCEL_PREPARING)
    assertThat(actions(LINK, RequestState.WAITING, connected = true)).containsExactly(SEND_NOW, REMOVE)
    // "Send now" only when connected.
    assertThat(actions(LINK, RequestState.WAITING, connected = false)).containsExactly(REMOVE)
    assertThat(actions(FILE, RequestState.SENDING, connected = true)).containsExactly(STOP_SENDING, REMOVE)
    listOf(RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING).forEach { state ->
      assertThat(actions(LINK, state, hasJob = true)).containsExactly(CANCEL)
      assertThat(actions(LINK, state, hasJob = true, cancelPending = true)).isEmpty()
    }
    assertThat(actions(LINK, RequestState.CANCEL_WAITING, hasJob = true)).containsExactly(UNDO_CANCEL)
    listOf(RequestState.READY_ON_COMPUTER, RequestState.GETTING_ON_PHONE, RequestState.ON_PHONE).forEach { state ->
      assertThat(actions(LINK, state, hasJob = true)).isEmpty()
    }
    assertThat(actions(FILE, RequestState.CANCELLED, Codes.CANCELLED, hasJob = true)).containsExactly(TRY_AGAIN, REMOVE)
    // "Starting again on your computer": only removal.
    assertThat(actions(LINK, RequestState.NEEDS_ATTENTION, Codes.NETWORK, hasJob = true, rerunPending = true)).containsExactly(REMOVE)
    assertThat(actions(LINK, RequestState.ANALYZING, hasJob = true, rerunPending = true)).containsExactly(REMOVE)
  }

  @Test
  fun aPausedQueueOffersConnectAgainOrWhyOnWaitingRequests() {
    val connectAgain = ComputerStatus.ConnectAgain.queuePause()
    assertThat(actions(LINK, RequestState.WAITING, pause = connectAgain)).containsExactly(REMOVE, CONNECT_AGAIN)
    listOf(ComputerStatus.ComputerTooOld, ComputerStatus.AppTooOld, ComputerStatus.DifferentComputer("Desk")).forEach {
      assertThat(actions(LINK, RequestState.WAITING, pause = it.queuePause())).containsExactly(REMOVE, WHY)
    }
    assertThat(ComputerStatus.Offline.queuePause()).isNull()
    assertThat(ComputerStatus.Connected("Desk", 0, 0, false).queuePause()).isNull()
  }

  @Test
  fun noFailureStateLeavesTheLearnerWithoutAWayOut() {
    val failures = listOf(RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING, RequestState.CANCELLED)
    val codes = jobFailures.map { it.first } + listOf(Codes.TOO_LARGE, Codes.UNSUPPORTED_TYPE, CopyCodes.NO_SPACE, CopyCodes.MISSING, null)
    val exits = setOf(TRY_AGAIN, TRY_AUDIO_ONLY, REMOVE, CONNECT_AGAIN)
    RequestKind.entries.forEach { kind ->
      failures.forEach { state ->
        codes.forEach { code ->
          listOf(true, false).forEach { hasJob ->
            assertWithMessage("$kind $state $code job=$hasJob")
              .that(actions(kind, state, code, hasJob = hasJob).intersect(exits)).isNotEmpty()
          }
        }
      }
    }
  }

  private fun actions(
    kind: RequestKind,
    state: RequestState,
    code: String? = null,
    hasJob: Boolean = false,
    video: Boolean = false,
    audioOnly: Boolean = false,
    copyKept: Boolean = false,
    readable: Boolean = false,
    connected: Boolean = false,
    pause: QueuePause? = null,
    rerunPending: Boolean = false,
    cancelPending: Boolean = false,
  ): Set<RequestAction> = RequestActions.of(
    ActionFacts(kind, state, code, hasJob, video, audioOnly, copyKept, readable, connected, pause, rerunPending, cancelPending),
  )
}
