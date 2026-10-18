package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.database.learning.dao.PracticeDao
import com.mapineda48.phonotrainer.core.database.learning.dao.ProgressDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SetDao
import com.mapineda48.phonotrainer.core.database.learning.entity.SavedItemEntity
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import com.mapineda48.phonotrainer.core.study.quiz.QuizSource
import com.mapineda48.phonotrainer.core.study.sets.Neighbour
import com.mapineda48.phonotrainer.core.study.sets.SavedCandidate
import com.mapineda48.phonotrainer.core.study.sets.SetCandidate
import com.mapineda48.phonotrainer.core.study.sets.SetInput
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** The learner's local day (spec F-08 item 3: "a new today's set is offered after local midnight"). */
internal class StudyDay @Inject constructor(private val clock: Clock) {
  fun now(): Instant = clock.now()

  fun today(): LocalDate = clock.now().atZone(clock.zone()).toLocalDate()

  /** Today's date, emitted again after each local midnight (checked at least once a minute, so clock changes count). */
  fun dates(): Flow<LocalDate> = flow {
    while (true) {
      val now = clock.now()
      val zone = clock.zone()
      val today = now.atZone(zone).toLocalDate()
      emit(today)
      val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
      delay(Duration.between(now, midnight).toMillis().coerceIn(1L, 60_000L))
    }
  }.distinctUntilChanged()

  val zone: ZoneId get() = clock.zone()
}

/** The newest of [rows]' times among those anchored in [short]'s phrases (a later shorts rule maps anchors by range). */
internal inline fun <T> latestIn(rows: List<T>?, short: ShortInfo, segment: (T) -> Int, at: (T) -> Long?): Long? =
  rows.orEmpty().filter { segment(it) in short.id.firstSegment..maxOf(short.id.firstSegment, short.lastSegment) }
    .mapNotNull(at).maxOrNull()

/**
 * What a set is built from, read through the :core:domain interfaces only (work-plan 6: a core WP never uses another's
 * implementation classes): the clips (`ClipRepository`), their playable shorts with each one's default spotlight
 * (`ShortRepository.sequence(InOrder)`), the saved shorts with their saved spotlight (`sequence(Saved)`), the seen
 * state and the clips left out of the Mix (settings).
 */
internal class StudyCatalog @Inject constructor(
  private val clips: ClipRepository,
  private val shorts: ShortRepository,
  private val progress: ProgressDao,
  private val saved: SavedDao,
  private val settings: SettingsRepository,
  private val day: StudyDay,
) {
  suspend fun setInput(before: List<Neighbour>): SetInput {
    val candidates = clips.clips(ClipQuery()).first().flatMap { clip ->
      shorts.sequence(FeedMode.InOrder(clip.id)).items.mapNotNull { short ->
        short.spotlight?.let { SetCandidate(short, it, clip.addedAt) }
      }
    }
    val seenByJob = progress.seen().groupBy { it.jobId }
    val lastSeen = candidates.mapNotNull { candidate ->
      val short = candidate.short
      latestIn(seenByJob[short.id.jobId.value], short, { it.firstSegment }, { it.lastSeenAt })
        ?.let { short.id to Instant.ofEpochMilli(it) }
    }.toMap()
    val savedByJob = saved.all().groupBy { it.jobId }
    val savedShorts = shorts.sequence(FeedMode.Saved).items.map { short ->
      val watched = latestIn(savedByJob[short.id.jobId.value], short, SavedItemEntity::segment, SavedItemEntity::lastWatchedAt)
      SavedCandidate(short, short.spotlight, watched?.let(Instant::ofEpochMilli))
    }
    return SetInput(
      shorts = candidates,
      saved = savedShorts,
      lastSeen = lastSeen,
      excluded = settings.settings.value.excludedFromMix,
      today = day.today(),
      zone = day.zone,
      before = before,
    )
  }
}

/** The quiz's lookups on the phone's corpus; each clip's data is read once per generation. */
internal class CorpusQuizSource(
  private val occurrences: OccurrenceRepository,
  private val content: ClipContentRepository,
) : QuizSource {
  private val clips = HashMap<JobId, ClipData?>()

  override suspend fun variants(word: String): List<WordVariant> = occurrences.variants(word)

  override suspend fun phrase(word: WordRef): Phrase? {
    val data = if (clips.containsKey(word.jobId)) clips[word.jobId] else loadOrNull(word.jobId).also { clips[word.jobId] = it }
    return data?.phrase(word.segment)
  }

  private suspend fun loadOrNull(id: JobId): ClipData? = try {
    content.clipData(id)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (unavailable: Exception) {
    null
  }
}

/**
 * Which change the learner was shown for a short or a word, for "changes met" (spec F-17 item 5) and the change kept
 * with a saved item (spec F-09 item 3 "word, change, clip"). The feed's spotlight depends on its mode (spec F-07
 * spotlight rules), so the Watch tab's last position decides: a change mode shows its change, a set the set's stored
 * spotlight, Saved the saved item's change; otherwise the short's default spotlight.
 */
internal class SpotlightChanges @Inject constructor(
  private val progress: ProgressDao,
  private val sets: SetDao,
  private val saved: SavedDao,
  private val practice: PracticeDao,
  private val shorts: ShortRepository,
  private val content: ClipContentRepository,
) {
  suspend fun ofShort(id: ShortId): ChangeLabel? {
    val info = shorts.shorts(id.jobId).first().firstOrNull { it.id == id }
    val last = progress.slot(ProgressDao.WATCH_LAST)
    val mode = last?.takeIf { it.jobId == id.jobId.value && it.firstSegment == id.firstSegment }?.let { StudyJson.mode(it.modeJson) }
    when (mode) {
      is FeedMode.Change -> return mode.label
      is FeedMode.Mix -> sets.get(mode.setId)?.items
        ?.firstOrNull { it.kind == SetDao.SHORT && it.jobId == id.jobId.value && it.firstSegment == id.firstSegment }
        ?.spotChange?.let { return ChangeLabel(it) }
      FeedMode.Saved -> {
        val lastSegment = maxOf(id.firstSegment, info?.lastSegment ?: id.firstSegment)
        saved.all().firstOrNull { it.jobId == id.jobId.value && it.segment in id.firstSegment..lastSegment && it.change != null }
          ?.change?.let { return ChangeLabel(it) }
      }
      is FeedMode.InOrder, null -> Unit
    }
    return info?.spotlight?.change
  }

  /**
   * The change of a saved word: the change of a question just answered on it (a set's or Practice's explanation), else
   * the change the feed showed on it, else its first label.
   */
  suspend fun ofWord(word: WordRef): ChangeLabel? {
    questionChange(word)?.let { return it }
    val labels = labelsOf(word)
    val shown = shorts.shortContaining(word)?.let { ofShort(it.id) }
    if (shown != null && (labels.isEmpty() || shown in labels)) return shown
    return labels.firstOrNull()
  }

  private suspend fun questionChange(word: WordRef): ChangeLabel? {
    val session = practice.observeCurrent().first()?.let { StudyJson.questions(it.questionsJson) }.orEmpty()
    val set = sets.newest()?.items.orEmpty().filter { it.kind == SetDao.QUESTION }.mapNotNull { StudyJson.question(it.questionJson) }
    return (session + set).firstOrNull { it.occurrence.word == word }?.change
  }

  private suspend fun labelsOf(word: WordRef): List<ChangeLabel> = try {
    content.clipData(word.jobId).phrase(word.segment)?.words?.getOrNull(word.word)?.changes.orEmpty()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (unavailable: Exception) {
    emptyList()
  }
}
