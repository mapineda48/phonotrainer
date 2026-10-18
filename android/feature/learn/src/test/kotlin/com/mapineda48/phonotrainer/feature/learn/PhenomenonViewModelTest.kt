package com.mapineda48.phonotrainer.feature.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRowPlayback
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** A phenomenon page's ViewModel on the settable fakes of work-plan 4.6 (spec F-15 item 2, 3 and its states). */
@OptIn(ExperimentalCoroutinesApi::class)
class PhenomenonViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val reference = FakeReferenceRepository()
  private val didactic = BundledContent.didactic()
  private val occurrences = FakeOccurrenceRepository()
  private val content = FakeClipContentRepository()
  private val clips = FakeClipRepository()
  private val player = HoldingSpanPlayer()
  private val settings = FakeSettingsRepository()
  private val messages = FakeUserMessages()
  private val store = ViewModelStore()
  private var created = 0

  private fun viewModel(
    label: ChangeLabel = LearnTestData.FLAPPING,
    occurrenceRepository: OccurrenceRepository = occurrences,
    spanPlayer: SpanPlayer = player,
  ): PhenomenonViewModel {
    val vm = PhenomenonViewModel(
      PhenomenonKey(label), reference, didactic, occurrenceRepository, content, clips, spanPlayer, settings, messages,
      Optional.empty<MouthRenderer>(),
    )
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = vm as T
    }
    return ViewModelProvider.create(store, factory)["phenomenon-${created++}", PhenomenonViewModel::class]
  }

  private fun TestScope.ready(vm: PhenomenonViewModel): () -> PhenomenonUiState.Ready {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    return { vm.state.value as PhenomenonUiState.Ready }
  }

  private fun examples(state: PhenomenonUiState.Ready): List<Occurrence> =
    (state.examples as ExamplesUi.Ready).rows.map { it.occurrence }

  @Test
  fun flappingShowsTwelveTimesAndUpToEightExamplesOffline() = runTest {
    // F-15-AC1: 12 eligible flapping occurrences in 9 shorts on the phone, no network.
    occurrences.counts.value = mapOf(LearnTestData.FLAPPING to ChangeCount(shorts = 9, occurrences = 12))
    occurrences.byChange[LearnTestData.FLAPPING] = LearnTestData.flapping(12)
    val state = ready(viewModel())
    assertThat(state().occurrences).isEqualTo(12)
    assertThat(examples(state())).hasSize(PhenomenonViewModel.EXAMPLES)
    assertThat(state().card.name).isEqualTo("Flapping")
    assertThat(state().familyName).isEqualTo("t/d processes")
  }

  @Test
  fun vowelReductionCarriesTheSpanishSpeakerTipFromTheWebContent() = runTest {
    // F-15-AC3.
    val state = ready(viewModel(LearnTestData.VOWEL_REDUCTION))
    assertThat(state().lesson?.spanishTip).startsWith("Spanish gives every vowel its full value.")
    assertThat(state().lesson?.listenFor).isNotEmpty()
    assertThat(state().demo?.word).isEqualTo("does")
  }

  @Test
  fun everyChangeOfTheReferenceHasItsPageAndItsLessonText() = runTest {
    for (label in reference.state.value.labels.keys) {
      val vm = viewModel(label)
      val state = ready(vm)
      assertThat(state().lesson).isNotNull()
      assertThat(state().card.description).isNotNull()
    }
  }

  @Test
  fun anUnknownLabelIsTheNoLessonState() = runTest {
    val vm = viewModel(ChangeLabel("no_such_change"))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    assertThat(vm.state.value).isEqualTo(PhenomenonUiState.Unknown)
  }

  @Test
  fun noExampleIsTheEmptyStateNotAnError() = runTest {
    occurrences.counts.value = emptyMap()
    val state = ready(viewModel())
    assertThat(state().occurrences).isEqualTo(0)
    assertThat(state().examples).isEqualTo(ExamplesUi.Ready(emptyList()))
  }

  @Test
  fun theExamplesAreReadAgainWhenTheIndexCountForTheChangeMoves() = runTest {
    occurrences.counts.value = mapOf(LearnTestData.FLAPPING to ChangeCount(1, 1))
    occurrences.byChange[LearnTestData.FLAPPING] = LearnTestData.flapping(1)
    val state = ready(viewModel())
    assertThat(examples(state())).hasSize(1)
    // A clip was indexed: its flapping words appear.
    occurrences.byChange[LearnTestData.FLAPPING] = LearnTestData.flapping(3)
    occurrences.counts.value = mapOf(LearnTestData.FLAPPING to ChangeCount(2, 3))
    assertThat(examples(state())).hasSize(3)
    assertThat(state().occurrences).isEqualTo(3)
  }

  @Test
  fun anUnreadableIndexSaysSoForTheCountAndTheExamples() = runTest {
    val failing = object : OccurrenceRepository by occurrences {
      override fun changeCounts(): Flow<Map<ChangeLabel, ChangeCount>> = flow { error("unreadable") }
      override suspend fun examples(change: ChangeLabel, limit: Int, onlyClip: JobId?): List<Occurrence> = error("unreadable")
    }
    val state = ready(viewModel(occurrenceRepository = failing))
    assertThat(state().counts).isEqualTo(CountsUi.Failed)
    assertThat(state().examples).isEqualTo(ExamplesUi.Failed)
    assertThat(state().lesson).isNotNull()
  }

  @Test
  fun anExamplePlaysItsPaddedWordOnTheLearnersTrackAndATapStopsIt() = runTest {
    settings.state.value = UserSettings(track = Track.DIALOGUE)
    occurrences.counts.value = mapOf(LearnTestData.FLAPPING to ChangeCount(1, 2))
    occurrences.byChange[LearnTestData.FLAPPING] = LearnTestData.flapping(2)
    val vm = viewModel()
    val state = ready(vm)
    val first = examples(state()).first()
    vm.play(first)
    assertThat(player.played).containsExactly(OccurrenceRowPlayback.span(first))
    assertThat(player.tracks).containsExactly(Track.DIALOGUE)
    assertThat((state().examples as ExamplesUi.Ready).rows.map { it.playing }).containsExactly(true, false).inOrder()
    vm.play(first)
    assertThat(player.stops).isEqualTo(1)
    assertThat((state().examples as ExamplesUi.Ready).rows.none { it.playing }).isTrue()
  }

  @Test
  fun anotherExampleReplacesThePlayingOneAndTheEndClearsIt() = runTest {
    occurrences.counts.value = mapOf(LearnTestData.FLAPPING to ChangeCount(1, 2))
    occurrences.byChange[LearnTestData.FLAPPING] = LearnTestData.flapping(2)
    val vm = viewModel()
    val state = ready(vm)
    val (first, second) = examples(state())
    vm.play(first)
    vm.play(second)
    assertThat((state().examples as ExamplesUi.Ready).rows.map { it.playing }).containsExactly(false, true).inOrder()
    player.finish()
    assertThat((state().examples as ExamplesUi.Ready).rows.none { it.playing }).isTrue()
    assertThat(player.played.map(ClipSpan::word)).containsExactly(first.word, second.word).inOrder()
  }

  @Test
  fun closingThePageStopsItsExampleButNeverAnotherScreensSound() = runTest {
    occurrences.counts.value = mapOf(LearnTestData.FLAPPING to ChangeCount(1, 1))
    occurrences.byChange[LearnTestData.FLAPPING] = LearnTestData.flapping(1)
    val idle = viewModel()
    ready(idle)
    store.clear()
    assertThat(player.stops).isEqualTo(0)

    val vm = viewModel()
    val state = ready(vm)
    vm.play(examples(state()).first())
    store.clear()
    assertThat(player.stops).isEqualTo(1)
  }

  @Test
  fun anExampleOnThePhoneOpensItsLessonAsAFullPage() = runTest {
    content.data[LearnTestData.JOB] = AnalysisFixtures.clipData(LearnTestData.JOB)
    val vm = viewModel()
    val example = LearnTestData.occurrence(segment = 2, word = 3)
    vm.openLesson(example)
    assertThat(vm.exits.first()).isEqualTo(LessonKey(LearnTestData.JOB, 2, 3, LessonOrigin.LEARN, LessonPresentation.FULL))
    assertThat(messages.posted).isEmpty()
  }

  @Test
  fun aDamagedClipStillOpensItsLessonWhichSaysSo() = runTest {
    content.failures[LearnTestData.JOB] = AppError.Content.Corrupt
    val vm = viewModel()
    vm.openLesson(LearnTestData.occurrence())
    assertThat(vm.exits.first()).isInstanceOf(LessonKey::class.java)
  }

  @Test
  fun anExampleWhoseClipLeftThePhoneOpensTheClipPage() = runTest {
    clips.details.value = mapOf(LearnTestData.JOB to LearnTestData.detail(LearnTestData.JOB))
    val vm = viewModel()
    vm.openLesson(LearnTestData.occurrence())
    assertThat(vm.exits.first()).isEqualTo(ClipKey(LearnTestData.JOB))
    assertThat(messages.posted).isEmpty()
  }

  @Test
  fun anExampleWhoseClipIsGoneOpensTheLibraryWithItsNotice() = runTest {
    // Spec 5.4 and decision I-14: "That clip is no longer on your computer or this phone."
    val vm = viewModel()
    vm.openLesson(LearnTestData.occurrence(job = LearnTestData.GONE_JOB))
    assertThat(vm.exits.first()).isEqualTo(LibraryKey)
    assertThat(messages.posted.map { it.kind }).containsExactly(UserMessageKind.WORD_CLIP_GONE)
  }

  @Test
  fun openingALessonStopsTheExample() = runTest {
    occurrences.counts.value = mapOf(LearnTestData.FLAPPING to ChangeCount(1, 1))
    occurrences.byChange[LearnTestData.FLAPPING] = LearnTestData.flapping(1)
    content.data[LearnTestData.JOB] = AnalysisFixtures.clipData(LearnTestData.JOB)
    val vm = viewModel()
    val state = ready(vm)
    val example = examples(state()).first()
    vm.play(example)
    vm.openLesson(example)
    assertThat(player.stops).isEqualTo(1)
  }

  @Test
  fun theGlossaryAnswersTheRegistersWhatsThis() {
    val vm = viewModel()
    assertThat(vm.glossary("register")?.title).isEqualTo("Register")
    assertThat(vm.glossary("no-such-term")).isNull()
  }
}
