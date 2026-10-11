package com.mapineda48.phonotrainer.core.network.dto

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.MediaKind
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteAnalysisInfo
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifact
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteError
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteMedia
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteSource
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteSummary
import com.mapineda48.phonotrainer.core.domain.model.remote.SeparationNote
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.domain.model.remote.VideoFacts
import com.mapineda48.phonotrainer.core.network.api.UploadState
import com.mapineda48.phonotrainer.core.network.http.MalformedAnswerException
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

// Validation before mapping (architecture 5.4, security review M-3): a value the computer reports reaches the domain
// only in the shape server-contract 6 documents. Anything else rejects the whole answer as malformed, never a part of
// it, so a hostile or broken listing cannot name a file outside the clip's folder, a foreign URL or an unknown file.
// Enum-valued fields are the exception: an unknown value is UNKNOWN (or, for a job code, processing_failed), so a
// future server never fails a listing (NFR-08, delivery review D-29).

/** Thrown by the mappers; the client turns it into `AppError.Computer.Malformed`. */
internal fun malformed(what: String): Nothing = throw MalformedAnswerException("malformed answer: $what")

/** The patterns and closed tables of server-contract 6.1, 6.3 and 6.4.1. */
internal object Wire {
  val JOB_ID = Regex("^\\d{8}-\\d{6}-[0-9a-f]{4}$")
  val REVISION = Regex("^[0-9a-f]{16}$")
  val SHA256 = Regex("^[0-9a-f]{64}$")

  /** `srv_…`, `dev_…`, `lib_…`: the contract's prefix + 26 base32 characters, read loosely (letters, digits, `_-`). */
  val OPAQUE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
  val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

  /** The only artifact names (server-contract 6.4.1) and the role each one has. */
  val ARTIFACT_ROLES: Map<String, ArtifactRole> = mapOf(
    "analysis" to ArtifactRole.CLIP_DATA,
    "audio_mix" to ArtifactRole.AUDIO_MIX,
    "audio_dialogue" to ArtifactRole.AUDIO_DIALOGUE,
    "original" to ArtifactRole.VIDEO,
    "analysis_compact" to ArtifactRole.CLIP_DATA,
    "audio_mix_opus" to ArtifactRole.AUDIO_MIX,
    "audio_dialogue_opus" to ArtifactRole.AUDIO_DIALOGUE,
    "poster" to ArtifactRole.POSTER,
    "video_phone" to ArtifactRole.VIDEO,
  )

  /** The job failure codes of server-contract 8.2; any other is `processing_failed` (NFR-08). */
  val JOB_CODES = setOf(
    "cancelled", "interrupted", "refused_address", "live_stream", "too_long", "playlist", "download_forbidden",
    "download_unavailable", "unsupported_site", "network", "no_space", "tool_missing", "not_media", "extract_failed",
    "processing_failed",
  )
  const val FALLBACK_JOB_CODE = "processing_failed"

  private const val MIB = 1024L * 1024L
  private const val GIB = 1024L * MIB

  /** The largest artifact of each role the phone downloads (architecture 5.4); a larger one is unavailable. */
  fun sizeCap(role: ArtifactRole): Long = when (role) {
    ArtifactRole.CLIP_DATA, ArtifactRole.POSTER, ArtifactRole.UNKNOWN -> 32 * MIB
    ArtifactRole.AUDIO_MIX, ArtifactRole.AUDIO_DIALOGUE -> 1 * GIB
    ArtifactRole.VIDEO -> 4 * GIB
  }

  fun artifactPath(jobId: String, name: String): String = "/api/phone/jobs/$jobId/artifacts/$name"

  val ROTATIONS = setOf(0, 90, 180, 270)
}

// --- identity, pairing, health ------------------------------------------------------------------------------------

internal fun HelloDto.toIdentity(proofValid: Boolean?): ComputerIdentity {
  if (service != SERVICE) malformed("hello.service")
  return ComputerIdentity(
    phoneAccess = phoneAccess ?: malformed("hello.phone_access"),
    serverId = serverId?.let(::serverIdOf),
    name = name,
    apiVersion = apiVersion ?: malformed("hello.api_version"),
    serverTime = serverTime ?: malformed("hello.server_time"),
    proofValid = proofValid,
    revoked = revoked,
  )
}

