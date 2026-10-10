package com.mapineda48.phonotrainer.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

// The stub destinations of :feature:home at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-15 replaces them with its screens.

@Composable
internal fun HomeStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.home_title),
    lede = stringResource(R.string.home_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.home_link_library),
      onClick = { onNavigate(LibraryKey) },
      modifier = Modifier.testTag(HomeTags.LINK_LIBRARY),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.home_link_requests),
      onClick = { onNavigate(RequestsKey) },
      modifier = Modifier.testTag(HomeTags.LINK_REQUESTS),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.home_link_add),
      onClick = { onNavigate(AddClipKey()) },
      modifier = Modifier.testTag(HomeTags.LINK_ADD),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.home_link_connect),
      onClick = { onNavigate(ConnectKey()) },
      modifier = Modifier.testTag(HomeTags.LINK_CONNECT),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.home_link_settings),
      onClick = { onNavigate(SettingsKey) },
      modifier = Modifier.testTag(HomeTags.LINK_SETTINGS),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.home_link_today),
      onClick = { onNavigate(WatchKey(FeedStart.TodaySet)) },
      modifier = Modifier.testTag(HomeTags.LINK_TODAY),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun HomeStubScreenPreview() {
  PhonoTheme { HomeStubScreen(onNavigate = {}) }
}
