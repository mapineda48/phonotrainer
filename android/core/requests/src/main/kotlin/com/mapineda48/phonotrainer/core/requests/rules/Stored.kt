package com.mapineda48.phonotrainer.core.requests.rules

import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState

// The values the device queue stores (architecture 4.2): kinds and states by enum name, the outbox's kinds and states,
// and the error codes the queue reasons about (server-contract 8.1 and 8.2, architecture 8.3).

/** Outbox operations (architecture 4.2): "Forget this computer" is never queued. */
internal enum class OpKind { CANCEL, REMOVE_JOB, RERUN }

/** Where an outbox operation is: waiting, answered, or given up after a defect (logged). */
internal enum class OpState { PENDING, DONE, FAILED }

internal fun RequestState.stored(): String = name

internal fun RequestKind.stored(): String = name

internal fun storedState(value: String): RequestState =
  RequestState.entries.firstOrNull { it.name == value } ?: RequestState.NEEDS_ATTENTION

internal fun storedKind(value: String): RequestKind =
  RequestKind.entries.firstOrNull { it.name == value } ?: RequestKind.LINK

/** The phone-side copy codes (architecture 8.3): stored in `state_reason`, never sent to the computer. */
internal object CopyCodes {
  const val NO_SPACE = "copy_no_space"
  const val UNREADABLE = "copy_unreadable"
  const val INTERRUPTED = "copy_interrupted"
  const val MISSING = "copy_missing"
}

/** The codes of server-contract 8.1 and 8.2 the queue's rules name. */
internal object Codes {
  // Request-level (8.1)
  const val CREDENTIAL_REJECTED = "credential_rejected"
  const val CLOCK_SKEW = "clock_skew"
  const val PHONE_ACCESS_OFF = "phone_access_off"
  const val LAN_FORBIDDEN = "lan_forbidden"
  const val APP_TOO_OLD = "app_too_old"
  const val TOO_LARGE = "too_large"
  const val UNSUPPORTED_TYPE = "unsupported_type"
  const val INVALID_URL = "invalid_url"
  const val REFUSED_ADDRESS = "refused_address"
  const val CANNOT_CHECK_LINK = "cannot_check_link"
  const val NO_SPACE = "no_space"
  const val KEY_CONFLICT = "key_conflict"
  const val UPLOAD_BUSY = "upload_busy"
  const val UNKNOWN_UPLOAD = "unknown_upload"
  const val UNKNOWN_JOB = "unknown_job"
  const val LENGTH_MISMATCH = "length_mismatch"
  const val CHECKSUM_MISMATCH = "checksum_mismatch"
  const val ALREADY_FINISHED = "already_finished"
  const val SOURCE_GONE = "source_gone"
  const val RATE_LIMITED = "rate_limited"
  const val SERVER_ERROR = "server_error"
  const val ARTIFACT_NOT_READY = "artifact_not_ready"
  const val OFFSET_MISMATCH = "offset_mismatch"
  const val STORE_UNAVAILABLE = "store_unavailable"

  // Job failures (8.2)
  const val CANCELLED = "cancelled"
  const val INTERRUPTED = "interrupted"
  const val LIVE_STREAM = "live_stream"
  const val TOO_LONG = "too_long"
  const val PLAYLIST = "playlist"
  const val DOWNLOAD_FORBIDDEN = "download_forbidden"
  const val DOWNLOAD_UNAVAILABLE = "download_unavailable"
  const val UNSUPPORTED_SITE = "unsupported_site"
  const val NETWORK = "network"
  const val TOOL_MISSING = "tool_missing"
  const val NOT_MEDIA = "not_media"
  const val EXTRACT_FAILED = "extract_failed"
  const val PROCESSING_FAILED = "processing_failed"

  /** A failure without a code the phone could read. */
  const val UNKNOWN = "processing_failed"
}
