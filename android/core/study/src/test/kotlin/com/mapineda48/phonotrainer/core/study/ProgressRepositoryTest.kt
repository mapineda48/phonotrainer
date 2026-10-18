package com.mapineda48.phonotrainer.core.study

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedPosition
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RoomProgressRepository] and [RoomStudyProtection] (architecture 10, 7.5): seen at 80 % of the time actually played
 * (F-06-AC11), the clip's resume short (F-06-AC10), the Watch tab's last mode and position (F-06-AC12), Home's "Keep
 * watching" (F-03-AC6, F-03 item 4), changes met by seen spotlights, and what eviction must keep.
 */
@RunWith(RobolectricTestRunner::class)
class ProgressRepositoryTest {
  private val h = StudyHarness()
  private val changes = listOf("flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion",
    "flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion")
  private val job = JobId("x")

  @After
  fun close() = h.close()

  private fun at(mode: FeedMode, short: Int, positionMs: Long = 1_000) =
    FeedPosition(mode, ShortId(job, short), positionMs, h.clock.now())

  /** F-06-AC11: a short is seen at 80 % of its length actually played in one visit. */
  @Test
  fun seenAtFourFifthsActuallyPlayed() = runTest {
    h.addClip("x", changes)
    val id = ShortId(job, 2)

    // A 20 s short: 10 s played, then the bar dragged to the end, counts 10 s (the feed leaves seeks out).
    h.progress.recordPlayed(id, playedMs = 10_000, lengthMs = 20_000)
    assertThat(h.progress.seen().first()).isEmpty()
    h.progress.recordPlayed(id, playedMs = 15_999, lengthMs = 20_000)
    assertThat(h.progress.seen().first()).isEmpty()

    h.progress.recordPlayed(id, playedMs = 16_000, lengthMs = 20_000)
    assertThat(h.progress.seen().first()).containsExactly(id)
    assertThat(RoomProgressRepository.isSeen(16_000, 20_000)).isTrue()
    assertThat(RoomProgressRepository.isSeen(1, 0)).isFalse()
    // Any real playback counts as watching the clip (eviction order).
    assertThat(h.protection.lastWatched()).containsExactly(job, h.clock.now())
  }

  @Test
  fun aSeenShortMeetsTheChangeItsSpotlightShowed() = runTest {
    h.addClip("x", changes)
    // No feed position: the short's own spotlight (short 1 spotlights linking).
    h.progress.recordPlayed(ShortId(job, 1), 3_000, 3_000)
    assertThat(h.practice.changesMet().first()).containsExactly(ChangeLabel("linking"))

    // In a change mode, the mode's change.
    h.progress.saveFeedPosition(at(FeedMode.Change(ChangeLabel("glottalization")), 3))
    h.progress.recordPlayed(ShortId(job, 3), 3_000, 3_000)
    assertThat(h.practice.changesMet().first()).containsExactly(ChangeLabel("linking"), ChangeLabel("glottalization"))

    // Not seen: nothing met.
    h.progress.recordPlayed(ShortId(job, 4), 1_000, 3_000)
    assertThat(h.practice.changesMet().first()).hasSize(2)
  }

  @Test
  fun seeingAShortUpdatesWhenItsSavedItemsWereLastWatched() = runTest {
    h.addClip("x", changes)
    h.saved.saveShort(ShortId(job, 2), WordRef(job, 2, 1))
    assertThat(h.saved.saved().first().single().lastWatchedAt).isNull()

    h.clock.advanceBy(Duration.ofMinutes(5))
    h.progress.recordPlayed(ShortId(job, 2), 3_000, 3_000)

    assertThat(h.saved.saved().first().single().lastWatchedAt).isEqualTo(h.clock.now())
  }

  /** F-06-AC10: a clip left "In order" at short 7 resumes there. */
  @Test
  fun aClipResumesWhereItWasLeft() = runTest {
    h.addClip("x", changes)
    assertThat(h.progress.resume(job).first()).isNull()

    h.progress.saveFeedPosition(at(FeedMode.InOrder(job), 6, positionMs = 18_500))
    assertThat(h.progress.resume(job).first()).isEqualTo(ShortId(job, 6))

    // Watching the clip's shorts in another mode does not move where "In order" resumes.
    h.progress.saveFeedPosition(at(FeedMode.Change(ChangeLabel("flapping")), 0))
    assertThat(h.progress.resume(job).first()).isEqualTo(ShortId(job, 6))
  }

