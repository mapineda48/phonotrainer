package com.mapineda48.phonotrainer.core.requests.rules

import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState

/** The queue-wide pause, derived from `ComputerStatus` at read time and never written into rows (architecture 8.4). */
internal enum class QueuePause { CONNECT_AGAIN, UPDATE_COMPUTER, UPDATE_APP, DIFFERENT_COMPUTER }

internal fun ComputerStatus.queuePause(): QueuePause? = when (this) {
  ComputerStatus.ConnectAgain -> QueuePause.CONNECT_AGAIN
  ComputerStatus.ComputerTooOld -> QueuePause.UPDATE_COMPUTER
  ComputerStatus.AppTooOld -> QueuePause.UPDATE_APP
  is ComputerStatus.DifferentComputer -> QueuePause.DIFFERENT_COMPUTER
  else -> null
}

/** What [RequestActions.of] decides from: the state, the row and the moment's facts (architecture 8.4). */
internal data class ActionFacts(
  val kind: RequestKind,
  val state: RequestState,
  /** `state_reason` for the copy states, `last_error_code` otherwise. */
  val code: String?,
  /** The computer accepted it once: a failure is the job's, not one at sending. */
  val hasJob: Boolean,
  val isVideo: Boolean,
  /** The request, or its copy, is audio only already. */
  val audioOnly: Boolean,
  /** A complete copy is kept on this phone. */
  val copyKept: Boolean,
  /** The source can be read again: its share sheet still holds it, or it was picked with a lasting permission. */
  val readable: Boolean,
  val connected: Boolean,
  val pause: QueuePause?,
  /** A re-run waits in the outbox: "Starting again on your computer". */
  val rerunPending: Boolean,
  /** A cancel waits in the outbox while connected (it goes at once). */
  val cancelPending: Boolean,
)

/**
 * `Request.actions`, the one authority on what a request offers (architecture 8.4): `RequestFailureCopy` only words and
 * orders what this returns. The rows of spec 8.6 and F-20's actions table, for both kinds, with "Try audio only" only
 * where it can work (spec D-49) and "Send now" only while connected. Every failure state keeps "Remove" (SC-14).
 */
internal object RequestActions {
  fun of(facts: ActionFacts): Set<RequestAction> {
    if (facts.rerunPending) return setOf(RequestAction.REMOVE)
    return when (facts.state) {
      RequestState.PREPARING -> setOf(RequestAction.CANCEL_PREPARING)
      RequestState.COPY_FAILED -> buildSet {
        if (facts.code == CopyCodes.NO_SPACE && facts.isVideo && facts.readable && !facts.audioOnly) {
          add(RequestAction.TRY_AUDIO_ONLY)
        }
        add(RequestAction.REMOVE)
      }
      RequestState.COPY_MISSING -> setOf(RequestAction.REMOVE)
      RequestState.WAITING -> buildSet {
        if (facts.connected) add(RequestAction.SEND_NOW)
        add(RequestAction.REMOVE)
        when (facts.pause) {
          QueuePause.CONNECT_AGAIN -> add(RequestAction.CONNECT_AGAIN)
          QueuePause.UPDATE_COMPUTER, QueuePause.UPDATE_APP, QueuePause.DIFFERENT_COMPUTER -> add(RequestAction.WHY)
          null -> Unit
        }
      }
      RequestState.SENDING -> setOf(RequestAction.STOP_SENDING, RequestAction.REMOVE)
      RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING ->
        if (facts.cancelPending) emptySet() else setOf(RequestAction.CANCEL)
      RequestState.CANCEL_WAITING -> setOf(RequestAction.UNDO_CANCEL)
      RequestState.READY_ON_COMPUTER, RequestState.GETTING_ON_PHONE, RequestState.ON_PHONE -> emptySet()
      RequestState.CANCELLED ->
        if (facts.hasJob) setOf(RequestAction.TRY_AGAIN, RequestAction.REMOVE) else setOf(RequestAction.REMOVE)
      RequestState.NEEDS_ATTENTION -> if (facts.hasJob) jobFailure(facts) else atSending(facts)
    }
  }

  /** Refused before any job existed (spec 8.6 "at sending"). */
  private fun atSending(facts: ActionFacts): Set<RequestAction> = buildSet {
    val file = facts.kind == RequestKind.FILE
    when (facts.code) {
      Codes.TOO_LARGE -> if (file && facts.isVideo && facts.copyKept && !facts.audioOnly) {
        add(RequestAction.TRY_AUDIO_ONLY)
      }
      // A file damaged on the way three times (server-contract 8.1): it may pass with the copy still on the phone.
      Codes.CHECKSUM_MISMATCH -> if (file && facts.copyKept) add(RequestAction.TRY_AGAIN)
    }
    add(RequestAction.REMOVE)
  }

  /** The job failed on the computer (spec 8.6, D-40, D-49): links and files differ for "Try audio only". */
  private fun jobFailure(facts: ActionFacts): Set<RequestAction> {
    val link = facts.kind == RequestKind.LINK
    val actions = when (facts.code) {
      Codes.DOWNLOAD_FORBIDDEN ->
        listOf(RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY, RequestAction.OPEN_LINK, RequestAction.REMOVE)
      Codes.DOWNLOAD_UNAVAILABLE, Codes.UNSUPPORTED_SITE, Codes.PLAYLIST ->
        listOf(RequestAction.OPEN_LINK, RequestAction.REMOVE)
      Codes.LIVE_STREAM -> listOf(RequestAction.TRY_AGAIN, RequestAction.OPEN_LINK, RequestAction.REMOVE)
      Codes.TOO_LONG -> listOf(RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS, RequestAction.REMOVE)
      Codes.NETWORK, Codes.TOOL_MISSING, Codes.INTERRUPTED, Codes.CANCELLED ->
        listOf(RequestAction.TRY_AGAIN, RequestAction.REMOVE)
      Codes.NO_SPACE ->
        if (link) listOf(RequestAction.TRY_AUDIO_ONLY, RequestAction.REMOVE) else listOf(RequestAction.TRY_AGAIN, RequestAction.REMOVE)
      Codes.NOT_MEDIA, Codes.EXTRACT_FAILED, Codes.REFUSED_ADDRESS, Codes.SOURCE_GONE, Codes.INVALID_URL,
      Codes.UNSUPPORTED_TYPE, Codes.TOO_LARGE -> listOf(RequestAction.REMOVE)
      // processing_failed and any code this app does not know (architecture 19).
      else -> listOf(RequestAction.TRY_AGAIN, RequestAction.SHOW_DETAILS, RequestAction.REMOVE)
    }
    // A file's failure never offers "Try audio only" (an upload re-run keeps its options), and only links open.
    return actions.filterTo(LinkedHashSet()) { action ->
      when (action) {
        RequestAction.TRY_AUDIO_ONLY, RequestAction.OPEN_LINK -> link
        else -> true
      }
    }
  }
}
