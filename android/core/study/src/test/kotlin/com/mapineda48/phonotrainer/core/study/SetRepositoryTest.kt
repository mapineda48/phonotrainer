package com.mapineda48.phonotrainer.core.study

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.StudySetKind
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RoomSetRepository] on a real `learning.db` (spec F-08, architecture 10): today's set is built once a day and never
 * changes (F-08-AC3), a new one after local midnight (F-08-AC4), progress resumes and `done` marks follow the cursor
 * across a process restart (F-08-AC2, D-45), answers count once and in Practice accuracy (F-08-AC5), deleted clips drop
 * out, saved shorts come back tagged, and "Another set" replays when nothing new is due.
 */
@RunWith(RobolectricTestRunner::class)
class SetRepositoryTest {
  private val h = StudyHarness()
  private val ten = listOf("flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion",
    "flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion")

  @After
  fun close() = h.close()

  private val StudySet.shorts get() = items.filterIsInstance<StudySetItem.Short>()
  private val StudySet.questions get() = items.filterIsInstance<StudySetItem.QuestionItem>()

  @Test
  fun todaysSetHoldsEightShortsThenUpToFiveQuestionsOnTheChangesItMet() = runTest {
    h.addClip("a", ten, addedAt = h.clock.now().minus(Duration.ofDays(1)))
    h.addClip("b", ten)

    val set = h.sets.ensureToday()!!

    assertThat(set.kind).isEqualTo(StudySetKind.TODAY)
    assertThat(set.date).isEqualTo(LocalDate.of(2026, 10, 10))
    assertThat(set.shorts).hasSize(8)
    assertThat(set.questions).hasSize(5)
    assertThat(set.items.take(8).all { it is StudySetItem.Short }).isTrue()
    val met = set.shorts.map { it.spotlight!!.change }.toSet()
    assertThat(met).containsAtLeastElementsIn(set.questions.map { it.question.change }.toSet())
    assertThat(set.cursor).isEqualTo(0)
    assertThat(set.shorts.none { it.done || it.dropped }).isTrue()
    // The newest clip first, then the other one, never three of one clip in a row.
    assertThat(set.shorts.count { it.short.id.jobId == JobId("b") }).isEqualTo(6)
    assertThat(set.shorts.map { it.short.id.jobId }.windowed(3).none { it.toSet().size == 1 }).isTrue()

    assertThat(h.sets.ensureToday()).isEqualTo(set)
    assertThat(h.sets.today().first()).isEqualTo(set)
  }

  /** F-08-AC3: today's set is unchanged when a new clip arrives, and the next set may use it. */
  @Test
  fun aNewClipWaitsForTheNextSet() = runTest {
    h.addClip("a", ten)
    val today = h.sets.ensureToday()!!

    h.addClip("new", ten, addedAt = h.clock.now().plus(Duration.ofHours(1)))

    assertThat(h.sets.ensureToday()).isEqualTo(today)
    assertThat(h.sets.today().first()).isEqualTo(today)
    val next = h.sets.anotherSet()!!
    assertThat(next.kind).isEqualTo(StudySetKind.EXTRA)
    assertThat(next.id).isNotEqualTo(today.id)
    assertThat(next.shorts.map { it.short.id.jobId }).contains(JobId("new"))
  }

  /** F-08-AC4: a new today's set is offered once the test clock passes local midnight. */
  @Test
  fun aNewSetAfterLocalMidnight() = runTest {
    h.addClip("a", ten)
    h.clock.instant = LocalDate.of(2026, 10, 10).atTime(23, 59).atZone(h.clock.zoneId).toInstant()
    val yesterday = h.sets.ensureToday()!!
    assertThat(yesterday.date).isEqualTo(LocalDate.of(2026, 10, 10))

    h.clock.advanceBy(Duration.ofMinutes(2))

    assertThat(h.sets.today().first()).isNull()
    val today = h.sets.ensureToday()!!
    assertThat(today.id).isNotEqualTo(yesterday.id)
    assertThat(today.date).isEqualTo(LocalDate.of(2026, 10, 11))
    assertThat(today.kind).isEqualTo(StudySetKind.TODAY)
    assertThat(h.sets.today().first()?.id).isEqualTo(today.id)
  }

