package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.database.learning.dao.PracticeDao
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeSessionEntity
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.PracticeRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.PracticeSession
import com.mapineda48.phonotrainer.core.study.quiz.Quiz
import com.mapineda48.phonotrainer.core.study.quiz.QuizGenerator
import com.mapineda48.phonotrainer.core.study.quiz.Tally
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Practice tab's sessions and progress (spec F-17; architecture 10). A session is [Quiz.SESSION_LENGTH] questions
 * built at once with the web's rules ([QuizGenerator]: the pool of playable occurrences of every quiz change, kinds
 * rotating, seeded, no reused occurrence, no "Which change?" with a focus, weak spots weighted 1.5 − accuracy) and
 * stored, so it resumes at the same question after process death. Accuracy is the web's `{seen, correct}` per change,
 * fed by Practice and by the sets' questions; "changes met" by seen spotlights and answered questions. In v1-core
 * accuracy is kept only on this phone, so "Reset" clears it here (spec F-17 item 5).
 */
@Singleton
internal class RoomPracticeRepository @Inject constructor(
  private val practice: PracticeDao,
  private val occurrences: OccurrenceRepository,
  private val content: ClipContentRepository,
  private val references: ReferenceRepository,
  private val day: StudyDay,
  @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : PracticeRepository {
  private val writing = Mutex()

  override suspend fun newSession(focus: ChangeLabel?, weakSpots: Boolean): PracticeSession = writing.withLock {
    val reference = references.reference.value
    val quizChanges = Quiz.quizChanges(reference).toSet()
    // An unknown focus practises everything, as on the web ("Unknown focus: practicing everything").
    val known = focus?.takeIf { it in reference.labels }
    val pool = Quiz.buildPool(occurrences.quizPool(POOL_PER_CHANGE).filterKeys { it in quizChanges })
    val stats = practice.stats().associate { ChangeLabel(it.change) to Tally(it.seen, it.correct) }
    val now = day.now()
    val seed = Math.floorMod(now.toEpochMilli(), SEED_RANGE)
    val generator = QuizGenerator(reference, CorpusQuizSource(occurrences, content))
    val questions = withContext(cpu) {
      generator.questions(pool, Quiz.SESSION_LENGTH, seed, focus = known, weakSpots = weakSpots, stats = stats)
    }
    val answers = List<String?>(questions.size) { null }
    val id = practice.start(
      PracticeSessionEntity(
        focus = known?.value,
        weakSpots = weakSpots,
        seed = seed,
        questionsJson = StudyJson.questions(questions),
        index = 0,
        answersJson = StudyJson.answers(answers),
        createdAt = now.toEpochMilli(),
      ),
    )
    PracticeSession(id, known, weakSpots, questions, index = 0, answers = answers, createdAt = now, finishedAt = null)
  }

  /** The stored session, or null when there is none or it cannot be read (a damaged row never crashes Practice). */
  override fun currentSession(): Flow<PracticeSession?> =
    practice.observeCurrent().map { it?.toSession() }.distinctUntilChanged()

  override suspend fun answer(sessionId: Long, index: Int, choice: String): AnswerResult = writing.withLock {
    val row = checkNotNull(practice.session(sessionId)) { "No practice session $sessionId" }
    val session = checkNotNull(row.toSession()) { "Practice session $sessionId cannot be read" }
    val question = checkNotNull(session.questions.getOrNull(index)) { "Practice session $sessionId has no question $index" }
    check(session.answers[index] == null) { "Question $index of practice session $sessionId is answered already" }
    val correct = choice == question.answer
    val answers = session.answers.toMutableList().also { it[index] = choice }
    val next = maxOf(session.index, index + 1)
    val now = day.now().toEpochMilli()
    val finished = if (next >= session.questions.size) row.finishedAt ?: now else null
    check(practice.answer(sessionId, next, StudyJson.answers(answers), finished, question.change.value, correct, now)) {
      "Practice session $sessionId is gone"
    }
    AnswerResult(correct, question.answer, question.change, question.occurrence)
  }

  override fun accuracy(): Flow<List<ChangeAccuracy>> = practice.observeStats()
    .map { rows -> rows.map { ChangeAccuracy(ChangeLabel(it.change), it.seen, it.correct) } }
    .distinctUntilChanged()

  override fun changesMet(): Flow<Set<ChangeLabel>> = practice.observeMet()
    .map { rows -> rows.mapTo(LinkedHashSet()) { ChangeLabel(it.change) } }
    .distinctUntilChanged()

  override suspend fun reset() = writing.withLock { practice.clearStats() }

  private fun PracticeSessionEntity.toSession(): PracticeSession? {
    val questions = StudyJson.questions(questionsJson) ?: return null
    val stored = StudyJson.answers(answersJson).orEmpty()
    val answers = List(questions.size) { stored.getOrNull(it) }
    return PracticeSession(
      id = id,
      focus = focus?.let(::ChangeLabel),
      weakSpots = weakSpots,
      questions = questions,
      index = index.coerceIn(0, questions.size),
      answers = answers,
      createdAt = Instant.ofEpochMilli(createdAt),
      finishedAt = finishedAt?.let(Instant::ofEpochMilli),
    )
  }

  private companion object {
    const val POOL_PER_CHANGE = 80

    /** The web seeds a session with `Math.floor(Math.random() * 1e9)`; the phone takes the clock's milliseconds. */
    const val SEED_RANGE = 1_000_000_000L
  }
}
