package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The last probe in words (spec 7.5, D-42; architecture 6.2): one row per `ProbeOutcome`, the one mapping. */
@RunWith(RobolectricTestRunner::class)
class ProbeOutcomeCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

  @Test
  fun everyOutcomeHasItsWords() {
    val table = mapOf(
      ProbeOutcome.Ok to "Your computer answered.",
      ProbeOutcome.NoAnswer(3) to "No answer after 3 s",
      ProbeOutcome.NoAnswer(10) to "No answer after 10 s",
      ProbeOutcome.Refused to "Your computer refused the connection.",
      ProbeOutcome.NoRoute to "This phone has no route to your computer.",
      ProbeOutcome.Reset to "The connection was cut.",
      ProbeOutcome.VpnBlocks to "A VPN on this phone blocks your computer.",
      ProbeOutcome.PhoneAccessOff to "Your computer answered, but phone access is off there or not approved for this network.",
      ProbeOutcome.NotPhonoTrainer to "Something answered, but it isn't PhonoTrainer.",
      ProbeOutcome.ProofFailed to "A different computer answered.",
    )
    // Every subtype of the sealed interface is in the table (nine kinds).
    assertThat(table.keys.map { it::class }.toSet()).hasSize(9)
    table.forEach { (outcome, words) ->
      assertWithMessage("$outcome").that(ProbeOutcomeCopy.of(outcome, resources)).isEqualTo(words)
    }
  }
}
