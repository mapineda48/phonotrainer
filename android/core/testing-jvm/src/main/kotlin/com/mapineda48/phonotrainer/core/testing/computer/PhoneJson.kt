package com.mapineda48.phonotrainer.core.testing.computer

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
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.domain.model.remote.VideoFacts
import com.mapineda48.phonotrainer.core.network.api.UploadState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import com.mapineda48.phonotrainer.core.domain.model.remote.MediaKind as RemoteMediaKind
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind as RemoteSourceKind

/** An answer the in-memory client cannot map: it becomes `AppError.Computer.Malformed`. */
internal class MalformedAnswer(message: String) : RuntimeException(message)

/**
 * The phone shapes of server-contract 6 read back into the `:core:domain` model, for [FakeComputerClient]. Like the
 * production mapper (architecture 5.4) it ignores unknown fields, maps unknown enum values to `UNKNOWN` (an unknown job
 * code to `processing_failed`, NFR-08) and refuses an answer whose required fields are missing.
 */
internal object PhoneJson {
  fun parse(bytes: ByteArray): JsonObject = try {
    Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw MalformedAnswer("not an object")
  } catch (e: IllegalArgumentException) {
    throw MalformedAnswer(e.message ?: "not JSON")
  }

  fun library(o: JsonObject) = RemoteLibrary(
    apiVersion = o.int("api_version"),
    serverId = ServerId(o.string("server_id")),
    libraryId = LibraryId(o.string("library_id")),
    generatedAt = o.instant("generated_at"),
    jobs = o.array("jobs").map { job(it.obj()) },
  )

  fun job(o: JsonObject): RemoteJob {
    val progress = o.objOrNull("progress")
    val source = o.obj("source")
    val options = o.objOrNull("options")
    return RemoteJob(
      id = JobId(o.string("id")),
      revision = o.string("revision"),
      status = enumOf(o.stringOrNull("status"), RemoteStatus.UNKNOWN),
      stage = o.stringOrNull("stage")?.let { enumOf(it, Stage.UNKNOWN) },
      percent = progress?.intOrNull("percent") ?: 0,
      segment = progress?.intOrNull("segment"),
      segments = progress?.intOrNull("segments"),
      downloadPercent = progress?.intOrNull("download_percent"),
      separation = o.stringOrNull("separation")?.let { enumOf(it, SeparationNote.UNKNOWN) },
      queuePosition = o.intOrNull("queue_position"),
      title = o.stringOrNull("title"),
      source = RemoteSource(
        kind = enumOf(source.stringOrNull("kind"), RemoteSourceKind.UNKNOWN),
        url = source.stringOrNull("url"),
        site = source.stringOrNull("site")?.let(::site),
        fileName = source.stringOrNull("file_name"),
      ),
      audioOnly = options?.booleanOrNull("audio_only") ?: false,
      separateDialogue = options?.booleanOrNull("separate_dialogue") ?: true,
      deviceId = o.stringOrNull("device_id")?.let(::DeviceId),
      own = o.booleanOrNull("own") ?: false,
      clientRequestId = o.stringOrNull("client_request_id")?.let(::RequestId),
      rerunOf = o.stringOrNull("rerun_of")?.let(::JobId),
      createdAt = o.instant("created"),
      startedAt = o.instantOrNull("started"),
      finishedAt = o.instantOrNull("finished"),
      error = o.objOrNull("error")?.let(::error),
      summary = o.objOrNull("summary")?.let(::summary),
      analysis = o.objOrNull("analysis")?.let(::analysis),
      media = o.objOrNull("media")?.let(::media),
      artifacts = (o["artifacts"] as? JsonArray)?.map { artifact(it.obj()) },
    )
  }

  fun identity(o: JsonObject, proofValid: Boolean?) = ComputerIdentity(
    phoneAccess = o.booleanOrNull("phone_access") ?: throw MalformedAnswer("phone_access"),
    serverId = o.stringOrNull("server_id")?.let(::ServerId),
    name = o.stringOrNull("name"),
    apiVersion = o.int("api_version"),
    serverTime = o.long("server_time"),
    proofValid = proofValid,
    revoked = o.booleanOrNull("revoked"),
  )

