package com.mapineda48.phonotrainer.core.testing.fake.corpus

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// Settable fakes of the :core:corpus interfaces (work-plan 4.6; WP-10 owns this folder after B1).

class FakeClipContentRepository : ClipContentRepository {
  val data = mutableMapOf<JobId, ClipData>()
  val mediaById = mutableMapOf<JobId, ClipMedia>()
  val compare = mutableMapOf<WordRef, List<CompareRow>>()
  val openLeases = mutableSetOf<JobId>()

  override suspend fun clipData(id: JobId): ClipData = data[id] ?: throw ClipDataException(AppError.Content.Missing)

  override suspend fun media(id: JobId): ClipMedia? = mediaById[id]

  override fun open(id: JobId): ClipLease {
    openLeases += id
    return object : ClipLease {
      override val id: JobId = id

      override fun close() {
        openLeases -= id
      }
    }
  }

  override suspend fun compareRows(word: WordRef): List<CompareRow> = compare[word].orEmpty()
}

class FakeShortRepository : ShortRepository {
  val shortsById = MutableStateFlow(emptyMap<JobId, List<ShortInfo>>())
  var sequenceFor: (FeedMode) -> FeedSequence = { FeedSequence(it, emptyList(), 0) }

  override fun shorts(id: JobId): Flow<List<ShortInfo>> = shortsById.map { it[id].orEmpty() }

  override suspend fun sequence(mode: FeedMode): FeedSequence = sequenceFor(mode)

  override suspend fun shortContaining(word: WordRef): ShortInfo? =
    shortsById.value[word.jobId].orEmpty().lastOrNull { it.id.firstSegment <= word.segment && word.segment <= it.lastSegment }
}

class FakeOccurrenceRepository : OccurrenceRepository {
  val counts = MutableStateFlow(emptyMap<ChangeLabel, ChangeCount>())
  val totalsState = MutableStateFlow(CorpusTotals(0, 0, 0, 0))
  val byChange = mutableMapOf<ChangeLabel, List<Occurrence>>()
  val bySymbol = mutableMapOf<String, List<Occurrence>>()
  val variantsByWord = mutableMapOf<String, List<WordVariant>>()

  override fun changeCounts(): Flow<Map<ChangeLabel, ChangeCount>> = counts

  override suspend fun examples(change: ChangeLabel, limit: Int, onlyClip: JobId?): List<Occurrence> =
    byChange[change].orEmpty().filter { onlyClip == null || it.word.jobId == onlyClip }.take(limit)

  override suspend fun examplesWithSymbol(symbol: String, limit: Int): List<Occurrence> = bySymbol[symbol].orEmpty().take(limit)

  override suspend fun quizPool(perChange: Int): Map<ChangeLabel, List<Occurrence>> = byChange.mapValues { it.value.take(perChange) }

  override suspend fun variants(word: String): List<WordVariant> = variantsByWord[word].orEmpty()

  override fun totals(): Flow<CorpusTotals> = totalsState
}

class FakeClipIndexer(var outcome: (JobId) -> IndexOutcome = { IndexOutcome.Indexed(shorts = 0, occurrences = 0) }) : ClipIndexer {
  val indexed = mutableListOf<JobId>()
  val dropped = mutableListOf<Set<JobId>>()
  var reindexes = 0
    private set

  override suspend fun index(id: JobId): IndexOutcome {
    indexed += id
    return outcome(id)
  }

  override suspend fun reindexStale() {
    reindexes++
  }

  override suspend fun drop(ids: Set<JobId>) {
    dropped += ids
  }
}

class FakeClipLeases : ClipLeases {
  val open = mutableSetOf<JobId>()
  private val releases = MutableSharedFlow<JobId>(extraBufferCapacity = 16)

  override fun isOpen(id: JobId): Boolean = id in open

  override val released: Flow<JobId> = releases

  /** Closes the lease on [id] and tells whoever listens. */
  fun release(id: JobId) {
    open -= id
    releases.tryEmit(id)
  }
}
