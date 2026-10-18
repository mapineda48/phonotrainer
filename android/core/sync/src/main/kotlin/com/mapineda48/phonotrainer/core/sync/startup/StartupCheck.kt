package com.mapineda48.phonotrainer.core.sync.startup

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.common.di.ProcessStart
import com.mapineda48.phonotrainer.core.common.files.FileLayout
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactErrors
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.storage.StorageRefresh
import com.mapineda48.phonotrainer.core.sync.transfer.ClipFiles
import com.mapineda48.phonotrainer.core.sync.transfer.DownloadPlanner
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's own directories: `filesDir` and `cacheDir` (architecture 4.6). */
internal data class SyncDirs(val filesDir: File, val cacheDir: File)

/**
 * The start-up check of architecture 4.6, once per process: it acts **only on rows and files last updated before this
 * process started** ([ProcessStart]), so a share that cold-starts the app, or a download started in this process, is
 * never mistaken for an interrupted one. Its own sweep first (downloads a dead process left `DOWNLOADING` go back to
 * the queue with their part; orphaned parts, files no row names and folders of clips that are gone are deleted; the
 * reference body's interrupted `.reference.json.part`; stale recordings), then every [StartupTask] in the order of its
 * [StartupOrder] (the device queue's copy checks among them), then the storage usage is measured again. File intake,
 * transfers and the first sync pass wait for it through [StartupGate.awaitDone].
 */
@Singleton
class StartupCheck @Inject internal constructor(
  @param:ProcessStart private val processStart: Instant,
  private val tasks: Provider<Set<@JvmSuppressWildcards StartupTask>>,
  private val db: LibraryDatabase,
  private val clips: ClipDao,
  private val artifacts: ArtifactDao,
  private val files: ClipFiles,
  private val dirs: SyncDirs,
  private val settings: SettingsRepository,
  private val refresh: StorageRefresh,
  @param:ApplicationScope private val scope: CoroutineScope,
  @param:IoDispatcher private val io: CoroutineDispatcher,
  private val log: PhonoLog,
) : StartupGate {
  private val started = AtomicBoolean(false)
  private val done = CompletableDeferred<Unit>()

  /** Starts the check once, in the application scope (no caller can cancel it half-way). */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    scope.launch {
      try {
        run()
      } finally {
        done.complete(Unit)
      }
    }
  }

  override suspend fun awaitDone() {
    start()
    done.await()
  }

  /**
   * The check itself; [awaitDone] runs it once per process. The learner's settings are read first (the store answers
   * in every case), so the repair below, and every download run that waits for this check, judge the learner's track
   * and video choice rather than the defaults of a process that has not read the store yet.
   */
  internal suspend fun run() {
    settings.awaitLoaded()
    val before = processStart.toEpochMilli()
    guarded("sync.startup_sweep_failed") { sweep(before) }
    for (task in ordered(tasks.get())) {
      guarded("sync.startup_task_failed") { task.run(processStart) }
    }
    refresh.refresh()
  }

  private suspend fun sweep(before: Long) {
    val requeued = artifacts.requeueInterrupted(before)
    if (requeued > 0) log.d { "$requeued interrupted downloads go back to the queue" }
    repairMissing()
    val known = clips.all().mapTo(HashSet()) { it.jobId }
    val rows = artifacts.files()
    val inUse = rows.mapNotNullTo(HashSet()) { it.localFile }
    val parts = rows.filter { it.partRevision != null }
      .mapNotNullTo(HashSet()) { row -> files.part(row.jobId, row.name, row.partRevision!!)?.let(files::relative) }
    withContext(io) {
      val dirsOfClips = files.root.listFiles().orEmpty()
      for (dir in dirsOfClips) {
        if (!dir.isDirectory || dir.name !in known) {
          if (dir.lastModified() < before && !dir.deleteRecursively()) log.w("sync.orphan_not_deleted")
          continue
        }
        for (file in dir.listFiles().orEmpty()) {
          if (file.lastModified() >= before) continue
          val relative = files.relative(file)
          if (relative !in inUse && relative !in parts && !file.deleteRecursively()) log.w("sync.orphan_not_deleted")
        }
      }
      deleteOlder(File(File(dirs.filesDir, FileLayout.REFERENCE), REFERENCE_PART), before)
      FileLayout.takesDir(dirs.cacheDir).listFiles().orEmpty().forEach { deleteOlder(it, before) }
    }
  }

  /**
   * A verified file that is no longer on this phone (deleted outside the app, a restored backup without media) is
   * downloaded again: its row goes back to `ABSENT`. The clip stays "On this phone" while its study pack for the
   * learner's track is still complete (a lost video or second track is only downloaded again, SC-11); otherwise it
   * reads "Needs downloading again" until the pack is here.
   */
  private suspend fun repairMissing() {
    val missing = artifacts.all().filter { row ->
      row.localState == LocalStates.READY && withContext(io) { files.resolve(row.localFile)?.isFile != true }
    }
    if (missing.isEmpty()) return
    log.w("sync.verified_file_missing")
    val track = settings.settings.value.track
    db.withTransaction {
      for (row in missing) {
        artifacts.update(
          row.copy(
            localState = LocalStates.ABSENT, localFile = null, localSha256 = null, localBytes = null,
            lastError = ArtifactErrors.MISSING,
          ),
        )
      }
      for (jobId in missing.mapTo(LinkedHashSet()) { it.jobId }) {
        if (!DownloadPlanner.packOnPhone(artifacts.forJob(jobId), track)) clips.clearOnPhone(jobId)
      }
    }
  }

  private fun deleteOlder(file: File, before: Long) {
    if (file.exists() && file.lastModified() < before && !file.deleteRecursively()) log.w("sync.stale_file_not_deleted")
  }

  private suspend fun guarded(event: String, block: suspend () -> Unit) {
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failed: RuntimeException) {
      log.w(event, failed)
    } catch (failed: IOException) {
      log.w(event, failed)
    }
  }

  companion object {
    /** The interrupted write of `:core:content`'s `ReferenceStore` (wave-1 review G-3). */
    const val REFERENCE_PART = ".reference.json.part"

    /** A task whose class lost its [StartupOrder] (R8 keeps it, see `consumer-rules.pro`) runs last. */
    const val UNORDERED = Int.MAX_VALUE

    /** [tasks] in the order of their [StartupOrder], lowest first; ties keep their class name order. */
    fun ordered(tasks: Set<StartupTask>): List<StartupTask> = tasks.sortedWith(
      compareBy<StartupTask> { it.javaClass.getAnnotation(StartupOrder::class.java)?.value ?: UNORDERED }
        .thenBy { it.javaClass.name },
    )
  }
}
