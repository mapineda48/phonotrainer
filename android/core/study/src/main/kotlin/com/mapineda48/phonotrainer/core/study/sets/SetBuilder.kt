package com.mapineda48.phonotrainer.core.study.sets

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A playable short of the Mix: it has a spotlight (spec F-06 item 2); [clipAddedAt] orders "newest clip first". */
data class SetCandidate(val short: ShortInfo, val spotlight: Spotlight, val clipAddedAt: Instant)

/** A saved short (or the short holding a saved word) with its saved spotlight and when it was last watched here. */
data class SavedCandidate(val short: ShortInfo, val spotlight: Spotlight?, val lastWatchedAt: Instant?)

/** One short of a set, in set order. */
data class SetPick(val short: ShortInfo, val spotlight: Spotlight, val saved: Boolean) {
  val id: ShortId get() = short.id
  val clip: JobId get() = short.id.jobId
  val change: ChangeLabel get() = spotlight.change
}

/** A short shown just before a set: its clip and spotlight change. */
data class Neighbour(val clip: JobId, val change: ChangeLabel?)

/**
 * What a set is built from. [shorts]: the eligible shorts of playable clips; [saved]: the saved ones, most recently
 * saved first; [lastSeen]: when each short was last seen (absent = unseen); [excluded]: clips left out of the Mix;
 * [before]: the clips of the last shorts of the previous set, in order, so the Mix never shows three shorts of one clip
 * in a row across two sets (spec F-06-AC4).
 */
data class SetInput(
  val shorts: List<SetCandidate>,
  val saved: List<SavedCandidate>,
  val lastSeen: Map<ShortId, Instant>,
  val excluded: Set<JobId>,
  val today: LocalDate,
  val zone: ZoneId,
  val before: List<Neighbour> = emptyList(),
)

/**
 * Today's set and extra sets (spec F-08 item 2, architecture 10), a pure function. Composition, in this order until 8
 * shorts are chosen: (1) up to 2 saved shorts not watched for 3 or more days (never watched here counts as long ago);
 * (2) unseen shorts, newest clip first, "Safe to produce" spotlights first, then in timeline order; (3) shorts not seen
 * for 7 or more days, least recently seen first. Days are local calendar days. A clip is not given more shorts than
 * can still be interleaved (at most ⌊(2n + 2) / 3⌋ of n), unless no other clip has any left.
 *
 * Order: the order of picks with the fewest runs of three shorts of one clip, then the fewest neighbours with the same
 * spotlight change (spec: "when another order exists"), searched depth-first in the composition order with
 * backtracking over the at most 8 picks, so the first best order keeps the composition order where it can.
 */
object SetBuilder {
  const val SHORTS = 8
  const val SAVED_MAX = 2
  const val SAVED_AFTER_DAYS = 3L
  const val STALE_AFTER_DAYS = 7L

  /** The version of these rules, stored with each set (`study_set.rule_version`). */
  const val RULE_VERSION = 1

  /** The set of [input]'s day; empty when nothing is due (the "Mix with nothing left" end card, spec F-06 item 9). */
  fun build(input: SetInput): List<SetPick> {
    val savedById = savedSpotlights(input)
    val dueSaved = input.saved
      .filter { it.short.id.jobId !in input.excluded && it.spotlight != null && dueAgain(it.lastWatchedAt, input) }
      .withIndex()
      .sortedWith(compareBy<IndexedValue<SavedCandidate>>({ it.value.lastWatchedAt ?: Instant.MIN }, { it.index }))
      .map { SetPick(it.value.short, it.value.spotlight!!, saved = true) }
      .distinctBy { it.id }
    val eligible = input.shorts.filter { it.short.id.jobId !in input.excluded }
    val unseen = eligible
      .filter { it.short.id !in input.lastSeen }
      .sortedWith(
        compareByDescending<SetCandidate> { it.clipAddedAt }
          .thenByDescending { it.short.id.jobId.value }
          .thenBy { if (it.spotlight.advice == Advice.PRODUCE) 0 else 1 }
          .thenBy { it.short.ordinal },
      )
    val stale = eligible
      .filter { candidate -> input.lastSeen[candidate.short.id]?.let { daysSince(it, input) >= STALE_AFTER_DAYS } == true }
      .sortedWith(
        compareBy<SetCandidate> { input.lastSeen.getValue(it.short.id) }
          .thenByDescending { it.clipAddedAt }
          .thenBy { it.short.ordinal },
      )
    val priority = dueSaved + (unseen + stale).map { it.pick(savedById) }
    return arrange(choose(priority, savedMax = SAVED_MAX), input.before)
  }

