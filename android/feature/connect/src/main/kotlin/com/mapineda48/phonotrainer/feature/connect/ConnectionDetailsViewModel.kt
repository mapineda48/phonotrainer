package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.DecoderCapabilities
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ConnectionDiagnostics
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything Connection details shows (P-07). */
@Immutable
internal data class DetailsUiState(
  val status: ComputerStatus = ComputerStatus.NotConnected,
  val sync: SyncState = SyncState.Idle,
  val paired: Boolean = false,
  val diagnostics: ConnectionDiagnostics = EMPTY_DIAGNOSTICS,
  /** The decoders this phone reports (architecture 11.7); null while they are being read. */
  val decoders: List<DecoderInfo>? = null,
  val now: Instant? = null,
  val checking: Boolean = false,
  val copied: Boolean = false,
) {
  companion object {
    val EMPTY_DIAGNOSTICS = ConnectionDiagnostics(null, null, null, null, null, null, 0, 0, 0, false, emptyList())
  }
}

/** The app's version and Android's, for "Copy diagnostics" (they say nothing about the owner or the network). */
@Immutable
internal data class PlatformInfo(val appVersion: String?, val androidRelease: String, val sdk: Int)

/**
 * Connection details (spec 7.5 "Why?", F-22 "Connection details", design 10.2 P-07): the status, the last probe in
 * words, the facts of `ConnectionDiagnostics` (this phone's address included, on screen only, D-39), the decoders and
 * "Check now". The decoder list is read once, off the main thread (MediaCodecList can take a moment).
 */
@HiltViewModel
internal class ConnectionDetailsViewModel @Inject constructor(
  private val computer: ComputerStatusRepository,
  private val sync: SyncController,
  private val decoderCapabilities: DecoderCapabilities,
  private val clock: Clock,
) : ViewModel() {
  private val local = MutableStateFlow(Local())

  private val connection = combine(computer.status, computer.paired, computer.diagnostics, sync.state) { status, paired, diagnostics, syncState ->
    Connection(status, paired != null, diagnostics, syncState)
  }

  val state: StateFlow<DetailsUiState> = combine(connection, local) { c, l ->
    DetailsUiState(
      status = c.status,
      sync = c.sync,
      paired = c.paired,
      diagnostics = c.diagnostics,
      decoders = l.decoders ?: c.diagnostics.decoders.takeIf { it.isNotEmpty() },
      now = clock.now(),
      checking = l.checking,
      copied = l.copied,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailsUiState())

  init {
    viewModelScope.launch {
      val decoders = try {
        withContext(Dispatchers.Default) { decoderCapabilities.summary() }
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        emptyList()
      }
      local.update { it.copy(decoders = decoders) }
    }
  }

  /** "Check now": one probe of the paired computer, once at a time; the status follows from the repository. */
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
  fun syncNow() = sync.syncNow(SyncReason.SYNC_NOW)

  /** The diagnostics went to the clipboard: the page says what they hold. */
  fun copied() = local.update { it.copy(copied = true) }

  private data class Local(val decoders: List<DecoderInfo>? = null, val checking: Boolean = false, val copied: Boolean = false)

  private data class Connection(val status: ComputerStatus, val paired: Boolean, val diagnostics: ConnectionDiagnostics, val sync: SyncState)

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
