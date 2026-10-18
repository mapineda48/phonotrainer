package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import kotlinx.coroutines.flow.Flow

/**
 * The `clip` table's queries (architecture 4.2, 7.3, 7.7; WP-08). Rows are written by `:core:sync` only (the corpus
 * index columns excepted, which `ShortDao` writes): the catalogue reconcile, the job channel, the learner's choices
 * about this phone's copy and the deletion paths.
 */
@Dao
interface ClipDao {
  @Query("SELECT COUNT(*) FROM clip")
  suspend fun count(): Int

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(clip: ClipEntity)

  /** The number of rows changed: 0 when the clip no longer exists. */
  @Update
  suspend fun update(clip: ClipEntity): Int

  @Query("SELECT * FROM clip WHERE job_id = :jobId")
  suspend fun get(jobId: String): ClipEntity?

  @Query("SELECT * FROM clip WHERE job_id IN (:jobIds)")
  suspend fun getAll(jobIds: List<String>): List<ClipEntity>

  @Query("SELECT * FROM clip WHERE job_id = :jobId")
  fun observe(jobId: String): Flow<ClipEntity?>

  /** Every row, in the catalogue's order (newest first, ties by job id, F-04-AC4). */
  @Query("SELECT * FROM clip ORDER BY created_at DESC, job_id DESC")
  suspend fun all(): List<ClipEntity>

  /**
   * The clips of the catalogue (spec F-04): finished jobs of any origin, newest first with ties broken by job id.
   * Queued, running, failed and cancelled jobs are requests or "Started on your computer", never clips.
   */
  @Query("SELECT * FROM clip WHERE status = 'DONE' ORDER BY created_at DESC, job_id DESC")
  fun observeFinished(): Flow<List<ClipEntity>>

  /**
   * "Started on your computer" (architecture 8.7): the paired computer's jobs this phone did not submit and that are
   * not finished, hidden ones included (the row carries the flag).
   */
  @Query(
    "SELECT * FROM clip WHERE origin = 'ACTIVE' AND submitted_by_device IS NULL AND status != 'DONE' " +
      "ORDER BY created_at DESC, job_id DESC",
  )
  fun observeBrowserJobs(): Flow<List<ClipEntity>>

  /** The `ACTIVE` clips of one computer and library: what a listing of theirs may update or delete (7.7). */
  @Query("SELECT * FROM clip WHERE origin = 'ACTIVE' AND server_id = :serverId AND library_id = :libraryId")
  suspend fun active(serverId: String, libraryId: String): List<ClipEntity>

  /**
   * A listing reports another library (QT-14): the computer's `ACTIVE` clips of any other library are tagged
   * `OTHER_LIBRARY` and never touched by a listing again. Returns how many were tagged.
   */
  @Query(
    "UPDATE clip SET origin = 'OTHER_LIBRARY' WHERE origin = 'ACTIVE' AND server_id = :serverId " +
      "AND library_id != :libraryId",
  )
  suspend fun tagOtherLibrary(serverId: String, libraryId: String): Int

  /**
   * "Forget this computer" (architecture 6.3, CR-WP07-2): the computer's `ACTIVE` clips are tagged
   * `FORGOTTEN_COMPUTER` and stay on this phone. Returns how many were tagged.
   */
  @Query("UPDATE clip SET origin = 'FORGOTTEN_COMPUTER' WHERE origin = 'ACTIVE' AND server_id = :serverId")
  suspend fun tagForgotten(serverId: String): Int

  @Query("DELETE FROM clip WHERE job_id IN (:jobIds)")
  suspend fun delete(jobIds: List<String>): Int

  /** Clips whose removal waits for the screens that show them to close (QT-10). */
  @Query("SELECT job_id FROM clip WHERE deleted_on_computer = 1")
  suspend fun deferredRemovals(): List<String>

