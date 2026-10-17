package com.mapineda48.phonotrainer.core.connectivity.discovery

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.support.LoopbackOnly
import com.mapineda48.phonotrainer.core.connectivity.support.RecordingResponder
import com.mapineda48.phonotrainer.core.connectivity.support.loopbackTransport
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The phone's UDP half of discovery against responders on the loopback interface (architecture 6.1, work-plan WP-07
 * Proof): real sockets, real time.
 */
class UdpDiscoveryTransportTest {
  private val nonce = "0123456789abcdef0123456789abcdef"
  private val probe = DiscoveryProtocol.probe(nonce)

  @Test
  fun theFakeComputersReplyArrivesFromItsAddressAndAnswersThisProbe() = runBlocking {
    FakeComputer(name = "Study desk").start().use { computer ->
      val port = computer.startDiscoveryResponder()
      val datagrams = loopbackTransport().probe(probe, listOf("127.0.0.1:$port"), listOf(Duration.ZERO), Duration.ofMillis(800)).toList()

      assertThat(datagrams).hasSize(1)
      assertThat(datagrams.single().sourceHost).isEqualTo("127.0.0.1")
      assertThat(DiscoveryProtocol.parseReply(datagrams.single().bytes, nonce))
        .isEqualTo(DiscoveryReply(computer.serverId, "Study desk", computer.address.port, 6))
    }
  }

  @Test
  fun theProbeLeavesAt0And1And3sToEveryTargetAndListeningEndsAt10s() = runBlocking {
    RecordingResponder().use { first ->
      RecordingResponder().use { second ->
        val started = System.nanoTime()
        val datagrams = loopbackTransport().probe(
          probe,
          listOf("127.0.0.1:${first.port}", "127.0.0.1:${second.port}"),
          DiscoveryProtocol.SCHEDULE,
          DiscoveryProtocol.LISTEN,
        ).toList()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(datagrams).isEmpty()
        assertThat(elapsedMs).isIn(com.google.common.collect.Range.closed(9_900L, 11_500L))
        listOf(first, second).forEach { responder ->
          val offsets = responder.arrivals.map { (at, _) -> (at - started) / 1_000_000 }
          assertThat(offsets).hasSize(3)
          assertThat(offsets[0]).isAtMost(500L)
          assertThat(offsets[1]).isIn(com.google.common.collect.Range.closed(900L, 1_500L))
          assertThat(offsets[2]).isIn(com.google.common.collect.Range.closed(2_900L, 3_500L))
          assertThat(responder.arrivals.map { it.second.toString(Charsets.UTF_8) }.distinct())
            .containsExactly(probe.toString(Charsets.UTF_8))
        }
      }
    }
  }

  @Test
  fun repliesFromSourcesThePolicyRefusesAreDropped() = runBlocking {
    FakeComputer().start().use { computer ->
      val port = computer.startDiscoveryResponder()
      val refuseAll = object : AddressPolicy {
        override fun allowsCandidate(host: String): Boolean = false
      }
      val datagrams = loopbackTransport(refuseAll)
        .probe(probe, listOf("127.0.0.1:$port"), listOf(Duration.ZERO), Duration.ofMillis(600)).toList()

      assertThat(datagrams).isEmpty()
    }
  }

  @Test
  fun aTargetThatIsNotAnAddressLiteralIsSkippedSoNothingIsEverResolved() = runBlocking {
    RecordingResponder().use { responder ->
      loopbackTransport().probe(
        probe,
        listOf("localhost:${responder.port}", "127.0.0.1:${responder.port}", "127.0.0.1:0", "127.0.0.256"),
        listOf(Duration.ZERO),
        Duration.ofMillis(500),
      ).toList()

      assertThat(responder.arrivals).hasSize(1)
    }
  }

  @Test
  fun withoutAWifiToBindTheSearchEndsAtOnceWithNoAnswer() = runBlocking {
    val transport = UdpDiscoveryTransport(
      binder = { throw IOException("no Wi-Fi") },
      policy = LoopbackOnly,
      io = Dispatchers.IO,
      log = PhonoLog.NONE,
    )
    val started = System.nanoTime()

    assertThat(transport.probe(probe, listOf("127.0.0.1:9"), DiscoveryProtocol.SCHEDULE, DiscoveryProtocol.LISTEN).toList()).isEmpty()
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000L)
  }
}
