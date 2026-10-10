package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Test
import java.util.zip.GZIPInputStream

/** v1-complete routes behind their capability flags: renditions, phone video, record yourself (6.4, 6.9). */
class CompleteTierTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val credentials = computer.pairDevice()
  private val phone = TestPhone(computer, credentials)

  @After
  fun tearDown() = computer.close()

  @Test
  fun aCompleteComputerReportsApi7AndItsFlags() {
    computer.enableCompleteTier()

    val health = phone.get("/api/phone/health").json
    val caps = health.obj("capabilities")

    assertThat(health["api_version"]!!.prim().int).isEqualTo(7)
    for (flag in listOf("renditions", "phone_video", "resumable_uploads", "record_compare")) {
      assertThat(caps[flag]!!.prim().boolean).isTrue()
    }
  }

  @Test
  fun renditionsAreListedAfterTheCoreArtifactsAndTheCompactClipDataIsTheSameJson() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip()

    val artifacts = phone.get("/api/phone/jobs/${id.value}").json.arr("artifacts").map { it.jsonObject }
    val names = artifacts.map { it.str("name") }
    val compactUrl = artifacts.first { it.str("name") == "analysis_compact" }.str("url")!!
    val analysisUrl = artifacts.first { it.str("name") == "analysis" }.str("url")!!

    assertThat(names).containsExactly(
      "analysis", "audio_mix", "audio_dialogue", "original", "analysis_compact", "audio_mix_opus",
      "audio_dialogue_opus", "poster",
    ).inOrder()
    val compact = phone.get(compactUrl)
    assertThat(compact.headers["Content-Type"]).isEqualTo("application/gzip")
    assertThat(compact.headers["Content-Encoding"]).isNull()
    val decoded = GZIPInputStream(compact.bytes.inputStream()).readBytes().decodeToString()
    assertThat(Json.parseToJsonElement(decoded)).isEqualTo(Json.parseToJsonElement(phone.get(analysisUrl).text))
  }

  @Test
  fun anAudioClipHasNoPoster() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip(video = false)

    val names = phone.get("/api/phone/jobs/${id.value}").json.arr("artifacts").map { it.jsonObject.str("name") }

    assertThat(names).doesNotContain("poster")
    assertThat(names).doesNotContain("original")
  }

  @Test
  fun preparingRenditionsBecomeReadyAndChangeTheRevision() {
    computer.enableCompleteTier()
    computer.renditionsPreparing = true
    val id = computer.addFinishedClip()
    val before = phone.get("/api/phone/jobs/${id.value}").json

    computer.finishRenditions(id)
    val after = phone.get("/api/phone/jobs/${id.value}").json

    val status = { job: kotlinx.serialization.json.JsonObject, name: String ->
      job.arr("artifacts").map { it.jsonObject }.first { it.str("name") == name }.str("status")
    }
    assertThat(status(before, "audio_mix_opus")).isEqualTo("preparing")
    assertThat(status(after, "audio_mix_opus")).isEqualTo("ready")
    assertThat(after.str("revision")).isNotEqualTo(before.str("revision"))
  }

  @Test
  fun aFailedRenditionCarriesItsMessage() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip()

    computer.setArtifactStatus(id, "poster", ArtifactStatus.FAILED, "ffmpeg could not read the video")

    val poster = phone.get("/api/phone/jobs/${id.value}").json.arr("artifacts").map { it.jsonObject }
      .first { it.str("name") == "poster" }
    assertThat(poster.str("status")).isEqualTo("failed")
    assertThat(poster.str("error")).isEqualTo("ffmpeg could not read the video")
    assertThat(poster.str("url")).isNull()
  }

  @Test
  fun phoneVideoIsPreparedOnRequestAndThenReady() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip()
    val path = "/api/phone/jobs/${id.value}/renditions/video_phone"

    val first = phone.post(path)
    val again = phone.post(path)
    computer.finishRenditions(id)
    val ready = phone.post(path)

    assertThat(first.code).isEqualTo(202)
    assertThat(first.json.str("status")).isEqualTo("preparing")
    assertThat(again.code).isEqualTo(202)
    assertThat(ready.code).isEqualTo(200)
    assertThat(ready.json.str("status")).isEqualTo("ready")
    val entry = phone.get("/api/phone/jobs/${id.value}").json.arr("artifacts").map { it.jsonObject }
      .first { it.str("name") == "video_phone" }
    assertThat(entry.obj("video").str("codec")).isEqualTo("h264")
    assertThat(entry.obj("video")["height"]!!.prim().int).isEqualTo(720)
    assertThat(phone.get(entry.str("url")!!).code).isEqualTo(200)
  }

  @Test
  fun phoneVideoRefusals() {
    assertThat(phone.post("/api/phone/jobs/20261010-091503-4be1/renditions/video_phone").errorCode)
      .isEqualTo("unknown_route")
    computer.enableCompleteTier()
    val running = computer.addJob(JobSeed(status = JobStatus.RUNNING))
    val audio = computer.addFinishedClip(video = false)
    val gone = computer.addFinishedClip()
    computer.removeSource(gone)

    assertThat(phone.post("/api/phone/jobs/${running.value}/renditions/video_phone").errorCode).isEqualTo("not_ready")
    assertThat(phone.post("/api/phone/jobs/${audio.value}/renditions/video_phone").errorCode).isEqualTo("not_video")
    assertThat(phone.post("/api/phone/jobs/20261010-091503-4be1/renditions/video_phone").errorCode)
      .isEqualTo("unknown_job")
    val unavailable = phone.post("/api/phone/jobs/${gone.value}/renditions/video_phone")
    assertThat(unavailable.code).isEqualTo(200)
    assertThat(unavailable.json.str("status")).isEqualTo("unavailable")
  }

  @Test
  fun contourAnswersTheSpanAndRefusesBadOnes() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip().value

    val contour = phone.get("/api/phone/jobs/$id/contour?start=0.5&end=1.25")
    assertThat(contour.code).isEqualTo(200)
    assertThat(contour.json.str("audio")).isEqualTo("dialogue")
    assertThat(contour.json.arr("track")).isNotEmpty()

    assertThat(phone.get("/api/phone/jobs/$id/contour?start=2&end=1").errorCode).isEqualTo("bad_span")
    assertThat(phone.get("/api/phone/jobs/$id/contour?start=0&end=31").errorCode).isEqualTo("bad_span")
    assertThat(phone.get("/api/phone/jobs/$id/contour?start=x&end=1").errorCode).isEqualTo("invalid_request")
    assertThat(phone.get("/api/phone/jobs/20261010-091503-4be1/contour?start=0&end=1").errorCode)
      .isEqualTo("unknown_job")
  }

  @Test
  fun compareMeasuresATakeAndKeepsNothing() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip().value

    val response = phone.send("POST", "/api/phone/jobs/$id/compare", take(webm(), "audio/webm"))

    assertThat(response.code).isEqualTo(200)
    assertThat(response.json.keys).containsExactly("native", "take", "comparison")
    assertThat(response.json.obj("take").containsKey("duration_s")).isTrue()
  }

  @Test
  fun compareRefusalsHaveTheRecordYourselfCodes() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip().value
    val path = "/api/phone/jobs/$id/compare"

    assertThat(phone.send("POST", path, TestPhone.chunked("x")).errorCode).isEqualTo("length_required")
    val noFile = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("start", "0").build()
    assertThat(phone.send("POST", path, noFile).errorCode).isEqualTo("missing_file")
    assertThat(phone.send("POST", path, take(webm(), "audio/webm", start = "x")).errorCode).isEqualTo("bad_span")
    val wrongType = phone.send("POST", path, take(webm(), "audio/ogg"))
    assertThat(wrongType.code).isEqualTo(415)
    assertThat(wrongType.errorCode).isEqualTo("unsupported_type")
    val empty = phone.send("POST", path, take(ByteArray(0), "audio/webm"))
    assertThat(empty.code).isEqualTo(422)
    assertThat(empty.errorCode).isEqualTo("empty")
    val large = phone.send("POST", path, take(ByteArray(8 * 1024 * 1024 + 64 * 1024 + 1), "audio/webm"))
    assertThat(large.code).isEqualTo(413)
    assertThat(large.errorCode).isEqualTo("too_large")
  }

  @Test
  fun recordCompareCodesFromTheAnalysisSideComeThroughFailNext() {
    computer.enableCompleteTier()
    val id = computer.addFinishedClip().value
    computer.failNext("/api/phone/jobs/*/compare", 422, "no_voice")

    val response = phone.send("POST", "/api/phone/jobs/$id/compare", take(webm(), "audio/webm"))

    assertThat(response.errorCode).isEqualTo("no_voice")
  }

  private fun take(bytes: ByteArray, type: String, start: String = "0.5", end: String = "1.5") =
    MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("file", "take.webm", bytes.toRequestBody(type.toMediaType()))
      .addFormDataPart("start", start)
      .addFormDataPart("end", end)
      .build()

  private fun webm(): ByteArray = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(256) { it.toByte() }
}
