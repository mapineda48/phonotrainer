package com.mapineda48.phonotrainer.core.requests

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.LinkInput
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.requests.send.UploadRun
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClient
import com.mapineda48.phonotrainer.core.testing.computer.FakeMediaBytes
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uploads (architecture 8.5, v1-core): declared then sent whole under the request's key; restarted from zero after an
 * interruption until the computer's "newest wins" lets it through (QT-4 core); the background size rule (F-20-AC10);
 * "Stop sending" (F-20-AC11); early refusals; "Try audio only" from the kept copy with no re-run sent.
 */
@RunWith(RobolectricTestRunner::class)
class UploaderTest {
  private fun queueTest(block: suspend TestScope.(QueueHarness) -> Unit) = runTest {
    QueueHarness(backgroundScope).use { block(it) }
  }

  private suspend fun QueueHarness.waitingFile(bytes: ByteArray, declared: Long? = null, name: String = "VID_0001.mp4"): RequestId {
    val id = RequestId("10000000-0000-4000-8000-%012d".format(core.requests.count() + 1))
    val copy = files.copy(id, "mp4")
    copy.parentFile!!.mkdirs()
    copy.writeBytes(bytes)
    val now = clock.now().toEpochMilli()
    core.insert(
      RequestEntity(
        id = id.value,
        seq = 0,
        kind = "FILE",
        state = "WAITING",
        createdAt = now,
        updatedAt = now,
        fileName = name,
        fileMime = "video/mp4",
        fileBytes = declared ?: bytes.size.toLong(),
        copyFile = files.relative(copy),
        copyBytes = bytes.size.toLong(),
        copyComplete = true,
        fileSha256 = Sha256.hex(bytes),
        separateDialogue = true,
        targetServerId = computer.serverId.value,
      ),
    )
    return id
  }

