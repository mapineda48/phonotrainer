package com.mapineda48.phonotrainer.feature.practice

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeSpanPlayer
import com.mapineda48.phonotrainer.core.testing.fake.player.SpanPlay
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSavedRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The Practice tab's ViewModel (spec F-17) over WP-14's contract ([ScriptedPracticeRepository]) and the settable fakes
 * of work-plan 4.6: start, answer, next, results, focus and weak spots, playback, "Save", reset, the empty and
 * exhausted pools, and a process death mid-session that comes back on the same question.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val flapping = ChangeLabel("flapping")
  private val practice = ScriptedPracticeRepository()
  private val saved = FakeSavedRepository()
  private val occurrences = FakeOccurrenceRepository()
  private val reference = FakeReferenceRepository()
  private val player = FakeSpanPlayer()
  private val settings = FakeSettingsRepository(UserSettings(track = Track.DIALOGUE))

  /** Ten questions: the three kinds in turn, from the preview's word. */
  private val ten: List<Question> = List(PracticeLogic.SESSION_LENGTH) { i ->
    val base = listOf(PracticePreviewData.formQuestion, PracticePreviewData.changeQuestion, PracticePreviewData.countQuestion)[i % 3]
    base.copy(key = "${base.key}:$i")
  }

  init {
    occurrences.counts.value = mapOf(flapping to ChangeCount(shorts = 3, occurrences = 12))
    practice.questions = { _, _ -> ten }
  }

  private fun TestScope.viewModel(key: PracticeKey = PracticeKey(), handle: SavedStateHandle = SavedStateHandle()): PracticeViewModel {
    val vm = PracticeViewModel(key, handle, practice, saved, occurrences, reference, player, settings)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    return vm
  }

  @Test
  fun theStartScreenShowsTheProgressWeakestFirst() = runTest {
    practice.accuracyState.value = listOf(
      ChangeAccuracy(flapping, seen = 10, correct = 7),
      ChangeAccuracy(ChangeLabel("t_deletion"), seen = 9, correct = 4),
      ChangeAccuracy(ChangeLabel("linking"), seen = 0, correct = 0),
    )
    practice.met.value = setOf(flapping, ChangeLabel("t_deletion"))
    val vm = viewModel()
    val state = vm.state.value
    assertThat(state.page).isEqualTo(PracticePage.START)
    assertThat(state.progress.rows.map { it.change.label.value }).containsExactly("t_deletion", "flapping").inOrder()
    assertThat(state.progress.met).isEqualTo(2)
    assertThat(state.progress.total).isEqualTo(16)
    assertThat(state.progress.notMet.map { it.label }).doesNotContain(flapping)
  }

  @Test
  fun aSessionRunsQuestionByQuestionToItsResults() = runTest {
    val vm = viewModel()
    vm.start()
    var state = vm.state.value
    assertThat(state.page).isEqualTo(PracticePage.SESSION)
    assertThat(state.session?.number).isEqualTo(1)
    assertThat(state.session?.of).isEqualTo(10)
    assertThat(state.session?.chosen).isNull()

    // A wrong answer: the feedback shows on the same question, and the accuracy updates (F-17-AC4).
    vm.answer("dictionary")
    state = vm.state.value
    assertThat(state.session?.number).isEqualTo(1)
    assertThat(state.session?.chosen).isEqualTo("dictionary")
    assertThat(state.session?.feedback?.result?.correct).isFalse()
    assertThat(state.session?.wrong).isEqualTo(1)
    assertThat(practice.accuracyState.value.single { it.change == flapping }.seen).isEqualTo(1)

    // A second tap on an answered question changes nothing.
    vm.answer("said")
    assertThat(practice.answered).hasSize(1)

    repeat(9) { i ->
      vm.next()
      assertThat(vm.state.value.session?.number).isEqualTo(i + 2)
      val question = checkNotNull(vm.state.value.session).question
      vm.answer(question.answer)
    }
    assertThat(vm.state.value.session?.last).isTrue()
    vm.next()
    state = vm.state.value
    assertThat(state.page).isEqualTo(PracticePage.RESULTS)
    val results = checkNotNull(state.results)
    assertThat(results.right).isEqualTo(9)
    assertThat(results.total).isEqualTo(10)
    assertThat(results.partial).isFalse()
    assertThat(results.missed.map { it.label }).containsExactly(flapping)
    assertThat(results.lines).hasSize(10)
  }

  @Test
  fun nextIsIgnoredUntilTheQuestionIsAnswered() = runTest {
    val vm = viewModel()
    vm.start()
    vm.next()
    assertThat(vm.state.value.session?.number).isEqualTo(1)
  }

  @Test
  fun theFocusOfTheKeyStartsAFocusedSessionUntilItIsSetAside() = runTest {
    val vm = viewModel(PracticeKey(focus = "flapping"))
    val focus = vm.state.value.start.focus as FocusUi.Known
    assertThat(focus.change.name).isEqualTo("flapping")
    assertThat(focus.playable).isTrue()
    vm.setWeakSpots(true)
    vm.start()
    assertThat(practice.started.last()).isEqualTo(flapping to true)

    vm.backToStart()
    vm.clearFocus()
    assertThat(vm.state.value.start.focus).isNull()
    vm.start()
    assertThat(practice.started.last()).isEqualTo(null to true)
  }

  @Test
  fun aFocusWithoutExamplesOffersNoStartAndAnUnknownOnePractisesEverything() = runTest {
    val none = viewModel(PracticeKey(focus = "h_dropping"))
    assertThat((none.state.value.start.focus as FocusUi.Known).playable).isFalse()
    assertThat(none.state.value.start.canStart).isFalse()

    val unknown = viewModel(PracticeKey(focus = "flap"))
    assertThat(unknown.state.value.start.focus).isEqualTo(FocusUi.Unknown("flap"))
    unknown.start()
    assertThat(practice.started.last()).isEqualTo(null to false)
  }

  @Test
  fun practiceMyWeakSpotsStartsAgainWithTheSwitchOn() = runTest {
    practice.questions = { _, _ -> ten.take(1) }
    val vm = viewModel()
    vm.start()
    vm.answer("said")
    vm.next()
    assertThat(vm.state.value.page).isEqualTo(PracticePage.RESULTS)
    vm.startWeakSpots()
    assertThat(practice.started.last()).isEqualTo(null to true)
    assertThat(vm.state.value.start.weakSpots).isTrue()
    assertThat(vm.state.value.page).isEqualTo(PracticePage.SESSION)
  }

  @Test
  fun aProcessDeathMidSessionComesBackOnTheSameQuestion() = runTest {
    val handle = SavedStateHandle()
    val before = viewModel(handle = handle)
    before.start()
    before.answer("said")
    before.next()
    before.answer("linking")
    // The process dies with question 2 answered and its feedback on screen; the saved state and learning.db stay.
    val restored = viewModel(handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
    val session = checkNotNull(restored.state.value.session)
    assertThat(restored.state.value.page).isEqualTo(PracticePage.SESSION)
    assertThat(session.number).isEqualTo(2)
    assertThat(session.chosen).isEqualTo("linking")
    assertThat(session.feedback?.result?.correct).isFalse()
    restored.next()
    assertThat(restored.state.value.session?.number).isEqualTo(3)
    assertThat(restored.state.value.session?.chosen).isNull()
  }

  @Test
  fun aSessionLeftInAnotherVisitIsOfferedAsContinue() = runTest {
    val first = viewModel()
    first.start()
    first.answer("said")
    first.next()
    first.answer("flapping")
    first.backToStart()
    assertThat(first.state.value.start.resume).isEqualTo(ResumeUi(number = 3, of = 10))

    // A new visit (no saved state): the start screen, with "Continue" at the first unanswered question.
    val second = viewModel()
    assertThat(second.state.value.page).isEqualTo(PracticePage.START)
    assertThat(second.state.value.start.resume).isEqualTo(ResumeUi(number = 3, of = 10))
    second.resume()
    assertThat(second.state.value.page).isEqualTo(PracticePage.SESSION)
    assertThat(second.state.value.session?.number).isEqualTo(3)
    assertThat(second.state.value.start.resume).isNull()
  }

  @Test
  fun theButtonsPlayTheQuestionAtItsSpeedOnTheLearnersTrack() = runTest {
    val vm = viewModel()
    vm.start()
    val question = PracticePreviewData.formQuestion
    vm.play(Playback.NORMAL)
    vm.play(Playback.SLOW)
    vm.play(Playback.CONTEXT)
    assertThat(player.calls).containsExactly(
      SpanPlay(question.clip, 1f, Track.DIALOGUE, false),
      SpanPlay(question.clip, 0.5f, Track.DIALOGUE, false),
      SpanPlay(checkNotNull(question.context), 1f, Track.DIALOGUE, false),
    ).inOrder()
    vm.answer("said")
    val stops = player.stops
    vm.next()
    assertThat(player.stops).isGreaterThan(stops)
  }

  @Test
  fun saveSavesTheAnsweredWordOnlyWhenTapped() = runTest {
    val vm = viewModel()
    vm.start()
    vm.saveWord()
    assertThat(saved.savedWords).isEmpty()
    vm.answer("dictionary")
    assertThat(saved.savedWords).isEmpty()
    vm.saveWord()
    assertThat(saved.savedWords).containsExactly(PracticePreviewData.water)
  }

  @Test
  fun resetClearsTheAccuracy() = runTest {
    practice.accuracyState.value = listOf(ChangeAccuracy(flapping, seen = 4, correct = 1))
    val vm = viewModel()
    assertThat(vm.state.value.progress.rows).hasSize(1)
    vm.reset()
    assertThat(practice.resets).isEqualTo(1)
    assertThat(vm.state.value.progress.rows).isEmpty()
  }

  @Test
  fun noClipsMeansNothingToPractice() = runTest {
    occurrences.counts.value = emptyMap()
    val vm = viewModel()
    assertThat(vm.state.value.page).isEqualTo(PracticePage.EMPTY)
    // Only elided words leave nothing to ask either (web quizPhenomena).
    occurrences.counts.value = mapOf(ChangeLabel("word_elision") to ChangeCount(1, 5))
    assertThat(vm.state.value.page).isEqualTo(PracticePage.EMPTY)
  }

  @Test
  fun aPoolThatRunsOutEarlyEndsWithThePartialLine() = runTest {
    practice.questions = { _, _ -> ten.take(2) }
    val vm = viewModel()
    vm.start()
    assertThat(vm.state.value.session?.of).isEqualTo(2)
    vm.answer("said")
    vm.next()
    vm.answer("flapping")
    vm.next()
    val results = checkNotNull(vm.state.value.results)
    assertThat(results.partial).isTrue()
    assertThat(results.right).isEqualTo(2)

    // No question at all: straight to the results, which say why.
    practice.questions = { _, _ -> emptyList() }
    vm.start()
    assertThat(vm.state.value.page).isEqualTo(PracticePage.RESULTS)
    assertThat(vm.state.value.results?.total).isEqualTo(0)
    assertThat(vm.state.value.results?.partial).isTrue()
  }

  @Test
  fun aChangeQuestionCarriesItsOptionsFamilies() = runTest {
    practice.questions = { _, _ -> listOf(PracticePreviewData.changeQuestion) }
    val vm = viewModel()
    vm.start()
    val session = checkNotNull(vm.state.value.session)
    assertThat(session.question.kind).isEqualTo(QuestionKind.CHANGE)
    assertThat(session.families).containsEntry("linking", com.mapineda48.phonotrainer.core.domain.model.Family.BOUNDARY)
  }
}
