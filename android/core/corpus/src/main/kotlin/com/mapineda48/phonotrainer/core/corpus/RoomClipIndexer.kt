package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.analysis.ChangeAdvice
import com.mapineda48.phonotrainer.core.analysis.ClipDerivation
import com.mapineda48.phonotrainer.core.analysis.ShortsV1
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.dao.ShortDao
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The clip indexer (architecture 9.5): after the sync pass verified a clip's clip data, and after an app update raised
 * [ShortsV1.RULE_VERSION], it parses the analysis (through the shared cache), derives the shorts and the occurrences
 * with `:core:analysis` ([ClipDerivation], advice and families from the current reference) and replaces the clip's
 * `short` and `occurrence` rows in one transaction that also records `indexed_revision` (the clip-data file's
 * revision) and `index_rule_version`.
 *
 * Dropping a clip's index (the learner removed it, or it was deleted on the computer) is **deferred while a screen
 * holds a lease on it** (architecture 7.7, QT-10): the open feed, lesson or Clip page keeps its shorts until it
 * closes; indexing the clip again cancels a deferred drop.
 *
 * Indexing runs one clip at a time; parsing and deriving run on [derivation] (`Dispatchers.Default`), never on the
 * caller's thread (NFR-02).
 */
@Singleton
internal class RoomClipIndexer @Inject constructor(
  private val shorts: ShortDao,
  private val loader: ClipDataLoader,
  private val reference: ReferenceRepository,
  private val leases: ClipLeaseRegistry,
  @DefaultDispatcher private val derivation: CoroutineDispatcher,
  @ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) : ClipIndexer {
  private val mutex = Mutex()

  /** Drops waiting for a clip's last lease to close ([Drop.WAITING]) or due since it closed ([Drop.DUE]). */
  private val deferred = HashMap<JobId, Drop>()

  private enum class Drop { WAITING, DUE }

  init {
    leases.onReleased { id ->
      val due = synchronized(deferred) { deferred.replace(id, Drop.WAITING, Drop.DUE) }
      if (due) scope.launch { dropDeferred(id) }
    }
  }

  override suspend fun index(id: JobId): IndexOutcome = mutex.withLock {
    // A fresh index wins over a drop still waiting for a lease.
    synchronized(deferred) { deferred.remove(id) }
    val file = loader.currentFile(id) ?: return@withLock IndexOutcome.Missing
    val revision = ClipDataLoader.revisionOf(file)
    val state = shorts.indexState(id.value) ?: return@withLock IndexOutcome.Missing
    if (state.indexedRevision == revision && state.ruleVersion == ShortsV1.RULE_VERSION) {
      return@withLock IndexOutcome.Unchanged
    }
    val clip = try {
      loader.load(id, file)
    } catch (failed: ClipDataException) {
      log.w("corpus.index_unreadable", failed) { "clip data of ${id.value}: ${failed.error}" }
      return@withLock if (failed.error == AppError.Content.Corrupt) IndexOutcome.Corrupt else IndexOutcome.Missing
    }
    val derived = try {
      withContext(derivation) { ClipDerivation.derive(clip, ChangeAdvice(reference.reference.value)) }
    } catch (failed: IllegalArgumentException) {
      // An analysis the rules cannot cut into shorts is unusable here, like a file that does not parse.
      log.w("corpus.index_underivable", failed) { "clip ${id.value}" }
      return@withLock IndexOutcome.Corrupt
    }
    val written = shorts.replaceIndex(
      jobId = id.value,
      shorts = derived.shorts.map { it.toEntity(id, derived.ruleVersion) },
      occurrences = derived.occurrences.map { it.toEntity() },
      indexedRevision = revision,
      ruleVersion = derived.ruleVersion,
    )
    if (!written) return@withLock IndexOutcome.Missing
    log.d { "indexed ${id.value}: ${derived.shorts.size} shorts, ${derived.occurrences.size} occurrences" }
    IndexOutcome.Indexed(shorts = derived.shorts.size, occurrences = derived.occurrences.size)
  }

  override suspend fun reindexStale() {
    for (stale in shorts.staleIndexes(ShortsV1.RULE_VERSION)) {
      val outcome = index(JobId(stale.jobId))
      log.d { "re-indexed ${stale.jobId} (rules ${stale.ruleVersion} → ${ShortsV1.RULE_VERSION}): $outcome" }
    }
  }

  /** Drops the index of [ids] now, except for clips with an open lease, dropped when their last lease closes. */
  override suspend fun drop(ids: Set<JobId>) {
    if (ids.isEmpty()) return
    val now = ids.filterNot { id ->
      synchronized(deferred) {
        leases.isOpen(id).also { open -> if (open) deferred[id] = Drop.WAITING }
      }
    }.toSet()
    if (now.isNotEmpty()) mutex.withLock { dropNow(now) }
  }

  private suspend fun dropDeferred(id: JobId) = mutex.withLock {
    val due = synchronized(deferred) {
      when {
        // Indexed again, or already dropped, since the lease closed.
        deferred[id] != Drop.DUE -> false
        // Opened again since: wait for that lease too.
        leases.isOpen(id) -> {
          deferred[id] = Drop.WAITING
          false
        }
        else -> {
          deferred.remove(id)
          true
        }
      }
    }
    if (due) dropNow(setOf(id))
  }

  /** Called with [mutex] held. */
  private suspend fun dropNow(ids: Set<JobId>) {
    loader.evict(ids)
    shorts.dropIndex(ids.map { it.value })
    log.d { "dropped the index of ${ids.size} clips" }
  }

  /** Whether a drop of [id] waits for its lease (tests). */
  fun isDropDeferred(id: JobId): Boolean = synchronized(deferred) { id in deferred }
}