  /**
   * "Replay the mix" (spec F-06 item 9): a set from the seen shorts, least recently seen first, with the same clip and
   * change interleaving. Empty when nothing was seen.
   */
  fun replay(input: SetInput): List<SetPick> {
    val savedById = savedSpotlights(input)
    val seen = input.shorts
      .filter { it.short.id.jobId !in input.excluded && it.short.id in input.lastSeen }
      .sortedWith(
        compareBy<SetCandidate> { input.lastSeen.getValue(it.short.id) }
          .thenByDescending { it.clipAddedAt }
          .thenBy { it.short.ordinal },
      )
      .map { it.pick(savedById) }
    return arrange(choose(seen, savedMax = Int.MAX_VALUE), input.before)
  }

  /** Saved shorts keep their saved spotlight wherever a set uses them (spec F-07 spotlight rule 1). */
  private fun savedSpotlights(input: SetInput): Map<ShortId, Spotlight> =
    input.saved.filter { it.spotlight != null }.reversed().associate { it.short.id to it.spotlight!! }

  private fun SetCandidate.pick(saved: Map<ShortId, Spotlight>): SetPick {
    val savedSpotlight = saved[short.id]
    return SetPick(short, savedSpotlight ?: spotlight, saved = savedSpotlight != null)
  }

  private fun dueAgain(lastWatched: Instant?, input: SetInput): Boolean =
    lastWatched == null || daysSince(lastWatched, input) >= SAVED_AFTER_DAYS

  private fun daysSince(at: Instant, input: SetInput): Long =
    ChronoUnit.DAYS.between(LocalDate.ofInstant(at, input.zone), input.today)

  /** The first [SHORTS] distinct picks of [priority], each clip within its share while another clip can fill in. */
  private fun choose(priority: List<SetPick>, savedMax: Int): List<SetPick> {
    val distinct = priority.distinctBy { it.id }
    val size = minOf(SHORTS, distinct.size)
    val cap = (2 * size + 2) / 3
    val chosen = ArrayList<SetPick>(size)
    val skipped = ArrayList<SetPick>()
    val perClip = HashMap<JobId, Int>()
    var saved = 0
    for (pick in distinct) {
      if (chosen.size >= size) break
      if (pick.saved && saved >= savedMax) continue
      if ((perClip[pick.clip] ?: 0) >= cap) {
        skipped += pick
        continue
      }
      chosen += pick
      perClip[pick.clip] = (perClip[pick.clip] ?: 0) + 1
      if (pick.saved) saved++
    }
    // No other clip has shorts left: the rest comes from the clips already chosen.
    for (pick in skipped) {
      if (chosen.size >= size) break
      chosen += pick
    }
    return chosen
  }

  /** The best order of [picks] (fewest clip runs of three, then fewest alike neighbours), after [before]. */
  private fun arrange(picks: List<SetPick>, before: List<Neighbour>): List<SetPick> {
    if (picks.size <= 1) return picks
    val search = Search(picks, before.takeLast(2))
    search.run()
    return search.best
  }

  private class Search(private val picks: List<SetPick>, private val before: List<Neighbour>) {
    var best: List<SetPick> = picks
    private var bestClipRuns = Int.MAX_VALUE
    private var bestAlike = Int.MAX_VALUE
    private val used = BooleanArray(picks.size)
    private val order = ArrayList<SetPick>(picks.size)

    fun run() = step(0, 0)

    private fun step(clipRuns: Int, alike: Int) {
      if (!better(clipRuns, alike)) return
      if (order.size == picks.size) {
        best = order.toList()
        bestClipRuns = clipRuns
        bestAlike = alike
        return
      }
      val last = neighbour(1)
      val beforeLast = neighbour(2)
      for (i in picks.indices) {
        if (used[i]) continue
        if (bestClipRuns == 0 && bestAlike == 0) return
        val pick = picks[i]
        val run = last?.clip == pick.clip && beforeLast?.clip == pick.clip
        val same = last != null && last.change == pick.change
        used[i] = true
        order += pick
        step(clipRuns + if (run) 1 else 0, alike + if (same) 1 else 0)
        order.removeAt(order.lastIndex)
        used[i] = false
      }
    }

    /** The [back]-th short before the next position: from this order, then from the previous set. */
    private fun neighbour(back: Int): Neighbour? {
      val inOrder = order.size - back
      if (inOrder >= 0) return order[inOrder].let { Neighbour(it.clip, it.change) }
      return before.getOrNull(before.size + inOrder)
    }

    private fun better(clipRuns: Int, alike: Int): Boolean =
      clipRuns < bestClipRuns || (clipRuns == bestClipRuns && alike < bestAlike)
  }
}
