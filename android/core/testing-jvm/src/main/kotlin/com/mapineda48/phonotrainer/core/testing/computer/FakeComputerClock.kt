package com.mapineda48.phonotrainer.core.testing.computer

import java.time.Duration
import java.time.Instant

/**
 * The fake computer's clock (unix time). Code expiry, rate limits, nonces, the signature window, upload idleness and
 * session expiry all read it, so a test moves time with [advance] instead of sleeping (server-contract 11.1).
 *
 * A **running** clock (the default) also follows real time from its start value, so rate-limit windows and
 * idleness behave as on a real computer when a test never touches the clock; a **frozen** clock moves only through
 * [advance] and [set].
 */
class FakeComputerClock private constructor(startEpochMillis: Long, private val running: Boolean) {
  private val origin = System.nanoTime()
  private var offsetMillis = startEpochMillis

  @Synchronized
  fun nowMillis(): Long = offsetMillis + if (running) (System.nanoTime() - origin) / NANOS_PER_MILLI else 0

  fun nowSeconds(): Long = Math.floorDiv(nowMillis(), MILLIS_PER_SECOND)

  fun now(): Instant = Instant.ofEpochMilli(nowMillis())

  @Synchronized
  fun advance(duration: Duration) {
    offsetMillis += duration.toMillis()
  }

  fun advanceSeconds(seconds: Long) = advance(Duration.ofSeconds(seconds))

  /** Makes the clock read [epochSecond] now (a running clock goes on from there). */
  @Synchronized
  fun set(epochSecond: Long) {
    offsetMillis += epochSecond * MILLIS_PER_SECOND - nowMillis()
  }

  companion object {
    /** 2026-10-10T09:00:00Z: the `ts` of the reference vectors and the contract's `server_time` examples. */
    const val REFERENCE_EPOCH_S: Long = 1_791_622_800L

    fun running(startEpochSecond: Long = REFERENCE_EPOCH_S): FakeComputerClock =
      FakeComputerClock(startEpochSecond * MILLIS_PER_SECOND, running = true)

    fun frozen(startEpochSecond: Long = REFERENCE_EPOCH_S): FakeComputerClock =
      FakeComputerClock(startEpochSecond * MILLIS_PER_SECOND, running = false)

    private const val NANOS_PER_MILLI = 1_000_000L
    private const val MILLIS_PER_SECOND = 1_000L
  }
}
