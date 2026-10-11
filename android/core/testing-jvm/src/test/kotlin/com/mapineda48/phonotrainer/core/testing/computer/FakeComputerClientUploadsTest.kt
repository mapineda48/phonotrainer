package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.network.api.UploadChunk
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.network.api.UploadState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind as RemoteSourceKind

/**
 * [FakeComputerClient]'s uploads (server-contract 6.6 with the limits of 4.7): declarations, the single `PUT` with its
 * progress, the stall, "newest wins" and idle rules (QT-4) in memory, and the v1-complete routes (`GET`/`PATCH`
 * offsets, the phone video, record yourself).
 */
class FakeComputerClientUploadsTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val computer = FakeComputer(clock = FakeComputerClock.frozen())
  private val phone = computer.pairDevice()
  private val client = FakeComputerClient(computer, phone)
  private val bytes = FakeMediaBytes.video(size = 200_000, salt = 3)
  private val file: File by lazy { folder.newFile("VID_0001.mp4").apply { writeBytes(bytes) } }

  @After
  fun tearDown() = computer.close()

  @Test
  fun aDeclaredFileIsSentWithProgressAndBecomesAJobOfThisPhone() = runTest {
    val key = RequestId(newRequestKey())
    val progress = mutableListOf<Long>()

    val declared = client.openUpload(declaration(key))
    val sent = client.putUpload(key, file) { progress += it }

    val open = declared.ok() as UploadState.Open
    assertThat(declared.status()).isEqualTo(201)
    assertThat(open.offset).isEqualTo(0)
    assertThat(open.bytes).isEqualTo(bytes.size.toLong())
    assertThat(open.resumable).isFalse()
    assertThat(open.maxChunkBytes).isEqualTo(8L * FakeCapabilities.MIB)
    assertThat(sent.status()).isEqualTo(201)
    val job = sent.ok().value
    assertThat(sent.ok().created).isTrue()
    assertThat(job.source.kind).isEqualTo(RemoteSourceKind.UPLOAD)
    assertThat(job.source.fileName).isEqualTo("VID_0001.mp4")
    assertThat(job.clientRequestId).isEqualTo(key)
    assertThat(progress.size).isGreaterThan(1)
    assertThat(progress.last()).isEqualTo(bytes.size.toLong())
    assertThat(computer.job(job.id)!!.sourceBytes).isEqualTo(bytes)
  }

  @Test
  fun aRepeatedPutOrDeclarationFindsTheJobItsKeyCreated() = runTest {
    val key = RequestId(newRequestKey())
    client.openUpload(declaration(key))
    val first = client.putUpload(key, file) {}.ok().value

    val again = client.putUpload(key, file) {}
    val declaredAgain = client.openUpload(declaration(key))

    assertThat(again.status()).isEqualTo(200)
    assertThat(again.ok().created).isFalse()
    assertThat(again.ok().value.id).isEqualTo(first.id)
    assertThat((declaredAgain.ok() as UploadState.Done).job.id).isEqualTo(first.id)
    assertThat(computer.jobsWithKey(key)).hasSize(1)
  }

  @Test
  fun declarationsAreRefusedBeforeAnyByteIsSent() = runTest {
    val text = client.openUpload(declaration(RequestId(newRequestKey()), name = "notes.txt")).refused()
    computer.maxUploadBytes = 10
    val large = client.openUpload(declaration(RequestId(newRequestKey()))).refused()
    computer.maxUploadBytes = 2L * FakeCapabilities.GIB
    computer.lowOnSpace()
    val full = client.openUpload(declaration(RequestId(newRequestKey()))).refused()

    assertThat(text.status to text.code).isEqualTo(400 to "unsupported_type")
    assertThat(large.status to large.code).isEqualTo(413 to "too_large")
    assertThat(full.status to full.code).isEqualTo(507 to "no_space")
  }

  @Test
  fun aDamagedFileIsRefusedAndAnUndeclaredOneIsUnknown() = runTest {
    val key = RequestId(newRequestKey())
    client.openUpload(declaration(key, sha256 = "0".repeat(64)))

    val damaged = client.putUpload(key, file) {}.refused()
    val undeclared = client.putUpload(RequestId(newRequestKey()), file) {}.refused()

    assertThat(damaged.status to damaged.code).isEqualTo(422 to "checksum_mismatch")
    assertThat(undeclared.status to undeclared.code).isEqualTo(404 to "unknown_upload")
  }

  @Test
  fun aStalledBodyHoldsTheSlotUntilTheNewestWinsAfterTenSeconds() = runTest {
    val key = RequestId(newRequestKey())
    client.openUpload(declaration(key))
    computer.stallUpload(afterBytes = 50_000)

    val stalled = client.putUpload(key, file) {}
    val inFlight = computer.uploadsInFlight.single()
    val busy = client.putUpload(key, file) {}.refused()
    computer.clock.advanceSeconds(10)
    val newest = client.putUpload(key, file) {}

    assertThat(stalled.failure()).isEqualTo(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(60)))
    assertThat(inFlight.receivedBytes).isEqualTo(50_000)
    assertThat(busy.status to busy.code).isEqualTo(409 to "upload_busy")
    assertThat(newest.status()).isEqualTo(201)
    assertThat(computer.uploadsInFlight).isEmpty()
    assertThat(computer.jobsWithKey(key)).hasSize(1)
  }

  @Test
  fun aStalledBodyIsAbortedAfterThirtySecondsWithoutAByte() = runTest {
    val key = RequestId(newRequestKey())
    client.openUpload(declaration(key))
    computer.stallUpload(afterBytes = 1_000)
    client.putUpload(key, file) {}

    computer.clock.advanceSeconds(30)

    assertThat(computer.uploadsInFlight).isEmpty()
    assertThat(computer.requests.last { it.method == "PUT" }.status).isNull()
    assertThat(computer.uploadSessions.single().offset).isEqualTo(0)
    assertThat(client.putUpload(key, file) {}.status()).isEqualTo(201)
  }

  @Test
  fun removingAnUploadAbandonsItsSession() = runTest {
    val key = RequestId(newRequestKey())
    client.openUpload(declaration(key))

    val removed = client.deleteUpload(key)

    assertThat(removed.status()).isEqualTo(204)
    assertThat(computer.uploadSessions).isEmpty()
    assertThat(client.putUpload(key, file) {}.refused().code).isEqualTo("unknown_upload")
  }

  @Test
  fun resumableUploadsSendChunksAtTheComputersOffset() = runTest {
    computer.enableCompleteTier()
    val key = RequestId(newRequestKey())
    val half = bytes.size / 2L

    val open = client.openUpload(declaration(key)).ok() as UploadState.Open
    val first = client.patchUpload(key, file, 0, half)
    val status = client.uploadStatus(key).ok() as UploadState.Open
    val mismatch = client.patchUpload(key, file, 0, half).refused()
    val last = client.patchUpload(key, file, half, bytes.size - half)

    assertThat(open.resumable).isTrue()
    assertThat(first.status()).isEqualTo(204)
    assertThat(first.ok()).isEqualTo(UploadChunk.Accepted(half))
    assertThat(status.offset).isEqualTo(half)
    assertThat(mismatch.status to mismatch.code).isEqualTo(409 to "offset_mismatch")
    assertThat(mismatch.offset).isEqualTo(half)
    assertThat(last.status()).isEqualTo(201)
    val finished = last.ok() as UploadChunk.Finished
    assertThat(finished.job.created).isTrue()
    assertThat(computer.job(finished.job.value.id)!!.sourceBytes).isEqualTo(bytes)
    assertThat((client.uploadStatus(key).ok() as UploadState.Done).job.id).isEqualTo(finished.job.value.id)
  }

  @Test
  fun aPhoneVideoIsPreparedThenReady() = runTest {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip()

    val preparing = client.requestPhoneVideo(id)
    computer.finishRenditions(id)
    val ready = client.requestPhoneVideo(id)

    assertThat(preparing.status()).isEqualTo(202)
    assertThat(preparing.ok()).isEqualTo(RemoteArtifactStatus.PREPARING)
    assertThat(ready.status()).isEqualTo(200)
    assertThat(ready.ok()).isEqualTo(RemoteArtifactStatus.READY)
    val audio = computer.addFinishedClip(video = false)
    assertThat(client.requestPhoneVideo(audio).refused().code).isEqualTo("not_video")
  }

  @Test
  fun recordYourselfMeasuresASpanAndComparesATake() = runTest {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip()
    val take = folder.newFile("take.wav").apply { writeBytes(FakeMediaBytes.wav(1.0)) }

    val contour = client.contour(id, 0.5, 1.5)
    val compared = client.compare(id, take, "audio/wav", 0.5, 1.5)
    val mislabelled = client.compare(id, take, "audio/mp4", 0.5, 1.5).refused()
    val badSpan = client.contour(id, 2.0, 1.0).refused()

    assertThat(contour.ok()).contains("\"median_hz\"")
    assertThat(compared.ok()).contains("\"comparison\"")
    assertThat(mislabelled.status to mislabelled.code).isEqualTo(415 to "unsupported_type")
    assertThat(badSpan.code).isEqualTo("bad_span")
  }

  @Test
  fun completeRoutesAreUnknownOnACoreComputer() = runTest {
    val id = computer.addFinishedClip()

    val refused = client.contour(id, 0.5, 1.5).refused()

    assertThat(refused.status to refused.code).isEqualTo(404 to "unknown_route")
  }

  private fun declaration(
    key: RequestId,
    name: String = "VID_0001.mp4",
    sha256: String = PhoneCrypto.sha256Hex(bytes),
  ) = UploadDeclaration(key, name, bytes.size.toLong(), sha256, separateDialogue = true)
}
