package com.mapineda48.phonotrainer.core.study

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedPosition
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RoomSavedRepository] (spec F-09): a saved short is listed first with its spotlight word (F-09-AC1), a saved word
 * with its change, an item opens the short that contains its anchor under a later shorts rule (F-09-AC3), "last
 * watched" comes from the seen state, an item of a clip not on the phone stays listed, and a deleted clip's items go.
 */
@RunWith(RobolectricTestRunner::class)
class SavedRepositoryTest {
  private val h = StudyHarness()
  private val job = JobId("x")
  private val changes = listOf("flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion",
    "flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion")

  @After
  fun close() = h.close()

  /** F-09-AC1: a saved short is listed first with its spotlight word. */
  @Test
  fun aSavedShortIsListedFirstWithItsWord() = runTest {
    h.addClip("x", changes, title = "Coffee shop small talk")
    h.saved.saveWord(WordRef(job, 7, 1))
    h.clock.advanceBy(Duration.ofMinutes(1))

    h.saved.saveShort(ShortId(job, 2), spotlight = WordRef(job, 2, 1))

    val first = h.saved.saved().first().first()
    assertThat(first.kind).isEqualTo(SavedKind.SHORT)
    assertThat(first.short).isEqualTo(ShortId(job, 2))
    assertThat(first.word).isEqualTo(WordRef(job, 2, 1))
    assertThat(first.change).isEqualTo(ChangeLabel("h_dropping"))
    assertThat(first.clipTitle).isEqualTo("Coffee shop small talk")
    assertThat(first.available).isTrue()
    assertThat(first.savedAt).isEqualTo(h.clock.now())
    assertThat(h.saved.isSaved(ShortId(job, 2)).first()).isTrue()
  }

  @Test
  fun aShortSavedInAChangeModeKeepsThatChangeAndItsInstance() = runTest {
    h.addClip("x", changes)
    // Short 3 spotlights vowel reduction by default; the feed showed it in the flapping mode on another word.
    h.progress.saveFeedPosition(FeedPosition(FeedMode.Change(ChangeLabel("flapping")), ShortId(job, 3), 0, h.clock.now()))

    h.saved.saveShort(ShortId(job, 3), spotlight = WordRef(job, 3, 2))

    val item = h.saved.saved().first().single()
    assertThat(item.change).isEqualTo(ChangeLabel("flapping"))
    // The flapping mode of this fake corpus has no instance in short 3, so no spotlight word can be found again.
    assertThat(item.word).isNull()
  }

  @Test
  fun aShortSavedWithoutASpotlightKeepsNoChange() = runTest {
    h.addClip("x", changes)
    h.saved.saveShort(ShortId(job, 3), spotlight = null)
    val item = h.saved.saved().first().single()
    assertThat(item.change).isNull()
    assertThat(item.word).isNull()
  }

  @Test
  fun aSavedWordKeepsTheChangeOfTheQuestionItWasSavedFrom() = runTest {
    h.addClip("x", changes)
    val session = h.practice.newSession(null, false)
    val question = session.questions.first()

    h.saved.saveWord(question.occurrence.word)

    val item = h.saved.saved().first().single()
    assertThat(item.kind).isEqualTo(SavedKind.WORD)
    assertThat(item.word).isEqualTo(question.occurrence.word)
    assertThat(item.change).isEqualTo(question.change)
    assertThat(item.short).isEqualTo(ShortId(job, question.occurrence.word.segment))
    assertThat(h.saved.isSaved(item.short).first()).isFalse()
  }

  @Test
  fun aSavedWordWithoutAQuestionKeepsItsLabel() = runTest {
    h.addClip("x", changes)
    h.saved.saveWord(WordRef(job, 4, 1))
    assertThat(h.saved.saved().first().single().change).isEqualTo(ChangeLabel("t_deletion"))
  }

  /** F-09-AC3: a saved short opens the short containing its anchor under a later shorts rule. */
  @Test
  fun anAnchorFollowsALaterShortsRule() = runTest {
    val shorts = h.addClip("x", changes)
    h.saved.saveShort(ShortId(job, 7), WordRef(job, 7, 1))

    // "Shorts v2": phrases 6 to 8 are one short now.
    h.shorts.shortsById.value = mapOf(job to shorts.filter { it.id.firstSegment < 6 } + shorts[6].copy(lastSegment = 8) + shorts.drop(9))

    val item = h.saved.saved().first().single()
    assertThat(item.short).isEqualTo(ShortId(job, 6))
    assertThat(h.saved.isSaved(ShortId(job, 6)).first()).isTrue()
  }

  @Test
  fun savingTwiceKeepsOneItemAndUnsavingRemovesIt() = runTest {
    h.addClip("x", changes)
    h.saved.saveShort(ShortId(job, 1), WordRef(job, 1, 1))
    h.saved.saveShort(ShortId(job, 1), WordRef(job, 1, 1))
    val items = h.saved.saved().first()
    assertThat(items).hasSize(1)

    h.saved.unsave(items.single().id)

    assertThat(h.saved.saved().first()).isEmpty()
    assertThat(h.saved.isSaved(ShortId(job, 1)).first()).isFalse()
  }

  @Test
  fun anItemSavedAfterItsShortWasSeenStartsWithThatTime() = runTest {
    h.addClip("x", changes)
    h.progress.recordPlayed(ShortId(job, 5), 3_000, 3_000)
    val seenAt = h.clock.now()
    h.clock.advanceBy(Duration.ofHours(2))

    h.saved.saveWord(WordRef(job, 5, 1))

    assertThat(h.saved.saved().first().single().lastWatchedAt).isEqualTo(seenAt)
    // Watched today, so tomorrow's set leaves it out (F-09-AC2).
    h.clock.advanceBy(Duration.ofDays(1))
    val set = h.sets.ensureToday()!!
    assertThat(set.items.filterIsInstance<StudySetItem.Short>().none { it.savedTag }).isTrue()
  }

  @Test
  fun anItemWhoseClipIsNotOnThePhoneStaysListedAsUnavailable() = runTest {
    h.addClip("x", changes)
    h.saved.saveShort(ShortId(job, 1), WordRef(job, 1, 1))

    h.clips.all.value = h.clips.all.value.map { it.copy(availability = ClipAvailability.NotOnThisPhone) }

    val item = h.saved.saved().first().single()
    assertThat(item.available).isFalse()
    assertThat(item.clipTitle).isEqualTo("Clip x")
  }

  @Test
  fun aClipDeletedOnTheComputerTakesItsSavedItems() = runTest {
    h.addClip("x", changes)
    h.addClip("y", changes)
    h.saved.saveShort(ShortId(job, 1), WordRef(job, 1, 1))
    h.saved.saveWord(WordRef(JobId("y"), 2, 1))

    h.removal.onClipsRemoved(setOf(job))

    assertThat(h.saved.saved().first().map { it.short.jobId }).containsExactly(JobId("y"))
  }
}
