package com.mapineda48.phonotrainer.core.testing.fake.sync

import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.MassDeletionPrompt
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

// Settable fakes of the :core:sync interfaces (work-plan 4.6; WP-08 owns this folder after B1).

class FakeSyncController : SyncController {
  val stateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
  val reasons = mutableListOf<SyncReason>()

  override val state: StateFlow<SyncState> = stateFlow

  override fun syncNow(reason: SyncReason) {
    reasons += reason
  }
}

/** Clips in memory; [clips] applies the search text and the KEPT filter only (enough for screen tests). */
class FakeClipRepository : ClipRepository {
  val all = MutableStateFlow(emptyList<ClipSummary>())
  val details = MutableStateFlow(emptyMap<JobId, ClipDetail>())
  val browser = MutableStateFlow(emptyList<ComputerJob>())
  val newOnPhone = MutableStateFlow(emptyList<ClipSummary>())
  val massDeletion = MutableStateFlow<MassDeletionPrompt?>(null)
  val actions = mutableListOf<String>()

  override fun clips(query: ClipQuery): Flow<List<ClipSummary>> = all.map { clips ->
    clips.filter { query.search.isBlank() || it.title.contains(query.search, ignoreCase = true) }
      .filter { query.filter != ClipFilter.KEPT || it.kept }
  }

  override fun clip(id: JobId): Flow<ClipDetail?> = details.map { it[id] }

  override fun browserJobs(): Flow<List<ComputerJob>> = browser

  override fun newOnThisPhone(): Flow<List<ClipSummary>> = newOnPhone

  override val pendingMassDeletion: Flow<MassDeletionPrompt?> = massDeletion

  override suspend fun resolveMassDeletion(remove: Boolean) {
    actions += "resolveMassDeletion:$remove"
    massDeletion.value = null
  }

  override suspend fun setKept(id: JobId, kept: Boolean) {
    actions += "setKept:${id.value}:$kept"
  }

  override suspend fun downloadToPhone(id: JobId) {
    actions += "downloadToPhone:${id.value}"
  }

  override suspend fun removeFromPhone(id: JobId) {
    actions += "removeFromPhone:${id.value}"
  }

  override suspend fun downloadVideo(id: JobId) {
    actions += "downloadVideo:${id.value}"
  }

  override suspend fun removeVideo(id: JobId) {
    actions += "removeVideo:${id.value}"
  }

  override suspend fun hideBrowserJob(id: JobId) {
    actions += "hideBrowserJob:${id.value}"
  }
}

class FakeStorageRepository : StorageRepository {
  val usageState = MutableStateFlow(
    StorageUsage(0, 0, 0, 0, StorageBudget.Limit(2_000_000_000), 0, 0, false, false, emptyList()),
  )
  var videosRemoved = 0
    private set

  override val usage: Flow<StorageUsage> = usageState

  override suspend fun setBudget(budget: StorageBudget) {
    usageState.value = usageState.value.copy(budget = budget)
  }

  override suspend fun removeAllVideos() {
    videosRemoved++
  }
}
