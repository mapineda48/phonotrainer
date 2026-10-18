package com.mapineda48.phonotrainer.core.sync.catalogue

import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifact
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// The one mapping from what the computer reports (work-plan 4.2, server-contract 6.3) to the `clip` and `artifact`
// rows (architecture 4.2). What the computer reports is overwritten; what the learner chose about this phone's copy
// (kept, wanted, hidden, removed, the video) and the local state of each file are kept.

private const val MILLIS_PER_SECOND = 1_000.0

/**
 * [this] job as its `clip` row: a new row when [existing] is null (first seen at [now]), otherwise [existing] with the
 * computer's fields replaced and the learner's kept. A listing that names the job resets its missing count. [lean]
 * (a channel event) leaves the revision as it was: the revision names the artifacts the last listing reported, and a
 * different one in an event asks for a pass (architecture 7.6).
 */
internal fun RemoteJob.toClipRow(
  existing: ClipEntity?,
  serverId: String,
  libraryId: String,
  now: Long,
  lean: Boolean = false,
): ClipEntity {
  val base = existing ?: ClipEntity(
    jobId = id.value,
    serverId = serverId,
    libraryId = libraryId,
    origin = Origins.ACTIVE,
    revision = revision,
    status = status.name,
    title = "",
    firstSeenAt = now,
  )
  return base.copy(
    serverId = serverId,
    libraryId = libraryId,
    origin = Origins.ACTIVE,
    revision = if (lean && existing != null) existing.revision else revision,
    status = status.name,
    stage = stage?.name,
    percent = percent,
    queuePosition = queuePosition,
    title = title ?: existing?.title?.takeIf { it.isNotEmpty() } ?: source.fileName.orEmpty(),
    sourceKind = source.kind.name,
    sourceUrl = source.url,
    site = source.site?.name,
    submittedByDevice = if (own) deviceId?.value ?: existing?.submittedByDevice else null,
    createdAt = createdAt.toEpochMilli(),
    startedAt = startedAt?.toEpochMilli(),
    finishedAt = finishedAt?.toEpochMilli(),
    errorCode = error?.code,
    errorMessage = error?.message,
    durationMs = (summary?.durationS ?: media?.durationS)?.let { (it * MILLIS_PER_SECOND).toLong() }
      ?: existing?.durationMs,
    segmentCount = summary?.segments ?: existing?.segmentCount,
    wordCount = summary?.words ?: existing?.wordCount,
    changeCountsJson = summary?.changeCounts?.let(::countsJson) ?: existing?.changeCountsJson,
    rulesVersion = analysis?.rulesVersion ?: existing?.rulesVersion,
    phoneEngine = analysis?.phoneEngine ?: existing?.phoneEngine,
    hasDialogue = analysis?.dialogueSeparation ?: existing?.hasDialogue ?: false,
    audioOnlyJob = audioOnly,
    mediaKind = media?.kind?.name ?: existing?.mediaKind,
    videoCodec = media?.codec ?: existing?.videoCodec,
    videoWidth = media?.width ?: existing?.videoWidth,
    videoHeight = media?.height ?: existing?.videoHeight,
    videoRotation = media?.rotation ?: existing?.videoRotation,
    videoOffsetMs = media?.startOffsetMs ?: existing?.videoOffsetMs,
    missingListings = if (lean) existing?.missingListings ?: 0 else 0,
  )
}

private fun countsJson(counts: Map<String, Int>): String =
  buildJsonObject { counts.toSortedMap().forEach { (label, count) -> put(label, JsonPrimitive(count)) } }.toString()

/** Whether this build stores [this] artifact: a name of server-contract 6.4.1 and a known role. */
internal val RemoteArtifact.storable: Boolean
  get() = name in ArtifactNames.EXTENSIONS && role != ArtifactRole.UNKNOWN

/**
 * [this] listed artifact as its row: [existing] with the computer's fields replaced, its local copy kept. A file that
 * failed verification twice is tried again when the computer reports another revision of it.
 */
internal fun RemoteArtifact.toArtifactRow(jobId: String, existing: ArtifactEntity?, now: Long): ArtifactEntity {
  val base = existing ?: ArtifactEntity(
    jobId = jobId,
    name = name,
    role = role.name,
    remoteStatus = status.name,
    updatedAt = now,
  )
  val newRevision = existing != null && existing.remoteSha256 != null && sha256 != null &&
    !existing.remoteSha256.equals(sha256, ignoreCase = true)
  val failedBefore = base.localState == LocalStates.FAILED
  return base.copy(
    role = role.name,
    remoteStatus = status.name,
    remoteBytes = bytes,
    remoteSha256 = sha256?.lowercase(),
    remoteType = contentType,
    remoteUrl = url,
    localState = if (failedBefore && newRevision) LocalStates.ABSENT else base.localState,
    verifyFailures = if (newRevision) 0 else base.verifyFailures,
    lastError = if (newRevision && base.lastError == ArtifactErrors.DAMAGED) null else base.lastError,
    updatedAt = now,
  )
}

/** A row the listing no longer names: its verified copy stays in use, but nothing more comes from the computer. */
internal fun ArtifactEntity.unlisted(now: Long): ArtifactEntity = copy(
  remoteStatus = RemoteStates.UNAVAILABLE,
  remoteBytes = null,
  remoteSha256 = null,
  remoteUrl = null,
  localState = if (localState == LocalStates.READY) LocalStates.READY else LocalStates.ABSENT,
  pauseReason = null,
  updatedAt = now,
)
