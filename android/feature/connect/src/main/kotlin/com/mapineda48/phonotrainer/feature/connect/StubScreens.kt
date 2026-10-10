package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

// The stub destinations of :feature:connect at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-13 replaces them with its screens.

@Composable
internal fun ConnectStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.connect_title),
    lede = stringResource(R.string.connect_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.connect_link_details),
      onClick = { onNavigate(ConnectionDetailsKey) },
      modifier = Modifier.testTag(ConnectTags.LINK_DETAILS),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun ConnectStubScreenPreview() {
  PhonoTheme { ConnectStubScreen(onNavigate = {}) }
}

@Composable
internal fun ConnectionDetailsStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.connect_details_title),
    lede = stringResource(R.string.connect_details_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun ConnectionDetailsStubScreenPreview() {
  PhonoTheme { ConnectionDetailsStubScreen() }
}
