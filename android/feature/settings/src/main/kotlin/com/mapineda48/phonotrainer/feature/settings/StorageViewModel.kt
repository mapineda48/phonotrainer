package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the measurement of what this phone keeps stands. */
sealed interface StorageRead {
  data object Loading : StorageRead

  data object Failed : StorageRead

  data class Ready(val usage: StorageUsage) : StorageRead
}

/** Settings > Storage (spec F-21, design 9.20). */
@Immutable
data class StorageUiState(
  val read: StorageRead = StorageRead.Loading,
  val downloadVideos: Boolean = true,
  /** Why downloads wait, when the sync engine says they do. */
  val paused: PauseReason? = null,
  val backgroundRestricted: Boolean = false,
  /** The clips whose action is running (no second tap until it ends). */
  val busy: Set<JobId> = emptySet(),
) {
  val usage: StorageUsage? get() = (read as? StorageRead.Ready)?.usage
}

/** The four budgets of spec F-21 (2 GB the default, decimal units as the budget is stored). */
internal val BUDGETS: List<StorageBudget> = listOf(
  StorageBudget.Limit(1_000_000_000),
  StorageBudget.Limit(2_000_000_000),
  StorageBudget.Limit(5_000_000_000),
  StorageBudget.FreeSpaceOnly,
)

/**
 * Storage (spec F-21): what this phone keeps, by category, against the budget and the free space; the budget and
 * "Download videos automatically"; the clips on this phone, largest first, each with "Remove the video", "Remove from
 * this phone…" and "Keep on this phone"; and "Remove all videos". Nothing here is sent to the computer (F-05-AC11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class StorageViewModel @Inject constructor(
  private val storage: StorageRepository,
  private val settings: SettingsRepository,
  private val clips: ClipRepository,
  private val trigger: SyncTrigger,
  sync: SyncController,
  computer: ComputerStatusRepository,
) : ViewModel() {
  private val attempts = MutableStateFlow(0)
  private val busy = MutableStateFlow(emptySet<JobId>())

  private val read: Flow<StorageRead> = attempts.flatMapLatest {
    storage.usage
      .map<StorageUsage, StorageRead> { StorageRead.Ready(it) }
      .catch { if (it is CancellationException) throw it else emit(StorageRead.Failed) }
  }

  private val paused: Flow<PauseReason?> = sync.state.map { (it as? SyncState.Paused)?.reason }

  val state: StateFlow<StorageUiState> = combine(
    read,
    settings.settings,
    paused,
    computer.backgroundRestricted,
    busy,
  ) { read, current, reason, restricted, working ->
    StorageUiState(read, current.downloadVideos, reason, restricted, working)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StorageUiState(downloadVideos = settings.settings.value.downloadVideos))

  fun retry() {
    attempts.value += 1
  }

  fun setBudget(budget: StorageBudget) {
    viewModelScope.launch { quietly { storage.setBudget(budget) } }
  }

  /** "Download videos automatically": on asks for a sync at once, so the videos within the budget start coming. */
  fun setDownloadVideos(on: Boolean) {
    viewModelScope.launch {
      quietly { settings.update { it.copy(downloadVideos = on) } }
      if (on) trigger.request(SyncReason.SYNC_NOW)
    }
  }

  fun removeVideo(id: JobId) = perform(id) { clips.removeVideo(id) }

  /** "Remove from this phone" confirmed: a local removal only (F-05-AC11). */
  fun removeFromPhone(id: JobId) = perform(id) { clips.removeFromPhone(id) }

  fun setKept(id: JobId, kept: Boolean) = perform(id) { clips.setKept(id, kept) }

  fun removeAllVideos() {
    viewModelScope.launch { quietly { storage.removeAllVideos() } }
  }

  private fun perform(id: JobId, action: suspend () -> Unit) {
    if (id in busy.value) return
    busy.update { it + id }
    viewModelScope.launch {
      try {
        quietly { action() }
      } finally {
        busy.update { it - id }
      }
    }
  }

  /** Runs [action]; a failure leaves the page as the repositories say it is (they are the truth on screen). */
  private suspend fun quietly(action: suspend () -> Unit) {
    try {
      action()
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
