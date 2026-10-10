package com.mapineda48.phonotrainer.core.database.library.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * `short` (architecture 4.2, 9.2): derived by the indexer, rebuilt when `index_rule_version` changes. The
 * `default_spot_*` columns are null for a short without a spotlight.
 */
@Entity(
  tableName = "short",
  primaryKeys = ["job_id", "first_segment"],
  foreignKeys = [
    ForeignKey(
      entity = ClipEntity::class,
      parentColumns = ["job_id"],
      childColumns = ["job_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["job_id", "ordinal"])],
)
data class ShortEntity(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "first_segment") val firstSegment: Int,
  @ColumnInfo(name = "rule_version") val ruleVersion: Int,
  @ColumnInfo(name = "ordinal") val ordinal: Int,
  @ColumnInfo(name = "last_segment") val lastSegment: Int,
  /** Phrase bounds. */
  @ColumnInfo(name = "start_ms") val startMs: Long,
  @ColumnInfo(name = "end_ms") val endMs: Long,
  @ColumnInfo(name = "play_start_ms") val playStartMs: Long,
  @ColumnInfo(name = "play_end_ms") val playEndMs: Long,
  @ColumnInfo(name = "default_spot_segment") val defaultSpotSegment: Int? = null,
  @ColumnInfo(name = "default_spot_word") val defaultSpotWord: Int? = null,
  @ColumnInfo(name = "default_spot_change") val defaultSpotChange: String? = null,
  @ColumnInfo(name = "default_spot_advice") val defaultSpotAdvice: String? = null,
  @ColumnInfo(name = "default_spot_crosses") val defaultSpotCrosses: Boolean? = null,
  @ColumnInfo(name = "marked_words") val markedWords: Int,
)

/** `occurrence` (architecture 4.2, 9.4): one row per word × change of a clip, derived by the indexer. */
@Entity(
  tableName = "occurrence",
  primaryKeys = ["job_id", "segment", "word", "change"],
  foreignKeys = [
    ForeignKey(
      entity = ClipEntity::class,
      parentColumns = ["job_id"],
      childColumns = ["job_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["change", "eligible", "diff_cost"]),
    Index(value = ["job_id", "short_first_segment"]),
    Index(value = ["word_key"]),
  ],
)
data class OccurrenceEntity(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "segment") val segment: Int,
  @ColumnInfo(name = "word") val word: Int,
  @ColumnInfo(name = "change") val change: String,
  @ColumnInfo(name = "short_first_segment") val shortFirstSegment: Int,
  @ColumnInfo(name = "word_text") val wordText: String,
  /** Lower-case letters and apostrophes, for "one per word". */
  @ColumnInfo(name = "word_key") val wordKey: String,
  @ColumnInfo(name = "dict_ipa") val dictIpa: String? = null,
  @ColumnInfo(name = "realized_ipa") val realizedIpa: String? = null,
  @ColumnInfo(name = "start_ms") val startMs: Long,
  @ColumnInfo(name = "end_ms") val endMs: Long,
  @ColumnInfo(name = "diff_cost") val diffCost: Double,
  @ColumnInfo(name = "low_confidence") val lowConfidence: Boolean,
  /** Not low confidence, at least 60 ms, and with a heard form. */
  @ColumnInfo(name = "eligible") val eligible: Boolean,
  @ColumnInfo(name = "crosses_boundary") val crossesBoundary: Boolean,
  @ColumnInfo(name = "next_word_text") val nextWordText: String? = null,
  @ColumnInfo(name = "advice") val advice: String,
  @ColumnInfo(name = "family") val family: String,
)
