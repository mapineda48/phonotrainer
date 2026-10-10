package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_BAD_REQUEST
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_CREATED
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_FORBIDDEN
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_OK
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_UPGRADE_REQUIRED
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import java.text.Normalizer
import java.util.Locale

// Identity, pairing and health (server-contract 6.2).

/** `GET /api/phone/hello`: identity, phone-access state and, for a paired phone's challenge, the identity proof. */
internal fun ComputerCore.hello(call: Call): MockResponse {
  if (!phoneAccess) {
    return json(
      HTTP_OK,
      buildJsonObject {
        put("service", "phonotrainer")
        put("phone_access", false)
        put("api_version", apiVersion)
        put("server_time", serverTimeS())
      },
    )
  }
  val deviceId = call.query("device_id")
  val challenge = call.query("challenge")
  if (challenge != null && !CHALLENGE.matches(challenge)) throw invalidRequest("The challenge is not valid.")
  if (deviceId != null && challenge != null) {
    rateLimit("hello", call.connection?.remoteAddress ?: "unknown", perSource = 20, total = 120)
  }
  val device = if (deviceId != null && challenge != null) devices[deviceId] else null
  return json(
    HTTP_OK,
    buildJsonObject {
      put("service", "phonotrainer")
      put("phone_access", true)
      put("server_id", serverId)
      put("name", name)
      put("api_version", apiVersion)
      put("server_time", serverTimeS())
      put("proof", device?.let { PhoneCrypto.helloProof(it.key, serverId, it.id, challenge!!) })
      put("revoked", device?.revoked)
    },
  )
}

