package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Health, the listing, one job, request lookup, reference and artifacts (server-contract 6.2 to 6.4, 6.8). */
class LibraryAndArtifactsTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val credentials = computer.pairDevice("Pixel 9")
  private val phone = TestPhone(computer, credentials)

  @After
  fun tearDown() = computer.close()

  @Test
  fun healthReportsIdentityQueueSpaceAndCapabilities() {
    computer.addJob(JobSeed(status = JobStatus.RUNNING))
    computer.addJob(JobSeed(status = JobStatus.QUEUED))
    computer.addJob(JobSeed(status = JobStatus.QUEUED))
    computer.maxLinkDurationS = 1_800

    val health = phone.get("/api/phone/health").json

    assertThat(health.str("server_id")).isEqualTo(computer.serverId.value)
    assertThat(health.obj("device").str("device_id")).isEqualTo(credentials.deviceId.value)
    assertThat(health.obj("device").str("name")).isEqualTo("Pixel 9")
    assertThat(health.obj("queue")["analyzing"]!!.prim().int).isEqualTo(1)
    assertThat(health.obj("queue")["in_line"]!!.prim().int).isEqualTo(2)
    assertThat(health["low_on_space"]!!.prim().boolean).isFalse()
    val caps = health.obj("capabilities")
    assertThat(caps.obj("downloader").str("version")).isEqualTo("2026.8.19")
    assertThat(caps["renditions"]!!.prim().boolean).isFalse()
    assertThat(caps["max_upload_bytes"]!!.prim().long).isEqualTo(2L * 1024 * 1024 * 1024)
    assertThat(caps["max_chunk_bytes"]!!.prim().long).isEqualTo(8L * 1024 * 1024)
    assertThat(caps["max_link_duration_s"]!!.prim().long).isEqualTo(1_800)
  }

  @Test
  fun lowOnSpaceShowsInHealth() {
    computer.lowOnSpace()

    val health = phone.get("/api/phone/health").json

    assertThat(health["low_on_space"]!!.prim().boolean).isTrue()
  }

  @Test
  fun theListingHoldsEveryJobInIdOrderWithOwnershipPerViewer() {
    val other = computer.pairDevice("Other phone")
    val mine = computer.addJob(JobSeed(deviceId = credentials.deviceId, clientRequestId = RequestId(key(1))))
    val theirs = computer.addJob(JobSeed(deviceId = other.deviceId, clientRequestId = RequestId(key(2))))
    val browser = computer.addFinishedClip("From the browser")

    val library = phone.get("/api/phone/library").json
    val jobs = library.arr("jobs").map { it.jsonObject }

    assertThat(library.str("library_id")).isEqualTo(computer.libraryId.value)
    assertThat(library["complete"]!!.prim().boolean).isTrue()
    // Ordered by id; ids made within one second differ only by their random suffix (server-contract 6.3).
    assertThat(jobs.map { it.str("id") }).containsExactly(mine.value, theirs.value, browser.value)
    assertThat(jobs.map { it.str("id") }).isInOrder()
    val byId = jobs.associateBy { it.str("id") }
    assertThat(byId.getValue(mine.value)["own"]!!.prim().boolean).isTrue()
    assertThat(byId.getValue(mine.value).str("device_id")).isEqualTo(credentials.deviceId.value)
    assertThat(byId.getValue(mine.value).str("client_request_id")).isEqualTo(key(1))
    assertThat(byId.getValue(theirs.value)["own"]!!.prim().boolean).isFalse()
    assertThat(byId.getValue(theirs.value)["device_id"]).isEqualTo(JsonNull)
    assertThat(byId.getValue(theirs.value)["client_request_id"]).isEqualTo(JsonNull)
    assertThat(byId.getValue(browser.value)["device_id"]).isEqualTo(JsonNull)
  }

  @Test
  fun aDoneJobHasTheContractShape() {
    val id = computer.addFinishedClip("Ordering coffee")

    val job = phone.get("/api/phone/jobs/${id.value}").json

    assertThat(job.str("id")).matches("\\d{8}-\\d{6}-[0-9a-f]{4}")
    assertThat(job.str("revision")).matches("[0-9a-f]{16}")
    assertThat(job.str("status")).isEqualTo("done")
    assertThat(job.str("title")).isEqualTo("Ordering coffee")
    assertThat(job.obj("source").str("site")).isEqualTo("youtube")
    assertThat(job.obj("summary")["words"]!!.prim().int).isEqualTo(MinimalClip.WORDS)
    assertThat(job.obj("analysis")["dialogue_separation"]!!.prim().boolean).isTrue()
    assertThat(job.obj("media").str("kind")).isEqualTo("video")
    assertThat(job.obj("media")["rotation"]!!.prim().int).isEqualTo(0)
    assertThat(job.str("created")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+00:00")
    val names = job.arr("artifacts").map { it.jsonObject.str("name") }
    assertThat(names).containsExactly("analysis", "audio_mix", "audio_dialogue", "original").inOrder()
    val mix = job.arr("artifacts").map { it.jsonObject }.first { it.str("name") == "audio_mix" }
    assertThat(mix.str("url")).isEqualTo("/api/phone/jobs/${id.value}/artifacts/audio_mix")
    assertThat(mix.str("revision")).isEqualTo(mix.str("sha256")!!.substring(0, 16))
  }

  @Test
  fun progressShowsStageQueuePositionsAndKeepsTheRevision() {
    val first = computer.addJob(JobSeed(status = JobStatus.QUEUED))
    val second = computer.addJob(JobSeed(status = JobStatus.QUEUED))
    val before = phone.get("/api/phone/jobs/${second.value}").json

    computer.progress(first, JobStage.DOWNLOAD, 3, downloadPercent = 42)
    val running = phone.get("/api/phone/jobs/${first.value}").json
    val waiting = phone.get("/api/phone/jobs/${second.value}").json

    assertThat(before["queue_position"]!!.prim().int).isEqualTo(2)
    assertThat(running.str("stage")).isEqualTo("download")
    assertThat(running.obj("progress")["download_percent"]!!.prim().int).isEqualTo(42)
    assertThat(waiting["queue_position"]!!.prim().int).isEqualTo(1)
    assertThat(waiting.str("revision")).isEqualTo(before.str("revision"))
  }

  @Test
  fun aFailedLinkCarriesItsCodeAndTheDownloaderVersion() {
    val id = computer.addJob(JobSeed(status = JobStatus.QUEUED, url = "https://x.com/someone/status/1234567890"))
    val queued = phone.get("/api/phone/jobs/${id.value}").json.str("revision")

    computer.failJob(id, "too_long")
    val failed = phone.get("/api/phone/jobs/${id.value}").json

    assertThat(failed.str("status")).isEqualTo("error")
    assertThat(failed.obj("source").str("site")).isEqualTo("x")
    assertThat(failed.obj("error").str("code")).isEqualTo("too_long")
    assertThat(failed.obj("error").str("downloader_version")).isEqualTo("2026.8.19")
    assertThat(failed.str("revision")).isNotEqualTo(queued)
  }

  @Test
  fun liveStreamFailuresToo() {
    val id = computer.addJob(JobSeed(status = JobStatus.QUEUED))

    computer.failJob(id, "live_stream")

    val error = phone.get("/api/phone/jobs/${id.value}").json.obj("error")
    assertThat(error.str("code")).isEqualTo("live_stream")
    assertThat(error.str("downloader_version")).isEqualTo("2026.8.19")
  }

  @Test
  fun anUnknownJobIsUnknownJob() {
    val response = phone.get("/api/phone/jobs/20261010-091503-4be1")

    assertThat(response.code).isEqualTo(404)
    assertThat(response.errorCode).isEqualTo("unknown_job")
  }

  @Test
  fun requestLookupFindsOnlyThisPhonesKeys() {
    val other = computer.pairDevice("Other phone")
    val id = computer.addJob(JobSeed(deviceId = credentials.deviceId, clientRequestId = RequestId(key(3))))
    computer.addJob(JobSeed(deviceId = other.deviceId, clientRequestId = RequestId(key(4))))

    assertThat(phone.get("/api/phone/requests/${key(3).uppercase()}").jobId).isEqualTo(id.value)
    val foreign = phone.get("/api/phone/requests/${key(4)}")
    assertThat(foreign.code).isEqualTo(404)
    assertThat(foreign.errorCode).isEqualTo("unknown_request")
  }

  @Test
  fun referenceIsServedWithItsVersionEtag() {
    computer.referenceJson = """{"api_version": 6, "families": [{"key": "linking"}]}"""

    val response = phone.get("/api/phone/reference")

    assertThat(response.text).isEqualTo("""{"api_version": 6, "families": [{"key": "linking"}]}""")
    assertThat(response.headers["ETag"]).isEqualTo("\"api-6\"")
  }

  @Test
  fun anArtifactIsServedWholeWithItsEtagAndChecksum() {
    val id = computer.addFinishedClip()
    val entry = artifactEntry(id, "audio_mix")

    val response = phone.get(entry.str("url")!!)

    assertThat(response.code).isEqualTo(200)
    assertThat(response.headers["Content-Type"]).isEqualTo("audio/wav")
    assertThat(response.headers["Accept-Ranges"]).isEqualTo("bytes")
    assertThat(response.headers["ETag"]).isEqualTo("\"${entry.str("sha256")}\"")
    assertThat(PhoneCrypto.sha256Hex(response.bytes)).isEqualTo(entry.str("sha256"))
    assertThat(response.bytes.size.toLong()).isEqualTo(entry["bytes"]!!.prim().long)
  }

  @Test
  fun rangeResumesAndIfRangeMismatchRestartsFromZero() {
    val id = computer.addFinishedClip()
    val entry = artifactEntry(id, "audio_mix")
    val url = entry.str("url")!!
    val whole = phone.get(url).bytes

    val tail = phone.get(url, mapOf("Range" to "bytes=1000-", "If-Range" to "\"${entry.str("sha256")}\""))
    assertThat(tail.code).isEqualTo(206)
    assertThat(tail.headers["Content-Range"]).isEqualTo("bytes 1000-${whole.size - 1}/${whole.size}")
    assertThat(tail.bytes).isEqualTo(whole.copyOfRange(1000, whole.size))

    val stale = phone.get(url, mapOf("Range" to "bytes=1000-", "If-Range" to "\"${"0".repeat(64)}\""))
    assertThat(stale.code).isEqualTo(200)
    assertThat(stale.bytes).isEqualTo(whole)
  }

  @Test
  fun aRangeAtTheEndIsUnsatisfiable() {
    val id = computer.addFinishedClip()
    val entry = artifactEntry(id, "analysis")

    val response = phone.get(entry.str("url")!!, mapOf("Range" to "bytes=${entry["bytes"]!!.prim().long}-"))

    assertThat(response.code).isEqualTo(416)
  }

  @Test
  fun unknownAndUnreadyArtifactsAreRefused() {
    val done = computer.addFinishedClip()
    val running = computer.addJob(JobSeed(status = JobStatus.RUNNING))

    assertThat(phone.get("/api/phone/jobs/${done.value}/artifacts/report").errorCode).isEqualTo("unknown_artifact")
    assertThat(phone.get("/api/phone/jobs/${done.value}/artifacts/poster").errorCode).isEqualTo("unknown_artifact")
    val notReady = phone.get("/api/phone/jobs/${running.value}/artifacts/analysis")
    assertThat(notReady.code).isEqualTo(409)
    assertThat(notReady.errorCode).isEqualTo("artifact_not_ready")
    computer.setArtifactStatus(done, "audio_mix", ArtifactStatus.PREPARING)
    assertThat(phone.get("/api/phone/jobs/${done.value}/artifacts/audio_mix").errorCode).isEqualTo("artifact_not_ready")
    assertThat(phone.get("/api/phone/jobs/20261010-091503-4be1/artifacts/analysis").errorCode).isEqualTo("unknown_job")
  }

  @Test
  fun theOriginalIsUnavailableOnceItsFileIsGone() {
    val id = computer.addFinishedClip()

    computer.removeSource(id)

    assertThat(artifactEntry(id, "original").str("status")).isEqualTo("unavailable")
    assertThat(phone.get("/api/phone/jobs/${id.value}/artifacts/original").errorCode).isEqualTo("artifact_not_ready")
  }

  @Test
  fun anAudioOnlyClipHasNoDialogueWhenSeparationWasOffAndNoOriginal() {
    val id = computer.addJob(JobSeed(audioOnly = true, separateDialogue = false))

    val job = phone.get("/api/phone/jobs/${id.value}").json

    assertThat(job.arr("artifacts").map { it.jsonObject.str("name") }).containsExactly("analysis", "audio_mix")
    assertThat(job.str("separation")).isEqualTo("off")
    assertThat(job.obj("media").str("kind")).isEqualTo("audio")
  }

  @Test
  fun truncateCutsTheDownloadAndTheNextOneIsWhole() {
    val id = computer.addFinishedClip()
    val url = artifactEntry(id, "audio_mix").str("url")!!
    computer.truncate("audio_mix", atBytes = 2_000)

    val partial = phone.client.newCall(Request.Builder().url(computer.url(url)).build()).execute()
    assertThat(partial.code).isEqualTo(200)
    val received = mutableListOf<Byte>()
    assertThrows(IOException::class.java) {
      partial.body.source().use { source ->
        while (true) received += source.readByte()
      }
    }

    assertThat(received).hasSize(2_000)
    assertThat(phone.get(url).bytes.size).isGreaterThan(2_000)
  }

  @Test
  fun corruptKeepsTheSizeAndBreaksTheChecksum() {
    val id = computer.addFinishedClip()
    val entry = artifactEntry(id, "analysis")
    computer.corrupt("analysis")

    val bad = phone.get(entry.str("url")!!).bytes

    assertThat(bad.size.toLong()).isEqualTo(entry["bytes"]!!.prim().long)
    assertThat(PhoneCrypto.sha256Hex(bad)).isNotEqualTo(entry.str("sha256"))
    assertThat(PhoneCrypto.sha256Hex(phone.get(entry.str("url")!!).bytes)).isEqualTo(entry.str("sha256"))
  }

  @Test
  fun theServedAnalysisIsTheMinimalClip() {
    val id = computer.addFinishedClip()

    val analysis = phone.get(artifactEntry(id, "analysis").str("url")!!).json

    assertThat(analysis.arr("segments")).hasSize(MinimalClip.SEGMENTS)
    assertThat(analysis.obj("meta").str("source")).isEqualTo("Seeded clip")
  }

  private fun artifactEntry(id: JobId, name: String) = phone.get("/api/phone/jobs/${id.value}").json
    .arr("artifacts").map { it.jsonObject }.first { it.str("name") == name }

  private fun key(n: Int) = "00000000-0000-4000-8000-00000000000$n"
}
