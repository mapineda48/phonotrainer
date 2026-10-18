package com.mapineda48.phonotrainer.feature.practice

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PracticeSession
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import java.time.Instant
import org.junit.Test

/** The pure rules of Practice (spec F-17; the web's `PracticePage` and `StatsCard`) and where its buttons lead. */
class PracticeLogicTest {
  private val reference = SampleReference.reference
  private val flapping = ChangeLabel("flapping")
  private val tdDeletion = ChangeLabel("t_deletion")
  private val linking = ChangeLabel("linking")

  @Test
  fun aChangeIsNamedAndFamiliedByTheReference() {
    assertThat(PracticeLogic.change(reference, tdDeletion)).isEqualTo(ChangeRef(tdDeletion, "t/d deletion", Family.TD))
    // A key the reference does not know is read as words, without a family mark.
    assertThat(PracticeLogic.change(reference, ChangeLabel("new_rule"))).isEqualTo(ChangeRef(ChangeLabel("new_rule"), "new rule", Family.OTHER))
  }

  @Test
  fun nothingToPracticeWhenNoQuizzedChangeHasAnExample() {
    assertThat(PracticeLogic.nothingToPractice(emptyMap())).isTrue()
    assertThat(PracticeLogic.nothingToPractice(mapOf(flapping to ChangeCount(2, 0)))).isTrue()
    assertThat(PracticeLogic.nothingToPractice(mapOf(ChangeLabel("word_elision") to ChangeCount(1, 4)))).isTrue()
    assertThat(PracticeLogic.nothingToPractice(mapOf(flapping to ChangeCount(1, 1)))).isFalse()
  }

  @Test
  fun theFocusComesFromTheKeyUntilItIsSetAside() {
    val counts = mapOf(flapping to ChangeCount(3, 12))
    assertThat(PracticeLogic.focus(null, cleared = false, reference, counts)).isNull()
    assertThat(PracticeLogic.focus("flapping", cleared = true, reference, counts)).isNull()
    val known = PracticeLogic.focus("flapping", cleared = false, reference, counts)
    assertThat(known).isEqualTo(FocusUi.Known(ChangeRef(flapping, "flapping", Family.TD), playable = true))
    assertThat(PracticeLogic.sessionFocus(known)).isEqualTo(flapping)
    val none = PracticeLogic.focus("linking", cleared = false, reference, counts) as FocusUi.Known
    assertThat(none.playable).isFalse()
    // An unknown key practises everything, as the web says ("Unknown focus").
    val unknown = PracticeLogic.focus("flap", cleared = false, reference, counts)
    assertThat(unknown).isEqualTo(FocusUi.Unknown("flap"))
    assertThat(PracticeLogic.sessionFocus(unknown)).isNull()
  }

  @Test
  fun progressListsAnsweredChangesWeakestFirstWithTheSavedCountAndTheChangesMet() {
    val progress = PracticeLogic.progress(
      reference,
      accuracy = listOf(
        ChangeAccuracy(flapping, seen = 10, correct = 7),
        ChangeAccuracy(linking, seen = 4, correct = 2),
        ChangeAccuracy(tdDeletion, seen = 2, correct = 1),
        ChangeAccuracy(ChangeLabel("h_dropping"), seen = 0, correct = 0),
      ),
      met = setOf(flapping, linking, ChangeLabel("not_a_label")),
      saved = 12,
    )
    // 50 % ties are ordered by name: "linking" before "t/d deletion".
    assertThat(progress.rows.map { it.change.label }).containsExactly(linking, tdDeletion, flapping).inOrder()
    assertThat(progress.rows.last().percent).isEqualTo(70)
    assertThat(progress.saved).isEqualTo(12)
    assertThat(progress.met).isEqualTo(2)
    assertThat(progress.total).isEqualTo(16)
    assertThat(progress.notMet).hasSize(14)
    assertThat(progress.notMet.map { it.label }).containsNoneOf(flapping, linking)
    assertThat(progress.notMet.first().label).isEqualTo(ChangeLabel("vowel_reduction"))
  }

