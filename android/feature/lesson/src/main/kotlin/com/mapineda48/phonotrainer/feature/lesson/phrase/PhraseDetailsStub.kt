package com.mapineda48.phonotrainer.feature.lesson.phrase

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.NavOverlay
import com.mapineda48.phonotrainer.core.navigation.PhraseDetailsKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.feature.lesson.R
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

// F-11 (v1-complete). This package is reserved for WP-31: in v1-core it only installs the stub destination, and it
// binds no extension point (work-plan 4.4), so `PhraseDetailsEntry` stays absent and no menu offers it.

@Composable
internal fun PhraseDetailsStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.phrase_title),
    lede = stringResource(R.string.phrase_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun PhraseDetailsStubScreenPreview() {
  PhonoTheme { PhraseDetailsStubScreen() }
}

/** Contributes the phrase-details overlay to the shell (architecture 16.2, 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object PhraseDetailsNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller {
    entry<PhraseDetailsKey>(metadata = { NavOverlay.sheet() }) { PhraseDetailsStubScreen() }
  }
}
