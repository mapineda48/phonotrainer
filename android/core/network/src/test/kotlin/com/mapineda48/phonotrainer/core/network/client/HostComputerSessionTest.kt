package com.mapineda48.phonotrainer.core.network.client

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.RerunBody
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.network.support.LostAnswerProxy
import com.mapineda48.phonotrainer.core.network.support.PhoneClock
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeMediaBytes
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The client against the **real server code**: the host-side fake computer of server-contract 12
 * (`android/scripts/device-qa/host_computer.py`, the server built from this checkout with fake analyses and a
 * downloader that refuses every link). It runs only when `PHONOTRAINER_HOST_COMPUTER` (`127.0.0.1:<port>`) and a fresh
 * `PHONOTRAINER_PAIRING_CODE` are set, and is skipped otherwise (the hand-off of WP-05b says how it was run). It proves
 * what the JVM fake cannot: the uploads' answers as the server gives them, a request after uvicorn has closed an idle
 * keep-alive connection (5 s), which a client must survive (wave-1 review), and an answer lost on a reused connection,
 * sent again with a fresh signature that the server accepts (WP-05b reviews). It never sends a link (spec D-36).
 */
class HostComputerSessionTest {
  @get:Rule
  val folder = TemporaryFolder()

  @Test
  fun aWholeSessionAgainstTheRealServerCode() {
    val address = System.getenv("PHONOTRAINER_HOST_COMPUTER")
    val code = System.getenv("PHONOTRAINER_PAIRING_CODE")
    assumeTrue("the host-side fake computer is not running", !address.isNullOrBlank() && !code.isNullOrBlank())
    val at = HostPort(address!!.substringBefore(':'), address.substringAfter(':').toInt())

    // The JVM fake computer is only the stack's clock source here; the phone's clock is the real one.
    FakeComputer().use { unused ->
      TestStack(unused, phoneClock = PhoneClock { Instant.now() }).use { stack ->
        runBlocking { session(stack, at, code!!) }
      }
    }
  }

  private suspend fun session(stack: TestStack, at: HostPort, code: String) {
    val client = stack.client
    val identity = stack.peers.withCandidate(at) { client.hello(at) } as ApiResult.Ok
    assertThat(identity.value.phoneAccess).isTrue()
    assertThat(identity.value.apiVersion).isAtLeast(6)

    val grant = stack.peers.withCandidate(at) {
      client.pair(at, PairRequest(code, "WP-05b JVM test", 1, "1.0.0", null, null))
    } as ApiResult.Ok
    stack.store.save(grant.value, at, "WP-05b JVM test", stack.clock.now(), 0)
    stack.peers.setCurrent(at)
    val proof = client.hello(at, grant.value.deviceId) as ApiResult.Ok
    assertThat(proof.value.proofValid).isTrue()
    assertThat((client.health() as ApiResult.Ok).value.deviceId).isEqualTo(grant.value.deviceId)

    // The seeded clip, listed and downloaded as the sync engine would.
    val library = (client.library() as ApiResult.Ok).value
    val seeded = library.jobs.first { it.status == RemoteStatus.DONE }
    val analysis = seeded.artifacts!!.first { it.name == "analysis" }
    val part = File(folder.root, "analysis.part")
    val download = stack.transfer.download(DownloadRequest(analysis.url!!, part, 0, null, analysis.bytes!!)) {}
    assertThat(download).isInstanceOf(DownloadOutcome.Complete::class.java)
    assertThat(Sha256.of(part).sha256).isEqualTo(analysis.sha256)
    assertThat((client.reference() as ApiResult.Ok).value).contains("{")

    lostAnswer(stack, at, grant.value.deviceId)

    // After more than uvicorn's 5 s keep-alive, the next requests still go through.
    delay(IDLE_MS)
    assertThat(client.library()).isInstanceOf(ApiResult.Ok::class.java)

    // Uploads, with the server's own refusals.
    val take = File(folder.root, "take.wav").apply { writeBytes(FakeMediaBytes.wav(2.0)) }
    val sha = Sha256.of(take).sha256
    val key = RequestId(UUID.randomUUID().toString())
    assertThat(client.openUpload(UploadDeclaration(key, take.name, take.length(), sha, false))).isInstanceOf(ApiResult.Ok::class.java)
    expectRefusal(client.openUpload(UploadDeclaration(key, take.name, take.length(), "0".repeat(64), false)), 409, "key_conflict")
    expectRefusal(client.putUpload(RequestId(UUID.randomUUID().toString()), take) {}, 404, "unknown_upload")
    val damaged = RequestId(UUID.randomUUID().toString())
    client.openUpload(UploadDeclaration(damaged, take.name, take.length(), "0".repeat(64), false))
    expectRefusal(client.putUpload(damaged, take) {}, 422, "checksum_mismatch")
    val longer = RequestId(UUID.randomUUID().toString())
    client.openUpload(UploadDeclaration(longer, take.name, take.length() + 1, sha, false))
    expectRefusal(client.putUpload(longer, take) {}, 400, "length_mismatch")
    client.deleteUpload(damaged)
    client.deleteUpload(longer)

    delay(IDLE_MS)
    val created = client.putUpload(key, take) {} as ApiResult.Ok
    assertThat(created.status).isEqualTo(201)
    val job = created.value.value

    // The job channel announces it; then cancel, re-run, cancel and delete, as the emulator smoke test does.
    val events = withTimeout(CHANNEL_MS) { stack.channel.open().take(2).toList() }
    assertThat(events[0]).isInstanceOf(ChannelEvent.Hello::class.java)
    assertThat((events[1] as ChannelEvent.Snapshot).jobs.map { it.id }).contains(job.id)

    client.cancel(job.id)
    awaitStatus(stack, job.id.value, RemoteStatus.CANCELLED)
    delay(IDLE_MS)
    val again = client.rerun(job.id, RerunBody(RequestId(UUID.randomUUID().toString()), null)) as ApiResult.Ok
    client.cancel(again.value.value.id)
    awaitStatus(stack, again.value.value.id.value, RemoteStatus.CANCELLED)
    assertThat(client.deleteJob(again.value.value.id)).isEqualTo(ApiResult.Ok(Unit, 204))

    assertThat(client.unpairSelf()).isEqualTo(ApiResult.Ok(Unit, 204))
    expectRefusal(client.health(), 401, "credential_rejected")
  }

