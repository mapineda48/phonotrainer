package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.serialization.json.boolean
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Links, cancel, delete, re-run and restarts (server-contract 4.5, 6.5, 6.7, 8.2) and their codes. */
class LinksAndJobActionsTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val credentials = computer.pairDevice()
  private val phone = TestPhone(computer, credentials)

  @After
  fun tearDown() = computer.close()

  @Test
  fun aLinkCreatesAQueuedPhoneJob() {
    val key = TestPhone.newKey()

    val created = phone.createLink("  https://www.youtube.com/shorts/abc123def45  ", key)

    assertThat(created.code).isEqualTo(201)
    assertThat(created.json["created"]!!.prim().boolean).isTrue()
    val job = created.job
    assertThat(job.str("status")).isEqualTo("queued")
    assertThat(job.obj("source").str("url")).isEqualTo("https://www.youtube.com/shorts/abc123def45")
    assertThat(job.obj("source").str("site")).isEqualTo("youtube_shorts")
    assertThat(job.str("client_request_id")).isEqualTo(key)
    assertThat(job["title"]!!.toString()).isEqualTo("null")
  }

  @Test
  fun theSameKeyTwiceGivesTheSameJob() {
    val key = TestPhone.newKey()
    val first = phone.createLink("https://youtu.be/abc123def45", key)

    val again = phone.createLink("https://youtu.be/abc123def45", key.uppercase())

    assertThat(again.code).isEqualTo(200)
    assertThat(again.json["created"]!!.prim().boolean).isFalse()
    assertThat(again.jobId).isEqualTo(first.jobId)
    assertThat(computer.jobsWithKey(RequestId(key))).hasSize(1)
  }

  @Test
  fun aKeyReusedForAnotherLinkIsAConflict() {
    val key = TestPhone.newKey()
    phone.createLink("https://youtu.be/abc123def45", key)

    val response = phone.createLink("https://youtu.be/zzz", key)

    assertThat(response.code).isEqualTo(409)
    assertThat(response.errorCode).isEqualTo("key_conflict")
  }

  @Test
  fun keysAreScopedToTheDevice() {
    val key = TestPhone.newKey()
    phone.createLink("https://youtu.be/abc123def45", key)
    val other = TestPhone(computer, computer.pairDevice("Other"))

    assertThat(other.createLink("https://youtu.be/abc123def45", key).code).isEqualTo(201)
    assertThat(computer.jobsWithKey(RequestId(key))).hasSize(2)
  }

  @Test
  fun linksThatAreNotHttpUrlsAreInvalid() {
    val bad = listOf(
      "ftp://example.com/video", "https://user:secret@example.com/v", "not a link", "",
      "https://example.com/" + "a".repeat(2_049),
    )
    for (url in bad) {
      val response = phone.createLink(url)
      assertThat(response.code).isEqualTo(400)
      assertThat(response.errorCode).isEqualTo("invalid_url")
    }
  }

  @Test
  fun privateAndLocalTargetsAreRefused() {
    computer.linkHost("rebind.example", LinkHostAnswer.REFUSED)
    // hygiene: test-vector begin (SSRF targets of server-contract 4.5, synthetic)
    val refused = listOf(
      "http://127.0.0.1/x", "http://10.1.2.3/x", "http://192.168.1.1/x", "http://169.254.169.254/latest",
      "http://100.64.0.1/x", "http://[::1]/x", "http://[fd00::1]/x", "http://[64:ff9b::a00:1]/x",
      "http://[2002:a00:1::1]/x", "http://[2001:0:1::1]/x", "http://[::ffff:10.0.0.1]/x", "http://224.0.0.1/x",
      "https://rebind.example/watch",
    )
    for (url in refused) {
      val response = phone.createLink(url)
      assertThat(response.errorCode).isEqualTo("refused_address")
      assertThat(response.code).isEqualTo(400)
    }
    assertThat(phone.createLink("http://8.8.8.8/x").code).isEqualTo(201)
    // hygiene: test-vector end
  }

  @Test
  fun anUnresolvableHostCannotBeChecked() {
    computer.linkHost("nowhere.example", LinkHostAnswer.UNRESOLVABLE)

    val response = phone.createLink("https://nowhere.example/v")

    assertThat(response.code).isEqualTo(503)
    assertThat(response.errorCode).isEqualTo("cannot_check_link")
  }

  @Test
  fun applyThenDropCreatesTheJobAndLosesTheAnswerThenTheRetryFindsIt() {
    val key = TestPhone.newKey()
    computer.applyThenDrop("/api/phone/jobs/link")

    assertThrows(IOException::class.java) { phone.createLink("https://youtu.be/abc123def45", key) }
    val retry = phone.createLink("https://youtu.be/abc123def45", key)

    assertThat(retry.code).isEqualTo(200)
    assertThat(computer.jobsWithKey(RequestId(key))).hasSize(1)
    assertThat(computer.requests.first().status).isNull()
  }

  @Test
  fun cancellingAQueuedJobCancelsIt() {
    val id = phone.createLink("https://youtu.be/abc123def45").jobId

    val response = phone.post("/api/phone/jobs/$id/cancel")

    assertThat(response.code).isEqualTo(200)
    assertThat(response.job.str("status")).isEqualTo("cancelled")
    assertThat(response.job.obj("error").str("code")).isEqualTo("cancelled")
  }

  @Test
  fun aDeferredCancelOfARunningJobLandsAtItsNextProgress() {
    computer.deferRunningCancel = true
    val id = phone.createLink("https://youtu.be/abc123def45").jobId
    computer.progress(JobId(id), JobStage.TRANSCRIBE, 40)

    val response = phone.post("/api/phone/jobs/$id/cancel")
    assertThat(response.job.str("status")).isEqualTo("running")

    computer.finishJob(JobId(id))
    assertThat(computer.job(JobId(id))!!.status).isEqualTo(JobStatus.CANCELLED)
  }

  @Test
  fun cancellingADoneJobIsAlreadyFinishedWithTheJob() {
    val id = computer.addJob(JobSeed(deviceId = credentials.deviceId))

    val response = phone.post("/api/phone/jobs/${id.value}/cancel")

    assertThat(response.code).isEqualTo(409)
    assertThat(response.errorCode).isEqualTo("already_finished")
    assertThat(response.json.obj("job").str("status")).isEqualTo("done")
  }

  @Test
  fun cancellingAFailedJobIsANoOp() {
    val id = computer.addJob(JobSeed(status = JobStatus.ERROR, deviceId = credentials.deviceId, errorCode = "network"))

    val response = phone.post("/api/phone/jobs/${id.value}/cancel")

    assertThat(response.code).isEqualTo(200)
    assertThat(response.job.obj("error").str("code")).isEqualTo("network")
  }

  @Test
  fun actionsOnOtherJobsAreNotOwnAndOnMissingOnesUnknown() {
    val browser = computer.addJob(JobSeed(status = JobStatus.ERROR))
    for (path in listOf("cancel", "rerun")) {
      val body = if (path == "rerun") """{"client_request_id": "${TestPhone.newKey()}"}""" else null
      val response = phone.post("/api/phone/jobs/${browser.value}/$path", body)
      assertThat(response.code).isEqualTo(403)
      assertThat(response.errorCode).isEqualTo("not_own_job")
    }
    assertThat(phone.delete("/api/phone/jobs/${browser.value}").errorCode).isEqualTo("not_own_job")
    assertThat(phone.post("/api/phone/jobs/20261010-091503-4be1/cancel").errorCode).isEqualTo("unknown_job")
    assertThat(phone.delete("/api/phone/jobs/20261010-091503-4be1").errorCode).isEqualTo("unknown_job")
  }

  @Test
  fun onlyFailedOrCancelledJobsCanBeDeleted() {
    val queued = phone.createLink("https://youtu.be/abc123def45").jobId
    val done = computer.addJob(JobSeed(deviceId = credentials.deviceId))
    val failed = computer.addJob(JobSeed(status = JobStatus.ERROR, deviceId = credentials.deviceId))

    assertThat(phone.delete("/api/phone/jobs/$queued").errorCode).isEqualTo("not_deletable")
    assertThat(phone.delete("/api/phone/jobs/${done.value}").errorCode).isEqualTo("not_deletable")
    assertThat(phone.delete("/api/phone/jobs/${failed.value}").code).isEqualTo(204)
    assertThat(computer.job(failed)).isNull()
  }

  @Test
  fun rerunOfALinkReplacesTheOldJobWithAudioOnly() {
    val old = phone.createLink("https://youtu.be/abc123def45").jobId
    computer.failJob(JobId(old), "download_forbidden")
    val key = TestPhone.newKey()

    val response = phone.rerun(old, key, audioOnly = true)

    assertThat(response.code).isEqualTo(201)
    assertThat(response.json.str("replaced")).isEqualTo(old)
    assertThat(response.job.str("rerun_of")).isEqualTo(old)
    assertThat(response.job.obj("options")["audio_only"]!!.prim().boolean).isTrue()
    assertThat(computer.job(JobId(old))).isNull()

    val repeat = phone.rerun(old, key, audioOnly = true)
    assertThat(repeat.code).isEqualTo(200)
    assertThat(repeat.jobId).isEqualTo(response.jobId)
    assertThat(repeat.json.str("replaced")).isEqualTo(old)
  }

  @Test
  fun rerunOfAnUploadMovesTheFile() {
    val bytes = FakeMediaBytes.video(salt = 9)
    val old = phone.upload(bytes).jobId
    computer.failJob(JobId(old), "processing_failed")

    val created = phone.rerun(old)

    assertThat(created.code).isEqualTo(201)
    assertThat(computer.job(JobId(created.jobId))!!.sourceBytes).isEqualTo(bytes)
    assertThat(phone.rerun(created.jobId).errorCode).isEqualTo("not_rerunnable")
  }

  @Test
  fun rerunRefusalsHaveTheirCodes() {
    val upload = phone.upload(FakeMediaBytes.video(salt = 3)).jobId
    computer.failJob(JobId(upload), "interrupted")
    val invalid = phone.rerun(upload, audioOnly = true)
    assertThat(invalid.code).isEqualTo(400)
    assertThat(invalid.errorCode).isEqualTo("invalid_option")

    computer.removeSource(JobId(upload))
    val gone = phone.rerun(upload)
    assertThat(gone.code).isEqualTo(410)
    assertThat(gone.errorCode).isEqualTo("source_gone")
    assertThat(computer.job(JobId(upload))).isNotNull()

    val queued = phone.createLink("https://youtu.be/abc123def45").jobId
    assertThat(phone.rerun(queued).errorCode).isEqualTo("not_rerunnable")

    val usedKey = TestPhone.newKey()
    phone.createLink("https://youtu.be/other", usedKey)
    computer.failJob(JobId(queued), "network")
    assertThat(phone.rerun(queued, usedKey).errorCode).isEqualTo("key_conflict")
  }

  @Test
  fun rerunChecksTheLinkAgain() {
    val id = phone.createLink("https://moved.example/v").jobId
    computer.failJob(JobId(id), "network")

    computer.linkHost("moved.example", LinkHostAnswer.REFUSED)
    assertThat(phone.rerun(id).errorCode).isEqualTo("refused_address")
    computer.linkHost("moved.example", LinkHostAnswer.UNRESOLVABLE)
    assertThat(phone.rerun(id).errorCode).isEqualTo("cannot_check_link")
  }

  @Test
  fun aRestartInterruptsQueuedAndRunningJobs() {
    val running = phone.createLink("https://youtu.be/a1").jobId
    val queued = phone.createLink("https://youtu.be/a2").jobId
    val done = computer.addFinishedClip()
    computer.progress(JobId(running), JobStage.PHONES, 70)

    computer.restart()
    phone.client.connectionPool.evictAll() // the restart closed every connection, as a real restart does

    for (id in listOf(running, queued)) {
      val job = phone.get("/api/phone/jobs/$id").json
      assertThat(job.str("status")).isEqualTo("error")
      assertThat(job.obj("error").str("code")).isEqualTo("interrupted")
    }
    assertThat(computer.job(done)!!.status).isEqualTo(JobStatus.DONE)
    assertThat(phone.rerun(running).code).isEqualTo(201)
  }

  @Test
  fun deletionOnTheComputerRemovesTheJob() {
    val id = computer.addFinishedClip()

    computer.deleteJob(id)

    assertThat(phone.get("/api/phone/jobs/${id.value}").errorCode).isEqualTo("unknown_job")
  }
}
