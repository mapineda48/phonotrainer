package com.mapineda48.phonotrainer.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The wire shapes of server-contract 6 and 7, field by field (architecture 5.4). Every field is optional with a
// default: decoding never fails on a missing or unknown field, and enum-valued fields are plain strings mapped
// leniently by the mappers (an unknown value is UNKNOWN, NFR-08). Required fields are enforced by the mappers, which
// also validate ids, names, hex and URLs before anything reaches the domain (security review M-3).

/** The client's one JSON configuration (architecture 5.4). */
internal val ComputerJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  coerceInputValues = true
}

// --- 6.2 Identity, pairing, health ------------------------------------------------------------------------------

@Serializable
internal data class HelloDto(
  val service: String? = null,
  @SerialName("phone_access") val phoneAccess: Boolean? = null,
  @SerialName("server_id") val serverId: String? = null,
  val name: String? = null,
  @SerialName("api_version") val apiVersion: Int? = null,
  @SerialName("server_time") val serverTime: Long? = null,
  val proof: String? = null,
  val revoked: Boolean? = null,
)

/** `GET /api/health` of a server older than API 6: only its `api_version` is read (D-15). */
@Serializable
internal data class LegacyHealthDto(@SerialName("api_version") val apiVersion: Int? = null)

@Serializable
internal data class PairRequestDto(
  val code: String,
  @SerialName("device_name") val deviceName: String,
  @SerialName("app_version") val appVersion: Int,
  @SerialName("app_version_name") val appVersionName: String,
  @SerialName("previous_device_id") val previousDeviceId: String? = null,
  @SerialName("previous_token_proof") val previousTokenProof: String? = null,
)

@Serializable
internal data class PairGrantDto(
  @SerialName("device_id") val deviceId: String? = null,
  val token: String? = null,
  @SerialName("server_id") val serverId: String? = null,
  val name: String? = null,
  @SerialName("library_id") val libraryId: String? = null,
  @SerialName("api_version") val apiVersion: Int? = null,
  @SerialName("min_app_version") val minAppVersion: Int? = null,
  @SerialName("server_time") val serverTime: Long? = null,
)

@Serializable
internal data class HealthDto(
  @SerialName("server_id") val serverId: String? = null,
  val name: String? = null,
  @SerialName("library_id") val libraryId: String? = null,
  @SerialName("api_version") val apiVersion: Int? = null,
  @SerialName("min_app_version") val minAppVersion: Int? = null,
  val version: String? = null,
  @SerialName("server_time") val serverTime: Long? = null,
  val device: HealthDeviceDto? = null,
  val queue: QueueDto? = null,
  @SerialName("low_on_space") val lowOnSpace: Boolean? = null,
  val capabilities: CapabilitiesDto? = null,
)

@Serializable
internal data class HealthDeviceDto(
  @SerialName("device_id") val deviceId: String? = null,
  val name: String? = null,
)

@Serializable
internal data class QueueDto(
  val analyzing: Int? = null,
  @SerialName("in_line") val inLine: Int? = null,
)

@Serializable
internal data class CapabilitiesDto(
  @SerialName("link_downloads") val linkDownloads: Boolean? = null,
  val downloader: DownloaderDto? = null,
  @SerialName("dialogue_separation") val dialogueSeparation: Boolean? = null,
  val renditions: Boolean? = null,
  @SerialName("phone_video") val phoneVideo: Boolean? = null,
  @SerialName("resumable_uploads") val resumableUploads: Boolean? = null,
  @SerialName("record_compare") val recordCompare: Boolean? = null,
  @SerialName("max_upload_bytes") val maxUploadBytes: Long? = null,
  @SerialName("max_chunk_bytes") val maxChunkBytes: Long? = null,
  @SerialName("max_link_duration_s") val maxLinkDurationS: Long? = null,
  @SerialName("free_bytes") val freeBytes: Long? = null,
)

@Serializable
internal data class DownloaderDto(val name: String? = null, val version: String? = null)

// --- 6.3 The listing and the job --------------------------------------------------------------------------------

@Serializable
internal data class LibraryDto(
  @SerialName("api_version") val apiVersion: Int? = null,
  @SerialName("server_id") val serverId: String? = null,
  @SerialName("library_id") val libraryId: String? = null,
  @SerialName("generated_at") val generatedAt: String? = null,
  val jobs: List<JobDto>? = null,
)

