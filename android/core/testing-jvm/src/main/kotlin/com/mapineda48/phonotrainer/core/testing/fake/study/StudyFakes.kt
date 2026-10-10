package com.mapineda48.phonotrainer.core.testing.fake.study

import com.mapineda48.phonotrainer.core.domain.api.PracticeRepository
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SetRepository
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
import com.mapineda48.phonotrainer.core.domain.spi.StudyProtection
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// Settable fakes of the :core:study interfaces (work-plan 4.6; WP-14 owns this folder after B1).

class FakeProgressRepository : ProgressRepository {
  val seenState = MutableStateFlow(emptySet<ShortId>())
  val resumeById = MutableStateFlow(emptyMap<JobId, ShortId>())
  val lastFeedState = MutableStateFlow<FeedPosition?>(null)
  val keepWatchingState = MutableStateFlow<KeepWatching?>(null)
  val opened = mutableListOf<JobId>()

  override fun seen(): Flow<Set<ShortId>> = seenState

  override suspend fun recordPlayed(id: ShortId, playedMs: Long, lengthMs: Long) {
    if (lengthMs > 0 && playedMs * 10 >= lengthMs * 8) seenState.value += id
  }

  override fun resume(id: JobId): Flow<ShortId?> = resumeById.map { it[id] }

  override fun lastFeed(): Flow<FeedPosition?> = lastFeedState

  override suspend fun saveFeedPosition(position: FeedPosition) {
    lastFeedState.value = position
  }

  override fun keepWatching(): Flow<KeepWatching?> = keepWatchingState

  override suspend fun markOpened(id: JobId) {
    opened += id
  }
}

class FakeSetRepository : SetRepository {
  val todayState = MutableStateFlow<StudySet?>(null)
  var another: StudySet? = null
  var answerFor: (Long, Int, String) -> AnswerResult = { _, _, _ -> error("No answer arranged in FakeSetRepository") }
  val advanced = mutableListOf<Pair<Long, Int>>()

  override fun today(): Flow<StudySet?> = todayState

  override suspend fun ensureToday(): StudySet? = todayState.value

  override suspend fun anotherSet(): StudySet? = another

  override suspend fun advance(setId: Long, position: Int) {
    advanced += setId to position
  }

  override suspend fun answer(setId: Long, position: Int, choice: String): AnswerResult = answerFor(setId, position, choice)
}

class FakePracticeRepository : PracticeRepository {
  val session = MutableStateFlow<PracticeSession?>(null)
  val accuracyState = MutableStateFlow(emptyList<ChangeAccuracy>())
  val met = MutableStateFlow(emptySet<ChangeLabel>())
  var answerFor: (Long, Int, String) -> AnswerResult = { _, _, _ -> error("No answer arranged in FakePracticeRepository") }
  var resets = 0
    private set

  override suspend fun newSession(focus: ChangeLabel?, weakSpots: Boolean): PracticeSession =
    PracticeSession(1, focus, weakSpots, emptyList(), 0, emptyList(), Instant.EPOCH, null).also { session.value = it }

  override fun currentSession(): Flow<PracticeSession?> = session

  override suspend fun answer(sessionId: Long, index: Int, choice: String): AnswerResult = answerFor(sessionId, index, choice)

  override fun accuracy(): Flow<List<ChangeAccuracy>> = accuracyState

  override fun changesMet(): Flow<Set<ChangeLabel>> = met

  override suspend fun reset() {
    resets++
    accuracyState.value = emptyList()
  }
}

class FakeSavedRepository : SavedRepository {
  val items = MutableStateFlow(emptyList<SavedItem>())
  val savedShorts = mutableListOf<ShortId>()
  val savedWords = mutableListOf<WordRef>()

  override fun saved(): Flow<List<SavedItem>> = items

  override fun isSaved(id: ShortId): Flow<Boolean> = items.map { list -> list.any { it.short == id } }

  override suspend fun saveShort(id: ShortId, spotlight: WordRef?) {
    savedShorts += id
  }

  override suspend fun saveWord(word: WordRef) {
    savedWords += word
  }

  override suspend fun unsave(itemId: Long) {
    items.value = items.value.filterNot { it.id == itemId }
  }
}

class FakeStudyProtection(
  var protected: Set<JobId> = emptySet(),
  var watched: Map<JobId, Instant> = emptyMap(),
) : StudyProtection {
  override suspend fun protectedClips(): Set<JobId> = protected

  override suspend fun lastWatched(): Map<JobId, Instant> = watched
}
