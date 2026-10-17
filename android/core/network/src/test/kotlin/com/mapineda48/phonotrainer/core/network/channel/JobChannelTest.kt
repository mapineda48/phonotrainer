package com.mapineda48.phonotrainer.core.network.channel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.network.http.NetworkTimeouts
import com.mapineda48.phonotrainer.core.network.support.RawPeer
import com.mapineda48.phonotrainer.core.network.support.RecordingPlatform
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.zip.Deflater
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test

/**
 * The job channel (architecture 5.5, server-contract 7) over a real WebSocket: the fake computer's `hello`,
 * `snapshot`, `job` and `deleted` events, a channel the computer drops or closes (both fail as lost), a silent peer
 * that misses its pongs (short ping intervals stand for 7 s), a change of network, and frames this app does not know
 * or cannot trust.
 */
class JobChannelTest {
  private val computer = FakeComputer().start()

  @After
  fun close() {
    computer.close()
  }

  @Test
  fun theComputersEventsArriveInOrderAndLean() = runBlocking<Unit> {
    TestStack(computer).use { stack ->
      val first = computer.addFinishedClip("Ordering coffee")
      stack.pair()

      stack.channel.open().test(timeout = 5.seconds) {
        assertThat(awaitItem()).isEqualTo(ChannelEvent.Hello(computer.serverId, computer.libraryId, 6))
        val snapshot = awaitItem() as ChannelEvent.Snapshot
        assertThat(snapshot.jobs.map { it.id }).containsExactly(first)
        assertThat(snapshot.jobs.single().artifacts).isNull()

        val second = computer.addFinishedClip("At the station")
        val job = awaitItem() as ChannelEvent.Job
        assertThat(job.job.id).isEqualTo(second)
        assertThat(job.job.status).isEqualTo(RemoteStatus.DONE)
        assertThat(job.job.artifacts).isNull()

        computer.deleteJob(first)
        assertThat(awaitItem()).isEqualTo(ChannelEvent.Deleted(first))
        cancelAndIgnoreRemainingEvents()
      }
      val upgrade = computer.requests.single { it.path == "/api/phone/events" }
      assertThat(upgrade.signedBy).isNotNull()
      // No compression is offered, so each message is as long on the wire as in memory (architecture 5.4).
      assertThat(upgrade.headers.keys.map { it.lowercase() }).doesNotContain("sec-websocket-extensions")
    }
  }

  @Test
  fun aChannelTheComputerDropsFailsAsLost() = runBlocking<Unit> {
    TestStack(computer).use { stack ->
      stack.pair()

      stack.channel.open().test(timeout = 5.seconds) {
        assertThat(awaitItem()).isInstanceOf(ChannelEvent.Hello::class.java)
        assertThat(awaitItem()).isInstanceOf(ChannelEvent.Snapshot::class.java)
        computer.dropChannel()
        assertThat(awaitError()).isInstanceOf(ChannelLostException::class.java)
      }
    }
  }