  @Query("UPDATE clip SET deleted_on_computer = 1 WHERE job_id IN (:jobIds)")
  suspend fun markDeletedOnComputer(jobIds: List<String>): Int

  @Query("UPDATE clip SET kept = :kept WHERE job_id = :jobId")
  suspend fun setKept(jobId: String, kept: Boolean): Int

  @Query("UPDATE clip SET hidden = 1 WHERE job_id = :jobId")
  suspend fun hide(jobId: String): Int

  /** "Download to this phone" / "Download when connected" (F-04-AC2, F-05-AC4). */
  @Query("UPDATE clip SET wanted = 1, removed_by_learner = 0 WHERE job_id = :jobId")
  suspend fun markWanted(jobId: String): Int

  /** "Remove from this phone": never downloaded again until "Download to this phone" (7.7). */
  @Query(
    "UPDATE clip SET removed_by_learner = 1, wanted = 0, video_wanted = 0, on_phone_at = NULL WHERE job_id = :jobId",
  )
  suspend fun markRemovedByLearner(jobId: String): Int

  @Query("UPDATE clip SET video_wanted = :wanted WHERE job_id = :jobId")
  suspend fun setVideoWanted(jobId: String, wanted: Boolean): Int

  @Query("UPDATE clip SET video_wanted = 0")
  suspend fun clearVideoWanted(): Int

  /** The study pack became complete and verified: "On this phone" from [at] (SC-11). */
  @Query("UPDATE clip SET on_phone_at = :at WHERE job_id = :jobId AND on_phone_at IS NULL")
  suspend fun markOnPhone(jobId: String, at: Long): Int

  @Query("UPDATE clip SET on_phone_at = NULL WHERE job_id = :jobId")
  suspend fun clearOnPhone(jobId: String): Int

  /**
   * A lean job event of the channel (architecture 7.6): the job's state as the computer reports it, without
   * artifacts. Returns 0 when the clip has no row (the next listing brings it).
   */
  @Query(
    "UPDATE clip SET status = :status, stage = :stage, percent = :percent, queue_position = :queuePosition, " +
      "title = :title, error_code = :errorCode, error_message = :errorMessage, started_at = :startedAt, " +
      "finished_at = :finishedAt WHERE job_id = :jobId AND origin = 'ACTIVE'",
  )
  suspend fun updateLean(
    jobId: String,
    status: String,
    stage: String?,
    percent: Int?,
    queuePosition: Int?,
    title: String,
    errorCode: String?,
    errorMessage: String?,
    startedAt: Long?,
    finishedAt: Long?,
  ): Int

  /**
   * Clips whose verified clip data is not the revision the corpus indexed (architecture 7.1 step 6): never indexed, or
   * indexed from another file.
   */
  @Query(
    "SELECT c.job_id FROM clip c JOIN artifact a ON a.job_id = c.job_id AND a.role = 'CLIP_DATA' " +
      "AND a.local_state = 'READY' AND a.local_file IS NOT NULL AND a.local_sha256 IS NOT NULL " +
      "WHERE c.deleted_on_computer = 0 " +
      "AND (c.indexed_revision IS NULL OR c.indexed_revision != substr(a.local_sha256, 1, 16)) " +
      "ORDER BY c.created_at DESC, c.job_id DESC",
  )
  suspend fun needingIndex(): List<String>

  /** The highest rules version of the catalogue: older ones read "Older rules" (spec F-04, the web's rule). */
  @Query("SELECT MAX(rules_version) FROM clip WHERE status = 'DONE'")
  fun observeNewestRules(): Flow<Int?>

  /** The number of shorts each clip has (the Library's "You've seen 12 of 15 shorts"). */
  @Query("SELECT job_id, COUNT(*) AS shorts FROM short GROUP BY job_id")
  fun observeShortCounts(): Flow<List<ClipShortCount>>
}

/** How many shorts the corpus derived for one clip. */
data class ClipShortCount(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "shorts") val shorts: Int,
)
