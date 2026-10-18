package com.mapineda48.phonotrainer.feature.requests

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapineda48.phonotrainer.core.designsystem.component.StepState
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The milestones of a request's timeline (spec F-20 item 2), each with its words and its place icon. */
internal enum class Milestone(@StringRes val label: Int, @DrawableRes val icon: Int) {
  ADDED(R.string.detail_milestone_added, PhonoIcons.AvailOnPhone),
  SENT(R.string.detail_milestone_sent, PhonoIcons.StatusSending),
  IN_LINE(R.string.detail_milestone_in_line, PhonoIcons.StatusWaiting),
  ANALYZING(R.string.detail_milestone_analyzing, PhonoIcons.StatusAnalyzing),
  READY(R.string.detail_milestone_ready, PhonoIcons.AvailOnComputer),
  ON_PHONE(R.string.detail_milestone_on_phone, PhonoIcons.AvailOnPhone),
}

/** One milestone as the timeline draws it: done (with its time), current (since its time) or next. */
internal data class MilestoneRow(val milestone: Milestone, val state: StepState, val at: Instant?)

/** The rules of the request detail (spec F-20 item 2; design 10.2 P-04, render `18`). Pure. */
internal object RequestDetailRules {
  /**
   * The milestone timeline of [request]: "Added on this phone" → "Sent to your computer" → "Waiting in line" →
   * "Analyzing" → "Ready on your computer" → "On this phone", each with its time from `Request.createdAt` and
   * `Request.timeline`. Every milestone before the one the request has reached is done; the reached one is current while
   * something happens there (preparing the file, sending, in line, analyzing, getting it on this phone); the rest are
   * next. A failure or a cancel keeps what it reached, with nothing current.
   */
  fun timeline(request: Request): List<MilestoneRow> {
    val times = mapOf(
      Milestone.ADDED to request.createdAt,
      Milestone.SENT to request.timeline.sentAt,
      Milestone.IN_LINE to request.timeline.inLineAt,
      Milestone.ANALYZING to request.timeline.analyzingAt,
      Milestone.READY to request.timeline.readyAt,
      Milestone.ON_PHONE to request.timeline.onPhoneAt,
    )
    val (reached, active) = reachedOf(request, times)
    return Milestone.entries.map { milestone ->
      val state = when {
        milestone.ordinal < reached -> StepState.DONE
        milestone.ordinal == reached -> if (active) StepState.CURRENT else StepState.DONE
        else -> StepState.NEXT
      }
      MilestoneRow(milestone, state, times[milestone])
    }
  }

  /** The index of the milestone [request] has reached, and whether something is still happening there. */
  private fun reachedOf(request: Request, times: Map<Milestone, Instant?>): Pair<Int, Boolean> {
    val lastTimed = Milestone.entries.lastOrNull { times[it] != null }?.ordinal ?: 0
    if (request.rerunPending) return Milestone.SENT.ordinal to true
    return when (request.state) {
      RequestState.PREPARING -> Milestone.ADDED.ordinal to true
      RequestState.COPY_FAILED, RequestState.COPY_MISSING, RequestState.WAITING -> Milestone.ADDED.ordinal to false
      RequestState.SENDING -> Milestone.SENT.ordinal to true
      RequestState.SENT -> Milestone.SENT.ordinal to false
      RequestState.IN_LINE -> Milestone.IN_LINE.ordinal to true
      RequestState.ANALYZING -> Milestone.ANALYZING.ordinal to true
      RequestState.READY_ON_COMPUTER -> Milestone.READY.ordinal to false
      RequestState.GETTING_ON_PHONE -> Milestone.ON_PHONE.ordinal to true
      RequestState.ON_PHONE -> Milestone.ON_PHONE.ordinal to false
      RequestState.NEEDS_ATTENTION, RequestState.CANCEL_WAITING, RequestState.CANCELLED -> lastTimed to false
    }
  }

  /** Whether "What your computer is doing" shows: the computer has (or is about to start again) the analysis. */
  fun showsStages(request: Request, failure: Boolean): Boolean {
    if (failure) return false
    if (request.rerunPending) return true
    return request.state in ACCEPTED
  }

  /** Whether the estimate and the first-run note show: it is on the computer and not done yet. */
  fun onComputer(request: Request): Boolean =
    request.rerunPending || request.state == RequestState.SENT || request.state == RequestState.IN_LINE ||
      request.state == RequestState.ANALYZING

  /**
   * The estimate (spec F-20 item 2), only once the clip's duration is known: "Usually takes about as long as the clip
   * (about 4 min)."; before that, for a link, "…once it's downloaded."; for a file whose duration is unknown, nothing.
   * The duration is not in `Request` yet (change request CR-WP22-1), so callers pass null until it is.
   */
  fun estimateMinutes(durationS: Double?): Int? =
    durationS?.takeIf { it > 0 }?.let { ((it + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1) }

  /** Whether the link-only estimate line applies ([durationS] unknown, a link). */
  fun linkEstimate(request: Request, durationS: Double?): Boolean = request.kind == RequestKind.LINK && durationS == null

  /** A milestone's time: "9:02" today, "Oct 9, 9:02" on another day (in the learner's zone). */
  fun time(at: Instant, now: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    val sameDay = local.toLocalDate() == now.atZone(zone).toLocalDate()
    return (if (sameDay) TODAY else OTHER_DAY).format(local)
  }

  private val ACCEPTED = setOf(
    RequestState.SENT,
    RequestState.IN_LINE,
    RequestState.ANALYZING,
    RequestState.CANCEL_WAITING,
    RequestState.READY_ON_COMPUTER,
    RequestState.GETTING_ON_PHONE,
    RequestState.ON_PHONE,
  )
  private const val SECONDS_PER_MINUTE = 60.0
  private val TODAY: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.US)
  private val OTHER_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, H:mm", Locale.US)
}
