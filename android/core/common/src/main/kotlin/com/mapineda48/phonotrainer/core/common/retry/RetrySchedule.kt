package com.mapineda48.phonotrainer.core.common.retry

import java.time.Duration
import java.time.Instant

/**
 * When the device queue tries a waiting request again (architecture 8.5): the first attempt at the next transition to
 * `Connected`, then 30 s, 1 min, 2 min and 5 min after each failed attempt, then every 15 min while connected, with no
 * maximum. After [ATTEMPTS_BEFORE_NOTICE] failed attempts the request row reads "Couldn't send yet: will try again"
 * with the last reason. The schedule only spaces attempts while connected; what a reconnection does to a request's
 * count is the device queue's decision (WP-09), which keeps the count in `WaitingInfo.attempts`.
 */
object RetrySchedule {
  /** The waits after the first, second, third and fourth failed attempt. */
  val STEPS: List<Duration> = listOf(
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(2),
    Duration.ofMinutes(5),
  )

  /** The wait after every later failed attempt, while connected. */
  val STEADY: Duration = Duration.ofMinutes(15)

  /** Failed attempts after which a request reads "Couldn't send yet: will try again". */
  const val ATTEMPTS_BEFORE_NOTICE: Int = 3

  /**
   * The wait before the next attempt after [failedAttempts] consecutive failures. Zero before the first attempt: a new
   * request waits only for the next transition to `Connected` (or goes at once when connected).
   */
  fun delayAfter(failedAttempts: Int): Duration {
    require(failedAttempts >= 0) { "failedAttempts must not be negative: $failedAttempts" }
    return when (failedAttempts) {
      0 -> Duration.ZERO
      in 1..STEPS.size -> STEPS[failedAttempts - 1]
      else -> STEADY
    }
  }

  /**
   * When the next attempt is due: [lastAttemptAt] plus [delayAfter]. Null before the first attempt, which is due at the
   * next transition to `Connected`.
   */
  fun nextAttemptAt(failedAttempts: Int, lastAttemptAt: Instant?): Instant? =
    if (failedAttempts == 0 || lastAttemptAt == null) null else lastAttemptAt.plus(delayAfter(failedAttempts))

  /** Whether an attempt may start at [now] while connected. */
  fun isDue(failedAttempts: Int, lastAttemptAt: Instant?, now: Instant): Boolean =
    nextAttemptAt(failedAttempts, lastAttemptAt)?.let { !now.isBefore(it) } ?: true

  /** Whether the request row says "Couldn't send yet: will try again" with the last reason. */
  fun showsStillTrying(failedAttempts: Int): Boolean = failedAttempts >= ATTEMPTS_BEFORE_NOTICE
}
