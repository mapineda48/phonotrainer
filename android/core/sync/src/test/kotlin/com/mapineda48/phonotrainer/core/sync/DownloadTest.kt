package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.sync.transfer.RunOutcome
import com.mapineda48.phonotrainer.core.testing.computer.FakeMedia
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import com.mapineda48.phonotrainer.core.testing.computer.Reachability
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The downloads of architecture 7.4 (QT-9, SC-11; F-21-AC2, AC3, AC6, AC7; F-04-AC2, F-05-AC4). */
@RunWith(RobolectricTestRunner::class)
class DownloadTest {
  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  private suspend fun SyncHarness.summary(id: JobId): ClipSummary =
    clipRepository.clips(ClipQuery()).first().single { it.id == id }

  private fun SyncHarness.artifactGets(name: String) =
    computer.requests.filter { it.method == "GET" && it.target.endsWith("/artifacts/$name") }

  @Test
  fun aStudyPackInterruptedAtSeventyPercentResumesFromThereAndIsOnThePhoneOnlyAfterVerification() = sync { h ->
    // QT-9, F-21-AC3.
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val size = h.artifact(id, Names.MIX).remoteBytes!!
    val cut = size * 7 / 10
    h.computer.truncate(Names.MIX, atBytes = cut)

    assertThat(h.downloader.run {}).isEqualTo(RunOutcome.NOT_CONNECTED)

    val stopped = h.artifact(id, Names.MIX)
    assertThat(stopped.localState).isEqualTo("QUEUED")
    assertThat(stopped.partBytes).isAtLeast(cut)
    assertThat(h.summary(id).availability).isNotEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.reconciler.onPhone).isEmpty()

    assertThat(h.downloader.run {}).isEqualTo(RunOutcome.DONE)

