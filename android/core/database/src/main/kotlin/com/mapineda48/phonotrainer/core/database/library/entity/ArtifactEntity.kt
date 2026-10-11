package com.mapineda48.phonotrainer.core.database.library.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * `artifact` (architecture 4.2): one row per served file of a clip, with its local copy and resume state. The role, both
 * states and [updatedAt] are NOT NULL, [localState] starts ABSENT and [verifyFailures] at 0 (decision I-17).
 */
@Entity(
  tableName = "artifact",
  primaryKeys = ["job_id", "name"],
  foreignKeys = [
    ForeignKey(
      entity = ClipEntity::class,
      parentColumns = ["job_id"],
      childColumns = ["job_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["local_state"]), Index(value = ["role", "local_state"])],
)
data class ArtifactEntity(
  @ColumnInfo(name = "job_id") val jobId: String,
  /** `analysis`, `audio_mix`, `audio_dialogue`, `original`; v1-complete renditions (server-contract 6.4). */
  @ColumnInfo(name = "name") val name: String,
  /** CLIP_DATA, AUDIO_MIX, AUDIO_DIALOGUE, VIDEO, POSTER. */
  @ColumnInfo(name = "role") val role: String,
  /** READY, PREPARING, FAILED, UNAVAILABLE. */
  @ColumnInfo(name = "remote_status") val remoteStatus: String,
  @ColumnInfo(name = "remote_bytes") val remoteBytes: Long? = null,
  @ColumnInfo(name = "remote_sha256") val remoteSha256: String? = null,
  @ColumnInfo(name = "remote_type") val remoteType: String? = null,
  @ColumnInfo(name = "remote_url") val remoteUrl: String? = null,
  /** ABSENT, QUEUED, DOWNLOADING, PAUSED, READY, FAILED. */
  @ColumnInfo(name = "local_state", defaultValue = LOCAL_ABSENT) val localState: String = LOCAL_ABSENT,
  /** Path relative to `filesDir` (architecture 4.6). */
  @ColumnInfo(name = "local_file") val localFile: String? = null,
  @ColumnInfo(name = "local_sha256") val localSha256: String? = null,
  @ColumnInfo(name = "local_bytes") val localBytes: Long? = null,
  @ColumnInfo(name = "part_bytes") val partBytes: Long? = null,
  @ColumnInfo(name = "part_revision") val partRevision: String? = null,
  @ColumnInfo(name = "etag") val etag: String? = null,
  @ColumnInfo(name = "verify_failures", defaultValue = "0") val verifyFailures: Int = 0,
  @ColumnInfo(name = "pause_reason") val pauseReason: String? = null,
  @ColumnInfo(name = "last_error") val lastError: String? = null,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
  companion object {
    /** The local state of a file nothing was downloaded for yet. */
    const val LOCAL_ABSENT = "ABSENT"
  }
}
