package com.mapineda48.phonotrainer.core.network.dto

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.MediaKind
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.SeparationNote
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClock
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Test

/**
 * Tolerant reading and validation before mapping (architecture 5.4, NFR-08, security review M-3), against crafted
 * answers: unknown fields are ignored and unknown enum values are UNKNOWN, while a listing that names a job outside
 * the id pattern, an artifact outside the closed table, bad hex, a foreign `url` or a body over its cap is refused as
 * a whole; and every error envelope maps to `ApiResult.Refused` with its optional fields.
 */
class AnswersTest {
  private val server = MockWebServer().apply { start(InetAddress.getByName(FakeComputer.HOST), 0) }
  private val computer = FakeComputer(clock = FakeComputerClock.frozen())
  private val stack = TestStack(computer)

  init {
    runBlocking { stack.trust(HostPort(FakeComputer.HOST, server.port)) }
  }

  @After
  fun close() {
    stack.close()
    server.close()
    computer.close()
  }

  private fun answer(code: Int, body: String, vararg headers: Pair<String, String>) {
    val builder = MockResponse.Builder().code(code).body(body).setHeader("Content-Type", "application/json")
    headers.forEach { (name, value) -> builder.setHeader(name, value) }
    server.enqueue(builder.build())
  }

  private fun listing(vararg jobs: JsonObject): String = buildJsonObject {
    put("api_version", 6)
    put("server_id", "srv_test")
    put("library_id", "lib_test")
    put("generated_at", "2026-10-10T09:41:00+00:00")
    put("complete", true)
    put("jobs", JsonArray(jobs.toList()))
  }.toString()

  private suspend fun library(vararg jobs: JsonObject): ApiResult<RemoteLibrary> {
    answer(200, listing(*jobs))
    return stack.client.library()
  }

  @Test
  fun aDocumentedJobIsMappedFieldByField() = runBlocking<Unit> {
    val listed = library(job()) as ApiResult.Ok
    val job = listed.value.jobs.single()

    assertThat(job.id).isEqualTo(JobId(JOB_ID))
    assertThat(job.revision).isEqualTo(REVISION)
    assertThat(job.status).isEqualTo(RemoteStatus.DONE)
    assertThat(job.source.kind).isEqualTo(SourceKind.LINK)
    assertThat(job.source.site).isEqualTo(Site.YOUTUBE)
    assertThat(job.clientRequestId).isEqualTo(RequestId(UUID_ONE))
    assertThat(job.summary!!.changeCounts).containsExactly("flapping", 12, "linking", 30)
    assertThat(job.media!!.kind).isEqualTo(MediaKind.VIDEO)
    val artifact = job.artifacts!!.single()
    assertThat(artifact.role).isEqualTo(ArtifactRole.AUDIO_MIX)
    assertThat(artifact.status).isEqualTo(RemoteArtifactStatus.READY)
    assertThat(artifact.url).isEqualTo("/api/phone/jobs/$JOB_ID/artifacts/audio_mix")
  }

  @Test
  fun unknownFieldsAreIgnoredAndUnknownValuesAreUnknown() = runBlocking<Unit> {
    val future = job {
      put("status", "archived")
      put("stage", "translate")
      put("separation", "partial")
      put("a_new_field", buildJsonObject { put("nested", true) })
      putJsonObject("source") {
        put("kind", "stream")
        put("url", "https://example.org/watch")
        put("site", "vimeo")
      }
      putJsonObject("media") { put("kind", "hologram") }
      putJsonObject("error") {
        put("code", "quantum_failure")
        put("message", "Something new.")
      }
    }

    val job = (library(future) as ApiResult.Ok).value.jobs.single()

    assertThat(job.status).isEqualTo(RemoteStatus.UNKNOWN)
    assertThat(job.stage).isEqualTo(Stage.UNKNOWN)
    assertThat(job.separation).isEqualTo(SeparationNote.UNKNOWN)
    assertThat(job.source.kind).isEqualTo(SourceKind.UNKNOWN)
    assertThat(job.source.site).isEqualTo(Site.OTHER)
    assertThat(job.media!!.kind).isEqualTo(MediaKind.UNKNOWN)
    assertThat(job.error!!.code).isEqualTo("processing_failed")
    assertThat(job.error!!.message).isEqualTo("Something new.")
  }

