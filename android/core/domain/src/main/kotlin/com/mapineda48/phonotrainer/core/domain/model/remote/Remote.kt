package com.mapineda48.phonotrainer.core.domain.model.remote

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.Site
import java.time.Instant

// What the computer reports, mapped by :core:network from server-contract 6.3 (work-plan 4.2). Every enum decoded
// from the computer has an UNKNOWN member: a future value never fails a listing (NFR-08, D-29).

data class RemoteLibrary(
  val apiVersion: Int,
  val serverId: ServerId,
  val libraryId: LibraryId,
  val generatedAt: Instant,
  val jobs: List<RemoteJob>,
)

data class RemoteJob(
  val id: JobId,
  val revision: String,
  val status: RemoteStatus,
  val stage: Stage?,
  val percent: Int,
  val segment: Int?,
  val segments: Int?,
  val downloadPercent: Int?,
  val separation: SeparationNote?,
  val queuePosition: Int?,
  val title: String?,
  val source: RemoteSource,
  val audioOnly: Boolean,
  val separateDialogue: Boolean,
  val deviceId: DeviceId?,
  val own: Boolean,
  val clientRequestId: RequestId?,
  val rerunOf: JobId?,
  val createdAt: Instant,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val error: RemoteError?,
  val summary: RemoteSummary?,
  val analysis: RemoteAnalysisInfo?,
  val media: RemoteMedia?,
  /** Null when the job came from the job channel (lean). */
  val artifacts: List<RemoteArtifact>?,
)

enum class RemoteStatus { QUEUED, RUNNING, DONE, ERROR, CANCELLED, UNKNOWN }

enum class Stage { DOWNLOAD, EXTRACT, SEPARATE, TRANSCRIBE, PHONES, SAVE, UNKNOWN }

enum class SeparationNote { OFF, SKIPPED, UNKNOWN }

data class RemoteSource(val kind: SourceKind, val url: String?, val site: Site?, val fileName: String?)

enum class SourceKind { LINK, UPLOAD, PATH, IMPORT, UNKNOWN }

data class RemoteError(val code: String, val message: String, val downloaderVersion: String?)

data class RemoteSummary(val durationS: Double?, val segments: Int, val words: Int, val changeCounts: Map<String, Int>)

data class RemoteAnalysisInfo(val rulesVersion: Int?, val phoneEngine: String?, val dialogueSeparation: Boolean)

/** Rotation and offset are 0 when unknown (server-contract 6.3). */
data class RemoteMedia(
  val kind: MediaKind,
  val codec: String?,
  val width: Int?,
  val height: Int?,
  val rotation: Int,
  val startOffsetMs: Long,
  val durationS: Double?,
)

enum class MediaKind { VIDEO, AUDIO, NONE, UNKNOWN }

data class RemoteArtifact(
  val name: String,
  val role: ArtifactRole,
  val status: RemoteArtifactStatus,
  val bytes: Long?,
  val sha256: String?,
  val revision: String?,
  val contentType: String?,
  val url: String?,
  val error: String?,
  val video: VideoFacts?,
)

enum class ArtifactRole { CLIP_DATA, AUDIO_MIX, AUDIO_DIALOGUE, VIDEO, POSTER, UNKNOWN }

enum class RemoteArtifactStatus { READY, PREPARING, FAILED, UNAVAILABLE, UNKNOWN }

data class VideoFacts(val codec: String, val width: Int, val height: Int)

/** The answer to `hello`. [proofValid] is null when no challenge was sent. */
data class ComputerIdentity(
  val phoneAccess: Boolean,
  val serverId: ServerId?,
  val name: String?,
  val apiVersion: Int,
  val serverTime: Long,
  val proofValid: Boolean?,
  val revoked: Boolean?,
)

data class ComputerHealth(
  val serverId: ServerId,
  val name: String,
  val libraryId: LibraryId,
  val apiVersion: Int,
  val minAppVersion: Int,
  val version: String,
  val serverTime: Long,
  val deviceId: DeviceId,
  val analyzing: Int,
  val inLine: Int,
  val lowOnSpace: Boolean,
  val capabilities: Capabilities,
)

data class Capabilities(
  val linkDownloads: Boolean,
  val downloaderVersion: String?,
  val dialogueSeparation: Boolean,
  val renditions: Boolean,
  val phoneVideo: Boolean,
  val resumableUploads: Boolean,
  val recordCompare: Boolean,
  val maxUploadBytes: Long,
  val maxChunkBytes: Long,
  val maxLinkDurationS: Long,
  val freeBytes: Long,
)

/** The pairing answer. [token] is the secret of architecture 20.3: never logged, cleared once it is encrypted. */
data class PairGrant(
  val deviceId: DeviceId,
  val token: CharArray,
  val serverId: ServerId,
  val name: String,
  val libraryId: LibraryId,
  val apiVersion: Int,
  val minAppVersion: Int,
  val serverTime: Long,
)

/** One event of the job channel (architecture 5.5). */
sealed interface ChannelEvent {
  data class Hello(val serverId: ServerId, val libraryId: LibraryId, val apiVersion: Int) : ChannelEvent

  data class Snapshot(val jobs: List<RemoteJob>) : ChannelEvent

  data class Job(val job: RemoteJob) : ChannelEvent

  data class Deleted(val id: JobId) : ChannelEvent
}
