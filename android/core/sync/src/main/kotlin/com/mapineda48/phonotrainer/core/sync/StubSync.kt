package com.mapineda48.phonotrainer.core.sync

import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.MassDeletionPrompt
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// Working stubs of the contract freeze (work-plan 2.3): idle, no clip, nothing to download. WP-08 replaces this file.

@Singleton
class StubSyncController @Inject constructor() : SyncController, SyncTrigger {
  override val state: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)

  override fun syncNow(reason: SyncReason) = Unit

  override fun request(reason: SyncReason) = Unit
}

class StubClipRepository @Inject constructor() : ClipRepository {
  override fun clips(query: ClipQuery): Flow<List<ClipSummary>> = flowOf(emptyList())

  override fun clip(id: JobId): Flow<ClipDetail?> = flowOf(null)

  override fun browserJobs(): Flow<List<ComputerJob>> = flowOf(emptyList())

  override fun newOnThisPhone(): Flow<List<ClipSummary>> = flowOf(emptyList())

  override val pendingMassDeletion: Flow<MassDeletionPrompt?> = flowOf(null)

  override suspend fun resolveMassDeletion(remove: Boolean) = Unit

  override suspend fun setKept(id: JobId, kept: Boolean) = Unit

  override suspend fun downloadToPhone(id: JobId) = Unit

  override suspend fun removeFromPhone(id: JobId) = Unit

  override suspend fun downloadVideo(id: JobId) = Unit

  override suspend fun removeVideo(id: JobId) = Unit

  override suspend fun hideBrowserJob(id: JobId) = Unit
}

class StubStorageRepository @Inject constructor() : StorageRepository {
  override val usage: Flow<StorageUsage> = flowOf(
    StorageUsage(
      clipsBytes = 0,
      videosBytes = 0,
      waitingCopiesBytes = 0,
      cacheBytes = 0,
      budget = StorageBudget.Limit(2_000_000_000),
      freeBytes = 0,
      floorBytes = 0,
      budgetReached = false,
      belowFloor = false,
      perClip = emptyList(),
    ),
  )

  override suspend fun setBudget(budget: StorageBudget) = Unit

  override suspend fun removeAllVideos() = Unit
}

/** Stub: start-up has nothing to clean. */
class StubStartupGate @Inject constructor() : StartupGate {
  override suspend fun awaitDone() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
interface SyncModule {
  @Binds
  fun controller(impl: StubSyncController): SyncController

  @Binds
  fun trigger(impl: StubSyncController): SyncTrigger

  @Binds
  fun clips(impl: StubClipRepository): ClipRepository

  @Binds
  fun storage(impl: StubStorageRepository): StorageRepository

  @Binds
  fun startupGate(impl: StubStartupGate): StartupGate
}
