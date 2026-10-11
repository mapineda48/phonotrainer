package com.mapineda48.phonotrainer.core.database.library.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `clip` (architecture 4.2): the catalogue mirror, one row per job any listing of the paired server reported, plus
 * clips kept from a forgotten computer or a previous library. Instants are epoch milliseconds (UTC); enums are stored
 * by name. Besides the columns the architecture declares NOT NULL, [firstSeenAt] (written with the row) is NOT NULL
 * and the 0/1 flags are NOT NULL DEFAULT 0 (decision I-17); what the computer reports stays nullable, because an
 * older listing may leave it out.
 */
@Entity(
  tableName = "clip",
  indices = [
    Index(value = ["server_id", "library_id"]),
    Index(value = ["status"]),
    Index(value = ["created_at", "job_id"]),
    Index(value = ["origin"]),
  ],
)
data class ClipEntity(
  @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "server_id") val serverId: String,
  @ColumnInfo(name = "library_id") val libraryId: String,
  /** ACTIVE, FORGOTTEN_COMPUTER, OTHER_LIBRARY. */
  @ColumnInfo(name = "origin") val origin: String,
  /** The listing revision. */
  @ColumnInfo(name = "revision") val revision: String,
  @ColumnInfo(name = "status") val status: String,
  @ColumnInfo(name = "stage") val stage: String? = null,
  @ColumnInfo(name = "percent") val percent: Int? = null,
  @ColumnInfo(name = "queue_position") val queuePosition: Int? = null,
  @ColumnInfo(name = "title") val title: String,
  @ColumnInfo(name = "source_kind") val sourceKind: String? = null,
  @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
  @ColumnInfo(name = "site") val site: String? = null,
  /** Device id, or null for browser and command-line jobs. */
  @ColumnInfo(name = "submitted_by_device") val submittedByDevice: String? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long? = null,
  @ColumnInfo(name = "started_at") val startedAt: Long? = null,
  @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
  @ColumnInfo(name = "error_code") val errorCode: String? = null,
  @ColumnInfo(name = "error_message") val errorMessage: String? = null,
  @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
  @ColumnInfo(name = "segment_count") val segmentCount: Int? = null,
  @ColumnInfo(name = "word_count") val wordCount: Int? = null,
  @ColumnInfo(name = "change_counts_json") val changeCountsJson: String? = null,
  @ColumnInfo(name = "rules_version") val rulesVersion: Int? = null,
  @ColumnInfo(name = "phone_engine") val phoneEngine: String? = null,
  @ColumnInfo(name = "has_dialogue", defaultValue = "0") val hasDialogue: Boolean = false,
  @ColumnInfo(name = "audio_only_job", defaultValue = "0") val audioOnlyJob: Boolean = false,
  /** VIDEO, AUDIO, NONE (from the listing's `media`). */
  @ColumnInfo(name = "media_kind") val mediaKind: String? = null,
  @ColumnInfo(name = "video_codec") val videoCodec: String? = null,
  @ColumnInfo(name = "video_width") val videoWidth: Int? = null,
  @ColumnInfo(name = "video_height") val videoHeight: Int? = null,
  @ColumnInfo(name = "video_rotation") val videoRotation: Int? = null,
  @ColumnInfo(name = "video_offset_ms") val videoOffsetMs: Long? = null,
  /** Consecutive complete listings without this job (architecture 7.9). */
  @ColumnInfo(name = "missing_listings", defaultValue = "0") val missingListings: Int = 0,
  /** Removal deferred while open. */
  @ColumnInfo(name = "deleted_on_computer", defaultValue = "0") val deletedOnComputer: Boolean = false,
  @ColumnInfo(name = "wanted", defaultValue = "0") val wanted: Boolean = false,
  @ColumnInfo(name = "removed_by_learner", defaultValue = "0") val removedByLearner: Boolean = false,
  @ColumnInfo(name = "kept", defaultValue = "0") val kept: Boolean = false,
  @ColumnInfo(name = "hidden", defaultValue = "0") val hidden: Boolean = false,
  /** "Download the video" when automatic video download is off. */
  @ColumnInfo(name = "video_wanted", defaultValue = "0") val videoWanted: Boolean = false,
  @ColumnInfo(name = "indexed_revision") val indexedRevision: String? = null,
  @ColumnInfo(name = "index_rule_version") val indexRuleVersion: Int? = null,
  /** When this phone first listed the clip ("New on this phone"); written with the row. */
  @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
  @ColumnInfo(name = "on_phone_at") val onPhoneAt: Long? = null,
)
