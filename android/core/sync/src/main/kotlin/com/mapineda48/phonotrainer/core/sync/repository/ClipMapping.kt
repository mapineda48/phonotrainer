package com.mapineda48.phonotrainer.core.sync.repository

import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.ArtifactProgress
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipStorage
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactErrors
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactNames
import com.mapineda48.phonotrainer.core.sync.catalogue.ClipStatuses
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Origins
import com.mapineda48.phonotrainer.core.sync.catalogue.Pauses
import com.mapineda48.phonotrainer.core.sync.catalogue.RemoteStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Roles
import com.mapineda48.phonotrainer.core.sync.transfer.DownloadPlanner
import com.mapineda48.phonotrainer.core.sync.transfer.LiveTransfer
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a clip's words depend on besides its rows. */
internal data class ClipContext(
  val track: Track,
  /** The paired computer is connected: a waiting download says "Bringing", otherwise "paused: not connected". */
  val connected: Boolean,
  /** The file downloading now, for its live percent. */
  val live: LiveTransfer?,
)

/**
 * The one mapping from the `clip` and `artifact` rows to the words of spec 7.11 (architecture 3.4): a clip is "On this
 * phone" only when its study pack (clip data + the audio of the learner's track) is verified here (SC-11); the video's
 * words follow its own row; each file's state shows on the Clip page ("Clip data ✓ · Audio 60 % · Video waiting").
 */
internal object ClipMapping {
  private const val PERCENT = 100
  private const val MILLIS_PER_SECOND = 1_000.0

  fun summary(
    clip: ClipEntity,
    rows: List<ArtifactEntity>,
    context: ClipContext,
    newestRules: Int?,
    shortsSeen: Int,
    shortsTotal: Int,
  ): ClipSummary = ClipSummary(
    id = JobId(clip.jobId),
    title = clip.title,
    durationS = clip.durationMs?.let { it / MILLIS_PER_SECOND },
    words = clip.wordCount,
    changeCount = changeCounts(clip.changeCountsJson)?.values?.sum(),
    source = source(clip),
    availability = availability(clip, rows, context),
    video = video(clip, rows, context.live),
    kept = clip.kept,
    origin = Origins.of(clip.origin),
    olderRules = clip.rulesVersion.let { rules -> rules != null && newestRules != null && rules < newestRules },
    shortsSeen = shortsSeen,
    shortsTotal = shortsTotal,
    addedAt = Instant.ofEpochMilli(clip.createdAt ?: clip.firstSeenAt),
  )

  fun availability(clip: ClipEntity, rows: List<ArtifactEntity>, context: ClipContext): ClipAvailability {
    if (clip.status != ClipStatuses.DONE) return ClipAvailability.GettingReadyOnComputer
    if (DownloadPlanner.packOnPhone(rows, context.track)) return ClipAvailability.OnThisPhone
    if (clip.removedByLearner || clip.origin != Origins.ACTIVE) return ClipAvailability.NotOnThisPhone
    val byName = rows.associateBy { it.name }
    val (chosen, _) = DownloadPlanner.audioNames(byName, context.track)
    val pack = listOfNotNull(byName[ArtifactNames.ANALYSIS], byName[chosen])
    val clipData = byName[ArtifactNames.ANALYSIS]
    if (clipData == null || (clipData.remoteStatus != RemoteStates.READY && clipData.localState != LocalStates.READY)) {
      return ClipAvailability.GettingReadyOnComputer
    }
    val waiting = pack.filter { it.localState != LocalStates.READY }
    val damaged = setOf(ArtifactErrors.CORRUPT, ArtifactErrors.MISSING)
    if (waiting.any { it.localState == LocalStates.FAILED || it.lastError in damaged }) {
      return ClipAvailability.NeedsDownloadingAgain
    }
    waiting.firstNotNullOfOrNull { row -> Pauses.of(row.pauseReason)?.takeIf { row.localState == LocalStates.PAUSED } }
      ?.let { return ClipAvailability.DownloadPaused(it) }
    if (!context.connected) return ClipAvailability.DownloadPaused(PauseReason.NOT_CONNECTED)
    return ClipAvailability.Bringing(percentOf(pack, context.live))
  }

  fun video(clip: ClipEntity, rows: List<ArtifactEntity>, live: LiveTransfer?): VideoAvailability {
    if (clip.audioOnlyJob || clip.mediaKind == MEDIA_AUDIO || clip.mediaKind == MEDIA_NONE) {
      return VideoAvailability.AudioOnlyClip
    }
    val videos = rows.filter { it.role == Roles.VIDEO }
    if (videos.any { it.localState == LocalStates.READY && it.localFile != null }) return VideoAvailability.OnThisPhone
    if (videos.isEmpty()) {
      return if (clip.mediaKind == MEDIA_VIDEO) VideoAvailability.NotAvailableFromComputer else VideoAvailability.AudioOnlyClip
    }
    videos.firstOrNull { it.localState == LocalStates.DOWNLOADING }?.let { return VideoAvailability.Downloading(percentOf(listOf(it), live)) }
    if (videos.any { it.lastError == ArtifactErrors.CANNOT_PLAY }) return VideoAvailability.CannotPlayOnThisPhone
    return when {
      videos.any { it.remoteStatus == RemoteStates.READY } -> VideoAvailability.NotOnThisPhone
      videos.any { it.remoteStatus == RemoteStates.PREPARING } -> VideoAvailability.GettingReadyOnComputer
      else -> VideoAvailability.NotAvailableFromComputer
    }
  }

