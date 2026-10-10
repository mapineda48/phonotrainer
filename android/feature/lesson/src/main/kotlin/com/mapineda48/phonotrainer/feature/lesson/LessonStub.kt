package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.NavOverlay
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** F-10 stub: the lesson's title and lede and "Close". WP-18 replaces it. */
@Composable
internal fun LessonStubScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.lesson_title),
    lede = stringResource(R.string.lesson_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.lesson_close),
      onClick = onClose,
      modifier = Modifier.testTag(LessonTags.CLOSE),
      kind = PhonoButtonKind.QUIET,
    )
  }
}

@Preview
@Composable
private fun LessonStubScreenPreview() {
  PhonoTheme { LessonStubScreen(onClose = {}) }
}

/**
 * Contributes the lesson to the shell (architecture 16.2, 16.3): with `presentation = SHEET` its entry carries the
 * overlay metadata, so it renders as a sheet over the feed, which stays composed beneath.
 */
@Module
@InstallIn(SingletonComponent::class)
object LessonNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<LessonKey>(
      metadata = { key -> if (key.presentation == LessonPresentation.SHEET) NavOverlay.sheet() else emptyMap() },
    ) { LessonStubScreen(onClose = navigator::back) }
  }
}
