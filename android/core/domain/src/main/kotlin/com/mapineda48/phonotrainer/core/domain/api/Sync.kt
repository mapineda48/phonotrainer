package com.mapineda48.phonotrainer.core.domain.api

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
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// :core:sync (WP-08), architecture 3.4 and 7.

interface SyncController {
  val state: StateFlow<SyncState>

  fun syncNow(reason: SyncReason)

  /** When the last complete listing of the paired computer was reconciled: "Last synced {time}" (F-21). */
  val lastSynced: Flow<Instant?> get() = flowOf(null)
}

interface ClipRepository {
  fun clips(query: ClipQuery): Flow<List<ClipSummary>>

  fun clip(id: JobId): Flow<ClipDetail?>

  /** "Started on your computer". */
  fun browserJobs(): Flow<List<ComputerJob>>

  fun newOnThisPhone(): Flow<List<ClipSummary>>

  val pendingMassDeletion: Flow<MassDeletionPrompt?>

  suspend fun resolveMassDeletion(remove: Boolean)

  suspend fun setKept(id: JobId, kept: Boolean)

  /** "Download to this phone" / "when connected". */
  suspend fun downloadToPhone(id: JobId)

  suspend fun removeFromPhone(id: JobId)

  suspend fun downloadVideo(id: JobId)

  suspend fun removeVideo(id: JobId)

  suspend fun hideBrowserJob(id: JobId)
}

interface StorageRepository {
  /** Clips, videos, waiting copies, cache, budget, floor. */
  val usage: Flow<StorageUsage>

  suspend fun setBudget(budget: StorageBudget)

  suspend fun removeAllVideos()
}
