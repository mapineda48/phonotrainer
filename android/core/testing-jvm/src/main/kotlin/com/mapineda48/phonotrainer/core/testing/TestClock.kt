package com.mapineda48.phonotrainer.core.testing

import com.mapineda48.phonotrainer.core.domain.api.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [Clock] tests move by hand (architecture 3.3), alone or together with the coroutine test scheduler. Starts at
 * 2026-10-10T09:00:00Z in UTC unless told otherwise.
 */
class TestClock(
  var instant: Instant = Instant.parse("2026-10-10T09:00:00Z"),
  var zoneId: ZoneId = ZoneOffset.UTC,
) : Clock {
  override fun now(): Instant = instant

  override fun zone(): ZoneId = zoneId

  fun advanceBy(duration: Duration) {
    instant = instant.plus(duration)
  }
}
