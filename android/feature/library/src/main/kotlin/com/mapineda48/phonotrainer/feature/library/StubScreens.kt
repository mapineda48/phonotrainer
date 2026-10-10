package com.mapineda48.phonotrainer.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

// The stub destinations of :feature:library at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-16 replaces them with its screens.

@Composable
internal fun ClipStubScreen(key: ClipKey, onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.library_clip_title),
    lede = stringResource(R.string.library_clip_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.library_clip_link_watch),
      onClick = { onNavigate(WatchKey(FeedStart.InOrder(key.jobId, firstSegment = null, fromStart = true))) },
      modifier = Modifier.testTag(LibraryTags.CLIP_WATCH),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun ClipStubScreenPreview() {
  PhonoTheme { ClipStubScreen(ClipKey(JobId("20261004-133519-a77c")), onNavigate = {}) }
}
