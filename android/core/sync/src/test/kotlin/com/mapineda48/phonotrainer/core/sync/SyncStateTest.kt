package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.sync.engine.SyncStateSource
import com.mapineda48.phonotrainer.core.sync.transfer.LiveTransfer
import org.junit.Test

/** The sync state of Appendix A.2 and spec 7.11 ("Syncing · bringing 3 clips · 42 %", "34 MB of 61 MB"). */
class SyncStateTest {
  private fun row(job: String, name: String, state: String, bytes: Long = 100, part: Long? = null, pause: String? = null) =
    ArtifactEntity(
      jobId = job, name = name, role = "AUDIO_MIX", remoteStatus = "READY", remoteBytes = bytes, localState = state,
      partBytes = part, pauseReason = pause, updatedAt = 1,
    )

  private fun state(
    rows: List<ArtifactEntity>,
    connected: Boolean = true,
    downloading: Boolean = false,
    live: LiveTransfer? = null,
    passing: Boolean = false,
    waiting: Int = 0,
  ) = SyncStateSource.stateOf(rows, connected, downloading, live, passing, waiting)

  @Test
  fun nothingOnItsWayAndNothingRunningIsIdle() {
    assertThat(state(listOf(row("a", "analysis", "READY")))).isEqualTo(SyncState.Idle)
    assertThat(state(emptyList(), connected = false)).isEqualTo(SyncState.Idle)
  }

  @Test
  fun filesOnTheirWayCountClipsBytesAndThePercentWithTheLiveFile() {
    val rows = listOf(
      row("a", "analysis", "DOWNLOADING", bytes = 100, part = 10),
      row("a", "audio_mix", "QUEUED", bytes = 300),
      row("b", "analysis", "QUEUED", bytes = 600, part = 60),
      row("c", "analysis", "READY", bytes = 999),
    )

    val active = state(rows, downloading = true, live = LiveTransfer("a", "analysis", 40, 100))

    assertThat(active).isEqualTo(SyncState.Active(bringing = 2, percent = 10, sending = 0, doneBytes = 100, totalBytes = 1_000))
  }

  @Test
  fun aPassSendingRequestsWithNothingToBringIsSyncingWithoutPercent() {
    assertThat(state(emptyList(), passing = true, waiting = 2)).isEqualTo(SyncState.Active(bringing = 0, percent = null, sending = 2))
  }

  @Test
  fun filesWaitingWhileNotConnectedArePausedForThatReason() {
    assertThat(state(listOf(row("a", "analysis", "QUEUED")), connected = false))
      .isEqualTo(SyncState.Paused(PauseReason.NOT_CONNECTED))
  }

  @Test
  fun filesAllPausedForSpaceSayWhichAndTheFloorComesFirst() {
    val budget = row("a", "analysis", "PAUSED", pause = "BUDGET_REACHED")
    val floor = row("b", "analysis", "PAUSED", pause = "FREE_SPACE_FLOOR")

    assertThat(state(listOf(budget))).isEqualTo(SyncState.Paused(PauseReason.BUDGET_REACHED))
    assertThat(state(listOf(budget, floor))).isEqualTo(SyncState.Paused(PauseReason.FREE_SPACE_FLOOR))
    // A file still on its way besides the paused one keeps the engine syncing.
    assertThat(state(listOf(budget, row("c", "analysis", "QUEUED")))).isInstanceOf(SyncState.Active::class.java)
  }

  @Test
  fun aVideoTheLearnerRemovedIsNotOnItsWay() {
    assertThat(state(listOf(row("a", "original", "ABSENT", pause = "REMOVED_BY_LEARNER")))).isEqualTo(SyncState.Idle)
  }

  @Test
  fun aNewRevisionOfAFileInUseIsOnItsWay() {
    val update = row("a", "analysis", "READY", part = 50).copy(partRevision = "0123456789abcdef")

    assertThat(state(listOf(update), downloading = true)).isEqualTo(
      SyncState.Active(bringing = 1, percent = 50, sending = 0, doneBytes = 50, totalBytes = 100),
    )
  }
}