internal const val SERVICE = "phonotrainer"

internal fun PairGrantDto.toGrant(): PairGrant {
  val secret = token ?: malformed("pair.token")
  if (secret.isEmpty() || secret.length > MAX_TOKEN_CHARS || secret.any { it <= ' ' || it.code > ASCII_MAX }) {
    malformed("pair.token")
  }
  return PairGrant(
    deviceId = deviceIdOf(deviceId ?: malformed("pair.device_id")),
    token = secret.toCharArray(),
    serverId = serverIdOf(serverId ?: malformed("pair.server_id")),
    name = name ?: malformed("pair.name"),
    libraryId = libraryIdOf(libraryId ?: malformed("pair.library_id")),
    apiVersion = apiVersion ?: malformed("pair.api_version"),
    minAppVersion = minAppVersion ?: malformed("pair.min_app_version"),
    serverTime = serverTime ?: malformed("pair.server_time"),
  )
}

private const val MAX_TOKEN_CHARS = 256
private const val ASCII_MAX = 126

internal fun HealthDto.toHealth(): ComputerHealth {
  val caps = capabilities ?: malformed("health.capabilities")
  val queue = queue ?: malformed("health.queue")
  return ComputerHealth(
    serverId = serverIdOf(serverId ?: malformed("health.server_id")),
    name = name ?: malformed("health.name"),
    libraryId = libraryIdOf(libraryId ?: malformed("health.library_id")),
    apiVersion = apiVersion ?: malformed("health.api_version"),
    minAppVersion = minAppVersion ?: malformed("health.min_app_version"),
    version = version ?: malformed("health.version"),
    serverTime = serverTime ?: malformed("health.server_time"),
    deviceId = deviceIdOf(device?.deviceId ?: malformed("health.device")),
    analyzing = (queue.analyzing ?: malformed("health.queue.analyzing")).coerceAtLeast(0),
    inLine = (queue.inLine ?: malformed("health.queue.in_line")).coerceAtLeast(0),
    lowOnSpace = lowOnSpace ?: false,
    capabilities = Capabilities(
      linkDownloads = caps.linkDownloads ?: false,
      downloaderVersion = caps.downloader?.version,
      dialogueSeparation = caps.dialogueSeparation ?: false,
      renditions = caps.renditions ?: false,
      phoneVideo = caps.phoneVideo ?: false,
      resumableUploads = caps.resumableUploads ?: false,
      recordCompare = caps.recordCompare ?: false,
      maxUploadBytes = nonNegative(caps.maxUploadBytes, "capabilities.max_upload_bytes"),
      maxChunkBytes = nonNegative(caps.maxChunkBytes, "capabilities.max_chunk_bytes"),
      maxLinkDurationS = nonNegative(caps.maxLinkDurationS, "capabilities.max_link_duration_s"),
      freeBytes = nonNegative(caps.freeBytes, "capabilities.free_bytes"),
    ),
  )
}

// --- listing and jobs -----------------------------------------------------------------------------------------------

internal fun LibraryDto.toLibrary(): RemoteLibrary {
  val mapped = (jobs ?: malformed("library.jobs")).map { it.toJob(lean = false) }
  if (mapped.map { it.id }.toSet().size != mapped.size) malformed("library: a job id twice")
  return RemoteLibrary(
    apiVersion = apiVersion ?: malformed("library.api_version"),
    serverId = serverIdOf(serverId ?: malformed("library.server_id")),
    libraryId = libraryIdOf(libraryId ?: malformed("library.library_id")),
    generatedAt = instantOf(generatedAt ?: malformed("library.generated_at"), "library.generated_at"),
    jobs = mapped,
  )
}

