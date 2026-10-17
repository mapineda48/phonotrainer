package com.mapineda48.phonotrainer.feature.requests

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeAnnouncer
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** [FakeRequestRepository] whose `requests()` fails while [failures] is above zero (each failure takes one). */
private class FailingRequests(val fake: FakeRequestRepository = FakeRequestRepository()) : RequestRepository by fake {
  var failures = 0

  override fun requests(): Flow<List<Request>> {
    if (failures > 0) {
      failures--
      return flow { throw IllegalStateException("the queue is unreadable") }
    }
    return fake.requests()
  }
}

/** Requests' and the request detail's ViewModels over the settable fakes of work-plan 4.6 (spec F-20). */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestsViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val requests = FailingRequests()
  private val clips = FakeClipRepository()
  private val status = FakeComputerStatusRepository(RequestRowSamples.connected)
  private val clock = TestClock(RequestRowSamples.now)
  private val announcer = FakeAnnouncer()

  private fun TestScope.collected(saved: SavedStateHandle = SavedStateHandle()): RequestsViewModel =
    RequestsViewModel(requests, clips, status, clock, announcer, saved).also { vm ->
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

  private fun TestScope.detail(id: String, saved: SavedStateHandle = SavedStateHandle()): RequestDetailViewModel =
    RequestDetailViewModel(RequestDetailKey(RequestId(id)), requests, status, clock, announcer, saved).also { vm ->
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

  @Test
  fun theQueueIsGroupedWithTheBrowsersJobsAndTheComputersState() = runTest(main.dispatcher) {
    requests.fake.all.value = RequestRowSamples.all.map { it.second }
    clips.browser.value = listOf(RequestsSamples.browserFailed)
    status.pairedState.value = paired()
    val vm = collected()

    val state = vm.state.value
    assertThat(state.load).isEqualTo(Load.READY)
    assertThat(state.groups.map { it.kind }.first()).isEqualTo(RequestGroupKind.ATTENTION)
    assertThat(state.browser.map { it.id }).containsExactly(RequestsSamples.browserFailed.id)
    assertThat(state.browserExpanded).isFalse()
    assertThat(state.capabilities).isEqualTo(RequestRowSamples.capabilities)
    assertThat(state.empty).isFalse()

    status.statusState.value = ComputerStatus.ConnectAgain
    assertThat(vm.state.value.paused).isTrue()
  }

  @Test
  fun anEmptyQueueIsTheEmptyStateAndAnUnreadableOneOffersTryAgainWithoutClearingIt() = runTest(main.dispatcher) {
    requests.failures = 1
    val vm = collected()
    assertThat(vm.state.value.load).isEqualTo(Load.FAILED)
    assertThat(vm.state.value.empty).isFalse()

    vm.retry()
    runCurrent()
    assertThat(vm.state.value.load).isEqualTo(Load.READY)
    assertThat(vm.state.value.empty).isTrue()
  }

  @Test
  fun actionsGoToTheQueueAndTheBrowserGroupRemembersWhetherItIsOpen() = runTest(main.dispatcher) {
    val saved = SavedStateHandle()
    val vm = collected(saved)
    vm.perform(RequestId("waiting"), RequestAction.SEND_NOW)
    vm.hideBrowserJob(RequestsSamples.browserFailed.id)
    vm.setBrowserExpanded(true)
    runCurrent()

    assertThat(requests.fake.performed).containsExactly(RequestId("waiting") to RequestAction.SEND_NOW)
    assertThat(clips.actions).containsExactly("hideBrowserJob:${RequestsSamples.browserFailed.id.value}")
    assertThat(vm.state.value.browserExpanded).isTrue()
    // A process death: the same saved state opens the group again.
    assertThat(collected(saved).state.value.browserExpanded).isTrue()
  }

  @Test
  fun aStageChangeIsAnnouncedOnceAndPercentsAreNot() = runTest(main.dispatcher) {
    val first = RequestRowSamples.analyzing.copy(progress = RequestProgress(Stage.TRANSCRIBE, 40, null, null, null, null))
    requests.fake.all.value = listOf(first)
    val vm = collected()
    val stages = mutableListOf<Stage>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.stageChanges.collect { stages += it } }

    requests.fake.all.value = listOf(first.copy(progress = first.progress?.copy(percent = 45)))
    requests.fake.all.value = listOf(first.copy(progress = RequestProgress(Stage.PHONES, 55, null, null, null, null, segment = 1, segments = 9)))
    requests.fake.all.value = listOf(first.copy(progress = RequestProgress(Stage.PHONES, 58, null, null, null, null, segment = 2, segments = 9)))
    runCurrent()

    assertThat(stages).containsExactly(Stage.PHONES)
    vm.announce("Now: Hear the phones.")
    assertThat(announcer.announced).containsExactly("Now: Hear the phones.")
  }

  @Test
  fun agesAreCountedAgainWhileTheScreenIsShown() = runTest(main.dispatcher) {
    requests.fake.all.value = listOf(RequestRowSamples.waiting)
    val vm = collected()
    assertThat(vm.state.value.now).isEqualTo(RequestRowSamples.now)

    clock.instant = RequestRowSamples.now.plusSeconds(60)
    advanceTimeBy(RequestsViewModel.TICK_MS + 1)
    assertThat(vm.state.value.now).isEqualTo(RequestRowSamples.now.plusSeconds(60))
  }

  @Test
  fun theDetailFollowsItsRequestItsTogglesAndItsRemoval() = runTest(main.dispatcher) {
    requests.fake.all.value = listOf(RequestRowSamples.downloadForbidden)
    val saved = SavedStateHandle()
    val vm = detail("forbidden", saved)
    assertThat(vm.state.value.request).isEqualTo(RequestRowSamples.downloadForbidden)
    assertThat(vm.state.value.removed).isFalse()

    vm.setDetailsExpanded(true)
    vm.setLinkExpanded(true)
    assertThat(vm.state.value.detailsExpanded).isTrue()
    assertThat(detail("forbidden", saved).state.value.linkExpanded).isTrue()

    vm.perform(RequestAction.TRY_AUDIO_ONLY)
    runCurrent()
    assertThat(requests.fake.performed).containsExactly(RequestId("forbidden") to RequestAction.TRY_AUDIO_ONLY)

    requests.fake.all.value = emptyList()
    assertThat(vm.state.value.removed).isTrue()
  }

  @Test
  fun theDetailAnnouncesAStageChange() = runTest(main.dispatcher) {
    val request = RequestRowSamples.analyzing
    requests.fake.all.value = listOf(request.copy(progress = RequestProgress(Stage.EXTRACT, 5, null, null, null, null)))
    val vm = detail("analyzing")
    val stages = mutableListOf<Stage>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.stageChanges.collect { stages += it } }

    requests.fake.all.value = listOf(request.copy(progress = RequestProgress(Stage.SEPARATE, 20, null, null, null, null)))
    runCurrent()
    assertThat(stages).containsExactly(Stage.SEPARATE)
  }

  private fun paired() = PairedComputer(
    serverId = ServerId("server-1"),
    name = "Desk PC",
    deviceId = DeviceId("device-1"),
    deviceName = "Test phone",
    address = HostPort("192.0.2.10", 8765),
    libraryId = LibraryId("library-1"),
    pairedAt = Instant.EPOCH,
    lastSeen = null,
    lastSync = null,
    apiVersion = 6,
    capabilities = RequestRowSamples.capabilities,
  )
}
