package com.mapineda48.phonotrainer.core.network.signing

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.support.RecordingPlatform
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClock
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Test

/**
 * Every network attempt is checked and signed on its own (architecture 5.1 and 5.3, the WP-05b security and contract
 * reviews). OkHttp sends a request again on a new connection when a reused one fails before its answer: that attempt
 * carries a fresh nonce, because the computer refuses a nonce it has seen as a replay with `401 credential_rejected`
 * (server-contract 4.3), which would cost the pairing; and it never reaches an address that has not proved itself on
 * the network current at that moment. A proof on its way across a change of network verifies nothing, and a connection
 * opened on an earlier network is never used again.
 */
class AttemptSigningTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen())

  @After
  fun close() {
    computer.close()
  }

  @Test
  fun aLostAnswerOnAReusedConnectionIsSentAgainWithAFreshSignature() = runBlocking<Unit> {
    val server = MockWebServer().apply { start(InetAddress.getByName(FakeComputer.HOST), 0) }
    server.use {
      server.enqueue(json())
      server.enqueue(lostAnswer())
      server.enqueue(json())
      TestStack(computer).use { stack ->
        stack.trust(HostPort(FakeComputer.HOST, server.port))
        assertThat(stack.client.reference()).isInstanceOf(ApiResult.Ok::class.java)

        val second = stack.client.reference()

        val first = server.takeRequest(WAIT_S, TimeUnit.SECONDS)!!
        val lost = server.takeRequest(WAIT_S, TimeUnit.SECONDS)!!
        val resent = server.takeRequest(WAIT_S, TimeUnit.SECONDS)!!
        assertThat(second).isInstanceOf(ApiResult.Ok::class.java)
        assertThat(lost.connectionIndex).isEqualTo(first.connectionIndex)
        assertThat(resent.connectionIndex).isNotEqualTo(lost.connectionIndex)
        assertThat(resent.url.encodedPath).isEqualTo(lost.url.encodedPath)
        assertThat(field(resent, "nonce")).isNotEqualTo(field(lost, "nonce"))
        assertThat(field(resent, "mac")).isNotEqualTo(field(lost, "mac"))
        assertThat(server.requestCount).isEqualTo(3)
      }
    }
  }

  @Test
  fun aRetryAfterANetworkChangeIsRefusedAndNothingSignedIsSent() = runBlocking<Unit> {
    val platform = RecordingPlatform()
    TestStack(computer, platform = platform).use { stack ->
      val server = MockWebServer()
      val answered = AtomicInteger()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          if (answered.incrementAndGet() == 2) {
            stack.binder.bind(NETWORK_B) // the network changes while the request is on the wire
            lostAnswer()
          } else {
            json()
          }
      }
      server.start(InetAddress.getByName(FakeComputer.HOST), 0)
      server.use {
        stack.binder.bind(NETWORK_A)
        val at = HostPort(FakeComputer.HOST, server.port)
        stack.trust(at)
        assertThat(stack.client.reference()).isInstanceOf(ApiResult.Ok::class.java)

        val second = stack.client.reference()

        assertThat(second).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
        assertThat(stack.verified.isVerified(at)).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
        // OkHttp's retry opened its socket on the new network and was stopped there, before a byte was written.
        assertThat(platform.bound.map { it.first }).containsExactly(NETWORK_A, NETWORK_B).inOrder()
      }
    }
  }

  @Test
  fun aProofOnItsWayAcrossANetworkChangeVerifiesNothing() = runBlocking<Unit> {
    val platform = RecordingPlatform()
    val received = CountDownLatch(1)
    val release = CountDownLatch(1)
    val server = MockWebServer()
    TestStack(computer, platform = platform).use { stack ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.url.encodedPath != "/api/phone/hello") return json()
          received.countDown()
          release.await(WAIT_S, TimeUnit.SECONDS)
          return json(helloAnswer(request.url.queryParameter("challenge")!!, stack.clock.now().epochSecond))
        }
      }
      server.start(InetAddress.getByName(FakeComputer.HOST), 0)
      server.use {
        stack.binder.bind(NETWORK_A)
        val at = HostPort(FakeComputer.HOST, server.port)
        stack.trust(at)
        stack.verified.reset()

        val hello = async(Dispatchers.IO) { stack.client.hello(at, DEVICE) }
        assertThat(received.await(WAIT_S, TimeUnit.SECONDS)).isTrue()
        stack.binder.bind(NETWORK_B)
        release.countDown()

        assertThat(hello.await()).isEqualTo(ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset)))
        assertThat(stack.verified.isVerified(at)).isFalse()
        assertThat(stack.client.reference()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
        assertThat(server.requestCount).isEqualTo(1)

        // A proof made on the new network verifies the address again.
        val again = stack.client.hello(at, DEVICE) as ApiResult.Ok
        assertThat(again.value.proofValid).isTrue()
        assertThat(stack.verified.isVerified(at)).isTrue()
        assertThat(stack.client.reference()).isInstanceOf(ApiResult.Ok::class.java)
      }
    }
  }

  @Test
  fun anAddressChangeWhileAProofIsOnItsWayVerifiesNothing() = runBlocking<Unit> {
    val received = CountDownLatch(1)
    val release = CountDownLatch(1)
    val server = MockWebServer()
    TestStack(computer).use { stack ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          received.countDown()
          release.await(WAIT_S, TimeUnit.SECONDS)
          return json(helloAnswer(request.url.queryParameter("challenge")!!, stack.clock.now().epochSecond))
        }
      }
      server.start(InetAddress.getByName(FakeComputer.HOST), 0)
      server.use {
        val at = HostPort(FakeComputer.HOST, server.port)
        stack.trust(at)
        stack.verified.reset()

        val hello = async(Dispatchers.IO) { stack.peers.withCandidate(at) { stack.client.hello(at, DEVICE) } }
        assertThat(received.await(WAIT_S, TimeUnit.SECONDS)).isTrue()
        stack.peers.setCurrent(HostPort(FakeComputer.HOST, server.port + 1))
        release.countDown()

        assertThat(hello.await()).isEqualTo(ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset)))
        assertThat(stack.verified.isVerified(at)).isFalse()
      }
    }
  }

  @Test
  fun aConnectionFromAnEarlierNetworkIsNeverUsedAgain() = runBlocking<Unit> {
    val platform = RecordingPlatform()
    TestStack(computer, platform = platform).use { stack ->
      val server = MockWebServer()
      val answered = AtomicInteger()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          // The network changes while this call holds its connection, so the pool's eviction cannot close it.
          if (answered.incrementAndGet() == 1) stack.binder.bind(NETWORK_B)
          return json(identity(SERVER_TIME))
        }
      }
      server.start(InetAddress.getByName(FakeComputer.HOST), 0)
      server.use {
        stack.binder.bind(NETWORK_A)
        val at = HostPort(FakeComputer.HOST, server.port)
        stack.peers.setCurrent(at)

        val before = stack.client.hello(at)
        val after = stack.client.hello(at)

        val first = server.takeRequest(WAIT_S, TimeUnit.SECONDS)!!
        val second = server.takeRequest(WAIT_S, TimeUnit.SECONDS)!!
        assertThat(before).isInstanceOf(ApiResult.Ok::class.java)
        assertThat(after).isInstanceOf(ApiResult.Ok::class.java)
        assertThat(second.connectionIndex).isNotEqualTo(first.connectionIndex)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(platform.bound.map { it.first }).containsExactly(NETWORK_A, NETWORK_B).inOrder()
        assertThat(platform.bound.first().second.isClosed).isTrue()
      }
    }
  }

  private fun json(body: String = "{}"): MockResponse =
    MockResponse.Builder().code(OK).body(body).setHeader("Content-Type", "application/json").build()

  /** The computer reads the request (and would record its nonce), then the connection dies before the answer. */
  private fun lostAnswer(): MockResponse =
    MockResponse.Builder().code(OK).body("{}").onResponseStart(SocketEffect.CloseSocket()).build()

  /** `hello` with a valid proof for the synthetic pairing of [TestStack.trust]. */
  private fun helloAnswer(challenge: String, serverTime: Long): String {
    val key = MessageDigest.getInstance("SHA-256").digest(TOKEN.toByteArray(Charsets.UTF_8))
    return identity(serverTime, proof = Pt1.helloProof(key, SERVER_ID, DEVICE.value, challenge))
  }

  private fun identity(serverTime: Long, proof: String? = null): String =
    """{"service":"phonotrainer","phone_access":true,"server_id":"$SERVER_ID","name":"Test computer",""" +
      """"api_version":6,"server_time":$serverTime""" + (proof?.let { ""","proof":"$it"}""" } ?: "}")

  private fun field(request: RecordedRequest, name: String): String =
    Regex("$name=([0-9a-f]+)").find(request.headers["Authorization"]!!)!!.groupValues[1]

  private companion object {
    const val OK = 200
    const val WAIT_S = 5L
    const val NETWORK_A = 101L
    const val NETWORK_B = 102L
    const val TOKEN = "pt1-test-token"
    const val SERVER_ID = "srv_test"
    const val SERVER_TIME = 1_791_622_800L
    val DEVICE = DeviceId("dev_test")
  }
}
