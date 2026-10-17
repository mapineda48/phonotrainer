package com.mapineda48.phonotrainer.core.database.library.dao

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.OccurrenceEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ShortEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-10's `OccurrenceDao` (architecture 9.4, spec D-48): the corpus queries read only the eligible rows of playable
 * clips, count changes in both units, follow the clip files (a clip that becomes playable is counted at once), carry
 * the word's other labels and the clip's title, and match IPA fragments literally.
 */
@RunWith(RobolectricTestRunner::class)
class OccurrenceDaoTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  private val dao = db.occurrenceDao()
  private val shorts = db.shortDao()

  @After
  fun close() = db.close()

  @Test
  fun changeCountsGiveShortsAndOccurrencesOfEligibleRowsInPlayableClipsOnly() = runBlocking<Unit> {
    playableClip("a")
    index(
      "a",
      row("a", 0, 0, "flapping", short = 0),
      row("a", 0, 1, "flapping", short = 0),
      row("a", 2, 0, "flapping", short = 2),
      row("a", 2, 1, "flapping", short = 2, eligible = false),
      row("a", 2, 1, "linking", short = 2),
    )
    clip("not-here")
    file("not-here", "analysis", "CLIP_DATA", "READY")
    index("not-here", row("not-here", 0, 0, "flapping", short = 0))

    val counts = dao.changeCounts().first().associateBy { it.change }

    assertThat(counts.keys).containsExactly("flapping", "linking")
    assertThat(counts.getValue("flapping")).isEqualTo(ChangeCountRow("flapping", shorts = 2, occurrences = 3))
    assertThat(counts.getValue("linking")).isEqualTo(ChangeCountRow("linking", shorts = 1, occurrences = 1))
  }

  @Test
  fun anObservedCountFollowsTheArrivalOfTheAudio() = runBlocking<Unit> {
    clip("a")
    file("a", "analysis", "CLIP_DATA", "READY")
    index("a", row("a", 0, 0, "flapping", short = 0))
    val observing = CompletableDeferred<List<ChangeCountRow>>()
    val counted = async(Dispatchers.Default) {
      dao.changeCounts().onEach { observing.complete(it) }.first { it.isNotEmpty() }
    }
    assertThat(observing.await()).isEmpty()

    // The sync engine records the verified audio in its own transaction; the count observes the artifact table.
    withContext(Dispatchers.IO) { db.runInTransaction { file("a", "audio_mix", "AUDIO_MIX", "READY") } }

    assertThat(withTimeout(TIMEOUT_MS) { counted.await() }).containsExactly(ChangeCountRow("flapping", 1, 1))
  }

  @Test
  fun rowsCarryTheClipTitleAndTheWordsOtherLabels() = runBlocking<Unit> {
    playableClip("a")
    index(
      "a",
      row("a", 0, 0, "flapping", short = 0),
      row("a", 0, 0, "vowel_reduction", short = 0),
      row("a", 0, 0, "linking", short = 0),
      row("a", 0, 1, "flapping", short = 0),
    )

    val rows = dao.eligibleOf("flapping", onlyClip = null)

    assertThat(rows.map { it.clipTitle }.distinct()).containsExactly("Clip a")
    assertThat(rows.first { it.occurrence.word == 0 }.otherChanges!!.split(' ')).containsExactly("vowel_reduction", "linking")
    assertThat(rows.first { it.occurrence.word == 1 }.otherChanges).isNull()
  }

  @Test
  fun examplesAreClearestFirstAndCanBeLimitedToOneClip() = runBlocking<Unit> {
    playableClip("a")
    playableClip("b")
    index("a", row("a", 0, 0, "flapping", short = 0, cost = 0.5), row("a", 0, 1, "flapping", short = 0, cost = 0.1))
    index("b", row("b", 0, 0, "flapping", short = 0, cost = 0.3))

    assertThat(dao.eligibleOf("flapping", null).map { it.occurrence.diffCost }).containsExactly(0.1, 0.3, 0.5).inOrder()
    assertThat(dao.eligibleOf("flapping", "b").map { it.occurrence.jobId }).containsExactly("b")
  }

  @Test
  fun ipaFragmentsMatchTheHeardOrTheDictionaryFormLiterally() = runBlocking<Unit> {
    playableClip("a")
    index(
      "a",
      row("a", 0, 0, "flapping", short = 0, dict = "wɔtɚ", heard = "wɔɾɚ"),
      row("a", 0, 1, "t_deletion", short = 0, dict = "læst", heard = "læs"),
      row("a", 0, 2, "linking", short = 0, dict = "100%", heard = "a_b"),
    )

    assertThat(dao.eligibleLike("%ɾ%").map { it.occurrence.word }).containsExactly(0)
    assertThat(dao.eligibleLike("%t%").map { it.occurrence.word }).containsExactly(0, 1)
    assertThat(dao.eligibleLike("%\\%%").map { it.occurrence.word }).containsExactly(2)
    assertThat(dao.eligibleLike("%\\_%").map { it.occurrence.word }).containsExactly(2)
  }

  @Test
  fun theQuizOrderIsByChangeThenMostDivergentFirst() = runBlocking<Unit> {
    playableClip("a")
    index(
      "a",
      row("a", 0, 0, "linking", short = 0, cost = 0.2),
      row("a", 0, 1, "flapping", short = 0, cost = 0.1),
      row("a", 0, 2, "flapping", short = 0, cost = 0.9),
    )

    val order = dao.eligibleByChange().map { it.occurrence.change to it.occurrence.diffCost }

    assertThat(order).containsExactly("flapping" to 0.9, "flapping" to 0.1, "linking" to 0.2).inOrder()
  }

  @Test
  fun totalsCountThePlayableIndexedClips() = runBlocking<Unit> {
    playableClip("a", words = 40)
    index("a", row("a", 0, 0, "flapping", short = 0), row("a", 0, 1, "flapping", short = 0, eligible = false))
    playableClip("unindexed", words = 10)
    clip("not-here", words = 70)
    file("not-here", "analysis", "CLIP_DATA", "READY")
    index("not-here", row("not-here", 0, 0, "flapping", short = 0))

    assertThat(dao.totals().first()).isEqualTo(TotalsRow(clips = 1, shorts = 1, words = 40, occurrences = 1))
  }

  private suspend fun index(jobId: String, vararg rows: OccurrenceEntity) {
    val firsts = rows.map { it.shortFirstSegment }.distinct().sorted().ifEmpty { listOf(0) }
    val shortRows = firsts.mapIndexed { i, first ->
      ShortEntity(
        jobId = jobId, firstSegment = first, ruleVersion = 1, ordinal = i + 1, lastSegment = first + 1,
        startMs = 0, endMs = 1_000, playStartMs = 0, playEndMs = 1_400, markedWords = 1,
      )
    }
    shorts.replaceIndex(jobId, shortRows, rows.toList(), "rev", 1)
  }

  private fun playableClip(id: String, words: Int? = null) {
    clip(id, words)
    file(id, "analysis", "CLIP_DATA", "READY")
    file(id, "audio_mix", "AUDIO_MIX", "READY")
  }

  private fun clip(id: String, words: Int? = null) {
    val values = ContentValues().apply {
      put("job_id", id)
      put("server_id", "server-1")
      put("library_id", "library-1")
      put("origin", "ACTIVE")
      put("revision", "listing-1")
      put("status", "DONE")
      put("title", "Clip $id")
      put("word_count", words)
      put("first_seen_at", 1_000L)
    }
    db.openHelper.writableDatabase.insert("clip", SQLiteDatabase.CONFLICT_ABORT, values)
  }

  private fun file(id: String, name: String, role: String, state: String) {
    val values = ContentValues().apply {
      put("job_id", id)
      put("name", name)
      put("role", role)
      put("remote_status", "READY")
      put("local_state", state)
      put("local_file", "clips/$id/$name")
      put("local_sha256", "sha-$name")
      put("updated_at", 1_000L)
    }
    db.openHelper.writableDatabase.insert("artifact", SQLiteDatabase.CONFLICT_REPLACE, values)
  }

  private companion object {
    const val TIMEOUT_MS = 5_000L

    fun row(
      jobId: String,
      segment: Int,
      word: Int,
      change: String,
      short: Int,
      eligible: Boolean = true,
      cost: Double = 0.2,
      dict: String = "wɔtɚ",
      heard: String = "wɔɾɚ",
    ) = OccurrenceEntity(
      jobId = jobId, segment = segment, word = word, change = change, shortFirstSegment = short,
      wordText = "word$word", wordKey = "word$word", dictIpa = dict, realizedIpa = heard, startMs = 0, endMs = 300,
      diffCost = cost, lowConfidence = !eligible, eligible = eligible, crossesBoundary = false, advice = "PRODUCE",
      family = "TD",
    )
  }
}
