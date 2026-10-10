package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** T2 stub: where the feed would start, and a way to open the lesson sheet over it. WP-17 replaces it. */
@Composable
internal fun WatchStubScreen(key: WatchKey, onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.watch_title),
    lede = stringResource(R.string.watch_lede),
    modifier = modifier,
  ) {
    Text(
      text = stringResource(
        when (key.start) {
          null, FeedStart.Resume -> R.string.watch_start_last
          FeedStart.TodaySet -> R.string.watch_start_today
          is FeedStart.InOrder -> R.string.watch_start_in_order
          is FeedStart.ChangeMode -> R.string.watch_start_change
          FeedStart.SavedMode -> R.string.watch_start_saved
          is FeedStart.AtWord -> R.string.watch_start_word
        },
      ),
      modifier = Modifier.testTag(WatchTags.START),
    )
    PhonoButton(
      text = stringResource(R.string.watch_open_lesson),
      onClick = {
        onNavigate(LessonKey(JobId("stub-1"), segment = 0, word = 0, LessonOrigin.FEED, LessonPresentation.SHEET))
      },
      modifier = Modifier.testTag(WatchTags.OPEN_LESSON),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun WatchStubScreenPreview() {
  PhonoTheme { WatchStubScreen(WatchKey(FeedStart.TodaySet), onNavigate = {}) }
}

/** Contributes the feed to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object WatchNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<WatchKey> { key -> WatchStubScreen(key, navigator::navigate) }
  }
}
