package com.mapineda48.phonotrainer.feature.lesson

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ShadowingRunner
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeDidacticContent
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeShadowingRunner
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeSpanPlayer
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSavedRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import com.mapineda48.phonotrainer.core.ui.extension.RecordSection
import java.time.Instant
import java.util.Optional
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The lesson's ViewModel on the settable fakes of work-plan 4.6 (spec F-10, F-12; architecture 3.1, 16.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class LessonViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val content = FakeClipContentRepository().apply {
    data[LessonFixtures.JOB] = LessonFixtures.clip
    mediaById[LessonFixtures.JOB] = LessonFixtures.media(dialogue = true)
    compare[LessonFixtures.DID] = LessonFixtures.didRows
    compare[LessonFixtures.WATER] = LessonFixtures.waterRows
  }
  private val clips = FakeClipRepository()
  private val spanPlayer = FakeSpanPlayer()
  private val shadowing = FakeShadowingRunner()
  private val saved = FakeSavedRepository()
  private val settings = FakeSettingsRepository()
  private val reference = FakeReferenceRepository()
  private val didactic = FakeDidacticContent()
  private val store = ViewModelStore()
  private var created = 0

  private fun key(word: WordRef, presentation: LessonPresentation = LessonPresentation.SHEET, origin: LessonOrigin = LessonOrigin.FEED) =
    LessonKey(word.jobId, word.segment, word.word, origin, presentation)

  /** A ViewModel in a store (so `store.clear()` runs its onCleared, as popping its entry does). */
  private fun viewModel(
    key: LessonKey = key(LessonFixtures.WATER),
    handle: SavedStateHandle = SavedStateHandle(),
    runner: ShadowingRunner = shadowing,
  ): LessonViewModel {
    val vm = LessonViewModel(
      key, handle, content, clips, spanPlayer, runner, saved, settings, reference, didactic,
      Optional.empty<MouthRenderer>(), Optional.empty<RecordSection>(),
    )
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = vm as T
    }
    return ViewModelProvider.create(store, factory)["lesson-${created++}", LessonViewModel::class]
  }

  /** Collects the state while [block] runs (it is `WhileSubscribed`), and gives the current Ready state. */
  private fun TestScope.ready(vm: LessonViewModel): () -> LessonUiState.Ready {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    return { vm.state.value as LessonUiState.Ready }
  }

  @Test
  fun itOpensOnTheKeysWordWithFullGuidanceStepsOneToFourOpen() = runTest {
    val vm = viewModel()
    val state = ready(vm)
    assertThat(state().header.ref).isEqualTo(LessonFixtures.WATER)
    assertThat(state().header.text).isEqualTo("water")
    assertThat(state().header.position).isEqualTo(5)
    assertThat(state().header.total).isEqualTo(9)
    assertThat(state().header.phrase).isEqualTo(1)
    assertThat(state().open).containsExactly(LessonStep.LISTEN, LessonStep.COMPARE, LessonStep.WHY, LessonStep.SEE)
    assertThat(state().header.changes.map { it.label.value }).containsExactly("flapping")
    assertThat(state().compare.sorts.map { it.status })
      .containsExactly(CompareStatus.SAME, CompareStatus.SAME, CompareStatus.CHANGED, CompareStatus.SAME).inOrder()
  }

  @Test
  fun compactGuidanceOpensWithOnlyTheTitles() = runTest {
    settings.state.value = UserSettings(lessonGuidance = LessonGuidance.COMPACT)
    val vm = viewModel()
    val state = ready(vm)
    // F-10-AC3: only the five titles; each opens on tap.
    assertThat(state().open).isEmpty()
    vm.toggleStep(LessonStep.WHY)
    assertThat(state().open).containsExactly(LessonStep.WHY)
    assertThat(state().at).isEqualTo(LessonStep.WHY)
    vm.toggleStep(LessonStep.WHY)
    assertThat(state().open).isEmpty()
  }

  @Test
  fun nextChangeMovesToTheNextWordWithAChangeEvenInALaterPhrase() = runTest {
    val vm = viewModel(key(LessonFixtures.YOU))
    val state = ready(vm)
    // F-10-AC5: "get" and "the" have no change, "water" has one.
    vm.nextChange()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.WATER)
    // From the last word of the phrase it crosses into the next phrase.
    vm.nextChange()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.GONNA)
    vm.nextChange()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.GO)
    // No change after it: the button is disabled and does nothing.
    assertThat(state().header.canNextChange).isFalse()
    vm.nextChange()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.GO)
  }

  @Test
  fun previousAndNextWalkEveryWordAndSkipAPhraseWithoutWords() = runTest {
    val vm = viewModel(key(LessonFixtures.DID))
    val state = ready(vm)
    assertThat(state().header.canPrevious).isFalse()
    vm.previous()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.DID)
    repeat(4) { vm.next() }
    assertThat(state().header.ref).isEqualTo(LessonFixtures.WATER)
    vm.next()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.IM)
    repeat(2) { vm.next() }
    vm.next()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.OK)
    assertThat(state().header.canNext).isFalse()
    vm.previous()
    assertThat(state().header.ref).isEqualTo(LessonFixtures.GO)
  }

  @Test
  fun anotherWordResetsWhatBelongsToAWord() = runTest {
    val vm = viewModel(key(LessonFixtures.DID))
    val state = ready(vm)
    vm.toggleStep(LessonStep.PRACTICE)
    vm.pickSort(2)
    vm.play(ListenTarget.WORD)
    vm.setSpeed(0.5f)
    assertThat(state().compare.selected).isNotNull()
    assertThat(state().listen.played).isEqualTo(1)
    vm.nextChange()
    // Spec F-10 item 3: the state resets per word (steps back to the guidance default, no selection, no plays);
    // the learner's speed stays.
    assertThat(state().header.ref).isEqualTo(LessonFixtures.YOU)
    assertThat(state().open).containsExactly(LessonStep.LISTEN, LessonStep.COMPARE, LessonStep.WHY, LessonStep.SEE)
    assertThat(state().compare.selected).isNull()
    assertThat(state().listen.played).isEqualTo(0)
    assertThat(state().listen.speed).isEqualTo(0.5f)
  }

  @Test
  fun aProcessDeathAtStepThreeComesBackOnTheSameWordAtStepThree() = runTest {
    settings.state.value = UserSettings(lessonGuidance = LessonGuidance.COMPACT)
    val handle = SavedStateHandle()
    val first = viewModel(key(LessonFixtures.YOU), handle)
    val before = ready(first)
    first.nextChange()
    first.toggleStep(LessonStep.WHY)
    assertThat(before().header.ref).isEqualTo(LessonFixtures.WATER)

    // F-10-AC6: what the entry's SavedStateHandle kept is all a new process has.
    val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
    val second = viewModel(key(LessonFixtures.YOU), restored)
    val after = ready(second)
    assertThat(after().header.ref).isEqualTo(LessonFixtures.WATER)
    assertThat(after().open).containsExactly(LessonStep.WHY)
    assertThat(after().at).isEqualTo(LessonStep.WHY)
  }

  @Test
  fun theSavedStateKeepsTheChoicesOfTheLesson() = runTest {
    val handle = SavedStateHandle()
    val first = viewModel(key(LessonFixtures.DID), handle)
    ready(first)
    first.setSpeed(0.75f)
    first.setLoop(true)
    first.setTrack(Track.DIALOGUE)
    first.pickSort(3)
    first.setPractice(ShadowingTarget.WORD, 0.5f)
    first.setStream(MouthStream.DICTIONARY)
    val second = viewModel(key(LessonFixtures.DID), SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
    val state = ready(second)
    assertThat(state().listen.speed).isEqualTo(0.75f)
    assertThat(state().listen.loop).isTrue()
    assertThat(state().listen.track).isEqualTo(Track.DIALOGUE)
    assertThat(state().compare.selected?.sort?.index).isEqualTo(3)
    assertThat(state().practice.target).isEqualTo(ShadowingTarget.WORD)
    assertThat(state().practice.slowSpeed).isEqualTo(0.5f)
    assertThat(state().see.stream).isEqualTo(MouthStream.DICTIONARY)
  }

  @Test
  fun playTheWordPlaysItPaddedAtTheChosenSpeedAndTrack() = runTest {
    val vm = viewModel()
    val state = ready(vm)
    vm.setSpeed(0.5f)
    vm.setTrack(Track.DIALOGUE)
    vm.play(ListenTarget.WORD)
    // "water?" is 0.75 to 0.98 s: 20 ms before, 60 ms after (web `wordSpan`).
    val call = spanPlayer.calls.single()
    assertThat(call.span).isEqualTo(ClipSpan(LessonFixtures.JOB, 730, 1_040, LessonFixtures.WATER))
    assertThat(call.speed).isEqualTo(0.5f)
    assertThat(call.track).isEqualTo(Track.DIALOGUE)
    assertThat(call.loop).isFalse()
    assertThat(state().listen.played).isEqualTo(1)
  }

  @Test
  fun playWithTheNextWordAndThePhrase() = runTest {
    val vm = viewModel(key(LessonFixtures.DID))
    val state = ready(vm)
    assertThat(state().listen.nextText).isEqualTo("you")
    vm.setLoop(true)
    vm.play(ListenTarget.WITH_NEXT)
    vm.play(ListenTarget.PHRASE)
    // "did" (0 to 0.17 s) through "you" (0.2 to 0.31 s), then the whole phrase unpadded.
    assertThat(spanPlayer.calls.map { it.span }).containsExactly(
      ClipSpan(LessonFixtures.JOB, 0, 370, LessonFixtures.DID),
      ClipSpan(LessonFixtures.JOB, 0, 1_100, null),
    ).inOrder()
    assertThat(spanPlayer.calls.all { it.loop }).isTrue()
    assertThat(state().listen.played).isEqualTo(2)
  }

  @Test
  fun aWordThatDoesNotCrossItsBoundaryHasNoPlayWithTheNext() = runTest {
    val vm = viewModel(key(LessonFixtures.GET))
    val state = ready(vm)
    assertThat(state().listen.nextText).isNull()
    assertThat(state().compare.gap).isNull()
    vm.play(ListenTarget.WITH_NEXT)
    assertThat(spanPlayer.calls).isEmpty()
  }

  @Test
  fun aBoundaryChangeComparesBothWordsAndMeasuresTheGap() = runTest {
    val vm = viewModel(key(LessonFixtures.DID))
    val state = ready(vm)
    val compare = state().compare
    assertThat(compare.dictionary).isEqualTo("dɪd juː")
    assertThat(compare.said).isEqualTo("dɪdʒ‿ə")
    assertThat(compare.nextText).isEqualTo("you")
    assertThat(compare.sorts.filter { it.nextWord }.map { it.status }).containsExactly(CompareStatus.NOT_HEARD, CompareStatus.CHANGED).inOrder()
    // Last heard sound of "did" ends at 0.17 s, the first of "you" starts at 0.2 s.
    assertThat(compare.gap?.ms).isEqualTo(30)
  }

  @Test
  fun pickingASortSelectsItAndPlaysItsSound() = runTest {
    val vm = viewModel()
    val state = ready(vm)
    vm.pickSort(2)
    val sound = checkNotNull(state().compare.selected)
    assertThat(sound.symbol).isEqualTo("ɾ")
    assertThat(sound.name).isEqualTo("alveolar tap")
    assertThat(sound.explainKey).isEqualTo("ipa:ɾ")
    // The heard [ɾ] at 0.87 to 0.92 s, 20 ms around it.
    assertThat(spanPlayer.calls.single().span).isEqualTo(ClipSpan(LessonFixtures.JOB, 850, 940, LessonFixtures.WATER))
    vm.playSelectedSound()
    assertThat(spanPlayer.calls).hasSize(2)
  }

  @Test
  fun seeItInTheMouthOpensStepFourHoldingTheSound() = runTest {
    settings.state.value = UserSettings(lessonGuidance = LessonGuidance.COMPACT)
    val vm = viewModel()
    val state = ready(vm)
    vm.pickSort(2)
    val reveal = state().reveal
    vm.seeSelectedInMouth()
    assertThat(state().open).contains(LessonStep.SEE)
    assertThat(state().at).isEqualTo(LessonStep.SEE)
    assertThat(state().reveal).isGreaterThan(reveal)
    assertThat(state().see.stream).isEqualTo(MouthStream.SAID)
    assertThat(state().see.held).isEqualTo(2)
    assertThat(state().see.sounds[2].symbol).isEqualTo("ɾ")
    vm.follow()
    assertThat(state().see.held).isNull()
  }

  @Test
  fun aDroppedSoundIsShownInTheDictionaryRow() = runTest {
    val vm = viewModel(key(LessonFixtures.DID))
    val state = ready(vm)
    vm.pickSort(3)
    vm.seeSelectedInMouth()
    assertThat(state().see.stream).isEqualTo(MouthStream.DICTIONARY)
    assertThat(state().see.sounds[checkNotNull(state().see.held)].symbol).isEqualTo("j")
  }

  @Test
  fun stepFourHoldsAndPlaysASound() = runTest {
    val vm = viewModel()
    val state = ready(vm)
    vm.holdSound(1)
    assertThat(state().see.held).isEqualTo(1)
    assertThat(spanPlayer.calls.single().span.startMs).isEqualTo(790)
    vm.setStream(MouthStream.DICTIONARY)
    assertThat(state().see.held).isNull()
    assertThat(state().see.sounds.map { it.symbol }).containsExactly("w", "ɔ", "t", "ɚ").inOrder()
  }

  @Test
  fun shadowingStartsOnThePhraseWhenItIsShortAndOnTheWordOtherwise() = runTest {
    val short = viewModel(key(LessonFixtures.WATER))
    val shortState = ready(short)
    assertThat(shortState().practice.target).isEqualTo(ShadowingTarget.PHRASE)
    short.startShadowing()
    assertThat(shadowing.calls.single().span).isEqualTo(ClipSpan(LessonFixtures.JOB, 0, 1_100, null))
    assertThat(shadowing.calls.single().slowSpeed).isEqualTo(0.75f)

    val long = viewModel(key(LessonFixtures.IM))
    val longState = ready(long)
    // Phrase 2 lasts 7 s: shadowing starts from the word and the next one.
    assertThat(longState().practice.target).isEqualTo(ShadowingTarget.WORD)
    assertThat(longState().practice.wordAndNext).isTrue()
    long.setPractice(ShadowingTarget.WORD, 0.5f)
    long.startShadowing()
    val run = shadowing.calls.last()
    assertThat(run.span).isEqualTo(ClipSpan(LessonFixtures.JOB, 2_480, 2_990, LessonFixtures.IM))
    assertThat(run.slowSpeed).isEqualTo(0.5f)
    assertThat(run.track).isEqualTo(Track.MIX)
  }

  @Test
  fun theRunnersStateShowsOnlyWhileThisLessonShadows() = runTest {
    val runner = HoldingRunner()
    val vm = viewModel(runner = runner)
    val state = ready(vm)
    // Someone else's loop (the feed's rail) is not this lesson's.
    runner.states.value = ShadowingState.Running(1, 4, ShadowingPhase.LISTEN, 0.75f)
    assertThat(state().practice.shadowing).isEqualTo(ShadowingState.Idle)
    vm.startShadowing()
    assertThat(state().practice.shadowing).isEqualTo(ShadowingState.Running(1, 4, ShadowingPhase.LISTEN, 0.75f))
    // Playing something stops the loop (one sound at a time).
    vm.play(ListenTarget.WORD)
    assertThat(runner.stops).isEqualTo(1)
    assertThat(state().practice.shadowing).isEqualTo(ShadowingState.Idle)
    vm.startShadowing()
    vm.stopShadowing()
    assertThat(runner.stops).isEqualTo(2)
  }

  @Test
  fun closingTheLessonStopsItsSoundAndReleasesTheClip() = runTest {
    val runner = HoldingRunner()
    val vm = viewModel(runner = runner)
    ready(vm)
    assertThat(content.leaseCount(LessonFixtures.JOB)).isEqualTo(1)
    vm.startShadowing()
    // F-12 States: closing the lesson stops shadowing (the runner restores the speed); the deferred removal can go.
    store.clear()
    assertThat(runner.stops).isEqualTo(1)
    assertThat(content.leaseCount(LessonFixtures.JOB)).isEqualTo(0)
  }

  @Test
  fun movingToAnotherWordStopsShadowing() = runTest {
    val runner = HoldingRunner()
    val vm = viewModel(runner = runner)
    ready(vm)
    vm.startShadowing()
    vm.next()
    assertThat(runner.stops).isEqualTo(1)
  }

  @Test
  fun saveThisWordSavesItAndUnsavesIt() = runTest {
    val vm = viewModel()
    val state = ready(vm)
    assertThat(state().header.saved).isFalse()
    vm.toggleSaved()
    assertThat(saved.savedWords).containsExactly(LessonFixtures.WATER)
    saved.items.value = listOf(savedWord(7, LessonFixtures.WATER))
    assertThat(state().header.saved).isTrue()
    vm.toggleSaved()
    assertThat(saved.items.value).isEmpty()
    assertThat(state().header.saved).isFalse()
  }

  private fun savedWord(id: Long, word: WordRef) = SavedItem(
    id = id, kind = SavedKind.WORD, word = word, short = ShortId(word.jobId, word.segment), change = null,
    savedAt = Instant.EPOCH, lastWatchedAt = null, clipTitle = "A clip", available = true,
  )

  @Test
  fun withoutAudioNothingPlaysAndTheStepsSaySo() = runTest {
    content.mediaById.clear()
    val vm = viewModel()
    val state = ready(vm)
    assertThat(state().listen.hasAudio).isFalse()
    assertThat(state().practice.hasAudio).isFalse()
    vm.play(ListenTarget.WORD)
    vm.pickSort(2)
    vm.startShadowing()
    assertThat(spanPlayer.calls).isEmpty()
    assertThat(shadowing.calls).isEmpty()
    // Steps 2 to 4 stay readable.
    assertThat(state().compare.sorts).hasSize(4)
  }

  @Test
  fun theTrackChoiceIsOfferedOnlyWhenADialogueTrackIsOnThePhone() = runTest {
    settings.state.value = UserSettings(track = Track.DIALOGUE)
    content.mediaById[LessonFixtures.JOB] = LessonFixtures.media(dialogue = false)
    val vm = viewModel()
    val state = ready(vm)
    assertThat(state().listen.dialogue).isFalse()
    assertThat(state().listen.track).isEqualTo(Track.MIX)
  }

  @Test
  fun aDamagedOrMissingClipOrAWordNoLongerThereIsSaid() = runTest {
    content.failures[LessonFixtures.JOB] = AppError.Content.Corrupt
    content.data.clear()
    val damaged = viewModel()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { damaged.state.collect {} }
    assertThat(damaged.state.value).isEqualTo(LessonUiState.Unavailable(Unavailable.CLIP_DAMAGED))

    content.failures.clear()
    val gone = viewModel()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gone.state.collect {} }
    assertThat(gone.state.value).isEqualTo(LessonUiState.Unavailable(Unavailable.CLIP_GONE))

    content.data[LessonFixtures.JOB] = LessonFixtures.clip
    val wordGone = viewModel(key(WordRef(LessonFixtures.JOB, 0, 9)))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { wordGone.state.collect {} }
    assertThat(wordGone.state.value).isEqualTo(LessonUiState.Unavailable(Unavailable.WORD_GONE))
  }

  @Test
  fun overTheFeedThePausedPillNamesTheClip() = runTest {
    clips.details.value = mapOf(LessonFixtures.JOB to detail("Two friends at the lake"))
    val sheet = viewModel()
    assertThat(ready(sheet)().paused).isEqualTo(PausedUi("Two friends at the lake"))
    val full = viewModel(key(LessonFixtures.WATER, LessonPresentation.FULL, LessonOrigin.CLIP_PAGE))
    assertThat(ready(full)().paused).isNull()
  }

  private fun detail(title: String): ClipDetail = LessonTestClips.detail(LessonFixtures.JOB, title)

  @Test
  fun stepThreeExplainsEachChangeWithItsAdviceAndTheFooterFacts() = runTest {
    val vm = viewModel(key(LessonFixtures.GONNA))
    val state = ready(vm)
    val card = state().why.cards.single()
    assertThat(card.change.name).isEqualTo("lexical contraction")
    assertThat(card.lexicalForm).isEqualTo("gonna")
    // A lexical contraction takes its own reduced form's advice (web `adviceFor`).
    assertThat(card.change.advice).isEqualTo(reference.state.value.lexicalPractice["gonna"])
    assertThat(card.change.advice).isNotNull()
    vm.next()
    // "go": the recognizer was unsure; the header badge and the notice say so.
    assertThat(state().header.reliability).isEqualTo(Reliability.LOW_CONFIDENCE)
    assertThat(state().why.reliability).isEqualTo(Reliability.LOW_CONFIDENCE)
    vm.previous()
    vm.previous()
    // "I'm": no change, said as the dictionary says it.
    assertThat(state().why.noChange).isEqualTo(NoChange.SAME)
  }

  @Test
  fun aLinkingWordShowsItsKindOfLinkAndTheFooterFacts() = runTest {
    val vm = viewModel(key(LessonFixtures.DID))
    val state = ready(vm)
    val why = state().why
    assertThat(why.prominence).isEqualTo(30)
    assertThat(why.divergence).isEqualTo(0.0)
    assertThat(state().header.advice?.advice).isEqualTo(com.mapineda48.phonotrainer.core.domain.model.Advice.PRODUCE)
    assertThat(state().practice.recognizeOnly).isFalse()
  }

  /** A runner whose `run` suspends until stopped, like the real one. */
  private class HoldingRunner : ShadowingRunner {
    val states = MutableStateFlow<ShadowingState>(ShadowingState.Idle)
    override val state: StateFlow<ShadowingState> = states
    var stops = 0
    private var running: CompletableDeferred<Unit>? = null

    override suspend fun run(span: ClipSpan, slowSpeed: Float, track: Track) {
      val done = CompletableDeferred<Unit>()
      running = done
      try {
        done.await()
      } finally {
        running = null
      }
    }

    override fun stop() {
      stops++
      running?.complete(Unit)
    }
  }
}
