package com.mapineda48.phonotrainer.core.database.library.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.database.library.entity.RequestOpEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The device queue's queries (WP-09; architecture 4.2, 8): creation order, what the sender may pick, the outbox order,
 * and what a request shows from the catalogue mirror and its outbox.
 */
@RunWith(RobolectricTestRunner::class)
class RequestDaoTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  private val requests = db.requestDao()
  private val ops = db.requestOpDao()

  @After
  fun close() = db.close()

  @Test
  fun creationOrderGrowsFromOne() = runTest {
    assertThat(requests.nextSeq()).isEqualTo(1)
    requests.insert(link("a", seq = requests.nextSeq()))
    requests.insert(link("b", seq = requests.nextSeq()))

    assertThat(requests.all().map { it.id to it.seq }).containsExactly("a" to 1L, "b" to 2L).inOrder()
  }

  @Test
  fun theSenderPicksDueUnheldWaitingRequestsOfOneKindInCreationOrder() = runTest {
    requests.insert(link("late", seq = 1, nextAttemptAt = 5_000))
    requests.insert(link("held", seq = 2, held = true))
    requests.insert(link("first", seq = 3))
    requests.insert(link("sent", seq = 4, state = "SENT"))
    requests.insert(file("copying", seq = 5, copyComplete = false))
    requests.insert(file("copied", seq = 6, copyComplete = true))
    requests.insert(link("second", seq = 7, nextAttemptAt = 1_000))

    assertThat(requests.dueWaiting("LINK", now = 2_000).map { it.id }).containsExactly("first", "second").inOrder()
    assertThat(requests.dueWaiting("LINK", now = 9_000).map { it.id }).containsExactly("late", "first", "second").inOrder()
    assertThat(requests.dueWaiting("FILE", now = 9_000).map { it.id }).containsExactly("copied")
  }

  @Test
  fun aReconnectionClearsTheWaitsButKeepsTheAttempts() = runTest {
    requests.insert(link("a", seq = 1, nextAttemptAt = 60_000, attempts = 3))
    ops.insert(RequestOpEntity(id = "op", kind = "CANCEL", jobId = "job", createdAt = 1, nextAttemptAt = 60_000, attempts = 2))

    requests.clearWaits()
    ops.clearWaits()

    assertThat(requests.get("a")!!.nextAttemptAt).isNull()
    assertThat(requests.get("a")!!.attempts).isEqualTo(3)
    assertThat(ops.get("op")!!.nextAttemptAt).isNull()
    assertThat(ops.get("op")!!.attempts).isEqualTo(2)
  }

  @Test
  fun theOutboxIsSentInTheOrderItWasMade() = runTest {
    ops.insert(RequestOpEntity(id = "z-second", kind = "RERUN", jobId = "j2", createdAt = 10))
    ops.insert(RequestOpEntity(id = "a-third", kind = "RERUN", jobId = "j3", createdAt = 10))
    ops.insert(RequestOpEntity(id = "m-first", kind = "CANCEL", jobId = "j1", createdAt = 5))
    ops.insert(RequestOpEntity(id = "done", kind = "CANCEL", jobId = "j0", createdAt = 1, state = "DONE"))

    assertThat(ops.pending().map { it.id }).containsExactly("m-first", "z-second", "a-third").inOrder()
  }

  @Test
  fun finishedOperationsArePrunedAndPendingOnesNever() = runTest {
    ops.insert(RequestOpEntity(id = "old-done", kind = "CANCEL", jobId = "j", createdAt = 1, state = "DONE"))
    ops.insert(RequestOpEntity(id = "old-pending", kind = "CANCEL", jobId = "j", createdAt = 1))
    ops.insert(RequestOpEntity(id = "new-done", kind = "CANCEL", jobId = "j", createdAt = 100, state = "FAILED"))

    ops.pruneFinished(before = 50)

    assertThat(ops.pending().map { it.id }).containsExactly("old-pending")
    assertThat(ops.get("old-done")).isNull()
    assertThat(ops.get("new-done")).isNotNull()
  }

  @Test
  fun aRequestShowsItsJobFromTheCatalogueAndWhatWaitsInTheOutbox() = runTest {
    requests.insert(link("a", seq = 1, state = "ANALYZING", jobId = "20261011-101010-abcd"))
    requests.insert(link("b", seq = 2, state = "READY_ON_COMPUTER", jobId = "20261011-101011-abcd"))
    val sql = db.openHelper.writableDatabase
    sql.execSQL(
      "INSERT INTO clip (job_id, server_id, library_id, origin, revision, status, stage, percent, queue_position, title, " +
        "first_seen_at) VALUES ('20261011-101010-abcd', 's', 'l', 'ACTIVE', 'r', 'RUNNING', 'TRANSCRIBE', 40, NULL, " +
        "'A walk in the park', 1)",
    )
    sql.execSQL(
      "INSERT INTO clip (job_id, server_id, library_id, origin, revision, status, title, first_seen_at) " +
        "VALUES ('20261011-101011-abcd', 's', 'l', 'ACTIVE', 'r', 'DONE', 'Coffee', 1)",
    )
    sql.execSQL(
      "INSERT INTO artifact (job_id, name, role, remote_status, remote_bytes, local_state, part_bytes, updated_at) " +
        "VALUES ('20261011-101011-abcd', 'analysis', 'CLIP_DATA', 'READY', 100, 'READY', NULL, 1)",
    )
    sql.execSQL(
      "INSERT INTO artifact (job_id, name, role, remote_status, remote_bytes, local_state, part_bytes, updated_at) " +
        "VALUES ('20261011-101011-abcd', 'audio_mix', 'AUDIO_MIX', 'READY', 300, 'DOWNLOADING', 100, 1)",
    )
    sql.execSQL(
      "INSERT INTO artifact (job_id, name, role, remote_status, remote_bytes, local_state, updated_at) " +
        "VALUES ('20261011-101011-abcd', 'original', 'VIDEO', 'READY', 9000, 'QUEUED', 1)",
    )
    ops.insert(RequestOpEntity(id = "op", kind = "CANCEL", requestId = "a", jobId = "20261011-101010-abcd", createdAt = 1))

    val views = requests.observeAll().first().associateBy { it.request.id }

    val analyzing = views.getValue("a")
    assertThat(analyzing.jobStage).isEqualTo("TRANSCRIBE")
    assertThat(analyzing.jobPercent).isEqualTo(40)
    assertThat(analyzing.jobTitle).isEqualTo("A walk in the park")
    assertThat(analyzing.cancelPending).isTrue()
    assertThat(analyzing.rerunPending).isFalse()
    val ready = views.getValue("b")
    assertThat(ready.packDoneBytes).isEqualTo(200)
    assertThat(ready.packTotalBytes).isEqualTo(400)
    assertThat(ready.packTransfers).isEqualTo(1)
    assertThat(requests.studyPackTransfers("20261011-101011-abcd")).isEqualTo(1)
    assertThat(requests.observe("a").first()!!.jobStatus).isEqualTo("RUNNING")
  }

  @Test
  fun duplicatesAreFoundByKeyInTheQueueAndAmongCatalogueLinks() = runTest {
    requests.insert(link("old", seq = 1, linkKey = "yt:abc"))
    requests.insert(link("new", seq = 2, linkKey = "yt:abc"))
    db.openHelper.writableDatabase.execSQL(
      "INSERT INTO clip (job_id, server_id, library_id, origin, revision, status, title, source_url, first_seen_at) " +
        "VALUES ('20261011-101010-abcd', 's', 'l', 'ACTIVE', 'r', 'DONE', 'A clip', 'https://youtu.be/xyz', 1)",
    )

    assertThat(requests.latestWithLinkKey("yt:abc")!!.id).isEqualTo("new")
    assertThat(requests.latestWithLinkKey("yt:none")).isNull()
    assertThat(requests.catalogueLinks()).containsExactly(
      CatalogueLink("20261011-101010-abcd", "A clip", "https://youtu.be/xyz"),
    )
  }

  @Test
  fun aRerunKeyFindsItsOperation() = runTest {
    ops.insert(RequestOpEntity(id = "key-1", kind = "RERUN", requestId = "a", jobId = "j", createdAt = 1))
    ops.insert(RequestOpEntity(id = "key-2", kind = "CANCEL", requestId = "a", jobId = "j", createdAt = 2))

    assertThat(ops.rerunWithKey("key-1")!!.requestId).isEqualTo("a")
    assertThat(ops.rerunWithKey("key-2")).isNull()
    assertThat(ops.pendingFor("a").map { it.id }).containsExactly("key-1", "key-2").inOrder()
  }

  private fun link(
    id: String,
    seq: Long,
    state: String = "WAITING",
    held: Boolean = false,
    nextAttemptAt: Long? = null,
    attempts: Int = 0,
    jobId: String? = null,
    linkKey: String? = null,
  ) = RequestEntity(
    id = id,
    seq = seq,
    kind = "LINK",
    state = state,
    createdAt = seq,
    updatedAt = seq,
    held = held,
    nextAttemptAt = nextAttemptAt,
    attempts = attempts,
    jobId = jobId,
    linkKey = linkKey,
    linkSent = "https://youtu.be/$id",
  )

  private fun file(id: String, seq: Long, copyComplete: Boolean) = RequestEntity(
    id = id,
    seq = seq,
    kind = "FILE",
    state = "WAITING",
    createdAt = seq,
    updatedAt = seq,
    copyComplete = copyComplete,
  )
}
