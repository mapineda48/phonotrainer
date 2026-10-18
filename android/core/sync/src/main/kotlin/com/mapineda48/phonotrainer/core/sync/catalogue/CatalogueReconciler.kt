package com.mapineda48.phonotrainer.core.sync.catalogue

import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.sync.meta.PendingMassDeletion
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The computer a listing must come from: the paired one, by server id, with the name the prompt says. */
internal data class PairedSource(val serverId: String, val serverName: String)

/** What reconciling one listing did. */
internal sealed interface ListingOutcome {
  /** Another computer's listing: ignored entirely, nothing deleted (QT-11). */
  data object OtherComputer : ListingOutcome

  /**
   * Applied: [libraryChanged] when the computer's earlier library's clips were tagged `OTHER_LIBRARY` (QT-14);
   * [removed] the clips deleted now; [askedLearner] when a mass deletion waits for the learner instead.
   */
  data class Applied(val libraryChanged: Boolean, val removed: Set<String>, val askedLearner: Boolean) : ListingOutcome
}

/**
 * The catalogue reconcile (architecture 7.3, 7.6, 7.7): a complete listing of the paired computer upserts every job
 * into `clip` and its artifacts into `artifact`, a new library id tags the earlier library's clips (never deleted, never
 * touched again by the new library's listings), and a clip missing from two consecutive complete listings of the same
 * server and library is deleted, unless more than half of the computer's clips would go at once: then the learner is
 * asked (the mass-deletion guard). Lean channel events update the job's state between listings.
 */
@Singleton
internal class CatalogueReconciler @Inject constructor(
  private val db: LibraryDatabase,
  private val clips: ClipDao,
  private val artifacts: ArtifactDao,
  private val meta: SyncMeta,
  private val removal: ClipRemoval,
  private val messages: UserMessages,
  private val clock: Clock,
  private val log: PhonoLog,
) {
  private val mutex = Mutex()

  suspend fun reconcile(library: RemoteLibrary, paired: PairedSource): ListingOutcome = mutex.withLock {
    if (library.serverId.value != paired.serverId) {
      log.w("sync.listing_other_computer")
      return@withLock ListingOutcome.OtherComputer
    }
    val server = paired.serverId
    val libraryId = library.libraryId.value
    val now = clock.now().toEpochMilli()
    val listed = library.jobs.mapTo(HashSet()) { it.id.value }
    var tagged = 0
    val candidates = ArrayList<String>()
    var activeCount = 0
    db.withTransaction {
      tagged = clips.tagOtherLibrary(server, libraryId)
      val existing = clips.getAll(listed.toList()).associateBy { it.jobId }
      for (job in library.jobs) upsert(job, existing[job.id.value], server, libraryId, now)
      val active = clips.active(server, libraryId)
      activeCount = active.size
      for (clip in active) {
        if (clip.jobId in listed || clip.missingListings == KEPT_BY_LEARNER) continue
        val missing = clip.missingListings + 1
        clips.update(clip.copy(missingListings = missing))
        if (missing >= DELETE_AFTER_MISSING) candidates += clip.jobId
      }
    }
    if (tagged > 0) messages.post(UserMessageKind.LIBRARY_CHANGED)
    meta.recordListing(server, libraryId, clock.now())
    val removed: Set<String>
    val asked: Boolean
    if (isMass(candidates.size, activeCount)) {
      meta.setPendingMassDeletion(PendingMassDeletion(candidates, candidates.size, activeCount, paired.serverName))
      removed = emptySet()
      asked = true
    } else {
      meta.setPendingMassDeletion(null)
      removed = removal.deletedOnComputer(candidates)
      asked = false
    }
    ListingOutcome.Applied(libraryChanged = tagged > 0, removed = removed, askedLearner = asked)
  }

  /**
   * The learner answered the mass-deletion question: [remove] deletes the clips the prompt names (those still
   * missing); otherwise they stay, and no later listing asks about them again until they are listed once more.
   */
  suspend fun resolveMassDeletion(remove: Boolean) {
    val prompt = meta.pendingMassDeletion() ?: return
    meta.setPendingMassDeletion(null)
    val still = clips.getAll(prompt.jobIds).filter { it.origin == Origins.ACTIVE && it.missingListings >= DELETE_AFTER_MISSING }
    if (remove) {
      removal.deletedOnComputer(still.map { it.jobId })
    } else {
      db.withTransaction { still.forEach { clips.update(it.copy(missingListings = KEPT_BY_LEARNER)) } }
    }
  }

  /**
   * A lean job of the channel (architecture 7.6), for the computer and library the channel said hello with: updates
   * the job's state between listings, or adds a job this phone did not know yet. Returns true when a pass must fetch
   * the listing (a new job, or a revision other than the one the last listing reported).
   */
  suspend fun applyLean(job: RemoteJob, paired: PairedSource, libraryId: String): Boolean = mutex.withLock {
    val existing = clips.get(job.id.value)
    if (existing == null) {
      clips.insert(job.toClipRow(null, paired.serverId, libraryId, clock.now().toEpochMilli(), lean = true))
      return@withLock true
    }
    if (existing.origin != Origins.ACTIVE || existing.serverId != paired.serverId) return@withLock false
    clips.update(job.toClipRow(existing, paired.serverId, existing.libraryId, clock.now().toEpochMilli(), lean = true))
    existing.revision != job.revision
  }

  private suspend fun upsert(job: RemoteJob, existing: ClipEntity?, server: String, libraryId: String, now: Long) {
    if (existing != null) {
      if (existing.serverId != server) return
      val sameLibraryAgain = Origins.of(existing.origin) == ClipOrigin.OTHER_LIBRARY && existing.libraryId == libraryId
      // Clips of a forgotten computer, or of another library, are never touched by a listing (7.3); a clip of this
      // very library comes back when the computer uses its library again.
      if (existing.origin != Origins.ACTIVE && !sameLibraryAgain) return
    }
    val row = job.toClipRow(existing, server, libraryId, now)
    if (existing == null) clips.insert(row) else clips.update(row)
    reconcileArtifacts(job, now)
  }

  private suspend fun reconcileArtifacts(job: RemoteJob, now: Long) {
    val listed = job.artifacts ?: return
    val jobId = job.id.value
    val rows = artifacts.forJob(jobId).associateBy { it.name }
    val names = HashSet<String>()
    for (artifact in listed) {
      if (!artifact.storable || !names.add(artifact.name)) continue
      val row = rows[artifact.name]
      val updated = artifact.toArtifactRow(jobId, row, now)
      if (row == null) artifacts.insert(updated) else artifacts.update(updated)
    }
    for (row in rows.values) {
      if (row.name in names) continue
      if (row.localState == LocalStates.READY && row.localFile != null) artifacts.update(row.unlisted(now))
      else artifacts.delete(jobId, row.name)
    }
  }

  private fun isMass(count: Int, ofTotal: Int): Boolean = count > 1 && count * 2 > ofTotal

  companion object {
    /** Consecutive complete listings without a clip before it is deleted (spec 7.9). */
    const val DELETE_AFTER_MISSING = 2

    /** `missing_listings` of a clip the learner chose to keep after the mass-deletion question. */
    const val KEPT_BY_LEARNER = -1
  }
}
