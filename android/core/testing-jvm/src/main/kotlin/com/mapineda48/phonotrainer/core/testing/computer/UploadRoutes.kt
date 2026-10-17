package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_BAD_REQUEST
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_CONFLICT
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_CREATED
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_LENGTH_REQUIRED
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_NOT_FOUND
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_NO_SPACE
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_OK
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_TOO_LARGE
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_UNPROCESSABLE
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import java.util.Locale

// Upload sessions (server-contract 6.6; limits of 4.7). The streamed side (stall, newest wins, idle abort) lives in
// ComputerCore's NetworkHost methods. Sessions are named by their key alone (3.2), and a refusal sent before the body
// was read closes the connection (4.7); only a checksum, known once the whole body arrived, keeps it.

/** `POST /api/phone/uploads`: declare a file; refuses early, before any byte is sent (spec 7.7). */
internal fun ComputerCore.declareUpload(call: Call): MockResponse {
  val device = call.device!!
  val body = jsonBody(call)
  val key = requestKey(body.string("client_request_id")) ?: throw invalidRequest("client_request_id must be a UUID.")
  val rawName = body.string("file_name") ?: throw invalidRequest("file_name is required.")
  val bytes = (body["bytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    ?: throw invalidRequest("bytes must be a number.")
  val sha256 = body.string("sha256")?.takeIf { SHA256.matches(it) }?.lowercase(Locale.ROOT)
    ?: throw invalidRequest("sha256 must be 64 hex characters.")
  val separate = body.nullableBoolean("separate_dialogue")
  if (bytes < 1) throw invalidRequest("bytes must be at least 1.")
  if (rawName.contains('/') || rawName.contains('\\')) throw invalidRequest("file_name must be a base name.")
  val cleaned = cleanText(rawName)
  if (cleaned.isEmpty() || cleaned.codePointCount(0, cleaned.length) > MAX_FILE_NAME) {
    throw invalidRequest("file_name is not valid.")
  }
  // Stored under a name that fits the disk; the same declaration always gives the same name.
  val fileName = fitFileName(cleaned)
  jobForKey(device.id, key)?.let { job ->
    if (createdByThisUpload(job, fileName, bytes, sha256)) {
      return json(HTTP_OK, buildJsonObject {
        put("state", "done")
        put("job", jobJson(job, device.id))
      })
    }
    throw keyConflict()
  }
  ownSession(device.id, key)?.let { session ->
    if (!session.sameFile(fileName, bytes, sha256)) throw keyConflict()
    return json(HTTP_OK, openState(session))
  }
  if (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) !in MEDIA_SUFFIXES) {
    throw Refusal(
      HTTP_BAD_REQUEST, "unsupported_type",
      "PhonoTrainer accepts ${MEDIA_SUFFIXES.joinToString(" ") { ".$it" }} files.",
    )
  }
  if (bytes > capabilities.maxUploadBytes) {
    throw Refusal(HTTP_TOO_LARGE, "too_large", "Files are limited to ${capabilities.maxUploadBytes} bytes.")
  }
  // A key held by another device's open session (a UUID clash, 6.1).
  if (sessions.containsKey(key)) throw keyConflict()
  if (sessions.values.count { it.deviceId == device.id } >= ComputerCore.MAX_OPEN_SESSIONS) {
    throw Refusal(HTTP_CONFLICT, "upload_busy", "Too many unfinished uploads from this phone.")
  }
  if (freeForUploads() - bytes < ComputerCore.MIN_FREE_BYTES) throw noSpace()
  val session = UploadSession(device.id, key, fileName, bytes, sha256, separate, clock.nowMillis())
  sessions[key] = session
  return json(HTTP_CREATED, openState(session))
}

/**
 * Did the job [key] created come from an upload of this very file? Its name, and its size and SHA-256 as recorded
 * when it arrived (a job that recorded neither, a seeded one, is matched on the name), 6.6.
 */
private fun createdByThisUpload(job: Job, fileName: String, bytes: Long, sha256: String): Boolean =
  job.kind == SourceKind.UPLOAD && job.rerunOf == null && job.fileName == fileName &&
    (job.uploadBytes == null || job.uploadBytes == bytes) && (job.uploadSha256 == null || job.uploadSha256 == sha256)

/** `PUT /api/phone/uploads/{key}`: the whole file in one body (core). */
internal fun ComputerCore.putUpload(call: Call): MockResponse {
  val device = call.device!!
  val key = uploadKey(call)
  val length = declaredLength(call, "The upload must declare its length.")
  val session = ownSession(device.id, key) ?: return existingJobOr404(device.id, key, HTTP_OK)
  checkNotBusy(call, device.id)
  if (length != session.bytes) {
    throw Refusal(HTTP_BAD_REQUEST, "length_mismatch", "The body length differs from the declared size.").closing()
  }
  if (freeForUploads(excluding = session) - session.bytes < ComputerCore.MIN_FREE_BYTES) throw noSpace().closing()
  session.lastActivityMillis = clock.nowMillis()
  if (PhoneCrypto.sha256Hex(call.body) != session.sha256) {
    session.part.reset()
    throw Refusal(HTTP_UNPROCESSABLE, "checksum_mismatch", "The file arrived damaged; send it again.")
  }
  return finishUpload(session, call.body, device.id)
}

/** `GET /api/phone/uploads/{key}` (complete): the session's offset, or the job the key created. */
internal fun ComputerCore.uploadStatus(call: Call): MockResponse {
  val device = call.device!!
  val key = uploadKey(call)
  ownSession(device.id, key)?.let { return json(HTTP_OK, openState(it)) }
  val job = jobForKey(device.id, key)?.takeIf { it.kind == SourceKind.UPLOAD } ?: throw unknownUpload()
  return json(HTTP_OK, buildJsonObject {
    put("state", "done")
    put("job", jobJson(job, device.id))
  })
}

/** `PATCH /api/phone/uploads/{key}` (complete): one chunk at `Upload-Offset`. */
internal fun ComputerCore.patchUpload(call: Call): MockResponse {
  val device = call.device!!
  val key = uploadKey(call)
  val length = declaredLength(call, "The chunk must declare its length.")
  val session = ownSession(device.id, key) ?: return existingJobOr404(device.id, key, HTTP_OK)
  if (call.header("Content-Type")?.substringBefore(';')?.trim() != "application/offset+octet-stream") {
    throw invalidRequest("A chunk is application/offset+octet-stream.").closing()
  }
  val offset = call.header("Upload-Offset")?.toLongOrNull()
    ?: throw invalidRequest("Upload-Offset is required.").closing()
  if (length > capabilities.maxChunkBytes) {
    throw Refusal(HTTP_TOO_LARGE, "chunk_too_large", "Chunks are limited to ${capabilities.maxChunkBytes} bytes.")
      .closing()
  }
  if (length < 1) throw invalidRequest("A chunk holds at least one byte.").closing()
  // A chunk the network layer streamed into the part was admitted at its start, offset included (4.7).
  val streamed = call.watch?.takeIf { it.streamsToPart }
  if (streamed == null && offset != session.offset) {
    throw Refusal(
      HTTP_CONFLICT, "offset_mismatch", "This computer has a different number of bytes.",
      extra = mapOf("offset" to JsonPrimitive(session.offset)),
    ).closing()
  }
  checkNotBusy(call, device.id)
  if (offset + length > session.bytes) {
    throw Refusal(HTTP_BAD_REQUEST, "length_mismatch", "The chunk goes past the declared size.").closing()
  }
  if (freeForUploads(excluding = session) - session.bytes < ComputerCore.MIN_FREE_BYTES) throw noSpace().closing()
  if (streamed == null) session.part.write(call.body)
  session.lastActivityMillis = clock.nowMillis()
  if (session.offset < session.bytes) {
    return noContent(mapOf("Upload-Offset" to session.offset.toString()))
  }
  val whole = session.part.toByteArray()
  if (PhoneCrypto.sha256Hex(whole) != session.sha256) {
    session.part.reset()
    throw Refusal(HTTP_UNPROCESSABLE, "checksum_mismatch", "The file arrived damaged; it starts again at 0.")
  }
  return finishUpload(session, whole, device.id)
}

/** `DELETE /api/phone/uploads/{key}`: "Remove" while sending; always `204`. */
internal fun ComputerCore.deleteUpload(call: Call): MockResponse {
  val device = call.device!!
  val key = uploadKey(call)
  // Only this device's session goes; a body still arriving for it is answered as replaced (6.6).
  if (ownSession(device.id, key) != null) {
    inFlight.filter { it.deviceId == device.id && it.key == key && !it.ended }.forEach(::supersede)
    sessions.remove(key)
  }
  return noContent()
}

private fun ComputerCore.finishUpload(session: UploadSession, bytes: ByteArray, viewer: String): MockResponse {
  sessions.remove(session.key)
  val job = newJob(
    SourceKind.UPLOAD, null, session.fileName, audioOnly = false, separateDialogue = session.separateDialogue ?: true,
    deviceId = session.deviceId, clientRequestId = session.key, rerunOf = null,
  )
  job.source = bytes
  job.uploadBytes = session.bytes
  job.uploadSha256 = session.sha256
  publish(job)
  return json(HTTP_CREATED, buildJsonObject { put("job", jobJson(job, viewer)) })
}

/** At most one upload body per device; a newer body for the same key replaced an idle one when it started (4.7). */
private fun ComputerCore.checkNotBusy(call: Call, deviceId: String) {
  val watch = call.watch
  if (watch != null) {
    // A body the computer already answered (replaced or idle) keeps that answer.
    watch.answer?.let { throw it }
    if (watch.refusedBusy) throw busy().closing()
    return
  }
  // A body the network layer did not follow (an empty one): judged now.
  if (inFlight.any { it.deviceId == deviceId && !it.ended }) throw busy().closing()
}

/** The body's `Content-Length`: `411` without one or with a chunked body, `400` when malformed (6.6). */
private fun ComputerCore.declaredLength(call: Call, missing: String): Long {
  val lengths = call.request.headers.values("Content-Length")
  val chunked = call.header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
  if (lengths.isEmpty() || chunked) throw Refusal(HTTP_LENGTH_REQUIRED, "length_required", missing).closing()
  return lengths.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
    ?: throw invalidRequest("Content-Length is malformed.").closing()
}

private fun ComputerCore.existingJobOr404(deviceId: String, key: String, status: Int): MockResponse {
  val job = jobForKey(deviceId, key)?.takeIf { it.kind == SourceKind.UPLOAD } ?: throw unknownUpload().closing()
  return json(status, buildJsonObject { put("job", jobJson(job, deviceId)) })
}

private fun ComputerCore.openState(session: UploadSession): JsonObject = buildJsonObject {
  put("state", "open")
  putJsonObject("upload") {
    put("client_request_id", session.key)
    put("offset", if (capabilities.resumableUploads) session.offset else 0L)
    put("bytes", session.bytes)
    put("expires_at", PhoneShapes.iso(session.lastActivityMillis + ComputerCore.SESSION_TTL_MS + skewS * 1_000L))
    put("max_chunk_bytes", capabilities.maxChunkBytes)
    put("resumable", capabilities.resumableUploads)
  }
}

private fun uploadKey(call: Call): String = call.segments[3].lowercase(Locale.ROOT)

private fun unknownUpload() = Refusal(HTTP_NOT_FOUND, "unknown_upload", "There is no such upload; declare it first.")

private fun busy() = Refusal(HTTP_CONFLICT, "upload_busy", "Another upload from this phone is still receiving bytes.")

private fun noSpace() = Refusal(HTTP_NO_SPACE, "no_space", "This computer would drop below 2 GiB of free space.")

/**
 * [name] shortened, if needed, so that its UTF-8 form fits [maxBytes] (file systems count bytes, not characters):
 * the stem loses its last whole characters and trailing spaces or dots, the suffix is kept, and the same name always
 * gives the same result (6.6; the server's `fit_file_name`).
 */
internal fun fitFileName(name: String, maxBytes: Int = MAX_FILE_NAME_BYTES): String {
  if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
  val dot = name.lastIndexOf('.')
  // As Python's os.path.splitext: leading dots are not a suffix.
  val split = dot > 0 && name.substring(0, dot).any { it != '.' }
  val stem = if (split) name.substring(0, dot) else name
  val suffix = if (split) name.substring(dot) else ""
  val room = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
  if (stem.isEmpty() || room < 1) return utf8Prefix(name, maxBytes)
  return utf8Prefix(stem, room).trimEnd(' ', '.').ifEmpty { "upload" } + suffix
}

/** The longest prefix of whole characters of [text] whose UTF-8 form fits [maxBytes]. */
private fun utf8Prefix(text: String, maxBytes: Int): String {
  var bytes = 0
  var end = 0
  while (end < text.length) {
    val next = end + Character.charCount(text.codePointAt(end))
    val size = text.substring(end, next).toByteArray(Charsets.UTF_8).size
    if (bytes + size > maxBytes) break
    bytes += size
    end = next
  }
  return text.substring(0, end)
}

private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
private const val MAX_FILE_NAME = 200
private const val MAX_FILE_NAME_BYTES = 200
private val MEDIA_SUFFIXES = listOf("webm", "mp4", "mkv", "mov", "avi", "m4v", "mp3", "wav", "m4a", "flac", "ogg", "opus", "aac")
