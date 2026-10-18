package com.mapineda48.phonotrainer.core.study.sets

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * [SetBuilder] (spec F-08 item 2, architecture 10): composition (F-08-AC1, F-09-AC2), interleaving (no two alike
 * spotlight changes in a row when another order exists, at most 2 consecutive shorts of one clip, F-06-AC4, also
 * across consecutive sets), the 3-day and 7-day rules in local days, exclusions, short sets and "Replay the mix".
 */
class SetBuilderTest {
  private val zone = ZoneId.of("America/Bogota")
  private val today = LocalDate.of(2026, 10, 10)
  private val now = today.atTime(9, 0).atZone(zone).toInstant()
  private val changes = listOf("flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion").map(::ChangeLabel)

  private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

  private fun short(
    job: String,
    ordinal: Int,
    change: ChangeLabel = changes[ordinal % changes.size],
    advice: Advice = Advice.PRODUCE,
  ): ShortInfo {
    val first = ordinal * 3
    val spotlight = Spotlight(WordRef(JobId(job), first, 1), change, advice, crossesBoundary = false)
    return ShortInfo(ShortId(JobId(job), first), ordinal + 1, 30, first + 2, first * 4_000L, first * 4_000L + 9_000, spotlight, 3)
  }

  private fun candidates(job: String, count: Int, addedDaysAgo: Long, change: (Int) -> ChangeLabel = { changes[it % changes.size] }) =
    (0 until count).map { SetCandidate(short(job, it, change(it)), short(job, it, change(it)).spotlight!!, daysAgo(addedDaysAgo)) }

  private fun input(
    shorts: List<SetCandidate>,
    saved: List<SavedCandidate> = emptyList(),
    lastSeen: Map<ShortId, Instant> = emptyMap(),
    excluded: Set<JobId> = emptySet(),
    before: List<Neighbour> = emptyList(),
    day: LocalDate = today,
  ) = SetInput(shorts, saved, lastSeen, excluded, day, zone, before)

  private fun List<SetPick>.maxClipRun(before: List<JobId> = emptyList()): Int {
    var best = 0
    var run = 0
    var last: JobId? = null
    for (clip in before + map { it.clip }) {
      run = if (clip == last) run + 1 else 1
      last = clip
      best = maxOf(best, run)
    }
    return best
  }

  private fun List<SetPick>.alikeNeighbours(): Int = zipWithNext().count { (a, b) -> a.change == b.change }

  /** F-08-AC1: two saved shorts back and six unseen ones, no two alike spotlight changes in a row. */
  @Test
  fun twoSavedAndSixUnseenShorts() {
    // 3 saved shorts last watched 4 days ago (seen then), and 20 unseen shorts in two clips.
    val old = candidates("old", 3, addedDaysAgo = 30)
    val saved = old.map { SavedCandidate(it.short, it.spotlight, lastWatchedAt = daysAgo(4)) }
    val unseen = candidates("a", 10, addedDaysAgo = 2) + candidates("b", 10, addedDaysAgo = 1)
    val lastSeen = old.associate { it.short.id to daysAgo(4) }

    val set = SetBuilder.build(input(old + unseen, saved, lastSeen))

    assertThat(set).hasSize(8)
    assertThat(set.count { it.saved }).isEqualTo(2)
    assertThat(set.filter { it.saved }.map { it.id }).containsAnyIn(old.map { it.short.id })
    assertThat(set.filterNot { it.saved }.map { it.id }).containsNoneIn(old.map { it.short.id })
    assertThat(set.filterNot { it.saved }.map { it.id.jobId.value }.toSet()).containsAnyOf("a", "b")
    assertThat(set.alikeNeighbours()).isEqualTo(0)
    assertThat(set.maxClipRun()).isAtMost(2)
  }

  @Test
  fun unseenShortsComeNewestClipFirstAndSafeToProduceFirst() {
    val newest = (0 until 4).map { i ->
      val advice = if (i < 2) Advice.UNDERSTAND else Advice.PRODUCE
      val info = short("new", i, advice = advice)
      SetCandidate(info, info.spotlight!!, daysAgo(0))
    }
    val older = candidates("older", 6, addedDaysAgo = 5)
    val oldest = candidates("oldest", 6, addedDaysAgo = 9)

    val set = SetBuilder.build(input(newest + older + oldest))

    assertThat(set.map { it.id }).containsAtLeastElementsIn(newest.map { it.short.id })
    assertThat(set.count { it.clip.value == "older" }).isEqualTo(4)
    assertThat(set.none { it.clip.value == "oldest" }).isTrue()
    // Among the newest clip's shorts the "Safe to produce" ones come before the "Recognize only" ones.
    val inNew = set.filter { it.clip.value == "new" }
    assertThat(inNew.first().spotlight.advice).isEqualTo(Advice.PRODUCE)
  }

