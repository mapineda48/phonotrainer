package com.mapineda48.phonotrainer.feature.connect

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeDecoderCapabilities
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeSyncController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Connection details on the settable fakes: the diagnostics as the status repository has them, the decoders, "Check now". */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDetailsViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val computer = FakeComputerStatusRepository()
  private val sync = FakeSyncController()
  private val decoders = FakeDecoderCapabilities(decoders = ConnectSamples.decoders)
  private val clock = TestClock(ConnectSamples.now)

  private fun viewModel() = ConnectionDetailsViewModel(computer, sync, decoders, clock)

  private fun TestScope.subscribe(viewModel: ConnectionDetailsViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
  }

  @Test
  fun theStateIsWhatTheStatusRepositoryKnows() = runTest {
    computer.statusState.value = ComputerStatus.Connected("Study desk", 0, 1, false)
    computer.pairedState.value = null
    computer.diagnosticsState.value = ConnectSamples.diagnostics
    val viewModel = viewModel()
    subscribe(viewModel)

    val state = viewModel.state.first { it.decoders != null }
    assertThat(state.status).isEqualTo(ComputerStatus.Connected("Study desk", 0, 1, false))
    assertThat(state.diagnostics.phoneAddress).isEqualTo("192.0.2.20")
    assertThat(state.decoders).isEqualTo(ConnectSamples.decoders)
    assertThat(state.now).isEqualTo(ConnectSamples.now)
    assertThat(state.paired).isFalse()
  }

  @Test
  fun checkNowAsksOnceAndSyncNowSyncs() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)
    viewModel.checkNow()
    assertThat(computer.checks).isEqualTo(1)
    assertThat(viewModel.state.value.checking).isFalse()
    viewModel.syncNow()
    assertThat(sync.reasons).containsExactly(SyncReason.SYNC_NOW)
  }

  @Test
  fun copyingIsRemembered() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)
    assertThat(viewModel.state.value.copied).isFalse()
    viewModel.copied()
    assertThat(viewModel.state.value.copied).isTrue()
  }
}
