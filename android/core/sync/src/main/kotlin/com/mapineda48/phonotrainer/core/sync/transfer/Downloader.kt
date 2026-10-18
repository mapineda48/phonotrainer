package com.mapineda48.phonotrainer.core.sync.transfer

import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.DecoderCapabilities
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import com.mapineda48.phonotrainer.core.domain.spi.StudyProtection
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactErrors
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactNames
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Pauses
import com.mapineda48.phonotrainer.core.sync.catalogue.Roles
import com.mapineda48.phonotrainer.core.sync.storage.Admission
import com.mapineda48.phonotrainer.core.sync.storage.DeviceSpace
import com.mapineda48.phonotrainer.core.sync.storage.StoragePolicy
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How a download run ended. */
internal enum class RunOutcome {
  /** Nothing more to download now (every wanted file is here, waits, or failed for this run). */
  DONE,

  /** The computer is not connected (or stopped answering): the queue waits for the next connection. */
  NOT_CONNECTED,

  /** The computer refuses this phone (connect again, phone access off, a different computer, an update needed). */
  BLOCKED,

  /** Below the free-space floor: every download pauses (spec 7.10). */
  PAUSED,
}

/** What the run reports to a visible transfer's notification: the file, its progress, the files still to come. */
internal data class RunProgress(val title: String, val doneBytes: Long, val totalBytes: Long, val filesLeft: Int)

/**
 * The downloads of architecture 7.4, one file at a time, re-reading the queue after each file:
 * - **transfer** to `clips/<jobId>/.<name>.<revision>.part`, resumed with `Range` and `If-Range` from the part's length
 *   (the transfer syncs the part before each progress report); a part already complete, or a `416`, is verified
 *   without restarting; a `200` where `206` was expected restarts from zero;
 * - **verify** size and SHA-256 against the listing; a mismatch deletes the part and retries once, the second failure
 *   marks the file failed ("Download failed: the file was damaged on the way.") and the previous good file stays;
 * - **atomic switch**: the part is renamed to `<name>.<sha8>.<ext>` and the row updated in one transaction, then the
 *   previous file is deleted; readers resolve files through the row, so they always see a complete file;
 * - a clip is "On this phone" only once its study pack is complete and verified (SC-11): its clip data is indexed
 *   first (`ClipIndexer`), then the device queue hears of it once (`RequestReconciler.onClipOnPhone`);
 * - **pauses**: not connected, the free-space floor, the budget (7.5); after each file the budget step evicts videos.
 */