  @Test
  fun shortsSeenWithinSevenDaysWaitAndStaleOnesComeLeastRecentlySeenFirst() {
    val shorts = candidates("a", 6, addedDaysAgo = 40) + candidates("b", 6, addedDaysAgo = 40)
    val seen = shorts.withIndex().associate { (i, c) -> c.short.id to daysAgo(if (i % 2 == 0) 3L else 7L + i) }

    val set = SetBuilder.build(input(shorts, lastSeen = seen))

    // Only the shorts seen 7 or more days ago (odd positions) qualify.
    assertThat(set.map { it.id }).containsExactlyElementsIn(shorts.filterIndexed { i, _ -> i % 2 == 1 }.map { it.short.id })
    assertThat(SetBuilder.build(input(shorts, lastSeen = shorts.associate { it.short.id to daysAgo(6) }))).isEmpty()
  }

  @Test
  fun sevenDaysAreLocalCalendarDays() {
    val shorts = candidates("a", 2, addedDaysAgo = 40)
    // 23:30 local seven days before today, and 00:30 local six days before today.
    val sevenDays = today.minusDays(7).atTime(23, 30).atZone(zone).toInstant()
    val sixDays = today.minusDays(6).atTime(0, 30).atZone(zone).toInstant()
    val set = SetBuilder.build(input(shorts, lastSeen = mapOf(shorts[0].short.id to sevenDays, shorts[1].short.id to sixDays)))
    assertThat(set.map { it.id }).containsExactly(shorts[0].short.id)
  }

  /** F-09-AC2: a saved short watched today is not in tomorrow's set; one watched three days before can be. */
  @Test
  fun savedShortsReturnAfterThreeDays() {
    val shorts = candidates("a", 2, addedDaysAgo = 40)
    val watchedToday = SavedCandidate(shorts[0].short, shorts[0].spotlight, now)
    // Watched two days before today: three days before tomorrow.
    val watchedThreeDaysAgo = SavedCandidate(shorts[1].short, shorts[1].spotlight, daysAgo(2))
    val seen = mapOf(shorts[0].short.id to now, shorts[1].short.id to daysAgo(2))

    val tomorrow = SetBuilder.build(input(shorts, listOf(watchedToday, watchedThreeDaysAgo), seen, day = today.plusDays(1)))

    assertThat(SetBuilder.build(input(shorts, listOf(watchedToday, watchedThreeDaysAgo), seen))).isEmpty()
    assertThat(tomorrow.map { it.id }).doesNotContain(shorts[0].short.id)
    assertThat(tomorrow.map { it.id }).contains(shorts[1].short.id)
    assertThat(tomorrow.single { it.id == shorts[1].short.id }.saved).isTrue()
    // A saved short never watched on this phone (saved on another phone, or a word saved in a lesson) is due.
    val never = SavedCandidate(shorts[0].short, shorts[0].spotlight, null)
    assertThat(SetBuilder.build(input(shorts, listOf(never), seen)).map { it.id }).containsExactly(shorts[0].short.id)
  }

  @Test
  fun aSavedShortKeepsItsSavedSpotlight() {
    val shorts = candidates("a", 3, addedDaysAgo = 40)
    val savedSpot = Spotlight(WordRef(JobId("a"), 0, 0), ChangeLabel("linking"), Advice.PRODUCE, crossesBoundary = true)
    val set = SetBuilder.build(input(shorts, listOf(SavedCandidate(shorts[0].short, savedSpot, null))))
    val pick = set.single { it.id == shorts[0].short.id }
    assertThat(pick.saved).isTrue()
    assertThat(pick.spotlight).isEqualTo(savedSpot)
  }

