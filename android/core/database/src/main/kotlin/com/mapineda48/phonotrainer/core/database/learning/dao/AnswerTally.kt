package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Query

/**
 * The bookkeeping of one answer, shared by [SetDao] (a set's questions) and [PracticeDao] (the Practice tab): the web's
 * `{seen, correct}` per change in `practice_stat` (a set's answers count in Practice accuracy, spec F-08 item 5) and the
 * change in `change_met` ("a question on it answered", spec F-17 item 5). Each DAO calls [tally] inside its own answer
 * transaction, so an answer and its counts are written together or not at all.
 */
abstract class AnswerTally {
  /** Creates the change's row at 0 / 0 when it has none (the counters are NOT NULL DEFAULT 0). */
  @Query("INSERT OR IGNORE INTO practice_stat (`change`, seen, correct) VALUES (:change, 0, 0)")
  abstract suspend fun ensureStat(change: String)

  /** One more answer on [change]; [right] is 1 for a right answer, 0 otherwise. */
  @Query("UPDATE practice_stat SET seen = seen + 1, correct = correct + :right WHERE `change` = :change")
  abstract suspend fun countAnswer(change: String, right: Int)

  /** The first time the learner met [change]; a later call keeps the first time. */
  @Query("INSERT OR IGNORE INTO change_met (`change`, first_met_at) VALUES (:change, :at)")
  abstract suspend fun meet(change: String, at: Long)

  /** [ensureStat], [countAnswer] and [meet]; the caller's transaction makes them one write. */
  suspend fun tally(change: String, correct: Boolean, at: Long) {
    ensureStat(change)
    countAnswer(change, if (correct) 1 else 0)
    meet(change, at)
  }
}
