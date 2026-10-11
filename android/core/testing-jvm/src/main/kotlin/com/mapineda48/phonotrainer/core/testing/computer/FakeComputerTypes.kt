package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import java.time.Duration

// Public types of the fake computer's knobs, seeds and observations (architecture 21.3).

/** Whether the computer answers (architecture 21.3, `reachability`). */
sealed interface Reachability {
  /** Answers at once. */
  data object Reachable : Reachability

  /** Answers nothing: no byte reaches the computer or leaves it, so every call waits for its own timeout. */
  data object Unreachable : Reachability

  /** Answers after [latency] (real time): a computer busy analyzing. */
  data class Slow(val latency: Duration) : Reachability
}

/** How an impersonating responder answers `hello`'s challenge (architecture 21.3, spec F-02-AC11). */
enum class ProofMode {
  /** `proof: null`, as a host that merely repeats the paired server id. */
  MISSING,

  /** A well-formed proof computed with the wrong key. */
  WRONG,
}

/** The job statuses of server-contract 6.3. */
enum class JobStatus(val wire: String) {
  QUEUED("queued"),
  RUNNING("running"),
  DONE("done"),
  ERROR("error"),
  CANCELLED("cancelled"),
}

/** The stages of server-contract 6.3 (the web's `StageKey` values). */
enum class JobStage(val wire: String) {
  DOWNLOAD("download"),
  EXTRACT("extract"),
  SEPARATE("separate"),
  TRANSCRIBE("transcribe"),
  PHONES("phones"),
  SAVE("save"),
}

/** `source.kind` of server-contract 6.3. */
enum class SourceKind(val wire: String) {
  LINK("link"),
  UPLOAD("upload"),
  PATH("path"),
  IMPORT("import"),
}

/** `media.kind` of server-contract 6.4.3. */
enum class MediaKind(val wire: String) {
  VIDEO("video"),
  AUDIO("audio"),
  NONE("none"),
}

/** Artifact statuses of server-contract 6.4.2. */
enum class ArtifactStatus(val wire: String) {
  READY("ready"),
  PREPARING("preparing"),
  FAILED("failed"),
  UNAVAILABLE("unavailable"),
}

/** How a host name in a link resolves for the SSRF guard (server-contract 4.5); the fake never uses DNS. */
enum class LinkHostAnswer {
  /** Resolves to global unicast addresses: accepted. */
  PUBLIC,

  /** Resolves to a private or local address: `400 refused_address`. */
  REFUSED,

  /** Resolution fails or times out: `503 cannot_check_link`. */
  UNRESOLVABLE,
}

/** Facts of the original media (server-contract 6.4.3). Unknown rotation and offset are 0, as the server reports. */
data class FakeMedia(
  val kind: MediaKind = MediaKind.VIDEO,
  val codec: String? = "h264",
  val width: Int? = 1280,
  val height: Int? = 720,
  val rotation: Int = 0,
  val startOffsetMs: Long = 0,
  val durationS: Double? = 3.0,
) {
  companion object {
    val AUDIO = FakeMedia(kind = MediaKind.AUDIO, codec = "opus", width = null, height = null)
  }
}

/**
 * The capability flags and limits `health` reports (server-contract 3.4, 6.2). v1-core values by default;
 * [FakeComputer.enableCompleteTier] turns the four v1-complete flags on.
 */
data class FakeCapabilities(
  val linkDownloads: Boolean = true,
  val downloaderVersion: String? = "2026.8.19",
  val jsRuntime: String? = "node",
  val challengeSolver: Boolean = false,
  val dialogueSeparation: Boolean = true,
  val discovery: Boolean = true,
  val renditions: Boolean = false,
  val phoneVideo: Boolean = false,
  val resumableUploads: Boolean = false,
  val recordCompare: Boolean = false,
  val maxUploadBytes: Long = 2L * GIB,
  val maxChunkBytes: Long = 8L * MIB,
  val maxLinkDurationS: Long = 3_600L,
) {
  companion object {
    const val MIB: Long = 1024L * 1024L
    const val GIB: Long = 1024L * MIB
  }
}

