package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.RerunBody
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeClockOffset
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeVerifiedPeers
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Test
import java.time.Duration
import com.mapineda48.phonotrainer.core.domain.model.remote.MediaKind as RemoteMediaKind
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind as RemoteSourceKind

/**
 * [FakeComputerClient]: every core route of server-contract 6.10 in memory, its answers mapped to the frozen
 * `:core:network-api` and `:core:domain` types, and the knobs of architecture 21.3 seen through it. The computer is
 * never started: the client needs no socket.
 */
@OptIn(ExperimentalCoroutinesApi::class) // currentTime
class FakeComputerClientTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen())
  private val phone = computer.pairDevice("Pixel 9")
  private val client = FakeComputerClient(computer, phone)

  @After
  fun tearDown() = computer.close()

  // --- identity, pairing, health ----------------------------------------------------------------------------------

  @Test
  fun helloWithoutAChallengeReadsTheIdentityOnly() = runTest {
    val identity = client.hello(computer.address).ok()

    assertThat(identity.phoneAccess).isTrue()
    assertThat(identity.serverId).isEqualTo(computer.serverId)
    assertThat(identity.name).isEqualTo(FakeComputer.DEFAULT_NAME)
    assertThat(identity.apiVersion).isEqualTo(6)
    assertThat(identity.serverTime).isEqualTo(FakeComputerClock.REFERENCE_EPOCH_S)
    assertThat(identity.proofValid).isNull()
    assertThat(identity.revoked).isNull()
    assertThat(computer.requests.single().target).isEqualTo("/api/phone/hello")
  }

  @Test
  fun helloWithAChallengeChecksTheProofAndSendsNoCredential() = runTest {
    val identity = client.hello(computer.address, challengeFor = phone.deviceId).ok()

    assertThat(identity.proofValid).isTrue()
    assertThat(identity.revoked).isFalse()
    val sent = computer.requests.single()
    assertThat(sent.target).matches("/api/phone/hello\\?device_id=${phone.deviceId.value}&challenge=[0-9a-f]{32}")
    assertThat(sent.hadAuthorization).isFalse()
  }

  @Test
  fun aRevokedPhoneStillGetsAValidProofThatSaysRevoked() = runTest {
    computer.revokeDevice()

    val identity = client.hello(computer.address, challengeFor = phone.deviceId).ok()

    assertThat(identity.proofValid).isTrue()
    assertThat(identity.revoked).isTrue()
  }

  @Test
  fun anImpersonatorFailsTheProofWithThePairedServerId() = runTest {
    computer.impersonate(ProofMode.MISSING).use { missing ->
      computer.impersonate(ProofMode.WRONG).use { wrong ->
        val noProof = client.hello(missing.address, challengeFor = phone.deviceId).ok()
        val badProof = client.hello(wrong.address, challengeFor = phone.deviceId).ok()

        assertThat(noProof.serverId).isEqualTo(computer.serverId)
        assertThat(noProof.proofValid).isFalse()
        assertThat(badProof.serverId).isEqualTo(computer.serverId)
        assertThat(badProof.proofValid).isFalse()
        assertThat(missing.requests.single().hadAuthorization).isFalse()
        assertThat(computer.requests).isEmpty()
      }
    }
  }

  @Test
  fun withVerifiedPeersNothingIsSignedForAnUnverifiedAddress() = runTest {
    computer.impersonate(ProofMode.MISSING).use { impersonator ->
      val verified = FakeVerifiedPeers()
      val guarded = FakeComputerClient(
        listOf(computer), phone, currentPeer = { impersonator.address }, verifiedPeers = verified,
      )

      val refused = guarded.library().refused()

      assertThat(refused.code).isEqualTo("credential_rejected")
      assertThat(impersonator.requests.map { it.hadAuthorization }).containsExactly(false)
      verified.markVerified(computer.address, computer.serverId)
      val signed = FakeComputerClient(listOf(computer), phone, verifiedPeers = verified)
      assertThat(signed.library().status()).isEqualTo(200)
    }
  }

  @Test
  fun phoneAccessOffAnswersHelloAndRefusesEverythingElse() = runTest {
    computer.phoneAccessOff()

    val identity = client.hello(computer.address, challengeFor = phone.deviceId).ok()
    val health = client.health().refused()

    assertThat(identity.phoneAccess).isFalse()
    assertThat(identity.serverId).isNull()
    assertThat(identity.proofValid).isFalse()
    assertThat(health.status).isEqualTo(403)
    assertThat(health.code).isEqualTo("phone_access_off")
  }

  @Test
  fun aSuspendedNetworkRefusesHelloToo() = runTest {
    computer.lanForbidden()

    val refused = client.hello(computer.address).refused()

    assertThat(refused.status).isEqualTo(403)
    assertThat(refused.code).isEqualTo("lan_forbidden")
  }

  @Test
  fun anOldComputerIsRecognizedThroughLegacyHealth() = runTest {
    computer.apiVersion = 5

    val hello = client.hello(computer.address).refused()
    val legacy = client.legacyHealth(computer.address)

    assertThat(hello.status).isEqualTo(404)
    assertThat(hello.code).isEmpty()
    assertThat(legacy.ok()).isEqualTo(5)
  }

  @Test
  fun aCurrentComputerDoesNotServeLegacyHealthToTheLan() = runTest {
    assertThat(client.legacyHealth(computer.address).refused().code).isEqualTo("lan_forbidden")
  }

  @Test
  fun pairingAdoptsTheGrantAndSignsWithIt() = runTest {
    val fresh = FakeComputerClient(computer)
    val code = computer.newPairingCode()

    val result = fresh.pair(computer.address, pairRequest(code))

    val grant = result.ok()
    assertThat(result.status()).isEqualTo(201)
    assertThat(grant.serverId).isEqualTo(computer.serverId)
    assertThat(grant.libraryId).isEqualTo(computer.libraryId)
    assertThat(grant.name).isEqualTo(FakeComputer.DEFAULT_NAME)
    assertThat(String(grant.token)).startsWith("pt1_")
    assertThat(fresh.credentials!!.deviceId).isEqualTo(grant.deviceId)
    assertThat(fresh.health().ok().deviceId).isEqualTo(grant.deviceId)
    assertThat(computer.pairedDevices.map { it.deviceId }).contains(grant.deviceId)
  }

  @Test
  fun pairingRefusalsCarryTheirCodes() = runTest {
    val fresh = FakeComputerClient(computer)
    computer.newPairingCode("123456")

    val wrong = fresh.pair(computer.address, pairRequest("654321")).refused()
    computer.clock.advanceSeconds(301)
    val expired = fresh.pair(computer.address, pairRequest("123456")).refused()
    computer.minAppVersion = 2
    val tooOld = fresh.pair(computer.address, pairRequest("123456")).refused()

    assertThat(wrong.status to wrong.code).isEqualTo(403 to "code_invalid")
    assertThat(expired.status to expired.code).isEqualTo(403 to "code_expired")
    assertThat(tooOld.status to tooOld.code).isEqualTo(426 to "app_too_old")
    assertThat(fresh.credentials).isNull()
  }

  @Test
  fun tooManyPairingAttemptsAreRateLimitedWithRetryAfter() = runTest {
    val fresh = FakeComputerClient(computer)
    computer.newPairingCode("123456")
    repeat(10) { fresh.pair(computer.address, pairRequest("000000")) }

    val limited = fresh.pair(computer.address, pairRequest("000000")).refused()

    assertThat(limited.status).isEqualTo(429)
    assertThat(limited.code).isEqualTo("rate_limited")
    assertThat(limited.retryAfterS).isNotNull()
  }

  @Test
  fun connectAgainKeepsTheDeviceIdAndRetiresTheOldKey() = runTest {
    val code = computer.newPairingCode()
    val proof = PhoneCrypto.reactivationProof(phone.key, code)

    val grant = client.pair(computer.address, pairRequest(code, previous = phone.deviceId.value to proof)).ok()

    assertThat(grant.deviceId).isEqualTo(phone.deviceId)
    assertThat(client.health().status()).isEqualTo(200)
    assertThat(FakeComputerClient(computer, phone).health().refused().code).isEqualTo("credential_rejected")
  }

  @Test
  fun healthMapsIdentityQueueSpaceAndCapabilities() = runTest {
    computer.addJob(JobSeed(status = JobStatus.RUNNING))
    computer.addJob(JobSeed(status = JobStatus.QUEUED))
    computer.maxLinkDurationS = 1_800

    val health = client.health().ok()

    assertThat(health.serverId).isEqualTo(computer.serverId)
    assertThat(health.libraryId).isEqualTo(computer.libraryId)
    assertThat(health.deviceId).isEqualTo(phone.deviceId)
    assertThat(health.apiVersion).isEqualTo(6)
    assertThat(health.minAppVersion).isEqualTo(1)
    assertThat(health.analyzing).isEqualTo(1)
    assertThat(health.inLine).isEqualTo(1)
    assertThat(health.lowOnSpace).isFalse()
    assertThat(health.capabilities.linkDownloads).isTrue()
    assertThat(health.capabilities.downloaderVersion).isEqualTo("2026.8.19")
    assertThat(health.capabilities.maxLinkDurationS).isEqualTo(1_800)
    assertThat(health.capabilities.maxUploadBytes).isEqualTo(2L * FakeCapabilities.GIB)
    assertThat(health.capabilities.resumableUploads).isFalse()
    computer.lowOnSpace()
    assertThat(client.health().ok().lowOnSpace).isTrue()
  }

  @Test
  fun forgettingTheComputerRevokesThisPhoneOnce() = runTest {
    val first = client.unpairSelf()
    val again = client.unpairSelf().refused()

    assertThat(first.status()).isEqualTo(204)
    assertThat(computer.pairedDevices.single().revoked).isTrue()
    assertThat(again.status to again.code).isEqualTo(401 to "credential_rejected")
  }

  // --- the listing, jobs, requests, reference ---------------------------------------------------------------------

  @Test
  fun theListingMapsEveryFieldOfTheContractShape() = runTest {
    val done = computer.addFinishedClip("Ordering coffee")
    val failed = computer.addJob(JobSeed(status = JobStatus.ERROR, title = null, errorCode = "download_forbidden"))
    val key = RequestId(newRequestKey())
    client.createLink(LinkCreate(key, "https://www.youtube.com/shorts/abc123def45", audioOnly = true, null))

    val library = client.library().ok()

    assertThat(library.apiVersion).isEqualTo(6)
    assertThat(library.serverId).isEqualTo(computer.serverId)
    assertThat(library.libraryId).isEqualTo(computer.libraryId)
    assertThat(library.jobs).hasSize(3)
    val clip = library.jobs.single { it.id == done }
    assertThat(clip.status).isEqualTo(RemoteStatus.DONE)
    assertThat(clip.revision).matches("[0-9a-f]{16}")
    assertThat(clip.title).isEqualTo("Ordering coffee")
    assertThat(clip.source.kind).isEqualTo(RemoteSourceKind.LINK)
    assertThat(clip.source.site).isEqualTo(Site.YOUTUBE)
    assertThat(clip.own).isFalse()
    assertThat(clip.deviceId).isNull()
    assertThat(clip.percent).isEqualTo(100)
    assertThat(clip.summary!!.words).isEqualTo(AnalysisFixtures.MkAnalysis.WORDS)
    assertThat(clip.summary!!.changeCounts).isEqualTo(AnalysisFixtures.MkAnalysis.CHANGE_COUNTS)
    assertThat(clip.analysis!!.dialogueSeparation).isTrue()
    assertThat(clip.analysis!!.rulesVersion).isEqualTo(3)
    assertThat(clip.media!!.kind).isEqualTo(RemoteMediaKind.VIDEO)
    assertThat(clip.media!!.rotation).isEqualTo(0)
    assertThat(clip.finishedAt).isNotNull()
    val artifacts = clip.artifacts!!
    assertThat(artifacts.map { it.name })
      .containsExactly("analysis", "audio_mix", "audio_dialogue", "original").inOrder()
    assertThat(artifacts.map { it.role }).containsExactly(
      ArtifactRole.CLIP_DATA, ArtifactRole.AUDIO_MIX, ArtifactRole.AUDIO_DIALOGUE, ArtifactRole.VIDEO,
    ).inOrder()
    val analysis = artifacts.first()
    assertThat(analysis.status).isEqualTo(RemoteArtifactStatus.READY)
    assertThat(analysis.sha256).matches("[0-9a-f]{64}")
    assertThat(analysis.revision).isEqualTo(analysis.sha256!!.take(16))
    assertThat(analysis.url).isEqualTo("/api/phone/jobs/${done.value}/artifacts/analysis")
    val error = library.jobs.single { it.id == failed }.error!!
    assertThat(error.code).isEqualTo("download_forbidden")
    assertThat(error.downloaderVersion).isEqualTo("2026.8.19")
    val own = library.jobs.single { it.own }
    assertThat(own.status).isEqualTo(RemoteStatus.QUEUED)
    assertThat(own.queuePosition).isEqualTo(1)
    assertThat(own.deviceId).isEqualTo(phone.deviceId)
    assertThat(own.clientRequestId).isEqualTo(key)
    assertThat(own.source.site).isEqualTo(Site.YOUTUBE_SHORTS)
    assertThat(own.audioOnly).isTrue()
    assertThat(own.artifacts).isEmpty()
  }

  @Test
  fun oneJobAndARequestLookup() = runTest {
    val key = RequestId(newRequestKey())
    val created = client.createLink(LinkCreate(key, "https://youtu.be/abc123def45", false, null)).ok().value

    assertThat(client.job(created.id).ok().id).isEqualTo(created.id)
    assertThat(client.requestLookup(key).ok().id).isEqualTo(created.id)
    assertThat(client.job(JobId("20260101-000000-abcd")).refused().code).isEqualTo("unknown_job")
    assertThat(client.requestLookup(RequestId(newRequestKey())).refused().code).isEqualTo("unknown_request")
  }

  @Test
  fun theReferenceIsTheRawBody() = runTest {
    computer.referenceJson = """{"api_version": 6, "families": ["reduction"], "phenomena": []}"""

    assertThat(client.reference().ok()).isEqualTo(computer.referenceJson)
  }

  // --- links and job actions ---------------------------------------------------------------------------------------

  @Test
  fun aLinkIsCreatedOnceForItsKey() = runTest {
    val key = RequestId(newRequestKey())
    val body = LinkCreate(key, "https://youtu.be/abc123def45", audioOnly = false, separateDialogue = false)

    val first = client.createLink(body)
    val again = client.createLink(body)
    val conflict = client.createLink(body.copy(url = "https://youtu.be/zzz")).refused()

    assertThat(first.status()).isEqualTo(201)
    assertThat(first.ok().created).isTrue()
    assertThat(first.ok().value.separateDialogue).isFalse()
    assertThat(again.status()).isEqualTo(200)
    assertThat(again.ok().created).isFalse()
    assertThat(again.ok().value.id).isEqualTo(first.ok().value.id)
    assertThat(conflict.status to conflict.code).isEqualTo(409 to "key_conflict")
    assertThat(computer.jobsWithKey(key)).hasSize(1)
  }

  @Test
  fun aLostAnswerLeavesOneJobAndTheRetryFindsIt() = runTest {
    computer.applyThenDrop("/api/phone/jobs/link")
    val key = RequestId(newRequestKey())
    val body = LinkCreate(key, "https://youtu.be/abc123def45", false, null)

    val lost = client.createLink(body)
    val retried = client.createLink(body)

    assertThat(lost.failure()).isEqualTo(AppError.Computer.Unreachable(ProbeOutcome.Reset))
    assertThat(retried.status()).isEqualTo(200)
    assertThat(retried.ok().created).isFalse()
    assertThat(computer.jobsWithKey(key)).hasSize(1)
  }

  @Test
  fun linkRefusalsCarryTheirCodes() = runTest {
    computer.linkHost("private.example", LinkHostAnswer.REFUSED)
    computer.linkHost("nowhere.example", LinkHostAnswer.UNRESOLVABLE)

    val invalid = client.createLink(LinkCreate(RequestId(newRequestKey()), "not a link", false, null)).refused()
    val private = client.createLink(LinkCreate(RequestId(newRequestKey()), "https://private.example/v", false, null))
    val unresolved = client.createLink(LinkCreate(RequestId(newRequestKey()), "https://nowhere.example/v", false, null))

    assertThat(invalid.status to invalid.code).isEqualTo(400 to "invalid_url")
    assertThat(private.refused().code).isEqualTo("refused_address")
    assertThat(unresolved.refused().status to unresolved.refused().code).isEqualTo(503 to "cannot_check_link")
  }

  @Test
  fun cancelIsTooLateForADoneJobAndRefusedForAnotherDevicesJob() = runTest {
    val link = LinkCreate(RequestId(newRequestKey()), "https://youtu.be/a1", false, null)
    val queued = client.createLink(link).ok().value
    val done = computer.addJob(JobSeed(deviceId = phone.deviceId, clientRequestId = RequestId(newRequestKey())))
    val browser = computer.addFinishedClip()

    val cancelled = client.cancel(queued.id).ok()
    val tooLate = client.cancel(done).refused()
    val notOwn = client.cancel(browser).refused()

    assertThat(cancelled.status).isEqualTo(RemoteStatus.CANCELLED)
    assertThat(cancelled.error!!.code).isEqualTo("cancelled")
    assertThat(tooLate.status to tooLate.code).isEqualTo(409 to "already_finished")
    assertThat(tooLate.job!!.status).isEqualTo(RemoteStatus.DONE)
    assertThat(notOwn.status to notOwn.code).isEqualTo(403 to "not_own_job")
  }

  @Test
  fun aFailedLinkRunsAgainOnceForItsKeyAndADoneJobCannotBeDeleted() = runTest {
    val failed = computer.addJob(
      JobSeed(status = JobStatus.ERROR, errorCode = "interrupted", deviceId = phone.deviceId),
    )
    val done = computer.addJob(JobSeed(deviceId = phone.deviceId))
    val key = RequestId(newRequestKey())

    val rerun = client.rerun(failed, RerunBody(key, audioOnly = true))
    val repeated = client.rerun(failed, RerunBody(key, audioOnly = true))
    val notDeletable = client.deleteJob(done).refused()

    assertThat(rerun.status()).isEqualTo(201)
    assertThat(rerun.ok().value.rerunOf).isEqualTo(failed)
    assertThat(rerun.ok().value.audioOnly).isTrue()
    assertThat(repeated.status()).isEqualTo(200)
    assertThat(repeated.ok().value.id).isEqualTo(rerun.ok().value.id)
    assertThat(client.job(failed).refused().code).isEqualTo("unknown_job")
    assertThat(notDeletable.status to notDeletable.code).isEqualTo(409 to "not_deletable")
    client.cancel(rerun.ok().value.id)
    assertThat(client.deleteJob(rerun.ok().value.id).status()).isEqualTo(204)
    assertThat(computer.job(rerun.ok().value.id)).isNull()
  }

  // --- knobs ---------------------------------------------------------------------------------------------------------

  @Test
  fun anUnreachableComputerAnswersNothingAndHearsNothing() = runTest {
    computer.reachability = Reachability.Unreachable

    assertThat(client.health().failure()).isEqualTo(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(3)))
    assertThat(client.library().failure()).isEqualTo(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(30)))
    assertThat(computer.requests).isEmpty()
  }

  @Test
  fun waitingForTimeoutsSpendsThemOnTheTestClock() = runTest {
    val patient = FakeComputerClient(listOf(computer), phone, waitForTimeouts = true)
    computer.reachability = Reachability.Unreachable

    patient.health()

    assertThat(currentTime).isEqualTo(3_000)
  }

  @Test
  fun aSlowComputerAnswersLate() = runTest {
    computer.reachability = Reachability.Slow(Duration.ofSeconds(2))

    val library = client.library()

    assertThat(library.status()).isEqualTo(200)
    assertThat(currentTime).isEqualTo(2_000)
  }

  @Test
  fun failNextGivesTheErrorEnvelopeAndRetryAfter() = runTest {
    computer.failNext("/api/phone/library", 503)
    computer.failNext("/api/phone/library", 429, code = "rate_limited", retryAfterS = 7)

    val unavailable = client.library().refused()
    val limited = client.library().refused()

    assertThat(unavailable.status).isEqualTo(503)
    assertThat(unavailable.code).isEmpty()
    assertThat(unavailable.detail).isEqualTo("Internal Server Error")
    assertThat(limited.code).isEqualTo("rate_limited")
    assertThat(limited.retryAfterS).isEqualTo(7)
  }

  @Test
  fun anAnswerThatIsNotTheShapeIsMalformed() = runTest {
    computer.failNext("/api/phone/library", 200, code = "not_a_listing")

    assertThat(client.library().failure()).isEqualTo(AppError.Computer.Malformed)
  }

  @Test
  fun aRevokedPhoneIsRejected() = runTest {
    computer.revokeDevice()

    val refused = client.library().refused()

    assertThat(refused.status to refused.code).isEqualTo(401 to "credential_rejected")
  }

  @Test
  fun aClockSkewIsLearnedAndTheCallRetriedOnce() = runTest {
    computer.clockSkew(900)
    val offset = FakeClockOffset(phoneNowS = computer.clock.nowSeconds())
    val skewed = FakeComputerClient(listOf(computer), phone, clockOffset = offset)

    val library = skewed.library()

    assertThat(library.status()).isEqualTo(200)
    assertThat(computer.requests.map { it.status }).containsExactly(401, 200).inOrder()
    assertThat(skewed.clockOffsetS).isEqualTo(900)
    assertThat(offset.offsetS.value).isEqualTo(900)
  }

  @Test
  fun withoutACurrentComputerSignedCallsAreGuarded() = runTest {
    val noCurrent = FakeComputerClient(listOf(computer), phone, currentPeer = { null })

    assertThat(noCurrent.library().failure()).isEqualTo(AppError.Computer.Guarded)
    assertThat(computer.requests).isEmpty()
  }

  @Test
  fun anAddressWhereNothingListensGivesNoAnswer() = runTest {
    val nowhere = HostPort(FakeComputer.HOST, 1)

    assertThat(client.hello(nowhere).failure()).isEqualTo(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(3)))
  }

  @Test
  fun eachComputerAnswersAtItsOwnAddress() = runTest {
    FakeComputer(clock = FakeComputerClock.frozen()).use { other ->
      var current = computer.address
      val roaming = FakeComputerClient(listOf(computer, other), phone, currentPeer = { current })

      val there = roaming.hello(other.address, challengeFor = phone.deviceId).ok()
      current = other.address
      val refused = roaming.library().refused()

      assertThat(other.address).isNotEqualTo(computer.address)
      assertThat(there.serverId).isEqualTo(other.serverId)
      assertThat(there.proofValid).isFalse()
      assertThat(refused.code).isEqualTo("credential_rejected")
      assertThat(computer.requests).isEmpty()
    }
  }

  @Test
  fun theInMemoryClientAndAnHttpPhoneShareOneStartedComputer() = runTest {
    computer.start()
    val key = RequestId(newRequestKey())

    val created = client.createLink(LinkCreate(key, "https://youtu.be/abc123def45", false, null)).ok().value
    val overHttp = TestPhone(computer, phone).get("/api/phone/library").json.arr("jobs")

    assertThat(computer.address.port).isNotEqualTo(0)
    assertThat(overHttp.map { (it as JsonObject).str("id") }).containsExactly(created.id.value)
  }

  private fun pairRequest(code: String, previous: Pair<String, String>? = null) = PairRequest(
    code = code, deviceName = "Pixel 9", appVersion = 1, appVersionName = "1.0.0",
    previousDeviceId = previous?.first?.let(::DeviceId),
    previousTokenProof = previous?.second,
  )
}
