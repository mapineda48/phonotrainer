package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.database.learning.dao.ProgressDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SetDao
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.spi.ClipRemovalHook
import com.mapineda48.phonotrainer.core.domain.spi.StudyProtection
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * What the storage budget must not evict (architecture 7.5): the clips of today's set and the clips opened in the last
 * 24 h; and when each clip was last watched, so the least recently watched video goes first.
 */
internal class RoomStudyProtection @Inject constructor(
  private val sets: SetDao,
  private val progress: ProgressDao,
  private val day: StudyDay,
) : StudyProtection {
  override suspend fun protectedClips(): Set<JobId> {
    val today = sets.clipsOf(SetDao.TODAY, day.today().toString())
    val opened = progress.openedSince(day.now().minus(OPENED_WINDOW).toEpochMilli())
    return (today + opened).mapTo(LinkedHashSet(), ::JobId)
  }

  override suspend fun lastWatched(): Map<JobId, Instant> =
    progress.lastWatched().associate { JobId(it.jobId) to Instant.ofEpochMilli(it.lastWatchedAt) }

  private companion object {
    val OPENED_WINDOW: Duration = Duration.ofHours(24)
  }
}

/**
 * The study side of a clip's removal after its deletion on the computer (architecture 7.7): its shorts drop out of
 * every set (spec F-08 item 3, the count updates), its saved items go (spec F-09 item 4), and so do its seen state,
 * resume point and feed positions. Practice accuracy and the changes met are kept.
 */
internal class StudyRemovalHook @Inject constructor(
  private val sets: SetDao,
  private val saved: SavedDao,
  private val progress: ProgressDao,
) : ClipRemovalHook {
  override suspend fun onClipsRemoved(ids: Set<JobId>) {
    if (ids.isEmpty()) return
    val jobs = ids.map { it.value }
    for (chunk in jobs.chunked(ProgressDao.MAX_ARGS)) {
      sets.dropShortsOf(chunk)
      saved.deleteJobs(chunk)
    }
    progress.forgetClips(jobs)
  }
}
