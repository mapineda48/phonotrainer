package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import kotlinx.coroutines.flow.Flow

/**
 * The `artifact` table's queries (architecture 4.2, 7.4, 7.5; WP-08): one row per served file of a clip, with the
 * verified copy in use (`local_state` READY with its file and checksum) and the resume state of the next one. Rows are
 * written by `:core:sync` only; the corpus and the device queue read them.
 */
@Dao
interface ArtifactDao {
  @Query("SELECT COUNT(*) FROM artifact")
  suspend fun count(): Int

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(artifact: ArtifactEntity)

  /** The number of rows changed: 0 when the row no longer exists. */
  @Update
  suspend fun update(artifact: ArtifactEntity): Int

  @Query("SELECT * FROM artifact WHERE job_id = :jobId AND name = :name")
  suspend fun get(jobId: String, name: String): ArtifactEntity?

  @Query("SELECT * FROM artifact WHERE job_id = :jobId ORDER BY name")
  suspend fun forJob(jobId: String): List<ArtifactEntity>

  @Query("SELECT * FROM artifact WHERE job_id = :jobId ORDER BY name")
  fun observeForJob(jobId: String): Flow<List<ArtifactEntity>>

  /** Every row (the planner, the storage policy and the start-up sweep read the whole table). */
  @Query("SELECT * FROM artifact ORDER BY job_id, name")
  suspend fun all(): List<ArtifactEntity>

  /** Every row, as it changes (the Library's availability and the storage usage). */
  @Query("SELECT * FROM artifact ORDER BY job_id, name")
  fun observeAll(): Flow<List<ArtifactEntity>>

  @Query("DELETE FROM artifact WHERE job_id = :jobId AND name = :name")
  suspend fun delete(jobId: String, name: String): Int

  /** The resume state of a download (at most every 512 KiB, architecture 7.4). */
  @Query(
    "UPDATE artifact SET part_bytes = :partBytes, updated_at = :at WHERE job_id = :jobId AND name = :name " +
      "AND local_state = 'DOWNLOADING'",
  )
  suspend fun recordPart(jobId: String, name: String, partBytes: Long, at: Long): Int

  /**
   * Downloads a previous process left `DOWNLOADING` (rows last updated before [before], architecture 4.6) go back to
   * the queue: their part stays and is resumed. Returns how many.
   */
  @Query(
    "UPDATE artifact SET local_state = 'QUEUED' WHERE local_state = 'DOWNLOADING' AND updated_at < :before",
  )
  suspend fun requeueInterrupted(before: Long): Int

  /** Every file a row names (in use or partly downloaded), for the start-up sweep. */
  @Query("SELECT job_id, name, local_file, part_revision FROM artifact")
  suspend fun files(): List<ArtifactFiles>
}

/** The files one artifact row names: its verified copy and the revision its partial download belongs to. */
data class ArtifactFiles(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "name") val name: String,
  @ColumnInfo(name = "local_file") val localFile: String?,
  @ColumnInfo(name = "part_revision") val partRevision: String?,
)
