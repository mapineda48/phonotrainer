package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What "Forget this computer…" did last: nothing yet, done (with the name it forgot), or refused. */
sealed interface ForgetOutcome {
  data object None : ForgetOutcome

  data class Forgotten(val name: String) : ForgetOutcome

  data class Failed(val name: String) : ForgetOutcome
}

/** Settings > Your computer (spec F-22 "Your computer", design 10.2 P-12). */
@Immutable
data class YourComputerUiState(
  val status: ComputerStatus = ComputerStatus.NotConnected,
  val paired: PairedComputer? = null,
  val sync: SyncState = SyncState.Idle,
  val lastSynced: Instant? = null,
  val checkedAt: Instant? = null,
  val backgroundRestricted: Boolean = false,
  val now: Instant? = null,
  val checking: Boolean = false,
  val forgetting: Boolean = false,
  val forget: ForgetOutcome = ForgetOutcome.None,
)

/**
 * Your computer: the paired computer and its status, when it was last seen and last synced, and the four ways on
 * from here ("Check now", "Connection details", "Enter the new address", "Connect again") plus "Forget this
 * computer…" (spec F-22, F-22-AC3). Forgetting goes through [PairingRepository.forget], which removes the credential
 * and keeps every clip and waiting request (architecture 6.3); this page then shows the not-connected state.
 */
@HiltViewModel
internal class YourComputerViewModel @Inject constructor(
  private val computer: ComputerStatusRepository,
  private val pairing: PairingRepository,
  private val sync: SyncController,
  private val clock: Clock,
) : ViewModel() {
  private val local = MutableStateFlow(Local())

  private val lastSynced = sync.lastSynced
    .catch { if (it is CancellationException) throw it else emit(null) }
    .onStart { emit(null) }

  private val connection = combine(
    computer.status,
    computer.paired,
    computer.backgroundRestricted,
    computer.diagnostics.map { it.lastProbeAt },
    sync.state,
  ) { status, paired, restricted, checkedAt, syncState -> Connection(status, paired, restricted, checkedAt, syncState) }

  val state: StateFlow<YourComputerUiState> = combine(connection, lastSynced, local) { c, synced, l ->
    YourComputerUiState(
      status = c.status,
      paired = c.paired,
      sync = c.sync,
      lastSynced = synced ?: c.paired?.lastSync,
      checkedAt = c.checkedAt,
      backgroundRestricted = c.restricted,
      now = clock.now(),
      checking = l.checking,
      forgetting = l.forgetting,
      forget = l.forget,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), YourComputerUiState())

  /** "Check now": one probe of the paired computer; the status follows from the repository. */
  fun checkNow() {
    if (local.value.checking) return
    local.update { it.copy(checking = true) }
    viewModelScope.launch {
      try {
        computer.checkNow()
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
      } finally {
        local.update { it.copy(checking = false) }
      }
    }
  }

  /** The status card's "Sync now". */
  fun syncNow() {
    sync.syncNow(SyncReason.SYNC_NOW)
  }

  /** "Forget this computer" confirmed (F-22-AC3). */
  fun forget() {
    if (local.value.forgetting) return
    val name = computer.paired.value?.name ?: return
    local.update { it.copy(forgetting = true, forget = ForgetOutcome.None) }
    viewModelScope.launch {
      val result = try {
        pairing.forget()
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        null
      }
      val outcome = if (result is ForgetResult.Forgotten) ForgetOutcome.Forgotten(name) else ForgetOutcome.Failed(name)
      local.update { it.copy(forgetting = false, forget = outcome) }
    }
  }

  private data class Local(val checking: Boolean = false, val forgetting: Boolean = false, val forget: ForgetOutcome = ForgetOutcome.None)

  private data class Connection(
    val status: ComputerStatus,
    val paired: PairedComputer?,
    val restricted: Boolean,
    val checkedAt: Instant?,
    val sync: SyncState,
  )

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
