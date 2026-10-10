package com.mapineda48.phonotrainer.feature.insights

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.DestinationStub

// The stub destinations of :feature:insights at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-32 replaces them with its screens.

@Composable
internal fun InsightsStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.insights_title),
    lede = stringResource(R.string.insights_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun InsightsStubScreenPreview() {
  PhonoTheme { InsightsStubScreen() }
}
