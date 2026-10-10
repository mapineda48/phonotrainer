package com.mapineda48.phonotrainer.core.testing.computer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phone shapes of server-contract 6.3 and 6.4.2, built by one serializer as the server's `phone/shape.py` does:
 * never a path, a workspace or a progress log; `device_id` and `client_request_id` only on the viewer's own jobs.
 */
internal object PhoneShapes {
  private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.ROOT).withZone(ZoneOffset.UTC)

  fun iso(epochMillis: Long?): String? = epochMillis?.let { ISO.format(Instant.ofEpochMilli(it - Math.floorMod(it, 1000L))) }

  /** A PhoneJob as [viewer] sees it; [lean] leaves `artifacts` out (channel events, server-contract 7). */
  fun job(job: Job, viewer: String?, queuePosition: Int?, lean: Boolean, downloaderVersion: String?): JsonObject {
    val own = job.ownedBy(viewer)
    return buildJsonObject {
      put("id", job.id)
      put("revision", job.revision)
      put("status", job.status.wire)
      put("stage", job.stage?.wire)
      putJsonObject("progress") {
        put("percent", job.percent)
        put("segment", job.segment)
        put("segments", job.segments)
        put("download_percent", job.downloadPercent)
      }
      put("separation", separation(job))
      put("queue_position", queuePosition)
      put("title", job.title)
      putJsonObject("source") {
        put("kind", job.kind.wire)
        put("url", if (job.kind == SourceKind.LINK) job.url else null)
        put("site", if (job.kind == SourceKind.LINK) job.url?.let(LinkRules::site) else null)
        put("file_name", if (job.kind == SourceKind.LINK) null else job.fileName)
      }
      putJsonObject("options") {
        put("audio_only", job.audioOnly)
        put("separate_dialogue", job.separateDialogue)
      }
      put("device_id", if (own) job.deviceId else null)
      put("own", own)
      put("client_request_id", if (own) job.clientRequestId else null)
      put("rerun_of", job.rerunOf)
      put("created", iso(job.createdAtMillis))
      put("started", iso(job.startedAtMillis))
      put("finished", iso(job.finishedAtMillis))
      put("error", error(job, downloaderVersion) ?: JsonNull)
      put("summary", summary(job) ?: JsonNull)
      put("analysis", analysis(job) ?: JsonNull)
      put("media", media(job.media) ?: JsonNull)
      if (!lean) put("artifacts", JsonArray(job.artifacts.values.map { artifact(job, it) }))
    }
  }

  private fun separation(job: Job): String? = when {
    !job.separateDialogue -> "off"
    job.separationSkipped -> "skipped"
    else -> null
  }

  private fun error(job: Job, downloaderVersion: String?): JsonObject? {
    val code = job.errorCode ?: return null
    return buildJsonObject {
      put("code", code)
      put("message", job.errorMessage ?: JobCodes.message(code))
      if (code in JobCodes.DOWNLOAD_CODES) put("downloader_version", downloaderVersion)
    }
  }

  private fun summary(job: Job): JsonObject? {
    val content = job.content ?: return null
    if (job.status != JobStatus.DONE) return null
    return buildJsonObject {
      put("duration_s", content.durationS)
      put("segments", content.segments)
      put("words", content.words)
      putJsonObject("phenomena_counts") { content.changeCounts.forEach { (label, count) -> put(label, count) } }
    }
  }

  private fun analysis(job: Job): JsonObject? {
    if (job.status != JobStatus.DONE || job.content == null) return null
    return buildJsonObject {
      put("rules_version", RULES_VERSION)
      put("phone_engine", "timit61")
      put("dialogue_separation", job.content?.audioDialogue != null)
      put("phonotrainer_version", "0.1.0")
    }
  }

  private fun media(media: FakeMedia?): JsonObject? = media?.let {
    buildJsonObject {
      put("kind", it.kind.wire)
      put("codec", it.codec)
      put("width", it.width)
      put("height", it.height)
      put("rotation", it.rotation)
      put("start_offset_ms", it.startOffsetMs)
      put("duration_s", it.durationS)
    }
  }

  fun artifact(job: Job, artifact: Artifact): JsonObject {
    val ready = artifact.status == ArtifactStatus.READY
    val sha = artifact.sha256
    return buildJsonObject {
      put("name", artifact.name)
      put("role", artifact.role)
      put("status", artifact.status.wire)
      put("bytes", if (ready) artifact.bytes?.size?.toLong() else null)
      put("sha256", sha)
      put("revision", sha?.substring(0, Job.REVISION_HEX))
      put("content_type", artifact.contentType)
      put("url", if (ready) "/api/phone/jobs/${job.id}/artifacts/${artifact.name}" else null)
      put("error", artifact.error)
      artifact.video?.takeIf { ready }?.let { (codec, width, height) ->
        putJsonObject("video") {
          put("codec", codec)
          put("width", width)
          put("height", height)
        }
      }
    }
  }

  /** The error envelope of server-contract 6.1 and 8.1, with the route's extra fields. */
  fun refusal(code: String, detail: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
    JsonObject(mapOf("detail" to JsonPrimitive(detail), "code" to JsonPrimitive(code)) + extra)

  private const val RULES_VERSION = 3
}

/** The job failure codes of server-contract 8.2 and their clean messages (8.3). */
internal object JobCodes {
  /** Codes 4 to 10 of 8.2: their error carries `downloader_version`. */
  val DOWNLOAD_CODES = setOf(
    "live_stream", "too_long", "playlist", "download_forbidden", "download_unavailable", "unsupported_site", "network",
  )

  val ALL = setOf(
    "cancelled", "interrupted", "refused_address", "live_stream", "too_long", "playlist", "download_forbidden",
    "download_unavailable", "unsupported_site", "network", "no_space", "tool_missing", "not_media", "extract_failed",
    "processing_failed",
  )

  fun message(code: String): String = when (code) {
    "cancelled" -> "Cancelled before starting."
    "interrupted" -> "the server stopped before this job finished."
    "refused_address" -> "This link points to a private or local address."
    "live_stream" -> "Live streams can't be analyzed."
    "too_long" -> "This video is longer than this computer accepts."
    "playlist" -> "that is a playlist, not a single video"
    "download_forbidden" -> "unable to download video data: HTTP Error 403: Forbidden"
    "download_unavailable" -> "Video unavailable"
    "unsupported_site" -> "Unsupported URL"
    "network" -> "Unable to download webpage: timed out"
    "no_space" -> "No space left on device"
    "tool_missing" -> "ffmpeg: not found"
    "not_media" -> "Invalid data found when processing input"
    "extract_failed" -> "audio extraction failed"
    else -> "The analysis failed."
  }
}
