package com.mapineda48.phonotrainer.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability

/** The tone of a status line; each tone also has its own icon, so colour is never the only cue. */
enum class StatusTone { NEUTRAL, ACTIVE, GOOD, CAUTION, CRITICAL }

/** Icon + words for a request, connection or availability state (design 9.17 to 9.19). */
@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.NEUTRAL) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (tone == StatusTone.CRITICAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    modifier = modifier,
  )
}

/** A clip's availability on this phone, in the words of spec 7.11 (design 9.18). */
@Composable
fun AvailabilityChip(availability: ClipAvailability, modifier: Modifier = Modifier) {
  val text = when (availability) {
    ClipAvailability.OnThisPhone -> stringResource(R.string.ui_availability_on_phone)
    is ClipAvailability.Bringing -> stringResource(R.string.ui_availability_bringing, availability.percent)
    ClipAvailability.NotOnThisPhone -> stringResource(R.string.ui_availability_not_on_phone)
    ClipAvailability.GettingReadyOnComputer -> stringResource(R.string.ui_availability_getting_ready)
    is ClipAvailability.DownloadPaused -> stringResource(R.string.ui_availability_paused)
    ClipAvailability.NeedsDownloadingAgain -> stringResource(R.string.ui_availability_again)
  }
  StatusLine(text = text, modifier = modifier)
}

@Preview
@Composable
private fun StatusPreview() {
  PhonoTheme { AvailabilityChip(ClipAvailability.Bringing(42)) }
}