  /**
   * An answer lost on a reused connection (the WP-05b reviews): OkHttp sends the request again on a new connection,
   * signed again with a fresh nonce, so the server, which refuses a nonce it has seen with `401 credential_rejected`
   * (server-contract 4.3), answers it and the phone stays paired. Through [LostAnswerProxy], a new address for the
   * phone, so it proves the computer there first and again back at [at].
   */
  private suspend fun lostAnswer(stack: TestStack, at: HostPort, device: DeviceId) {
    val client = stack.client
    LostAnswerProxy(at, "/api/phone/reference", nth = 2).use { proxy ->
      stack.peers.setCurrent(proxy.address)
      assertThat((client.hello(proxy.address, device) as ApiResult.Ok).value.proofValid).isTrue()
      assertThat(client.reference()).isInstanceOf(ApiResult.Ok::class.java)

      assertThat(client.reference()).isInstanceOf(ApiResult.Ok::class.java)

      assertThat(proxy.nonces).hasSize(3)
      assertThat(proxy.nonces.toSet()).hasSize(3)
      assertThat(client.health()).isInstanceOf(ApiResult.Ok::class.java)
    }
    stack.peers.setCurrent(at)
    assertThat((client.hello(at, device) as ApiResult.Ok).value.proofValid).isTrue()
  }

  private fun expectRefusal(result: ApiResult<*>, status: Int, code: String) {
    assertWithMessage("$status $code").that(result).isInstanceOf(ApiResult.Refused::class.java)
    val refused = result as ApiResult.Refused
    assertWithMessage("$status $code").that(refused.status to refused.code).isEqualTo(status to code)
  }

  private suspend fun awaitStatus(stack: TestStack, id: String, status: RemoteStatus) {
    withTimeout(STATUS_MS) {
      while (true) {
        val job = stack.client.job(com.mapineda48.phonotrainer.core.domain.model.JobId(id))
        if (job is ApiResult.Ok && job.value.status == status) return@withTimeout
        delay(POLL_MS)
      }
    }
  }

  private companion object {
    const val IDLE_MS = 6_000L
    const val CHANNEL_MS = 10_000L
    const val STATUS_MS = 20_000L
    const val POLL_MS = 250L
  }
}
