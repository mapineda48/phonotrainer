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
// ComputerCore's NetworkHost methods.

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
  val fileName = cleanText(rawName)
  if (fileName.isEmpty() || fileName.length > MAX_FILE_NAME) throw invalidRequest("file_name is not valid.")
  jobForKey(device.id, key)?.let { job ->
    val sameFile = job.fileName == fileName && job.source?.let { PhoneCrypto.sha256Hex(it) == sha256 } != false
    if (job.kind == SourceKind.UPLOAD && job.rerunOf == null && sameFile) {
      return json(HTTP_OK, buildJsonObject {
        put("state", "done")
        put("job", jobJson(job, device.id))
      })
    }
    throw keyConflict()
  }
  sessions[device.id to key]?.let { session ->
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
  if (sessions.values.count { it.deviceId == device.id } >= ComputerCore.MAX_OPEN_SESSIONS) {
    throw Refusal(HTTP_CONFLICT, "upload_busy", "Too many unfinished uploads from this phone.")
  }
  if (freeForUploads() - bytes < ComputerCore.MIN_FREE_BYTES) throw noSpace()
  val session = UploadSession(device.id, key, fileName, bytes, sha256, separate, clock.nowMillis())
  sessions[device.id to key] = session
  return json(HTTP_CREATED, openState(session))
}

/** `PUT /api/phone/uploads/{key}`: the whole file in one body (core). */
internal fun ComputerCore.putUpload(call: Call): MockResponse {
  val device = call.device!!
  val key = uploadKey(call)
  val length = call.header("Content-Length")?.toLongOrNull()
    ?: throw Refusal(HTTP_LENGTH_REQUIRED, "length_required", "The upload must declare its length.")
  val session = sessions[device.id to key] ?: return existingJobOr404(device.id, key, HTTP_OK)
  checkNotBusy(call, device.id)
  if (length != session.bytes) {
    throw Refusal(HTTP_BAD_REQUEST, "length_mismatch", "The body length differs from the declared size.")
  }
  if (freeForUploads(excluding = session) - session.bytes < ComputerCore.MIN_FREE_BYTES) throw noSpace()
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
  sessions[device.id to key]?.let { return json(HTTP_OK, openState(it)) }
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
  val length = call.header("Content-Length")?.toLongOrNull()
    ?: throw Refusal(HTTP_LENGTH_REQUIRED, "length_required", "The chunk must declare its length.")
  val session = sessions[device.id to key] ?: return existingJobOr404(device.id, key, HTTP_OK)
  if (call.header("Content-Type")?.substringBefore(';')?.trim() != "application/offset+octet-stream") {
    throw invalidRequest("A chunk is application/offset+octet-stream.")
  }
  val offset = call.header("Upload-Offset")?.toLongOrNull() ?: throw invalidRequest("Upload-Offset is required.")
  if (length > capabilities.maxChunkBytes) {
    throw Refusal(HTTP_TOO_LARGE, "chunk_too_large", "Chunks are limited to ${capabilities.maxChunkBytes} bytes.")
  }
  if (length < 1) throw invalidRequest("A chunk holds at least one byte.")
  // A chunk the network layer streamed into the part was admitted at its start, offset included (4.7).
  val streamed = call.watch?.takeIf { it.streamsToPart }
  if (streamed == null && offset != session.offset) {
    throw Refusal(
      HTTP_CONFLICT, "offset_mismatch", "This computer has a different number of bytes.",
      extra = mapOf("offset" to JsonPrimitive(session.offset)),
    )
  }
  checkNotBusy(call, device.id)
  if (offset + length > session.bytes) {
    throw Refusal(HTTP_BAD_REQUEST, "length_mismatch", "The chunk goes past the declared size.")
  }
  if (freeForUploads(excluding = session) - session.bytes < ComputerCore.MIN_FREE_BYTES) throw noSpace()
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
  inFlight.filter { it.deviceId == device.id && it.key == key && !it.ended }.forEach(::abortWatch)
  sessions.remove(device.id to key)
  return noContent()
}

private fun ComputerCore.finishUpload(session: UploadSession, bytes: ByteArray, viewer: String): MockResponse {
  sessions.remove(session.deviceId to session.key)
  val job = newJob(
    SourceKind.UPLOAD, null, session.fileName, audioOnly = false, separateDialogue = session.separateDialogue ?: true,
    deviceId = session.deviceId, clientRequestId = session.key, rerunOf = null,
  )
  job.source = bytes
  publish(job)
  return json(HTTP_CREATED, buildJsonObject { put("job", jobJson(job, viewer)) })
}

/** At most one upload body per device; a newer body for the same key replaced an idle one when it started (4.7). */
private fun ComputerCore.checkNotBusy(call: Call, deviceId: String) {
  val watch = call.watch
  if (watch != null) {
    if (watch.refusedBusy) throw busy()
    return
  }
  // A body the network layer did not follow (an empty one): judged now.
  if (inFlight.any { it.deviceId == deviceId && !it.ended }) throw busy()
}

private fun ComputerCore.existingJobOr404(deviceId: String, key: String, status: Int): MockResponse {
  val job = jobForKey(deviceId, key)?.takeIf { it.kind == SourceKind.UPLOAD } ?: throw unknownUpload()
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

private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
private const val MAX_FILE_NAME = 200
private val MEDIA_SUFFIXES = listOf("webm", "mp4", "mkv", "mov", "avi", "m4v", "mp3", "wav", "m4a", "flac", "ogg", "opus", "aac")
