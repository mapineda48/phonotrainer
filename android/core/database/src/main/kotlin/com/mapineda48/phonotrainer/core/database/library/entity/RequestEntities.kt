package com.mapineda48.phonotrainer.core.database.library.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `request` (architecture 4.2, 8): the device queue. Kind, state and the creation and update times are NOT NULL; the
 * counters and 0/1 flags are NOT NULL DEFAULT 0 (decision I-17); timeline times and what a kind does not use are
 * nullable.
 */
@Entity(
  tableName = "request",
  indices = [
    Index(value = ["state"]),
    Index(value = ["job_id"], unique = true),
    Index(value = ["link_key"]),
    Index(value = ["file_sha256"]),
    Index(value = ["seq"], unique = true),
  ],
)
data class RequestEntity(
  /** Client UUID, the first idempotency key. */
  @PrimaryKey @ColumnInfo(name = "id") val id: String,
  /** Creation order (max + 1 inside the insert transaction). */
  @ColumnInfo(name = "seq") val seq: Long,
  /** LINK, FILE. */
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "state") val state: String,
  /** For COPY_FAILED and COPY_MISSING, the phone-side code (architecture 8.3). */
  @ColumnInfo(name = "state_reason") val stateReason: String? = null,
  /** "Stop sending" keeps it waiting until "Send now". */
  @ColumnInfo(name = "held", defaultValue = "0") val held: Boolean = false,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  @ColumnInfo(name = "link_shared") val linkShared: String? = null,
  @ColumnInfo(name = "link_sent") val linkSent: String? = null,
  @ColumnInfo(name = "link_key") val linkKey: String? = null,
  @ColumnInfo(name = "site") val site: String? = null,
  @ColumnInfo(name = "title_hint") val titleHint: String? = null,
  @ColumnInfo(name = "file_name") val fileName: String? = null,
  @ColumnInfo(name = "file_mime") val fileMime: String? = null,
  @ColumnInfo(name = "file_bytes") val fileBytes: Long? = null,
  @ColumnInfo(name = "file_duration_ms") val fileDurationMs: Long? = null,
  /** Computed while copying. */
  @ColumnInfo(name = "file_sha256") val fileSha256: String? = null,
  /** Always `requests/<id>/source.<ext>`, never a name from the sharing app. */
  @ColumnInfo(name = "copy_file") val copyFile: String? = null,
  @ColumnInfo(name = "copy_bytes") val copyBytes: Long? = null,
  @ColumnInfo(name = "copy_complete", defaultValue = "0") val copyComplete: Boolean = false,
  @ColumnInfo(name = "source_uri") val sourceUri: String? = null,
  @ColumnInfo(name = "source_persisted", defaultValue = "0") val sourcePersisted: Boolean = false,
  @ColumnInfo(name = "audio_extracted", defaultValue = "0") val audioExtracted: Boolean = false,
  @ColumnInfo(name = "whole_file_fallback", defaultValue = "0") val wholeFileFallback: Boolean = false,
  @ColumnInfo(name = "audio_only", defaultValue = "0") val audioOnly: Boolean = false,
  @ColumnInfo(name = "separate_dialogue", defaultValue = "0") val separateDialogue: Boolean = false,
  /** Null until a computer is paired. */
  @ColumnInfo(name = "target_server_id") val targetServerId: String? = null,
  @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
  @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,
  @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
  @ColumnInfo(name = "last_error_message") val lastErrorMessage: String? = null,
  /** The space a refused copy needs ("Free {N} MB", architecture 8.3). */
  @ColumnInfo(name = "needed_bytes") val neededBytes: Long? = null,
  @ColumnInfo(name = "job_id") val jobId: String? = null,
  @ColumnInfo(name = "accepted_at") val acceptedAt: Long? = null,
  @ColumnInfo(name = "rerun_count", defaultValue = "0") val rerunCount: Int = 0,
  @ColumnInfo(name = "auto_rerun_used", defaultValue = "0") val autoRerunUsed: Boolean = false,
  /** Bytes the computer confirmed (v1-complete). */
  @ColumnInfo(name = "upload_offset") val uploadOffset: Long? = null,
  @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
  @ColumnInfo(name = "in_line_at") val inLineAt: Long? = null,
  @ColumnInfo(name = "analyzing_at") val analyzingAt: Long? = null,
  @ColumnInfo(name = "ready_at") val readyAt: Long? = null,
  @ColumnInfo(name = "on_phone_at") val onPhoneAt: Long? = null,
  @ColumnInfo(name = "notified_ready", defaultValue = "0") val notifiedReady: Boolean = false,
  @ColumnInfo(name = "notified_attention", defaultValue = "0") val notifiedAttention: Boolean = false,
)

/**
 * `request_op` (architecture 4.2): the outbox of commands that must reach the computer. [requestId] has no foreign
 * key: a REMOVE_JOB outlives its request row. "Forget this computer" is never queued (6.3). Kind, state and
 * [createdAt] are NOT NULL, [state] starts PENDING and [attempts] at 0 (decision I-17).
 */
@Entity(tableName = "request_op", indices = [Index(value = ["state", "created_at"])])
data class RequestOpEntity(
  /** UUID; for RERUN it is the re-run's idempotency key. */
  @PrimaryKey @ColumnInfo(name = "id") val id: String,
  /** CANCEL, REMOVE_JOB, RERUN. */
  @ColumnInfo(name = "kind") val kind: String,
  @ColumnInfo(name = "request_id") val requestId: String? = null,
  @ColumnInfo(name = "job_id") val jobId: String? = null,
  /** RERUN only (architecture 4.2: NULL for the other kinds). */
  @ColumnInfo(name = "audio_only") val audioOnly: Boolean? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
  @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,
  /** PENDING, DONE, FAILED. */
  @ColumnInfo(name = "state", defaultValue = STATE_PENDING) val state: String = STATE_PENDING,
  @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
) {
  companion object {
    /** The state of an operation that has not reached the computer yet. */
    const val STATE_PENDING = "PENDING"
  }
}

/**
 * `sync_meta` (architecture 4.2): key/value. Last listing time, the last complete listing's server and library ids,
 * the cached reference API version, the pending mass-deletion prompt, the last reindex rule version.
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
  @PrimaryKey @ColumnInfo(name = "key") val key: String,
  @ColumnInfo(name = "value") val value: String? = null,
)
