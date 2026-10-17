package com.mapineda48.phonotrainer.core.testing.computer

import okhttp3.WebSocket
import java.io.ByteArrayOutputStream
import java.util.Locale

// The fake computer's in-memory state: what the real server keeps in its configuration directory, its job store and
// its workspace (server-contract 3.2). Every field is guarded by ComputerCore's lock.

/** A row of `devices.json`. */
internal class Device(
  val id: String,
  var name: String,
  var key: ByteArray,
  val pairedAtMillis: Long,
  var appVersion: Int,
  var appVersionName: String,
) {
  var lastSeenAtMillis: Long? = null
  var revokedAtMillis: Long? = null

  /** Nonces seen in the last 600 s, oldest first (server-contract 4.3). */
  val nonces = LinkedHashMap<String, Long>()

  val revoked: Boolean get() = revokedAtMillis != null
}

/** The current pairing code (`pairing.json`, server-contract 2.3 and 3.2). */
internal class PairingCode(val code: String, val createdAtMillis: Long) {
  var wrongAttempts = 0
  var locked = false
  var consumed = false
  val expiresAtMillis: Long get() = createdAtMillis + PAIRING_CODE_TTL_MS

  companion object {
    const val PAIRING_CODE_TTL_MS = 300_000L
    const val MAX_WRONG = 5
  }
}

/** One artifact of a job (server-contract 6.4.1, 6.4.2). */
internal class Artifact(
  val name: String,
  val role: String,
  val contentType: String,
  var status: ArtifactStatus,
  var bytes: ByteArray?,
) {
  var error: String? = null
  var video: Triple<String, Int, Int>? = null

  val sha256: String? get() = if (status == ArtifactStatus.READY) bytes?.let(PhoneCrypto::sha256Hex) else null
}

/** A job of the store (server-contract 6.3), with what the phone shape needs. */
internal class Job(
  val id: String,
  val seq: Long,
  val kind: SourceKind,
  val url: String?,
  val fileName: String?,
  val audioOnly: Boolean,
  val separateDialogue: Boolean,
  val deviceId: String?,
  val clientRequestId: String?,
  val rerunOf: String?,
  val createdAtMillis: Long,
) {
  var status = JobStatus.QUEUED
  var stage: JobStage? = null
  var percent = 0
  var segment: Int? = null
  var segments: Int? = null
  var downloadPercent: Int? = null
  var separationSkipped = false
  var title: String? = null
  var startedAtMillis: Long? = null
  var finishedAtMillis: Long? = null
  var errorCode: String? = null
  var errorMessage: String? = null
  var cancelRequested = false
  var media: FakeMedia? = null
  var content: ClipContent? = null

  /** The uploaded file or the downloaded original; null once gone (`410 source_gone`, `original` unavailable). */
  var source: ByteArray? = null

  /** The size and SHA-256 a phone declared for the file it uploaded (`job.json`'s `upload_bytes`, `upload_sha256`). */
  var uploadBytes: Long? = null
  var uploadSha256: String? = null
  var sourceGone = false

  /** In the listing's order (the table of server-contract 6.4.1). */
  val artifacts = LinkedHashMap<String, Artifact>()

  fun ownedBy(deviceId: String?): Boolean = deviceId != null && this.deviceId == deviceId

  /**
   * First 16 hex digits of sha256 over `[status, error.code, [[name, status, sha256], …]]` (server-contract 6.3),
   * serialized compactly as WP-01's `revision_of` does.
   */
  val revision: String
    get() {
      val listed = artifacts.values.joinToString(",") { "[${quote(it.name)},${quote(it.status.wire)},${quote(it.sha256)}]" }
      val text = "[${quote(status.wire)},${quote(errorCode)},[$listed]]"
      return PhoneCrypto.sha256Hex(text.toByteArray(Charsets.UTF_8)).substring(0, REVISION_HEX)
    }

  companion object {
    const val REVISION_HEX = 16

    private fun quote(value: String?): String = if (value == null) "null" else "\"$value\""
  }
}

/** An upload session (`.phone-uploads/<key>.json` and its `.part`, server-contract 6.6). */
internal class UploadSession(
  val deviceId: String,
  val key: String,
  val fileName: String,
  val bytes: Long,
  val sha256: String,
  val separateDialogue: Boolean?,
  var lastActivityMillis: Long,
) {
  val part = ByteArrayOutputStream()

  val offset: Long get() = part.size().toLong()

  fun sameFile(fileName: String, bytes: Long, sha256: String): Boolean =
    this.fileName == fileName && this.bytes == bytes && this.sha256 == sha256.lowercase(Locale.ROOT)
}

/** An open (or opening) phone channel (server-contract 7). */
internal class Channel(val deviceId: String, val peer: Peer?) {
  var socket: WebSocket? = null

  /** Ends the channel abruptly. A server-side WebSocket has no call to cancel, so its connection is closed. */
  fun drop() {
    peer?.abort()
  }
}

/** A `failNext`, `applyThenDrop`, `truncate`, `corrupt` or `stallUpload` rule, used [times] times. */
internal class FaultRule(
  val method: String?,
  val pattern: String,
  var times: Int,
  val status: Int = 0,
  val code: String? = null,
  val retryAfterS: Int? = null,
  val atBytes: Long = 0,
) {
  /** `*` matches one path segment; the pattern is matched against the decoded path. */
  fun matches(method: String, path: String): Boolean {
    if (this.method != null && !this.method.equals(method, ignoreCase = true)) return false
    val want = pattern.trim('/').split('/')
    val have = path.trim('/').split('/')
    return want.size == have.size && want.zip(have).all { (w, h) -> w == "*" || w == h }
  }
}
