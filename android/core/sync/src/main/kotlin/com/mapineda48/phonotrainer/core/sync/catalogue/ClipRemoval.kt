package com.mapineda48.phonotrainer.core.sync.catalogue

import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.spi.ClipLeases
import com.mapineda48.phonotrainer.core.domain.spi.ClipRemovalHook
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.sync.transfer.ClipFiles
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Removing clips deleted on the computer (architecture 7.7, spec 7.9): their files, their rows (the artifact, short and
 * occurrence rows go with them), and, through the removal hooks, their derived rows, saved items and positions; per-change
 * practice stats are kept by their owner. A clip a screen holds open (a [ClipLeases] lease) is only marked
 * `deleted_on_computer` and removed when its last lease closes (QT-10). One notice per batch counts the clips the
 * learner had in the catalogue (finished ones); jobs that never became clips leave silently.
 */
@Singleton
internal class ClipRemoval @Inject constructor(
  private val db: LibraryDatabase,
  private val clips: ClipDao,
  private val files: ClipFiles,
  private val leases: ClipLeases,
  private val hooks: Provider<Set<@JvmSuppressWildcards ClipRemovalHook>>,
  private val reconciler: Provider<RequestReconciler>,
  private val messages: UserMessages,
  private val log: PhonoLog,
) {
  private val mutex = Mutex()

  /**
   * Clips [jobIds] were deleted on the computer: removed now, or when the screens showing them close. The device queue
   * hears of each one (its request, if any, is gone with it). Returns the ids removed now.
   */
  suspend fun deletedOnComputer(jobIds: Collection<String>): Set<String> {
    if (jobIds.isEmpty()) return emptySet()
    val known = clips.getMany(jobIds)
    for (id in known.map { it.jobId }) {
      runCatching { reconciler.get().onJobDeleted(JobId(id)) }.onFailure { failed ->
        if (failed is CancellationException) throw failed
        log.w("sync.request_not_told_of_deletion", failed)
      }
    }
    if (known.isEmpty()) return emptySet()
    clips.markDeletedOnComputerMany(known.map { it.jobId })
    return applyDeferred()
  }

  /**
   * Removes every clip marked `deleted_on_computer` that no screen holds open any longer; returns their ids. Called
   * after each deletion, when a lease closes and at every pass (the lease flow is hot without replay).
   */
  suspend fun applyDeferred(): Set<String> = mutex.withLock {
    val due = clips.deferredRemovals().filterNot { leases.isOpen(JobId(it)) }
    if (due.isEmpty()) return@withLock emptySet()
    val rows = clips.getMany(due)
    db.withTransaction { clips.deleteMany(due) }
    due.forEach { id -> if (!files.deleteClip(id)) log.w("sync.clip_files_not_deleted") }
    val ids = due.mapTo(LinkedHashSet()) { JobId(it) }
    for (hook in hooks.get()) {
      try {
        hook.onClipsRemoved(ids)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failed: RuntimeException) {
        log.w("sync.removal_hook_failed", failed)
      }
    }
    val shown = rows.count { it.status == ClipStatuses.DONE }
    if (shown > 0) messages.post(UserMessageKind.CLIPS_REMOVED, listOf(shown.toString()))
    due.toSet()
  }
}
