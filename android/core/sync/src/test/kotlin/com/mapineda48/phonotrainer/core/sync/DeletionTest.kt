package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import com.mapineda48.phonotrainer.core.testing.computer.JobStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Deletion semantics (architecture 7.7, spec 7.9; QT-10). */
@RunWith(RobolectricTestRunner::class)
class DeletionTest {
  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  @Test
  fun aClipDeletedOnTheComputerWhileOpenStaysUntilItsScreenClosesThenGoesWithOneNotice() = sync { h ->
    // QT-10.
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.syncAndDownload()
    h.leases.open += id

    val now = h.removal.deletedOnComputer(listOf(id.value))

    assertThat(now).isEmpty()
    assertThat(h.clip(id).deletedOnComputer).isTrue()
    assertThat(h.files.clipDir(id.value)!!.listFiles().orEmpty()).isNotEmpty()
    assertThat(h.messages.posted).isEmpty()
    assertThat(h.reconciler.deleted).containsExactly(id)

    // A pass while it is still open removes nothing.
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    assertThat(h.clips.get(id.value)).isNotNull()

    h.leases.release(id)
    h.removal.applyDeferred()

    assertThat(h.clips.get(id.value)).isNull()
    assertThat(h.files.clipDir(id.value)!!.exists()).isFalse()
    assertThat(h.hooks.removed).containsExactly(setOf(id))
    assertThat(h.messages.posted.map { it.kind to it.args }).containsExactly(UserMessageKind.CLIPS_REMOVED to listOf("1"))
  }

  @Test
  fun aRemovalThatWaitedForAScreenIsAppliedByTheNextPassWhenTheReleaseWasMissed() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.leases.open += id
    h.removal.deletedOnComputer(listOf(id.value))
    // The release flow is hot without replay: nobody heard this one.
    h.leases.open -= id

    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.clips.get(id.value)).isNull()
  }

  @Test
  fun oneNoticeCountsTheClipsOfABatchAndJobsThatNeverBecameClipsLeaveSilently() = sync { h ->
    val a = h.computer.addFinishedClip("A")
    val b = h.computer.addFinishedClip("B")
    val failed = h.computer.addJob(JobSeed(status = JobStatus.ERROR, errorCode = "download_forbidden"))
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    h.removal.deletedOnComputer(listOf(a.value, b.value, failed.value))

    assertThat(h.clips.count()).isEqualTo(0)
    assertThat(h.messages.posted.single().args).containsExactly("2")
    assertThat(h.hooks.removed.single()).containsExactly(a, b, failed)
  }

  @Test
  fun aDeletionOfAClipThisPhoneDoesNotKnowChangesNothing() = sync { h ->
    h.computer.addFinishedClip("A")
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.removal.deletedOnComputer(listOf("20261010-000000-0000"))).isEmpty()
    assertThat(h.clips.count()).isEqualTo(1)
    assertThat(h.messages.posted).isEmpty()
  }

  @Test
  fun aDeletionOfMoreClipsThanOneStatementMayBindIsAppliedWhole() = sync { h ->
    // SQLite before 3.32 (Android 10) binds at most 999 variables in one statement.
    val ids = (1..1_200).map { "20261010-000000-${it.toString().padStart(4, '0')}" }
    for (id in ids) {
      h.clips.insert(
        ClipEntity(
          jobId = id, serverId = h.computer.serverId.value, libraryId = h.computer.libraryId.value, origin = "ACTIVE",
          revision = "r", status = "DONE", title = id, submittedByDevice = null, createdAt = 1, firstSeenAt = 1,
        ),
      )
    }

    val removed = h.removal.deletedOnComputer(ids)

    assertThat(removed).hasSize(1_200)
    assertThat(h.clips.count()).isEqualTo(0)
    assertThat(h.messages.posted.single().args).containsExactly("1200")
  }
}
