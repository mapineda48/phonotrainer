package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.WaitingInfo
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which reason a waiting row shows (spec F-20 item 1, D-61; work-plan WP-22 Proof): a table over every
 * `ComputerStatus` and the `WaitingInfo` cases: the four pauses, "Not on Wi-Fi", "Your computer isn't reachable",
 * `Connected` after 0, 1 and 3 attempts, and `NotConnected` with no line.
 */
@RunWith(RobolectricTestRunner::class)
class WaitingReasonCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
  private val now = Instant.parse("2026-10-10T09:41:00Z")
  private val connected = ComputerStatus.Connected("Desk PC", analyzing = 0, inLine = 0, lowOnSpace = false)

  private fun waiting(attempts: Int, inSeconds: Long? = 110) =
    WaitingInfo(held = false, attempts = attempts, nextAttemptAt = inSeconds?.let { now.plusSeconds(it) })

  @Test
  fun everyStatusAndAttemptGivesItsWaitingReason() {
    val unreachable = ComputerStatus.NotReachable("Desk PC", null, false, ProbeOutcome.NoAnswer(3))
    val table: List<Triple<ComputerStatus, WaitingInfo?, String?>> = listOf(
      Triple(ComputerStatus.ConnectAgain, waiting(2), "Your computer no longer recognizes this phone"),
      Triple(ComputerStatus.ComputerTooOld, waiting(0), "Update PhonoTrainer on your computer"),
      Triple(ComputerStatus.AppTooOld, null, "Update this app"),
      Triple(ComputerStatus.DifferentComputer("Other PC"), waiting(5), "A different computer answered"),
      Triple(ComputerStatus.Offline, waiting(0), "Not on Wi-Fi"),
      Triple(ComputerStatus.NotOnWifi, waiting(4), "Not on Wi-Fi"),
      Triple(ComputerStatus.Looking, waiting(0), "Your computer isn't reachable"),
      Triple(ComputerStatus.Checking, waiting(1), "Your computer isn't reachable"),
      Triple(unreachable, null, "Your computer isn't reachable"),
      Triple(connected, waiting(0), null),
      Triple(connected, null, null),
      Triple(connected, waiting(1), "Will try again in 2 min"),
      Triple(connected, waiting(2, inSeconds = 15), "Will try again in 1 min"),
      Triple(connected, waiting(2, inSeconds = -30), "Will try again in 1 min"),
      Triple(connected, waiting(1, inSeconds = null), "Couldn't send yet: will try again"),
      Triple(connected, waiting(3), "Couldn't send yet: will try again"),
      Triple(connected, waiting(12), "Couldn't send yet: will try again"),
      Triple(ComputerStatus.NotConnected, waiting(0), null),
      Triple(ComputerStatus.NotConnected, waiting(3), null),
    )
    table.forEach { (status, info, expected) ->
      assertWithMessage("$status, $info").that(WaitingReasonCopy.of(status, info, now, resources)).isEqualTo(expected)
    }
  }

  @Test
  fun thePauseAndTheConnectionStepsAreSeparateForDeletionCopy() {
    assertThat(WaitingReasonCopy.pause(ComputerStatus.ConnectAgain, resources)).isEqualTo("Your computer no longer recognizes this phone")
    assertThat(WaitingReasonCopy.pause(ComputerStatus.Offline, resources)).isNull()
    assertThat(WaitingReasonCopy.pause(connected, resources)).isNull()
    assertThat(WaitingReasonCopy.connection(ComputerStatus.ConnectAgain, waiting(3), now, resources)).isNull()
    assertThat(WaitingReasonCopy.connection(ComputerStatus.Offline, null, now, resources)).isEqualTo("Not on Wi-Fi")
    assertThat(WaitingReasonCopy.connection(connected, waiting(1, inSeconds = 290), now, resources)).isEqualTo("Will try again in 5 min")
  }

  @Test
  fun minutesAreRoundedUpAndAtLeastOne() {
    assertThat(WaitingReasonCopy.minutesUntil(now.plusSeconds(61), now)).isEqualTo(2)
    assertThat(WaitingReasonCopy.minutesUntil(now.plusSeconds(60), now)).isEqualTo(1)
    assertThat(WaitingReasonCopy.minutesUntil(now, now)).isEqualTo(1)
    assertThat(WaitingReasonCopy.minutesUntil(null, now)).isNull()
  }
}
