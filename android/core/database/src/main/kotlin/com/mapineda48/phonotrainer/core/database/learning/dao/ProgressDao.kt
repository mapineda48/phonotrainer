package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mapineda48.phonotrainer.core.database.learning.entity.FeedPositionEntity
import kotlinx.coroutines.flow.Flow

/** A seen short and when it was last seen. */
data class SeenRow(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "first_segment") val firstSegment: Int,
  @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

/** When a clip was last watched (eviction order, architecture 7.5). */
data class WatchedRow(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
)

/**
 * Queries over `short_seen`, `clip_progress` and `feed_position` (architecture 4.3, 10; WP-14 owns every learning DAO).
 * Anchors are job + first segment; a later shorts rule maps them to the short whose phrases contain them.
 */
@Dao
abstract class ProgressDao {
  @Query("SELECT COUNT(*) FROM short_seen")
  abstract suspend fun count(): Int

  @Query("SELECT job_id, first_segment, last_seen_at FROM short_seen")
  abstract fun observeSeen(): Flow<List<SeenRow>>

  @Query("SELECT job_id, first_segment, last_seen_at FROM short_seen")
  abstract suspend fun seen(): List<SeenRow>

  @Query(
    "INSERT OR IGNORE INTO short_seen (job_id, first_segment, first_seen_at, last_seen_at, seen_count) " +
      "VALUES (:jobId, :firstSegment, :at, :at, 0)",
  )
  abstract suspend fun insertSeen(jobId: String, firstSegment: Int, at: Long)

  @Query(
    "UPDATE short_seen SET last_seen_at = :at, seen_count = seen_count + 1 " +
      "WHERE job_id = :jobId AND first_segment = :firstSegment",
  )
  abstract suspend fun bumpSeen(jobId: String, firstSegment: Int, at: Long)

  /** The short was seen (spec F-06 item 5) [at]: first seen kept, last seen and the count updated. */
  @Transaction
  open suspend fun markSeen(jobId: String, firstSegment: Int, at: Long) {
    insertSeen(jobId, firstSegment, at)
    bumpSeen(jobId, firstSegment, at)
  }

  @Query("SELECT seen_count FROM short_seen WHERE job_id = :jobId AND first_segment = :firstSegment")
  abstract suspend fun seenCount(jobId: String, firstSegment: Int): Int?

  @Query("INSERT OR IGNORE INTO clip_progress (job_id) VALUES (:jobId)")
  abstract suspend fun ensureClip(jobId: String)

  @Query("UPDATE clip_progress SET resume_first_segment = :firstSegment, resume_position_ms = :positionMs WHERE job_id = :jobId")
  abstract suspend fun updateResume(jobId: String, firstSegment: Int, positionMs: Long)

  @Query("UPDATE clip_progress SET last_opened_at = :at WHERE job_id = :jobId")
  abstract suspend fun updateOpened(jobId: String, at: Long)

  @Query("UPDATE clip_progress SET last_watched_at = :at WHERE job_id = :jobId")
  abstract suspend fun updateWatched(jobId: String, at: Long)

  /** Where the learner left the clip "In order" (spec F-06-AC10). */
  @Transaction
  open suspend fun setResume(jobId: String, firstSegment: Int, positionMs: Long) {
    ensureClip(jobId)
    updateResume(jobId, firstSegment, positionMs)
  }

  @Transaction
  open suspend fun setOpened(jobId: String, at: Long) {
    ensureClip(jobId)
    updateOpened(jobId, at)
  }

  @Transaction
  open suspend fun setWatched(jobId: String, at: Long) {
    ensureClip(jobId)
    updateWatched(jobId, at)
  }

  @Query("SELECT resume_first_segment FROM clip_progress WHERE job_id = :jobId")
  abstract fun observeResume(jobId: String): Flow<Int?>

  @Query("SELECT job_id FROM clip_progress WHERE last_opened_at >= :since")
  abstract suspend fun openedSince(since: Long): List<String>

  @Query("SELECT job_id, last_watched_at FROM clip_progress WHERE last_watched_at IS NOT NULL")
  abstract suspend fun lastWatched(): List<WatchedRow>

  @Upsert
  abstract suspend fun saveSlot(position: FeedPositionEntity)

  @Query("SELECT * FROM feed_position WHERE slot = :slot")
  abstract suspend fun slot(slot: String): FeedPositionEntity?

  @Query("SELECT * FROM feed_position WHERE slot = :slot")
  abstract fun observeSlot(slot: String): Flow<FeedPositionEntity?>

  @Query("DELETE FROM short_seen WHERE job_id IN (:jobIds)")
  abstract suspend fun deleteSeen(jobIds: List<String>)

  @Query("DELETE FROM clip_progress WHERE job_id IN (:jobIds)")
  abstract suspend fun deleteClipProgress(jobIds: List<String>)

  @Query("DELETE FROM feed_position WHERE job_id IN (:jobIds)")
  abstract suspend fun deleteSlots(jobIds: List<String>)

  /** Clips deleted on the computer leave no seen state, resume point or feed position behind (architecture 7.7). */
  @Transaction
  open suspend fun forgetClips(jobIds: List<String>) {
    for (chunk in jobIds.chunked(MAX_ARGS)) {
      deleteSeen(chunk)
      deleteClipProgress(chunk)
      deleteSlots(chunk)
    }
  }

  companion object {
    /** The Watch tab's last mode and position (spec 5.2 item 5). */
    const val WATCH_LAST = "WATCH_LAST"

    /** Home's "Keep watching": the last position outside today's set (spec F-03 item 4). */
    const val KEEP_WATCHING = "KEEP_WATCHING"

    /** Bound parameters per statement, under SQLite's oldest limit of 999. */
    const val MAX_ARGS = 500
  }
}
