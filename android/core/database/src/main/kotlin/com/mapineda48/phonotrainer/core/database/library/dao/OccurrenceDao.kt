package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.mapineda48.phonotrainer.core.database.library.entity.OccurrenceEntity
import kotlinx.coroutines.flow.Flow

/** The other labels of the word of the row `o` (one `occurrence` row per word × change), space-separated. */
private const val OTHER_CHANGES: String =
  "(SELECT GROUP_CONCAT(x.change, ' ') FROM occurrence x WHERE x.job_id = o.job_id AND x.segment = o.segment" +
    " AND x.word = o.word AND x.change <> o.change) AS other_changes"

/** An occurrence with its clip's title and its word's other labels (`Occurrence.otherChanges`). */
private const val ROW: String = "SELECT o.*, c.title AS clip_title, $OTHER_CHANGES FROM occurrence o" +
  " JOIN clip c ON c.job_id = o.job_id"

/** Clearest first (lowest `diff_cost`), then a fixed order, as `ExamplePicker` and the spotlight rule sort. */
private const val CLEAREST: String = "o.diff_cost, o.job_id, o.segment, o.word, o.change"

/**
 * The `occurrence` table's queries (WP-10; architecture 4.2, 9.4; spec D-16): the phone's equivalents of the server's
 * corpus endpoints, over the **eligible** rows (the spotlight candidates of architecture 9.3: not low confidence, at
 * least 60 ms, with a heard form) of **playable** clips ([PLAYABLE_CLIP]) only.
 */
@Dao
interface OccurrenceDao {
  @Query("SELECT COUNT(*) FROM occurrence")
  suspend fun count(): Int

  /**
   * The two count units of spec D-48 per change, from one grouped query: [ChangeCountRow.shorts] = the distinct
   * shorts with an eligible row of the change (what its change mode plays), [ChangeCountRow.occurrences] = its
   * eligible rows. Changes with none are absent.
   */
  @Query(
    "SELECT o.change AS change, COUNT(DISTINCT o.job_id || ' ' || o.short_first_segment) AS shorts," +
      " COUNT(*) AS occurrences FROM occurrence o JOIN clip c ON c.job_id = o.job_id" +
      " WHERE o.eligible = 1 AND $PLAYABLE_CLIP GROUP BY o.change ORDER BY o.change",
  )
  fun changeCounts(): Flow<List<ChangeCountRow>>

  /** The eligible rows of [change] (of [onlyClip] when not null), clearest first. */
  @Query(
    "$ROW WHERE o.eligible = 1 AND o.change = :change AND (:onlyClip IS NULL OR o.job_id = :onlyClip)" +
      " AND $PLAYABLE_CLIP ORDER BY $CLEAREST",
  )
  suspend fun eligibleOf(change: String, onlyClip: String?): List<OccurrenceRow>

  /**
   * Eligible rows whose heard or dictionary form contains [fragment] (a `LIKE` pattern, escaped with `\`), clearest
   * first: a superset of a symbol's examples, which the caller narrows with the IPA tokenizer.
   */
  @Query(
    "$ROW WHERE o.eligible = 1 AND (o.realized_ipa LIKE :fragment ESCAPE '\\' OR o.dict_ipa LIKE :fragment ESCAPE '\\')" +
      " AND $PLAYABLE_CLIP ORDER BY $CLEAREST",
  )
  suspend fun eligibleLike(fragment: String): List<OccurrenceRow>

  /** Every eligible row, by change, most divergent first (the server's corpus order, which the web's quiz reads). */
  @Query(
    "$ROW WHERE o.eligible = 1 AND $PLAYABLE_CLIP" +
      " ORDER BY o.change, o.diff_cost DESC, o.job_id, o.segment, o.word",
  )
  suspend fun eligibleByChange(): List<OccurrenceRow>

  /** The eligible rows of the word [wordKey] (`WordKey`), clearest first. */
  @Query("$ROW WHERE o.eligible = 1 AND o.word_key = :wordKey AND $PLAYABLE_CLIP ORDER BY $CLEAREST")
  suspend fun eligibleOfWord(wordKey: String): List<OccurrenceRow>

  /** Every eligible row of a playable clip with [change] (of [onlyClip] when not null): a change mode's candidates. */
  @Query(
    "SELECT o.* FROM occurrence o JOIN clip c ON c.job_id = o.job_id WHERE o.eligible = 1 AND o.change = :change" +
      " AND (:onlyClip IS NULL OR o.job_id = :onlyClip) AND $PLAYABLE_CLIP ORDER BY $CLEAREST",
  )
  suspend fun candidatesOf(change: String, onlyClip: String?): List<OccurrenceEntity>

  /** Every row (eligible or not) of the short of [jobId] starting at [firstSegment], in clip order. */
  @Query(
    "SELECT * FROM occurrence WHERE job_id = :jobId AND short_first_segment = :firstSegment" +
      " ORDER BY segment, word, change",
  )
  suspend fun rowsOfShort(jobId: String, firstSegment: Int): List<OccurrenceEntity>

  /**
   * What the playable clips hold: clips with at least one short, their shorts, their words (the listing's
   * `word_count`) and their eligible occurrences.
   */
  @Query(
    "SELECT COUNT(*) AS clips, COALESCE(SUM((SELECT COUNT(*) FROM short s WHERE s.job_id = c.job_id)), 0) AS shorts," +
      " COALESCE(SUM(c.word_count), 0) AS words," +
      " COALESCE(SUM((SELECT COUNT(*) FROM occurrence o WHERE o.job_id = c.job_id AND o.eligible = 1)), 0)" +
      " AS occurrences FROM clip c WHERE EXISTS (SELECT 1 FROM short s WHERE s.job_id = c.job_id) AND $PLAYABLE_CLIP",
  )
  fun totals(): Flow<TotalsRow>
}

/** [OccurrenceDao.changeCounts]: one change's short count and occurrence count (spec D-48). */
data class ChangeCountRow(
  @ColumnInfo(name = "change") val change: String,
  @ColumnInfo(name = "shorts") val shorts: Int,
  @ColumnInfo(name = "occurrences") val occurrences: Int,
)

/** An `occurrence` row with its clip's title and its word's [otherChanges] (space-separated, unordered). */
data class OccurrenceRow(
  @Embedded val occurrence: OccurrenceEntity,
  @ColumnInfo(name = "clip_title") val clipTitle: String,
  @ColumnInfo(name = "other_changes") val otherChanges: String?,
)

/** [OccurrenceDao.totals]. */
data class TotalsRow(
  @ColumnInfo(name = "clips") val clips: Int,
  @ColumnInfo(name = "shorts") val shorts: Int,
  @ColumnInfo(name = "words") val words: Int,
  @ColumnInfo(name = "occurrences") val occurrences: Int,
)
