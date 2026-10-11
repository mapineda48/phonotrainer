package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifact
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [FakeComputerClient]'s artifact transfer (architecture 7.4: Range, If-Range, a complete part, 416, the `truncate`
 * and `corrupt` knobs) and its job channel (server-contract 7), in memory.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class FakeComputerClientTransferAndChannelTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val computer = FakeComputer(clock = FakeComputerClock.frozen())
  private val phone = computer.pairDevice()
  private val client = FakeComputerClient(computer, phone)

  @After
  fun tearDown() = computer.close()

  // --- downloads ---------------------------------------------------------------------------------------------------

  @Test
  fun anArtifactDownloadsWholeWithItsETag() = runTest {
    val artifact = artifactOf(computer.addFinishedClip(), "analysis")
    val target = part("analysis")
    val progress = mutableListOf<Long>()

    val outcome = client.transfer.download(request(artifact, target)) { progress += it }

    assertThat(outcome).isEqualTo(DownloadOutcome.Complete("\"${artifact.sha256}\"", artifact.bytes!!))
    assertThat(PhoneCrypto.sha256Hex(target.readBytes())).isEqualTo(artifact.sha256)
    assertThat(progress.last()).isEqualTo(artifact.bytes)
    assertThat(computer.requests.last().signedBy).isEqualTo(phone.deviceId)
  }

  @Test
  fun aCutDownloadResumesWithRangeAndIfRange() = runTest {
    val artifact = artifactOf(computer.addFinishedClip(), "audio_mix")
    val target = part("audio_mix")
    computer.truncate("audio_mix", atBytes = 1_000)

    val cut = client.transfer.download(request(artifact, target)) {}
    val resumed = client.transfer.download(request(artifact, target, resumeFrom = 1_000, ifRange = etag(artifact))) {}

    assertThat(cut).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset)))
    assertThat(resumed).isEqualTo(DownloadOutcome.Complete(etag(artifact), artifact.bytes!!))
    assertThat(PhoneCrypto.sha256Hex(target.readBytes())).isEqualTo(artifact.sha256)
    val headers = computer.requests.last().headers
    assertThat(headers["range"]).isEqualTo("bytes=1000-")
    assertThat(headers["if-range"]).isEqualTo(etag(artifact))
    assertThat(computer.requests.last().status).isEqualTo(206)
  }

  @Test
  fun aStaleIfRangeRestartsFromZero() = runTest {
    val artifact = artifactOf(computer.addFinishedClip(), "audio_mix")
    val target = part("audio_mix").apply {
      parentFile.mkdirs()
      writeBytes(ByteArray(1_000))
    }

    val staleTag = "\"${"0".repeat(64)}\""
    val stale = client.transfer.download(request(artifact, target, resumeFrom = 1_000, ifRange = staleTag)) {}
    val again = client.transfer.download(request(artifact, target)) {}

    assertThat(stale).isEqualTo(DownloadOutcome.RestartedFromZero)
    assertThat(again).isEqualTo(DownloadOutcome.Complete(etag(artifact), artifact.bytes!!))
    assertThat(PhoneCrypto.sha256Hex(target.readBytes())).isEqualTo(artifact.sha256)
  }

  @Test
  fun aCompletePartIsNeverRequested() = runTest {
    val artifact = artifactOf(computer.addFinishedClip(), "analysis")
    val sent = computer.requests.size

    val outcome = client.transfer.download(
      request(artifact, part("analysis"), resumeFrom = artifact.bytes!!, ifRange = etag(artifact)),
    ) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.Complete(etag(artifact), artifact.bytes!!))
    assertThat(computer.requests).hasSize(sent)
  }

  @Test
  fun aRangePastTheEndIsNotSatisfiable() = runTest {
    val artifact = artifactOf(computer.addFinishedClip(), "analysis")

    val outcome = client.transfer.download(
      DownloadRequest(artifact.url!!, part("analysis"), artifact.bytes!!, etag(artifact), expectedBytes = 0),
    ) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.RangeNotSatisfiable)
  }

  @Test
  fun aCorruptDownloadHasTheRightSizeAndTheWrongBytes() = runTest {
    val artifact = artifactOf(computer.addFinishedClip(), "analysis")
    val target = part("analysis")
    computer.corrupt("analysis")

    val outcome = client.transfer.download(request(artifact, target)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.Complete(etag(artifact), artifact.bytes!!))
    assertThat(PhoneCrypto.sha256Hex(target.readBytes())).isNotEqualTo(artifact.sha256)
  }

  @Test
  fun refusedDownloadsNameTheirError() = runTest {
    val queued = computer.addJob(JobSeed(status = JobStatus.QUEUED))
    val url = "/api/phone/jobs/${queued.value}/artifacts/analysis"

    val notReady = client.transfer.download(DownloadRequest(url, part("x"), 0, null, 0)) {}
    computer.revokeDevice()
    val rejected = client.transfer.download(DownloadRequest(url, part("y"), 0, null, 0)) {}
    computer.reachability = Reachability.Unreachable
    val silent = client.transfer.download(DownloadRequest(url, part("z"), 0, null, 0)) {}

    assertThat(notReady).isEqualTo(
      DownloadOutcome.Failed(AppError.Computer.Refused("artifact_not_ready", "This file is not ready yet.")),
    )
    assertThat(rejected).isEqualTo(DownloadOutcome.Failed(AppError.Computer.CredentialRejected))
    assertThat(silent).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(30))))
  }

  @Test
  fun anAbsoluteArtifactUrlReachesItsComputer() = runTest {
    val artifact = artifactOf(computer.addFinishedClip(), "analysis")
    val absolute = "http://${computer.address.host}:${computer.address.port}${artifact.url}"

    val outcome = client.transfer.download(DownloadRequest(absolute, part("analysis"), 0, null, artifact.bytes!!)) {}

    assertThat(outcome).isInstanceOf(DownloadOutcome.Complete::class.java)
  }

  // --- the job channel ---------------------------------------------------------------------------------------------

  @Test
  fun theChannelSaysHelloThenALeanSnapshotThenFollowsTheComputer() = runTest {
    val done = computer.addFinishedClip()
    val events = collectChannel()

    val created = client.createLink(LinkCreate(RequestId(newRequestKey()), "https://youtu.be/abc123def45", false, null))
    val own = created.ok().value.id
    computer.progress(own, JobStage.DOWNLOAD, 10)
    computer.deleteJob(done)
    runCurrent()

    assertThat(events[0]).isEqualTo(ChannelEvent.Hello(computer.serverId, computer.libraryId, 6))
    val snapshot = events[1] as ChannelEvent.Snapshot
    assertThat(snapshot.jobs.map { it.id }).containsExactly(done)
    assertThat(snapshot.jobs.single().artifacts).isNull()
    val jobs = events.filterIsInstance<ChannelEvent.Job>().map { it.job }
    assertThat(jobs.map { it.status }).containsExactly(RemoteStatus.QUEUED, RemoteStatus.RUNNING).inOrder()
    assertThat(jobs.all { it.own && it.artifacts == null }).isTrue()
    assertThat(events.last()).isEqualTo(ChannelEvent.Deleted(done))
    assertThat(computer.openChannels).isEqualTo(1)
  }

  @Test
  fun stoppingTheCollectionClosesTheChannelOnTheComputer() = runTest {
    val collecting = launch { client.channel.open().collect {} }
    runCurrent()
    val open = computer.openChannels

    collecting.cancel()
    runCurrent()

    assertThat(open).isEqualTo(1)
    assertThat(computer.openChannels).isEqualTo(0)
  }

  @Test
  fun aDroppedChannelFails() = runTest {
    var failure: Throwable? = null
    launch { runCatching { client.channel.open().toList() }.onFailure { failure = it } }
    runCurrent()

    computer.dropChannel()
    runCurrent()

    assertThat(failure).isInstanceOf(IOException::class.java)
    assertThat(computer.openChannels).isEqualTo(0)
  }

  @Test
  fun anUnreachableComputerEndsTheChannel() = runTest {
    var failure: Throwable? = null
    launch { runCatching { client.channel.open().toList() }.onFailure { failure = it } }
    runCurrent()

    computer.reachability = Reachability.Unreachable
    runCurrent()

    assertThat(failure).isInstanceOf(IOException::class.java)
  }

  @Test
  fun aRefusedUpgradeFails() = runTest {
    computer.revokeDevice()

    val failure = runCatching { client.channel.open().first() }.exceptionOrNull()

    assertThat(failure).isInstanceOf(IOException::class.java)
    assertThat(failure).hasMessageThat().contains("403")
    assertThat(computer.openChannels).isEqualTo(0)
  }

  private fun TestScope.collectChannel(): List<ChannelEvent> {
    val events = mutableListOf<ChannelEvent>()
    backgroundScope.launch { client.channel.open().collect { events += it } }
    runCurrent()
    return events
  }

  private suspend fun artifactOf(id: JobId, name: String): RemoteArtifact =
    client.job(id).ok().artifacts!!.single { it.name == name }

  private fun part(name: String): File = File(folder.root, "clips/.$name.part")

  private fun etag(artifact: RemoteArtifact) = "\"${artifact.sha256}\""

  private fun request(artifact: RemoteArtifact, target: File, resumeFrom: Long = 0, ifRange: String? = null) =
    DownloadRequest(artifact.url!!, target, resumeFrom, ifRange, artifact.bytes!!)
}
