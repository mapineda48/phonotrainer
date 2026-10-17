package com.mapineda48.phonotrainer.feature.watch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.ArtifactProgress
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.CorpusTotals
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedPosition
import com.mapineda48.phonotrainer.core.domain.model.FeedSequence
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.StudySetKind
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeCoachMarkRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeDidacticContent
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeFeedPlayer
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeShadowingRunner
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeSpanPlayer
import com.mapineda48.phonotrainer.core.testing.fake.study.FakePracticeRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeProgressRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSavedRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSetRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeSyncController
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.ui.extension.PhraseDetailsEntry
import com.mapineda48.phonotrainer.core.ui.extension.RecordSection
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** The feed's ViewModel on the settable fakes of work-plan 4.6 (spec F-06 to F-09, architecture 10 and 11). */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val job = AnalysisFixtures.JOB
  private val other = JobId("20261005-101010-b001")
  private val player = FakeFeedPlayer()
  private val spanPlayer = FakeSpanPlayer()
  private val shadowing = FakeShadowingRunner()
  private val shorts = FakeShortRepository()
  private val content = FakeClipContentRepository()
  private val occurrences = FakeOccurrenceRepository()
  private val clips = FakeClipRepository()
  private val messages = FakeUserMessages()
  private val sets = FakeSetRepository()
  private val progress = FakeProgressRepository()
  private val saved = FakeSavedRepository()
  private val settings = FakeSettingsRepository()
  private val coach = FakeCoachMarkRepository()
  private val reference = FakeReferenceRepository()
  private val didactic = FakeDidacticContent()
  private val computer = FakeComputerStatusRepository()
  private val sync = FakeSyncController()
  private val clock = TestClock()
  private val created = mutableListOf<WatchViewModel>()

  private val flapping = ChangeLabel("flapping")

  private fun info(i: Int, jobId: JobId = job, spotlight: Spotlight? = defaultSpot(jobId, i)) = ShortInfo(
    id = ShortId(jobId, i * 2),
    ordinal = i + 1,
    ofTotal = 10,
    lastSegment = i * 2 + 1,
    playStartMs = i * 10_000L,
    playEndMs = i * 10_000L + 10_000,
    spotlight = spotlight,
    markedWords = 1,
  )

  private fun defaultSpot(jobId: JobId, i: Int) = Spotlight(WordRef(jobId, i * 2, 0), flapping, Advice.PRODUCE, false)

  private fun media(jobId: JobId = job, dialogue: File? = null) = ClipMedia(jobId, dialogue)

  @Suppress("FunctionName")
  private fun ClipMedia(jobId: JobId, dialogue: File?) = com.mapineda48.phonotrainer.core.domain.model.ClipMedia(
    jobId = jobId,
    audioMix = File("mix-${jobId.value}.wav"),
    audioDialogue = dialogue,
    videoFile = null,
    videoCodec = null,
    videoWidth = null,
    videoHeight = null,
    videoRotation = 0,
    videoOffsetMs = 0,
    poster = null,
    videoAvailability = VideoAvailability.AudioOnlyClip,
  )

  private fun arrangeClip(jobId: JobId = job, count: Int = 10): List<ShortInfo> {
    val list = (0 until count).map { info(it, jobId) }
    shorts.shortsById.value = shorts.shortsById.value + (jobId to list)
    content.mediaById[jobId] = media(jobId)
    content.data[jobId] = AnalysisFixtures.clipData(jobId)
    occurrences.totalsState.value = CorpusTotals(1, count, 50, 10)
    return list
  }

  private fun viewModel(
    start: FeedStart? = FeedStart.InOrder(job, firstSegment = null, fromStart = false),
    saved: SavedStateHandle = SavedStateHandle(),
    record: RecordSection? = null,
  ): WatchViewModel = WatchViewModel(
    key = WatchKey(start),
    savedState = saved,
    player = player,
    spanPlayer = spanPlayer,
    shadowing = shadowing,
    shorts = shorts,
    content = content,
    occurrences = occurrences,
    clips = clips,
    messages = messages,
    sets = sets,
    progress = progress,
    saved = this.saved,
    settingsRepository = settings,
    coachMarks = coach,
    reference = reference,
    didactic = didactic,
    computer = computer,
    sync = sync,
    clock = clock,
    cpu = main.dispatcher,
    phraseDetails = Optional.empty<PhraseDetailsEntry>(),
    recordSection = Optional.ofNullable(record),
  ).also { created += it }

  private fun advance(ms: Long) {
    main.dispatcher.scheduler.advanceTimeBy(ms)
    main.dispatcher.scheduler.runCurrent()
  }

  @After
  fun tearDown() {
    created.forEach { it.viewModelScope.cancel() }
  }

  // --- Modes and start positions --------------------------------------------------------------------------------

  @Test
  fun inOrderResumesWhereTheClipWasLeftAndSettlesWithAutoplay() {
    arrangeClip()
    progress.resumeById.value = mapOf(job to ShortId(job, 12))
    val vm = viewModel()
    val state = vm.state.value
    // F-06-AC10: left at short 7 → the feed resumes at short 7.
    assertThat(state.loading).isFalse()
    assertThat(state.current).isEqualTo(6)
    assertThat(state.pages.last()).isEqualTo(FeedPage.End(EndKind.CLIP_END))
    assertThat(player.commands).contains("setQueue:10:6")
    vm.onPageSettled(6)
    assertThat(player.commands.last()).isEqualTo("settle:6:true")
    assertThat(state.mode).isInstanceOf(ModeName.InOrder::class.java)
  }

  @Test
  fun withTalkBackOnOrPlayEachShortOffNothingPlaysByItself() {
    arrangeClip()
    val vm = viewModel()
    vm.setTalkBack(true)
    vm.onPageSettled(0)
    // F-07-AC11: with TalkBack, a short does not play until "Play" is activated.
    assertThat(player.commands.last()).isEqualTo("settle:0:false")
    vm.setTalkBack(false)
    settings.state.value = UserSettings(playEachShort = false)
    vm.onPageSettled(1)
    assertThat(player.commands.last()).isEqualTo("settle:1:false")
  }

  @Test
  fun aChangeModePlaysTheShortsOfThatChangeAndAnEmptyOneSaysSo() {
    arrangeClip()
    val vm = viewModel(FeedStart.ChangeMode(flapping, onlyClip = null))
    assertThat(vm.state.value.shortPages).hasSize(10)
    assertThat((vm.state.value.mode as ModeName.Change).name).isEqualTo("flapping")
    assertThat(vm.state.value.pages.last()).isEqualTo(FeedPage.End(EndKind.CHANGE_END))
    val empty = viewModel(FeedStart.ChangeMode(ChangeLabel("linking"), onlyClip = null))
    assertThat(empty.state.value.empty).isEqualTo(FeedEmpty.NoShortsWithChange("linking"))
  }

  @Test
  fun anEmptySavedModeAndAnEmptyPhoneSayWhatIsMissing() {
    shorts.sequenceFor = { mode -> FeedSequence(mode, emptyList(), 0) }
    assertThat(viewModel(FeedStart.SavedMode).state.value.empty).isEqualTo(FeedEmpty.NothingSaved)
    // No set and nothing playable: first run when never paired, clips coming when paired.
    assertThat(viewModel(FeedStart.TodaySet).state.value.empty).isEqualTo(FeedEmpty.FirstRun)
    computer.pairedState.value = paired()
    assertThat(viewModel(FeedStart.TodaySet).state.value.empty).isEqualTo(FeedEmpty.ClipsComing(null))
    // Playable shorts but no set to build: the Mix's "nothing left" end card.
    occurrences.totalsState.value = CorpusTotals(1, 4, 20, 3)
    val nothing = viewModel(FeedStart.TodaySet).state.value
    assertThat(nothing.empty).isNull()
    assertThat(nothing.pages).containsExactly(FeedPage.End(EndKind.NOTHING_LEFT))
  }

  @Test
  fun theWatchTabResumesTheLastModeAndShort() {
    // F-06-AC12: the Watch tab opens the last mode at the last short.
    arrangeClip()
    progress.lastFeedState.value = FeedPosition(FeedMode.Change(flapping), ShortId(job, 8), 41_000, clock.now())
    val vm = viewModel(start = null)
    assertThat(vm.state.value.mode).isInstanceOf(ModeName.Change::class.java)
    assertThat(vm.state.value.current).isEqualTo(4)
  }

  @Test
  fun aRecreatedFeedComesBackOnTheSameShort() {
    arrangeClip()
    val handle = SavedStateHandle()
    val first = viewModel(saved = handle)
    first.onPageSettled(3)
    val again = viewModel(saved = handle)
    assertThat(again.state.value.current).isEqualTo(3)
  }

  @Test
  fun aWordTargetOpensItsShortAndTheLessonOnce() {
    arrangeClip()
    val handle = SavedStateHandle()
    val vm = viewModel(FeedStart.AtWord(job, segment = 9, word = 1, openLesson = true), saved = handle)
    assertThat(vm.state.value.current).isEqualTo(4)
    assertThat(exitOf(vm)).isEqualTo(WatchExit.Lesson(WordRef(job, 9, 1)))
    assertThat(exitOf(vm)).isNull()
    assertThat(exitOf(viewModel(FeedStart.AtWord(job, 9, 1, openLesson = true), saved = handle))).isNull()
    assertThat(messages.posted).isEmpty()
  }

  @Test
  fun aWordTargetWhoseClipHasNoShortsHereOpensTheClipPage() {
    // Spec 5.4: the clip is still listed (on the computer, not on this phone): its Clip page, no notice.
    clips.details.value = mapOf(job to detail(ArtifactLocalState.READY))
    val vm = viewModel(FeedStart.AtWord(job, segment = 9, word = 1, openLesson = true))
    assertThat(exitOf(vm)).isEqualTo(WatchExit.ClipPage(job))
    assertThat(messages.posted).isEmpty()
  }

  @Test
  fun aWordTargetWhoseClipIsGoneGoesToTheLibraryWithItsNotice() {
    // Spec 5.4 and decision I-14: "That clip is no longer on your computer or this phone." (WORD_CLIP_GONE), once.
    val handle = SavedStateHandle()
    val vm = viewModel(FeedStart.AtWord(job, segment = 9, word = 1, openLesson = true), saved = handle)
    assertThat(exitOf(vm)).isEqualTo(WatchExit.Library)
    assertThat(messages.posted.map { it.kind }).containsExactly(UserMessageKind.WORD_CLIP_GONE)
    assertThat(exitOf(viewModel(FeedStart.AtWord(job, 9, 1, openLesson = true), saved = handle))).isNull()
    assertThat(messages.posted).hasSize(1)
  }

  /** What the route does once with a word target ([WatchViewModel.startExit]). */
  private fun exitOf(vm: WatchViewModel): WatchExit? {
    var exit: WatchExit? = null
    vm.viewModelScope.launch { exit = vm.startExit() }
    advance(0)
    return exit
  }

  // --- Sets (F-08) ----------------------------------------------------------------------------------------------

  private fun todaySet(cursor: Int = 0, completed: Boolean = false, dropped: Int? = null): StudySet {
    val items = (0 until 3).map { i ->
      StudySetItem.Short(info(i), spotlight = null, savedTag = i == 1, dropped = i == dropped, done = i < cursor)
    } + StudySetItem.QuestionItem(WatchPreviewData.question, choice = null, correct = null)
    return StudySet(7, StudySetKind.TODAY, LocalDate.of(2026, 10, 10), items, cursor, 0, 0, if (completed) Instant.EPOCH else null)
  }

  @Test
  fun todaysSetResumesAtItsCursorWithItsQuestionsAndSummary() {
    arrangeClip()
    sets.todayState.value = todaySet(cursor = 2)
    val vm = viewModel(FeedStart.TodaySet)
    val state = vm.state.value
    // F-08-AC2: the same set resumes at the short after the ones completed.
    assertThat(state.current).isEqualTo(2)
    assertThat(state.pages.map { it::class.simpleName })
      .containsExactly("Short", "Short", "Short", "QuestionPage", "End").inOrder()
    assertThat((state.pages[1] as FeedPage.Short).savedTag).isTrue()
    assertThat(state.shortSet).isEqualTo(3)
    assertThat(state.position).isEqualTo(HeaderPosition.InSet(3, 3, SetProgress(2, 2, 3, finished = false)))
    vm.onPageSettled(3)
    assertThat(sets.advanced.last()).isEqualTo(7L to 3)
    vm.onPageSettled(4)
    assertThat(sets.advanced.last()).isEqualTo(7L to 4)
    assertThat(vm.state.value.position).isInstanceOf(HeaderPosition.Done::class.java)
  }

  @Test
  fun aCompletedSetShowsItsSummaryAndQuestionsCanBeTurnedOff() {
    arrangeClip()
    sets.todayState.value = todaySet(completed = true)
    assertThat(viewModel(FeedStart.TodaySet).state.value.currentPage).isEqualTo(FeedPage.End(EndKind.SET_SUMMARY))
    settings.state.value = UserSettings(questionsAfterSet = false)
    val noQuestions = viewModel(FeedStart.TodaySet).state.value
    assertThat(noQuestions.pages.none { it is FeedPage.QuestionPage }).isTrue()
  }

  @Test
  fun anAnswerShowsItsFeedbackAndNothingIsSavedUnlessTapped() {
    arrangeClip()
    sets.todayState.value = todaySet()
    val result = AnswerResult(false, "[ˈwɔɾɚ]", flapping, WatchPreviewData.question.occurrence)
    sets.answerFor = { _, _, _ -> result }
    val vm = viewModel(FeedStart.TodaySet)
    val question = vm.state.value.pages[3] as FeedPage.QuestionPage
    vm.answer(question, "b")
    val answered = vm.state.value.pages[3] as FeedPage.QuestionPage
    assertThat(answered.choice).isEqualTo("b")
    assertThat(answered.result).isEqualTo(result)
    // F-08-AC6: "Save" is offered and nothing is added to Saved unless it is tapped.
    assertThat(saved.savedWords).isEmpty()
    vm.saveAnswerWord(result)
    assertThat(saved.savedWords).containsExactly(WatchPreviewData.question.occurrence.word)
  }

  @Test
  fun aShortOfADeletedClipLeavesTheSetAtTheNextSwipe() {
    arrangeClip()
    sets.todayState.value = todaySet()
    val vm = viewModel(FeedStart.TodaySet)
    vm.onPageSettled(0)
    sets.todayState.value = todaySet(dropped = 1)
    vm.onPageSettled(1)
    assertThat(vm.state.value.shortPages.map { it.setPosition }).containsExactly(0, 2).inOrder()
  }

  @Test
  fun anotherSetReplacesTheFeedWithANewSet() {
    arrangeClip()
    sets.todayState.value = todaySet(completed = true)
    sets.another = todaySet().copy(id = 8, kind = StudySetKind.EXTRA)
    val vm = viewModel(FeedStart.TodaySet)
    val before = vm.state.value.generation
    vm.anotherSet()
    assertThat(vm.state.value.mode).isEqualTo(ModeName.Mix(today = false))
    assertThat(vm.state.value.current).isEqualTo(0)
    assertThat(vm.state.value.generation).isGreaterThan(before)
  }

  // --- Playback (F-06, F-07) ------------------------------------------------------------------------------------

  @Test
  fun speedCyclesAndIsKeptForTheNextShorts() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    vm.cycleSpeed()
    vm.cycleSpeed()
    // F-07-AC3 and F-06-AC8: 0.5× is kept by the pool and remembered.
    assertThat(player.stateFlow.value.speed).isEqualTo(0.5f)
    assertThat(settings.state.value.defaultSpeed).isEqualTo(0.5f)
    vm.onPageSettled(1)
    assertThat(player.stateFlow.value.speed).isEqualTo(0.5f)
    val afterCycle = player.commands.drop(player.commands.indexOf("setSpeed:0.5") + 1)
    assertThat(afterCycle.none { it.startsWith("setSpeed") }).isTrue()
  }

  @Test
  fun aNewFeedStartsAtTheRememberedSpeedAndTrack() {
    settings.state.value = UserSettings(defaultSpeed = 0.75f, track = Track.DIALOGUE)
    arrangeClip()
    viewModel()
    assertThat(player.commands.take(2)).containsExactly("setSpeed:0.75", "setTrack:DIALOGUE").inOrder()
  }

  @Test
  fun aShortReplaysOnceAtItsEndThenStops() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(2)
    player.commands.clear()
    player.stateFlow.value = player.stateFlow.value.copy(playing = false, ended = true)
    // F-07-AC7: the default replays once, then stops on the same short.
    assertThat(player.commands).containsExactly("seekTo:20000", "play").inOrder()
    player.stateFlow.value = player.stateFlow.value.copy(ended = false, playing = true)
    player.commands.clear()
    player.stateFlow.value = player.stateFlow.value.copy(playing = false, ended = true)
    assertThat(player.commands).isEmpty()
  }

  @Test
  fun goToTheNextOneMovesOnAtTheEndAndStopStaysPut() {
    arrangeClip()
    settings.state.value = UserSettings(endOfShort = EndOfShort.NEXT)
    val vm = viewModel()
    vm.onPageSettled(0)
    player.stateFlow.value = player.stateFlow.value.copy(ended = true)
    assertThat(vm.state.value.scroll?.page).isEqualTo(1)
    vm.scrolled(checkNotNull(vm.state.value.scroll))
    assertThat(vm.state.value.scroll).isNull()
    settings.state.value = UserSettings(endOfShort = EndOfShort.STOP)
    vm.onPageSettled(1)
    player.commands.clear()
    player.stateFlow.value = player.stateFlow.value.copy(ended = false)
    player.stateFlow.value = player.stateFlow.value.copy(ended = true)
    assertThat(player.commands).isEmpty()
    assertThat(vm.state.value.scroll).isNull()
  }

  @Test
  fun aLoopingShortNeverReplaysByItself() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    player.stateFlow.value = player.stateFlow.value.copy(loop = LoopMode.SHORT)
    player.commands.clear()
    player.stateFlow.value = player.stateFlow.value.copy(ended = true)
    assertThat(player.commands).isEmpty()
  }

  @Test
  fun aShortIsSeenAtEightyPercentPlayedAndADragDoesNotCount() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    player.stateFlow.value = player.stateFlow.value.copy(playing = true)
    repeat(25) {
      player.position += SeenTracker.PERIOD_MS
      advance(SeenTracker.PERIOD_MS)
    }
    // 5 s of a 10 s short, then a drag to the end: not seen.
    vm.seek(1f)
    player.position = 10_000
    advance(SeenTracker.PERIOD_MS)
    assertThat(progress.seenState.value).isEmpty()
    vm.seek(0f)
    player.position = 0
    repeat(16) {
      player.position += SeenTracker.PERIOD_MS
      advance(SeenTracker.PERIOD_MS)
    }
    // 5 s + 3.2 s = 8.2 s ≥ 80 %: seen (F-06-AC11).
    assertThat(progress.seenState.value).containsExactly(ShortId(job, 0))
  }

  @Test
  fun theFeedTellsThePoolWhenWatchStopsBeingVisible() {
    // Architecture 11.2: the pool releases its decoders while Watch is hidden and prepares again on return.
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    vm.setVisible(false)
    vm.setVisible(false)
    vm.setVisible(true)
    assertThat(player.visibility).containsExactly(false, true).inOrder()
    assertThat(progress.lastFeedState.value?.short).isEqualTo(ShortId(job, 0))
  }

  @Test
  fun theFeedHoldsALeaseOnTheClipOnScreenAndReleasesThePool() {
    arrangeClip()
    arrangeClip(other, count = 2)
    shorts.sequenceFor = { mode -> FeedSequence(mode, shorts.shortsById.value.values.flatten(), 0) }
    val vm = viewModel(FeedStart.ChangeMode(flapping, onlyClip = null))
    vm.onPageSettled(0)
    assertThat(content.openLeases).containsExactly(job)
    vm.onPageSettled(10)
    assertThat(content.openLeases).containsExactly(other)
    assertThat(progress.opened).containsExactly(job, other).inOrder()
  }

  @Test
  fun saveSavesTheShortWithItsSpotlightWordAndUnsaveRemovesIt() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    vm.toggleSave()
    // F-09-AC1: the short is saved with its spotlight word.
    assertThat(saved.savedShorts).containsExactly(ShortId(job, 0))
    saved.items.value = listOf(
      SavedItem(41, SavedKind.SHORT, WordRef(job, 0, 0), ShortId(job, 0), flapping, Instant.EPOCH, null, "Clip", true),
    )
    assertThat(vm.state.value.saved).containsExactly(ShortId(job, 0))
    vm.toggleSave()
    assertThat(saved.items.value).isEmpty()
  }

  @Test
  fun freshMediaReachesThePoolWhenTheClipsArtifactsChange() {
    // F-06-AC13 (ViewModel side): the dialogue arrives → the same queue with fresh media, at the same index.
    arrangeClip()
    clips.details.value = mapOf(job to detail(ArtifactLocalState.DOWNLOADING))
    val vm = viewModel()
    vm.onPageSettled(3)
    val dialogue = File("dialogue.wav")
    content.mediaById[job] = media(job, dialogue)
    player.commands.clear()
    clips.details.value = mapOf(job to detail(ArtifactLocalState.READY))
    assertThat(player.commands).contains("setQueue:10:3")
    assertThat(player.queue.all { it.media.audioDialogue == dialogue }).isTrue()
  }

  @Test
  fun anArrivedTrackReachesThePoolWhileTheShortPlaysSoTheNextShortHasIt() {
    // F-06-AC13: even before the clip's own flow reports it, the neighbours get the fresh media within a second.
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    content.mediaById[job] = media(job, File("dialogue.wav"))
    player.commands.clear()
    advance(SeenTracker.PERIOD_MS * WatchViewModel.REFRESH_TICKS)
    assertThat(player.commands).contains("setQueue:10:0")
  }

  @Test
  fun aNeighbourOfAChangedClipIsPreparedWithItsFreshMedia() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    content.mediaById[job] = media(job, File("dialogue.wav"))
    player.commands.clear()
    vm.onPageSettled(1)
    assertThat(player.commands).contains("setQueue:10:1")
  }

  @Test
  fun theCardsGetTheirClipDataAndACorruptClipSaysSo() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    assertThat(vm.state.value.contents.keys).containsAtLeast(ShortId(job, 0), ShortId(job, 2))
    content.data.remove(job)
    content.failures[job] = com.mapineda48.phonotrainer.core.domain.model.AppError.Content.Corrupt
    val broken = viewModel()
    broken.onPageSettled(0)
    assertThat(broken.state.value.failed).containsExactly(job)
  }

  @Test
  fun theOfflineBadgeShowsForAPairedComputerThatIsNotReachable() {
    arrangeClip()
    val vm = viewModel()
    assertThat(vm.state.value.offline).isFalse()
    computer.pairedState.value = paired()
    computer.statusState.value = ComputerStatus.Offline
    assertThat(vm.state.value.offline).isTrue()
    computer.statusState.value = ComputerStatus.Connected("Desk", 0, 0, false)
    assertThat(vm.state.value.offline).isFalse()
  }

  @Test
  fun thePickerCountsEqualTheChangesShortCounts() {
    // D-48: the mode picker reads ChangeCount.shorts.
    arrangeClip()
    occurrences.counts.value = mapOf(flapping to ChangeCount(shorts = 34, occurrences = 41))
    val vm = viewModel()
    val changes = vm.state.value.picker.families.flatMap { it.changes }
    assertThat(changes.first { it.label == flapping }.shorts).isEqualTo(34)
    assertThat(changes.first { it.label == ChangeLabel("linking") }.shorts).isEqualTo(0)
    assertThat(changes).hasSize(reference.state.value.labels.size)
  }

  @Test
  fun thePickerListsClipsWithWhereTheyWereLeft() {
    arrangeClip()
    clips.all.value = listOf(summary(job, "Two friends"))
    progress.resumeById.value = mapOf(job to ShortId(job, 6))
    val vm = viewModel()
    vm.loadPickerClips()
    assertThat(vm.state.value.picker.clips).containsExactly(PickerClip(job, "Two friends", leftAt = 4))
  }

  @Test
  fun leavingAClipOutOfTheMixIsKeptInSettingsAndReversible() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    vm.setExcluded(true)
    assertThat(settings.state.value.excludedFromMix).containsExactly(job)
    assertThat(vm.state.value.currentExcluded).isTrue()
    vm.setExcluded(false)
    assertThat(settings.state.value.excludedFromMix).isEmpty()
  }

  @Test
  fun theWordAndItsSlowReplayPlayTheSpotlightSpan() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    val slip = checkNotNull(vm.state.value.contents[ShortId(job, 0)]?.spotlight)
    vm.playWord()
    assertThat(player.commands.last()).isEqualTo("playSpan:${slip.span.startMs}-${slip.span.endMs}")
    vm.playWordSlowly()
    assertThat(spanPlayer.calls.last().speed).isEqualTo(0.5f)
    assertThat(spanPlayer.calls.last().span).isEqualTo(slip.span)
  }

  @Test
  fun aLongPressLoopsThePhraseUntilPressedAgain() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    vm.loopPhrase(1)
    assertThat(player.stateFlow.value.loop).isEqualTo(LoopMode.PHRASE)
    assertThat(player.commands).contains("seekTo:2000")
    vm.loopPhrase(1)
    assertThat(player.stateFlow.value.loop).isEqualTo(LoopMode.OFF)
  }

  @Test
  fun shadowingRunsOnTheCurrentPhraseAndStopsWithTheSheet() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    vm.startShadowing(0.75f)
    assertThat(shadowing.calls.single().span.jobId).isEqualTo(job)
    assertThat(shadowing.calls.single().slowSpeed).isEqualTo(0.75f)
    vm.stopShadowing()
    assertThat(shadowing.stops).isEqualTo(1)
  }

  @Test
  fun listenFirstShowsAPhraseOnceItHasPlayed() {
    settings.state.value = UserSettings(listenFirst = true)
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    assertThat(vm.state.value.revealed).isEmpty()
    player.position = 1_300
    advance(SeenTracker.PERIOD_MS)
    assertThat(vm.state.value.revealed).containsExactly(0)
    vm.revealWords()
    assertThat(vm.state.value.revealed).containsExactly(0, 1)
  }

  @Test
  fun theCoachMarksComeOneAtATimeAndGoWhenDismissed() {
    arrangeClip()
    val vm = viewModel()
    vm.onPageSettled(0)
    assertThat(vm.state.value.coachMark).isEqualTo(WatchCoachMark.SWIPE)
    vm.dismissCoachMark(WatchCoachMark.SWIPE)
    assertThat(vm.state.value.coachMark).isEqualTo(WatchCoachMark.TAP_WORD)
  }

  @Test
  fun recordYourselfIsOfferedOnlyWhenItsModuleIsPresent() {
    arrangeClip()
    assertThat(viewModel().state.value.canRecord).isFalse()
    assertThat(viewModel().state.value.canPhraseDetails).isFalse()
  }

  private fun paired() = PairedComputer(
    serverId = ServerId("server"),
    name = "Desk",
    deviceId = DeviceId("device"),
    deviceName = "Test phone",
    address = HostPort("192.0.2.10", 8765),
    libraryId = LibraryId("library"),
    pairedAt = Instant.EPOCH,
    lastSeen = null,
    lastSync = null,
    apiVersion = 6,
    capabilities = null,
  )

  private fun summary(id: JobId, title: String) = ClipSummary(
    id = id,
    title = title,
    durationS = 60.0,
    words = 100,
    changeCount = 10,
    source = ClipSource(SourceKind.LINK, null, null, null),
    availability = ClipAvailability.OnThisPhone,
    video = VideoAvailability.AudioOnlyClip,
    kept = false,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = 0,
    shortsTotal = 10,
    addedAt = Instant.EPOCH,
  )

  private fun detail(dialogue: ArtifactLocalState) = ClipDetail(
    summary = summary(job, "Two friends"),
    durationS = 60.0,
    segments = 20,
    changeCounts = emptyMap(),
    rulesVersion = 3,
    phoneEngine = "timit61",
    dialogueSeparation = true,
    hasDialogueTrack = true,
    studyPackBytes = null,
    videoBytes = null,
    lastWatched = null,
    resume = null,
    computerError = null,
    artifacts = listOf(ArtifactProgress(ArtifactRole.AUDIO_DIALOGUE, dialogue, null, null, 0)),
  )
}
