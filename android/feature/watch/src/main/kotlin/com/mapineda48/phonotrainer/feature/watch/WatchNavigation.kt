package com.mapineda48.phonotrainer.feature.watch

import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PhraseDetailsKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.player.android.PlayerSurface
import com.mapineda48.phonotrainer.core.ui.a11y.LocalTalkBackOn
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The stateful Watch entry (architecture 3.1): one [WatchViewModel] per `WatchKey` (it outlives tab switches, 16.2),
 * told whether Watch is visible (composed and started) so the player pool releases its decoders otherwise (11.2), and
 * whether TalkBack is on (no autoplay, A-08). Navigation goes through the shell's [Navigator].
 */
@Composable
internal fun WatchRoute(key: WatchKey, navigator: Navigator) {
  val viewModel = hiltViewModel<WatchViewModel, WatchViewModel.Factory>(creationCallback = { it.create(key) })
  val state by viewModel.state.collectAsStateWithLifecycle()
  val talkBack = LocalTalkBackOn.current
  LaunchedEffect(talkBack) { viewModel.setTalkBack(talkBack) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> viewModel.setVisible(true)
        Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      viewModel.setVisible(false)
    }
  }
  LaunchedEffect(viewModel) {
    when (val exit = viewModel.startExit()) {
      is WatchExit.Lesson -> navigator.navigate(lessonKey(exit.word))
      is WatchExit.ClipPage -> navigator.navigate(ClipKey(exit.jobId))
      WatchExit.Library -> navigator.navigate(LibraryKey)
      null -> Unit
    }
  }
  val muted = rememberMediaVolumeMuted()
  val openLesson: (WordRef) -> Unit = { word ->
    viewModel.pauseForLesson()
    navigator.navigate(lessonKey(word))
  }
  val actions = WatchActions(
    card = CardCallbacks(
      onTogglePlay = viewModel::togglePlay,
      onReplay = viewModel::replayPhrase,
      onHold = viewModel::holdSlow,
      onSpeed = viewModel::cycleSpeed,
      onSlower = viewModel::slower,
      onLoop = viewModel::cycleLoop,
      onSave = viewModel::toggleSave,
      onWord = openLesson,
      onLoopPhrase = viewModel::loopPhrase,
      onSeek = viewModel::seek,
      onPlayWord = viewModel::playWord,
      onSlowly = viewModel::playWordSlowly,
      onOpenChange = {
        state.contents[state.currentShort?.id]?.spotlight?.label?.let { navigator.navigate(PhenomenonKey(it)) }
      },
      onOpenClip = { state.currentShort?.id?.jobId?.let { navigator.navigate(ClipKey(it)) } },
      onShowWords = viewModel::revealWords,
      onShowCaptions = { viewModel.setCaptions(com.mapineda48.phonotrainer.core.domain.model.CaptionMode.MARKS) },
      onDismissCoach = viewModel::dismissCoachMark,
    ),
    end = EndCallbacks(
      onHome = { navigator.navigate(HomeKey) },
      onAnotherSet = viewModel::anotherSet,
      onPractice = { focus -> navigator.navigate(PracticeKey(focus?.value)) },
      onWatchAgain = viewModel::watchAgain,
      onNextClip = { navigator.navigate(WatchKey(FeedStart.InOrder(it, firstSegment = null, fromStart = false))) },
      onLearn = { navigator.navigate(PhenomenonKey(it)) },
      onReplayMix = viewModel::anotherSet,
      onAddClip = { navigator.navigate(AddClipKey()) },
    ),
    question = QuestionCallbacks(
      onAnswer = viewModel::answer,
      onPlay = viewModel::playQuestion,
      onLesson = { openLesson(it.explanation.word) },
      onLearn = { navigator.navigate(PhenomenonKey(it.change)) },
      onWatchMore = { navigator.navigate(WatchKey(FeedStart.ChangeMode(it.change, onlyClip = null))) },
      onSave = viewModel::saveAnswerWord,
    ),
    empty = EmptyCallbacks(
      onConnect = { navigator.navigate(ConnectKey(ConnectMode.FROM_HOME)) },
      onAddClip = { navigator.navigate(AddClipKey()) },
      onLearn = { navigator.navigate(LearnKey) },
      onWatchMix = { navigator.navigate(WatchKey(FeedStart.TodaySet)) },
    ),
    onPageSettled = viewModel::onPageSettled,
    onScrolled = viewModel::scrolled,
    onPick = { navigator.navigate(WatchKey(it)) },
    onLearnChange = { navigator.navigate(PhenomenonKey(it)) },
    onPickerOpened = viewModel::loadPickerClips,
    onTrack = viewModel::setTrack,
    onCaptions = viewModel::setCaptions,
    onListenFirst = viewModel::setListenFirst,
    onPhraseDetails = {
      val short = state.currentShort
      val segment = state.contents[short?.id]?.phraseAt(viewModel.positionMs())?.segment
      if (short != null && segment != null) navigator.navigate(PhraseDetailsKey(short.id.jobId, segment))
    },
    onOpenClipPage = { state.currentShort?.id?.jobId?.let { navigator.navigate(ClipKey(it)) } },
    onDownloadVideo = viewModel::downloadVideo,
    onExcluded = viewModel::setExcluded,
    onShadowStart = { viewModel.startShadowing(it.slowSpeed) },
    onShadowStop = viewModel::stopShadowing,
    onLearnMore = { target -> navigator.navigate(target.toKey()) },
  )
  val record = viewModel.recordSection
  WatchScreen(
    state = state,
    positionMs = viewModel::positionMs,
    volumeMuted = muted,
    actions = actions,
    surface = { index, modifier, scale -> PlayerSurface(viewModel.feedPlayer, index, modifier, scale) },
    glossary = viewModel::glossary,
    record = record?.let { section ->
      { modifier ->
        val target = viewModel.recordTarget()
        if (target != null) section.Section(target.span, target, modifier)
      }
    },
  )
}

private fun lessonKey(word: WordRef) =
  LessonKey(word.jobId, word.segment, word.word, LessonOrigin.FEED, LessonPresentation.SHEET)

private fun LinkTarget.toKey() = when (this) {
  LinkTarget.Learn -> LearnKey
  is LinkTarget.Phenomenon -> PhenomenonKey(label)
  is LinkTarget.Ipa -> IpaChartKey(symbol)
  is LinkTarget.Practice -> PracticeKey(focus?.value)
  LinkTarget.Insights -> InsightsKey
}

/** Whether the media volume is at 0 ("Turn the volume up: this is all about sound.", F-07 States), kept current. */
@Composable
internal fun rememberMediaVolumeMuted(): Boolean {
  val context = LocalContext.current
  val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
  var muted by remember(audio) { mutableStateOf(audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) }
  DisposableEffect(audio) {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        muted = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
      }
    }
    context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
    onDispose { context.contentResolver.unregisterContentObserver(observer) }
  }
  return muted
}

/** Contributes the feed to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object WatchNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<WatchKey> { key -> WatchRoute(key, navigator) }
  }
}
