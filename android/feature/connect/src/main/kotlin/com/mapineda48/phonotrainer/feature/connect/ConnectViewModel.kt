package com.mapineda48.phonotrainer.feature.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.FoundComputer
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Connect to your computer (spec F-02, design 10.2 P-06), over `PairingRepository` (WP-07): the search starts by
 * itself (except in the new-address mode, which opens on the address field), its rows grow as computers answer, the
 * search's own preselection is followed only when nothing conflicts (D-39), a typed address is checked for its
 * identity before anything else is sent, and the code goes to the picked computer only. Nothing is stored here: the
 * repository stores the pairing, and a wrong code stores nothing (F-02-AC3). The draft address, the phone's name and
 * the picked row survive process death (architecture 3.1); the code does not (it is single use and short-lived).
 */
@HiltViewModel(assistedFactory = ConnectViewModel.Factory::class)
internal class ConnectViewModel @AssistedInject constructor(
  @Assisted key: ConnectKey,
  private val pairing: PairingRepository,
  computer: ComputerStatusRepository,
  phoneModel: PhoneModel,
  private val savedState: SavedStateHandle,
) : ViewModel() {
  val mode: ConnectMode = key.mode

  private val paired: StateFlow<PairedComputer?> = computer.paired
  private val latest = MutableStateFlow<SearchState?>(null)
  private val running = MutableStateFlow(false)
  private val local = MutableStateFlow(
    Local(
      selection = savedState[SELECTION],
      addressOpen = savedState[ADDRESS_OPEN] ?: (mode == ConnectMode.NEW_ADDRESS),
      address = savedState[ADDRESS] ?: "",
      deviceName = savedState[DEVICE_NAME] ?: phoneModel.name().take(ConnectUiState.NAME_MAX),
    ),
  )
  private var searchJob: Job? = null
  private var searchGeneration = 0

  val state: StateFlow<ConnectUiState> = combine(latest, running, paired, local, ::build)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), current())

  init {
    if (mode != ConnectMode.NEW_ADDRESS) search()
  }

  /** Starts a search (and "Search again"): at most 10 s, rows as computers answer (architecture 6.1). */
  fun search() {
    searchJob?.cancel()
    val generation = ++searchGeneration
    latest.value = null
    running.value = true
    searchJob = viewModelScope.launch {
      try {
        pairing.search().collect { latest.value = it }
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        latest.value = SearchState.NothingFound
      } finally {
        if (generation == searchGeneration) running.value = false
      }
    }
  }

  /** "Enter the address instead". */
  fun openAddress() = updateLocal { it.copy(addressOpen = true) }

  fun setAddress(text: String) = updateLocal { it.copy(address = text.take(AddressInput.MAX_LENGTH), check = null) }

  /**
   * "Check": asks the typed address who it is (identity only, never a credential, F-02-AC7). In the new-address mode
   * the answer is accepted only from the paired computer (F-02-AC6), and that ends the page.
   */
  fun check() {
    val current = local.value
    if (current.check == AddressCheck.Checking) return
    val address = AddressInput.parse(current.address)
    if (address == null) {
      updateLocal { it.copy(check = AddressCheck.Invalid) }
      return
    }
    updateLocal { it.copy(check = AddressCheck.Checking) }
    viewModelScope.launch {
      val result = try {
        if (mode == ConnectMode.NEW_ADDRESS) pairing.useNewAddress(address) else pairing.check(address)
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        CheckResult.NoAnswer
      }
      when {
        result is CheckResult.Ok && mode == ConnectMode.NEW_ADDRESS ->
          updateLocal { it.copy(check = AddressCheck.Answered(result), done = ConnectDone.NewAddress(result.computer.name)) }
        result is CheckResult.Ok -> updateLocal {
          it.copy(check = AddressCheck.Answered(result), typed = result.computer, selection = ComputerRow.keyOf(result.computer), codeError = null)
        }
        else -> updateLocal { it.copy(check = AddressCheck.Answered(result)) }
      }
    }
  }

  /** Picks the row with [key]. */
  fun select(key: String) = updateLocal { it.copy(selection = key, codeError = null) }

  fun setCode(text: String) {
    val code = ConnectRules.codeOf(text)
    updateLocal { if (it.code == code) it else it.copy(code = code, codeError = null) }
  }

  fun setDeviceName(text: String) = updateLocal { it.copy(deviceName = text.take(ConnectUiState.NAME_MAX)) }

  /** "Connect": the code and this phone's name go to the picked computer (F-02-AC2), once at a time. */
  fun connect() {
    val now = current()
    val target = now.selected ?: return
    if (!now.canConnect) return
    searchJob?.cancel()
    val code = now.code
    val name = now.deviceName.trim()
    updateLocal { it.copy(pairing = true, codeError = null) }
    viewModelScope.launch {
      val computer = target.computer
      val result = try {
        pairing.pair(PairingTarget(computer.address, computer.serverId, computer.name), code, name)
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        PairResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset))
      }
      val error = ConnectRules.codeError(result, now.pairedName, target.kind == RowKind.PAIRED)
      updateLocal {
        if (result is PairResult.Paired) {
          it.copy(pairing = false, code = "", done = ConnectDone.Paired(result.computer.name))
        } else {
          it.copy(
            pairing = false,
            code = if (error != null && ConnectRules.clearsCode(error)) "" else it.code,
            codeError = error,
            codeErrorSerial = it.codeErrorSerial + 1,
          )
        }
      }
    }
  }

  /** The state as of now, whether or not the screen is collecting it. */
  private fun current(): ConnectUiState = build(latest.value, running.value, paired.value, local.value)

  private fun updateLocal(change: (Local) -> Local) {
    local.update(change)
    val saved = local.value
    savedState[SELECTION] = saved.selection
    savedState[ADDRESS_OPEN] = saved.addressOpen
    savedState[ADDRESS] = saved.address
    savedState[DEVICE_NAME] = saved.deviceName
  }

  private fun build(latest: SearchState?, running: Boolean, paired: PairedComputer?, local: Local): ConnectUiState {
    val search = ConnectRules.searchUi(latest, running, paired)
    val typed = local.typed?.let { ComputerRow(it, ConnectRules.kindOf(it, paired), typed = true) }
    val draft = ConnectUiState(
      mode = mode,
      pairedName = paired?.name,
      search = search,
      typed = typed,
      addressOpen = local.addressOpen,
      address = local.address,
      addressCheck = local.check,
      code = local.code,
      deviceName = local.deviceName,
      pairing = local.pairing,
      codeError = local.codeError,
      codeErrorSerial = local.codeErrorSerial,
      done = local.done,
    )
    val selection = local.selection?.takeIf { key -> draft.rows.any { it.key == key } }
      ?: ConnectRules.defaultSelection(latest, draft.rows)
    return draft.copy(selectedKey = selection)
  }

  private data class Local(
    val selection: String?,
    val addressOpen: Boolean,
    val address: String,
    val deviceName: String,
    val typed: FoundComputer? = null,
    val check: AddressCheck? = null,
    val code: String = "",
    val pairing: Boolean = false,
    val codeError: CodeError? = null,
    val codeErrorSerial: Int = 0,
    val done: ConnectDone? = null,
  )

  @AssistedFactory
  interface Factory {
    fun create(key: ConnectKey): ConnectViewModel
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
    const val SELECTION = "selection"
    const val ADDRESS_OPEN = "addressOpen"
    const val ADDRESS = "address"
    const val DEVICE_NAME = "deviceName"
  }
}
