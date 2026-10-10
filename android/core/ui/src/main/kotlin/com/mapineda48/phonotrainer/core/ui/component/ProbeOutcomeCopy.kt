package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.ui.R

/**
 * The last probe in words (spec 7.5, architecture 6.2; WP-15). The strings live in `strings_connection.xml`, so [of]
 * takes the [Resources] to read them from (an addition to work-plan 4.5's signature).
 */
object ProbeOutcomeCopy {
  fun of(outcome: ProbeOutcome, resources: Resources): String = when (outcome) {
    ProbeOutcome.Ok -> resources.getString(R.string.probe_ok)
    is ProbeOutcome.NoAnswer -> resources.getString(R.string.probe_no_answer, outcome.afterS)
    ProbeOutcome.Refused -> resources.getString(R.string.probe_refused)
    ProbeOutcome.NoRoute -> resources.getString(R.string.probe_no_route)
    ProbeOutcome.Reset -> resources.getString(R.string.probe_reset)
    ProbeOutcome.VpnBlocks -> resources.getString(R.string.probe_vpn)
    ProbeOutcome.PhoneAccessOff -> resources.getString(R.string.probe_phone_access_off)
    ProbeOutcome.NotPhonoTrainer -> resources.getString(R.string.probe_not_phonotrainer)
    ProbeOutcome.ProofFailed -> resources.getString(R.string.probe_proof_failed)
  }
}
