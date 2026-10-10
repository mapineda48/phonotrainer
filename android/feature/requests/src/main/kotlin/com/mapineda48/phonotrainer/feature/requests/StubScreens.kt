package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

// The stub destinations of :feature:requests at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-22 replaces them with its screens.

@Composable
internal fun RequestsStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.requests_title),
    lede = stringResource(R.string.requests_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.requests_link_add),
      onClick = { onNavigate(AddClipKey()) },
      modifier = Modifier.testTag(RequestsTags.LINK_ADD),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun RequestsStubScreenPreview() {
  PhonoTheme { RequestsStubScreen(onNavigate = {}) }
}

@Composable
internal fun RequestDetailStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.requests_detail_title),
    lede = stringResource(R.string.requests_detail_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun RequestDetailStubScreenPreview() {
  PhonoTheme { RequestDetailStubScreen() }
}

@Composable
internal fun AddClipStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.requests_add_title),
    lede = stringResource(R.string.requests_add_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun AddClipStubScreenPreview() {
  PhonoTheme { AddClipStubScreen() }
}
