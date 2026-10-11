package com.mapineda48.phonotrainer.core.database.learning.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// LearningDatabase, version 1 (architecture 4.3). Rows reference clips by job id (+ segment + word) only, so a restore
// on a new phone re-attaches them when the clips sync again from the same computer. Columns the architecture lists
// without a type are nullable.

/** `short_seen`: seen state per short, anchored by job + first segment. */
@Entity(tableName = "short_seen", primaryKeys = ["job_id", "first_segment"])
data class ShortSeenEntity(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "first_segment") val firstSegment: Int,
  @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long? = null,
  @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long? = null,
  @ColumnInfo(name = "seen_count") val seenCount: Int? = null,
)

/** `clip_progress`: where the learner left a clip. */
@Entity(tableName = "clip_progress")
data class ClipProgressEntity(
  @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "resume_first_segment") val resumeFirstSegment: Int? = null,
  @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long? = null,
  @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
  @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long? = null,
  @ColumnInfo(name = "excluded_from_mix") val excludedFromMix: Boolean? = null,
)

/** `feed_position`: the slots WATCH_LAST and KEEP_WATCHING. */
@Entity(tableName = "feed_position")
data class FeedPositionEntity(
  @PrimaryKey @ColumnInfo(name = "slot") val slot: String,
  @ColumnInfo(name = "mode_json") val modeJson: String? = null,
  @ColumnInfo(name = "job_id") val jobId: String? = null,
  @ColumnInfo(name = "first_segment") val firstSegment: Int? = null,
  @ColumnInfo(name = "position_ms") val positionMs: Long? = null,
  @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
)

/** `saved_item`: saved shorts and words; one row per kind and anchor. */
@Entity(
  tableName = "saved_item",
  indices = [Index(value = ["kind", "job_id", "segment", "word"], unique = true)],
)
data class SavedItemEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
  /** SHORT, WORD. */
  @ColumnInfo(name = "kind") val kind: String? = null,
  @ColumnInfo(name = "job_id") val jobId: String? = null,
  @ColumnInfo(name = "segment") val segment: Int? = null,
  @ColumnInfo(name = "word") val word: Int? = null,
  @ColumnInfo(name = "short_first_segment") val shortFirstSegment: Int? = null,
  @ColumnInfo(name = "change") val change: String? = null,
  @ColumnInfo(name = "saved_at") val savedAt: Long? = null,
  @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long? = null,
)

/** `practice_stat`: the web's per-change accuracy. */
@Entity(tableName = "practice_stat")
data class PracticeStatEntity(
  @PrimaryKey @ColumnInfo(name = "change") val change: String,
  @ColumnInfo(name = "seen") val seen: Int? = null,
  @ColumnInfo(name = "correct") val correct: Int? = null,
)

/** `change_met`: when the learner first met each change. */
@Entity(tableName = "change_met")
data class ChangeMetEntity(
  @PrimaryKey @ColumnInfo(name = "change") val change: String,
  @ColumnInfo(name = "first_met_at") val firstMetAt: Long? = null,
)

/** `study_set`: today's set and extra sets (F-08); a set never changes once generated. */
@Entity(tableName = "study_set")
data class StudySetEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
  /** TODAY, EXTRA. */
  @ColumnInfo(name = "kind") val kind: String? = null,
  @ColumnInfo(name = "local_date") val localDate: String? = null,
  @ColumnInfo(name = "seed") val seed: Long? = null,
  @ColumnInfo(name = "rule_version") val ruleVersion: Int? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long? = null,
  @ColumnInfo(name = "cursor") val cursor: Int? = null,
  @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
  @ColumnInfo(name = "answered") val answered: Int? = null,
  @ColumnInfo(name = "right") val right: Int? = null,
)

/** `study_set_item`: the shorts and questions of a set, in order. */
@Entity(
  tableName = "study_set_item",
  primaryKeys = ["set_id", "position"],
  foreignKeys = [
    ForeignKey(
      entity = StudySetEntity::class,
      parentColumns = ["id"],
      childColumns = ["set_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
data class StudySetItemEntity(
  @ColumnInfo(name = "set_id") val setId: Long,
  @ColumnInfo(name = "position") val position: Int,
  /** SHORT, QUESTION. */
  @ColumnInfo(name = "kind") val kind: String? = null,
  @ColumnInfo(name = "job_id") val jobId: String? = null,
  @ColumnInfo(name = "first_segment") val firstSegment: Int? = null,
  @ColumnInfo(name = "spot_segment") val spotSegment: Int? = null,
  @ColumnInfo(name = "spot_word") val spotWord: Int? = null,
  @ColumnInfo(name = "spot_change") val spotChange: String? = null,
  @ColumnInfo(name = "saved_tag") val savedTag: Boolean? = null,
  @ColumnInfo(name = "question_json") val questionJson: String? = null,
  @ColumnInfo(name = "choice") val choice: String? = null,
  @ColumnInfo(name = "correct") val correct: Boolean? = null,
  @ColumnInfo(name = "dropped") val dropped: Boolean? = null,
)

/** `practice_session`: the current Practice session (F-17). */
@Entity(tableName = "practice_session")
data class PracticeSessionEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
  @ColumnInfo(name = "focus") val focus: String? = null,
  @ColumnInfo(name = "weak_spots") val weakSpots: Boolean? = null,
  @ColumnInfo(name = "seed") val seed: Long? = null,
  @ColumnInfo(name = "questions_json") val questionsJson: String? = null,
  @ColumnInfo(name = "index") val index: Int? = null,
  @ColumnInfo(name = "answers_json") val answersJson: String? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long? = null,
  @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)

/**
 * `learning_meta` (CR-01-A1, architecture 4.3): facts about this learning history, by key. One row in v1:
 * `learning_id`, a random UUID written when the database is created (`LearningDatabase.LEARNING_ID_CALLBACK`) and never
 * changed. It is backed up with the progress it names; in v1-complete it names this history on every computer it is
 * shared with (architecture 7.8). No DAO reads it in v1-core.
 */
@Entity(tableName = "learning_meta")
data class LearningMetaEntity(
  @PrimaryKey @ColumnInfo(name = "key") val key: String,
  @ColumnInfo(name = "value") val value: String? = null,
)