  @Test
  fun aPercentIsRounded() {
    assertThat(AccuracyRow(ChangeRef(flapping, "flapping", Family.TD), correct = 4, seen = 9).percent).isEqualTo(44)
    assertThat(AccuracyRow(ChangeRef(flapping, "flapping", Family.TD), correct = 2, seen = 3).percent).isEqualTo(67)
  }

  private fun session(answers: List<String?>, finished: Boolean = false): PracticeSession {
    val questions = listOf(PracticePreviewData.formQuestion, PracticePreviewData.changeQuestion, PracticePreviewData.countQuestion)
      .map { it }
      .let { base -> List(answers.size) { base[it % 3] } }
    val index = answers.indexOfFirst { it == null }.let { if (it < 0) answers.size else it }
    return PracticeSession(1, null, false, questions, index, answers, Instant.EPOCH, if (finished) Instant.EPOCH else null)
  }

  @Test
  fun theResultsCountTheRightAnswersAndListTheMissedChangesOnce() {
    val done = session(listOf("dictionary", "linking", "5", "said", "flapping", "4"), finished = true)
    val results = PracticeLogic.results(done, reference)
    assertThat(results.right).isEqualTo(3)
    assertThat(results.total).isEqualTo(6)
    assertThat(results.partial).isTrue()
    // Every sample question is about flapping: one chip, however many misses.
    assertThat(results.missed.map { it.label }).containsExactly(flapping)
    assertThat(results.lines.map { it.correct }).containsExactly(false, false, true, true, true, false).inOrder()
    assertThat(results.lines.first()).isEqualTo(ResultLine(1, QuestionKind.FORM, "water", "flapping", correct = false))
    assertThat(PracticeLogic.tally(done)).isEqualTo(3 to 3)
  }

  @Test
  fun aFullSessionIsNotPartial() {
    val results = PracticeLogic.results(session(List(10) { "said" }, finished = true), reference)
    assertThat(results.partial).isFalse()
    assertThat(results.total).isEqualTo(10)
  }

  @Test
  fun onlyAnUnfinishedSessionWithAQuestionLeftCanBeResumed() {
    assertThat(PracticeLogic.resumable(null)).isFalse()
    assertThat(PracticeLogic.resumable(session(listOf("said", null, null)))).isTrue()
    assertThat(PracticeLogic.resumable(session(listOf("said", "flapping", "5"), finished = true))).isFalse()
    assertThat(PracticeLogic.resumable(session(emptyList()))).isFalse()
  }

  @Test
  fun aResultIsRebuiltFromTheStoredChoice() {
    val question = PracticePreviewData.formQuestion
    val result = PracticeLogic.result(question, "variant:2")
    assertThat(result.correct).isFalse()
    assertThat(result.answer).isEqualTo("said")
    assertThat(result.change).isEqualTo(flapping)
    assertThat(result.explanation).isEqualTo(question.occurrence)
    assertThat(PracticeLogic.result(question, "said").correct).isTrue()
  }

  @Test
  fun theWordIsShownWithoutThePunctuationAroundIt() {
    assertThat(PracticeLogic.displayWord("“water,”")).isEqualTo("water")
    assertThat(PracticeLogic.displayWord("don't.")).isEqualTo("don't")
    assertThat(PracticeLogic.displayWord("…")).isEqualTo("…")
  }

  @Test
  fun theButtonsLeadToTheFullLessonTheChangeModeAndTheSavedMode() {
    val word = PracticePreviewData.water
    assertThat(PracticeDestinations.lesson(word, LessonOrigin.PRACTICE).presentation).isEqualTo(LessonPresentation.FULL)
    assertThat(PracticeDestinations.lesson(word, LessonOrigin.SAVED).origin).isEqualTo(LessonOrigin.SAVED)
    assertThat(PracticeDestinations.changeMode(flapping)).isEqualTo(WatchKey(FeedStart.ChangeMode(flapping, onlyClip = null)))
    assertThat(PracticeDestinations.savedMode).isEqualTo(WatchKey(FeedStart.SavedMode))
    // F-09-AC3: a saved short opens the short that contains its anchor phrase.
    val row = PracticePreviewData.savedRow(1)
    assertThat(PracticeDestinations.savedShort(row)).isEqualTo(WatchKey(FeedStart.AtWord(row.short.jobId, 2, 0, openLesson = false)))
  }
}