  @Test
  fun anUnknownArtifactStatusIsUnknownAndAFileOverItsRoleCapIsUnavailable() = runBlocking<Unit> {
    val listed = library(
      job {
        putJsonArray("artifacts") {
          add(artifact("audio_mix") { put("status", "cooking") })
          add(artifact("analysis", role = "clip_data") { put("bytes", 33L * 1024 * 1024) })
        }
      },
    ) as ApiResult.Ok

    val artifacts = listed.value.jobs.single().artifacts!!.associateBy { it.name }
    assertThat(artifacts.getValue("audio_mix").status).isEqualTo(RemoteArtifactStatus.UNKNOWN)
    assertThat(artifacts.getValue("analysis").status).isEqualTo(RemoteArtifactStatus.UNAVAILABLE)
  }

  @Test
  fun hostileListingsAreMalformedAsAWhole() = runBlocking<Unit> {
    val hostile = mapOf(
      "a path in the job id" to job { put("id", "../../etc") },
      "a job id outside the pattern" to job { put("id", "20261010-091503-4BE1") },
      "a revision that is not hex" to job { put("revision", "zzzzzzzzzzzzzzzz") },
      "an unknown artifact name" to job { putJsonArray("artifacts") { add(artifact("report.html")) } },
      "an artifact name with a path" to job { putJsonArray("artifacts") { add(artifact("../audio_mix")) } },
      "a sha256 in upper case" to job {
        putJsonArray("artifacts") { add(artifact("audio_mix") { put("sha256", SHA.uppercase()) }) }
      },
      "a short sha256" to job { putJsonArray("artifacts") { add(artifact("audio_mix") { put("sha256", "abc") }) } },
      "a foreign url" to job {
        putJsonArray("artifacts") { add(artifact("audio_mix") { put("url", "http://198.51.100.7/audio_mix") }) }
      },
      "another job's url" to job {
        putJsonArray("artifacts") {
          add(artifact("audio_mix") { put("url", "/api/phone/jobs/20261010-091503-0000/artifacts/audio_mix") })
        }
      },
      "a role that is not the name's" to job { putJsonArray("artifacts") { add(artifact("audio_mix", role = "video")) } },
      "a ready artifact without its checksum" to job {
        putJsonArray("artifacts") { add(artifact("audio_mix") { put("sha256", null as String?) }) }
      },
      "negative bytes" to job { putJsonArray("artifacts") { add(artifact("audio_mix") { put("bytes", -1) }) } },
      "an artifact twice" to job { putJsonArray("artifacts") { add(artifact("audio_mix")); add(artifact("audio_mix")) } },
      "a key that is not a UUID" to job { put("client_request_id", "../key") },
      "a created time that is not a time" to job { put("created", "yesterday") },
      "a job without an id" to job { remove("id") },
    )
    for ((why, job) in hostile) {
      assertWithMessage(why, library(job)).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
    }
    assertWithMessage("a job id twice", library(job(), job())).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
  }

