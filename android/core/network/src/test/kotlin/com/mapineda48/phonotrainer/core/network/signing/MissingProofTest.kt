package com.mapineda48.phonotrainer.core.network.signing

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.http.NetworkTimeouts
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClock
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.Test

/**
 * A challenged `hello` at the verified address that brings no valid proof, however it ends, leaves the address
 * unverified (architecture 5.3, the WP-05b Proof item "a wrong or missing proof leaves the peer unverified", known gap
 * f5 of round 1): whatever answers there now (another device that took the address, a rogue responder) has not proved
 * itself, so no signed request is sent there, not even one already on its way from another part of the app.
 */
class MissingProofTest {
  @Test
  fun aHelloRefusedWith404ForgetsTheVerifiedAddress() {
    FakeComputer().start().use { computer ->
      TestStack(computer).use { stack ->
        runBlocking {
          val device = DeviceId(stack.pair().deviceId)
          computer.failNext("/api/phone/hello", 404, "unknown_route")

          val hello = stack.client.hello(computer.address, device)

          assertThat((hello as ApiResult.Refused).status).isEqualTo(404)
          assertThat(stack.verified.isVerified(computer.address)).isFalse()
          assertThat(stack.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
          assertThat(computer.requests.map { it.path }).doesNotContain("/api/phone/library")
        }
      }
    }
  }

  @Test
  fun aHelloAnsweredWithSomethingThatIsNotJsonForgetsTheVerifiedAddress() = afterHelloAnswered(
    MockResponse.Builder().code(OK).body("<html>Welcome</html>").setHeader("Content-Type", "text/html").build(),
    expected = ApiResult.Failed(AppError.Computer.Malformed),
  )

  @Test
  fun aHelloWithNoAnswerForgetsTheVerifiedAddress() = afterHelloAnswered(
    MockResponse.Builder().code(OK).body("{}").onResponseStart(SocketEffect.Stall).build(),
    expected = ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(1))),
  )

  @Test
  fun aHelloWhoseConnectionIsResetForgetsTheVerifiedAddress() = afterHelloAnswered(
    MockResponse.Builder().code(OK).body("{}").onResponseStart(SocketEffect.CloseSocket()).build(),
    expected = ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset)),
  )

  @Test
  fun aHelloAnsweredWithAServerErrorForgetsTheVerifiedAddress() = afterHelloAnswered(
    MockResponse.Builder().code(SERVER_ERROR).body("""{"detail": "boom"}""")
      .setHeader("Content-Type", "application/json").build(),
    expected = null,
  )

  /**
   * The address is verified ([TestStack.trust]); a challenged `hello` gets [helloAnswer] and ends as [expected] (any
   * refusal when null); then a signed call fails as `Guarded` and never reaches the address.
   */
  private fun afterHelloAnswered(helloAnswer: MockResponse, expected: ApiResult<*>?) {
    val computer = FakeComputer(clock = FakeComputerClock.frozen())
    val paths = CopyOnWriteArrayList<String>()
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        paths += request.url.encodedPath
        return if (request.url.encodedPath == HELLO) helloAnswer else MockResponse.Builder().code(OK).body("{}").build()
      }
    }
    server.start(InetAddress.getByName(FakeComputer.HOST), 0)
    server.use {
      TestStack(computer, timeouts = NetworkTimeouts(probe = Duration.ofMillis(PROBE_MS))).use { stack ->
        runBlocking {
          val at = HostPort(FakeComputer.HOST, server.port)
          stack.trust(at)
          assertThat(stack.verified.isVerified(at)).isTrue()

          val hello = stack.client.hello(at, DEVICE)

          if (expected != null) {
            assertThat(hello).isEqualTo(expected)
          } else {
            assertThat(hello).isInstanceOf(ApiResult.Refused::class.java)
          }
          assertThat(stack.verified.isVerified(at)).isFalse()
          assertThat(stack.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
          assertThat(stack.client.reference()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
          assertThat(paths.toSet()).containsExactly(HELLO)
        }
      }
    }
    computer.close()
  }

  private companion object {
    const val OK = 200
    const val SERVER_ERROR = 500
    const val PROBE_MS = 400L
    const val HELLO = "/api/phone/hello"
    val DEVICE = DeviceId("dev_test")
  }
}
