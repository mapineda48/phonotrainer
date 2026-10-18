package com.mapineda48.phonotrainer.core.sync.storage

import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Roles
import com.mapineda48.phonotrainer.core.sync.transfer.DownloadPlanner
import com.mapineda48.phonotrainer.core.sync.transfer.Want
import com.mapineda48.phonotrainer.core.sync.transfer.WantKind
import java.time.Instant

/** The verified bytes downloaded clips take: study packs (clip data and both audio tracks) and videos (7.5). */
internal data class ClipBytes(val studyPacks: Long, val videos: Long) {
  val total: Long get() = studyPacks + videos
}

/** Whether a download may start now, and if not, why (architecture 7.4, 7.5). */
internal sealed interface Admission {
  data object Allowed : Admission

  /** Below the free-space floor: every download pauses. */
  data object BelowFloor : Admission

  /** This file does not fit the budget (a video only after evicting lower-ranked videos). */
  data object OverBudget : Admission
}

/**
 * The storage budget and the free-space floor (architecture 7.5, spec 7.10, D-19), as pure functions of the rows:
 * - the budget (1 GB, 2 GB by default, 5 GB, or free space only) covers downloaded clips, study packs and videos;
 * - the floor is the smaller of 1 GB and 10 % of the phone's storage; below it downloads pause;
 * - a study pack fits while study packs alone stay within the budget (videos make room afterwards); a video fits when
 *   it does after evicting the videos ranked below it, so a download never evicts a video that ranks higher and two
 *   videos never take each other's place in turn;
 * - over the budget, videos go first from the least recently watched clip that is not kept, not in today's set and not
 *   opened in the last 24 hours; study packs are never removed automatically, and when they alone exceed the budget by
 *   10 % the learner is told "Storage budget reached".
 */
internal object StoragePolicy {
  /** 1 GB, the floor's upper bound. */
  const val FLOOR_CAP_BYTES: Long = 1_000_000_000

  /** 10 % of the phone's storage, the floor's other bound. */
  private const val FLOOR_PERCENT = 10
  private const val PERCENT = 100

  /** Study packs alone may exceed the budget by this much before the learner is told (7.10). */
  private const val OVER_BUDGET_PERCENT = 110

  fun floorBytes(totalBytes: Long): Long = minOf(FLOOR_CAP_BYTES, totalBytes * FLOOR_PERCENT / PERCENT)

  fun usage(artifacts: List<ArtifactEntity>): ClipBytes {
    var packs = 0L
    var videos = 0L
    for (row in artifacts) {
      if (row.localState != LocalStates.READY || row.localFile == null) continue
      val bytes = row.localBytes ?: 0
      when (row.role) {
        in Roles.PACK -> packs += bytes
        Roles.VIDEO -> videos += bytes
      }
    }
    return ClipBytes(packs, videos)
  }

  /** Study packs alone exceed the budget by 10 %: "Storage budget reached. Remove clips or raise the budget." */
  fun packsOverBudget(usage: ClipBytes, budget: StorageBudget): Boolean =
    budget is StorageBudget.Limit && usage.studyPacks * PERCENT > budget.bytes * OVER_BUDGET_PERCENT

  /**
   * Whether [want] may download now. [freeBytes] is what `getAllocatableBytes` reports, [floorBytes] the floor;
   * [evictable] are the videos the budget step may remove, lowest-ranked first ([evictionOrder]).
   */
  fun admit(
    want: Want,
    remainingBytes: Long,
    usage: ClipBytes,
    budget: StorageBudget,
    freeBytes: Long,
    floorBytes: Long,
    evictable: List<Want>,
    rank: Comparator<ClipEntity>,
  ): Admission {
    if (freeBytes - remainingBytes < floorBytes) return Admission.BelowFloor
    val limit = (budget as? StorageBudget.Limit)?.bytes ?: return Admission.Allowed
    return when (want.kind) {
      WantKind.PACK, WantKind.SECOND_TRACK ->
        if (usage.studyPacks + want.bytes <= limit) Admission.Allowed else Admission.OverBudget
      WantKind.VIDEO -> {
        val below = evictable.filter { it.jobId != want.jobId && rank.compare(it.clip, want.clip) > 0 }
        val room = below.sumOf { it.artifact.localBytes ?: 0 }
        if (usage.total + want.bytes - room <= limit) Admission.Allowed else Admission.OverBudget
      }
    }
  }

  /**
   * The videos the budget step may remove, in the order it removes them: least recently watched first (never-watched
   * clips before watched ones), then the oldest; never a kept clip, one of [protectedClips] (today's set and clips
   * opened in the last 24 h) or [except].
   */
  fun evictionOrder(
    clips: List<ClipEntity>,
    artifacts: List<ArtifactEntity>,
    lastWatched: Map<String, Instant>,
    protectedClips: Set<String>,
    except: Set<String> = emptySet(),
  ): List<Want> {
    val byJob = clips.associateBy { it.jobId }
    return artifacts.asSequence()
      .filter { it.role == Roles.VIDEO && it.localState == LocalStates.READY && it.localFile != null }
      .mapNotNull { row -> byJob[row.jobId]?.let { Want(it, row, WantKind.VIDEO) } }
      .filter { !it.clip.kept && it.jobId !in protectedClips && it.jobId !in except }
      .sortedWith(
        compareBy<Want> { lastWatched[it.jobId] ?: Instant.MIN }.thenBy { it.clip.createdAt ?: 0L }.thenBy { it.jobId },
      )
      .toList()
  }

  /** The videos to remove so the downloaded clips fit [budget] again, from [order] (7.5). */
  fun toEvict(usage: ClipBytes, budget: StorageBudget, order: List<Want>): List<Want> {
    val limit = (budget as? StorageBudget.Limit)?.bytes ?: return emptyList()
    var total = usage.total
    val out = ArrayList<Want>()
    for (want in order) {
      if (total <= limit) break
      out += want
      total -= want.artifact.localBytes ?: 0
    }
    return out
  }

  /** The planner's video rank (kept, then most recently watched, then newest): a download only evicts lower ones. */
  fun videoRank(lastWatched: Map<String, Instant>): Comparator<ClipEntity> = DownloadPlanner.videoComparator(lastWatched)
}
