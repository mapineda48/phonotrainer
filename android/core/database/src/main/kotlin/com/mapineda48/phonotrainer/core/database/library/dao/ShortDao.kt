package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.mapineda48.phonotrainer.core.database.library.entity.OccurrenceEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ShortEntity
import kotlinx.coroutines.flow.Flow

/**
 * A clip is **playable** (spec F-06 item 2: "its clip's study pack is on the phone"; the feed never streams) when its
 * clip data and at least one of its two audio tracks are verified on this phone (`artifact.local_state = READY` with
 * a file), and it is not waiting to be removed after a deletion on the computer. Either track counts: the player
 * plays the other one when the learner's chosen track is not on the phone yet (spec F-06 Partial, D-47; architecture
 * 11.1). Every count, sequence and example of the corpus is restricted to playable clips, so a clip whose study pack
 * is missing counts nowhere (architecture 9.4). Used as `… JOIN clip c … WHERE <PLAYABLE_CLIP>`.
 */
internal const val PLAYABLE_CLIP: String =
  "(c.deleted_on_computer = 0" +
    " AND EXISTS (SELECT 1 FROM artifact pa WHERE pa.job_id = c.job_id AND pa.role = 'CLIP_DATA'" +
    " AND pa.local_state = 'READY' AND pa.local_file IS NOT NULL)" +
    " AND EXISTS (SELECT 1 FROM artifact pb WHERE pb.job_id = c.job_id" +
    " AND pb.role IN ('AUDIO_MIX', 'AUDIO_DIALOGUE') AND pb.local_state = 'READY' AND pb.local_file IS NOT NULL))"

/** The number of shorts of the clip of the short `s` ("Short 4 of 15"). */
private const val OF_TOTAL: String = "(SELECT COUNT(*) FROM short t WHERE t.job_id = s.job_id) AS of_total"

/**
 * The `short` table's queries and the corpus index of a clip (WP-10; architecture 4.2, 9.5): replacing the derived
 * `short` and `occurrence` rows of a clip in one transaction together with the clip's index state, and the read-only
 * views of the `clip` and `artifact` rows (WP-08's tables) that the indexer and the media resolution need. The only
 * `clip` columns written here are `indexed_revision` and `index_rule_version`, the derived index state that
 * architecture 4.2 assigns to section 9.
 */
@Dao
abstract class ShortDao {
  @Query("SELECT COUNT(*) FROM short")
  abstract suspend fun count(): Int

  // ---------------------------------------------------------------------------------------------------------------
  // The index of a clip (architecture 9.5)

  /**
   * Replaces the derived rows of [jobId] by [shorts] and [occurrences] and records [indexedRevision] and [ruleVersion]
   * on its `clip` row, all in one transaction: a reader sees the old index or the new one, never a mix, and a failure
   * anywhere leaves the old index in place. Returns false, changing nothing, when the clip has no row.
   *
   * @throws IllegalArgumentException when a row belongs to another clip (nothing is changed).
   */
  @Transaction
  open suspend fun replaceIndex(
    jobId: String,
    shorts: List<ShortEntity>,
    occurrences: List<OccurrenceEntity>,
    indexedRevision: String,
    ruleVersion: Int,
  ): Boolean {
    require(shorts.all { it.jobId == jobId } && occurrences.all { it.jobId == jobId }) {
      "every derived row must belong to the clip being indexed"
    }
    if (clipCount(jobId) == 0) return false
    deleteOccurrences(listOf(jobId))
    deleteShorts(listOf(jobId))
    insertShorts(shorts)
    insertOccurrences(occurrences)
    setIndexState(jobId, indexedRevision, ruleVersion)
    return true
  }

  /** Drops the derived rows of [jobIds] and clears their index state, in one transaction. */
  @Transaction
  open suspend fun dropIndex(jobIds: List<String>) {
    for (chunk in jobIds.chunked(MAX_IDS)) {
      deleteOccurrences(chunk)
      deleteShorts(chunk)
      clearIndexState(chunk)
    }
  }

