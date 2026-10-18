package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.GlossaryIndexKey
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Learn (T3): every way out goes through the shell's [Navigator]. */
@Composable
internal fun LearnRoute(navigator: Navigator, modifier: Modifier = Modifier) {
  val viewModel = hiltViewModel<LearnViewModel>()
  val state by viewModel.state.collectAsStateWithLifecycle()
  LearnScreen(
    state = state,
    actions = LearnActions(
      onOpenChange = { navigator.navigate(PhenomenonKey(it)) },
      onIpaChart = { navigator.navigate(IpaChartKey()) },
      onGlossary = { navigator.navigate(GlossaryIndexKey) },
      onInsights = { navigator.navigate(InsightsKey) },
    ),
    modifier = modifier,
  )
}

/**
 * A phenomenon page: one [PhenomenonViewModel] per [PhenomenonKey]. "Watch examples" opens the change's feed (F-15-AC2),
 * "Practice this" Practice with its focus, "Open in its lesson" where [ExampleLessons] says; leaving for another page
 * stops the example playing.
 */
@Composable
internal fun PhenomenonRoute(key: PhenomenonKey, navigator: Navigator, modifier: Modifier = Modifier) {
  val viewModel = hiltViewModel<PhenomenonViewModel, PhenomenonViewModel.Factory>(creationCallback = { it.create(key) })
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) { viewModel.exits.collect(navigator::navigate) }
  fun leave(to: androidx.navigation3.runtime.NavKey) {
    viewModel.stopSound()
    navigator.navigate(to)
  }
  PhenomenonScreen(
    state = state,
    actions = PhenomenonActions(
      onBack = navigator::back,
      onWatch = { leave(WatchKey(FeedStart.ChangeMode(it, onlyClip = null))) },
      onPractice = { leave(PracticeKey(focus = it.value)) },
      onPlay = viewModel::play,
      onOpenLesson = viewModel::openLesson,
      onAddClip = { leave(AddClipKey()) },
      onLearn = { leave(LearnKey) },
      onIpaChart = { leave(IpaChartKey(it)) },
      onLearnMore = { leave(it.toKey()) },
    ),
    modifier = modifier,
    glossary = viewModel::glossary,
    mouth = viewModel.mouth,
  )
}

/** The IPA chart: one [IpaChartViewModel] per [IpaChartKey], its selection in the entry's saved state. */
@Composable
internal fun IpaChartRoute(key: IpaChartKey, navigator: Navigator, modifier: Modifier = Modifier) {
  val viewModel = hiltViewModel<IpaChartViewModel, IpaChartViewModel.Factory>(creationCallback = { it.create(key) })
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) { viewModel.exits.collect(navigator::navigate) }
  IpaChartScreen(
    state = state,
    actions = IpaChartActions(
      onBack = navigator::back,
      onSelect = viewModel::select,
      onPlay = viewModel::play,
      onOpenLesson = viewModel::openLesson,
      onOpenChange = {
        viewModel.select(null)
        navigator.navigate(PhenomenonKey(it))
      },
    ),
    modifier = modifier,
    mouth = viewModel.mouth,
  )
}

/** "Words we use": the search in the entry's saved state; a row's "Learn more" goes where the glossary says. */
@Composable
internal fun GlossaryIndexRoute(navigator: Navigator, modifier: Modifier = Modifier) {
  val viewModel = hiltViewModel<GlossaryIndexViewModel>()
  val state by viewModel.state.collectAsStateWithLifecycle()
  GlossaryIndexScreen(
    state = state,
    actions = GlossaryActions(
      onBack = navigator::back,
      onSearch = viewModel::search,
      onClear = viewModel::clear,
      onLearnMore = { navigator.navigate(it.toKey()) },
    ),
    modifier = modifier,
    entry = viewModel::entry,
  )
}

/** Contributes the destinations of :feature:learn to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object LearnNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<LearnKey> { LearnRoute(navigator) }
    entry<PhenomenonKey> { key -> PhenomenonRoute(key, navigator) }
    entry<IpaChartKey> { key -> IpaChartRoute(key, navigator) }
    entry<GlossaryIndexKey> { GlossaryIndexRoute(navigator) }
  }
}
