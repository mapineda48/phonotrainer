package com.mapineda48.phonotrainer.core.network.client

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.Created
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.RerunBody
import com.mapineda48.phonotrainer.core.network.api.UploadChunk
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.network.api.UploadState
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeMediaBytes
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import com.mapineda48.phonotrainer.core.testing.computer.JobStatus
import com.mapineda48.phonotrainer.core.testing.computer.LinkHostAnswer
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.mapineda48.phonotrainer.core.testing.computer.SourceKind as FakeSourceKind

/**
 * `ComputerClient` against the fake computer over HTTP, one test per route of server-contract 6.10 with the answers
 * that route documents, each produced by the computer's own rules (its knobs where the rule needs a state: a full
 * disk, a revoked phone, a suspended network); then every request-level code of 8.1 and the answers every signed
 * route may give (401, 403, 400 bad_host, 5xx), so each reaches the caller as `Refused` with its status and code. The
 * CR-01 routes of 6.11 and 6.12 are v1-complete (WP-28).
 */
class ConformanceTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val computer = FakeComputer().start()
  private val stack = TestStack(computer)
  private val client get() = stack.client
  private var device = DeviceId("")

  @Before
  fun pairThePhone() = runBlocking {
    device = DeviceId(stack.pair().deviceId)
  }

  @After
  fun close() {
    stack.close()
    computer.close()
  }

  private fun key(): RequestId = RequestId(UUID.randomUUID().toString())

  private fun file(bytes: ByteArray, name: String = "clip.mp4"): File = File(folder.newFolder(), name).apply { writeBytes(bytes) }

  private fun refusal(result: ApiResult<*>): Pair<Int, String> {
    assertWithMessage("expected a refusal").that(result).isInstanceOf(ApiResult.Refused::class.java)
    return (result as ApiResult.Refused).let { it.status to it.code }
  }

  private fun <T> ok(result: ApiResult<T>): ApiResult.Ok<T> {
    assertWithMessage("expected an answer").that(result).isInstanceOf(ApiResult.Ok::class.java)
    @Suppress("UNCHECKED_CAST")
    return result as ApiResult.Ok<T>
  }

  /** A link this phone sent, as the computer created it. */
  private suspend fun ownLink(url: String = "https://youtu.be/abc123def45"): RemoteJob =
    ok(client.createLink(LinkCreate(key(), url, audioOnly = false, separateDialogue = null))).value.value

  /** A file this phone uploaded, as the computer created it. */
  private suspend fun ownUpload(bytes: ByteArray = FakeMediaBytes.video(salt = 3)): RemoteJob {
    val upload = file(bytes)
    val id = key()
    ok(client.openUpload(UploadDeclaration(id, upload.name, upload.length(), Sha256.of(upload).sha256, null)))
    return ok(client.putUpload(id, upload) {}).value.value
  }

  // --- 6.2 identity, pairing, health -------------------------------------------------------------------------------

  @Test
  fun hello() = runBlocking<Unit> {
    val withProof = ok(client.hello(computer.address, device)).value
    assertThat(withProof.serverId).isEqualTo(computer.serverId)
    assertThat(withProof.proofValid).isTrue()
    assertThat(withProof.revoked).isFalse()
    assertThat(withProof.apiVersion).isEqualTo(6)

    var limited: ApiResult<*>? = null
    repeat(CHALLENGES_PER_MINUTE + 1) { if (limited == null) limited = client.hello(computer.address, device).takeIf { it is ApiResult.Refused } }
    assertThat(refusal(limited!!)).isEqualTo(429 to "rate_limited")
    assertThat((limited as ApiResult.Refused).retryAfterS).isNotNull()

    computer.phoneAccessOff()
    val off = ok(client.hello(computer.address)).value
    assertThat(off.phoneAccess).isFalse()
    assertThat(off.serverId).isNull()

    computer.phoneAccessOn()
    computer.lanForbidden()
    assertThat(refusal(client.hello(computer.address))).isEqualTo(403 to "lan_forbidden")
  }

  @Test
  fun pair() = runBlocking<Unit> {
    suspend fun pairWith(code: String, appVersion: Int = 1) = stack.peers.withCandidate(computer.address) {
      client.pair(computer.address, PairRequest(code, "Second phone", appVersion, "1.0.0", null, null))
    }
    // Each group starts a fresh pairing-attempt window, so the rate limit (10 a minute) answers only where asked.
    fun nextWindow() = computer.clock.advance(Duration.ofSeconds(RATE_WINDOW_S))

    val granted = ok(pairWith(computer.newPairingCode()))
    assertThat(granted.status).isEqualTo(201)
    assertThat(granted.value.serverId).isEqualTo(computer.serverId)
    assertThat(granted.value.libraryId).isEqualTo(computer.libraryId)
    assertThat(String(granted.value.token)).startsWith("pt1_")
    assertThat(refusal(pairWith("000000"))).isEqualTo(403 to "code_invalid")
    assertThat(refusal(pairWith("12"))).isEqualTo(400 to "invalid_request")

    nextWindow()
    val expiring = computer.newPairingCode()
    computer.clock.advance(Duration.ofMinutes(6))
    assertThat(refusal(pairWith(expiring))).isEqualTo(403 to "code_expired")

    nextWindow()
    val locked = computer.newPairingCode()
    val wrong = if (locked == "000001") "000002" else "000001"
    repeat(5) { pairWith(wrong) }
    assertThat(refusal(pairWith(locked))).isEqualTo(403 to "code_locked")

    nextWindow()
    computer.minAppVersion = 2
    assertThat(refusal(pairWith(computer.newPairingCode(), appVersion = 1))).isEqualTo(426 to "app_too_old")
    computer.minAppVersion = 1
    computer.phoneAccessOff()
    assertThat(refusal(pairWith(computer.newPairingCode()))).isEqualTo(403 to "phone_access_off")
    computer.phoneAccessOn()

    nextWindow()
    computer.newPairingCode()
    var limited: ApiResult<*>? = null
    repeat(PAIRING_ATTEMPTS) { if (limited == null) limited = pairWith(wrong).takeIf { refusal(it).first == 429 } }
    assertThat(refusal(limited!!)).isEqualTo(429 to "rate_limited")
    assertThat((limited as ApiResult.Refused).retryAfterS).isNotNull()
  }

  @Test
  fun health() = runBlocking<Unit> {
    computer.addJob(JobSeed(status = JobStatus.RUNNING))
    computer.addJob(JobSeed(status = JobStatus.QUEUED))

    val health = ok(client.health()).value
    assertThat(health.deviceId).isEqualTo(device)
    assertThat(health.analyzing).isEqualTo(1)
    assertThat(health.inLine).isEqualTo(1)
    assertThat(health.capabilities.downloaderVersion).isEqualTo("2026.8.19")
    assertThat(health.capabilities.renditions).isFalse()

    computer.revokeDevice()
    assertThat(refusal(client.health())).isEqualTo(401 to "credential_rejected")
  }

  @Test
  fun unpairSelf() = runBlocking<Unit> {
    assertThat(client.unpairSelf()).isEqualTo(ApiResult.Ok(Unit, 204))
    assertThat(computer.pairedDevices.single().revoked).isTrue()
    assertThat(refusal(client.unpairSelf())).isEqualTo(401 to "credential_rejected")
  }

  // --- 6.3 listing, job, request lookup ----------------------------------------------------------------------------

  @Test
  fun libraryJobAndRequestLookup() = runBlocking<Unit> {
    val browser = computer.addFinishedClip("From the browser")
    val mine = ownLink()

    val library = ok(client.library()).value
    assertThat(library.jobs.map { it.id }).containsExactly(browser, mine.id)
    assertThat(library.jobs.single { it.id == browser }.own).isFalse()
    assertThat(library.jobs.single { it.id == browser }.deviceId).isNull()
    assertThat(library.jobs.single { it.id == mine.id }.own).isTrue()

    assertThat(ok(client.job(browser)).value.artifacts).isNotEmpty()
    assertThat(refusal(client.job(JobId("20000101-000000-0000")))).isEqualTo(404 to "unknown_job")

    assertThat(ok(client.requestLookup(mine.clientRequestId!!)).value.id).isEqualTo(mine.id)
    assertThat(refusal(client.requestLookup(key()))).isEqualTo(404 to "unknown_request")
  }

  @Test
  fun reference() = runBlocking<Unit> {
    assertThat(ok(client.reference()).value).isEqualTo(computer.referenceJson)
  }

  // --- 6.5 links --------------------------------------------------------------------------------------------------

  @Test
  fun createLink() = runBlocking<Unit> {
    val id = key()
    val link = LinkCreate(id, "https://youtu.be/abc123def45", audioOnly = true, separateDialogue = false)

    val created = ok(client.createLink(link))
    assertThat(created.status).isEqualTo(201)
    assertThat(created.value.created).isTrue()
    assertThat(created.value.value.audioOnly).isTrue()
    assertThat(created.value.value.clientRequestId).isEqualTo(id)
    val again = ok(client.createLink(link))
    assertThat(again.value).isEqualTo(Created(again.value.value, created = false))
    assertThat(computer.jobsWithKey(id)).hasSize(1)

    assertThat(refusal(client.createLink(link.copy(url = "https://youtu.be/another1")))).isEqualTo(409 to "key_conflict")
    assertThat(refusal(client.createLink(LinkCreate(key(), "not a link", false, null)))).isEqualTo(400 to "invalid_url")
    computer.linkHost("private.example", LinkHostAnswer.REFUSED)
    computer.linkHost("unknown.example", LinkHostAnswer.UNRESOLVABLE)
    assertThat(refusal(client.createLink(LinkCreate(key(), "https://private.example/v", false, null))))
      .isEqualTo(400 to "refused_address")
    assertThat(refusal(client.createLink(LinkCreate(key(), "https://unknown.example/v", false, null))))
      .isEqualTo(503 to "cannot_check_link")
  }

  // --- 6.6 uploads ------------------------------------------------------------------------------------------------

  @Test
  fun uploadDeclarations() = runBlocking<Unit> {
    val upload = file(FakeMediaBytes.video(salt = 1))
    val sha = Sha256.of(upload).sha256
    val id = key()
    val declaration = UploadDeclaration(id, upload.name, upload.length(), sha, separateDialogue = true)

    val open = ok(client.openUpload(declaration))
    assertThat(open.status).isEqualTo(201)
    val session = open.value as UploadState.Open
    assertThat(session.offset).isEqualTo(0)
    assertThat(session.bytes).isEqualTo(upload.length())
    assertThat(session.resumable).isFalse()
    assertThat(ok(client.openUpload(declaration)).status).isEqualTo(200)

    assertThat(refusal(client.openUpload(declaration.copy(sha256 = "0".repeat(64))))).isEqualTo(409 to "key_conflict")
    assertThat(refusal(client.openUpload(UploadDeclaration(key(), "notes.txt", 10, sha, null))))
      .isEqualTo(400 to "unsupported_type")
    computer.maxUploadBytes = 100
    assertThat(refusal(client.openUpload(UploadDeclaration(key(), "big.mp4", 1_000, sha, null)))).isEqualTo(413 to "too_large")
    computer.maxUploadBytes = 2L * 1024 * 1024 * 1024
    computer.lowOnSpace()
    assertThat(refusal(client.openUpload(UploadDeclaration(key(), "a.mp4", 10, sha, null)))).isEqualTo(507 to "no_space")
    computer.lowOnSpace(false)
    repeat(3) { ok(client.openUpload(UploadDeclaration(key(), "more$it.mp4", 10, sha, null))) }
    assertThat(refusal(client.openUpload(UploadDeclaration(key(), "fifth.mp4", 10, sha, null)))).isEqualTo(409 to "upload_busy")

    assertThat(client.deleteUpload(id)).isEqualTo(ApiResult.Ok(Unit, 204))
    assertThat(client.deleteUpload(id)).isEqualTo(ApiResult.Ok(Unit, 204))
    assertThat(computer.uploadSessions.map { it.clientRequestId }).doesNotContain(id)
  }

  @Test
  fun uploadBodies() = runBlocking<Unit> {
    val bytes = FakeMediaBytes.video(salt = 2)
    val upload = file(bytes)
    val id = key()
    ok(client.openUpload(UploadDeclaration(id, upload.name, upload.length(), Sha256.of(upload).sha256, null)))
    val progress = mutableListOf<Long>()

    val created = ok(client.putUpload(id, upload) { progress += it })
    assertThat(created.status).isEqualTo(201)
    assertThat(created.value.created).isTrue()
    assertThat(progress.last()).isEqualTo(upload.length())
    assertThat(computer.job(created.value.value.id)!!.sourceBytes).isEqualTo(bytes)
    assertThat(ok(client.putUpload(id, upload) {}).value.created).isFalse()
    val done = ok(client.openUpload(UploadDeclaration(id, upload.name, upload.length(), Sha256.of(upload).sha256, null)))
    assertThat((done.value as UploadState.Done).job.id).isEqualTo(created.value.value.id)

    assertThat(refusal(client.putUpload(key(), upload) {})).isEqualTo(404 to "unknown_upload")
    val damaged = key()
    ok(client.openUpload(UploadDeclaration(damaged, upload.name, upload.length(), "0".repeat(64), null)))
    assertThat(refusal(client.putUpload(damaged, upload) {})).isEqualTo(422 to "checksum_mismatch")
    val longer = key()
    ok(client.openUpload(UploadDeclaration(longer, upload.name, upload.length() + 1, Sha256.of(upload).sha256, null)))
    assertThat(refusal(client.putUpload(longer, upload) {})).isEqualTo(400 to "length_mismatch")
  }

  @Test
  fun resumableUploads() = runBlocking<Unit> {
    computer.enableCompleteTier()
    val bytes = FakeMediaBytes.video(salt = 4)
    val upload = file(bytes)
    val id = key()
    val half = upload.length() / 2
    val session = ok(client.openUpload(UploadDeclaration(id, upload.name, upload.length(), Sha256.of(upload).sha256, null)))
    assertThat((session.value as UploadState.Open).resumable).isTrue()

    assertThat(ok(client.patchUpload(id, upload, 0, half)).value).isEqualTo(UploadChunk.Accepted(half))
    assertThat((ok(client.uploadStatus(id)).value as UploadState.Open).offset).isEqualTo(half)
    val mismatch = client.patchUpload(id, upload, 0, half) as ApiResult.Refused
    assertThat(mismatch.status to mismatch.code).isEqualTo(409 to "offset_mismatch")
    assertThat(mismatch.offset).isEqualTo(half)
    computer.capabilities = computer.capabilities.copy(maxChunkBytes = 16)
    assertThat(refusal(client.patchUpload(id, upload, half, 17))).isEqualTo(413 to "chunk_too_large")
    computer.capabilities = computer.capabilities.copy(maxChunkBytes = 8L * 1024 * 1024)

    val finished = ok(client.patchUpload(id, upload, half, upload.length() - half)).value as UploadChunk.Finished
    assertThat(finished.job.created).isTrue()
    assertThat(computer.job(finished.job.value.id)!!.sourceBytes).isEqualTo(bytes)
    assertThat(refusal(client.uploadStatus(key()))).isEqualTo(404 to "unknown_upload")
  }

  // --- 6.7 cancel, delete, re-run ---------------------------------------------------------------------------------

  @Test
  fun cancel() = runBlocking<Unit> {
    val queued = ownLink()
    assertThat(ok(client.cancel(queued.id)).value.status).isEqualTo(RemoteStatus.CANCELLED)
    assertThat(ok(client.cancel(queued.id)).value.status).isEqualTo(RemoteStatus.CANCELLED)

    val finished = ownLink("https://youtu.be/finished01")
    computer.finishJob(finished.id, "Finished")
    val late = client.cancel(finished.id) as ApiResult.Refused
    assertThat(late.status to late.code).isEqualTo(409 to "already_finished")
    assertThat(late.job!!.status).isEqualTo(RemoteStatus.DONE)

    assertThat(refusal(client.cancel(computer.addFinishedClip("Browser")))).isEqualTo(403 to "not_own_job")
    assertThat(refusal(client.cancel(JobId("20000101-000000-0000")))).isEqualTo(404 to "unknown_job")
  }

  @Test
  fun deleteJob() = runBlocking<Unit> {
    val cancelled = ownLink()
    ok(client.cancel(cancelled.id))
    assertThat(client.deleteJob(cancelled.id)).isEqualTo(ApiResult.Ok(Unit, 204))
    assertThat(computer.job(cancelled.id)).isNull()

    val finished = ownLink("https://youtu.be/finished02")
    computer.finishJob(finished.id)
    assertThat(refusal(client.deleteJob(finished.id))).isEqualTo(409 to "not_deletable")
    val browser = computer.addJob(JobSeed(status = JobStatus.ERROR, errorCode = "network"))
    assertThat(refusal(client.deleteJob(browser))).isEqualTo(403 to "not_own_job")
    assertThat(refusal(client.deleteJob(JobId("20000101-000000-0000")))).isEqualTo(404 to "unknown_job")
  }

  @Test
  fun rerun() = runBlocking<Unit> {
    val failed = ownLink()
    computer.failJob(failed.id, "network")
    val id = key()

    val again = ok(client.rerun(failed.id, RerunBody(id, audioOnly = true)))
    assertThat(again.status).isEqualTo(201)
    assertThat(again.value.created).isTrue()
    assertThat(again.value.value.rerunOf).isEqualTo(failed.id)
    assertThat(again.value.value.audioOnly).isTrue()
    assertThat(ok(client.rerun(failed.id, RerunBody(id, audioOnly = true))).value.created).isFalse()

    val done = ownLink("https://youtu.be/finished03")
    computer.finishJob(done.id)
    assertThat(refusal(client.rerun(done.id, RerunBody(key(), null)))).isEqualTo(409 to "not_rerunnable")
    val browser = computer.addJob(JobSeed(status = JobStatus.ERROR, errorCode = "network"))
    assertThat(refusal(client.rerun(browser, RerunBody(key(), null)))).isEqualTo(403 to "not_own_job")
    assertThat(refusal(client.rerun(JobId("20000101-000000-0000"), RerunBody(key(), null)))).isEqualTo(404 to "unknown_job")

    val other = ownLink("https://youtu.be/another02")
    computer.failJob(other.id, "network")
    assertThat(refusal(client.rerun(other.id, RerunBody(id, null)))).isEqualTo(409 to "key_conflict")

    computer.linkHost("private.example", LinkHostAnswer.PUBLIC)
    val moved = ownLink("https://private.example/v")
    computer.failJob(moved.id, "network")
    computer.linkHost("private.example", LinkHostAnswer.REFUSED)
    assertThat(refusal(client.rerun(moved.id, RerunBody(key(), null)))).isEqualTo(400 to "refused_address")
    computer.linkHost("private.example", LinkHostAnswer.UNRESOLVABLE)
    assertThat(refusal(client.rerun(moved.id, RerunBody(key(), null)))).isEqualTo(503 to "cannot_check_link")

    val uploaded = ownUpload()
    computer.failJob(uploaded.id, "processing_failed")
    assertThat(refusal(client.rerun(uploaded.id, RerunBody(key(), audioOnly = true)))).isEqualTo(400 to "invalid_option")
    computer.removeSource(uploaded.id)
    assertThat(refusal(client.rerun(uploaded.id, RerunBody(key(), null)))).isEqualTo(410 to "source_gone")
  }

  // --- v1-complete: phone video, record yourself (6.4.4, 6.9) -------------------------------------------------------

  @Test
  fun phoneVideoAndRecordYourself() = runBlocking<Unit> {
    computer.enableCompleteTier()
    val video = computer.addFinishedClip("Video", video = true)
    val audio = computer.addFinishedClip("Audio", video = false)
    val queued = computer.addJob(JobSeed(status = JobStatus.QUEUED))

    assertThat(ok(client.requestPhoneVideo(video)).value).isAnyOf(RemoteArtifactStatus.PREPARING, RemoteArtifactStatus.READY)
    assertThat(refusal(client.requestPhoneVideo(audio))).isEqualTo(409 to "not_video")
    assertThat(refusal(client.requestPhoneVideo(queued))).isEqualTo(409 to "not_ready")

    assertThat(ok(client.contour(video, 0.5, 1.5)).value).contains("{")
    val take = file(FakeMediaBytes.wav(1.0), "take.wav")
    assertThat(ok(client.compare(video, take, "audio/wav", 0.5, 1.5)).value).contains("comparison")
    assertThat(refusal(client.compare(video, take, "text/plain", 0.5, 1.5))).isEqualTo(415 to "unsupported_type")
  }

  // --- answers every signed route may give (6.10), and every request-level code (8.1) ------------------------------

  /** Each signed route, called the way the phone calls it, with the path and method the computer's knobs name. */
  private val signedRoutes: List<Triple<String, String, suspend () -> ApiResult<*>>> by lazy {
    val job = JobId("20261010-091503-4be1")
    listOf(
      Triple("GET", "/api/phone/health") { client.health() },
      Triple("DELETE", "/api/phone/devices/self") { client.unpairSelf() },
      Triple("GET", "/api/phone/library") { client.library() },
      Triple("GET", "/api/phone/jobs/*") { client.job(job) },
      Triple("GET", "/api/phone/requests/*") { client.requestLookup(key()) },
      Triple("GET", "/api/phone/reference") { client.reference() },
      Triple("POST", "/api/phone/jobs/link") { client.createLink(LinkCreate(key(), "https://youtu.be/abc", false, null)) },
      Triple("POST", "/api/phone/uploads") { client.openUpload(UploadDeclaration(key(), "a.mp4", 1, "0".repeat(64), null)) },
      Triple("PUT", "/api/phone/uploads/*") { client.putUpload(key(), file(byteArrayOf(1))) {} },
      Triple("DELETE", "/api/phone/uploads/*") { client.deleteUpload(key()) },
      Triple("POST", "/api/phone/jobs/*/cancel") { client.cancel(job) },
      Triple("DELETE", "/api/phone/jobs/*") { client.deleteJob(job) },
      Triple("POST", "/api/phone/jobs/*/rerun") { client.rerun(job, RerunBody(key(), null)) },
    )
  }

  @Test
  fun everySignedRouteReportsTheAnswersAnyRouteMayGive() = runBlocking<Unit> {
    val shared = listOf(
      401 to "credential_rejected",
      401 to "clock_skew",
      403 to "phone_access_off",
      403 to "lan_forbidden",
      400 to "bad_host",
      400 to "invalid_request",
      500 to "server_error",
      503 to "",
    )
    for ((method, path, call) in signedRoutes) {
      for ((status, code) in shared) {
        computer.failNext(path, status, code.ifEmpty { null }, method = method)
        assertWithMessage("$method $path answering $status $code").that(refusal(call())).isEqualTo(status to code)
      }
    }
  }

  @Test
  fun everyRequestLevelCodeReachesTheCallerWithItsStatus() = runBlocking<Unit> {
    // The codes of server-contract 8.1 for v1-core routes, each answered by the route that documents it.
    val codes = listOf(
      Triple("GET", "/api/phone/hello", 404 to "unknown_route"),
      Triple("POST", "/api/phone/pair", 403 to "code_invalid"),
      Triple("POST", "/api/phone/pair", 403 to "code_expired"),
      Triple("POST", "/api/phone/pair", 403 to "code_locked"),
      Triple("POST", "/api/phone/pair", 429 to "rate_limited"),
      Triple("POST", "/api/phone/pair", 426 to "app_too_old"),
      Triple("POST", "/api/phone/jobs/link", 413 to "too_large"),
      Triple("PUT", "/api/phone/uploads/*", 411 to "length_required"),
      Triple("PUT", "/api/phone/uploads/*", 400 to "length_mismatch"),
      Triple("PUT", "/api/phone/uploads/*", 422 to "checksum_mismatch"),
      Triple("PUT", "/api/phone/uploads/*", 507 to "no_space"),
      Triple("PUT", "/api/phone/uploads/*", 409 to "upload_busy"),
      Triple("PUT", "/api/phone/uploads/*", 404 to "unknown_upload"),
      Triple("POST", "/api/phone/uploads", 400 to "unsupported_type"),
      Triple("POST", "/api/phone/uploads", 409 to "key_conflict"),
      Triple("POST", "/api/phone/jobs/link", 400 to "invalid_url"),
      Triple("POST", "/api/phone/jobs/link", 400 to "refused_address"),
      Triple("POST", "/api/phone/jobs/link", 503 to "cannot_check_link"),
      Triple("GET", "/api/phone/jobs/*", 404 to "unknown_job"),
      Triple("GET", "/api/phone/requests/*", 404 to "unknown_request"),
      Triple("POST", "/api/phone/jobs/*/cancel", 403 to "not_own_job"),
      Triple("POST", "/api/phone/jobs/*/cancel", 409 to "already_finished"),
      Triple("DELETE", "/api/phone/jobs/*", 409 to "not_deletable"),
      Triple("POST", "/api/phone/jobs/*/rerun", 409 to "not_rerunnable"),
      Triple("POST", "/api/phone/jobs/*/rerun", 410 to "source_gone"),
      Triple("POST", "/api/phone/jobs/*/rerun", 400 to "invalid_option"),
    )
    val calls: Map<Pair<String, String>, suspend () -> ApiResult<*>> = signedRoutes.associate { (m, p, c) -> (m to p) to c } +
      mapOf(
        ("GET" to "/api/phone/hello") to suspend { client.hello(computer.address, device) },
        ("POST" to "/api/phone/pair") to suspend {
          stack.peers.withCandidate(computer.address) {
            client.pair(computer.address, PairRequest("482913", "Phone", 1, "1.0.0", null, null))
          }
        },
      )
    for ((method, path, expected) in codes) {
      val (status, code) = expected
      computer.failNext(path, status, code, method = method, retryAfterS = if (status == 429) 30 else null)
      val answer = calls.getValue(method to path)()
      assertWithMessage("$method $path answering $status $code").that(refusal(answer)).isEqualTo(expected)
      if (status == 429) assertThat((answer as ApiResult.Refused).retryAfterS).isEqualTo(30)
    }
  }

  private companion object {
    const val CHALLENGES_PER_MINUTE = 20
    const val PAIRING_ATTEMPTS = 12
    const val RATE_WINDOW_S = 61L
  }
}