  /** Each file of the clip with its state and percent (F-21: "Clip data ✓ · Audio 60 % · Video waiting"). */
  fun progress(rows: List<ArtifactEntity>, context: ClipContext, videosWanted: Boolean): List<ArtifactProgress> =
    rows.filter { it.role in SHOWN_ROLES && it.name in ArtifactNames.EXTENSIONS }.map { row ->
      val state = LocalStates.of(row.localState)
      val waiting = state == ArtifactLocalState.QUEUED || state == ArtifactLocalState.ABSENT
      val reason = Pauses.of(row.pauseReason) ?: when {
        row.role == Roles.VIDEO && waiting && !videosWanted -> PauseReason.NOT_WANTED
        waiting && row.remoteStatus == RemoteStates.READY && !context.connected -> PauseReason.NOT_CONNECTED
        else -> null
      }
      ArtifactProgress(
        role = ArtifactRole.entries.firstOrNull { it.name == row.role } ?: ArtifactRole.UNKNOWN,
        state = state,
        percent = when (state) {
          ArtifactLocalState.READY -> PERCENT
          ArtifactLocalState.DOWNLOADING, ArtifactLocalState.PAUSED, ArtifactLocalState.QUEUED -> percentOf(listOf(row), context.live)
          else -> null
        },
        pauseReason = reason,
        verifyFailures = row.verifyFailures,
      )
    }

  fun browserJob(clip: ClipEntity): ComputerJob = ComputerJob(
    id = JobId(clip.jobId),
    title = clip.title.takeIf { it.isNotEmpty() },
    status = ClipStatuses.of(clip.status),
    stage = clip.stage?.let { stage -> Stage.entries.firstOrNull { it.name == stage } ?: Stage.UNKNOWN },
    percent = clip.percent ?: 0,
    queuePosition = clip.queuePosition,
    source = source(clip),
    error = error(clip),
    createdAt = Instant.ofEpochMilli(clip.createdAt ?: clip.firstSeenAt),
    hidden = clip.hidden,
  )

  fun storage(clip: ClipEntity, rows: List<ArtifactEntity>): ClipStorage = ClipStorage(
    id = JobId(clip.jobId),
    title = clip.title,
    studyPackBytes = rows.filter { it.role in Roles.PACK }.sumOf { it.verifiedBytes() },
    videoBytes = rows.filter { it.role == Roles.VIDEO }.sumOf { it.verifiedBytes() },
    kept = clip.kept,
  )

  fun error(clip: ClipEntity): RequestError? = clip.errorCode?.let {
    RequestError(code = it, message = clip.errorMessage, downloaderVersion = null, atSending = false)
  }

  /** The per-change counts of the listing's summary (`phenomena_counts`), or null when the listing had none. */
  fun changeCounts(json: String?): Map<ChangeLabel, Int>? {
    if (json.isNullOrEmpty()) return null
    return try {
      Json.parseToJsonElement(json).jsonObject.mapNotNull { (label, value) ->
        value.jsonPrimitive.intOrNull?.let { ChangeLabel(label) to it }
      }.toMap()
    } catch (unreadable: IllegalArgumentException) {
      null
    }
  }

  fun source(clip: ClipEntity): ClipSource = ClipSource(
    kind = SourceKind.entries.firstOrNull { it.name == clip.sourceKind } ?: SourceKind.UNKNOWN,
    site = clip.site?.let { site -> Site.entries.firstOrNull { it.name == site } ?: Site.OTHER },
    link = clip.sourceUrl,
    fileName = null,
  )

  private fun ArtifactEntity.verifiedBytes(): Long =
    if (localState == LocalStates.READY && localFile != null) localBytes ?: 0 else 0

  private fun percentOf(rows: List<ArtifactEntity>, live: LiveTransfer?): Int {
    var done = 0L
    var total = 0L
    for (row in rows) {
      val bytes = row.remoteBytes ?: continue
      total += bytes
      done += when {
        row.localState == LocalStates.READY && row.localFile != null -> bytes
        live != null && live.jobId == row.jobId && live.name == row.name -> live.doneBytes
        else -> row.partBytes ?: 0
      }.coerceIn(0, bytes)
    }
    if (total <= 0) return 0
    return (done * PERCENT / total).toInt().coerceIn(0, PERCENT - 1)
  }

  private val SHOWN_ROLES = setOf(Roles.CLIP_DATA, Roles.AUDIO_MIX, Roles.AUDIO_DIALOGUE, Roles.VIDEO)
  private const val MEDIA_AUDIO = "AUDIO"
  private const val MEDIA_NONE = "NONE"
  private const val MEDIA_VIDEO = "VIDEO"
}