  fun health(o: JsonObject): ComputerHealth {
    val caps = o.obj("capabilities")
    val queue = o.obj("queue")
    return ComputerHealth(
      serverId = ServerId(o.string("server_id")),
      name = o.string("name"),
      libraryId = LibraryId(o.string("library_id")),
      apiVersion = o.int("api_version"),
      minAppVersion = o.int("min_app_version"),
      version = o.string("version"),
      serverTime = o.long("server_time"),
      deviceId = DeviceId(o.obj("device").string("device_id")),
      analyzing = queue.int("analyzing"),
      inLine = queue.int("in_line"),
      lowOnSpace = o.booleanOrNull("low_on_space") ?: false,
      capabilities = Capabilities(
        linkDownloads = caps.booleanOrNull("link_downloads") ?: false,
        downloaderVersion = caps.objOrNull("downloader")?.stringOrNull("version"),
        dialogueSeparation = caps.booleanOrNull("dialogue_separation") ?: false,
        renditions = caps.booleanOrNull("renditions") ?: false,
        phoneVideo = caps.booleanOrNull("phone_video") ?: false,
        resumableUploads = caps.booleanOrNull("resumable_uploads") ?: false,
        recordCompare = caps.booleanOrNull("record_compare") ?: false,
        maxUploadBytes = caps.long("max_upload_bytes"),
        maxChunkBytes = caps.long("max_chunk_bytes"),
        maxLinkDurationS = caps.long("max_link_duration_s"),
        freeBytes = caps.long("free_bytes"),
      ),
    )
  }

  fun grant(o: JsonObject) = PairGrant(
    deviceId = DeviceId(o.string("device_id")),
    token = o.string("token").toCharArray(),
    serverId = ServerId(o.string("server_id")),
    name = o.string("name"),
    libraryId = LibraryId(o.string("library_id")),
    apiVersion = o.int("api_version"),
    minAppVersion = o.int("min_app_version"),
    serverTime = o.long("server_time"),
  )

  /** The bodies of `POST /api/phone/uploads` and `GET /api/phone/uploads/{key}` (server-contract 6.6). */
  fun uploadState(o: JsonObject): UploadState = when (o.stringOrNull("state")) {
    "open" -> o.obj("upload").let {
      UploadState.Open(
        clientRequestId = RequestId(it.string("client_request_id")),
        offset = it.long("offset"),
        bytes = it.long("bytes"),
        expiresAt = it.instant("expires_at"),
        maxChunkBytes = it.long("max_chunk_bytes"),
        resumable = it.booleanOrNull("resumable") ?: false,
      )
    }
    "done" -> UploadState.Done(job(o.obj("job")))
    else -> throw MalformedAnswer("upload state")
  }

  /** One frame of the job channel (server-contract 7); null for a type this client does not know. */
  fun event(text: String): ChannelEvent? {
    val o = parse(text.toByteArray(Charsets.UTF_8))
    return when (o.stringOrNull("type")) {
      "hello" -> ChannelEvent.Hello(
        ServerId(o.string("server_id")), LibraryId(o.string("library_id")), o.int("api_version"),
      )
      "snapshot" -> ChannelEvent.Snapshot(o.array("jobs").map { job(it.obj()) })
      "job" -> ChannelEvent.Job(job(o.obj("job")))
      "deleted" -> ChannelEvent.Deleted(JobId(o.string("id")))
      else -> null
    }
  }

  fun artifactStatus(text: String?): RemoteArtifactStatus = enumOf(text, RemoteArtifactStatus.UNKNOWN)

  private fun error(o: JsonObject) = RemoteError(
    code = o.stringOrNull("code")?.takeIf { it in JobCodes.ALL } ?: "processing_failed",
    message = o.stringOrNull("message") ?: "",
    downloaderVersion = o.stringOrNull("downloader_version"),
  )

  private fun summary(o: JsonObject) = RemoteSummary(
    durationS = o.doubleOrNull("duration_s"),
    segments = o.intOrNull("segments") ?: 0,
    words = o.intOrNull("words") ?: 0,
    changeCounts = o.objOrNull("phenomena_counts")
      ?.mapNotNull { (label, count) -> (count as? JsonPrimitive)?.intOrNull?.let { label to it } }
      ?.toMap()
      .orEmpty(),
  )

