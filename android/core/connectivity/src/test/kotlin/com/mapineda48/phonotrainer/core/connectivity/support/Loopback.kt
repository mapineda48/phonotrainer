package com.mapineda48.phonotrainer.core.connectivity.support

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.discovery.UdpDiscoveryTransport
import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** The test address policy: the loopback interface the fake computers listen on, and nothing else. */
internal object LoopbackOnly : AddressPolicy {
  override fun allowsCandidate(host: String): Boolean = host == LOOPBACK

  const val LOOPBACK = "127.0.0.1"
}

/** The real UDP transport, unbound (loopback needs no network binding), under [policy]. */
internal fun loopbackTransport(policy: AddressPolicy = LoopbackOnly): UdpDiscoveryTransport =
  UdpDiscoveryTransport(binder = { }, policy = policy, io = Dispatchers.IO, log = PhonoLog.NONE)

/**
 * Sends discovery to loopback responders instead of the broadcast addresses (the phone's broadcast never reaches a
 * test's loopback ports), on a shorter schedule, and records what the caller asked for: the real targets, schedule
 * and listening time are asserted from [calls].
 */
internal class LoopbackTransport(
  private val responderPorts: () -> List<Int>,
  private val schedule: List<Duration> = listOf(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(300)),
  private val listenFor: Duration = Duration.ofMillis(1_500),
  private val delegate: DiscoveryTransport = loopbackTransport(),
) : DiscoveryTransport {
  data class Call(val broadcastTargets: List<String>, val schedule: List<Duration>, val listenFor: Duration)

  val calls = CopyOnWriteArrayList<Call>()

  override fun probe(datagram: ByteArray, broadcastTargets: List<String>, schedule: List<Duration>, listenFor: Duration): Flow<Datagram> {
    calls += Call(broadcastTargets, schedule, listenFor)
    return delegate.probe(datagram, responderPorts().map { "${LoopbackOnly.LOOPBACK}:$it" }, this.schedule, this.listenFor)
  }
}

/** A UDP socket on loopback that records when each datagram arrives and answers with [answer] (null: silent). */
internal class RecordingResponder(private val answer: (ByteArray) -> ByteArray? = { null }) : Closeable {
  private val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(LoopbackOnly.LOOPBACK), 0))
  val arrivals = CopyOnWriteArrayList<Pair<Long, ByteArray>>()
  val port: Int get() = socket.localPort

  private val worker = thread(isDaemon = true, name = "test-discovery-responder") {
    val buffer = ByteArray(1024)
    while (!socket.isClosed) {
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        socket.receive(packet)
      } catch (_: SocketException) {
        break
      }
      val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
      arrivals += System.nanoTime() to bytes
      answer(bytes)?.let { reply ->
        try {
          socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
        } catch (_: SocketException) {
          break
        }
      }
    }
  }

  override fun close() {
    socket.close()
    worker.join(1_000)
  }
}
