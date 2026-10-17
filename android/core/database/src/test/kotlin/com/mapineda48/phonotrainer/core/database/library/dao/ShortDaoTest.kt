package com.mapineda48.phonotrainer.core.database.library.dao

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.OccurrenceEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ShortEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-10's `ShortDao` (architecture 4.2, 9.5): a clip's index is replaced in one transaction together with its index
 * state, a failure leaves the previous index whole, a drop clears both, stale indexes are found by rule version, and
 * the playable rule needs the clip data and one audio track on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class ShortDaoTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  private val dao = db.shortDao()

  @After
  fun close() = db.close()

  @Test
  fun replacingAnIndexSwapsEveryRowAndRecordsTheRevisionAndRuleVersion() = runBlocking<Unit> {
    clip("a")
    clip("b")
    assertThat(dao.replaceIndex("a", shorts("a", 0, 3), occurrences("a", 0, 3), "rev-1", 1)).isTrue()
    assertThat(dao.replaceIndex("b", shorts("b", 0), occurrences("b", 0), "rev-b", 1)).isTrue()

    assertThat(dao.replaceIndex("a", shorts("a", 0, 5, 9), occurrences("a", 5), "rev-2", 2)).isTrue()

    assertThat(dao.shortsOf("a").first().map { it.short.firstSegment }).containsExactly(0, 5, 9).inOrder()
    assertThat(dao.shortsOf("a").first().map { it.ofTotal }.distinct()).containsExactly(3)
    assertThat(count("SELECT COUNT(*) FROM occurrence WHERE job_id = 'a'")).isEqualTo(1)
    assertThat(dao.indexState("a")).isEqualTo(IndexState("a", "rev-2", 2))
    // The other clip is untouched.
    assertThat(dao.shortsOf("b").first()).hasSize(1)
    assertThat(dao.indexState("b")).isEqualTo(IndexState("b", "rev-b", 1))
  }

  @Test
  fun aReplacementThatFailsHalfwayLeavesThePreviousIndexWhole() = runBlocking<Unit> {
    clip("a")
    dao.replaceIndex("a", shorts("a", 0, 3), occurrences("a", 0, 3), "rev-1", 1)
    val duplicate = occurrences("a", 0) + occurrences("a", 0) // the same primary key twice

    assertThrows(SQLiteConstraintException::class.java) {
      runBlocking { dao.replaceIndex("a", shorts("a", 0, 4, 8), duplicate, "rev-2", 1) }
    }

    assertThat(dao.shortsOf("a").first().map { it.short.firstSegment }).containsExactly(0, 3).inOrder()
    assertThat(count("SELECT COUNT(*) FROM occurrence WHERE job_id = 'a'")).isEqualTo(2)
    assertThat(dao.indexState("a")).isEqualTo(IndexState("a", "rev-1", 1))
  }

  @Test
  fun rowsOfAnotherClipAreRefusedBeforeAnythingIsWritten() = runBlocking<Unit> {
    clip("a")
    dao.replaceIndex("a", shorts("a", 0), occurrences("a", 0), "rev-1", 1)

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { dao.replaceIndex("a", shorts("a", 0) + shorts("b", 0), emptyList(), "rev-2", 1) }
    }

    assertThat(dao.indexState("a")).isEqualTo(IndexState("a", "rev-1", 1))
    assertThat(dao.shortsOf("a").first()).hasSize(1)
  }

  @Test
  fun anUnknownClipIsNotIndexed() = runBlocking<Unit> {
    assertThat(dao.replaceIndex("ghost", shorts("ghost", 0), emptyList(), "rev", 1)).isFalse()
    assertThat(dao.count()).isEqualTo(0)
    assertThat(dao.indexState("ghost")).isNull()
  }

  @Test
  fun droppingClearsTheRowsAndTheIndexStateOfThoseClipsOnly() = runBlocking<Unit> {
    clip("a")
    clip("b")
    dao.replaceIndex("a", shorts("a", 0, 3), occurrences("a", 0, 3), "rev-a", 1)
    dao.replaceIndex("b", shorts("b", 0), occurrences("b", 0), "rev-b", 1)

    dao.dropIndex(listOf("a"))

    assertThat(dao.shortsOf("a").first()).isEmpty()
    assertThat(count("SELECT COUNT(*) FROM occurrence WHERE job_id = 'a'")).isEqualTo(0)
    assertThat(dao.indexState("a")).isEqualTo(IndexState("a", null, null))
    assertThat(dao.shortsOf("b").first()).hasSize(1)
    assertThat(dao.indexState("b")).isEqualTo(IndexState("b", "rev-b", 1))
  }

  @Test
  fun staleIndexesAreTheClipsWithClipDataAndAnotherOrNoRuleVersion() = runBlocking<Unit> {
    clip("current")
    clip("older")
    clip("never")
    clip("no-data")
    listOf("current", "older", "never").forEach { artifact(it, "analysis", "CLIP_DATA", "READY") }
    artifact("no-data", "analysis", "CLIP_DATA", "DOWNLOADING", file = null)
    dao.replaceIndex("current", shorts("current", 0), emptyList(), "rev", 2)
    dao.replaceIndex("older", shorts("older", 0), emptyList(), "rev", 1)

    assertThat(dao.staleIndexes(2).map { it.jobId }).containsExactly("older", "never")
  }

  @Test
  fun aClipIsPlayableWithItsClipDataAndOneAudioTrackOnThePhone() = runBlocking<Unit> {
    clip("both")
    artifact("both", "analysis", "CLIP_DATA", "READY")
    artifact("both", "audio_mix", "AUDIO_MIX", "READY")
    clip("dialogue-only")
    artifact("dialogue-only", "analysis", "CLIP_DATA", "READY")
    artifact("dialogue-only", "audio_dialogue", "AUDIO_DIALOGUE", "READY")
    clip("no-audio")
    artifact("no-audio", "analysis", "CLIP_DATA", "READY")
    artifact("no-audio", "audio_mix", "AUDIO_MIX", "DOWNLOADING", file = null)
    clip("no-data")
    artifact("no-data", "audio_mix", "AUDIO_MIX", "READY")
    clip("deleted", deleted = true)
    artifact("deleted", "analysis", "CLIP_DATA", "READY")
    artifact("deleted", "audio_mix", "AUDIO_MIX", "READY")

    val playable = listOf("both", "dialogue-only", "no-audio", "no-data", "deleted").filter { dao.playableCount(it) > 0 }

    assertThat(playable).containsExactly("both", "dialogue-only")
  }

  @Test
  fun onlyVerifiedClipDataFilesAreRead() = runBlocking<Unit> {
    clip("a")
    artifact("a", "analysis", "CLIP_DATA", "READY")
    artifact("a", "analysis_compact", "CLIP_DATA", "QUEUED", file = null)
    artifact("a", "audio_mix", "AUDIO_MIX", "READY")

    assertThat(dao.clipDataFiles("a")).containsExactly(ClipDataFile("analysis", "clips/a/analysis", "sha-analysis"))
  }

  @Test
  fun theShortHoldingASegmentIsFoundByItsPhraseRange() = runBlocking<Unit> {
    clip("a")
    dao.replaceIndex("a", shorts("a", 0, 3, 7), emptyList(), "rev", 1)

    assertThat(dao.shortContaining("a", 5)?.short?.firstSegment).isEqualTo(3)
    assertThat(dao.shortContaining("a", 0)?.short?.firstSegment).isEqualTo(0)
    assertThat(dao.shortContaining("a", 99)).isNull()
  }

  private fun clip(id: String, deleted: Boolean = false) {
    val values = ContentValues().apply {
      put("job_id", id)
      put("server_id", "server-1")
      put("library_id", "library-1")
      put("origin", "ACTIVE")
      put("revision", "listing-1")
      put("status", "DONE")
      put("title", "Clip $id")
      put("deleted_on_computer", deleted)
      put("first_seen_at", 1_000L)
    }
    db.openHelper.writableDatabase.insert("clip", SQLiteDatabase.CONFLICT_ABORT, values)
  }

  private fun artifact(id: String, name: String, role: String, state: String, file: String? = "clips/$id/$name") {
    val values = ContentValues().apply {
      put("job_id", id)
      put("name", name)
      put("role", role)
      put("remote_status", "READY")
      put("local_state", state)
      put("local_file", file)
      put("local_sha256", file?.let { "sha-$name" })
      put("updated_at", 1_000L)
    }
    db.openHelper.writableDatabase.insert("artifact", SQLiteDatabase.CONFLICT_ABORT, values)
  }

  private fun count(query: String): Int =
    db.openHelper.readableDatabase.query(query).use {
      it.moveToFirst()
      it.getInt(0)
    }

  private companion object {
    /** Shorts of [jobId] starting at [firstSegments], each three phrases long at most. */
    fun shorts(jobId: String, vararg firstSegments: Int): List<ShortEntity> = firstSegments.mapIndexed { i, first ->
      ShortEntity(
        jobId = jobId, firstSegment = first, ruleVersion = 1, ordinal = i + 1,
        lastSegment = firstSegments.getOrNull(i + 1)?.minus(1) ?: (first + 2),
        startMs = first * 1_000L, endMs = first * 1_000L + 900, playStartMs = first * 1_000L,
        playEndMs = first * 1_000L + 1_300, markedWords = 1,
      )
    }

    /** One flapping occurrence on the first word of each of [segments]. */
    fun occurrences(jobId: String, vararg segments: Int): List<OccurrenceEntity> = segments.map { segment ->
      OccurrenceEntity(
        jobId = jobId, segment = segment, word = 0, change = "flapping", shortFirstSegment = segment,
        wordText = "water", wordKey = "water", dictIpa = "wɔtɚ", realizedIpa = "wɔɾɚ", startMs = 0, endMs = 300,
        diffCost = 0.2, lowConfidence = false, eligible = true, crossesBoundary = false, advice = "PRODUCE",
        family = "TD",
      )
    }
  }
}
