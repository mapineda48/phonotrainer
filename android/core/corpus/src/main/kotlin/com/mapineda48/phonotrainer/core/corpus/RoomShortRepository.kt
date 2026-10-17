package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.analysis.OccurrenceRecord
import com.mapineda48.phonotrainer.core.analysis.SpotlightMode
import com.mapineda48.phonotrainer.core.analysis.SpotlightRules
import com.mapineda48.phonotrainer.core.database.library.dao.OccurrenceDao
import com.mapineda48.phonotrainer.core.database.library.dao.ShortDao
import com.mapineda48.phonotrainer.core.database.library.dao.ShortRow
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedSequence
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The shorts of the phone's clips and the feed's sequences (spec 5.3, F-06, F-07; architecture 9.2, 9.3):
 * - **In order**: every short of one playable clip in timeline order, each with its default spotlight (rules 3 to 5),
 *   shorts without a candidate included ("No labelled change here").
 * - **Change** ("only flapping"): every playable short with at least one candidate of the change (an eligible row:
 *   not low confidence, at least 60 ms, heard), its spotlight forced to the clearest such candidate (rule 2; spec
 *   F-06-AC5), ordered clearest first with one short per distinct word first, then the rest (spec 5.3). Its length
 *   is by construction `OccurrenceRepository.changeCounts()`'s short count (spec D-48, F-03-AC7).
 * - **Saved**: the shorts of the saved items (saved shorts, and the shorts holding saved words) whose clip is playable,
 *   most recently saved first, each once, spotlit on the saved word (rule 1; F-09).
 * - **Mix**: sets are built and stored by `SetRepository` (WP-14); this answers an empty sequence for it.
 *
 * Every sequence starts at index 0; where the feed resumes is the feed's choice (`FeedStart`).
 */
internal class RoomShortRepository @Inject constructor(
  private val shortDao: ShortDao,
  private val occurrenceDao: OccurrenceDao,
  // A Provider: :core:study's repositories may read shorts too, so neither side builds the other eagerly.
  private val saved: Provider<SavedRepository>,
) : ShortRepository {
  override fun shorts(id: JobId): Flow<List<ShortInfo>> =
    shortDao.shortsOf(id.value).map { rows -> rows.map { it.toInfo() } }

  override suspend fun sequence(mode: FeedMode): FeedSequence = when (mode) {
    is FeedMode.InOrder -> FeedSequence(mode, shortDao.playableShortsOf(mode.jobId.value).map { it.toInfo() }, 0)
    is FeedMode.Change -> FeedSequence(mode, changeSequence(mode.label, mode.onlyClip), 0)
    FeedMode.Saved -> FeedSequence(mode, savedSequence(), 0)
    is FeedMode.Mix -> FeedSequence(mode, emptyList(), 0)
  }

  override suspend fun shortContaining(word: WordRef): ShortInfo? =
    shortDao.shortContaining(word.jobId.value, word.segment)?.toInfo()

  private suspend fun changeSequence(label: ChangeLabel, onlyClip: JobId?): List<ShortInfo> {
    val candidates = occurrenceDao.candidatesOf(label.value, onlyClip?.value).map { it.toRecord() }
    if (candidates.isEmpty()) return emptyList()
    val rows = shortDao.playableShorts().associateBy { it.shortId }
    val picks = candidates.groupBy { ShortId(it.word.jobId, it.shortFirstSegment) }.mapNotNull { (id, records) ->
      val row = rows[id] ?: return@mapNotNull null
      val spotlight = SpotlightRules.choose(records, SpotlightMode.Change(label)) ?: return@mapNotNull null
      val record = records.first { it.word == spotlight.word && it.change == label }
      ChangePick(row, spotlight, record)
    }
    return ChangeOrder.order(picks).map { it.row.toInfo(it.spotlight) }
  }

  private suspend fun savedSequence(): List<ShortInfo> {
    val items = saved.get().saved().first().sortedByDescending { it.savedAt }
    val playable = HashMap<String, Boolean>()
    val seen = HashSet<ShortId>()
    val out = ArrayList<ShortInfo>()
    for (item in items) {
      val jobId = item.short.jobId.value
      if (!playable.getOrPut(jobId) { shortDao.playableCount(jobId) > 0 }) continue
      // Anchored to job + segment (+ word), never to a short id: a later shorts rule finds the short that holds it. A
      // saved short keeps its first phrase as its anchor (F-09 item 1), a saved word its own phrase.
      val anchor = if (item.kind == SavedKind.SHORT) item.short.firstSegment else item.word?.segment ?: item.short.firstSegment
      val row = shortDao.shortContaining(jobId, anchor) ?: continue
      if (!seen.add(row.shortId)) continue
      out += row.toInfo(savedSpotlight(row, item.word, item.change))
    }
    return out
  }

  private suspend fun savedSpotlight(row: ShortRow, word: WordRef?, change: ChangeLabel?): Spotlight? {
    if (word == null) return row.short.defaultSpotlight()
    val records = occurrenceDao.rowsOfShort(row.short.jobId, row.short.firstSegment).map { it.toRecord() }
    return SpotlightRules.choose(records, SpotlightMode.Saved(word, change))
  }
}

/** A short of a change mode, its spotlight and the occurrence the spotlight is. */
internal class ChangePick(val row: ShortRow, val spotlight: Spotlight, val record: OccurrenceRecord)

/**
 * The order of a change mode (spec 5.3): clearest instance first (lowest `diff_cost` of each short's spotlight, ties
 * by clip, phrase and word), **one short per distinct word first** (by `WordKey`: "water" once before "water" again),
 * then the rest in the same order.
 */
internal object ChangeOrder {
  fun order(picks: List<ChangePick>): List<ChangePick> {
    val sorted = picks.sortedWith(
      compareBy<ChangePick> { it.record.diffCost }
        .thenBy { it.record.word.jobId.value }
        .thenBy { it.record.word.segment }
        .thenBy { it.record.word.word },
    )
    val words = HashSet<String>()
    // A word without letters ("2020") has no key to repeat: it always counts as new.
    val (first, rest) = sorted.partition { it.record.wordKey.isEmpty() || words.add(it.record.wordKey) }
    return first + rest
  }
}
