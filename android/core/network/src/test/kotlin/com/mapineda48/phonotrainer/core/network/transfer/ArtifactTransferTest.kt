package com.mapineda48.phonotrainer.core.network.transfer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifact
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.ArtifactStatus
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Resumable artifact downloads (architecture 7.4) against the fake computer over HTTP: whole files, `Range` resume
 * with `If-Range`, a changed file restarting from zero, a complete part verified without a request, `416`, a
 * connection lost mid-file keeping what arrived, and refusals in the error model.
 */
class ArtifactTransferTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val computer = FakeComputer().start()
  private val stack = TestStack(computer)

  @After
  fun close() {
    stack.close()
    computer.close()
  }

  /** The `original` video of a finished clip (the largest artifact), as the listing reports it. */
  private suspend fun original(): RemoteArtifact {
    computer.addFinishedClip("Ordering coffee", video = true)
    stack.pair()
    val library = stack.client.library() as ApiResult.Ok
    return library.value.jobs.single().artifacts!!.single { it.name == "original" }
  }

  private fun bytesOf(artifact: RemoteArtifact): ByteArray =
    computer.jobs.single().sourceBytes!!.also { check(it.size.toLong() == artifact.bytes) }

  @Test
  fun aWholeFileArrivesAndMatchesItsChecksum() = runBlocking<Unit> {
    val artifact = original()
    val part = File(folder.root, "clip/.original.part")
    val progress = CopyOnWriteArrayList<Long>()

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, 0, null, artifact.bytes!!)) { progress += it }

    assertThat(outcome).isEqualTo(DownloadOutcome.Complete("\"${artifact.sha256}\"", artifact.bytes!!))
    assertThat(Sha256.of(part).sha256).isEqualTo(artifact.sha256)
    assertThat(progress.last()).isEqualTo(artifact.bytes)
    assertThat(progress).isInOrder()
    val sent = computer.requests.last()
    assertThat(sent.headers.keys.map { it.lowercase() }).doesNotContain("range")
    assertThat(sent.signedBy).isNotNull()
  }

  @Test
  fun aPartResumesFromItsLengthWithRangeAndIfRange() = runBlocking<Unit> {
    val artifact = original()
    val whole = bytesOf(artifact)
    val half = whole.size / 2
    val part = folder.newFile().apply { writeBytes(whole.copyOf(half)) }
    val etag = "\"${artifact.sha256}\""

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, half.toLong(), etag, artifact.bytes!!)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.Complete(etag, artifact.bytes!!))
    assertThat(part.readBytes()).isEqualTo(whole)
    val headers = computer.requests.last().headers.mapKeys { it.key.lowercase() }
    assertThat(headers["range"]).isEqualTo("bytes=$half-")
    assertThat(headers["if-range"]).isEqualTo(etag)
  }

  @Test
  fun aFileThatChangedRestartsFromZero() = runBlocking<Unit> {
    val artifact = original()
    val part = folder.newFile().apply { writeBytes(ByteArray(100) { 7 }) }

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, 100, "\"stale\"", artifact.bytes!!)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.RestartedFromZero)
    assertThat(part.length()).isEqualTo(0)
  }

  @Test
  fun aCompletePartIsLeftToVerifyWithoutARequest() = runBlocking<Unit> {
    val artifact = original()
    val part = folder.newFile().apply { writeBytes(bytesOf(artifact)) }
    val before = computer.requests.size

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, artifact.bytes!!, "\"e\"", artifact.bytes!!)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.Complete("\"e\"", artifact.bytes!!))
    assertThat(computer.requests.size).isEqualTo(before)
  }

  @Test
  fun aRangePastTheEndIsNotSatisfiable() = runBlocking<Unit> {
    val artifact = original()
    val size = artifact.bytes!!
    val part = folder.newFile().apply { writeBytes(bytesOf(artifact)) }

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, size, null, size + 10)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.RangeNotSatisfiable)
    assertThat(part.length()).isEqualTo(size)
  }

  @Test
  fun aPartShorterThanItsRecordedLengthStartsAgain() = runBlocking<Unit> {
    val artifact = original()
    val part = folder.newFile().apply { writeBytes(ByteArray(10)) }
    val before = computer.requests.size

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, 500, null, artifact.bytes!!)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.RestartedFromZero)
    assertThat(computer.requests.size).isEqualTo(before)
  }

  @Test
  fun aConnectionLostMidFileKeepsWhatArrived() = runBlocking<Unit> {
    val artifact = original()
    computer.truncate("original", atBytes = 1_000)
    val part = folder.newFile()

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, 0, null, artifact.bytes!!)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset)))
    assertThat(part.length()).isEqualTo(1_000)
    assertThat(part.readBytes()).isEqualTo(bytesOf(artifact).copyOf(1_000))

    val resumed = stack.transfer.download(DownloadRequest(artifact.url!!, part, 1_000, null, artifact.bytes!!)) {}
    assertThat(resumed).isInstanceOf(DownloadOutcome.Complete::class.java)
    assertThat(Sha256.of(part).sha256).isEqualTo(artifact.sha256)
  }

  @Test
  fun moreBytesThanListedAreRefusedAsMalformed() = runBlocking<Unit> {
    val artifact = original()
    val part = folder.newFile()

    val outcome = stack.transfer.download(DownloadRequest(artifact.url!!, part, 0, null, artifact.bytes!! - 1)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Malformed))
    assertThat(part.length()).isAtMost(artifact.bytes!! - 1)
  }

  @Test
  fun refusalsComeBackInTheErrorModel() = runBlocking<Unit> {
    val artifact = original()
    val job = computer.jobs.single().id
    computer.setArtifactStatus(job, "original", ArtifactStatus.PREPARING)

    val notReady = stack.transfer.download(DownloadRequest(artifact.url!!, folder.newFile(), 0, null, artifact.bytes!!)) {}
    val unknown = stack.transfer.download(
      DownloadRequest("/api/phone/jobs/${job.value}/artifacts/poster", folder.newFile(), 0, null, 10),
    ) {}
    computer.revokeDevice()
    computer.setArtifactStatus(job, "original", ArtifactStatus.READY)
    val revoked = stack.transfer.download(DownloadRequest(artifact.url!!, folder.newFile(), 0, null, artifact.bytes!!)) {}

    assertThat(notReady).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Refused("artifact_not_ready", detailOf(notReady))))
    assertThat((unknown as DownloadOutcome.Failed).error).isInstanceOf(AppError.Computer.Refused::class.java)
    assertThat((unknown.error as AppError.Computer.Refused).code).isAnyOf("unknown_artifact", "artifact_not_ready")
    assertThat(revoked).isEqualTo(DownloadOutcome.Failed(AppError.Computer.CredentialRejected))
  }

  private fun detailOf(outcome: DownloadOutcome): String =
    ((outcome as DownloadOutcome.Failed).error as AppError.Computer.Refused).detail
}