  /**
   * The real computer closes an accepted channel with code 1008 when it revokes the device or phone access is turned
   * off (server-contract 7): a lost channel like any other, never a normal end (architecture 5.5).
   */
  @Test
  fun aChannelTheComputerClosesFailsAsLost() = runBlocking<Unit> {
    val server = MockWebServer().apply { start(InetAddress.getByName(FakeComputer.HOST), 0) }
    server.use {
      server.enqueue(
        MockResponse.Builder().webSocketUpgrade(
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              webSocket.send("""{"type": "deleted", "id": "$JOB_ID"}""")
              webSocket.close(POLICY_VIOLATION, "device revoked")
            }
          },
        ).build(),
      )
      TestStack(computer).use { stack ->
        stack.trust(HostPort(FakeComputer.HOST, server.port))

        stack.channel.open().test(timeout = 5.seconds) {
          assertThat(awaitItem()).isEqualTo(ChannelEvent.Deleted(JobId(JOB_ID)))
          val failure = awaitError()
          assertThat(failure).isInstanceOf(ChannelLostException::class.java)
          assertThat(failure.message).contains("1008")
        }
      }
    }
  }

  @Test
  fun aPeerThatMissesItsPongsFailsTheChannelWithinTwoIntervals() = runBlocking<Unit> {
    val ping = Duration.ofMillis(PING_MS)
    SilentWebSocketPeer().use { peer ->
      TestStack(computer, timeouts = NetworkTimeouts(channelPing = ping)).use { stack ->
        stack.trust(peer.address)
        val started = System.nanoTime()

        val failure = withTimeout(5_000) { runCatching { stack.channel.open().toList() }.exceptionOrNull() }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertThat(failure).isInstanceOf(ChannelLostException::class.java)
        // The ping's own failure is in the cause chain (coroutines may wrap the exception once more).
        val causes = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertThat(causes.any { "didn't receive pong" in it }).isTrue()
        assertThat(elapsedMs).isAtLeast(PING_MS)
        assertThat(elapsedMs).isLessThan(2 * PING_MS + SLACK_MS)
      }
    }
  }

  @Test
  fun aChangeOfNetworkClosesTheChannel() = runBlocking<Unit> {
    val platform = RecordingPlatform()
    TestStack(computer, platform = platform).use { stack ->
      stack.binder.bind(NETWORK_A)
      stack.pair()

      stack.channel.open().test(timeout = 5.seconds) {
        assertThat(awaitItem()).isInstanceOf(ChannelEvent.Hello::class.java)
        assertThat(awaitItem()).isInstanceOf(ChannelEvent.Snapshot::class.java)
        stack.binder.bind(NETWORK_B)
        assertThat(awaitError()).isInstanceOf(ChannelLostException::class.java)
      }
    }
  }

  @Test
  fun framesThisAppDoesNotKnowOrCannotTrustAreSkipped() = runBlocking<Unit> {
    val server = MockWebServer().apply { start(InetAddress.getByName(FakeComputer.HOST), 0) }
    server.use {
      server.enqueue(
        MockResponse.Builder().webSocketUpgrade(
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              webSocket.send("""{"type": "progress_v9", "anything": 1}""")
              webSocket.send("not json at all")
              webSocket.send("""{"type": "deleted", "id": "../../etc"}""")
              webSocket.send("""{"type": "job", "job": {"id": "$JOB_ID"}}""")
              webSocket.send("""{"type": "deleted", "id": "$JOB_ID", "extra": true}""")
              webSocket.close(1000, null)
            }
          },
        ).build(),
      )
      TestStack(computer).use { stack ->
        stack.trust(HostPort(FakeComputer.HOST, server.port))

        stack.channel.open().test(timeout = 5.seconds) {
          assertThat(awaitItem()).isEqualTo(ChannelEvent.Deleted(JobId(JOB_ID)))
          // The computer's close ends the channel as lost (server-contract 7); nothing else was emitted before it.
          assertThat(awaitError()).isInstanceOf(ChannelLostException::class.java)
        }
      }
    }
  }

  /**
   * OkHttp inflates a compressed message without a limit, so a frame of a few kilobytes could become a message of any
   * size: an acceptance that names an extension the phone never offered fails the channel before a frame is read.
   */
  @Test
  fun anAcceptanceThatNamesAnExtensionFailsTheChannelBeforeAFrameIsRead() = runBlocking<Unit> {
    val frame = deflatedFrame(deletedEvent(padding = DEFLATED_PADDING))
    RawPeer {
      acceptWebSocket("Sec-WebSocket-Extensions: permessage-deflate\r\n")
      write(frame)
      awaitPhoneClose()
    }.use { peer ->
      TestStack(computer).use { stack ->
        stack.trust(peer.address)

        stack.channel.open().test(timeout = 5.seconds) {
          val failure = awaitError()
          assertThat(failure).isInstanceOf(ChannelLostException::class.java)
          assertThat(causes(failure).any { "extension" in it }).isTrue()
        }
        assertThat(peer.awaitDone()).isTrue()
        assertThat(peer.closedByPhone).isTrue()
        assertThat(peer.header("Sec-WebSocket-Extensions")).isNull()
      }
    }
  }

  @Test
  fun aMessageOverTheBoundFailsTheChannelBeforeItIsRead() = runBlocking<Unit> {
    RawPeer {
      acceptWebSocket()
      write(RawPeer.frameHeader(RawPeer.FIN or RawPeer.TEXT, OVERSIZED_BYTES))
      stream(OVERSIZED_BYTES)
    }.use { peer ->
      TestStack(computer).use { stack ->
        stack.trust(peer.address)

        stack.channel.open().test(timeout = 5.seconds) {
          val failure = awaitError()
          assertThat(failure).isInstanceOf(ChannelLostException::class.java)
          assertThat(causes(failure).any { "larger than" in it }).isTrue()
        }
        assertThat(peer.awaitDone()).isTrue()
        assertThat(peer.closedByPhone).isTrue()
        assertThat(peer.written.get()).isLessThan(CUT_SHORT_BYTES)
      }
    }
  }

  @Test
  fun aMessageSplitIntoFramesIsBoundedAsAWhole() = runBlocking<Unit> {
    RawPeer {
      acceptWebSocket()
      write(RawPeer.frameHeader(RawPeer.TEXT, FRAGMENT_BYTES))
      stream(FRAGMENT_BYTES)
      write(RawPeer.frameHeader(RawPeer.FIN or RawPeer.CONTINUATION, FRAGMENT_BYTES))
      stream(FRAGMENT_BYTES)
    }.use { peer ->
      TestStack(computer).use { stack ->
        stack.trust(peer.address)

        stack.channel.open().test(timeout = 5.seconds) {
          val failure = awaitError()
          assertThat(causes(failure).any { "larger than" in it }).isTrue()
        }
        assertThat(peer.awaitDone()).isTrue()
      }
    }
  }

  @Test
  fun aComputerThatSendsMoreEventsThanTheCollectorTakesLosesTheChannel() = runBlocking<Unit> {
    val server = MockWebServer().apply { start(InetAddress.getByName(FakeComputer.HOST), 0) }
    server.use {
      server.enqueue(
        MockResponse.Builder().webSocketUpgrade(
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              repeat(WebSocketJobChannel.MAX_PENDING_EVENTS + FLOOD_EXTRA) { webSocket.send(deletedEvent()) }
            }
          },
        ).build(),
      )
      TestStack(computer).use { stack ->
        stack.trust(HostPort(FakeComputer.HOST, server.port))

        val (received, failure) = collectSlowly(stack)

        assertThat(failure).isInstanceOf(ChannelLostException::class.java)
        assertThat(failure!!.message).contains("faster")
        assertThat(received).isAtMost(WebSocketJobChannel.MAX_PENDING_EVENTS + 1)
      }
    }
  }

  @Test
  fun eventsWaitingForTheCollectorAreBoundedInSize() = runBlocking<Unit> {
    val frame = deletedEvent(padding = LARGE_PADDING).toByteArray(Charsets.UTF_8)
    RawPeer {
      acceptWebSocket()
      repeat(LARGE_EVENTS) { write(RawPeer.frameHeader(RawPeer.FIN or RawPeer.TEXT, frame.size.toLong()) + frame) }
      awaitPhoneClose()
    }.use { peer ->
      TestStack(computer).use { stack ->
        stack.trust(peer.address)

        val (received, failure) = collectSlowly(stack)

        assertThat(failure).isInstanceOf(ChannelLostException::class.java)
        assertThat(failure!!.message).contains("faster")
        assertThat(received).isLessThan(LARGE_EVENTS)
      }
    }
  }

  /** Collects the channel with a collector that stalls on its first event; the events it took and how it ended. */
  private suspend fun collectSlowly(stack: TestStack): Pair<Int, Throwable?> {
    var received = 0
    val failure = withTimeout(10_000) {
      runCatching {
        stack.channel.open().collect { if (++received == 1) delay(STALL_MS) }
      }.exceptionOrNull()
    }
    return received to failure
  }

  private fun causes(failure: Throwable): List<String> =
    generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()

  /** A valid `deleted` frame's text, with an unknown field of [padding] characters (ignored by the parser). */
  private fun deletedEvent(padding: Int = 0): String =
    """{"type": "deleted", "id": "$JOB_ID"""" + (if (padding > 0) """, "pad": "${"x".repeat(padding)}"""" else "") + "}"

  /** [text] as one `permessage-deflate` frame (RFC 7692): raw deflate, without the sync flush's 00 00 FF FF. */
  private fun deflatedFrame(text: String): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    deflater.setInput(text.toByteArray(Charsets.UTF_8))
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    do {
      val count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
      out.write(buffer, 0, count)
    } while (count == buffer.size || !deflater.needsInput())
    deflater.end()
    val payload = out.toByteArray().let { it.copyOf(it.size - SYNC_FLUSH_TAIL) }
    return RawPeer.frameHeader(RawPeer.FIN or RawPeer.RSV1 or RawPeer.TEXT, payload.size.toLong()) + payload
  }

  @Test
  fun anUnverifiedAddressNeverGetsAnUpgrade() = runBlocking<Unit> {
    TestStack(computer).use { stack ->
      val device = DeviceId(stack.pair().deviceId)
      stack.verified.reset()

      val failure = runCatching { withTimeout(5_000) { stack.channel.open().toList() } }.exceptionOrNull()

      assertThat(failure).isInstanceOf(ChannelLostException::class.java)
      assertThat(computer.requests.map { it.path }).doesNotContain("/api/phone/events")
      assertThat(device.value).isNotEmpty()
    }
  }

  /**
   * A peer that completes the WebSocket handshake and then never reads or writes again: the phone's pings get no
   * pong, as when the computer's Wi-Fi vanishes without a TCP reset.
   */
  private class SilentWebSocketPeer : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName(FakeComputer.HOST))
    val address = HostPort(FakeComputer.HOST, server.localPort)

    private val worker = thread(isDaemon = true, name = "silent-peer") {
      runCatching {
        val socket = server.accept()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        var key = ""
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) break
          if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
        }
        val accept = Base64.getEncoder().encodeToString(
          MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1)),
        )
        socket.getOutputStream().write(
          ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        socket.getOutputStream().flush()
        Thread.sleep(Long.MAX_VALUE)
      }
    }

    override fun close() {
      worker.interrupt()
      server.close()
    }
  }

  private companion object {
    const val PING_MS = 300L
    const val SLACK_MS = 700L
    const val NETWORK_A = 101L
    const val NETWORK_B = 102L
    const val POLICY_VIOLATION = 1008
    const val JOB_ID = "20261010-091503-4be1"
    const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    const val MIB = 1024 * 1024
    const val DEFLATED_PADDING = 8 * MIB
    const val OVERSIZED_BYTES = 64L * MIB
    const val FRAGMENT_BYTES = 10L * MIB
    const val CUT_SHORT_BYTES = 32L * MIB
    const val FLOOD_EXTRA = 64
    const val LARGE_PADDING = 9 * MIB
    const val LARGE_EVENTS = 3
    const val STALL_MS = 1_500L
    const val SYNC_FLUSH_TAIL = 4
  }
}
