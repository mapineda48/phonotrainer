package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.AvailabilityChip
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.R

/** A clip row of the Library and the feed mode picker (WP-16; API frozen at M2, work-plan 4.5). Placeholder. */
@Composable
fun ClipRow(
  state: ClipCardState,
  onWatch: () -> Unit,
  onOpen: () -> Unit,
  onMore: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier.heightIn(min = 56.dp).clickable(onClick = onOpen).testTag(ClipCardTags.OPEN)) {
    Column(modifier = Modifier.weight(1f)) {
      Text(state.clip.title, style = MaterialTheme.typography.titleMedium)
      AvailabilityChip(state.clip.availability)
    }
    PhonoButton(stringResource(R.string.clip_watch), onWatch, Modifier.testTag(ClipCardTags.WATCH))
    PhonoButton(
      stringResource(R.string.clip_more, state.clip.title),
      onMore,
      Modifier.testTag(ClipCardTags.MORE),
      kind = PhonoButtonKind.QUIET,
    )
  }
}

@Preview
@Composable
private fun ClipRowPreview() {
  PhonoTheme { ClipRow(ClipCardState(PreviewData.clip), onWatch = {}, onOpen = {}, onMore = {}) }
}
