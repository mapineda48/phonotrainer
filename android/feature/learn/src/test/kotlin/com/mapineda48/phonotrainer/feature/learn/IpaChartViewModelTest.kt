package com.mapineda48.phonotrainer.feature.learn

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The IPA chart's ViewModel on the settable fakes of work-plan 4.6 and the bundled content (spec F-16). */
@OptIn(ExperimentalCoroutinesApi::class)
class IpaChartViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val reference = FakeReferenceRepository()
  private val didactic = BundledContent.didactic()
  private val occurrences = FakeOccurrenceRepository()
  private val content = FakeClipContentRepository()
  private val clips = FakeClipRepository()
  private val player = HoldingSpanPlayer()
  private val messages = FakeUserMessages()
  private val store = ViewModelStore()
  private var created = 0

  private fun viewModel(symbol: String? = null, handle: SavedStateHandle = SavedStateHandle()): IpaChartViewModel {
    val vm = IpaChartViewModel(
      IpaChartKey(symbol), handle, reference, didactic, occurrences, content, clips, player, FakeSettingsRepository(), messages,
      Optional.empty<MouthRenderer>(),
    )
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = vm as T
    }
    return ViewModelProvider.create(store, factory)["ipa-${created++}", IpaChartViewModel::class]
  }

  private fun TestScope.state(vm: IpaChartViewModel): () -> IpaChartUiState {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    return { vm.state.value }
  }

  private val tapExamples = listOf(
    LearnTestData.occurrence(word = 4, text = "water"),
    LearnTestData.occurrence(word = 6, text = "better", startMs = 46_000),
  )

  @Test
  fun nothingIsSelectedAtFirstAndEveryPlacedSymbolHasItsName() = runTest {
    val state = state(viewModel())
    assertThat(state().selected).isNull()
    assertThat(state().detail).isNull()
    assertThat(state().names.keys).containsAtLeastElementsIn(state().layout.placed)
    assertThat(state().weak).containsExactly("ə", "ɐ", "ᵻ", "ɚ")
  }

  @Test
  fun theKeysSymbolOpensItsSheetWithUpToSixExamplesFromThisPhone() = runTest {
    // F-16-AC3: [ɾ] selected and examples on the phone.
    occurrences.bySymbol["ɾ"] = List(9) { LearnTestData.occurrence(word = it, text = "w$it") }
    val state = state(viewModel("ɾ"))
    assertThat(state().selected).isEqualTo("ɾ")
    val detail = state().detail!!
    assertThat(detail.name).isEqualTo("Alveolar tap")
    assertThat(detail.change?.label).isEqualTo(ChangeLabel("flapping"))
    assertThat((detail.examples as ExamplesUi.Ready).rows).hasSize(IpaChartViewModel.EXAMPLES)
  }

  @Test
  fun aSymbolTheTableDoesNotKnowSelectsNothing() = runTest {
    val state = state(viewModel("§"))
    assertThat(state().selected).isNull()
  }

  @Test
  fun selectingAndClosingFollowTheLearnerAndSurviveAProcessDeath() = runTest {
    val handle = SavedStateHandle()
    val vm = viewModel("ɾ", handle)
    val state = state(vm)
    vm.select("ə")
    assertThat(state().selected).isEqualTo("ə")
    assertThat(state().detail?.change?.label).isEqualTo(ChangeLabel("vowel_reduction"))
    // A process death: the entry comes back with the same key and the saved state.
    val restored = viewModel("ɾ", SavedStateHandle(mapOf(IpaChartViewModel.KEY_SELECTED to handle.get<String>(IpaChartViewModel.KEY_SELECTED))))
    assertThat(state(restored)().selected).isEqualTo("ə")
    // Closed sheet: a restore keeps it closed, even though the key names a symbol.
    vm.select(null)
    assertThat(state().detail).isNull()
    val closed = SavedStateHandle().apply { set(IpaChartViewModel.KEY_SELECTED, handle.get<String>(IpaChartViewModel.KEY_SELECTED)) }
    assertThat(state(viewModel("ɾ", closed))().selected).isNull()
  }

  @Test
  fun aSymbolWithNoExampleOnThePhoneHasAnEmptyList() = runTest {
    val vm = viewModel("ʔ")
    val state = state(vm)
    assertThat(state().detail?.examples).isEqualTo(ExamplesUi.Ready(emptyList()))
  }

  @Test
  fun theExamplesFollowTheSelectionAndTheIndex() = runTest {
    occurrences.bySymbol["ɾ"] = tapExamples.take(1)
    val vm = viewModel("ɾ")
    val state = state(vm)
    assertThat((state().detail!!.examples as ExamplesUi.Ready).rows).hasSize(1)
    occurrences.bySymbol["ɾ"] = tapExamples
    occurrences.counts.value = mapOf(ChangeLabel("flapping") to ChangeCount(2, 2))
    assertThat((state().detail!!.examples as ExamplesUi.Ready).rows).hasSize(2)
    vm.select("ʔ")
    assertThat(state().detail?.examples).isEqualTo(ExamplesUi.Ready(emptyList()))
  }

  @Test
  fun anExamplePlaysAndChoosingAnotherSymbolStopsIt() = runTest {
    occurrences.bySymbol["ɾ"] = tapExamples
    val vm = viewModel("ɾ")
    val state = state(vm)
    vm.play(tapExamples[1])
    assertThat((state().detail!!.examples as ExamplesUi.Ready).rows.map { it.playing }).containsExactly(false, true).inOrder()
    vm.select("ə")
    assertThat(player.stops).isEqualTo(1)
  }

  @Test
  fun anExampleWhoseClipIsGoneOpensTheLibraryWithItsNotice() = runTest {
    val vm = viewModel("ɾ")
    vm.openLesson(LearnTestData.occurrence(job = LearnTestData.GONE_JOB))
    assertThat(vm.exits.first()).isEqualTo(LibraryKey)
    assertThat(messages.posted.map { it.kind }).containsExactly(UserMessageKind.WORD_CLIP_GONE)
  }
}
