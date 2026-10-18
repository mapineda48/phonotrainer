package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.database.learning.dao.SetDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SetWithItems
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetItemEntity
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SetRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.StudySetKind
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.study.quiz.Quiz
import com.mapineda48.phonotrainer.core.study.quiz.QuizGenerator
import com.mapineda48.phonotrainer.core.study.sets.Neighbour
import com.mapineda48.phonotrainer.core.study.sets.SetBuilder
import com.mapineda48.phonotrainer.core.study.sets.SetPick
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Today's set and extra sets (spec F-08; architecture 10). A set is built once by [SetBuilder] with up to
 * [QUESTIONS] questions from the changes of its shorts ([QuizGenerator], spec F-08 item 5) and stored; afterwards only
 * its cursor, its answers and the `dropped` mark of shorts whose clip was deleted change. Today's set is the first set
 * of the local day (seeded with the day, so its questions repeat); "Another set" builds an extra set with the same
 * rules, or, when nothing new is due, a replay of the seen shorts ("Replay the mix", spec F-06 item 9; the feed offers
 * both through this one call).
 *
 * Positions are stored item positions: the shorts first, then the questions. The cursor is the first item the learner
 * has not moved past, so a short before it is `done` (spec D-45), and the set is complete once the cursor passes its
 * last item.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomSetRepository @Inject constructor(
  private val sets: SetDao,
  private val catalog: StudyCatalog,
  private val shorts: ShortRepository,
  private val occurrences: OccurrenceRepository,
  private val content: ClipContentRepository,
  private val references: ReferenceRepository,
  private val day: StudyDay,
  @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : SetRepository {
  private val building = Mutex()

  override fun today(): Flow<StudySet?> =
    day.dates()
      .flatMapLatest { sets.observeLatest(SetDao.TODAY, it.toString()) }
      .flatMapLatest { row -> if (row == null) flowOf(null) else resolve(row) }
      .distinctUntilChanged()

  override suspend fun ensureToday(): StudySet? = building.withLock {
    val date = day.today()
    sets.latest(SetDao.TODAY, date.toString())?.let { return@withLock resolve(it).first() }
    create(StudySetKind.TODAY, date, seed = date.toEpochDay(), replayWhenEmpty = false)
  }

  override suspend fun anotherSet(): StudySet? = building.withLock {
    create(StudySetKind.EXTRA, day.today(), seed = sets.count() + 1L, replayWhenEmpty = true)
  }

  override suspend fun advance(setId: Long, position: Int) = sets.advance(setId, position, day.now().toEpochMilli())

  override suspend fun answer(setId: Long, position: Int, choice: String): AnswerResult {
    val row = checkNotNull(sets.get(setId)) { "No set $setId" }
    val item = checkNotNull(row.items.firstOrNull { it.position == position }) { "Set $setId has no item $position" }
    check(item.kind == SetDao.QUESTION) { "Item $position of set $setId is not a question" }
    check(item.choice == null) { "Question $position of set $setId is answered already" }
    val question = checkNotNull(StudyJson.question(item.questionJson)) { "Question $position of set $setId cannot be read" }
    val correct = choice == question.answer
    check(sets.answer(setId, position, choice, correct, question.change.value, day.now().toEpochMilli())) {
      "Question $position of set $setId is answered already"
    }
    return AnswerResult(correct, question.answer, question.change, question.occurrence)
  }

  private suspend fun create(kind: StudySetKind, date: LocalDate, seed: Long, replayWhenEmpty: Boolean): StudySet? {
    val input = catalog.setInput(before = tailOf(sets.newest()))
    val picks = withContext(cpu) {
      SetBuilder.build(input).ifEmpty { if (replayWhenEmpty) SetBuilder.replay(input) else emptyList() }
    }
    if (picks.isEmpty()) return null
    val questions = questionsFor(picks, seed)
    val now = day.now().toEpochMilli()
    val entity = StudySetEntity(
      kind = kind.name,
      localDate = date.toString(),
      seed = seed,
      ruleVersion = SetBuilder.RULE_VERSION,
      createdAt = now,
    )
    val items = picks.mapIndexed { position, pick -> pick.toEntity(position) } +
      questions.mapIndexed { n, question -> question.toEntity(picks.size + n) }
    val id = sets.create(entity, items)
    return resolve(checkNotNull(sets.get(id))).first()
  }

  /** Up to [QUESTIONS] questions built only from the changes met in this set (retrieval practice, spec F-08 item 5). */
  private suspend fun questionsFor(picks: List<SetPick>, seed: Long): List<Question> {
    val reference = references.reference.value
    val changes = picks.map { it.change }.toSet()
    val quizChanges = Quiz.quizChanges(reference).toSet()
    val pool = Quiz.buildPool(occurrences.quizPool(POOL_PER_CHANGE).filterKeys { it in changes && it in quizChanges })
    if (pool.isEmpty()) return emptyList()
    val generator = QuizGenerator(reference, CorpusQuizSource(occurrences, content))
    // With one change, every "Which change?" answer would be the one the learner just met in every short.
    return withContext(cpu) {
      generator.questions(pool, QUESTIONS, seed, skipChangeKind = pool.map { it.change }.distinct().size <= 1)
    }
  }

  /** The clips and changes of the last shorts of [set], so the next set continues its interleaving. */
  private fun tailOf(set: SetWithItems?): List<Neighbour> =
    set?.ordered.orEmpty().filter { it.kind == SetDao.SHORT && !it.dropped && it.jobId != null }
      .takeLast(2).map { Neighbour(JobId(it.jobId!!), it.spotChange?.let(::ChangeLabel)) }

  /**
   * The stored set with its shorts as the corpus has them now: each short found by its first phrase (or the short that
   * now contains it), with the stored spotlight; a short whose clip has no shorts any more is `dropped`.
   */
  private fun resolve(row: SetWithItems): Flow<StudySet> {
    val jobs = row.items.filter { it.kind == SetDao.SHORT }.mapNotNull { it.jobId }.distinct().map(::JobId)
    val byJob: Flow<Map<JobId, List<ShortInfo>>> = if (jobs.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(jobs.map { job -> shorts.shorts(job) }) { lists -> jobs.zip(lists).toMap() }
    }
    return byJob.mapLatest { toStudySet(row, it) }
  }

  private suspend fun toStudySet(row: SetWithItems, byJob: Map<JobId, List<ShortInfo>>): StudySet {
    val set = row.set
    val ordered = row.ordered
    val savedSpotlights = if (ordered.any { it.savedTag }) {
      shorts.sequence(FeedMode.Saved).items.mapNotNull { short -> short.spotlight?.let { short.id to it } }.toMap()
    } else {
      emptyMap()
    }
    val items = ArrayList<StudySetItem>(ordered.size)
    for (item in ordered) {
      if (item.kind == SetDao.SHORT) {
        items += shortItem(item, set.cursor, byJob, savedSpotlights)
      } else {
        // Questions come last: one that cannot be read ends the list, so every position still matches its item.
        val question = StudyJson.question(item.questionJson) ?: break
        items += StudySetItem.QuestionItem(question, item.choice, item.correct)
      }
    }
    return StudySet(
      id = set.id,
      kind = if (set.kind == SetDao.EXTRA) StudySetKind.EXTRA else StudySetKind.TODAY,
      date = LocalDate.parse(set.localDate),
      items = items,
      cursor = set.cursor,
      answered = set.answered,
      right = set.right,
      completedAt = set.completedAt?.let(Instant::ofEpochMilli),
    )
  }

  private suspend fun shortItem(
    item: StudySetItemEntity,
    cursor: Int,
    byJob: Map<JobId, List<ShortInfo>>,
    savedSpotlights: Map<ShortId, Spotlight>,
  ): StudySetItem.Short {
    val job = JobId(item.jobId.orEmpty())
    val first = item.firstSegment ?: 0
    val list = byJob[job].orEmpty()
    val info = list.firstOrNull { it.id.firstSegment == first }
      ?: list.firstOrNull { first in it.id.firstSegment..maxOf(it.id.firstSegment, it.lastSegment) }
    val spotlight = spotlightOf(item, job, info, savedSpotlights)
    val short = info?.copy(spotlight = spotlight)
      ?: ShortInfo(ShortId(job, first), ordinal = 0, ofTotal = 0, lastSegment = first, playStartMs = 0, playEndMs = 0, spotlight, 0)
    return StudySetItem.Short(
      short = short,
      spotlight = spotlight,
      savedTag = item.savedTag,
      dropped = item.dropped || info == null,
      done = item.position < cursor,
    )
  }

  /**
   * The stored spotlight, as the corpus describes it when it still can (the saved short's spotlight, else the short's
   * default one); otherwise rebuilt from the stored word and change, so a set never changes its spotlight.
   */
  private suspend fun spotlightOf(
    item: StudySetItemEntity,
    job: JobId,
    info: ShortInfo?,
    savedSpotlights: Map<ShortId, Spotlight>,
  ): Spotlight? {
    val change = item.spotChange?.let(::ChangeLabel)
    val word = if (item.spotSegment != null && item.spotWord != null) WordRef(job, item.spotSegment!!, item.spotWord!!) else null
    val known = listOfNotNull(info?.let { savedSpotlights[it.id] }, info?.spotlight)
    if (word == null || change == null) return known.firstOrNull()
    known.firstOrNull { it.word == word && it.change == change }?.let { return it }
    val advice = references.reference.value.practice[change]?.advice ?: Advice.UNDERSTAND
    return Spotlight(word, change, advice, crossesBoundary(word))
  }

  private suspend fun crossesBoundary(word: WordRef): Boolean = try {
    content.clipData(word.jobId).phrase(word.segment)?.words?.getOrNull(word.word)?.crossesBoundary == true
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (unavailable: Exception) {
    false
  }

  private fun SetPick.toEntity(position: Int) = StudySetItemEntity(
    setId = 0,
    position = position,
    kind = SetDao.SHORT,
    jobId = short.id.jobId.value,
    firstSegment = short.id.firstSegment,
    spotSegment = spotlight.word.segment,
    spotWord = spotlight.word.word,
    spotChange = spotlight.change.value,
    savedTag = saved,
  )

  private fun Question.toEntity(position: Int) = StudySetItemEntity(
    setId = 0,
    position = position,
    kind = SetDao.QUESTION,
    jobId = occurrence.word.jobId.value,
    questionJson = StudyJson.question(this),
  )

  companion object {
    /** Questions after a set's shorts (spec F-08 item 1). */
    const val QUESTIONS = 5

    /** Occurrences per change in the quiz pool (the web's `PER_PHENOMENON`). */
    const val POOL_PER_CHANGE = 80
  }
}