/** One PhoneJob; [lean] (channel events) has no artifact list (server-contract 7). */
internal fun JobDto.toJob(lean: Boolean): RemoteJob {
  val jobId = jobIdOf(id ?: malformed("job.id"))
  val rev = revision?.takeIf(Wire.REVISION::matches) ?: malformed("job.revision")
  val source = source
  val options = options
  return RemoteJob(
    id = jobId,
    revision = rev,
    status = enumOf(status, RemoteStatus.UNKNOWN),
    stage = stage?.let { enumOf(it, Stage.UNKNOWN) },
    percent = (progress?.percent ?: 0).coerceIn(0, MAX_PERCENT),
    segment = progress?.segment?.takeIf { it >= 0 },
    segments = progress?.segments?.takeIf { it >= 0 },
    downloadPercent = progress?.downloadPercent?.coerceIn(0, MAX_PERCENT),
    separation = separation?.let { enumOf(it, SeparationNote.UNKNOWN) },
    queuePosition = queuePosition?.takeIf { it >= 1 },
    title = title,
    source = RemoteSource(
      kind = enumOf(source?.kind, SourceKind.UNKNOWN),
      url = source?.url,
      site = source?.site?.let(::siteOf),
      fileName = source?.fileName,
    ),
    audioOnly = options?.audioOnly ?: false,
    separateDialogue = options?.separateDialogue ?: true,
    deviceId = deviceId?.let(::deviceIdOf),
    own = own ?: false,
    clientRequestId = clientRequestId?.let(::requestIdOf),
    rerunOf = rerunOf?.let(::jobIdOf),
    createdAt = instantOf(created ?: malformed("job.created"), "job.created"),
    startedAt = started?.let { instantOf(it, "job.started") },
    finishedAt = finished?.let { instantOf(it, "job.finished") },
    error = error?.let {
      RemoteError(
        code = it.code?.takeIf(Wire.JOB_CODES::contains) ?: Wire.FALLBACK_JOB_CODE,
        message = it.message.orEmpty(),
        downloaderVersion = it.downloaderVersion,
      )
    },
    summary = summary?.let {
      RemoteSummary(
        durationS = it.durationS?.takeIf { duration -> duration >= 0 },
        segments = (it.segments ?: 0).coerceAtLeast(0),
        words = (it.words ?: 0).coerceAtLeast(0),
        changeCounts = it.phenomenaCounts.orEmpty().filterValues { count -> count >= 0 },
      )
    },
    analysis = analysis?.let {
      RemoteAnalysisInfo(it.rulesVersion, it.phoneEngine, it.dialogueSeparation ?: false)
    },
    media = media?.let {
      RemoteMedia(
        kind = enumOf(it.kind, MediaKind.UNKNOWN),
        codec = it.codec,
        width = it.width?.takeIf { size -> size > 0 },
        height = it.height?.takeIf { size -> size > 0 },
        rotation = it.rotation?.takeIf(Wire.ROTATIONS::contains) ?: 0,
        startOffsetMs = it.startOffsetMs ?: 0L,
        durationS = it.durationS?.takeIf { duration -> duration >= 0 },
      )
    },
    artifacts = if (lean) null else artifactsOf(jobId.value, artifacts),
  )
}

private fun artifactsOf(jobId: String, entries: List<ArtifactDto>?): List<RemoteArtifact> {
  val mapped = entries.orEmpty().map { it.toArtifact(jobId) }
  if (mapped.map { it.name }.toSet().size != mapped.size) malformed("artifacts: a name twice")
  return mapped
}

private fun ArtifactDto.toArtifact(jobId: String): RemoteArtifact {
  val artifactName = name ?: malformed("artifact.name")
  val tableRole = Wire.ARTIFACT_ROLES[artifactName] ?: malformed("artifact.name")
  val declaredRole = enumOf(role, ArtifactRole.UNKNOWN)
  if (declaredRole != ArtifactRole.UNKNOWN && declaredRole != tableRole) malformed("artifact.role")
  sha256?.let { if (!Wire.SHA256.matches(it)) malformed("artifact.sha256") }
  revision?.let { if (!Wire.REVISION.matches(it)) malformed("artifact.revision") }
  url?.let { if (it != Wire.artifactPath(jobId, artifactName)) malformed("artifact.url") }
  bytes?.let { if (it < 0) malformed("artifact.bytes") }
  var state = enumOf(status, RemoteArtifactStatus.UNKNOWN)
  if (state == RemoteArtifactStatus.READY) {
    if (bytes == null || sha256 == null || revision == null || url == null) malformed("artifact: ready without its file")
    // A file larger than its role allows is never downloaded (architecture 5.4): it reads as unavailable.
    if (bytes > Wire.sizeCap(tableRole)) state = RemoteArtifactStatus.UNAVAILABLE
  }
  return RemoteArtifact(
    name = artifactName,
    role = tableRole,
    status = state,
    bytes = bytes,
    sha256 = sha256,
    revision = revision,
    contentType = contentType,
    url = url,
    error = error,
    video = video?.let { facts ->
      val codec = facts.codec
      val width = facts.width
      val height = facts.height
      if (codec != null && width != null && height != null && width > 0 && height > 0) {
        VideoFacts(codec, width, height)
      } else {
        null
      }
    },
  )
}

