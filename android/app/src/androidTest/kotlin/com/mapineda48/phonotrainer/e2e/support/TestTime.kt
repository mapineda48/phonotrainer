package com.mapineda48.phonotrainer.e2e.support

import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.e2e.di.FakeComputerHolder
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The one way an end-to-end test moves time (architecture 21.5): the phone's [TestClock] (bound by
 * `TestClockModule`) and this test's fake computer's frozen clock move together, so signatures stay inside the
 * computer's window and code expiry, rate-limit windows and upload idleness pass exactly when a test says so.
 */
class TestTime @Inject constructor(private val clock: TestClock, private val computers: FakeComputerHolder) {
  val now: Instant get() = clock.now()

  fun advanceBy(duration: Duration) {
    require(!duration.isNegative) { "time only moves forward in a test ($duration)" }
    clock.advanceBy(duration)
    computers.startedOrNull()?.clock?.advance(duration)
  }
}
