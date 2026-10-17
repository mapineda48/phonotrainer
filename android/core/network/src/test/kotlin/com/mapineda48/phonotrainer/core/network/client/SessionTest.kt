package com.mapineda48.phonotrainer.core.network.client

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.http.NetworkTimeouts
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import com.mapineda48.phonotrainer.core.testing.computer.JobStatus
import com.mapineda48.phonotrainer.core.testing.computer.Reachability
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/** A whole phone session over real HTTP against the fake computer: pairing, the proof, health, the listing. */
class SessionTest {
  private val computer = FakeComputer().start()
  private val stack = TestStack(computer)

  @After
  fun close() {
    stack.close()
    computer.close()
  }

  @Test
  fun aPairedPhoneProvesTheComputerAndReadsItsHealthAndListing() = runBlocking<Unit> {
    val clip = computer.addFinishedClip("Ordering coffee")
    val paired = stack.pair()

    val health = stack.client.health() as ApiResult.Ok
    val library = stack.client.library() as ApiResult.Ok

    assertThat(stack.verified.isVerified(computer.address)).isTrue()
    assertThat(health.value.serverId).isEqualTo(computer.serverId)
    assertThat(health.value.deviceId.value).isEqualTo(paired.deviceId)
    assertThat(health.value.capabilities.maxUploadBytes).isGreaterThan(0L)
    assertThat(library.value.libraryId).isEqualTo(computer.libraryId)
    val job = library.value.jobs.single()
    assertThat(job.id).isEqualTo(clip)
    assertThat(job.status).isEqualTo(RemoteStatus.DONE)
    assertThat(job.title).isEqualTo("Ordering coffee")
    assertThat(job.artifacts!!.map { it.name }).containsAtLeast("analysis", "audio_mix")
    // Every signed request carried this phone's valid signature.
    val signed = computer.requests.filter { it.path != "/api/phone/hello" && it.path != "/api/phone/pair" }
    assertThat(signed.map { it.signedBy?.value }.distinct()).containsExactly(paired.deviceId)
  }

  @Test
  fun theReferenceIsReadAsTheComputerServesIt() = runBlocking<Unit> {
    stack.pair()

    val reference = stack.client.reference() as ApiResult.Ok

    assertThat(reference.value).isEqualTo(computer.referenceJson)
  }

  @Test
  fun anOldComputerRefusesHelloAndAnswersItsLegacyHealthWithItsVersion() = runBlocking<Unit> {
    computer.apiVersion = 5

    val hello = stack.peers.withCandidate(computer.address) { stack.client.hello(computer.address) }
    val legacy = stack.peers.withCandidate(computer.address) { stack.client.legacyHealth(computer.address) }

    assertThat((hello as ApiResult.Refused).status).isEqualTo(404)
    assertThat((legacy as ApiResult.Ok).value).isEqualTo(5)
    assertThat(computer.requests.map { it.hadAuthorization }).containsExactly(false, false)
  }

  @Test
  fun probesWaitLongerWhileTheComputerIsAnalyzing() {
    val slow = NetworkTimeouts(probe = Duration.ofMillis(400), probeWhileAnalyzing = Duration.ofSeconds(3))
    TestStack(computer, timeouts = slow).use { quick ->
      runBlocking {
        val device = quick.pair()
        computer.reachability = Reachability.Slow(Duration.ofMillis(1_200))
        val before = quick.client.hello(computer.address, com.mapineda48.phonotrainer.core.domain.model.DeviceId(device.deviceId))
        assertThat(before).isEqualTo(ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(1))))

        // A probe with no answer proved nothing, so the address proves itself again (architecture 5.3).
        computer.reachability = Reachability.Reachable
        val again = quick.client.hello(computer.address, com.mapineda48.phonotrainer.core.domain.model.DeviceId(device.deviceId))
        assertThat((again as ApiResult.Ok).value.proofValid).isTrue()
        computer.addJob(JobSeed(status = JobStatus.RUNNING))
        assertThat((quick.client.health() as ApiResult.Ok).value.analyzing).isEqualTo(1)
        computer.reachability = Reachability.Slow(Duration.ofMillis(1_200))

        val during = quick.client.hello(computer.address, com.mapineda48.phonotrainer.core.domain.model.DeviceId(device.deviceId))
        assertThat((during as ApiResult.Ok).value.proofValid).isTrue()
      }
    }
  }
}
