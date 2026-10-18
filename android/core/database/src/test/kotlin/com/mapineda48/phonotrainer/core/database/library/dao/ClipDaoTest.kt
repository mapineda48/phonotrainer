package com.mapineda48.phonotrainer.core.database.library.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.database.library.entity.SyncMetaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sync engine's queries (WP-08; architecture 4.2, 7.3 to 7.7): the catalogue's order, what a listing may touch,
 * the library and "Forget" tags, the learner's choices, what needs indexing, and the artifact and `sync_meta` rows.
 */
@RunWith(RobolectricTestRunner::class)
class ClipDaoTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  private val clips = db.clipDao()
  private val artifacts = db.artifactDao()
  private val meta = db.syncMetaDao()

  @After
  fun close() = db.close()

  private fun clip(
    id: String,
    status: String = "DONE",
    createdAt: Long = 1_000,
    origin: String = "ACTIVE",
    server: String = "srv_a",
    library: String = "lib_a",
    device: String? = null,
  ) = ClipEntity(
    jobId = id, serverId = server, libraryId = library, origin = origin, revision = "r", status = status, title = id,
    submittedByDevice = device, createdAt = createdAt, firstSeenAt = 1,
  )

  private fun artifact(job: String, name: String, role: String, state: String = "ABSENT", sha: String? = null) =
    ArtifactEntity(
      jobId = job, name = name, role = role, remoteStatus = "READY", localState = state,
      localFile = if (state == "READY") "clips/$job/$name" else null, localSha256 = sha, updatedAt = 5,
    )

  @Test
  fun theCatalogueListsFinishedClipsNewestFirstWithTiesBrokenByJobId() = runTest {
    clips.insert(clip("20261010-090000-aaaa", createdAt = 1_000))
    clips.insert(clip("20261010-090000-bbbb", createdAt = 1_000))
    clips.insert(clip("20261010-080000-cccc", createdAt = 500))
    clips.insert(clip("20261010-100000-dddd", status = "RUNNING", createdAt = 2_000))

    val ids = clips.observeFinished().first().map { it.jobId }

    assertThat(ids).containsExactly("20261010-090000-bbbb", "20261010-090000-aaaa", "20261010-080000-cccc").inOrder()
  }

  @Test
  fun startedOnYourComputerHoldsUnfinishedJobsThisPhoneDidNotSubmit() = runTest {
    clips.insert(clip("browser", status = "RUNNING"))
    clips.insert(clip("failed", status = "ERROR"))
    clips.insert(clip("mine", status = "RUNNING", device = "dev_me"))
    clips.insert(clip("done", status = "DONE"))
    clips.insert(clip("forgotten", status = "ERROR", origin = "FORGOTTEN_COMPUTER"))

    assertThat(clips.observeBrowserJobs().first().map { it.jobId }).containsExactly("browser", "failed")

    clips.hide("failed")
    assertThat(clips.observeBrowserJobs().first().single { it.jobId == "failed" }.hidden).isTrue()
  }

  @Test
  fun aNewLibraryTagsOnlyThatComputersActiveClipsOfOtherLibraries() = runTest {
    clips.insert(clip("old", library = "lib_a"))
    clips.insert(clip("new", library = "lib_b"))
    clips.insert(clip("other computer", server = "srv_b", library = "lib_a"))
    clips.insert(clip("forgotten", origin = "FORGOTTEN_COMPUTER"))

    assertThat(clips.tagOtherLibrary("srv_a", "lib_b")).isEqualTo(1)

    assertThat(clips.get("old")!!.origin).isEqualTo("OTHER_LIBRARY")
    assertThat(clips.get("new")!!.origin).isEqualTo("ACTIVE")
    assertThat(clips.get("other computer")!!.origin).isEqualTo("ACTIVE")
    assertThat(clips.get("forgotten")!!.origin).isEqualTo("FORGOTTEN_COMPUTER")
    assertThat(clips.active("srv_a", "lib_b").map { it.jobId }).containsExactly("new")
  }

  @Test
  fun forgettingAComputerTagsItsActiveClipsAndKeepsThem() = runTest {
    clips.insert(clip("a"))
    clips.insert(clip("b", library = "lib_b", origin = "OTHER_LIBRARY"))
    clips.insert(clip("c", server = "srv_b"))

    assertThat(clips.tagForgotten("srv_a")).isEqualTo(1)

    assertThat(clips.all().associate { it.jobId to it.origin })
      .containsExactly("a", "FORGOTTEN_COMPUTER", "b", "OTHER_LIBRARY", "c", "ACTIVE")
  }

  @Test
  fun theLearnersChoicesAboutThisPhonesCopy() = runTest {
    clips.insert(clip("a").copy(onPhoneAt = 9))

    clips.setKept("a", true)
    clips.markRemovedByLearner("a")
    assertThat(clips.get("a")!!.let { listOf(it.kept, it.removedByLearner, it.wanted, it.onPhoneAt) })
      .containsExactly(true, true, false, null).inOrder()

    clips.markWanted("a")
    clips.setVideoWanted("a", true)
    assertThat(clips.get("a")!!.let { listOf(it.removedByLearner, it.wanted, it.videoWanted) })
      .containsExactly(false, true, true).inOrder()

    assertThat(clips.markOnPhone("a", 10)).isEqualTo(1)
    assertThat(clips.markOnPhone("a", 20)).isEqualTo(0)
    assertThat(clips.get("a")!!.onPhoneAt).isEqualTo(10)
    clips.clearVideoWanted()
    assertThat(clips.get("a")!!.videoWanted).isFalse()
  }

  @Test
  fun deletionsOnTheComputerWaitForTheirScreensAndRemovingAClipTakesItsFileRows() = runTest {
    clips.insert(clip("a"))
    clips.insert(clip("b"))
    artifacts.insert(artifact("a", "analysis", "CLIP_DATA"))

    clips.markDeletedOnComputer(listOf("a"))
    assertThat(clips.deferredRemovals()).containsExactly("a")

    assertThat(clips.delete(listOf("a"))).isEqualTo(1)
    assertThat(artifacts.forJob("a")).isEmpty()
    assertThat(clips.count()).isEqualTo(1)
  }

  @Test
  fun aLeanEventUpdatesOnlyAnActiveClipsState() = runTest {
    clips.insert(clip("a", status = "QUEUED"))
    clips.insert(clip("b", status = "QUEUED", origin = "OTHER_LIBRARY"))

    val changed = clips.updateLean("a", "RUNNING", "TRANSCRIBE", 40, null, "Title", null, null, 5, null)
    val untouched = clips.updateLean("b", "RUNNING", "TRANSCRIBE", 40, null, "Title", null, null, 5, null)

    assertThat(changed).isEqualTo(1)
    assertThat(untouched).isEqualTo(0)
    assertThat(clips.get("a")!!.let { listOf(it.status, it.stage, it.percent, it.title, it.revision) })
      .containsExactly("RUNNING", "TRANSCRIBE", 40, "Title", "r").inOrder()
  }

  @Test
  fun clipsNeedIndexingWhenTheirVerifiedClipDataIsNotTheIndexedRevision() = runTest {
    val sha = "abcdef0123456789" + "0".repeat(48)
    clips.insert(clip("never"))
    clips.insert(clip("same").copy(indexedRevision = sha.take(16)))
    clips.insert(clip("stale").copy(indexedRevision = "ffffffffffffffff"))
    clips.insert(clip("absent"))
    listOf("never", "same", "stale").forEach { artifacts.insert(artifact(it, "analysis", "CLIP_DATA", "READY", sha)) }
    artifacts.insert(artifact("absent", "analysis", "CLIP_DATA"))

    assertThat(clips.needingIndex()).containsExactly("never", "stale")
  }

  @Test
  fun theNewestRulesAndTheShortCountsOfTheCatalogue() = runTest {
    clips.insert(clip("a").copy(rulesVersion = 2))
    clips.insert(clip("b").copy(rulesVersion = 3))
    db.openHelper.writableDatabase.execSQL(
      "INSERT INTO short (job_id, first_segment, rule_version, ordinal, last_segment, start_ms, end_ms, play_start_ms, " +
        "play_end_ms, marked_words) VALUES ('a', 0, 1, 1, 1, 0, 1, 0, 1, 0), ('a', 2, 1, 2, 3, 1, 2, 1, 2, 0)",
    )

    assertThat(clips.observeNewestRules().first()).isEqualTo(3)
    assertThat(clips.observeShortCounts().first()).containsExactly(ClipShortCount("a", 2))
  }

  @Test
  fun artifactRowsKeepTheirResumeStateAndInterruptedDownloadsGoBackToTheQueue() = runTest {
    clips.insert(clip("a"))
    artifacts.insert(artifact("a", "audio_mix", "AUDIO_MIX", "DOWNLOADING").copy(partRevision = "0123456789abcdef"))
    artifacts.insert(artifact("a", "analysis", "CLIP_DATA", "DOWNLOADING").copy(updatedAt = 50))

    assertThat(artifacts.recordPart("a", "audio_mix", 4_096, 6)).isEqualTo(1)
    assertThat(artifacts.get("a", "audio_mix")!!.partBytes).isEqualTo(4_096)

    assertThat(artifacts.requeueInterrupted(before = 10)).isEqualTo(1)
    assertThat(artifacts.get("a", "audio_mix")!!.localState).isEqualTo("QUEUED")
    assertThat(artifacts.get("a", "analysis")!!.localState).isEqualTo("DOWNLOADING")
    assertThat(artifacts.files().map { it.partRevision }).contains("0123456789abcdef")
    assertThat(artifacts.observeAll().first()).hasSize(2)
    assertThat(artifacts.delete("a", "analysis")).isEqualTo(1)
  }

  @Test
  fun syncMetaIsAKeyValueStore() = runTest {
    assertThat(meta.get("k")).isNull()

    meta.put(SyncMetaEntity("k", "1"))
    meta.put(SyncMetaEntity("k", "2"))

    assertThat(meta.get("k")).isEqualTo("2")
    assertThat(meta.observe("k").first()).isEqualTo("2")
    assertThat(meta.delete("k")).isEqualTo(1)
    assertThat(meta.count()).isEqualTo(0)
  }
}
