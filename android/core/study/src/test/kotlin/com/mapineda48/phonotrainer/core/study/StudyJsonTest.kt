package com.mapineda48.phonotrainer.core.study

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.study.quiz.PoolEntry
import com.mapineda48.phonotrainer.core.study.quiz.Quiz
import com.mapineda48.phonotrainer.core.study.quiz.seededRng
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [StudyJson]: questions, answers and feed modes survive a round trip, and damaged text reads as null. */
@RunWith(RobolectricTestRunner::class)
class StudyJsonTest {
  private val h = StudyHarness()

  @After
  fun close() = h.close()

  @Test
  fun everyKindOfQuestionSurvivesARoundTrip() {
    val occurrence = h.occurrence(JobId("x"), 0, ChangeLabel("linking"))
      .copy(otherChanges = listOf(ChangeLabel("flapping")), dictIpa = null, nextWordText = null)
    val entry = PoolEntry(ChangeLabel("linking"), occurrence)
    val change = Quiz.changeItem(entry, SampleReference.reference, seededRng(1))
    val phrase = AnalysisFixtures.phrase(
      index = 0, startS = 0.0, endS = 2.0,
      words = List(4) { AnalysisFixtures.word(it, "w$it", it * 0.4, "w", "w", changes = if (it == 1) listOf("linking") else emptyList()) },
    )
    val count = Quiz.countItem(entry.copy(occurrence = occurrence.copy(word = occurrence.word.copy(word = 1))), phrase, seededRng(2))!!
    assertThat(count.kind).isEqualTo(QuestionKind.COUNT)

    for (question in listOf(change, count)) {
      assertThat(StudyJson.question(StudyJson.question(question))).isEqualTo(question)
    }
    assertThat(StudyJson.questions(StudyJson.questions(listOf(change, count)))).containsExactly(change, count).inOrder()
  }

  @Test
  fun answersAndModesSurviveARoundTrip() {
    val answers = listOf("said", null, "3")
    assertThat(StudyJson.answers(StudyJson.answers(answers))).isEqualTo(answers)
    for (mode in listOf(FeedMode.Mix(7), FeedMode.InOrder(JobId("x")), FeedMode.Change(ChangeLabel("flapping")),
      FeedMode.Change(ChangeLabel("linking"), JobId("y")), FeedMode.Saved)) {
      assertThat(StudyJson.mode(StudyJson.mode(mode))).isEqualTo(mode)
    }
  }

  @Test
  fun damagedTextReadsAsNull() {
    assertThat(StudyJson.question("{not json")).isNull()
    assertThat(StudyJson.question("""{"kind":"DICTATION"}""")).isNull()
    assertThat(StudyJson.question(null)).isNull()
    assertThat(StudyJson.questions("[1,2]")).isNull()
    assertThat(StudyJson.answers("")).isNull()
    assertThat(StudyJson.mode("""{"type":"mix"}""")).isNull()
    assertThat(StudyJson.mode("""{"type":"carousel"}""")).isNull()
    assertThat(StudyJson.mode("[]")).isNull()
  }
}
