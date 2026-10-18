package com.mapineda48.phonotrainer.feature.practice

import com.mapineda48.phonotrainer.core.domain.api.PracticeRepository
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.PracticeSession
import com.mapineda48.phonotrainer.core.domain.model.Question
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [PracticeRepository] that keeps WP-14's contract (its hand-off): a new session holds the questions [questions]
 * gives for its focus and replaces the stored one; `answer` stores the choice, moves `index` past it, finishes the
 * session on its last question, counts the answer in the accuracy and throws [IllegalStateException] for a question
 * answered already. The shared fake of work-plan 4.6 builds empty sessions and stores no answer, which a session
 * screen cannot walk through, so this test double lives with the tests that need it.
 */
internal class ScriptedPracticeRepository(
  var questions: (focus: ChangeLabel?, weakSpots: Boolean) -> List<Question> = { _, _ -> emptyList() },
) : PracticeRepository {
  val stored = MutableStateFlow<PracticeSession?>(null)
  val accuracyState = MutableStateFlow(emptyList<ChangeAccuracy>())
  val met = MutableStateFlow(emptySet<ChangeLabel>())
  val started = mutableListOf<Pair<ChangeLabel?, Boolean>>()
  val answered = mutableListOf<Triple<Long, Int, String>>()
  var resets = 0
    private set
  private var nextId = 1L

  override suspend fun newSession(focus: ChangeLabel?, weakSpots: Boolean): PracticeSession {
    started += focus to weakSpots
    val list = questions(focus, weakSpots)
    val session = PracticeSession(nextId++, focus, weakSpots, list, 0, List(list.size) { null }, Instant.EPOCH, null)
    stored.value = session
    return session
  }

  override fun currentSession(): Flow<PracticeSession?> = stored

  override suspend fun answer(sessionId: Long, index: Int, choice: String): AnswerResult {
    val session = checkNotNull(stored.value?.takeIf { it.id == sessionId }) { "No practice session $sessionId" }
    val question = checkNotNull(session.questions.getOrNull(index)) { "No question $index" }
    check(session.answers[index] == null) { "Question $index is answered already" }
    answered += Triple(sessionId, index, choice)
    val answers = session.answers.toMutableList().also { it[index] = choice }
    val next = maxOf(session.index, index + 1)
    stored.value = session.copy(
      index = next,
      answers = answers,
      finishedAt = if (next >= session.questions.size) Instant.EPOCH else null,
    )
    val correct = choice == question.answer
    val old = accuracyState.value.firstOrNull { it.change == question.change } ?: ChangeAccuracy(question.change, 0, 0)
    accuracyState.value = accuracyState.value.filterNot { it.change == question.change } +
      old.copy(seen = old.seen + 1, correct = old.correct + if (correct) 1 else 0)
    met.value += question.change
    return AnswerResult(correct, question.answer, question.change, question.occurrence)
  }

  override fun accuracy(): Flow<List<ChangeAccuracy>> = accuracyState

  override fun changesMet(): Flow<Set<ChangeLabel>> = met

  override suspend fun reset() {
    resets++
    accuracyState.value = emptyList()
  }
}