  @Test
  fun aResumePointFollowsALaterShortsRuleToTheShortThatContainsIt() = runTest {
    val shorts = h.addClip("x", changes)
    h.progress.saveFeedPosition(at(FeedMode.InOrder(job), 7))

    // "Shorts v2": phrases 6 to 8 are one short now.
    val merged = shorts.filter { it.id.firstSegment < 6 } + shorts[6].copy(lastSegment = 8) + shorts.drop(9)
    h.shorts.shortsById.value = mapOf(job to merged)

    assertThat(h.progress.resume(job).first()).isEqualTo(ShortId(job, 6))
  }

  /** F-06-AC12: the Watch tab returns to its last mode and short, also after a restart. */
  @Test
  fun theWatchTabReturnsToItsLastShort() = runTest {
    h.addClip("x", changes)
    val position = at(FeedMode.Change(ChangeLabel("flapping")), 4, positionMs = 12_250)

    h.progress.saveFeedPosition(position)
    h.restart()

    assertThat(h.progress.lastFeed().first()).isEqualTo(position)
  }

  /** F-03-AC6: "Keep watching" shows the clip and the short where the learner stopped. */
  @Test
  fun keepWatchingShowsWhereLearnerStopped() = runTest {
    h.addClip("x", changes, title = "Coffee shop small talk")
    assertThat(h.progress.keepWatching().first()).isNull()

    h.progress.saveFeedPosition(at(FeedMode.InOrder(job), 3, positionMs = 9_400))

    val keep = h.progress.keepWatching().first()!!
    assertThat(keep.clip.id).isEqualTo(job)
    assertThat(keep.clip.title).isEqualTo("Coffee shop small talk")
    assertThat(keep.short).isEqualTo(ShortId(job, 3))
    assertThat(keep.positionMs).isEqualTo(9_400)
  }

  @Test
  fun keepWatchingHidesWhileTheLastPositionIsTodaysSetAndComesBackTheNextDay() = runTest {
    h.addClip("x", changes)
    h.progress.saveFeedPosition(at(FeedMode.InOrder(job), 3))
    val set = h.sets.ensureToday()!!

    h.progress.saveFeedPosition(at(FeedMode.Mix(set.id), 5))
    assertThat(h.progress.keepWatching().first()).isNull()
    assertThat(h.progress.lastFeed().first()?.mode).isEqualTo(FeedMode.Mix(set.id))

    // The next day the last position is yesterday's set, not today's: the clip position shows again.
    h.clock.advanceBy(Duration.ofDays(1))
    assertThat(h.progress.keepWatching().first()?.short).isEqualTo(ShortId(job, 3))
  }

  @Test
  fun keepWatchingNeedsTheClipInTheLibrary() = runTest {
    h.addClip("x", changes)
    h.progress.saveFeedPosition(at(FeedMode.InOrder(job), 3))
    h.clips.details.value = emptyMap()
    assertThat(h.progress.keepWatching().first()).isNull()
  }

  /** Eviction keeps the clips of today's set and the clips opened in the last 24 hours (architecture 7.5). */
  @Test
  fun evictionKeepsTodaysAndRecentClips() = runTest {
    h.addClip("x", changes)
    h.addClip("y", changes)
    h.addClip("z", changes)
    h.progress.markOpened(JobId("z"))
    h.sets.ensureToday()

    assertThat(h.protection.protectedClips()).containsAtLeast(JobId("z"), JobId("y"))

    h.clock.advanceBy(Duration.ofHours(25))
    // A new day: yesterday's set no longer protects, and z was opened more than 24 h ago.
    assertThat(h.protection.protectedClips()).isEmpty()
    assertThat(h.clips.all.value.first().availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun aDeletedClipLeavesNoSeenStateResumePointOrPositionBehind() = runTest {
    h.addClip("x", changes)
    h.progress.recordPlayed(ShortId(job, 1), 3_000, 3_000)
    h.progress.saveFeedPosition(at(FeedMode.InOrder(job), 2))

    h.removal.onClipsRemoved(setOf(job))

    assertThat(h.progress.seen().first()).isEmpty()
    assertThat(h.progress.resume(job).first()).isNull()
    assertThat(h.progress.lastFeed().first()).isNull()
    assertThat(h.progress.keepWatching().first()).isNull()
    // Practice history is kept.
    assertThat(h.practice.changesMet().first()).containsExactly(ChangeLabel("linking"))
  }
}