/** The content a finished job serves: its clip data and audio (see [FixtureClips]). */
class ClipContent(
  val analysisJson: ByteArray,
  val audioMix: ByteArray,
  val audioDialogue: ByteArray?,
  val durationS: Double,
  val segments: Int,
  val words: Int,
  val changeCounts: Map<String, Int>,
)

/** Makes the content of a job that finishes (the title is the job's, or null for a link not yet downloaded). */
fun interface ClipContentProvider {
  fun contentFor(title: String?, separateDialogue: Boolean): ClipContent
}

/** A job to seed directly into the computer's store, as the browser, the command line or another phone made it. */
data class JobSeed(
  val status: JobStatus = JobStatus.DONE,
  val kind: SourceKind = SourceKind.LINK,
  val url: String? = if (kind == SourceKind.LINK) "https://youtu.be/abc123def45" else null,
  val fileName: String? = if (kind == SourceKind.LINK) null else "clip.mp4",
  val title: String? = "Seeded clip",
  /** The submitting phone; null for browser and command-line jobs. */
  val deviceId: DeviceId? = null,
  val clientRequestId: RequestId? = null,
  val audioOnly: Boolean = false,
  val separateDialogue: Boolean = true,
  val media: FakeMedia? = if (audioOnly) FakeMedia.AUDIO else FakeMedia(),
  /** The job failure code (server-contract 8.2) when [status] is `ERROR` or `CANCELLED`. */
  val errorCode: String? = null,
  val content: ClipContent? = null,
)

/** A job as the fake computer holds it, for assertions. */
data class FakeJobSnapshot(
  val id: JobId,
  val status: JobStatus,
  val stage: JobStage?,
  val percent: Int,
  val kind: SourceKind,
  val url: String?,
  val fileName: String?,
  val title: String?,
  val deviceId: DeviceId?,
  val clientRequestId: RequestId?,
  val rerunOf: JobId?,
  val audioOnly: Boolean,
  val separateDialogue: Boolean,
  val errorCode: String?,
  val revision: String,
  val artifacts: Map<String, ArtifactStatus>,
  /** The uploaded file (or the stored original) when the job has one. */
  val sourceBytes: ByteArray?,
)

/** A paired phone as `devices.json` holds it (server-contract 3.2), for assertions. */
data class PairedDevice(
  val deviceId: DeviceId,
  val name: String,
  val appVersion: Int,
  val appVersionName: String,
  val pairedAtEpochS: Long,
  val lastSeenAtEpochS: Long?,
  val revoked: Boolean,
)

/** One request that reached the computer, in arrival order (architecture 21.3, `requests`). */
data class RecordedCall(
  val method: String,
  /** The request target as sent: path and query, still percent-encoded. */
  val target: String,
  val headers: Map<String, String>,
  val bodySize: Long,
  val atEpochMillis: Long,
  /** The answer's status; null when no answer was sent (dropped, aborted or interrupted). */
  val status: Int?,
  /** The device whose valid signature the request carried, if any. */
  val signedBy: DeviceId?,
) {
  val path: String get() = target.substringBefore('?')

  val hadAuthorization: Boolean get() = headers.keys.any { it.equals("authorization", ignoreCase = true) }
}

/** An upload session (server-contract 6.6), for assertions. */
data class UploadSessionSnapshot(
  val clientRequestId: RequestId,
  val deviceId: DeviceId,
  val fileName: String,
  val bytes: Long,
  val offset: Long,
)

/** An upload body being received, for assertions (the stall and idle rules of server-contract 4.7). */
data class UploadInFlight(
  val clientRequestId: RequestId,
  val method: String,
  val receivedBytes: Long,
  val contentLength: Long,
)
