package com.mapineda48.phonotrainer.feature.learn

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** "Words we use" on the bundled glossary (spec F-14 index, AC3, AC4; D-47). */
@OptIn(ExperimentalCoroutinesApi::class)
class GlossaryIndexViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val didactic = BundledContent.didactic()

  @Test
  fun everyTermAndMeasureIsListedAndNoSymbol() = runTest {
    val vm = GlossaryIndexViewModel(SavedStateHandle(), didactic)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    val state = vm.state.value
    assertThat(state.total).isEqualTo(BundledContent.wordsWeUse)
    assertThat(state.shown).isEqualTo(state.total)
    assertThat(state.terms.map { it.key }).doesNotContain("ipa:ɾ")
    assertThat(state.measures.map { it.key }.all { it.startsWith("metric:") }).isTrue()
    assertThat(state.terms.map { it.key }.none { it.startsWith("metric:") }).isTrue()
  }

  @Test
  fun searchingSchwaFindsIt() = runTest {
    // F-14-AC3.
    val vm = GlossaryIndexViewModel(SavedStateHandle(), didactic)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    vm.search("schwa")
    assertThat(vm.state.value.terms.map { it.key }).contains("schwa")
    assertThat(vm.state.value.shown).isLessThan(vm.state.value.total)
  }

  @Test
  fun xyzzyMatchesNothingAndClearingBringsEveryRowBack() = runTest {
    // F-14-AC4.
    val vm = GlossaryIndexViewModel(SavedStateHandle(), didactic)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    vm.search("xyzzy")
    assertThat(vm.state.value.shown).isEqualTo(0)
    vm.clear()
    assertThat(vm.state.value.query).isEmpty()
    assertThat(vm.state.value.shown).isEqualTo(vm.state.value.total)
  }

  @Test
  fun theSearchSurvivesAProcessDeath() = runTest {
    val handle = SavedStateHandle()
    GlossaryIndexViewModel(handle, didactic).search("schwa")
    val restored = GlossaryIndexViewModel(SavedStateHandle(mapOf(GlossaryIndexViewModel.KEY_QUERY to handle.get<String>(GlossaryIndexViewModel.KEY_QUERY))), didactic)
    assertThat(restored.state.value.query).isEqualTo("schwa")
    assertThat(restored.state.value.terms.map { it.key }).contains("schwa")
  }

  @Test
  fun aRowOpensItsOwnEntry() {
    val vm = GlossaryIndexViewModel(SavedStateHandle(), didactic)
    assertThat(vm.entry("schwa")?.title).isEqualTo("Schwa")
  }
}