  @Query("SELECT COUNT(*) FROM clip WHERE job_id = :jobId")
  abstract suspend fun clipCount(jobId: String): Int

  @Query("DELETE FROM short WHERE job_id IN (:jobIds)")
  abstract suspend fun deleteShorts(jobIds: List<String>)

  @Query("DELETE FROM occurrence WHERE job_id IN (:jobIds)")
  abstract suspend fun deleteOccurrences(jobIds: List<String>)

  @Insert
  abstract suspend fun insertShorts(rows: List<ShortEntity>)

  @Insert
  abstract suspend fun insertOccurrences(rows: List<OccurrenceEntity>)

  @Query(
    "UPDATE clip SET indexed_revision = :indexedRevision, index_rule_version = :ruleVersion WHERE job_id = :jobId",
  )
  abstract suspend fun setIndexState(jobId: String, indexedRevision: String, ruleVersion: Int)

  @Query("UPDATE clip SET indexed_revision = NULL, index_rule_version = NULL WHERE job_id IN (:jobIds)")
  abstract suspend fun clearIndexState(jobIds: List<String>)

  /** The index state of [jobId], or null when the clip has no row. */
  @Query(
    "SELECT job_id, indexed_revision, index_rule_version FROM clip WHERE job_id = :jobId",
  )
  abstract suspend fun indexState(jobId: String): IndexState?

  /**
   * Clips whose clip data is on this phone but whose index is missing or was made with another rule version than
   * [ruleVersion] (architecture 9.5: an app update raised `RULE_VERSION`), oldest first.
   */
  @Query(
    "SELECT c.job_id, c.indexed_revision, c.index_rule_version FROM clip c" +
      " WHERE EXISTS (SELECT 1 FROM artifact a WHERE a.job_id = c.job_id AND a.role = 'CLIP_DATA'" +
      " AND a.local_state = 'READY' AND a.local_file IS NOT NULL AND a.local_sha256 IS NOT NULL)" +
      " AND (c.indexed_revision IS NULL OR c.index_rule_version IS NULL OR c.index_rule_version <> :ruleVersion)" +
      " ORDER BY c.first_seen_at, c.job_id",
  )
  abstract suspend fun staleIndexes(ruleVersion: Int): List<IndexState>

  // ---------------------------------------------------------------------------------------------------------------
  // Files of a clip (read-only views of WP-08's `artifact` and `clip` rows)

  /** The verified clip-data files of [jobId] (`analysis`, and `analysis_compact` in v1-complete). */
  @Query(
    "SELECT name, local_file, local_sha256 FROM artifact WHERE job_id = :jobId AND role = 'CLIP_DATA'" +
      " AND local_state = 'READY' AND local_file IS NOT NULL AND local_sha256 IS NOT NULL ORDER BY name",
  )
  abstract suspend fun clipDataFiles(jobId: String): List<ClipDataFile>

  /** Every artifact row of [jobId], for its media (architecture 4.2, 11.1). */
  @Query(
    "SELECT name, role, remote_status, remote_bytes, local_state, local_file, part_bytes FROM artifact" +
      " WHERE job_id = :jobId ORDER BY name",
  )
  abstract suspend fun mediaFiles(jobId: String): List<MediaFile>

  /** What the listing said about the clip's media, or null when the clip has no row. */
  @Query(
    "SELECT job_id, title, media_kind, audio_only_job, has_dialogue, video_codec, video_width, video_height," +
      " video_rotation, video_offset_ms, deleted_on_computer FROM clip WHERE job_id = :jobId",
  )
  abstract suspend fun mediaFacts(jobId: String): MediaFacts?

  /** Whether [jobId] is playable now ([PLAYABLE_CLIP]). */
  @Query("SELECT COUNT(*) FROM clip c WHERE c.job_id = :jobId AND $PLAYABLE_CLIP")
  abstract suspend fun playableCount(jobId: String): Int