  /** F-08-AC2 and D-45: 3 of 8 done marks the first three shorts, and the marks survive a process restart. */
  @Test
  fun doneMarksFollowTheCursorAcrossRestarts() = runTest {
    h.addClip("a", ten)
    h.addClip("b", ten)
    val set = h.sets.ensureToday()!!

    h.sets.advance(set.id, 3)
    h.sets.advance(set.id, 1) // never backwards
    h.restart()

    val resumed = h.sets.ensureToday()!!
    assertThat(resumed.id).isEqualTo(set.id)
    assertThat(resumed.cursor).isEqualTo(3)
    // 3 of 8 done: the first three shorts are marked, short 4 is where the set resumes.
    assertThat(resumed.shorts.map { it.done }).containsExactly(true, true, true, false, false, false, false, false).inOrder()
    assertThat(h.sets.today().first()!!.shorts.count { it.done }).isEqualTo(3)
    assertThat(resumed.completedAt).isNull()
  }

  /** F-08-AC5: answers count once, "N of 5 right" matches them, and they count in Practice accuracy. */
  @Test
  fun answersCountOnceAndInAccuracy() = runTest {
    h.addClip("a", ten)
    h.addClip("b", ten)
    val set = h.sets.ensureToday()!!
    val questions = set.items.withIndex().filter { it.value is StudySetItem.QuestionItem }
    assertThat(questions).hasSize(5)

    val results = questions.mapIndexed { n, (position, item) ->
      val question = (item as StudySetItem.QuestionItem).question
      val choice = if (n < 4) question.answer else question.options.first { it.id != question.answer }.id
      h.sets.answer(set.id, position, choice)
    }

    assertThat(results.map { it.correct }).containsExactly(true, true, true, true, false).inOrder()
    assertThat(results.last().answer).isEqualTo((questions.last().value as StudySetItem.QuestionItem).question.answer)
    assertThrows(IllegalStateException::class.java) { runBlocking { h.sets.answer(set.id, questions[0].index, "x") } }
    assertThrows(IllegalStateException::class.java) { runBlocking { h.sets.answer(set.id, 0, "x") } }

    h.sets.advance(set.id, set.items.size)
    val done = h.sets.today().first()!!
    assertThat(done.answered).isEqualTo(5)
    assertThat(done.right).isEqualTo(4)
    assertThat(done.completedAt).isNotNull()
    assertThat(done.questions.map { it.correct }).containsExactly(true, true, true, true, false).inOrder()
    assertThat(done.shorts.all { it.done }).isTrue()
    val accuracy = h.practice.accuracy().first()
    assertThat(accuracy.sumOf { it.seen }).isEqualTo(5)
    assertThat(accuracy.sumOf { it.correct }).isEqualTo(4)
    assertThat(h.practice.changesMet().first()).containsAtLeastElementsIn(results.map { it.change }.toSet())
  }

  @Test
  fun aClipDeletedOnTheComputerDropsItsShortsAndTheCountUpdates() = runTest {
    h.addClip("a", ten)
    h.addClip("b", ten)
    val set = h.sets.ensureToday()!!
    val fromA = set.shorts.count { it.short.id.jobId == JobId("a") }
    assertThat(fromA).isGreaterThan(0)

    h.removal.onClipsRemoved(setOf(JobId("a")))

    val after = h.sets.today().first()!!
    assertThat(after.items).hasSize(set.items.size)
    assertThat(after.shorts.filter { it.dropped }.map { it.short.id.jobId }.toSet()).containsExactly(JobId("a"))
    assertThat(after.shorts.count { !it.dropped }).isEqualTo(8 - fromA)
    assertThat(h.protection.protectedClips()).doesNotContain(JobId("a"))
    assertThat(h.protection.protectedClips()).contains(JobId("b"))
  }

  @Test
  fun aShortWhoseClipLostItsShortsReadsAsDroppedAndKeepsItsStoredSpotlight() = runTest {
    h.addClip("a", ten)
    val set = h.sets.ensureToday()!!
    val first = set.shorts.first()

    h.shorts.shortsById.value = emptyMap()

    val after = h.sets.today().first()!!
    assertThat(after.shorts.all { it.dropped }).isTrue()
    assertThat(after.shorts.first().spotlight!!.word).isEqualTo(first.spotlight!!.word)
    assertThat(after.shorts.first().spotlight!!.change).isEqualTo(first.spotlight!!.change)
    assertThat(after.shorts.first().short.id).isEqualTo(first.short.id)
  }

