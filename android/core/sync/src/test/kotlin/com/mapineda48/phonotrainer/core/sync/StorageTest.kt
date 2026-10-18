package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.sync.engine.SyncStateSource
import com.mapineda48.phonotrainer.core.sync.storage.StoragePolicy
import com.mapineda48.phonotrainer.core.sync.transfer.RunOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The storage budget, the free-space floor and eviction (architecture 7.5, spec 7.10; F-21-AC4). */
@RunWith(RobolectricTestRunner::class)
class StorageTest {
  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  private suspend fun SyncHarness.video(id: JobId): VideoAvailability =
    clipRepository.clips(ClipQuery()).first().single { it.id == id }.video

  private suspend fun SyncHarness.availability(id: JobId): ClipAvailability =
    clipRepository.clips(ClipQuery()).first().single { it.id == id }.availability

  @Test
  fun overTheBudgetTheVideoOfTheLeastRecentlyWatchedUnprotectedClipGoesAndItStaysOnThePhone() = sync { h ->
    // F-21-AC4.
    h.settings.state.value = h.settings.state.value.copy(downloadVideos = false)
    val longAgo = h.computer.addFinishedClip("Watched long ago")
    val kept = h.computer.addFinishedClip("Kept")
    val today = h.computer.addFinishedClip("In today's set")
    val recent = h.computer.addFinishedClip("Watched two days ago")
    val incoming = h.computer.addFinishedClip("Watched yesterday, audio only so far")
    h.syncAndDownload()
    listOf(longAgo, kept, today, recent).forEach { h.clipRepository.downloadVideo(it) }
    h.syncAndDownload()
    h.clipRepository.setKept(kept, kept = true)
    val now = h.clock.now()
    h.protection.protected = setOf(today)
    h.protection.watched = mapOf(
      longAgo to now.minusSeconds(10 * DAY), kept to now.minusSeconds(30 * DAY), today to now.minusSeconds(20 * DAY),
      recent to now.minusSeconds(2 * DAY), incoming to now.minusSeconds(DAY),
    )
    val full = h.storage.usage.first()
    h.storage.setBudget(StorageBudget.Limit(full.clipsBytes + full.videosBytes))

    h.clipRepository.downloadVideo(incoming)
    h.syncAndDownload()

    assertThat(h.video(incoming)).isEqualTo(VideoAvailability.OnThisPhone)
    assertThat(h.video(longAgo)).isEqualTo(VideoAvailability.NotOnThisPhone)
    assertThat(h.availability(longAgo)).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.video(recent)).isEqualTo(VideoAvailability.OnThisPhone)
    assertThat(h.video(kept)).isEqualTo(VideoAvailability.OnThisPhone)
    assertThat(h.video(today)).isEqualTo(VideoAvailability.OnThisPhone)
    val after = h.storage.usage.first()
    assertThat(after.clipsBytes + after.videosBytes).isAtMost(full.clipsBytes + full.videosBytes)
  }

  @Test
  fun aVideoNeverTakesThePlaceOfAVideoThatRanksAboveIt() = sync { h ->
    h.settings.state.value = h.settings.state.value.copy(downloadVideos = false)
    val watched = h.computer.addFinishedClip("Watched yesterday")
    val never = h.computer.addFinishedClip("Never watched")
    h.syncAndDownload()
    h.clipRepository.downloadVideo(watched)
    h.syncAndDownload()
    h.protection.watched = mapOf(watched to h.clock.now().minusSeconds(DAY))
    val full = h.storage.usage.first()
    h.storage.setBudget(StorageBudget.Limit(full.clipsBytes + full.videosBytes))

    h.clipRepository.downloadVideo(never)
    h.syncAndDownload()

    assertThat(h.video(watched)).isEqualTo(VideoAvailability.OnThisPhone)
    assertThat(h.video(never)).isEqualTo(VideoAvailability.NotOnThisPhone)
    val original = h.clipRepository.clip(never).first()!!.artifacts.single { it.role.name == "VIDEO" }
    assertThat(original.pauseReason).isEqualTo(PauseReason.BUDGET_REACHED)
  }

  @Test
  fun belowTheFreeSpaceFloorDownloadsPauseAndResumeWhenSpaceComesBack() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.space.free = 500_000_000
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.downloader.run {}).isEqualTo(RunOutcome.PAUSED)

    assertThat(h.availability(id)).isEqualTo(ClipAvailability.DownloadPaused(PauseReason.FREE_SPACE_FLOOR))
    assertThat(h.storage.usage.first().belowFloor).isTrue()
    assertThat(h.storage.usage.first().floorBytes).isEqualTo(StoragePolicy.FLOOR_CAP_BYTES)
    val rows = h.artifacts.forJob(id.value)
    assertThat(SyncStateSource.stateOf(rows, connected = true, downloading = false, live = null, passing = false, waitingRequests = 0))
      .isEqualTo(SyncState.Paused(PauseReason.FREE_SPACE_FLOOR))
    assertThat(h.computer.requests.filter { "/artifacts/" in it.target }).isEmpty()

    h.space.free = 40L * SyncHarness.GB
    h.downloader.run {}

    assertThat(h.availability(id)).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun theFloorIsTheSmallerOfOneGigabyteAndTenPercentOfTheStorage() {
    assertThat(StoragePolicy.floorBytes(64L * SyncHarness.GB)).isEqualTo(SyncHarness.GB)
    assertThat(StoragePolicy.floorBytes(8L * SyncHarness.GB)).isEqualTo(800_000_000L)
  }

  @Test
  fun aStudyPackThatDoesNotFitTheBudgetWaitsUntilTheBudgetIsRaised() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.settings.state.value = h.settings.state.value.copy(storageBudget = StorageBudget.Limit(1))

    h.syncAndDownload()

    assertThat(h.availability(id)).isEqualTo(ClipAvailability.DownloadPaused(PauseReason.BUDGET_REACHED))
    assertThat(h.storage.usage.first().budgetReached).isTrue()

    h.storage.setBudget(StorageBudget.FreeSpaceOnly)
    assertThat(h.trigger.reasons).contains(SyncReason.SYNC_NOW)
    h.syncAndDownload()

    assertThat(h.availability(id)).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.storage.usage.first().budgetReached).isFalse()
  }

  @Test
  fun studyPacksAreNeverRemovedAutomaticallyAndWhenTheyAloneExceedTheBudgetTheLearnerIsTold() = sync { h ->
    val a = h.computer.addFinishedClip("A")
    val b = h.computer.addFinishedClip("B")
    h.syncAndDownload()
    val usage = h.storage.usage.first()

    h.storage.setBudget(StorageBudget.Limit(usage.clipsBytes / 2))

    val after = h.storage.usage.first()
    assertThat(after.videosBytes).isEqualTo(0)
    assertThat(after.clipsBytes).isEqualTo(usage.clipsBytes)
    assertThat(after.budgetReached).isTrue()
    assertThat(h.availability(a)).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.availability(b)).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun theUsageSumsVerifiedFilesPerClipBiggestFirstWithTheQueueCopiesAndTheCache() = sync { h ->
    val small = h.computer.addFinishedClip("Audio only", video = false)
    val big = h.computer.addFinishedClip("With video")
    h.space.waitingCopies = 300
    h.space.cache = 20
    h.syncAndDownload()

    val usage = h.storage.usage.first()
    val rows = h.artifacts.all()
    assertThat(usage.clipsBytes).isEqualTo(rows.filter { it.role != "VIDEO" }.sumOf { it.localBytes ?: 0 })
    assertThat(usage.videosBytes).isEqualTo(rows.filter { it.role == "VIDEO" }.sumOf { it.localBytes ?: 0 })
    assertThat(usage.perClip.map { it.id }).containsExactly(big, small).inOrder()
    assertThat(usage.waitingCopiesBytes).isEqualTo(300)
    assertThat(usage.cacheBytes).isEqualTo(20)
    assertThat(usage.budget).isEqualTo(StorageBudget.Limit(2_000_000_000))
    assertThat(usage.freeBytes).isEqualTo(h.space.free)
  }

  @Test
  fun removeAllVideosKeepsTheClipsAndNoVideoComesBackByItself() = sync { h ->
    val a = h.computer.addFinishedClip("A")
    val b = h.computer.addFinishedClip("B")
    h.syncAndDownload()

    h.storage.removeAllVideos()
    h.syncAndDownload()

    assertThat(h.video(a)).isEqualTo(VideoAvailability.NotOnThisPhone)
    assertThat(h.video(b)).isEqualTo(VideoAvailability.NotOnThisPhone)
    assertThat(h.availability(a)).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.storage.usage.first().videosBytes).isEqualTo(0)
    assertThat(h.files.clipDir(a.value)!!.listFiles()!!.none { it.name.startsWith("original") }).isTrue()

    h.clipRepository.downloadVideo(a)
    h.syncAndDownload()
    assertThat(h.video(a)).isEqualTo(VideoAvailability.OnThisPhone)
    assertThat(h.video(b)).isEqualTo(VideoAvailability.NotOnThisPhone)
  }

  private companion object {
    const val DAY = 86_400L
  }
}
