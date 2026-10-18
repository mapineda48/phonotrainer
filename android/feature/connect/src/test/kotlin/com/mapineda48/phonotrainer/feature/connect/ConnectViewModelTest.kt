package com.mapineda48.phonotrainer.feature.connect

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.FoundComputer
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakePairingRepository
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Connect's ViewModel on the settable fakes of `:core:connectivity` (work-plan 4.6): the search, the rows and their
 * preselection (none under a conflict, D-39), the typed address (identity only; the new-address mode accepts the paired
 * computer only, F-02-AC6/AC7), the code going to the picked computer once at a time (F-02-AC2), the errors and the
 * focus serial (F-02-AC3), and what survives process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val pairing = FakePairingRepository()
  private val computer = FakeComputerStatusRepository()
  private val desk = ConnectSamples.desk

  private fun viewModel(mode: ConnectMode = ConnectMode.FROM_HOME, saved: SavedStateHandle = SavedStateHandle()) =
    ConnectViewModel(ConnectKey(mode), pairing, computer, { "Pixel 9" }, saved)

  private fun TestScope.subscribe(viewModel: ConnectViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
  }

  private fun paired(computer: FoundComputer = desk) = PairedComputer(
    computer.serverId, computer.name, DeviceId("d1"), "Pixel 9", computer.address, LibraryId("l1"),
    Instant.parse("2026-10-18T09:00:00Z"), null, null, 6, null,
  )

  @Test
  fun theSearchStartsByItselfAndOneComputerIsPreselected() = runTest {
    pairing.searchStates = listOf(SearchState.Searching, SearchState.Found(listOf(desk), preselected = desk))
    val viewModel = viewModel()
    subscribe(viewModel)

    val state = viewModel.state.value
    assertThat(pairing.searches).isEqualTo(1)
    assertThat(state.search).isEqualTo(SearchUi.Found(listOf(ComputerRow(desk, RowKind.READY)), conflict = false, stillLooking = false))
    assertThat(state.selected?.computer).isEqualTo(desk)
    assertThat(state.deviceName).isEqualTo("Pixel 9")
  }

  @Test
  fun twoAnswersWithOneNameAreTwoRowsAndNoneIsPicked() = runTest {
    val twins = listOf(desk.copy(conflict = true), ConnectSamples.deskTwin)
    pairing.searchStates = listOf(SearchState.Found(twins, preselected = null, conflict = true))
    val viewModel = viewModel()
    subscribe(viewModel)

    val state = viewModel.state.value
    assertThat(state.rows).hasSize(2)
    assertThat((state.search as SearchUi.Found).conflict).isTrue()
    assertThat(state.selected).isNull()
    // The learner picks one; only then the code section appears.
    viewModel.select(ComputerRow.keyOf(ConnectSamples.deskTwin))
    assertThat(viewModel.state.value.selected?.computer).isEqualTo(ConnectSamples.deskTwin)
  }

  @Test
  fun nothingFoundAndNotOnWifiAndAFailingSearch() = runTest {
    pairing.searchStates = listOf(SearchState.Searching, SearchState.NothingFound)
    val viewModel = viewModel()
    subscribe(viewModel)
    assertThat(viewModel.state.value.search).isEqualTo(SearchUi.NothingFound)

    pairing.searchStates = listOf(SearchState.NotOnWifi)
    viewModel.search()
    assertThat(viewModel.state.value.search).isEqualTo(SearchUi.NotOnWifi)
    assertThat(pairing.searches).isEqualTo(2)

    val failing = object : com.mapineda48.phonotrainer.core.domain.api.PairingRepository by pairing {
      override fun search(): Flow<SearchState> = flow { throw IllegalStateException("socket") }
    }
    val broken = ConnectViewModel(ConnectKey(ConnectMode.FROM_HOME), failing, computer, { "Pixel 9" }, SavedStateHandle())
    subscribe(broken)
    assertThat(broken.state.value.search).isEqualTo(SearchUi.NothingFound)
  }

  @Test
  fun aTypedAddressIsCheckedForItsIdentityAndThenPicked() = runTest {
    pairing.searchStates = listOf(SearchState.NothingFound)
    pairing.checkResult = { CheckResult.Ok(desk) }
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.openAddress()
    viewModel.setAddress("192.0.2.10:8000")
    viewModel.check()
    val state = viewModel.state.value
    assertThat(pairing.checked).containsExactly(HostPort("192.0.2.10", 8000))
    assertThat(pairing.newAddresses).isEmpty()
    assertThat(state.typed).isEqualTo(ComputerRow(desk, RowKind.READY, typed = true))
    assertThat(state.selected?.key).isEqualTo(ComputerRow.keyOf(desk))
  }

  @Test
  fun aTypedAddressWithoutPhoneAccessIsReportedAndNothingIsPicked() = runTest {
    pairing.searchStates = listOf(SearchState.NothingFound)
    pairing.checkResult = { CheckResult.PhoneAccessOff }
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.setAddress("192.0.2.10:8000")
    viewModel.check()
    assertThat(viewModel.state.value.addressCheck).isEqualTo(AddressCheck.Answered(CheckResult.PhoneAccessOff))
    assertThat(viewModel.state.value.selected).isNull()
    assertThat(pairing.paired).isEmpty()
  }

  @Test
  fun somethingThatIsNotAnAddressIsNeverSent() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)
    viewModel.setAddress("my-computer.local")
    viewModel.check()
    assertThat(viewModel.state.value.addressCheck).isEqualTo(AddressCheck.Invalid)
    assertThat(pairing.checked).isEmpty()
  }

  @Test
  fun theNewAddressIsAcceptedOnlyFromThePairedComputer() = runTest {
    computer.pairedState.value = paired()
    pairing.useNewAddressResult = { CheckResult.DifferentComputer }
    val viewModel = viewModel(ConnectMode.NEW_ADDRESS)
    subscribe(viewModel)
    assertThat(pairing.searches).isEqualTo(0)
    assertThat(viewModel.state.value.addressOpen).isTrue()

    viewModel.setAddress("192.0.2.12:8000")
    viewModel.check()
    assertThat(pairing.newAddresses).containsExactly(HostPort("192.0.2.12", 8000))
    assertThat(pairing.checked).isEmpty()
    assertThat(viewModel.state.value.addressCheck).isEqualTo(AddressCheck.Answered(CheckResult.DifferentComputer))
    assertThat(viewModel.state.value.done).isNull()

    pairing.useNewAddressResult = { CheckResult.Ok(desk.copy(address = it)) }
    viewModel.check()
    assertThat(viewModel.state.value.done).isEqualTo(ConnectDone.NewAddress("Study desk"))
  }

  @Test
  fun theCodeAndThisPhonesNameGoToThePickedComputer() = runTest {
    pairing.searchStates = listOf(SearchState.Found(listOf(desk), preselected = desk))
    pairing.pairResult = { target, _ -> PairResult.Paired(paired().copy(address = target.address)) }
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.setCode("482 913")
    viewModel.setDeviceName("  Kitchen phone ")
    assertThat(viewModel.state.value.canConnect).isTrue()
    viewModel.connect()
    assertThat(pairing.paired).containsExactly(Triple(PairingTarget(desk.address, desk.serverId, desk.name), "482913", "Kitchen phone"))
    assertThat(viewModel.state.value.done).isEqualTo(ConnectDone.Paired("Study desk"))
    assertThat(viewModel.state.value.code).isEmpty()
  }

  @Test
  fun aWrongCodeIsClearedAndTheFocusGoesBackToTheCells() = runTest {
    pairing.searchStates = listOf(SearchState.Found(listOf(desk), preselected = desk))
    pairing.pairResult = { _, _ -> PairResult.CodeInvalid }
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.setCode("111111")
    viewModel.connect()
    var state = viewModel.state.value
    assertThat(state.codeError).isEqualTo(CodeError.Invalid)
    assertThat(state.code).isEmpty()
    assertThat(state.codeErrorSerial).isEqualTo(1)
    assertThat(state.done).isNull()

    // Typing again clears the error; the next wrong answer moves the serial on, so the focus returns again.
    viewModel.setCode("2")
    assertThat(viewModel.state.value.codeError).isNull()
    viewModel.setCode("222222")
    pairing.pairResult = { _, _ -> PairResult.RateLimited(30) }
    viewModel.connect()
    state = viewModel.state.value
    assertThat(state.codeError).isEqualTo(CodeError.RateLimited(30))
    assertThat(state.code).isEqualTo("222222")
    assertThat(state.codeErrorSerial).isEqualTo(2)
  }

  @Test
  fun connectIsSentOnceAtATime() = runTest {
    pairing.searchStates = listOf(SearchState.Found(listOf(desk), preselected = desk))
    val answer = CompletableDeferred<PairResult>()
    val slow = object : com.mapineda48.phonotrainer.core.domain.api.PairingRepository by pairing {
      var calls = 0

      override suspend fun pair(target: PairingTarget, code: String, deviceName: String): PairResult {
        calls++
        return answer.await()
      }
    }
    val viewModel = ConnectViewModel(ConnectKey(ConnectMode.FROM_HOME), slow, computer, { "Pixel 9" }, SavedStateHandle())
    subscribe(viewModel)
    viewModel.setCode("482913")
    viewModel.connect()
    assertThat(viewModel.state.value.pairing).isTrue()
    assertThat(viewModel.state.value.canConnect).isFalse()
    viewModel.connect()
    answer.complete(PairResult.CodeExpired)
    assertThat(slow.calls).isEqualTo(1)
    assertThat(viewModel.state.value.codeError).isEqualTo(CodeError.Expired)
    assertThat(viewModel.state.value.pairing).isFalse()
  }

  @Test
  fun whilePairedOnlyThePairedComputerCanBePicked() = runTest {
    computer.pairedState.value = paired()
    pairing.searchStates = listOf(SearchState.Found(listOf(desk, ConnectSamples.other), preselected = null))
    pairing.pairResult = { _, _ -> PairResult.Failed(AppError.Computer.DifferentComputer) }
    val viewModel = viewModel(ConnectMode.CONNECT_AGAIN)
    subscribe(viewModel)

    val state = viewModel.state.value
    assertThat(state.rows.map { it.kind }).containsExactly(RowKind.PAIRED, RowKind.OTHER_COMPUTER).inOrder()
    assertThat(state.selected?.computer).isEqualTo(desk)
    assertThat(state.pairedName).isEqualTo("Study desk")

    // Picking the other computer leaves nothing to connect to.
    viewModel.select(ComputerRow.keyOf(ConnectSamples.other))
    assertThat(viewModel.state.value.selected).isNull()
    viewModel.setCode("482913")
    viewModel.connect()
    assertThat(pairing.paired).isEmpty()
  }

  @Test
  fun theDraftSurvivesProcessDeathButTheCodeDoesNot() = runTest {
    pairing.searchStates = listOf(SearchState.Found(listOf(desk), preselected = desk))
    val saved = SavedStateHandle()
    val first = viewModel(saved = saved)
    subscribe(first)
    first.openAddress()
    first.setAddress("192.0.2.10:8000")
    first.setDeviceName("Kitchen phone")
    first.setCode("482913")

    val second = viewModel(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
    subscribe(second)
    val state = second.state.value
    assertThat(state.addressOpen).isTrue()
    assertThat(state.address).isEqualTo("192.0.2.10:8000")
    assertThat(state.deviceName).isEqualTo("Kitchen phone")
    assertThat(state.code).isEmpty()
    assertThat(saved.keys()).doesNotContain("code")
  }
}