  @Test
  fun theStoredSpotlightStaysWhenTheCorpusWouldNowChooseAnother() = runTest {
    val shorts = h.addClip("a", ten)
    val set = h.sets.ensureToday()!!
    val item = set.shorts.first()
    val other = Spotlight(WordRef(JobId("a"), item.short.id.firstSegment, 3), ChangeLabel("linking"), item.spotlight!!.advice, false)

    h.shorts.shortsById.value = mapOf(JobId("a") to shorts.map { if (it.id == item.short.id) it.copy(spotlight = other) else it })

    val after = h.sets.today().first()!!.shorts.first()
    assertThat(after.spotlight!!.word).isEqualTo(item.spotlight!!.word)
    assertThat(after.spotlight!!.change).isEqualTo(item.spotlight!!.change)
    assertThat(after.short.spotlight).isEqualTo(after.spotlight)
  }

  @Test
  fun savedShortsComeBackTaggedOnTheirSavedWord() = runTest {
    val shorts = h.addClip("a", ten)
    val target = shorts[9]
    h.saved.saveWord(WordRef(JobId("a"), target.id.firstSegment, 1))

    val set = h.sets.ensureToday()!!

    val saved = set.shorts.filter { it.savedTag }
    assertThat(saved.map { it.short.id }).containsExactly(target.id)
    assertThat(saved.single().spotlight!!.word).isEqualTo(WordRef(JobId("a"), target.id.firstSegment, 1))
    assertThat(set.shorts.first().short.id).isEqualTo(target.id)
  }

  @Test
  fun anotherSetReplaysTheSeenShortsWhenNothingNewIsDue() = runTest {
    val shorts = h.addClip("a", ten.take(4)) + h.addClip("b", ten.take(4))
    shorts.forEach { h.progress.recordPlayed(it.id, 3_000, 3_000) }

    assertThat(h.sets.ensureToday()).isNull()
    val replay = h.sets.anotherSet()!!
    assertThat(replay.kind).isEqualTo(StudySetKind.EXTRA)
    assertThat(replay.shorts.map { it.short.id }).containsExactlyElementsIn(shorts.map { it.id })
  }

  @Test
  fun noClipsMeanNoSet() = runTest {
    assertThat(h.sets.ensureToday()).isNull()
    assertThat(h.sets.anotherSet()).isNull()
    assertThat(h.sets.today().first()).isNull()
  }

  @Test
  fun aSetWithOneChangeAsksNoWhichChangeQuestion() = runTest {
    h.addClip("a", List(10) { "flapping" })
    val set = h.sets.ensureToday()!!
    assertThat(set.questions).isNotEmpty()
    assertThat(set.questions.map { it.question.kind.name }).doesNotContain("CHANGE")
  }

  @Test
  fun withoutQuestionMaterialTheSetHasOnlyShorts() = runTest {
    h.addClip("a", ten)
    h.occurrences.byChange.clear()
    val set = h.sets.ensureToday()!!
    assertThat(set.shorts).hasSize(8)
    assertThat(set.questions).isEmpty()
  }

  @Test
  fun theNextSetContinuesTheInterleavingOfThePreviousOne() = runTest {
    h.addClip("a", ten)
    h.addClip("b", ten, addedAt = h.clock.now().minus(Duration.ofDays(1)))
    h.addClip("c", ten, addedAt = h.clock.now().minus(Duration.ofDays(2)))
    val listed = ArrayList<ShortId>()
    repeat(3) {
      val set = if (it == 0) h.sets.ensureToday()!! else h.sets.anotherSet()!!
      set.shorts.forEach { short -> h.progress.recordPlayed(short.short.id, 3_000, 3_000) }
      listed += set.shorts.map { short -> short.short.id }
    }
    assertThat(listed.map { it.jobId }.take(20).windowed(3).none { it.toSet().size == 1 }).isTrue()
    assertThat(h.db.setDao().count()).isEqualTo(3)
  }
}
