package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.mapineda48.phonotrainer.core.database.learning.entity.ChangeMetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeSessionEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeStatEntity
import kotlinx.coroutines.flow.Flow

/**
 * Queries over `practice_stat`, `change_met` and `practice_session` (architecture 4.3, 10; WP-14 owns every learning
 * DAO). Practice keeps one session: starting a new one deletes the others, so the current session is the only row and
 * survives process death (spec F-17 "Process death mid-session: resumes at the same question").
 */
@Dao
abstract class PracticeDao : AnswerTally() {
  @Query("SELECT COUNT(*) FROM practice_stat")
  abstract suspend fun count(): Int

  /** Accuracy per change, the web's `{seen, correct}`. */
  @Query("SELECT * FROM practice_stat ORDER BY `change`")
  abstract fun observeStats(): Flow<List<PracticeStatEntity>>

  @Query("SELECT * FROM practice_stat ORDER BY `change`")
  abstract suspend fun stats(): List<PracticeStatEntity>

  /** "Forget your practice history?" on this phone (v1-core): accuracy only; changes met and sessions stay. */
  @Query("DELETE FROM practice_stat")
  abstract suspend fun clearStats()

  @Query("SELECT * FROM change_met ORDER BY first_met_at, `change`")
  abstract fun observeMet(): Flow<List<ChangeMetEntity>>

  @Insert
  abstract suspend fun insertSession(session: PracticeSessionEntity): Long

  @Query("DELETE FROM practice_session WHERE id <> :keep")
  abstract suspend fun deleteSessionsExcept(keep: Long)

  /** Stores [session] as the only session and returns its id. */
  @Transaction
  open suspend fun start(session: PracticeSessionEntity): Long {
    val id = insertSession(session.copy(id = 0))
    deleteSessionsExcept(id)
    return id
  }

  /** The current session (the newest; [start] keeps only one). */
  @Query("SELECT * FROM practice_session ORDER BY id DESC LIMIT 1")
  abstract fun observeCurrent(): Flow<PracticeSessionEntity?>

  @Query("SELECT * FROM practice_session WHERE id = :id")
  abstract suspend fun session(id: Long): PracticeSessionEntity?

  @Query(
    "UPDATE practice_session SET `index` = :index, answers_json = :answersJson, finished_at = :finishedAt " +
      "WHERE id = :id",
  )
  abstract suspend fun saveProgress(id: Long, index: Int, answersJson: String, finishedAt: Long?): Int

  /**
   * One answer of session [id]: its new position and answers, and the counts of [change] ([AnswerTally.tally]), in one
   * transaction. Returns false (and writes nothing) when the session no longer exists.
   */
  @Transaction
  open suspend fun answer(
    id: Long,
    index: Int,
    answersJson: String,
    finishedAt: Long?,
    change: String,
    correct: Boolean,
    at: Long,
  ): Boolean {
    if (saveProgress(id, index, answersJson, finishedAt) == 0) return false
    tally(change, correct, at)
    return true
  }
}