@Serializable
internal data class JobDto(
  val id: String? = null,
  val revision: String? = null,
  val status: String? = null,
  val stage: String? = null,
  val progress: ProgressDto? = null,
  val separation: String? = null,
  @SerialName("queue_position") val queuePosition: Int? = null,
  val title: String? = null,
  val source: SourceDto? = null,
  val options: OptionsDto? = null,
  @SerialName("device_id") val deviceId: String? = null,
  val own: Boolean? = null,
  @SerialName("client_request_id") val clientRequestId: String? = null,
  @SerialName("rerun_of") val rerunOf: String? = null,
  val created: String? = null,
  val started: String? = null,
  val finished: String? = null,
  val error: JobErrorDto? = null,
  val summary: SummaryDto? = null,
  val analysis: AnalysisDto? = null,
  val media: MediaDto? = null,
  val artifacts: List<ArtifactDto>? = null,
)

@Serializable
internal data class ProgressDto(
  val percent: Int? = null,
  val segment: Int? = null,
  val segments: Int? = null,
  @SerialName("download_percent") val downloadPercent: Int? = null,
)

@Serializable
internal data class SourceDto(
  val kind: String? = null,
  val url: String? = null,
  val site: String? = null,
  @SerialName("file_name") val fileName: String? = null,
)

@Serializable
internal data class OptionsDto(
  @SerialName("audio_only") val audioOnly: Boolean? = null,
  @SerialName("separate_dialogue") val separateDialogue: Boolean? = null,
)

@Serializable
internal data class JobErrorDto(
  val code: String? = null,
  val message: String? = null,
  @SerialName("downloader_version") val downloaderVersion: String? = null,
)

@Serializable
internal data class SummaryDto(
  @SerialName("duration_s") val durationS: Double? = null,
  val segments: Int? = null,
  val words: Int? = null,
  @SerialName("phenomena_counts") val phenomenaCounts: Map<String, Int>? = null,
)

@Serializable
internal data class AnalysisDto(
  @SerialName("rules_version") val rulesVersion: Int? = null,
  @SerialName("phone_engine") val phoneEngine: String? = null,
  @SerialName("dialogue_separation") val dialogueSeparation: Boolean? = null,
)

@Serializable
internal data class MediaDto(
  val kind: String? = null,
  val codec: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  val rotation: Int? = null,
  @SerialName("start_offset_ms") val startOffsetMs: Long? = null,
  @SerialName("duration_s") val durationS: Double? = null,
)

@Serializable
internal data class ArtifactDto(
  val name: String? = null,
  val role: String? = null,
  val status: String? = null,
  val bytes: Long? = null,
  val sha256: String? = null,
  val revision: String? = null,
  @SerialName("content_type") val contentType: String? = null,
  val url: String? = null,
  val error: String? = null,
  val video: VideoDto? = null,
)

@Serializable
internal data class VideoDto(val codec: String? = null, val width: Int? = null, val height: Int? = null)

/** `{"job": PhoneJob}` answers, with `created` (links) or `replaced` (re-runs) beside it. */
@Serializable
internal data class JobEnvelopeDto(val job: JobDto? = null)

// --- 6.5 to 6.7 Requests ------------------------------------------------------------------------------------------

@Serializable
internal data class LinkCreateDto(
  @SerialName("client_request_id") val clientRequestId: String,
  val url: String,
  @SerialName("audio_only") val audioOnly: Boolean,
  @SerialName("separate_dialogue") val separateDialogue: Boolean? = null,
)

@Serializable
internal data class UploadDeclarationDto(
  @SerialName("client_request_id") val clientRequestId: String,
  @SerialName("file_name") val fileName: String,
  val bytes: Long,
  val sha256: String,
  @SerialName("separate_dialogue") val separateDialogue: Boolean? = null,
)

@Serializable
internal data class UploadStateDto(
  val state: String? = null,
  val upload: UploadSessionDto? = null,
  val job: JobDto? = null,
)

@Serializable
internal data class UploadSessionDto(
  @SerialName("client_request_id") val clientRequestId: String? = null,
  val offset: Long? = null,
  val bytes: Long? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
  @SerialName("max_chunk_bytes") val maxChunkBytes: Long? = null,
  val resumable: Boolean? = null,
)

@Serializable
internal data class RerunDto(
  @SerialName("client_request_id") val clientRequestId: String,
  @SerialName("audio_only") val audioOnly: Boolean? = null,
)

/** `{"status": …}` of the phone-video request (6.4.4, complete). */
@Serializable
internal data class RenditionStatusDto(val status: String? = null)

// --- 7 The job channel ------------------------------------------------------------------------------------------

@Serializable
internal data class ChannelFrameDto(
  val type: String? = null,
  @SerialName("server_id") val serverId: String? = null,
  @SerialName("library_id") val libraryId: String? = null,
  @SerialName("api_version") val apiVersion: Int? = null,
  val jobs: List<JobDto>? = null,
  val job: JobDto? = null,
  val id: String? = null,
)
