package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapineda48.phonotrainer.core.database.library.entity.RequestOpEntity

/**
 * The `request_op` table's queries (architecture 4.2, 8.5; WP-09): the outbox of cancels, removals and re-runs, sent
 * before any new request in the order they were made (spec 7.7, F-20-AC5). Operations made in one transaction get
 * increasing `created_at` values, and the row id breaks any remaining tie, so the outbox order is the order of
 * creation (the automatic re-runs keep the order the computer accepted their jobs in, QT-5, QT-12).
 */
@Dao
interface RequestOpDao {
  @Query("SELECT COUNT(*) FROM request_op")
  suspend fun count(): Int

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(op: RequestOpEntity)

  /** The number of rows changed: 0 when the operation no longer exists. */
  @Update
  suspend fun update(op: RequestOpEntity): Int

  @Query("DELETE FROM request_op WHERE id = :id")
  suspend fun delete(id: String): Int

  @Query("SELECT * FROM request_op WHERE id = :id")
  suspend fun get(id: String): RequestOpEntity?

  /** Every operation that has not reached the computer, oldest first. */
  @Query("SELECT * FROM request_op WHERE state = 'PENDING' ORDER BY created_at, rowid")
  suspend fun pending(): List<RequestOpEntity>

  @Query("SELECT * FROM request_op WHERE state = 'PENDING' AND request_id = :requestId ORDER BY created_at, rowid")
  suspend fun pendingFor(requestId: String): List<RequestOpEntity>

  /** Operations that created a job with [key] as its idempotency key (a re-run's id, architecture 8.6). */
  @Query("SELECT * FROM request_op WHERE id = :key AND kind = 'RERUN'")
  suspend fun rerunWithKey(key: String): RequestOpEntity?

  /** "Retry immediately on the next transition to connected" (spec 7.7): the attempts are kept, the wait is not. */
  @Query("UPDATE request_op SET next_attempt_at = NULL WHERE state = 'PENDING' AND next_attempt_at IS NOT NULL")
  suspend fun clearWaits(): Int

  /** Finished operations older than [before] (epoch ms); pending ones are never pruned. */
  @Query("DELETE FROM request_op WHERE state != 'PENDING' AND created_at < :before")
  suspend fun pruneFinished(before: Long): Int
}
