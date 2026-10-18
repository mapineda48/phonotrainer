package com.mapineda48.phonotrainer.feature.learn

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.ui.extension.InsightsAvailability
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Learn's ViewModel on the settable fakes of work-plan 4.6 (spec F-15 item 1 and its states, D-48). */
@OptIn(ExperimentalCoroutinesApi::class)
class LearnViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val reference = FakeReferenceRepository()
  private val occurrences = FakeOccurrenceRepository()
  private val clips = FakeClipRepository()

  private fun viewModel(
    occurrenceRepository: OccurrenceRepository = occurrences,
    insights: InsightsAvailability? = null,
  ) = LearnViewModel(reference, occurrenceRepository, clips, Optional.ofNullable(insights))

  private fun TestScope.collect(vm: LearnViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
  }

  @Test
  fun theTextIsThereAtOnceAndTheCountsArriveFromTheIndex() = runTest {
    val vm = viewModel()
    // Before anything is collected: the sections from the reference, the counts not read yet.
    assertThat(vm.state.value.sections.flatMap { it.cards }).hasSize(16)
    assertThat(vm.state.value.counts).isEqualTo(CountsUi.Loading)
    occurrences.counts.value = mapOf(ChangeLabel("flapping") to ChangeCount(shorts = 9, occurrences = 12))
    collect(vm)
    val counts = vm.state.value.counts as CountsUi.Ready
    // F-15-AC1: the card's unit is shorts.
    assertThat(counts.shorts(ChangeLabel("flapping"))).isEqualTo(9)
    assertThat(counts.shorts(ChangeLabel("t_deletion"))).isEqualTo(0)
    occurrences.counts.value = mapOf(ChangeLabel("flapping") to ChangeCount(shorts = 10, occurrences = 14))
    assertThat((vm.state.value.counts as CountsUi.Ready).shorts(ChangeLabel("flapping"))).isEqualTo(10)
  }

  @Test
  fun anUnreadableIndexSaysSoAndKeepsTheExplanations() = runTest {
    val failing = object : OccurrenceRepository by occurrences {
      override fun changeCounts(): Flow<Map<ChangeLabel, ChangeCount>> = flow { error("the index is unreadable") }
    }
    val vm = viewModel(failing)
    collect(vm)
    assertThat(vm.state.value.counts).isEqualTo(CountsUi.Failed)
    assertThat(vm.state.value.sections).isNotEmpty()
  }

  @Test
  fun whenSomeClipsAreNotHereTheCountsAreSaidToBeThisPhones() = runTest {
    val vm = viewModel()
    collect(vm)
    clips.all.value = listOf(LearnTestData.summary(LearnTestData.JOB))
    assertThat(vm.state.value.partial).isFalse()
    clips.all.value = listOf(
      LearnTestData.summary(LearnTestData.JOB),
      LearnTestData.summary(LearnTestData.GONE_JOB, ClipAvailability.NotOnThisPhone),
    )
    assertThat(vm.state.value.partial).isTrue()
  }

  @Test
  fun aNewerReferenceRedrawsTheSections() = runTest {
    val vm = viewModel()
    collect(vm)
    reference.state.value = reference.state.value.copy(
      labels = reference.state.value.labels + (ChangeLabel("flapping") to "the flap"),
    )
    val flapping = vm.state.value.sections.flatMap { it.cards }.first { it.label == ChangeLabel("flapping") }
    assertThat(flapping.name).isEqualTo("The flap")
  }

  @Test
  fun withoutInsightsThereIsNoEntryAndWithItTheEntryShowsItsTeaser() = runTest {
    val without = viewModel()
    collect(without)
    assertThat(without.state.value.insights).isNull()

    val teaser = MutableStateFlow<String?>(null)
    val with = viewModel(insights = object : InsightsAvailability {
      override val teaser: Flow<String?> = teaser
    })
    collect(with)
    assertThat(with.state.value.insights).isEqualTo(InsightsEntryUi(null))
    teaser.value = "58 % of your words differ"
    assertThat(with.state.value.insights).isEqualTo(InsightsEntryUi("58 % of your words differ"))
  }
}
