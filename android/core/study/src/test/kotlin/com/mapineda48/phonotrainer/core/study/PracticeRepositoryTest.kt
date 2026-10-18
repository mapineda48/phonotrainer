package com.mapineda48.phonotrainer.core.study

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeSessionEntity
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RoomPracticeRepository] (spec F-17): ten questions from the phone's clips with no network (F-17-AC1), no "Which
 * change?" with a focus (F-17-AC2), accuracy updated by a wrong answer (F-17-AC4), the session resumed at the same
 * question after process death, a phone-only reset, and a damaged row that never crashes Practice (the web's "ignores
 * broken data"). Accuracy survives a restart (the web's "round trip through storage").
 */
@RunWith(RobolectricTestRunner::class)
class PracticeRepositoryTest {
  private val h = StudyHarness()
  private val changes = listOf("flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion",
    "flapping", "linking", "h_dropping", "vowel_reduction", "t_deletion")

  @After
  fun close() = h.close()

  /** F-17-AC1: ten questions from the clips on the phone, with no network, each with something to play. */
  @Test
  fun tenQuestionsFromThePhonesClips() = runTest {
    h.addClip("a", changes)
    h.addClip("b", changes)

    val session = h.practice.newSession(focus = null, weakSpots = false)

    assertThat(session.questions).hasSize(10)
    assertThat(session.index).isEqualTo(0)
    assertThat(session.answers).containsExactlyElementsIn(List<String?>(10) { null })
    assertThat(session.questions.map { it.kind }.toSet())
      .containsExactly(QuestionKind.FORM, QuestionKind.CHANGE, QuestionKind.COUNT)
    assertThat(session.questions.all { it.clip.endMs > it.clip.startMs && it.clip.jobId.value in setOf("a", "b") }).isTrue()
    assertThat(session.questions.map { it.occurrence.word }.toSet()).hasSize(10)
    assertThat(h.practice.currentSession().first()).isEqualTo(session)
  }

  /** F-17-AC2: a focus asks no "Which change?" question. */
  @Test
  fun aFocusAsksNoWhichChange() = runTest {
    h.addClip("a", changes)
    h.addClip("b", changes)

    val session = h.practice.newSession(focus = ChangeLabel("flapping"), weakSpots = true)

    assertThat(session.focus).isEqualTo(ChangeLabel("flapping"))
    assertThat(session.questions).isNotEmpty()
    assertThat(session.questions.map { it.kind }).doesNotContain(QuestionKind.CHANGE)
    assertThat(session.questions.map { it.change }.toSet()).containsExactly(ChangeLabel("flapping"))
    // An unknown focus practises everything.
    assertThat(h.practice.newSession(ChangeLabel("not_a_change"), false).focus).isNull()
  }

  /** F-17-AC4: a wrong answer updates the accuracy of its change. */
  @Test
  fun aWrongAnswerUpdatesAccuracy() = runTest {
    h.addClip("a", changes)
    val session = h.practice.newSession(null, false)
    val question = session.questions[0]
    val wrong = question.options.first { it.id != question.answer }.id

    val result = h.practice.answer(session.id, 0, wrong)

    assertThat(result.correct).isFalse()
    assertThat(result.answer).isEqualTo(question.answer)
    assertThat(result.change).isEqualTo(question.change)
    assertThat(result.explanation).isEqualTo(question.occurrence)
    val accuracy = h.practice.accuracy().first().single { it.change == question.change }
    assertThat(accuracy.seen to accuracy.correct).isEqualTo(1 to 0)
    assertThat(h.practice.changesMet().first()).contains(question.change)
  }

  @Test
  fun aSessionResumesAtTheSameQuestionAfterProcessDeath() = runTest {
    h.addClip("a", changes)
    val session = h.practice.newSession(null, false)
    repeat(3) { i -> h.practice.answer(session.id, i, session.questions[i].answer) }

    h.restart()

    val resumed = h.practice.currentSession().first()!!
    assertThat(resumed.id).isEqualTo(session.id)
    assertThat(resumed.questions).isEqualTo(session.questions)
    assertThat(resumed.index).isEqualTo(3)
    assertThat(resumed.answers.take(4)).containsExactly(
      session.questions[0].answer, session.questions[1].answer, session.questions[2].answer, null,
    ).inOrder()
    assertThat(resumed.finishedAt).isNull()
    // Accuracy survived the restart too.
    assertThat(h.practice.accuracy().first().sumOf { it.correct }).isEqualTo(3)
  }

  @Test
  fun aQuestionIsAnsweredOnceAndTheLastAnswerFinishesTheSession() = runTest {
    h.addClip("a", changes)
    val session = h.practice.newSession(null, false)

    h.practice.answer(session.id, 0, session.questions[0].answer)
    assertThrows(IllegalStateException::class.java) { runBlocking { h.practice.answer(session.id, 0, "x") } }
    assertThrows(IllegalStateException::class.java) { runBlocking { h.practice.answer(session.id, 99, "x") } }
    assertThrows(IllegalStateException::class.java) { runBlocking { h.practice.answer(session.id + 1, 1, "x") } }
    for (i in 1 until session.questions.size) h.practice.answer(session.id, i, session.questions[i].answer)

    val done = h.practice.currentSession().first()!!
    assertThat(done.index).isEqualTo(session.questions.size)
    assertThat(done.finishedAt).isEqualTo(h.clock.now())
    assertThat(h.practice.accuracy().first().sumOf { it.seen }).isEqualTo(session.questions.size)
  }

  @Test
  fun aNewSessionReplacesTheOldOne() = runTest {
    h.addClip("a", changes)
    val first = h.practice.newSession(null, false)
    val second = h.practice.newSession(null, false)
    assertThat(second.id).isNotEqualTo(first.id)
    assertThat(h.practice.currentSession().first()?.id).isEqualTo(second.id)
  }

  @Test
  fun resetForgetsAccuracyOnThisPhoneButNotTheChangesMet() = runTest {
    h.addClip("a", changes)
    val session = h.practice.newSession(null, false)
    h.practice.answer(session.id, 0, session.questions[0].answer)

    h.practice.reset()

    assertThat(h.practice.accuracy().first()).isEmpty()
    assertThat(h.practice.changesMet().first()).contains(session.questions[0].change)
  }

  @Test
  fun noClipsGiveAnEmptySession() = runTest {
    val session = h.practice.newSession(null, false)
    assertThat(session.questions).isEmpty()
    assertThat(session.answers).isEmpty()
  }

  @Test
  fun aDamagedSessionReadsAsNoSession() = runTest {
    h.db.practiceDao().start(PracticeSessionEntity(seed = 1, questionsJson = "{not json", createdAt = 0))
    assertThat(h.practice.currentSession().first()).isNull()
  }
}
