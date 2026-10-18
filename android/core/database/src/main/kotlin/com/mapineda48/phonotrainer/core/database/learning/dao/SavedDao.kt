package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mapineda48.phonotrainer.core.database.learning.entity.SavedItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Queries over `saved_item` (architecture 4.3; spec F-09; WP-14 owns every learning DAO). One row per kind and anchor:
 * a SHORT by its first phrase with `word` = [SavedItemEntity.NO_WORD], a WORD by its phrase and word. Kinds: [SHORT],
 * [WORD].
 */
@Dao
abstract class SavedDao {
  @Query("SELECT COUNT(*) FROM saved_item")
  abstract suspend fun count(): Int

  /** Most recently saved first (spec F-09 item 3). */
  @Query("SELECT * FROM saved_item ORDER BY saved_at DESC, id DESC")
  abstract fun observeAll(): Flow<List<SavedItemEntity>>

  @Query("SELECT * FROM saved_item ORDER BY saved_at DESC, id DESC")
  abstract suspend fun all(): List<SavedItemEntity>

  /** Saves [item] unless the same anchor is saved already; returns the new id, or -1 when it was. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract suspend fun insert(item: SavedItemEntity): Long

  @Query("SELECT * FROM saved_item WHERE kind = :kind AND job_id = :jobId AND segment = :segment AND word = :word")
  abstract suspend fun find(kind: String, jobId: String, segment: Int, word: Int): SavedItemEntity?

  @Query("DELETE FROM saved_item WHERE id = :id")
  abstract suspend fun delete(id: Long): Int

  /** The learner watched the phrases [first]..[last] of [jobId]: every item anchored there was watched [at]. */
  @Query("UPDATE saved_item SET last_watched_at = :at WHERE job_id = :jobId AND segment BETWEEN :first AND :last")
  abstract suspend fun touchWatched(jobId: String, first: Int, last: Int, at: Long): Int

  /** Saved items of clips deleted on the computer disappear with them (spec F-09 item 4, architecture 7.7). */
  @Query("DELETE FROM saved_item WHERE job_id IN (:jobIds)")
  abstract suspend fun deleteJobs(jobIds: List<String>)

  companion object {
    const val SHORT = "SHORT"
    const val WORD = "WORD"
  }
}
