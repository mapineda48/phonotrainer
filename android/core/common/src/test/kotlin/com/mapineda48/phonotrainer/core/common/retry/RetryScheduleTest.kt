package com.mapineda48.phonotrainer.core.common.retry

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

/** Every step of architecture 8.5: next Connected → 30 s → 1 min → 2 min → 5 min → every 15 min, no maximum. */
class RetryScheduleTest {
  private val failedAt = Instant.parse("2026-10-10T09:00:00Z")

  @Test
  fun theFirstAttemptWaitsOnlyForConnected() {
    assertThat(RetrySchedule.delayAfter(0)).isEqualTo(Duration.ZERO)
    assertThat(RetrySchedule.nextAttemptAt(0, null)).isNull()
    assertThat(RetrySchedule.nextAttemptAt(0, failedAt)).isNull()
    assertThat(RetrySchedule.isDue(0, null, failedAt)).isTrue()
  }

  @Test
  fun eachFailureWaitsTheNextStep() {
    assertThat(RetrySchedule.delayAfter(1)).isEqualTo(Duration.ofSeconds(30))
    assertThat(RetrySchedule.delayAfter(2)).isEqualTo(Duration.ofMinutes(1))
    assertThat(RetrySchedule.delayAfter(3)).isEqualTo(Duration.ofMinutes(2))
    assertThat(RetrySchedule.delayAfter(4)).isEqualTo(Duration.ofMinutes(5))
  }

  @Test
  fun thenEveryFifteenMinutesWithNoMaximum() {
    listOf(5, 6, 10, 100, 10_000, Int.MAX_VALUE).forEach { failures ->
      assertThat(RetrySchedule.delayAfter(failures)).isEqualTo(Duration.ofMinutes(15))
    }
  }

  @Test
  fun theWholeSequenceInOrder() {
    val waits = (0..7).map { RetrySchedule.delayAfter(it).seconds }
    assertThat(waits).containsExactly(0L, 30L, 60L, 120L, 300L, 900L, 900L, 900L).inOrder()
  }

  @Test
  fun theNextAttemptIsTheLastOnePlusItsStep() {
    assertThat(RetrySchedule.nextAttemptAt(1, failedAt)).isEqualTo(Instant.parse("2026-10-10T09:00:30Z"))
    assertThat(RetrySchedule.nextAttemptAt(4, failedAt)).isEqualTo(Instant.parse("2026-10-10T09:05:00Z"))
    assertThat(RetrySchedule.nextAttemptAt(9, failedAt)).isEqualTo(Instant.parse("2026-10-10T09:15:00Z"))
  }

  @Test
  fun anAttemptIsDueExactlyWhenItsStepHasPassed() {
    assertThat(RetrySchedule.isDue(2, failedAt, failedAt.plusSeconds(59))).isFalse()
    assertThat(RetrySchedule.isDue(2, failedAt, failedAt.plusSeconds(60))).isTrue()
    assertThat(RetrySchedule.isDue(2, failedAt, failedAt.plusSeconds(61))).isTrue()
    // A failure count without its time (an old row) is due at once rather than stuck.
    assertThat(RetrySchedule.isDue(2, null, failedAt)).isTrue()
  }

  @Test
  fun theRowSaysStillTryingFromTheThirdFailure() {
    assertThat(RetrySchedule.showsStillTrying(0)).isFalse()
    assertThat(RetrySchedule.showsStillTrying(2)).isFalse()
    assertThat(RetrySchedule.showsStillTrying(3)).isTrue()
    assertThat(RetrySchedule.showsStillTrying(40)).isTrue()
  }

  @Test
  fun aNegativeCountIsAProgrammingError() {
    assertThrows(IllegalArgumentException::class.java) { RetrySchedule.delayAfter(-1) }
  }
}
