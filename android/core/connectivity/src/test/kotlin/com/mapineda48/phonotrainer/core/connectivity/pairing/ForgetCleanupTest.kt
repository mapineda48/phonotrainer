package com.mapineda48.phonotrainer.core.connectivity.pairing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What "Forget this computer" leaves in `library.db` (architecture 6.3, spec 7.9, F-22-AC3): every clip stays and
 * the forgotten computer's active ones read "From a computer you forgot"; requests that computer accepted are gone;
 * waiting requests stay, untargeted; its waiting operations are dropped.
 */
@RunWith(RobolectricTestRunner::class)
class ForgetCleanupTest {
  private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
    .allowMainThreadQueries()
    .build()

  @After
  fun close() = database.close()

  private fun clip(jobId: String, serverId: String, origin: String = "ACTIVE") {
    database.openHelper.writableDatabase.execSQL(
      "INSERT INTO clip (job_id, server_id, library_id, origin, revision, status, title, first_seen_at) " +
        "VALUES (?, ?, 'lib_example1', ?, '0123456789abcdef', 'DONE', 'A clip', 0)",
      arrayOf<Any>(jobId, serverId, origin),
    )
  }

  private fun op(id: String, state: String) {
    database.openHelper.writableDatabase.execSQL(
      "INSERT INTO request_op (id, kind, job_id, created_at, state) VALUES (?, 'CANCEL', '20261010-091503-4be1', 0, ?)",
      arrayOf<Any>(id, state),
    )
  }

  private fun request(id: String, seq: Long, state: String, jobId: String?) = RequestEntity(
    id = id, seq = seq, kind = "LINK", state = state, createdAt = 0, updatedAt = 0, targetServerId = "srv_example1",
    jobId = jobId,
  )

  private fun strings(sql: String): List<String> = database.query(sql, null).use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "null") }
  }

  @Test
  fun theForgottenComputersClipsAreTaggedAcceptedRequestsGoAndWaitingOnesStayUntargeted(): Unit = runBlocking {
    clip("20261010-091503-0001", "srv_example1")
    clip("20261010-091503-0002", "srv_example1", origin = "OTHER_LIBRARY")
    clip("20261010-091503-0003", "srv_example2")
    val requests = database.requestDao()
    requests.insert(request("00000000-0000-4000-8000-000000000001", 1, "WAITING", null))
    requests.insert(request("00000000-0000-4000-8000-000000000002", 2, "ON_COMPUTER", "20261010-091503-0001"))
    requests.insert(request("00000000-0000-4000-8000-000000000003", 3, "READY", "20261010-091503-0004"))
    op("op-pending", "PENDING")
    op("op-done", "DONE")

    RoomForgetCleanup(database).forget(ServerId("srv_example1"))

    assertThat(strings("SELECT origin FROM clip ORDER BY job_id"))
      .containsExactly("FORGOTTEN_COMPUTER", "OTHER_LIBRARY", "ACTIVE").inOrder()
    val left = requests.all()
    assertThat(left.map { it.id }).containsExactly("00000000-0000-4000-8000-000000000001")
    assertThat(left.single().targetServerId).isNull()
    assertThat(left.single().state).isEqualTo("WAITING")
    assertThat(strings("SELECT id FROM request_op")).containsExactly("op-done")
  }
}