  @Test
  fun aFileIsDeclaredThenSentWholeWithItsKeyAndItsCopyGoesOnceAccepted() = queueTest { queue ->
    val bytes = FakeMediaBytes.video(size = 50_000, salt = 1)
    val id = queue.waitingFile(bytes)

    assertThat(queue.uploader.run(FakeForeground())).isEqualTo(UploadRun.IDLE)

    val job = queue.computer.jobsWithKey(id).single()
    assertThat(job.sourceBytes).isEqualTo(bytes)
    assertThat(job.fileName).isEqualTo("VID_0001.mp4")
    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.IN_LINE)
    assertThat(request.jobId).isEqualTo(job.id)
    assertThat(queue.files.dir(id).exists()).isFalse()
  }

  @Test
  fun anInterruptedUploadRestartsFromZeroWithTheSameKeyUntilTheNewestBodyWins() = queueTest { queue ->
    val bytes = FakeMediaBytes.video(size = 40_000, salt = 2)
    val id = queue.waitingFile(bytes)
    queue.computer.stallUpload(afterBytes = 1_000)

    // The Wi-Fi drops mid-body: the attempt counts, nothing fails, the copy stays.
    assertThat(queue.uploader.run(FakeForeground())).isEqualTo(UploadRun.STOPPED)
    val waiting = queue.request(id)
    assertThat(waiting.state).isEqualTo(RequestState.WAITING)
    assertThat(waiting.waiting!!.attempts).isEqualTo(1)
    assertThat(queue.files.dir(id).exists()).isTrue()

    // Back 30 s later by the phone's schedule, 5 s by the computer's: the old body is not idle long enough yet, so the
    // computer answers upload_busy, and the phone waits until the newest body wins.
    queue.clock.advanceBy(Duration.ofSeconds(30))
    queue.computer.clock.advanceSeconds(5)
    queue.uploader.run(FakeForeground())

    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
    assertThat(queue.computer.jobsWithKey(id).single().sourceBytes).isEqualTo(bytes)
    assertThat(queue.client.puts).isAtLeast(3)
    assertThat(queue.request(id).state).isEqualTo(RequestState.IN_LINE)
  }

  @Test
  fun aLargeFileWaitsForTheAppWhileTheLinksBehindItGo() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 20_000, salt = 3), declared = 600_000_000)
    val link = (queue.repository.addLink(LinkInput("https://youtu.be/abc123def45", null, audioOnly = false)) as AddResult.Added).id
    val background = FakeForeground(visible = false)

    assertThat(queue.uploader.run(background)).isEqualTo(UploadRun.IDLE)
    queue.sender.sendLinks()

    val waiting = queue.request(id)
    assertThat(waiting.state).isEqualTo(RequestState.WAITING)
    assertThat(waiting.waiting!!.needsAppOpen).isTrue()
    assertThat(waiting.waiting!!.attempts).isEqualTo(0)
    assertThat(waiting.lastError).isNull()
    assertThat(queue.computer.requests.none { it.path.startsWith("/api/phone/uploads") }).isTrue()
    assertThat(queue.request(link).state).isEqualTo(RequestState.IN_LINE)

    // Visible, but Android refuses the foreground service now: it still waits, nothing failed.
    assertThat(queue.uploader.run(FakeForeground(visible = true, allowed = false))).isEqualTo(UploadRun.IDLE)
    assertThat(queue.request(id).state).isEqualTo(RequestState.WAITING)

    // The app is open: the upload starts as a visible transfer.
    val visible = FakeForeground(visible = true)
    queue.uploader.run(visible)
    assertThat(visible.promoted).isNotEmpty()
    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
  }

  @Test
  fun aMeasuredUploadRateDecidesWhatFitsOneBackgroundRun() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 20_000, salt = 4), declared = 600_000_000)
    queue.pairing.state.value = QueueHarness.record(queue.computer.serverId, throughput = 2_000_000.0)

    assertThat(queue.request(id).waiting!!.needsAppOpen).isFalse()
    queue.uploader.run(FakeForeground(visible = false))

    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
  }

  @Test
  fun stopSendingHoldsTheFileUntilSendNow() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 30_000, salt = 5))
    queue.client.putGate = CompletableDeferred()
    val running = launch { queue.uploader.run(FakeForeground()) }
    queue.client.putStarted.await()
    assertThat(queue.request(id).state).isEqualTo(RequestState.SENDING)
    assertThat(queue.request(id).actions).containsExactly(RequestAction.STOP_SENDING, RequestAction.REMOVE)

    queue.repository.perform(id, RequestAction.STOP_SENDING)
    running.join()

    val held = queue.request(id)
    assertThat(held.state).isEqualTo(RequestState.WAITING)
    assertThat(held.waiting!!.held).isTrue()
    assertThat(held.actions).contains(RequestAction.SEND_NOW)
    assertThat(queue.computer.jobsWithKey(id)).isEmpty()
    // Not sent again while connected.
    queue.client.putGate = null
    queue.uploader.run(FakeForeground())
    assertThat(queue.computer.jobsWithKey(id)).isEmpty()

    queue.repository.perform(id, RequestAction.SEND_NOW)
    withContext(Dispatchers.Default) { withTimeout(5_000) { while (queue.work.uploads.isEmpty()) delay(10) } }
    assertThat(queue.work.uploads).containsExactly(false)
    queue.uploader.run(FakeForeground())
    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
  }

  @Test
  fun removeWhileSendingStopsTheBytesAndDeletesTheCopy() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 30_000, salt = 6))
    queue.client.putGate = CompletableDeferred()
    val running = launch { queue.uploader.run(FakeForeground()) }
    queue.client.putStarted.await()

    queue.repository.perform(id, RequestAction.REMOVE)
    running.join()

    assertThat(queue.row(id)).isNull()
    assertThat(queue.files.dir(id).exists()).isFalse()
    assertThat(queue.computer.jobsWithKey(id)).isEmpty()
  }

  @Test
  fun aStoppedWorkerLeavesTheFileWaitingAndCountsNothing() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 30_000, salt = 7))
    queue.client.putGate = CompletableDeferred()
    val running = launch { queue.uploader.run(FakeForeground()) }
    queue.client.putStarted.await()

    running.cancel()
    running.join()

    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.WAITING)
    assertThat(request.waiting!!.attempts).isEqualTo(0)
    assertThat(request.waiting!!.held).isFalse()
  }

  @Test
  fun aVideoTooLargeForTheComputerIsRefusedBeforeAnyByteAndItsAudioIsTakenFromTheCopy() = queueTest { queue ->
    queue.pairing.state.value = QueueHarness.record(queue.computer.serverId, capabilities = QueueHarness.capabilities(maxUploadBytes = 10_000))
    val id = queue.waitingFile(FakeMediaBytes.video(size = 30_000, salt = 8))

    queue.uploader.run(FakeForeground())
    val refused = queue.request(id)
    assertThat(refused.state).isEqualTo(RequestState.NEEDS_ATTENTION)
    assertThat(refused.lastError!!.code).isEqualTo("too_large")
    assertThat(refused.lastError!!.atSending).isTrue()
    assertThat(refused.actions).containsExactly(RequestAction.TRY_AUDIO_ONLY, RequestAction.REMOVE)
    assertThat(queue.computer.requests).isEmpty()

    queue.extraction.audio = ByteArray(2_000) { 9 }
    queue.repository.perform(id, RequestAction.TRY_AUDIO_ONLY)
    assertThat(queue.request(id).state).isEqualTo(RequestState.PREPARING)
    assertThat(queue.work.picked).containsExactly(id)
    queue.runIntakes()
    val extracted = queue.row(id)!!
    assertThat(extracted.state).isEqualTo("WAITING")
    assertThat(extracted.audioExtracted).isTrue()
    assertThat(extracted.copyFile).endsWith("source.m4a")
    assertThat(queue.files.dir(id).list()!!.toList()).containsExactly("source.m4a")

    queue.uploader.run(FakeForeground())

    val job = queue.computer.jobsWithKey(id).single()
    assertThat(job.fileName).isEqualTo("VID_0001.m4a")
    assertThat(job.sourceBytes).isEqualTo(ByteArray(2_000) { 9 })
    assertThat(queue.computer.requests.none { it.path.endsWith("/rerun") }).isTrue()
  }

  @Test
  fun aComputerLowOnSpacePausesUploadsAndCountsNothing() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 20_000, salt = 9))
    queue.status.statusState.value = ComputerStatus.Connected("Desk", 0, 0, lowOnSpace = true)

    assertThat(queue.uploader.run(FakeForeground())).isEqualTo(UploadRun.STOPPED)
    assertThat(queue.computer.requests).isEmpty()

    queue.status.statusState.value = QueueHarness.CONNECTED
    queue.computer.lowOnSpace()
    assertThat(queue.uploader.run(FakeForeground())).isEqualTo(UploadRun.STOPPED)
    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.WAITING)
    assertThat(request.waiting!!.attempts).isEqualTo(0)
    assertThat(request.lastError).isNull()
  }

  @Test
  fun aCopyGoneBeforeSendingIsMissingAndNeverSent() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 20_000, salt = 10))
    queue.files.dir(id).deleteRecursively()

    queue.uploader.run(FakeForeground())

    assertThat(queue.request(id).state).isEqualTo(RequestState.COPY_MISSING)
    assertThat(queue.request(id).actions).containsExactly(RequestAction.REMOVE)
    assertThat(queue.computer.requests).isEmpty()
  }

  @Test
  fun aFileReRunIsSentFromTheComputersCopyWithoutUploadingAgain() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 20_000, salt = 11))
    queue.uploader.run(FakeForeground())
    val job = queue.request(id).jobId!!
    queue.computer.failJob(job, "no_space")
    queue.reconciler.reconcile((queue.fakeClient.library() as ApiResult.Ok).value)
    assertThat(queue.request(id).actions).containsExactly(RequestAction.TRY_AGAIN, RequestAction.REMOVE)
    val putsBefore = queue.client.puts

    queue.repository.perform(id, RequestAction.TRY_AGAIN)
    queue.sender.sendOperations()

    assertThat(queue.client.puts).isEqualTo(putsBefore)
    assertThat(queue.computer.requests.filter { it.path.endsWith("/rerun") }).hasSize(1)
    assertThat(queue.request(id).jobId).isNotEqualTo(job)
    assertThat(queue.repository.requests().first()).hasSize(1)
  }

  @Test
  fun aStaleSessionOfTheKeyIsAbandonedOnceAndTheFileDeclaredAgain() = queueTest { queue ->
    // The key declared the whole video before its audio was extracted on the phone (architecture 8.4).
    val id = queue.waitingFile(FakeMediaBytes.video(size = 20_000, salt = 12))
    val video = FakeMediaBytes.video(size = 90_000, salt = 13)
    val stale = UploadDeclaration(id, "VID_0001.mp4", video.size.toLong(), Sha256.hex(video), separateDialogue = true)
    assertThat(queue.fakeClient.openUpload(stale)).isInstanceOf(ApiResult.Ok::class.java)

    queue.uploader.run(FakeForeground())

    val calls = queue.computer.requests.map { it.method to it.path }
    assertThat(calls).contains("DELETE" to "/api/phone/uploads/${id.value}")
    assertThat(queue.computer.jobsWithKey(id).single().sourceBytes).isEqualTo(FakeMediaBytes.video(size = 20_000, salt = 12))
    assertThat(queue.request(id).state).isEqualTo(RequestState.IN_LINE)
  }

  @Test
  fun aKeyHeldByAnotherPhoneWaitsAndFailsNothing() = queueTest { queue ->
    val id = queue.waitingFile(FakeMediaBytes.video(size = 20_000, salt = 14))
    val otherPhone = queue.computer.pairDevice("Other")
    val other = FakeComputerClient(queue.computer, otherPhone)
    val theirs = FakeMediaBytes.video(size = 10_000, salt = 15)
    other.openUpload(UploadDeclaration(id, "theirs.mp4", theirs.size.toLong(), Sha256.hex(theirs), separateDialogue = null))

    assertThat(queue.uploader.run(FakeForeground())).isEqualTo(UploadRun.IDLE)

    // A UUID clash is a defect of the key (server-contract 8.1): logged, retried on the schedule, never "failed".
    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.WAITING)
    assertThat(request.waiting!!.attempts).isEqualTo(1)
    assertThat(queue.computer.jobsWithKey(id)).isEmpty()
    val declarations = queue.computer.requests.filter { it.method == "POST" && it.path == "/api/phone/uploads" && it.signedBy != otherPhone.deviceId }
    assertThat(declarations.map { it.status }).containsExactly(409, 409)
  }

  @Test
  fun aStoreFailureOnTheComputerKeepsTheCopyAndTheNextTryUsesTheSameKey() = queueTest { queue ->
    val bytes = FakeMediaBytes.video(size = 20_000, salt = 16)
    val id = queue.waitingFile(bytes)
    queue.computer.failNext("/api/phone/uploads/*", 500, "server_error", method = "PUT")

    queue.uploader.run(FakeForeground())
    val waiting = queue.request(id)
    assertThat(waiting.state).isEqualTo(RequestState.WAITING)
    assertThat(waiting.waiting!!.attempts).isEqualTo(1)
    assertThat(queue.files.dir(id).exists()).isTrue()

    queue.clock.advanceBy(Duration.ofSeconds(30))
    queue.uploader.run(FakeForeground())

    assertThat(queue.computer.jobsWithKey(id).single().sourceBytes).isEqualTo(bytes)
    assertThat(queue.request(id).state).isEqualTo(RequestState.IN_LINE)
  }
}