  @Test
  fun excludedClipsAreLeftOutOfTheMixIncludingTheirSavedShorts() {
    val a = candidates("a", 5, addedDaysAgo = 1)
    val b = candidates("b", 5, addedDaysAgo = 2)
    val saved = listOf(SavedCandidate(a[0].short, a[0].spotlight, null))
    val set = SetBuilder.build(input(a + b, saved, excluded = setOf(JobId("a"))))
    assertThat(set.map { it.clip.value }.toSet()).containsExactly("b")
    assertThat(set).hasSize(5)
  }

  @Test
  fun fewerEligibleShortsMakeAShorterSetAndNoneMakeNoSet() {
    assertThat(SetBuilder.build(input(candidates("a", 3, addedDaysAgo = 1) + candidates("b", 1, 2)))).hasSize(4)
    assertThat(SetBuilder.build(input(emptyList()))).isEmpty()
  }

  @Test
  fun oneClipOnlyStillGivesEightShorts() {
    val set = SetBuilder.build(input(candidates("only", 12, addedDaysAgo = 1)))
    assertThat(set).hasSize(8)
  }

  @Test
  fun aClipGetsNoMoreShortsThanCanBeInterleaved() {
    // The newest clip has 20 unseen shorts, the other one 2: at most 6 of 8 come from the newest, so they interleave.
    val set = SetBuilder.build(input(candidates("new", 20, addedDaysAgo = 0) + candidates("old", 2, addedDaysAgo = 9)))
    assertThat(set.count { it.clip.value == "new" }).isEqualTo(6)
    assertThat(set.maxClipRun()).isAtMost(2)
  }

  @Test
  fun noTwoAlikeChangesInARowWhenAnotherOrderExistsElseAsFewAsPossible() {
    val flap = ChangeLabel("flapping")
    val link = ChangeLabel("linking")
    // Four flaps and four linkings: alternating is possible.
    val even = candidates("a", 4, 1) { flap } + candidates("b", 4, 2) { link }
    assertThat(SetBuilder.build(input(even)).alikeNeighbours()).isEqualTo(0)
    // Six flaps and two linkings: the best order has three alike pairs.
    val uneven = candidates("a", 3, 1) { flap } + candidates("b", 3, 2) { flap } + candidates("c", 2, 3) { link }
    val set = SetBuilder.build(input(uneven))
    assertThat(set).hasSize(8)
    assertThat(set.alikeNeighbours()).isEqualTo(3)
    assertThat(set.maxClipRun()).isAtMost(2)
  }

  /** F-06-AC4: twenty shorts of the Mix, over consecutive sets, never show three of one clip in a row. */
  @Test
  fun noThreeShortsOfOneClipInARow() {
    val all = candidates("a", 10, 1) + candidates("b", 10, 2) + candidates("c", 10, 3)
    val seen = HashMap<ShortId, Instant>()
    val listed = ArrayList<SetPick>()
    var before = emptyList<Neighbour>()
    while (listed.size < 20) {
      val set = SetBuilder.build(input(all, lastSeen = seen, before = before))
      assertThat(set).isNotEmpty()
      listed += set
      set.forEach { seen[it.id] = now }
      before = set.takeLast(2).map { Neighbour(it.clip, it.change) }
    }
    assertThat(listed.take(20).maxClipRun()).isAtMost(2)
  }

  @Test
  fun replayTheMixUsesSeenShortsLeastRecentlySeenFirst() {
    val shorts = candidates("a", 6, 40) + candidates("b", 6, 40)
    val seen = shorts.withIndex().associate { (i, c) -> c.short.id to daysAgo(i.toLong() % 7) }
    assertThat(SetBuilder.build(input(shorts, lastSeen = seen))).isEmpty()

    val replay = SetBuilder.replay(input(shorts, lastSeen = seen))

    assertThat(replay).hasSize(8)
    // The 8 least recently seen: those seen 6, 5, 4, 3 days ago (and 2 days ago), never the ones seen today.
    val seenToday = shorts.filterIndexed { i, _ -> i % 7 == 0 }.map { it.short.id }
    assertThat(replay.map { it.id }).containsNoneIn(seenToday)
    assertThat(SetBuilder.replay(input(shorts))).isEmpty()
  }

  @Test
  fun theSameInputGivesTheSameSet() {
    val shorts = candidates("a", 9, 1) + candidates("b", 9, 2)
    assertThat(SetBuilder.build(input(shorts))).isEqualTo(SetBuilder.build(input(shorts)))
  }
}
