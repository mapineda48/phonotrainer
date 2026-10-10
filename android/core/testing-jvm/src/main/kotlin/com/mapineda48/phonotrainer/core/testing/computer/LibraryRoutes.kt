package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_ACCEPTED
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_BAD_REQUEST
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_CONFLICT
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_LENGTH_REQUIRED
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_NOT_FOUND
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_OK
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_PARTIAL
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_RANGE
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_TOO_LARGE
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_UNPROCESSABLE
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_UNSUPPORTED_MEDIA
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.SocketHandler
import okio.Buffer
import java.util.Locale
import kotlin.math.round

// The listing, one job, request lookup, reference, artifacts and the v1-complete media routes (server-contract 6.3,
// 6.4, 6.8, 6.9).

internal fun ComputerCore.library(call: Call): MockResponse = json(
  HTTP_OK,
  buildJsonObject {
    put("api_version", apiVersion)
    put("server_id", serverId)
    put("library_id", libraryId)
    put("generated_at", PhoneShapes.iso(serverNowMillis()))
    put("complete", true)
    put("jobs", JsonArray(jobs.values.map { jobJson(it, call.device!!.id) }))
  },
)

internal fun ComputerCore.jobRoute(call: Call): MockResponse =
  json(HTTP_OK, jobJson(knownJob(call.segments[3]), call.device!!.id))

internal fun ComputerCore.requestLookup(call: Call): MockResponse {
  val key = requestKey(call.segments[3])
  val job = key?.let { jobForKey(call.device!!.id, it) }
    ?: throw Refusal(HTTP_NOT_FOUND, "unknown_request", "This phone sent no request with that id.")
  return json(HTTP_OK, buildJsonObject { put("job", jobJson(job, call.device!!.id)) })
}

/** `GET /api/phone/reference`: byte for byte `/api/reference`, with `ETag: "api-<API_VERSION>"`. */
internal fun ComputerCore.reference(@Suppress("UNUSED_PARAMETER") call: Call): MockResponse = MockResponse.Builder()
  .code(HTTP_OK)
  .setHeader("Content-Type", "application/json")
  .setHeader("Cache-Control", "no-store")
  .setHeader("ETag", "\"api-$apiVersion\"")
  .body(Buffer().writeUtf8(referenceJson))
  .build()

/** The job named by a path segment, or `404 unknown_job`. */
internal fun ComputerCore.knownJob(id: String): Job =
  jobs[id] ?: throw Refusal(HTTP_NOT_FOUND, "unknown_job", "There is no such job on this computer.")

/** `GET /api/phone/jobs/{id}/artifacts/{name}`: the file, `Range`, `If-Range`, `ETag` and the download knobs. */
internal fun ComputerCore.artifact(call: Call): MockResponse {
  val job = knownJob(call.segments[3])
  val name = call.segments[5]
  val known = ArtifactNames.ALL
  if (name !in known) throw Refusal(HTTP_NOT_FOUND, "unknown_artifact", "There is no such artifact.")
  val artifact = job.artifacts[name]
  if (artifact == null) {
    if (job.status == JobStatus.DONE) throw Refusal(HTTP_NOT_FOUND, "unknown_artifact", "This job has no such artifact.")
    throw notReady()
  }
  val bytes = artifact.bytes
  if (artifact.status != ArtifactStatus.READY || bytes == null) throw notReady()
  val etag = "\"${PhoneCrypto.sha256Hex(bytes)}\""
  val size = bytes.size.toLong()
  val ifRange = call.header("If-Range")
  val range = call.header("Range")?.takeIf { ifRange == null || ifRange == etag }
  var start = 0L
  var end = size - 1
  var status = HTTP_OK
  if (range != null) {
    val match = RANGE.matchEntire(range.trim())
    if (match != null) {
      start = match.groupValues[1].toLong()
      end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(size - 1) ?: (size - 1)
      if (start >= size || start > end) {
        return MockResponse.Builder()
          .code(HTTP_RANGE)
          .setHeader("Content-Range", "bytes */$size")
          .setHeader("Content-Type", "application/json")
          .body("{\"detail\": \"Range Not Satisfiable\"}")
          .build()
      }
      status = HTTP_PARTIAL
    }
  }
  var served = bytes.copyOfRange(start.toInt(), (end + 1).toInt())
  if (takeRule(corruptions, "GET", "/$name") != null) served = ByteArray(served.size) { (served[it].toInt() xor 0xff).toByte() }
  val builder = MockResponse.Builder()
    .code(status)
    .setHeader("Content-Type", artifact.contentType)
    .setHeader("Accept-Ranges", "bytes")
    .setHeader("ETag", etag)
  if (status == HTTP_PARTIAL) builder.setHeader("Content-Range", "bytes $start-$end/$size")
  val truncation = takeRule(truncations, "GET", "/$name")
  if (truncation != null) {
    // Content-Length promises the whole range; the body stops at the absolute offset `atBytes`, then the socket closes.
    val cut = (truncation.atBytes - start).coerceIn(0, served.size.toLong()).toInt()
    val partial = served.copyOfRange(0, cut)
    builder.setHeader("Content-Length", served.size.toString())
    builder.socketHandler(
      object : SocketHandler {
        override fun handle(socket: okio.Socket) {
          socket.sink.write(Buffer().write(partial), partial.size.toLong())
          socket.sink.flush()
          socket.cancel()
        }
      },
    )
    return builder.build()
  }
  return builder.body(Buffer().write(served)).build()
}

