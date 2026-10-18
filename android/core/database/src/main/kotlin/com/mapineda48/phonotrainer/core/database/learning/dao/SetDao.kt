package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetItemEntity
import kotlinx.coroutines.flow.Flow

/** A set with its items, in position order. */
data class SetWithItems(
  @Embedded val set: StudySetEntity,
  @Relation(parentColumn = "id", entityColumn = "set_id")
  val items: List<StudySetItemEntity>,
) {
  /** [items] sorted by position (a relation has no order of its own). */
  val ordered: List<StudySetItemEntity> get() = items.sortedBy { it.position }
}

/**
 * Queries over `study_set` and `study_set_item` (architecture 4.3, 10; spec F-08; WP-14 owns every learning DAO). A set
 * never changes once stored, except its cursor, its answers and the `dropped` mark of shorts whose clip was deleted.
 * Kinds: [TODAY], [EXTRA]; item kinds: [SHORT], [QUESTION].
 */
@Dao
abstract class SetDao : AnswerTally() {
  @Query("SELECT COUNT(*) FROM study_set")
  abstract suspend fun count(): Int

  @Insert
  abstract suspend fun insertSet(set: StudySetEntity): Long

  @Insert
  abstract suspend fun insertItems(items: List<StudySetItemEntity>)

  /** Stores [set] with [items] (their `set_id` is replaced by the new id) and returns the new id. */
  @Transaction
  open suspend fun create(set: StudySetEntity, items: List<StudySetItemEntity>): Long {
    val id = insertSet(set.copy(id = 0))
    insertItems(items.map { it.copy(setId = id) })
    return id
  }

  @Transaction
  @Query("SELECT * FROM study_set WHERE id = :id")
  abstract suspend fun get(id: Long): SetWithItems?

  @Transaction
  @Query("SELECT * FROM study_set WHERE id = :id")
  abstract fun observe(id: Long): Flow<SetWithItems?>

  /** The newest set of [kind] for [localDate] (ISO `yyyy-MM-dd`): today's set when [kind] is [TODAY]. */
  @Transaction
  @Query("SELECT * FROM study_set WHERE kind = :kind AND local_date = :localDate ORDER BY id DESC LIMIT 1")
  abstract suspend fun latest(kind: String, localDate: String): SetWithItems?

  @Transaction
  @Query("SELECT * FROM study_set WHERE kind = :kind AND local_date = :localDate ORDER BY id DESC LIMIT 1")
  abstract fun observeLatest(kind: String, localDate: String): Flow<SetWithItems?>

  /** The newest set of any kind (the clips of its last shorts continue the interleaving of the next one). */
  @Transaction
  @Query("SELECT * FROM study_set ORDER BY id DESC LIMIT 1")
  abstract suspend fun newest(): SetWithItems?

  @Query("SELECT COUNT(*) FROM study_set_item WHERE set_id = :setId")
  abstract suspend fun itemCount(setId: Long): Int

  /** Moves the cursor forward to [position]; never backwards. Returns the rows changed. */
  @Query("UPDATE study_set SET cursor = :position WHERE id = :setId AND cursor < :position")
  abstract suspend fun moveCursor(setId: Long, position: Int): Int

  @Query("UPDATE study_set SET completed_at = :at WHERE id = :setId AND completed_at IS NULL")
  abstract suspend fun complete(setId: Long, at: Long): Int

  /**
   * The learner reached [position] of set [setId]: the cursor moves forward (items before it are done, spec D-45) and
   * the set is complete once the cursor passes its last item (the summary card).
   */
  @Transaction
  open suspend fun advance(setId: Long, position: Int, at: Long) {
    moveCursor(setId, position)
    if (position >= itemCount(setId)) complete(setId, at)
  }

  @Query(
    "UPDATE study_set_item SET choice = :choice, correct = :correct " +
      "WHERE set_id = :setId AND position = :position AND kind = 'QUESTION' AND choice IS NULL",
  )
  abstract suspend fun answerItem(setId: Long, position: Int, choice: String, correct: Boolean): Int

  @Query("UPDATE study_set SET answered = answered + 1, `right` = `right` + :right WHERE id = :setId")
  abstract suspend fun countSetAnswer(setId: Long, right: Int)

  /**
   * Answers question [position] of set [setId] once: the item, the set's "N of 5 right", and the change's practice
   * counts ([AnswerTally.tally]) in one transaction. Returns false (nothing written) when the item is not an unanswered
   * question.
   */
  @Transaction
  open suspend fun answer(setId: Long, position: Int, choice: String, correct: Boolean, change: String, at: Long): Boolean {
    if (answerItem(setId, position, choice, correct) == 0) return false
    countSetAnswer(setId, if (correct) 1 else 0)
    tally(change, correct, at)
    return true
  }

  /** Shorts of clips deleted from the phone drop out of every set (spec F-08 item 3); the rest stays as it was. */
  @Query("UPDATE study_set_item SET dropped = 1 WHERE kind = 'SHORT' AND job_id IN (:jobIds)")
  abstract suspend fun dropShortsOf(jobIds: List<String>)

  /** The clips of the remaining shorts of [kind] sets of [localDate] (today's set protects its clips, architecture 7.5). */
  @Query(
    "SELECT DISTINCT i.job_id FROM study_set_item i JOIN study_set s ON s.id = i.set_id " +
      "WHERE s.kind = :kind AND s.local_date = :localDate AND i.kind = 'SHORT' AND i.dropped = 0 AND i.job_id IS NOT NULL",
  )
  abstract suspend fun clipsOf(kind: String, localDate: String): List<String>

  companion object {
    const val TODAY = "TODAY"
    const val EXTRA = "EXTRA"
    const val SHORT = "SHORT"
    const val QUESTION = "QUESTION"
  }
}
