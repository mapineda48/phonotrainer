package com.mapineda48.phonotrainer.core.network.http

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.support.RawPeer
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A `101 Switching Protocols` is no way around the caps (architecture 5.4, the WP-05b security review): OkHttp hands a
 * `101` to a plain request on with its body, and Retrofit buffers the whole body of a non-2xx answer, so any responder
 * (a candidate that needs no proof, or the verified address) could make the app read a body of any size. Only the job
 * channel's WebSocket upgrade may switch protocols; any other `101` is malformed and its body is never read.
 */
class SwitchingProtocolsTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val computer = FakeComputer(clock = FakeComputerClock.frozen())
  private val stack = TestStack(computer)

  @After
  fun close() {
    stack.close()
    computer.close()
  }

  @Test
  fun aCandidateThatAnswersHelloWithASwitchWithAHugeBodyIsMalformedAndNeverRead() = runBlocking<Unit> {
    oversizedSwitch().use { peer ->
      val hello = stack.peers.withCandidate(peer.address) { stack.client.hello(peer.address) }

      assertThat(hello).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
      assertThat(peer.header("Authorization")).isNull()
      assertCutShort(peer)
    }
  }

  @Test
  fun theVerifiedAddressAnsweringASignedRouteWithASwitchWithAHugeBodyIsMalformedAndNeverRead() = runBlocking<Unit> {
    oversizedSwitch().use { peer ->
      stack.trust(peer.address)

      val library = stack.client.library()

      assertThat(library).isEqualTo(ApiResult.Failed(AppError.Computer.Malformed))
      assertThat(peer.header("Authorization")).startsWith("PT1 ")
      assertCutShort(peer)
    }
  }

  @Test
  fun anArtifactAnsweredWithASwitchIsMalformedAndNothingIsWritten() = runBlocking<Unit> {
    oversizedSwitch().use { peer ->
      stack.trust(peer.address)
      val part = folder.newFile("analysis.part")
      val url = "/api/phone/jobs/$JOB_ID/artifacts/analysis"

      val outcome = stack.transfer.download(DownloadRequest(url, part, 0, null, ARTIFACT_BYTES)) {}

      assertThat(outcome).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Malformed))
      assertThat(part.length()).isEqualTo(0)
      assertCutShort(peer)
    }
  }

  /** A responder that answers any request with a `101` and a declared body of [BODY_BYTES], streamed until refused. */
  private fun oversizedSwitch() = RawPeer {
    writeText(
      "HTTP/1.1 101 Switching Protocols\r\nContent-Type: application/json\r\nContent-Length: $BODY_BYTES\r\n\r\n",
    )
    stream(BODY_BYTES)
  }

  private fun assertCutShort(peer: RawPeer) {
    assertThat(peer.awaitDone()).isTrue()
    assertThat(peer.closedByPhone).isTrue()
    assertThat(peer.written.get()).isLessThan(CUT_SHORT_BYTES)
  }

  private companion object {
    const val BODY_BYTES = 200L * 1024L * 1024L

    /** What the loopback's socket buffers may take in before the phone's close reaches the writer. */
    const val CUT_SHORT_BYTES = 32L * 1024L * 1024L
    const val ARTIFACT_BYTES = 1024L
    const val JOB_ID = "20261010-091503-4be1"
  }
}