private fun notReady() = Refusal(HTTP_CONFLICT, "artifact_not_ready", "This file is not ready yet.")

/** `POST /api/phone/jobs/{id}/renditions/video_phone` (complete). */
internal fun ComputerCore.requestPhoneVideo(call: Call): MockResponse {
  val job = knownJob(call.segments[3])
  if (job.status != JobStatus.DONE) throw Refusal(HTTP_CONFLICT, "not_ready", "This job has not finished.")
  if (job.media?.kind != MediaKind.VIDEO) throw Refusal(HTTP_CONFLICT, "not_video", "This job has no video.")
  val existing = job.artifacts[ArtifactNames.VIDEO_PHONE]
  val status = when {
    existing != null -> existing.status
    job.source == null -> ArtifactStatus.UNAVAILABLE.also { addPhoneVideo(job, it) }
    else -> ArtifactStatus.PREPARING.also { addPhoneVideo(job, it) }
  }
  val code = if (status == ArtifactStatus.PREPARING) HTTP_ACCEPTED else HTTP_OK
  return json(code, buildJsonObject { put("status", status.wire) })
}

private fun ComputerCore.addPhoneVideo(job: Job, status: ArtifactStatus) {
  job.artifacts[ArtifactNames.VIDEO_PHONE] =
    Artifact(ArtifactNames.VIDEO_PHONE, "video", "video/mp4", status, null)
  publish(job)
}

/** `GET /api/phone/jobs/{id}/contour?start=&end=` (complete): a canned contour of the span. */
internal fun ComputerCore.contour(call: Call): MockResponse {
  val job = knownJob(call.segments[3])
  val start = call.query("start")?.toDoubleOrNull() ?: throw invalidRequest("start and end must be numbers.")
  val end = call.query("end")?.toDoubleOrNull() ?: throw invalidRequest("start and end must be numbers.")
  checkSpan(start, end)
  return json(HTTP_OK, nativeContour(job, start, end))
}

/** `POST /api/phone/jobs/{id}/compare` (complete): multipart `file`, `start`, `end`; the take is never kept. */
internal fun ComputerCore.compare(call: Call): MockResponse {
  val job = knownJob(call.segments[3])
  val length = call.header("Content-Length")?.toLongOrNull()
    ?: throw takeError(HTTP_LENGTH_REQUIRED, "length_required", "the upload must declare its length")
  if (length > MAX_TAKE_BYTES + FORM_OVERHEAD_BYTES) {
    throw takeError(HTTP_TOO_LARGE, "too_large", "recordings are limited to 8 MB")
  }
  val parts = Multipart.parse(call.header("Content-Type"), call.body) ?: emptyList()
  val file = parts.firstOrNull { it.name == "file" && it.fileName != null }
    ?: throw takeError(HTTP_BAD_REQUEST, "missing_file", "no recording was sent")
  val start = spanField(parts, "start")
  val end = spanField(parts, "end")
  checkSpan(start, end)
  if (file.data.isEmpty()) throw takeError(HTTP_UNPROCESSABLE, "empty", "the recording arrived empty")
  if (file.data.size > MAX_TAKE_BYTES) throw takeError(HTTP_TOO_LARGE, "too_large", "recordings are limited to 8 MB")
  checkContainer(file.contentType, file.data)
  val native = nativeContour(job, start, end)
  val take = contourJson(0.0, TAKE_SECONDS, extra = { put("duration_s", TAKE_SECONDS) })
  return json(
    HTTP_OK,
    buildJsonObject {
      put("native", native)
      put("take", take)
      putJsonObject("comparison") {
        put("time_scale", 1.0)
        putJsonArray("overlay") {}
        putJsonArray("observations") {
          add(
            buildJsonObject {
              put("kind", "match")
              put("text", "Both rise at the end.")
            },
          )
        }
      }
    },
  )
}

private fun spanField(parts: List<Multipart.Part>, name: String): Double =
  parts.firstOrNull { it.name == name }?.data?.toString(Charsets.UTF_8)?.trim()?.toDoubleOrNull()
    ?: throw takeError(HTTP_BAD_REQUEST, "bad_span", "$name must be a number of seconds")

private fun checkSpan(start: Double, end: Double) {
  if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) {
    throw takeError(HTTP_BAD_REQUEST, "bad_span", "the span must satisfy 0 ≤ start < end")
  }
  if (end - start > MAX_SPAN_S) throw takeError(HTTP_BAD_REQUEST, "bad_span", "spans are limited to 30 seconds")
  if (end - start < MIN_SPAN_S) throw takeError(HTTP_BAD_REQUEST, "bad_span", "the span is too short to measure")
}

