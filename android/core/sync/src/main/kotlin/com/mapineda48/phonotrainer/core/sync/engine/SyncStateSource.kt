package com.mapineda48.phonotrainer.core.sync.engine

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Pauses
import com.mapineda48.phonotrainer.core.sync.transfer.LiveTransfer
import com.mapineda48.phonotrainer.core.sync.transfer.TransferBoard
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * [SyncState] (Appendix A.2, spec 7.11): "Syncing · bringing 3 clips · 42 %" while a pass or a download runs, with the
 * bytes of the files on their way ("34 MB of 61 MB"); paused with its reason when downloads wait (not connected, the
 * free-space floor, the budget); idle otherwise. "Syncing" is only ever shown together with `Connected` (3.4).
 */
@Singleton
internal class SyncStateSource @Inject constructor(
  artifacts: ArtifactDao,
  status: ComputerStatusRepository,
  board: TransferBoard,
  passes: PassFlag,
  requests: Provider<RequestRepository>,
  @ApplicationScope scope: CoroutineScope,
  private val log: PhonoLog,
) {
  private val counts: Flow<RequestCounts> = flow { emitAll(requests.get().counts()) }
    .catch { failed ->
      if (failed is CancellationException) throw failed
      log.w("sync.counts_unknown", failed)
    }
    .onStart { emit(RequestCounts(0, 0, 0, 0)) }

  val state: StateFlow<SyncState> = combine(
    artifacts.observeAll(),
    status.status,
    combine(board.active, board.current, passes.running) { downloading, live, passing -> Triple(downloading, live, passing) },
    counts,
  ) { rows, current, (downloading, live, passing), counts ->
    stateOf(rows, current is ComputerStatus.Connected, downloading, live, passing, counts.waiting)
  }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, SyncState.Idle)

  companion object {
    private const val PERCENT = 100

    /** The pure rule, tested on its own. */
    fun stateOf(
      rows: List<ArtifactEntity>,
      connected: Boolean,
      downloading: Boolean,
      live: LiveTransfer?,
      passing: Boolean,
      waitingRequests: Int,
    ): SyncState {
      val pending = rows.filter { it.onItsWay() }
      val sending = if (connected && passing) waitingRequests else 0
      if (pending.isEmpty()) {
        return if (connected && (passing || downloading)) SyncState.Active(bringing = 0, percent = null, sending = sending) else SyncState.Idle
      }
      if (!connected) return SyncState.Paused(PauseReason.NOT_CONNECTED)
      val paused = pending.filter { it.pauseReason != null && it.pauseReason != Pauses.REMOVED_BY_LEARNER }
      if (!downloading && !passing && paused.size == pending.size) {
        val reasons = paused.mapNotNull { Pauses.of(it.pauseReason) }.toSet()
        val reason = if (PauseReason.FREE_SPACE_FLOOR in reasons) PauseReason.FREE_SPACE_FLOOR else reasons.firstOrNull()
        if (reason != null) return SyncState.Paused(reason)
      }
      var done = 0L
      var total = 0L
      for (row in pending) {
        val bytes = row.remoteBytes ?: continue
        total += bytes
        done += (if (live != null && live.jobId == row.jobId && live.name == row.name) live.doneBytes else row.partBytes ?: 0)
          .coerceIn(0, bytes)
      }
      return SyncState.Active(
        bringing = pending.mapTo(HashSet()) { it.jobId }.size,
        percent = if (total > 0) (done * PERCENT / total).toInt().coerceIn(0, PERCENT - 1) else null,
        sending = sending,
        doneBytes = done.takeIf { total > 0 },
        totalBytes = total.takeIf { it > 0 },
      )
    }

    /** A file on its way to this phone: waiting, downloading or paused, or a new revision of a file in use. */
    private fun ArtifactEntity.onItsWay(): Boolean = when (localState) {
      LocalStates.QUEUED, LocalStates.DOWNLOADING -> true
      LocalStates.PAUSED -> pauseReason != Pauses.REMOVED_BY_LEARNER
      LocalStates.READY -> partRevision != null
      else -> false
    }
  }
}

/**
 * Whether a sync pass is running: written by the orchestrator, read by the sync state without creating the
 * orchestrator (which needs the device queue, whose requests ask the sync engine for passes).
 */
@Singleton
internal class PassFlag @Inject constructor() {
  val running: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
