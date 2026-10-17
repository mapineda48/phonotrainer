package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.NavOverlay
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The stateful lesson entry (architecture 3.1): one [LessonViewModel] per [LessonKey] (its word and step survive a
 * process death in the entry's saved state, 16.2), and every way out through the shell's [Navigator]: closing goes
 * back (to the paused feed, F-07-AC6), "Watch more like this" opens the change's feed, the chips and "Learn about"
 * the phenomenon page, "Open in the IPA chart" the chart on that symbol.
 */
@Composable
internal fun LessonRoute(key: LessonKey, navigator: Navigator, modifier: Modifier = Modifier) {
  val viewModel = hiltViewModel<LessonViewModel, LessonViewModel.Factory>(creationCallback = { it.create(key) })
  val state by viewModel.state.collectAsStateWithLifecycle()
  val actions = LessonActions(
    onClose = navigator::back,
    onToggleSaved = viewModel::toggleSaved,
    onPrevious = viewModel::previous,
    onNext = viewModel::next,
    onNextChange = viewModel::nextChange,
    onToggleStep = viewModel::toggleStep,
    onPlay = viewModel::play,
    onSpeed = viewModel::setSpeed,
    onLoop = viewModel::setLoop,
    onTrack = viewModel::setTrack,
    onPickSort = viewModel::pickSort,
    onPlaySound = viewModel::playSelectedSound,
    onSeeMouth = viewModel::seeSelectedInMouth,
    onIpaChart = { navigator.navigate(IpaChartKey(it)) },
    onOpenChange = { navigator.navigate(PhenomenonKey(it)) },
    onWatchMore = { navigator.navigate(WatchKey(FeedStart.ChangeMode(it, onlyClip = null))) },
    onStream = viewModel::setStream,
    onHoldSound = viewModel::holdSound,
    onFollow = viewModel::follow,
    onPractice = viewModel::setPractice,
    onStartShadowing = viewModel::startShadowing,
    onStopShadowing = viewModel::stopShadowing,
    onLearnMore = { navigator.navigate(it.toKey()) },
  )
  val record = viewModel.record
  LessonScreen(
    state = state,
    presentation = key.presentation,
    actions = actions,
    modifier = modifier,
    glossary = viewModel::glossary,
    mouth = viewModel.mouth,
    playheadMs = {
      val playback = viewModel.playback.value
      if (playback.span?.jobId == key.jobId) playback.positionMs else null
    },
    record = record?.let { section -> { span, target, slotModifier -> section.Section(span, target, slotModifier) } },
  )
}

/** Where "Learn more" of a "What's this?" sheet goes (architecture 15.1's link table). */
private fun LinkTarget.toKey() = when (this) {
  LinkTarget.Learn -> LearnKey
  is LinkTarget.Phenomenon -> PhenomenonKey(label)
  is LinkTarget.Ipa -> IpaChartKey(symbol)
  is LinkTarget.Practice -> PracticeKey(focus?.value)
  LinkTarget.Insights -> InsightsKey
}

/**
 * Contributes the lesson to the shell (architecture 16.2, 16.3): with `presentation = SHEET` its entry carries the
 * overlay metadata, so it renders as a bottom sheet over the feed, which stays composed (and paused) beneath; with
 * `FULL` it is a page of its own (from the Clip page, Learn, Practice and Saved).
 */
@Module
@InstallIn(SingletonComponent::class)
object LessonNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<LessonKey>(
      metadata = { key -> if (key.presentation == LessonPresentation.SHEET) NavOverlay.sheet() else emptyMap() },
    ) { key -> LessonRoute(key, navigator) }
  }
}
