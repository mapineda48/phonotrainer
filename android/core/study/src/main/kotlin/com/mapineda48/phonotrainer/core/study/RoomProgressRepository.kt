package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.database.learning.dao.PracticeDao
import com.mapineda48.phonotrainer.core.database.learning.dao.ProgressDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SetDao
import com.mapineda48.phonotrainer.core.database.learning.entity.FeedPositionEntity
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedPosition
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.KeepWatching
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Seen state, resume points and the feed's positions (architecture 10; spec F-06 items 4 and 5, F-03 item 4, 5.2).
 * - **Seen**: a visit that actually played 80 % of the short (the feed excludes seeks) marks it seen; the saved items
 *   anchored in it were watched then, and its spotlight change is met (spec F-17 item 5). Any real playback counts as
 *   watching the clip (eviction order, architecture 7.5).
 * - **Positions**: every position is the Watch tab's last one (`WATCH_LAST`); a position outside today's set is also
 *   Home's "Keep watching" (`KEEP_WATCHING`), which shows while the last position is not today's set; an "In order"
 *   position is the clip's resume point (spec F-06-AC10). Anchors are mapped to the current shorts by phrase range.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomProgressRepository @Inject constructor(
  private val progress: ProgressDao,
  private val sets: SetDao,
  private val saved: SavedDao,
  private val practice: PracticeDao,
  private val shorts: ShortRepository,
  private val clips: ClipRepository,
  private val spotlights: SpotlightChanges,
  private val day: StudyDay,
) : ProgressRepository {
  override fun seen(): Flow<Set<ShortId>> = progress.observeSeen()
    .map { rows -> rows.mapTo(HashSet()) { ShortId(JobId(it.jobId), it.firstSegment) } }
    .distinctUntilChanged()

  override suspend fun recordPlayed(id: ShortId, playedMs: Long, lengthMs: Long) {
    if (playedMs <= 0) return
    val now = day.now().toEpochMilli()
    val job = id.jobId.value
    progress.setWatched(job, now)
    if (!isSeen(playedMs, lengthMs)) return
    progress.markSeen(job, id.firstSegment, now)
    val lastSegment = shorts.shorts(id.jobId).first().firstOrNull { it.id == id }?.lastSegment ?: id.firstSegment
    saved.touchWatched(job, id.firstSegment, maxOf(id.firstSegment, lastSegment), now)
    spotlights.ofShort(id)?.let { practice.meet(it.value, now) }
  }

  override fun resume(id: JobId): Flow<ShortId?> =
    combine(progress.observeResume(id.value), shorts.shorts(id)) { segment, list ->
      segment?.let { s -> list.firstOrNull { s in it.id.firstSegment..maxOf(it.id.firstSegment, it.lastSegment) }?.id ?: ShortId(id, s) }
    }.distinctUntilChanged()

  override fun lastFeed(): Flow<FeedPosition?> =
    progress.observeSlot(ProgressDao.WATCH_LAST).map { it?.toPosition() }.distinctUntilChanged()

  override suspend fun saveFeedPosition(position: FeedPosition) {
    val mode = position.mode
    progress.saveSlot(position.toEntity(ProgressDao.WATCH_LAST))
    if (!isTodaysSet(mode)) progress.saveSlot(position.toEntity(ProgressDao.KEEP_WATCHING))
    if (mode is FeedMode.InOrder && mode.jobId == position.short.jobId) {
      progress.setResume(mode.jobId.value, position.short.firstSegment, position.positionMs)
    }
  }

  override fun keepWatching(): Flow<KeepWatching?> {
    val todaysSet = day.dates().flatMapLatest { sets.observeLatest(SetDao.TODAY, it.toString()) }.map { it?.set?.id }
    return combine(
      progress.observeSlot(ProgressDao.WATCH_LAST),
      progress.observeSlot(ProgressDao.KEEP_WATCHING),
      todaysSet,
    ) { last, keep, todayId ->
      val lastMode = last?.let { StudyJson.mode(it.modeJson) }
      if (todayId != null && lastMode == FeedMode.Mix(todayId)) null else keep?.toPosition()
    }.distinctUntilChanged().flatMapLatest { position ->
      if (position == null) {
        flowOf(null)
      } else {
        clips.clip(position.short.jobId).map { detail ->
          detail?.summary?.let { KeepWatching(it, position.short, position.positionMs) }
        }
      }
    }.distinctUntilChanged()
  }

  override suspend fun markOpened(id: JobId) = progress.setOpened(id.value, day.now().toEpochMilli())

  private suspend fun isTodaysSet(mode: FeedMode): Boolean {
    if (mode !is FeedMode.Mix) return false
    val set = sets.get(mode.setId)?.set ?: return false
    return set.kind == SetDao.TODAY && set.localDate == day.today().toString()
  }

  private fun FeedPosition.toEntity(slot: String) = FeedPositionEntity(
    slot = slot,
    modeJson = StudyJson.mode(mode),
    jobId = short.jobId.value,
    firstSegment = short.firstSegment,
    positionMs = positionMs,
    updatedAt = updatedAt.toEpochMilli(),
  )

  private fun FeedPositionEntity.toPosition(): FeedPosition? {
    val mode = StudyJson.mode(modeJson) ?: return null
    val job = jobId ?: return null
    return FeedPosition(mode, ShortId(JobId(job), firstSegment ?: 0), positionMs ?: 0, Instant.ofEpochMilli(updatedAt))
  }

  companion object {
    /** Seen at 80 % of the short's length actually played in one visit (spec F-06 item 5, F-06-AC11). */
    fun isSeen(playedMs: Long, lengthMs: Long): Boolean = lengthMs > 0 && playedMs * 10 >= lengthMs * 8
  }
}
