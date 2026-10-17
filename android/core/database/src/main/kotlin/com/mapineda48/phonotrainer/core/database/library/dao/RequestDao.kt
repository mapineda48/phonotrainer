package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import kotlinx.coroutines.flow.Flow

private const val REQUEST_PACK_ROLES = "'CLIP_DATA', 'AUDIO_MIX', 'AUDIO_DIALOGUE'"

private const val REQUEST_VIEW =
  "SELECT request.*, clip.status AS job_status, clip.stage AS job_stage, clip.percent AS job_percent, " +
    "clip.queue_position AS job_queue_position, clip.title AS job_title, " +
    "(SELECT SUM(CASE WHEN a.local_state = 'READY' THEN COALESCE(a.local_bytes, a.remote_bytes, 0) " +
    "ELSE COALESCE(a.part_bytes, 0) END) FROM artifact a WHERE a.job_id = request.job_id " +
    "AND a.role IN ($REQUEST_PACK_ROLES) AND a.local_state != 'ABSENT') AS pack_done_bytes, " +
    "(SELECT SUM(COALESCE(a.remote_bytes, 0)) FROM artifact a WHERE a.job_id = request.job_id " +
    "AND a.role IN ($REQUEST_PACK_ROLES) AND a.local_state != 'ABSENT') AS pack_total_bytes, " +
    "(SELECT COUNT(*) FROM artifact a WHERE a.job_id = request.job_id AND a.role IN ($REQUEST_PACK_ROLES) " +
    "AND a.local_state IN ('QUEUED', 'DOWNLOADING', 'PAUSED')) AS pack_transfers, " +
    "EXISTS (SELECT 1 FROM request_op o WHERE o.request_id = request.id AND o.kind = 'RERUN' " +
    "AND o.state = 'PENDING') AS rerun_pending, " +
    "EXISTS (SELECT 1 FROM request_op o WHERE o.request_id = request.id AND o.kind = 'CANCEL' " +
    "AND o.state = 'PENDING') AS cancel_pending " +
    "FROM request LEFT JOIN clip ON clip.job_id = request.job_id"

/**
 * The `request` table's queries (architecture 4.2, 8; WP-09). Rows are written by `:core:requests` only, one
 * transaction per transition; the queries that read the `clip`, `artifact` and `request_op` tables only read them, for
 * what a request shows (its job's last known progress, the study pack coming to this phone, a re-run or a cancel on
 * its way) and for duplicate links already in the catalogue.
 */
@Dao
interface RequestDao {
  @Query("SELECT COUNT(*) FROM request")
  suspend fun count(): Int

  /** The next creation order, read inside the insert's transaction (architecture 4.2). */
  @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM request")
  suspend fun nextSeq(): Long

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(request: RequestEntity)

  /** The number of rows changed: 0 when the request no longer exists. */
  @Update
  suspend fun update(request: RequestEntity): Int

  @Query("DELETE FROM request WHERE id = :id")
  suspend fun delete(id: String): Int

  @Query("SELECT * FROM request WHERE id = :id")
  suspend fun get(id: String): RequestEntity?

  @Query("SELECT * FROM request WHERE job_id = :jobId")
  suspend fun byJob(jobId: String): RequestEntity?

  @Query("SELECT * FROM request ORDER BY seq")
  suspend fun all(): List<RequestEntity>

  @Query("SELECT * FROM request WHERE state IN (:states) ORDER BY seq")
  suspend fun inStates(states: List<String>): List<RequestEntity>

  /**
   * Waiting requests of [kind] the sender may pick at [now]: not held by "Stop sending" (D-46), due by the retry
   * schedule, in creation order (spec 7.7). Files also need their complete copy.
   */
  @Query(
    "SELECT * FROM request WHERE state = 'WAITING' AND kind = :kind AND held = 0 " +
      "AND (kind = 'LINK' OR copy_complete = 1) " +
      "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) ORDER BY seq",
  )
  suspend fun dueWaiting(kind: String, now: Long): List<RequestEntity>

  /** The newest request of a duplicate key (architecture 8.2 rule 5). */
  @Query("SELECT * FROM request WHERE link_key = :key ORDER BY seq DESC LIMIT 1")
  suspend fun latestWithLinkKey(key: String): RequestEntity?

  /** "Retry immediately on the next transition to connected" (spec 7.7): the attempts are kept, the wait is not. */
  @Query("UPDATE request SET next_attempt_at = NULL WHERE state = 'WAITING' AND next_attempt_at IS NOT NULL")
  suspend fun clearWaits(): Int

  @Query("SELECT id FROM request")
  suspend fun ids(): List<String>

  /** Every request with what it shows, in creation order. */
  @Query("$REQUEST_VIEW ORDER BY request.seq")
  fun observeAll(): Flow<List<RequestWithJob>>

  @Query("$REQUEST_VIEW WHERE request.id = :id")
  fun observe(id: String): Flow<RequestWithJob?>

  /** The catalogue's links, for duplicates (architecture 8.2 rule 5). */
  @Query("SELECT job_id, title, source_url FROM clip WHERE source_url IS NOT NULL")
  suspend fun catalogueLinks(): List<CatalogueLink>

  /** Study-pack files of a clip queued, downloading or paused on their way to this phone (spec 7.7). */
  @Query(
    "SELECT COUNT(*) FROM artifact WHERE job_id = :jobId AND role IN ($REQUEST_PACK_ROLES) " +
      "AND local_state IN ('QUEUED', 'DOWNLOADING', 'PAUSED')",
  )
  suspend fun studyPackTransfers(jobId: String): Int

}

/**
 * A request with what it shows beyond its own row (architecture 8.4): its job's last known state from the catalogue
 * mirror (the "Last known: Analyzing 40 %" of spec F-20), its study pack on the way to this phone, and whether a
 * re-run ("Starting again on your computer") or a cancel is waiting in the outbox.
 */
data class RequestWithJob(
  @Embedded val request: RequestEntity,
  @ColumnInfo(name = "job_status") val jobStatus: String?,
  @ColumnInfo(name = "job_stage") val jobStage: String?,
  @ColumnInfo(name = "job_percent") val jobPercent: Int?,
  @ColumnInfo(name = "job_queue_position") val jobQueuePosition: Int?,
  @ColumnInfo(name = "job_title") val jobTitle: String?,
  @ColumnInfo(name = "pack_done_bytes") val packDoneBytes: Long?,
  @ColumnInfo(name = "pack_total_bytes") val packTotalBytes: Long?,
  @ColumnInfo(name = "pack_transfers") val packTransfers: Int,
  @ColumnInfo(name = "rerun_pending") val rerunPending: Boolean,
  @ColumnInfo(name = "cancel_pending") val cancelPending: Boolean,
)

/** A catalogue clip that came from a link. */
data class CatalogueLink(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "title") val title: String,
  @ColumnInfo(name = "source_url") val sourceUrl: String,
)