    val resumed = h.artifactGets(Names.MIX).last()
    val from = resumed.headers.header("Range")!!.removePrefix("bytes=").removeSuffix("-").toLong()
    assertThat(from).isAtLeast(cut)
    assertThat(h.artifact(id, Names.MIX).localState).isEqualTo("READY")
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.reconciler.onPhone).containsExactly(id)
  }

  @Test
  fun aPartAlreadyCompleteIsVerifiedWithoutARequest() = sync { h ->
    // The process died between the last write and the rename (Android review M5).
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val row = h.artifact(id, Names.MIX)
    val revision = row.remoteSha256!!.take(16)
    val part = h.files.part(id.value, Names.MIX, revision)!!
    h.fakeClient.transfer.download(DownloadRequest(row.remoteUrl!!, part, 0, null, row.remoteBytes!!)) {}
    h.artifacts.update(row.copy(partBytes = row.remoteBytes, partRevision = revision))
    val before = h.artifactGets(Names.MIX).size

    h.downloader.run {}

    assertThat(h.artifactGets(Names.MIX)).hasSize(before)
    assertThat(h.artifact(id, Names.MIX).localState).isEqualTo("READY")
    assertThat(part.exists()).isFalse()
  }

  @Test
  fun aRangeNotSatisfiableAnswerIsMetByVerifyingThePartAndRestartsOnlyWhenItDoesNotMatch() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val row = h.artifact(id, Names.MIX)
    val part = h.files.part(id.value, Names.MIX, row.remoteSha256!!.take(16))!!
    h.fakeClient.transfer.download(DownloadRequest(row.remoteUrl!!, part, 0, null, row.remoteBytes!!)) {}
    val real = h.transfer
    var answered416 = 0
    h.transfer = object : ArtifactTransfer {
      override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome =
        if (request.target == part && answered416 == 0) {
          answered416++
          DownloadOutcome.RangeNotSatisfiable
        } else {
          real.download(request, onProgress)
        }
    }

    h.downloader.run {}

    assertThat(answered416).isEqualTo(1)
    assertThat(h.transfers.count { it.target == part }).isEqualTo(1)
    assertThat(h.artifact(id, Names.MIX).localState).isEqualTo("READY")

    // A part that does not match is deleted and downloaded again from zero.
    val other = h.computer.addFinishedClip("At the station", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val otherRow = h.artifact(other, Names.MIX)
    val otherPart = h.files.part(other.value, Names.MIX, otherRow.remoteSha256!!.take(16))!!
    otherPart.parentFile!!.mkdirs()
    otherPart.writeBytes(ByteArray(otherRow.remoteBytes!!.toInt()) { 7 })
    answered416 = 0
    h.transfer = object : ArtifactTransfer {
      override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome =
        if (request.target == otherPart && answered416 == 0) {
          answered416++
          DownloadOutcome.RangeNotSatisfiable
        } else {
          real.download(request, onProgress)
        }
    }

    h.downloader.run {}

    val again = h.transfers.filter { it.target == otherPart }
    assertThat(again.last().resumeFrom).isEqualTo(0L)
    assertThat(h.artifact(other, Names.MIX).localState).isEqualTo("READY")
  }

  @Test
  fun aFileDamagedOnTheWayTwiceFailsAndThePreviousGoodFileStaysInUse() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.syncAndDownload()
    val good = h.artifact(id, Names.ANALYSIS)
    h.computer.finishJob(id, content = FixtureClips.content(AnalysisFixtures.mkAnalysis(source = "Ordering coffee, again"), false))
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.computer.corrupt(Names.ANALYSIS, times = 2)

    assertThat(h.downloader.run {}).isEqualTo(RunOutcome.DONE)

    val failed = h.artifact(id, Names.ANALYSIS)
    assertThat(failed.verifyFailures).isEqualTo(2)
    assertThat(failed.lastError).isEqualTo("damaged")
    assertThat(failed.localState).isEqualTo("READY")
    assertThat(failed.localSha256).isEqualTo(good.localSha256)
    assertThat(h.files.resolve(failed.localFile)!!.readBytes().size.toLong()).isEqualTo(good.localBytes)
    assertThat(h.artifactGets(Names.ANALYSIS)).hasSize(3)
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)

    // A third run does not try again by itself.
    h.downloader.run {}
    assertThat(h.artifactGets(Names.ANALYSIS)).hasSize(3)
  }

  @Test
  fun aNewClipDamagedTwiceNeedsDownloadingAgainUntilTryAgain() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.computer.corrupt(Names.ANALYSIS, times = 2)

    h.downloader.run {}

    assertThat(h.artifact(id, Names.ANALYSIS).localState).isEqualTo("FAILED")
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.NeedsDownloadingAgain)
    val detail = h.clipRepository.clip(id).first()!!
    assertThat(detail.artifacts.single { it.role == ArtifactRole.CLIP_DATA }.verifyFailures).isEqualTo(2)
    assertThat(detail.artifacts.single { it.role == ArtifactRole.CLIP_DATA }.state).isEqualTo(ArtifactLocalState.FAILED)

    h.clipRepository.downloadToPhone(id)
    h.syncAndDownload()

    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun theClipDataIsIndexedOnceVerifiedAndTheQueueHearsOfTheClipOnceItsStudyPackIsHere() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)

    h.syncAndDownload()
    h.syncAndDownload()

    assertThat(h.events.indexOf("index:${id.value}")).isLessThan(h.events.indexOf("onPhone:${id.value}"))
    assertThat(h.reconciler.onPhone).containsExactly(id)
    assertThat(h.clip(id).onPhoneAt).isEqualTo(h.clock.now().toEpochMilli())
  }

  @Test
  fun studyPacksComeFirstInTheirOrderThenSecondTracksThenVideosInTheirOrder() = sync { h ->
    // Created in this order, ten seconds apart on the computer's clock.
    val oldest = h.computer.addFinishedClip("Oldest")
    h.computer.clock.advanceSeconds(10)
    val newest = h.computer.addFinishedClip("Newer")
    h.computer.clock.advanceSeconds(10)
    val mine = h.computer.addJob(JobSeed(title = "Mine", deviceId = h.phone.deviceId))
    h.computer.clock.advanceSeconds(10)
    val wanted = h.computer.addFinishedClip("Wanted")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.clipRepository.downloadToPhone(wanted)
    h.clipRepository.setKept(oldest, kept = true)
    h.protection.watched = mapOf(newest to Instant.parse("2026-10-09T10:00:00Z"))

    h.downloader.run {}

    val order = h.computer.requests.filter { it.method == "GET" && "/artifacts/" in it.target }
      .map { call -> call.target.substringAfter("/jobs/").let { it.substringBefore("/") to it.substringAfterLast("/") } }
    val packs = order.filter { it.second == Names.ANALYSIS || it.second == Names.MIX }.map { it.first }.distinct()
    assertThat(packs).containsExactly(mine.value, wanted.value, newest.value, oldest.value).inOrder()
    val firstSecondTrack = order.indexOfFirst { it.second == Names.DIALOGUE }
    val lastPack = order.indexOfLast { it.second == Names.ANALYSIS || it.second == Names.MIX }
    assertThat(firstSecondTrack).isGreaterThan(lastPack)
    val videos = order.filter { it.second == Names.ORIGINAL }.map { it.first }
    assertThat(videos).containsExactly(oldest.value, newest.value, wanted.value, mine.value).inOrder()
    assertThat(order.indexOfFirst { it.second == Names.ORIGINAL }).isGreaterThan(order.indexOfLast { it.second == Names.DIALOGUE })
  }

  @Test
  fun theChosenTrackMakesTheStudyPackAndTheOtherOneFollows() = sync { h ->
    h.settings.state.value = h.settings.state.value.copy(track = Track.DIALOGUE, downloadVideos = false)
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.computer.truncate(Names.MIX, atBytes = 10)

    h.downloader.run {}

    assertThat(h.artifact(id, Names.DIALOGUE).localState).isEqualTo("READY")
    assertThat(h.artifact(id, Names.MIX).localState).isEqualTo("QUEUED")
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun theOriginalDownloadsOnlyWhenThisPhoneCanDecodeItsCodecAtItsSize() = sync { h ->
    h.decoders.playable = { codec, width, height -> codec == "h264" && minOf(width, height) <= 1080 }
    val playable = h.computer.addFinishedClip("Plays here")
    val fourK = h.computer.addJob(JobSeed(title = "4K AV1", media = FakeMedia(codec = "av1", width = 3840, height = 2160)))

    h.syncAndDownload()

    assertThat(h.artifact(playable, Names.ORIGINAL).localState).isEqualTo("READY")
    assertThat(h.summary(playable).video).isEqualTo(VideoAvailability.OnThisPhone)
    assertThat(h.artifact(fourK, Names.ORIGINAL).localState).isEqualTo("ABSENT")
    assertThat(h.summary(fourK).video).isEqualTo(VideoAvailability.CannotPlayOnThisPhone)
    // F-21-AC6 (v1-core part): the study pack never waits for the video.
    assertThat(h.summary(fourK).availability).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.artifactGets(Names.ORIGINAL)).hasSize(1)
  }

  @Test
  fun withAutomaticVideoDownloadOffNoVideoComesAndTheClipSaysSo() = sync { h ->
    // F-21-AC2.
    h.settings.state.value = h.settings.state.value.copy(downloadVideos = false)
    val id = h.computer.addFinishedClip("Ordering coffee")

    h.syncAndDownload()

    assertThat(h.artifactGets(Names.ORIGINAL)).isEmpty()
    assertThat(h.summary(id).video).isEqualTo(VideoAvailability.NotOnThisPhone)
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)
    val video = h.clipRepository.clip(id).first()!!.artifacts.single { it.role == ArtifactRole.VIDEO }
    assertThat(video.pauseReason).isEqualTo(PauseReason.NOT_WANTED)

    h.clipRepository.downloadVideo(id)
    h.syncAndDownload()
    assertThat(h.summary(id).video).isEqualTo(VideoAvailability.OnThisPhone)
  }

  @Test
  fun aMissingOriginalLeavesAnAudioClipThatStudiesNormally() = sync { h ->
    // F-21-AC7.
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.computer.removeSource(id)

    h.syncAndDownload()

    assertThat(h.summary(id).video).isEqualTo(VideoAvailability.NotAvailableFromComputer)
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun aClipRemovedFromThePhoneIsNeverDownloadedAgainUntilTheLearnerAsks() = sync { h ->
    // F-04-AC2, F-05-AC4.
    h.settings.state.value = h.settings.state.value.copy(downloadVideos = false)
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.syncAndDownload()

    h.clipRepository.removeFromPhone(id)
    repeat(2) { h.syncAndDownload() }

    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.NotOnThisPhone)
    assertThat(h.files.clipDir(id.value)!!.listFiles().orEmpty().filter { it.isFile }).isEmpty()
    assertThat(h.indexer.dropped).contains(setOf(id))
    assertThat(h.hooks.removed).isEmpty()

    // "Download when connected" while the computer is away: wanted, then brought at the next sync, video included.
    h.status.statusState.value = ComputerStatus.NotReachable("Desk", null, false, ProbeOutcome.NoAnswer(3))
    h.clipRepository.downloadToPhone(id)
    assertThat(h.clip(id).wanted).isTrue()
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.DownloadPaused(PauseReason.NOT_CONNECTED))

    h.status.statusState.value = SyncHarness.CONNECTED
    h.syncAndDownload()

    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.summary(id).video).isEqualTo(VideoAvailability.OnThisPhone)
  }

  @Test
  fun removingTheVideoKeepsTheClipStudiableAndItIsNotDownloadedAgainByItself() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.syncAndDownload()

    h.clipRepository.removeVideo(id)
    h.syncAndDownload()

    assertThat(h.summary(id).video).isEqualTo(VideoAvailability.NotOnThisPhone)
    assertThat(h.summary(id).availability).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.artifactGets(Names.ORIGINAL)).hasSize(1)
  }

  @Test
  fun aRunStoppedInTheMiddleOfAFileKeepsWhatArrivedAndTheNextRunResumesFromThere() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val row = h.artifact(id, Names.MIX)
    val half = row.remoteBytes!! / 2
    val real = h.transfer
    val whole = java.io.File(h.cacheDir, "whole.part")
    h.fakeClient.transfer.download(DownloadRequest(row.remoteUrl!!, whole, 0, null, row.remoteBytes!!)) {}
    val firstHalf = whole.readBytes().copyOf(half.toInt())
    val arrived = CompletableDeferred<Unit>()
    h.transfer = object : ArtifactTransfer {
      override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome {
        if (!request.target.name.startsWith(".audio_mix")) return real.download(request, onProgress)
        request.target.parentFile!!.mkdirs()
        request.target.writeBytes(firstHalf)
        onProgress(half)
        arrived.complete(Unit)
        awaitCancellation()
      }
    }
    val run = launch(Dispatchers.Default) { h.downloader.run {} }
    arrived.await()

    run.cancelAndJoin()

    val stopped = h.artifact(id, Names.MIX)
    assertThat(stopped.localState).isEqualTo("QUEUED")
    assertThat(stopped.partBytes).isEqualTo(half)
    h.transfer = real
    h.downloader.run {}
    assertThat(h.transfers.last { it.target.name.startsWith(".audio_mix") }.resumeFrom).isEqualTo(half)
    assertThat(h.artifact(id, Names.MIX).localState).isEqualTo("READY")
    assertThat(h.artifact(id, Names.MIX).verifyFailures).isEqualTo(0)
  }

  @Test
  fun downloadsStopWhenTheComputerStopsAnsweringAndNothingIsMarkedFailed() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.computer.reachability = Reachability.Unreachable

    assertThat(h.downloader.run {}).isEqualTo(RunOutcome.NOT_CONNECTED)

    assertThat(h.artifacts.forJob(id.value).map { it.localState }.toSet()).doesNotContain("FAILED")
    assertThat(h.status.checks).isAtLeast(2)
  }

  @Test
  fun aRevokedPhoneStopsTheDownloadsWithoutFailingAnyFile() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.computer.revokeDevice()

    assertThat(h.downloader.run {}).isEqualTo(RunOutcome.BLOCKED)
    assertThat(h.artifacts.forJob(id.value).map { it.localState }.toSet()).doesNotContain("FAILED")
  }
}
