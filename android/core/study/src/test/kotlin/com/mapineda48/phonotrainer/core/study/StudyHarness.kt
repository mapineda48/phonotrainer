package com.mapineda48.phonotrainer.core.study

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedSequence
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The study repositories over a real in-memory `learning.db` and the settable fakes of everything else (work-plan 4.6):
 * the library (`FakeClipRepository`), the corpus (`FakeShortRepository`, `FakeOccurrenceRepository`,
 * `FakeClipContentRepository`), the reference, the settings and a [TestClock] in Bogotá time. [addClip] puts a clip on
 * the phone with one short per phrase, each spotlit on its second word, the occurrences of those words in the quiz
 * pool, and the clip data that "How many words?" reads. [restart] opens new repositories on the same database (a
 * process restart). The Saved feed mode is answered like WP-10's from the saved rows.
 */
internal class StudyHarness(
  val clock: TestClock = TestClock(Instant.parse("2026-10-10T14:00:00Z"), ZoneId.of("America/Bogota")),
) : AutoCloseable {
  private val context: Context = ApplicationProvider.getApplicationContext()
  val db: LearningDatabase = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java)
    .addCallback(LearningDatabase.LEARNING_ID_CALLBACK)
    .allowMainThreadQueries()
    .build()
  val clips = FakeClipRepository()
  val shorts = FakeShortRepository()
  val occurrences = FakeOccurrenceRepository()
  val content = FakeClipContentRepository()
  val reference = FakeReferenceRepository()
  val settings = FakeSettingsRepository()

  private val defaultSequence = shorts.sequenceFor

  init {
    shorts.sequenceFor = { mode -> if (mode == FeedMode.Saved) FeedSequence(mode, savedSequence(), 0) else defaultSequence(mode) }
  }

  lateinit var progress: RoomProgressRepository
    private set
  lateinit var sets: RoomSetRepository
    private set
  lateinit var practice: RoomPracticeRepository
    private set
  lateinit var saved: RoomSavedRepository
    private set
  lateinit var protection: RoomStudyProtection
    private set
  lateinit var removal: StudyRemovalHook
    private set

  init {
    restart()
  }

  /** New repositories on the same database, as after a process restart. */
  fun restart() {
    val day = StudyDay(clock)
    val spotlights = SpotlightChanges(db.progressDao(), db.setDao(), db.savedDao(), db.practiceDao(), shorts, content)
    val catalog = StudyCatalog(clips, shorts, db.progressDao(), db.savedDao(), settings, day)
    progress = RoomProgressRepository(db.progressDao(), db.setDao(), db.savedDao(), db.practiceDao(), shorts, clips, spotlights, day)
    sets = RoomSetRepository(db.setDao(), catalog, shorts, occurrences, content, reference, day, Dispatchers.Unconfined)
    practice = RoomPracticeRepository(db.practiceDao(), occurrences, content, reference, day, Dispatchers.Unconfined)
    saved = RoomSavedRepository(db.savedDao(), db.progressDao(), shorts, clips, spotlights, day)
    protection = RoomStudyProtection(db.setDao(), db.progressDao(), day)
    removal = StudyRemovalHook(db.setDao(), db.savedDao(), db.progressDao())
  }

  /**
   * Adds clip [job] (added [addedAt]) with [changes].size shorts: short `i` is phrase `i` (3 s long, four words) whose
   * second word carries `changes[i]` and is its spotlight; [advice] picks its badge.
   */
  fun addClip(
    job: String,
    changes: List<String>,
    addedAt: Instant = clock.now(),
    advice: (Int) -> Advice = { Advice.PRODUCE },
    title: String = "Clip $job",
  ): List<ShortInfo> {
    val id = JobId(job)
    val phrases = changes.mapIndexed { i, change ->
      val t0 = i * 3.0
      AnalysisFixtures.phrase(
        index = i, startS = t0, endS = t0 + 2.4,
        words = listOf(
          AnalysisFixtures.word(0, "so", t0, "s oʊ", "s oʊ"),
          AnalysisFixtures.word(1, "got$i", t0 + 0.5, "ɡ ɑ t", "ɡ ɑ ɾ", changes = listOf(change), diffCost = 0.3),
          AnalysisFixtures.word(2, "it", t0 + 1.0, "ɪ t", "ɪ t"),
          AnalysisFixtures.word(3, "now", t0 + 1.5, "n aʊ", "n aʊ"),
        ),
      )
    }
    content.data[id] = AnalysisFixtures.clip(phrases, jobId = id)
    val infos = changes.mapIndexed { i, change ->
      val spotlight = Spotlight(WordRef(id, i, 1), ChangeLabel(change), advice(i), crossesBoundary = false)
      ShortInfo(ShortId(id, i), i + 1, changes.size, i, i * 3_000L, i * 3_000L + 2_800, spotlight, 1)
    }
    shorts.shortsById.value = shorts.shortsById.value + (id to infos)
    changes.forEachIndexed { i, change ->
      val label = ChangeLabel(change)
      occurrences.byChange[label] = occurrences.byChange[label].orEmpty() + occurrence(id, i, label, title)
    }
    occurrences.variantsByWord.putAll(changes.indices.associate { "got$it" to listOf(WordVariant("ɡɑʔ", 2, emptyList())) })
    val summary = summary(id, title, addedAt)
    clips.all.value = clips.all.value + summary
    clips.details.value = clips.details.value + (id to detail(summary))
    return infos
  }

  fun occurrence(job: JobId, segment: Int, change: ChangeLabel, title: String = "Clip ${job.value}") = Occurrence(
    word = WordRef(job, segment, 1), shortFirstSegment = segment, wordText = "got$segment", dictIpa = "ɡˈɑt",
    realizedIpa = "ɡɑɾ", startMs = segment * 3_000L + 500, endMs = segment * 3_000L + 680, change = change, diffCost = 0.3,
    advice = Advice.PRODUCE, family = reference.state.value.familyOf[change] ?: Family.LEXICAL, crossesBoundary = false,
    nextWordText = "it", clipTitle = title,
  )

  fun summary(id: JobId, title: String, addedAt: Instant, availability: ClipAvailability = ClipAvailability.OnThisPhone) =
    ClipSummary(
      id = id, title = title, durationS = 60.0, words = 40, changeCount = 10,
      source = ClipSource(SourceKind.LINK, null, null, null), availability = availability,
      video = VideoAvailability.AudioOnlyClip, kept = false, origin = ClipOrigin.ACTIVE, olderRules = false,
      shortsSeen = 0, shortsTotal = 10, addedAt = addedAt,
    )

  private fun detail(summary: ClipSummary) = ClipDetail(
    summary = summary, durationS = 60.0, segments = 10, changeCounts = emptyMap(), rulesVersion = 3, phoneEngine = null,
    dialogueSeparation = null, hasDialogueTrack = false, studyPackBytes = null, videoBytes = null, lastWatched = null,
    resume = null, computerError = null,
  )

  /** WP-10's Saved sequence on these fakes: each saved item's short once, most recently saved first, on its word. */
  private fun savedSequence(): List<ShortInfo> = runBlocking {
    db.savedDao().all().mapNotNull { row ->
      val job = JobId(row.jobId)
      val short = shorts.shortsById.value[job].orEmpty().lastOrNull { row.segment in it.id.firstSegment..it.lastSegment }
        ?: return@mapNotNull null
      val spotlight = if (row.kind == SavedDao.WORD) {
        Spotlight(WordRef(job, row.segment, row.word), ChangeLabel(row.change ?: "flapping"), Advice.PRODUCE, false)
      } else {
        short.spotlight
      }
      short.copy(spotlight = spotlight)
    }.distinctBy { it.id }
  }

  override fun close() = db.close()
}