internal fun JobEnvelopeDto.toJob(): RemoteJob = (job ?: malformed("job")).toJob(lean = false)

internal fun UploadStateDto.toUploadState(): UploadState = when (state) {
  "open" -> {
    val session = upload ?: malformed("upload")
    UploadState.Open(
      clientRequestId = requestIdOf(session.clientRequestId ?: malformed("upload.client_request_id")),
      offset = nonNegative(session.offset, "upload.offset"),
      bytes = nonNegative(session.bytes, "upload.bytes"),
      expiresAt = instantOf(session.expiresAt ?: malformed("upload.expires_at"), "upload.expires_at"),
      maxChunkBytes = nonNegative(session.maxChunkBytes, "upload.max_chunk_bytes"),
      resumable = session.resumable ?: false,
    )
  }
  "done" -> UploadState.Done((job ?: malformed("upload.job")).toJob(lean = false))
  else -> malformed("upload.state")
}

/** One frame of the job channel; null for a frame type this app does not know (a newer server, NFR-08). */
internal fun ChannelFrameDto.toEvent(): ChannelEvent? = when (type) {
  "hello" -> ChannelEvent.Hello(
    serverIdOf(serverId ?: malformed("hello.server_id")),
    libraryIdOf(libraryId ?: malformed("hello.library_id")),
    apiVersion ?: malformed("hello.api_version"),
  )
  "snapshot" -> {
    val mapped = (jobs ?: malformed("snapshot.jobs")).map { it.toJob(lean = true) }
    if (mapped.map { it.id }.toSet().size != mapped.size) malformed("snapshot: a job id twice")
    ChannelEvent.Snapshot(mapped)
  }
  "job" -> ChannelEvent.Job((job ?: malformed("job")).toJob(lean = true))
  "deleted" -> ChannelEvent.Deleted(jobIdOf(id ?: malformed("deleted.id")))
  else -> null
}

// --- values ---------------------------------------------------------------------------------------------------------

internal fun jobIdOf(text: String): JobId = if (Wire.JOB_ID.matches(text)) JobId(text) else malformed("job id")

internal fun requestIdOf(text: String): RequestId =
  if (Wire.UUID.matches(text)) RequestId(text.lowercase(Locale.ROOT)) else malformed("client_request_id")

private fun serverIdOf(text: String): ServerId = ServerId(opaque(text, "server_id"))

private fun deviceIdOf(text: String): DeviceId = DeviceId(opaque(text, "device_id"))

private fun libraryIdOf(text: String): LibraryId = LibraryId(opaque(text, "library_id"))

private fun opaque(text: String, what: String): String = if (Wire.OPAQUE_ID.matches(text)) text else malformed(what)

private fun nonNegative(value: Long?, what: String): Long = value?.takeIf { it >= 0 } ?: malformed(what)

private fun instantOf(text: String, what: String): Instant = try {
  OffsetDateTime.parse(text).toInstant()
} catch (_: DateTimeException) {
  malformed(what)
}

/** `source.site` (server-contract 6.3); a value this app does not know is `OTHER` (`Site` has no `UNKNOWN`). */
private fun siteOf(text: String): Site = when (text) {
  "youtube" -> Site.YOUTUBE
  "youtube_shorts" -> Site.YOUTUBE_SHORTS
  "x" -> Site.X
  else -> Site.OTHER
}

/** The member whose name is the wire value in upper case (`done` → `DONE`), or [unknown]. */
private inline fun <reified E : Enum<E>> enumOf(text: String?, unknown: E): E {
  val upper = text?.uppercase(Locale.ROOT) ?: return unknown
  return enumValues<E>().firstOrNull { it != unknown && it.name == upper } ?: unknown
}

private const val MAX_PERCENT = 100
