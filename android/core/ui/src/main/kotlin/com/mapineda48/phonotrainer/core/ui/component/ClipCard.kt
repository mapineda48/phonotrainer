package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.ui.AvailabilityChip
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R

/** What a clip card or row shows (design 9.18). */
@Immutable
data class ClipCardState(val clip: ClipSummary)

/** Test tags of the clip card and row. */
object ClipCardTags {
  const val WATCH = "ui.clip.watch"
  const val OPEN = "ui.clip.open"
  const val MORE = "ui.clip.more"
}

/** The Home shelf card (WP-16; API frozen at M2, work-plan 4.5). Placeholder visuals. */
@Composable
fun ClipCard(
  state: ClipCardState,
  onWatch: () -> Unit,
  onOpen: () -> Unit,
  onMore: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PhonoCard(modifier = modifier) {
    Text(state.clip.title, style = MaterialTheme.typography.titleMedium)
    AvailabilityChip(state.clip.availability)
    Text(pluralStringResource(R.plurals.clip_shorts_seen, state.clip.shortsTotal, state.clip.shortsSeen, state.clip.shortsTotal))
    Row {
      PhonoButton(stringResource(R.string.clip_watch), onWatch, Modifier.testTag(ClipCardTags.WATCH))
      PhonoButton(
        stringResource(R.string.clip_open, state.clip.title),
        onOpen,
        Modifier.testTag(ClipCardTags.OPEN),
        kind = PhonoButtonKind.QUIET,
      )
      PhonoButton(
        stringResource(R.string.clip_more, state.clip.title),
        onMore,
        Modifier.testTag(ClipCardTags.MORE),
        kind = PhonoButtonKind.QUIET,
      )
    }
  }
}

@Preview
@Composable
private fun ClipCardPreview() {
  PhonoTheme { ClipCard(ClipCardState(PreviewData.clip), onWatch = {}, onOpen = {}, onMore = {}) }
}
