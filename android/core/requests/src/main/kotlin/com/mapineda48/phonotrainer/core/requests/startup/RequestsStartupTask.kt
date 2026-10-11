package com.mapineda48.phonotrainer.core.requests.startup

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.RequestWork
import com.mapineda48.phonotrainer.core.requests.rules.CopyCodes
import com.mapineda48.phonotrainer.core.requests.rules.QueueEvent
import com.mapineda48.phonotrainer.core.requests.rules.stored
import com.mapineda48.phonotrainer.core.requests.rules.storedState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The device queue's copy checks at every start (architecture 4.6, 8.3), on rows and files last touched before this
 * process started, so a share that cold-starts the app is never mistaken for an interrupted one:
 * - a shared file still `PREPARING` lost its descriptor with the old process: `COPY_FAILED` `copy_interrupted`, its
 *   part deleted (QT-13); a picked file's copy restarts by itself (F-19-AC4);
 * - a request left `SENDING` goes back to waiting with its key (nothing is counted, nothing is lost);
 * - a waiting file whose kept copy is gone becomes `COPY_MISSING` and is never sent (F-20-AC9);
 * - directories under `requests/` that no request owns are deleted, and answered outbox operations older than 30 days.
 *
 * It also starts the queue's reconnect rule: on every transition to `Connected` the waits of the retry schedule are
 * cleared, so waiting requests and operations go at once (spec 7.7); their attempt counts stay.
 */
@StartupOrder(RequestsStartupTask.ORDER)
@Singleton
internal class RequestsStartupTask @Inject constructor(
  private val core: QueueCore,
  private val work: RequestWork,
  private val status: ComputerStatusRepository,
  @param:ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) : StartupTask {
  private val watching = AtomicBoolean(false)

  override suspend fun run(processStart: Instant) {
    val before = processStart.toEpochMilli()
    val rows = core.requests.all()
    rows.filter { it.updatedAt < before }.forEach { row ->
      val id = RequestId(row.id)
      when (storedState(row.state)) {
        RequestState.PREPARING -> if (row.sourcePersisted || core.files.resolve(id, row.copyFile)?.isFile == true) {
          core.files.part(id).delete()
          work.startFileIntake(id)
        } else {
          core.apply(id, QueueEvent.CopyFailed(CopyCodes.INTERRUPTED))
        }
        RequestState.SENDING -> core.apply(id, QueueEvent.Blocked)
        else -> Unit
      }
    }
    core.requests.inStates(listOf(RequestState.WAITING.stored(), RequestState.SENDING.stored())).forEach { row ->
      if (row.kind != RequestKind.FILE.stored() || !row.copyComplete || row.updatedAt >= before) return@forEach
      val copy = core.files.resolve(RequestId(row.id), row.copyFile)
      if (copy == null || !copy.isFile) core.apply(RequestId(row.id), QueueEvent.CopyMissing)
    }
    sweepOrphans(before, rows.mapTo(HashSet()) { it.id })
    core.ops.pruneFinished(before - KEEP_FINISHED_OPS.toMillis())
    watchReconnects()
  }

  private fun sweepOrphans(before: Long, owned: Set<String>) {
    val dirs = core.files.root.listFiles() ?: return
    dirs.filter { it.name !in owned && it.lastModified() < before }.forEach { dir ->
      if (!dir.deleteRecursively()) log.w("requests.orphan_not_deleted")
    }
  }

  private fun watchReconnects() {
    if (!watching.compareAndSet(false, true)) return
    var wasConnected = status.status.value is ComputerStatus.Connected
    scope.launch {
      status.status.map { it is ComputerStatus.Connected }.distinctUntilChanged().collect { connected ->
        if (connected && !wasConnected) {
          core.transaction {
            core.requests.clearWaits()
            core.ops.clearWaits()
          }
        }
        wasConnected = connected
      }
    }
  }

  companion object {
    /** After `StartupCheck`'s own sweeps of clip files (the order is `StartupCheck`'s, architecture 4.6). */
    const val ORDER: Int = 20

    private val KEEP_FINISHED_OPS: Duration = Duration.ofDays(30)
  }
}
