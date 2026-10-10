package com.mapineda48.phonotrainer.core.study

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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Working stubs of the contract freeze (work-plan 2.3): nothing seen, saved or practised, and no clip to make a set
// from. WP-14 replaces this file.

class StubProgressRepository @Inject constructor() : ProgressRepository {
  override fun seen(): Flow<Set<ShortId>> = flowOf(emptySet())

  override suspend fun recordPlayed(id: ShortId, playedMs: Long, lengthMs: Long) = Unit

  override fun resume(id: JobId): Flow<ShortId?> = flowOf(null)

  override fun lastFeed(): Flow<FeedPosition?> = flowOf(null)

  override suspend fun saveFeedPosition(position: FeedPosition) = Unit

  override fun keepWatching(): Flow<KeepWatching?> = flowOf(null)

  override suspend fun markOpened(id: JobId) = Unit
}

class StubSetRepository @Inject constructor() : SetRepository {
  override fun today(): Flow<StudySet?> = flowOf(null)

  override suspend fun ensureToday(): StudySet? = null

  override suspend fun anotherSet(): StudySet? = null

  override suspend fun advance(setId: Long, position: Int) = Unit

  override suspend fun answer(setId: Long, position: Int, choice: String): AnswerResult =
    error("No set exists before WP-14: nothing can be answered")
}

class StubPracticeRepository @Inject constructor() : PracticeRepository {
  override suspend fun newSession(focus: ChangeLabel?, weakSpots: Boolean): PracticeSession =
    PracticeSession(0, focus, weakSpots, emptyList(), index = 0, answers = emptyList(), createdAt = Instant.EPOCH, finishedAt = null)

  override fun currentSession(): Flow<PracticeSession?> = flowOf(null)

  override suspend fun answer(sessionId: Long, index: Int, choice: String): AnswerResult =
    error("No session has questions before WP-14: nothing can be answered")

  override fun accuracy(): Flow<List<ChangeAccuracy>> = flowOf(emptyList())

  override fun changesMet(): Flow<Set<ChangeLabel>> = flowOf(emptySet())

  override suspend fun reset() = Unit
}

class StubSavedRepository @Inject constructor() : SavedRepository {
  override fun saved(): Flow<List<SavedItem>> = flowOf(emptyList())

  override fun isSaved(id: ShortId): Flow<Boolean> = flowOf(false)

  override suspend fun saveShort(id: ShortId, spotlight: WordRef?) = Unit

  override suspend fun saveWord(word: WordRef) = Unit

  override suspend fun unsave(itemId: Long) = Unit
}

class StubStudyProtection @Inject constructor() : StudyProtection {
  override suspend fun protectedClips(): Set<JobId> = emptySet()

  override suspend fun lastWatched(): Map<JobId, Instant> = emptyMap()
}

@Module
@InstallIn(SingletonComponent::class)
interface StudyModule {
  @Binds
  fun progress(impl: StubProgressRepository): ProgressRepository

  @Binds
  fun sets(impl: StubSetRepository): SetRepository

  @Binds
  fun practice(impl: StubPracticeRepository): PracticeRepository

  @Binds
  fun saved(impl: StubSavedRepository): SavedRepository

  @Binds
  fun protection(impl: StubStudyProtection): StudyProtection
}