  private fun analysis(o: JsonObject) = RemoteAnalysisInfo(
    rulesVersion = o.intOrNull("rules_version"),
    phoneEngine = o.stringOrNull("phone_engine"),
    dialogueSeparation = o.booleanOrNull("dialogue_separation") ?: false,
  )

  private fun media(o: JsonObject) = RemoteMedia(
    kind = enumOf(o.stringOrNull("kind"), RemoteMediaKind.UNKNOWN),
    codec = o.stringOrNull("codec"),
    width = o.intOrNull("width"),
    height = o.intOrNull("height"),
    rotation = o.intOrNull("rotation") ?: 0,
    startOffsetMs = o.longOrNull("start_offset_ms") ?: 0L,
    durationS = o.doubleOrNull("duration_s"),
  )

  private fun artifact(o: JsonObject) = RemoteArtifact(
    name = o.string("name"),
    role = enumOf(o.stringOrNull("role"), ArtifactRole.UNKNOWN),
    status = artifactStatus(o.stringOrNull("status")),
    bytes = o.longOrNull("bytes"),
    sha256 = o.stringOrNull("sha256"),
    revision = o.stringOrNull("revision"),
    contentType = o.stringOrNull("content_type"),
    url = o.stringOrNull("url"),
    error = o.stringOrNull("error"),
    video = o.objOrNull("video")?.let { VideoFacts(it.string("codec"), it.int("width"), it.int("height")) },
  )

  /** `source.site` (server-contract 6.3); a value this client does not know is `OTHER` (`Site` has no `UNKNOWN`). */
  private fun site(text: String): Site = when (text) {
    "youtube" -> Site.YOUTUBE
    "youtube_shorts" -> Site.YOUTUBE_SHORTS
    "x" -> Site.X
    else -> Site.OTHER
  }

  /** The wire value `snake_case` of an enum member named `UPPER_CASE`, or [unknown]. */
  private inline fun <reified E : Enum<E>> enumOf(text: String?, unknown: E): E =
    enumValues<E>().firstOrNull { it.name.equals(text, ignoreCase = true) && it != unknown } ?: unknown

  // --- typed field readers ----------------------------------------------------------------------------------------

  private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw MalformedAnswer("not an object")

  private fun JsonObject.obj(name: String): JsonObject = objOrNull(name) ?: throw MalformedAnswer(name)

  private fun JsonObject.objOrNull(name: String): JsonObject? = this[name]?.takeIf { it !is JsonNull } as? JsonObject

  private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: throw MalformedAnswer(name)

  private fun JsonObject.primitive(name: String): JsonPrimitive? =
    this[name]?.takeIf { it !is JsonNull } as? JsonPrimitive

  private fun JsonObject.stringOrNull(name: String): String? = primitive(name)?.takeIf { it.isString }?.content

  private fun JsonObject.string(name: String): String = stringOrNull(name) ?: throw MalformedAnswer(name)

  private fun JsonObject.intOrNull(name: String): Int? = primitive(name)?.takeIf { !it.isString }?.intOrNull

  private fun JsonObject.int(name: String): Int = intOrNull(name) ?: throw MalformedAnswer(name)

  private fun JsonObject.longOrNull(name: String): Long? = primitive(name)?.takeIf { !it.isString }?.longOrNull

  private fun JsonObject.long(name: String): Long = longOrNull(name) ?: throw MalformedAnswer(name)

  private fun JsonObject.doubleOrNull(name: String): Double? = primitive(name)?.takeIf { !it.isString }?.doubleOrNull

  private fun JsonObject.booleanOrNull(name: String): Boolean? = primitive(name)?.takeIf { !it.isString }?.booleanOrNull

  private fun JsonObject.instantOrNull(name: String): Instant? = stringOrNull(name)?.let {
    try {
      OffsetDateTime.parse(it).toInstant()
    } catch (e: DateTimeException) {
      throw MalformedAnswer("$name: ${e.message}")
    }
  }

  private fun JsonObject.instant(name: String): Instant = instantOrNull(name) ?: throw MalformedAnswer(name)
}