  // ---------------------------------------------------------------------------------------------------------------
  // Shorts

  /** The shorts of [jobId] in timeline order (the Clip page), whatever is on the phone. */
  @Query("SELECT s.*, $OF_TOTAL FROM short s WHERE s.job_id = :jobId ORDER BY s.ordinal")
  abstract fun shortsOf(jobId: String): Flow<List<ShortRow>>

  /** The shorts of [jobId] in timeline order, when the clip is playable; else none ("In order"). */
  @Query(
    "SELECT s.*, $OF_TOTAL FROM short s JOIN clip c ON c.job_id = s.job_id" +
      " WHERE s.job_id = :jobId AND $PLAYABLE_CLIP ORDER BY s.ordinal",
  )
  abstract suspend fun playableShortsOf(jobId: String): List<ShortRow>

  /** Every short of a playable clip (change and Saved modes pick theirs among them). */
  @Query(
    "SELECT s.*, $OF_TOTAL FROM short s JOIN clip c ON c.job_id = s.job_id WHERE $PLAYABLE_CLIP" +
      " ORDER BY s.job_id, s.ordinal",
  )
  abstract suspend fun playableShorts(): List<ShortRow>

  /** The short of [jobId] whose phrases include [segment], whatever is on the phone. */
  @Query(
    "SELECT s.*, $OF_TOTAL FROM short s WHERE s.job_id = :jobId AND s.first_segment <= :segment" +
      " AND s.last_segment >= :segment ORDER BY s.first_segment DESC LIMIT 1",
  )
  abstract suspend fun shortContaining(jobId: String, segment: Int): ShortRow?

  private companion object {
    /** Below SQLite's 999 bound variables. */
    const val MAX_IDS = 500
  }
}

/** A clip's index state (`clip.indexed_revision`, `clip.index_rule_version`). */
data class IndexState(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "indexed_revision") val indexedRevision: String?,
  @ColumnInfo(name = "index_rule_version") val ruleVersion: Int?,
)

/** A verified clip-data file: its artifact [name], its path under `filesDir` and its SHA-256. */
data class ClipDataFile(
  @ColumnInfo(name = "name") val name: String,
  @ColumnInfo(name = "local_file") val localFile: String,
  @ColumnInfo(name = "local_sha256") val localSha256: String,
)

/** One artifact row as the media resolution reads it. */
data class MediaFile(
  @ColumnInfo(name = "name") val name: String,
  @ColumnInfo(name = "role") val role: String,
  @ColumnInfo(name = "remote_status") val remoteStatus: String,
  @ColumnInfo(name = "remote_bytes") val remoteBytes: Long?,
  @ColumnInfo(name = "local_state") val localState: String,
  @ColumnInfo(name = "local_file") val localFile: String?,
  @ColumnInfo(name = "part_bytes") val partBytes: Long?,
)

/** The `clip` columns the media resolution reads. */
data class MediaFacts(
  @ColumnInfo(name = "job_id") val jobId: String,
  @ColumnInfo(name = "title") val title: String,
  @ColumnInfo(name = "media_kind") val mediaKind: String?,
  @ColumnInfo(name = "audio_only_job") val audioOnlyJob: Boolean,
  @ColumnInfo(name = "has_dialogue") val hasDialogue: Boolean,
  @ColumnInfo(name = "video_codec") val videoCodec: String?,
  @ColumnInfo(name = "video_width") val videoWidth: Int?,
  @ColumnInfo(name = "video_height") val videoHeight: Int?,
  @ColumnInfo(name = "video_rotation") val videoRotation: Int?,
  @ColumnInfo(name = "video_offset_ms") val videoOffsetMs: Long?,
  @ColumnInfo(name = "deleted_on_computer") val deletedOnComputer: Boolean,
)

/** A `short` row with the number of shorts of its clip. */
data class ShortRow(
  @Embedded val short: ShortEntity,
  @ColumnInfo(name = "of_total") val ofTotal: Int,
)
