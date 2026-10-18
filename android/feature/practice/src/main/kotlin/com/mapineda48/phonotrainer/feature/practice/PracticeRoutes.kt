package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey

/** Where Practice and Saved lead (spec F-17 items 3 to 5, F-09 item 3); one place, tested on the JVM. */
internal object PracticeDestinations {
  /** "Open the lesson" / "Open this word's lesson": the full-page lesson (design 9.24: from Practice and Saved). */
  fun lesson(word: WordRef, origin: LessonOrigin): LessonKey =
    LessonKey(word.jobId, word.segment, word.word, origin, LessonPresentation.FULL)

  /** "Watch more like this": the change mode of the feed over every clip. */
  fun changeMode(change: ChangeLabel): WatchKey =
    WatchKey(FeedStart.ChangeMode(change, onlyClip = null))

  /** "Watch saved": the Saved mode of the feed. */
  val savedMode: WatchKey = WatchKey(FeedStart.SavedMode)

  /**
   * A saved short opens the short that contains its anchor phrase (F-09-AC3), whatever the shorts rule now is: the
   * feed at the anchor phrase's first word finds that short.
   */
  fun savedShort(row: SavedRowUi): WatchKey =
    WatchKey(FeedStart.AtWord(row.short.jobId, row.short.firstSegment, word = 0, openLesson = false))

  fun clip(id: JobId): ClipKey = ClipKey(id)
}

/** The Practice tab's entry (T4): the ViewModel's state, each control wired to a call or a destination. */
@Composable
internal fun PracticeRoute(
  key: PracticeKey,
  navigator: Navigator,
  viewModel: PracticeViewModel = hiltViewModel<PracticeViewModel, PracticeViewModel.Factory>(creationCallback = { it.create(key) }),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  DisposableEffect(viewModel) { onDispose { viewModel.stopPlayback() } }
  val actions = remember(viewModel, navigator) {
    PracticeActions(
      onClearFocus = viewModel::clearFocus,
      onWeakSpots = viewModel::setWeakSpots,
      onStart = viewModel::start,
      onResume = viewModel::resume,
      onAnswer = viewModel::answer,
      onPlay = viewModel::play,
      onNext = viewModel::next,
      onLeave = viewModel::backToStart,
      onLesson = { navigator.navigate(PracticeDestinations.lesson(it, LessonOrigin.PRACTICE)) },
      onLearn = { navigator.navigate(PhenomenonKey(it)) },
      onWatchMore = { navigator.navigate(PracticeDestinations.changeMode(it)) },
      onSave = viewModel::saveWord,
      onAgain = viewModel::start,
      onAgainWeakSpots = viewModel::startWeakSpots,
      onBackToStart = viewModel::backToStart,
      onReset = viewModel::reset,
      onSaved = { navigator.navigate(SavedKey) },
      onAddClip = { navigator.navigate(AddClipKey()) },
    )
  }
  PracticeScreen(state, actions)
}

/** Saved's entry (P-11). */
@Composable
internal fun SavedRoute(navigator: Navigator, viewModel: SavedViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  DisposableEffect(viewModel) { onDispose { viewModel.stopPlayback() } }
  val actions = remember(viewModel, navigator) {
    SavedActions(
      onBack = navigator::back,
      onWatchSaved = { navigator.navigate(PracticeDestinations.savedMode) },
      onGoToWatch = { navigator.navigate(WatchKey()) },
      onPlay = viewModel::play,
      onOpenLesson = { row -> row.word?.let { navigator.navigate(PracticeDestinations.lesson(it, LessonOrigin.SAVED)) } },
      onWatchShort = { navigator.navigate(PracticeDestinations.savedShort(it)) },
      onOpenClip = { navigator.navigate(PracticeDestinations.clip(it.short.jobId)) },
      onLearn = { row -> row.change?.let { navigator.navigate(PhenomenonKey(it.label)) } },
      onUnsave = viewModel::unsave,
    )
  }
  SavedScreen(state, actions)
}
