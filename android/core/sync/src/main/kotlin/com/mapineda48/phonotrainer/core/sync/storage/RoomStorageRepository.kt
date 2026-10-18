package com.mapineda48.phonotrainer.core.sync.storage

import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Pauses
import com.mapineda48.phonotrainer.core.sync.catalogue.Roles
import com.mapineda48.phonotrainer.core.sync.repository.ClipMapping
import com.mapineda48.phonotrainer.core.sync.transfer.ClipFiles
import com.mapineda48.phonotrainer.core.sync.transfer.Downloader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * When the storage usage must be measured again although no row changed: files of the device queue came or went, the
 * start-up sweep ran (architecture 4.6).
 */
@Singleton
internal class StorageRefresh @Inject constructor() {
  val ticks = MutableStateFlow(0L)

  fun refresh() {
    ticks.value += 1
  }
}

/**
 * [StorageRepository] (architecture 7.5, spec F-21 Storage): the bytes of downloaded clips (study packs and videos)
 * from the verified rows, the device queue's waiting copies and the cache from the files, the budget from the settings,
 * the free space as `getAllocatableBytes` reports it and the floor; and the two actions, a new budget (the budget step
 * runs at once, and a raised budget lets paused downloads go) and "Remove all videos".
 */
@Singleton
class RoomStorageRepository @Inject internal constructor(
  private val db: LibraryDatabase,
  private val clips: ClipDao,
  private val artifacts: ArtifactDao,
  private val settings: SettingsRepository,
  private val space: DeviceSpace,
  private val files: ClipFiles,
  private val downloader: Downloader,
  private val trigger: SyncTrigger,
  private val refresh: StorageRefresh,
  private val clock: Clock,
  @param:IoDispatcher private val io: CoroutineDispatcher,
) : StorageRepository {
  override val usage: Flow<StorageUsage> = combine(
    artifacts.observeAll(),
    clips.observeFinished(),
    settings.settings,
    refresh.ticks,
  ) { rows, finished, current, _ ->
    val bytes = StoragePolicy.usage(rows)
    val budget = current.storageBudget
    val free = space.allocatableBytes()
    val floor = StoragePolicy.floorBytes(space.totalBytes())
    val byJob = rows.groupBy { it.jobId }
    val perClip = finished.map { ClipMapping.storage(it, byJob[it.jobId].orEmpty()) }
      .filter { it.studyPackBytes + it.videoBytes > 0 }
      .sortedByDescending { it.studyPackBytes + it.videoBytes }
    StorageUsage(
      clipsBytes = bytes.studyPacks,
      videosBytes = bytes.videos,
      waitingCopiesBytes = space.waitingCopiesBytes(),
      cacheBytes = space.cacheBytes(),
      budget = budget,
      freeBytes = free,
      floorBytes = floor,
      budgetReached = StoragePolicy.packsOverBudget(bytes, budget) ||
        rows.any { it.localState == LocalStates.PAUSED && it.pauseReason == Pauses.BUDGET_REACHED },
      belowFloor = free < floor,
      perClip = perClip,
    )
  }.flowOn(io)

  override suspend fun setBudget(budget: StorageBudget) {
    settings.update { it.copy(storageBudget = budget) }
    downloader.enforceBudget()
    trigger.request(SyncReason.SYNC_NOW)
  }

  override suspend fun removeAllVideos() {
    val removed = db.withTransaction {
      clips.clearVideoWanted()
      val gone = ArrayList<String>()
      for (row in artifacts.all()) {
        if (row.role != Roles.VIDEO) continue
        row.localFile?.let(gone::add)
        row.partRevision?.let { revision -> files.part(row.jobId, row.name, revision)?.let { gone += files.relative(it) } }
        artifacts.update(
          row.copy(
            localState = LocalStates.ABSENT,
            localFile = null,
            localSha256 = null,
            localBytes = null,
            partBytes = null,
            partRevision = null,
            etag = null,
            pauseReason = Pauses.REMOVED_BY_LEARNER,
            updatedAt = clock.now().toEpochMilli(),
          ),
        )
      }
      gone
    }
    withContext(io) { removed.forEach { files.resolve(it)?.delete() } }
    refresh.refresh()
  }
}
