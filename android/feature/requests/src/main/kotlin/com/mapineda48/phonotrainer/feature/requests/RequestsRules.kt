package com.mapineda48.phonotrainer.feature.requests

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.ui.component.RequestRowStages
import java.time.Duration
import java.time.Instant

/** The groups of the Requests screen, in the spec's order (F-20 item 1), each with its head's words and icon. */
internal enum class RequestGroupKind(@StringRes val label: Int, @DrawableRes val icon: Int) {
  ATTENTION(R.string.requests_group_attention, PhonoIcons.StatusAttention),
  PREPARING(R.string.requests_group_preparing, PhonoIcons.StatusPreparing),
  WAITING(R.string.requests_group_waiting, PhonoIcons.StatusWaiting),
  SENDING(R.string.requests_group_sending, PhonoIcons.StatusSending),
  ON_COMPUTER(R.string.requests_group_on_computer, PhonoIcons.AvailOnComputer),
  GETTING(R.string.requests_group_getting, PhonoIcons.StatusDownloading),
  DONE(R.string.requests_group_done, PhonoIcons.StatusReady),
}

/** One group of the Requests screen and its requests, in the order they are shown. */
internal data class RequestGroup(val kind: RequestGroupKind, val requests: List<Request>)

/** How a request action is carried out from a card (architecture 8.4: only `Request.actions` are offered). */
internal enum class ActionRoute {
  /** `RequestRepository.perform` at once. */
  PERFORM,

  /** "Stop analyzing “{title}” on your computer?" first (spec F-20 actions table). */
  CONFIRM_CANCEL,

  /** "Remove “{title}”?" first (F-20-AC6: "When Remove is confirmed"). */
  CONFIRM_REMOVE,

  /** The link handed to the system, only on tap (spec 8.6 rules). */
  OPEN_LINK,

  /** The computer's cleaned message, in the request detail. */
  DETAILS,

  /** Connect, in its "connect again" mode. */
  CONNECT_AGAIN,

  /** Connection details: the "Why?" checklist. */
  WHY,
}

/**
 * The rules of the Requests screen (spec F-20): which group a request sits in, which browser jobs show, how each
 * action is carried out, and which stage changes are announced. Pure, so they are tested without the screen.
 */
internal object RequestsRules {
  /** "Done recently" keeps a request this long after it reached this phone (spec F-20 item 1). */
  val DONE_RECENTLY: Duration = Duration.ofDays(7)

  /**
   * The group of [request] at [now], or null when it is not shown (a request that reached this phone more than 7 days
   * ago). A request whose automatic re-run is queued is on your computer ("Starting again on your computer").
   */
  fun groupOf(request: Request, now: Instant): RequestGroupKind? {
    if (request.rerunPending) return RequestGroupKind.ON_COMPUTER
    return when (request.state) {
      RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING, RequestState.CANCELLED ->
        RequestGroupKind.ATTENTION
      RequestState.PREPARING -> RequestGroupKind.PREPARING
      RequestState.WAITING -> RequestGroupKind.WAITING
      RequestState.SENDING -> RequestGroupKind.SENDING
      RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING, RequestState.CANCEL_WAITING -> RequestGroupKind.ON_COMPUTER
      RequestState.READY_ON_COMPUTER, RequestState.GETTING_ON_PHONE -> RequestGroupKind.GETTING
      RequestState.ON_PHONE -> RequestGroupKind.DONE.takeIf { Duration.between(onPhoneAt(request), now) <= DONE_RECENTLY }
    }
  }

