package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.ClipLease
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.CompareRow
import com.mapineda48.phonotrainer.core.domain.model.CorpusTotals
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedSequence
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import com.mapineda48.phonotrainer.core.domain.spi.ClipLeases
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

// Working stubs of the contract freeze (work-plan 2.3): no clip is on the phone. WP-10 replaces this file.

class StubClipContentRepository @Inject constructor() : ClipContentRepository {
  override suspend fun clipData(id: JobId): ClipData = throw ClipDataException(AppError.Content.Missing)

  override suspend fun media(id: JobId): ClipMedia? = null

  override fun open(id: JobId): ClipLease = object : ClipLease {
    override val id: JobId = id

    override fun close() = Unit
  }

  override suspend fun compareRows(word: WordRef): List<CompareRow> = emptyList()
}

class StubShortRepository @Inject constructor() : ShortRepository {
  override fun shorts(id: JobId): Flow<List<ShortInfo>> = flowOf(emptyList())

  override suspend fun sequence(mode: FeedMode): FeedSequence = FeedSequence(mode, emptyList(), startIndex = 0)

  override suspend fun shortContaining(word: WordRef): ShortInfo? = null
}

class StubOccurrenceRepository @Inject constructor() : OccurrenceRepository {
  override fun changeCounts(): Flow<Map<ChangeLabel, ChangeCount>> = flowOf(emptyMap())

  override suspend fun examples(change: ChangeLabel, limit: Int, onlyClip: JobId?): List<Occurrence> = emptyList()

  override suspend fun examplesWithSymbol(symbol: String, limit: Int): List<Occurrence> = emptyList()

  override suspend fun quizPool(perChange: Int): Map<ChangeLabel, List<Occurrence>> = emptyMap()

  override suspend fun variants(word: String): List<WordVariant> = emptyList()

  override fun totals(): Flow<CorpusTotals> = flowOf(CorpusTotals(clips = 0, shorts = 0, words = 0, occurrences = 0))
}

class StubClipIndexer @Inject constructor() : ClipIndexer {
  override suspend fun index(id: JobId): IndexOutcome = IndexOutcome.Missing

  override suspend fun reindexStale() = Unit

  override suspend fun drop(ids: Set<JobId>) = Unit
}

class StubClipLeases @Inject constructor() : ClipLeases {
  override fun isOpen(id: JobId): Boolean = false

  override val released: Flow<JobId> = emptyFlow()
}

@Module
@InstallIn(SingletonComponent::class)
interface CorpusModule {
  @Binds
  fun content(impl: StubClipContentRepository): ClipContentRepository

  @Binds
  fun shorts(impl: StubShortRepository): ShortRepository

  @Binds
  fun occurrences(impl: StubOccurrenceRepository): OccurrenceRepository

  @Binds
  fun indexer(impl: StubClipIndexer): ClipIndexer

  @Binds
  fun leases(impl: StubClipLeases): ClipLeases
}
