package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.database.learning.dao.ProgressDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.database.learning.entity.SavedItemEntity
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Saved shorts and words (spec F-09; architecture 10). A short is saved by its anchor, job + first phrase, a word by
 * job + phrase + word and the short that holds it (spec F-09 item 1); the change kept with an item is the one the
 * learner was shown ([SpotlightChanges]), and a saved short's spotlight word is found again from it (the version 1
 * row of a SHORT has no word column to keep it in). Items open the short that contains their anchor under the current shorts rule
 * (spec F-09-AC3). "When last watched" comes from this phone's seen state: an item saved after its short was seen
 * starts with that time, and seeing the short again updates it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomSavedRepository @Inject constructor(
  private val saved: SavedDao,
  private val progress: ProgressDao,
  private val shorts: ShortRepository,
  private val clips: ClipRepository,
  private val spotlights: SpotlightChanges,
  private val day: StudyDay,
) : SavedRepository {
  override fun saved(): Flow<List<SavedItem>> =
    combine(saved.observeAll(), clips.clips(ClipQuery())) { rows, clipList -> rows to clipList.associateBy { it.id } }
      .mapLatest { (rows, byId) -> rows.map { it.toItem(byId[JobId(it.jobId)]) } }
      .distinctUntilChanged()

  override fun isSaved(id: ShortId): Flow<Boolean> =
    saved().map { items -> items.any { it.kind == SavedKind.SHORT && it.short == id } }.distinctUntilChanged()

  override suspend fun saveShort(id: ShortId, spotlight: WordRef?) {
    val change = if (spotlight != null) spotlights.ofShort(id) else null
    val short = shorts.shorts(id.jobId).first().firstOrNull { it.id == id }
    saved.insert(
      SavedItemEntity(
        kind = SavedDao.SHORT,
        jobId = id.jobId.value,
        segment = id.firstSegment,
        word = SavedItemEntity.NO_WORD,
        shortFirstSegment = id.firstSegment,
        change = change?.value,
        savedAt = day.now().toEpochMilli(),
        lastWatchedAt = lastSeen(id.jobId, id.firstSegment, short),
      ),
    )
  }

  override suspend fun saveWord(word: WordRef) {
    val short = shorts.shortContaining(word)
    val change: ChangeLabel? = spotlights.ofWord(word)
    saved.insert(
      SavedItemEntity(
        kind = SavedDao.WORD,
        jobId = word.jobId.value,
        segment = word.segment,
        word = word.word,
        shortFirstSegment = short?.id?.firstSegment ?: word.segment,
        change = change?.value,
        savedAt = day.now().toEpochMilli(),
        lastWatchedAt = lastSeen(word.jobId, word.segment, short),
      ),
    )
  }

  override suspend fun unsave(itemId: Long) {
    saved.delete(itemId)
  }

  /**
   * A saved short's spotlight word (spec F-09 item 1, F-09-AC1): the word its kept change spotlights in it, by the
   * spotlight rules (its default spotlight when that has the change, else its clearest instance of the change, as a
   * change mode shows it). Null for a short saved without a spotlight, or one whose clip has no shorts here.
   */
  private suspend fun spotlightWord(job: JobId, short: ShortInfo?, change: ChangeLabel?): WordRef? {
    if (short == null || change == null) return null
    short.spotlight?.takeIf { it.change == change }?.let { return it.word }
    return shorts.sequence(FeedMode.Change(change, onlyClip = job)).items.firstOrNull { it.id == short.id }?.spotlight?.word
  }

  /** When the short holding [segment] was last seen on this phone, or null. */
  private suspend fun lastSeen(job: JobId, segment: Int, short: ShortInfo?): Long? {
    val rows = progress.seen().filter { it.jobId == job.value }
    if (short == null) return rows.filter { it.firstSegment == segment }.maxOfOrNull { it.lastSeenAt }
    return latestIn(rows, short, { it.firstSegment }, { it.lastSeenAt })
  }

  private suspend fun SavedItemEntity.toItem(clip: ClipSummary?): SavedItem {
    val job = JobId(jobId)
    val isWord = kind == SavedDao.WORD
    val anchor = WordRef(job, segment, if (isWord) word else 0)
    val info = shorts.shortContaining(anchor)
    val label = change?.let(::ChangeLabel)
    return SavedItem(
      id = id,
      kind = if (isWord) SavedKind.WORD else SavedKind.SHORT,
      word = if (isWord) anchor else spotlightWord(job, info, label),
      short = info?.id ?: ShortId(job, shortFirstSegment ?: segment),
      change = label,
      savedAt = Instant.ofEpochMilli(savedAt),
      lastWatchedAt = lastWatchedAt?.let(Instant::ofEpochMilli),
      clipTitle = clip?.title.orEmpty(),
      available = clip?.availability == ClipAvailability.OnThisPhone,
    )
  }
}
