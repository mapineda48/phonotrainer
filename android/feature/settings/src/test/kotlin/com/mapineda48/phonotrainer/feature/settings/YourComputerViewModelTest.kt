package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakePairingRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeSyncController
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Your computer's state and actions (spec F-22, F-22-AC3): forgetting goes through the pairing repository only and
 * keeps the page's words; "Check now" asks the status repository once at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YourComputerViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val computer = FakeComputerStatusRepository()
  private val pairing = FakePairingRepository()
  private val sync = FakeSyncController()
  private val clock = TestClock(Instant.parse("2026-10-18T10:00:00Z"))

  private fun viewModel(status: ComputerStatusRepository = computer) =
    YourComputerViewModel(status, pairing, sync, clock)

  private fun TestScope.subscribe(viewModel: YourComputerViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
  }

  @Test
  fun theStateShowsThePairedComputerItsStatusAndWhenItLastSynced() = runTest {
    computer.pairedState.value = SettingsSamples.computer
    computer.statusState.value = ComputerStatus.Connected("Study desk", 1, 2, false)
    computer.restricted.value = true
    val viewModel = viewModel()
    subscribe(viewModel)

    val state = viewModel.state.value
    assertThat(state.paired).isEqualTo(SettingsSamples.computer)
    assertThat(state.status).isEqualTo(ComputerStatus.Connected("Study desk", 1, 2, false))
    assertThat(state.backgroundRestricted).isTrue()
    // "Last synced" is the paired computer's (CR-WP08-1).
    assertThat(state.lastSynced).isEqualTo(SettingsSamples.computer.lastSync)
    assertThat(state.now).isEqualTo(clock.instant)
  }

  @Test
  fun aListingOfAnEarlierPairingIsNotThisComputersLastSync() = runTest {
    // The engine's last listing is older than this pairing, so it belongs to an earlier one: the status repository
    // gives the paired computer no last sync, and the page shows none either (CR-WP08-1).
    sync.lastSyncedFlow.value = Instant.parse("2026-10-17T08:00:00Z")
    computer.pairedState.value =
      SettingsSamples.computer.copy(pairedAt = Instant.parse("2026-10-18T09:00:00Z"), lastSync = null)
    val viewModel = viewModel()
    subscribe(viewModel)

    assertThat(viewModel.state.value.lastSynced).isNull()
  }

  @Test
  fun thePairedComputersLastSyncIsWhatThePageShows() = runTest {
    // The engine's own time is not read: the status repository already turned it into the paired computer's.
    sync.lastSyncedFlow.value = Instant.parse("2026-10-18T09:30:00Z")
    val synced = Instant.parse("2026-10-18T09:59:00Z")
    computer.pairedState.value = SettingsSamples.computer.copy(lastSync = synced)
    val viewModel = viewModel()
    subscribe(viewModel)
    assertThat(viewModel.state.value.lastSynced).isEqualTo(synced)

    val next = Instant.parse("2026-10-18T10:00:00Z")
    computer.pairedState.value = SettingsSamples.computer.copy(lastSync = next)
    assertThat(viewModel.state.value.lastSynced).isEqualTo(next)
  }

  @Test
  fun forgetUsesThePairingRepositoryAndSaysWhatItForgot() = runTest {
    computer.pairedState.value = SettingsSamples.computer
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.forget()

    assertThat(pairing.forgets).isEqualTo(1)
    assertThat(viewModel.state.value.forget).isEqualTo(ForgetOutcome.Forgotten("Study desk"))
    assertThat(viewModel.state.value.forgetting).isFalse()
  }

  @Test
  fun aRefusedForgetIsReportedAndCanBeTriedAgain() = runTest {
    computer.pairedState.value = SettingsSamples.computer
    pairing.forgetResult = ForgetResult.Failed(AppError.Computer.Offline)
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.forget()
    assertThat(viewModel.state.value.forget).isEqualTo(ForgetOutcome.Failed("Study desk"))

    pairing.forgetResult = ForgetResult.Forgotten
    viewModel.forget()
    assertThat(pairing.forgets).isEqualTo(2)
    assertThat(viewModel.state.value.forget).isEqualTo(ForgetOutcome.Forgotten("Study desk"))
  }

  @Test
  fun withoutAPairedComputerThereIsNothingToForget() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.forget()

    assertThat(pairing.forgets).isEqualTo(0)
    assertThat(viewModel.state.value.forget).isEqualTo(ForgetOutcome.None)
  }

  @Test
  fun checkNowAsksOnceAtATimeAndSyncNowAsksTheEngine() = runTest {
    val answer = CompletableDeferred<ComputerStatus>()
    var checks = 0
    val slow = object : ComputerStatusRepository by computer {
      override suspend fun checkNow(): ComputerStatus {
        checks++
        return answer.await()
      }
    }
    val viewModel = viewModel(status = slow)
    subscribe(viewModel)

    viewModel.checkNow()
    viewModel.checkNow()
    assertThat(viewModel.state.value.checking).isTrue()
    assertThat(checks).isEqualTo(1)
    answer.complete(ComputerStatus.Checking)
    assertThat(viewModel.state.value.checking).isFalse()

    viewModel.syncNow()
    assertThat(sync.reasons).containsExactly(SyncReason.SYNC_NOW)
  }
}