  @Test
  fun aListingOverSixteenMebibytesIsMalformedEvenWithoutADeclaredLength() = runBlocking<Unit> {
    val padding = "x".repeat(17 * 1024 * 1024)
    val body = listing(job { put("title", padding) })
    server.enqueue(MockResponse.Builder().code(200).chunkedBody(Buffer().writeUtf8(body), 64 * 1024).build())

    assertThat(stack.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
  }

  @Test
  fun anyOtherAnswerOverSixtyFourKibibytesIsMalformed() = runBlocking<Unit> {
    answer(200, buildJsonObject { put("job", job { put("title", "y".repeat(70 * 1024)) }) }.toString())

    assertThat(stack.client.requestLookup(RequestId(UUID_ONE))).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
  }

  @Test
  fun anAnswerThatIsNotTheDocumentedShapeIsMalformed() = runBlocking<Unit> {
    answer(200, "[1, 2, 3]")
    assertThat(stack.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
    answer(200, "not json")
    assertThat(stack.client.health()).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
    answer(200, buildJsonObject { put("service", "something-else"); put("phone_access", true) }.toString())
    assertThat(stack.peers.withCandidate(HostPort(FakeComputer.HOST, server.port)) {
      stack.client.hello(HostPort(FakeComputer.HOST, server.port))
    }).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
  }

  @Test
  fun aStringDetailAndItsCodeAreARefusal() = runBlocking<Unit> {
    answer(409, """{"detail": "Too late to cancel.", "code": "already_finished", "job": ${job()}}""")

    val refused = stack.client.cancel(JobId(JOB_ID)) as ApiResult.Refused

    assertThat(refused.status).isEqualTo(409)
    assertThat(refused.code).isEqualTo("already_finished")
    assertThat(refused.detail).isEqualTo("Too late to cancel.")
    assertThat(refused.job!!.id).isEqualTo(JobId(JOB_ID))
  }

  @Test
  fun aValidationListIsARefusalWithItsMessages() = runBlocking<Unit> {
    answer(422, """{"detail": [{"loc": ["body", "url"], "msg": "field required", "type": "missing"}, {"msg": "bad"}]}""")

    val refused = stack.client.createLink(LinkCreate(RequestId(UUID_ONE), "https://youtu.be/abc", false, null))

    assertThat(refused).isEqualTo(ApiResult.Refused(422, "", "field required; bad"))
  }

  @Test
  fun theOptionalFieldsAndRetryAfterAreCarried() = runBlocking<Unit> {
    answer(409, """{"detail": "Offset differs.", "code": "offset_mismatch", "offset": 4096}""")
    answer(401, """{"detail": "Clock differs.", "code": "clock_skew"}""")
    answer(429, """{"detail": "Too fast.", "code": "rate_limited"}""", "Retry-After" to "17")
    answer(409, """{"detail": "Done.", "code": "already_finished", "job": {"id": "../x"}}""")
    answer(502, "<html>Bad gateway</html>", "Content-Type" to "text/html")

    assertThat((stack.client.library() as ApiResult.Refused).offset).isEqualTo(4096)
    val skew = stack.client.library() as ApiResult.Refused
    assertThat(skew.code).isEqualTo("clock_skew")
    assertThat(skew.serverTime).isNull()
    assertThat((stack.client.library() as ApiResult.Refused).retryAfterS).isEqualTo(17)
    val hostileJob = stack.client.cancel(JobId(JOB_ID)) as ApiResult.Refused
    assertThat(hostileJob.code).isEqualTo("already_finished")
    assertThat(hostileJob.job).isNull()
    assertThat(stack.client.library()).isEqualTo(ApiResult.Refused(502, "", ""))
  }

  @Test
  fun aClockSkewWithItsServerTimeIsRetriedOnceWithTheNewOffset() = runBlocking<Unit> {
    val computerTime = stack.clock.now().epochSecond + 3_600
    answer(401, """{"detail": "Clock differs.", "code": "clock_skew", "server_time": $computerTime}""")
    answer(200, listing())

    assertThat(stack.client.library()).isInstanceOf(ApiResult.Ok::class.java)
    val first = server.takeRequest().headers["Authorization"]!!
    val second = server.takeRequest().headers["Authorization"]!!
    assertThat(tsOf(second) - tsOf(first)).isEqualTo(3_600)
    assertThat(stack.clockOffset.offsetS.value).isEqualTo(3_600)
  }

  @Test
  fun pathParametersOutsideTheirShapeAreNeverSent() = runBlocking<Unit> {
    assertThat(stack.client.job(JobId("../health"))).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
    assertThat(stack.client.requestLookup(RequestId("../library"))).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
    assertThat(server.requestCount).isEqualTo(0)
  }

  private fun tsOf(header: String): Long = Regex("ts=(\\d+)").find(header)!!.groupValues[1].toLong()

  private fun assertWithMessage(why: String, actual: Any?) = com.google.common.truth.Truth.assertWithMessage(why).that(actual)

  private fun job(edit: MutableMap<String, JsonElement>.() -> Unit = {}): JsonObject {
    val base = buildJsonObject {
      put("id", JOB_ID)
      put("revision", REVISION)
      put("status", "done")
      put("stage", null as String?)
      putJsonObject("progress") { put("percent", 100) }
      put("separation", null as String?)
      put("queue_position", null as Int?)
      put("title", "Ordering coffee")
      putJsonObject("source") {
        put("kind", "link")
        put("url", "https://youtu.be/abc123def45")
        put("site", "youtube")
        put("file_name", null as String?)
      }
      putJsonObject("options") {
        put("audio_only", false)
        put("separate_dialogue", true)
      }
      put("device_id", "dev_test")
      put("own", true)
      put("client_request_id", UUID_ONE)
      put("rerun_of", null as String?)
      put("created", "2026-10-10T09:15:03+00:00")
      put("started", "2026-10-10T09:15:04+00:00")
      put("finished", "2026-10-10T09:19:31+00:00")
      put("error", null as String?)
      putJsonObject("summary") {
        put("duration_s", 260.5)
        put("segments", 87)
        put("words", 691)
        putJsonObject("phenomena_counts") {
          put("flapping", 12)
          put("linking", 30)
        }
      }
      putJsonObject("analysis") {
        put("rules_version", 3)
        put("phone_engine", "timit61")
        put("dialogue_separation", true)
      }
      putJsonObject("media") {
        put("kind", "video")
        put("codec", "h264")
        put("width", 1280)
        put("height", 720)
        put("rotation", 0)
        put("start_offset_ms", 0)
        put("duration_s", 260.6)
      }
      put("artifacts", buildJsonArray { add(artifact("audio_mix")) })
    }
    return JsonObject(base.toMutableMap().apply(edit))
  }

  private fun artifact(
    name: String,
    role: String = Wire.ARTIFACT_ROLES[name]?.name?.lowercase() ?: "audio_mix",
    edit: MutableMap<String, JsonElement>.() -> Unit = {},
  ): JsonObject {
    val base = buildJsonObject {
      put("name", name)
      put("role", role)
      put("status", "ready")
      put("bytes", 8_336_600)
      put("sha256", SHA)
      put("revision", SHA.take(16))
      put("content_type", "audio/wav")
      put("url", "/api/phone/jobs/$JOB_ID/artifacts/$name")
      put("error", null as String?)
    }
    return JsonObject(base.toMutableMap().apply(edit))
  }

  private fun MutableMap<String, JsonElement>.put(key: String, value: String?) {
    this[key] = JsonPrimitive(value)
  }

  private fun MutableMap<String, JsonElement>.put(key: String, value: Number?) {
    this[key] = JsonPrimitive(value)
  }

  private fun MutableMap<String, JsonElement>.put(key: String, value: JsonElement) {
    this[key] = value
  }

  private fun MutableMap<String, JsonElement>.putJsonObject(key: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
    this[key] = buildJsonObject(build)
  }

  private fun MutableMap<String, JsonElement>.putJsonArray(key: String, build: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) {
    this[key] = buildJsonArray(build)
  }

  private companion object {
    const val JOB_ID = "20261010-091503-4be1"
    const val REVISION = "5c0e8a1f9b2d7e64"
    const val SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val UUID_ONE = "00000000-0000-4000-8000-000000000001"
  }
}
