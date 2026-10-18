package com.mapineda48.phonotrainer.core.sync.repository

import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.MassDeletionPrompt
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import com.mapineda48.phonotrainer.core.domain.spi.StudyProtection
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactErrors
import com.mapineda48.phonotrainer.core.sync.catalogue.CatalogueReconciler
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Pauses
import com.mapineda48.phonotrainer.core.sync.catalogue.Roles
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.transfer.ClipFiles
import com.mapineda48.phonotrainer.core.sync.transfer.TransferBoard
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * [ClipRepository] over the catalogue mirror (architecture 3.4, 7.3 to 7.7): the clips and their words from the `clip`
 * and `artifact` rows, the jobs started on the computer, the mass-deletion question, and the learner's choices about
 * this phone's copy, each one transaction followed by a pass when something must be downloaded.
 */
@Singleton
class RoomClipRepository @Inject internal constructor(
  private val db: LibraryDatabase,
  private val clips: ClipDao,
  private val artifacts: ArtifactDao,
  private val settings: SettingsRepository,
  private val status: ComputerStatusRepository,
  private val board: TransferBoard,
  private val progress: Provider<ProgressRepository>,
  private val protection: StudyProtection,
  private val meta: SyncMeta,
  private val catalogue: CatalogueReconciler,
  private val files: ClipFiles,
  private val indexer: ClipIndexer,
  private val trigger: SyncTrigger,
  private val clock: Clock,
  @param:IoDispatcher private val io: CoroutineDispatcher,
  private val log: PhonoLog,
) : ClipRepository {
  private val context: Flow<ClipContext> = combine(
    settings.settings.map { it.track }.distinctUntilChanged(),
    status.status.map { it is ComputerStatus.Connected }.distinctUntilChanged(),
    board.current,
  ) { track, connected, live -> ClipContext(track, connected, live) }

  private val seen: Flow<Map<String, Int>> = flow { emitAll(progress.get().seen()) }
    .catch { failed ->
      log.w("sync.seen_unknown", failed)
      emit(emptySet())
    }
    .onStart { emit(emptySet()) }
    .map { ids -> ids.groupingBy { it.jobId.value }.eachCount() }

  private val shortCounts: Flow<Map<String, Int>> =
    clips.observeShortCounts().map { rows -> rows.associate { it.jobId to it.shorts } }

  private val summaries: Flow<List<ClipSummary>> = combine(
    clips.observeFinished(),
    artifacts.observeAll(),
    context,
    combine(clips.observeNewestRules(), shortCounts, seen) { newest, shorts, seenBy -> Triple(newest, shorts, seenBy) },
  ) { rows, files, context, (newest, shorts, seenBy) ->
    val byJob = files.groupBy { it.jobId }
    rows.map { clip ->
      ClipMapping.summary(
        clip, byJob[clip.jobId].orEmpty(), context, newest,
        shortsSeen = seenBy[clip.jobId] ?: 0, shortsTotal = shorts[clip.jobId] ?: 0,
      )
    }
  }

  override fun clips(query: ClipQuery): Flow<List<ClipSummary>> = summaries.map { all -> arrange(all, query) }

  override fun clip(id: JobId): Flow<ClipDetail?> = combine(
    clips.observe(id.value),
    artifacts.observeForJob(id.value),
    context,
    combine(clips.observeNewestRules(), shortCounts, seen) { newest, shorts, seenBy -> Triple(newest, shorts, seenBy) },
    resume(id),
  ) { clip, rows, context, (newest, shorts, seenBy), resume ->
    clip ?: return@combine null
    val watched = runCatching { protection.lastWatched()[id] }.getOrNull()
    detail(clip, rows, context, newest, seenBy[clip.jobId] ?: 0, shorts[clip.jobId] ?: 0, resume, watched)
  }

  override fun browserJobs(): Flow<List<ComputerJob>> =
    clips.observeBrowserJobs().map { rows -> rows.map(ClipMapping::browserJob) }

  override fun newOnThisPhone(): Flow<List<ClipSummary>> = combine(clips.observeFinished(), summaries) { rows, all ->
    val since = clock.now().minus(NEW_FOR).toEpochMilli()
    val watched = runCatching { protection.lastWatched() }.getOrDefault(emptyMap()).keys.mapTo(HashSet()) { it.value }
    val arrived = rows.filter { (it.onPhoneAt ?: 0) >= since && it.jobId !in watched }
      .sortedByDescending { it.onPhoneAt }
      .mapTo(LinkedHashSet()) { it.jobId }
    val byId = all.associateBy { it.id.value }
    arrived.mapNotNull { byId[it] }.filter { it.availability == ClipAvailability.OnThisPhone }
  }

  override val pendingMassDeletion: Flow<MassDeletionPrompt?> = meta.observePendingMassDeletion().map { prompt ->
    prompt?.let { MassDeletionPrompt(count = it.count, ofTotal = it.ofTotal, serverName = it.serverName) }
  }

  override suspend fun resolveMassDeletion(remove: Boolean) = catalogue.resolveMassDeletion(remove)

  override suspend fun setKept(id: JobId, kept: Boolean) {
    clips.setKept(id.value, kept)
  }

  override suspend fun downloadToPhone(id: JobId) {
    db.withTransaction {
      if (clips.markWanted(id.value) == 0) return@withTransaction
      for (row in artifacts.forJob(id.value)) {
        if (row.role !in Roles.PACK && row.role != Roles.VIDEO) continue
        val again = row.localState == LocalStates.FAILED || row.verifyFailures > 0 ||
          row.lastError == ArtifactErrors.DAMAGED || row.lastError == ArtifactErrors.CORRUPT
        if (!again) continue
        artifacts.update(
          row.copy(
            localState = if (row.localState == LocalStates.READY) LocalStates.READY else LocalStates.ABSENT,
            verifyFailures = 0,
            lastError = null,
            updatedAt = now(),
          ),
        )
      }
    }
    trigger.request(SyncReason.SYNC_NOW)
  }

  override suspend fun removeFromPhone(id: JobId) {
    val removed = db.withTransaction {
      if (clips.markRemovedByLearner(id.value) == 0) return@withTransaction emptyList()
      val gone = ArrayList<String>()
      for (row in artifacts.forJob(id.value)) {
        if (row.role !in Roles.PACK && row.role != Roles.VIDEO && row.role != Roles.POSTER) continue
        row.localFile?.let(gone::add)
        row.partRevision?.let { revision -> files.part(row.jobId, row.name, revision)?.let { gone += files.relative(it) } }
        artifacts.update(row.cleared())
      }
      gone
    }
    withContext(io) { removed.forEach { files.resolve(it)?.delete() } }
    // The derived rows go too (the corpus defers it while a screen shows the clip); history and stats stay.
    indexer.drop(setOf(id))
  }

  override suspend fun downloadVideo(id: JobId) {
    db.withTransaction {
      if (clips.setVideoWanted(id.value, true) == 0) return@withTransaction
      for (row in artifacts.forJob(id.value)) {
        if (row.role != Roles.VIDEO) continue
        val again = row.pauseReason == Pauses.REMOVED_BY_LEARNER || row.localState == LocalStates.FAILED ||
          row.lastError == ArtifactErrors.DAMAGED
        if (again) {
          artifacts.update(
            row.copy(
              localState = if (row.localState == LocalStates.READY) LocalStates.READY else LocalStates.ABSENT,
              pauseReason = null,
              verifyFailures = 0,
              lastError = row.lastError.takeIf { it == ArtifactErrors.CANNOT_PLAY },
              updatedAt = now(),
            ),
          )
        }
      }
    }
    trigger.request(SyncReason.SYNC_NOW)
  }

  override suspend fun removeVideo(id: JobId) {
    val removed = db.withTransaction {
      clips.setVideoWanted(id.value, false)
      val gone = ArrayList<String>()
      for (row in artifacts.forJob(id.value)) {
        if (row.role != Roles.VIDEO) continue
        row.localFile?.let(gone::add)
        row.partRevision?.let { revision -> files.part(row.jobId, row.name, revision)?.let { gone += files.relative(it) } }
        artifacts.update(row.cleared().copy(pauseReason = Pauses.REMOVED_BY_LEARNER))
      }
      gone
    }
    withContext(io) { removed.forEach { files.resolve(it)?.delete() } }
  }

  override suspend fun hideBrowserJob(id: JobId) {
    clips.hide(id.value)
  }

  private fun detail(
    clip: ClipEntity,
    rows: List<ArtifactEntity>,
    context: ClipContext,
    newest: Int?,
    seen: Int,
    total: Int,
    resume: ShortId?,
    lastWatched: Instant?,
  ): ClipDetail {
    val summary = ClipMapping.summary(clip, rows, context, newest, seen, total)
    val storage = ClipMapping.storage(clip, rows)
    val videosWanted = settings.settings.value.downloadVideos || clip.videoWanted || clip.wanted
    return ClipDetail(
      summary = summary,
      durationS = summary.durationS,
      segments = clip.segmentCount,
      changeCounts = ClipMapping.changeCounts(clip.changeCountsJson).orEmpty(),
      rulesVersion = clip.rulesVersion,
      phoneEngine = clip.phoneEngine,
      dialogueSeparation = clip.hasDialogue,
      hasDialogueTrack = rows.any { it.role == Roles.AUDIO_DIALOGUE },
      studyPackBytes = storage.studyPackBytes.takeIf { it > 0 },
      videoBytes = storage.videoBytes.takeIf { it > 0 },
      lastWatched = lastWatched,
      resume = resume,
      computerError = ClipMapping.error(clip),
      artifacts = ClipMapping.progress(rows, context, videosWanted && summary.video != VideoAvailability.AudioOnlyClip),
      deletedOnComputer = clip.deletedOnComputer,
    )
  }

  private fun resume(id: JobId): Flow<ShortId?> = flow { emitAll(progress.get().resume(id)) }
    .catch { failed ->
      log.w("sync.resume_unknown", failed)
      emit(null)
    }
    .onStart { emit(null) }

  private fun ArtifactEntity.cleared(): ArtifactEntity = copy(
    localState = LocalStates.ABSENT,
    localFile = null,
    localSha256 = null,
    localBytes = null,
    partBytes = null,
    partRevision = null,
    etag = null,
    verifyFailures = 0,
    pauseReason = null,
    lastError = lastError.takeIf { it == ArtifactErrors.CANNOT_PLAY },
    updatedAt = now(),
  )

  private fun now(): Long = clock.now().toEpochMilli()

  private fun arrange(all: List<ClipSummary>, query: ClipQuery): List<ClipSummary> {
    val needle = query.search.trim().lowercase(Locale.ROOT)
    return all.filter { (needle.isEmpty() || it.title.lowercase(Locale.ROOT).contains(needle)) && passes(it, query.filter) }
      .sortedWith(comparator(query.sort))
  }

  private fun passes(clip: ClipSummary, filter: ClipFilter): Boolean = when (filter) {
    ClipFilter.ALL -> true
    ClipFilter.ON_THIS_PHONE -> clip.availability == ClipAvailability.OnThisPhone
    ClipFilter.NOT_ON_THIS_PHONE -> clip.availability != ClipAvailability.OnThisPhone
    ClipFilter.IN_PROGRESS ->
      clip.availability is ClipAvailability.Bringing || clip.availability == ClipAvailability.GettingReadyOnComputer
    ClipFilter.KEPT -> clip.kept
    ClipFilter.VIDEO -> clip.video != VideoAvailability.AudioOnlyClip
    ClipFilter.AUDIO_ONLY -> clip.video == VideoAvailability.AudioOnlyClip
  }

  private fun comparator(sort: ClipSort): Comparator<ClipSummary> {
    val byId = compareBy<ClipSummary> { it.id.value }
    return when (sort) {
      ClipSort.NEWEST -> compareByDescending<ClipSummary> { it.addedAt }.then(byId.reversed())
      ClipSort.OLDEST -> compareBy<ClipSummary> { it.addedAt }.then(byId)
      ClipSort.TITLE -> compareBy<ClipSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenByDescending { it.addedAt }.then(byId.reversed())
      ClipSort.LONGEST -> compareByDescending<ClipSummary> { it.durationS ?: -1.0 }.then(byId.reversed())
    }
  }

  private companion object {
    /** A clip that arrived within this long and was not watched yet is "New on this phone" (spec F-03 item 6). */
    val NEW_FOR: Duration = Duration.ofHours(72)
  }
}