/** The declared type must name a container and the bytes must be that container (`learner_audio.container_for`). */
private fun checkContainer(contentType: String?, data: ByteArray) {
  val base = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
  val declared = CONTAINER_OF_TYPE[base]
    ?: throw takeError(HTTP_UNSUPPORTED_MEDIA, "unsupported_type", "recordings must be WebM, Ogg, MP4 or WAV audio")
  val head = data.copyOfRange(0, minOf(data.size, SNIFF_BYTES))
  val ascii = String(head, Charsets.ISO_8859_1)
  val found = when {
    head.size >= 4 && head[0] == 0x1a.toByte() && head[1] == 0x45.toByte() && head[2] == 0xdf.toByte() &&
      head[3] == 0xa3.toByte() -> "webm"
    ascii.startsWith("OggS") -> "ogg"
    head.size >= 12 && ascii.substring(4, 8) == "ftyp" -> "mp4"
    ascii.startsWith("RIFF") && head.size >= 12 && ascii.substring(8, 12) == "WAVE" -> "wav"
    else -> null
  }
  if (found != declared) {
    throw takeError(HTTP_UNSUPPORTED_MEDIA, "unsupported_type", "the file does not look like the declared audio")
  }
}

private fun ComputerCore.nativeContour(job: Job, start: Double, end: Double): JsonObject {
  if (job.status != JobStatus.DONE || job.content == null) {
    throw Refusal(HTTP_NOT_FOUND, "unknown_job", "${job.id} has no audio to compare against")
  }
  val dialogue = job.content?.audioDialogue != null
  return contourJson(start, end) {
    put("audio", if (dialogue) "dialogue" else "mix")
    put("segment", 0)
  }
}

private fun contourJson(
  start: Double,
  end: Double,
  extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
  put("start", round3(start))
  put("end", round3(end))
  putJsonObject("speech") {
    put("start", round3(start))
    put("end", round3(end))
  }
  put("speech_s", round3(end - start))
  put("voiced_s", round3((end - start) * VOICED_SHARE))
  put("median_hz", MEDIAN_HZ)
  put("f0_floor", F0_FLOOR)
  put("f0_ceiling", F0_CEILING)
  put("range_st", RANGE_ST)
  put("final_contour", "rising")
  put("final_slope_st", SLOPE_ST)
  put("peak", kotlinx.serialization.json.JsonNull)
  putJsonArray("pauses") {}
  putJsonArray("track") {
    val frames = ((end - start) / FRAME_S).toInt().coerceAtMost(MAX_FRAMES)
    for (i in 0 until frames) {
      add(
        buildJsonArray {
          add(kotlinx.serialization.json.JsonPrimitive(round3(start + i * FRAME_S)))
          add(kotlinx.serialization.json.JsonPrimitive(MEDIAN_HZ + i))
          add(kotlinx.serialization.json.JsonPrimitive(0.0))
          add(kotlinx.serialization.json.JsonPrimitive(LEVEL_DB))
        },
      )
    }
  }
  extra()
}

private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0

private fun takeError(status: Int, code: String, detail: String) = Refusal(status, code, detail)

/** The artifact names of server-contract 6.4.1. */
internal object ArtifactNames {
  const val ANALYSIS = "analysis"
  const val AUDIO_MIX = "audio_mix"
  const val AUDIO_DIALOGUE = "audio_dialogue"
  const val ORIGINAL = "original"
  const val ANALYSIS_COMPACT = "analysis_compact"
  const val AUDIO_MIX_OPUS = "audio_mix_opus"
  const val AUDIO_DIALOGUE_OPUS = "audio_dialogue_opus"
  const val POSTER = "poster"
  const val VIDEO_PHONE = "video_phone"

  val ALL = listOf(
    ANALYSIS, AUDIO_MIX, AUDIO_DIALOGUE, ORIGINAL, ANALYSIS_COMPACT, AUDIO_MIX_OPUS, AUDIO_DIALOGUE_OPUS, POSTER,
    VIDEO_PHONE,
  )
}

private val RANGE = Regex("^bytes=(\\d+)-(\\d*)$")
private val CONTAINER_OF_TYPE = mapOf(
  "audio/webm" to "webm", "video/webm" to "webm",
  "audio/ogg" to "ogg", "application/ogg" to "ogg", "audio/opus" to "ogg",
  "audio/mp4" to "mp4", "video/mp4" to "mp4", "audio/x-m4a" to "mp4", "audio/m4a" to "mp4",
  "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/vnd.wave" to "wav",
)
private const val MAX_TAKE_BYTES = 8 * 1024 * 1024
private const val FORM_OVERHEAD_BYTES = 64 * 1024
private const val SNIFF_BYTES = 16
private const val MAX_SPAN_S = 30.0
private const val MIN_SPAN_S = 0.05
private const val TAKE_SECONDS = 1.5
private const val VOICED_SHARE = 0.6
private const val MEDIAN_HZ = 120.0
private const val F0_FLOOR = 75.0
private const val F0_CEILING = 300.0
private const val RANGE_ST = 4.2
private const val SLOPE_ST = 3.1
private const val FRAME_S = 0.01
private const val MAX_FRAMES = 3_000
private const val LEVEL_DB = 60.0
