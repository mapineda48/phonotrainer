package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

// The stub destinations of :feature:practice at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-21 replaces them with its screens.

@Composable
internal fun PracticeStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.practice_title),
    lede = stringResource(R.string.practice_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.practice_link_saved),
      onClick = { onNavigate(SavedKey) },
      modifier = Modifier.testTag(PracticeTags.LINK_SAVED),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun PracticeStubScreenPreview() {
  PhonoTheme { PracticeStubScreen(onNavigate = {}) }
}

@Composable
internal fun SavedStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.practice_saved_title),
    lede = stringResource(R.string.practice_saved_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun SavedStubScreenPreview() {
  PhonoTheme { SavedStubScreen() }
}
