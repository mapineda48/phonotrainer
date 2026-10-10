package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.FeedPosition
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.KeepWatching
import com.mapineda48.phonotrainer.core.domain.model.PracticeSession
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import kotlinx.coroutines.flow.Flow

// :core:study (WP-14), architecture 3.4 and 10.

interface ProgressRepository {
  fun seen(): Flow<Set<ShortId>>

  /** Seen at 80 % actually played. */
  suspend fun recordPlayed(id: ShortId, playedMs: Long, lengthMs: Long)

  fun resume(id: JobId): Flow<ShortId?>

  fun lastFeed(): Flow<FeedPosition?>

  suspend fun saveFeedPosition(position: FeedPosition)

  fun keepWatching(): Flow<KeepWatching?>

  suspend fun markOpened(id: JobId)
}

interface SetRepository {
  fun today(): Flow<StudySet?>

  suspend fun ensureToday(): StudySet?

  suspend fun anotherSet(): StudySet?

  suspend fun advance(setId: Long, position: Int)

  /** [choice]: the option id (work-plan Appendix A.5). */
  suspend fun answer(setId: Long, position: Int, choice: String): AnswerResult
}

interface PracticeRepository {
  suspend fun newSession(focus: ChangeLabel?, weakSpots: Boolean): PracticeSession

  fun currentSession(): Flow<PracticeSession?>

  suspend fun answer(sessionId: Long, index: Int, choice: String): AnswerResult

  fun accuracy(): Flow<List<ChangeAccuracy>>

  fun changesMet(): Flow<Set<ChangeLabel>>

  suspend fun reset()
}

interface SavedRepository {
  fun saved(): Flow<List<SavedItem>>

  fun isSaved(id: ShortId): Flow<Boolean>

  suspend fun saveShort(id: ShortId, spotlight: WordRef?)

  suspend fun saveWord(word: WordRef)

  suspend fun unsave(itemId: Long)
}
