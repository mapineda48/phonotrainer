package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.WaitingInfo
import com.mapineda48.phonotrainer.core.ui.R
import java.time.Duration
import java.time.Instant

/**
 * Which reason a waiting row shows (spec F-20 item 1, D-61; architecture 19; design 9.19): the one list of waiting
 * reasons, for requests (`RequestRow`, WP-22) and, in v1-complete, deletions (`DeletionCopy`, WP-34, which calls [pause],
 * then its two deletion steps, then [connection]). Each function returns the line, or null when none applies. No word
 * is new: they are spec 7.5's and F-20's.
 *
 * A request held by "Stop sending" and a file too large for one background run keep their own lines (D-46, D-41): they
 * are `RequestRow`'s, not this list's.
 */
object WaitingReasonCopy {
  /**
   * Step 1, the queue's pause, from `ComputerStatus` (architecture 8.4: derived, never written into rows):
   * "Your computer no longer recognizes this phone", "Update PhonoTrainer on your computer", "Update this app", "A
   * different computer answered"; null for every other status.
   */
  fun pause(status: ComputerStatus, resources: Resources): String? = when (status) {
    ComputerStatus.ConnectAgain -> resources.getString(R.string.waiting_connect_again)
    ComputerStatus.ComputerTooOld -> resources.getString(R.string.waiting_computer_too_old)
    ComputerStatus.AppTooOld -> resources.getString(R.string.waiting_app_too_old)
    is ComputerStatus.DifferentComputer -> resources.getString(R.string.waiting_different_computer)
    else -> null
  }

  /**
   * Steps 3 and 4: not connected, "Not on Wi-Fi" without Wi-Fi (`Offline`, `NotOnWifi`) and "Your computer isn't
   * reachable" otherwise (`Looking`, `Checking`, `NotReachable`); connected after a failed attempt, "Will try again in
   * {n} min" ([WaitingInfo.attempts] 1 or 2, {n} from [WaitingInfo.nextAttemptAt] and [now], at least 1) and, after 3
   * or more, "Couldn't send yet: will try again". Null when connected before a failed attempt, and for `NotConnected`,
   * for which D-61 names no words (the queue simply waits for a computer).
   */
  fun connection(status: ComputerStatus, waiting: WaitingInfo?, now: Instant, resources: Resources): String? = when (status) {
    ComputerStatus.Offline, ComputerStatus.NotOnWifi -> resources.getString(R.string.waiting_not_on_wifi)
    ComputerStatus.Looking, ComputerStatus.Checking, is ComputerStatus.NotReachable ->
      resources.getString(R.string.waiting_unreachable)
    is ComputerStatus.Connected -> retry(waiting, now, resources)
    ComputerStatus.NotConnected, ComputerStatus.ConnectAgain, ComputerStatus.ComputerTooOld, ComputerStatus.AppTooOld,
    is ComputerStatus.DifferentComputer,
    -> null
  }

  /** The reason of a waiting request: [pause], then [connection]. */
  fun of(status: ComputerStatus, waiting: WaitingInfo?, now: Instant, resources: Resources): String? =
    pause(status, resources) ?: connection(status, waiting, now, resources)

  /** Minutes until [WaitingInfo.nextAttemptAt], rounded up, at least 1; null when no time is known. */
  fun minutesUntil(next: Instant?, now: Instant): Int? {
    next ?: return null
    val seconds = Duration.between(now, next).seconds
    return ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)
  }

  private fun retry(waiting: WaitingInfo?, now: Instant, resources: Resources): String? {
    val attempts = waiting?.attempts ?: 0
    return when {
      attempts <= 0 -> null
      attempts < MANY_ATTEMPTS -> minutesUntil(waiting?.nextAttemptAt, now)
        ?.let { resources.getQuantityString(R.plurals.waiting_retry_in, it, it) }
        ?: resources.getString(R.string.waiting_retry_later)
      else -> resources.getString(R.string.waiting_retry_later)
    }
  }

  /** From this many failed attempts the row reads "Couldn't send yet: will try again" (spec 7.7, architecture 8.5). */
  const val MANY_ATTEMPTS: Int = 3

  private const val SECONDS_PER_MINUTE = 60L
}
