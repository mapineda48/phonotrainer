package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Upload sessions (server-contract 6.6) with the limits of 4.7: declarations, the single `PUT`, the stall, "newest
 * wins" and idle rules (QT-4), and the v1-complete `GET`/`PATCH` offsets.
 */
class UploadsTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val credentials = computer.pairDevice()
  private val phone = TestPhone(computer, credentials)
  private val background = Executors.newCachedThreadPool()

  @After
  fun tearDown() {
    computer.close()
    background.shutdownNow()
  }

  @Test
  fun aDeclaredFileUploadsIntoAJobOfThisPhone() {
    val bytes = FakeMediaBytes.video(salt = 1)
    val key = TestPhone.newKey()

    val declared = phone.declare(key, "VID_0001.mp4", bytes)
    val upload = declared.json.obj("upload")
    assertThat(declared.code).isEqualTo(201)
    assertThat(declared.json.str("state")).isEqualTo("open")
    assertThat(upload["offset"]!!.prim().long).isEqualTo(0)
    assertThat(upload["resumable"]!!.prim().boolean).isFalse()
    assertThat(upload["max_chunk_bytes"]!!.prim().long).isEqualTo(8L * 1024 * 1024)
    assertThat(upload.str("expires_at")).isEqualTo("2026-10-12T09:00:00+00:00")

    val put = phone.put("/api/phone/uploads/$key", bytes)
    assertThat(put.code).isEqualTo(201)
    assertThat(put.job.obj("source").str("kind")).isEqualTo("upload")
    assertThat(put.job.obj("source").str("file_name")).isEqualTo("VID_0001.mp4")
    assertThat(put.job.str("title")).isEqualTo("VID_0001")
    assertThat(put.job.str("client_request_id")).isEqualTo(key)
    assertThat(computer.job(JobId(put.jobId))!!.sourceBytes).isEqualTo(bytes)
    assertThat(computer.uploadSessions).isEmpty()
  }

  @Test
  fun repeatedDeclarationsAndPutsAreIdempotent() {
    val bytes = FakeMediaBytes.video(salt = 2)
    val key = TestPhone.newKey()
    phone.declare(key, "VID_0002.mp4", bytes)

    assertThat(phone.declare(key, "VID_0002.mp4", bytes).code).isEqualTo(200)
    val first = phone.put("/api/phone/uploads/$key", bytes)
    val again = phone.put("/api/phone/uploads/$key", bytes)
    val declaredAfter = phone.declare(key, "VID_0002.mp4", bytes)

    assertThat(again.code).isEqualTo(200)
    assertThat(again.jobId).isEqualTo(first.jobId)
    assertThat(declaredAfter.code).isEqualTo(200)
    assertThat(declaredAfter.json.str("state")).isEqualTo("done")
    assertThat(declaredAfter.jobId).isEqualTo(first.jobId)
    assertThat(computer.jobsWithKey(RequestId(key))).hasSize(1)
  }

  @Test
  fun aLostPutAnswerIsFoundAgainByTheRetry() {
    val bytes = FakeMediaBytes.video(salt = 20)
    val key = TestPhone.newKey()
    phone.declare(key, "lost.mp4", bytes)
    computer.applyThenDrop("/api/phone/uploads/*", method = "PUT")

    val lost = runCatching { phone.put("/api/phone/uploads/$key", bytes) }
    val retry = phone.put("/api/phone/uploads/$key", bytes)

    assertThat(lost.isFailure).isTrue()
    assertThat(retry.code).isEqualTo(200)
    assertThat(computer.jobsWithKey(RequestId(key))).hasSize(1)
  }

  @Test
  fun declarationsRefuseEarly() {
    val bytes = FakeMediaBytes.video(salt = 3)

    val type = phone.declare(TestPhone.newKey(), "notes.txt", bytes)
    assertThat(type.code).isEqualTo(400)
    assertThat(type.errorCode).isEqualTo("unsupported_type")

    computer.maxUploadBytes = 100
    val large = phone.declare(TestPhone.newKey(), "big.mp4", bytes)
    assertThat(large.code).isEqualTo(413)
    assertThat(large.errorCode).isEqualTo("too_large")
    computer.maxUploadBytes = 2L * 1024 * 1024 * 1024

    val key = TestPhone.newKey()
    phone.declare(key, "a.mp4", bytes)
    val conflict = phone.declare(key, "b.mp4", bytes)
    assertThat(conflict.code).isEqualTo(409)
    assertThat(conflict.errorCode).isEqualTo("key_conflict")

    for (body in listOf(
      """{"client_request_id": "${TestPhone.newKey()}", "file_name": "a/b.mp4", "bytes": 3, "sha256": "${"a".repeat(64)}"}""",
      """{"client_request_id": "${TestPhone.newKey()}", "file_name": "a.mp4", "bytes": 0, "sha256": "${"a".repeat(64)}"}""",
      """{"client_request_id": "${TestPhone.newKey()}", "file_name": "a.mp4", "bytes": 3, "sha256": "abc"}""",
      """{"client_request_id": "not-a-uuid", "file_name": "a.mp4", "bytes": 3, "sha256": "${"a".repeat(64)}"}""",
    )) {
      assertThat(phone.post("/api/phone/uploads", body).errorCode).isEqualTo("invalid_request")
    }
  }

  @Test
  fun aFifthOpenSessionIsBusy() {
    repeat(4) { phone.declare(TestPhone.newKey(), "f$it.mp4", FakeMediaBytes.video(salt = it)) }

    val fifth = phone.declare(TestPhone.newKey(), "f5.mp4", FakeMediaBytes.video(salt = 5))

    assertThat(fifth.code).isEqualTo(409)
    assertThat(fifth.errorCode).isEqualTo("upload_busy")
  }

  @Test
  fun declaredBytesAreReservedAgainstFreeSpace() {
    val bytes = FakeMediaBytes.video(size = 600, salt = 4)
    computer.freeBytes = 2L * 1024 * 1024 * 1024 + 1_000

    assertThat(phone.declare(TestPhone.newKey(), "one.mp4", bytes).code).isEqualTo(201)
    val second = phone.declare(TestPhone.newKey(), "two.mp4", FakeMediaBytes.video(size = 600, salt = 5))

    assertThat(second.code).isEqualTo(507)
    assertThat(second.errorCode).isEqualTo("no_space")
  }

  @Test
  fun lowOnSpaceRefusesDeclarations() {
    computer.lowOnSpace()

    assertThat(phone.declare(TestPhone.newKey(), "a.mp4", FakeMediaBytes.video()).errorCode).isEqualTo("no_space")
  }

  @Test
  fun putRefusalsHaveTheirCodes() {
    val bytes = FakeMediaBytes.video(salt = 6)
    val key = TestPhone.newKey()

    assertThat(phone.put("/api/phone/uploads/$key", bytes).errorCode).isEqualTo("unknown_upload")
    phone.declare(key, "a.mp4", bytes)
    val chunked = phone.send("PUT", "/api/phone/uploads/$key", TestPhone.chunked("x"))
    assertThat(chunked.code).isEqualTo(411)
    assertThat(chunked.errorCode).isEqualTo("length_required")
    val short = phone.put("/api/phone/uploads/$key", bytes.copyOf(bytes.size - 1))
    assertThat(short.code).isEqualTo(400)
    assertThat(short.errorCode).isEqualTo("length_mismatch")
    val damaged = phone.put("/api/phone/uploads/$key", bytes.copyOf().also { it[100] = (it[100] + 1).toByte() })
    assertThat(damaged.code).isEqualTo(422)
    assertThat(damaged.errorCode).isEqualTo("checksum_mismatch")
    computer.lowOnSpace()
    assertThat(phone.put("/api/phone/uploads/$key", bytes).errorCode).isEqualTo("no_space")
    computer.lowOnSpace(false)
    assertThat(phone.put("/api/phone/uploads/$key", bytes).code).isEqualTo(201)
  }

  @Test
  fun deletingAnUploadIsAlways204() {
    val bytes = FakeMediaBytes.video(salt = 7)
    val key = TestPhone.newKey()
    phone.declare(key, "a.mp4", bytes)

    assertThat(phone.delete("/api/phone/uploads/$key").code).isEqualTo(204)
    assertThat(phone.delete("/api/phone/uploads/$key").code).isEqualTo(204)
    assertThat(phone.put("/api/phone/uploads/$key", bytes).errorCode).isEqualTo("unknown_upload")
  }

  @Test
  fun sessionsUntouchedFor48HoursExpire() {
    val bytes = FakeMediaBytes.video(salt = 8)
    val key = TestPhone.newKey()
    phone.declare(key, "a.mp4", bytes)

    computer.clock.advanceSeconds(48 * 3_600)

    assertThat(phone.put("/api/phone/uploads/$key", bytes).errorCode).isEqualTo("unknown_upload")
  }

  @Test
  fun aStalledPutIsReplacedByASecondPutAfterTenSeconds() {
    val bytes = FakeMediaBytes.video(size = 4_000, salt = 10)
    val key = TestPhone.newKey()
    phone.declare(key, "stall.mp4", bytes)
    computer.stallUpload(afterBytes = 1_000)

    val stalled = putInBackground(key, bytes)
    awaitInFlight { it.size == 1 && it[0].receivedBytes == 1_000L }
    computer.clock.advanceSeconds(10)
    val second = phone.put("/api/phone/uploads/$key", bytes)

    assertThat(second.code).isEqualTo(201)
    assertThat(stalled.get(TestPhone.TIMEOUT_S * 2, TimeUnit.SECONDS).isFailure).isTrue()
    assertThat(computer.jobsWithKey(RequestId(key))).hasSize(1)
    assertThat(computer.job(JobId(second.jobId))!!.sourceBytes).isEqualTo(bytes)
  }

  @Test
  fun aSecondPutBeforeTenSecondsIsBusy() {
    val bytes = FakeMediaBytes.video(size = 4_000, salt = 11)
    val key = TestPhone.newKey()
    phone.declare(key, "stall.mp4", bytes)
    computer.stallUpload(afterBytes = 1_000)

    putInBackground(key, bytes)
    awaitInFlight { it.singleOrNull()?.receivedBytes == 1_000L }
    computer.clock.advanceSeconds(5)
    val second = phone.put("/api/phone/uploads/$key", bytes)

    assertThat(second.code).isEqualTo(409)
    assertThat(second.errorCode).isEqualTo("upload_busy")
  }

  @Test
  fun anotherKeyOfTheSameDeviceIsBusyButAnotherDeviceIsNot() {
    val bytes = FakeMediaBytes.video(size = 4_000, salt = 12)
    val key = TestPhone.newKey()
    val otherKey = TestPhone.newKey()
    phone.declare(key, "stall.mp4", bytes)
    phone.declare(otherKey, "next.mp4", bytes)
    computer.stallUpload(afterBytes = 1_000)
    putInBackground(key, bytes)
    awaitInFlight { it.singleOrNull()?.receivedBytes == 1_000L }
    computer.clock.advanceSeconds(20)

    assertThat(phone.put("/api/phone/uploads/$otherKey", bytes).errorCode).isEqualTo("upload_busy")
    val other = TestPhone(computer, computer.pairDevice("Other"))
    val otherOwnKey = TestPhone.newKey()
    other.declare(otherOwnKey, "theirs.mp4", bytes)
    assertThat(other.put("/api/phone/uploads/$otherOwnKey", bytes).code).isEqualTo(201)
  }

  @Test
  fun aBodyIdleFor30SecondsIsAbortedAndItsPartDeleted() {
    val bytes = FakeMediaBytes.video(size = 4_000, salt = 13)
    val key = TestPhone.newKey()
    phone.declare(key, "stall.mp4", bytes)
    computer.stallUpload(afterBytes = 1_000)
    val stalled = putInBackground(key, bytes)
    awaitInFlight { it.singleOrNull()?.receivedBytes == 1_000L }

    computer.clock.advanceSeconds(30)
    awaitInFlight { it.isEmpty() }

    assertThat(stalled.get(TestPhone.TIMEOUT_S * 2, TimeUnit.SECONDS).isFailure).isTrue()
    assertThat(computer.uploadSessions.single().offset).isEqualTo(0)
    assertThat(computer.requests.last { it.method == "PUT" }.status).isNull()
    assertThat(phone.put("/api/phone/uploads/$key", bytes).code).isEqualTo(201)
  }

  @Test
  fun completeTierRoutesDoNotExistOnACoreComputer() {
    val key = TestPhone.newKey()
    phone.declare(key, "a.mp4", FakeMediaBytes.video())

    assertThat(phone.get("/api/phone/uploads/$key").errorCode).isEqualTo("unknown_route")
    assertThat(phone.patch("/api/phone/uploads/$key", byteArrayOf(1), 0).errorCode).isEqualTo("unknown_route")
  }

  @Test
  fun patchChunksCompleteAnUpload() {
    computer.enableCompleteTier()
    val bytes = FakeMediaBytes.video(size = 3_000, salt = 14)
    val key = TestPhone.newKey()
    assertThat(phone.declare(key, "a.mp4", bytes).json.obj("upload")["resumable"]!!.prim().boolean).isTrue()

    val first = phone.patch("/api/phone/uploads/$key", bytes.copyOfRange(0, 1_000), 0)
    assertThat(first.code).isEqualTo(204)
    assertThat(first.headers["Upload-Offset"]).isEqualTo("1000")
    assertThat(phone.get("/api/phone/uploads/$key").json.obj("upload")["offset"]!!.prim().long).isEqualTo(1_000)

    val mismatch = phone.patch("/api/phone/uploads/$key", bytes.copyOfRange(0, 1_000), 0)
    assertThat(mismatch.code).isEqualTo(409)
    assertThat(mismatch.errorCode).isEqualTo("offset_mismatch")
    assertThat(mismatch.json["offset"]!!.prim().long).isEqualTo(1_000)

    val last = phone.patch("/api/phone/uploads/$key", bytes.copyOfRange(1_000, 3_000), 1_000)
    assertThat(last.code).isEqualTo(201)
    assertThat(computer.job(JobId(last.jobId))!!.sourceBytes).isEqualTo(bytes)
    assertThat(phone.get("/api/phone/uploads/$key").json.str("state")).isEqualTo("done")
  }

  @Test
  fun patchRefusalsHaveTheirCodes() {
    computer.enableCompleteTier()
    computer.capabilities = computer.capabilities.copy(maxChunkBytes = 1_000)
    val bytes = FakeMediaBytes.video(size = 2_000, salt = 15)
    val key = TestPhone.newKey()

    assertThat(phone.patch("/api/phone/uploads/$key", bytes, 0).errorCode).isEqualTo("unknown_upload")
    assertThat(phone.get("/api/phone/uploads/$key").errorCode).isEqualTo("unknown_upload")
    phone.declare(key, "a.mp4", bytes)
    val large = phone.patch("/api/phone/uploads/$key", bytes.copyOfRange(0, 1_001), 0)
    assertThat(large.code).isEqualTo(413)
    assertThat(large.errorCode).isEqualTo("chunk_too_large")

    phone.patch("/api/phone/uploads/$key", bytes.copyOfRange(0, 1_000), 0)
    val damaged = phone.patch("/api/phone/uploads/$key", ByteArray(1_000), 1_000)
    assertThat(damaged.code).isEqualTo(422)
    assertThat(damaged.errorCode).isEqualTo("checksum_mismatch")
    assertThat(phone.get("/api/phone/uploads/$key").json.obj("upload")["offset"]!!.prim().long).isEqualTo(0)
  }

  @Test
  fun anInterruptedBodyKeepsItsBytesInTheCompleteTierAndPatchResumesIt() {
    computer.enableCompleteTier()
    val bytes = FakeMediaBytes.video(size = 4_000, salt = 16)
    val key = TestPhone.newKey()
    phone.declare(key, "resume.mp4", bytes)
    computer.stallUpload(afterBytes = 2_500)
    putInBackground(key, bytes)
    awaitInFlight { it.singleOrNull()?.receivedBytes == 2_500L }

    computer.clock.advanceSeconds(30)
    awaitInFlight { it.isEmpty() }
    val offset = phone.get("/api/phone/uploads/$key").json.obj("upload")["offset"]!!.prim().long
    val rest = phone.patch("/api/phone/uploads/$key", bytes.copyOfRange(offset.toInt(), bytes.size), offset)

    assertThat(offset).isEqualTo(2_500)
    assertThat(rest.code).isEqualTo(201)
    assertThat(computer.job(JobId(rest.jobId))!!.sourceBytes).isEqualTo(bytes)
  }

  @Test
  fun aStalledPatchIsReplacedByANewerPatchForTheSameKey() {
    computer.enableCompleteTier()
    val bytes = FakeMediaBytes.video(size = 3_000, salt = 17)
    val key = TestPhone.newKey()
    phone.declare(key, "a.mp4", bytes)
    computer.stallUpload(afterBytes = 500)
    background.submit<Result<TestResponse>> { runCatching { phone.patch("/api/phone/uploads/$key", bytes, 0) } }
    awaitInFlight { it.singleOrNull()?.receivedBytes == 500L }

    computer.clock.advanceSeconds(10)
    val offset = phone.get("/api/phone/uploads/$key").json.obj("upload")["offset"]!!.prim().long
    val newer = phone.patch("/api/phone/uploads/$key", bytes.copyOfRange(offset.toInt(), bytes.size), offset)

    assertThat(newer.code).isEqualTo(201)
    assertThat(computer.job(JobId(newer.jobId))!!.sourceBytes).isEqualTo(bytes)
  }

  private fun putInBackground(key: String, bytes: ByteArray): Future<Result<TestResponse>> =
    background.submit<Result<TestResponse>> { runCatching { phone.put("/api/phone/uploads/$key", bytes) } }

  private fun awaitInFlight(condition: (List<UploadInFlight>) -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TestPhone.TIMEOUT_S)
    while (!condition(computer.uploadsInFlight)) {
      check(System.nanoTime() < deadline) { "uploads in flight: ${computer.uploadsInFlight}" }
      Thread.sleep(POLL_MS)
    }
  }

  private companion object {
    const val POLL_MS = 10L
  }
}
