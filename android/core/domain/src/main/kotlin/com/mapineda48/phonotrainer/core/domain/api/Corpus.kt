package com.mapineda48.phonotrainer.core.domain.api

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
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import kotlinx.coroutines.flow.Flow

// :core:corpus (WP-10), architecture 3.4 and 9.

interface ClipContentRepository {
  /** The parsed analysis, LRU-cached; throws [ClipDataException] (Corrupt or Missing). */
  @Throws(ClipDataException::class)
  suspend fun clipData(id: JobId): ClipData

  /** Local audio (both tracks), video, poster, offset. */
  suspend fun media(id: JobId): ClipMedia?

  /** Defers deletion while a screen shows the clip. */
  fun open(id: JobId): ClipLease

  /** Lesson step 2, computed with :core:analysis (revision 3). */
  suspend fun compareRows(word: WordRef): List<CompareRow>
}

interface ShortRepository {
  fun shorts(id: JobId): Flow<List<ShortInfo>>

  /** Ordered shorts with the mode's spotlight. */
  suspend fun sequence(mode: FeedMode): FeedSequence

  suspend fun shortContaining(word: WordRef): ShortInfo?
}

interface OccurrenceRepository {
  /**
   * Two units per change (spec D-48, architecture 9.4). `shorts` = the playable shorts with at least one spotlight
   * candidate of that change, i.e. exactly what `sequence(FeedMode.Change(label))` plays: Home chips, mode picker,
   * Learn change cards. `occurrences` = eligible occurrences of that change in playable clips: the phenomenon page
   * only. An absent key is 0.
   */
  fun changeCounts(): Flow<Map<ChangeLabel, ChangeCount>>

  suspend fun examples(change: ChangeLabel, limit: Int = 8, onlyClip: JobId? = null): List<Occurrence>

  suspend fun examplesWithSymbol(symbol: String, limit: Int = 6): List<Occurrence>

  suspend fun quizPool(perChange: Int = 80): Map<ChangeLabel, List<Occurrence>>

  suspend fun variants(word: String): List<WordVariant>

  fun totals(): Flow<CorpusTotals>
}
