package com.mapineda48.phonotrainer.core.database.learning.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.learning.entity.FeedPositionEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeSessionEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeStatEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.SavedItemEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-14's learning DAOs (architecture 4.3, 10) on the real version 1 schema: seen state counts visits, positions and
 * resume points are upserts, a set's cursor only moves forward, an answer is counted once together with its practice
 * counts, Practice keeps one session, saved anchors are unique, and a clip's deletion clears what belongs to it.
 */
@RunWith(RobolectricTestRunner::class)
class LearningDaoTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java)
    .addCallback(LearningDatabase.LEARNING_ID_CALLBACK)
    .build()
  private val progress = db.progressDao()
  private val sets = db.setDao()
  private val practice = db.practiceDao()
  private val saved = db.savedDao()

  @After
  fun close() = db.close()

  @Test
  fun seeingAShortTwiceKeepsTheFirstTimeAndCountsBothVisits() = runBlocking<Unit> {
    progress.markSeen("a", 0, 100)
    progress.markSeen("a", 0, 200)
    progress.markSeen("a", 4, 300)

    assertThat(progress.seen()).containsExactly(SeenRow("a", 0, 200), SeenRow("a", 4, 300))
    assertThat(progress.seenCount("a", 0)).isEqualTo(2)
    assertThat(progress.observeSeen().first()).hasSize(2)
    assertThat(count("SELECT first_seen_at FROM short_seen WHERE first_segment = 0")).isEqualTo(100)
  }

  @Test
  fun clipProgressIsCreatedOnFirstUseAndEachFieldIsKeptApart() = runBlocking<Unit> {
    progress.setResume("a", 7, 1_500)
    progress.setOpened("a", 1_000)
    progress.setWatched("b", 2_000)
    progress.setResume("a", 9, 2_500)

    assertThat(progress.observeResume("a").first()).isEqualTo(9)
    assertThat(progress.observeResume("b").first()).isNull()
    assertThat(progress.observeResume("missing").first()).isNull()
    assertThat(progress.openedSince(1_000)).containsExactly("a")
    assertThat(progress.openedSince(1_001)).isEmpty()
    assertThat(progress.lastWatched()).containsExactly(WatchedRow("b", 2_000))
  }

  @Test
  fun aFeedSlotIsReplacedNotDuplicated() = runBlocking<Unit> {
    progress.saveSlot(FeedPositionEntity(ProgressDao.WATCH_LAST, "{}", "a", 0, 10, 1))
    progress.saveSlot(FeedPositionEntity(ProgressDao.WATCH_LAST, "{\"x\":1}", "b", 4, 20, 2))
    progress.saveSlot(FeedPositionEntity(ProgressDao.KEEP_WATCHING, "{}", "c", 8, 30, 3))

    assertThat(progress.slot(ProgressDao.WATCH_LAST)).isEqualTo(FeedPositionEntity(ProgressDao.WATCH_LAST, "{\"x\":1}", "b", 4, 20, 2))
    assertThat(progress.observeSlot(ProgressDao.KEEP_WATCHING).first()?.jobId).isEqualTo("c")
    assertThat(count("SELECT COUNT(*) FROM feed_position")).isEqualTo(2)
  }

  @Test
  fun forgettingClipsClearsTheirSeenStateProgressAndPositionsOnly() = runBlocking<Unit> {
    progress.markSeen("a", 0, 1)
    progress.markSeen("b", 0, 1)
    progress.setResume("a", 3, 0)
    progress.setResume("b", 3, 0)
    progress.saveSlot(FeedPositionEntity(ProgressDao.WATCH_LAST, "{}", "a", 0, 0, 1))
    progress.saveSlot(FeedPositionEntity(ProgressDao.KEEP_WATCHING, "{}", "b", 0, 0, 1))

    progress.forgetClips(listOf("a"))

    assertThat(progress.seen().map { it.jobId }).containsExactly("b")
    assertThat(progress.observeResume("a").first()).isNull()
    assertThat(progress.observeResume("b").first()).isEqualTo(3)
    assertThat(progress.slot(ProgressDao.WATCH_LAST)).isNull()
    assertThat(progress.slot(ProgressDao.KEEP_WATCHING)).isNotNull()
  }

  @Test
  fun aStoredSetComesBackWithItsItemsInPositionOrder() = runBlocking<Unit> {
    val id = sets.create(set("2026-10-10"), listOf(short(2, "c"), short(0, "a"), question(3), short(1, "b")))

    val stored = sets.get(id)!!
    assertThat(stored.ordered.map { it.position }).containsExactly(0, 1, 2, 3).inOrder()
    assertThat(stored.ordered.map { it.setId }.distinct()).containsExactly(id)
    assertThat(sets.latest(SetDao.TODAY, "2026-10-10")?.set?.id).isEqualTo(id)
    assertThat(sets.latest(SetDao.TODAY, "2026-10-11")).isNull()
    assertThat(sets.latest(SetDao.EXTRA, "2026-10-10")).isNull()
    assertThat(sets.observeLatest(SetDao.TODAY, "2026-10-10").first()?.set?.id).isEqualTo(id)
    assertThat(sets.newest()?.set?.id).isEqualTo(id)
  }

  @Test
  fun theCursorOnlyMovesForwardAndTheSetCompletesPastItsLastItem() = runBlocking<Unit> {
    val id = sets.create(set("2026-10-10"), listOf(short(0, "a"), short(1, "b"), question(2)))

    sets.advance(id, 2, 10)
    sets.advance(id, 1, 11)
    assertThat(sets.get(id)!!.set.cursor).isEqualTo(2)
    assertThat(sets.get(id)!!.set.completedAt).isNull()

    sets.advance(id, 3, 12)
    sets.advance(id, 3, 13)
    assertThat(sets.get(id)!!.set.cursor).isEqualTo(3)
    assertThat(sets.get(id)!!.set.completedAt).isEqualTo(12)
  }

  @Test
  fun aSetQuestionIsAnsweredOnceAndCountsInPracticeAccuracy() = runBlocking<Unit> {
    val id = sets.create(set("2026-10-10"), listOf(short(0, "a"), question(1), question(2)))

    assertThat(sets.answer(id, 1, "said", correct = true, change = "flapping", at = 5)).isTrue()
    assertThat(sets.answer(id, 1, "dictionary", correct = false, change = "flapping", at = 6)).isFalse()
    assertThat(sets.answer(id, 2, "linking", correct = false, change = "flapping", at = 7)).isTrue()
    // A short is not a question: nothing is written.
    assertThat(sets.answer(id, 0, "said", correct = true, change = "linking", at = 8)).isFalse()

    val stored = sets.get(id)!!
    assertThat(stored.set.answered).isEqualTo(2)
    assertThat(stored.set.right).isEqualTo(1)
    assertThat(stored.ordered[1].choice).isEqualTo("said")
    assertThat(stored.ordered[1].correct).isTrue()
    assertThat(stored.ordered[2].correct).isFalse()
    assertThat(practice.stats()).containsExactly(PracticeStatEntity("flapping", seen = 2, correct = 1))
    assertThat(practice.observeMet().first().map { it.change to it.firstMetAt }).containsExactly("flapping" to 5L)
  }

  @Test
  fun droppingAClipMarksItsShortsInEverySetAndTodaysClipsLeaveItOut() = runBlocking<Unit> {
    val today = sets.create(set("2026-10-10"), listOf(short(0, "a"), short(1, "b"), question(2)))
    val extra = sets.create(set("2026-10-10", SetDao.EXTRA), listOf(short(0, "a"), short(1, "c")))

    sets.dropShortsOf(listOf("a"))

    assertThat(sets.get(today)!!.ordered.map { it.dropped }).containsExactly(true, false, false).inOrder()
    assertThat(sets.get(extra)!!.ordered.map { it.dropped }).containsExactly(true, false).inOrder()
    assertThat(sets.clipsOf(SetDao.TODAY, "2026-10-10")).containsExactly("b")
    assertThat(sets.clipsOf(SetDao.TODAY, "2026-10-09")).isEmpty()
  }

  @Test
  fun practiceKeepsOneSessionAndAnAnswerWritesItsCountsWithIt() = runBlocking<Unit> {
    val first = practice.start(session("[]"))
    val second = practice.start(session("[1]"))

    assertThat(practice.session(first)).isNull()
    assertThat(practice.observeCurrent().first()?.id).isEqualTo(second)

    assertThat(practice.answer(second, 1, "[\"a\"]", null, "linking", correct = false, at = 9)).isTrue()
    assertThat(practice.answer(first, 1, "[\"a\"]", 10, "linking", correct = true, at = 9)).isFalse()

    val current = practice.session(second)!!
    assertThat(current.index).isEqualTo(1)
    assertThat(current.answersJson).isEqualTo("[\"a\"]")
    assertThat(current.finishedAt).isNull()
    assertThat(practice.stats()).containsExactly(PracticeStatEntity("linking", seen = 1, correct = 0))
  }

  @Test
  fun forgettingPracticeHistoryClearsAccuracyButNotTheChangesMet() = runBlocking<Unit> {
    val id = practice.start(session("[]"))
    practice.answer(id, 1, "[]", null, "flapping", correct = true, at = 1)

    practice.clearStats()

    assertThat(practice.observeStats().first()).isEmpty()
    assertThat(practice.count()).isEqualTo(0)
    assertThat(practice.observeMet().first().map { it.change }).containsExactly("flapping")
  }

  @Test
  fun aSavedAnchorIsUniqueAndTheListIsMostRecentFirst() = runBlocking<Unit> {
    val shortId = saved.insert(savedShort("a", 0, at = 1))
    assertThat(saved.insert(savedShort("a", 0, at = 5))).isEqualTo(-1)
    val wordId = saved.insert(savedWord("a", 2, 3, at = 3))

    assertThat(saved.all().map { it.id }).containsExactly(wordId, shortId).inOrder()
    assertThat(saved.observeAll().first().first().kind).isEqualTo(SavedDao.WORD)
    assertThat(saved.find(SavedDao.SHORT, "a", 0, SavedItemEntity.NO_WORD)?.id).isEqualTo(shortId)
    assertThat(saved.delete(shortId)).isEqualTo(1)
    assertThat(saved.delete(shortId)).isEqualTo(0)
    assertThat(saved.count()).isEqualTo(1)
  }

  @Test
  fun watchingPhrasesTouchesOnlyTheItemsAnchoredThereAndADeletionRemovesAClipsItems() = runBlocking<Unit> {
    saved.insert(savedShort("a", 0, at = 1))
    saved.insert(savedWord("a", 3, 1, at = 2))
    saved.insert(savedWord("a", 9, 1, at = 3))
    saved.insert(savedShort("b", 0, at = 4))

    assertThat(saved.touchWatched("a", 0, 4, 50)).isEqualTo(2)

    assertThat(saved.all().associate { "${it.jobId}:${it.segment}" to it.lastWatchedAt })
      .containsExactly("a:0", 50L, "a:3", 50L, "a:9", null, "b:0", null)

    saved.deleteJobs(listOf("a"))
    assertThat(saved.all().map { it.jobId }).containsExactly("b")
  }

  private fun set(date: String, kind: String = SetDao.TODAY) =
    StudySetEntity(kind = kind, localDate = date, seed = 1, ruleVersion = 1, createdAt = 0)

  private fun short(position: Int, job: String) =
    StudySetItemEntity(setId = 0, position = position, kind = SetDao.SHORT, jobId = job, firstSegment = 0)

  private fun question(position: Int) =
    StudySetItemEntity(setId = 0, position = position, kind = SetDao.QUESTION, questionJson = "{}")

  private fun session(questions: String) = PracticeSessionEntity(seed = 1, questionsJson = questions, createdAt = 0)

  private fun savedShort(job: String, segment: Int, at: Long) =
    SavedItemEntity(kind = SavedDao.SHORT, jobId = job, segment = segment, shortFirstSegment = segment, savedAt = at)

  private fun savedWord(job: String, segment: Int, word: Int, at: Long) =
    SavedItemEntity(kind = SavedDao.WORD, jobId = job, segment = segment, word = word, shortFirstSegment = segment, savedAt = at)

  private fun count(sql: String): Long = db.openHelper.readableDatabase.query(sql).use { cursor ->
    cursor.moveToFirst()
    cursor.getLong(0)
  }
}