  /**
   * [requests] in their groups, in the spec's order, empty groups left out. Inside a group the oldest comes first (the
   * order requests are sent and analyzed, spec 7.7), with the id as the tie-break so the order is stable; "Done
   * recently" shows the newest first.
   */
  fun groups(requests: List<Request>, now: Instant): List<RequestGroup> {
    val byGroup = requests.groupBy { groupOf(it, now) }
    return RequestGroupKind.entries.mapNotNull { kind ->
      val members = byGroup[kind].orEmpty()
      if (members.isEmpty()) return@mapNotNull null
      val ordered = if (kind == RequestGroupKind.DONE) {
        members.sortedWith(compareByDescending<Request> { onPhoneAt(it) }.thenBy { it.id.value })
      } else {
        members.sortedWith(compareBy<Request> { it.createdAt }.thenBy { it.id.value })
      }
      RequestGroup(kind, ordered)
    }
  }

  private fun onPhoneAt(request: Request): Instant = request.timeline.onPhoneAt ?: request.updatedAt ?: request.createdAt

  /**
   * "Started on your computer" (spec F-20 item 1, F-20-AC8): the browser's and the command line's jobs that are not
   * hidden and not done (a done one is a clip like any other): active ones, so the learner knows why their own request
   * waits in line, and failed ones, which offer only "Hide on this phone". Oldest first.
   */
  fun browser(jobs: List<ComputerJob>): List<ComputerJob> = jobs
    .filter { !it.hidden && it.status != RemoteStatus.DONE && it.status != RemoteStatus.UNKNOWN }
    .sortedWith(compareBy<ComputerJob> { it.createdAt }.thenBy { it.id.value })

  /** Whether a browser job failed (so it offers "Hide on this phone"). */
  fun failed(job: ComputerJob): Boolean = job.status == RemoteStatus.ERROR || job.status == RemoteStatus.CANCELLED

  /** How [action] is carried out. */
  fun route(action: RequestAction): ActionRoute = when (action) {
    RequestAction.CANCEL -> ActionRoute.CONFIRM_CANCEL
    RequestAction.REMOVE -> ActionRoute.CONFIRM_REMOVE
    RequestAction.OPEN_LINK -> ActionRoute.OPEN_LINK
    RequestAction.SHOW_DETAILS -> ActionRoute.DETAILS
    RequestAction.CONNECT_AGAIN -> ActionRoute.CONNECT_AGAIN
    RequestAction.WHY -> ActionRoute.WHY
    RequestAction.CANCEL_PREPARING, RequestAction.SEND_NOW, RequestAction.STOP_SENDING, RequestAction.UNDO_CANCEL,
    RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY,
    -> ActionRoute.PERFORM
  }

  /**
   * The link "Open the link" hands to the system, or null: only an http(s) link, trimmed, never anything else the row
   * holds (spec 8.6 rules; the link is the request's own, as sent).
   */
  fun openable(link: String?): String? {
    val trimmed = link?.trim() ?: return null
    val scheme = trimmed.substringBefore("://", "").lowercase()
    return trimmed.takeIf { (scheme == "http" || scheme == "https") && trimmed.none { it.isWhitespace() } }
  }

  /** Which words the "Remove" confirmation uses for [request] (spec F-20 actions table, item 4). */
  @StringRes
  fun removeBody(request: Request): Int = when {
    request.jobId != null -> R.string.requests_remove_body_job
    request.state == RequestState.COPY_FAILED || request.state == RequestState.COPY_MISSING -> R.string.requests_remove_body_local
    request.kind == RequestKind.FILE -> R.string.requests_remove_body_file
    else -> R.string.requests_remove_body_link
  }
}

/**
 * Which stage changes are announced (spec F-20 item 5, F-20-AC2): only a change of an analyzing request's stage, never
 * its percent or phrase, and never the first stage a screen sees (it is on screen already).
 */
internal class StageWatch {
  private val last = mutableMapOf<RequestId, Stage?>()

  /** The stages to announce now that [requests] are shown, in order. */
  fun changes(requests: List<Request>): List<Stage> {
    val announce = mutableListOf<Stage>()
    val seen = requests.associate { it.id to RequestRowStages.current(it) }
    seen.forEach { (id, stage) ->
      if (id in last && stage != null && last[id] != stage) announce += stage
    }
    last.clear()
    last.putAll(seen)
    return announce
  }
}