@Singleton
internal class Downloader @Inject constructor(
  private val db: LibraryDatabase,
  private val clips: ClipDao,
  private val artifacts: ArtifactDao,
  private val files: ClipFiles,
  private val transfer: ArtifactTransfer,
  private val status: ComputerStatusRepository,
  private val pairing: PairingStore,
  private val settings: SettingsRepository,
  private val decoders: DecoderCapabilities,
  private val protection: StudyProtection,
  private val space: DeviceSpace,
  private val indexer: ClipIndexer,
  private val reconciler: Provider<RequestReconciler>,
  private val board: TransferBoard,
  private val startup: StartupGate,
  private val clock: Clock,
  @param:IoDispatcher private val io: CoroutineDispatcher,
  private val log: PhonoLog,
) {
  private val mutex = Mutex()

  /** Downloads until nothing more can be downloaded now; [progress] follows each file (a visible transfer). */
  suspend fun run(progress: (RunProgress) -> Unit = {}): RunOutcome = mutex.withLock {
    startup.awaitDone()
    board.runStarted()
    try {
      loop(progress)
    } finally {
      board.runEnded()
    }
  }

  /**
   * Brings the rows in line with the plan without downloading (architecture 7.1 step 7): wanted files waiting become
   * `QUEUED`, files no longer wanted go back to `ABSENT`, a video this phone cannot play says so. Returns the plan.
   */
  suspend fun refreshQueue(): DownloadPlan {
    val context = planContext() ?: return DownloadPlan(emptyList(), emptySet())
    val allClips = clips.all()
    val rows = artifacts.all()
    val plan = DownloadPlanner.plan(allClips, rows, context)
    applyQueue(plan, rows)
    return plan
  }

  /** The verified videos to remove so the downloaded clips fit the budget again (7.5); removes them. */
  suspend fun enforceBudget(except: Set<String> = emptySet()): Int {
    val budget = settings.settings.value.storageBudget
    val rows = artifacts.all()
    val order = StoragePolicy.evictionOrder(
      clips.all(), rows, protection.lastWatched().keysToString(), protection.protectedClips().mapTo(HashSet()) { it.value },
      except,
    )
    val evict = StoragePolicy.toEvict(StoragePolicy.usage(rows), budget, order)
    for (want in evict) removeVerified(want.artifact)
    if (evict.isNotEmpty()) log.d { "evicted ${evict.size} videos for the budget" }
    return evict.size
  }

  private suspend fun loop(progress: (RunProgress) -> Unit): RunOutcome {
    val skipped = HashSet<String>()
    while (true) {
      currentCoroutineContext().ensureActive()
      if (status.status.value !is ComputerStatus.Connected) return RunOutcome.NOT_CONNECTED
      val context = planContext() ?: return RunOutcome.DONE
      val allClips = clips.all()
      val rows = artifacts.all()
      val plan = DownloadPlanner.plan(allClips, rows, context)
      applyQueue(plan, rows)
      val pending = plan.wanted.filter { key(it) !in skipped }
      val next = pending.firstOrNull() ?: return RunOutcome.DONE
      val fresh = artifacts.get(next.jobId, next.name) ?: continue
      val want = next.copy(artifact = fresh)
      when (admit(want, allClips, rows, context)) {
        Admission.BelowFloor -> {
          pause(pending, Pauses.FREE_SPACE_FLOOR)
          return RunOutcome.PAUSED
        }
        Admission.OverBudget -> {
          pause(listOf(want), Pauses.BUDGET_REACHED)
          skipped += key(want)
          continue
        }
        Admission.Allowed -> Unit
      }
      progress(RunProgress(want.clip.title, partLength(want), want.bytes, pending.size))
      when (fetch(want, pending.size, progress)) {
        Fetched.VERIFIED -> afterVerified(want)
        Fetched.RETRY -> Unit
        Fetched.SKIP -> skipped += key(want)
        Fetched.NOT_CONNECTED -> return RunOutcome.NOT_CONNECTED
        Fetched.BLOCKED -> return RunOutcome.BLOCKED
      }
    }
  }

  private suspend fun admit(want: Want, allClips: List<ClipEntity>, rows: List<ArtifactEntity>, context: PlanContext): Admission {
    val budget = settings.settings.value.storageBudget
    val evictable = StoragePolicy.evictionOrder(
      allClips, rows, context.lastWatched, protection.protectedClips().mapTo(HashSet()) { it.value },
    )
    val free = withContext(io) { space.allocatableBytes() }
    val floor = StoragePolicy.floorBytes(withContext(io) { space.totalBytes() })
    val remaining = (want.bytes - partLength(want)).coerceAtLeast(0)
    return StoragePolicy.admit(
      want, remaining, StoragePolicy.usage(rows), budget, free, floor, evictable, StoragePolicy.videoRank(context.lastWatched),
    )
  }

  private enum class Fetched { VERIFIED, RETRY, SKIP, NOT_CONNECTED, BLOCKED }

  private suspend fun fetch(want: Want, filesLeft: Int, progress: (RunProgress) -> Unit): Fetched {
    val row = want.artifact
    val sha = row.remoteSha256 ?: return Fetched.SKIP
    val url = row.remoteUrl ?: return Fetched.SKIP
    val revision = sha.take(REVISION_DIGITS)
    val part = files.part(want.jobId, want.name, revision) ?: return Fetched.SKIP
    val oldRevision = row.partRevision
    if (oldRevision != null && oldRevision != revision) {
      withContext(io) { files.part(want.jobId, want.name, oldRevision)?.delete() }
    }
    var etag = if (row.partRevision == revision) row.etag else null
    var restarts = 0
    while (true) {
      var from = withContext(io) { if (part.isFile) part.length() else 0L }
      if (from > want.bytes) {
        withContext(io) { part.delete() }
        from = 0
        etag = null
      }
      markDownloading(row, revision, from, etag)
      board.started(want.jobId, want.name, from, want.bytes)
      val outcome = try {
        download(want, part, url, from, etag, filesLeft, progress)
      } finally {
        board.finished()
      }
      when (outcome) {
        is DownloadOutcome.Complete -> return verify(want, part, revision, outcome.etag)
        DownloadOutcome.RangeNotSatisfiable -> return verify(want, part, revision, etag)
        DownloadOutcome.RestartedFromZero -> {
          etag = null
          if (++restarts > MAX_RESTARTS) {
            requeue(want, part, revision, null)
            return Fetched.SKIP
          }
        }
        is DownloadOutcome.Failed -> return failed(want, part, revision, etag, outcome.error)
      }
    }
  }

  private suspend fun download(
    want: Want,
    part: File,
    url: String,
    from: Long,
    etag: String?,
    filesLeft: Int,
    progress: (RunProgress) -> Unit,
  ): DownloadOutcome = coroutineScope {
    val persisted = AtomicLong(from)
    val request = DownloadRequest(url = url, target = part, resumeFrom = from, ifRange = etag, expectedBytes = want.bytes)
    transfer.download(request) { length ->
      board.progress(length)
      progress(RunProgress(want.clip.title, length, want.bytes, filesLeft))
      val last = persisted.get()
      if (length - last >= PERSIST_EVERY_BYTES && persisted.compareAndSet(last, length)) {
        launch { artifacts.recordPart(want.jobId, want.name, length, now()) }
      }
    }
  }

  private suspend fun verify(want: Want, part: File, revision: String, etag: String?): Fetched {
    val expected = want.bytes
    val sha = want.artifact.remoteSha256 ?: return Fetched.SKIP
    val hashed = withContext(io) { if (part.isFile) Sha256.of(part) else null }
    if (hashed == null || hashed.bytes != expected || !Sha256.sameDigest(hashed.sha256, sha)) {
      log.w("sync.download_damaged") { "${want.name} of ${want.jobId}" }
      withContext(io) { part.delete() }
      return damaged(want.artifact, ArtifactErrors.DAMAGED)
    }
    val target = files.verified(want.jobId, want.name, sha) ?: return Fetched.SKIP
    val moved = withContext(io) {
      target.parentFile?.mkdirs()
      if (target.exists()) target.delete()
      part.renameTo(target) || runCatching { part.copyTo(target, overwrite = true); part.delete() }.isSuccess
    }
    if (!moved) {
      log.w("sync.switch_failed")
      requeue(want, part, revision, etag)
      return Fetched.SKIP
    }
    val relative = files.relative(target)
    var previous: String? = null
    val switched = db.withTransaction {
      val current = artifacts.get(want.jobId, want.name) ?: return@withTransaction false
      // The learner removed the clip, or the video, while it was on its way: nothing is switched in.
      val clip = clips.get(want.jobId)
      if (clip == null || clip.removedByLearner || current.pauseReason == Pauses.REMOVED_BY_LEARNER) {
        return@withTransaction false
      }
      previous = current.localFile?.takeIf { it != relative }
      artifacts.update(
        current.copy(
          localState = LocalStates.READY,
          localFile = relative,
          localSha256 = sha,
          localBytes = expected,
          partBytes = null,
          partRevision = null,
          etag = null,
          verifyFailures = 0,
          pauseReason = null,
          lastError = null,
          updatedAt = now(),
        ),
      ) > 0
    }
    withContext(io) {
      if (!switched) target.delete()
      previous?.let(files::resolve)?.delete()
    }
    return if (switched) Fetched.VERIFIED else Fetched.SKIP
  }

  /**
   * A verified file was damaged ([why]: on the way, or clip data that does not parse): one more failure; the second
   * marks it failed. A previous good copy stays in use.
   */
  private suspend fun damaged(row: ArtifactEntity, why: String): Fetched {
    db.withTransaction {
      val current = artifacts.get(row.jobId, row.name) ?: return@withTransaction
      val failures = current.verifyFailures + 1
      val keepsCopy = current.localState == LocalStates.READY && current.localFile != null && why == ArtifactErrors.DAMAGED
      artifacts.update(
        current.copy(
          localState = when {
            keepsCopy -> LocalStates.READY
            failures >= DownloadPlanner.FAILURES_TO_GIVE_UP -> LocalStates.FAILED
            else -> LocalStates.QUEUED
          },
          partBytes = null,
          partRevision = null,
          etag = null,
          verifyFailures = failures,
          lastError = why,
          updatedAt = now(),
        ),
      )
    }
    return Fetched.RETRY
  }

  private suspend fun failed(want: Want, part: File, revision: String, etag: String?, error: AppError.Computer): Fetched {
    log.w("sync.download_failed.${errorCode(error)}")
    return when (error) {
      AppError.Computer.NotOnWifi, AppError.Computer.Offline, is AppError.Computer.Unreachable -> {
        requeue(want, part, revision, etag)
        checkStatus()
        Fetched.NOT_CONNECTED
      }
      AppError.Computer.CredentialRejected, AppError.Computer.PhoneAccessOff, AppError.Computer.AppTooOld,
      AppError.Computer.ComputerTooOld, AppError.Computer.DifferentComputer, AppError.Computer.Guarded -> {
        requeue(want, part, revision, etag)
        checkStatus()
        Fetched.BLOCKED
      }
      AppError.Computer.Malformed -> {
        withContext(io) { part.delete() }
        damaged(want.artifact, ArtifactErrors.DAMAGED)
      }
      is AppError.Computer.ClockSkew, is AppError.Computer.Refused -> {
        requeue(want, part, revision, etag, lastError = (error as? AppError.Computer.Refused)?.code)
        Fetched.SKIP
      }
    }
  }

  private suspend fun afterVerified(want: Want) {
    if (want.artifact.role == Roles.CLIP_DATA) {
      when (indexer.index(JobId(want.jobId))) {
        IndexOutcome.Corrupt -> {
          log.w("sync.clip_data_corrupt")
          artifacts.get(want.jobId, want.name)?.let { removeVerified(it, ArtifactErrors.CORRUPT) }
          damaged(want.artifact.copy(localState = LocalStates.ABSENT), ArtifactErrors.CORRUPT)
        }
        else -> Unit
      }
    }
    if (want.kind == WantKind.PACK) markOnPhoneIfComplete(want.jobId)
    if (want.kind == WantKind.VIDEO) {
      artifacts.get(want.jobId, want.name)?.let { if (it.pauseReason != null) artifacts.update(it.copy(pauseReason = null)) }
    }
    enforceBudget(except = setOf(want.jobId))
  }

  /** The study pack of [jobId] is complete and verified: "On this phone" from now, and the queue hears of it once. */
  suspend fun markOnPhoneIfComplete(jobId: String) {
    val track = settings.settings.value.track
    if (!DownloadPlanner.packOnPhone(artifacts.forJob(jobId), track)) return
    if (clips.markOnPhone(jobId, now()) == 0) return
    try {
      reconciler.get().onClipOnPhone(JobId(jobId))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failed: RuntimeException) {
      log.w("sync.request_not_told_on_phone", failed)
    }
  }

  /** Deletes a verified file of this phone and leaves its row `ABSENT` (an evicted video, corrupt clip data). */
  suspend fun removeVerified(row: ArtifactEntity, why: String? = null) {
    val file = files.resolve(row.localFile)
    db.withTransaction {
      val current = artifacts.get(row.jobId, row.name) ?: return@withTransaction
      artifacts.update(
        current.copy(
          localState = LocalStates.ABSENT,
          localFile = null,
          localSha256 = null,
          localBytes = null,
          lastError = why ?: current.lastError,
          updatedAt = now(),
        ),
      )
    }
    withContext(io) { file?.delete() }
  }

  private suspend fun markDownloading(row: ArtifactEntity, revision: String, from: Long, etag: String?) {
    db.withTransaction {
      val current = artifacts.get(row.jobId, row.name) ?: return@withTransaction
      val keepsCopy = current.localState == LocalStates.READY && current.localFile != null
      artifacts.update(
        current.copy(
          localState = if (keepsCopy) LocalStates.READY else LocalStates.DOWNLOADING,
          partBytes = from,
          partRevision = revision,
          etag = etag,
          pauseReason = null,
          updatedAt = now(),
        ),
      )
    }
  }

  /** A download that stopped and will be resumed: back to the queue with what arrived. */
  private suspend fun requeue(want: Want, part: File, revision: String, etag: String?, lastError: String? = null) {
    val length = withContext(io) { if (part.isFile) part.length() else 0L }
    db.withTransaction {
      val current = artifacts.get(want.jobId, want.name) ?: return@withTransaction
      val keepsCopy = current.localState == LocalStates.READY && current.localFile != null
      artifacts.update(
        current.copy(
          localState = if (keepsCopy) LocalStates.READY else LocalStates.QUEUED,
          partBytes = length,
          partRevision = revision,
          etag = etag,
          lastError = lastError ?: current.lastError,
          updatedAt = now(),
        ),
      )
    }
  }

  private suspend fun pause(wants: List<Want>, reason: String) {
    db.withTransaction {
      for (want in wants) {
        val current = artifacts.get(want.jobId, want.name) ?: continue
        val keepsCopy = current.localState == LocalStates.READY
        if (current.pauseReason == reason && (keepsCopy || current.localState == LocalStates.PAUSED)) continue
        artifacts.update(
          current.copy(
            localState = if (keepsCopy) LocalStates.READY else LocalStates.PAUSED,
            pauseReason = reason,
            updatedAt = now(),
          ),
        )
      }
    }
  }

  /**
   * Wanted files that wait become `QUEUED` (a paused one keeps its reason until a run admits it); files no longer
   * wanted go back to `ABSENT` (their part stays until a revision changes or the start-up sweep); videos this phone
   * cannot play are marked so.
   */
  private suspend fun applyQueue(plan: DownloadPlan, rows: List<ArtifactEntity>) {
    val wanted = plan.wanted.mapTo(HashSet()) { key(it) }
    val changes = ArrayList<ArtifactEntity>()
    for (row in rows) {
      val key = row.jobId + KEY_SEPARATOR + row.name
      val updated = when {
        key in wanted && (row.localState == LocalStates.ABSENT || row.localState == LocalStates.FAILED) ->
          row.copy(localState = LocalStates.QUEUED)
        key !in wanted && (row.localState == LocalStates.QUEUED || row.localState == LocalStates.PAUSED) ->
          row.copy(localState = LocalStates.ABSENT, pauseReason = row.pauseReason.takeIf { it == Pauses.REMOVED_BY_LEARNER })
        row.name == ArtifactNames.ORIGINAL && row.jobId in plan.cannotPlay && row.lastError != ArtifactErrors.CANNOT_PLAY ->
          row.copy(lastError = ArtifactErrors.CANNOT_PLAY)
        row.name == ArtifactNames.ORIGINAL && row.jobId !in plan.cannotPlay && row.lastError == ArtifactErrors.CANNOT_PLAY ->
          row.copy(lastError = null)
        else -> null
      }
      if (updated != null) changes += updated.copy(updatedAt = now())
    }
    if (changes.isEmpty()) return
    db.withTransaction { changes.forEach { artifacts.update(it) } }
  }

  private suspend fun planContext(): PlanContext? {
    val paired = runCatching { pairing.current() }.getOrNull() ?: return null
    val current = settings.settings.value
    return PlanContext(
      serverId = paired.serverId.value,
      deviceId = paired.deviceId.value,
      track = current.track,
      downloadVideos = current.downloadVideos,
      canPlay = { codec, width, height -> runCatching { decoders.canPlay(codec, width, height) }.getOrDefault(false) },
      lastWatched = protection.lastWatched().keysToString(),
    )
  }

  private suspend fun checkStatus() {
    try {
      status.checkNow()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failed: RuntimeException) {
      log.w("sync.check_failed", failed)
    }
  }

  private suspend fun partLength(want: Want): Long {
    val sha = want.artifact.remoteSha256 ?: return 0
    val part = files.part(want.jobId, want.name, sha.take(REVISION_DIGITS)) ?: return 0
    return withContext(io) { if (part.isFile) part.length().coerceAtMost(want.bytes) else 0L }
  }

  private fun now(): Long = clock.now().toEpochMilli()

  private fun key(want: Want): String = want.jobId + KEY_SEPARATOR + want.name

  private fun errorCode(error: AppError.Computer): String = when (error) {
    is AppError.Computer.Refused -> "refused"
    is AppError.Computer.Unreachable -> "unreachable"
    is AppError.Computer.ClockSkew -> "clock_skew"
    else -> error.javaClass.simpleName.lowercase()
  }

  private fun <V> Map<JobId, V>.keysToString(): Map<String, V> = mapKeys { it.key.value }

  companion object {
    /** Server-contract 6.4.2: an artifact's revision is the first 16 hex digits of its SHA-256. */
    const val REVISION_DIGITS = 16

    /** The row's `part_bytes` is written at most this often (architecture 7.4). */
    const val PERSIST_EVERY_BYTES: Long = 512L * 1024L

    /** A `200` where `206` was expected restarts from zero this many times in one run at most. */
    private const val MAX_RESTARTS = 1

    private const val KEY_SEPARATOR = "/"
  }
}