/** `POST /api/phone/pair`: the code rules of 2.3 and the re-activation proof of 6.2. */
internal fun ComputerCore.pair(call: Call): MockResponse {
  rateLimit("pair", call.connection?.remoteAddress ?: "unknown", perSource = 10, total = 30)
  val body = jsonBody(call)
  val code = body.string("code")?.takeIf { CODE.matches(it) } ?: throw invalidRequest("The code must be 6 digits.")
  val deviceName = body.string("device_name")?.let(::cleanText)?.takeIf { it.length in 1..MAX_DEVICE_NAME }
    ?: throw invalidRequest("The device name is not valid.")
  val appVersion = (body["app_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 1 }
    ?: throw invalidRequest("The app version is not valid.")
  val appVersionName = body.string("app_version_name") ?: ""
  val previousId = body.nullableString("previous_device_id")
  val previousProof = body.nullableString("previous_token_proof")
  if ((previousId == null) != (previousProof == null)) {
    throw invalidRequest("previous_device_id and previous_token_proof go together.")
  }
  if (appVersion < minAppVersion) {
    throw Refusal(HTTP_UPGRADE_REQUIRED, "app_too_old", "This app is older than this computer accepts.")
  }
  val current = pairing?.takeIf { !it.consumed }
    ?: throw Refusal(HTTP_FORBIDDEN, "code_invalid", "That pairing code is not valid.")
  if (current.locked) throw Refusal(HTTP_FORBIDDEN, "code_locked", "That pairing code is locked after 5 wrong attempts.")
  if (clock.nowMillis() >= current.expiresAtMillis) {
    throw Refusal(HTTP_FORBIDDEN, "code_expired", "That pairing code has expired.")
  }
  if (!PhoneCrypto.constantTimeEquals(code, current.code)) {
    current.wrongAttempts += 1
    if (current.wrongAttempts >= PairingCode.MAX_WRONG) current.locked = true
    throw Refusal(HTTP_FORBIDDEN, "code_invalid", "That pairing code is not valid.")
  }
  current.consumed = true
  val token = newToken()
  val key = PhoneCrypto.keyFromToken(token)
  val previous = previousId?.let { devices[it] }?.takeIf {
    PhoneCrypto.constantTimeEquals(PhoneCrypto.reactivationProof(it.key, code), previousProof!!)
  }
  val device = if (previous != null) {
    previous.key = key
    previous.name = deviceName
    previous.revokedAtMillis = null
    previous.appVersion = appVersion
    previous.appVersionName = appVersionName
    previous.nonces.clear()
    previous
  } else {
    Device(newId("dev_"), deviceName, key, clock.nowMillis(), appVersion, appVersionName).also { devices[it.id] = it }
  }
  return json(
    HTTP_CREATED,
    buildJsonObject {
      put("device_id", device.id)
      put("token", token)
      put("server_id", serverId)
      put("name", name)
      put("library_id", libraryId)
      put("api_version", apiVersion)
      put("min_app_version", minAppVersion)
      put("server_time", serverTimeS())
    },
  )
}

/** `GET /api/phone/health`: the authenticated probe, from memory only. */
internal fun ComputerCore.health(call: Call): MockResponse {
  val device = call.device!!
  val caps = capabilities
  val free = freeForUploads()
  return json(
    HTTP_OK,
    buildJsonObject {
      put("server_id", serverId)
      put("name", name)
      put("library_id", libraryId)
      put("api_version", apiVersion)
      put("min_app_version", minAppVersion)
      put("version", "0.1.0")
      put("server_time", serverTimeS())
      putJsonObject("device") {
        put("device_id", device.id)
        put("name", device.name)
      }
      putJsonObject("queue") {
        put("analyzing", jobs.values.count { it.status == JobStatus.RUNNING })
        put("in_line", jobs.values.count { it.status == JobStatus.QUEUED && !it.cancelRequested })
      }
      put("low_on_space", free < ComputerCore.LOW_SPACE_BYTES)
      putJsonObject("capabilities") {
        put("link_downloads", caps.linkDownloads)
        if (caps.downloaderVersion == null) {
          put("downloader", JsonNull)
        } else {
          putJsonObject("downloader") {
            put("name", "yt-dlp")
            put("version", caps.downloaderVersion)
            put("js_runtime", caps.jsRuntime)
            put("challenge_solver", caps.challengeSolver)
          }
        }
        put("dialogue_separation", caps.dialogueSeparation)
        put("discovery", caps.discovery)
        put("renditions", caps.renditions)
        put("phone_video", caps.phoneVideo)
        put("resumable_uploads", caps.resumableUploads)
        put("record_compare", caps.recordCompare)
        put("max_upload_bytes", caps.maxUploadBytes)
        put("max_chunk_bytes", caps.maxChunkBytes)
        put("max_link_duration_s", caps.maxLinkDurationS)
        put("free_bytes", free.coerceAtLeast(0))
      }
    },
  )
}

/** `DELETE /api/phone/devices/self`: "Forget this computer". */
internal fun ComputerCore.unpairSelf(call: Call): MockResponse {
  call.device!!.revokedAtMillis = clock.nowMillis()
  return noContent()
}

// --- small JSON readers shared by the routes -------------------------------------------------------------------

/** A string field; null when absent, null or not a string. */
internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A field that may be null or absent but, when present, must be a string. */
internal fun JsonObject.nullableString(name: String): String? {
  val value = this[name] ?: return null
  if (value is JsonNull) return null
  return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw Refusal(HTTP_BAD_REQUEST, "invalid_request", "$name must be a string.")
}

/** A boolean field that may be null or absent. */
internal fun JsonObject.nullableBoolean(name: String): Boolean? {
  val value = this[name] ?: return null
  if (value is JsonNull) return null
  return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    ?: throw Refusal(HTTP_BAD_REQUEST, "invalid_request", "$name must be true or false.")
}

/** Trimmed, with the Unicode categories Cc, Cf, Zl and Zp removed (server-contract 2.3). */
internal fun cleanText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
  .filter { ch ->
    when (Character.getType(ch).toByte()) {
      Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
      else -> true
    }
  }
  .trim()

/** A client request id normalized to lower case, or null when it is not a canonical UUID. */
internal fun requestKey(text: String?): String? = text?.takeIf { ComputerCore.UUID_RE.matches(it) }?.lowercase(Locale.ROOT)

private val CHALLENGE = Regex("^[0-9a-f]{32,64}$")
private val CODE = Regex("^[0-9]{6}$")
private const val MAX_DEVICE_NAME = 60
